//! Horizon (horizon-rust/horizon, MIT): Minecraft Java Edition 26.3 rewritten in Rust. A separate game, not the Java
//! client, so it runs without the Nether agent. We start it the way its Launch API 0.1 asks a third-party launcher to
//! (docs/api/launch.md and launch-guide.md in their repository):
//! 1. builds are the GitHub release tagged `nightly`, one archive per platform, checked against `SHA256SUMS` signed
//!    with Ed25519 (`SHA256SUMS.v2.sig`, over `horizon release nightly-<date>-<commit>\n` + the sums);
//! 2. `horizon-prepare` turns the owner's own 26.3 files (version JSON, client jar, libraries, assets, plus the server
//!    jar Mojang serves) into the resources the game reads; it downloads nothing itself;
//! 3. `horizon-client --resources … --game-dir … --home … --session-stdin` with the session JSON on standard input.
//! Only for a signed-in owner of Java Edition (the caller gates on `auth::launch_gate`), as Horizon requires.

use std::fs;
use std::io::{BufRead, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::AtomicBool;
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use base64::Engine;
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::download::{check_cancel, fetch, Check};
use crate::paths::Paths;
use crate::{java, Progress, Session};

/// The one Minecraft version Horizon plays.
pub const MINECRAFT: &str = "26.3";
const RELEASE_API: &str = "https://api.github.com/repos/horizon-rust/horizon/releases/tags/nightly";
pub const PROJECT_URL: &str = "https://github.com/horizon-rust/horizon";
/// crates/horizon-release-sign/release-keys.txt (one base64 Ed25519 key per line).
const RELEASE_KEYS: &[&str] = &["MU4oiIQcCxkZvbwSjjXs7SySCuM6ywRQ3nGaJYZG0gw="];
const MAX_BUILD: u64 = 512 * 1024 * 1024;
/// The last line on stderr when the game saved a crash report (launch-reference.md).
const CRASH_LINE: &str = "Horizon: crash report saved to ";

#[cfg(all(windows, target_arch = "aarch64"))]
const ASSET: &str = "horizon-windows-arm64.zip";
#[cfg(not(all(windows, target_arch = "aarch64")))]
const ASSET: &str = "horizon-windows-x64.zip";

const EXE: &str = if cfg!(windows) { ".exe" } else { "" };

/// `<data>/horizon`: `builds/<version>/` (unpacked builds), `prepared/` (resources, shared by every build),
/// `game/` (saves, options, packs, logs), `server-<mc>.jar`, `state.json`.
pub fn root(paths: &Paths) -> PathBuf { paths.root.join("horizon") }
pub fn game_dir(paths: &Paths) -> PathBuf { root(paths).join("game") }
fn prepared(paths: &Paths) -> PathBuf { root(paths).join("prepared") }
fn builds(paths: &Paths) -> PathBuf { root(paths).join("builds") }
fn state_file(paths: &Paths) -> PathBuf { root(paths).join("state.json") }

/// What we keep between starts.
#[derive(Serialize, Deserialize, Default, Clone, Debug)]
pub struct State {
    /// Installed build (`nightly-2026-11-20-0a875da`), "" = none.
    #[serde(default)]
    pub build: String,
    #[serde(default)]
    pub secs: i64,
    #[serde(default)]
    pub last_ts: i64,
}

pub fn state(paths: &Paths) -> State {
    fs::read(state_file(paths)).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}

pub fn save_state(paths: &Paths, s: &State) -> Result<()> {
    fs::create_dir_all(root(paths))?;
    let tmp = state_file(paths).with_extension("json.tmp");
    fs::write(&tmp, serde_json::to_vec_pretty(s)?)?;
    fs::rename(&tmp, state_file(paths))?;
    Ok(())
}

/// The installed build's folder, when it is whole.
pub fn installed(paths: &Paths) -> Option<(String, PathBuf)> {
    let b = state(paths).build;
    let dir = builds(paths).join(&b);
    (!b.is_empty() && is_build(&dir)).then_some((b, dir))
}

fn is_build(dir: &Path) -> bool {
    dir.join(format!("horizon-client{EXE}")).is_file() && dir.join(format!("horizon-prepare{EXE}")).is_file() && dir.join("research-lock.json").is_file()
}

/// "nightly-2026-11-20-0a875da" -> "2026-11-20 · 0a875da".
pub fn pretty(build: &str) -> String {
    match build.strip_prefix("nightly-") {
        Some(r) if r.len() > 11 => format!("{} · {}", &r[..10], &r[11..]),
        Some(r) => r.to_string(),
        None => build.to_string(),
    }
}

/// The newest build on GitHub.
pub struct Build { pub version: String, archive: String, size: u64, sums: String, sig: String }

#[derive(Deserialize)]
struct Rel { name: String, assets: Vec<RelAsset> }
#[derive(Deserialize)]
struct RelAsset { name: String, browser_download_url: String, #[serde(default)] size: u64 }

/// The version a Nightly's title names, strictly (`Nightly 2026-10-28 · 0c02067` -> `nightly-2026-10-28-0c02067`), as
/// horizon-release-sign's `build_title_version`: its v2 signature is for exactly that version.
fn title_version(title: &str) -> Option<String> {
    let (date, commit) = title.strip_prefix("Nightly ")?.split_once(" · ")?;
    let date_ok = date.len() == 10 && date.bytes().enumerate().all(|(i, b)| if i == 4 || i == 7 { b == b'-' } else { b.is_ascii_digit() });
    let commit_ok = (7..=40).contains(&commit.len()) && commit.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b));
    (date_ok && commit_ok).then(|| format!("nightly-{date}-{commit}"))
}

