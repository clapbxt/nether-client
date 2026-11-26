//! Parties, launcher side. The game draws the lobby (`ov/Party.java`); this module owns everything else: the party
//! endpoint (`core::party_net`), the leader's authority (`core::party::Party`), invites / join requests between
//! friends, chat routing, voice (`voice.rs`) and the game bridge.
//!
//! - Discovery: our endpoint id + party size ride on the friends presence lease (`friends::set_party`). Only online
//!   friends see it; only friends and the current party roster may connect (`auth`).
//! - Identity: a connection's key is checked against the friend list (the friends service vouches for which key belongs
//!   to which account) or the roster the leader built; frames from any other key are dropped. Account tokens never
//!   leave the launcher.
//! - Parties live in the game only (2026-11-21): with no game linked for a moment the party is left and friends see
//!   no party (size 0 in presence); invites only reach friends in game on the same Minecraft version.
//! - Ready gate: the leader picks a server or one of their worlds; when everyone in the lobby is ready a 5 s countdown
//!   runs (`Party::gate`), then the leader's game connects / opens and hosts the world and ready members follow.
//! - Bridge to the game: a loopback TCP socket (port + per-run token in `client/party/bridge.json`) carrying JSON
//!   lines: the game sends actions, we send the lobby state, live chat lines and who is talking. Chat exists only in
//!   memory (here and in the game) and is gone when the party ends; nothing is logged.
use std::collections::{HashMap, HashSet, VecDeque};
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use nether_core::party::{self, mic, Dest, Gate, Look, Member, Msg, Party, Snapshot};
use nether_core::party_net::{Event, Net};
use nether_core::Session;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use slint::ComponentHandle;

use crate::voice::{self, Voice};
use crate::{friends, AppWindow, Shared};

/// Members' voices the leader forwards at the same time (its own comes on top).
const FLOOR: usize = 4;

fn now_ms() -> u64 { std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0) }

/// Saved choices: `<root>/party.json`.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
struct Prefs {
    voice: voice::Cfg,
    /// relay privacy mode: peers never see our IP (the relay does)
    relay: bool,
    /// follow the leader's server / world when ready
    follow: bool,
    /// per-person volume and local mutes (uuid)
    vol: HashMap<String, f32>,
    muted: Vec<String>,
    /// asked once: what voice is before the mic is first turned on
    voice_intro: bool,
}

enum Cmd { Net(Event), Game(Value), Hello(u64, TcpStream), Poke(String, bool), ToGame(String, Value) }

#[derive(Clone)]
struct InInvite { id: u64, from: String, from_name: String, party: String, leader: String, leader_name: String, leader_peer: String, size: u32, at: Instant }

enum Role {
    Leader(Party),
    /// following a leader; `lost` = the leader's link dropped at
    Member { snap: Snapshot, leader_peer: String, lost: Option<Instant>, heard: Instant, joined: bool, since: Instant },
}

#[derive(Clone, Default, PartialEq)]
struct Mine { ready: bool, at: String, game: String, look: Look, server: String, ver: String, loader: String }

#[derive(Clone)]
struct Line { id: u64, from: String, name: String, text: String }

struct Actor {
    sh: Arc<Shared>,
    weak: slint::Weak<AppWindow>,
    tx: Sender<Cmd>,
    net: Option<Net>,
    who: Option<(String, String)>,
    role: Role,
    mine: Mine,
    prefs: Prefs,
    voice: Voice,
    invites: Vec<InInvite>,
    next_id: u64,
    /// friends we asked to join (uuid, when)
    asked: Vec<(String, Instant)>,
    /// frames from friends whose `Ask` we passed on: (friend uuid, their peer)
    relayed: Vec<(String, String)>,
    hello: HashSet<String>,
    names: HashMap<String, String>,
    chat: VecDeque<Line>,
    clients: Vec<(u64, TcpStream)>,
    last_state: String,
    last_talk: String,
    last_blocks: Vec<String>,
    vetoed: HashSet<String>,
    last_go: u64,
    last_ping: Instant,
    note: String,
    voice_route: Arc<Mutex<VoiceRoute>>,
    /// leader: members whose voice it forwards right now (sid -> last frame), at most FLOOR at once
    floor: HashMap<u16, Instant>,
    auth: Arc<Mutex<HashSet<String>>>,
    failed_net: Option<Instant>,
    /// the last game unlinked at (parties exist only while a game is linked)
    game_gone: Option<Instant>,
    /// leader, party started: when, and whether our game got there yet
    live_at: Option<Instant>,
    arrived: bool,
}

#[derive(Default, Clone)]
struct VoiceRoute { peers: Vec<String>, sid: u16, on: bool }

static TX: Mutex<Option<Sender<Cmd>>> = Mutex::new(None);
static NET: Mutex<Option<Net>> = Mutex::new(None);
static LEAVE: Mutex<Option<Box<dyn FnOnce() + Send>>> = Mutex::new(None);
/// Linked games (bridge client id -> the instance it runs, from its hello's "inst").
static GAMES: Mutex<Vec<(u64, String)>> = Mutex::new(Vec::new());

/// The running game of this instance is linked (its agent can be told to join a server).
pub fn game_linked(inst: &str) -> bool { GAMES.lock().unwrap_or_else(|e| e.into_inner()).iter().any(|(_, n)| n == inst) }

/// Tell the running game of `inst` to join a server: it saves and leaves its world (or disconnects) the way the pause
/// screen's button does, then connects. false = that game isn't linked (agent off or not started yet).
pub fn game_join(inst: &str, address: &str, name: &str) -> bool {
    if !game_linked(inst) { return false; }
    let Some(tx) = TX.lock().unwrap_or_else(|e| e.into_inner()).clone() else { return false };
    tx.send(Cmd::ToGame(inst.to_string(), json!({ "t": "go", "address": address, "name": name, "why": "join" }))).is_ok()
}

fn dir(sh: &Shared) -> PathBuf { sh.paths.root.join("client").join("party") }
fn prefs_file(sh: &Shared) -> PathBuf { sh.paths.root.join("party.json") }

fn session(sh: &Shared) -> Option<Session> { sh.session.lock().unwrap_or_else(|e| e.into_inner()).clone().filter(|s| s.user_type != "legacy") }

pub fn init(ui: &AppWindow, sh: Arc<Shared>) {
    let prefs: Prefs = nether_core::store::peek_json(&prefs_file(&sh)).unwrap_or(Prefs { follow: true, ..Default::default() });
    watch_devices();
    let (tx, rx) = channel();
    *TX.lock().unwrap_or_else(|e| e.into_inner()) = Some(tx.clone());
    bridge(&sh, tx.clone());
    let weak = ui.as_weak();
    std::thread::Builder::new().name("nether-party".into()).spawn(move || {
        let mut a = Actor::new(sh, weak, tx, prefs);
        let route = a.voice_route.clone();
        let net_slot = &NET;
        voice::SINK.set(Some(Box::new(move |seq, pkt| {
            let r = route.lock().unwrap_or_else(|e| e.into_inner()).clone();
            if !r.on { return; }
            let Some(net) = net_slot.lock().unwrap_or_else(|e| e.into_inner()).clone() else { return };
            let f = party::voice_frame(r.sid, seq, pkt);
            for p in &r.peers { net.voice(p, &f); }
        })));
        a.run(rx);
    }).ok();
}

/// Launcher closing: leave the party properly (the next member leads), close links. Short, best effort.
pub fn shutdown(sh: &Shared) {
    if let Some(f) = LEAVE.lock().unwrap_or_else(|e| e.into_inner()).take() { f(); }
    std::thread::sleep(Duration::from_millis(300)); // let the Leave frame go out
    if let Some(n) = NET.lock().unwrap_or_else(|e| e.into_inner()).take() { n.close(); }
    let _ = std::fs::remove_file(dir(sh).join("bridge.json"));
}

/// Others are in our party (the launcher asks before quitting).
/// A world invite was just sent to this friend: tell their launcher directly so it shows at once.
pub fn poke_world(uuid: &str) { poke(uuid, true); }

/// Our friends state changed in a way this friend should see: their launcher syncs at once.
pub fn nudge_friend(uuid: &str) { poke(uuid, false); }

fn poke(uuid: &str, world: bool) {
    if let Some(tx) = TX.lock().unwrap_or_else(|e| e.into_inner()).as_ref() { let _ = tx.send(Cmd::Poke(uuid.to_string(), world)); }
}

/// A friend's "check now": sync the friends state, at most every 3 s whoever asks.
fn synced_on_request() {
    static LAST: Mutex<Option<Instant>> = Mutex::new(None);
    let mut l = LAST.lock().unwrap_or_else(|e| e.into_inner());
    if l.is_some_and(|t| t.elapsed() < Duration::from_secs(3)) { return; }
    *l = Some(Instant::now());
    friends::poke();
}

pub fn busy() -> bool { PARTY_SIZE.load(std::sync::atomic::Ordering::SeqCst) > 1 }
static PARTY_SIZE: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

// ---------------------------------------------------------------- game bridge

fn bridge(sh: &Arc<Shared>, tx: Sender<Cmd>) {
    let d = dir(sh);
    let _ = std::fs::create_dir_all(&d);
    let Ok(listener) = TcpListener::bind(("127.0.0.1", 0)) else { return };
    let Ok(port) = listener.local_addr().map(|a| a.port()) else { return };
    let token = party::new_id();
    let _ = nether_core::store::write_atomic(&d.join("bridge.json"), json!({ "port": port, "token": token, "v": party::PROTO }).to_string().as_bytes());
    std::thread::Builder::new().name("nether-party-bridge".into()).spawn(move || {
        let mut next = 1u64;
        for s in listener.incoming().flatten() {
            let (tx, token) = (tx.clone(), token.clone());
            let id = next;
            next += 1;
            std::thread::spawn(move || {
                let _ = s.set_nodelay(true);
                let Ok(w) = s.try_clone() else { return };
                let _ = w.set_write_timeout(Some(Duration::from_millis(300)));
                let mut r = BufReader::new(s);
                let mut line = String::new();
                // first line: {"t":"hello","token":...}
                let _ = r.get_ref().set_read_timeout(Some(Duration::from_secs(5)));
                if r.read_line(&mut line).is_err() { return; }
                let ok = serde_json::from_str::<Value>(&line).ok().is_some_and(|v| v["t"] == "hello" && v["token"] == token.as_str());
                if !ok { return; }
                let inst = serde_json::from_str::<Value>(&line).ok().and_then(|v| v["inst"].as_str().map(str::to_string)).unwrap_or_default();
                let _ = r.get_ref().set_read_timeout(None);
                if tx.send(Cmd::Hello(id, w)).is_err() { return; }
                GAMES.lock().unwrap_or_else(|e| e.into_inner()).push((id, inst));
                loop {
                    line.clear();
                    match r.read_line(&mut line) {
                        Ok(0) | Err(_) => break,
                        Ok(n) if n > 8192 => break,
                        Ok(_) => if let Ok(v) = serde_json::from_str::<Value>(&line) { if tx.send(Cmd::Game(v)).is_err() { break; } },
                    }
                }
                GAMES.lock().unwrap_or_else(|e| e.into_inner()).retain(|(c, _)| *c != id);
                let _ = tx.send(Cmd::Game(json!({ "t": "bye", "client": id })));
            });
        }
    }).ok();
}

