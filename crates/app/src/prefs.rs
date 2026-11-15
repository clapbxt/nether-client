//! Launcher preferences (config.json "launcher"): renderer, start with Windows, what happens on game
//! launch / window close (tray), reduced motion. Plus the system-tray icon used when hiding.
use std::cell::RefCell;

use nether_core::paths::Paths;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use slint::ComponentHandle;

use crate::AppWindow;

#[derive(Serialize, Deserialize, Clone, Copy, Debug)]
#[serde(default)]
pub struct LauncherPrefs {
    /// GPU rendering (off = software renderer; applies on restart)
    pub gpu: bool,
    pub autostart: bool,
    /// 0 keep open, 1 minimize, 2 hide to tray
    pub on_launch: i32,
    /// 0 quit, 1 hide to tray
    pub on_close: i32,
    /// stop ambient animations (saves GPU/CPU)
    pub calm: bool,
    /// accent colour (settings::ACCENTS index, 0 = fire)
    pub accent: i32,
    /// theme (settings::THEME_NAMES index, 1 = Obsidian, the default)
    pub theme: i32,
    /// reading-text size: 0 default, 1 large, 2 larger
    pub text: i32,
    /// Nether capes in game (the agent looks up the players it sees at the cape service; off = no lookups)
    pub capes: bool,
    /// Discord Rich Presence (local Discord app only; off by default)
    pub discord: bool,
    /// download and install launcher updates by itself (installed copies only)
    pub auto_update: bool,
}

impl Default for LauncherPrefs {
    fn default() -> Self { LauncherPrefs { gpu: true, autostart: false, on_launch: 1, on_close: 0, calm: false, accent: 0, theme: 1, text: 0, capes: true, discord: true, auto_update: true } }
}

pub fn load(paths: &Paths) -> LauncherPrefs {
    serde_json::from_value(nether_core::store::config(paths)["launcher"].clone()).unwrap_or_default()
}

pub fn save(paths: &Paths, p: &LauncherPrefs) -> anyhow::Result<()> {
    let was_on = load(paths).capes;
    let v: Value = serde_json::to_value(p)?;
    nether_core::store::update_config(paths, |o| { o.insert("launcher".into(), v); })?;
    // turning Nether capes off deletes your entry at the cape service (now, or at the next sign-in)
    if was_on && !p.capes { crate::skin::forget_nether(paths, true); }
    Ok(())
}

/// Apply the GPU preference. Call once at startup, before the window is created: off selects Slint's software renderer.
pub fn apply_renderer(paths: &Paths) {
    if !load(paths).gpu && std::env::var_os("SLINT_BACKEND").is_none() { std::env::set_var("SLINT_BACKEND", "winit-software"); }
}

/// Add or remove the launcher from Windows startup (HKCU Run key).
pub fn set_autostart(on: bool) -> std::io::Result<()> {
    const KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
    let mut cmd = std::process::Command::new("reg");
    if on {
        let exe = std::env::current_exe()?;
        cmd.args(["add", KEY, "/v", "NetherClient", "/t", "REG_SZ", "/d", &format!("\"{}\"", exe.display()), "/f"]);
    } else {
        cmd.args(["delete", KEY, "/v", "NetherClient", "/f"]);
    }
    #[cfg(windows)]
    { use std::os::windows::process::CommandExt; cmd.creation_flags(0x0800_0000); } // no console window
    let out = cmd.output()?;
    if out.status.success() || !on { Ok(()) } else { Err(std::io::Error::other(String::from_utf8_lossy(&out.stderr).to_string())) }
}

// ---- system tray ----

thread_local! {
    static TRAY: RefCell<Option<(tray_icon::TrayIcon, tray_icon::menu::MenuId, tray_icon::menu::MenuId)>> = const { RefCell::new(None) };
    static TRAY_UI: RefCell<Option<slint::Weak<AppWindow>>> = const { RefCell::new(None) };
}

/// Tray clicks arrive on the tray's thread; run them on the UI thread (no polling timer).
fn on_ui(f: impl FnOnce(&AppWindow) + Send + 'static) {
    let _ = slint::invoke_from_event_loop(move || TRAY_UI.with(|w| if let Some(ui) = w.borrow().as_ref().and_then(|w| w.upgrade()) { f(&ui) }));
}

fn tray_icon_image(paths: &Paths) -> Option<tray_icon::Icon> {
    // the launcher's ghast icon at 32px
    let _ = paths;
    let img = image::load_from_memory(include_bytes!("../ui/assets/nether-icon.png")).ok()?.resize_exact(32, 32, image::imageops::FilterType::Lanczos3).to_rgba8();
    tray_icon::Icon::from_rgba(img.into_raw(), 32, 32).ok()
}

/// Hide the window to the tray (creating the tray icon the first time).
pub fn hide_to_tray(ui: &AppWindow, paths: &Paths) {
    TRAY.with(|t| {
        if t.borrow().is_some() { return; }
        let menu = tray_icon::menu::Menu::new();
        let show = tray_icon::menu::MenuItem::new("Show Nether Client", true, None);
        let quit = tray_icon::menu::MenuItem::new("Quit", true, None);
        let _ = menu.append_items(&[&show, &quit]);
        let mut b = tray_icon::TrayIconBuilder::new().with_menu(Box::new(menu)).with_tooltip("Nether Client");
        if let Some(i) = tray_icon_image(paths) { b = b.with_icon(i); }
        if let Ok(icon) = b.build() {
            let (show_id, quit_id) = (show.id().clone(), quit.id().clone());
            tray_icon::menu::MenuEvent::set_event_handler(Some(move |e: tray_icon::menu::MenuEvent| {
                if e.id == quit_id { on_ui(|_| { let _ = slint::quit_event_loop(); }); }
                if e.id == show_id { on_ui(crate::prefs::show); }
            }));
            tray_icon::TrayIconEvent::set_event_handler(Some(|e: tray_icon::TrayIconEvent| {
                if let tray_icon::TrayIconEvent::DoubleClick { .. } | tray_icon::TrayIconEvent::Click { button: tray_icon::MouseButton::Left, button_state: tray_icon::MouseButtonState::Up, .. } = e { on_ui(crate::prefs::show); }
            }));
            *t.borrow_mut() = Some((icon, show.id().clone(), quit.id().clone()));
        }
    });
    TRAY_UI.with(|w| *w.borrow_mut() = Some(ui.as_weak()));
    let _ = ui.hide();
}

/// Bring the window back (from the tray or minimized) and to the front.
pub fn show(ui: &AppWindow) {
    let _ = ui.show();
    ui.window().set_minimized(false);
    crate::winchrome::to_front(ui.window());
}
