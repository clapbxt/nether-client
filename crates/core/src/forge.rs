//! Forge and NeoForge. We do what their installer does, without its GUI: read `install_profile.json` from the installer
//! jar, merge its version JSON into the vanilla one, download its libraries and run its processors (the steps that
//! build the patched client). Old Forge (installer with `versionInfo`, up to 1.12.2) only needs its universal jar.
use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{anyhow, bail, Context, Result};
use rayon::prelude::*;
use reqwest::blocking::Client;
use serde_json::Value;

use crate::download::{fetch, get_json, Check};
use crate::mojang::{Arguments, Library, VersionJson};
use crate::paths::Paths;
use crate::Progress;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Forge,
    NeoForge,
}

impl Kind {
    pub fn name(self) -> &'static str {
        match self { Kind::Forge => "Forge", Kind::NeoForge => "NeoForge" }
    }
}

const NEO_MAVEN: &str = "https://maven.neoforged.net/releases/net/neoforged/neoforge";
const FORGE_MAVEN: &str = "https://maven.minecraftforge.net/net/minecraftforge/forge";
const FORGE_PROMOS: &str = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";

/// NeoForge versions for a Minecraft version start with this (1.21.1 -> "21.1.", 1.21 -> "21.0.", 26.3 -> "26.3.0.").
fn neo_prefix(mc: &str) -> String {
    let p: Vec<&str> = mc.split('.').collect();
    if p[0] == "1" {
        format!("{}.{}.", p.get(1).unwrap_or(&"0"), p.get(2).unwrap_or(&"0"))
    } else if p.len() == 2 {
        format!("{mc}.0.")
    } else {
        format!("{mc}.")
    }
}

/// Newest loader version for a Minecraft version (stable preferred; Forge: recommended, else latest).
pub fn latest(c: &Client, kind: Kind, mc: &str) -> Result<String> {
    let none = || anyhow!("{} does not support Minecraft {mc}", kind.name());
    match kind {
        Kind::NeoForge => {
            let xml = c.get(format!("{NEO_MAVEN}/maven-metadata.xml")).send()?.error_for_status()?.text()?;
            let pre = neo_prefix(mc);
            let all: Vec<&str> = xml.split("<version>").skip(1).filter_map(|s| s.split('<').next()).filter(|v| v.starts_with(&pre)).collect();
            all.iter().rev().find(|v| !v.contains('-')).or(all.last()).map(|s| s.to_string()).ok_or_else(none)
        }
        Kind::Forge => {
            let p: Value = get_json(c, FORGE_PROMOS)?;
            let get = |k: &str| p["promos"][format!("{mc}-{k}")].as_str().map(String::from);
            get("recommended").or_else(|| get("latest")).ok_or_else(none)
        }
    }
}

/// `group:artifact:version[:classifier][@ext]` -> repository-relative path.
pub fn artifact_path(coord: &str) -> Result<String> {
    let (c, ext) = coord.split_once('@').unwrap_or((coord, "jar"));
    let p: Vec<&str> = c.split(':').collect();
    if p.len() < 3 { bail!("bad artifact '{coord}'"); }
    let cls = p.get(3).map(|c| format!("-{c}")).unwrap_or_default();
    Ok(format!("{}/{}/{}/{}-{}{cls}.{ext}", p[0].replace('.', "/"), p[1], p[2], p[1], p[2]))
}

fn entry(zip: &mut zip::ZipArchive<fs::File>, name: &str) -> Result<Vec<u8>> {
    let mut f = zip.by_name(name).with_context(|| format!("installer has no {name}"))?;
    let mut buf = vec![];
    f.read_to_end(&mut buf)?;
    Ok(buf)
}

fn write_new(dest: &Path, bytes: &[u8]) -> Result<()> {
    if dest.exists() { return Ok(()); }
    crate::store::write_atomic(dest, bytes)?;
    Ok(())
}

/// Processor steps still to run after the game files are in place.
pub struct Pending {
    kind: Kind,
    mc: String,
    installer: PathBuf,
    profile: Value,
    marker: PathBuf,
}

fn installer(c: &Client, paths: &Paths, kind: Kind, mc: &str, ver: &str) -> Result<PathBuf> {
    match kind {
        Kind::NeoForge => {
            let dest = paths.libraries().join(format!("net/neoforged/neoforge/{ver}/neoforge-{ver}-installer.jar"));
            fetch(c, &format!("{NEO_MAVEN}/{ver}/neoforge-{ver}-installer.jar"), &dest, Check::None)?;
            Ok(dest)
        }
        Kind::Forge => {
            // some old builds repeat the Minecraft version at the end (1.8.9-11.15.1.2318-1.8.9)
            let mut last = None;
            for full in [format!("{mc}-{ver}"), format!("{mc}-{ver}-{mc}")] {
                let dest = paths.libraries().join(format!("net/minecraftforge/forge/{full}/forge-{full}-installer.jar"));
                match fetch(c, &format!("{FORGE_MAVEN}/{full}/forge-{full}-installer.jar"), &dest, Check::None) {
                    Ok(()) => return Ok(dest),
                    Err(e) => last = Some(e),
                }
            }
            Err(last.unwrap())
        }
    }
}

