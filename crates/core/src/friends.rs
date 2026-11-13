//! Friends-lite client (opt-in): mutual friend codes and hosting-only presence through the Nether API
//! (`/v1/friends*`, session token from the Mojang handshake in `backend`). No names are stored server side; names come
//! from Mojang's public profile lookup and are cached for the session.
use std::collections::HashMap;
use std::sync::Mutex;

use anyhow::{bail, Result};
use reqwest::blocking::Client;
use serde::Deserialize;

use crate::backend::{api_url, authed};
use crate::Session;

#[derive(Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct Friend {
    pub uuid: String,
    /// accepted | incoming | outgoing
    pub state: String,
    /// their hosted server address (accepted friends only, while they host)
    #[serde(default)]
    pub host: Option<String>,
    #[serde(default)]
    pub host_until: Option<i64>,
    /// filled in by `state()` from Mojang (not sent by the API)
    #[serde(skip)]
    pub name: String,
}

#[derive(Deserialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct State {
    pub on: bool,
    #[serde(default)]
    pub code: String,
    #[serde(default)]
    pub code_exp: i64,
    #[serde(default)]
    pub friends: Vec<Friend>,
}

/// Whether the service has Friends switched on (public, no sign-in). Off or unreachable = the launcher hides Friends.
pub fn enabled(c: &Client) -> bool {
    let api = api_url();
    if api.is_empty() { return false; }
    c.get(format!("{api}/v1/friends/flag")).send().ok().filter(|r| r.status().is_success())
        .and_then(|r| r.json::<serde_json::Value>().ok()).map(|v| v["enabled"] == true).unwrap_or(false)
}

fn reply(r: reqwest::blocking::Response) -> Result<State> {
    if !r.status().is_success() {
        let code = r.status();
        let msg = r.json::<serde_json::Value>().ok().and_then(|v| v["error"].as_str().map(str::to_string));
        bail!("{}", msg.unwrap_or_else(|| format!("the friends service answered {code}")));
    }
    Ok(r.json()?)
}

fn with_names(c: &Client, mut s: State) -> State {
    for f in &mut s.friends { f.name = name_of(c, &f.uuid); }
    s.friends.sort_by(|a, b| rank(a).cmp(&rank(b)).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
    s
}
/// incoming requests first, then friends hosting, other friends, sent requests
fn rank(f: &Friend) -> u8 { match f.state.as_str() { "incoming" => 0, "accepted" if f.host.is_some() => 1, "accepted" => 2, _ => 3 } }

pub fn state(c: &Client, s: &Session) -> Result<State> {
    let api = api_url();
    Ok(with_names(c, reply(authed(c, s, |t| c.get(format!("{api}/v1/friends")).bearer_auth(t))?)?))
}
/// Turn Friends on (creates your friend code).
pub fn turn_on(c: &Client, s: &Session) -> Result<State> {
    let api = api_url();
    Ok(with_names(c, reply(authed(c, s, |t| c.post(format!("{api}/v1/friends/on")).bearer_auth(t))?)?))
}
/// Turn Friends off: deletes your code, friends, requests and blocks on the server.
pub fn turn_off(c: &Client, s: &Session) -> Result<State> {
    let api = api_url();
    reply(authed(c, s, |t| c.delete(format!("{api}/v1/friends")).bearer_auth(t))?)
}
pub fn new_code(c: &Client, s: &Session) -> Result<State> {
    let api = api_url();
    Ok(with_names(c, reply(authed(c, s, |t| c.post(format!("{api}/v1/friends/code")).bearer_auth(t))?)?))
}
pub fn add(c: &Client, s: &Session, code: &str) -> Result<State> {
    let api = api_url();
    let code: String = code.chars().filter(|ch| ch.is_ascii_alphanumeric()).collect::<String>().to_ascii_uppercase();
    if code.len() != 8 { bail!("A friend code has 8 letters and numbers"); }
    Ok(with_names(c, reply(authed(c, s, |t| c.post(format!("{api}/v1/friends/add")).bearer_auth(t).json(&serde_json::json!({ "code": code })))?)?))
}
/// accept | decline | remove | block
pub fn respond(c: &Client, s: &Session, uuid: &str, action: &str) -> Result<State> {
    let api = api_url();
    Ok(with_names(c, reply(authed(c, s, |t| c.post(format!("{api}/v1/friends/respond")).bearer_auth(t).json(&serde_json::json!({ "uuid": uuid, "action": action })))?)?))
}
/// Show your hosted server's address to your friends for `minutes` (None = stop sharing).
pub fn share_host(c: &Client, s: &Session, host: Option<&str>, minutes: u32) -> Result<()> {
    let api = api_url();
    let r = authed(c, s, |t| c.put(format!("{api}/v1/friends/host")).bearer_auth(t).json(&serde_json::json!({ "host": host, "minutes": minutes })))?;
    if !r.status().is_success() { bail!("couldn't share the server with friends ({})", r.status()); }
    Ok(())
}

static NAMES: Mutex<Option<HashMap<String, String>>> = Mutex::new(None);

/// A player's name from Mojang's public profile lookup (cached; falls back to a short uuid).
fn name_of(c: &Client, uuid: &str) -> String {
    if let Some(n) = NAMES.lock().unwrap_or_else(|e| e.into_inner()).as_ref().and_then(|m| m.get(uuid)) { return n.clone(); }
    let ok_uuid = uuid.len() == 32 && uuid.bytes().all(|b| b.is_ascii_hexdigit());
    let name = ok_uuid.then(|| c.get(format!("https://sessionserver.mojang.com/session/minecraft/profile/{uuid}")).send().ok()).flatten()
        .filter(|r| r.status().is_success()).and_then(|r| r.json::<serde_json::Value>().ok())
        .and_then(|v| v["name"].as_str().filter(|n| n.len() <= 16 && n.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')).map(str::to_string));
    match name {
        Some(n) => { NAMES.lock().unwrap_or_else(|e| e.into_inner()).get_or_insert_with(HashMap::new).insert(uuid.to_string(), n.clone()); n }
        None => format!("Player {}", &uuid[..uuid.len().min(6)]),
    }
}
