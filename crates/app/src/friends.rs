//! Friends (top-bar drawer + in-game Friends screen): requests, the friends list with live status, JOIN a friend's
//! server or world when they share it, INVITE friends to yours, ASK TO JOIN, blocks, status and privacy switches.
//! No chat. The Nether API keeps the friend graph and a presence lease (`core::friends`); names and heads come from
//! Mojang. A worker thread syncs about once a minute (sooner after any action) and feeds both the drawer and the game:
//! the launcher writes `client/friends/state.json` (+ `heads/<uuid>.png`), the game writes `request.json` (actions)
//! and `activity.json` (what it is doing: menus / singleplayer / server address). No tokens ever reach the game.
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use nether_core::download::client;
use nether_core::friends::{self, Friend, Kind, Presence, State, Status};
use nether_core::{instances, share, Session};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use slint::{ComponentHandle, Image, ModelRc, Rgba8Pixel, SharedPixelBuffer, VecModel};

use crate::{show_toast, toast_action, ui_do, AppWindow, FriendRow, Friends, Shared};

/// Saved choices: `<root>/friends.json`.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
struct Prefs {
    status: u8,
    share: bool,
    open: bool,
    notify: bool,
    stars: Vec<String>,
    /// last known names (shown at once on the next start)
    names: HashMap<String, String>,
}
impl Default for Prefs {
    fn default() -> Self { Self { status: 1, share: true, open: true, notify: true, stars: vec![], names: HashMap::new() } }
}

/// What the game said it is doing (`activity.json`).
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
struct Activity { kind: u8, name: String, address: String }

#[derive(Clone)]
struct Head { rgba: Vec<u8>, at: Instant }

