//! Settings page pieces that are not plain prefs: Java per major version, launcher folders, the look
//! (accent colour + text size, applied to the `Theme` global), removing saved accounts, and the update check
//! against GitHub releases (silent when there is no release yet or no connection).
use std::path::{Path, PathBuf};
use std::sync::Arc;

use nether_core::java::{self, Source};
use slint::{Color, ComponentHandle, ModelRc, VecModel};

use crate::{show_toast, ui_do, AppWindow, Backend, JavaRt, Link, Shared, Theme};

/// Java majors the launcher uses and the game versions that want each.
const MAJORS: [(u32, &str); 4] = [(8, "Minecraft 1.16.5 and older"), (17, "Minecraft 1.17 to 1.20.4"), (21, "Minecraft 1.20.5 to 1.21.11"), (25, "Minecraft 26.1 and newer")];

/// Accent palettes: base, bright, deep, text on dark, pressed, PLAY plate (same order as the Settings swatches).
const ACCENTS: [[u32; 6]; 5] = [
    [0xF76A05, 0xFF9A2E, 0x8F3A02, 0xFFAA66, 0xD65A04, 0x4A2002], // fire (the logo's orange; default)
    [0xE5372D, 0xFF6157, 0x9E1F18, 0xFF8A82, 0xC42A21, 0x4A1210], // racing red
    [0x22A845, 0x3DDC6A, 0x146B2B, 0x7BE89A, 0x1B8A39, 0x0B3816], // acid
    [0x1F6FEB, 0x4C8DFF, 0x123F86, 0x8AB4FF, 0x1A5DC7, 0x0A2148], // cobalt
    [0x8B3DFF, 0xA66BFF, 0x4F1F99, 0xC4A1FF, 0x7330D9, 0x26104A], // violet
];
/// Themes: bg, s1, s2, s3, hairline, hairline2, text, dim, mut, ink (text on primary fills), primary fill, glass, glass hairline,
/// hover, backdrop top tint, panel sheen, card shadow (ARGB). v8: one Nether look (neutral charcoal / paper, small radii,
/// Nether Pixel titles, bevelled solid buttons) in Dark and Light; the accent is the only colour choice.
const THEMES: [[u32; 17]; 2] = [
    [0xFF0F0F11, 0xF218181B, 0xFF202024, 0xFF2A2A30, 0x10FFFFFF, 0x21FFFFFF, 0xFFF4F4F5, 0xFFA1A1AA, 0xFF71717A, 0xFF0F0F11, 0xFFF4F4F5, 0xC718181B, 0x17FFFFFF, 0x0BFFFFFF, 0xFF17171B, 0x08FFFFFF, 0x66000000], // Nether Dark
    [0xFFEEEEF1, 0xF7FFFFFF, 0xFFF5F5F7, 0xFFE6E6EB, 0x12000000, 0x24000000, 0xFF18181B, 0xFF52525B, 0xFF8A8A93, 0xFFFFFFFF, 0xFF18181B, 0xD9FFFFFF, 0x14000000, 0x0A000000, 0xFFF9F9FB, 0x80FFFFFF, 0x1F000000], // Nether Light
];
/// Names written to client.json for the in-game menu (`Th.NAMES`, same order).
pub const THEME_NAMES: [&str; 2] = ["Nether Dark", "Nether Light"];

/// Saved theme index -> a valid one (pre-v8 had four: the odd ones were light).
pub fn theme_idx(theme: i32) -> usize { (theme.max(0) % 2) as usize }

fn argb(c: u32) -> Color { Color::from_argb_u8((c >> 24) as u8, (c >> 16) as u8, (c >> 8) as u8, c as u8) }