// ---------------------------------------------------------------- the actor

impl Actor {
    fn new(sh: Arc<Shared>, weak: slint::Weak<AppWindow>, tx: Sender<Cmd>, prefs: Prefs) -> Actor {
        let voice = Voice::start(prefs.voice.clone());
        Actor {
            sh, weak, tx, net: None, who: None, role: Role::Leader(Party::solo(party::new_id(), Member::default())), mine: Mine { at: "launcher".into(), ..Default::default() },
            prefs, voice, invites: vec![], next_id: 1, asked: vec![], relayed: vec![], hello: HashSet::new(), names: HashMap::new(), chat: VecDeque::new(), clients: vec![],
            last_state: String::new(), last_talk: String::new(), last_blocks: vec![], vetoed: HashSet::new(), last_go: 0, last_ping: Instant::now(),  note: String::new(), voice_route: Arc::new(Mutex::new(VoiceRoute::default())), floor: HashMap::new(), auth: Arc::new(Mutex::new(HashSet::new())), failed_net: None,
            game_gone: Some(Instant::now()), live_at: None, arrived: false,
        }
    }

    fn me(&self) -> String { self.who.as_ref().map(|w| w.0.clone()).unwrap_or_default() }
    fn my_name(&self) -> String { self.who.as_ref().map(|w| w.1.clone()).unwrap_or_default() }
    fn peer(&self) -> String { self.net.as_ref().map(|n| n.id()).unwrap_or_default() }

    fn snap(&self) -> &Snapshot { match &self.role { Role::Leader(p) => &p.snap, Role::Member { snap, .. } => snap } }
    fn leader(&self) -> bool { matches!(self.role, Role::Leader(_)) }
    fn size(&self) -> usize { self.snap().size().max(1) }
    /// A game is linked: only then do we have a party others can see or join.
    fn in_game(&self) -> bool { !self.clients.is_empty() }
    /// Our party size for friends' presence (0 = not in game: no party to invite to or join).
    fn publish(&self) {
        PARTY_SIZE.store(if self.in_game() { self.size() } else { 1 }, std::sync::atomic::Ordering::SeqCst);
        if let Some(n) = &self.net { friends::set_party(n.id(), if self.in_game() { self.size() as u32 } else { 0 }); }
    }
    /// Another Minecraft version than ours ("" on either side = unknown, let it through).
    fn other_ver(&self, v: &str) -> bool { !v.is_empty() && !self.mine.ver.is_empty() && v != self.mine.ver }

    fn run(&mut self, rx: Receiver<Cmd>) {
        let mut last_tick = Instant::now();
        while self.step(&rx, &mut last_tick, Duration::from_millis(100)) {}
    }

