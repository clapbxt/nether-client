//! FPS boosters (2026-11-20): the curated performance mods for a profile's loader, what is installed, what has a
//! build for its version, and what they can't run with. Evidence: research/FPS boosters research (vault).
//! Fabric, Quilt (runs Fabric builds) and NeoForge get Sodium and friends; Forge gets Embeddium on 1.16.5-1.20.1;
//! Vanilla and Legacy Fabric have none (1.8.9 / 1.12.2 use OptiFine, and the Nether agent caps background FPS).
//! Files are only ever renamed to `.disabled` and back, never deleted.
use std::collections::HashMap;
use std::path::PathBuf;

use anyhow::Result;
use reqwest::blocking::Client;

use crate::launch::Loader;
use crate::paths::Paths;

/// One curated performance mod.
#[derive(Debug)]
pub struct Booster {
    /// Modrinth slug
    pub slug: &'static str,
    /// mod ids its jar declares (main one first)
    pub ids: &'static [&'static str],
    pub name: &'static str,
    /// what it does, from the project's own description
    pub does: &'static str,
    /// installed, enabled mod ids it refuses to run with (from its jar's `breaks` / `conflicts`, or its page for
    /// "optifine", which means an OptiFine jar in the mods folder)
    pub breaks: &'static [&'static str],
}

const SODIUM: Booster = Booster { slug: "sodium", ids: &["sodium"], name: "Sodium", does: "New rendering engine: much higher frame rates, less stutter", breaks: &["optifabric", "embeddium", "canvas", "vulkanmod", "optifine"] };
const LITHIUM: Booster = Booster { slug: "lithium", ids: &["lithium"], name: "Lithium", does: "Faster game logic: mobs, blocks and physics take less time each tick", breaks: &["optifabric"] };
const FERRITE: Booster = Booster { slug: "ferrite-core", ids: &["ferritecore"], name: "FerriteCore", does: "Uses far less memory for block and model data", breaks: &["hydrogen"] };
const MODERNFIX: Booster = Booster { slug: "modernfix", ids: &["modernfix"], name: "ModernFix", does: "Faster launches, less memory and many bug fixes", breaks: &[] };
const CULLING: Booster = Booster { slug: "entityculling", ids: &["entityculling"], name: "EntityCulling", does: "Skips drawing mobs, chests and signs you can't see", breaks: &["tlskincape"] };
const IMMEDIATE: Booster = Booster { slug: "immediatelyfast", ids: &["immediatelyfast"], name: "ImmediatelyFast", does: "Faster drawing of the HUD, text, maps and entities", breaks: &["optifabric", "optifine"] };
const DYNFPS: Booster = Booster { slug: "dynamic-fps", ids: &["dynamic_fps"], name: "Dynamic FPS", does: "Lowers FPS and volume while the game is in the background", breaks: &["optifabric", "smoothmenu"] };
const EMBEDDIUM: Booster = Booster { slug: "embeddium", ids: &["embeddium", "rubidium"], name: "Embeddium", does: "Sodium's rendering engine for Forge: higher frame rates, less stutter", breaks: &["optifine"] };

static MODERN: [&Booster; 7] = [&SODIUM, &LITHIUM, &FERRITE, &MODERNFIX, &CULLING, &IMMEDIATE, &DYNFPS];
static FORGE: [&Booster; 6] = [&EMBEDDIUM, &FERRITE, &MODERNFIX, &CULLING, &IMMEDIATE, &DYNFPS];

/// The boosters a loader can use (empty for Vanilla and Legacy Fabric).
pub fn catalog(loader: Loader) -> &'static [&'static Booster] {
    match loader {
        Loader::Fabric | Loader::Quilt | Loader::NeoForge => &MODERN,
        Loader::Forge => &FORGE,
        Loader::Vanilla | Loader::LegacyFabric => &[],
    }
}

/// Modrinth loader name of a launch loader.
pub fn modrinth_loader(loader: Loader) -> &'static str {
    match loader { Loader::Forge => "forge", Loader::NeoForge => "neoforge", Loader::Quilt => "quilt", _ => "fabric" }
}

/// A jar in the mods folder with what it declares.
#[derive(Clone, Debug)]
pub struct Jar { pub path: PathBuf, pub on: bool, pub name: String, pub ids: Vec<String>, pub deps: Vec<String> }

/// Every mod jar of the instance (enabled and `.disabled`). A jar without metadata whose name says OptiFine gets
/// the id "optifine" (OptiFine for Forge is a plain jar).
pub fn jars(paths: &Paths, instance: &str) -> Vec<Jar> {
    let mut out = vec![];
    for p in crate::instances::mod_files(paths, instance) {
        let f = p.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string();
        let on = f.ends_with(".jar");
        if !on && !f.ends_with(".jar.disabled") { continue; }
        let lower = f.to_lowercase();
        let (mut name, mut ids, deps) = crate::preflight::jar_info(&p).unwrap_or_default();
        if ids.is_empty() && lower.contains("optifine") && !lower.contains("optifabric") { ids.push("optifine".into()); name = "OptiFine".into(); }
        if name.trim().is_empty() || name.contains("${") { name = f.trim_end_matches(".disabled").trim_end_matches(".jar").to_string(); }
        out.push(Jar { path: p, on, name, ids, deps });
    }
    out
}

