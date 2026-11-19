//! Persisted instance list (instances.json in the launcher data dir), content files, and instance actions
//! (duplicate, delete, export as .mrpack).
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use anyhow::{bail, Result};
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::paths::Paths;

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct InstanceData {
    /// Block texture used as the icon ("" = default) and wallpaper version ("" = this instance's version).
    #[serde(default)]
    pub icon: String,
    #[serde(default)]
    pub cover: String,
    /// Use the shared game settings (keybinds, video, audio…). On by default.
    #[serde(default = "yes")]
    pub shared_settings: bool,
    pub name: String,
    pub version: String,
    pub loader: String,
    pub ram_gb: u32,
    pub hours: u32,
    pub mods: u32,
    pub client: String,
    pub last: String,
    /// Java major to force (0 = automatic).
    #[serde(default)]
    pub java: u32,
    /// Total play time in seconds (tracked from launches).
    #[serde(default)]
    pub played_secs: u64,
    /// Unix time of the last launch (0 = never / unknown).
    #[serde(default)]
    pub last_ts: u64,
    /// Extra JVM arguments, as typed (split by launch::split_args).
    #[serde(default)]
    pub jvm_args: String,
    /// Game window size; 0 = the game default.
    #[serde(default)]
    pub width: u32,
    #[serde(default)]
    pub height: u32,
    /// Start the game fullscreen.
    #[serde(default)]
    pub fullscreen: bool,
    /// Group on the Instances page ("" = none) and the player's own notes.
    #[serde(default)]
    pub group: String,
    #[serde(default)]
    pub notes: String,
    /// The last session ended in a crash (Home shows it until the next launch).
    #[serde(default)]
    pub crashed: bool,
    /// Restore points: make one before launch when the newest is a week old; automatic ones kept (0 = 5).
    #[serde(default)]
    pub backup_auto: bool,
    #[serde(default)]
    pub backup_keep: u32,
    /// A Nether profile (the Nether client runs in it) or plain Minecraft. None (instances from before profiles) is
    /// decided by `nether_ok`: Vanilla or Fabric on a version the client supports.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub nether: Option<bool>,
    /// The modpack this instance was installed from ("" = none): it lists under Play > Modpacks.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub pack: String,
    /// OptiFine on (Vanilla 1.8.9 / 1.12.2 Nether profiles): "" off, "on" = set up on the next launch (the launcher
    /// downloads it, see `optifine`).
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub optifine: String,
}

fn yes() -> bool { true }

/// Where the Nether client runs: Vanilla on 1.8.9, 1.12.2, 1.14 and newer (26.x too); Fabric and Forge on 1.14 and
/// newer, plus Fabric 1.8.9 (run on Legacy Fabric); Legacy Fabric on 1.8.9 and 1.12.2. NeoForge, Quilt and other versions are plain Minecraft only.
pub fn nether_ok(version: &str, loader: &str) -> bool {
    use crate::launch::Loader;
    let l = Loader::from_label(loader);
    let (legacy, vanilla) = (matches!(l, Loader::LegacyFabric), matches!(l, Loader::Vanilla));
    let fabric = matches!(l, Loader::Fabric | Loader::Forge);
    if !fabric && !legacy && !vanilla { return false; }
    let mut it = version.split('.').map(|p| p.parse::<u32>().ok());
    match (it.next().flatten(), it.next().flatten()) {
        (Some(1), Some(m)) if m >= 14 => !legacy,
        // a "Fabric" 1.8.9 instance runs on Legacy Fabric (launch::pre_fabric), whose names the agent maps (Tiny)
        (Some(1), Some(8 | 12)) => match version { "1.8.9" => !matches!(l, Loader::Forge), "1.12.2" => !fabric, _ => false },
        (Some(y), _) if y >= 26 => !legacy,
        _ => false,
    }
}

