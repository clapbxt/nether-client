//! Discord Rich Presence (opt-in, off by default). Talks only to the Discord app on this PC over its local IPC
//! pipe; nothing goes over the network from us. Shows "Minecraft <version> · <loader>" and the play time, never the
//! instance name or a server address. Hidden entirely until a Discord application id is configured.
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::sync::mpsc::{channel, Sender};
use std::sync::Mutex;

use nether_core::paths::Paths;
use serde_json::{json, Value};

/// Nether Client's Discord application id (empty = feature hidden). Override: env NETHER_DISCORD_APP_ID or
/// config.json {"discord_app_id": "..."}.
const DEFAULT_APP_ID: &str = "";

pub fn app_id(paths: &Paths) -> Option<String> {
    let cfg = nether_core::store::config(paths);
    std::env::var("NETHER_DISCORD_APP_ID").ok()
        .or_else(|| cfg.get("discord_app_id").and_then(Value::as_str).map(str::to_string))
        .or_else(|| Some(DEFAULT_APP_ID.to_string()))
        .map(|s| s.trim().to_string())
        .filter(|s| (15..=25).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_digit()))
}

#[derive(Clone)]
pub struct Activity { pub version: String, pub loader: String, pub since: u64 }

enum Msg { Enabled(bool), Act(Option<Activity>) }

static TX: Mutex<Option<Sender<Msg>>> = Mutex::new(None);

fn send(paths: &Paths, m: Msg) {
    let mut g = TX.lock().unwrap_or_else(|e| e.into_inner());
    if g.is_none() {
        let Some(id) = app_id(paths) else { return };
        let (tx, rx) = channel::<Msg>();
        std::thread::Builder::new().name("discord-rpc".into()).spawn(move || {
            let (mut on, mut act, mut pipe): (bool, Option<Activity>, Option<File>) = (false, None, None);
            while let Ok(m) = rx.recv() {
                match m { Msg::Enabled(e) => on = e, Msg::Act(a) => act = a }
                if !on { pipe = None; continue; } // dropping the pipe clears our status in Discord
                // one reconnect attempt per change: Discord may have been restarted since the last one
                for _ in 0..2 {
                    if pipe.is_none() { pipe = connect(&id); }
                    let Some(p) = pipe.as_mut() else { break };
                    if set_activity(p, act.as_ref()).is_ok() { break; }
                    pipe = None;
                }
            }
        }).ok();
        *g = Some(tx);
    }
    if let Some(tx) = g.as_ref() { let _ = tx.send(m); }
}

/// Turn the feature on or off (launcher setting).
pub fn enable(paths: &Paths, on: bool) { send(paths, Msg::Enabled(on)); }
/// A game started (Some) or the last one closed (None).
pub fn activity(paths: &Paths, a: Option<Activity>) { send(paths, Msg::Act(a)); }

fn frame(p: &mut File, op: u32, body: &Value) -> std::io::Result<Value> {
    let data = body.to_string();
    let mut buf = Vec::with_capacity(8 + data.len());
    buf.extend_from_slice(&op.to_le_bytes());
    buf.extend_from_slice(&(data.len() as u32).to_le_bytes());
    buf.extend_from_slice(data.as_bytes());
    p.write_all(&buf)?;
    let mut head = [0u8; 8];
    p.read_exact(&mut head)?;
    let (rop, len) = (u32::from_le_bytes(head[..4].try_into().unwrap()), u32::from_le_bytes(head[4..].try_into().unwrap()));
    if len > 64 * 1024 { return Err(std::io::Error::other("discord: oversized reply")); }
    let mut reply = vec![0u8; len as usize];
    p.read_exact(&mut reply)?;
    let v: Value = serde_json::from_slice(&reply).unwrap_or(Value::Null);
    // op 2 = close (bad client id, Discord shutting down); evt ERROR = rejected command
    if rop == 2 || v["evt"] == "ERROR" { return Err(std::io::Error::other(format!("discord: {}", v["data"]["message"]))); }
    Ok(v)
}

fn connect(id: &str) -> Option<File> {
    (0..10).find_map(|i| {
        let mut p = OpenOptions::new().read(true).write(true).open(format!(r"\\.\pipe\discord-ipc-{i}")).ok()?;
        frame(&mut p, 0, &json!({ "v": 1, "client_id": id })).ok()?;
        Some(p)
    })
}

fn set_activity(p: &mut File, a: Option<&Activity>) -> std::io::Result<()> {
    let activity = a.map(|a| json!({
        "details": format!("Minecraft {}", a.version),
        "state": format!("{} · Nether Client", a.loader),
        "timestamps": { "start": a.since },
        "assets": { "large_image": "nether", "large_text": "Nether Client" },
    }));
    let nonce = format!("{:x}", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0));
    frame(p, 1, &json!({ "cmd": "SET_ACTIVITY", "args": { "pid": std::process::id(), "activity": activity }, "nonce": nonce })).map(|_| ())
}
