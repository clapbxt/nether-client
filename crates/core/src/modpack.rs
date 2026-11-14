//! Modrinth modpacks (.mrpack): install into a NEW instance from a local file or a Modrinth project.
//! The caller adds the returned `InstanceData` to its list and saves it (`instances::save`).
use std::collections::HashMap;
use std::fs;
use std::io::{Read, Seek};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};

use anyhow::{anyhow, bail, Context, Result};
use rayon::prelude::*;
use reqwest::blocking::Client;
use serde::Deserialize;

use crate::download::{fetch, Check};
use crate::instances::{free_name, InstanceData};
use crate::launch::{Loader, LOADER_PIN};
use crate::paths::Paths;
use crate::Progress;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Index {
    #[serde(default)]
    name: String,
    #[serde(default)]
    files: Vec<PackFile>,
    #[serde(default)]
    dependencies: HashMap<String, String>,
}

#[derive(Deserialize)]
struct PackFile {
    path: String,
    hashes: Hashes,
    #[serde(default)]
    downloads: Vec<String>,
    #[serde(default)]
    env: Option<Env>,
}

#[derive(Deserialize)]
struct Hashes {
    sha1: String,
    #[serde(default)]
    sha512: Option<String>,
}

#[derive(Deserialize)]
struct Env {
    #[serde(default)]
    client: Option<String>,
}

/// What a pack declares, for showing before installing.
#[derive(Clone, Debug)]
pub struct PackInfo {
    pub name: String,
    pub minecraft: String,
    pub loader: Loader,
    /// Pinned loader version from the pack ("" when vanilla).
    pub loader_version: String,
    /// Files that will be downloaded (client-side ones).
    pub files: usize,
}

/// Join `rel` onto `root`, refusing anything that could leave it (absolute, `..`, drive prefixes, empty).
pub fn safe_join(root: &Path, rel: &str) -> Result<PathBuf> {
    let rel = rel.replace('\\', "/");
    if rel.is_empty() || rel.starts_with('/') || rel.contains(':') { bail!("unsafe path in pack: {rel}"); }
    let mut out = root.to_path_buf();
    for comp in Path::new(&rel).components() {
        match comp {
            Component::Normal(p) if p.to_str().is_some_and(crate::download::safe_file_name) => out.push(p),
            Component::CurDir => {}
            _ => bail!("unsafe path in pack: {rel}"),
        }
    }
    if out == root { bail!("unsafe path in pack: {rel}"); }
    Ok(out)
}

/// Extract every entry under `prefix` (e.g. "overrides/") of a zip into `dest`, path-checked. Returns the file count.
/// Limits against zip bombs: entries, bytes per file and in total (uncompressed), and the size of the index JSON.
pub(crate) const MAX_ENTRIES: usize = 20_000;
pub(crate) const MAX_ENTRY_BYTES: u64 = 1 << 30;
pub(crate) const MAX_TOTAL_BYTES: u64 = 4 << 30;
pub(crate) const MAX_INDEX_BYTES: u64 = 5 << 20;

pub(crate) fn extract_prefix<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, prefix: &str, dest: &Path) -> Result<usize> {
    extract_limited(zip, prefix, dest, MAX_ENTRIES, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES)
}

fn extract_limited<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, prefix: &str, dest: &Path, max_entries: usize, max_entry: u64, max_total: u64) -> Result<usize> {
    if zip.len() > max_entries { bail!("this archive has too many files ({})", zip.len()); }
    let (mut n, mut total) = (0, 0u64);
    for i in 0..zip.len() {
        let mut f = zip.by_index(i)?;
        let name = f.name().replace('\\', "/");
        let Some(rel) = name.strip_prefix(prefix) else { continue };
        if rel.is_empty() { continue; }
        let out = safe_join(dest, rel)?;
        if f.is_dir() || rel.ends_with('/') { fs::create_dir_all(&out)?; continue; }
        if let Some(p) = out.parent() { fs::create_dir_all(p)?; }
        if f.size() > max_entry { bail!("{rel} is too large to unpack"); }
        let mut w = fs::File::create(&out)?;
        // the declared size can lie: count what actually comes out
        let wrote = std::io::copy(&mut (&mut f).take(max_entry + 1), &mut w)?;
        total += wrote;
        if wrote > max_entry || total > max_total { bail!("this archive unpacks to far more data than it should; refusing it"); }
        n += 1;
    }
    Ok(n)
}

