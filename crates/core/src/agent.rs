//! The in-game client (the Java agent). Each instance gets its own copy in `<instance>/.nether/client/`, which
//! launch passes as `-javaagent`. Two sources:
//! - the build bundled into this launcher (always there, works offline);
//! - signed releases of the client repo ([`CLIENT_REPO`]), fetched by [`update`] when the launcher starts and
//!   kept in `<data>/client/builds/<version>/`.
//!
//! The newest build whose [`PROTOCOL`] matches wins. Updating is always on: [`update`] installs a new build into
//! every instance that already has a client, and [`ensure`] installs it into any other instance at launch.
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::paths::Paths;

/// The launcher <-> client contract this launcher speaks. It covers the `-Dnether.*` properties launch passes, the
/// boot jar beside the agent jar, and nether-media.exe beside it. A client build declares the protocol it was made
/// for, and only a build with this exact number is installed. Raise it only for a breaking change on either side.
pub const PROTOCOL: u32 = 1;
/// The public repo whose GitHub releases carry the signed client builds.
pub const CLIENT_REPO: &str = "clapbxt/nether-agent";
/// One hex Ed25519 public key per line; a release's `client.json.sig` must verify under one of them.
const CLIENT_KEYS: &str = include_str!("../res/client-ed25519.pub");
/// What a client signature covers: this prefix, then the exact bytes of `client.json`.
const SIGNED_PREFIX: &[u8] = b"nether-client\n";

const JAR: &str = "nether-agent.jar";
const BOOT_JAR: &str = "nether-agent-boot.jar";
const MANIFEST: &str = "client.json";
const SIG: &str = "client.json.sig";
const MEDIA_EXE: &str = "nether-media.exe";

static AGENT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/nether-agent.jar"));
static BOOT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/nether-agent-boot.jar"));
/// `client.json` of the bundled build (version, protocol, file hashes).
static BUNDLED_MANIFEST: &str = include_str!(concat!(env!("OUT_DIR"), "/client.json"));
/// Every module and setting of the bundled agent (JSON, the client build's `schema.json`).
pub static SCHEMA: &str = include_str!(concat!(env!("OUT_DIR"), "/schema.json"));
/// `nether-media.exe` (crates/media), written next to the jars; the agent finds it beside its `-javaagent` jar.
static MEDIA: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/nether-media.exe"));

/// Installs into instances and the build store never run at the same time (boot update vs. a launch).
static LOCK: Mutex<()> = Mutex::new(());

/// Global client config shared by all instances (see the Client page).
pub fn global_config(paths: &Paths) -> PathBuf { paths.root.join("client").join("global.json") }

/// Where an instance's copy of the client lives.
pub fn instance_dir(game_dir: &Path) -> PathBuf { game_dir.join(".nether").join("client") }

fn builds_dir(paths: &Paths) -> PathBuf { paths.root.join("client").join("builds") }

/// `client.json`: what a client build is.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Manifest {
    pub version: String,
    pub protocol: u32,
    /// file name -> sha256 (hex) of each jar
    pub files: BTreeMap<String, String>,
}

impl Manifest {
    /// This launcher can run the build.
    fn usable(&self) -> bool {
        self.protocol == PROTOCOL && safe_version(&self.version) && [JAR, BOOT_JAR].iter().all(|f| self.files.get(*f).is_some_and(|h| h.len() == 64))
    }
}

/// A version string that is safe as a folder name ("1.2.0", "1.3.0-beta.1").
fn safe_version(v: &str) -> bool {
    !v.is_empty() && v.len() <= 40 && v.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-') && !v.starts_with('.')
}

fn sha256_hex(b: &[u8]) -> String { hex::encode(Sha256::digest(b)) }

/// `a` is a higher version than `b` (same rules as launcher updates).
fn newer(a: &str, b: &str) -> bool { nether_net::update::newer(a, b) }

/// A client build this launcher could install.
enum Build {
    Bundled(Manifest),
    Downloaded(Manifest, PathBuf),
}

