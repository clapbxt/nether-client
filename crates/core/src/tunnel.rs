//! Free playit.gg tunnel for hosts whose network blocks incoming connections (CGNAT, no UPnP).
//! playit's official Windows agent is downloaded on demand from its GitHub releases (sha256 checked against the
//! release digest) and run portable (`--stdout`, no service, no install). Its claim link links this PC to a free
//! playit.gg account; then a Minecraft Java tunnel per server port is found or created through playit's API with
//! the agent key the agent saved. One agent serves every running server.
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{anyhow, bail, Context, Result};
use reqwest::blocking::Client;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use crate::paths::Paths;

const RELEASE: &str = "https://api.github.com/repos/playit-cloud/playit-agent/releases/latest";
const ASSET: &str = "playit-windows-x86_64-signed.exe";
const API: &str = "https://api.playit.gg";
/// Where tunnels are managed by hand when the API refuses to create one.
pub const WEBSITE: &str = "https://playit.gg/account/tunnels";

/// What the tunnel looks like for one server port.
#[derive(Clone, Debug, PartialEq)]
pub enum TunnelState {
    /// Working on it (what is happening).
    Starting(String),
    /// Open this link to link the agent to a free playit.gg account.
    Claim(String),
    /// Linked, but no Minecraft tunnel for this port could be made: (reason, page to add one by hand).
    NoTunnel(String, String),
    /// Friends join at this address.
    Online(String),
    Failed(String),
}

#[derive(Clone)]
enum Phase { Starting(String), Claim(String), Linked, Failed(String) }

struct St {
    phase: Phase,
    addrs: HashMap<u16, String>,
    refused: HashMap<u16, String>,
    web: String,
    last_line: String,
}

struct Inner {
    st: Mutex<St>,
    child: Mutex<Option<Child>>,
    stop: AtomicBool,
    ports: Mutex<Vec<u16>>,
}

/// Handle to the playit agent. Cheap to clone; poll `state(port)` from a UI timer.
#[derive(Clone)]
pub struct Tunnel(Arc<Inner>);

/// Download the agent if needed and start it in the background, asking for a tunnel to local `port`.
pub fn start(c: Client, paths: &Paths, port: u16) -> Tunnel {
    let inner = Arc::new(Inner {
        st: Mutex::new(St { phase: Phase::Starting("Downloading the playit.gg agent".into()), addrs: HashMap::new(), refused: HashMap::new(), web: WEBSITE.into(), last_line: String::new() }),
        child: Mutex::new(None),
        stop: AtomicBool::new(false),
        ports: Mutex::new(vec![port]),
    });
    let (w, dir) = (inner.clone(), paths.root.join("tools").join("playit"));
    std::thread::spawn(move || {
        if let Err(e) = run(&c, &w, dir) {
            if !w.stop.load(Ordering::SeqCst) { w.st.lock().unwrap().phase = Phase::Failed(format!("{e:#}")); }
        }
        if let Some(mut ch) = w.child.lock().unwrap().take() { let _ = ch.kill(); let _ = ch.wait(); }
    });
    Tunnel(inner)
}

impl Tunnel {
    /// Also tunnel local `port` (another running server).
    pub fn want(&self, port: u16) {
        let mut p = self.0.ports.lock().unwrap();
        if !p.contains(&port) { p.push(port); self.0.st.lock().unwrap().refused.remove(&port); }
    }

    /// True when a tunnel to local `port` was asked for.
    pub fn wants(&self, port: u16) -> bool { self.0.ports.lock().unwrap().contains(&port) }

    /// State for one server port.
    pub fn state(&self, port: u16) -> TunnelState {
        let s = self.0.st.lock().unwrap();
        match &s.phase {
            Phase::Starting(m) => TunnelState::Starting(m.clone()),
            Phase::Claim(u) => TunnelState::Claim(u.clone()),
            Phase::Failed(e) => TunnelState::Failed(e.clone()),
            Phase::Linked => match (s.addrs.get(&port), s.refused.get(&port)) {
                (Some(a), _) => TunnelState::Online(a.clone()),
                (None, Some(r)) => TunnelState::NoTunnel(r.clone(), s.web.clone()),
                _ => TunnelState::Starting("Creating the tunnel".into()),
            },
        }
    }

    /// True until stopped or failed.
    pub fn alive(&self) -> bool {
        !self.0.stop.load(Ordering::SeqCst) && !matches!(self.0.st.lock().unwrap().phase, Phase::Failed(_))
    }

