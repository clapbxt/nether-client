//! Shared Minecraft settings. One global `options.txt` (in the launcher data dir) is merged into an
//! instance's `options.txt` before launch and updated from it after the game closes, so keybinds,
//! FOV, audio, video and controls follow the player across instances.
//!
//! Compatibility: unknown keys are harmless to Minecraft, so mod keybinds (Fabric and Forge both store
//! them in options.txt) are kept globally and reach every instance that has the mod. Pre-1.13 versions
//! store keys as LWJGL2 key codes, so binds are translated both ways; Forge's `:MODIFIER` suffix is
//! kept for Forge/NeoForge and stripped for everything else. Per-instance and version keys are never shared.
//!
//! Mod video settings (Sodium, Embeddium, OptiFine) are whole files whose format belongs to the mod, so they are
//! shared as files instead: the newest copy wins in both directions.
use std::path::{Path, PathBuf};

use anyhow::Result;
use serde::{Deserialize, Serialize};
#[cfg(test)]
use serde_json::json;

use crate::paths::Paths;

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq)]
#[serde(default)]
pub struct SyncPrefs {
    pub enabled: bool,
    pub controls: bool,
    pub video: bool,
    pub audio: bool,
    pub other: bool,
    /// mod video settings files (MOD_FILES)
    pub mods: bool,
}

impl Default for SyncPrefs {
    fn default() -> Self { SyncPrefs { enabled: true, controls: true, video: true, audio: true, other: true, mods: true } }
}

/// Mod settings shared as whole files (path inside the game folder): Sodium, Embeddium, OptiFine.
const MOD_FILES: &[&str] = &["config/sodium-options.json", "config/embeddium-options.json", "optionsof.txt"];

fn mod_store(paths: &Paths, rel: &str) -> PathBuf { paths.root.join("global").join("mods").join(rel) }

fn mtime(p: &Path) -> Option<std::time::SystemTime> { std::fs::metadata(p).and_then(|m| m.modified()).ok() }

/// Copy `from` over `to` when `from` exists and is newer (or `to` is missing).
fn newer_over(from: &Path, to: &Path) -> Result<()> {
    let Some(f) = mtime(from) else { return Ok(()) };
    if mtime(to).is_some_and(|t| t >= f) { return Ok(()); }
    if let Some(d) = to.parent() { std::fs::create_dir_all(d)?; }
    std::fs::copy(from, to)?;
    Ok(())
}

/// Shared mod settings into the instance (before launch) or back out of it (after exit). A file is put into
/// every instance, even one without the mod yet, so a mod installed later starts from the shared settings
/// instead of writing its defaults (which would then win as the newest copy).
fn sync_mod_files(paths: &Paths, game_dir: &Path, into_instance: bool) {
    for rel in MOD_FILES {
        let (g, i) = (mod_store(paths, rel), game_dir.join(rel));
        let _ = if into_instance { newer_over(&g, &i) } else { newer_over(&i, &g) };
    }
}

pub fn load_prefs(paths: &Paths) -> SyncPrefs {
    serde_json::from_value(crate::store::config(paths)["settings_sync"].clone()).unwrap_or_default()
}

pub fn save_prefs(paths: &Paths, p: &SyncPrefs) -> Result<()> {
    let v = serde_json::to_value(p)?;
    crate::store::update_config(paths, |o| { o.insert("settings_sync".into(), v); })
}

pub fn global_file(paths: &Paths) -> PathBuf { paths.root.join("global").join("options.txt") }

/// Keys that belong to one instance or one game version.
const NEVER: &[&str] = &[
    "version", "resourcePacks", "incompatibleResourcePacks", "lastServer", "tutorialStep", "joinedFirstServer",
    "skipMultiplayerWarning", "skipRealms32bitWarning", "onboardAccessibility", "hasShownTrialsHint",
    "overrideWidth", "overrideHeight", "fullscreenResolution", DATA_VERSION,
];

/// Shared-file key holding the data version of the game the shared values were last taken from (1.13+ only).
const DATA_VERSION: &str = "ncDataVersion";

#[derive(PartialEq)]
enum Cat { Controls, Video, Audio, Other }