    /// One event (or a timeout), then the 250 ms upkeep when due. false = the channel closed.
    fn step(&mut self, rx: &Receiver<Cmd>, last_tick: &mut Instant, wait: Duration) -> bool {
        match rx.recv_timeout(wait) {
            Ok(Cmd::Net(e)) => self.on_net(e),
            Ok(Cmd::Game(v)) => self.on_game(&v),
            Ok(Cmd::Hello(id, s)) => { self.clients.push((id, s)); self.last_state.clear(); self.push_chat_history(id); }
            Ok(Cmd::ToGame(inst, v)) => {
                let ids: Vec<u64> = GAMES.lock().unwrap_or_else(|e| e.into_inner()).iter().filter(|(_, n)| *n == inst).map(|(c, _)| *c).collect();
                let line = format!("{v}
");
                self.clients.retain_mut(|(c, s)| !ids.contains(c) || s.write_all(line.as_bytes()).is_ok());
            }
            Ok(Cmd::Poke(uuid, world)) => { if let Some(fp) = self.friend_peer(&uuid) { self.tell(&fp, if world { Msg::WorldInvite } else { Msg::Sync }); } }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => return false,
        }
        if last_tick.elapsed() >= Duration::from_millis(250) { *last_tick = Instant::now(); self.tick(); }
        true
    }

    // ---------------- lifecycle

    /// Start / restart the endpoint for the signed-in account.
    fn ensure_net(&mut self) {
        let s = session(&self.sh);
        let who = s.as_ref().map(|s| (s.uuid.replace('-', "").to_lowercase(), s.name.clone()));
        if who != self.who {
            // signed out / another account: end everything
            self.end_party(true);
            if let Some(n) = self.net.take() { n.close(); }
            *NET.lock().unwrap_or_else(|e| e.into_inner()) = None;
            self.who = who;
            self.failed_net = None;
        }
        if self.who.is_none() || self.net.is_some() { return; }
        if self.failed_net.is_some_and(|t| t.elapsed() < Duration::from_secs(30)) { return; }
        let auth = self.auth.clone();
        // while we wait for an answer to a friend request, the new friend's key isn't listed yet: let it in to say "check"
        match Net::start(self.prefs.relay, move |peer| auth.lock().unwrap_or_else(|e| e.into_inner()).contains(peer) || friends::waiting()) {
            Ok((net, events)) => {
                let tx = self.tx.clone();
                std::thread::Builder::new().name("nether-party-net".into()).spawn(move || { for e in events { if tx.send(Cmd::Net(e)).is_err() { break; } } }).ok();
                *NET.lock().unwrap_or_else(|e| e.into_inner()) = Some(net.clone());
                self.net = Some(net);
                self.solo();
            }
            Err(e) => { self.failed_net = Some(Instant::now()); self.note = format!("{e:#}"); }
        }
    }

    fn me_member(&self) -> Member {
        Member { uuid: self.me(), name: self.my_name(), peer: self.peer(), ready: self.mine.ready, mic: self.prefs.voice.mode, deaf: self.prefs.voice.deaf,
            game: self.mine.game.clone(), at: self.mine.at.clone(), look: self.mine.look.clone(), ..Default::default() }
    }

    /// A fresh party of one.
    fn solo(&mut self) {
        self.role = Role::Leader(Party::solo(party::new_id(), self.me_member()));
        self.chat.clear();
        self.voice.reset();
        self.last_go = 0;
        self.push_chat_reset();
    }

    /// Leave the current party (others keep it). `quiet` = no toast.
    fn end_party(&mut self, quiet: bool) {
        let me = self.me();
        match std::mem::replace(&mut self.role, Role::Leader(Party::solo(String::new(), Member::default()))) {
            Role::Leader(mut p) => {
                let others: Vec<Member> = p.snap.members.iter().filter(|m| m.uuid != me).cloned().collect();
                if !others.is_empty() {
                    p.remove(&me);
                    let snap = p.snap.clone();
                    for m in &others { self.tell(&m.peer, Msg::State { snap: snap.clone() }); }
                }
            }
            Role::Member { leader_peer, .. } => self.tell(&leader_peer, Msg::Leave),
        }
        if !quiet { self.toast("You left the party"); }
        if self.net.is_some() { self.solo(); }
    }

    // ---------------- sending

    fn tell(&mut self, peer: &str, msg: Msg) {
        let Some(net) = self.net.clone() else { return };
        if peer.is_empty() || peer == net.id() { return; }
        if !self.hello.contains(peer) {
            self.hello.insert(peer.to_string());
            net.send(peer, &Msg::Hello { v: party::PROTO, uuid: self.me(), name: self.my_name() });
        }
        net.send(peer, &msg);
    }

    /// Leader: send the snapshot to every member.
    fn broadcast(&mut self) {
        let me = self.me();
        let (snap, peers) = match &self.role {
            Role::Leader(p) => (p.snap.clone(), p.snap.members.iter().filter(|m| m.uuid != me && !m.away).map(|m| m.peer.clone()).collect::<Vec<_>>()),
            _ => return,
        };
        for peer in peers { self.tell(&peer, Msg::State { snap: snap.clone() }); }
    }

    fn to_leader(&mut self, msg: Msg) {
        let me = self.me();
        let mut later: Option<Msg> = None;
        match &mut self.role {
            Role::Leader(_) => later = Some(msg),
            Role::Member { leader_peer, .. } => { let p = leader_peer.clone(); self.tell(&p, msg); }
        }
        if let Some(m) = later { self.as_leader(&me, &self.peer(), m); }
    }

    // ---------------- identities

    /// Who owns this key: an online friend (vouched by the friends service), a party member, or an invited joiner.
    fn ident(&self, peer: &str) -> Option<String> {
        if let Some(m) = self.snap().members.iter().find(|m| m.peer == peer) { return Some(m.uuid.clone()); }
        if let Role::Leader(p) = &self.role { if let Some(u) = p.allowed_peer(peer, now_ms()) { return Some(u.to_string()); } }
        if let Role::Member { leader_peer, snap, .. } = &self.role { if leader_peer == peer { return Some(snap.leader.clone()); } }
        if let Some(i) = self.invites.iter().find(|i| i.leader_peer == peer) { return Some(i.leader.clone()); }
        let blocked = friends::blocked();
        friends::list().unwrap_or_default().into_iter().find(|f| f.peer == peer && f.online() && !blocked.contains(&f.uuid)).map(|f| f.uuid)
    }

    fn friend_peer(&self, uuid: &str) -> Option<String> {
        friends::list().unwrap_or_default().into_iter().find(|f| f.uuid == uuid && f.online() && !f.peer.is_empty()).map(|f| f.peer)
    }
    fn is_friend(&self, uuid: &str) -> bool { friends::list().unwrap_or_default().iter().any(|f| f.uuid == uuid) }

    fn name_of(&self, uuid: &str) -> String {
        self.snap().member(uuid).map(|m| m.name.clone()).filter(|n| !n.is_empty())
            .or_else(|| self.names.get(uuid).cloned()).unwrap_or_else(|| friends::name(uuid))
    }

    fn refresh_auth(&self) {
        let mut set: HashSet<String> = HashSet::new();
        let blocked = friends::blocked();
        for f in friends::list().unwrap_or_default() { if f.online() && !f.peer.is_empty() && !blocked.contains(&f.uuid) { set.insert(f.peer); } }
        for m in &self.snap().members { set.insert(m.peer.clone()); }
        if let Role::Member { leader_peer, .. } = &self.role { set.insert(leader_peer.clone()); }
        for i in &self.invites { set.insert(i.leader_peer.clone()); }
        // invited / approved joiners and leaders that invited us (kept a while)
        let extra: Vec<String> = ALLOWED.lock().unwrap_or_else(|e| e.into_inner()).clone();
        set.extend(extra);
        *self.auth.lock().unwrap_or_else(|e| e.into_inner()) = set;
    }

    fn allow_key(&self, peer: &str) {
        let mut a = ALLOWED.lock().unwrap_or_else(|e| e.into_inner());
        if !a.iter().any(|p| p == peer) { a.push(peer.to_string()); if a.len() > 64 { a.remove(0); } }
        self.auth.lock().unwrap_or_else(|e| e.into_inner()).insert(peer.to_string());
    }

    // ---------------- network events

    fn on_net(&mut self, e: Event) {
        match e {
            Event::Up { .. } => {}
            Event::Down { peer } => { self.hello.remove(&peer); self.lost(&peer); }
            Event::Voice { peer, data } => self.on_voice(&peer, &data),
            Event::Frame { peer, msg } => {
                let Some(uuid) = self.ident(&peer) else {
                    // not a friend on our side yet: someone we asked may only say "we're friends now, check"
                    let waiting = friends::waiting();
                    match msg {
                        Msg::Hello { .. } if waiting => return,
                        Msg::Sync if waiting => synced_on_request(),
                        _ => {}
                    }
                    if let Some(n) = &self.net { n.hang_up(&peer); }
                    return;
                };
                self.on_frame(&peer, &uuid, msg);
            }
        }
    }

    fn lost(&mut self, peer: &str) {
        let me = self.me();
        let now = now_ms();
        let changed = match &mut self.role {
            Role::Leader(p) => match p.by_peer(peer).map(|m| m.uuid.clone()) {
                Some(u) if u != me => { p.lost(&u, now); true }
                _ => false,
            },
            Role::Member { leader_peer, lost, .. } => { if leader_peer == peer && lost.is_none() { *lost = Some(Instant::now()); } false }
        };
        if changed { self.broadcast(); }
    }

    fn on_voice(&mut self, peer: &str, data: &[u8]) {
        let Some((sid, seq, opus)) = party::parse_voice(data) else { return };
        // the leader forwards at most FLOOR members talking at once (more is noise, and each extra speaker costs the
        // leader's upload ~44 kb/s per listener): a new voice gets through when someone stops for 400 ms
        if let Role::Leader(p) = &self.role {
            let Some(from) = p.by_peer(peer).map(|m| m.sid) else { return };
            let now = Instant::now();
            self.floor.retain(|_, t| now.duration_since(*t) < Duration::from_millis(400));
            if !self.floor.contains_key(&from) && self.floor.len() >= FLOOR { return; }
            self.floor.insert(from, now);
        }
        let me = self.me();
        match &self.role {
            Role::Leader(p) => {
                let Some(from) = p.by_peer(peer) else { return };
                let (from_uuid, from_sid) = (from.uuid.clone(), from.sid);
                let out = party::restamp(data, from_sid);
                let to: Vec<String> = p.voice_to(&from_uuid).iter().filter(|m| m.uuid != me).map(|m| m.peer.clone()).collect();
                let hear = p.voice_to(&from_uuid).iter().any(|m| m.uuid == me);
                if let Some(n) = &self.net { for t in &to { n.voice(t, &out); } }
                if hear { self.voice.push(from_sid, seq, opus); }
            }
            Role::Member { leader_peer, snap, .. } => {
                if leader_peer != peer { return; }
                // pairwise blocks the leader may not know yet: we drop them too
                let blocked = friends::blocked();
                if snap.members.iter().find(|m| m.sid == sid).is_some_and(|m| blocked.contains(&m.uuid) || m.uuid == me) { return; }
                self.voice.push(sid, seq, opus);
            }
        }
    }

    fn on_frame(&mut self, peer: &str, uuid: &str, msg: Msg) {
        let me = self.me();
        match msg {
            Msg::Hello { name, .. } => {
                let n = party::clean_name(&name);
                if !n.is_empty() { self.names.insert(uuid.to_string(), n.clone()); friends::learn_name(uuid, &n); }
            }
            Msg::Ping => self.tell(peer, Msg::Pong),
            Msg::Pong => { if let Role::Member { heard, .. } = &mut self.role { *heard = Instant::now(); } }
            // ---- friend to friend
            Msg::Invite { party: pid, leader, leader_name, leader_peer, size, auto, ver } => {
                if !self.is_friend(uuid) || !party::valid_peer(&leader_peer) || !party::valid_uuid(&leader) || pid == self.snap().id { return; }
                if friends::blocked().contains(&leader) || !self.in_game() { return; }
                if self.other_ver(&ver) {
                    let n = self.name_of(uuid);
                    self.toast(&format!("{n} invited you, but their party plays Minecraft {ver}"));
                    return;
                }
                let we_asked = self.asked.iter().any(|(u, _)| u == uuid);
                self.invites.retain(|i| i.from != uuid);
                let id = self.next_id;
                self.next_id += 1;
                let inv = InInvite { id, from: uuid.to_string(), from_name: self.name_of(uuid), party: pid, leader, leader_name: party::clean_name(&leader_name), leader_peer, size: size.min(999), at: Instant::now() };
                self.allow_key(&inv.leader_peer);
                if auto && we_asked {
                    self.asked.retain(|(u, _)| u != uuid);
                    self.accept(inv);
                } else {
                    self.toast(&format!("{} invited you to their party", inv.from_name));
                    self.event("invite", &inv.from_name);
                    self.invites.push(inv);
                }
            }
            Msg::WorldInvite => { if self.is_friend(uuid) { crate::worldshare::check_now(); } }
            Msg::Sync => { if self.is_friend(uuid) { synced_on_request(); } }
            Msg::Ask => {
                if !self.is_friend(uuid) { return; }
                if !self.in_game() { self.tell(peer, Msg::AskAnswer { ok: false, why: "They aren't in game right now".into() }); return; }
                let name = self.name_of(uuid);
                if self.leader() {
                    self.as_leader(&me.clone(), &self.peer(), Msg::Request { uuid: uuid.to_string(), name, peer: peer.to_string() });
                } else {
                    self.relayed.retain(|(u, _)| u != uuid);
                    self.relayed.push((uuid.to_string(), peer.to_string()));
                    self.to_leader(Msg::Request { uuid: uuid.to_string(), name, peer: peer.to_string() });
                }
                self.tell(peer, Msg::AskAnswer { ok: true, why: String::new() });
            }
            Msg::AskAnswer { ok, why } => {
                if !self.asked.iter().any(|(u, _)| u == uuid) { return; }
                let n = self.name_of(uuid);
                if ok { self.say(&format!("Asked to join {n}'s party")); }
                else { self.asked.retain(|(u, _)| u != uuid); self.toast(&if why.is_empty() { format!("Couldn't join {n}'s party") } else { why }); }
            }
            // ---- from the leader
            Msg::State { snap } => self.on_state(peer, snap),
            Msg::Said { id, from, name, text } => {
                if !self.from_leader(peer) || friends::blocked().contains(&from) { return; }
                let text = party::clean_text(&text);
                if text.is_empty() { return; }
                self.line(Line { id, from, name: party::clean_name(&name), text });
            }
            Msg::Approved { uuid: friend } => {
                if !self.from_leader(peer) { return; }
                if let Some((_, fp)) = self.relayed.iter().find(|(u, _)| *u == friend).cloned() {
                    let s = self.snap().clone();
                    let leader_peer = s.member(&s.leader).map(|m| m.peer.clone()).unwrap_or_default();
                    self.tell(&fp, Msg::Invite { party: s.id.clone(), leader: s.leader.clone(), leader_name: self.name_of(&s.leader), leader_peer, size: s.size() as u32, auto: true, ver: self.mine.ver.clone() });
                    self.relayed.retain(|(u, _)| *u != friend);
                }
            }
            Msg::Declined { uuid: friend } => {
                if !self.from_leader(peer) { return; }
                if let Some((_, fp)) = self.relayed.iter().find(|(u, _)| *u == friend).cloned() {
                    self.tell(&fp, Msg::AskAnswer { ok: false, why: "The party leader didn't let you in this time".into() });
                    self.relayed.retain(|(u, _)| *u != friend);
                }
            }
            Msg::Kicked => {
                if !self.from_leader(peer) { return; }
                self.solo();
                self.toast("The party leader removed you from the party");
            }
            // ---- to the leader
            other => self.as_leader(uuid, peer, other),
        }
    }

    fn from_leader(&self, peer: &str) -> bool { matches!(&self.role, Role::Member { leader_peer, .. } if leader_peer == peer) }

    /// A member's frame to the leader (or our own action when we lead).
    fn as_leader(&mut self, uuid: &str, peer: &str, msg: Msg) {
        let me = self.me();
        let now = now_ms();
        let Role::Leader(p) = &mut self.role else { return };
        let mut changed = false;
        let mut after: Vec<(String, Msg)> = vec![];
        let mut toasts: Vec<String> = vec![];
        match msg {
            Msg::Admit { uuid: who, peer: their, name } => {
                if p.admit(uuid, &who, &their, now).is_ok() { self.names.insert(who.clone(), party::clean_name(&name)); changed = true; }
                self.allow_key(&their);
            }
            Msg::Request { uuid: who, name, peer: their } => {
                if friends::blocked().contains(&who) || self.vetoed.contains(&who) {
                    if uuid != me { after.push((peer.to_string(), Msg::Declined { uuid: who })); }
                    else { after.push((their, Msg::AskAnswer { ok: false, why: "Couldn't join this party".into() })); }
                } else {
                    let via_name = self.names.get(uuid).cloned().unwrap_or_else(|| friends::name(uuid));
                    let via_name = if uuid == me { self.my_name() } else { via_name };
                    let Role::Leader(p) = &mut self.role else { return };
                    match p.request(uuid, &via_name, &who, &name, &their, now) {
                        Ok(true) => {
                            changed = true;
                            let n = party::clean_name(&name);
                            toasts.push(if uuid == me { format!("{n} wants to join your party") } else { format!("{n} wants to join · friend of {via_name}") });
                        }
                        Ok(false) => {}
                        Err(e) => if uuid != me { after.push((peer.to_string(), Msg::Declined { uuid: who.clone() })); } else { after.push((their, Msg::AskAnswer { ok: false, why: e.to_string() })); },
                    }
                    if uuid == me { self.relayed.retain(|(u, _)| *u != who); }
                }
            }
            Msg::Join { party: pid, ver } => {
                let name = self.names.get(uuid).cloned().unwrap_or_else(|| friends::name(uuid));
                let party_ver = self.mine.ver.clone();
                let wrong = self.other_ver(&ver);
                let Role::Leader(p) = &mut self.role else { return };
                if pid != p.snap.id { after.push((peer.to_string(), Msg::AskAnswer { ok: false, why: "That party has ended".into() })); }
                else if wrong && !p.is_member(uuid) { after.push((peer.to_string(), Msg::AskAnswer { ok: false, why: format!("That party plays Minecraft {party_ver}") })); }
                else {
                    match p.join(uuid, &name, peer, now) {
                        Ok(new) => { changed = true; if new { toasts.push(format!("{name} joined the party")); } }
                        Err(e) => after.push((peer.to_string(), Msg::AskAnswer { ok: false, why: cap(&e.to_string()) })),
                    }
                }
            }
            Msg::Leave => {
                if uuid != me && p.is_member(uuid) {
                    let n = p.snap.member(uuid).map(|m| m.name.clone()).unwrap_or_default();
                    p.remove(uuid);
                    changed = true;
                    toasts.push(format!("{n} left the party"));
                    if let Some(net) = &self.net { net.hang_up(peer); }
                }
            }
            Msg::Kick { uuid: who } => {
                let n = p.snap.member(&who).map(|m| (m.name.clone(), m.peer.clone()));
                if p.kick(uuid, &who).is_ok() { changed = true; if let Some((n, pk)) = n { after.push((pk, Msg::Kicked)); toasts.push(format!("Removed {n} from the party")); } }
            }
            Msg::Promote { uuid: who } => {
                if p.promote(uuid, &who).is_ok() {
                    // hand the party over: we become a member of the new leader
                    let snap = p.snap.clone();
                    let lp = snap.member(&who).map(|m| m.peer.clone()).unwrap_or_default();
                    for m in snap.members.iter().filter(|m| m.uuid != me) { after.push((m.peer.clone(), Msg::State { snap: snap.clone() })); }
                    toasts.push(format!("{} leads the party now", snap.member(&who).map(|m| m.name.clone()).unwrap_or_default()));
                    self.role = Role::Member { snap, leader_peer: lp.clone(), lost: None, heard: Instant::now(), joined: true, since: Instant::now() };
                    for (pk, m) in after.drain(..) { self.tell(&pk, m); }
                    let pid = self.snap().id.clone();
                    let ver = self.mine.ver.clone();
                    self.tell(&lp, Msg::Join { party: pid, ver });
                    for t in toasts { self.toast(&t); }
                    return;
                }
            }
            Msg::Approve { uuid: who } => {
                if let Ok(r) = p.approve(uuid, &who, now) {
                    changed = true;
                    self.allow_key(&r.peer);
                    if r.via.is_empty() || r.via == me {
                        // our own friend asked us: invite them straight away
                        let s = self.snap().clone();
                        after.push((r.peer.clone(), Msg::Invite { party: s.id.clone(), leader: me.clone(), leader_name: self.my_name(), leader_peer: self.peer(), size: s.size() as u32, auto: true, ver: self.mine.ver.clone() }));
                    } else if let Some(m) = self.snap().member(&r.via) { after.push((m.peer.clone(), Msg::Approved { uuid: who })); }
                }
            }
            Msg::Decline { uuid: who } => {
                if let Ok(r) = p.decline(uuid, &who) {
                    changed = true;
                    if r.via.is_empty() { after.push((r.peer, Msg::AskAnswer { ok: false, why: "The party leader didn't let you in this time".into() })); }
                    else if let Some(m) = p.snap.member(&r.via) { after.push((m.peer.clone(), Msg::Declined { uuid: who })); }
                }
            }
            Msg::Veto { uuid: who } => {
                if let Some(r) = p.veto(uuid, &who) {
                    changed = true;
                    if !r.via.is_empty() { if let Some(m) = p.snap.member(&r.via) { after.push((m.peer.clone(), Msg::Declined { uuid: who })); } }
                    else { after.push((r.peer, Msg::AskAnswer { ok: false, why: "Couldn't join this party".into() })); }
                }
            }
            Msg::Me { ready, mic: m, deaf, game, at, look } => { changed = p.set_me(uuid, ready, m, deaf, &game, &at, look); }
            Msg::Blocks { uuids } => p.set_blocks(uuid, &uuids),
            Msg::Chat { text } => {
                let text = party::clean_text(&text);
                if text.is_empty() || !p.is_member(uuid) { return; }
                let name = p.snap.member(uuid).map(|m| m.name.clone()).unwrap_or_default();
                let id = self.next_id;
                self.next_id += 1;
                let to: Vec<Member> = p.text_to(uuid).into_iter().cloned().collect();
                for m in to {
                    if m.uuid == me { self.line(Line { id, from: uuid.to_string(), name: name.clone(), text: text.clone() }); }
                    else { after.push((m.peer.clone(), Msg::Said { id, from: uuid.to_string(), name: name.clone(), text: text.clone() })); }
                }
            }
            Msg::SetDest { dest } => { let e = p.snap.epoch; let _ = p.set_dest(uuid, dest); changed = p.snap.epoch != e; }
            Msg::Go => { changed = p.go(uuid).is_ok(); }
            _ => {}
        }
        for (pk, m) in after { self.tell(&pk, m); }
        for t in toasts { self.toast(&t); }
        if changed { self.broadcast(); self.after_change(); }
    }

    /// A snapshot from our leader.
    fn on_state(&mut self, peer: &str, snap: Snapshot) {
        let me = self.me();
        // a snapshot from a leader we are joining (accepted invite) or from our current leader
        let ok = match &self.role { Role::Member { leader_peer, .. } => leader_peer == peer, Role::Leader(_) => false };
        if !ok {
            // our old members after a handover / a leader we left: ignore
            return;
        }
        if snap.member(&me).is_none() {
            // not in it any more (kicked / the leader left us behind)
            self.solo();
            return;
        }
        if let Role::Member { snap: cur, .. } = &self.role { if snap.id == cur.id && snap.epoch < cur.epoch { return; } }
        if snap.leader == me {
            // handover: we lead now
            let old = match &self.role { Role::Member { snap, .. } => snap.leader.clone(), _ => String::new() };
            let gone = snap.member(&old).is_none().then_some(old);
            let p = Party::adopt(snap, &me, gone.as_deref());
            self.role = Role::Leader(p);
            self.toast("You lead the party now");
            self.broadcast();
            self.after_change();
            return;
        }
        let leader_changed = snap.leader != self.snap().leader;
        let lp = snap.member(&snap.leader).map(|m| m.peer.clone()).unwrap_or_default();
        let joined_now = matches!(&self.role, Role::Member { joined: false, .. });
        if let Role::Member { snap: cur, leader_peer, lost, heard, joined, .. } = &mut self.role {
            *cur = snap;
            *lost = None;
            *heard = Instant::now();
            *joined = true;
            if leader_changed && !lp.is_empty() { *leader_peer = lp.clone(); }
        }
        if joined_now { self.toast(&format!("You joined {}'s party", self.name_of(&self.snap().leader.clone()))); self.send_me(); }
        if leader_changed && !lp.is_empty() {
            let pid = self.snap().id.clone();
            let ver = self.mine.ver.clone();
            self.tell(&lp, Msg::Join { party: pid, ver });
        }
        self.after_change();
    }

    /// After any roster change: vetoes, pairwise blocks, the voice route, a PLAY to follow.
    fn after_change(&mut self) {
        let blocked = friends::blocked();
        let snap = self.snap().clone();
        // never let in someone we blocked
        for r in &snap.pending {
            if blocked.contains(&r.uuid) && !self.vetoed.contains(&r.uuid) {
                self.vetoed.insert(r.uuid.clone());
                self.to_leader(Msg::Veto { uuid: r.uuid.clone() });
            }
        }
        let mine: Vec<String> = snap.members.iter().filter(|m| blocked.contains(&m.uuid)).map(|m| m.uuid.clone()).collect();
        if mine != self.last_blocks {
            self.last_blocks = mine.clone();
            self.to_leader(Msg::Blocks { uuids: mine });
        }
        self.route_voice();
        // PLAY / follow
        if self.last_go == u64::MAX { self.last_go = snap.go; } // just joined: an earlier PLAY isn't ours to follow
        else if snap.go > self.last_go {
            self.last_go = snap.go;
            if !self.leader() {
                if let Some(d) = snap.dest.clone() {
                    let waiting = matches!(&d, Dest::World { ticket, .. } if ticket.is_empty()); // the leader's world is still opening
                    if self.mine.ready && self.mine.at == "lobby" { self.go_to(d, false); }
                    else if !waiting { self.event("went", &dest_name(&d)); }
                }
            }
        }
        self.publish();
    }

    fn route_voice(&self) {
        let me = self.me();
        let mut r = VoiceRoute::default();
        match &self.role {
            Role::Leader(p) => {
                r.sid = p.snap.member(&me).map(|m| m.sid).unwrap_or(1);
                r.peers = p.voice_to(&me).iter().filter(|m| m.uuid != me).map(|m| m.peer.clone()).collect();
            }
            Role::Member { leader_peer, lost, .. } => { if lost.is_none() { r.peers = vec![leader_peer.clone()]; } }
        }
        r.on = !r.peers.is_empty() && self.prefs.voice.mode != mic::OFF;
        *self.voice_route.lock().unwrap_or_else(|e| e.into_inner()) = r;
    }

    /// Ask the game to connect to a destination (a world needs its local P2P link first).
    fn go_to(&mut self, d: Dest, manual: bool) {
        match d {
            Dest::Server { address, name } => self.send_game(json!({ "t": "go", "address": address, "name": if name.is_empty() { address.clone() } else { name } })),
            Dest::World { ticket, name, host, ver, .. } => {
                if host == self.me() || ticket.is_empty() { return; }
                if self.other_ver(&ver) {
                    if manual { self.toast(&format!("This world needs Minecraft {ver}")); } else { self.event("went", &name); }
                    return;
                }
                let tx = self.tx.clone();
                std::thread::spawn(move || {
                    let v = match crate::worldshare::guest_address(&ticket) {
                        Ok(a) => json!({ "t": "go_ready", "address": a, "name": name }),
                        Err(e) => json!({ "t": "go_fail", "msg": format!("Couldn't reach the world: {e:#}") }),
                    };
                    let _ = tx.send(Cmd::Game(v));
                });
            }
        }
    }

    // ---------------- game actions

    fn on_game(&mut self, v: &Value) {
        let s = |k: &str| v[k].as_str().unwrap_or_default().to_string();
        let me = self.me();
        match v["t"].as_str().unwrap_or_default() {
            "bye" => {
                let id = v["client"].as_u64().unwrap_or(0);
                self.clients.retain(|(c, _)| *c != id);
                if !self.in_game() {
                    // the game closed: no lobby, not ready; the party ends shortly unless a game links again
                    self.game_gone = Some(Instant::now());
                    // keep the last look: the member still shows their cape and cosmetics until a game links again
                    self.mine = Mine { at: "launcher".into(), look: std::mem::take(&mut self.mine.look), ..Default::default() };
                    self.invites.clear();
                    self.send_me();
                    self.publish();
                }
                return;
            }
            "go_ready" => { self.send_game(json!({ "t": "go", "address": s("address"), "name": s("name") })); return; }
            "go_fail" => { self.toast(&s("msg")); return; }
            "ptt" => { self.voice.ptt(v["down"].as_bool().unwrap_or(false)); return; }
            "me" => {
                let look: Look = serde_json::from_value(v["look"].clone()).unwrap_or_default();
                let m = Mine { ready: self.mine.ready, at: s("at"), game: s("game"), look: look.clean(), server: s("server"), ver: s("ver"), loader: s("loader") };
                if m != self.mine {
                    let left_lobby = self.mine.at == "lobby" && m.at != "lobby";
                    self.mine = m;
                    if left_lobby && !self.leader() { self.mine.ready = false; }
                    self.send_me();
                }
                return;
            }
            _ => {}
        }
        if v["t"] != "act" { return; }
        let uuid = s("uuid");
        match v["op"].as_str().unwrap_or_default() {
            "invite" => self.invite(&uuid),
            "ask" => {
                let Some(fp) = self.friend_peer(&uuid) else { return self.toast("They aren't online in Nether right now") };
                self.asked.retain(|(u, _)| *u != uuid);
                self.asked.push((uuid.clone(), Instant::now()));
                self.tell(&fp, Msg::Ask);
                self.say(&format!("Asking to join {}...", self.name_of(&uuid)));
            }
            "accept" => {
                let id = v["id"].as_u64().unwrap_or(0);
                if let Some(i) = self.invites.iter().position(|i| i.id == id) { let inv = self.invites.remove(i); self.accept(inv); }
            }
            "decline" => { let id = v["id"].as_u64().unwrap_or(0); self.invites.retain(|i| i.id != id); }
            "approve" => self.to_leader(Msg::Approve { uuid }),
            "deny" => self.to_leader(Msg::Decline { uuid }),
            "leave" => { if self.size() > 1 { self.end_party(false); } }
            "kick" => self.to_leader(Msg::Kick { uuid }),
            "promote" => self.to_leader(Msg::Promote { uuid }),
            "chat" => {
                let text = party::clean_text(&s("text"));
                if text.is_empty() || self.size() < 2 { return; }
                if self.leader() { self.as_leader(&me.clone(), &self.peer(), Msg::Chat { text }); }
                else {
                    let id = self.next_id; self.next_id += 1;
                    self.line(Line { id, from: me.clone(), name: self.my_name(), text: text.clone() });
                    self.to_leader(Msg::Chat { text });
                }
            }
            "ready" => { self.mine.ready = v["on"].as_bool().unwrap_or(!self.mine.ready) && self.mine.at == "lobby"; self.send_me(); }
            "dest" => {
                // the leader picked: a server from their list, or one of their own worlds (opened when the party starts)
                let d = match s("kind").as_str() {
                    "world" if !s("name").is_empty() => Some(Dest::World { ticket: String::new(), name: s("name"), host: me.clone(), ver: self.mine.ver.clone(), loader: self.mine.loader.clone() }),
                    _ if !s("address").is_empty() => Some(Dest::Server { address: s("address"), name: s("name") }),
                    _ => None,
                };
                self.to_leader(Msg::SetDest { dest: d });
            }
            "follow" => { if self.snap().live { if let Some(d) = self.snap().dest.clone() { self.go_to(d, true); } } }
            "voice" => {
                let o = v["cfg"].clone();
                let was_off = self.prefs.voice.mode == mic::OFF;
                self.voice.set(|c| {
                    if let Some(m) = o["mode"].as_u64() { c.mode = m as u8; }
                    if let Some(b) = o["deaf"].as_bool() { c.deaf = b; }
                    if let Some(x) = o["input"].as_str() { c.input = x.to_string(); }
                    if let Some(x) = o["output"].as_str() { c.output = x.to_string(); }
                    if let Some(x) = o["gate"].as_f64() { c.gate = x as f32; }
                    if let Some(x) = o["gain"].as_f64() { c.gain = x as f32; }
                    if let Some(x) = o["volume"].as_f64() { c.volume = x as f32; }
                });
                if let Some(t) = o["test"].as_bool() { self.voice.test(t); }
                self.prefs.voice = self.voice.cfg();
                if was_off && self.prefs.voice.mode != mic::OFF { self.prefs.voice_intro = true; }
                self.save();
                self.send_me();
                self.route_voice();
            }
            "member_mute" => {
                let on = v["on"].as_bool().unwrap_or(true);
                self.prefs.muted.retain(|u| *u != uuid);
                if on { self.prefs.muted.push(uuid.clone()); }
                if let Some(m) = self.snap().member(&uuid) { self.voice.member_mute(m.sid, on); }
                self.save();
            }
            "member_vol" => {
                let x = v["v"].as_f64().unwrap_or(1.0) as f32;
                self.prefs.vol.insert(uuid.clone(), x.clamp(0.0, 2.0));
                if let Some(m) = self.snap().member(&uuid) { self.voice.member_volume(m.sid, x); }
                self.save();
            }
            "block" => {
                if party::valid_uuid(&uuid) && uuid != me {
                    friends::block(&self.weak, &self.sh, &uuid);
                    // at once, before the friends service answers
                    let mut b = self.last_blocks.clone();
                    if !b.contains(&uuid) { b.push(uuid.clone()); }
                    self.last_blocks = b.clone();
                    self.to_leader(Msg::Blocks { uuids: b });
                    self.say(&format!("Blocked {}", self.name_of(&uuid)));
                }
            }
            "relay" => {
                self.prefs.relay = v["on"].as_bool().unwrap_or(false);
                self.save();
                if self.size() <= 1 {
                    if let Some(n) = self.net.take() { n.close(); }
                    *NET.lock().unwrap_or_else(|e| e.into_inner()) = None;
                    self.failed_net = None;
                } else { self.say("Privacy mode applies after you leave this party"); }
            }
            "refresh" => friends::poke(),
            _ => {}
        }
        self.last_state.clear();
    }

    fn invite(&mut self, uuid: &str) {
        if !party::valid_uuid(uuid) || self.snap().member(uuid).is_some() { return; }
        let Some(fp) = self.friend_peer(uuid) else { return self.toast("They need to be online in Nether to get a party invite") };
        let f = friends::list().unwrap_or_default().into_iter().find(|f| f.uuid == uuid);
        if f.as_ref().is_some_and(|f| f.party == 0) { return self.toast(&format!("{} isn't in game: parties are in game only", self.name_of(uuid))); }
        if let Some(v) = f.map(|f| f.ver).filter(|v| self.other_ver(v)) { return self.toast(&format!("{} plays Minecraft {v}: parties are one version", self.name_of(uuid))); }
        let me = self.me();
        let name = self.name_of(uuid);
        if self.leader() { self.as_leader(&me.clone(), &self.peer(), Msg::Admit { uuid: uuid.to_string(), name: name.clone(), peer: fp.clone() }); }
        else { self.to_leader(Msg::Admit { uuid: uuid.to_string(), name: name.clone(), peer: fp.clone() }); }
        let s = self.snap().clone();
        let leader_peer = s.member(&s.leader).map(|m| m.peer.clone()).unwrap_or_default();
        self.tell(&fp, Msg::Invite { party: s.id.clone(), leader: s.leader.clone(), leader_name: self.name_of(&s.leader), leader_peer, size: s.size() as u32, auto: false, ver: self.mine.ver.clone() });
        self.say(&format!("Invited {name}"));
    }

    fn accept(&mut self, inv: InInvite) {
        if self.size() > 1 { self.end_party(true); }
        self.chat.clear();
        self.push_chat_reset();
        self.voice.reset();
        let snap = Snapshot { id: inv.party.clone(), leader: inv.leader.clone(), members: vec![self.me_member(), Member { uuid: inv.leader.clone(), name: inv.leader_name.clone(), peer: inv.leader_peer.clone(), ..Default::default() }], ..Default::default() };
        self.role = Role::Member { snap, leader_peer: inv.leader_peer.clone(), lost: None, heard: Instant::now(), joined: false, since: Instant::now() };
        self.last_go = u64::MAX; // don't follow a PLAY that happened before we joined
        self.allow_key(&inv.leader_peer);
        self.tell(&inv.leader_peer, Msg::Join { party: inv.party.clone(), ver: self.mine.ver.clone() });
        self.say(&format!("Joining {}'s party...", inv.leader_name));
    }

    fn send_me(&mut self) {
        let msg = Msg::Me { ready: self.mine.ready, mic: self.prefs.voice.mode, deaf: self.prefs.voice.deaf, game: self.mine.game.clone(), at: self.mine.at.clone(), look: self.mine.look.clone() };
        self.to_leader(msg);
    }

    // ---------------- every 250 ms

    fn tick(&mut self) {
        self.ensure_net();
        let me = self.me();
        let now = now_ms();
        self.invites.retain(|i| i.at.elapsed() < Duration::from_secs(300));
        // no game: no party (a quick restart of the game keeps it for a moment)
        if !self.in_game() && self.size() > 1 && self.game_gone.is_some_and(|t| t.elapsed() > Duration::from_secs(15)) {
            self.end_party(true);
        }
        self.asked.retain(|(_, t)| t.elapsed() < Duration::from_secs(300));
        // leader upkeep
        let mut changed = false;
        if let Role::Leader(p) = &mut self.role {
            let e = p.snap.epoch;
            for gone in p.expire(now) { let _ = gone; }
            changed = p.snap.epoch != e;
        }
        if changed { self.broadcast(); self.after_change(); }
        // member upkeep: keep the link warm, find the leader again or take over
        let mut take_over: Option<(Snapshot, String)> = None;
        let mut redial: Option<(String, String)> = None;
        let mut gave_up: Option<String> = None;
        if let Role::Member { snap, leader_peer, lost, heard, joined, since } = &mut self.role {
            if !*joined && since.elapsed() > Duration::from_secs(25) {
                gave_up = Some(snap.member(&snap.leader).map(|m| m.name.clone()).unwrap_or_default());
            } else {
            if *joined && heard.elapsed() > Duration::from_secs(30) && lost.is_none() { *lost = Some(Instant::now()); }
            if let Some(t) = *lost {
                if t.elapsed() > Duration::from_millis(party::LEADER_GRACE_MS) {
                    let old = snap.leader.clone();
                    match snap.successor(&old).cloned() {
                        Some(next) if next.uuid == me => take_over = Some((snap.clone(), old)),
                        Some(next) => {
                            snap.members.retain(|m| m.uuid != old);
                            snap.leader = next.uuid.clone();
                            *leader_peer = next.peer.clone();
                            *lost = Some(Instant::now());
                            redial = Some((next.peer.clone(), snap.id.clone()));
                        }
                        None => take_over = Some((snap.clone(), old)),
                    }
                } else if (t.elapsed().as_millis() / 250) % 12 == 0 {
                    redial = Some((leader_peer.clone(), snap.id.clone()));
                }
            }
            }
        }
        if let Some(n) = gave_up {
            self.solo();
            self.toast(&format!("Couldn't reach {n}'s party"));
            return;
        }
        if let Some((snap, old)) = take_over {
            let n = snap.member(&old).map(|m| m.name.clone()).unwrap_or_default();
            self.role = Role::Leader(Party::adopt(snap, &me, Some(&old)));
            if self.size() > 1 { self.toast(&format!("Lost {n}: you lead the party now")); } else { self.toast(&format!("Lost {n}: the party ended")); }
            self.broadcast();
            self.after_change();
        }
        if let Some((peer, pid)) = redial { let ver = self.mine.ver.clone(); self.tell(&peer, Msg::Join { party: pid, ver }); }
        // keepalive
        if self.last_ping.elapsed() > Duration::from_secs(8) {
            self.last_ping = Instant::now();
            match &self.role {
                Role::Member { leader_peer, joined: true, lost: None, .. } => { let p = leader_peer.clone(); self.tell(&p, Msg::Ping); }
                Role::Leader(p) => { let peers: Vec<String> = p.snap.members.iter().filter(|m| m.uuid != me && !m.away).map(|m| m.peer.clone()).collect(); for pk in peers { self.tell(&pk, Msg::Ping); } }
                _ => {}
            }
        }
        // the ready gate: everyone ready -> 5 s countdown -> start
        let gate = match &mut self.role { Role::Leader(p) => p.gate(now), _ => Gate::Idle };
        if gate != Gate::Idle { self.broadcast(); self.after_change(); }
        if gate == Gate::Go { self.start_here(); }
        if self.leader() && self.snap().live { self.live_upkeep(); }
        // local mutes / volumes follow sids
        let sids: Vec<(u16, String)> = self.snap().members.iter().map(|m| (m.sid, m.uuid.clone())).collect();
        for (sid, u) in sids {
            self.voice.member_mute(sid, self.prefs.muted.contains(&u));
            self.voice.member_volume(sid, self.prefs.vol.get(&u).copied().unwrap_or(1.0));
        }
        self.route_voice();
        self.voice.set_active(self.size() > 1);
        self.refresh_auth();
        // leave cleanly when the launcher quits
        let (leader_peer, snap_for_leave) = match &self.role {
            Role::Member { leader_peer, .. } => (Some(leader_peer.clone()), None),
            Role::Leader(p) if p.snap.size() > 1 => { let mut q = p.clone(); q.remove(&me); (None, Some((q.snap.clone(), p.snap.members.iter().filter(|m| m.uuid != me).map(|m| m.peer.clone()).collect::<Vec<_>>()))) }
            _ => (None, None),
        };
        if let Some(net) = self.net.clone() {
            *LEAVE.lock().unwrap_or_else(|e| e.into_inner()) = Some(Box::new(move || {
                if let Some(lp) = leader_peer { net.send(&lp, &Msg::Leave); }
                if let Some((snap, peers)) = snap_for_leave { for p in peers { net.send(&p, &Msg::State { snap: snap.clone() }); } }
            }));
        }
        self.publish();
        self.push_state();
        self.push_talk();
    }

    fn save(&self) { let _ = nether_core::store::write_json(&prefs_file(&self.sh), &self.prefs); }

    /// The countdown ran out (we lead): our game goes first. A server: connect. A world: the game opens it and hosts
    /// it for the party; `live_upkeep` sends its link to the members once it is up.
    fn start_here(&mut self) {
        self.live_at = Some(Instant::now());
        self.arrived = false;
        match self.snap().dest.clone() {
            Some(Dest::Server { address, name }) => self.send_game(json!({ "t": "go", "address": address, "name": if name.is_empty() { address.clone() } else { name } })),
            Some(Dest::World { name, .. }) => self.send_game(json!({ "t": "host_world", "name": name })),
            None => {}
        }
    }

    /// Leader while the party plays: a hosted world's link goes to the members; back in the lobby (or never got
    /// there) ends the round, everyone readies up again.
    fn live_upkeep(&mut self) {
        let me = self.me();
        let started = *self.live_at.get_or_insert_with(Instant::now);
        if self.mine.at != "lobby" { self.arrived = true; }
        let over = if self.arrived { self.mine.at == "lobby" } else { started.elapsed() > Duration::from_secs(60) };
        if over {
            if !self.arrived { self.toast("Couldn't start: the party is back in the lobby"); }
            if let Role::Leader(p) = &mut self.role { p.end_live(); }
            self.live_at = None;
            self.arrived = false;
            self.broadcast();
            self.after_change();
            return;
        }
        let pending = matches!(&self.snap().dest, Some(Dest::World { ticket, host, .. }) if ticket.is_empty() && *host == me);
        if pending {
            if let Some((ticket, ..)) = crate::worldshare::host_info() {
                let sent = match &mut self.role { Role::Leader(p) => p.set_ticket(&ticket), _ => false };
                if sent { self.broadcast(); self.after_change(); }
            }
        }
    }

    // ---------------- to the game

    fn send_game(&mut self, v: Value) {
        let line = format!("{v}\n");
        self.clients.retain_mut(|(_, s)| s.write_all(line.as_bytes()).is_ok());
    }

    fn toast(&mut self, t: &str) { self.send_game(json!({ "t": "toast", "text": t })); }
    /// A status line in the lobby (not a pop-up).
    fn say(&mut self, t: &str) { self.note = t.to_string(); self.last_state.clear(); }
    fn event(&mut self, kind: &str, text: &str) { self.send_game(json!({ "t": "event", "kind": kind, "text": text })); }

    fn line(&mut self, l: Line) {
        self.chat.push_back(l.clone());
        while self.chat.len() > 60 { self.chat.pop_front(); }
        self.send_game(json!({ "t": "chat", "id": l.id, "from": l.from, "name": l.name, "text": l.text, "me": l.from == self.me() }));
    }
    fn push_chat_reset(&mut self) { self.send_game(json!({ "t": "chat_clear" })); }
    /// A game that (re)connects mid-party gets the lines of this session again (memory only).
    fn push_chat_history(&mut self, id: u64) {
        let me = self.me();
        let lines: Vec<String> = self.chat.iter().map(|l| format!("{}\n", json!({ "t": "chat", "id": l.id, "from": l.from, "name": l.name, "text": l.text, "me": l.from == me }))).collect();
        if let Some((_, s)) = self.clients.iter_mut().find(|(c, _)| *c == id) { for l in lines { let _ = s.write_all(l.as_bytes()); } }
    }

    fn push_talk(&mut self) {
        let me = self.me();
        let talking: Vec<u16> = self.voice.talking();
        let mut who: Vec<String> = self.snap().members.iter().filter(|m| talking.contains(&m.sid) && m.uuid != me).map(|m| m.uuid.clone()).collect();
        let (level, sending, _, _) = self.voice.meter();
        if sending && self.size() > 1 { who.push(me.clone()); }
        let v = json!({ "t": "talk", "s": who, "lv": (level * 20.0).round() / 20.0, "tx": sending });
        let t = v.to_string();
        if t != self.last_talk { self.last_talk = t; self.send_game(v); }
    }

    fn push_state(&mut self) {
        if self.clients.is_empty() { return; }
        let v = self.state_json();
        let t = v.to_string();
        if t != self.last_state { self.last_state = t; self.send_game(v); }
    }

    fn state_json(&self) -> Value {
        let me = self.me();
        let snap = self.snap();
        let route = match &self.role {
            Role::Member { leader_peer, .. } => self.net.as_ref().and_then(|n| n.route(leader_peer)).unwrap_or(""),
            Role::Leader(p) => p.snap.members.iter().find(|m| m.uuid != me).and_then(|m| self.net.as_ref().and_then(|n| n.route(&m.peer))).unwrap_or(""),
        };
        let (lost, joining) = match &self.role { Role::Member { lost, joined, .. } => (lost.is_some(), !joined), _ => (false, false) };
        let start_in = if snap.start > 0 { snap.start.saturating_sub(now_ms()) } else { 0 };
        let members: Vec<Value> = snap.members.iter().map(|m| json!({
            "uuid": m.uuid, "name": if m.name.is_empty() { self.name_of(&m.uuid) } else { m.name.clone() }, "leader": m.uuid == snap.leader, "me": m.uuid == me,
            "ready": m.ready || (m.uuid == snap.leader), "mic": m.mic, "deaf": m.deaf, "away": m.away, "game": m.game, "at": m.at, "seq": m.seq,
            "look": m.look, "muted": self.prefs.muted.contains(&m.uuid), "vol": self.prefs.vol.get(&m.uuid).copied().unwrap_or(1.0),
            "friend": self.is_friend(&m.uuid), "blocked": self.last_blocks.contains(&m.uuid),
        })).collect();
        let pending: Vec<Value> = snap.pending.iter().map(|r| json!({ "uuid": r.uuid, "name": r.name, "via": r.via_name })).collect();
        let dest = snap.dest.as_ref().map(|d| match d {
            Dest::Server { address, name } => json!({ "kind": "server", "address": address, "name": name }),
            Dest::World { name, host, ver, loader, ticket } => json!({ "kind": "world", "name": name, "host": host, "host_name": self.name_of(host), "ver": ver, "loader": loader,
                "here": !self.other_ver(ver), "pending": ticket.is_empty() }),
        });
        let in_party: HashSet<&str> = snap.members.iter().map(|m| m.uuid.as_str()).collect();
        let asked: Vec<&str> = self.asked.iter().map(|(u, _)| u.as_str()).collect();
        let friends_v: Vec<Value> = friends::list().unwrap_or_default().iter().map(|f| {
            let on = f.online();
            json!({ "uuid": f.uuid, "name": friends::name(&f.uuid), "online": on, "status": if on { u8::from(f.status) } else { 0 },
                "act": if on { friends::game_line(f) } else { friends::offline_line(f) }, "party": if on { f.party } else { 0 }, "seen": f.seen,
                "nether": on && !f.peer.is_empty(), "here": in_party.contains(f.uuid.as_str()), "asked": asked.contains(&f.uuid.as_str()),
                "ingame": on && !f.peer.is_empty() && f.party > 0, "ver": f.ver, "same": !self.other_ver(&f.ver) })
        }).collect();
        let invites: Vec<Value> = self.invites.iter().map(|i| json!({ "id": i.id, "from": i.from, "from_name": i.from_name, "leader_name": i.leader_name, "size": i.size, "age": i.at.elapsed().as_secs() })).collect();
        let c = self.voice.cfg();
        let (_, _, ein, eout) = self.voice.meter();
        let (ins, outs) = DEVICES.lock().unwrap_or_else(|e| e.into_inner()).clone();
        json!({
            "t": "state",
            "ok": self.net.is_some(), "signed": self.who.is_some(), "me": me, "name": self.my_name(), "note": self.note,
            "service": if self.net.is_none() { if self.who.is_none() { "signin" } else { "starting" } } else if friends::list().is_none() { "friends" } else { "ok" },
            "party": { "id": snap.id, "leader": snap.leader, "size": snap.size(), "members": members, "pending": pending, "dest": dest, "go": snap.go,
                "route": route, "lost": lost, "joining": joining, "leading": self.leader(), "start_in": start_in, "live": snap.live },
            "invites": invites, "friends": friends_v,
            "voice": { "mode": c.mode, "deaf": c.deaf, "input": c.input, "output": c.output, "gate": c.gate, "gain": c.gain, "volume": c.volume,
                "test": self.voice.testing(), "err_in": ein, "err_out": eout, "intro": self.prefs.voice_intro,
                "ins": ins.iter().map(|(i, n)| json!([i, n])).collect::<Vec<_>>(), "outs": outs.iter().map(|(i, n)| json!([i, n])).collect::<Vec<_>>() },
            "relay": self.prefs.relay, "ready": self.mine.ready, "ver": self.mine.ver,
        })
    }
}

fn dest_name(d: &Dest) -> String { match d { Dest::Server { name, address } => if name.is_empty() { address.clone() } else { name.clone() }, Dest::World { name, .. } => name.clone() } }
fn cap(s: &str) -> String { let mut c = s.chars(); c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default() }

/// Keys allowed to dial us besides friends and the roster (invited joiners, leaders that invited us).
static ALLOWED: Mutex<Vec<String>> = Mutex::new(Vec::new());
/// Audio devices (refreshed in the background: enumerating can take a moment).
static DEVICES: Mutex<(Vec<(String, String)>, Vec<(String, String)>)> = Mutex::new((Vec::new(), Vec::new()));

/// Refresh the device lists now and then (cheap, off the actor thread).
pub fn watch_devices() {
    std::thread::Builder::new().name("nether-party-devices".into()).spawn(|| loop {
        let d = voice::devices();
        *DEVICES.lock().unwrap_or_else(|e| e.into_inner()) = d;
        std::thread::sleep(Duration::from_secs(20));
    }).ok();
}

/// Three launchers' party actors in one process over the real P2P network (n0 relays / hole punching):
/// `cargo test -p nether-app party_two_launchers -- --ignored --nocapture`
#[cfg(test)]
mod tests {
    use super::*;
    use nether_core::friends::{Friend, State, Status};
    use std::sync::atomic::{AtomicBool, AtomicU32};