    /// Stop the agent (tunnels go offline; the playit account and its tunnels stay for next time).
    pub fn stop(&self) {
        self.0.stop.store(true, Ordering::SeqCst);
        if let Some(mut ch) = self.0.child.lock().unwrap().take() { let _ = ch.kill(); let _ = ch.wait(); }
    }
}

fn sleep_unless_stopped(inner: &Inner, d: Duration) -> bool {
    let t = Instant::now();
    while t.elapsed() < d {
        if inner.stop.load(Ordering::SeqCst) { return false; }
        std::thread::sleep(Duration::from_millis(200));
    }
    !inner.stop.load(Ordering::SeqCst)
}

fn run(c: &Client, inner: &Arc<Inner>, dir: PathBuf) -> Result<()> {
    let exe = ensure_agent(c, &dir)?;
    if inner.stop.load(Ordering::SeqCst) { return Ok(()); }
    inner.st.lock().unwrap().phase = Phase::Starting("Starting playit.gg".into());
    let mut cmd = Command::new(&exe);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    let mut child = cmd.current_dir(&dir).arg("--stdout").stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped())
        .spawn().with_context(|| format!("starting {}", exe.display()))?;
    if let Some(o) = child.stdout.take() { pump(o, inner.clone()); }
    if let Some(e) = child.stderr.take() { pump(e, inner.clone()); }
    *inner.child.lock().unwrap() = Some(child);

    loop {
        let exited = match inner.child.lock().unwrap().as_mut() { Some(ch) => ch.try_wait().ok().flatten().is_some(), None => true };
        if inner.stop.load(Ordering::SeqCst) { return Ok(()); }
        if exited {
            let last = inner.st.lock().unwrap().last_line.clone();
            bail!("playit stopped{}", if last.is_empty() { String::new() } else { format!(": {last}") });
        }
        let mut wait = Duration::from_secs(2);
        if let Some(key) = secret() {
            match rundata(c, &key) {
                Ok((id, tunnels)) => {
                    let agent_id = Some(id);
                    let ports = inner.ports.lock().unwrap().clone();
                    let mut missing = vec![];
                    {
                        let mut s = inner.st.lock().unwrap();
                        s.phase = Phase::Linked;
                        s.addrs.clear();
                        for p in &ports {
                            match tunnels.iter().find(|t| t.port == *p) {
                                Some(t) if t.disabled.is_none() => { s.addrs.insert(*p, t.address.clone()); }
                                Some(t) => { s.refused.insert(*p, format!("playit.gg turned the tunnel off: {}", t.disabled.clone().unwrap_or_default())); }
                                None if !s.refused.contains_key(p) => missing.push(*p),
                                None => {}
                            }
                        }
                    }
                    for p in missing {
                        if let Err(e) = create_tunnel(c, &key, agent_id.as_deref(), p) {
                            inner.st.lock().unwrap().refused.insert(p, format!("{e:#}"));
                        }
                    }
                    let s = inner.st.lock().unwrap();
                    if ports.iter().all(|p| s.addrs.contains_key(p) || s.refused.contains_key(p)) { wait = Duration::from_secs(15); }
                }
                Err(e) => inner.st.lock().unwrap().last_line = format!("{e:#}"),
            }
        }
        if !sleep_unless_stopped(inner, wait) { return Ok(()); }
    }
}

fn pump(r: impl Read + Send + 'static, inner: Arc<Inner>) {
    std::thread::spawn(move || {
        for line in BufReader::new(r).lines().map_while(|l| l.ok()) {
            let line = line.trim().to_string();
            if line.is_empty() { continue; }
            let mut s = inner.st.lock().unwrap();
            if let Some(u) = find_url(&line, "https://playit.gg/claim/") {
                if !matches!(s.phase, Phase::Linked) { s.phase = Phase::Claim(u); }
            } else if let Some(u) = find_url(&line, "https://playit.gg/login/guest-account/") {
                s.web = u; // a guest account signs in through this link to see its tunnels
            }
            s.last_line = line;
        }
    });
}

fn find_url(line: &str, prefix: &str) -> Option<String> {
    let i = line.find(prefix)?;
    Some(line[i..].split_whitespace().next()?.trim_end_matches(['.', ',', ')', '"']).to_string())
}

/// The agent key playit saved after the claim (portable agent, or an installed playit service).
fn secret() -> Option<String> {
    let mut paths = vec![];
    if let Some(d) = dirs::config_local_dir() { paths.push(d.join("playit_gg").join("playit.toml")); }
    if let Some(d) = std::env::var_os("PROGRAMDATA") { paths.push(PathBuf::from(d).join("playit_gg").join("playit.toml")); }
    paths.iter().find_map(|p| parse_secret(&std::fs::read_to_string(p).ok()?))
}

