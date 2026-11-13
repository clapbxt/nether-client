//! Host: standalone local dedicated servers (`<root>/servers/<slug>/`), several may run at once.
//! Kinds: vanilla (Mojang jar), fabric (server launcher, mods in `mods/`), paper (PaperMC build, plugins in `plugins/`).
//! Install the jar, start it with the right Java, watch its log, manage properties/whitelist/ops/worlds/backups.
//! The EULA is never accepted here on its own: only `accept_eula` (called on the user's say-so) writes `eula=true`.
use std::collections::VecDeque;
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, bail, Context, Result};
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::download::{fetch, get_json, Check};
use crate::paths::Paths;
use crate::{instances, java, mojang, Progress};

const FABRIC_META: &str = "https://meta.fabricmc.net/v2/versions";
const PAPER_API: &str = "https://fill.papermc.io/v3/projects/paper";
/// Server kinds, as stored in `nether-host.json`.
pub const KINDS: [&str; 3] = ["vanilla", "fabric", "paper"];
const CONSOLE_PENDING_MAX: usize = 5000;
const CONSOLE_HISTORY_MAX: usize = 2000;

/// Returned by `start` while `eula.txt` does not say `eula=true`. Test with `is_eula_error`.
#[derive(Debug)]
pub struct EulaNotAccepted;

impl std::fmt::Display for EulaNotAccepted {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "the Minecraft EULA (https://aka.ms/MinecraftEULA) has not been accepted")
    }
}
impl std::error::Error for EulaNotAccepted {}

/// True when `e` is the distinct "EULA not accepted" error from `start`.
pub fn is_eula_error(e: &anyhow::Error) -> bool { e.downcast_ref::<EulaNotAccepted>().is_some() }

// ---- servers: folders under <root>/servers ----

/// Where standalone servers live.
pub fn servers_root(paths: &Paths) -> PathBuf { paths.root.join("servers") }

/// Launcher-side facts about one server (`nether-host.json`).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Meta {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub mc: String,
    /// one of `KINDS`
    #[serde(default = "vanilla")]
    pub kind: String,
    #[serde(default = "two")]
    pub ram_gb: u32,
    #[serde(default = "yes")]
    pub upnp: bool,
}
fn vanilla() -> String { "vanilla".into() }
fn two() -> u32 { 2 }
fn yes() -> bool { true }

impl Default for Meta {
    fn default() -> Self { Meta { name: String::new(), mc: String::new(), kind: vanilla(), ram_gb: 2, upnp: true } }
}

const META: &str = "nether-host.json";

/// Read `nether-host.json` (defaults when absent).
pub fn read_meta(dir: &Path) -> Meta {
    fs::read(dir.join(META)).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}

/// Write `nether-host.json`.
pub fn write_meta(dir: &Path, m: &Meta) -> Result<()> {
    fs::create_dir_all(dir)?;
    fs::write(dir.join(META), serde_json::to_vec_pretty(m)?)?;
    Ok(())
}

/// One server on disk.
#[derive(Clone, Debug)]
pub struct Entry { pub dir: PathBuf, pub meta: Meta }

fn slug(name: &str) -> String {
    let s: String = name.chars().map(|c| if c.is_ascii_alphanumeric() { c.to_ascii_lowercase() } else { '-' }).collect();
    let s = s.trim_matches('-').to_string();
    if s.is_empty() { "server".into() } else { s }
}

fn free_dir(paths: &Paths, name: &str) -> PathBuf {
    let (root, base) = (servers_root(paths), slug(name));
    let mut d = root.join(&base);
    let mut n = 2;
    while d.exists() { d = root.join(format!("{base}-{n}")); n += 1; }
    d
}

/// Older builds kept one server inside each instance (`<instance>/server/`): move those into `servers/` once.
fn migrate(paths: &Paths) {
    for i in instances::load(paths).unwrap_or_default() {
        let old = paths.instance(&i.name).join("server");
        let Ok(b) = fs::read(manifest_file(&old)) else { continue };
        let Ok(inst) = serde_json::from_slice::<Installed>(&b) else { continue };
        let mut m = read_meta(&old);
        m.name = i.name.clone();
        m.mc = inst.mc.clone();
        m.kind = inst.loader.clone();
        let to = free_dir(paths, &i.name);
        if fs::create_dir_all(servers_root(paths)).is_err() || fs::rename(&old, &to).is_err() { continue; }
        let _ = write_meta(&to, &m);
    }
}

/// Every server, by name (moves old per-instance servers in first).
pub fn list(paths: &Paths) -> Vec<Entry> {
    migrate(paths);
    let mut v: Vec<Entry> = fs::read_dir(servers_root(paths)).into_iter().flatten().flatten()
        .filter(|e| e.path().join(META).exists())
        .map(|e| {
            let dir = e.path();
            let mut meta = read_meta(&dir);
            if meta.name.is_empty() { meta.name = e.file_name().to_string_lossy().into(); }
            Entry { dir, meta }
        })
        .collect();
    v.sort_by_key(|e| e.meta.name.to_lowercase());
    v
}

/// The lowest port from 25565 that no other server is set to.
pub fn next_port(paths: &Paths) -> u16 {
    let used: Vec<u16> = list(paths).iter().filter_map(|e| read_settings(&e.dir).ok().map(|s| s.port)).collect();
    (25565..=u16::MAX).find(|p| !used.contains(p)).unwrap_or(25565)
}

/// Make a new server folder with its meta and first settings. Nothing is downloaded yet (see `install`).
pub fn create(paths: &Paths, meta: &Meta, settings: &Settings) -> Result<PathBuf> {
    if !KINDS.contains(&meta.kind.as_str()) { bail!("unknown server kind '{}'", meta.kind); }
    if meta.name.trim().is_empty() { bail!("the server needs a name"); }
    let dir = free_dir(paths, &meta.name);
    fs::create_dir_all(&dir)?;
    write_meta(&dir, meta)?;
    write_settings(&dir, settings)?;
    Ok(dir)
}

/// Remove a server folder (world, backups and all). Only folders directly inside `servers/` are touched.
pub fn delete(paths: &Paths, dir: &Path) -> Result<()> {
    let root = servers_root(paths).canonicalize()?;
    let d = dir.canonicalize()?;
    if d.parent() != Some(root.as_path()) { bail!("not a server folder"); }
    fs::remove_dir_all(&d)?;
    Ok(())
}

/// Folder add-ons go in: `plugins/` for Paper, `mods/` for Fabric; vanilla has none.
pub fn addon_dir(dir: &Path, kind: &str) -> Option<PathBuf> {
    match kind { "paper" => Some(dir.join("plugins")), "fabric" => Some(dir.join("mods")), _ => None }
}

/// Installed add-on jars, by name.
pub fn addons(dir: &Path, kind: &str) -> Vec<PathBuf> {
    let Some(d) = addon_dir(dir, kind) else { return vec![] };
    let mut v: Vec<PathBuf> = fs::read_dir(d).into_iter().flatten().flatten().map(|e| e.path())
        .filter(|p| p.is_file() && p.extension().map(|x| x == "jar").unwrap_or(false)).collect();
    v.sort_by_key(|p| p.file_name().map(|n| n.to_string_lossy().to_lowercase()).unwrap_or_default());
    v
}

