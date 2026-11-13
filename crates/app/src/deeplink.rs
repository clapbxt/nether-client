//! One launcher at a time: a later start (shortcut, a modpack opened with it) is handed to the running launcher over
//! loopback and exits.
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::time::Duration;

use slint::ComponentHandle;

use crate::{ui_do, AppWindow, Shared};

const PORT: u16 = 48651;
const HELLO: &[u8; 12] = b"NETHER-LINK\n";
/// Every message starts with this. A web page can't make a browser send it (requests start with a method line),
/// so pages poking 127.0.0.1 never reach the UI.
const MAGIC: &str = "NETHER1 ";

/// Files this process was started with (a modpack or mod opened with the launcher).
pub fn file_args() -> Vec<std::path::PathBuf> {
    std::env::args().skip(1).map(std::path::PathBuf::from).filter(|p| p.exists() && p.is_absolute()).collect()
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
    !(h.is_null() || unsafe { GetLastError() } == ERROR_ALREADY_EXISTS)
}
#[cfg(not(windows))]
pub fn first_instance() -> bool { true }

/// Hand this start over to the running launcher: files to open, or just "come to the front".
/// Retries for a few seconds (it may still be starting). True when it took it.
pub fn hand_over(files: &[std::path::PathBuf]) -> bool {
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

fn send(msg: &str) -> bool {
    let Ok(mut s) = TcpStream::connect_timeout(&SocketAddr::from((Ipv4Addr::LOCALHOST, PORT)), Duration::from_millis(400)) else { return false };
    let _ = s.set_read_timeout(Some(Duration::from_secs(1)));
    let mut hello = [0u8; 12];
    s.read_exact(&mut hello).is_ok() && &hello == HELLO && s.write_all(msg.as_bytes()).is_ok()
}

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

/// Accept links from later launches for the life of the launcher.
pub fn listen(ui: &AppWindow, sh: Arc<Shared>) {
    let Ok(l) = TcpListener::bind((Ipv4Addr::LOCALHOST, PORT)) else { return };
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        for mut s in l.incoming().flatten() {
            // one thread per connection: a slow or silent peer can't hold up the next launch
            let (weak, sh) = (weak.clone(), sh.clone());
            std::thread::spawn(move || {
                if s.write_all(HELLO).is_err() { return; }
                let _ = s.set_read_timeout(Some(Duration::from_secs(2)));
                let mut buf = String::new();
                let _ = (&mut s).take(4096).read_to_string(&mut buf);
                let Some(msg) = buf.trim().strip_prefix(MAGIC).map(str::to_string) else { return };
                ui_do(&weak, move |ui| handle(ui, &sh, &msg));
            });
        }
    });
}

/// A message from a later launch: SHOW, OPEN <file>.
fn handle(ui: &AppWindow, sh: &Arc<Shared>, msg: &str) {
    let (cmd, arg) = msg.split_once(' ').unwrap_or((msg, ""));
    match cmd {
        "SHOW" => crate::prefs::show(ui),
        "OPEN" if std::path::Path::new(arg).is_absolute() => { crate::prefs::show(ui); crate::drop::dropped(ui, sh, vec![std::path::PathBuf::from(arg)]); }
        _ => {}
    }
}