fn category(k: &str) -> Cat {
    const CONTROLS: &[&str] = &["mouseSensitivity", "invertYMouse", "autoJump", "toggleCrouch", "toggleSprint", "touchscreen", "rawMouseInput", "discrete_mouse_scroll", "mouseWheelSensitivity", "operatorItemsTab", "sprintWindow"];
    const VIDEO: &[&str] = &[
        "fov", "gamma", "renderDistance", "simulationDistance", "guiScale", "maxFps", "enableVsync", "fullscreen", "graphicsMode", "fancyGraphics",
        "renderClouds", "particles", "entityShadows", "ao", "bobView", "biomeBlendRadius", "entityDistanceScaling", "mipmapLevels", "fovEffectScale",
        "screenEffectScale", "darknessEffectScale", "glintSpeed", "glintStrength", "prioritizeChunkUpdates", "anaglyph3d", "useVbo", "fboEnable",
        "attackIndicator", "showAutosaveIndicator", "hideLightningFlashes", "damageTiltStrength", "highContrast", "menuBackgroundBlurriness", "textBackgroundOpacity",
    ];
    if k.starts_with("key_") || CONTROLS.contains(&k) { Cat::Controls }
    else if k.starts_with("soundCategory_") || k == "soundDevice" || k == "showSubtitles" || k == "directionalAudio" { Cat::Audio }
    else if VIDEO.contains(&k) { Cat::Video }
    else { Cat::Other }
}

fn allowed(p: &SyncPrefs, k: &str) -> bool {
    if NEVER.contains(&k) { return false; }
    match category(k) { Cat::Controls => p.controls, Cat::Video => p.video, Cat::Audio => p.audio, Cat::Other => p.other }
}

fn parse(text: &str) -> Vec<(String, String)> {
    text.lines().filter_map(|l| l.split_once(':').map(|(k, v)| (k.to_string(), v.to_string()))).collect()
}

fn read(p: &Path) -> Vec<(String, String)> {
    std::fs::read_to_string(p).map(|t| parse(&t)).unwrap_or_default()
}

fn write(p: &Path, kv: &[(String, String)]) -> Result<()> {
    if let Some(d) = p.parent() { std::fs::create_dir_all(d)?; }
    let mut s = String::new();
    for (k, v) in kv { s.push_str(k); s.push(':'); s.push_str(v); s.push('\n'); }
    if std::fs::read_to_string(p).ok().as_deref() == Some(s.as_str()) { return Ok(()); }
    crate::store::write_atomic(p, s.as_bytes())?;
    Ok(())
}

fn set(kv: &mut Vec<(String, String)>, k: &str, v: String) {
    match kv.iter_mut().find(|(x, _)| x == k) { Some(e) => e.1 = v, None => kv.push((k.to_string(), v)) }
}

/// Before 1.13 (LWJGL2): numeric key codes, "en_US"-style language codes.
pub fn is_legacy(version: &str) -> bool {
    let mut it = version.split('.');
    match (it.next().and_then(|a| a.parse::<u32>().ok()), it.next().and_then(|b| b.parse::<u32>().ok())) {
        (Some(1), Some(minor)) => minor < 13,
        _ => false,
    }
}

// LWJGL2 key code -> GLFW key name suffix (after "key.keyboard.")
const LWJGL: &[(i32, &str)] = &[
    (1, "escape"), (2, "1"), (3, "2"), (4, "3"), (5, "4"), (6, "5"), (7, "6"), (8, "7"), (9, "8"), (10, "9"), (11, "0"), (12, "minus"), (13, "equal"),
    (14, "backspace"), (15, "tab"), (16, "q"), (17, "w"), (18, "e"), (19, "r"), (20, "t"), (21, "y"), (22, "u"), (23, "i"), (24, "o"), (25, "p"),
    (26, "left.bracket"), (27, "right.bracket"), (28, "enter"), (29, "left.control"), (30, "a"), (31, "s"), (32, "d"), (33, "f"), (34, "g"), (35, "h"),
    (36, "j"), (37, "k"), (38, "l"), (39, "semicolon"), (40, "apostrophe"), (41, "grave.accent"), (42, "left.shift"), (43, "backslash"), (44, "z"),
    (45, "x"), (46, "c"), (47, "v"), (48, "b"), (49, "n"), (50, "m"), (51, "comma"), (52, "period"), (53, "slash"), (54, "right.shift"),
    (55, "keypad.multiply"), (56, "left.alt"), (57, "space"), (58, "caps.lock"), (59, "f1"), (60, "f2"), (61, "f3"), (62, "f4"), (63, "f5"), (64, "f6"),
    (65, "f7"), (66, "f8"), (67, "f9"), (68, "f10"), (69, "num.lock"), (70, "scroll.lock"), (71, "keypad.7"), (72, "keypad.8"), (73, "keypad.9"),
    (74, "keypad.subtract"), (75, "keypad.4"), (76, "keypad.5"), (77, "keypad.6"), (78, "keypad.add"), (79, "keypad.1"), (80, "keypad.2"), (81, "keypad.3"),
    (82, "keypad.0"), (83, "keypad.decimal"), (87, "f11"), (88, "f12"), (156, "keypad.enter"), (157, "right.control"), (181, "keypad.divide"),
    (183, "print.screen"), (184, "right.alt"), (197, "pause"), (199, "home"), (200, "up"), (201, "page.up"), (203, "left"), (205, "right"),
    (207, "end"), (208, "down"), (209, "page.down"), (210, "insert"), (211, "delete"), (219, "left.win"), (220, "right.win"),
];