/// Minecraft versions Paper has builds for (releases only), newest first.
pub fn paper_versions(c: &Client) -> Result<Vec<String>> {
    let v: Value = get_json(c, PAPER_API)?;
    let groups = v["versions"].as_object().ok_or_else(|| anyhow!("unexpected PaperMC response"))?;
    let mut out: Vec<String> = groups.values().flat_map(|g| g.as_array().cloned().unwrap_or_default())
        .filter_map(|x| x.as_str().map(String::from)).filter(|x| !x.contains('-')).collect();
    out.sort_by_key(|a| std::cmp::Reverse(ver_key(a)));
    out.dedup();
    Ok(out)
}

fn ver_key(v: &str) -> Vec<u32> { v.split('.').map(|p| p.parse().unwrap_or(0)).collect() }

/// The "unix seconds" now.
fn unix_now() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) }

/// `YYYYMMDD-HHMMSS` (UTC) for unix seconds.
pub fn stamp(secs: u64) -> String {
    let days = (secs / 86400) as i64;
    let rem = secs % 86400;
    // civil-from-days (Howard Hinnant)
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z.rem_euclid(146097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!("{y:04}{m:02}{d:02}-{:02}{:02}{:02}", rem / 3600, rem % 3600 / 60, rem % 60)
}

// ---- install ----

/// What `install` leaves in `server/nether-server.json`.
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Installed {
    /// Jar to run with `java -jar`.
    pub jar: String,
    pub mc: String,
    pub loader: String,
    pub java_component: String,
    pub java_major: u32,
    /// Human note, e.g. when a loader fell back to vanilla.
    #[serde(default)]
    pub note: String,
}

fn manifest_file(dir: &Path) -> PathBuf { dir.join("nether-server.json") }

/// True when `dir` has the jar for `mc` / `kind` downloaded.
pub fn is_installed(dir: &Path, mc: &str, kind: &str) -> bool {
    fs::read(manifest_file(dir)).ok().and_then(|b| serde_json::from_slice::<Installed>(&b).ok())
        .map(|m| m.mc == mc && m.loader == kind && dir.join(&m.jar).exists()).unwrap_or(false)
}

/// Download the server jar for `mc` into `dir`.
/// Vanilla: Mojang's server.jar, sha1 verified. Fabric: the official server launcher jar. Paper: the latest PaperMC build.
pub fn install(c: &Client, paths: &Paths, dir: &Path, mc: &str, kind: &str, progress: &Progress) -> Result<Installed> {
    if !KINDS.contains(&kind) { bail!("hosting a {kind} server is not supported"); }
    if let Ok(b) = fs::read(manifest_file(dir)) {
        if let Ok(m) = serde_json::from_slice::<Installed>(&b) {
            if m.mc == mc && m.loader == kind && dir.join(&m.jar).exists() { return Ok(m); }
        }
    }
    fs::create_dir_all(dir)?;
    progress("Preparing server", 0.0);
    mojang::version_json(c, paths, mc)?; // makes sure versions/<mc>/<mc>.json is on disk
    let raw: Value = serde_json::from_slice(&fs::read(paths.versions().join(mc).join(format!("{mc}.json")))?)?;
    let (component, major) = match raw.get("javaVersion") {
        Some(j) => (j["component"].as_str().unwrap_or("jre-legacy").to_string(), j["majorVersion"].as_u64().unwrap_or(8) as u32),
        None => ("jre-legacy".into(), 8),
    };
    let mut note = String::new();
    let jar = match kind {
        "paper" => {
            let b: Value = get_json(c, &format!("{PAPER_API}/versions/{mc}/builds/latest"))
                .map_err(|_| anyhow!("Paper has no build for Minecraft {mc}"))?;
            let d = &b["downloads"]["server:default"];
            let name = d["name"].as_str().ok_or_else(|| anyhow!("the Paper build has no download"))?.to_string();
            safe_jar_name(&name)?;
            let url = d["url"].as_str().ok_or_else(|| anyhow!("the Paper build has no url"))?;
            let check = d["checksums"]["sha256"].as_str().map(Check::Sha256).or_else(|| d["size"].as_u64().map(Check::Size)).unwrap_or(Check::None);
            progress("Downloading Paper", 0.3);
            fetch(c, url, &dir.join(&name), check)?;
            note = format!("Paper build {}", b["id"]);
            name
        }
        "fabric" => {
            let lv = crate::fabric::latest_loader(c, mc)?;
            let inst: Vec<Value> = get_json(c, &format!("{FABRIC_META}/installer"))?;
            let iv = inst.iter().find(|e| e["stable"].as_bool().unwrap_or(false)).or_else(|| inst.first())
                .and_then(|e| e["version"].as_str()).ok_or_else(|| anyhow!("no Fabric installer version"))?.to_string();
            let name = format!("fabric-server-{mc}-{lv}-{iv}.jar");
            safe_jar_name(&name)?;
            progress("Downloading Fabric server", 0.3);
            fetch(c, &format!("{FABRIC_META}/loader/{mc}/{lv}/{iv}/server/jar"), &dir.join(&name), Check::None)?;
            note = format!("Fabric loader {lv}");
            name
        }
        _ => {
            let s = raw["downloads"]["server"].as_object()
                .ok_or_else(|| anyhow!("Minecraft {mc} has no dedicated server download"))?;
            let url = s["url"].as_str().ok_or_else(|| anyhow!("server download has no url"))?;
            let sha = s["sha1"].as_str().ok_or_else(|| anyhow!("server download has no sha1"))?;
            let name = format!("server-{mc}.jar");
            progress("Downloading server", 0.3);
            fetch(c, url, &dir.join(&name), Check::Sha1(sha))?;
            name
        }
    };
    let m = Installed { jar, mc: mc.into(), loader: kind.into(), java_component: component, java_major: major, note };
    fs::write(manifest_file(dir), serde_json::to_vec_pretty(&m)?)?;
    progress("Server ready", 1.0);
    Ok(m)
}

// ---- EULA ----

/// True when `eula.txt` in `dir` contains `eula=true`.
pub fn eula_accepted(dir: &Path) -> bool {
    fs::read_to_string(dir.join("eula.txt")).map(|t| eula_text_accepted(&t)).unwrap_or(false)
}

fn eula_text_accepted(t: &str) -> bool {
    t.lines().filter(|l| !l.trim_start().starts_with('#')).any(|l| {
        let mut p = l.splitn(2, '=');
        p.next().map(str::trim) == Some("eula") && p.next().map(|v| v.trim().eq_ignore_ascii_case("true")) == Some(true)
    })
}

/// Write `eula=true`. Call only after the user has agreed to https://aka.ms/MinecraftEULA.
pub fn accept_eula(dir: &Path) -> Result<()> {
    fs::create_dir_all(dir)?;
    fs::write(dir.join("eula.txt"), "# By changing the setting below to TRUE you are indicating your agreement to the Minecraft EULA (https://aka.ms/MinecraftEULA).\neula=true\n")?;
    Ok(())
}

// ---- log parsing ----

/// Server lifecycle state.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status { Stopped, Starting, Running, Stopping, Crashed }

/// What a console line means to us.
#[derive(Clone, Debug, PartialEq)]
pub enum LogEvent {
    /// `Done (3.2s)! For help, type "help"`
    Ready { secs: f32 },
    Stopping,
    Join(String),
    Leave(String),
    /// `Saved the game` (answer to `save-all`)
    Saved,
}

/// The message part of a log line (after `[time] [thread/LEVEL]: `).
fn message(line: &str) -> &str {
    match line.find("]: ") { Some(i) => &line[i + 3..], None => line }
}

fn is_player_name(s: &str) -> bool { (1..=16).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_') }

/// Classify one console line.
pub fn parse_line(line: &str) -> Option<LogEvent> {
    let m = message(line.trim_end());
    if let Some(rest) = m.strip_prefix("Done (") {
        let secs = rest.split('s').next().and_then(|s| s.replace(',', ".").parse().ok()).unwrap_or(0.0);
        if rest.contains(")!") { return Some(LogEvent::Ready { secs }); }
    }
    if m == "Stopping server" { return Some(LogEvent::Stopping); }
    if m == "Saved the game" { return Some(LogEvent::Saved); }
    if let Some(n) = m.strip_suffix(" joined the game") { if is_player_name(n) { return Some(LogEvent::Join(n.into())); } }
    if let Some(n) = m.strip_suffix(" left the game") { if is_player_name(n) { return Some(LogEvent::Leave(n.into())); } }
    None
}

// ---- running server ----

/// What to start.
#[derive(Clone, Debug)]
pub struct ServerOpts {
    pub dir: PathBuf,
    pub version: String,
    /// one of `KINDS`
    pub kind: String,
    pub ram_mb: u32,
    pub port: u16,
    /// Override the Java major (None = the version JSON's).
    pub java_major: Option<u32>,
    /// Forward the port through UPnP while running.
    pub upnp: bool,
}

/// UPnP state of a running server.
#[derive(Clone, Debug, PartialEq)]
pub enum UpnpState { Off, Pending, Open { external_ip: Option<String> }, Failed(String) }

struct State {
    status: Status,
    pending: VecDeque<String>,
    history: VecDeque<String>,
    players: Vec<String>,
    stdin: Option<ChildStdin>,
    upnp: UpnpState,
    upnp_handle: Option<crate::net::Upnp>,
    exit_code: Option<i32>,
    stop_requested: bool,
}

struct Inner {
    st: Mutex<State>,
    child: Mutex<Option<Child>>,
    saved: AtomicU64,
    dir: PathBuf,
    port: u16,
}

/// Handle to a running (or finished) server. Cheap to clone; poll it from a UI timer.
#[derive(Clone)]
pub struct Server(Arc<Inner>);

impl Inner {
    fn push_line(&self, line: String) {
        let ev = parse_line(&line);
        let mut s = self.st.lock().unwrap();
        if s.pending.len() >= CONSOLE_PENDING_MAX { s.pending.pop_front(); }
        if s.history.len() >= CONSOLE_HISTORY_MAX { s.history.pop_front(); }
        s.pending.push_back(line.clone());
        s.history.push_back(line);
        match ev {
            Some(LogEvent::Ready { .. }) => if s.status == Status::Starting { s.status = Status::Running },
            Some(LogEvent::Stopping) => if matches!(s.status, Status::Starting | Status::Running) { s.status = Status::Stopping },
            Some(LogEvent::Join(n)) => if !s.players.contains(&n) { s.players.push(n); s.players.sort(); },
            Some(LogEvent::Leave(n)) => s.players.retain(|p| *p != n),
            Some(LogEvent::Saved) => { self.saved.fetch_add(1, Ordering::SeqCst); }
            None => {}
        }
    }

    fn finish(&self, code: Option<i32>) {
        let mut s = self.st.lock().unwrap();
        s.exit_code = code;
        s.status = if s.stop_requested || s.status == Status::Stopping || code == Some(0) { Status::Stopped } else { Status::Crashed };
        s.players.clear();
        s.stdin = None;
        if let Some(mut u) = s.upnp_handle.take() { u.close(); }
        if matches!(s.upnp, UpnpState::Open { .. } | UpnpState::Pending) { s.upnp = UpnpState::Off; }
    }
}

fn pump(r: impl Read + Send + 'static, inner: Arc<Inner>) {
    std::thread::spawn(move || {
        let mut rd = BufReader::new(r);
        let mut buf = Vec::new();
        loop {
            buf.clear();
            match rd.read_until(b'\n', &mut buf) {
                Ok(0) | Err(_) => break,
                Ok(_) => inner.push_line(String::from_utf8_lossy(&buf).trim_end_matches(['\r', '\n']).to_string()),
            }
        }
    });
}

fn java_exe(p: PathBuf) -> PathBuf {
    let c = p.with_file_name("java.exe");
    if c.exists() { c } else { p }
}

/// Install if needed, check the EULA, then start the server. Returns `EulaNotAccepted` (see `is_eula_error`)
/// until `eula.txt` says `eula=true`; the first call runs the jar once (it exits at once) so `eula.txt` and
/// `server.properties` exist for the user to review.
/// A file name from a web API that is safe to join onto a folder: no separators, drive or `..`, ends in .jar.
fn safe_jar_name(n: &str) -> Result<()> {
    if n.is_empty() || n.len() > 128 || n.contains(['/', '\\', ':']) || n.contains("..") || !n.ends_with(".jar") { bail!("unexpected server file name {n:?}"); }
    Ok(())
}

pub fn start(c: &Client, paths: &Paths, opts: &ServerOpts, progress: Progress) -> Result<Server> {
    let m = install(c, paths, &opts.dir, &opts.version, &opts.kind, &progress)?;
    let dir = opts.dir.clone();
    let major = opts.java_major.unwrap_or(m.java_major);
    let comp = if opts.java_major.is_some() { match major { 8 => "jre-legacy", 17 => "java-runtime-gamma", 21 => "java-runtime-delta", _ => "java-runtime-epsilon" }.to_string() } else { m.java_component.clone() };
    let exe = java_exe(java::find_or_install(c, paths, &comp, major, &progress, None)?);

    let mut cmd = Command::new(&exe);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    cmd.current_dir(&dir).arg(format!("-Xmx{}M", opts.ram_mb.max(512))).arg(format!("-Xms{}M", opts.ram_mb.clamp(256, 512)))
        .arg("-jar").arg(&m.jar).arg("nogui");

    if !eula_accepted(&dir) {
        if !dir.join("eula.txt").exists() {
            progress("Generating server files", 0.9);
            let mut child = cmd.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn()
                .with_context(|| format!("starting {}", exe.display()))?;
            let t = Instant::now();
            while child.try_wait()?.is_none() {
                if t.elapsed() > Duration::from_secs(120) { let _ = child.kill(); let _ = child.wait(); break; }
                std::thread::sleep(Duration::from_millis(200));
            }
        }
        return Err(EulaNotAccepted.into());
    }

    if opts.port != 0 {
        let mut p = Properties::load(&dir)?;
        p.set("server-port", &opts.port.to_string());
        p.save(&dir)?;
    }
    let port = Properties::load(&dir)?.get("server-port").and_then(|s| s.parse().ok()).unwrap_or(25565);
    if std::net::TcpListener::bind(("0.0.0.0", port)).is_err() {
        bail!("port {port} is already in use (is another server running on it?). Pick another port in Settings");
    }

    progress("Starting server", 0.95);
    let mut child = cmd.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn()
        .with_context(|| format!("starting {}", exe.display()))?;
    let (stdout, stderr, stdin) = (child.stdout.take(), child.stderr.take(), child.stdin.take());
    let inner = Arc::new(Inner {
        st: Mutex::new(State {
            status: Status::Starting, pending: VecDeque::new(), history: VecDeque::new(), players: vec![], stdin,
            upnp: if opts.upnp { UpnpState::Pending } else { UpnpState::Off }, upnp_handle: None, exit_code: None, stop_requested: false,
        }),
        child: Mutex::new(Some(child)),
        saved: AtomicU64::new(0),
        dir,
        port,
    });
    if let Some(o) = stdout { pump(o, inner.clone()); }
    if let Some(e) = stderr { pump(e, inner.clone()); }

    // waiter: notices the exit, tears down the UPnP mapping
    let w = inner.clone();
    std::thread::spawn(move || loop {
        let done = {
            let mut g = w.child.lock().unwrap();
            match g.as_mut().map(|c| c.try_wait()) {
                Some(Ok(Some(st))) => { *g = None; Some(st.code()) }
                Some(Err(_)) | None => Some(None),
                Some(Ok(None)) => None,
            }
        };
        if let Some(code) = done {
            std::thread::sleep(Duration::from_millis(300)); // let the pumps flush the last lines
            w.finish(code);
            break;
        }
        std::thread::sleep(Duration::from_millis(250));
    });

    if opts.upnp {
        let u = inner.clone();
        let dir = opts.dir.clone();
        std::thread::spawn(move || {
            // online-mode off lets anyone join under any name (op included): never forward such a server to the internet
            let r = if read_settings(&dir).map(|s| !s.online_mode).unwrap_or(false) {
                Err(anyhow!("online-mode is off in Settings, so the server is kept off the internet (anyone could join as any player, including an operator). Turn online-mode on to open it"))
            } else { crate::net::Upnp::open(u.port, "Nether Client server") };
            let mut s = u.st.lock().unwrap();
            if matches!(s.status, Status::Stopped | Status::Crashed) { return; } // exited meanwhile; Upnp drops and unmaps
            match r {
                Ok(h) => { s.upnp = UpnpState::Open { external_ip: h.external_ip().map(|i| i.to_string()) }; s.upnp_handle = Some(h); }
                Err(e) => s.upnp = UpnpState::Failed(e.to_string()),
            }
        });
    }
    Ok(Server(inner))
}

impl Server {
    /// Current lifecycle state.
    pub fn status(&self) -> Status { self.0.st.lock().unwrap().status }
    /// Exit code once the process ended.
    pub fn exit_code(&self) -> Option<i32> { self.0.st.lock().unwrap().exit_code }
    /// Server directory.
    pub fn dir(&self) -> &Path { &self.0.dir }
    /// The port the server listens on.
    pub fn port(&self) -> u16 { self.0.port }
    /// Console lines added since the last drain (oldest dropped past 5000).
    pub fn drain_console(&self) -> Vec<String> { self.0.st.lock().unwrap().pending.drain(..).collect() }
    /// The last 2000 console lines (not consumed by `drain_console`).
    pub fn console_history(&self) -> Vec<String> { self.0.st.lock().unwrap().history.iter().cloned().collect() }
    /// Names of players currently online (from join/leave lines).
    pub fn players(&self) -> Vec<String> { self.0.st.lock().unwrap().players.clone() }
    /// State of the UPnP mapping.
    pub fn upnp(&self) -> UpnpState { self.0.st.lock().unwrap().upnp.clone() }

    /// Send a console command (without the leading slash).
    pub fn send(&self, command: &str) -> Result<()> {
        let mut s = self.0.st.lock().unwrap();
        let cmd = command.trim().trim_start_matches('/');
        if cmd.is_empty() { return Ok(()); }
        let w = s.stdin.as_mut().ok_or_else(|| anyhow!("the server is not running"))?;
        writeln!(w, "{cmd}")?;
        w.flush()?;
        Ok(())
    }

    /// Ask the server to stop (`stop`), wait up to `timeout`, then kill it. Blocks; call from a worker thread.
    pub fn stop(&self, timeout: Duration) -> Result<()> {
        {
            let mut s = self.0.st.lock().unwrap();
            if matches!(s.status, Status::Stopped | Status::Crashed) { return Ok(()); }
            s.stop_requested = true;
        }
        // still booting: `stop` is only read once the world has loaded, so allow for world generation
        let timeout = if self.status() == Status::Starting { timeout.max(Duration::from_secs(180)) } else { timeout };
        let _ = self.send("stop");
        let t = Instant::now();
        while t.elapsed() < timeout {
            if matches!(self.status(), Status::Stopped | Status::Crashed) { return Ok(()); }
            std::thread::sleep(Duration::from_millis(150));
        }
        if let Some(c) = self.0.child.lock().unwrap().as_mut() { let _ = c.kill(); }
        let t = Instant::now();
        while t.elapsed() < Duration::from_secs(10) {
            if matches!(self.status(), Status::Stopped | Status::Crashed) { return Ok(()); }
            std::thread::sleep(Duration::from_millis(100));
        }
        bail!("the server did not exit")
    }

    /// Back up the world while the server runs (`save-off`, `save-all flush`, zip, `save-on`).
    pub fn backup(&self) -> Result<PathBuf> {
        match self.status() {
            Status::Running => {}
            Status::Stopped | Status::Crashed => return backup_world(&self.0.dir),
            _ => bail!("wait until the server has finished starting or stopping"),
        }
        let before = self.0.saved.load(Ordering::SeqCst);
        self.send("save-off")?;
        let r = (|| -> Result<PathBuf> {
            self.send("save-all flush")?;
            let t = Instant::now();
            while self.0.saved.load(Ordering::SeqCst) == before {
                if t.elapsed() > Duration::from_secs(120) { bail!("the server did not finish saving"); }
                std::thread::sleep(Duration::from_millis(200));
            }
            backup_world(&self.0.dir)
        })();
        let _ = self.send("save-on");
        r
    }
}

// ---- server.properties ----

/// `server.properties` as lines: unknown keys, comments and order survive a save.
#[derive(Clone, Debug, Default)]
pub struct Properties { lines: Vec<String> }

fn unescape(v: &str) -> String {
    let mut out = String::new();
    let mut it = v.chars();
    while let Some(c) = it.next() {
        if c != '\\' { out.push(c); continue; }
        match it.next() {
            Some('u') => {
                let hex: String = it.by_ref().take(4).collect();
                let mut cp = u32::from_str_radix(&hex, 16).unwrap_or(0xfffd);
                if (0xd800..0xdc00).contains(&cp) {
                    // surrogate pair \uD83D\uDE00
                    let save = it.clone();
                    if it.next() == Some('\\') && it.next() == Some('u') {
                        let lo = u32::from_str_radix(&it.by_ref().take(4).collect::<String>(), 16).unwrap_or(0);
                        if (0xdc00..0xe000).contains(&lo) { cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00); } else { it = save; }
                    } else { it = save; }
                }
                out.push(char::from_u32(cp).unwrap_or('\u{fffd}'));
            }
            Some('n') => out.push('\n'),
            Some('t') => out.push('\t'),
            Some('r') => out.push('\r'),
            Some('f') => out.push('\u{c}'),
            Some(o) => out.push(o),
            None => {}
        }
    }
    out
}

fn escape(v: &str) -> String {
    let mut out = String::new();
    for (i, c) in v.chars().enumerate() {
        match c {
            '\\' => out.push_str("\\\\"),
            ':' => out.push_str("\\:"),
            '=' => out.push_str("\\="),
            '#' => out.push_str("\\#"),
            '!' => out.push_str("\\!"),
            ' ' if i == 0 => out.push_str("\\ "),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 || (c as u32) > 0x7e => {
                let mut b = [0u16; 2];
                for u in c.encode_utf16(&mut b) { out.push_str(&format!("\\u{:04X}", u)); }
            }
            c => out.push(c),
        }
    }
    out
}

fn split_kv(line: &str) -> Option<(String, String)> {
    let t = line.trim_start();
    if t.is_empty() || t.starts_with('#') || t.starts_with('!') { return None; }
    let mut end = t.len();
    let mut esc = false;
    for (i, c) in t.char_indices() {
        if esc { esc = false; continue; }
        if c == '\\' { esc = true; continue; }
        if c == '=' || c == ':' || c.is_whitespace() { end = i; break; }
    }
    let key = unescape(&t[..end]);
    let mut rest = t[end..].trim_start();
    if rest.starts_with(['=', ':']) { rest = rest[1..].trim_start(); }
    Some((key, unescape(rest.trim_end_matches(['\r', '\n']))))
}

impl Properties {
    /// Parse text.
    pub fn parse(text: &str) -> Properties { Properties { lines: text.lines().map(String::from).collect() } }

    /// Read `server.properties` from `dir` (empty when absent).
    pub fn load(dir: &Path) -> Result<Properties> {
        match fs::read(dir.join("server.properties")) {
            Ok(b) => Ok(Properties::parse(&String::from_utf8_lossy(&b))),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Properties::default()),
            Err(e) => Err(e.into()),
        }
    }

    /// Value of `key`.
    pub fn get(&self, key: &str) -> Option<String> {
        self.lines.iter().rev().filter_map(|l| split_kv(l)).find(|(k, _)| k == key).map(|(_, v)| v)
    }

    /// Set `key` in place (appended when new); an unchanged value leaves its line byte-identical.
    pub fn set(&mut self, key: &str, value: &str) {
        let line = format!("{}={}", escape(key), escape(value));
        if let Some(i) = self.lines.iter().rposition(|l| split_kv(l).map(|(k, _)| k == key).unwrap_or(false)) {
            if split_kv(&self.lines[i]).map(|(_, v)| v) != Some(value.to_string()) { self.lines[i] = line; }
        } else {
            self.lines.push(line);
        }
    }

    /// Serialize.
    pub fn to_text(&self) -> String {
        let mut s = self.lines.join("\n");
        s.push('\n');
        s
    }

    /// Write `server.properties` (atomic).
    pub fn save(&self, dir: &Path) -> Result<()> {
        fs::create_dir_all(dir)?;
        let tmp = dir.join("server.properties.part");
        fs::write(&tmp, self.to_text())?;
        fs::rename(&tmp, dir.join("server.properties"))?;
        Ok(())
    }
}