struct St {
    prefs: Prefs,
    state: Option<State>,
    msg: String,
    filter: String,
    heads: HashMap<String, Head>,
    /// game events for in-game toasts: (id, kind, name)
    events: Vec<(u64, &'static str, String, String)>,
    next_event: u64,
    answer: Option<(f64, bool, String)>,
    /// what we last told the service (address = joinable spot of ours)
    me: Presence,
    /// the running game's (version, loader label)
    game: Option<(String, String)>,
    /// our party endpoint id + party size (app/party.rs), published to friends
    party: (String, u32),
}

static ST: Mutex<Option<St>> = Mutex::new(None);
static WAKE: (Mutex<bool>, Condvar) = (Mutex::new(false), Condvar::new());
/// friends to tell "check now" after our next sync (it lists their party key once we're friends)
static NUDGE: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// True while a friend request we sent is unanswered (their launcher may knock to say "we're friends now").
pub fn waiting() -> bool { with(|s| s.state.as_ref().is_some_and(|st| !st.outgoing.is_empty())) }

fn with<R>(f: impl FnOnce(&mut St) -> R) -> R {
    let mut g = ST.lock().unwrap_or_else(|e| e.into_inner());
    f(g.get_or_insert_with(|| St { prefs: Prefs::default(), state: None, msg: String::new(), filter: String::new(), heads: HashMap::new(),
        events: vec![], next_event: 1, answer: None, me: Presence::default(), game: None, party: (String::new(), 0) }))
}

/// Sync now instead of waiting for the next tick.
fn wake() { *WAKE.0.lock().unwrap_or_else(|e| e.into_inner()) = true; WAKE.1.notify_all(); }

fn dir(sh: &Shared) -> PathBuf { sh.paths.root.join("client").join("friends") }
fn prefs_file(sh: &Shared) -> PathBuf { sh.paths.root.join("friends.json") }
fn save_prefs(sh: &Shared) { let p = with(|s| s.prefs.clone()); let _ = nether_core::store::write_json(&prefs_file(sh), &p); }

fn session(sh: &Shared) -> Option<Session> {
    sh.session.lock().unwrap_or_else(|e| e.into_inner()).clone().filter(|s| s.user_type != "legacy")
}

pub fn name(uuid: &str) -> String { with(|s| s.prefs.names.get(uuid).cloned()).unwrap_or_else(|| format!("{}…", &uuid[..8.min(uuid.len())])) }

// ---------------- names + heads (Mojang) ----------------

/// Name and face for every uuid we show; profiles refresh every 6 hours. Worker thread.
fn resolve(sh: &Shared, uuids: &[String]) {
    let c = client();
    let mut changed = false;
    let mut limited = false;
    for u in uuids {
        let fresh = with(|s| s.heads.get(u).is_some_and(|h| h.at.elapsed() < Duration::from_secs(6 * 3600)) && s.prefs.names.contains_key(u));
        if fresh { continue; }
        let r = if limited { None } else { c.get(format!("https://sessionserver.mojang.com/session/minecraft/profile/{u}")).send().ok() };
        if r.as_ref().is_some_and(|r| r.status().as_u16() == 429) { limited = true; }
        let v = r.filter(|r| r.status().is_success()).and_then(|r| r.json::<Value>().ok());
        let Some(v) = v else {
            // sessionserver busy or down: a name still beats a uuid stub (separate rate limit, no skin)
            if with(|s| s.prefs.names.contains_key(u)) { continue; }
            let n = c.get(format!("https://api.minecraftservices.com/minecraft/profile/lookup/{u}")).send().ok()
                .filter(|r| r.status().is_success()).and_then(|r| r.json::<Value>().ok())
                .and_then(|v| v["name"].as_str().filter(|n| share::valid_name(n)).map(str::to_string));
            if let Some(n) = n { with(|s| s.prefs.names.insert(u.clone(), n)); changed = true; }
            continue;
        };
        if let Some(n) = v["name"].as_str().filter(|n| share::valid_name(n)) {
            with(|s| s.prefs.names.insert(u.clone(), n.to_string()));
            changed = true;
        }
        let tex = v["properties"].as_array().and_then(|a| a.iter().find(|p| p["name"] == "textures")).and_then(|p| p["value"].as_str());
        let face = tex.and_then(|t| nether_core::skins::parse_textures(t).ok())
            .and_then(|(url, _)| nether_core::skins::get_public(&url.replacen("http://", "https://", 1), 256 * 1024).ok())
            .and_then(|png| image::load_from_memory(&png).ok()).map(|i| i.to_rgba8()).filter(|i| i.width() == 64)
            .map(|skin| {
                let mut f = image::imageops::crop_imm(&skin, 8, 8, 8, 8).to_image();
                image::imageops::overlay(&mut f, &image::imageops::crop_imm(&skin, 40, 8, 8, 8).to_image(), 0, 0);
                image::imageops::resize(&f, 32, 32, image::imageops::FilterType::Nearest)
            });
        let rgba = face.map(|f| {
            let _ = std::fs::create_dir_all(dir(sh).join("heads"));
            let _ = f.save(dir(sh).join("heads").join(format!("{u}.png")));
            f.into_raw()
        }).unwrap_or_default();
        with(|s| s.heads.insert(u.clone(), Head { rgba, at: Instant::now() }));
    }
    if changed { save_prefs(sh); }
}

// ---------------- presence ----------------

/// The running game (version, loader) if any.
fn running(sh: &Shared) -> Option<(String, String)> {
    let names: Vec<String> = sh.running.lock().unwrap_or_else(|e| e.into_inner()).keys().cloned().collect();
    let first = names.first()?;
    instances::load(&sh.paths)?.into_iter().find(|d| &d.name == first).map(|d| (d.version, d.loader))
}


fn presence(sh: &Shared) -> Presence {
    let p = with(|s| s.prefs.clone());
    let (peer, party) = with(|s| s.party.clone());
    let mut me = Presence { status: Status::from(p.status), open: p.open, peer, party, ..Default::default() };
    let game = running(sh);
    with(|s| s.game = game.clone());
    let Some((ver, loader)) = game else { return me };
    me.ver = ver;
    me.loader = loader;
    let act: Activity = nether_core::store::peek_json(&dir(sh).join("activity.json")).unwrap_or_default();
    me.kind = match act.kind { 2 => Kind::Singleplayer, 3 => Kind::Server, _ => Kind::Menus };
    // a hosted world is joined through Invite to world (peer-to-peer ticket), never by address: friends ASK, we INVITE
    if let Some(world) = crate::worldshare::hosting() {
        me.kind = Kind::Hosting;
        if p.share { me.place = world.chars().take(64).collect(); }
    } else if me.kind == Kind::Server && p.share && share::valid_address(&act.address) {
        me.address = act.address.clone();
        me.place = act.name.chars().take(64).collect();
    }
    me
}

// ---------------- for the party (app/party.rs) ----------------

/// Publish our party endpoint id and party size to friends (synced at once when it changed).
pub fn set_party(peer: String, size: u32) {
    if with(|s| { let ch = s.party != (peer.clone(), size); s.party = (peer, size); ch }) { wake(); }
}

/// The friends list as last synced (None until the first sync).
pub fn list() -> Option<Vec<Friend>> { with(|s| s.state.as_ref().map(|st| st.friends.clone())) }

/// Players we blocked.
pub fn blocked() -> Vec<String> { with(|s| s.state.as_ref().map(|st| st.blocked.clone()).unwrap_or_default()) }

/// Remember a name (party members who aren't friends).
pub fn learn_name(uuid: &str, n: &str) {
    if share::valid_name(n) { with(|s| { if !s.prefs.names.contains_key(uuid) { s.prefs.names.insert(uuid.to_string(), n.to_string()); } }); }
}

/// Block someone (party member card): same as the friends list's BLOCK, no dialog (the game asked to confirm).
pub fn block(weak: &slint::Weak<AppWindow>, sh: &Arc<Shared>, uuid: &str) { run_act(weak, sh, "block".into(), uuid.to_string(), None); }

#[cfg(test)]
pub fn test_state(st: State) { with(|s| s.state = Some(st)); }

/// Sync soon (a party change friends should see).
pub fn poke() { wake(); }

// ---------------- the loop ----------------

pub fn init(ui: &AppWindow, sh: Arc<Shared>) {
    let prefs: Prefs = nether_core::store::peek_json(&prefs_file(&sh)).unwrap_or_default();
    with(|s| s.prefs = prefs);
    wire(ui, &sh);
    apply(ui);
    if std::env::var("NC_SNAP_STATE").is_ok_and(|s| s.starts_with("friends")) { return; } // captures keep their sample people
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let d = dir(&sh);
        let _ = std::fs::create_dir_all(&d);
        let mut last_sync: Option<Instant> = None;
        let mut last_text = String::new();
        let mut was_signed = false;
        let mut tick = 0u32;
        let mut me = Presence::default();
        loop {
            tick = tick.wrapping_add(1);
            // game actions
            if let Ok(b) = std::fs::read(d.join("request.json")) {
                let _ = std::fs::remove_file(d.join("request.json"));
                if let Ok(r) = serde_json::from_slice::<Value>(&b) { game_request(&weak, &sh, &r); }
            }
            let signed = session(&sh).is_some();
            if signed != was_signed { was_signed = signed; ui_do(&weak, move |ui| { ui.global::<Friends>().set_ready(signed); }); }
            let game = !sh.running.lock().unwrap_or_else(|e| e.into_inner()).is_empty();
            let every = if game { 45 } else { 90 };
            if tick % 4 == 1 || last_sync.is_none() { me = presence(&sh); }
            let changed = with(|s| s.me != me);
            if signed && (changed || last_sync.is_none_or(|t| t.elapsed() >= Duration::from_secs(every))) {
                last_sync = Some(Instant::now());
                sync(&weak, &sh, me.clone());
            }
            let text = game_state().to_string();
            if text != last_text && nether_core::store::write_atomic(&d.join("state.json"), text.as_bytes()).is_ok() { last_text = text; }
            // sleep 1 s (game requests are picked up fast) unless woken
            let (m, cv) = &WAKE;
            let g = m.lock().unwrap_or_else(|e| e.into_inner());
            let (mut g, _) = cv.wait_timeout_while(g, Duration::from_secs(1), |w| !*w).unwrap_or_else(|e| e.into_inner());
            if *g { last_sync = None; }
            *g = false;
        }
    });
}