/// Ask GitHub for the current Nightly (cached for 30 minutes: the API allows 60 calls an hour without a token).
pub fn latest(c: &Client) -> Result<Build> {
    let v = crate::download::get_cached(c, RELEASE_API, &[], Duration::from_secs(1800))?;
    let r: Rel = serde_json::from_value(v).context("reading Horizon's release list")?;
    let version = title_version(&r.name).ok_or_else(|| anyhow!("Horizon's newest build has a title we don't recognise ({})", r.name))?;
    let url = |n: &str| r.assets.iter().find(|a| a.name == n).map(|a| (a.browser_download_url.clone(), a.size));
    let ok = |u: &str| u.starts_with("https://github.com/horizon-rust/horizon/releases/download/");
    let (archive, size) = url(ASSET).ok_or_else(|| anyhow!("Horizon's newest build has no Windows download yet"))?;
    let (sums, _) = url("SHA256SUMS").ok_or_else(|| anyhow!("Horizon's newest build has no checksums"))?;
    let (sig, _) = url("SHA256SUMS.v2.sig").ok_or_else(|| anyhow!("Horizon's newest build isn't signed"))?;
    if ![&archive, &sums, &sig].iter().all(|u| ok(u)) { bail!("Horizon's download isn't on its GitHub releases"); }
    Ok(Build { version, archive, size, sums, sig })
}

/// `sig_text` (one base64 signature per line) signs `horizon release <version>\n` + `sums` with a release key.
fn signed(version: &str, sums: &[u8], sig_text: &str) -> bool {
    let mut msg = format!("horizon release {version}\n").into_bytes();
    msg.extend_from_slice(sums);
    let b64 = base64::engine::general_purpose::STANDARD;
    sig_text.lines().map(str::trim).filter(|l| !l.is_empty()).filter_map(|l| b64.decode(l).ok()).any(|sig| {
        RELEASE_KEYS.iter().filter_map(|k| b64.decode(k).ok()).any(|key| ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, key).verify(&msg, &sig).is_ok())
    })
}

/// The SHA-256 `sums` lists for `name` (`<hex>  <name>` lines).
fn listed(sums: &str, name: &str) -> Option<String> {
    sums.lines().find_map(|l| {
        let (h, n) = l.split_once(char::is_whitespace)?;
        (n.trim().trim_start_matches('*') == name && h.len() == 64).then(|| h.to_ascii_lowercase())
    })
}

/// Download, verify and unpack `b` into `builds/<version>/`, make it the installed build and remove older ones.
pub fn install(c: &Client, paths: &Paths, b: &Build, progress: &Progress, cancel: Option<&AtomicBool>) -> Result<PathBuf> {
    let dir = builds(paths).join(&b.version);
    if is_build(&dir) { set_build(paths, &b.version)?; return Ok(dir); }
    progress("Checking Horizon's signature", 0.02);
    let sums = c.get(&b.sums).send()?.error_for_status()?.bytes()?;
    let sig = c.get(&b.sig).send()?.error_for_status()?.text()?;
    if !signed(&b.version, &sums, &sig) { bail!("Horizon's download isn't signed by its release key: not installing it"); }
    let want = listed(&String::from_utf8_lossy(&sums), ASSET).ok_or_else(|| anyhow!("Horizon's checksums don't list {ASSET}"))?;

    fs::create_dir_all(builds(paths))?;
    let zip = builds(paths).join(format!("{}.zip.part", b.version));
    let res = download(c, &b.archive, &zip, b.size, &want, progress, cancel).and_then(|_| unpack(&zip, &dir, progress));
    let _ = fs::remove_file(&zip);
    res?;
    set_build(paths, &b.version)?;
    // keep only this build (Horizon's resources are re-checked against it before every start)
    if let Ok(rd) = fs::read_dir(builds(paths)) {
        for e in rd.flatten() { if e.path() != dir { let _ = if e.path().is_dir() { fs::remove_dir_all(e.path()) } else { fs::remove_file(e.path()) }; } }
    }
    Ok(dir)
}

