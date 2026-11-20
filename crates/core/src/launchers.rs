//! Instances of other launchers on this PC: Prism / MultiMC, the CurseForge app, the Modrinth App, ATLauncher and
//! the vanilla launcher. [`scan`] lists them from each launcher's usual place, [`scan_dir`] from a folder the player
//! picks (portable installs, ATLauncher, moved data), [`import`] copies one into a new instance. The source is only
//! read, never changed. On-disk formats: vault `research/Launcher instance formats.md`.
use std::collections::BTreeSet;
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use serde_json::Value;

use crate::import::{copy_tree, Imported};
use crate::instances::free_name;
use crate::launch::Loader;
use crate::modpack::new_instance;
use crate::paths::Paths;
use crate::Progress;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Kind { Prism, MultiMC, CurseForge, Modrinth, ATLauncher, Vanilla }

impl Kind {
    pub fn label(self) -> &'static str {
        match self {
            Kind::Prism => "Prism Launcher", Kind::MultiMC => "MultiMC", Kind::CurseForge => "CurseForge",
            Kind::Modrinth => "Modrinth App", Kind::ATLauncher => "ATLauncher", Kind::Vanilla => "Minecraft Launcher",
        }
    }
}

/// What to copy from the game folder.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Copy {
    /// everything except these top-level names
    Skip(Vec<String>),
    /// only these top-level names (a shared `.minecraft`: never versions / libraries / assets)
    Only(Vec<String>),
}

/// One instance another launcher has.
#[derive(Clone, Debug)]
pub struct Found {
    pub kind: Kind,
    pub name: String,
    pub mc: String,
    pub loader: Loader,
    pub loader_version: String,
    /// the folder with mods / saves / options.txt
    pub game_dir: PathBuf,
    /// what identifies it (instance folder, or `launcher_profiles.json#<id>`): remembered so the list can say IMPORTED
    pub source: String,
    pub copy: Copy,
    pub ram_mb: Option<u32>,
    pub jvm_args: String,
    /// what is not carried over (shown after the import)
    pub notes: Vec<String>,
}

/// Per-game data in a shared `.minecraft`.
const PER_GAME: &[&str] = &["saves", "mods", "config", "resourcepacks", "shaderpacks", "screenshots", "schematics", "options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat"];
/// Never copied: launcher data, logs, caches.
const NOT_GAME: &[&str] = &["logs", "crash-reports", ".fabric", ".mixin.out", "debug", "versions", "libraries", "assets", "runtime", "bin", "webcache", "webcache2", "natives"];

fn skip(extra: &[&str]) -> Copy { Copy::Skip(NOT_GAME.iter().chain(extra).map(|s| s.to_string()).collect()) }

fn read_json(p: &Path) -> Option<Value> {
    let s = fs::read_to_string(p).ok()?;
    serde_json::from_str(s.trim_start_matches('\u{feff}')).ok()
}

fn s(v: &Value) -> String { v.as_str().unwrap_or_default().trim().to_string() }

/// -Xmx / -Xms are the memory setting, not extra arguments; returns (other args, -Xmx in MB).
fn split_mem(args: &str) -> (String, Option<u32>) {
    let mut mb = None;
    let rest: Vec<&str> = args.split_whitespace().filter(|a| {
        if let Some(v) = a.strip_prefix("-Xmx") { mb = mem_mb(v); return false; }
        !a.starts_with("-Xms")
    }).collect();
    (rest.join(" "), mb)
}

fn mem_mb(v: &str) -> Option<u32> {
    let (n, unit) = v.split_at(v.find(|c: char| !c.is_ascii_digit()).unwrap_or(v.len()));
    let n: u64 = n.parse().ok()?;
    let mb = match unit.to_ascii_lowercase().as_str() { "g" => n * 1024, "m" | "" => n, "k" => n / 1024, _ => return None };
    u32::try_from(mb).ok()
}

fn loader_named(k: &str) -> Option<Loader> {
    Some(match k.to_ascii_lowercase().replace(['-', '_', ' '], "").as_str() {
        "vanilla" | "" => Loader::Vanilla, "fabric" => Loader::Fabric, "quilt" => Loader::Quilt, "forge" => Loader::Forge,
        "neoforge" => Loader::NeoForge, "legacyfabric" => Loader::LegacyFabric, _ => return None,
    })
}

/// Loader versions some launchers prefix with the game version ("1.20.1-47.2.0", "1.12.2-forge-14.23.5").
fn bare_lv(lv: &str, mc: &str) -> String {
    let lv = lv.trim();
    let lv = lv.strip_prefix(&format!("{mc}-")).unwrap_or(lv);
    lv.strip_prefix("forge-").or_else(|| lv.strip_prefix("neoforge-")).unwrap_or(lv).to_string()
}

// ---------------------------------------------------------------- Prism / MultiMC

