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
/// Themes: bg, s1, s2, s3, hairline, hairline2, text, dim, mut, ink (text on primary fills), primary fill, glass, glass hairline, hover (ARGB).
const THEMES: [[u32; 14]; 4] = [
    [0xFF000000, 0xFF0B0B0D, 0xFF141418, 0xFF1D1D23, 0x14FFFFFF, 0x29FFFFFF, 0xFFF1F2F5, 0xFFA3A8B3, 0xFF6B707B, 0xFF09090C, 0xFFF1F2F5, 0xA80B0B0D, 0x1AFFFFFF, 0x0FFFFFFF], // OLED
    [0xFF14151A, 0xFF1B1D23, 0xFF23262E, 0xFF2D313B, 0x12FFFFFF, 0x24FFFFFF, 0xFFE9ECF1, 0xFFA0A7B4, 0xFF6D7584, 0xFF0E0F13, 0xFFE9ECF1, 0xA81B1D23, 0x1AFFFFFF, 0x0FFFFFFF], // Obsidian
    [0xFF1A2130, 0xFF202839, 0xFF283245, 0xFF334057, 0x14BED2FF, 0x29BED2FF, 0xFFE6ECF7, 0xFF9EABC2, 0xFF6C7A93, 0xFF121823, 0xFFE6ECF7, 0xA8202839, 0x1ABED2FF, 0x10BED2FF], // Slate
    [0xFFEEF1F5, 0xFFFFFFFF, 0xFFF4F6F9, 0xFFE4E8EE, 0x17141E32, 0x2E141E32, 0xFF171A21, 0xFF545C6B, 0xFF8790A0, 0xFFF1F2F5, 0xFF171A21, 0xB8FFFFFF, 0x24141E32, 0x0F141E32], // Ice (light)
];
pub const THEME_NAMES: [&str; 4] = ["OLED", "Obsidian", "Slate", "Ice"];

fn argb(c: u32) -> Color { Color::from_argb_u8((c >> 24) as u8, (c >> 16) as u8, (c >> 8) as u8, c as u8) }

/// Push the theme surfaces into the Theme global.
pub fn apply_theme(ui: &AppWindow, theme: i32) {
    let c = THEMES[theme.clamp(0, THEMES.len() as i32 - 1) as usize];
    let t = ui.global::<Theme>();
    t.set_bg(argb(c[0])); t.set_s1(argb(c[1])); t.set_s2(argb(c[2])); t.set_s3(argb(c[3])); t.set_ln(argb(c[4])); t.set_ln2(argb(c[5]));
    t.set_tx(argb(c[6])); t.set_dim(argb(c[7])); t.set_mut(argb(c[8])); t.set_ink(argb(c[9])); t.set_ice(argb(c[10]));
    t.set_glass(argb(c[11])); t.set_glass_ln(argb(c[12])); t.set_hov(argb(c[13]));
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
    t.set_redtx(rgb(a[3]));
    t.set_press(rgb(a[4]));
    t.set_plate(rgb(a[5]));
    t.set_ts(TEXT_SCALES[text.clamp(0, TEXT_SCALES.len() as i32 - 1) as usize]);
}

fn java_rows(sh: &Shared) -> Vec<JavaRt> {
    MAJORS.iter().map(|(m, games)| {
        let (sub, custom) = match java::which(&sh.paths, *m) {
            Source::Custom(p) => (p.display().to_string(), true),
            Source::Downloaded(_) => ("Downloaded by Nether Client".into(), false),
            Source::System(p) => (format!("Installed: {}", p.display()), false),
            Source::Missing => ("Downloads automatically the first time a game needs it".into(), false),
        };
        JavaRt { major: *m as i32, title: format!("Java {m} · {games}").into(), sub: sub.into(), custom }
    }).collect()
}

/// Java rows are found on a worker (it looks through Program Files), never on the UI thread.
fn refresh_java(ui: &AppWindow, sh: &Arc<Shared>) {
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let rows: Vec<(i32, String, String, bool)> = java_rows(&sh).into_iter().map(|r| (r.major, r.title.to_string(), r.sub.to_string(), r.custom)).collect();
        ui_do(&weak, move |ui| ui.global::<Backend>().set_javas(ModelRc::new(VecModel::from(rows.into_iter().map(|(major, t, s, custom)| JavaRt { major, title: t.into(), sub: s.into(), custom }).collect::<Vec<_>>()))));
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
static READY: std::sync::Mutex<Option<PathBuf>> = std::sync::Mutex::new(None);

/// Check GitHub for a newer release. Installed copies with auto-update on download and verify it in the background;
/// it then installs when the launcher closes. `manual` = from CHECK NOW (reports every result as a toast).
/// update-state: 0 checking, 1 up to date, 2 newer (manual download), 3 couldn't check, 4 ready to install, 5 downloading
pub fn check_updates(ui: &AppWindow, sh: &Arc<Shared>, manual: bool) {
    let b = ui.global::<Backend>();
    if matches!(b.get_update_state(), 4 | 5) && !manual { return; }
    if b.get_update_state() == 4 { return; }
    b.set_update_state(0);
    let auto = installed_copy() && crate::prefs::load(&sh.paths).auto_update;
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let c = nether_core::download::client();
        let r = nether_core::update::latest(&c, env!("CARGO_PKG_VERSION"));
        match r {
            Ok(Some(rel)) if auto => {
                let ver = rel.version.clone();
                let v2 = ver.clone();
                ui_do(&weak, move |ui| { let b = ui.global::<Backend>(); b.set_update_state(5); b.set_update_ver(v2.as_str().into()); });
                let d = nether_core::update::download(&c, &sh.paths, &rel);
                ui_do(&weak, move |ui| {
                    let b = ui.global::<Backend>();
                    b.set_update_url(rel.page.as_str().into());
                    match d {
                        Ok(p) => {
                            *READY.lock().unwrap_or_else(|e| e.into_inner()) = Some(p);
                            b.set_update_state(4);
                            show_toast(ui, &sh, &format!("Nether Client {ver} is ready. It installs when you close the launcher"));
                        }
                        Err(e) => {
                            crate::applog::line(&format!("update {ver}: {e:#}"));
                            b.set_update_state(2);
                            if manual { show_toast(ui, &sh, &format!("Couldn't download the update: {e:#}")); }
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
            Ok(None) => ui_do(&weak, move |ui| { ui.global::<Backend>().set_update_state(1); if manual { show_toast(ui, &sh, "You're on the newest version"); } }),
            Err(e) => ui_do(&weak, move |ui| {
                crate::applog::line(&format!("update check: {e:#}"));
                ui.global::<Backend>().set_update_state(if manual { 3 } else { 1 });
                if manual { show_toast(ui, &sh, "Couldn't check for updates"); }
            }),
        }
    });
}

/// Hand a ready update to Setup, which waits for this launcher to exit, installs it, and (relaunch) starts it again.
pub fn apply_pending_update(relaunch: bool) -> bool {
    let Some(setup) = READY.lock().unwrap_or_else(|e| e.into_inner()).take() else { return false };
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
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compares_versions() {
        assert!(newer("v0.2.0", "0.1.0") && newer("0.1.1", "0.1.0") && newer("1.0", "0.9.9"));
        assert!(!newer("v0.1.0", "0.1.0") && !newer("0.0.9", "0.1.0") && !newer("", "0.1.0"));
        assert!(newer("v0.2.0-beta", "0.1.0"));
    }
}