/// Download the installer and merge the loader into `v`. `Pending` is returned while its processors have not run yet.
pub fn prepare(c: &Client, paths: &Paths, mut v: VersionJson, mc: &str, kind: Kind) -> Result<(VersionJson, Option<Pending>)> {
    let ver = latest(c, kind, mc)?;
    let installer = installer(c, paths, kind, mc, &ver)?;
    let mut zip = zip::ZipArchive::new(fs::File::open(&installer)?)?;
    let profile: Value = serde_json::from_slice(&entry(&mut zip, "install_profile.json")?)?;

    // jars shipped inside the installer
    for i in 0..zip.len() {
        let mut f = zip.by_index(i)?;
        let name = f.name().to_string();
        let Some(rel) = name.strip_prefix("maven/") else { continue };
        if f.is_dir() || rel.contains("..") { continue; }
        let mut buf = vec![];
        f.read_to_end(&mut buf)?;
        write_new(&paths.libraries().join(rel), &buf)?;
    }

    if profile.get("versionInfo").is_some() {
        let ins = &profile["install"];
        let (file, coord) = (ins["filePath"].as_str().unwrap_or_default(), ins["path"].as_str().unwrap_or_default());
        let bytes = entry(&mut zip, file)?;
        write_new(&paths.libraries().join(artifact_path(coord)?), &bytes)?;
        merge(&mut v, &profile["versionInfo"])?;
        return Ok((v, None));
    }

    let json = profile["json"].as_str().unwrap_or("/version.json").trim_start_matches('/').to_string();
    let vj: Value = serde_json::from_slice(&entry(&mut zip, &json)?)?;
    merge(&mut v, &vj)?;
    let marker = installer.with_extension("done");
    let pending = (!marker.exists()).then(|| Pending { kind, mc: mc.to_string(), installer, profile, marker });
    Ok((v, pending))
}

/// `group:artifact[:classifier]`, the identity a newer copy of a library replaces.
fn lib_key(name: &str) -> String {
    let n = name.split('@').next().unwrap_or(name);
    let p: Vec<&str> = n.split(':').collect();
    format!("{}:{}:{}", p.first().unwrap_or(&""), p.get(1).unwrap_or(&""), p.get(3).unwrap_or(&""))
}

fn merge(v: &mut VersionJson, j: &Value) -> Result<()> {
    if let Some(m) = j["mainClass"].as_str() { v.main_class = m.to_string(); }
    if let Some(a) = j.get("arguments") {
        let extra: Arguments = serde_json::from_value(a.clone())?;
        let base = v.arguments.get_or_insert_with(Default::default);
        base.jvm.extend(extra.jvm);
        base.game.extend(extra.game);
    }
    if let Some(m) = j["minecraftArguments"].as_str() { v.legacy_args = Some(m.to_string()); }
    let mut libs: Vec<Library> = vec![];
    for l in j["libraries"].as_array().map(|a| a.as_slice()).unwrap_or_default() {
        let mut l = l.clone();
        if l.get("downloads").is_none() {
            // old format: only a repository base (none = Mojang's)
            let url = match l["url"].as_str() {
                None => "https://libraries.minecraft.net/".to_string(),
                Some(u) if u.contains("minecraftforge") => "https://maven.minecraftforge.net/".to_string(),
                Some(u) => u.to_string(),
            };
            l["url"] = Value::String(url);
        }
        libs.push(serde_json::from_value(l)?);
    }
    let keys: HashSet<String> = libs.iter().map(|l| lib_key(&l.name)).collect();
    v.libraries.retain(|l| l.natives.is_some() || !keys.contains(&lib_key(&l.name)));
    v.libraries.extend(libs);
    Ok(())
}

fn main_class(jar: &Path) -> Result<String> {
    let mut zip = zip::ZipArchive::new(fs::File::open(jar)?)?;
    let mf = String::from_utf8_lossy(&entry(&mut zip, "META-INF/MANIFEST.MF")?).to_string();
    mf.lines().find_map(|l| l.strip_prefix("Main-Class:")).map(|s| s.trim().to_string()).ok_or_else(|| anyhow!("{} has no Main-Class", jar.display()))
}