/// "17" -> "key.keyboard.w", "-100" -> "key.mouse.left", "0" -> "key.keyboard.unknown"
fn key_from_legacy(v: &str) -> String {
    let Ok(n) = v.trim().parse::<i32>() else { return v.to_string() };
    match n {
        -100 => "key.mouse.left".into(),
        -99 => "key.mouse.right".into(),
        -98 => "key.mouse.middle".into(),
        n if (-97..-80).contains(&n) => format!("key.mouse.{}", n + 101),
        n => LWJGL.iter().find(|(c, _)| *c == n).map(|(_, s)| format!("key.keyboard.{s}")).unwrap_or_else(|| "key.keyboard.unknown".into()),
    }
}

fn key_to_legacy(v: &str) -> String {
    let name = v.split(':').next().unwrap_or(v);
    match name {
        "key.mouse.left" => "-100".into(),
        "key.mouse.right" => "-99".into(),
        "key.mouse.middle" => "-98".into(),
        n if n.starts_with("key.mouse.") => n["key.mouse.".len()..].parse::<i32>().map(|b| (b - 101).to_string()).unwrap_or_else(|_| "0".into()),
        n => n.strip_prefix("key.keyboard.").and_then(|s| LWJGL.iter().find(|(_, x)| *x == s)).map(|(c, _)| c.to_string()).unwrap_or_else(|| "0".into()),
    }
}

/// Instance value -> shared (modern) form.
fn canonical(k: &str, v: &str, legacy: bool) -> String {
    if legacy && k.starts_with("key_") { return key_from_legacy(v); }
    if k == "lang" { return v.to_lowercase(); }
    if k == "fov" { return fov(v); }
    v.to_string()
}

/// Shared value -> the form this instance's game expects.
fn for_instance(k: &str, v: &str, legacy: bool, forge: bool) -> String {
    if k.starts_with("key_") {
        if legacy { return key_to_legacy(v); }
        if !forge { return v.split(':').next().unwrap_or(v).to_string(); }
    }
    if k == "fov" { return fov(v); }
    if k == "lang" && legacy {
        if let Some((a, b)) = v.split_once('_') { return format!("{a}_{}", b.to_uppercase()); }
    }
    v.to_string()
}

/// Every version (1.8 included) stores FOV as (degrees - 70) / 40, between -1 and 1. Older builds of this file
/// converted 1.8-1.12 values as if they were degrees, which left values like -1.725 or 110 (4470 degrees in game);
/// those are walked back to the setting they came from.
fn fov(v: &str) -> String {
    let Ok(mut n) = v.trim().parse::<f64>() else { return v.to_string() };
    if (-1.0..=1.0).contains(&n) { return v.to_string(); }
    for _ in 0..4 {
        if (-1.0..=1.0).contains(&n) { return n.to_string(); }
        if (30.0..=110.0).contains(&n) { return ((n - 70.0) / 40.0).to_string(); }
        n = if n > 110.0 { (n - 70.0) / 40.0 } else if n < -1.0 { 70.0 + 40.0 * n } else { break };
    }
    n.clamp(-1.0, 1.0).to_string()
}

fn capture(global: &mut Vec<(String, String)>, from: &[(String, String)], legacy: bool, prefs: &SyncPrefs) {
    for (k, v) in from {
        if allowed(prefs, k) { set(global, k, canonical(k, v, legacy)); }
    }
    // remember which game version wrote these values, so a new instance can say so (see before_launch)
    if !legacy {
        if let Some((_, v)) = from.iter().find(|(k, v)| k == "version" && v.trim().parse::<u32>().is_ok()) { set(global, DATA_VERSION, v.trim().to_string()); }
    }
}

/// First run: start the shared settings from the official launcher's game settings, if there are any.
fn seed(paths: &Paths, prefs: &SyncPrefs) -> Vec<(String, String)> {
    let official = dirs::data_dir().map(|d| d.join(".minecraft").join("options.txt"));
    let src = official.map(|p| read(&p)).unwrap_or_default();
    // data version 1519 = 1.13; files without one are older
    let legacy = src.iter().find(|(k, _)| k == "version").and_then(|(_, v)| v.parse::<u32>().ok()).map(|d| d < 1519).unwrap_or(!src.is_empty());
    let mut g = vec![];
    capture(&mut g, &src, legacy, prefs);
    if !g.is_empty() { let _ = write(&global_file(paths), &g); }
    g
}