/// Launcher closing: tell friends we went offline (short, best effort).
pub fn shutdown(sh: &Shared) {
    let Some(s) = session(sh) else { return };
    let t = std::thread::spawn(move || friends::bye(&client(), &s));
    let start = Instant::now();
    while !t.is_finished() && start.elapsed() < Duration::from_secs(3) { std::thread::sleep(Duration::from_millis(50)); }
}

fn sync(weak: &slint::Weak<AppWindow>, sh: &Arc<Shared>, me: Presence) {
    let Some(s) = session(sh) else { return };
    match friends::sync(&client(), &s, &me) {
        Ok(new) => {
            let mut ids: Vec<String> = new.friends.iter().map(|f| f.uuid.clone()).collect();
            ids.extend(new.incoming.iter().chain(&new.outgoing).chain(&new.asks).map(|p| p.uuid.clone()));
            ids.extend(new.blocked.iter().cloned());
            resolve(sh, &ids);
            let old = with(|st| { st.me = me; st.msg.clear(); st.prefs.open = new.open; st.state.replace(new.clone()) });
            for u in std::mem::take(&mut *NUDGE.lock().unwrap_or_else(|e| e.into_inner())) { crate::party::nudge_friend(&u); }
            let notes = old.map(|o| notes(&o, &new)).unwrap_or_default();
            let quiet = with(|st| !st.prefs.notify || st.prefs.status == 3);
            with(|st| for (kind, uuid) in &notes {
                let id = st.next_event;
                st.next_event += 1;
                let n = st.prefs.names.get(uuid).cloned().unwrap_or_default();
                st.events.push((id, *kind, uuid.clone(), n));
                if st.events.len() > 20 { st.events.remove(0); }
            });
            let game = !sh.running.lock().unwrap_or_else(|e| e.into_inner()).is_empty();
            let sh2 = sh.clone();
            ui_do(weak, move |ui| {
                apply(ui);
                if quiet || game { return; } // the game shows its own pop-ups
                for (kind, uuid) in notes.into_iter().take(3) {
                    let n = name(&uuid);
                    match kind {
                        "request" => toast_action(ui, &sh2, &format!("{n} sent you a friend request"), false, "VIEW", |ui| {
                            let f = ui.global::<Friends>(); f.set_tab(1); f.set_open(true);
                        }),
                        "ask" => toast_action(ui, &sh2, &format!("{n} wants to join you"), false, "VIEW", |ui| {
                            let f = ui.global::<Friends>(); f.set_tab(1); f.set_open(true);
                        }),
                        "accepted" => show_toast(ui, &sh2, &format!("{n} accepted your friend request")),
                        _ => show_toast(ui, &sh2, &format!("{n} is online")),
                    }
                }
            });
        }
        Err(e) => {
            let msg = cap(&format!("{e:#}"));
            with(|st| st.msg = msg);
            ui_do(weak, apply);
        }
    }
}

