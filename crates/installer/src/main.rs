//! Nether Client setup (per user, no admin): install wizard, uninstall (`--uninstall`), and the auto-update applier
//! (`--update --pid <launcher pid> [--relaunch]`, started by the launcher after it downloaded and verified a release).
//! Installs to %LOCALAPPDATA%\Programs\Nether Client; registers in Apps & features (HKCU); data in %APPDATA%\NetherClient
//! is never touched unless the user ticks "Also delete my data" when uninstalling.
#![windows_subsystem = "windows"]

slint::include_modules!();

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{anyhow, bail, Context, Result};
use slint::ComponentHandle;

const VERSION: &str = env!("NETHER_VERSION");
const PAYLOAD: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/payload.bin"));
const PAYLOAD_SIZE: &str = env!("NETHER_PAYLOAD_SIZE");
const APP_EXE: &str = "NetherClient.exe";
const UNINSTALLER: &str = "Uninstall Nether Client.exe";
const LINK: &str = "Nether Client.lnk";
const UNINSTALL_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\NetherClient";
const RUN_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
const PROTOCOL_KEY: &str = r"HKCU\Software\Classes\nether";
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Default)]
struct Args { uninstall: bool, update: bool, pid: Option<u32>, relaunch: bool, temp_copy: bool }

fn args() -> Args {
    let v: Vec<String> = std::env::args().skip(1).collect();
    let mut a = Args::default();
    for (i, s) in v.iter().enumerate() {
        match s.as_str() {
            "--uninstall" => a.uninstall = true,
            "--update" => a.update = true,
            "--relaunch" => a.relaunch = true,
            "--temp-copy" => a.temp_copy = true,
            "--pid" => a.pid = v.get(i + 1).and_then(|p| p.parse().ok()),
            _ => {}
        }
    }
    a
}

// ---------------- registry (reg.exe, like the launcher) ----------------

fn reg(args: &[&str]) -> Result<String> {
    use std::os::windows::process::CommandExt;
    let out = Command::new("reg").args(args).creation_flags(CREATE_NO_WINDOW).output()?;
    if !out.status.success() { bail!("{}", String::from_utf8_lossy(&out.stderr).trim()); }
    Ok(String::from_utf8_lossy(&out.stdout).to_string())
}
fn reg_get(key: &str, name: &str) -> Option<String> {
    let out = reg(&["query", key, "/v", name]).ok()?;
    out.lines().find(|l| l.trim_start().starts_with(name)).and_then(|l| l.split_once("REG_SZ").map(|(_, v)| v.trim().to_string())).filter(|v| !v.is_empty())
}
fn reg_set(key: &str, name: &str, value: &str) -> Result<()> { reg(&["add", key, "/v", name, "/t", "REG_SZ", "/d", value, "/f"]).map(|_| ()) }
fn reg_dword(key: &str, name: &str, value: u32) -> Result<()> { reg(&["add", key, "/v", name, "/t", "REG_DWORD", "/d", &value.to_string(), "/f"]).map(|_| ()) }

fn installed_dir() -> Option<PathBuf> { reg_get(UNINSTALL_KEY, "InstallLocation").map(PathBuf::from).filter(|d| d.join(APP_EXE).exists()) }
fn installed_version() -> Option<String> { installed_dir().and(reg_get(UNINSTALL_KEY, "DisplayVersion")) }
/// `a` is a higher version than `b` ("0.10.0" > "0.9.3"; numeric parts only).
fn newer(a: &str, b: &str) -> bool {
    let parts = |v: &str| v.trim().trim_start_matches(['v', 'V']).split(['-', '+', ' ']).next().unwrap_or("").split('.').map(|p| p.parse::<u32>().unwrap_or(0)).collect::<Vec<_>>();
    let (a, b) = (parts(a), parts(b));
    (0..a.len().max(b.len())).map(|i| a.get(i).unwrap_or(&0).cmp(b.get(i).unwrap_or(&0))).find(|o| o.is_ne()) == Some(std::cmp::Ordering::Greater)
}
fn default_dir() -> PathBuf { PathBuf::from(std::env::var_os("LOCALAPPDATA").unwrap_or_default()).join("Programs").join("Nether Client") }
fn data_dir() -> PathBuf { PathBuf::from(std::env::var_os("APPDATA").unwrap_or_default()).join("NetherClient") }

// ---------------- Windows helpers ----------------

