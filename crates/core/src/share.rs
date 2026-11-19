//! Invite to world: a singleplayer world opened to friends from inside the game.
//! The game opens its world to LAN on a free port (the agent, `ov/WorldShare`); the launcher links friends to it
//! peer-to-peer (`p2p.rs`, nothing to set up), checks whether plain Minecraft can reach it too (UPnP + an outside
//! check) and sends invites through the Nether API. An invited player's launcher lists the invite and starts a matching instance (same version and
//! loader) straight into the world with Quick Play, the way OneLauncher joins: one match launches, several ask, none
//! offers to make one. A running game that matches joins from its own invite card.
use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::sync::Mutex;

use anyhow::{anyhow, bail, Result};
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::backend::{api_url, authed};
use crate::net;
use crate::Session;

/// A Minecraft account name: 1-16 of A-Z a-z 0-9 _.
pub fn valid_name(n: &str) -> bool { (1..=16).contains(&n.len()) && n.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_') }

/// An address an invite may carry: a P2P invite (`p2p::is_ticket`) or host:port (letters, digits, dots, dashes).
pub fn valid_address(a: &str) -> bool {
    if crate::p2p::is_ticket(a) { return true; }
    let Some((h, p)) = a.rsplit_once(':') else { return false };
    a.len() <= 96 && !h.is_empty() && p.parse::<u16>().is_ok_and(|p| p > 0)
        && h.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-')
}

/// Mojang account lookup: name -> (uuid without dashes, canonical name).
pub fn lookup(c: &Client, name: &str) -> Result<(String, String)> {
    if !valid_name(name) { bail!("'{name}' isn't a Minecraft name"); }
    let r = c.get(format!("https://api.mojang.com/users/profiles/minecraft/{name}")).send()?;
    match r.status().as_u16() {
        200 => {
            let v: Value = r.json()?;
            let id = v["id"].as_str().unwrap_or_default().replace('-', "").to_lowercase();
            if id.len() != 32 { bail!("Mojang sent a bad answer"); }
            Ok((id, v["name"].as_str().unwrap_or(name).to_string()))
        }
        204 | 404 => bail!("no Minecraft account is called {name}"),
        429 => bail!("Mojang is busy, try again in a minute"),
        s => bail!("Mojang lookup failed (HTTP {s})"),
    }
}

static NAMES: Mutex<Option<HashMap<String, String>>> = Mutex::new(None);

/// Current name of a player (uuid without dashes) from Mojang's session server; cached for this run.
pub fn name_of(c: &Client, uuid: &str) -> Option<String> {
    if let Some(n) = NAMES.lock().unwrap_or_else(|e| e.into_inner()).get_or_insert_with(HashMap::new).get(uuid) { return Some(n.clone()); }
    if uuid.len() != 32 || !uuid.bytes().all(|b| b.is_ascii_hexdigit()) { return None; }
    let v: Value = c.get(format!("https://sessionserver.mojang.com/session/minecraft/profile/{uuid}")).send().ok()?.error_for_status().ok()?.json().ok()?;
    let n = v["name"].as_str().filter(|n| valid_name(n))?.to_string();
    NAMES.lock().unwrap_or_else(|e| e.into_inner()).get_or_insert_with(HashMap::new).insert(uuid.to_string(), n.clone());
    Some(n)
}

// ---------------- reachability ----------------

/// Whether plain Minecraft can reach the world at `public` directly (no Nether Client needed).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Reach { pub ok: bool, pub public: String, pub msg: String }

/// Look up the public address (the router's, else an echo service) and ask an outside service whether the world
/// answers there. Blocking (up to ~25 s); call from a worker thread.
pub fn reach(c: &Client, port: u16, upnp: Result<Option<Ipv4Addr>, String>) -> Reach {
    if let Ok(None) = upnp {
        return Reach { msg: "Your router has no public address of its own (your provider shares one)".into(), ..Default::default() };
    }
    let ip = match upnp.as_ref().ok().copied().flatten().map(Ok).unwrap_or_else(|| net::echo_ip(c)) {
        Ok(ip) => ip,
        Err(e) => return Reach { msg: format!("Couldn't look up your public address ({e:#})"), ..Default::default() },
    };
    let public = net::Invite { host: ip.to_string(), port }.address();
    match net::reachable(c, &ip.to_string(), port) {
        Ok(true) => Reach { ok: true, public, msg: String::new() },
        Ok(false) => Reach { public, msg: match upnp {
            Ok(_) => "The port is open but still blocked (Windows Firewall or a second router)".into(),
            Err(e) => format!("Your router didn't open the port ({e})"),
        }, ..Default::default() },
        Err(e) => Reach { public, msg: format!("Couldn't check from outside ({e:#})"), ..Default::default() },
    }
}

// ---------------- invites (Nether API) ----------------

/// An invite to someone's world.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Invite {
    pub id: i64,
    /// sender uuid (no dashes)
    pub from: String,
    pub address: String,
    pub world: String,
    pub ver: String,
    /// loader label as the launcher shows it (Vanilla, Fabric, Forge, NeoForge, Quilt, Legacy Fabric)
    pub loader: String,
    /// sent (unix seconds)
    pub at: i64,
}