/// The settings the UI edits.
#[derive(Clone, Debug, PartialEq)]
pub struct Settings {
    pub port: u16,
    pub motd: String,
    pub max_players: u32,
    pub online_mode: bool,
    pub white_list: bool,
    pub difficulty: String,
    pub gamemode: String,
    pub pvp: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Settings { port: 25565, motd: "A Minecraft Server".into(), max_players: 20, online_mode: true, white_list: false, difficulty: "easy".into(), gamemode: "survival".into(), pvp: true }
    }
}

/// Read the editable settings (Mojang defaults for missing keys).
pub fn read_settings(dir: &Path) -> Result<Settings> {
    let p = Properties::load(dir)?;
    let d = Settings::default();
    let b = |k: &str, def: bool| p.get(k).map(|v| v.trim().eq_ignore_ascii_case("true")).unwrap_or(def);
    Ok(Settings {
        port: p.get("server-port").and_then(|v| v.trim().parse().ok()).unwrap_or(d.port),
        motd: p.get("motd").unwrap_or(d.motd),
        max_players: p.get("max-players").and_then(|v| v.trim().parse().ok()).unwrap_or(d.max_players),
        online_mode: b("online-mode", d.online_mode),
        white_list: b("white-list", d.white_list),
        difficulty: p.get("difficulty").unwrap_or(d.difficulty),
        gamemode: p.get("gamemode").unwrap_or(d.gamemode),
        pvp: b("pvp", d.pvp),
    })
}