impl Build {
    fn manifest(&self) -> &Manifest { match self { Build::Bundled(m) | Build::Downloaded(m, _) => m } }
    /// The file's bytes, only if they match the manifest's hash.
    fn file(&self, name: &str) -> Option<Vec<u8>> {
        let bytes = match self {
            Build::Bundled(_) => match name { JAR => AGENT.to_vec(), BOOT_JAR => BOOT.to_vec(), _ => return None },
            Build::Downloaded(_, dir) => fs::read(dir.join(name)).ok()?,
        };
        (self.manifest().files.get(name)? == &sha256_hex(&bytes)).then_some(bytes)
    }
}

/// The build bundled into this launcher. A bundle without a `client.json` (an older agent build) counts as
/// version 0.0.0 of this protocol, so any signed release replaces it.
fn bundled() -> Option<Build> {
    if AGENT.is_empty() || BOOT.is_empty() { return None; }
    let mut m = serde_json::from_str::<Manifest>(BUNDLED_MANIFEST)
        .unwrap_or_else(|_| Manifest { version: "0.0.0".into(), protocol: PROTOCOL, files: BTreeMap::new() });
    // the bytes are compiled in, so their hashes are the truth
    m.files = BTreeMap::from([(JAR.to_string(), sha256_hex(AGENT)), (BOOT_JAR.to_string(), sha256_hex(BOOT))]);
    m.usable().then_some(Build::Bundled(m))
}

fn keys() -> impl Iterator<Item = &'static str> {
    CLIENT_KEYS.lines().map(str::trim).filter(|l| l.len() == 64 && l.bytes().all(|b| b.is_ascii_hexdigit()))
}

/// `sig_hex` is a valid client signature of `manifest_bytes` under one of the client keys.
fn signed(manifest_bytes: &[u8], sig_hex: &str) -> bool { signed_by(keys(), manifest_bytes, sig_hex) }

fn signed_by<'a>(keys: impl Iterator<Item = &'a str>, manifest_bytes: &[u8], sig_hex: &str) -> bool {
    let Ok(sig) = hex::decode(sig_hex.trim()) else { return false };
    let msg = [SIGNED_PREFIX, manifest_bytes].concat();
    keys.filter_map(|k| hex::decode(k).ok()).any(|k| ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, k).verify(&msg, &sig).is_ok())
}

/// Signed, usable builds in the store (signature checked again on every read).
fn downloaded(paths: &Paths) -> Vec<Build> {
    let mut out = vec![];
    for e in fs::read_dir(builds_dir(paths)).into_iter().flatten().flatten() {
        let dir = e.path();
        let (Ok(json), Ok(sig)) = (fs::read(dir.join(MANIFEST)), fs::read_to_string(dir.join(SIG))) else { continue };
        if !signed(&json, &sig) { continue; }
        let Ok(m) = serde_json::from_slice::<Manifest>(&json) else { continue };
        if m.usable() && e.file_name().to_str() == Some(m.version.as_str()) { out.push(Build::Downloaded(m, dir)); }
    }
    out
}

/// Every installable build, newest first; the bundled one wins a tie.
fn candidates(paths: &Paths) -> Vec<Build> {
    let mut all: Vec<Build> = bundled().into_iter().collect();
    all.extend(downloaded(paths));
    // stable sort keeps the bundled build ahead of a downloaded one with the same version
    all.sort_by(|a, b| {
        let (a, b) = (&a.manifest().version, &b.manifest().version);
        if newer(a, b) { std::cmp::Ordering::Less } else if newer(b, a) { std::cmp::Ordering::Greater } else { std::cmp::Ordering::Equal }
    });
    all
}

/// Version of the client a launch would use now (None = no client available).
pub fn current_version(paths: &Paths) -> Option<String> { candidates(paths).first().map(|b| b.manifest().version.clone()) }