/// Push the theme surfaces, shape and display face into the Theme global.
pub fn apply_theme(ui: &AppWindow, theme: i32) {
    let i = theme_idx(theme);
    let c = THEMES[i];
    let light = i == 1;
    let t = ui.global::<Theme>();
    t.set_bg(argb(c[0])); t.set_s1(argb(c[1])); t.set_s2(argb(c[2])); t.set_s3(argb(c[3])); t.set_ln(argb(c[4])); t.set_ln2(argb(c[5]));
    t.set_tx(argb(c[6])); t.set_dim(argb(c[7])); t.set_mut(argb(c[8])); t.set_ink(argb(c[9])); t.set_ice(argb(c[10]));
    t.set_glass(argb(c[11])); t.set_glass_ln(argb(c[12])); t.set_hov(argb(c[13])); t.set_bg_top(argb(c[14])); t.set_sheen(argb(c[15])); t.set_shade(argb(c[16]));
    t.set_aura(if light { 0.05 } else { 0.07 });
    t.set_r1(5.0); t.set_r2(8.0); t.set_r3(12.0);
    t.set_square(false); t.set_light(light);
    t.set_disp("Barlow".into());
}

/// Accent pair (base, bright) as "#RRGGBB" for the in-game menu.
pub fn accent_hex(accent: i32) -> (String, String) {
    let a = ACCENTS[accent.clamp(0, ACCENTS.len() as i32 - 1) as usize];
    (format!("#{:06X}", a[0]), format!("#{:06X}", a[1]))
}

const TEXT_SCALES: [f32; 3] = [1.0, 1.1, 1.2];

fn rgb(c: u32) -> Color { Color::from_rgb_u8((c >> 16) as u8, (c >> 8) as u8, c as u8) }

/// Push the accent and text size into the Theme global.
pub fn apply_look(ui: &AppWindow, accent: i32, text: i32) {
    let a = ACCENTS[accent.clamp(0, ACCENTS.len() as i32 - 1) as usize];
    let t = ui.global::<Theme>();
    t.set_red(rgb(a[0]));
    t.set_red2(rgb(a[1]));
    t.set_red3(rgb(a[2]));
    t.set_redtx_d(rgb(a[3]));
    t.set_press(rgb(a[4]));
    t.set_plate(rgb(a[5]));
    t.set_ts(TEXT_SCALES[text.clamp(0, TEXT_SCALES.len() as i32 - 1) as usize]);
}

fn java_rows(sh: &Shared) -> Vec<JavaRt> {
    MAJORS.iter().map(|(m, games)| {
        let src = java::which(&sh.paths, *m);
        let ready = src != Source::Missing;
        let (sub, custom) = match src {
            Source::Custom(p) => (p.display().to_string(), true),
            Source::Downloaded(_) => ("Downloaded by Nether Client".into(), false),
            Source::System(p) => (format!("Installed: {}", p.display()), false),
            Source::Missing => ("Downloads automatically the first time a game needs it".into(), false),
        };
        JavaRt { major: *m as i32, title: format!("Java {m} · {games}").into(), sub: sub.into(), custom, ready }
    }).collect()
}

/// Java rows are found on a worker (it looks through Program Files), never on the UI thread.
fn refresh_java(ui: &AppWindow, sh: &Arc<Shared>) {
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let rows: Vec<(i32, String, String, bool, bool)> = java_rows(&sh).into_iter().map(|r| (r.major, r.title.to_string(), r.sub.to_string(), r.custom, r.ready)).collect();
        ui_do(&weak, move |ui| {
            crate::setup::java_notes(ui, &rows);
            ui.global::<Backend>().set_javas(ModelRc::new(VecModel::from(rows.into_iter().map(|(major, t, s, custom, ready)| JavaRt { major, title: t.into(), sub: s.into(), custom, ready }).collect::<Vec<_>>())));
        });
    });
}

fn open_dir(p: &Path) {
    let _ = std::fs::create_dir_all(p);
    let _ = std::process::Command::new("explorer").arg(p).spawn();
}

/// Running from an installed copy (Setup put it there next to its uninstaller), so updates can install themselves.
fn installed_copy() -> bool {
    std::env::current_exe().ok().and_then(|e| e.parent().map(|d| d.join("Uninstall Nether Client.exe").exists())).unwrap_or(false)
}

