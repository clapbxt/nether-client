//! Pre-launch check: problems that would stop the game, found before the wait, as crash-page findings with
//! one-click fixes. Local only (jar metadata, instance settings, RAM), cached per jar, so a clean instance adds
//! no noticeable time. Never blocks: the launcher always offers "Launch anyway"; ignored findings stay quiet.
use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::{Read, Seek};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use crate::crash::{Action, Finding};
use crate::launch::Loader;
use crate::paths::Paths;

/// The instance being started.
pub struct Target<'a> {
    pub name: &'a str,
    pub version: &'a str,
    pub loader: Loader,
    /// chosen Java major (0 = automatic)
    pub java: u32,
    pub ram_gb: u32,
}

/// What a mod jar declares about itself.
#[derive(Clone, Debug, Default, PartialEq)]
struct Meta {
    fabric: bool,
    quilt: bool,
    /// META-INF/mods.toml or mcmod.info
    forge: bool,
    /// META-INF/neoforge.mods.toml
    neoforge: bool,
    name: String,
    /// own mod ids, main one first
    ids: Vec<String>,
    /// ids it also stands in for (provides, jar-in-jar)
    provides: Vec<String>,
    /// required mod ids
    deps: Vec<String>,
    /// Fabric/Quilt game-version predicates (any may match)
    mc: Vec<String>,
    /// Forge/NeoForge maven range for minecraft
    mc_range: Option<String>,
}

/// Loader-provided ids that are never separate jars.
const BUILTIN: &[&str] = &["minecraft", "java", "fabricloader", "fabric-loader", "quilt_loader", "forge", "neoforge", "fml", "javafml", "lowcodefml", "mcp"];

const MAX_ROWS: usize = 12;

fn entry<R: Read + Seek>(z: &mut zip::ZipArchive<R>, name: &str) -> Option<Vec<u8>> {
    let mut f = z.by_name(name).ok()?;
    if f.size() > 16 << 20 { return None; }
    let mut b = Vec::with_capacity(f.size() as usize);
    f.read_to_end(&mut b).ok()?;
    Some(b)
}

/// JSON as mods write it: raw line breaks/tabs inside strings are common, so they become spaces first.
fn lenient_json(b: &[u8]) -> Option<serde_json::Value> {
    let clean: Vec<u8> = b.iter().map(|c| if matches!(c, b'\n' | b'\r' | b'\t') { b' ' } else { *c }).collect();
    let s = String::from_utf8_lossy(&clean);
    serde_json::from_str(s.trim_start_matches('\u{feff}')).ok()
}

fn strs(v: &serde_json::Value) -> Vec<String> {
    match v {
        serde_json::Value::String(s) => vec![s.clone()],
        serde_json::Value::Array(a) => a.iter().filter_map(|x| x.as_str().map(String::from).or_else(|| x["id"].as_str().map(String::from))).collect(),
        _ => vec![],
    }
}