impl Pending {
    /// Download the installer's tool libraries and run its client-side processors with `java`.
    pub fn run(&self, c: &Client, paths: &Paths, java: &Path, client_jar: &Path, progress: &Progress) -> Result<()> {
        let name = self.kind.name();
        let libdir = paths.libraries();
        progress(&format!("Installing {name}"), 0.9);
        let libs: Vec<&Value> = self.profile["libraries"].as_array().map(|a| a.iter().collect()).unwrap_or_default();
        let pool = rayon::ThreadPoolBuilder::new().num_threads(16).build()?;
        pool.install(|| {
            libs.par_iter().try_for_each(|l| -> Result<()> {
                let a = &l["downloads"]["artifact"];
                let path = match a["path"].as_str() { Some(p) => p.to_string(), None => artifact_path(l["name"].as_str().unwrap_or_default())? };
                let dest = libdir.join(path);
                match (a["url"].as_str().unwrap_or_default(), a["sha1"].as_str().unwrap_or_default()) {
                    ("", _) if dest.exists() => Ok(()),
                    ("", _) => bail!("{name} installer is missing {}", dest.display()),
                    (url, "") => fetch(c, url, &dest, Check::None),
                    (url, sha) => fetch(c, url, &dest, Check::Sha1(sha)),
                }
            })
        })?;

        let mut zip = zip::ZipArchive::new(fs::File::open(&self.installer)?)?;
        let tmp = self.installer.with_extension("tmp");
        fs::create_dir_all(&tmp)?;
        let mut data: HashMap<String, String> = HashMap::new();
        if let Some(d) = self.profile["data"].as_object() {
            for (k, val) in d {
                let Some(s) = val["client"].as_str() else { continue };
                let v = if let Some(a) = s.strip_prefix('[').and_then(|s| s.strip_suffix(']')) {
                    libdir.join(artifact_path(a)?).to_string_lossy().to_string()
                } else if let Some(lit) = s.strip_prefix('\'').and_then(|s| s.strip_suffix('\'')) {
                    lit.to_string()
                } else {
                    let rel = s.trim_start_matches('/');
                    let out = tmp.join(Path::new(rel).file_name().ok_or_else(|| anyhow!("bad installer data '{s}'"))?);
                    fs::write(&out, entry(&mut zip, rel)?)?;
                    out.to_string_lossy().to_string()
                };
                data.insert(k.clone(), v);
            }
        }
        let root = libdir.parent().unwrap_or(&libdir).to_path_buf();
        for (k, v) in [("MINECRAFT_JAR", client_jar.to_path_buf()), ("ROOT", root), ("INSTALLER", self.installer.clone()), ("LIBRARY_DIR", libdir.clone())] {
            data.insert(k.into(), v.to_string_lossy().to_string());
        }
        data.insert("SIDE".into(), "client".into());
        data.insert("MINECRAFT_VERSION".into(), self.mc.clone());

        let procs: Vec<&Value> = self.profile["processors"].as_array().map(|a| a.iter().collect()).unwrap_or_default();
        let procs: Vec<&Value> = procs.into_iter().filter(|p| p["sides"].as_array().map(|s| s.iter().any(|x| x == "client")).unwrap_or(true)).collect();
        for (i, p) in procs.iter().enumerate() {
            progress(&format!("Installing {name} ({}/{})", i + 1, procs.len()), 0.9 + 0.05 * i as f32 / procs.len() as f32);
            let jar = libdir.join(artifact_path(p["jar"].as_str().unwrap_or_default())?);
            let mut cp = vec![jar.to_string_lossy().to_string()];
            for e in p["classpath"].as_array().map(|a| a.as_slice()).unwrap_or_default() {
                cp.push(libdir.join(artifact_path(e.as_str().unwrap_or_default())?).to_string_lossy().to_string());
            }
            let mut args = vec![];
            for a in p["args"].as_array().map(|a| a.as_slice()).unwrap_or_default() {
                let a = a.as_str().unwrap_or_default();
                if let Some(art) = a.strip_prefix('[').and_then(|s| s.strip_suffix(']')) {
                    args.push(libdir.join(artifact_path(art)?).to_string_lossy().to_string());
                    continue;
                }
                let mut s = a.to_string();
                for (k, v) in &data { s = s.replace(&format!("{{{k}}}"), v); }
                args.push(s);
            }
            let mut cmd = Command::new(java);
            #[cfg(windows)]
            {
                use std::os::windows::process::CommandExt;
                cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
            }
            let out = cmd.arg("-cp").arg(cp.join(";")).arg(main_class(&jar)?).args(&args).current_dir(&tmp).output()
                .with_context(|| format!("running {name} install step {}", i + 1))?;
            if !out.status.success() {
                let log = format!("{}{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr));
                let tail: Vec<&str> = log.lines().rev().take(12).collect();
                bail!("{name} install step {} failed:\n{}", i + 1, tail.into_iter().rev().collect::<Vec<_>>().join("\n"));
            }
        }
        let _ = fs::remove_dir_all(&tmp);
        fs::write(&self.marker, "")?;
        Ok(())
    }
}