/// Write the editable settings, keeping every other key and comment. Takes effect on next start.
pub fn write_settings(dir: &Path, s: &Settings) -> Result<()> {
    let mut p = Properties::load(dir)?;
    p.set("server-port", &s.port.to_string());
    p.set("motd", &s.motd);
    p.set("max-players", &s.max_players.to_string());
    p.set("online-mode", &s.online_mode.to_string());
    p.set("white-list", &s.white_list.to_string());
    p.set("difficulty", &s.difficulty);
    p.set("gamemode", &s.gamemode);
    p.set("pvp", &s.pvp.to_string());
    p.save(dir)
}

// ---- whitelist / ops ----

/// A player entry in `whitelist.json` / `ops.json`.
#[derive(Clone, Debug, PartialEq)]
pub struct Player { pub name: String, pub uuid: String, pub level: Option<u32> }

fn dashed(id: &str) -> String {
    let h: String = id.chars().filter(|c| *c != '-').collect::<String>().to_lowercase();
    if h.len() != 32 { return h; }
    format!("{}-{}-{}-{}-{}", &h[..8], &h[8..12], &h[12..16], &h[16..20], &h[20..])
}

/// Mojang account lookup: name -> (dashed uuid, canonical name). Errors when the name does not exist.
pub fn lookup_player(c: &Client, name: &str) -> Result<(String, String)> {
    if !is_player_name(name) { bail!("'{name}' is not a valid Minecraft name"); }
    let r = c.get(format!("https://api.mojang.com/users/profiles/minecraft/{name}")).send()?;
    match r.status().as_u16() {
        200 => { let v: Value = r.json()?; Ok((dashed(v["id"].as_str().unwrap_or_default()), v["name"].as_str().unwrap_or(name).to_string())) }
        204 | 404 => bail!("no Minecraft account named '{name}'"),
        429 => bail!("Mojang is rate limiting lookups, try again in a minute"),
        s => bail!("Mojang lookup failed (HTTP {s})"),
    }
}