fn set_build(paths: &Paths, v: &str) -> Result<()> {
    let mut s = state(paths);
    s.build = v.to_string();
    save_state(paths, &s)
}

fn download(c: &Client, url: &str, dest: &Path, size: u64, sha256: &str, progress: &Progress, cancel: Option<&AtomicBool>) -> Result<()> {
    let mut resp = c.get(url).timeout(Duration::from_secs(1800)).send()?.error_for_status()?;
    let total = resp.content_length().unwrap_or(size).max(1);
    let mut out = std::io::BufWriter::new(fs::File::create(dest)?);
    let (mut h, mut n, mut buf, mut shown) = (Sha256::new(), 0u64, vec![0u8; 256 * 1024], 0u64);
    loop {
        check_cancel(cancel)?;
        let k = resp.read(&mut buf)?;
        if k == 0 { break; }
        n += k as u64;
        if n > MAX_BUILD { bail!("Horizon's download is larger than expected"); }
        h.update(&buf[..k]);
        out.write_all(&buf[..k])?;
        if n - shown > 2 * 1024 * 1024 {
            shown = n;
            progress(&format!("Downloading Horizon ({} / {} MB)", n / 1_048_576, total / 1_048_576), 0.03 + 0.5 * (n as f32 / total as f32).min(1.0));
        }
    }
    out.flush()?;
    if !hex::encode(h.finalize()).eq_ignore_ascii_case(sha256) { bail!("Horizon's download doesn't match its signed checksum"); }
    Ok(())
}

/// Unpack the archive's single top folder (`horizon-windows-x64/`) into `dir`, through a temp folder.
fn unpack(zip: &Path, dir: &Path, progress: &Progress) -> Result<()> {
    progress("Unpacking Horizon", 0.55);
    let tmp = dir.with_extension("unpacking");
    let _ = fs::remove_dir_all(&tmp);
    let mut z = zip::ZipArchive::new(fs::File::open(zip)?).context("opening Horizon's archive")?;
    for i in 0..z.len() {
        let mut f = z.by_index(i)?;
        let Some(name) = f.enclosed_name() else { bail!("Horizon's archive has an unsafe path") };
        // drop the top folder
        let rel: PathBuf = name.components().skip(1).collect();
        if rel.as_os_str().is_empty() { continue; }
        let out = tmp.join(rel);
        if f.is_dir() { fs::create_dir_all(&out)?; continue; }
        if let Some(p) = out.parent() { fs::create_dir_all(p)?; }
        std::io::copy(&mut f, &mut fs::File::create(&out)?)?;
    }
    if !is_build(&tmp) { let _ = fs::remove_dir_all(&tmp); bail!("Horizon's archive is missing the game"); }
    let _ = fs::remove_dir_all(dir);
    fs::rename(&tmp, dir)?;
    Ok(())
}

#[cfg(windows)]
fn quiet(cmd: &mut Command) { use std::os::windows::process::CommandExt; cmd.creation_flags(0x0800_0000); }
#[cfg(not(windows))]
fn quiet(_: &mut Command) {}

/// `horizon-prepare --check`: the prepared resources are complete and current for this build.
pub fn ready(paths: &Paths, build: &Path) -> bool {
    let mut cmd = Command::new(build.join(format!("horizon-prepare{EXE}")));
    quiet(&mut cmd);
    cmd.current_dir(build).arg("--root").arg(build).arg("--check").arg("--output").arg(prepared(paths));
    cmd.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).status().is_ok_and(|s| s.success())
}