/// Read a mod's metadata (and that of jars nested in it, as "provides").
fn meta_of<R: Read + Seek>(z: &mut zip::ZipArchive<R>, depth: u32) -> Meta {
    let mut m = Meta::default();
    let mut nested: Vec<String> = vec![];
    if let Some(j) = entry(z, "fabric.mod.json").and_then(|b| lenient_json(&b)) {
        m.fabric = true;
        if let Some(id) = j["id"].as_str() { m.ids.push(id.to_string()); }
        m.name = j["name"].as_str().unwrap_or_default().to_string();
        m.provides.extend(strs(&j["provides"]));
        if let Some(d) = j["depends"].as_object() {
            for (k, v) in d {
                if k == "minecraft" { m.mc = strs(v); } else { m.deps.push(k.clone()); }
            }
        }
        nested.extend(j["jars"].as_array().into_iter().flatten().filter_map(|x| x["file"].as_str().map(String::from)));
    }
    if let Some(j) = entry(z, "quilt.mod.json").and_then(|b| lenient_json(&b)) {
        m.quilt = true;
        let q = &j["quilt_loader"];
        if let Some(id) = q["id"].as_str() { if !m.ids.iter().any(|x| x == id) { m.ids.push(id.to_string()); } }
        if m.name.is_empty() { m.name = q["metadata"]["name"].as_str().unwrap_or_default().to_string(); }
        m.provides.extend(strs(&q["provides"]));
        if !m.fabric {
            for d in q["depends"].as_array().into_iter().flatten() {
                let (id, optional) = match d { serde_json::Value::String(s) => (s.clone(), false), o => (o["id"].as_str().unwrap_or_default().to_string(), o["optional"].as_bool().unwrap_or(false)) };
                if id.is_empty() || optional { continue; }
                if id == "minecraft" { m.mc = strs(&d["versions"]); } else { m.deps.push(id); }
            }
        }
        nested.extend(q["jars"].as_array().into_iter().flatten().filter_map(|x| x.as_str().map(String::from)));
    }
    for (file, neo) in [("META-INF/neoforge.mods.toml", true), ("META-INF/mods.toml", false)] {
        let Some(b) = entry(z, file) else { continue };
        if neo { m.neoforge = true } else { m.forge = true }
        let t = mods_toml(&String::from_utf8_lossy(&b));
        for (id, name) in t.mods {
            if m.name.is_empty() { m.name = name; }
            if !m.ids.contains(&id) { m.ids.push(id); }
        }
        if !m.fabric && !m.quilt { m.deps.extend(t.deps); if m.mc_range.is_none() { m.mc_range = t.mc; } }
    }
    if !m.forge && !m.neoforge {
        if let Some(j) = entry(z, "mcmod.info").and_then(|b| lenient_json(&b)) {
            m.forge = true;
            let list = if j.is_array() { j.clone() } else { j["modList"].clone() };
            for x in list.as_array().into_iter().flatten() {
                if let Some(id) = x["modid"].as_str() { m.ids.push(id.to_string()); }
                if m.name.is_empty() { m.name = x["name"].as_str().unwrap_or_default().to_string(); }
            }
        }
    }
    if let Some(j) = entry(z, "META-INF/jarjar/metadata.json").and_then(|b| lenient_json(&b)) {
        nested.extend(j["jars"].as_array().into_iter().flatten().filter_map(|x| x["path"].as_str().map(String::from)));
    }
    if depth < 2 {
        for n in nested {
            let Some(b) = entry(z, &n) else { continue };
            let Ok(mut inner) = zip::ZipArchive::new(std::io::Cursor::new(b)) else { continue };
            let im = meta_of(&mut inner, depth + 1);
            m.provides.extend(im.ids);
            m.provides.extend(im.provides);
        }
    }
    m.deps.retain(|d| !BUILTIN.contains(&d.as_str()));
    m.deps.sort();
    m.deps.dedup();
    m
}

struct Toml { mods: Vec<(String, String)>, deps: Vec<String>, mc: Option<String> }

