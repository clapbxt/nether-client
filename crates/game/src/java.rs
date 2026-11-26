use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{anyhow, Result};
use rayon::prelude::*;
use reqwest::blocking::Client;
use serde::Deserialize;
use std::collections::HashMap;
use std::sync::atomic::AtomicBool;

use crate::download::{fetch, get_json, Check};
use crate::paths::Paths;
use crate::Progress;

const RUNTIME_INDEX: &str = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

fn exe(home: &Path) -> Option<PathBuf> {
    for n in ["javaw.exe", "java.exe"] {
        let p = home.join("bin").join(n);
        if p.exists() { return Some(p); }
    }
    None
}

/// Major version from a JDK/JRE home's `release` file.
fn major_of(home: &Path) -> Option<u32> {
    let rel = fs::read_to_string(home.join("release")).ok()?;
    let v = rel.lines().find_map(|l| l.strip_prefix("JAVA_VERSION="))?.trim_matches('"').to_string();
    let mut parts = v.split(['.', '_']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 { parts.next()?.parse().ok() } else { Some(first) }
}

fn system_candidates() -> Vec<PathBuf> {
    let mut out = vec![];
    if let Some(j) = std::env::var_os("JAVA_HOME") { out.push(PathBuf::from(j)); }
    for base in ["C:\\Program Files\\Java", "C:\\Program Files\\Eclipse Adoptium", "C:\\Program Files\\Microsoft", "C:\\Program Files\\Zulu", "C:\\Program Files\\BellSoft"] {
        if let Ok(rd) = fs::read_dir(base) {
            for e in rd.flatten() { out.push(e.path()); }
        }
    }
    out
}

#[derive(Deserialize)]
struct RuntimeIndex(HashMap<String, HashMap<String, Vec<RuntimeEntry>>>);
#[derive(Deserialize)]
struct RuntimeEntry { manifest: RuntimeRef }
#[derive(Deserialize)]
struct RuntimeRef { url: String }
#[derive(Deserialize)]
struct RuntimeManifest { files: HashMap<String, RuntimeFile> }
#[derive(Deserialize)]
struct RuntimeFile {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    downloads: Option<RuntimeDownloads>,
}
#[derive(Deserialize)]
struct RuntimeDownloads { raw: RawFile }
#[derive(Deserialize)]
struct RawFile { url: String, sha1: String }

/// Mojang runtime component for a Java major version.
pub fn component(major: u32) -> &'static str {
    match major { 8 => "jre-legacy", 17 => "java-runtime-gamma", 21 => "java-runtime-delta", _ => "java-runtime-epsilon" }
}

/// The java(w).exe the user picked for `major` (config.json "java_paths"), while it still exists.
pub fn custom(paths: &Paths, major: u32) -> Option<PathBuf> {
    crate::store::config(paths)["java_paths"][major.to_string()].as_str().map(PathBuf::from).filter(|p| p.is_file())
}

/// Pick (`Some`) or forget (`None`) the Java used for `major`.
pub fn set_custom(paths: &Paths, major: u32, exe: Option<&Path>) -> Result<()> {
    crate::store::update_config(paths, |o| {
        let jp = o.entry("java_paths").or_insert_with(|| serde_json::json!({}));
        if !jp.is_object() { *jp = serde_json::json!({}); }
        let m = jp.as_object_mut().expect("object");
        match exe {
            Some(e) => { m.insert(major.to_string(), serde_json::Value::String(e.display().to_string())); }
            None => { m.remove(&major.to_string()); }
        }
    })
}

/// Marker written once every file of a downloaded runtime is in place (a cut-off download has none).
const COMPLETE: &str = ".nether-complete";

/// A runtime we downloaded, only when its download finished.
fn ours_ready(home: &Path) -> Option<PathBuf> {
    if home.join(COMPLETE).is_file() { exe(home) } else { None }
}

/// Major version of a java(w).exe, read from its home's `release` file (None when that file is missing).
pub fn exe_major(exe: &Path) -> Option<u32> { major_of(exe.parent()?.parent()?) }

/// Which Java a launch of `major` would use right now, without downloading anything.
#[derive(Clone, Debug, PartialEq)]
pub enum Source { Custom(PathBuf), Downloaded(PathBuf), System(PathBuf), Missing }

pub fn which(paths: &Paths, major: u32) -> Source {
    if let Some(e) = custom(paths, major) { return Source::Custom(e); }
    if let Some(e) = ours_ready(&paths.runtimes().join(component(major))) { return Source::Downloaded(e); }
    for home in system_candidates() {
        if major_of(&home) == Some(major) { if let Some(e) = exe(&home) { return Source::System(e); } }
    }
    Source::Missing
}

/// Find a Java matching `major`, downloading Mojang's bundled runtime `component` if none is installed.
/// A runtime whose download was cut off is completed (files already there are hash-checked and kept).
pub fn find_or_install(c: &Client, paths: &Paths, component: &str, major: u32, progress: &Progress, cancel: Option<&AtomicBool>) -> Result<PathBuf> {
    // 0) the one the user picked in Settings
    if let Some(e) = custom(paths, major) { return Ok(e); }
    // 1) a runtime we downloaded earlier (and finished)
    let ours = paths.runtimes().join(component);
    if let Some(e) = ours_ready(&ours) { return Ok(e); }
    // 2) an installed JDK/JRE with the exact major version
    for home in system_candidates() {
        if major_of(&home) == Some(major) {
            if let Some(e) = exe(&home) { return Ok(e); }
        }
    }
    // 3) Mojang's runtime for this component
    progress(&format!("Downloading Java {major}"), 0.0);
    let man = (|| -> Result<RuntimeManifest> {
        let idx: RuntimeIndex = get_json(c, RUNTIME_INDEX)?;
        let entry = idx.0.get("windows-x64").and_then(|m| m.get(component)).and_then(|v| v.first())
            .ok_or_else(|| anyhow!("no Mojang runtime '{component}' for windows-x64"))?;
        get_json(c, &entry.manifest.url)
    })();
    let man = match man {
        Ok(m) => m,
        // offline with a runtime downloaded before completion markers existed: use it rather than fail
        Err(_) if exe(&ours).is_some() && ours.join("lib").is_dir() => return exe(&ours).ok_or_else(|| anyhow!("no java executable")),
        Err(e) => return Err(e),
    };
    let files: Vec<(&String, &RuntimeFile)> = man.files.iter().filter(|(_, f)| f.kind == "file").collect();
    for (p, f) in &man.files { if f.kind == "directory" { fs::create_dir_all(ours.join(p))?; } }
    let total = files.len().max(1);
    let done = std::sync::atomic::AtomicUsize::new(0);
    let pool = rayon::ThreadPoolBuilder::new().num_threads(16).build()?;
    pool.install(|| {
        files.par_iter().try_for_each(|(p, f)| -> Result<()> {
            crate::download::check_cancel(cancel)?;
            let raw = &f.downloads.as_ref().ok_or_else(|| anyhow!("runtime file without download"))?.raw;
            fetch(c, &raw.url, &ours.join(p), Check::Sha1(&raw.sha1))?;
            let n = done.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
            if n.is_multiple_of(20) { progress(&format!("Downloading Java {major}"), n as f32 / total as f32); }
            Ok(())
        })
    })?;
    let e = exe(&ours).ok_or_else(|| anyhow!("downloaded runtime has no java executable"))?;
    fs::write(ours.join(COMPLETE), component)?;
    Ok(e)
}
