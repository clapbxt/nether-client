//! One launcher at a time: a later start (shortcut, a modpack opened with it) is handed to the running launcher over
//! loopback and exits. Also: joining a server by address with the instance that fits its version (Friends).
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::time::Duration;

use nether_core::net;
use slint::{ComponentHandle, Model};

use crate::{show_toast, ui_do, AppWindow, Backend, Shared};

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

/// After the user agreed: ask the server for its version, then offer to join with a fitting instance.
pub(crate) fn check(ui: &AppWindow, sh: &Arc<Shared>, addr: String) {
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let status = net::ping_server(&addr).ok();
        ui_do(&weak, move |ui| {
            let insts = ui.global::<Backend>().get_instances();
            let ver = status.as_ref().map(|s| s.version.clone()).unwrap_or_default();
            let list: Vec<(usize, String, String, i32)> = insts.iter().enumerate().map(|(i, x)| (i, x.title.to_string(), x.ver.to_string(), x.last_ts)).collect();
            let Some((idx, fits)) = pick(&list, &ver) else {
                show_toast(ui, &sh, &format!("Create an instance{} first, then open the invite again", if ver.is_empty() { String::new() } else { format!(" for {ver}") }));
                return;
            };
            let (_, title, iver, _) = &list[idx];
            let motd: String = status.as_ref().map(|s| s.motd.chars().take(120).collect()).unwrap_or_default();
            let text = match (&status, fits) {
                (None, _) => format!("The server at {addr} didn't answer yet. Try anyway with {title} ({iver})?"),
                (Some(s), true) => format!("{} · {}/{} online. Play {title} ({iver}) and connect?", if motd.is_empty() { "Minecraft server".into() } else { motd }, s.online, s.max),
                (Some(_), false) => format!("The server runs {ver}, and none of your instances match. Try with {title} ({iver})? Create a {ver} instance if it won't connect."),
            };
            let (sh2, addr2) = (sh.clone(), addr.clone());
            crate::instpage::ask(ui, &format!("Join {addr}?"), &text, "JOIN SERVER", false, move |ui| crate::instpage::join_server(ui, &sh2, idx as i32, &addr2));
        });
    });
}

/// The instance to join with: newest-played one whose version the server reports, else the newest-played one.
fn pick(list: &[(usize, String, String, i32)], server_ver: &str) -> Option<(usize, bool)> {
    let fits = |v: &str| !v.is_empty() && server_ver.split(|c: char| !(c.is_ascii_alphanumeric() || c == '.' || c == '-')).any(|t| t == v);
    let newest = |it: &mut dyn Iterator<Item = &(usize, String, String, i32)>| it.max_by_key(|x| x.3).map(|x| x.0);
    newest(&mut list.iter().filter(|x| fits(&x.2))).map(|i| (i, true)).or_else(|| newest(&mut list.iter()).map(|i| (i, false)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn picks_matching_version() {
        let l = vec![(0, "A".into(), "1.20.1".into(), 50), (1, "B".into(), "1.21.11".into(), 10), (2, "C".into(), "1.21.11".into(), 20)];
        assert_eq!(pick(&l, "Paper 1.21.11"), Some((2, true)));
        assert_eq!(pick(&l, "1.8.9"), Some((0, false)));
        assert_eq!(pick(&[], "1.21.11"), None);
    }
}