/// The launcher holds this mutex while it runs (single-instance guard).
fn launcher_running() -> bool {
    use windows::core::w;
    use windows::Win32::Foundation::CloseHandle;
    use windows::Win32::System::Threading::{OpenMutexW, SYNCHRONIZATION_SYNCHRONIZE};
    unsafe { OpenMutexW(SYNCHRONIZATION_SYNCHRONIZE, false, w!("Local\\NetherClient.Launcher")).map(|h| { let _ = CloseHandle(h); }).is_ok() }
}

fn wait_for_exit(pid: u32, secs: u32) {
    use windows::Win32::Foundation::CloseHandle;
    use windows::Win32::System::Threading::{OpenProcess, WaitForSingleObject, PROCESS_SYNCHRONIZE};
    unsafe {
        if let Ok(h) = OpenProcess(PROCESS_SYNCHRONIZE, false, pid) {
            let _ = WaitForSingleObject(h, secs * 1000);
            let _ = CloseHandle(h);
        }
    }
}

fn known_folder(id: &windows::core::GUID) -> Option<PathBuf> {
    use windows::Win32::UI::Shell::{SHGetKnownFolderPath, KNOWN_FOLDER_FLAG};
    unsafe {
        let p = SHGetKnownFolderPath(id, KNOWN_FOLDER_FLAG(0), None).ok()?;
        let s = p.to_string().ok();
        windows::Win32::System::Com::CoTaskMemFree(Some(p.0 as _));
        s.map(PathBuf::from)
    }
}
fn desktop_dir() -> Option<PathBuf> { known_folder(&windows::Win32::UI::Shell::FOLDERID_Desktop) }
fn programs_dir() -> Option<PathBuf> { known_folder(&windows::Win32::UI::Shell::FOLDERID_Programs) }

fn shortcut(lnk: &Path, target: &Path) -> Result<()> {
    use windows::core::{Interface, HSTRING};
    use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, IPersistFile, CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED};
    use windows::Win32::UI::Shell::{IShellLinkW, ShellLink};
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
        let link: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)?;
        link.SetPath(&HSTRING::from(target.as_os_str()))?;
        if let Some(dir) = target.parent() { link.SetWorkingDirectory(&HSTRING::from(dir.as_os_str()))?; }
        link.SetDescription(&HSTRING::from("Nether Client"))?;
        link.SetIconLocation(&HSTRING::from(target.as_os_str()), 0)?;
        let file: IPersistFile = link.cast()?;
        file.Save(&HSTRING::from(lnk.as_os_str()), true)?;
    }
    Ok(())
}

fn hwnd(w: &slint::Window) -> Option<windows::Win32::Foundation::HWND> {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    match w.window_handle().window_handle().ok()?.as_raw() {
        RawWindowHandle::Win32(h) => Some(windows::Win32::Foundation::HWND(h.hwnd.get() as _)),
        _ => None,
    }
}

// ---------------- install / uninstall ----------------

struct Opts { dir: PathBuf, desktop: bool, startmenu: bool, autostart: bool, fresh: bool }