#[derive(Clone, Debug, PartialEq)]
pub enum State {
    /// not in the mods folder
    Absent,
    /// installed and enabled
    On(PathBuf),
    /// installed, turned off (`.disabled`)
    Off(PathBuf),
}

/// A booster's state in one instance.
#[derive(Clone, Debug)]
pub struct Row {
    pub b: &'static Booster,
    pub state: State,
    /// enabled mods it can't run with: (name, jar)
    pub blockers: Vec<(String, PathBuf)>,
    /// enabled mods that need it (turning it off turns them off too): (name, jar)
    pub needed_by: Vec<(String, PathBuf)>,
}

/// Local, no network: each booster of the loader with its state, blockers and dependents.
pub fn rows(jars: &[Jar], loader: Loader) -> Vec<Row> {
    catalog(loader).iter().map(|b| {
        let mine = |j: &&Jar| j.ids.iter().any(|i| b.ids.contains(&i.as_str()));
        // an enabled copy wins over a disabled one
        let state = match jars.iter().filter(mine).max_by_key(|j| j.on) {
            Some(j) if j.on => State::On(j.path.clone()),
            Some(j) => State::Off(j.path.clone()),
            None => State::Absent,
        };
        let blockers = jars.iter().filter(|j| j.on && !mine(j) && j.ids.iter().any(|i| b.breaks.contains(&i.as_str()))).map(|j| (j.name.clone(), j.path.clone())).collect();
        let needed_by = jars.iter().filter(|j| j.on && !mine(j) && j.deps.iter().any(|d| b.ids.contains(&d.as_str()))).map(|j| (j.name.clone(), j.path.clone())).collect();
        Row { b, state, blockers, needed_by }
    }).collect()
}

/// Which boosters have a build for `mc` on `loader`: slug -> Some(true/false), None = couldn't ask Modrinth.
pub fn available(c: &Client, loader: Loader, mc: &str) -> HashMap<&'static str, Option<bool>> {
    let l = modrinth_loader(loader);
    catalog(loader).iter().map(|b| (b.slug, crate::modrinth::latest_compatible(c, b.slug, mc, Some(l)).ok().map(|v| v.is_some()))).collect()
}

/// What turning a booster on or off did, for the toast.
#[derive(Debug, Default)]
pub struct Change {
    /// names of mods turned off along with it (blockers when turning on, dependents when turning off)
    pub also_off: Vec<String>,
    /// downloaded from Modrinth (with dependencies)
    pub installed: bool,
}

/// Turn a booster on: enable its jar or install it (and its required mods) from Modrinth, then turn off the
/// mods it can't run with. Off: disable its jar and the mods that need it.
pub fn set(c: &Client, paths: &Paths, instance: &str, loader: Loader, mc: &str, slug: &str, on: bool) -> Result<Change> {
    let jars = jars(paths, instance);
    let row = rows(&jars, loader).into_iter().find(|r| r.b.slug == slug).ok_or_else(|| anyhow::anyhow!("{slug} isn't an FPS booster for {}", loader.label()))?;
    let mut ch = Change::default();
    if on {
        match &row.state {
            State::On(_) => {}
            State::Off(p) => { crate::instances::set_mod_enabled(p, true)?; }
            State::Absent => {
                let dir = paths.instance(instance).join("mods");
                std::fs::create_dir_all(&dir)?;
                crate::modrinth::install(c, slug, None, mc, Some(modrinth_loader(loader)), &dir, &|_, _, _| {})?;
                ch.installed = true;
            }
        }
        for (n, p) in &row.blockers { crate::instances::set_mod_enabled(p, false)?; ch.also_off.push(n.clone()); }
        // Fabric / Quilt: the boosters need Fabric API modules (Sodium's jar requires them although Modrinth lists no
        // dependency), so make sure it is there and on
        if matches!(loader, Loader::Fabric | Loader::Quilt) { ensure_fabric_api(c, paths, instance, mc)?; }
    } else {
        if let State::On(p) = &row.state { crate::instances::set_mod_enabled(p, false)?; }
        for (n, p) in &row.needed_by { crate::instances::set_mod_enabled(p, false)?; ch.also_off.push(n.clone()); }
    }
    Ok(ch)
}

