//! Import instances from other launchers: Prism/MultiMC (zip or folder), CurseForge modpack zips, Modrinth packs.
//! The source is never modified. The caller adds the returned `InstanceData` to its list and saves it.
use std::fs;
use std::io::{Read, Seek};
use std::path::Path;

use anyhow::{anyhow, bail, Context, Result};
use reqwest::blocking::Client;
use serde_json::Value;

use crate::instances::{free_name, InstanceData};
use crate::launch::Loader;
use crate::modpack::{extract_prefix, new_instance, safe_join};
use crate::paths::Paths;
use crate::Progress;

/// What kind of pack/instance a path is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Format {
    /// Modrinth `.mrpack`
    Mrpack,
    /// Prism / MultiMC instance (folder or exported zip)
    Prism,
    /// CurseForge modpack zip
    CurseForge,
}

/// A CurseForge file the pack needs but that could not be downloaded (no API key): ids to fetch later.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MissingFile {
    pub project_id: u64,
    pub file_id: u64,
    pub required: bool,
}

/// Result of an import: the new instance plus files that still need downloading (CurseForge only).
#[derive(Clone, Debug)]
pub struct Imported {
    pub data: InstanceData,
    pub missing: Vec<MissingFile>,
    /// what was not carried over (launcher imports: jar mods, a modified version imported as vanilla...)
    pub notes: Vec<String>,
}

fn norm(n: &str) -> String { n.replace('\\', "/") }

/// Shallowest zip entry named `file` (at the root or one folder deep) -> its folder prefix ("" or "dir/").
fn find_in_zip<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, file: &str) -> Option<String> {
    let names: Vec<String> = (0..zip.len()).filter_map(|i| zip.by_index_raw(i).ok().and_then(|f| f.name().ok().map(|n| norm(&n)))).collect();
    if names.iter().any(|n| n == file) { return Some(String::new()); }
    names.iter().find(|n| n.matches('/').count() == 1 && n.ends_with(&format!("/{file}"))).map(|n| n[..n.len() - file.len()].to_string())
}

/// Work out which format `path` is (file or folder).
pub fn detect(path: &Path) -> Result<Format> {
    if path.is_dir() {
        if path.join("instance.cfg").is_file() { return Ok(Format::Prism); }
        bail!("folder is not a Prism/MultiMC instance (no instance.cfg)");
    }
    let mut zip = zip::ZipArchive::new(fs::File::open(path).with_context(|| format!("opening {}", path.display()))?).context("not a zip file")?;
    if zip.by_name("modrinth.index.json").is_ok() { return Ok(Format::Mrpack); }
    if let Some(p) = find_in_zip(&mut zip, "instance.cfg") { let _ = p; return Ok(Format::Prism); }
    if let Ok(mut m) = zip.by_name("manifest.json") {
        let mut s = String::new();
        (&mut m).take(crate::modpack::MAX_INDEX_BYTES).read_to_string(&mut s)?;
        if serde_json::from_str::<Value>(&s).is_ok_and(|v| v["minecraft"].is_object()) { return Ok(Format::CurseForge); }
    }
    bail!("unrecognised modpack/instance format")
}

/// Import any supported format (auto-detected) as a new instance named `name` (None = the pack's own name).
pub fn import(c: &Client, paths: &Paths, path: &Path, name: Option<&str>, progress: &Progress) -> Result<Imported> {
    match detect(path)? {
        Format::Mrpack => Ok(Imported { data: crate::modpack::install_file(c, paths, path, name, progress)?, missing: vec![], notes: vec![] }),
        Format::Prism => import_prism(paths, path, name, progress),
        Format::CurseForge => import_curseforge(paths, path, name, progress),
    }
}

fn parse_cfg(s: &str) -> std::collections::HashMap<String, String> {
    s.lines().filter_map(|l| l.split_once('=')).map(|(k, v)| (k.trim().to_string(), v.trim().to_string())).collect()
}