/// The parts of a (neoforge.)mods.toml we need: [[mods]] ids/names, required [[dependencies.x]] and the
/// minecraft range. A line reader is enough for these flat tables.
fn mods_toml(s: &str) -> Toml {
    let mut tables: Vec<(bool, HashMap<String, String>)> = vec![];
    let mut cur: Option<(bool, HashMap<String, String>)> = None;
    let mut in_multi = false;
    for raw in s.lines() {
        let l = raw.trim();
        if in_multi { if l.contains("'''") || l.contains("\"\"\"") { in_multi = false; } continue; }
        if l.is_empty() || l.starts_with('#') { continue; }
        if l.starts_with('[') {
            if let Some(t) = cur.take() { tables.push(t); }
            let name = l.trim_matches(|c| c == '[' || c == ']').trim();
            cur = if name == "mods" { Some((true, HashMap::new())) } else if name.starts_with("dependencies.") { Some((false, HashMap::new())) } else { None };
            continue;
        }
        let Some((k, v)) = l.split_once('=') else { continue };
        let v = v.trim();
        if v.starts_with("'''") || v.starts_with("\"\"\"") { if !v[3..].contains("'''") && !v[3..].contains("\"\"\"") { in_multi = true; } continue; }
        let val = if let Some(r) = v.strip_prefix('"') { r.split('"').next().unwrap_or("") } else if let Some(r) = v.strip_prefix('\'') { r.split('\'').next().unwrap_or("") } else { v.split('#').next().unwrap_or("").trim() };
        if let Some((_, t)) = cur.as_mut() { t.insert(k.trim().to_lowercase(), val.to_string()); }
    }
    if let Some(t) = cur.take() { tables.push(t); }
    let mut out = Toml { mods: vec![], deps: vec![], mc: None };
    for (is_mod, t) in tables {
        let id = t.get("modid").cloned().unwrap_or_default();
        if id.is_empty() || id.contains("${") { continue; }
        if is_mod { out.mods.push((id, t.get("displayname").cloned().unwrap_or_default())); continue; }
        if id == "minecraft" { out.mc = t.get("versionrange").cloned().filter(|r| !r.contains("${")); continue; }
        let required = t.get("mandatory").is_some_and(|v| v == "true") || t.get("type").is_some_and(|v| v.eq_ignore_ascii_case("required"));
        let server_only = t.get("side").is_some_and(|v| v.eq_ignore_ascii_case("server"));
        if required && !server_only { out.deps.push(id); }
    }
    out
}

/// The mod's own display name from its jar metadata (cached), if it declares one.
pub fn mod_name(p: &Path) -> Option<String> { read_meta(p).map(|m| m.name).filter(|n| !n.trim().is_empty()) }

/// Metadata of a jar, cached by (path, size, modified) for the launcher's lifetime.
fn read_meta(p: &Path) -> Option<Meta> {
    type Cache = Mutex<HashMap<(PathBuf, u64, std::time::SystemTime), Option<Meta>>>;
    static CACHE: std::sync::OnceLock<Cache> = std::sync::OnceLock::new();
    let md = fs::metadata(p).ok()?;
    let key = (p.to_path_buf(), md.len(), md.modified().ok()?);
    let cache = CACHE.get_or_init(Default::default);
    if let Some(m) = cache.lock().unwrap_or_else(|e| e.into_inner()).get(&key) { return m.clone(); }
    let m = fs::File::open(p).ok().and_then(|f| zip::ZipArchive::new(std::io::BufReader::new(f)).ok()).map(|mut z| meta_of(&mut z, 0));
    cache.lock().unwrap_or_else(|e| e.into_inner()).insert(key, m.clone());
    m
}

// ---- game version predicates ----

/// "1.21.11" -> [1, 21, 11]; None for snapshots and other non-release ids.
fn nums(v: &str) -> Option<Vec<u32>> {
    let v = v.split(['-', '+']).next()?.trim();
    if v.is_empty() { return None; }
    v.split('.').map(|p| p.parse().ok()).collect()
}

fn cmp(a: &[u32], b: &[u32]) -> std::cmp::Ordering {
    let n = a.len().max(b.len());
    (0..n).map(|i| a.get(i).unwrap_or(&0).cmp(b.get(i).unwrap_or(&0))).find(|o| o.is_ne()).unwrap_or(std::cmp::Ordering::Equal)
}