fn cfg(p: &Path) -> std::collections::HashMap<String, String> {
    fs::read_to_string(p).unwrap_or_default().lines().filter_map(|l| l.split_once('=')).map(|(k, v)| (k.trim().to_string(), v.trim().to_string())).collect()
}

/// Instances under a Prism / MultiMC data folder (its `.cfg` names the instance folders).
pub(crate) fn prism_in(data: &Path, kind: Kind) -> Vec<Found> {
    let c = ["prismlauncher.cfg", "polymc.cfg", "multimc.cfg"].iter().map(|f| data.join(f)).find(|p| p.is_file()).map(|p| cfg(&p)).unwrap_or_default();
    let mut dirs = vec![c.get("InstanceDir").filter(|d| !d.is_empty()).cloned().unwrap_or_else(|| "instances".into())];
    dirs.extend(c.get("AdditionalInstanceDirs").into_iter().flat_map(|d| d.split(',')).map(|d| d.trim().to_string()).filter(|d| !d.is_empty()));
    let mut out = vec![];
    for d in dirs {
        let root = if Path::new(&d).is_absolute() { PathBuf::from(&d) } else { data.join(&d) };
        for e in fs::read_dir(&root).into_iter().flatten().flatten() {
            if let Some(f) = prism_one(&e.path(), kind) { out.push(f); }
        }
    }
    out
}

fn prism_one(dir: &Path, kind: Kind) -> Option<Found> {
    if !dir.join("instance.cfg").is_file() { return None; }
    let c = cfg(&dir.join("instance.cfg"));
    let (mc, loader, lv) = crate::import::parse_mmc_pack(&fs::read_to_string(dir.join("mmc-pack.json")).ok()?).ok()?;
    let game_dir = ["minecraft", ".minecraft"].iter().map(|d| dir.join(d)).find(|p| p.is_dir()).unwrap_or_else(|| dir.join("minecraft"));
    let on = |k: &str| c.get(k).map(String::as_str) == Some("true");
    let ram_mb = if on("OverrideMemory") { c.get("MaxMemAlloc").and_then(|m| m.parse().ok()) } else { None };
    let jvm_args = if on("OverrideJavaArgs") { split_mem(c.get("JvmArgs").map(String::as_str).unwrap_or("")).0 } else { String::new() };
    let mut notes = vec![];
    if dir.join("jarmods").read_dir().is_ok_and(|mut r| r.next().is_some()) { notes.push("jar mods are not supported".into()); }
    let name = c.get("name").filter(|n| !n.is_empty()).cloned().unwrap_or_else(|| dir.file_name().unwrap_or_default().to_string_lossy().into());
    Some(Found { kind, name, mc, loader, loader_version: lv, game_dir, source: dir.display().to_string(), copy: skip(&[]), ram_mb, jvm_args, notes })
}

// ---------------------------------------------------------------- CurseForge app

/// Every folder with a `minecraftinstance.json` under `root` (the app's Instances folder).
pub(crate) fn curseforge_in(root: &Path) -> Vec<Found> {
    let mut out = vec![];
    for e in fs::read_dir(root).into_iter().flatten().flatten() {
        let dir = e.path();
        let Some(v) = read_json(&dir.join("minecraftinstance.json")) else { continue };
        let b = &v["baseModLoader"];
        let mut mc = s(&v["gameVersion"]);
        if mc.is_empty() { mc = s(&b["minecraftVersion"]); }
        if mc.is_empty() { continue; }
        // name "forge-47.2.0" | "neoforge-21.1.206" | "fabric-0.18.4-1.21.8" | "quilt-<l>-<mc>"; forgeVersion holds the loader version for all
        let n = s(&b["name"]);
        let (loader, mut lv) = match n.split_once('-') {
            Some((k, rest)) => (loader_named(k), rest.strip_suffix(&format!("-{mc}")).unwrap_or(rest).to_string()),
            None => (Some(Loader::Vanilla), String::new()),
        };
        let Some(loader) = loader else { continue };
        let fv = s(&b["forgeVersion"]);
        if !fv.is_empty() { lv = fv; }
        let ram_mb = if v["isMemoryOverride"].as_bool() == Some(true) { v["allocatedMemory"].as_u64().map(|m| m as u32) } else { None };
        let jvm_args = split_mem(&s(&v["javaArgsOverride"])).0;
        let name = Some(s(&v["name"])).filter(|n| !n.is_empty()).unwrap_or_else(|| e.file_name().to_string_lossy().into());
        out.push(Found { kind: Kind::CurseForge, name, mc: mc.clone(), loader, loader_version: bare_lv(&lv, &mc), game_dir: dir.clone(), source: dir.display().to_string(),
            copy: skip(&["minecraftinstance.json", "profileImage", ".curseclient"]), ram_mb, jvm_args, notes: vec![] });
    }
    out
}