/// (minecraft version, loader, loader version) from a Prism `mmc-pack.json`.
pub(crate) fn parse_mmc_pack(s: &str) -> Result<(String, Loader, String)> {
    let v: Value = serde_json::from_str(s).context("mmc-pack.json is invalid")?;
    let comps = v["components"].as_array().ok_or_else(|| anyhow!("mmc-pack.json has no components"))?;
    let ver = |uid: &str| comps.iter().find(|c| c["uid"] == uid).and_then(|c| c["version"].as_str()).map(String::from);
    let mc = ver("net.minecraft").ok_or_else(|| anyhow!("instance has no Minecraft component"))?;
    for (uid, l) in [("net.fabricmc.fabric-loader", Loader::Fabric), ("org.quiltmc.quilt-loader", Loader::Quilt), ("net.neoforged", Loader::NeoForge), ("net.minecraftforge", Loader::Forge)] {
        if let Some(lv) = ver(uid) { return Ok((mc, l, lv)); }
    }
    Ok((mc, Loader::Vanilla, String::new()))
}

pub(crate) fn copy_tree(from: &Path, to: &Path, skip: &[&str]) -> Result<()> {
    fs::create_dir_all(to)?;
    for e in fs::read_dir(from)? {
        let e = e?;
        let n = e.file_name().to_string_lossy().to_string();
        let t = e.file_type()?;
        if t.is_symlink() || skip.contains(&n.as_str()) { continue; }
        if t.is_dir() { copy_tree(&e.path(), &to.join(&n), &[])?; } else { fs::copy(e.path(), to.join(&n))?; }
    }
    Ok(())
}

fn ram_gb(cfg: &std::collections::HashMap<String, String>) -> u32 {
    if cfg.get("OverrideMemory").map(String::as_str) != Some("true") { return 4; }
    cfg.get("MaxMemAlloc").and_then(|m| m.parse::<u32>().ok()).map_or(4, |mb| mb.div_ceil(1024).clamp(1, 16))
}

/// Import a Prism/MultiMC instance from a folder or an exported zip.
pub fn import_prism(paths: &Paths, path: &Path, name: Option<&str>, progress: &Progress) -> Result<Imported> {
    progress("Reading instance", 0.0);
    let stem = path.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let (cfg_s, pack_s, base, mut zip) = if path.is_dir() {
        (fs::read_to_string(path.join("instance.cfg"))?, fs::read_to_string(path.join("mmc-pack.json")).context("mmc-pack.json missing")?, String::new(), None)
    } else {
        let mut z = zip::ZipArchive::new(fs::File::open(path)?)?;
        let base = find_in_zip(&mut z, "instance.cfg").ok_or_else(|| anyhow!("instance.cfg missing"))?;
        let mut read = |f: &str| -> Result<String> { let mut s = String::new(); z.by_name(&format!("{base}{f}")).with_context(|| format!("{f} missing"))?.take(crate::modpack::MAX_INDEX_BYTES).read_to_string(&mut s)?; Ok(s) };
        let (a, b) = (read("instance.cfg")?, read("mmc-pack.json")?);
        (a, b, base, Some(z))
    };
    let cfg = parse_cfg(&cfg_s);
    let (mc, loader, lv) = parse_mmc_pack(&pack_s)?;
    let want = name.map(str::trim).filter(|n| !n.is_empty()).or(cfg.get("name").map(String::as_str).filter(|n| !n.is_empty())).unwrap_or(if stem.is_empty() { "Imported" } else { &stem });
    let name = free_name(paths, want, &[]);
    let dir = paths.instance(&name);
    fs::create_dir_all(&dir)?;
    let res = (|| -> Result<InstanceData> {
        progress("Copying files", 0.1);
        match &mut zip {
            None => {
                let src = [".minecraft", "minecraft"].iter().map(|d| path.join(d)).find(|p| p.is_dir());
                if let Some(s) = src { copy_tree(&s, &dir, &["logs", "crash-reports"])?; }
            }
            Some(z) => {
                let d = [".minecraft/", "minecraft/"].into_iter().find(|d| (0..z.len()).any(|i| z.by_index_raw(i).is_ok_and(|f| f.name().is_ok_and(|n| norm(&n).starts_with(&format!("{base}{d}"))))));
                if let Some(d) = d { extract_prefix(z, &format!("{base}{d}"), &dir)?; }
            }
        }
        let mut data = new_instance(paths, &name, &mc, loader, &lv)?;
        data.ram_gb = ram_gb(&cfg);
        progress("Instance imported", 1.0);
        Ok(data)
    })();
    if res.is_err() { let _ = fs::remove_dir_all(&dir); }
    Ok(Imported { data: res?, missing: vec![], notes: vec![] })
}