/// UUID for `name` on this server: Mojang's for online-mode servers, the offline UUID otherwise.
fn resolve(c: &Client, dir: &Path, name: &str) -> Result<(String, String)> {
    if read_settings(dir)?.online_mode { lookup_player(c, name) } else {
        if !is_player_name(name) { bail!("'{name}' is not a valid Minecraft name"); }
        Ok((dashed(&crate::Session::offline(name).uuid), name.to_string()))
    }
}

fn read_players(dir: &Path, file: &str) -> Result<Vec<Player>> {
    let v: Vec<Value> = match fs::read(dir.join(file)) {
        Ok(b) if !b.iter().all(u8::is_ascii_whitespace) => serde_json::from_slice(&b).with_context(|| format!("reading {file}"))?,
        _ => return Ok(vec![]),
    };
    Ok(v.iter().map(|e| Player {
        name: e["name"].as_str().unwrap_or_default().into(),
        uuid: e["uuid"].as_str().unwrap_or_default().into(),
        level: e["level"].as_u64().map(|l| l as u32),
    }).collect())
}

fn write_players(dir: &Path, file: &str, list: &[Player], ops: bool) -> Result<()> {
    let arr: Vec<Value> = list.iter().map(|p| if ops {
        json!({"uuid": p.uuid, "name": p.name, "level": p.level.unwrap_or(4), "bypassesPlayerLimit": false})
    } else { json!({"uuid": p.uuid, "name": p.name}) }).collect();
    fs::create_dir_all(dir)?;
    fs::write(dir.join(file), serde_json::to_vec_pretty(&arr)?)?;
    Ok(())
}