    fn shared(uuid: &str, name: &str, root: &std::path::Path) -> Arc<Shared> {
        Arc::new(Shared {
            paths: nether_core::paths::Paths { root: root.to_path_buf() }, client_id: None,
            session: Mutex::new(Some(Session { name: name.into(), uuid: uuid.into(), access_token: String::new(), user_type: "msa".into() })),
            cancel: AtomicBool::new(false), toast_id: AtomicU32::new(0), running: Mutex::new(Default::default()), launching: Mutex::new(Default::default()), stopping: Mutex::new(Default::default()),
        })
    }

    /// `game`: the far end of a stand-in game link (parties exist only while a game is linked); what the launcher
    /// sends the game lands here unread.
    struct Node { a: Actor, rx: Receiver<Cmd>, t: Instant, _game: Option<TcpStream> }
    impl Node {
        fn new(uuid: &str, name: &str, dir: &std::path::Path) -> Node { Node::with(uuid, name, dir, false) }
        /// `relay`: Hide my IP (relay-only): the link crosses the internet, like a friend whose router blocks a direct path
        fn with(uuid: &str, name: &str, dir: &std::path::Path, relay: bool) -> Node {
            let (tx, rx) = channel();
            let mut a = Actor::new(shared(uuid, name, dir), slint::Weak::default(), tx, Prefs { follow: true, relay, ..Default::default() });
            a.ensure_net();
            assert!(a.net.is_some(), "endpoint");
            let l = TcpListener::bind(("127.0.0.1", 0)).unwrap();
            let ours = TcpStream::connect(l.local_addr().unwrap()).unwrap();
            let (game, _) = l.accept().unwrap();
            a.clients.push((1, ours));
            a.on_game(&json!({ "t": "me", "at": "lobby", "ver": "1.21.11", "loader": "fabric", "game": "1.21.11 Fabric" }));
            Node { a, rx, t: Instant::now(), _game: Some(game) }
        }
        fn pump(&mut self) { for _ in 0..20 { self.a.step(&self.rx, &mut self.t, Duration::from_millis(5)); } }
    }

