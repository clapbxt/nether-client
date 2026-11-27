//! One launcher at a time: a later start (shortcut, a modpack opened with it) is handed to the running launcher and
//! exits. The hand-over is a WM_COPYDATA message to a message-only window, not a network port: window messages never
//! cross Windows sessions (other accounts on the PC can't reach it), and UIPI keeps lower-integrity (sandboxed)
//! processes out.
use std::path::{Path, PathBuf};
use std::sync::{mpsc, Arc, Mutex, OnceLock};
use std::time::Duration;

use slint::ComponentHandle;

use crate::{ui_do, AppWindow, Shared};

/// Every message starts with this.
const MAGIC: &str = "NETHER1 ";
/// `COPYDATASTRUCT.dwData` of our messages ("NCL1").
const TAG: usize = 0x4E43_4C31;
#[cfg(windows)]
const CLASS: &str = "NetherClient.Handover";

static TX: OnceLock<Mutex<mpsc::Sender<String>>> = OnceLock::new();

#[cfg(windows)]
fn wide(s: &str) -> Vec<u16> { s.encode_utf16().chain(std::iter::once(0)).collect() }

/// A file on this PC (absolute, not a `\\server\share` or `\\?\` path).
fn local_file(p: &Path) -> bool { p.is_absolute() && !p.as_os_str().to_string_lossy().starts_with(r"\\") }

/// Files this process was started with (a modpack or mod opened with the launcher).
pub fn file_args() -> Vec<PathBuf> {
    std::env::args().skip(1).map(PathBuf::from).filter(|p| local_file(p) && p.exists()).collect()
}

/// True for the first launcher process of this Windows session. Later ones hand over and exit
/// (two launchers would overwrite each other's instances.json and settings).
#[cfg(windows)]
pub fn first_instance() -> bool {
    use windows_sys::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS};
    use windows_sys::Win32::System::Threading::CreateMutexW;
    let name: Vec<u16> = "Local\\NetherClient.Launcher\0".encode_utf16().collect();
    // the handle lives as long as the process (released by Windows on exit)
    let h = unsafe { CreateMutexW(std::ptr::null(), 0, name.as_ptr()) };
    if h.is_null() { return false; }
    if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
        // let go of it, or the mutex outlives the other launcher and a retry (replace_other) never gets it
        unsafe { windows_sys::Win32::Foundation::CloseHandle(h) };
        return false;
    }
    true
}
#[cfg(not(windows))]
pub fn first_instance() -> bool { true }

/// The exe of the launcher already running in this session, when it is a different file from this one (an older
/// installed copy in the tray while a fresh build is started, or the other way round). None when it is this same exe
/// or can't be told.
#[cfg(windows)]
pub fn other_copy() -> Option<(PathBuf, u32)> {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::Threading::{OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION};
    use windows_sys::Win32::UI::WindowsAndMessaging::{FindWindowExW, GetWindowThreadProcessId, HWND_MESSAGE};
    let class = wide(CLASS);
    // the running copy may still be starting: its hand-over window shows up shortly after the mutex
    let mut hwnd = std::ptr::null_mut();
    for _ in 0..12 {
        hwnd = unsafe { FindWindowExW(HWND_MESSAGE, std::ptr::null_mut(), class.as_ptr(), std::ptr::null()) };
        if !hwnd.is_null() { break; }
        std::thread::sleep(Duration::from_millis(250));
    }
    if hwnd.is_null() { return None; }
    let mut pid = 0u32;
    unsafe { GetWindowThreadProcessId(hwnd, &mut pid) };
    if pid == 0 || pid == std::process::id() { return None; }
    let h = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
    if h.is_null() { return None; }
    let mut buf = vec![0u16; 1024];
    let mut len = buf.len() as u32;
    let ok = unsafe { QueryFullProcessImageNameW(h, 0, buf.as_mut_ptr(), &mut len) };
    unsafe { CloseHandle(h) };
    if ok == 0 { return None; }
    let theirs = PathBuf::from(String::from_utf16_lossy(&buf[..len as usize]));
    let mine = std::env::current_exe().ok()?;
    let same = |a: &Path, b: &Path| match (a.canonicalize(), b.canonicalize()) {
        (Ok(a), Ok(b)) => a == b,
        _ => a.as_os_str().eq_ignore_ascii_case(b.as_os_str()),
    };
    if same(&theirs, &mine) { None } else { Some((theirs, pid)) }
}
#[cfg(not(windows))]
pub fn other_copy() -> Option<(PathBuf, u32)> { None }