fn parse_secret(toml: &str) -> Option<String> {
    toml.lines().find_map(|l| {
        let (k, v) = l.split_once('=')?;
        (k.trim() == "secret_key").then(|| v.trim().trim_matches(['"', '\'']).to_string()).filter(|s| !s.is_empty())
    })
}

fn call(c: &Client, key: &str, path: &str, body: Value) -> Result<Value> {
    let r: Value = c.post(format!("{API}{path}")).header("Authorization", format!("Agent-Key {}", key.trim()))
        .timeout(Duration::from_secs(15)).json(&body).send()?.json()?;
    match r["status"].as_str() {
        Some("success") => Ok(r["data"].clone()),
        Some("fail") => Err(anyhow!("playit.gg refused: {}", r["data"].as_str().map(str::to_string).unwrap_or_else(|| r["data"].to_string()))),
        _ => Err(anyhow!("playit.gg error: {}", r["data"]["message"].as_str().map(str::to_string).unwrap_or_else(|| r["data"].to_string()))),
    }
}

struct Remote { port: u16, address: String, disabled: Option<String> }

/// The agent id and its Minecraft Java (or plain TCP) tunnels, keyed by local port.
fn rundata(c: &Client, key: &str) -> Result<(String, Vec<Remote>)> {
    let d = call(c, key, "/v1/agents/rundata", json!({}))?;
    Ok((d["agent_id"].as_str().unwrap_or_default().to_string(), tunnels_of(&d)))
}

fn tunnels_of(d: &Value) -> Vec<Remote> {
    d["tunnels"].as_array().into_iter().flatten().filter_map(|t| {
        let ty = t["tunnel_type"].as_str().unwrap_or("");
        if !(ty == "minecraft-java" || (ty.is_empty() && t["port_type"] != "udp")) { return None; }
        let port = t["agent_config"]["fields"].as_array()?.iter()
            .find(|f| f["name"] == "local_port")?["value"].as_str()?.trim().parse().ok()?;
        Some(Remote { port, address: t["display_address"].as_str()?.to_string(), disabled: t["disabled_reason"].as_str().map(str::to_string) })
    }).collect()
}

fn create_tunnel(c: &Client, key: &str, agent_id: Option<&str>, port: u16) -> Result<()> {
    call(c, key, "/v1/tunnels/create", json!({
        "ports": { "type": "tunnel-type", "details": "minecraft-java" },
        "origin": { "type": "agent", "data": { "agent_id": agent_id, "config": { "fields": [
            { "name": "local_ip", "value": "127.0.0.1" }, { "name": "local_port", "value": port.to_string() } ] } } },
        "enabled": true, "alloc": null, "name": format!("Nether Client {port}"), "firewall_id": null,
    })).map(|_| ())
}

/// The official agent from playit's GitHub releases, re-downloaded when a new release is out.
fn ensure_agent(c: &Client, dir: &std::path::Path) -> Result<PathBuf> {
    let (exe, tag_file) = (dir.join("playit.exe"), dir.join("version"));
    let rel: Value = match c.get(RELEASE).timeout(Duration::from_secs(15)).send().and_then(|r| r.error_for_status()).and_then(|r| r.json()) {
        Ok(v) => v,
        Err(_) if exe.exists() => return Ok(exe), // offline: keep the one we have
        Err(e) => return Err(e).context("looking up the playit.gg agent release"),
    };
    let tag = rel["tag_name"].as_str().unwrap_or_default();
    if exe.exists() && std::fs::read_to_string(&tag_file).ok().as_deref() == Some(tag) { return Ok(exe); }
    let asset = rel["assets"].as_array().into_iter().flatten().find(|a| a["name"] == ASSET)
        .ok_or_else(|| anyhow!("the playit.gg release {tag} has no {ASSET}"))?;
    let url = asset["browser_download_url"].as_str().ok_or_else(|| anyhow!("no download link for {ASSET}"))?;
    if !url.starts_with("https://github.com/playit-cloud/") { bail!("unexpected download location {url}"); }
    // fail closed: no published sha256 digest, no run
    let want = asset["digest"].as_str().and_then(|d| d.strip_prefix("sha256:")).filter(|d| d.len() == 64)
        .ok_or_else(|| anyhow!("the playit.gg release {tag} has no sha256 digest, so it can't be verified"))?;
    let mut bytes = Vec::new();
    { use std::io::Read; c.get(url).send()?.error_for_status()?.take(100 * 1024 * 1024).read_to_end(&mut bytes)?; }
    if !hex::encode(Sha256::digest(&bytes)).eq_ignore_ascii_case(want) { bail!("the playit.gg download is damaged (sha256 mismatch)"); }
    std::fs::create_dir_all(dir)?;
    let tmp = dir.join("playit.new.exe");
    std::fs::write(&tmp, &bytes)?;
    // the digest only proves the download matches GitHub's listing: the exe must also carry a valid Authenticode
    // signature, from the same publisher as the first copy we accepted (pinned in `signer`)
    let signer_file = dir.join("signer");
    let checked = authenticode(&tmp).and_then(|subject| match std::fs::read_to_string(&signer_file).ok().filter(|s| !s.trim().is_empty()) {
        Some(pinned) if pinned.trim() != subject => bail!("the new playit.gg agent is signed by \"{subject}\", not \"{}\" like before; not running it", pinned.trim()),
        Some(_) => Ok(()),
        None => { std::fs::write(&signer_file, &subject)?; Ok(()) }
    });
    if let Err(e) = checked { let _ = std::fs::remove_file(&tmp); return Err(e); }
    std::fs::rename(&tmp, &exe).context("replacing playit.exe (is it still running?)")?;
    std::fs::write(&tag_file, tag)?;
    Ok(exe)
}