/// Entries of `whitelist.json`.
pub fn whitelist(dir: &Path) -> Result<Vec<Player>> { read_players(dir, "whitelist.json") }
/// Entries of `ops.json`.
pub fn ops(dir: &Path) -> Result<Vec<Player>> { read_players(dir, "ops.json") }

/// Add `name` to `whitelist.json` (use the `whitelist add` command instead while the server runs).
pub fn whitelist_add(c: &Client, dir: &Path, name: &str) -> Result<Player> {
    let (uuid, name) = resolve(c, dir, name)?;
    let mut l = whitelist(dir)?;
    let p = Player { name, uuid, level: None };
    if !l.iter().any(|e| e.uuid == p.uuid) { l.push(p.clone()); write_players(dir, "whitelist.json", &l, false)?; }
    Ok(p)
}

/// Remove `name` from `whitelist.json`; true when it was there.
pub fn whitelist_remove(dir: &Path, name: &str) -> Result<bool> {
    let mut l = whitelist(dir)?;
    let n = l.len();
    l.retain(|e| !e.name.eq_ignore_ascii_case(name));
    if l.len() != n { write_players(dir, "whitelist.json", &l, false)?; }
    Ok(l.len() != n)
}

/// Make `name` an operator (level 1-4) in `ops.json`.
pub fn ops_add(c: &Client, dir: &Path, name: &str, level: u32) -> Result<Player> {
    let (uuid, name) = resolve(c, dir, name)?;
    let mut l = ops(dir)?;
    let p = Player { name, uuid, level: Some(level.clamp(1, 4)) };
    l.retain(|e| e.uuid != p.uuid);
    l.push(p.clone());
    write_players(dir, "ops.json", &l, true)?;
    Ok(p)
}

/// Remove `name` from `ops.json`; true when it was there.
pub fn ops_remove(dir: &Path, name: &str) -> Result<bool> {
    let mut l = ops(dir)?;
    let n = l.len();
    l.retain(|e| !e.name.eq_ignore_ascii_case(name));
    if l.len() != n { write_players(dir, "ops.json", &l, true)?; }
    Ok(l.len() != n)
}

// ---- worlds ----

fn copy_tree(src: &Path, dst: &Path) -> Result<()> {
    fs::create_dir_all(dst)?;
    for e in fs::read_dir(src)? {
        let e = e?;
        let name = e.file_name();
        if name == "session.lock" { continue; }
        let (s, d) = (e.path(), dst.join(&name));
        if e.file_type()?.is_dir() { copy_tree(&s, &d)?; } else { fs::copy(&s, &d)?; }
    }
    Ok(())
}

/// Singleplayer worlds (folders with a `level.dat`) of every instance: (instance name, world folder).
pub fn list_saves(paths: &Paths) -> Vec<(String, String)> {
    let mut out = vec![];
    for i in instances::load(paths).unwrap_or_default() {
        let mut v: Vec<String> = fs::read_dir(paths.instance(&i.name).join("saves")).into_iter().flatten().flatten()
            .filter(|e| e.path().join("level.dat").exists()).filter_map(|e| e.file_name().into_string().ok()).collect();
        v.sort();
        out.extend(v.into_iter().map(|w| (i.name.clone(), w)));
    }
    out
}

/// Copy singleplayer world `save` of `instance` into the server at `dir` as its world (the source is never touched).
/// An existing server world folder is renamed to `<level>.old-<stamp>`, never deleted. Stop the server first.
pub fn import_save(paths: &Paths, dir: &Path, instance: &str, save: &str) -> Result<PathBuf> {
    if save.is_empty() || save.contains(['/', '\\']) || save == ".." || save == "." { bail!("invalid world name"); }
    let src = paths.instance(instance).join("saves").join(save);
    if !src.join("level.dat").exists() { bail!("'{save}' is not a Minecraft world"); }
    fs::create_dir_all(dir)?;
    let level = Properties::load(dir)?.get("level-name").filter(|s| !s.is_empty()).unwrap_or_else(|| "world".into());
    let dst = dir.join(&level);
    if dst.exists() {
        let old = dir.join(format!("{level}.old-{}", stamp(unix_now())));
        fs::rename(&dst, &old).with_context(|| "moving the current server world aside (is the server stopped?)")?;
    }
    if let Err(e) = copy_tree(&src, &dst) {
        let _ = fs::remove_dir_all(&dst); // only our partial copy
        return Err(e);
    }
    Ok(dst)
}

// ---- backup ----

fn zip_tree(z: &mut zip::ZipWriter<fs::File>, base: &Path, cur: &Path, opts: zip::write::SimpleFileOptions) -> Result<()> {
    for e in fs::read_dir(cur)? {
        let e = e?;
        let p = e.path();
        if e.file_name() == "session.lock" { continue; } // held open by a running server
        let rel = p.strip_prefix(base)?.to_string_lossy().replace('\\', "/");
        if e.file_type()?.is_dir() {
            z.add_directory(format!("{rel}/"), opts)?;
            zip_tree(z, base, &p, opts)?;
        } else {
            z.start_file(rel, opts)?;
            std::io::copy(&mut fs::File::open(&p)?, z)?;
        }
    }
    Ok(())
}