/// One Fabric version predicate (">=1.20 <1.21", "~1.20.1", "1.21.x", "*") against `ver`. None = can't tell.
fn fabric_one(pred: &str, ver: &[u32]) -> Option<bool> {
    let mut all = true;
    for term in pred.split_whitespace() {
        if term == "*" { continue; }
        let (op, rest) = [">=", "<=", ">", "<", "=", "~", "^"].iter().find_map(|o| term.strip_prefix(o).map(|r| (*o, r))).unwrap_or(("", term));
        let rest = rest.trim();
        // wildcards: 1.20.x / 1.20.*
        let parts: Vec<&str> = rest.split(['-', '+']).next()?.split('.').collect();
        if let Some(w) = parts.iter().position(|p| matches!(*p, "x" | "X" | "*")) {
            let pre: Option<Vec<u32>> = parts[..w].iter().map(|p| p.parse().ok()).collect();
            let pre = pre?;
            if ver.len() < pre.len() || ver[..pre.len()] != pre[..] { all = false; }
            continue;
        }
        let v = nums(rest)?;
        use std::cmp::Ordering::*;
        let ok = match op {
            ">=" => cmp(ver, &v) != Less,
            "<=" => cmp(ver, &v) != Greater,
            ">" => cmp(ver, &v) == Greater,
            "<" => cmp(ver, &v) == Less,
            // ~1.20.1 / ~1.20 = same minor version; ~1 = same major
            "~" => { let up = if v.len() >= 2 { vec![v[0], v[1] + 1] } else { vec![v[0] + 1] }; cmp(ver, &v) != Less && cmp(ver, &up) == Less }
            "^" => cmp(ver, &v) != Less && ver.first() == v.first(),
            _ => cmp(ver, &v) == Equal,
        };
        if !ok { all = false; }
    }
    Some(all)
}

/// Any of the predicates (each may hold "||" alternatives) matches `version`. None = can't tell.
fn fabric_ok(preds: &[String], version: &str) -> Option<bool> {
    let ver = nums(version)?;
    let alts: Vec<&str> = preds.iter().flat_map(|p| p.split("||")).map(str::trim).collect();
    if alts.is_empty() { return None; }
    let res: Vec<Option<bool>> = alts.iter().map(|p| fabric_one(p, &ver)).collect();
    if res.contains(&Some(true)) { return Some(true); }
    if res.iter().all(|r| *r == Some(false)) { Some(false) } else { None }
}

/// Maven range ("[1.20.1,1.21)", "[1.20.1]", several sets) against `version`. A bare version is a soft
/// requirement (always fine). None = can't tell.
fn maven_ok(range: &str, version: &str) -> Option<bool> {
    let ver = nums(version)?;
    let r = range.trim();
    if r.is_empty() || !(r.starts_with('[') || r.starts_with('(')) { return Some(true); }
    let mut any = false;
    let mut rest = r;
    while let Some(start) = rest.find(['[', '(']) {
        let end = rest[start..].find([']', ')'])? + start;
        let (open, close) = (&rest[start..start + 1], &rest[end..end + 1]);
        let body = &rest[start + 1..end];
        let (lo, hi) = match body.split_once(',') { Some((a, b)) => (a.trim(), b.trim()), None => (body.trim(), body.trim()) };
        use std::cmp::Ordering::*;
        let lo_ok = lo.is_empty() || { let l = nums(lo)?; let c = cmp(&ver, &l); c == Greater || (c == Equal && open == "[") };
        let hi_ok = hi.is_empty() || { let h = nums(hi)?; let c = cmp(&ver, &h); c == Less || (c == Equal && close == "]") };
        if lo_ok && hi_ok { any = true; }
        rest = &rest[end + 1..];
    }
    Some(any)
}

// ---- rules ----

/// Java major a Minecraft version needs: from the installed version JSON, else by release.
fn java_needed(paths: &Paths, version: &str) -> Option<u32> {
    let f = paths.versions().join(version).join(format!("{version}.json"));
    if let Some(m) = crate::store::peek_json::<serde_json::Value>(&f).and_then(|j| j["javaVersion"]["majorVersion"].as_u64()) { return Some(m as u32); }
    let v = nums(version)?;
    Some(match (v.first().copied()?, v.get(1).copied().unwrap_or(0), v.get(2).copied().unwrap_or(0)) {
        (1, m, _) if m <= 16 => 8,
        (1, 17, _) => 16,
        (1, m, p) if m < 20 || (m == 20 && p <= 4) => 17,
        (1, _, _) => 21,
        _ => 25,
    })
}

