//! Discord Rich Presence (opt-in, off by default). Talks only to the Discord app on this PC over its local IPC
//! pipe; nothing goes over the network from us. The launcher shows "In the launcher" while no game runs; while one
//! runs, the game's own agent (`ov/Discord.java`, started by `-Dnether.discord`, see `main::launch_opts`) shows
//! "Singleplayer|Multiplayer · <version>", "<ign> · <loader>" and the play time, so either one open works alone and the launcher steps aside
//! when both are. Never the instance name, world or server address. Hidden until a Discord application id is configured.
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::sync::mpsc::{channel, Sender};
use std::sync::Mutex;

use nether_core::paths::Paths;
use serde_json::{json, Value};

/// Nether Client's Discord application id (empty = feature hidden). Override: env NETHER_DISCORD_APP_ID or
/// config.json {"discord_app_id": "..."}.
const DEFAULT_APP_ID: &str = "1571524080341876868";

pub fn app_id(paths: &Paths) -> Option<String> {
    let cfg = nether_core::store::config(paths);
    std::env::var("NETHER_DISCORD_APP_ID").ok()
        .or_else(|| cfg.get("discord_app_id").and_then(Value::as_str).map(str::to_string))
        .or_else(|| Some(DEFAULT_APP_ID.to_string()))
        .map(|s| s.trim().to_string())
        .filter(|s| (15..=25).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_digit()))
}

enum Msg { Enabled(bool), Idle(bool) }

fn start_secs() -> u64 {
    static START: std::sync::OnceLock<u64> = std::sync::OnceLock::new();
    *START.get_or_init(|| std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0))
}

static TX: Mutex<Option<Sender<Msg>>> = Mutex::new(None);

fn send(paths: &Paths, m: Msg) {
    let mut g = TX.lock().unwrap_or_else(|e| e.into_inner());
    if g.is_none() {
        let Some(id) = app_id(paths) else { return };
        let (tx, rx) = channel::<Msg>();
        std::thread::Builder::new().name("discord-rpc".into()).spawn(move || {
            let (mut on, mut idle, mut pipe): (bool, bool, Option<File>) = (false, true, None);
            while let Ok(m) = rx.recv() {
                match m { Msg::Enabled(e) => on = e, Msg::Idle(i) => idle = i }
                // Dropping the pipe clears our status in Discord. While a game runs the launcher closes its connection
                // instead of sending a null activity, so the game's agent (same application id, connects a few seconds
                // later) is the only connection Discord sees; an open launcher socket left the game's status hidden.
                if !on || !idle { pipe = None; continue; }
                // one reconnect attempt per change: Discord may have been restarted since the last one
                for _ in 0..2 {
                    if pipe.is_none() { pipe = connect(&id); }
                    let Some(p) = pipe.as_mut() else { break };
                    if set_activity(p, idle).is_ok() { break; }
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
/// A game started (false: its agent owns the status) or the last one closed (true: back to "In the launcher").
pub fn idle(paths: &Paths, idle: bool) { send(paths, Msg::Idle(idle)); }
/// Value for `-Dnether.discord`: the application id when the setting is on, so the game shows its own status.
pub fn game_prop(paths: &Paths, on: bool) -> Option<String> { if on { app_id(paths) } else { None } }

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

fn set_activity(p: &mut File, idle: bool) -> std::io::Result<()> {
    let activity = idle.then(|| json!({
        "details": "In the launcher",
        "state": "Nether Client",
        "timestamps": { "start": start_secs() },
        "assets": { "large_image": "nether", "large_text": "Nether Client" },
    }));
    let nonce = format!("{:x}", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0));
    frame(p, 1, &json!({ "cmd": "SET_ACTIVITY", "args": { "pid": std::process::id(), "activity": activity }, "nonce": nonce })).map(|_| ())
}