    fn run_until(n: &mut [&mut Node], secs: u64, mut done: impl FnMut(&[&mut Node]) -> bool) -> bool {
        let end = Instant::now() + Duration::from_secs(secs);
        while Instant::now() < end {
            for x in n.iter_mut() { x.pump(); }
            if done(n) { return true; }
        }
        false
    }

    #[test]
    #[ignore]
    fn party_two_launchers() {
        let dir = std::env::temp_dir().join("nether-party-test");
        let _ = std::fs::create_dir_all(&dir);
        let (ua, ub, uc) = ("a".repeat(32), "b".repeat(32), "c".repeat(32));
        let mut a = Node::new(&ua, "Alice", &dir.join("a"));
        let mut b = Node::new(&ub, "Bob", &dir.join("b"));
        let mut c = Node::new(&uc, "Cara", &dir.join("c"));
        // the friends service (one list shared by the three in this process; each ignores itself)
        let f = |u: &str, p: &str| Friend { uuid: u.into(), status: Status::Online, peer: p.into(), party: 1, ver: "1.21.11".into(), ..Default::default() };
        let (pa, pb, pc) = (a.a.peer(), b.a.peer(), c.a.peer());
        crate::friends::test_state(State { friends: vec![f(&ua, &pa), f(&ub, &pb), f(&uc, &pc)], ..Default::default() });
        // Alice invites Bob; Bob accepts
        a.a.on_game(&json!({ "t": "act", "op": "invite", "uuid": ub }));
        assert!(run_until(&mut [&mut a, &mut b, &mut c], 40, |n| !n[1].a.invites.is_empty()), "Bob got no invite");
        let id = b.a.invites[0].id;
        b.a.on_game(&json!({ "t": "act", "op": "accept", "id": id }));
        assert!(run_until(&mut [&mut a, &mut b, &mut c], 40, |n| n[0].a.size() == 2 && n[1].a.size() == 2 && !n[1].a.leader()), "Bob didn't join");
        println!("joined: route {:?}", a.a.net.as_ref().unwrap().route(&pb));
        // chat goes member -> leader, live only
        b.a.on_game(&json!({ "t": "act", "op": "chat", "text": "hi alice" }));
        assert!(run_until(&mut [&mut a, &mut b, &mut c], 20, |n| n[0].a.chat.iter().any(|l| l.text == "hi alice")), "no chat");
        // Cara asks Bob (a member, not the leader); Alice sees "friend of Bob" and approves; Cara joins
        c.a.on_game(&json!({ "t": "act", "op": "ask", "uuid": ub }));
        assert!(run_until(&mut [&mut a, &mut b, &mut c], 30, |n| n[0].a.snap().pending.iter().any(|r| r.uuid == "c".repeat(32) && r.via_name == "Bob")), "no request at the leader");
        a.a.on_game(&json!({ "t": "act", "op": "approve", "uuid": uc }));
        assert!(run_until(&mut [&mut a, &mut b, &mut c], 40, |n| n.iter().all(|x| x.a.size() == 3)), "Cara didn't join");
        // the ready gate: Alice picks a server, Bob and Cara ready up, 5 s later the party starts and they follow
        a.a.on_game(&json!({ "t": "act", "op": "dest", "kind": "server", "address": "mc.example.net:25565", "name": "Ex" }));
        assert!(run_until(&mut [&mut a, &mut b, &mut c], 10, |n| n[2].a.snap().dest.is_some()), "pick not seen");
        b.a.on_game(&json!({ "t": "act", "op": "ready", "on": true }));
        c.a.on_game(&json!({ "t": "act", "op": "ready", "on": true }));
        assert!(run_until(&mut [&mut a, &mut b, &mut c], 10, |n| n[0].a.snap().start > 0), "no countdown");
        let t0 = Instant::now();
        assert!(run_until(&mut [&mut a, &mut b, &mut c], 15, |n| n[0].a.snap().live && n[1].a.last_go == n[0].a.snap().go && n[2].a.last_go == n[0].a.snap().go), "didn't start");
        println!("started {:.1} s after the countdown began", t0.elapsed().as_secs_f32());
        // voice: Bob's frame goes to the leader, who stamps Bob's id and passes it to Cara
        let bob_sid = a.a.snap().member(&ub).map(|m| m.sid).unwrap();
        for seq in 0..6u16 { b.a.net.as_ref().unwrap().voice(&pa, &party::voice_frame(0, seq, &[0xF8, 0xFF, 0xFE])); }
        assert!(run_until(&mut [&mut a, &mut b, &mut c], 10, |n| n[2].a.voice.talking().contains(&bob_sid)), "voice not forwarded");
        // the leader hands over to Bob, then leaves; Bob still leads Cara
        a.a.on_game(&json!({ "t": "act", "op": "promote", "uuid": ub }));
        assert!(run_until(&mut [&mut a, &mut b, &mut c], 30, |n| n[1].a.leader() && !n[0].a.leader() && n[2].a.snap().leader == n[1].a.me()), "no handover");
        a.a.on_game(&json!({ "t": "act", "op": "leave" }));
        assert!(run_until(&mut [&mut a, &mut b, &mut c], 30, |n| n[0].a.size() == 1 && n[1].a.size() == 2 && n[2].a.size() == 2), "leave");
        // Bob's launcher dies: Cara (the only one left) takes over after the grace
        b.a.net.as_ref().unwrap().close();
        b.a.net = None;
        assert!(run_until(&mut [&mut c], 90, |n| n[0].a.leader()), "no takeover");
        println!("ok: invite, join, chat, request via a friend, handover, leave, lost leader");
    }