// ---------------------------------------------------------------- Modrinth App

/// Profiles of a Modrinth App data folder: `app.db` (SQLite; read from a copy, the app may hold it open), or the
/// pre-SQLite `profiles/<x>/profile.json`.
pub(crate) fn modrinth_in(config: &Path) -> Vec<Found> {
    let db = config.join("app.db");
    if db.is_file() { return modrinth_db(config, &db).unwrap_or_default(); }
    let mut out = vec![];
    for e in fs::read_dir(config.join("profiles")).into_iter().flatten().flatten() {
        let Some(v) = read_json(&e.path().join("profile.json")) else { continue };
        let m = &v["metadata"];
        let lv = if m["loader_version"].is_object() { s(&m["loader_version"]["id"]) } else { s(&m["loader_version"]) };
        let ram = v["memory"]["maximum"].as_u64().map(|m| m as u32);
        let args = v["java"]["extra_arguments"].as_array().map(|a| a.iter().map(s).collect::<Vec<_>>().join(" ")).unwrap_or_default();
        if let Some(f) = modrinth_found(&e.path(), &s(&m["name"]), &s(&m["game_version"]), &s(&m["loader"]), &lv, ram, &args) { out.push(f); }
    }
    out
}

fn modrinth_found(dir: &Path, name: &str, mc: &str, loader: &str, lv: &str, ram_mb: Option<u32>, args: &str) -> Option<Found> {
    if mc.is_empty() || !dir.is_dir() { return None; }
    let loader = loader_named(loader)?;
    let name = if name.is_empty() { dir.file_name()?.to_string_lossy().to_string() } else { name.to_string() };
    Some(Found { kind: Kind::Modrinth, name, mc: mc.into(), loader, loader_version: bare_lv(lv, mc), game_dir: dir.into(), source: dir.display().to_string(),
        copy: skip(&["profile.json"]), ram_mb, jvm_args: split_mem(args).0, notes: vec![] })
}

fn modrinth_db(config: &Path, db: &Path) -> Result<Vec<Found>> {
    let tmp = std::env::temp_dir().join(format!("nether-mr-{}", std::process::id()));
    fs::create_dir_all(&tmp)?;
    for ext in ["", "-wal", "-shm"] {
        let src = PathBuf::from(format!("{}{ext}", db.display()));
        if src.is_file() { fs::copy(&src, tmp.join(format!("app.db{ext}")))?; }
    }
    let res = (|| -> Result<Vec<Found>> {
        let c = rusqlite::Connection::open(tmp.join("app.db"))?;
        let has = |t: &str| c.query_row("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?1", [t], |r| r.get::<_, i64>(0)).unwrap_or(0) > 0;
        let text = |r: &rusqlite::Row, i: usize| -> String {
            match r.get_ref(i) { Ok(rusqlite::types::ValueRef::Text(t)) => String::from_utf8_lossy(t).into(), Ok(rusqlite::types::ValueRef::Integer(n)) => n.to_string(), _ => String::new() }
        };
        // settings.custom_dir moves profiles/ (app.db stays put)
        let custom: Option<String> = if has("settings") { c.query_row("SELECT custom_dir FROM settings LIMIT 1", [], |r| r.get::<_, Option<String>>(0)).ok().flatten() } else { None };
        let root = custom.filter(|d| !d.is_empty() && Path::new(d).is_dir()).map(PathBuf::from).unwrap_or_else(|| config.to_path_buf()).join("profiles");
        let mut rows: Vec<(String, String, String, String, String, Option<u32>, String)> = vec![];
        if has("instances") && has("instance_content_sets") {
            let mut q = c.prepare("SELECT i.id, i.path, i.name, s.game_version, s.loader, s.loader_version FROM instances i JOIN instance_content_sets s ON i.applied_content_set_id = s.id")?;
            let list: Vec<(String, String, String, String, String, String)> = q.query_map([], |r| Ok((text(r, 0), text(r, 1), text(r, 2), text(r, 3), text(r, 4), text(r, 5))))?.flatten().collect();
            for (id, path, name, mc, l, lv) in list {
                let ov: Option<Value> = if has("instance_launch_overrides") {
                    c.query_row("SELECT json(overrides) FROM instance_launch_overrides WHERE instance_id = ?1", [&id], |r| r.get::<_, String>(0)).ok().and_then(|j| serde_json::from_str(&j).ok())
                } else { None };
                let ram = ov.as_ref().and_then(|o| o["memory"]["maximum"].as_u64()).map(|m| m as u32);
                let args = ov.as_ref().and_then(|o| o["extra_launch_args"].as_array().map(|a| a.iter().map(s).collect::<Vec<_>>().join(" "))).unwrap_or_default();
                rows.push((path, name, mc, l, lv, ram, args));
            }
        } else if has("profiles") {
            let mut q = c.prepare("SELECT * FROM profiles")?;
            let cols: Vec<String> = q.column_names().into_iter().map(String::from).collect();
            let at = |n: &str| cols.iter().position(|c| c == n);
            let (p, n, g, l, v, m, a) = (at("path"), at("name"), at("game_version"), at("mod_loader"), at("mod_loader_version"), at("override_mc_memory_max"), at("override_extra_launch_args"));
            let get = |r: &rusqlite::Row, i: Option<usize>| i.map(|i| text(r, i)).unwrap_or_default();
            rows = q.query_map([], |r| {
                let args = get(r, a);
                let args = serde_json::from_str::<Value>(&args).ok().and_then(|v| v.as_array().map(|a| a.iter().map(s).collect::<Vec<_>>().join(" "))).unwrap_or(args);
                Ok((get(r, p), get(r, n), get(r, g), get(r, l), get(r, v), get(r, m).parse().ok(), args))
            })?.flatten().collect();
        }
        Ok(rows.into_iter().filter(|r| !r.0.is_empty() && !r.0.contains("..")).filter_map(|(path, name, mc, l, lv, ram, args)| modrinth_found(&root.join(&path), &name, &mc, &l, &lv, ram, &args)).collect())
    })();
    let _ = fs::remove_dir_all(&tmp);
    res
}