fn loader_of(deps: &HashMap<String, String>) -> Result<(Loader, String)> {
    for (k, l) in [("fabric-loader", Loader::Fabric), ("quilt-loader", Loader::Quilt), ("neoforge", Loader::NeoForge), ("forge", Loader::Forge)] {
        if let Some(v) = deps.get(k) { return Ok((l, v.clone())); }
    }
    if let Some(k) = deps.keys().find(|k| *k != "minecraft") { bail!("unsupported mod loader in pack: {k}"); }
    Ok((Loader::Vanilla, String::new()))
}

fn client_wanted(f: &PackFile) -> bool {
    f.env.as_ref().and_then(|e| e.client.as_deref()) != Some("unsupported")
}

fn read_index<R: Read + Seek>(zip: &mut zip::ZipArchive<R>) -> Result<Index> {
    let mut s = String::new();
    zip.by_name("modrinth.index.json").context("not a Modrinth pack (modrinth.index.json missing)")?.take(MAX_INDEX_BYTES).read_to_string(&mut s)?;
    serde_json::from_str(&s).context("modrinth.index.json is invalid")
}

/// Read a `.mrpack`'s name, Minecraft version and loader without installing.
pub fn info(mrpack: &Path) -> Result<PackInfo> {
    let mut zip = zip::ZipArchive::new(fs::File::open(mrpack)?)?;
    let idx = read_index(&mut zip)?;
    let (loader, loader_version) = loader_of(&idx.dependencies)?;
    let minecraft = idx.dependencies.get("minecraft").cloned().ok_or_else(|| anyhow!("pack has no Minecraft version"))?;
    Ok(PackInfo { name: idx.name.clone(), minecraft, loader, loader_version, files: idx.files.iter().filter(|f| client_wanted(f)).count() })
}

/// Create the `InstanceData` for a freshly populated folder (pins the loader version, counts mods).
pub(crate) fn new_instance(paths: &Paths, name: &str, mc: &str, loader: Loader, loader_version: &str) -> Result<InstanceData> {
    // both come from the pack: they become folder names and URL parts later
    if !crate::mojang::valid_version_id(mc) { bail!("the pack names an invalid Minecraft version"); }
    if !loader_version.is_empty() && !crate::launch::valid_loader_version(loader_version) { bail!("the pack names an invalid loader version"); }
    let dir = paths.instance(name);
    if !loader_version.is_empty() && loader.flavor().is_some() { fs::write(dir.join(LOADER_PIN), loader_version)?; }
    let mods = crate::instances::mod_files(paths, name).len() as u32;
    Ok(InstanceData {
        icon: String::new(), cover: String::new(), shared_settings: true, name: name.into(), version: mc.into(), loader: loader.label().into(),
        ram_gb: 4, hours: 0, mods, client: "Global".into(), last: "Never".into(), java: 0, played_secs: 0, last_ts: 0, ..Default::default()
    })
}

fn allowed_host(url: &str) -> bool {
    reqwest::Url::parse(url).ok().is_some_and(|u| {
        u.scheme() == "https" && matches!(u.host_str(), Some("cdn.modrinth.com" | "github.com" | "raw.githubusercontent.com" | "gitlab.com"))
    })
}