/// Zip the world (plus pre-1.16 `_nether` / `_the_end` folders) to `server/backups/<level>-<stamp>.zip`.
/// Only consistent while the server is stopped; use `Server::backup` for a running one.
pub fn backup_world(dir: &Path) -> Result<PathBuf> {
    let level = Properties::load(dir)?.get("level-name").filter(|s| !s.is_empty()).unwrap_or_else(|| "world".into());
    let folders: Vec<String> = [level.clone(), format!("{level}_nether"), format!("{level}_the_end")].into_iter().filter(|f| dir.join(f).is_dir()).collect();
    if folders.is_empty() { bail!("there is no world to back up yet"); }
    let out_dir = dir.join("backups");
    fs::create_dir_all(&out_dir)?;
    let out = out_dir.join(format!("{level}-{}.zip", stamp(unix_now())));
    let tmp = out.with_extension("zip.part");
    let res = (|| -> Result<()> {
        let mut z = zip::ZipWriter::new(fs::File::create(&tmp)?);
        let opts = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        for f in &folders {
            z.add_directory(format!("{f}/"), opts)?;
            zip_tree(&mut z, dir, &dir.join(f), opts)?;
        }
        z.finish()?;
        Ok(())
    })();
    match res {
        Ok(()) => { fs::rename(&tmp, &out)?; Ok(out) }
        Err(e) => { let _ = fs::remove_file(&tmp); Err(e) }
    }
}

/// Backups in `server/backups/`, newest first.
pub fn list_backups(dir: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = fs::read_dir(dir.join("backups")).into_iter().flatten().flatten().map(|e| e.path())
        .filter(|p| p.extension().map(|x| x == "zip").unwrap_or(false)).collect();
    v.sort();
    v.reverse();
    v
}

#[cfg(test)]
mod tests {
    use super::*;