/// (minecraft, loader, loader version, name, override folder, missing files) from a CurseForge `manifest.json`.
fn parse_cf_manifest(s: &str) -> Result<(String, Loader, String, String, String, Vec<MissingFile>)> {
    let v: Value = serde_json::from_str(s).context("manifest.json is invalid")?;
    let mc = v["minecraft"]["version"].as_str().ok_or_else(|| anyhow!("manifest has no Minecraft version"))?.to_string();
    let loaders = v["minecraft"]["modLoaders"].as_array().cloned().unwrap_or_default();
    let pick = loaders.iter().find(|l| l["primary"].as_bool() == Some(true)).or(loaders.first());
    let (mut loader, mut lv) = (Loader::Vanilla, String::new());
    if let Some(id) = pick.and_then(|l| l["id"].as_str()) {
        let (k, ver) = id.split_once('-').unwrap_or((id, ""));
        loader = match k { "fabric" => Loader::Fabric, "quilt" => Loader::Quilt, "forge" => Loader::Forge, "neoforge" => Loader::NeoForge, _ => bail!("unsupported mod loader in pack: {id}") };
        lv = ver.to_string();
    }
    let missing = v["files"].as_array().into_iter().flatten().filter_map(|f| Some(MissingFile {
        project_id: f["projectID"].as_u64()?, file_id: f["fileID"].as_u64()?, required: f["required"].as_bool().unwrap_or(true),
    })).collect();
    let overrides = v["overrides"].as_str().unwrap_or("overrides").to_string();
    Ok((mc, loader, lv, v["name"].as_str().unwrap_or_default().to_string(), overrides, missing))
}