/// Install a local `.mrpack` as a new instance named `name` (None = the pack's name; a free name is chosen if taken).
/// Downloads are hash-verified; the new folder is removed again if anything fails.
pub fn install_file(c: &Client, paths: &Paths, mrpack: &Path, name: Option<&str>, progress: &Progress) -> Result<InstanceData> {
    progress("Reading modpack", 0.0);
    let mut zip = zip::ZipArchive::new(fs::File::open(mrpack).with_context(|| format!("opening {}", mrpack.display()))?)?;
    let idx = read_index(&mut zip)?;
    let (loader, lv) = loader_of(&idx.dependencies)?;
    let mc = idx.dependencies.get("minecraft").cloned().ok_or_else(|| anyhow!("pack has no Minecraft version"))?;
    let base = name.map(str::trim).filter(|n| !n.is_empty()).or(Some(idx.name.trim()).filter(|n| !n.is_empty())).unwrap_or("Modpack");
    let name = free_name(paths, base, &[]);
    let dir = paths.instance(&name);
    fs::create_dir_all(&dir)?;
    let res = (|| -> Result<InstanceData> {
        let files: Vec<&PackFile> = idx.files.iter().filter(|f| client_wanted(f)).collect();
        // validate every path before anything is downloaded
        let jobs: Vec<(PathBuf, &PackFile)> = files.iter().map(|f| safe_join(&dir, &f.path).map(|p| (p, *f))).collect::<Result<_>>()?;
        let pool = rayon::ThreadPoolBuilder::new().num_threads(8).build()?;
        let done = AtomicUsize::new(0);
        let total = jobs.len().max(1);
        progress("Downloading files", 0.02);
        pool.install(|| {
            jobs.par_iter().try_for_each(|(dest, f)| -> Result<()> {
                let urls: Vec<&String> = f.downloads.iter().filter(|u| allowed_host(u)).collect();
                if urls.is_empty() { bail!("no allowed download URL for {}", f.path); }
                let mut last = None;
                for u in urls {
                    match fetch(c, u, dest, Check::Sha1(&f.hashes.sha1)).and_then(|_| match &f.hashes.sha512 {
                        Some(h) if !crate::modrinth::file_sha512(dest)?.eq_ignore_ascii_case(h) => {
                            let _ = fs::remove_file(dest);
                            bail!("sha512 mismatch for {}", f.path)
                        }
                        _ => Ok(()),
                    }) {
                        Ok(()) => { last = None; break; }
                        Err(e) => last = Some(e),
                    }
                }
                if let Some(e) = last { return Err(e); }
                let n = done.fetch_add(1, Ordering::Relaxed) + 1;
                progress("Downloading files", 0.02 + 0.88 * n as f32 / total as f32);
                Ok(())
            })
        })?;
        progress("Copying overrides", 0.92);
        extract_prefix(&mut zip, "overrides/", &dir)?;
        extract_prefix(&mut zip, "client-overrides/", &dir)?;
        let data = new_instance(paths, &name, &mc, loader, &lv)?;
        progress("Modpack installed", 1.0);
        Ok(data)
    })();
    if res.is_err() { let _ = fs::remove_dir_all(&dir); }
    res
}

/// Download a Modrinth modpack (`version` = version id, None = newest) and install it as a new instance.
pub fn install_project(c: &Client, paths: &Paths, project: &str, version: Option<&str>, name: Option<&str>, progress: &Progress) -> Result<InstanceData> {
    progress("Finding modpack", 0.0);
    let p = crate::modrinth::pack_file(c, project, version)?;
    if !allowed_host(&p.url) { bail!("unexpected download host for {}", p.filename); }
    if !crate::download::safe_file_name(&p.filename) { bail!("unsafe pack file name {}", p.filename); }
    let dest = paths.root.join("cache").join("packs").join(&p.filename);
    fetch(c, &p.url, &dest, Check::Sha1(&p.sha1))?;
    let name = name.map(String::from).or(Some(p.title.clone()).filter(|t| !t.is_empty()));
    let sub: Progress = {
        let progress = progress.clone();
        std::sync::Arc::new(move |m, f| progress(m, 0.05 + 0.95 * f))
    };
    install_file(c, paths, &dest, name.as_deref(), &sub)
}