/// Another launcher exe is running (usually the installed one, hidden in the tray, while a new build is started).
/// Handing over would show that old window, so ask whether to close it and start this one instead. True when it was
/// closed and this start may go on.
#[cfg(windows)]
pub fn replace_other(theirs: &Path, pid: u32) -> bool {
    use windows_sys::Win32::Foundation::{CloseHandle, WAIT_OBJECT_0};
    use windows_sys::Win32::System::Threading::{OpenProcess, TerminateProcess, WaitForSingleObject, PROCESS_SYNCHRONIZE, PROCESS_TERMINATE};
    use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, IDYES, MB_ICONQUESTION, MB_YESNO};
    let mine = std::env::current_exe().map(|p| p.display().to_string()).unwrap_or_default();
    let text = wide(&format!(
        "Another Nether Client is already running (maybe in the tray):\n{}\n\nClose it and start this one instead?\n{}\n\nNo brings the running one to the front.",
        theirs.display(), mine));
    let title = wide("Nether Client");
    if unsafe { MessageBoxW(std::ptr::null_mut(), text.as_ptr(), title.as_ptr(), MB_YESNO | MB_ICONQUESTION) } != IDYES { return false; }
    let h = unsafe { OpenProcess(PROCESS_TERMINATE | PROCESS_SYNCHRONIZE, 0, pid) };
    if h.is_null() { return false; }
    let gone = unsafe { TerminateProcess(h, 0) != 0 && WaitForSingleObject(h, 5000) == WAIT_OBJECT_0 };
    unsafe { CloseHandle(h) };
    // its mutex is released with it; take it now
    gone && first_instance()
}
#[cfg(not(windows))]
pub fn replace_other(_: &Path, _: u32) -> bool { false }

/// Hand this start over to the running launcher: files to open, or just "come to the front".
/// Retries for a few seconds (it may still be starting). True when it took it.
pub fn hand_over(files: &[PathBuf]) -> bool {
    #[cfg(windows)]
    unsafe { windows_sys::Win32::UI::WindowsAndMessaging::AllowSetForegroundWindow(windows_sys::Win32::UI::WindowsAndMessaging::ASFW_ANY); }
    let mut msgs: Vec<String> = files.iter().map(|f| format!("{MAGIC}OPEN {}", f.display())).collect();
    msgs.push(format!("{MAGIC}SHOW"));
    let deadline = std::time::Instant::now() + Duration::from_secs(6);
    let mut i = 0;
    while i < msgs.len() {
        if send(&msgs[i]) { i += 1; continue; }
        if std::time::Instant::now() > deadline { return false; }
        std::thread::sleep(Duration::from_millis(250));
    }
    true
}

#[cfg(windows)]
fn send(msg: &str) -> bool {
    use windows_sys::Win32::System::DataExchange::COPYDATASTRUCT;
    use windows_sys::Win32::UI::WindowsAndMessaging::{FindWindowExW, SendMessageTimeoutW, HWND_MESSAGE, SMTO_ABORTIFHUNG, WM_COPYDATA};
    let class = wide(CLASS);
    let hwnd = unsafe { FindWindowExW(HWND_MESSAGE, std::ptr::null_mut(), class.as_ptr(), std::ptr::null()) };
    if hwnd.is_null() { return false; }
    let bytes = msg.as_bytes();
    let cds = COPYDATASTRUCT { dwData: TAG, cbData: bytes.len() as u32, lpData: bytes.as_ptr() as *mut _ };
    let mut result = 0usize;
    let ok = unsafe { SendMessageTimeoutW(hwnd, WM_COPYDATA, 0, &cds as *const COPYDATASTRUCT as isize, SMTO_ABORTIFHUNG, 3000, &mut result) };
    ok != 0 && result == 1
}
#[cfg(not(windows))]
fn send(_: &str) -> bool { false }

/// Tell the user (no window exists yet) that the running launcher didn't answer.
#[cfg(windows)]
pub fn busy_notice() {
    use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONINFORMATION, MB_OK};
    let w = |s: &str| s.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
    let (text, title) = (w("Nether Client is already running, but it isn't answering yet. Give it a moment, or close it from the tray and start it again."), w("Nether Client"));
    unsafe { MessageBoxW(std::ptr::null_mut(), text.as_ptr(), title.as_ptr(), MB_OK | MB_ICONINFORMATION); }
}
#[cfg(not(windows))]
pub fn busy_notice() {}