// ---------------------------------------------------------------- ATLauncher

/// `instances/<x>/instance.json` under an ATLauncher folder (one with `configs/` and `instances/`).
pub(crate) fn atlauncher_in(base: &Path) -> Vec<Found> {
    let mut out = vec![];
    for e in fs::read_dir(base.join("instances")).into_iter().flatten().flatten() {
        let dir = e.path();
        let Some(v) = read_json(&dir.join("instance.json")) else { continue };
        let l = &v["launcher"];
        let mc = s(&v["version"]);
        if mc.is_empty() { continue; }
        let lvo = &l["loaderVersion"];
        let Some(loader) = (if lvo.is_object() { loader_named(&s(&lvo["type"])) } else { Some(Loader::Vanilla) }) else { continue };
        let mut notes = vec![];
        if dir.join("jarmods").read_dir().is_ok_and(|mut r| r.next().is_some()) { notes.push("jar mods are not supported".into()); }
        let name = Some(s(&l["name"])).filter(|n| !n.is_empty()).unwrap_or_else(|| e.file_name().to_string_lossy().into());
        out.push(Found { kind: Kind::ATLauncher, name, mc: mc.clone(), loader, loader_version: bare_lv(&s(&lvo["version"]), &mc), game_dir: dir.clone(),
            source: dir.display().to_string(), copy: skip(&["instance.json", "instance.png", "jarmods", "disabledmods", "loaders"]),
            ram_mb: l["maximumMemory"].as_u64().map(|m| m as u32), jvm_args: split_mem(&s(&l["javaArguments"])).0, notes });
    }
    out
}

// ---------------------------------------------------------------- vanilla launcher

/// (loader, loader version, minecraft version) from a `lastVersionId`, reading `versions/<id>/<id>.json` when needed.
fn vanilla_version(mc_dir: &Path, id: &str) -> (Loader, String, String, Option<String>) {
    let inherits = read_json(&mc_dir.join("versions").join(id).join(format!("{id}.json"))).map(|v| s(&v["inheritsFrom"])).filter(|s| !s.is_empty());
    for (pre, l) in [("fabric-loader-", Loader::Fabric), ("quilt-loader-", Loader::Quilt)] {
        if let Some(rest) = id.strip_prefix(pre) {
            if let Some((lv, mc)) = rest.split_once('-') { return (l, lv.into(), inherits.unwrap_or_else(|| mc.into()), None); }
        }
    }
    if let Some(rest) = id.strip_prefix("neoforge-") {
        let mc = inherits.unwrap_or_else(|| { let p: Vec<&str> = rest.split('.').collect(); if p.len() >= 2 { format!("1.{}.{}", p[0], p[1]).trim_end_matches(".0").to_string() } else { String::new() } });
        return (Loader::NeoForge, rest.into(), mc, None);
    }
    for (mark, l) in [("-neoforge-", Loader::NeoForge), ("-forge-", Loader::Forge), ("-forge", Loader::Forge)] {
        if let Some((mc, lv)) = id.split_once(mark) {
            // legacy "1.12.2-forge1.12.2-14.23.5.2860" / "1.8.9-forge1.8.9-11.15.1.2318-1.8.9"
            let lv = lv.strip_prefix(&format!("{mc}-")).unwrap_or(lv);
            let lv = lv.strip_suffix(&format!("-{mc}")).unwrap_or(lv);
            return (l, lv.into(), inherits.unwrap_or_else(|| mc.into()), None);
        }
    }
    match inherits {
        Some(mc) => (Loader::Vanilla, String::new(), mc, Some(format!("\"{id}\" is a modified version (OptiFine or similar): imported as vanilla"))),
        None => (Loader::Vanilla, String::new(), id.into(), None),
    }
}