/// Prepare the resources from the player's own 26.3 (downloaded into the shared folders like any version), Mojang's
/// Java the version asks for, and the 26.3 server jar. Only called for a signed-in owner.
pub fn prepare(c: &Client, paths: &Paths, build: &Path, progress: &Progress, cancel: Option<&AtomicBool>) -> Result<()> {
    let p2 = progress.clone();
    let files: Progress = std::sync::Arc::new(move |m, f| p2(&format!("Minecraft {MINECRAFT} · {m}"), 0.56 + 0.12 * f));
    let v = crate::launch::install_vanilla(c, paths, MINECRAFT, &files, cancel)?;
    check_cancel(cancel)?;
    let major = lock_java(build).unwrap_or_else(|| v.java.as_ref().map(|j| j.major).unwrap_or(25));
    let component = v.java.as_ref().filter(|j| j.major == major).map(|j| j.component.clone()).unwrap_or_else(|| java::component(major).to_string());
    let exe = java::find_or_install(c, paths, &component, major, &files, cancel)?;
    let home = exe.parent().and_then(Path::parent).ok_or_else(|| anyhow!("can't find Java {major}'s folder"))?.to_path_buf();
    let server = v.downloads.server.as_ref().ok_or_else(|| anyhow!("Minecraft {MINECRAFT} has no server download"))?;
    let jar = root(paths).join(format!("server-{MINECRAFT}.jar"));
    progress(&format!("Downloading the {MINECRAFT} server jar from Mojang"), 0.69);
    fetch(c, &server.url, &jar, Check::Sha1(&server.sha1))?;
    check_cancel(cancel)?;

    progress("Preparing Horizon from your Minecraft", 0.7);
    let mut cmd = Command::new(build.join(format!("horizon-prepare{EXE}")));
    quiet(&mut cmd);
    let mut path = vec![home.join("bin")];
    path.extend(std::env::var_os("PATH").iter().flat_map(std::env::split_paths));
    cmd.env("JAVA_HOME", &home);
    if let Ok(p) = std::env::join_paths(path) { cmd.env("PATH", p); }
    cmd.current_dir(build).arg("--root").arg(build)
        .arg("--prepare").arg("--server-jar").arg(&jar)
        .arg("--minecraft-dir").arg(&paths.root)
        .arg("--java-home").arg(&home)
        .arg("--output").arg(prepared(paths))
        .args(["--complete", "--progress", "json"])
        .stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped());
    let mut child = cmd.spawn().context("starting horizon-prepare")?;
    let log_path = root(paths).join("prepare.log");
    let mut log = fs::File::create(&log_path).ok();
    // stderr is human text: keep it in the log without blocking the child
    let err = child.stderr.take().map(|e| std::thread::spawn(move || { let mut s = String::new(); let _ = std::io::BufReader::new(e).read_to_string(&mut s); s }));
    let (mut error, mut frac) = (None, 0.0f32);
    if let Some(out) = child.stdout.take() {
        for line in std::io::BufReader::new(out).lines().map_while(Result::ok) {
            if let Some(l) = log.as_mut() { let _ = writeln!(l, "{line}"); }
            if cancel.is_some_and(|c| c.load(std::sync::atomic::Ordering::SeqCst)) { let _ = child.kill(); break; }
            let Ok(j) = serde_json::from_str::<serde_json::Value>(&line) else { continue };
            if let Some(f) = j["fraction"].as_f64() { frac = f.clamp(0.0, 1.0) as f32; }
            if let Some(e) = j["error"].as_str() { error = Some(e.to_string()); }
            progress("Preparing Horizon from your Minecraft (first time only)", 0.7 + 0.28 * frac);
        }
    }
    let status = child.wait()?;
    let stderr = err.and_then(|h| h.join().ok()).unwrap_or_default();
    if let Some(l) = log.as_mut() { let _ = writeln!(l, "--- stderr ---\n{stderr}"); }
    check_cancel(cancel)?;
    if !status.success() {
        let why = error.or_else(|| stderr.lines().rev().find(|l| !l.trim().is_empty()).map(|l| l.trim_start_matches("error: ").to_string()));
        bail!("{}", why.unwrap_or_else(|| format!("horizon-prepare stopped ({status})")));
    }
    Ok(())
}

/// The Java major research-lock.json pins (`minecraft.java_major`).
fn lock_java(build: &Path) -> Option<u32> {
    let j: serde_json::Value = serde_json::from_slice(&fs::read(build.join("research-lock.json")).ok()?).ok()?;
    j["minecraft"]["java_major"].as_u64().map(|m| m as u32)
}