/// A downloaded, signature-checked setup waiting to install (when the launcher closes, or RESTART NOW).
static READY: std::sync::Mutex<Option<nether_core::update::Ready>> = std::sync::Mutex::new(None);

/// UPDATE NOW was pressed before the download finished: install as soon as it is verified.
static WANT_INSTALL: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Install the verified update now and close; Setup relaunches the new version.
fn install_now(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    b.set_update_gate_busy(true);
    if apply_pending_update(true) { let _ = slint::quit_event_loop(); return; }
    b.set_update_gate_busy(false);
    b.set_update_state(2);
    show_toast(ui, sh, "Couldn't start the update. Try again");
}

/// The in-game client updates itself, always: a newer compatible release of the client repo is downloaded, verified
/// and installed into every instance (nether_core::agent::update). One check at a time; failures only go to the log,
/// and the instances keep the client they have.
pub fn update_client(sh: &Arc<Shared>) {
    static BUSY: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
    if BUSY.swap(true, std::sync::atomic::Ordering::AcqRel) { return; }
    let sh = sh.clone();
    std::thread::spawn(move || {
        match nether_core::agent::update(&sh.paths, &nether_core::download::client()) {
            Ok(Some(v)) => crate::applog::line(&format!("client updated to {v}")),
            Ok(None) => {}
            Err(e) => crate::applog::line(&format!("client update: {e:#}")),
        }
        BUSY.store(false, std::sync::atomic::Ordering::Release);
    });
}

/// Check GitHub for a newer release. An installed copy on an old version is gated (update now or exit) while the
/// signed setup downloads and verifies in the background. `manual` = from CHECK NOW / TRY AGAIN (toasts every result).
/// update-state: 0 checking, 1 up to date, 2 newer (manual download), 3 couldn't check, 4 ready to install, 5 downloading
pub fn check_updates(ui: &AppWindow, sh: &Arc<Shared>, manual: bool) {
    update_client(sh); // the client updates on its own schedule, whatever state the launcher update is in
    let b = ui.global::<Backend>();
    if matches!(b.get_update_state(), 4 | 5) && !manual { return; }
    if b.get_update_state() == 4 { return; }
    b.set_update_state(0);
    let installed = installed_copy();
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let c = nether_core::download::client();
        let r = nether_core::update::latest(&c, env!("CARGO_PKG_VERSION"), crate::prefs::load(&sh.paths).beta);
        let done = |ui: &AppWindow| { WANT_INSTALL.store(false, std::sync::atomic::Ordering::Relaxed); ui.global::<Backend>().set_update_gate_busy(false); };
        match r {
            Ok(Some(rel)) if installed => {
                let ver = rel.version.clone();
                let v2 = ver.clone();
                ui_do(&weak, move |ui| { let b = ui.global::<Backend>(); b.set_update_state(5); b.set_update_ver(v2.as_str().into()); b.set_update_gate(true); });
                let d = nether_core::update::download(&c, &sh.paths, &rel);
                ui_do(&weak, move |ui| {
                    let b = ui.global::<Backend>();
                    b.set_update_url(rel.page.as_str().into());
                    match d {
                        Ok(p) => {
                            *READY.lock().unwrap_or_else(|e| e.into_inner()) = Some(p);
                            b.set_update_state(4);
                            if WANT_INSTALL.swap(false, std::sync::atomic::Ordering::Relaxed) { install_now(ui, &sh); }
                        }
                        Err(e) => {
                            crate::applog::line(&format!("update {ver}: {e:#}"));
                            b.set_update_state(2);
                            done(ui);
                        }
                    }
                });
            }
            Ok(Some(rel)) => ui_do(&weak, move |ui| {
                let b = ui.global::<Backend>();
                b.set_update_state(2);
                b.set_update_ver(rel.version.as_str().into());
                b.set_update_url(rel.page.as_str().into());
                show_toast(ui, &sh, &format!("Nether Client {} is available · Settings > Storage & About", rel.version));
            }),
            Ok(None) => ui_do(&weak, move |ui| { done(ui); ui.global::<Backend>().set_update_gate(false); ui.global::<Backend>().set_update_state(1); if manual { show_toast(ui, &sh, "You're on the newest version"); } }),
            Err(e) => ui_do(&weak, move |ui| {
                crate::applog::line(&format!("update check: {e:#}"));
                done(ui);
                // gated and the retry couldn't reach GitHub: stay gated (TRY AGAIN shows)
                let gated = ui.global::<Backend>().get_update_gate();
                ui.global::<Backend>().set_update_state(if gated { 2 } else if manual { 3 } else { 1 });
                if manual { show_toast(ui, &sh, "Couldn't check for updates"); }
            }),
        }
    });
}