/// Import a CurseForge modpack zip. Version, loader and overrides are installed; the mod files themselves need a
/// CurseForge API key, so their ids come back in `Imported::missing`.
pub fn import_curseforge(paths: &Paths, zip_path: &Path, name: Option<&str>, progress: &Progress) -> Result<Imported> {
    progress("Reading modpack", 0.0);
    let mut zip = zip::ZipArchive::new(fs::File::open(zip_path)?)?;
    let mut s = String::new();
    zip.by_name("manifest.json").context("manifest.json missing")?.take(crate::modpack::MAX_INDEX_BYTES).read_to_string(&mut s)?;
    let (mc, loader, lv, pack_name, ov, missing) = parse_cf_manifest(&s)?;
    let stem = zip_path.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let want = name.map(str::trim).filter(|n| !n.is_empty()).or(Some(pack_name.as_str()).filter(|n| !n.is_empty())).unwrap_or(if stem.is_empty() { "Imported" } else { &stem });
    let name = free_name(paths, want, &[]);
    let dir = paths.instance(&name);
    fs::create_dir_all(&dir)?;
    let res = (|| -> Result<InstanceData> {
        progress("Copying overrides", 0.3);
        // the override folder name comes from the pack: it must itself be a plain relative name
        safe_join(&dir, &ov)?;
        extract_prefix(&mut zip, &format!("{}/", ov.trim_matches('/')), &dir)?;
        let d = new_instance(paths, &name, &mc, loader, &lv)?;
        progress("Modpack imported", 1.0);
        Ok(d)
    })();
    if res.is_err() { let _ = fs::remove_dir_all(&dir); }
    Ok(Imported { data: res?, missing, notes: vec![] })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::modpack::tmpdir;
    use std::io::Write;

    fn quiet() -> Progress { std::sync::Arc::new(|_, _| {}) }

    fn zip_of(entries: &[(&str, &str)]) -> Vec<u8> {
        let mut z = zip::ZipWriter::new(std::io::Cursor::new(vec![]));
        for (n, c) in entries { z.start_file(*n, zip::write::SimpleFileOptions::default()).unwrap(); z.write_all(c.as_bytes()).unwrap(); }
        z.finish().unwrap().into_inner()
    }

    const MMC: &str = r#"{"components":[{"uid":"net.minecraft","version":"1.20.1"},{"uid":"net.fabricmc.intermediary","version":"1.20.1"},{"uid":"net.fabricmc.fabric-loader","version":"0.15.7"}],"formatVersion":1}"#;
    const CFG: &str = "InstanceType=OneSix\nOverrideMemory=true\nMaxMemAlloc=6144\nname=My Prism\n";

    #[test]
    fn prism_zip_and_folder() {
        let root = tmpdir("prism");
        let paths = Paths { root: root.clone() };
        let z = root.join("p.zip");
        fs::write(&z, zip_of(&[("My Prism/instance.cfg", CFG), ("My Prism/mmc-pack.json", MMC), ("My Prism/.minecraft/options.txt", "fov:1"), ("My Prism/.minecraft/saves/W/level.dat", "x")])).unwrap();
        assert_eq!(detect(&z).unwrap(), Format::Prism);
        let r = import(&crate::download::client(), &paths, &z, None, &quiet()).unwrap();
        assert_eq!((r.data.name.as_str(), r.data.version.as_str(), r.data.loader.as_str(), r.data.ram_gb), ("My Prism", "1.20.1", "Fabric", 6));
        assert!(paths.instance("My Prism").join("saves/W/level.dat").exists());

        let f = root.join("folder");
        fs::create_dir_all(f.join("minecraft/mods")).unwrap();
        fs::write(f.join("instance.cfg"), "name=Folder One\n").unwrap();
        fs::write(f.join("mmc-pack.json"), MMC.replace("fabric-loader", "quilt-loader").replace("net.fabricmc.quilt-loader", "org.quiltmc.quilt-loader")).unwrap();
        fs::write(f.join("minecraft/mods/a.jar"), "j").unwrap();
        assert_eq!(detect(&f).unwrap(), Format::Prism);
        let r = import(&crate::download::client(), &paths, &f, None, &quiet()).unwrap();
        assert_eq!((r.data.loader.as_str(), r.data.mods), ("Quilt", 1));
        assert!(f.join("minecraft/mods/a.jar").exists(), "source untouched");
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn curseforge_zip_reports_missing() {
        let root = tmpdir("cf");
        let paths = Paths { root: root.clone() };
        let m = r#"{"minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.2.0","primary":true}]},"manifestType":"minecraftModpack","name":"CF Pack","overrides":"overrides","files":[{"projectID":238222,"fileID":4838941,"required":true},{"projectID":1,"fileID":2,"required":false}]}"#;
        let z = root.join("cf.zip");
        fs::write(&z, zip_of(&[("manifest.json", m), ("overrides/config/x.cfg", "1")])).unwrap();
        assert_eq!(detect(&z).unwrap(), Format::CurseForge);
        let r = import(&crate::download::client(), &paths, &z, None, &quiet()).unwrap();
        assert_eq!((r.data.name.as_str(), r.data.loader.as_str()), ("CF Pack", "Forge"));
        assert_eq!(r.missing, vec![MissingFile { project_id: 238222, file_id: 4838941, required: true }, MissingFile { project_id: 1, file_id: 2, required: false }]);
        assert!(paths.instance("CF Pack").join("config/x.cfg").exists());
        // hostile override folder is refused and nothing is left behind
        let bad = m.replace("\"overrides\":\"overrides\"", "\"overrides\":\"../x\"").replace("CF Pack", "Bad Pack");
        fs::write(&z, zip_of(&[("manifest.json", &bad), ("../x/f", "1")])).unwrap();
        assert!(import(&crate::download::client(), &paths, &z, None, &quiet()).is_err());
        assert!(!paths.instance("Bad Pack").exists());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn detects_mrpack_and_junk() {
        let root = tmpdir("detect");
        let z = root.join("a.mrpack");
        fs::write(&z, zip_of(&[("modrinth.index.json", "{}")])).unwrap();
        assert_eq!(detect(&z).unwrap(), Format::Mrpack);
        let j = root.join("junk.zip");
        fs::write(&j, zip_of(&[("readme.txt", "hi")])).unwrap();
        assert!(detect(&j).is_err());
        assert!(detect(&root).is_err());
        let _ = fs::remove_dir_all(root);
    }
}