/// Replace the shared settings with the official launcher's ones. Returns how many settings were imported.
pub fn import_official(paths: &Paths) -> Result<usize> {
    let prefs = load_prefs(paths);
    let _ = std::fs::remove_file(global_file(paths));
    Ok(seed(paths, &prefs).len())
}

/// Merge the shared settings into an instance's options.txt (call before launching).
pub fn before_launch(paths: &Paths, game_dir: &Path, version: &str, loader_label: &str) -> Result<()> {
    let prefs = load_prefs(paths);
    if !prefs.enabled { return Ok(()); }
    if prefs.mods { sync_mod_files(paths, game_dir, true); }
    let file = game_dir.join("options.txt");
    let mut inst = read(&file);
    let legacy = is_legacy(version);
    let mut global = read(&global_file(paths));
    if global.is_empty() {
        global = seed(paths, &prefs);
        // nothing to import: this instance's own settings become the shared ones
        if global.is_empty() && !inst.is_empty() {
            capture(&mut global, &inst, legacy, &prefs);
            write(&global_file(paths), &global)?;
            return Ok(());
        }
    }
    if global.is_empty() { return Ok(()); }
    let forge = loader_label.to_lowercase().contains("forge");
    for (k, v) in &global {
        if allowed(&prefs, k) { set(&mut inst, k, for_instance(k, v, legacy, forge)); }
    }
    // A new instance has no options.txt, and Minecraft data-fixes a file without "version" from version 0, running
    // every options fix since 1.12 (key codes, AO, blur…) over values that are already current. Say which version wrote
    // the values (1519 = 1.13, the first with these key names, for shared files from before this was recorded).
    if !legacy && !inst.iter().any(|(k, _)| k == "version") {
        let dv = global.iter().find(|(k, _)| k == DATA_VERSION).map(|(_, v)| v.clone()).unwrap_or_else(|| "1519".into());
        inst.insert(0, ("version".into(), dv));
    }
    write(&file, &inst)
}

/// Copy changes made in-game back into the shared settings (call after the game exits).
pub fn after_exit(paths: &Paths, game_dir: &Path, version: &str) -> Result<()> {
    let prefs = load_prefs(paths);
    if !prefs.enabled { return Ok(()); }
    if prefs.mods { sync_mod_files(paths, game_dir, false); }
    let inst = read(&game_dir.join("options.txt"));
    if inst.is_empty() { return Ok(()); }
    let mut global = read(&global_file(paths));
    capture(&mut global, &inst, is_legacy(version), &prefs);
    write(&global_file(paths), &global)
}