/// Start `horizon-client` for the session (standard input, never the command line). stderr goes to
/// `game/logs/launcher.log`, where `crash_report` finds the report's path. An offline (dev) session plays
/// single-player only.
pub fn launch(paths: &Paths, build: &Path, session: &Session, window: Option<(u32, u32)>) -> Result<Child> {
    let game = game_dir(paths);
    fs::create_dir_all(game.join("logs"))?;
    let log = fs::File::create(game.join("logs").join("launcher.log")).context("creating launcher.log")?;
    let mut cmd = Command::new(build.join(format!("horizon-client{EXE}")));
    quiet(&mut cmd);
    cmd.current_dir(&game).arg("--resources").arg(prepared(paths)).arg("--game-dir").arg(&game).arg("--home").arg(&game);
    if let Some((w, h)) = window { cmd.args(["--width", &w.to_string(), "--height", &h.to_string()]); }
    let online = session.user_type == "msa";
    if online { cmd.arg("--session-stdin"); }
    cmd.stdin(if online { Stdio::piped() } else { Stdio::null() }).stdout(Stdio::from(log.try_clone()?)).stderr(Stdio::from(log));
    let mut child = cmd.spawn().context("starting horizon-client")?;
    if online {
        let json = serde_json::json!({ "name": session.name, "uuid": session.uuid, "access_token": session.access_token, "user_type": "msa" });
        if let Some(mut i) = child.stdin.take() { i.write_all(json.to_string().as_bytes())?; } // dropped = closed
    }
    Ok(child)
}

/// After an exit with code 1 / 101: the crash report the game named on its last stderr line.
pub fn crash_report(paths: &Paths) -> Option<PathBuf> {
    let s = fs::read_to_string(game_dir(paths).join("logs").join("launcher.log")).ok()?;
    s.lines().rev().find_map(|l| l.strip_prefix(CRASH_LINE)).map(|p| PathBuf::from(p.trim())).filter(|p| p.is_file())
}

/// Exit code 2: the option the game refused (the last stderr line).
pub fn refused(paths: &Paths) -> Option<String> {
    let s = fs::read_to_string(game_dir(paths).join("logs").join("launcher.log")).ok()?;
    s.lines().rev().find(|l| l.starts_with("Horizon:")).map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn titles_and_sums() {
        assert_eq!(title_version("Nightly 2026-11-20 · 0a875da").as_deref(), Some("nightly-2026-11-20-0a875da"));
        assert_eq!(title_version("Nightly 2026-11-20"), None);
        assert_eq!(title_version("Snapshot 2026-11-20 · 0a875da"), None);
        assert_eq!(pretty("nightly-2026-11-20-0a875da"), "2026-11-20 · 0a875da");
        let sums = "b52c  horizon-linux-x86_64.tar.gz\n5514bc94f1d2c9b370f4444726236ad9e303d2d34e1323593393e7ebe07abac3  horizon-windows-x64.zip\n";
        assert_eq!(listed(sums, "horizon-windows-x64.zip").as_deref(), Some("5514bc94f1d2c9b370f4444726236ad9e303d2d34e1323593393e7ebe07abac3"));
        assert_eq!(listed(sums, "horizon-linux-x86_64.tar.gz"), None);
    }

    /// The live Nightly verifies with the release key and lists our archive.
    /// `cargo test -p nether-core horizon::tests::live -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn live() {
        let c = crate::download::client();
        let b = latest(&c).unwrap();
        let sums = c.get(&b.sums).send().unwrap().bytes().unwrap();
        let sig = c.get(&b.sig).send().unwrap().text().unwrap();
        assert!(signed(&b.version, &sums, &sig), "signature for {}", b.version);
        assert!(!signed("nightly-2000-01-01-0000000", &sums, &sig));
        assert!(listed(&String::from_utf8_lossy(&sums), ASSET).is_some());
        println!("{} {}", b.version, b.archive);
    }

    /// Downloads, verifies and unpacks the live build into a temp data folder (runs nothing from it).
    /// `cargo test -p nether-core horizon::tests::live_install -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn live_install() {
        let paths = Paths { root: std::env::temp_dir().join(format!("nc-horizon-{}", std::process::id())) };
        let c = crate::download::client();
        let b = latest(&c).unwrap();
        let p: Progress = std::sync::Arc::new(|_, _| {});
        let dir = install(&c, &paths, &b, &p, None).unwrap();
        assert!(is_build(&dir));
        assert_eq!(installed(&paths).map(|(v, _)| v), Some(b.version.clone()));
        assert_eq!(lock_java(&dir), Some(25));
        let _ = fs::remove_dir_all(&paths.root);
    }
}