/// Modrinth slug for a missing mod id (most match; a few well-known ones differ).
pub fn slug_for(id: &str) -> String {
    match id { "fabric" => "fabric-api".into(), "yet_another_config_lib_v3" | "yet-another-config-lib" => "yacl".into(), _ => id.replace('_', "-") }
}

fn fname(p: &Path) -> String { p.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string() }

fn label(m: &Meta, p: &Path) -> String {
    if !m.name.trim().is_empty() && !m.name.contains("${") { m.name.trim().to_string() } else { fname(p).trim_end_matches(".jar").to_string() }
}

/// Everything the check finds for `t`, most serious first (at most 12 rows).
pub fn check(paths: &Paths, t: &Target) -> Vec<Finding> {
    let mut out: Vec<Finding> = vec![];
    let f = |id: &'static str, title: String, explanation: String, fix: String, action: Option<Action>| Finding { id, title, explanation, fix, action };

    // Java
    if t.java > 0 {
        if let Some(need) = java_needed(paths, t.version) {
            if t.java < need {
                out.push(f("java-version", format!("Java {} is too old for Minecraft {}", t.java, t.version),
                    format!("Minecraft {} needs Java {need} or newer. With Java {} it stops right away.", t.version, t.java),
                    format!("Let the launcher pick Java. It downloads Java {need} if it is missing."), Some(Action::AutoJava)));
            } else if t.loader == Loader::Forge && need == 8 && t.java > 8 {
                out.push(f("java-too-new", format!("Java {} is too new for Forge {}", t.java, t.version),
                    format!("Forge for Minecraft {} only runs on Java 8.", t.version),
                    "Let the launcher pick Java (Java 8 for this version).".into(), Some(Action::AutoJava)));
            }
        }
    }

    // memory
    let total = crate::instances::system_ram_mb();
    let alloc = t.ram_gb as u64 * 1024;
    if total > 0 && alloc * 4 > total * 3 {
        let (tg, cap) = ((total as f64 / 1024.0).round() as u32, ((total * 3 / 4) / 1024).max(2) as u32);
        if cap < t.ram_gb {
            let (title, why) = if alloc >= total {
                ("More memory than this PC has".to_string(), format!("{} is set to {} GB, but this PC has {tg} GB in total, so Java can't start the game.", t.name, t.ram_gb))
            } else {
                ("Too little memory left for Windows".to_string(), format!("{} of this PC's {tg} GB go to the game. Windows and other apps need the rest, or the game stutters and can crash.", t.ram_gb))
            };
            out.push(f("ram", title, why, format!("Use {cap} GB. 4-8 GB suits most modpacks."), Some(Action::SetRam(cap))));
        }
    }

    if t.loader == Loader::Vanilla { out.truncate(MAX_ROWS); return out; }
    let jars: Vec<(PathBuf, Meta)> = crate::instances::mod_files(paths, t.name).into_iter()
        .filter(|p| p.extension().is_some_and(|e| e == "jar"))
        .filter_map(|p| read_meta(&p).map(|m| (p, m)))
        .collect();
    let connector = jars.iter().any(|(_, m)| m.ids.iter().any(|i| i == "connector"));
    let fits = |m: &Meta| -> Option<bool> {
        if !(m.fabric || m.quilt || m.forge || m.neoforge) { return None; }
        Some(match t.loader {
            Loader::Fabric | Loader::LegacyFabric => m.fabric,
            Loader::Quilt => m.fabric || m.quilt,
            Loader::Forge => m.forge,
            Loader::NeoForge => m.neoforge || m.forge || (connector && m.fabric),
            Loader::Vanilla => true,
        })
    };
    let made_for = |m: &Meta| if m.neoforge && !m.forge { "NeoForge" } else if m.forge { "Forge" } else if m.quilt && !m.fabric { "Quilt" } else { "Fabric" };
    let mut active: Vec<&(PathBuf, Meta)> = vec![];
    for j in &jars {
        let (p, m) = j;
        match fits(m) {
            Some(false) => out.push(f("wrong-loader", format!("{} is a {} mod", label(m, p), made_for(m)),
                format!("{} is built for {}, and this instance runs {}. Mods only load on the loader they were made for.", fname(p), made_for(m), t.loader.label()),
                "Disable it, then get its build for this loader from the Mods tab.".into(), Some(Action::Disable(p.clone())))),
            Some(true) => {
                let mc_ok = if !m.mc.is_empty() { fabric_ok(&m.mc, t.version) } else { m.mc_range.as_deref().and_then(|r| maven_ok(r, t.version)) };
                if mc_ok == Some(false) {
                    let want = if !m.mc.is_empty() { m.mc.join(" or ") } else { m.mc_range.clone().unwrap_or_default() };
                    out.push(f("wrong-minecraft-version", format!("{} is for another Minecraft version", label(m, p)),
                        format!("{} says it needs Minecraft {want}; this instance is {}.", fname(p), t.version),
                        "Disable it, or update it from the Mods tab to the build for this version.".into(), Some(Action::Disable(p.clone()))));
                } else {
                    active.push(j);
                }
            }
            None => {}
        }
    }

    // the same mod twice: keep the newest file
    let mut by_id: HashMap<&str, Vec<&(PathBuf, Meta)>> = HashMap::new();
    for j in &active { if let Some(id) = j.1.ids.first() { by_id.entry(id.as_str()).or_default().push(j); } }
    let mut dups: Vec<_> = by_id.into_iter().filter(|(_, v)| v.len() > 1).collect();
    dups.sort_by_key(|(id, _)| id.to_string());
    let mut gone: HashSet<&Path> = HashSet::new();
    for (id, mut v) in dups {
        let t_of = |p: &Path| fs::metadata(p).and_then(|m| m.modified()).ok();
        v.sort_by_key(|(p, _)| t_of(p));
        let (old, new) = (&v[0].0, &v[v.len() - 1].0);
        gone.insert(old.as_path());
        out.push(f("duplicate-mods", format!("{} is installed twice", label(&v[0].1, old)),
            format!("{} and {} both contain the mod \"{id}\". The game refuses to start with two copies.", fname(old), fname(new)),
            format!("Disable the older copy ({}).", fname(old)), Some(Action::Disable(old.clone()))));
    }

    // missing required mods
    let mut have: HashSet<String> = BUILTIN.iter().map(|s| s.to_string()).collect();
    // MixinExtras ships inside Fabric Loader 0.15+ (we always install a newer one), Quilt Loader and NeoForge
    if matches!(t.loader, Loader::Fabric | Loader::Quilt | Loader::LegacyFabric | Loader::NeoForge) { have.insert("mixinextras".into()); }
    for (_, m) in active.iter().filter(|(p, _)| !gone.contains(p.as_path())) { have.extend(m.ids.iter().cloned()); have.extend(m.provides.iter().cloned()); }
    let fapi = have.contains("fabric-api") || have.contains("fabric");
    let mut missing: Vec<(String, Vec<String>)> = vec![];
    for (p, m) in &active {
        for d in &m.deps {
            if have.contains(d) || (fapi && d.starts_with("fabric-")) { continue; }
            match missing.iter_mut().find(|(id, _)| id == d) { Some((_, who)) => who.push(label(m, p)), None => missing.push((d.clone(), vec![label(m, p)])) }
        }
    }
    for (dep, who) in missing {
        let names = if who.len() > 2 { format!("{}, {} and {} more", who[0], who[1], who.len() - 2) } else { who.join(" and ") };
        out.push(f("missing-dependency", format!("{dep} is missing"),
            format!("{names} need{} \"{dep}\", which is not installed. The game stops while loading without it.", if who.len() == 1 { "s" } else { "" }),
            format!("Install {dep} from Modrinth (the build for {} {}).", t.loader.label(), t.version), Some(Action::Install(slug_for(&dep)))));
    }
    out.truncate(MAX_ROWS);
    out
}