/// What changed between two syncs that deserves a pop-up.
fn notes(old: &State, new: &State) -> Vec<(&'static str, String)> {
    let mut out = vec![];
    let was_on: HashSet<&str> = old.friends.iter().filter(|f| f.online()).map(|f| f.uuid.as_str()).collect();
    let were: HashSet<&str> = old.friends.iter().map(|f| f.uuid.as_str()).collect();
    let inc: HashSet<&str> = old.incoming.iter().map(|p| p.uuid.as_str()).collect();
    let asks: HashSet<(&str, i64)> = old.asks.iter().map(|p| (p.uuid.as_str(), p.at)).collect();
    let sent: HashSet<&str> = old.outgoing.iter().map(|p| p.uuid.as_str()).collect();
    for p in &new.incoming { if !inc.contains(p.uuid.as_str()) { out.push(("request", p.uuid.clone())); } }
    for p in &new.asks { if !asks.contains(&(p.uuid.as_str(), p.at)) { out.push(("ask", p.uuid.clone())); } }
    for f in &new.friends {
        if !were.contains(f.uuid.as_str()) && sent.contains(f.uuid.as_str()) { out.push(("accepted", f.uuid.clone())); }
        else if were.contains(f.uuid.as_str()) && f.online() && !was_on.contains(f.uuid.as_str()) { out.push(("online", f.uuid.clone())); }
    }
    out
}

/// 12345 -> "12,345"
fn group(n: u32) -> String {
    let d = n.to_string();
    d.chars().enumerate().fold(String::new(), |mut o, (i, c)| { if i > 0 && (d.len() - i) % 3 == 0 { o.push(','); } o.push(c); o })
}
fn cap(s: &str) -> String { let mut c = s.chars(); c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default() }

fn ago(t: i64) -> String {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
    let d = (now - t).max(0);
    match d {
        _ if t <= 0 => String::new(),
        0..=119 => "just now".into(),
        120..=3599 => format!("{} min ago", d / 60),
        3600..=86_399 => format!("{} h ago", d / 3600),
        _ => format!("{} d ago", d / 86_400),
    }
}

/// "Offline · seen 5 min ago", or "Offline" when never seen.
pub fn offline_line(f: &Friend) -> String {
    if f.seen > 0 { format!("Offline · seen {}", ago(f.seen)) } else { "Offline".into() }
}

pub fn game_line(f: &Friend) -> String {
    let game = match (f.ver.is_empty(), f.loader.as_str()) {
        (true, _) => "Minecraft".to_string(),
        (false, "" | "Vanilla") => f.ver.clone(),
        (false, l) => format!("{} {l}", f.ver),
    };
    match f.kind {
        Kind::Launcher => "In the launcher".into(),
        Kind::Menus => format!("Playing {game}"),
        Kind::Singleplayer => format!("Singleplayer · {game}"),
        Kind::Server if !f.place.is_empty() => format!("On {} · {game}", f.place),
        Kind::Server => format!("Multiplayer · {game}"),
        Kind::Hosting if !f.place.is_empty() => format!("Hosting {} · {game}", f.place),
        Kind::Hosting => format!("Hosting a world · {game}"),
    }
}