    /// Voice over the real network, measured: `NC_PARTY_N` people (default 3) join a leader, `NC_TALKERS` of them
    /// (default 1) talk for `NC_SECS` (default 15) and the last member listens. `NC_RELAY=1` forces every link
    /// through n0's relay (a friend on another network whose router blocks a direct path); `NC_NET_IMPAIR` (see
    /// `party_net::impair`) adds loss / delay / jitter on every hop. Prints delivery, FEC / concealment, dropouts,
    /// latency and the leader's upload.
    /// `cargo test -p nether-app --release voice_quality -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn voice_quality() {
        use opus1::{Application, Bitrate, Channels, InbandFec, OpusEncoder, SampleRate};
        use std::sync::atomic::Ordering::Relaxed;
        let env = |k: &str, d: usize| std::env::var(k).ok().and_then(|v| v.parse().ok()).unwrap_or(d);
        let (n, talkers, secs) = (env("NC_PARTY_N", 3).max(2), env("NC_TALKERS", 1), env("NC_SECS", 15));
        let talkers = talkers.min(n - 1).max(1);
        let relay = std::env::var("NC_RELAY").is_ok_and(|v| v == "1");
        let dir = std::env::temp_dir().join("nether-voice-test");
        let _ = std::fs::create_dir_all(&dir);
        let uuids: Vec<String> = (0..n).map(|i| format!("{:032x}", 0xA000 + i)).collect();
        let t0 = Instant::now();
        let mut nodes: Vec<Node> = (0..n).map(|i| Node::with(&uuids[i], &format!("P{i}"), &dir.join(format!("p{i}")), relay)).collect();
        let f = |u: &str, p: &str| Friend { uuid: u.into(), status: Status::Online, peer: p.into(), party: 1, ver: "1.21.11".into(), ..Default::default() };
        crate::friends::test_state(State { friends: (0..n).map(|i| f(&uuids[i], &nodes[i].a.peer())).collect(), ..Default::default() });
        println!("{n} endpoints up in {:?} (relay-only: {relay}, impair: {:?})", t0.elapsed(), std::env::var("NC_NET_IMPAIR").ok());
        let pump = |nodes: &mut Vec<Node>| for x in nodes.iter_mut() { x.a.step(&x.rx, &mut x.t, Duration::ZERO); };
        let lp0 = nodes[0].a.peer();
        for i in 1..n { nodes[0].a.on_game(&json!({ "t": "act", "op": "invite", "uuid": uuids[i] })); }
        let end = Instant::now() + Duration::from_secs(60 + 3 * n as u64);
        let mut accepted = vec![false; n];
        while Instant::now() < end && nodes[0].a.size() < n {
            pump(&mut nodes);
            for i in 1..n {
                if !accepted[i] && !nodes[i].a.invites.is_empty() {
                    let id = nodes[i].a.invites[0].id;
                    nodes[i].a.on_game(&json!({ "t": "act", "op": "accept", "id": id }));
                    accepted[i] = true;
                }
            }
            std::thread::sleep(Duration::from_millis(1));
        }
        if nodes[0].a.size() != n {
            for (i, x) in nodes.iter().enumerate() {
                let net = x.a.net.as_ref().unwrap();
                println!("  P{i}: size {} leader {} invites {} accepted {} link to leader {:?} / from leader {:?}", x.a.size(), x.a.leader(), x.a.invites.len(), accepted[i],
                    net.route(&lp0), nodes[0].a.net.as_ref().unwrap().route(&x.a.peer()));
            }
        }
        assert_eq!(nodes[0].a.size(), n, "not everyone joined");
        let lp = nodes[0].a.peer();
        let routes: Vec<_> = (1..n).map(|i| nodes[0].a.net.as_ref().unwrap().route(&nodes[i].a.peer())).collect();
        println!("party of {n} formed in {:?}; leader routes: {routes:?}", t0.elapsed());
        let settle = Instant::now() + Duration::from_secs(3);
        while Instant::now() < settle { pump(&mut nodes); std::thread::sleep(Duration::from_millis(1)); }
        // talker j says a voice-like signal (pitch + syllables), 20 ms a frame, in real time; the last member listens
        let listener = n - 1;
        let tk: Vec<usize> = (1..=talkers).map(|k| if k == listener { 0 } else { k }).collect();
        let mut enc: Vec<OpusEncoder> = tk.iter().map(|_| {
            let mut e = OpusEncoder::new(SampleRate::Hz48000, Channels::Mono, Application::Voip).unwrap();
            e.set_bitrate(Bitrate::BitsPerSecond(std::num::NonZeroU32::new(24_000).unwrap()));
            e.set_inband_fec(InbandFec::On);
            let _ = e.set_packet_loss_perc(10);
            e
        }).collect();
        let frames = secs * 50;
        let sig = |k: usize, i: usize| -> f32 {
            let t = i as f32 / 48_000.0;
            if (t * 1000.0) as u32 % 400 >= 280 { return 0.0; } // syllables: 280 ms on, 120 ms off
            // pitch 140 Hz (+40 per talker) gliding +-15 Hz: phase is the integral of the frequency
            let ph = (140.0 + 40.0 * k as f64) * t as f64 - 5.0 * (t as f64 * 3.0).cos();
            (0..6).map(|h| (((h + 1) as f64 * ph).fract() as f32 * std::f32::consts::TAU).sin() / (h + 1) as f32).sum::<f32>() * 0.18
        };
        let (out0, bytes0) = (nether_core::party_net::VOICE_OUT.0.load(Relaxed), nether_core::party_net::VOICE_OUT.1.load(Relaxed));
        let (mut sent_pk, mut sent_b) = (0u64, 0u64);
        let mut heard: Vec<f32> = Vec::with_capacity(frames * voice::FRAME + 96_000);
        let mut mix_ns = 0u128;
        let tail = 100; // keep listening 2 s after the last frame
        let start = Instant::now();
        let (mut k_send, mut k_mix) = (0usize, 0usize);
        while k_mix < frames + tail {
            let now = start.elapsed();
            while k_send < frames && now >= Duration::from_millis(20 * k_send as u64) {
                for (j, &who) in tk.iter().enumerate() {
                    let pcm: Vec<f32> = (0..voice::FRAME).map(|x| sig(j, k_send * voice::FRAME + x)).collect();
                    let mut b = [0u8; 400];
                    let len = enc[j].encode(&pcm, voice::FRAME, &mut b).unwrap();
                    let frame = party::voice_frame(0, k_send as u16, &b[..len]);
                    if who == 0 { let me = lp.clone(); nodes[0].a.on_voice(&me, &frame); }
                    else { nodes[who].a.net.as_ref().unwrap().voice(&lp, &frame); sent_pk += 1; sent_b += frame.len() as u64; }
                }
                k_send += 1;
            }
            while k_mix < frames + tail && now >= Duration::from_millis(20 * k_mix as u64) {
                let t = Instant::now();
                heard.extend(nodes[listener].a.voice.mix());
                mix_ns += t.elapsed().as_nanos();
                k_mix += 1;
            }
            pump(&mut nodes);
            std::thread::sleep(Duration::from_micros(500));
        }
        let st = nodes[listener].a.voice.stats();
        let fwd_pk = nether_core::party_net::VOICE_OUT.0.load(Relaxed) - out0 - sent_pk;
        let fwd_b = nether_core::party_net::VOICE_OUT.1.load(Relaxed) - bytes0 - sent_b;
        // the leader's upload, with UDP / IP / QUIC overhead (~60 bytes a datagram)
        let up_kbps = (fwd_b + fwd_pk * 60) as f64 * 8.0 / secs as f64 / 1000.0;
        let expected = (frames * talkers) as f64;
        println!("listener: {st:?}");
        println!("delivered {:.1}% of frames; rebuilt by FEC {}, concealed {}, ran dry {} times; decode+mix {:.3} ms per 20 ms",
            st.recv as f64 / expected * 100.0, st.fec, st.plc, st.dry, mix_ns as f64 / k_mix as f64 / 1e6);
        println!("leader forwarded {fwd_pk} datagrams: upload ~{up_kbps:.0} kb/s for {talkers} talker(s), {} member(s)", n - 1);
        if talkers == 1 {
            // what was said vs what was heard, 20 ms at a time: latency = best lag; dropout = speech that came out silent
            let rms = |v: &[f32]| (v.iter().map(|x| x * x).sum::<f32>() / v.len().max(1) as f32).sqrt();
            let said: Vec<f32> = (0..frames).map(|k| rms(&(0..voice::FRAME).map(|x| sig(0, k * voice::FRAME + x)).collect::<Vec<_>>())).collect();
            let got: Vec<f32> = heard.chunks(voice::FRAME).map(rms).collect();
            let corr = |l: usize| said.iter().enumerate().map(|(k, s)| s * got.get(k + l).copied().unwrap_or(0.0)).sum::<f32>();
            let lag = (0..tail).max_by(|&a, &b| corr(a).total_cmp(&corr(b))).unwrap();
            // mid-syllable frames only (an onset / release frame straddles silence in either signal)
            let voiced: Vec<usize> = (50..frames - 2).filter(|&k| (k - 2..=k + 2).all(|j| said[j] > 0.05)).collect();
            // the codec's overall level, then frames far below it
            let gain = voiced.iter().map(|&k| got.get(k + lag).copied().unwrap_or(0.0)).sum::<f32>() / voiced.iter().map(|&k| said[k]).sum::<f32>().max(1e-6);
            let drop = voiced.iter().filter(|&&k| got.get(k + lag).copied().unwrap_or(0.0) < said[k] * gain * 0.3).count();
            if std::env::var("NC_VOICE_DEBUG").is_ok() {
                for &k in &voiced { let g = got.get(k + lag).copied().unwrap_or(0.0); if g < said[k] * gain * 0.3 { println!("  drop k={k} pos={} said={:.3} got={g:.3} prev={:.3} next={:.3}", k % 20, said[k], got[k + lag - 1], got[k + lag + 1]); } }
            }
            println!("latency through the network + jitter buffer ~{} ms (+ the sound card's ~60 ms); level {gain:.2}; dropouts {:.2}% of speech ({drop} of {} frames)",
                lag * 20, drop as f64 / voiced.len().max(1) as f64 * 100.0, voiced.len());
            assert!(st.recv as f64 > expected * 0.5, "most voice never arrived");
        }
        for x in &nodes { x.a.net.as_ref().unwrap().close(); }
    }