/// Stable key of a finding, for "don't warn me about this again".
pub fn key(f: &Finding) -> String { format!("{}|{}", f.id, f.title) }

fn ignore_file(paths: &Paths, instance: &str) -> PathBuf { paths.instance(instance).join(".nether").join("preflight.json") }

/// Findings the user has not chosen to launch past before.
pub fn pending(paths: &Paths, t: &Target) -> Vec<Finding> {
    let ignored: HashSet<String> = crate::store::peek_json::<Vec<String>>(&ignore_file(paths, t.name)).unwrap_or_default().into_iter().collect();
    check(paths, t).into_iter().filter(|f| !ignored.contains(&key(f))).collect()
}

/// "Launch anyway": these findings stay quiet for this instance from now on.
pub fn ignore(paths: &Paths, instance: &str, keys: &[String]) -> anyhow::Result<()> {
    let file = ignore_file(paths, instance);
    let mut v: Vec<String> = crate::store::peek_json(&file).unwrap_or_default();
    for k in keys { if !v.contains(k) { v.push(k.clone()); } }
    crate::store::write_json(&file, &v)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn jar(dir: &Path, name: &str, files: &[(&str, &[u8])]) -> PathBuf {
        let p = dir.join(name);
        let mut z = zip::ZipWriter::new(fs::File::create(&p).unwrap());
        for (n, b) in files { z.start_file(*n, zip::write::SimpleFileOptions::default()).unwrap(); z.write_all(b).unwrap(); }
        z.finish().unwrap();
        p
    }

    fn nested(files: &[(&str, &[u8])]) -> Vec<u8> {
        let mut z = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        for (n, b) in files { z.start_file(*n, zip::write::SimpleFileOptions::default()).unwrap(); z.write_all(b).unwrap(); }
        z.finish().unwrap().into_inner()
    }

    fn ids(v: &[Finding]) -> Vec<&'static str> { v.iter().map(|f| f.id).collect() }

    #[test]
    fn version_predicates() {
        let t = |p: &str, v: &str| fabric_ok(&[p.to_string()], v);
        assert_eq!(t(">=1.20 <1.21", "1.20.4"), Some(true));
        assert_eq!(t(">=1.20 <1.21", "1.21.1"), Some(false));
        assert_eq!(t("~1.20.1", "1.20.6"), Some(true));
        assert_eq!(t("~1.20.1", "1.21"), Some(false));
        assert_eq!(t("1.21.x", "1.21.11"), Some(true));
        assert_eq!(t("1.21.x", "1.20.1"), Some(false));
        assert_eq!(t("1.21", "1.21.0"), Some(true));
        assert_eq!(t(">=1.21.2-", "1.21.11"), Some(true));
        assert_eq!(t("*", "1.8.9"), Some(true));
        assert_eq!(t("^1.20", "1.21.4"), Some(true));
        assert_eq!(t(">=1.21", "25w14a"), None);
        assert_eq!(fabric_ok(&["1.20.1".into(), "1.21.1".into()], "1.21.1"), Some(true));
        assert_eq!(maven_ok("[1.20.1,1.21)", "1.20.1"), Some(true));
        assert_eq!(maven_ok("[1.20.1,1.21)", "1.21"), Some(false));
        assert_eq!(maven_ok("[1.21.1]", "1.21.1"), Some(true));
        assert_eq!(maven_ok("[1.18,1.19),[1.20,1.21)", "1.20.4"), Some(true));
        assert_eq!(maven_ok("[1.19,)", "1.8.9"), Some(false));
        assert_eq!(maven_ok("1.20.1", "1.21"), Some(true));
    }

    #[test]
    fn toml_reader() {
        let t = mods_toml("modLoader=\"javafml\"\n[[mods]]\nmodId=\"jei\" # id\ndisplayName='Just Enough Items'\ndescription='''\nline [x]\n'''\n[[dependencies.jei]]\n    modId=\"forge\"\n    mandatory=true\n[[dependencies.jei]]\n    modId=\"minecraft\"\n    versionRange=\"[1.20.1,1.21)\"\n[[dependencies.jei]]\n    modId=\"geckolib\"\n    type=\"required\"\n    side=\"BOTH\"\n[[dependencies.jei]]\n    modId=\"curios\"\n    type=\"optional\"\n");
        assert_eq!(t.mods, vec![("jei".to_string(), "Just Enough Items".to_string())]);
        assert_eq!(t.deps, vec!["forge".to_string(), "geckolib".to_string()]);
        assert_eq!(t.mc.as_deref(), Some("[1.20.1,1.21)"));
    }

    #[test]
    fn finds_problems_and_stays_quiet_when_clean() {
        let root = crate::modpack::tmpdir("preflight");
        let paths = Paths { root: root.clone() };
        let mods = paths.instance("t").join("mods");
        fs::create_dir_all(&mods).unwrap();
        let tgt = |java| Target { name: "t", version: "1.21.1", loader: Loader::Fabric, java, ram_gb: 2 };
        // clean: fabric api (nested module) + a mod depending on a module and on fabric-api
        let inner = nested(&[("fabric.mod.json", br#"{"id":"fabric-rendering-v1"}"#)]);
        jar(&mods, "fabric-api.jar", &[("fabric.mod.json", br#"{"id":"fabric-api","provides":["fabric"],"jars":[{"file":"META-INF/jars/r.jar"}]}"#), ("META-INF/jars/r.jar", &inner)]);
        jar(&mods, "sodium.jar", &[("fabric.mod.json", b"{\"id\":\"sodium\",\"name\":\"Sodium\",\"description\":\"two\nlines\",\"depends\":{\"minecraft\":\">=1.21 <1.22\",\"fabric-rendering-v1\":\"*\",\"fabricloader\":\">=0.15\",\"fabric\":\"*\"}}")]);
        jar(&mods, "lithium.jar", &[("fabric.mod.json", br#"{"id":"lithium","name":"Lithium","depends":{"mixinextras":">=0.3"}}"#)]); // bundled in the loader
        assert!(check(&paths, &tgt(0)).is_empty(), "{:?}", check(&paths, &tgt(0)));

        // duplicate, missing dependency, wrong loader, wrong minecraft, old Java
        jar(&mods, "sodium-old.jar", &[("fabric.mod.json", br#"{"id":"sodium","name":"Sodium"}"#)]);
        jar(&mods, "extra.jar", &[("fabric.mod.json", br#"{"id":"extra","name":"Extra","depends":{"cloth_config":"*"}}"#)]);
        jar(&mods, "jei-forge.jar", &[("META-INF/mods.toml", b"[[mods]]\nmodId=\"jei\"\ndisplayName=\"JEI\"\n")]);
        jar(&mods, "old.jar", &[("fabric.mod.json", br#"{"id":"old","name":"Old","depends":{"minecraft":"1.20.1"}}"#)]);
        fs::write(mods.join("notamod.jar"), b"garbage").unwrap();
        let f = check(&paths, &tgt(17));
        let got = ids(&f);
        for want in ["java-version", "duplicate-mods", "missing-dependency", "wrong-loader", "wrong-minecraft-version"] { assert!(got.contains(&want), "{want} not in {got:?}"); }
        let miss = f.iter().find(|x| x.id == "missing-dependency").unwrap();
        assert_eq!(miss.action, Some(Action::Install("cloth-config".into())));
        assert!(matches!(&f.iter().find(|x| x.id == "wrong-loader").unwrap().action, Some(Action::Disable(p)) if p.ends_with("jei-forge.jar")));

        // launching past them keeps them quiet
        ignore(&paths, "t", &f.iter().map(key).collect::<Vec<_>>()).unwrap();
        assert!(pending(&paths, &tgt(17)).is_empty());
        let _ = fs::remove_dir_all(root);
    }
}