/// Write `build` into `dir` (only files that differ). Fails if a file can't be written, for example while a
/// running game holds the jar open.
fn install(build: &Build, dir: &Path) -> Result<PathBuf> {
    for name in [BOOT_JAR, JAR] {
        let bytes = build.file(name).ok_or_else(|| anyhow!("{name} of client {} doesn't match its hash", build.manifest().version))?;
        let p = dir.join(name);
        if fs::read(&p).ok().as_deref() != Some(&bytes[..]) { crate::store::write_atomic(&p, &bytes).with_context(|| format!("writing {}", p.display()))?; }
    }
    let json = serde_json::to_vec_pretty(build.manifest())?;
    let p = dir.join(MANIFEST);
    if fs::read(&p).ok().as_deref() != Some(&json[..]) { crate::store::write_atomic(&p, &json)?; }
    // optional: a running helper locks the exe (write fails) and keeps the identical old copy
    let p = dir.join(MEDIA_EXE);
    if cfg!(windows) && !MEDIA.is_empty() && fs::read(&p).ok().as_deref() != Some(MEDIA) { let _ = fs::write(&p, MEDIA); }
    Ok(dir.join(JAR))
}

/// The copy already in `dir`, if it is complete and one this launcher can run.
fn installed(dir: &Path) -> Option<PathBuf> {
    let m: Manifest = serde_json::from_slice(&fs::read(dir.join(MANIFEST)).ok()?).ok()?;
    if !m.usable() { return None; }
    for name in [JAR, BOOT_JAR] { if &sha256_hex(&fs::read(dir.join(name)).ok()?) != m.files.get(name)? { return None; } }
    Some(dir.join(JAR))
}

/// Install the newest usable client into the instance and return its `-javaagent` jar. If the newest can't be
/// written (a running game holds the files), an older build or the copy already there is used. None = no client.
pub fn ensure(paths: &Paths, game_dir: &Path) -> Option<PathBuf> {
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let dir = instance_dir(game_dir);
    for b in candidates(paths) {
        match install(&b, &dir) {
            Ok(jar) => return Some(jar),
            Err(e) => eprintln!("client {}: {e:#}", b.manifest().version),
        }
    }
    installed(&dir)
}

/// A client release on GitHub.
struct Release {
    version: String,
    urls: BTreeMap<&'static str, String>,
}

/// The latest full release of the client repo (None = nothing published yet).
fn latest(c: &reqwest::blocking::Client) -> Result<Option<Release>> {
    let r = c.get(format!("https://api.github.com/repos/{CLIENT_REPO}/releases/latest")).header("Accept", "application/vnd.github+json").send()?;
    if r.status().as_u16() == 404 { return Ok(None); }
    let v: serde_json::Value = r.error_for_status()?.json()?;
    let tag = v["tag_name"].as_str().unwrap_or_default();
    let version = tag.trim_start_matches(['v', 'V']).to_string();
    if !safe_version(&version) { bail!("unexpected client release tag {tag:?}"); }
    let prefix = format!("https://github.com/{CLIENT_REPO}/releases/download/");
    let mut urls = BTreeMap::new();
    for a in v["assets"].as_array().into_iter().flatten() {
        let (Some(name), Some(url)) = (a["name"].as_str(), a["browser_download_url"].as_str()) else { continue };
        let Some(key) = [JAR, BOOT_JAR, MANIFEST, SIG].into_iter().find(|k| *k == name) else { continue };
        if !url.starts_with(&prefix) { bail!("unexpected client download address"); }
        urls.insert(key, url.to_string());
    }
    if urls.len() != 4 { bail!("client release {version} is missing files"); }
    Ok(Some(Release { version, urls }))
}

/// Small text download with a size cap.
fn small(c: &reqwest::blocking::Client, url: &str, cap: u64) -> Result<Vec<u8>> {
    use std::io::Read;
    let mut b = Vec::new();
    c.get(url).send()?.error_for_status()?.take(cap + 1).read_to_end(&mut b)?;
    if b.len() as u64 > cap { bail!("{url} is too large"); }
    Ok(b)
}