/// Fill the drawer from the current state. UI thread.
fn apply(ui: &AppWindow) {
    let g = ui.global::<Friends>();
    let (state, prefs, msg, filter, heads, me) = with(|s| (s.state.clone(), s.prefs.clone(), s.msg.clone(), s.filter.to_lowercase(), s.heads.clone(), s.me.clone()));
    g.set_status(prefs.status as i32);
    g.set_share(prefs.share);
    g.set_notify(prefs.notify);
    g.set_open_requests(prefs.open);
    g.set_msg(msg.into());
    let Some(st) = state else { return };
    g.set_loaded(true);
    let head = |u: &str| heads.get(u).filter(|h| h.rgba.len() == 32 * 32 * 4)
        .map(|h| Image::from_rgba8(SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(&h.rgba, 32, 32)));
    let row = |u: &str, section: i32| {
        let img = head(u);
        FriendRow { uuid: u.into(), name: name(u).into(), has_head: img.is_some(), head: img.unwrap_or_default(), section, ..Default::default() }
    };
    let mine = !me.address.is_empty() || me.kind == Kind::Hosting;
    let mut fr: Vec<&Friend> = st.friends.iter().collect();
    let star = |f: &Friend| prefs.stars.contains(&f.uuid);
    fr.sort_by(|a, b| star(b).cmp(&star(a)).then(b.online().cmp(&a.online()))
        .then((b.kind as u8).cmp(&(a.kind as u8))).then(name(&a.uuid).to_lowercase().cmp(&name(&b.uuid).to_lowercase())));
    let friends: Vec<FriendRow> = fr.iter().filter(|f| filter.is_empty() || name(&f.uuid).to_lowercase().contains(&filter)).map(|f| {
        let on = f.online();
        let detail = match (on, f.status) {
            (true, Status::Away) => "Away".to_string(),
            (true, Status::Busy) => "Do not disturb".to_string(),
            (true, _) => String::new(),
            (false, _) => if f.seen > 0 { format!("seen {}", ago(f.seen)) } else { String::new() },
        };
        FriendRow {
            status: if on { u8::from(f.status) as i32 } else { 0 },
            activity: if on { game_line(f) } else { "Offline".into() }.into(),
            detail: detail.into(),
            can_join: on && !f.address.is_empty() && f.address != me.address,
            can_invite: on && mine && f.address != me.address,
            can_ask: on && f.kind != Kind::Launcher && f.address.is_empty(),
            star: star(f),
            ..row(&f.uuid, 0)
        }
    }).collect();
    let header = |t: &str| FriendRow { name: t.into(), section: -1, ..Default::default() };
    let mut req = vec![];
    if !st.asks.is_empty() { req.push(header("WANT TO JOIN YOU")); req.extend(st.asks.iter().map(|p| FriendRow { activity: format!("Asked {}", ago(p.at)).into(), ..row(&p.uuid, 3) })); }
    if !st.incoming.is_empty() { req.push(header("FRIEND REQUESTS")); req.extend(st.incoming.iter().map(|p| FriendRow { activity: format!("Sent {}", ago(p.at)).into(), ..row(&p.uuid, 1) })); }
    if !st.outgoing.is_empty() { req.push(header("SENT")); req.extend(st.outgoing.iter().map(|p| FriendRow { activity: format!("Waiting · sent {}", ago(p.at)).into(), ..row(&p.uuid, 2) })); }
    let blocked: Vec<FriendRow> = st.blocked.iter().map(|u| FriendRow { activity: "Blocked".into(), ..row(u, 4) }).collect();
    g.set_total(st.friends.len() as i32);
    g.set_online(st.friends.iter().filter(|f| f.online()).count() as i32);
    g.set_everyone(if st.online > 0 { group(st.online).into() } else { "".into() });
    g.set_pending((st.incoming.len() + st.asks.len()) as i32);
    g.set_badge((st.incoming.len() + st.asks.len()) as i32);
    g.set_friends(ModelRc::new(VecModel::from(friends)));
    g.set_requests(ModelRc::new(VecModel::from(req)));
    g.set_blocked(ModelRc::new(VecModel::from(blocked)));
}

// ---------------- actions ----------------

/// Run a friends action on a worker thread; errors land in the drawer (and the game's answer). `seq` = game request.
fn run_act(weak: &slint::Weak<AppWindow>, sh: &Arc<Shared>, op: String, uuid: String, seq: Option<f64>) {
    let Some(s) = session(sh) else {
        with(|st| { st.msg = "Sign in with Microsoft to use Friends.".into(); if let Some(q) = seq { st.answer = Some((q, false, st.msg.clone())); } });
        ui_do(weak, apply);
        return;
    };
    let (weak, sh) = (weak.clone(), sh.clone());
    std::thread::spawn(move || {
        let r = friends::act(&client(), &s, &op, &uuid);
        // their launcher shows it at once (we're friends now / we asked to join)
        if matches!((op.as_str(), &r), ("accept" | "ask", Ok(_)) | ("request", Ok(true))) { NUDGE.lock().unwrap_or_else(|e| e.into_inner()).push(uuid.clone()); }
        // a removed friend's key is still listed until our next sync: tell them now
        if op == "remove" && r.is_ok() { crate::party::nudge_friend(&uuid); }
        let n = name(&uuid);
        let (ok, msg) = match &r {
            Ok(now) => (true, match op.as_str() {
                "request" if *now => format!("You and {n} are friends now"),
                "request" => format!("Friend request sent to {n}"),
                "accept" => format!("You and {n} are friends now"),
                "ask" => format!("Asked {n} to invite you"),
                "block" => format!("Blocked {n}"),
                "remove" => format!("Removed {n}"),
                _ => String::new(),
            }),
            Err(e) => (false, cap(&format!("{e:#}"))),
        };
        with(|st| { st.msg = if ok { String::new() } else { msg.clone() }; if let Some(q) = seq { st.answer = Some((q, ok, msg.clone())); } });
        wake();
        ui_do(&weak, move |ui| {
            apply(ui);
            if ok && !msg.is_empty() && seq.is_none() { show_toast(ui, &sh, &msg); }
        });
    });
}