#[cfg(test)]
mod nether_tests {
    #[test]
    fn where_nether_runs() {
        use super::nether_ok as ok;
        assert!(ok("1.21.11", "Fabric") && ok("1.21.11", "Vanilla") && ok("26.3", "Fabric") && ok("1.14", "Vanilla"));
        assert!(ok("1.8.9", "Vanilla") && ok("1.8.9", "Legacy Fabric") && ok("1.12.2", "Vanilla"));
        assert!(ok("1.20.1", "Forge") && !ok("1.12.2", "Forge") && !ok("1.8.9", "Forge"));
        assert!(ok("1.8.9", "Fabric") && !ok("1.12.2", "Fabric") && !ok("1.21.1", "NeoForge") && !ok("1.20.1", "Quilt"));
        assert!(!ok("1.13.2", "Vanilla") && !ok("1.7.10", "Vanilla") && !ok("1.8.8", "Vanilla") && !ok("25w14a", "Vanilla"));
        assert!(!ok("1.21.1", "Legacy Fabric"));
    }
}

impl InstanceData {
    /// The Nether client runs in this instance.
    pub fn nether_on(&self) -> bool { self.nether != Some(false) && nether_ok(&self.version, &self.loader) }
}

fn file(paths: &Paths) -> PathBuf { paths.root.join("instances.json") }

/// The saved list for read-only lookups (None when missing or unreadable). Never moves or writes anything.
pub fn load(paths: &Paths) -> Option<Vec<InstanceData>> {
    crate::store::peek_json(&file(paths))
}

/// What the launcher found at startup.
pub enum Opened {
    /// No instances.json yet (first run).
    Fresh,
    List(Vec<InstanceData>),
    /// instances.json was damaged or gone: the damaged file sits at `aside` (when there was one) and the
    /// last good backup was loaded instead.
    Restored { list: Vec<InstanceData>, aside: Option<PathBuf> },
    /// The file exists but can't be read right now (locked). Nothing may be saved over it this session.
    Unreadable(String),
}

/// Startup load: tells a first run from a damaged or locked file, and moves old non-ASCII folders to their new names.
pub fn open(paths: &Paths) -> Opened {
    use crate::store::{peek_json, read_json, Load};
    let res = match read_json::<Vec<InstanceData>>(&file(paths)) {
        Err(e) => return Opened::Unreadable(e.to_string()),
        Ok(Load::Missing) => match peek_json::<Vec<InstanceData>>(&backup_file(paths)) {
            // the list itself is gone but its backup is not: bring it back
            Some(list) if !list.is_empty() => Opened::Restored { list, aside: None },
            _ => Opened::Fresh,
        },
        Ok(Load::Ok(list)) => Opened::List(list),
        Ok(Load::Damaged(aside)) => Opened::Restored { list: peek_json(&backup_file(paths)).unwrap_or_default(), aside: Some(aside) },
    };
    if let Opened::List(l) | Opened::Restored { list: l, .. } = &res { migrate_dirs(paths, l); }
    res
}

/// Instances with non-ASCII names used to share one lossy folder name ("Лес" and "Мир" were both `---`).
/// Move each one that owns its old folder alone to its new, unique folder.
fn migrate_dirs(paths: &Paths, list: &[InstanceData]) {
    for d in list {
        let (old, new) = (paths.legacy_instance(&d.name), paths.instance(&d.name));
        if old == new || new.exists() || !old.exists() { continue; }
        if list.iter().filter(|o| paths.legacy_instance(&o.name) == old).count() == 1 { let _ = fs::rename(&old, &new); }
    }
}

fn backup_file(paths: &Paths) -> PathBuf { paths.root.join("instances.json.bak") }

pub fn save(paths: &Paths, list: &[InstanceData]) -> Result<()> {
    fs::create_dir_all(&paths.root)?;
    // keep an hourly backup of the last good list (saves happen on every edit, so not on each one)
    let bak = backup_file(paths);
    let stale = fs::metadata(&bak).and_then(|m| m.modified()).map(|t| t.elapsed().map(|e| e.as_secs() > 3600).unwrap_or(true)).unwrap_or(true);
    if stale && load_strict(&file(paths)) { let _ = fs::copy(file(paths), &bak); }
    crate::store::write_json(&file(paths), &list)
}

/// True when the file parses as an instance list (only good lists become backups).
fn load_strict(p: &Path) -> bool { fs::read(p).ok().is_some_and(|r| serde_json::from_slice::<Vec<InstanceData>>(&r).is_ok()) }