fn install(o: &Opts, progress: &dyn Fn(f32, &str)) -> Result<()> {
    if PAYLOAD.is_empty() { bail!("This setup was built without Nether Client inside. Download the setup from the release page."); }
    progress(0.02, "Preparing the folder");
    std::fs::create_dir_all(&o.dir).with_context(|| format!("couldn't create {}", o.dir.display()))?;
    let exe = o.dir.join(APP_EXE);
    let (tmp, old) = (o.dir.join("NetherClient.exe.new"), o.dir.join("NetherClient.exe.old"));
    let total: f32 = PAYLOAD_SIZE.parse::<f32>().unwrap_or(1.0).max(1.0);
    {
        let mut z = flate2::read::DeflateDecoder::new(PAYLOAD);
        let mut f = std::fs::File::create(&tmp).with_context(|| format!("couldn't write to {}", o.dir.display()))?;
        let mut buf = vec![0u8; 512 * 1024];
        let mut done = 0usize;
        loop {
            let n = z.read(&mut buf)?;
            if n == 0 { break; }
            f.write_all(&buf[..n])?;
            done += n;
            progress(0.05 + 0.80 * (done as f32 / total), "Copying Nether Client");
        }
        f.sync_all()?;
    }
    // a running exe can't be overwritten but can be moved aside
    let _ = std::fs::remove_file(&old);
    if exe.exists() { std::fs::rename(&exe, &old).context("Nether Client is still open. Close it and try again")?; }
    std::fs::rename(&tmp, &exe)?;
    let _ = std::fs::remove_file(&old);

    progress(0.88, "Registering with Windows");
    let me = std::env::current_exe()?;
    let uninstaller = o.dir.join(UNINSTALLER);
    if me != uninstaller { let _ = std::fs::copy(&me, &uninstaller); }
    let size_kb = std::fs::metadata(&exe).map(|m| (m.len() / 1024) as u32).unwrap_or(0);
    reg_set(UNINSTALL_KEY, "DisplayName", "Nether Client")?;
    reg_set(UNINSTALL_KEY, "DisplayVersion", VERSION)?;
    reg_set(UNINSTALL_KEY, "Publisher", "Nether Client")?;
    reg_set(UNINSTALL_KEY, "DisplayIcon", &format!("{},0", exe.display()))?;
    reg_set(UNINSTALL_KEY, "InstallLocation", &o.dir.display().to_string())?;
    reg_set(UNINSTALL_KEY, "UninstallString", &format!("\"{}\" --uninstall", uninstaller.display()))?;
    reg_dword(UNINSTALL_KEY, "EstimatedSize", size_kb)?;
    reg_dword(UNINSTALL_KEY, "NoModify", 1)?;
    reg_dword(UNINSTALL_KEY, "NoRepair", 1)?;

    if o.fresh {
        progress(0.94, "Adding shortcuts");
        if o.startmenu { if let Some(p) = programs_dir() { shortcut(&p.join(LINK), &exe)?; } }
        if o.desktop { if let Some(d) = desktop_dir() { shortcut(&d.join(LINK), &exe)?; } }
        if o.autostart { reg_set(RUN_KEY, "NetherClient", &format!("\"{}\"", exe.display()))?; }
    }
    progress(1.0, "Done");
    Ok(())
}

fn uninstall(dir: &Path, wipe: bool, progress: &dyn Fn(f32, &str)) -> Result<()> {
    if launcher_running() { bail!("Nether Client is still open. Close it (and its tray icon), then try again."); }
    progress(0.1, "Removing shortcuts");
    for d in [desktop_dir(), programs_dir()].into_iter().flatten() { let _ = std::fs::remove_file(d.join(LINK)); }
    let exe = dir.join(APP_EXE).display().to_string().to_lowercase();
    if reg_get(RUN_KEY, "NetherClient").is_some_and(|v| v.to_lowercase().contains(&exe)) { let _ = reg(&["delete", RUN_KEY, "/v", "NetherClient", "/f"]); }
    // the nether:// link handler, only when it still points at this install
    if reg(&["query", &format!(r"{PROTOCOL_KEY}\shell\open\command"), "/ve"]).is_ok_and(|v| v.to_lowercase().contains(&exe)) {
        let _ = reg(&["delete", PROTOCOL_KEY, "/f"]);
    }
    progress(0.35, "Removing Nether Client");
    for f in [APP_EXE, "NetherClient.exe.old", "NetherClient.exe.new", UNINSTALLER] {
        let p = dir.join(f);
        if p.exists() { std::fs::remove_file(&p).with_context(|| format!("couldn't remove {}", p.display()))?; }
    }
    let _ = std::fs::remove_dir(dir); // only if empty: never delete files we didn't put there
    let _ = reg(&["delete", UNINSTALL_KEY, "/f"]);
    if wipe {
        progress(0.6, "Deleting your data");
        let d = data_dir();
        if d.ends_with("NetherClient") && d.exists() { std::fs::remove_dir_all(&d).with_context(|| format!("couldn't delete {}", d.display()))?; }
    }
    progress(1.0, "Done");
    Ok(())
}

// ---------------- UI ----------------

fn run_job(ui: &SetupWindow, job: impl FnOnce(&dyn Fn(f32, &str)) -> Result<()> + Send + 'static, after: impl FnOnce(&SetupWindow, bool) + Send + 'static) {
    let s = ui.global::<Setup>();
    s.set_step(2);
    s.set_progress(0.0);
    s.set_error("".into());
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let w2 = weak.clone();
        let report = move |f: f32, msg: &str| {
            let msg = msg.to_string();
            let _ = w2.upgrade_in_event_loop(move |ui| { let s = ui.global::<Setup>(); s.set_progress(f); s.set_status(msg.into()); });
        };
        let r = job(&report);
        let _ = slint::invoke_from_event_loop(move || {
            let Some(ui) = weak.upgrade() else { return };
            let ok = r.is_ok();
            if let Err(e) = r { let s = ui.global::<Setup>(); s.set_error(format!("{e:#}").into()); s.set_step(4); } else { ui.global::<Setup>().set_step(3); }
            after(&ui, ok);
        });
    });
}