/// Hand a ready update to Setup, which waits for this launcher to exit, installs it, and (relaunch) starts it again.
pub fn apply_pending_update(relaunch: bool) -> bool {
    let Some(ready) = READY.lock().unwrap_or_else(|e| e.into_inner()).take() else { return false };
    // it waited in a user-writable folder: check the signature again right before running it
    if let Err(e) = nether_core::update::check(&ready) { crate::applog::line(&format!("update not installed: {e:#}")); return false; }
    let setup = ready.path;
    let pid = std::process::id().to_string();
    let mut args = vec!["--update", "--pid", pid.as_str()];
    if relaunch { args.push("--relaunch"); }
    match std::process::Command::new(&setup).args(&args).spawn() {
        Ok(_) => { crate::applog::line(&format!("installing update {}", setup.display())); true }
        Err(e) => { crate::applog::line(&format!("couldn't start the update: {e}")); false }
    }
}

/// Settings > Support: launcher version, Windows, memory, Java runtimes, instances, the end of the launcher log,
/// to the clipboard for a bug report (user folder and tokens removed).
pub fn copy_sysinfo(ui: &AppWindow, sh: &Arc<Shared>) {
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let mut s = format!("Nether Client {}\n", env!("CARGO_PKG_VERSION"));
        let ver = std::process::Command::new("cmd").args(["/C", "ver"]).output().ok().map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).unwrap_or_default();
        s += &format!("OS: {} {} {ver}\nRAM: {} GB\n", std::env::consts::OS, std::env::consts::ARCH, nether_core::instances::system_ram_mb() / 1024);
        for (m, _) in MAJORS {
            s += &format!("Java {m}: {}\n", match java::which(&sh.paths, m) { Source::Custom(p) => format!("custom {}", p.display()), Source::Downloaded(_) => "downloaded".into(), Source::System(p) => format!("system {}", p.display()), Source::Missing => "not installed".into() });
        }
        for d in nether_core::instances::load(&sh.paths).unwrap_or_default() {
            s += &format!("Instance: {} · {} · {} · {} mods · {} GB\n", d.name, d.version, d.loader, nether_core::instances::mod_files(&sh.paths, &d.name).len(), d.ram_gb);
        }
        let log = std::fs::read_to_string(crate::applog::file()).unwrap_or_default();
        let tail: Vec<&str> = log.lines().rev().take(60).collect();
        s += "\nLauncher log (last lines):\n";
        s += &tail.into_iter().rev().collect::<Vec<_>>().join("\n");
        let text = crate::instpage::redact(&s, &sh);
        ui_do(&weak, move |ui| {
            let ok = arboard::Clipboard::new().and_then(|mut c| c.set_text(text)).is_ok();
            show_toast(ui, &sh, if ok { "System info copied. Paste it into your bug report" } else { "Couldn't copy to the clipboard" });
        });
    });
}