    // Real lines from a vanilla 1.21.11 server.
    const LOG: &str = "\
[10:42:01] [ServerMain/INFO]: Environment: Environment[sessionHost=https://sessionserver.mojang.com, servicesHost=https://api.minecraftservices.com, name=PROD]
[10:42:03] [Server thread/INFO]: Starting minecraft server version 1.21.11
[10:42:03] [Server thread/INFO]: Preparing level \"world\"
[10:42:09] [Server thread/INFO]: Done (6.512s)! For help, type \"help\"
[10:43:11] [User Authenticator #1/INFO]: UUID of player Steve is 8667ba71-b85a-4004-af54-457a9734eed7
[10:43:12] [Server thread/INFO]: Steve[/127.0.0.1:51234] logged in with entity id 41 at (1.5, 64.0, -3.5)
[10:43:12] [Server thread/INFO]: Steve joined the game
[10:43:20] [Server thread/INFO]: <Steve> Alex joined the game
[10:43:40] [Server thread/INFO]: Alex_1 joined the game
[10:44:00] [Server thread/INFO]: Steve left the game
[10:44:01] [Server thread/INFO]: Saved the game
[10:45:00] [Server thread/INFO]: Stopping server
[10:45:00] [Server thread/INFO]: Stopping the server";

    fn run(lines: &str) -> (Status, Vec<String>) {
        let inner = Inner {
            st: Mutex::new(State { status: Status::Starting, pending: VecDeque::new(), history: VecDeque::new(), players: vec![], stdin: None, upnp: UpnpState::Off, upnp_handle: None, exit_code: None, stop_requested: false }),
            child: Mutex::new(None), saved: AtomicU64::new(0), dir: PathBuf::new(), port: 25565,
        };
        for l in lines.lines() { inner.push_line(l.into()); }
        let s = inner.st.lock().unwrap();
        (s.status, s.players.clone())
    }

    #[test]
    fn parses_real_lines() {
        assert_eq!(parse_line("[10:42:09] [Server thread/INFO]: Done (6.512s)! For help, type \"help\""), Some(LogEvent::Ready { secs: 6.512 }));
        assert_eq!(parse_line("[10:45:00] [Server thread/INFO]: Stopping server"), Some(LogEvent::Stopping));
        assert_eq!(parse_line("[10:43:12] [Server thread/INFO]: Steve joined the game"), Some(LogEvent::Join("Steve".into())));
        assert_eq!(parse_line("[10:44:00] [Server thread/INFO]: Steve left the game"), Some(LogEvent::Leave("Steve".into())));
        assert_eq!(parse_line("[10:44:01] [Server thread/INFO]: Saved the game"), Some(LogEvent::Saved));
        assert_eq!(parse_line("[10:43:20] [Server thread/INFO]: <Steve> Alex joined the game"), None);
        assert_eq!(parse_line("[10:42:03] [Server thread/INFO]: Preparing level \"world\""), None);
        assert_eq!(parse_line("[10:42:03] [Server thread/INFO]: Done preparing level \"world\" (1.2s)"), None);
    }

    #[test]
    fn state_machine() {
        let (st, players) = run(LOG);
        assert_eq!(st, Status::Stopping);
        assert_eq!(players, vec!["Alex_1".to_string()]);
        let (st, _) = run("[10:42:03] [Server thread/INFO]: Starting minecraft server version 1.21.11");
        assert_eq!(st, Status::Starting);
        let (st, _) = run("[10:42:09] [Server thread/INFO]: Done (6.512s)! For help, type \"help\"");
        assert_eq!(st, Status::Running);
    }

    #[test]
    fn finish_classifies_exit() {
        let mk = |status, stop| Inner {
            st: Mutex::new(State { status, pending: VecDeque::new(), history: VecDeque::new(), players: vec!["A".into()], stdin: None, upnp: UpnpState::Off, upnp_handle: None, exit_code: None, stop_requested: stop }),
            child: Mutex::new(None), saved: AtomicU64::new(0), dir: PathBuf::new(), port: 1,
        };
        let a = mk(Status::Running, false); a.finish(Some(0));
        assert_eq!(a.st.lock().unwrap().status, Status::Stopped);
        let a = mk(Status::Running, false); a.finish(Some(1));
        assert_eq!(a.st.lock().unwrap().status, Status::Crashed);
        let a = mk(Status::Starting, false); a.finish(Some(1));
        assert_eq!(a.st.lock().unwrap().status, Status::Crashed);
        let a = mk(Status::Running, true); a.finish(Some(143));
        assert_eq!(a.st.lock().unwrap().status, Status::Stopped);
        assert!(a.st.lock().unwrap().players.is_empty());
    }

    #[test]
    fn console_ring_is_bounded() {
        let inner = Inner {
            st: Mutex::new(State { status: Status::Starting, pending: VecDeque::new(), history: VecDeque::new(), players: vec![], stdin: None, upnp: UpnpState::Off, upnp_handle: None, exit_code: None, stop_requested: false }),
            child: Mutex::new(None), saved: AtomicU64::new(0), dir: PathBuf::new(), port: 1,
        };
        for i in 0..6000 { inner.push_line(format!("line {i}")); }
        let s = inner.st.lock().unwrap();
        assert_eq!(s.pending.len(), CONSOLE_PENDING_MAX);
        assert_eq!(s.history.len(), CONSOLE_HISTORY_MAX);
        assert_eq!(s.pending.back().unwrap(), "line 5999");
    }

    #[test]
    fn eula_text() {
        assert!(!eula_text_accepted("#By changing the setting below to TRUE you are indicating your agreement\n#Tue Nov 10 10:42:03 CET 2026\neula=false\n"));
        assert!(eula_text_accepted("# c\neula=true\n"));
        assert!(eula_text_accepted("eula = TRUE"));
        assert!(!eula_text_accepted("# eula=true\n"));
        assert!(!eula_text_accepted(""));
    }

    #[test]
    fn eula_error_is_distinct() {
        let e: anyhow::Error = EulaNotAccepted.into();
        assert!(is_eula_error(&e));
        assert!(!is_eula_error(&anyhow!("other")));
    }

    const PROPS: &str = "#Minecraft server properties\n#Tue Nov 10 10:42:09 CET 2026\naccept-transfers=false\ndifficulty=easy\nenable-command-block=false\ngamemode=survival\nmax-players=20\nmotd=A Minecraft Server\nonline-mode=true\npvp=true\nserver-port=25565\nwhite-list=false\n";

    #[test]
    fn properties_round_trip_keeps_everything() {
        let p = Properties::parse(PROPS);
        assert_eq!(p.to_text(), PROPS);
        let mut p = p;
        p.set("motd", "A Minecraft Server"); // unchanged value, same bytes
        assert_eq!(p.to_text(), PROPS);
        p.set("server-port", "25570");
        p.set("brand-new", "1");
        let t = p.to_text();
        assert!(t.contains("#Minecraft server properties\n"));
        assert!(t.contains("accept-transfers=false\n") && t.contains("enable-command-block=false\n"));
        assert!(t.contains("server-port=25570\n") && t.ends_with("brand-new=1\n"));
        assert_eq!(Properties::parse(&t).get("server-port").as_deref(), Some("25570"));
    }

    #[test]
    fn properties_escaping() {
        let mut p = Properties::default();
        p.set("motd", "Caf\u{e9} \u{1F600}: a=b \\ x");
        let t = p.to_text();
        assert!(t.is_ascii(), "{t}");
        assert_eq!(Properties::parse(&t).get("motd").as_deref(), Some("Caf\u{e9} \u{1F600}: a=b \\ x"));
        assert_eq!(Properties::parse("motd=\\u00A7aHi\n").get("motd").as_deref(), Some("\u{a7}aHi"));
        assert_eq!(Properties::parse("a : b\n").get("a").as_deref(), Some("b"));
        assert_eq!(Properties::parse("empty=\n").get("empty").as_deref(), Some(""));
    }

    #[test]
    fn settings_files() {
        let d = std::env::temp_dir().join(format!("nc-srv-test-{}", unix_now() ^ std::process::id() as u64));
        fs::create_dir_all(&d).unwrap();
        assert_eq!(read_settings(&d).unwrap(), Settings::default());
        fs::write(d.join("server.properties"), PROPS).unwrap();
        let mut s = read_settings(&d).unwrap();
        assert_eq!(s, Settings::default());
        s.port = 25999; s.motd = "Hi \u{a7}a".into(); s.pvp = false; s.white_list = true; s.gamemode = "creative".into(); s.difficulty = "hard".into(); s.max_players = 5; s.online_mode = false;
        write_settings(&d, &s).unwrap();
        assert_eq!(read_settings(&d).unwrap(), s);
        let t = fs::read_to_string(d.join("server.properties")).unwrap();
        assert!(t.contains("accept-transfers=false") && t.starts_with("#Minecraft server properties"));
        fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn whitelist_and_ops_files() {
        let d = std::env::temp_dir().join(format!("nc-srv-wl-{}", unix_now() ^ std::process::id() as u64));
        fs::create_dir_all(&d).unwrap();
        fs::write(d.join("server.properties"), "online-mode=false\n").unwrap();
        let c = crate::download::client();
        let p = whitelist_add(&c, &d, "Steve").unwrap(); // offline mode: no network
        assert_eq!(p.uuid, dashed(&crate::Session::offline("Steve").uuid));
        assert_eq!(p.uuid.len(), 36);
        whitelist_add(&c, &d, "Steve").unwrap();
        assert_eq!(whitelist(&d).unwrap().len(), 1);
        ops_add(&c, &d, "Steve", 9).unwrap();
        assert_eq!(ops(&d).unwrap()[0].level, Some(4));
        let raw: Value = serde_json::from_slice(&fs::read(d.join("ops.json")).unwrap()).unwrap();
        assert_eq!(raw[0]["bypassesPlayerLimit"], false);
        assert!(whitelist_remove(&d, "steve").unwrap());
        assert!(!whitelist_remove(&d, "steve").unwrap());
        assert!(ops_remove(&d, "Steve").unwrap());
        assert!(lookup_player(&c, "bad name!").is_err());
        fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn world_import_and_backup() {
        let root = std::env::temp_dir().join(format!("nc-srv-world-{}", unix_now() ^ std::process::id() as u64));
        let paths = Paths { root: root.clone() };
        let saves = paths.instance("t").join("saves").join("My World");
        fs::create_dir_all(saves.join("region")).unwrap();
        fs::write(saves.join("level.dat"), b"x").unwrap();
        fs::write(saves.join("session.lock"), b"x").unwrap();
        fs::write(saves.join("region").join("r.0.0.mca"), b"chunk").unwrap();
        let dir = servers_root(&paths).join("t");
        assert!(import_save(&paths, &dir, "t", "../x").is_err());
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("server.properties"), "level-name=world\n").unwrap();
        fs::create_dir_all(dir.join("world")).unwrap();
        fs::write(dir.join("world").join("old.txt"), b"keep me").unwrap();
        let dst = import_save(&paths, &dir, "t", "My World").unwrap();
        assert!(dst.join("region").join("r.0.0.mca").exists() && !dst.join("session.lock").exists());
        assert!(saves.join("level.dat").exists(), "source must stay");
        let old: Vec<_> = fs::read_dir(&dir).unwrap().flatten().filter(|e| e.file_name().to_string_lossy().starts_with("world.old-")).collect();
        assert_eq!(old.len(), 1);
        assert_eq!(fs::read(old[0].path().join("old.txt")).unwrap(), b"keep me");
        let zip = backup_world(&dir).unwrap();
        let mut a = zip::ZipArchive::new(fs::File::open(&zip).unwrap()).unwrap();
        assert!(a.by_name("world/region/r.0.0.mca").is_ok());
        assert!(a.by_name("world/level.dat").is_ok());
        assert!(a.by_name("world/session.lock").is_err());
        assert_eq!(list_backups(&dir).len(), 1);
        fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn stamp_format() {
        assert_eq!(stamp(0), "19700101-000000");
        assert_eq!(stamp(1_700_000_000), "20231114-221320");
        assert_eq!(stamp(951_782_400), "20000229-000000");
    }
}