/// A unique scratch folder for tests.
#[cfg(test)]
pub(crate) fn tmpdir(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("nc-test-{tag}-{}-{:?}", std::process::id(), std::thread::current().id()).replace(['(', ')', ' '], ""));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn pack(index: &str, extra: &[(&str, &str)]) -> Vec<u8> {
        let mut z = zip::ZipWriter::new(std::io::Cursor::new(vec![]));
        let o = zip::write::SimpleFileOptions::default();
        z.start_file("modrinth.index.json", o).unwrap();
        z.write_all(index.as_bytes()).unwrap();
        for (n, c) in extra { z.start_file(*n, o).unwrap(); z.write_all(c.as_bytes()).unwrap(); }
        z.finish().unwrap().into_inner()
    }

    #[test]
    fn extraction_limits() {
        let bytes = pack("{}", &[("overrides/a.txt", "0123456789"), ("overrides/b.txt", "0123456789")]);
        let open = || zip::ZipArchive::new(std::io::Cursor::new(bytes.clone())).unwrap();
        let d = std::env::temp_dir().join(format!("nc-lim-{}", std::process::id()));
        assert_eq!(extract_limited(&mut open(), "overrides/", &d, 10, 100, 100).unwrap(), 2);
        assert!(extract_limited(&mut open(), "overrides/", &d, 2, 100, 100).is_err(), "too many entries");
        assert!(extract_limited(&mut open(), "overrides/", &d, 10, 5, 100).is_err(), "entry too large");
        assert!(extract_limited(&mut open(), "overrides/", &d, 10, 100, 15).is_err(), "total too large");
        let _ = fs::remove_dir_all(&d);
    }

    fn quiet() -> Progress { std::sync::Arc::new(|_, _| {}) }

    #[test]
    fn safe_join_rejects_escapes() {
        let r = Path::new("/inst");
        assert!(safe_join(r, "mods/a.jar").is_ok());
        for bad in ["../x", "mods/../../x", "/etc/x", "C:/x", "C:\\x", "..\\x", "", "mods/.."] {
            assert!(safe_join(r, bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn installs_offline_pack_with_overrides() {
        let root = tmpdir("mrpack");
        let paths = Paths { root: root.clone() };
        let idx = r#"{"formatVersion":1,"game":"minecraft","name":"Tiny Pack","versionId":"1","files":[
            {"path":"mods/server-only.jar","hashes":{"sha1":"00"},"downloads":["https://evil.example/x"],"env":{"client":"unsupported","server":"required"}}],
            "dependencies":{"minecraft":"1.21.1","fabric-loader":"0.16.9"}}"#;
        let bytes = pack(idx, &[("overrides/config/a.toml", "x=1"), ("client-overrides/options.txt", "fov:70"), ("server-overrides/skip.txt", "no")]);
        let f = root.join("t.mrpack");
        fs::write(&f, bytes).unwrap();
        let i = info(&f).unwrap();
        assert_eq!((i.minecraft.as_str(), i.loader, i.files), ("1.21.1", Loader::Fabric, 0));
        let d = install_file(&crate::download::client(), &paths, &f, None, &quiet()).unwrap();
        assert_eq!((d.name.as_str(), d.loader.as_str(), d.version.as_str()), ("Tiny Pack", "Fabric", "1.21.1"));
        let dir = paths.instance("Tiny Pack");
        assert_eq!(fs::read_to_string(dir.join("config/a.toml")).unwrap(), "x=1");
        assert!(dir.join("options.txt").exists() && !dir.join("skip.txt").exists());
        assert_eq!(fs::read_to_string(dir.join(LOADER_PIN)).unwrap(), "0.16.9");
        // second install of the same pack gets a free name and leaves the first alone
        let d2 = install_file(&crate::download::client(), &paths, &f, None, &quiet()).unwrap();
        assert_eq!(d2.name, "Tiny Pack (2)");
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn rejects_zip_slip_and_cleans_up() {
        let root = tmpdir("slip");
        let paths = Paths { root: root.clone() };
        let idx = r#"{"name":"Evil","files":[],"dependencies":{"minecraft":"1.21.1"}}"#;
        let f = root.join("e.mrpack");
        fs::write(&f, pack(idx, &[("overrides/../../escaped.txt", "x")])).unwrap();
        assert!(install_file(&crate::download::client(), &paths, &f, None, &quiet()).is_err());
        assert!(!paths.instance("Evil").exists());
        assert!(!root.join("escaped.txt").exists());
        // a file entry that escapes is refused before any download
        let idx = r#"{"name":"Evil2","files":[{"path":"../x.jar","hashes":{"sha1":"00"},"downloads":["https://cdn.modrinth.com/x"]}],"dependencies":{"minecraft":"1.21.1"}}"#;
        fs::write(&f, pack(idx, &[])).unwrap();
        assert!(install_file(&crate::download::client(), &paths, &f, None, &quiet()).is_err());
        assert!(!paths.instance("Evil2").exists());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn maps_loaders() {
        let m = |k: &str| loader_of(&HashMap::from([("minecraft".to_string(), "1".to_string()), (k.to_string(), "9".to_string())])).map(|x| x.0);
        assert_eq!(m("quilt-loader").unwrap(), Loader::Quilt);
        assert_eq!(m("neoforge").unwrap(), Loader::NeoForge);
        assert_eq!(m("forge").unwrap(), Loader::Forge);
        assert!(m("weird-loader").is_err());
    }
}