/// Custom profiles of `launcher_profiles.json` in a `.minecraft` folder ("Latest release / snapshot" are skipped:
/// their worlds and settings come in through IMPORT WORLD and the settings import).
pub(crate) fn vanilla_in(mc_dir: &Path) -> Vec<Found> {
    let Some(v) = read_json(&mc_dir.join("launcher_profiles.json")) else { return vec![] };
    let mut out = vec![];
    for (id, p) in v["profiles"].as_object().into_iter().flatten() {
        if s(&p["type"]).starts_with("latest-") { continue; }
        let ver = s(&p["lastVersionId"]);
        if ver.is_empty() || ver.starts_with("latest-") { continue; }
        let (loader, lv, mc, note) = vanilla_version(mc_dir, &ver);
        let gd = s(&p["gameDir"]);
        let (game_dir, copy) = if gd.is_empty() || Path::new(&gd) == mc_dir {
            (mc_dir.to_path_buf(), Copy::Only(PER_GAME.iter().map(|s| s.to_string()).collect()))
        } else { (PathBuf::from(&gd), skip(&[])) };
        let (jvm_args, ram_mb) = split_mem(&s(&p["javaArgs"]));
        let name = Some(s(&p["name"])).filter(|n| !n.is_empty()).unwrap_or_else(|| ver.clone());
        out.push(Found { kind: Kind::Vanilla, name, mc, loader, loader_version: lv, game_dir, source: format!("{}#{id}", mc_dir.join("launcher_profiles.json").display()),
            copy, ram_mb, jvm_args, notes: note.into_iter().collect() });
    }
    out
}

// ---------------------------------------------------------------- finding them

fn env_dir(k: &str) -> Option<PathBuf> { std::env::var_os(k).map(PathBuf::from).filter(|p| p.is_dir()) }

/// The `.minecraft` folder of the official launcher.
pub fn minecraft_dir() -> Option<PathBuf> {
    let d = if cfg!(windows) { dirs::data_dir()?.join(".minecraft") } else if cfg!(target_os = "macos") { dirs::data_dir()?.join("minecraft") } else { dirs::home_dir()?.join(".minecraft") };
    Some(d).filter(|d| d.is_dir())
}

/// Instances of every launcher found in its usual place.
pub fn scan() -> Vec<Found> {
    let mut out = vec![];
    let data = dirs::data_dir();
    if let Some(d) = env_dir("PRISMLAUNCHER_DATA_DIR").or_else(|| data.as_ref().map(|d| d.join("PrismLauncher")).filter(|d| d.is_dir())) { out.extend(prism_in(&d, Kind::Prism)); }
    let home = dirs::home_dir();
    let cf = [home.as_ref().map(|h| h.join("curseforge/minecraft/Instances")), dirs::document_dir().map(|d| d.join("Curse/Minecraft/Instances"))];
    for d in cf.into_iter().flatten().filter(|d| d.is_dir()) { out.extend(curseforge_in(&d)); }
    let mr = [env_dir("THESEUS_CONFIG_DIR"), data.as_ref().map(|d| d.join("ModrinthApp")), data.as_ref().map(|d| d.join("com.modrinth.theseus"))];
    for d in mr.into_iter().flatten().filter(|d| d.is_dir()) { out.extend(modrinth_in(&d)); }
    // ATLauncher keeps its data next to wherever it was installed: try the usual spots
    let at = [data.as_ref().map(|d| d.join("ATLauncher")), dirs::data_local_dir().map(|d| d.join("Programs/ATLauncher")), home.as_ref().map(|h| h.join("ATLauncher")),
        dirs::download_dir().map(|d| d.join("ATLauncher")), data.as_ref().map(|d| d.join(".atlauncher"))];
    for d in at.into_iter().flatten().filter(|d| d.join("instances").is_dir() && d.join("configs").is_dir()) { out.extend(atlauncher_in(&d)); }
    if let Some(m) = minecraft_dir() { out.extend(vanilla_in(&m)); }
    dedup(out)
}

