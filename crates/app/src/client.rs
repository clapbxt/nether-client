//! The in-game client's config files. Modules and their settings are edited only in game (Right Shift menu);
//! the launcher just decides which file the game uses, the same way game settings are shared:
//! an instance that shares settings uses the global `client/global.json`, any other uses its own
//! `<instance>/nether-client.json` (keys missing there fall back to the global file).
use std::path::PathBuf;

use serde_json::{json, Value};

use crate::Shared;

/// Module icons are our own line set only now (no item-texture fallback).
pub fn icon_names() -> Vec<String> { vec![] }

fn file(sh: &Shared, inst: Option<&str>) -> PathBuf {
    match inst {
        Some(name) => sh.paths.instance(name).join("nether-client.json"),
        None => sh.paths.root.join("client").join("global.json"),
    }
}

/// The config as an object; a damaged file is moved aside (kept, never silently overwritten).
fn read_json(p: &std::path::Path) -> Value {
    match nether_core::store::read_json::<Value>(p) {
        Ok(nether_core::store::Load::Ok(v)) if v.is_object() => v,
        _ => json!({}),
    }
}

/// Atomic write (temp file + rename): the agent, which reloads this file live, never reads half of it.
fn write_json(p: &std::path::Path, v: &Value) {
    if let Err(e) = nether_core::store::write_atomic(p, &serde_json::to_vec_pretty(v).unwrap_or_default()) { crate::applog::line(&format!("saving {} failed: {e}", p.display())); }
}

/// The instance's own client config (what the agent gets as `nether.instanceConfig`), or None when the instance
/// shares settings (`shared`: its "Share game settings" switch, and sharing on in Settings): then the game reads
/// and saves the global file.
pub fn instance_file(sh: &Shared, name: &str, shared: bool) -> Option<PathBuf> {
    let global = shared && nether_core::gamesettings::load_prefs(&sh.paths).enabled;
    (!global).then(|| file(sh, Some(name)))
}

/// The look the in-game menu follows (theme name + accent pair), written to the global client.json.
pub fn set_look(sh: &Shared, theme: &str, accent: (String, String)) {
    let p = file(sh, None);
    let mut root = read_json(&p);
    if !root["modules"].is_object() { root["modules"] = json!({}); }
    if !root["modules"]["client"].is_object() { root["modules"]["client"] = json!({}); }
    let c = &mut root["modules"]["client"];
    if c["theme"] == theme && c["accent"] == accent.0 && c["accent2"] == accent.1 { return; }
    c["theme"] = theme.into(); c["accent"] = accent.0.into(); c["accent2"] = accent.1.into();
    write_json(&p, &root);
}

/// Old instance configs carried `inject` / `groups` (client on/off, module groups) and `use_global` (the removed
/// launcher Client page's switch): drop them.
pub fn migrate(sh: &Shared, name: &str) {
    let p = file(sh, Some(name));
    let mut v = read_json(&p);
    let Some(o) = v.as_object_mut() else { return };
    if o.remove("inject").is_some() | o.remove("groups").is_some() | o.remove("use_global").is_some() { write_json(&p, &v); }
}

/// New instance from a Nether pack with built-in client settings: they go into the instance's own file, so they
/// apply when the instance keeps its own settings (an instance that shares settings uses the global setup).
pub fn setup_instance(paths: &nether_core::paths::Paths, inst: &str, preset: Option<&Value>) {
    let Some(Value::Object(pm)) = preset else { return };
    let p = paths.instance(inst).join("nether-client.json");
    let mut root = read_json(&p);
    if !root["modules"].is_object() { root["modules"] = json!({}); }
    for (id, kv) in pm {
        if !root["modules"][id].is_object() { root["modules"][id] = json!({}); }
        if let Value::Object(kv) = kv { for (k, v) in kv { root["modules"][id][k] = v.clone(); } }
    }
    write_json(&p, &root);
}