/// Add by name (Mojang lookup, then a request).
fn add(weak: &slint::Weak<AppWindow>, sh: &Arc<Shared>, who: String, seq: Option<f64>) {
    let who = who.trim().to_string();
    if who.is_empty() { return; }
    let (weak, sh) = (weak.clone(), sh.clone());
    std::thread::spawn(move || match share::lookup(&client(), &who) {
        Ok((uuid, real)) => {
            with(|st| { st.prefs.names.insert(uuid.clone(), real); });
            if session(&sh).is_some_and(|s| s.uuid.replace('-', "").eq_ignore_ascii_case(&uuid)) {
                let m = "That's you".to_string();
                with(|st| { st.msg = m.clone(); if let Some(q) = seq { st.answer = Some((q, false, m)); } });
                ui_do(&weak, apply);
                return;
            }
            ui_do(&weak, |ui| ui.global::<Friends>().set_add_text("".into()));
            run_act(&weak, &sh, "request".into(), uuid, seq);
        }
        Err(e) => {
            let m = cap(&format!("{e:#}"));
            with(|st| { st.msg = m.clone(); if let Some(q) = seq { st.answer = Some((q, false, m)); } });
            ui_do(&weak, apply);
        }
    });
}

/// Invite a friend to our world (Invite to world, peer-to-peer) or to the server our game is on (world invite with the
/// server address).
fn invite(weak: &slint::Weak<AppWindow>, sh: &Arc<Shared>, uuid: String, seq: Option<f64>) {
    let me = with(|s| s.me.clone());
    let hosting = me.kind == Kind::Hosting;
    let server = (me.kind == Kind::Server && !me.address.is_empty()).then(|| me.address.clone()).or_else(|| {
        let act: Activity = nether_core::store::peek_json(&dir(sh).join("activity.json")).unwrap_or_default();
        (act.kind == 3 && share::valid_address(&act.address)).then_some(act.address)
    });
    let (true, Some(s)) = (hosting || server.is_some(), session(sh)) else {
        let m = "Open your world to friends (pause menu > INVITE) or join a server first".to_string();
        with(|st| if let Some(q) = seq { st.answer = Some((q, false, m.clone())); });
        let sh = sh.clone();
        ui_do(weak, move |ui| if seq.is_none() { show_toast(ui, &sh, &m) });
        return;
    };
    let (weak, sh) = (weak.clone(), sh.clone());
    std::thread::spawn(move || {
        let c = client();
        let r = if hosting { crate::worldshare::invite_uuid(&sh, &uuid) } else {
            let addr = server.unwrap_or_default();
            let (ver, loader) = running(&sh).unwrap_or_default();
            let place = if me.place.is_empty() { addr.rsplit_once(':').map(|(h, _)| h.to_string()).unwrap_or_else(|| addr.clone()) } else { me.place.clone() };
            share::send(&c, &s, &uuid, &addr, &place, &ver, if loader.is_empty() { "Vanilla" } else { &loader })
        };
        let _ = friends::act(&c, &s, "dismiss", &uuid); // answers their ask, if any
        let n = name(&uuid);
        let (ok, m) = match r { Ok(()) => (true, format!("Invited {n}")), Err(e) => (false, cap(&format!("Couldn't invite {n}: {e:#}"))) };
        with(|st| if let Some(q) = seq { st.answer = Some((q, ok, m.clone())); });
        wake();
        ui_do(&weak, move |ui| { apply(ui); if seq.is_none() { show_toast(ui, &sh, &m); } });
    });
}

fn join(ui: &AppWindow, sh: &Arc<Shared>, uuid: &str) {
    let f = with(|s| s.state.as_ref().and_then(|st| st.friends.iter().find(|f| f.uuid == uuid).cloned()));
    let Some(f) = f.filter(|f| !f.address.is_empty()) else { return show_toast(ui, sh, "They aren't sharing a server right now") };
    ui.global::<Friends>().set_open(false);
    let inv = share::Invite { id: 0, from: f.uuid.clone(), address: f.address.clone(), world: if f.place.is_empty() { f.address.clone() } else { f.place.clone() },
        ver: f.ver.clone(), loader: if f.loader.is_empty() { "Vanilla".into() } else { f.loader.clone() }, at: 0 };
    crate::worldshare::join_friend(ui, sh, &name(uuid), inv);
}

