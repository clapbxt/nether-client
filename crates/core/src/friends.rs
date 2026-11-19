//! Friends through the Nether API (`/v1/friends/*`, Worker `src/friends.js`): requests, the friends list with presence,
//! blocks and "ask to join". No chat. The server stores no names: they come from Mojang (`share::lookup` / `name_of`).
//! Presence is a lease the launcher renews with [`sync`] about once a minute; [`bye`] ends it at once.
use anyhow::{anyhow, bail, Result};
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::backend::{api_url, authed};
use crate::Session;

/// Online status a player picks. `Invisible` = friends see you offline.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(into = "u8", from = "u8")]
pub enum Status { Invisible, #[default] Online, Away, Busy }
impl From<Status> for u8 { fn from(s: Status) -> u8 { s as u8 } }
impl From<u8> for Status { fn from(v: u8) -> Self { match v { 0 => Self::Invisible, 2 => Self::Away, 3 => Self::Busy, _ => Self::Online } } }

/// What a player is doing.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(into = "u8", from = "u8")]
pub enum Kind { #[default] Launcher, Menus, Singleplayer, Server, Hosting }
impl From<Kind> for u8 { fn from(s: Kind) -> u8 { s as u8 } }
impl From<u8> for Kind { fn from(v: u8) -> Self { match v { 1 => Self::Menus, 2 => Self::Singleplayer, 3 => Self::Server, 4 => Self::Hosting, _ => Self::Launcher } } }

/// What we tell friends about ourselves.
#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct Presence {
    pub status: Status,
    pub kind: Kind,
    pub ver: String,
    pub loader: String,
    /// server or world name (only when sharing)
    pub place: String,
    /// host:port friends can join (only when sharing)
    pub address: String,
    /// takes friend requests
    pub open: bool,
    /// party endpoint id (64 hex) friends dial for party invites / requests ("" = none)
    pub peer: String,
    /// people in our party (1 = solo, 0 = no party service)
    pub party: u32,
}

#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
pub struct Friend {
    pub uuid: String,
    #[serde(default)]
    pub status: Status,
    #[serde(default)]
    pub kind: Kind,
    #[serde(default)]
    pub ver: String,
    #[serde(default)]
    pub loader: String,
    #[serde(default)]
    pub place: String,
    #[serde(default)]
    pub address: String,
    /// online since (unix s, 0 = offline)
    #[serde(default)]
    pub since: i64,
    /// last seen (unix s; now while online, 0 = never)
    #[serde(default)]
    pub seen: i64,
    #[serde(default)]
    pub since_friends: i64,
    /// their party endpoint id while online ("" = none / older launcher)
    #[serde(default)]
    pub peer: String,
    /// people in their party (0 = unknown)
    #[serde(default)]
    pub party: u32,
}
impl Friend { pub fn online(&self) -> bool { self.status != Status::Invisible } }

#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
pub struct Pending { pub uuid: String, #[serde(default)] pub at: i64 }

#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
pub struct State {
    #[serde(default)]
    pub friends: Vec<Friend>,
    #[serde(default)]
    pub incoming: Vec<Pending>,
    #[serde(default)]
    pub outgoing: Vec<Pending>,
    /// friends asking you to invite them
    #[serde(default)]
    pub asks: Vec<Pending>,
    #[serde(default)]
    pub blocked: Vec<String>,
    #[serde(default = "yes")]
    pub open: bool,
    /// Nether players online right now, everyone (server-cached about a minute; 0 = unknown)
    #[serde(default)]
    pub online: u32,
}
fn yes() -> bool { true }

fn service() -> Result<String> {
    let api = api_url();
    if api.is_empty() { bail!("the Nether friends service isn't set up"); }
    Ok(api)
}

fn api_error(r: reqwest::blocking::Response) -> anyhow::Error {
    let s = r.status();
    let msg = r.json::<Value>().ok().and_then(|v| v["error"].as_str().map(str::to_string));
    match (s.as_u16(), msg) {
        (_, Some(m)) => anyhow!("{m}"),
        (429, None) => anyhow!("slow down a moment"),
        (404, None) => anyhow!("the friends service isn't live yet"),
        (n, None) => anyhow!("the friends service answered HTTP {n}"),
    }
}

fn valid_uuid(u: &str) -> bool { u.len() == 32 && u.bytes().all(|b| b.is_ascii_hexdigit()) }

/// Renew presence and read the whole friends state.
pub fn sync(c: &Client, s: &Session, me: &Presence) -> Result<State> {
    let api = service()?;
    let r = authed(c, s, |t| c.post(format!("{api}/v1/friends/sync")).bearer_auth(t).json(me))?;
    if !r.status().is_success() { return Err(api_error(r)); }
    let mut st: State = r.json()?;
    st.friends.retain(|f| valid_uuid(&f.uuid));
    for f in &mut st.friends {
        if !crate::share::valid_address(&f.address) { f.address.clear(); }
        f.place = f.place.chars().filter(|c| !c.is_control()).take(64).collect();
        if !crate::party::valid_peer(&f.peer) { f.peer.clear(); }
        f.party = f.party.min(999);
    }
    st.incoming.retain(|p| valid_uuid(&p.uuid));
    st.outgoing.retain(|p| valid_uuid(&p.uuid));
    st.asks.retain(|p| valid_uuid(&p.uuid));
    Ok(st)
}

/// Go offline now (launcher closing). Best effort.
pub fn bye(c: &Client, s: &Session) {
    if let Ok(api) = service() {
        let _ = authed(c, s, |t| c.post(format!("{api}/v1/friends/sync")).bearer_auth(t).json(&json!({ "bye": true, "status": 1 })));
    }
}

/// An action on another player: request, accept, deny, cancel, remove, block, unblock, ask, dismiss.
/// `request` answers Ok(true) when you are friends right away (they had asked you).
pub fn act(c: &Client, s: &Session, op: &str, uuid: &str) -> Result<bool> {
    const OPS: [&str; 9] = ["request", "accept", "deny", "cancel", "remove", "block", "unblock", "ask", "dismiss"];
    if !OPS.contains(&op) || !valid_uuid(uuid) { bail!("bad friends action"); }
    let api = service()?;
    let r = authed(c, s, |t| c.post(format!("{api}/v1/friends/{op}")).bearer_auth(t).json(&json!({ "uuid": uuid })))?;
    if !r.status().is_success() { return Err(api_error(r)); }
    Ok(r.json::<Value>().ok().is_some_and(|v| v["friends"] == true))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parses_state() {
        let st: State = serde_json::from_str(r#"{"friends":[{"uuid":"0123456789abcdef0123456789abcdef","status":2,"kind":3,"ver":"1.21.11","loader":"Fabric","place":"Hypixel","address":"mc.hypixel.net:25565","since":5,"seen":9,"since_friends":1}],"incoming":[{"uuid":"aa","at":1}],"outgoing":[],"asks":[],"blocked":[],"open":false}"#).unwrap();
        assert_eq!(st.friends[0].status, Status::Away);
        assert_eq!(st.friends[0].kind, Kind::Server);
        assert!(!st.open);
        assert_eq!(serde_json::to_value(Presence { status: Status::Busy, open: true, ..Default::default() }).unwrap()["status"], 3);
    }
}
