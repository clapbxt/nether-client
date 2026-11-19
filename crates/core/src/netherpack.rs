//! Nether packs: small curated bundles. A pack is just a JSON file naming Modrinth projects
//! (downloaded for the instance's version at install time) plus optional client module groups.
//! Built-in packs ship with the launcher; then packs from the backend content repo (cached), then `<root>/packs/*.json`.
use std::path::Path;

use anyhow::Result;
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};

use crate::paths::Paths;

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct NetherPack {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub desc: String,
    /// Modrinth loader the mods are for ("fabric").
    #[serde(default = "fabric")]
    pub loader: String,
    /// Modrinth project slugs or ids.
    pub mods: Vec<String>,
    /// Client module groups the pack turns on (agent groups: hud, pvp, qol, menu).
    #[serde(default)]
    pub groups: Vec<String>,
    /// Built-in client settings: an agent `modules` object laid over the global setup (None = none).
    #[serde(default)]
    pub client: Option<serde_json::Value>,
}

fn fabric() -> String { "fabric".into() }

fn pack(id: &str, name: &str, desc: &str, mods: &[&str], groups: &[&str]) -> NetherPack {
    NetherPack { id: id.into(), name: name.into(), desc: desc.into(), loader: fabric(), mods: mods.iter().map(|s| s.to_string()).collect(), groups: groups.iter().map(|s| s.to_string()).collect(), client: None }
}

/// Packs bundled with the launcher.
pub fn builtin() -> Vec<NetherPack> {
    vec![
        pack("essentials", "Nether Essentials", "The performance core: Sodium, Lithium, FerriteCore, ModernFix and a mod menu.", &["fabric-api", "sodium", "lithium", "ferrite-core", "modernfix", "entityculling", "immediatelyfast", "dynamic-fps", "modmenu"], &["hud", "qol", "menu"]),
        pack("shaders", "Nether Shaders", "Essentials plus Iris, ready for any shader pack.", &["sodium", "iris", "lithium", "ferrite-core", "entityculling", "modmenu"], &["hud", "qol", "menu"]),
        NetherPack {
            client: Some(serde_json::json!({
                "keys": {"enabled": true}, "cps": {"enabled": true}, "fps": {"enabled": true},
                "armor": {"enabled": true, "layout": "Icons", "value": "Durability"}, "potions": {"enabled": true},
                "crosshair": {"enabled": true}
            })),
            ..pack("pvp", "Nether PvP", "Lean and high-FPS for servers, with the full PvP HUD.", &["sodium", "lithium", "ferrite-core", "entityculling", "immediatelyfast"], &["hud", "pvp", "qol", "menu"])
        },
        pack("lite", "Nether Lite", "Just Sodium and the client. Closest to vanilla.", &["sodium"], &["hud", "menu"]),
    ]
}

/// Built-ins, then published packs, then user packs from `<root>/packs/*.json` (a later pack with the same id replaces it).
pub fn all(paths: &Paths) -> Vec<NetherPack> {
    let mut out = builtin();
    for p in crate::backend::packs_cached() {
        if p.id.is_empty() || p.mods.is_empty() { continue; }
        match out.iter_mut().find(|x| x.id == p.id) { Some(x) => *x = p, None => out.push(p) }
    }
    let dir = paths.root.join("packs");
    let mut files: Vec<_> = std::fs::read_dir(&dir).map(|r| r.flatten().map(|e| e.path()).filter(|p| p.extension().is_some_and(|x| x == "json")).collect()).unwrap_or_default();
    files.sort();
    for f in files {
        let Ok(p) = std::fs::read(&f).map_err(anyhow::Error::from).and_then(|b| Ok(serde_json::from_slice::<NetherPack>(&b)?)) else { continue };
        if p.id.is_empty() || p.mods.is_empty() { continue; }
        match out.iter_mut().find(|x| x.id == p.id) { Some(x) => *x = p, None => out.push(p) }
    }
    out
}

pub fn find(paths: &Paths, id: &str) -> Option<NetherPack> { all(paths).into_iter().find(|p| p.id == id) }

/// Install every mod of `pack` built for `mc` into `mods_dir`. Mods with no build for this version are skipped
/// and returned as the second list. Progress = (message, 0..1).
pub fn install(c: &Client, pack: &NetherPack, mc: &str, mods_dir: &Path, progress: &crate::Progress) -> Result<(Vec<String>, Vec<String>)> {
    std::fs::create_dir_all(mods_dir)?;
    let (mut ok, mut missing) = (vec![], vec![]);
    let n = pack.mods.len().max(1) as f32;
    for (i, m) in pack.mods.iter().enumerate() {
        progress(&format!("Installing {m}"), i as f32 / n);
        let base = i as f32 / n;
        let sub = |_f: &str, d: u64, t: u64| { if t > 0 { progress(&format!("Installing {m}"), base + (d as f32 / t as f32) / n); } };
        match crate::modrinth::install(c, m, None, mc, Some(&pack.loader), mods_dir, &sub) {
            Ok(_) => ok.push(m.clone()),
            Err(_) => missing.push(m.clone()),
        }
    }
    progress("Pack installed", 1.0);
    Ok((ok, missing))
}