/// Older versions registered a `nether://` link scheme; remove it if it still points at this exe (worker thread).
pub fn unregister_old() {
    let Ok(exe) = std::env::current_exe() else { return };
    std::thread::spawn(move || {
        const KEY: &str = r"HKCU\Software\Classes\nether";
        let reg = |args: &[&str]| {
            let mut c = std::process::Command::new("reg");
            c.args(args);
            #[cfg(windows)]
            { use std::os::windows::process::CommandExt; c.creation_flags(0x0800_0000); }
            c.output().ok()
        };
        let cmd = format!(r"{KEY}\shell\open\command");
        let ours = reg(&["query", &cmd, "/ve"]).is_some_and(|o| String::from_utf8_lossy(&o.stdout).to_lowercase().contains(&exe.display().to_string().to_lowercase()));
        if ours { reg(&["delete", KEY, "/f"]); }
    });
}

/// Accept hand-overs from later launches for the life of the launcher.
pub fn listen(ui: &AppWindow, sh: Arc<Shared>) {
    let (tx, rx) = mpsc::channel::<String>();
    if TX.set(Mutex::new(tx)).is_err() { return; }
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        for msg in rx {
            let sh = sh.clone();
            ui_do(&weak, move |ui| handle(ui, &sh, &msg));
        }
    });
    #[cfg(windows)]
    std::thread::spawn(|| unsafe { window_loop() });
}

/// The message-only window that receives hand-overs, with its own message loop.
#[cfg(windows)]
unsafe fn window_loop() {
    use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows_sys::Win32::UI::WindowsAndMessaging::{CreateWindowExW, DispatchMessageW, GetMessageW, RegisterClassW, TranslateMessage, HWND_MESSAGE, MSG, WNDCLASSW};
    let class = wide(CLASS);
    let hinst = GetModuleHandleW(std::ptr::null());
    let mut wc: WNDCLASSW = std::mem::zeroed();
    wc.lpfnWndProc = Some(wndproc);
    wc.hInstance = hinst;
    wc.lpszClassName = class.as_ptr();
    if RegisterClassW(&wc) == 0 { return; }
    let hwnd = CreateWindowExW(0, class.as_ptr(), std::ptr::null(), 0, 0, 0, 0, 0, HWND_MESSAGE, std::ptr::null_mut(), hinst, std::ptr::null());
    if hwnd.is_null() { return; }
    let mut m: MSG = std::mem::zeroed();
    while GetMessageW(&mut m, std::ptr::null_mut(), 0, 0) > 0 {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
}

#[cfg(windows)]
unsafe extern "system" fn wndproc(hwnd: windows_sys::Win32::Foundation::HWND, msg: u32, wp: usize, lp: isize) -> isize {
    use windows_sys::Win32::System::DataExchange::COPYDATASTRUCT;
    use windows_sys::Win32::UI::WindowsAndMessaging::{DefWindowProcW, WM_COPYDATA};
    if msg != WM_COPYDATA { return DefWindowProcW(hwnd, msg, wp, lp); }
    if lp == 0 { return 0; }
    let cds = &*(lp as *const COPYDATASTRUCT);
    if cds.dwData != TAG || cds.cbData as usize > 4096 || cds.lpData.is_null() { return 0; }
    let bytes = std::slice::from_raw_parts(cds.lpData as *const u8, cds.cbData as usize);
    let Some(m) = std::str::from_utf8(bytes).ok().and_then(|s| s.strip_prefix(MAGIC)) else { return 0 };
    match TX.get().and_then(|t| t.lock().ok()).map(|t| t.send(m.to_string())) {
        Some(Ok(())) => 1,
        _ => 0,
    }
}

/// A message from a later launch: SHOW, OPEN <file>.
fn handle(ui: &AppWindow, sh: &Arc<Shared>, msg: &str) {
    let (cmd, arg) = msg.split_once(' ').unwrap_or((msg, ""));
    match cmd {
        "SHOW" => crate::prefs::show(ui),
        "OPEN" if local_file(Path::new(arg)) => { crate::prefs::show(ui); crate::drop::dropped(ui, sh, vec![PathBuf::from(arg)]); }
        _ => {}
    }
}