/// Instances in a folder the player picked: a launcher's data folder (or its `instances` / `Instances` folder),
/// a `.minecraft`, or one instance folder.
pub fn scan_dir(dir: &Path) -> Vec<Found> {
    let mut out = vec![];
    let is = |f: &str| dir.join(f).exists();
    if is("prismlauncher.cfg") || is("polymc.cfg") { out.extend(prism_in(dir, Kind::Prism)); }
    if is("multimc.cfg") { out.extend(prism_in(dir, Kind::MultiMC)); }
    if is("app.db") || (is("profiles") && is("meta")) { out.extend(modrinth_in(dir)); }
    if is("instances") && is("configs") { out.extend(atlauncher_in(dir)); }
    if is("launcher_profiles.json") { out.extend(vanilla_in(dir)); }
    if out.is_empty() {
        out.extend(curseforge_in(dir));
        for e in fs::read_dir(dir).into_iter().flatten().flatten() { if let Some(f) = prism_one(&e.path(), Kind::Prism) { out.push(f); } }
        if let Some(f) = prism_one(dir, Kind::Prism) { out.push(f); }
        if let Some(p) = dir.parent() {
            if dir.join("minecraftinstance.json").is_file() { out.extend(curseforge_in(p).into_iter().filter(|f| f.game_dir == dir)); }
            if dir.join("instance.json").is_file() { out.extend(atlauncher_in(p.parent().unwrap_or(p)).into_iter().filter(|f| f.game_dir == dir)); }
        }
    }
    dedup(out)
}