fn set_pref(sh: &Arc<Shared>, f: impl FnOnce(&mut Prefs)) {
    with(|s| f(&mut s.prefs));
    save_prefs(sh);
    wake();
}

fn act(ui: &AppWindow, sh: &Arc<Shared>, op: &str, uuid: &str) {
    let weak = ui.as_weak();
    let n = name(uuid);
    match op {
        "join" => join(ui, sh, uuid),
        "invite" => invite(&weak, sh, uuid.to_string(), None),
        "star" => { set_pref(sh, |p| if let Some(i) = p.stars.iter().position(|s| s == uuid) { p.stars.remove(i); } else { p.stars.push(uuid.to_string()); }); apply(ui); }
        "copy" => { if let Ok(mut c) = arboard::Clipboard::new() { let _ = c.set_text(n.clone()); } show_toast(ui, sh, "Name copied"); }
        "remove" | "block" => {
            let (title, text, yes) = if op == "remove" {
                (format!("Remove {n}?"), format!("{n} is removed from your friends. You can add them again later."), "REMOVE")
            } else {
                (format!("Block {n}?"), format!("{n} is removed from your friends and can't send you requests or invites. They aren't told."), "BLOCK")
            };
            let (sh2, op, uuid) = (sh.clone(), op.to_string(), uuid.to_string());
            crate::instpage::ask(ui, &title, &text, yes, true, move |ui| run_act(&ui.as_weak(), &sh2, op, uuid, None));
        }
        _ => run_act(&weak, sh, op.to_string(), uuid.to_string(), None),
    }
}

fn wire(ui: &AppWindow, sh: &Arc<Shared>) {
    let g = ui.global::<Friends>();
    let (w, s) = (ui.as_weak(), sh.clone());
    g.on_add(move |n| add(&w, &s, n.to_string(), None));
    let (w, s) = (ui.as_weak(), sh.clone());
    g.on_act(move |op, u| if let Some(ui) = w.upgrade() { act(&ui, &s, op.as_str(), u.as_str()) });
    let (w, s) = (ui.as_weak(), sh.clone());
    g.on_set_status(move |v| { set_pref(&s, |p| p.status = v.clamp(0, 3) as u8); if let Some(ui) = w.upgrade() { apply(&ui); } });
    let s = sh.clone();
    g.on_set_share(move |v| set_pref(&s, |p| p.share = v));
    let s = sh.clone();
    g.on_set_open(move |v| set_pref(&s, |p| p.open = v));
    let s = sh.clone();
    g.on_set_notify(move |v| set_pref(&s, |p| p.notify = v));
    let w = ui.as_weak();
    g.on_search(move |t| { with(|s| s.filter = t.to_string()); if let Some(ui) = w.upgrade() { apply(&ui); } });
    g.on_refresh(wake);
}

// ---------------- game bridge ----------------

fn game_request(weak: &slint::Weak<AppWindow>, sh: &Arc<Shared>, r: &Value) {
    let seq = r["seq"].as_f64().unwrap_or(0.0);
    let op = r["op"].as_str().unwrap_or_default().to_string();
    let uuid = r["uuid"].as_str().unwrap_or_default().to_lowercase();
    match op.as_str() {
        "add" => add(weak, sh, r["name"].as_str().unwrap_or_default().to_string(), Some(seq)),
        "invite" => invite(weak, sh, uuid, Some(seq)),
        "status" => { set_pref(sh, |p| p.status = r["value"].as_u64().unwrap_or(1).min(3) as u8); ui_do(weak, apply); }
        "share" => { set_pref(sh, |p| p.share = r["value"].as_bool().unwrap_or(true)); ui_do(weak, apply); }
        "open" => { set_pref(sh, |p| p.open = r["value"].as_bool().unwrap_or(true)); ui_do(weak, apply); }
        "notify" => { set_pref(sh, |p| p.notify = r["value"].as_bool().unwrap_or(true)); ui_do(weak, apply); }
        "star" => { set_pref(sh, |p| if let Some(i) = p.stars.iter().position(|s| *s == uuid) { p.stars.remove(i); } else { p.stars.push(uuid.clone()); }); ui_do(weak, apply); }
        "refresh" => wake(),
        "join" => { let s2 = sh.clone(); ui_do(weak, move |ui| join(ui, &s2, &uuid)); }
        "accept" | "deny" | "cancel" | "remove" | "block" | "unblock" | "ask" | "dismiss" => run_act(weak, sh, op, uuid, Some(seq)),
        _ => {}
    }
}