/// Fetch, verify and store a client release into `<data>/client/builds/<version>/`.
fn download(c: &reqwest::blocking::Client, paths: &Paths, rel: &Release) -> Result<Option<Manifest>> {
    let json = small(c, &rel.urls[MANIFEST], 64 * 1024)?;
    let sig = String::from_utf8(small(c, &rel.urls[SIG], 512)?)?;
    if !signed(&json, &sig) { bail!("client {}: signature doesn't match, not installing it", rel.version); }
    let m: Manifest = serde_json::from_slice(&json).context("client.json")?;
    if m.version != rel.version { bail!("client release {} carries version {}", rel.version, m.version); }
    // made for another launcher (newer protocol): keep the current client until a launcher update arrives
    if !m.usable() { return Ok(None); }
    let root = builds_dir(paths);
    let part = root.join(format!(".{}.part", m.version));
    let _ = fs::remove_dir_all(&part);
    fs::create_dir_all(&part)?;
    for name in [JAR, BOOT_JAR] {
        crate::download::fetch(c, &rel.urls[name], &part.join(name), crate::download::Check::Sha256(&m.files[name]))?;
    }
    fs::write(part.join(MANIFEST), &json)?;
    fs::write(part.join(SIG), sig.trim())?;
    let dest = root.join(&m.version);
    let _ = fs::remove_dir_all(&dest);
    fs::rename(&part, &dest)?;
    Ok(Some(m))
}

/// Keep the two newest downloaded builds (the previous one stays for a game still running it).
fn prune(paths: &Paths) {
    let builds = candidates(paths);
    let keep: Vec<PathBuf> = builds.iter().filter_map(|b| match b { Build::Downloaded(_, d) => Some(d.clone()), _ => None }).take(2).collect();
    for e in fs::read_dir(builds_dir(paths)).into_iter().flatten().flatten() {
        if !keep.contains(&e.path()) { let _ = fs::remove_dir_all(e.path()); }
    }
}

/// Install the newest client into every instance that already has one. Instances that can't be written now
/// (game running) get it at their next launch.
pub fn install_all(paths: &Paths) {
    for e in fs::read_dir(paths.instances()).into_iter().flatten().flatten() {
        if instance_dir(&e.path()).is_dir() { let _ = ensure(paths, &e.path()); }
    }
}

/// Launcher start: if the client repo has a newer release this launcher can run, download it, check its
/// signature and hashes, and install it into every instance. Always on; Some(version) = updated.
pub fn update(paths: &Paths, c: &reqwest::blocking::Client) -> Result<Option<String>> {
    // the pre-instance layout kept one shared copy here; launches no longer use it
    let _ = fs::remove_dir_all(paths.root.join("agent"));
    let current = current_version(paths);
    let Some(rel) = latest(c)? else { return Ok(None) };
    if current.as_deref().is_some_and(|cur| !newer(&rel.version, cur)) { return Ok(None); }
    // no lock while downloading, so a launch meanwhile isn't held up (it uses the current build)
    let Some(m) = download(c, paths, &rel)? else { return Ok(None) };
    { let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner()); prune(paths); }
    install_all(paths);
    Ok(Some(m.version))
}

/// Folder name of the built-in resource pack (nametag icon font) in an instance's `resourcepacks/`.
pub const PACK: &str = "Nether Client";
const PACK_META: &str = r#"{"pack":{"description":"Nether Client icons","pack_format":75,"supported_formats":[1,999],"min_format":1,"max_format":999}}"#;
const PACK_FONT: &str = r#"{"providers":[{"type":"bitmap","file":"nether:font/icon.png","ascent":7,"height":8,"chars":[""]}]}"#;
const PACK_ICON: &[u8] = include_bytes!("../res/nether-pack/icon.png");

/// Unicode page e0 for 1.8.9-1.12.2 (no font providers): the 17x24 logo in a 384 px page (one 16-unit cell = 24 px).
const PACK_PAGE: &[u8] = include_bytes!("../res/nether-pack/unicode_page_e0.png");