pub fn init(ui: &AppWindow, sh: Arc<Shared>) {
    let b = ui.global::<Backend>();
    refresh_java(ui, &sh);
    let root = sh.paths.root.clone();
    let folders = [("Data folder", root.clone()), ("Instances", sh.paths.instances()), ("Java runtimes", sh.paths.runtimes())];
    b.set_folders(ModelRc::new(VecModel::from(folders.iter().map(|(k, p)| Link { k: (*k).into(), url: p.display().to_string().into() }).collect::<Vec<_>>())));
    b.on_open_path(move |p| {
        let p = PathBuf::from(p.as_str());
        // only the launcher's own folders
        if p.starts_with(&root) { open_dir(&p); }
    });
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        b.on_java_pick(move |major| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(f) = rfd::FileDialog::new().add_filter("Java", &["exe"]).set_title(format!("Choose javaw.exe for Java {major}")).pick_file() else { return };
            let name = f.file_name().and_then(|n| n.to_str()).unwrap_or("").to_lowercase();
            if name != "javaw.exe" && name != "java.exe" { show_toast(&ui, &sh, "Couldn't use that file: pick javaw.exe or java.exe from a Java bin folder"); return; }
            if let Some(found) = java::exe_major(&f).filter(|m| *m != major as u32) { show_toast(&ui, &sh, &format!("Couldn't use it: that is Java {found}, not Java {major}")); return; }
            match java::set_custom(&sh.paths, major as u32, Some(&f)) {
                Ok(()) => { refresh_java(&ui, &sh); show_toast(&ui, &sh, &format!("Java {major} set")); }
                Err(e) => show_toast(&ui, &sh, &format!("Couldn't save: {e}")),
            }
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        b.on_java_reset(move |major| {
            let Some(ui) = weak.upgrade() else { return };
            match java::set_custom(&sh.paths, major as u32, None) {
                Ok(()) => { refresh_java(&ui, &sh); show_toast(&ui, &sh, &format!("Java {major} is automatic again")); }
                Err(e) => show_toast(&ui, &sh, &format!("Couldn't save: {e}")),
            }
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        b.on_remove_account(move |uuid| {
            let Some(ui) = weak.upgrade() else { return };
            let name = nether_core::auth::accounts(&sh.paths).list.iter().find(|a| a.uuid == uuid.as_str()).map(|a| a.name.clone()).unwrap_or_default();
            match nether_core::auth::remove(&sh.paths, &uuid) {
                Ok(()) => { crate::show_accounts(&ui, &sh); show_toast(&ui, &sh, &format!("Removed {name} from this PC")); }
                Err(e) => show_toast(&ui, &sh, &format!("Couldn't remove the account: {e}")),
            }
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        b.on_check_updates(move || if let Some(ui) = weak.upgrade() { check_updates(&ui, &sh, true) });
        b.on_update_restart(|| { if apply_pending_update(true) { let _ = slint::quit_event_loop(); } });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        b.on_update_now(move || {
            let Some(ui) = weak.upgrade() else { return };
            let b = ui.global::<Backend>();
            match b.get_update_state() {
                4 => install_now(&ui, &sh),
                5 => { WANT_INSTALL.store(true, std::sync::atomic::Ordering::Relaxed); b.set_update_gate_busy(true); }
                _ => { WANT_INSTALL.store(true, std::sync::atomic::Ordering::Relaxed); b.set_update_gate_busy(true); check_updates(&ui, &sh, true); }
            }
        });
        b.on_update_exit(|| { let _ = slint::quit_event_loop(); });
    }
}

#[cfg(test)]
mod tests {
    use nether_core::update::newer;

    #[test]
    fn compares_versions() {
        assert!(newer("v0.2.0", "0.1.0") && newer("0.1.1", "0.1.0") && newer("1.0", "0.9.9"));
        assert!(!newer("v0.1.0", "0.1.0") && !newer("0.0.9", "0.1.0") && !newer("", "0.1.0"));
        assert!(newer("v0.2.0-beta", "0.1.0"));
    }
}