fn dedup(mut v: Vec<Found>) -> Vec<Found> {
    let mut seen = BTreeSet::new();
    v.retain(|f| seen.insert(f.source.clone()));
    v.sort_by(|a, b| a.kind.cmp(&b.kind).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
    v
}

// ---------------------------------------------------------------- importing

const RECORD: &str = "imported.json";

/// Sources already imported (so the list can mark them).
pub fn imported(paths: &Paths) -> BTreeSet<String> {
    fs::read_to_string(paths.root.join(RECORD)).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
}

fn remember(paths: &Paths, source: &str) {
    let mut all = imported(paths);
    all.insert(source.to_string());
    if let Ok(s) = serde_json::to_string_pretty(&all) { let _ = fs::write(paths.root.join(RECORD), s); }
}

/// Copy one found instance into a new Nether instance (named `name`, or its own name; a free name is chosen).
/// The new folder is removed again if anything fails; the source is only read.
pub fn import(paths: &Paths, f: &Found, name: Option<&str>, progress: &Progress) -> Result<Imported> {
    if !f.game_dir.is_dir() { bail!("{} is gone", f.game_dir.display()); }
    let want = name.map(str::trim).filter(|n| !n.is_empty()).unwrap_or(f.name.as_str());
    let name = free_name(paths, want, &[]);
    let dir = paths.instance(&name);
    if dir.exists() { bail!("{} already exists", dir.display()); }
    fs::create_dir_all(&dir)?;
    let res = (|| -> Result<crate::instances::InstanceData> {
        progress("Copying files", 0.1);
        match &f.copy {
            Copy::Skip(list) => {
                let l: Vec<&str> = list.iter().map(String::as_str).collect();
                copy_tree(&f.game_dir, &dir, &l)?;
            }
            Copy::Only(list) => for n in list {
                let p = f.game_dir.join(n);
                let Ok(t) = fs::symlink_metadata(&p) else { continue };
                if t.is_dir() { copy_tree(&p, &dir.join(n), &[])?; } else if t.is_file() { fs::copy(&p, dir.join(n)).with_context(|| format!("copying {n}"))?; }
            },
        }
        progress("Setting up", 0.9);
        let mut d = new_instance(paths, &name, &f.mc, f.loader, &f.loader_version)?;
        if let Some(mb) = f.ram_mb.filter(|m| *m >= 512) { d.ram_gb = mb.div_ceil(1024).clamp(1, 32); }
        d.jvm_args = f.jvm_args.clone();
        Ok(d)
    })();
    if res.is_err() { let _ = fs::remove_dir_all(&dir); }
    let data = res?;
    remember(paths, &f.source);
    progress("Instance imported", 1.0);
    Ok(Imported { data, missing: vec![], notes: f.notes.clone() })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::modpack::tmpdir;

    fn quiet() -> Progress { std::sync::Arc::new(|_, _| {}) }
    fn w(p: &Path, s: &str) { fs::create_dir_all(p.parent().unwrap()).unwrap(); fs::write(p, s).unwrap(); }

    #[test]
    fn prism_and_multimc() {
        let r = tmpdir("l-prism");
        w(&r.join("prismlauncher.cfg"), "InstanceDir=inst\nAdditionalInstanceDirs=\n");
        w(&r.join("inst/A/instance.cfg"), "name=Alpha\nOverrideMemory=true\nMaxMemAlloc=6144\nOverrideJavaArgs=true\nJvmArgs=-Xmx6G -XX:+UseG1GC\n");
        w(&r.join("inst/A/mmc-pack.json"), r#"{"components":[{"uid":"net.minecraft","version":"1.20.1"},{"uid":"net.fabricmc.fabric-loader","version":"0.15.7"}]}"#);
        w(&r.join("inst/A/minecraft/mods/a.jar"), "j");
        let f = scan_dir(&r);
        assert_eq!(f.len(), 1);
        let a = &f[0];
        assert_eq!((a.kind, a.name.as_str(), a.mc.as_str(), a.loader, a.loader_version.as_str(), a.ram_mb, a.jvm_args.as_str()),
            (Kind::Prism, "Alpha", "1.20.1", Loader::Fabric, "0.15.7", Some(6144), "-XX:+UseG1GC"));
        let _ = fs::remove_dir_all(r);
    }

    #[test]
    fn curseforge_app() {
        let r = tmpdir("l-cf");
        w(&r.join("Pack/minecraftinstance.json"), "\u{feff}{\"name\":\"All the Mods\",\"gameVersion\":\"1.21.8\",\"baseModLoader\":{\"name\":\"fabric-0.18.4-1.21.8\",\"forgeVersion\":\"0.18.4\",\"minecraftVersion\":\"1.21.8\"},\"isMemoryOverride\":false,\"allocatedMemory\":8192,\"javaArgsOverride\":null}");
        w(&r.join("Old/minecraftinstance.json"), r#"{"name":"Old Forge","gameVersion":"1.12.2","baseModLoader":{"name":"forge-14.23.5.2860"},"isMemoryOverride":true,"allocatedMemory":4096}"#);
        let f = curseforge_in(&r);
        let by = |n: &str| f.iter().find(|x| x.name == n).unwrap();
        assert_eq!((by("All the Mods").loader, by("All the Mods").loader_version.as_str(), by("All the Mods").ram_mb), (Loader::Fabric, "0.18.4", None));
        assert_eq!((by("Old Forge").loader, by("Old Forge").loader_version.as_str(), by("Old Forge").ram_mb), (Loader::Forge, "14.23.5.2860", Some(4096)));
        let _ = fs::remove_dir_all(r);
    }

    #[test]
    fn modrinth_new_old_and_json() {
        let r = tmpdir("l-mr");
        let c = rusqlite::Connection::open(r.join("app.db")).unwrap();
        c.execute_batch("CREATE TABLE instances(id TEXT, path TEXT, applied_content_set_id INTEGER, name TEXT);
            CREATE TABLE instance_content_sets(id INTEGER, instance_id TEXT, game_version TEXT, loader TEXT, loader_version TEXT);
            CREATE TABLE instance_launch_overrides(instance_id TEXT, overrides TEXT);
            INSERT INTO instances VALUES('i1', 'fabric-pack', 7, 'My Fabric');
            INSERT INTO instance_content_sets VALUES(7, 'i1', '1.21.1', 'neoforge', '21.1.206');
            INSERT INTO instance_launch_overrides VALUES('i1', '{\"memory\":{\"maximum\":5000},\"extra_launch_args\":[\"-Dx=1\"]}');").unwrap();
        drop(c);
        fs::create_dir_all(r.join("profiles/fabric-pack/mods")).unwrap();
        let f = modrinth_in(&r);
        assert_eq!(f.len(), 1);
        assert_eq!((f[0].name.as_str(), f[0].loader, f[0].loader_version.as_str(), f[0].ram_mb, f[0].jvm_args.as_str()), ("My Fabric", Loader::NeoForge, "21.1.206", Some(5000), "-Dx=1"));
        // older schema
        let r2 = tmpdir("l-mr2");
        let c = rusqlite::Connection::open(r2.join("app.db")).unwrap();
        c.execute_batch("CREATE TABLE profiles(path TEXT, name TEXT, game_version TEXT, mod_loader TEXT, mod_loader_version TEXT, override_mc_memory_max INTEGER, override_extra_launch_args TEXT);
            INSERT INTO profiles VALUES('p', 'Forge one', '1.20.1', 'forge', '1.20.1-47.2.0', NULL, '[]');").unwrap();
        drop(c);
        fs::create_dir_all(r2.join("profiles/p")).unwrap();
        let f = modrinth_in(&r2);
        assert_eq!((f[0].loader, f[0].loader_version.as_str(), f[0].ram_mb), (Loader::Forge, "47.2.0", None));
        // pre-SQLite
        let r3 = tmpdir("l-mr3");
        w(&r3.join("profiles/q/profile.json"), r#"{"metadata":{"name":"Quilty","game_version":"1.20.1","loader":"quilt","loader_version":{"id":"0.26.0"}},"memory":{"maximum":3072}}"#);
        let f = modrinth_in(&r3);
        assert_eq!((f[0].loader, f[0].loader_version.as_str(), f[0].ram_mb), (Loader::Quilt, "0.26.0", Some(3072)));
        for d in [r, r2, r3] { let _ = fs::remove_dir_all(d); }
    }

    #[test]
    fn atlauncher_and_vanilla_import() {
        let r = tmpdir("l-at");
        fs::create_dir_all(r.join("configs")).unwrap();
        w(&r.join("instances/LegacyPvP/instance.json"), r#"{"version":"1.8.9","launcher":{"name":"Legacy PvP","loaderVersion":{"type":"LegacyFabric","version":"0.16.0"},"maximumMemory":2048,"javaArguments":"-Xmx2G -Dfoo=1"}}"#);
        w(&r.join("instances/LegacyPvP/mods/m.jar"), "j");
        w(&r.join("instances/LegacyPvP/bin/minecraft.jar"), "x");
        let f = scan_dir(&r);
        assert_eq!((f[0].kind, f[0].loader, f[0].loader_version.as_str(), f[0].ram_mb, f[0].jvm_args.as_str()), (Kind::ATLauncher, Loader::LegacyFabric, "0.16.0", Some(2048), "-Dfoo=1"));

        let mc = tmpdir("l-mc");
        w(&mc.join("launcher_profiles.json"), r#"{"profiles":{"a":{"type":"latest-release","lastVersionId":"latest-release","name":""},
            "b":{"type":"custom","name":"Fabby","lastVersionId":"fabric-loader-0.15.7-1.20.1","javaArgs":"-Xmx4G -XX:+UseG1GC"},
            "c":{"type":"custom","name":"Forgey","lastVersionId":"1.12.2-forge1.12.2-14.23.5.2860"},
            "d":{"type":"custom","name":"OF","lastVersionId":"1.8.9-OptiFine_HD_U_M5"}}}"#);
        w(&mc.join("versions/1.8.9-OptiFine_HD_U_M5/1.8.9-OptiFine_HD_U_M5.json"), r#"{"inheritsFrom":"1.8.9"}"#);
        w(&mc.join("saves/W/level.dat"), "x");
        w(&mc.join("options.txt"), "fov:0.5");
        w(&mc.join("versions/1.20.1/1.20.1.jar"), "big");
        let f = scan_dir(&mc);
        assert_eq!(f.len(), 3, "latest-release skipped");
        let by = |n: &str| f.iter().find(|x| x.name == n).unwrap().clone();
        assert_eq!((by("Fabby").loader, by("Fabby").loader_version.as_str(), by("Fabby").mc.as_str(), by("Fabby").ram_mb), (Loader::Fabric, "0.15.7", "1.20.1", Some(4096)));
        assert_eq!((by("Forgey").loader, by("Forgey").loader_version.as_str(), by("Forgey").mc.as_str()), (Loader::Forge, "14.23.5.2860", "1.12.2"));
        assert_eq!((by("OF").loader, by("OF").mc.as_str(), by("OF").notes.len()), (Loader::Vanilla, "1.8.9", 1));

        // import: per-game data only from a shared .minecraft, source untouched, recorded
        let root = tmpdir("l-paths");
        let paths = Paths { root: root.clone() };
        let imp = import(&paths, &by("Fabby"), None, &quiet()).unwrap();
        let d = paths.instance(&imp.data.name);
        assert!(d.join("saves/W/level.dat").exists() && d.join("options.txt").exists());
        assert!(!d.join("versions").exists(), "launcher data not copied");
        assert_eq!((imp.data.ram_gb, imp.data.jvm_args.as_str(), imp.data.loader.as_str()), (4, "-XX:+UseG1GC", "Fabric"));
        assert!(mc.join("saves/W/level.dat").exists() && mc.join("versions/1.20.1/1.20.1.jar").exists(), "source untouched");
        assert!(imported(&paths).contains(&by("Fabby").source));
        // ATLauncher import skips its bin/
        let imp = import(&paths, &f_at(&r), None, &quiet()).unwrap();
        let d = paths.instance(&imp.data.name);
        assert!(d.join("mods/m.jar").exists() && !d.join("bin").exists() && !d.join("instance.json").exists());
        for x in [r, mc, root] { let _ = fs::remove_dir_all(x); }
    }

    fn f_at(r: &Path) -> Found { atlauncher_in(r).remove(0) }

    #[test]
    fn mem_and_versions() {
        assert_eq!(split_mem("-Xms1G -Xmx3072M -Dfoo"), ("-Dfoo".into(), Some(3072)));
        assert_eq!(bare_lv("1.20.1-47.2.0", "1.20.1"), "47.2.0");
        let (l, lv, mc, _) = vanilla_version(Path::new("."), "1.20.1-forge-47.2.0");
        assert_eq!((l, lv.as_str(), mc.as_str()), (Loader::Forge, "47.2.0", "1.20.1"));
        let (l, lv, mc, _) = vanilla_version(Path::new("."), "quilt-loader-0.26.0-1.20.1");
        assert_eq!((l, lv.as_str(), mc.as_str()), (Loader::Quilt, "0.26.0", "1.20.1"));
        let (l, _, mc, _) = vanilla_version(Path::new("."), "neoforge-21.1.206");
        assert_eq!((l, mc.as_str()), (Loader::NeoForge, "1.21.1"));
    }
}