/// Write the built-in pack into the instance and keep it switched on in options.txt (the agent only shows the
/// nametag icon once its font is loaded). Missing options.txt = first run: the pack is enabled on the next launch.
/// `legacy_jar` = client jar of a pre-1.13 version: the icon goes on unicode page e0 with the jar's glyph_sizes.bin
/// (U+E000 given a full-cell width); those files are removed for newer versions (their unicode font must stay vanilla).
pub fn ensure_pack(game_dir: &Path, legacy_jar: Option<&Path>) {
    let dir = game_dir.join("resourcepacks").join(PACK);
    let (page, sizes) = (dir.join("assets/minecraft/textures/font/unicode_page_e0.png"), dir.join("assets/minecraft/font/glyph_sizes.bin"));
    let glyphs = legacy_jar.and_then(glyph_sizes);
    let mut files: Vec<(PathBuf, &[u8])> = vec![
        (dir.join("pack.mcmeta"), PACK_META.as_bytes()),
        (dir.join("assets/nether/font/icon.json"), PACK_FONT.as_bytes()),
        (dir.join("assets/nether/textures/font/icon.png"), PACK_ICON),
    ];
    match &glyphs {
        Some(g) => { files.push((page, PACK_PAGE)); files.push((sizes, g)); }
        None => { let _ = std::fs::remove_file(&page); let _ = std::fs::remove_file(&sizes); }
    }
    for (f, b) in files {
        if std::fs::read(&f).ok().as_deref() == Some(b) { continue; }
        if let Some(d) = f.parent() { let _ = std::fs::create_dir_all(d); }
        let _ = std::fs::write(&f, b);
    }
    let opts = game_dir.join("options.txt");
    let Ok(text) = std::fs::read_to_string(&opts) else { return };
    // 1.13+: "file/<name>" next to "vanilla". Pre-1.13: folder names, and the pack must also be listed as
    // incompatible or the game drops it (its pack_format is not the version's own).
    let (entry, keys, base): (String, &[&str], &[&str]) = if legacy_jar.is_some() { (PACK.into(), &["resourcePacks", "incompatibleResourcePacks"], &[]) }
        else { (format!("file/{PACK}"), &["resourcePacks"], &["vanilla"]) };
    let mut found = vec![false; keys.len()];
    let lines: Vec<String> = text.lines().map(|l| {
        let Some((k, v)) = l.split_once(':') else { return l.to_string() };
        let Some(i) = keys.iter().position(|x| *x == k) else { return l.to_string() };
        found[i] = true;
        let mut list = serde_json::from_str::<Vec<String>>(v).unwrap_or_else(|_| base.iter().map(|s| s.to_string()).collect());
        if !list.contains(&entry) { list.push(entry.clone()); }
        format!("{k}:{}", serde_json::to_string(&list).unwrap_or_default())
    }).collect();
    let mut out = lines.join("\n");
    for (k, f) in keys.iter().zip(found) {
        if f { continue; }
        let mut list: Vec<String> = base.iter().map(|s| s.to_string()).collect();
        list.push(entry.clone());
        out.push_str(&format!("\n{k}:{}", serde_json::to_string(&list).unwrap_or_default()));
    }
    out.push('\n');
    if out != text { let _ = crate::store::write_atomic(&opts, out.as_bytes()); }
}

/// The jar's font/glyph_sizes.bin with U+E000 spanning the whole cell (columns 0-15).
fn glyph_sizes(jar: &Path) -> Option<Vec<u8>> {
    use std::io::Read;
    let mut z = zip::ZipArchive::new(std::fs::File::open(jar).ok()?).ok()?;
    let mut b = Vec::new();
    z.by_name("assets/minecraft/font/glyph_sizes.bin").ok()?.read_to_end(&mut b).ok()?;
    *b.get_mut(0xE000)? = 0x0B; // columns 0..11 of 16: the logo's 17 of 24 px
    Some(b)
}