/// A key from the shared settings (e.g. "key_key.nether.menu"), writing `default` there if it is missing,
/// so the game keeps it with the other keybinds and in-game changes come back here.
pub fn shared_key(paths: &Paths, key: &str, default: &str) -> String {
    let file = global_file(paths);
    let mut g = read(&file);
    if let Some((_, v)) = g.iter().find(|(k, _)| k == key) { return v.clone(); }
    g.push((key.to_string(), default.to_string()));
    let _ = write(&file, &g);
    default.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_round_trip() {
        assert_eq!(key_from_legacy("17"), "key.keyboard.w");
        assert_eq!(key_to_legacy("key.keyboard.w"), "17");
        assert_eq!(key_from_legacy("-100"), "key.mouse.left");
        assert_eq!(key_to_legacy("key.mouse.4"), "-97");
        assert_eq!(key_from_legacy("-97"), "key.mouse.4");
        assert_eq!(for_instance("key_key.forward", "key.keyboard.w:NONE", false, false), "key.keyboard.w");
        assert_eq!(for_instance("key_key.forward", "key.keyboard.w:NONE", true, false), "17");
        assert_eq!(for_instance("lang", "en_us", true, false), "en_US");
        assert!(is_legacy("1.8.9") && is_legacy("1.12.2") && !is_legacy("1.13") && !is_legacy("26.3"));
    }

    fn tmp(tag: &str) -> Paths {
        let root = std::env::temp_dir().join(format!("nc_gs_{tag}_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        Paths { root }
    }

    fn kv(a: &[(&str, &str)]) -> Vec<(String, String)> { a.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect() }

    #[test]
    fn merge_and_capture_round_trip() {
        let paths = tmp("rt");
        write(&global_file(&paths), &kv(&[("fov", "0.5"), ("key_key.forward", "key.keyboard.w")])).unwrap();
        let game = paths.root.join("inst");
        write(&game.join("options.txt"), &kv(&[("fov", "0.0"), ("version", "4000")])).unwrap();
        before_launch(&paths, &game, "1.21.1", "Fabric").unwrap();
        let inst = read(&game.join("options.txt"));
        assert!(inst.contains(&("fov".into(), "0.5".into())) && inst.contains(&("version".into(), "4000".into())));
        assert!(inst.contains(&("key_key.forward".into(), "key.keyboard.w".into())));
        write(&game.join("options.txt"), &kv(&[("fov", "1.0"), ("version", "9")])).unwrap();
        after_exit(&paths, &game, "1.21.1").unwrap();
        let g = read(&global_file(&paths));
        assert!(g.contains(&("fov".into(), "1.0".into())) && !g.iter().any(|(k, _)| k == "version"));
        // video category off: not merged
        save_prefs(&paths, &SyncPrefs { video: false, ..Default::default() }).unwrap();
        assert!(!load_prefs(&paths).video);
        write(&game.join("options.txt"), &kv(&[("fov", "0.0")])).unwrap();
        before_launch(&paths, &game, "1.21.1", "Vanilla").unwrap();
        assert!(read(&game.join("options.txt")).contains(&("fov".into(), "0.0".into())));
        let _ = std::fs::remove_dir_all(&paths.root);
    }

    #[test]
    fn mod_files_newest_wins() {
        let paths = tmp("modfiles");
        let (a, b) = (paths.root.join("a"), paths.root.join("b"));
        let f = "config/sodium-options.json";
        std::fs::create_dir_all(a.join("config")).unwrap();
        std::fs::write(a.join(f), "{\"quality\":1}").unwrap();
        after_exit(&paths, &a, "1.21.1").unwrap();
        assert_eq!(std::fs::read_to_string(mod_store(&paths, f)).unwrap(), "{\"quality\":1}");
        // an instance without the file gets the shared copy before it starts
        before_launch(&paths, &b, "1.21.1", "Fabric").unwrap();
        assert_eq!(std::fs::read_to_string(b.join(f)).unwrap(), "{\"quality\":1}");
        // old prefs without "mods" still load (defaults fill the gap)
        let p: SyncPrefs = serde_json::from_value(json!({"enabled": true, "controls": false, "video": true, "audio": true, "other": true})).unwrap();
        assert!(p.mods && !p.controls);
        let _ = std::fs::remove_dir_all(&paths.root);
    }

    #[test]
    fn legacy_fov_and_keys_convert() {
        let paths = tmp("legacy");
        write(&global_file(&paths), &kv(&[("fov", "0.5"), ("key_key.forward", "key.keyboard.w")])).unwrap();
        let game = paths.root.join("old");
        write(&game.join("options.txt"), &kv(&[("fov", "1.0")])).unwrap();
        before_launch(&paths, &game, "1.8.9", "Vanilla").unwrap();
        let i = read(&game.join("options.txt"));
        // 1.8.9 stores FOV normalized like every other version; no "version" line before 1.13
        assert!(i.contains(&("fov".into(), "0.5".into())) && i.contains(&("key_key.forward".into(), "17".into())));
        assert!(!i.iter().any(|(k, _)| k == "version"));
        // values left by the old degrees conversion come back to the setting they were
        assert_eq!(fov("-1.725"), "1");
        assert_eq!(fov("110"), "1");
        assert_eq!(fov("4470"), "1");
        assert_eq!(fov("90"), "0.5");
        assert_eq!(fov("0.25"), "0.25");
        let _ = std::fs::remove_dir_all(&paths.root);
    }

    #[test]
    fn new_instance_gets_data_version() {
        let paths = tmp("dv");
        let (a, b) = (paths.root.join("a"), paths.root.join("b"));
        write(&a.join("options.txt"), &kv(&[("version", "4671"), ("key_key.forward", "key.keyboard.w")])).unwrap();
        after_exit(&paths, &a, "1.21.11").unwrap();
        assert!(read(&global_file(&paths)).contains(&(DATA_VERSION.into(), "4671".into())));
        before_launch(&paths, &b, "26.3", "Fabric").unwrap();
        let i = read(&b.join("options.txt"));
        assert_eq!(i[0], ("version".into(), "4671".into()));
        assert!(!i.iter().any(|(k, _)| k == DATA_VERSION));
        let _ = std::fs::remove_dir_all(&paths.root);
    }
}