fn start_install(ui: &SetupWindow) {
    let s = ui.global::<Setup>();
    if launcher_running() {
        s.set_error("Nether Client is open. Close it (right-click its tray icon > Quit if it's hidden), then press TRY AGAIN.".into());
        s.set_step(4);
        return;
    }
    let o = Opts { dir: PathBuf::from(s.get_location().as_str()), desktop: s.get_desktop(), startmenu: s.get_startmenu(), autostart: s.get_autostart(), fresh: s.get_installed_version().is_empty() };
    run_job(ui, move |p| install(&o, p), |_, _| {});
}

fn main() -> Result<()> {
    let a = args();
    // the uninstaller can't delete itself: run a temp copy, which removes itself after closing
    if a.uninstall && !a.temp_copy {
        let me = std::env::current_exe()?;
        let tmp = std::env::temp_dir().join(format!("NetherClientUninstall-{}.exe", std::process::id()));
        std::fs::copy(&me, &tmp)?;
        Command::new(&tmp).args(["--uninstall", "--temp-copy"]).spawn()?;
        return Ok(());
    }

    let ui = SetupWindow::new().map_err(|e| anyhow!("{e}"))?;
    let s = ui.global::<Setup>();
    s.set_version(VERSION.into());
    s.set_data_dir(data_dir().display().to_string().into());
    let existing = installed_dir();
    s.set_installed_version(installed_version().unwrap_or_default().into());
    s.set_location(existing.clone().unwrap_or_else(default_dir).display().to_string().into());
    s.set_mode(if a.uninstall { 1 } else if a.update { 2 } else { 0 });

    {
        let w = ui.as_weak();
        s.on_drag(move || {
            use windows::Win32::Foundation::{LPARAM, WPARAM};
            use windows::Win32::UI::Input::KeyboardAndMouse::ReleaseCapture;
            use windows::Win32::UI::WindowsAndMessaging::{SendMessageW, HTCAPTION, WM_NCLBUTTONDOWN};
            if let Some(h) = w.upgrade().and_then(|ui| hwnd(ui.window())) {
                unsafe { let _ = ReleaseCapture(); SendMessageW(h, WM_NCLBUTTONDOWN, Some(WPARAM(HTCAPTION as usize)), Some(LPARAM(0))); }
            }
        });
        let w = ui.as_weak();
        s.on_minimize(move || if let Some(ui) = w.upgrade() { ui.window().set_minimized(true) });
        s.on_close(|| { let _ = slint::quit_event_loop(); });
        let w = ui.as_weak();
        s.on_browse(move || {
            let Some(ui) = w.upgrade() else { return };
            if let Some(d) = rfd::FileDialog::new().set_title("Install Nether Client to").pick_folder() {
                // always our own subfolder, so uninstalling never touches anything else in the chosen folder
                let d = if d.ends_with("Nether Client") { d } else { d.join("Nether Client") };
                ui.global::<Setup>().set_location(d.display().to_string().into());
            }
        });
        let w = ui.as_weak();
        s.on_finish(move || {
            if let Some(ui) = w.upgrade() {
                let s = ui.global::<Setup>();
                if s.get_mode() == 0 && s.get_launch() && s.get_step() == 3 {
                    let _ = Command::new(PathBuf::from(s.get_location().as_str()).join(APP_EXE)).spawn();
                }
            }
            let _ = slint::quit_event_loop();
        });
        let (w, dir) = (ui.as_weak(), existing.clone());
        s.on_start(move || {
            let Some(ui) = w.upgrade() else { return };
            let s = ui.global::<Setup>();
            if s.get_mode() == 1 {
                let Some(dir) = dir.clone() else { s.set_error("Nether Client isn't installed for this Windows account.".into()); s.set_step(4); return };
                let wipe = s.get_wipe_data();
                run_job(&ui, move |p| uninstall(&dir, wipe, p), |_, _| {});
            } else {
                start_install(&ui);
            }
        });
        let w = ui.as_weak();
        s.on_retry(move || {
            let Some(ui) = w.upgrade() else { return };
            let s = ui.global::<Setup>();
            s.set_error("".into());
            s.set_step(if s.get_mode() == 1 { 0 } else { 1 });
        });
    }

    if a.update {
        // auto-update: wait for the launcher to close, install over it, start it again if it asked to
        let Some(dir) = existing.clone() else {
            s.set_error("Nether Client isn't installed for this Windows account, so there is nothing to update.".into());
            ui.run().map_err(|e| anyhow!("{e}"))?;
            return Ok(());
        };
        s.set_status("Waiting for Nether Client to close".into());
        let (pid, relaunch) = (a.pid, a.relaunch);
        let o = Opts { dir: dir.clone(), desktop: false, startmenu: false, autostart: false, fresh: false };
        run_job(&ui, move |p| {
            // an update only ever moves forward (an old signed setup must not downgrade the launcher)
            if let Some(cur) = installed_version().filter(|cur| !newer(VERSION, cur)) { bail!("This update ({VERSION}) is not newer than the installed Nether Client {cur}."); }
            if let Some(pid) = pid { wait_for_exit(pid, 60); }
            for _ in 0..50 { if !launcher_running() { break; } std::thread::sleep(std::time::Duration::from_millis(200)); }
            install(&o, p)
        }, move |ui, ok| {
            if ok {
                if relaunch { let _ = Command::new(dir.join(APP_EXE)).spawn(); }
                let _ = ui.hide();
                let _ = slint::quit_event_loop();
            }
        });
    }

    // dev: NC_SETUP_AUTO=install:<dir> (no shortcuts) or uninstall, then quit; result in NC_SETUP_AUTO_OUT
    #[cfg(feature = "dev")]
    if let Ok(auto) = std::env::var("NC_SETUP_AUTO") {
        let s = ui.global::<Setup>();
        if let Some(dir) = auto.strip_prefix("install:") { s.set_location(dir.into()); s.set_desktop(false); s.set_startmenu(false); s.set_autostart(false); }
        let w = ui.as_weak();
        slint::Timer::single_shot(std::time::Duration::from_millis(300), move || if let Some(ui) = w.upgrade() { ui.global::<Setup>().invoke_start(); });
        let w = ui.as_weak();
        let t = slint::Timer::default();
        t.start(slint::TimerMode::Repeated, std::time::Duration::from_millis(200), move || if let Some(ui) = w.upgrade() {
            let s = ui.global::<Setup>();
            if s.get_step() >= 3 {
                if let Ok(out) = std::env::var("NC_SETUP_AUTO_OUT") { let _ = std::fs::write(out, format!("step={} error={}", s.get_step(), s.get_error())); }
                let _ = slint::quit_event_loop();
            }
        });
        std::mem::forget(t);
    }
    #[cfg(feature = "dev")]
    if let Ok(spec) = std::env::var("NC_SETUP_SNAP") {
        let p: Vec<String> = spec.split('|').map(str::to_string).collect();
        let s = ui.global::<Setup>();
        s.set_mode(p.get(1).and_then(|v| v.parse().ok()).unwrap_or(0));
        s.set_step(p.get(2).and_then(|v| v.parse().ok()).unwrap_or(0));
        s.set_installed_version(p.get(3).cloned().unwrap_or_default().into());
        s.set_progress(0.62);
        s.set_status("Copying Nether Client".into());
        if s.get_step() == 4 { s.set_error("Nether Client is open. Close it (right-click its tray icon > Quit if it's hidden), then press TRY AGAIN.".into()); }
        let (w, path) = (ui.as_weak(), p[0].clone());
        slint::Timer::single_shot(std::time::Duration::from_millis(1500), move || {
            if let Some(ui) = w.upgrade() {
                if let Ok(b) = ui.window().take_snapshot() { let _ = image::save_buffer(&path, b.as_bytes(), b.width(), b.height(), image::ColorType::Rgba8); }
            }
            let _ = slint::quit_event_loop();
        });
    }

    ui.run().map_err(|e| anyhow!("{e}"))?;

    if a.temp_copy {
        // delete this temp copy once it has exited
        use std::os::windows::process::CommandExt;
        if let Ok(me) = std::env::current_exe() {
            let _ = Command::new("cmd").args(["/C", &format!("ping 127.0.0.1 -n 3 >NUL & del /F /Q \"{}\"", me.display())]).creation_flags(CREATE_NO_WINDOW).spawn();
        }
    }
    Ok(())
}