fn api_error(r: reqwest::blocking::Response) -> anyhow::Error {
    let s = r.status();
    let msg = r.json::<Value>().ok().and_then(|v| v["error"].as_str().map(str::to_string));
    match (s.as_u16(), msg) {
        (429, _) => anyhow!("too many invites in a minute, wait a moment"),
        (_, Some(m)) => anyhow!("{m}"),
        (n, None) => anyhow!("the invite service answered HTTP {n}"),
    }
}

fn service() -> Result<String> {
    let api = api_url();
    if api.is_empty() { bail!("the Nether invite service isn't set up"); }
    Ok(api)
}

/// Invite player `to` (uuid, no dashes) to the world at `address`.
pub fn send(c: &Client, s: &Session, to: &str, address: &str, world: &str, ver: &str, loader: &str) -> Result<()> {
    let api = service()?;
    let body = json!({ "to": to, "address": address, "world": world, "ver": ver, "loader": loader });
    let r = authed(c, s, |t| c.post(format!("{api}/v1/invites")).bearer_auth(t).json(&body))?;
    if !r.status().is_success() { return Err(api_error(r)); }
    Ok(())
}

/// Invites waiting for this player (newest first).
pub fn incoming(c: &Client, s: &Session) -> Result<Vec<Invite>> {
    let api = service()?;
    let r = authed(c, s, |t| c.get(format!("{api}/v1/invites")).bearer_auth(t))?;
    if !r.status().is_success() { return Err(api_error(r)); }
    let v: Value = r.json()?;
    let mut out: Vec<Invite> = serde_json::from_value(v["invites"].clone()).unwrap_or_default();
    out.retain(|i| valid_address(&i.address) && i.from.len() == 32);
    Ok(out)
}

/// Drop an invite (declined, or joined).
pub fn dismiss(c: &Client, s: &Session, id: i64) -> Result<()> {
    let api = service()?;
    let r = authed(c, s, |t| c.delete(format!("{api}/v1/invites/{id}")).bearer_auth(t))?;
    if !r.status().is_success() && r.status() != reqwest::StatusCode::NOT_FOUND { return Err(api_error(r)); }
    Ok(())
}

/// Withdraw every invite this player sent (the world closed or stopped sharing).
pub fn cancel_all(c: &Client, s: &Session) -> Result<()> {
    let api = service()?;
    let r = authed(c, s, |t| c.delete(format!("{api}/v1/invites")).bearer_auth(t))?;
    if !r.status().is_success() { return Err(api_error(r)); }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses_and_names() {
        assert!(valid_address("1.2.3.4:25565") && valid_address("abc-de.joinmc.link:4567"));
        assert!(!valid_address("1.2.3.4") && !valid_address("bad host:1") && !valid_address("x:0") && !valid_address(":25565"));
        assert!(valid_name("Steve_01") && !valid_name("") && !valid_name("way_too_long_name_x") && !valid_name("a b"));
    }
}