/// Windows' Authenticode check of an exe (Get-AuthenticodeSignature): the signer's subject when the signature is valid.
fn authenticode(path: &std::path::Path) -> Result<String> {
    let lit = path.display().to_string().replace('\'', "''");
    let script = format!("$s = Get-AuthenticodeSignature -LiteralPath '{lit}'; if ($s.Status -eq 'Valid') {{ $s.SignerCertificate.Subject }} else {{ 'INVALID ' + $s.Status }}");
    let ps = std::env::var_os("SystemRoot").map(|r| PathBuf::from(r).join(r"System32\WindowsPowerShell\v1.0\powershell.exe")).filter(|p| p.is_file()).unwrap_or_else(|| PathBuf::from("powershell"));
    let mut cmd = Command::new(ps);
    cmd.args(["-NoProfile", "-NonInteractive", "-Command", &script]);
    #[cfg(windows)]
    { use std::os::windows::process::CommandExt; cmd.creation_flags(0x0800_0000); }
    let out = cmd.output().context("checking the playit.gg agent's signature")?;
    let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if s.is_empty() || s.starts_with("INVALID ") { bail!("the playit.gg agent has no valid signature ({}); not running it", s.trim_start_matches("INVALID ")); }
    Ok(s)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_agent_output_and_api() {
        assert_eq!(find_url("Open this link to finish setting up playit: https://playit.gg/claim/ab12cd.", "https://playit.gg/claim/").as_deref(), Some("https://playit.gg/claim/ab12cd"));
        assert_eq!(parse_secret("secret_key = \"abc123\"\n").as_deref(), Some("abc123"));
        assert_eq!(parse_secret("other = 1"), None);
        let d = json!({"agent_id": "x", "tunnels": [
            {"tunnel_type": "minecraft-java", "port_type": "tcp", "display_address": "ab-cd.gl.joinmc.link", "disabled_reason": null,
             "agent_config": {"fields": [{"name": "local_ip", "value": "127.0.0.1"}, {"name": "local_port", "value": "25566"}]}},
            {"tunnel_type": "minecraft-bedrock", "port_type": "udp", "display_address": "b:1", "agent_config": {"fields": [{"name": "local_port", "value": "19132"}]}}]});
        let t = tunnels_of(&d);
        assert_eq!(t.len(), 1);
        assert_eq!((t[0].port, t[0].address.as_str()), (25566, "ab-cd.gl.joinmc.link"));
    }

    /// Manual: `cargo test -p nether-core --release tunnel_agent_live -- --ignored --nocapture` downloads and starts the agent.
    #[test]
    #[ignore]
    fn tunnel_agent_live() {
        let paths = Paths::new();
        let t = start(crate::download::client(), &paths, 25565);
        for _ in 0..90 {
            let s = t.state(25565);
            println!("{s:?}");
            if matches!(s, TunnelState::Claim(_) | TunnelState::Online(_) | TunnelState::Failed(_) | TunnelState::NoTunnel(..)) { break; }
            std::thread::sleep(Duration::from_secs(1));
        }
        t.stop();
    }
}