/// Fabric API installed and enabled: enable a disabled copy, else download it.
fn ensure_fabric_api(c: &Client, paths: &Paths, instance: &str, mc: &str) -> Result<()> {
    let js = jars(paths, instance);
    let fapi = |j: &&Jar| j.ids.iter().any(|i| i == "fabric-api" || i == "fabric");
    if js.iter().filter(fapi).any(|j| j.on) { return Ok(()); }
    if let Some(j) = js.iter().find(fapi) { crate::instances::set_mod_enabled(&j.path, true)?; return Ok(()); }
    crate::modrinth::install(c, "fabric-api", None, mc, Some("fabric"), &paths.instance(instance).join("mods"), &|_, _, _| {})?;
    Ok(())
}

/// The instance has Dynamic FPS on (the agent's own background limiter then steps aside).
pub fn dynamic_fps_on(paths: &Paths, instance: &str) -> bool { dynamic_fps_on_jars(&jars(paths, instance)) }

/// Dynamic FPS is among these jars, enabled.
pub fn dynamic_fps_on_jars(jars: &[Jar]) -> bool { jars.iter().any(|j| j.on && j.ids.iter().any(|i| i == "dynamic_fps")) }

#[cfg(test)]
mod tests {
    use super::*;

    fn jar(path: &str, on: bool, ids: &[&str], deps: &[&str]) -> Jar {
        Jar { path: PathBuf::from(path), on, name: ids[0].to_string(), ids: ids.iter().map(|s| s.to_string()).collect(), deps: deps.iter().map(|s| s.to_string()).collect() }
    }

    #[test]
    fn states_blockers_and_dependents() {
        let js = vec![
            jar("sodium.jar", true, &["sodium"], &["fabric-api"]),
            jar("iris.jar", true, &["iris"], &["sodium"]),
            jar("lithium.jar.disabled", false, &["lithium"], &[]),
            jar("optifabric.jar", true, &["optifabric"], &[]),
        ];
        let rs = rows(&js, Loader::Fabric);
        let r = |s: &str| rs.iter().find(|r| r.b.slug == s).unwrap();
        assert_eq!(r("sodium").state, State::On("sodium.jar".into()));
        assert_eq!(r("lithium").state, State::Off("lithium.jar.disabled".into()));
        assert_eq!(r("ferrite-core").state, State::Absent);
        assert_eq!(r("sodium").blockers.len(), 1);
        assert_eq!(r("sodium").needed_by[0].0, "iris");
        assert!(r("ferrite-core").blockers.is_empty());
        // Forge: Embeddium covers the old Rubidium id; an OptiFine jar blocks it
        let fj = vec![jar("rubidium.jar", true, &["rubidium"], &[]), jar("OptiFine_1.20.1.jar", true, &["optifine"], &[])];
        let fr = rows(&fj, Loader::Forge);
        assert!(matches!(fr[0].state, State::On(_)) && fr[0].blockers.len() == 1);
        assert!(catalog(Loader::Vanilla).is_empty() && catalog(Loader::LegacyFabric).is_empty());
        assert_eq!(catalog(Loader::Quilt).len(), 7);
    }

    /// Network: on downloads Sodium (+ Fabric API) and turns a clashing OptiFabric off; off renames it; on again restores it.
    #[test]
    #[ignore]
    fn switch_installs_disables_and_restores() {
        let root = crate::modpack::tmpdir("perf-set");
        let paths = Paths { root: root.clone() };
        let mods = paths.instance("t").join("mods");
        std::fs::create_dir_all(&mods).unwrap();
        {
            let f = std::fs::File::create(mods.join("optifabric.jar")).unwrap();
            let mut z = zip::ZipWriter::new(f);
            z.start_file::<_, ()>("fabric.mod.json", Default::default()).unwrap();
            std::io::Write::write_all(&mut z, br#"{"id":"optifabric","name":"OptiFabric","version":"1.14.3"}"#).unwrap();
            z.finish().unwrap();
        }
        let c = crate::download::client();
        let ch = set(&c, &paths, "t", Loader::Fabric, "1.21.11", "sodium", true).unwrap();
        assert!(ch.installed && ch.also_off == vec!["OptiFabric".to_string()], "{ch:?}");
        let js = jars(&paths, "t");
        assert!(js.iter().any(|j| j.on && j.ids.iter().any(|i| i == "fabric-api")), "fabric-api came along");
        assert!(mods.join("optifabric.jar.disabled").exists());
        set(&c, &paths, "t", Loader::Fabric, "1.21.11", "sodium", false).unwrap();
        assert!(matches!(rows(&jars(&paths, "t"), Loader::Fabric)[0].state, State::Off(_)));
        let ch = set(&c, &paths, "t", Loader::Fabric, "1.21.11", "sodium", true).unwrap();
        assert!(!ch.installed && matches!(rows(&jars(&paths, "t"), Loader::Fabric)[0].state, State::On(_)));
        let _ = std::fs::remove_dir_all(root);
    }
}