#[cfg(test)]
mod pack_tests {
    #[test]
    fn enables_pack_once() {
        let d = std::env::temp_dir().join(format!("nether-pack-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join("options.txt"), "version:4671\nresourcePacks:[\"vanilla\",\"file/Faithful.zip\"]\nfov:0.0\n").unwrap();
        super::ensure_pack(&d, None);
        super::ensure_pack(&d, None);
        let t = std::fs::read_to_string(d.join("options.txt")).unwrap();
        assert!(t.contains(r#"resourcePacks:["vanilla","file/Faithful.zip","file/Nether Client"]"#), "{t}");
        assert!(d.join("resourcepacks/Nether Client/assets/nether/font/icon.json").exists());
        let _ = std::fs::remove_dir_all(&d);
    }
}

#[cfg(test)]
mod client_tests {
    use super::*;
    use ring::signature::{Ed25519KeyPair, KeyPair};

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("nether-client-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn manifest(version: &str, protocol: u32, jar: &[u8], boot: &[u8]) -> Manifest {
        Manifest { version: version.into(), protocol, files: BTreeMap::from([(JAR.into(), sha256_hex(jar)), (BOOT_JAR.into(), sha256_hex(boot))]) }
    }

    #[test]
    fn signature_covers_the_manifest() {
        let rng = ring::rand::SystemRandom::new();
        let kp = Ed25519KeyPair::from_pkcs8(Ed25519KeyPair::generate_pkcs8(&rng).unwrap().as_ref()).unwrap();
        let key = hex::encode(kp.public_key().as_ref());
        let json = serde_json::to_vec(&manifest("1.2.0", PROTOCOL, b"a", b"b")).unwrap();
        let sig = hex::encode(kp.sign(&[SIGNED_PREFIX, &json[..]].concat()).as_ref());
        assert!(signed_by([key.as_str()].into_iter(), &json, &sig));
        let mut bad = json.clone();
        bad[5] ^= 1;
        assert!(!signed_by([key.as_str()].into_iter(), &bad, &sig));
        assert!(!signed_by(std::iter::empty(), &json, &sig));
        // a signature without the prefix (any other signed blob) doesn't pass for a client manifest
        let raw = hex::encode(kp.sign(&json).as_ref());
        assert!(!signed_by([key.as_str()].into_iter(), &json, &raw));
    }

    #[test]
    fn only_this_protocol_is_usable() {
        assert!(manifest("1.0.0", PROTOCOL, b"a", b"b").usable());
        assert!(!manifest("1.0.0", PROTOCOL + 1, b"a", b"b").usable());
        assert!(!manifest("../x", PROTOCOL, b"a", b"b").usable());
        assert!(!Manifest { version: "1.0.0".into(), protocol: PROTOCOL, files: BTreeMap::new() }.usable());
    }

    #[test]
    fn installs_into_the_instance_and_checks_hashes() {
        let d = tmp("install");
        let build_dir = d.join("build");
        fs::create_dir_all(&build_dir).unwrap();
        fs::write(build_dir.join(JAR), b"agent v2").unwrap();
        fs::write(build_dir.join(BOOT_JAR), b"boot v2").unwrap();
        let b = Build::Downloaded(manifest("2.0.0", PROTOCOL, b"agent v2", b"boot v2"), build_dir.clone());
        let inst = instance_dir(&d.join("game"));
        let jar = install(&b, &inst).unwrap();
        assert_eq!(jar, inst.join(JAR));
        assert_eq!(fs::read(inst.join(BOOT_JAR)).unwrap(), b"boot v2");
        assert_eq!(installed(&inst), Some(inst.join(JAR)));
        // a tampered build file is refused, and the installed copy stays as it was
        fs::write(build_dir.join(JAR), b"evil").unwrap();
        assert!(install(&b, &inst).is_err());
        assert_eq!(fs::read(inst.join(JAR)).unwrap(), b"agent v2");
        // a tampered installed copy is not used
        fs::write(inst.join(JAR), b"evil").unwrap();
        assert_eq!(installed(&inst), None);
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn unsigned_builds_in_the_store_are_ignored() {
        let d = tmp("store");
        let paths = Paths { root: d.clone() };
        let dir = builds_dir(&paths).join("9.0.0");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join(JAR), b"j").unwrap();
        fs::write(dir.join(BOOT_JAR), b"b").unwrap();
        fs::write(dir.join(MANIFEST), serde_json::to_vec(&manifest("9.0.0", PROTOCOL, b"j", b"b")).unwrap()).unwrap();
        fs::write(dir.join(SIG), "00".repeat(64)).unwrap();
        assert!(downloaded(&paths).is_empty());
        let _ = fs::remove_dir_all(&d);
    }
}