/// Total physical memory in MB (0 when unknown).
pub fn system_ram_mb() -> u64 {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};
        let mut m: MEMORYSTATUSEX = std::mem::zeroed();
        m.dwLength = std::mem::size_of::<MEMORYSTATUSEX>() as u32;
        if GlobalMemoryStatusEx(&mut m) != 0 { return m.ullTotalPhys / (1024 * 1024); }
    }
    0
}

/// Content kinds inside an instance: 0 mods, 1 resource packs, 2 shader packs.
pub fn content_dir(paths: &Paths, instance: &str, kind: i32) -> PathBuf {
    paths.instance(instance).join(match kind { 1 => "resourcepacks", 2 => "shaderpacks", _ => "mods" })
}

/// Files of a content kind (enabled and `.disabled`), sorted by name: `.jar` for mods, `.zip` for packs.
pub fn content_files(paths: &Paths, instance: &str, kind: i32) -> Vec<PathBuf> {
    let ext = if kind == 0 { ".jar" } else { ".zip" };
    let mut v: Vec<PathBuf> = fs::read_dir(content_dir(paths, instance, kind))
        .map(|rd| rd.flatten().map(|e| e.path()).filter(|p| {
            let n = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
            n.ends_with(ext) || n.ends_with(&format!("{ext}.disabled"))
        }).collect())
        .unwrap_or_default();
    v.sort();
    v
}

/// Mod jars in an instance (enabled `.jar` and disabled `.jar.disabled`), sorted by name.
pub fn mod_files(paths: &Paths, instance: &str) -> Vec<PathBuf> { content_files(paths, instance, 0) }

/// Enable/disable a file by renaming it. Returns the new path.
pub fn set_mod_enabled(p: &PathBuf, enabled: bool) -> Result<PathBuf> {
    let s = p.to_string_lossy().to_string();
    let new = if enabled { s.strip_suffix(".disabled").unwrap_or(&s).to_string() } else if s.ends_with(".disabled") { s } else { format!("{s}.disabled") };
    let np = PathBuf::from(new);
    if &np != p { fs::rename(p, &np)?; }
    Ok(np)
}

/// A name whose folder doesn't exist yet: "Name", "Name (2)", ...
pub fn free_name(paths: &Paths, base: &str, taken: &[String]) -> String {
    let slug = |n: &str| paths.instance(n);
    let free = |n: &str| !slug(n).exists() && !taken.iter().any(|t| slug(t) == slug(n));
    if free(base) { return base.to_string(); }
    (2..).map(|i| format!("{base} ({i})")).find(|n| free(n)).unwrap()
}

fn copy_dir(from: &Path, to: &Path) -> Result<()> {
    fs::create_dir_all(to)?;
    for e in fs::read_dir(from)? {
        let e = e?;
        let dest = to.join(e.file_name());
        if e.file_type()?.is_dir() { copy_dir(&e.path(), &dest)?; } else { fs::copy(e.path(), dest)?; }
    }
    Ok(())
}

/// Copy an instance's folder (worlds, mods, settings) to a new instance name.
pub fn duplicate(paths: &Paths, from: &str, to: &str) -> Result<()> {
    if from.trim().is_empty() || to.trim().is_empty() { bail!("an instance needs a name"); }
    let (src, dst) = (paths.instance(from), paths.instance(to));
    if dst.exists() { bail!("{to} already exists"); }
    if src.exists() { copy_dir(&src, &dst) } else { Ok(fs::create_dir_all(dst)?) }
}

/// Move an instance's folder to the Recycle Bin (it can be restored from there).
pub fn delete(paths: &Paths, name: &str) -> Result<()> {
    if name.trim().is_empty() { bail!("an instance needs a name"); }
    let dir = paths.instance(name);
    if dir.exists() { crate::trash::recycle(&dir)?; }
    Ok(())
}