/// `state.json` for the game: names, status lines, what can be joined, pending requests, pop-up events.
fn game_state() -> Value {
    with(|s| {
        let nm = |u: &str| s.prefs.names.get(u).cloned().unwrap_or_default();
        let mine = !s.me.address.is_empty() || s.me.kind == Kind::Hosting;
        let friends: Vec<Value> = s.state.as_ref().map(|st| st.friends.iter().map(|f| {
            let on = f.online();
            json!({
                "uuid": f.uuid, "name": nm(&f.uuid), "status": if on { u8::from(f.status) } else { 0 },
                "act": if on { game_line(f) } else { offline_line(f) },
                "join": if on { f.address.as_str() } else { "" }, "ver": f.ver, "loader": f.loader,
                "here": s.game.as_ref().is_some_and(|(v, l)| *v == f.ver && l.eq_ignore_ascii_case(if f.loader.is_empty() { "Vanilla" } else { &f.loader })),
                "invite": on && mine && f.address != s.me.address, "ask": on && f.kind != Kind::Launcher && f.address.is_empty(),
                "star": s.prefs.stars.contains(&f.uuid),
            })
        }).collect()).unwrap_or_default();
        let list = |v: Option<&Vec<friends::Pending>>| v.map(|v| v.iter().map(|p| json!({ "uuid": p.uuid, "name": nm(&p.uuid), "at": p.at })).collect::<Vec<_>>()).unwrap_or_default();
        let st = s.state.as_ref();
        json!({
            "loaded": st.is_some(), "msg": s.msg, "everyone": st.map_or(0, |st| st.online),
            "me": { "status": s.prefs.status, "share": s.prefs.share, "open": s.prefs.open, "notify": s.prefs.notify, "joinable": mine },
            "friends": friends,
            "incoming": list(st.map(|s| &s.incoming)), "outgoing": list(st.map(|s| &s.outgoing)), "asks": list(st.map(|s| &s.asks)),
            "blocked": st.map(|st| st.blocked.iter().map(|u| json!({ "uuid": u, "name": nm(u) })).collect::<Vec<_>>()).unwrap_or_default(),
            "events": s.events.iter().map(|(id, k, u, n)| json!({ "id": id, "kind": k, "uuid": u, "name": n })).collect::<Vec<_>>(),
            "answer": s.answer.as_ref().map(|(q, ok, m)| json!({ "seq": q, "ok": ok, "msg": m })),
        })
    })
}

/// Dev captures (`NC_SNAP_STATE=friends|friends-req|friends-set`): the drawer open with sample people.
pub fn snap(ui: &AppWindow, state: &str) {
    if !state.starts_with("friends") { return; }
    let id = |c: char| c.to_string().repeat(32);
    let f = |c: char, status: Status, kind: Kind, place: &str, address: &str, seen: i64| Friend {
        uuid: id(c), status, kind, ver: "1.21.11".into(), loader: "Fabric".into(), place: place.into(), address: address.into(), since: 1, seen, since_friends: 1, peer: String::new(), party: 0,
    };
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
    let st = State {
        friends: vec![
            f('a', Status::Online, Kind::Server, "mc.hypixel.net", "mc.hypixel.net:25565", now),
            f('b', Status::Away, Kind::Hosting, "Survival 2", "", now),
            f('c', Status::Busy, Kind::Singleplayer, "", "", now),
            f('d', Status::Online, Kind::Launcher, "", "", now),
            f('e', Status::Invisible, Kind::Launcher, "", "", now - 3 * 3600),
            f('f', Status::Invisible, Kind::Launcher, "", "", now - 4 * 86_400),
        ],
        incoming: vec![friends::Pending { uuid: id('1'), at: now - 600 }],
        outgoing: vec![friends::Pending { uuid: id('2'), at: now - 7200 }],
        asks: vec![friends::Pending { uuid: id('3'), at: now - 60 }],
        blocked: vec![],
        open: true,
        online: 1284,
    };
    with(|s| {
        for (c, n) in [('a', "Dream_Tide"), ('b', "Notch"), ('c', "Kyra_44"), ('d', "ember"), ('e', "Pixelwolf"), ('f', "slowpoke_09"), ('1', "Grian"), ('2', "TangoTek"), ('3', "Kyra_44")] {
            s.prefs.names.insert(id(c), n.into());
        }
        s.prefs.stars = vec![id('c')];
        s.me = Presence { kind: Kind::Server, address: "play.example.net:25565".into(), ..Default::default() };
        s.state = Some(st);
    });
    apply(ui);
    let g = ui.global::<Friends>();
    g.set_ready(true);
    g.set_open(true);
    if state == "friends-req" { g.set_tab(1); }
    if state == "friends-set" { g.set_settings(true); }
}