    /// Dev: a real two-person party whose leader serves the game bridge in the real client folder, for a game started
    /// meanwhile (`launch` example) to connect to. `NC_BRIDGE_SECS` (default 150).
    #[test]
    #[ignore]
    fn bridge_with_game() {
        let (ua, ub) = ("ec70bcaf702f4bb8b48d276fa52a780c".to_string(), "069a79f444e94726a5befca90e38aaf5".to_string());
        let (tx, rx) = channel();
        let sh = Arc::new(Shared {
            paths: nether_core::paths::Paths::new(), client_id: None,
            session: Mutex::new(Some(Session { name: "Dream".into(), uuid: ua.clone(), access_token: String::new(), user_type: "msa".into() })),
            cancel: AtomicBool::new(false), toast_id: AtomicU32::new(0), running: Mutex::new(Default::default()), launching: Mutex::new(Default::default()), stopping: Mutex::new(Default::default()),
        });
        bridge(&sh, tx.clone());
        let mut a = Node { a: Actor::new(sh.clone(), slint::Weak::default(), tx, Prefs { follow: true, ..Default::default() }), rx, t: Instant::now(), _game: None }; // the real game links over the bridge
        a.a.ensure_net();
        let dir = std::env::temp_dir().join("nether-party-test");
        let mut b = Node::new(&ub, "Notch", &dir.join("b2"));
        let f = |u: &str, p: &str| Friend { uuid: u.into(), status: Status::Online, peer: p.into(), party: 1, ver: "1.21.11".into(), ..Default::default() };
        let (pa, pb) = (a.a.peer(), b.a.peer());
        crate::friends::test_state(State { friends: vec![f(&ua, &pa), f(&ub, &pb)], ..Default::default() });
        a.a.on_game(&json!({ "t": "act", "op": "invite", "uuid": ub }));
        assert!(run_until(&mut [&mut a, &mut b], 40, |n| !n[1].a.invites.is_empty()));
        let id = b.a.invites[0].id;
        b.a.on_game(&json!({ "t": "act", "op": "accept", "id": id }));
        assert!(run_until(&mut [&mut a, &mut b], 40, |n| n[0].a.size() == 2));
        b.a.on_game(&json!({ "t": "act", "op": "ready", "on": true }));
        let secs = std::env::var("NC_BRIDGE_SECS").ok().and_then(|s| s.parse().ok()).unwrap_or(150);
        let end = Instant::now() + Duration::from_secs(secs);
        let mut said = 0;
        let mut seq = 0u16;
        // NC_BRIDGE_GO=<address>: 30 s after the game connects, the party "goes" there (the game leaves its world first)
        let go = std::env::var("NC_BRIDGE_GO").ok();
        let mut linked_at: Option<Instant> = None;
        while Instant::now() < end {
            a.pump();
            b.pump();
            if linked_at.is_none() && !a.a.clients.is_empty() { linked_at = Some(Instant::now()); }
            if let (Some(addr), Some(t)) = (&go, linked_at) {
                if t.elapsed() > Duration::from_secs(30) { a.a.send_game(json!({ "t": "go", "address": addr, "name": "Party server" })); println!("sent go {addr}"); linked_at = Some(Instant::now() + Duration::from_secs(100_000)); }
            }
            if said < 3 && !a.a.clients.is_empty() && Instant::now() > end - Duration::from_secs(secs - 40 - said * 5) {
                let line = ["gg, that was close", "ready for another?", "queue bedwars"][said as usize];
                b.a.on_game(&json!({ "t": "act", "op": "chat", "text": line }));
                said += 1;
            }
            seq = seq.wrapping_add(1);
            b.a.net.as_ref().unwrap().voice(&pa, &party::voice_frame(0, seq, &[0xF8, 0xFF, 0xFE]));
        }
        println!("game clients: {}, last state bytes: {}", a.a.clients.len(), a.a.last_state.len());
        let _ = std::fs::remove_file(dir_of(&sh).join("bridge.json"));
    }

    fn dir_of(sh: &Shared) -> PathBuf { dir(sh) }
}