/// Move an instance's folder to the folder of its new name. Fails when that folder is taken.
pub fn rename(paths: &Paths, from: &str, to: &str) -> Result<()> {
    if to.trim().is_empty() { bail!("give the instance a name"); }
    let (src, dst) = (paths.instance(from), paths.instance(to));
    if src == dst { return Ok(()); }
    if dst.exists() { bail!("an instance called {to} already exists"); }
    if src.exists() { fs::rename(&src, &dst).map_err(|e| anyhow::anyhow!("its folder is in use ({e}); close the game and any open files first"))?; }
    Ok(())
}

/// Export as a Modrinth modpack: files Modrinth hosts are referenced by URL, everything else
/// (unknown jars, config, packs, options) goes into `overrides/`. Disabled files are left out.
pub fn export_mrpack(c: &Client, paths: &Paths, d: &InstanceData, out: &Path) -> Result<()> {
    let root = paths.instance(&d.name);
    let mut known = vec![];
    let mut hashes = vec![];
    for kind in 0..3 {
        for p in content_files(paths, &d.name, kind) {
            if p.to_string_lossy().ends_with(".disabled") { continue; }
            let h = crate::modrinth::file_sha512(&p)?;
            hashes.push(h.clone());
            known.push((p, h));
        }
    }
    let meta = crate::modrinth::file_meta(c, &hashes).unwrap_or_default();
    let mut files = vec![];
    let mut overrides: Vec<PathBuf> = vec![];
    for (p, h) in &known {
        let rel = p.strip_prefix(&root)?.to_string_lossy().replace('\\', "/");
        match meta.get(h) {
            Some((url, sha1, size)) if !url.is_empty() => files.push(json!({
                "path": rel, "hashes": {"sha1": sha1, "sha512": h}, "downloads": [url], "fileSize": size,
                "env": {"client": "required", "server": "required"}
            })),
            _ => overrides.push(p.clone()),
        }
    }
    for extra in ["config", "options.txt"] {
        let p = root.join(extra);
        if p.exists() { overrides.push(p); }
    }
    let mut deps = json!({"minecraft": d.version});
    match crate::launch::Loader::from_label(&d.loader) {
        crate::launch::Loader::Fabric => deps["fabric-loader"] = json!(crate::fabric::latest_loader(c, &d.version)?),
        crate::launch::Loader::Quilt => deps["quilt-loader"] = json!(crate::fabric::latest(c, crate::fabric::Flavor::Quilt, &d.version)?),
        // the mrpack spec has no Legacy Fabric key; fabric-loader is the closest and Legacy Fabric uses the same loader jar
        crate::launch::Loader::LegacyFabric => deps["fabric-loader"] = json!(crate::fabric::latest(c, crate::fabric::Flavor::LegacyFabric, &d.version)?),
        crate::launch::Loader::Forge => deps["forge"] = json!(crate::forge::latest(c, crate::forge::Kind::Forge, &d.version)?),
        crate::launch::Loader::NeoForge => deps["neoforge"] = json!(crate::forge::latest(c, crate::forge::Kind::NeoForge, &d.version)?),
        crate::launch::Loader::Vanilla => {}
    }
    let index = json!({"formatVersion": 1, "game": "minecraft", "versionId": "1.0.0", "name": d.name, "files": files, "dependencies": deps});

    if let Some(dir) = out.parent() { fs::create_dir_all(dir)?; }
    let mut zip = zip::ZipWriter::new(fs::File::create(out)?);
    let opt = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    zip.start_file("modrinth.index.json", opt)?;
    zip.write_all(&serde_json::to_vec_pretty(&index)?)?;
    fn add(zip: &mut zip::ZipWriter<fs::File>, root: &Path, p: &Path, opt: zip::write::SimpleFileOptions) -> Result<()> {
        if p.is_dir() {
            for e in fs::read_dir(p)? { add(zip, root, &e?.path(), opt)?; }
        } else {
            let rel = p.strip_prefix(root)?.to_string_lossy().replace('\\', "/");
            zip.start_file(format!("overrides/{rel}"), opt)?;
            zip.write_all(&fs::read(p)?)?;
        }
        Ok(())
    }
    for p in &overrides { add(&mut zip, &root, p, opt)?; }
    zip.finish()?;
    Ok(())
}
