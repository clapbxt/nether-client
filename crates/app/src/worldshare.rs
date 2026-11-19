//! Invite to world, launcher side (`nether_core::share`, `nether_core::p2p`). The game's `ov/WorldShare` writes
//! requests to `client/share/request.json` (open a world on a port, invite a player, stop, join / show / dismiss an
//! invite); this module answers in `client/share/state.json`.
//! Reaching the world needs nothing from the player: friends with Nether Client get a peer-to-peer link (iroh: hole
//! punching, free relay fallback), dialed from the invite; the game joins a local port that this module pipes to the
//! host. For friends on plain Minecraft, UPnP + an outside check give a public address when the router allows it.
//! Incoming invites are polled: the game shows them on a card, the launcher in a dialog that starts a matching
//! instance straight into the world (OneLauncher's plan: one match launches, several ask, none offers to make one).
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use nether_core::download::client;
use nether_core::launch::{Loader, QuickPlay};
use nether_core::net::{self, Upnp};
use nether_core::p2p;
use nether_core::share::{self, Invite};
use nether_core::{instances, Session};
use serde_json::{json, Value};
use slint::{ComponentHandle, Model, ModelRc, SharedString, VecModel};

use crate::{show_toast, ui_do, AppWindow, Backend, Shared, WorldInvite};

/// The world this player shares right now.
struct Host {
    gen: u64,
    port: u16,
    world: String,
    ver: String,
    loader: String,
    /// starting | online | failed
    phase: &'static str,
    msg: String,
    /// P2P invite address (`iroh:...`), "" until the link is up
    ticket: String,
    /// address plain Minecraft can join from anywhere ("" = the router doesn't allow it)
    public: String,
    /// checking | open | closed
    reach: &'static str,
    lan: String,
    upnp: Option<Upnp>,
    link: Option<p2p::Host>,
    /// invites sent (newest first; the game marks its friends list from these)
    sent: Vec<Sent>,
    /// invites asked for while the link was still starting: (name, uuid or ""), sent once it is up
    queue: Vec<(String, String)>,
}

struct Sent { name: String, uuid: String, ok: bool, msg: String }

/// Remember an invite's outcome for the game (one entry per player).
fn record(h: &mut Host, name: &str, uuid: &str, ok: bool, msg: &str) {
    h.sent.retain(|x| !(x.name.eq_ignore_ascii_case(name) || (!uuid.is_empty() && x.uuid == uuid)));
    h.sent.insert(0, Sent { name: name.into(), uuid: uuid.into(), ok, msg: msg.into() });
    h.sent.truncate(32);
}

/// An incoming invite with its sender's name; `here` = the running game is the right version and loader.
#[derive(Clone)]
struct Shown { inv: Invite, from: String, here: bool }

/// A link to a friend's world: the game joins 127.0.0.1:port.
struct Guest { ticket: String, link: p2p::Guest, at: Instant }

struct St {
    host: Option<Host>,
    gen: u64,
    invites: Vec<Shown>,
    /// invite ids already announced (never shown twice)
    seen: Vec<i64>,
    answer: Option<(f64, bool, String)>,
    guests: Vec<Guest>,
    /// the game asked to join an invite: (invite id, address to connect to)
    joined: Option<(i64, String)>,
}

static ST: Mutex<St> = Mutex::new(St { host: None, gen: 0, invites: Vec::new(), seen: Vec::new(), answer: None, guests: Vec::new(), joined: None });

fn st() -> std::sync::MutexGuard<'static, St> { ST.lock().unwrap_or_else(|e| e.into_inner()) }

fn dir(sh: &Shared) -> PathBuf { sh.paths.root.join("client").join("share") }

/// A signed-in Microsoft session (invites need one; offline sessions have no Nether sign-in).
fn session(sh: &Shared) -> Option<Session> {
    sh.session.lock().unwrap_or_else(|e| e.into_inner()).clone().filter(|s| s.user_type != "legacy")
}

// ---------------- the loop ----------------

/// Start serving the game's requests and polling invites (background thread, for the launcher's lifetime).
pub fn init(ui: &AppWindow, sh: Arc<Shared>) {
    wire(ui, &sh);
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let d = dir(&sh);
        let _ = std::fs::create_dir_all(&d);
        let _ = std::fs::remove_file(d.join("request.json"));
        let _ = std::fs::remove_file(d.join("state.json")); // a world shared by an earlier run is closed
        let mut last = String::new();
        let mut poll_at = Instant::now() + Duration::from_secs(8);
        loop {
            std::thread::sleep(Duration::from_millis(500));
            if let Ok(b) = std::fs::read(d.join("request.json")) {
                let _ = std::fs::remove_file(d.join("request.json"));
                if let Ok(r) = serde_json::from_slice::<Value>(&b) { request(&weak, &sh, &r); }
            }
            let games = running(&sh);
            if games.is_empty() {
                if st().host.is_some() { stop(&sh); }
                drop_idle_guests();
                p2p::cool();
            } else if st().host.is_none() {
                p2p::warm(); // like Essential: the link is ready before the player opens their world
            }
            if Instant::now() >= poll_at || CHECK_NOW.swap(false, std::sync::atomic::Ordering::SeqCst) {
                poll_at = Instant::now() + Duration::from_secs(if games.is_empty() { 30 } else { 15 });
                poll(&weak, &sh, &games);
            }
            let text = state_json().to_string();
            if text != last && (!games.is_empty() || !last.is_empty()) {
                if nether_core::store::write_atomic(&d.join("state.json"), text.as_bytes()).is_ok() { last = text; }
            }
        }
    });
}

/// Close the world links, the port and withdraw sent invites (launcher closing).
pub fn shutdown(sh: &Shared) {
    if st().host.is_some() { stop(sh); }
    let guests = std::mem::take(&mut st().guests);
    for g in guests { g.link.close(); }
}

/// (version, loader) of every running instance.
fn running(sh: &Shared) -> Vec<(String, Loader)> {
    let names: Vec<String> = sh.running.lock().unwrap_or_else(|e| e.into_inner()).keys().cloned().collect();
    if names.is_empty() { return vec![]; }
    let all = instances::load(&sh.paths).unwrap_or_default();
    all.into_iter().filter(|d| names.contains(&d.name)).map(|d| (d.version, Loader::from_label(&d.loader))).collect()
}

fn request(weak: &slint::Weak<AppWindow>, sh: &Arc<Shared>, r: &Value) {
    let seq = r["seq"].as_f64().unwrap_or(0.0);
    if let Some(o) = r.get("open").filter(|o| o.is_object()) {
        let port = o["port"].as_u64().filter(|p| (1024..=65535).contains(p)).unwrap_or(0) as u16;
        if port == 0 { return answer(seq, false, "Bad port"); }
        let s = |k: &str, max: usize| o[k].as_str().unwrap_or_default().chars().filter(|c| !c.is_control()).take(max).collect::<String>();
        open(sh, port, s("world", 64), s("ver", 32), s("loader", 16));
        answer(seq, true, "");
    } else if let Some(name) = r["invite"].as_str() {
        invite(sh, seq, name.trim().to_string(), r["uuid"].as_str().unwrap_or_default());
    } else if r["stop"].as_bool() == Some(true) {
        stop(sh);
        answer(seq, true, "Stopped sharing with friends");
    } else if let Some(id) = r["join"].as_i64() {
        let inv = st().invites.iter().find(|s| s.inv.id == id).map(|s| s.inv.clone());
        let Some(inv) = inv else { return answer(seq, false, "That invite has ended") };
        let sh = sh.clone();
        std::thread::spawn(move || match local_address(&inv.address) {
            Ok(a) => { st().joined = Some((id, a)); answer(seq, true, ""); dismiss(&sh, id); }
            Err(e) => answer(seq, false, &format!("Couldn't reach the world: {e:#}")),
        });
    } else if let Some(id) = r["show"].as_i64() {
        let shown = st().invites.iter().find(|s| s.inv.id == id).cloned();
        if let Some(s) = shown { let sh = sh.clone(); ui_do(weak, move |ui| { crate::prefs::show(ui); show(ui, &sh, s); }); }
        answer(seq, true, "");
    } else if let Some(id) = r["dismiss"].as_i64() {
        dismiss(sh, id);
        answer(seq, true, "");
    }
}

static CHECK_NOW: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// A friend says they just invited us: check for invites now instead of at the next poll.
pub fn check_now() { CHECK_NOW.store(true, std::sync::atomic::Ordering::SeqCst); }

fn answer(seq: f64, ok: bool, msg: &str) { st().answer = Some((seq, ok, msg.to_string())); }

// ---------------- hosting ----------------

fn open(sh: &Arc<Shared>, port: u16, world: String, ver: String, loader: String) {
    if st().host.as_ref().is_some_and(|h| h.port == port) { return; }
    if st().host.is_some() { stop(sh); }
    let lan = net::lan_ip().map(|ip| net::Invite { host: ip.to_string(), port }.address()).unwrap_or_default();
    let gen = {
        let mut s = st();
        s.gen += 1;
        let gen = s.gen;
        s.host = Some(Host { gen, port, world, ver, loader, phase: "starting", msg: String::new(), ticket: String::new(), public: String::new(), reach: "checking", lan, upnp: None, link: None, sent: vec![], queue: vec![] });
        gen
    };
    // friends with Nether: the peer-to-peer link (always works, nothing to set up)
    let sh2 = sh.clone();
    std::thread::spawn(move || {
        let r = p2p::Host::start(port);
        let mut s = st();
        let Some(h) = s.host.as_mut().filter(|h| h.gen == gen) else { drop(s); if let Ok(l) = r { l.close(); } return };
        let queued = std::mem::take(&mut h.queue);
        match r {
            Ok(l) => { h.ticket = l.ticket(); h.link = Some(l); h.phase = "online"; }
            Err(e) => {
                h.phase = "failed";
                h.msg = format!("{e:#}");
                for (n, u) in &queued { record(h, n, u, false, "Couldn't open your world to friends"); }
                return;
            }
        }
        drop(s);
        // invites picked while the link was starting go out now
        for (n, u) in queued { send(&sh2, None, n, u); }
    });
    // friends on plain Minecraft: a public address when the router opens the port
    std::thread::spawn(move || {
        let c = client();
        let up = Upnp::open(port, "Nether Client world");
        let ext = up.as_ref().map(|u| u.external_ip()).map_err(|e| format!("{e:#}"));
        match st().host.as_mut().filter(|h| h.gen == gen) {
            Some(h) => h.upnp = up.ok(),
            None => { if let Ok(mut u) = up { u.close(); } return; }
        }
        let r = share::reach(&c, port, ext);
        if let Some(h) = st().host.as_mut().filter(|h| h.gen == gen) {
            h.reach = if r.ok { "open" } else { "closed" };
            if r.ok { h.public = r.public; }
        }
    });
}

fn stop(sh: &Shared) {
    let h = st().host.take();
    let Some(mut h) = h else { return };
    if let Some(u) = h.upnp.as_mut() { u.close(); }
    if let Some(l) = h.link.take() { std::thread::spawn(move || l.close()); }
    if !h.sent.is_empty() {
        if let Some(s) = session(sh) { std::thread::spawn(move || { let _ = share::cancel_all(&client(), &s); }); }
    }
}

/// Invite a player by name (looked up) or, for a friend, by uuid. While the link is still starting the invite waits
/// for it, so the game can open the world and invite in one click.
fn invite(sh: &Arc<Shared>, seq: f64, name: String, uuid: &str) {
    let uuid = uuid.replace('-', "").to_lowercase();
    let uuid = if uuid.len() == 32 && uuid.chars().all(|c| c.is_ascii_hexdigit()) { uuid } else { String::new() };
    if session(sh).is_none() { return answer(seq, false, "Sign in to the launcher to send invites"); }
    {
        let mut s = st();
        let Some(h) = s.host.as_mut() else { drop(s); return answer(seq, false, "Open your world to friends first") };
        if h.phase == "failed" { let m = format!("Couldn't open your world to friends: {}", h.msg); drop(s); return answer(seq, false, &m); }
        if h.ticket.is_empty() {
            h.queue.retain(|(n, u)| !(n.eq_ignore_ascii_case(&name) || (!uuid.is_empty() && *u == uuid)));
            h.queue.push((name.clone(), uuid.clone()));
            record(h, &name, &uuid, true, "Invited: sent when your world is ready");
            drop(s);
            return answer(seq, true, &format!("Inviting {name}..."));
        }
    }
    send(sh, Some(seq), name, uuid);
}

/// Send one invite in the background (name looked up when there is no uuid); `seq` answers the game's request.
fn send(sh: &Arc<Shared>, seq: Option<f64>, name: String, uuid: String) {
    let Some(s) = session(sh) else { return };
    let Some((addr, world, ver, loader)) = host_info() else {
        if let Some(q) = seq { answer(q, false, "Open your world to friends first"); }
        return;
    };
    std::thread::spawn(move || {
        let c = client();
        let me = s.uuid.replace('-', "").to_lowercase();
        let who = if uuid.is_empty() { share::lookup(&c, &name) } else { Ok((uuid.clone(), name.clone())) };
        let r = who.and_then(|(u, canon)| {
            if u == me { anyhow::bail!("that's you"); }
            share::send(&c, &s, &u, &addr, &world, &ver, &loader)?;
            crate::party::poke_world(&u);
            Ok((u, canon))
        });
        let (ok, shown, u, msg) = match r {
            Ok((u, canon)) => (true, canon.clone(), u, format!("Invited {canon}")),
            Err(e) => (false, name.clone(), uuid.clone(), format!("{e:#}")),
        };
        let mut st = st();
        if let Some(h) = st.host.as_mut() { record(h, &shown, &u, ok, &msg); }
        if let Some(q) = seq { st.answer = Some((q, ok, msg)); }
    });
}

// ---------------- for the friends system (app/friends.rs) ----------------

/// The shared world's name while friends can be invited to it.
pub fn hosting() -> Option<String> { st().host.as_ref().filter(|h| !h.ticket.is_empty()).map(|h| h.world.clone()) }

/// The shared world for the party: (P2P ticket, world name, version, loader) once its link is up.
pub fn host_info() -> Option<(String, String, String, String)> {
    st().host.as_ref().filter(|h| !h.ticket.is_empty()).map(|h| (h.ticket.clone(), h.world.clone(), h.ver.clone(), h.loader.clone()))
}

/// Where this game connects for a party member's world ticket (a local P2P link). Blocking.
pub fn guest_address(ticket: &str) -> anyhow::Result<String> { local_address(ticket) }

/// Invite a player (uuid, no dashes) to the shared world. Blocking: call from a worker thread.
pub fn invite_uuid(sh: &Arc<Shared>, uuid: &str) -> anyhow::Result<()> {
    let (addr, world, ver, loader) = st().host.as_ref().filter(|h| !h.ticket.is_empty())
        .map(|h| (h.ticket.clone(), h.world.clone(), h.ver.clone(), h.loader.clone()))
        .ok_or_else(|| anyhow::anyhow!("open your world to friends first"))?;
    let s = session(sh).ok_or_else(|| anyhow::anyhow!("sign in to send invites"))?;
    let c = client();
    share::send(&c, &s, uuid, &addr, &world, &ver, &loader)?;
    crate::party::poke_world(uuid);
    let name = share::name_of(&c, uuid).unwrap_or_else(|| "Friend".into());
    if let Some(h) = st().host.as_mut() { record(h, &name, uuid, true, &format!("Invited {name}")); }
    Ok(())
}

/// Open the WORLD INVITE dialog for a friend's game (id 0 = not a stored invite; a plain host:port works too).
pub fn join_friend(ui: &AppWindow, sh: &Arc<Shared>, from_name: &str, inv: Invite) {
    show(ui, sh, Shown { inv, from: from_name.to_string(), here: false });
}

// ---------------- joining (guest side) ----------------

/// Where the game should connect for an invite address: a P2P invite gets a local link (made once, reused), a plain
/// address is used as it is. Blocking (binds the link); call off the UI thread.
fn local_address(addr: &str) -> anyhow::Result<String> {
    if !p2p::is_ticket(addr) { return Ok(addr.to_string()); }
    if let Some(g) = st().guests.iter_mut().find(|g| g.ticket == addr) { g.at = Instant::now(); return Ok(format!("127.0.0.1:{}", g.link.port())); }
    let link = p2p::Guest::start(addr)?;
    let port = link.port();
    st().guests.push(Guest { ticket: addr.to_string(), link, at: Instant::now() });
    Ok(format!("127.0.0.1:{port}"))
}

/// No game runs: links older than 15 minutes go (a launch that installs first keeps its link meanwhile).
fn drop_idle_guests() {
    let old: Vec<Guest> = {
        let mut s = st();
        let (old, keep) = std::mem::take(&mut s.guests).into_iter().partition(|g| g.at.elapsed() > Duration::from_secs(900));
        s.guests = keep;
        old
    };
    for g in old { std::thread::spawn(move || g.link.close()); }
}

// ---------------- incoming invites ----------------

fn poll(weak: &slint::Weak<AppWindow>, sh: &Arc<Shared>, games: &[(String, Loader)]) {
    let Some(s) = session(sh) else { return };
    let Ok(list) = share::incoming(&client(), &s) else { return };
    let c = client();
    let shown: Vec<Shown> = list.into_iter().map(|inv| {
        let from = share::name_of(&c, &inv.from).unwrap_or_else(|| "A friend".into());
        let here = games.iter().any(|(v, l)| *v == inv.ver && *l == Loader::from_label(&inv.loader));
        Shown { inv, from, here }
    }).collect();
    let fresh: Vec<Shown> = {
        let mut st = st();
        let fresh = shown.iter().filter(|x| !st.seen.contains(&x.inv.id)).cloned().collect();
        for x in &shown { if !st.seen.contains(&x.inv.id) { st.seen.push(x.inv.id); } }
        st.invites = shown;
        fresh
    };
    // a running game shows its own card; otherwise the launcher asks
    if let Some(first) = fresh.into_iter().next().filter(|_| games.is_empty()) {
        let sh = sh.clone();
        ui_do(weak, move |ui| show(ui, &sh, first));
    }
}

fn dismiss(sh: &Shared, id: i64) {
    if id <= 0 { return; } // a friend's game opened from the friends list, not a stored invite
    st().invites.retain(|x| x.inv.id != id);
    if let Some(s) = session(sh) { std::thread::spawn(move || { let _ = share::dismiss(&client(), &s, id); }); }
}

fn state_json() -> Value {
    let s = st();
    let host = s.host.as_ref().map(|h| json!({
        "port": h.port, "phase": h.phase, "msg": h.msg, "ready": !h.ticket.is_empty(), "public": h.public, "reach": h.reach, "lan": h.lan,
        "friends": h.link.as_ref().map_or(0, |l| l.peers()),
        "sent": h.sent.iter().map(|x| json!({ "name": x.name, "uuid": x.uuid, "ok": x.ok, "msg": x.msg })).collect::<Vec<_>>(),
    }));
    let invites: Vec<Value> = s.invites.iter().map(|x| json!({
        "id": x.inv.id, "from": x.from, "world": x.inv.world, "ver": x.inv.ver, "loader": x.inv.loader, "here": x.here,
    })).collect();
    let answer = s.answer.as_ref().map(|(q, ok, m)| json!({ "seq": q, "ok": ok, "msg": m }));
    let joined = s.joined.as_ref().map(|(id, a)| json!({ "id": id, "address": a }));
    json!({ "host": host, "invites": invites, "answer": answer, "joined": joined })
}

// ---------------- launcher dialog ----------------

/// Instances that can join: same version and loader (row indices into Backend.instances).
fn matches(ui: &AppWindow, inv: &Invite) -> Vec<(usize, SharedString)> {
    let want = Loader::from_label(&inv.loader);
    ui.global::<Backend>().get_instances().iter().enumerate()
        .filter(|(_, i)| i.ver.as_str() == inv.ver && Loader::from_label(&i.loader) == want).map(|(n, i)| (n, i.name)).collect()
}

thread_local! { static CUR: std::cell::RefCell<Option<Shown>> = const { std::cell::RefCell::new(None) }; }

fn show(ui: &AppWindow, _sh: &Arc<Shared>, s: Shown) {
    let w = ui.global::<WorldInvite>();
    let m = matches(ui, &s.inv);
    w.set_from(s.from.as_str().into());
    w.set_world(s.inv.world.as_str().into());
    w.set_game(format!("Minecraft {} · {}", s.inv.ver, Loader::from_label(&s.inv.loader).label()).into());
    w.set_plan(match m.len() { 0 => 2, 1 => 0, _ => 1 });
    w.set_insts(ModelRc::new(VecModel::from(m.into_iter().map(|(_, n)| n).collect::<Vec<_>>())));
    w.set_pick(0);
    w.set_open(true);
    CUR.with(|c| *c.borrow_mut() = Some(s));
}

fn close(ui: &AppWindow) {
    ui.global::<WorldInvite>().set_open(false);
    CUR.with(|c| *c.borrow_mut() = None);
}

fn join(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some(s) = CUR.with(|c| c.borrow().clone()) else { return };
    let w = ui.global::<WorldInvite>();
    let b = ui.global::<Backend>();
    let mut m = matches(ui, &s.inv);
    if m.is_empty() {
        // none: make one for this version and loader, then join with it
        let loader = Loader::from_label(&s.inv.loader);
        let idx = match loader { Loader::Fabric => 1, Loader::Forge => 2, Loader::NeoForge => 3, Loader::Quilt => 4, Loader::LegacyFabric => 5, Loader::Vanilla => 0 };
        let base = if loader == Loader::Vanilla { s.inv.ver.clone() } else { format!("{} {}", s.inv.ver, loader.label()) };
        let taken = |n: &str| b.get_instances().iter().any(|i| i.name.as_str().eq_ignore_ascii_case(n)) || sh.paths.instance(n).exists();
        let name = (1..50).map(|k| if k == 1 { base.clone() } else { format!("{base} ({k})") }).find(|n| !taken(n)).unwrap_or(base);
        if !b.invoke_create_instance(name.into(), s.inv.ver.as_str().into(), idx, 4, "".into(), "".into()) { return; }
        m = matches(ui, &s.inv);
    }
    let pick = (w.get_pick().max(0) as usize).min(m.len().saturating_sub(1));
    let Some(inst) = m.get(pick).and_then(|(row, _)| b.get_instances().row_data(*row)) else { return };
    // a running game (this instance or another) is handled by play_into: told to join, or closed first
    close(ui);
    // open the link first (off the UI thread), then start the game into it
    let (weak, sh2, addr, id, name) = (ui.as_weak(), sh.clone(), s.inv.address.clone(), s.inv.id, inst.name.to_string());
    std::thread::spawn(move || {
        let r = local_address(&addr);
        ui_do(&weak, move |ui| match r {
            Ok(a) => {
                let Some(inst) = ui.global::<Backend>().get_instances().iter().find(|i| i.name.as_str() == name) else { return };
                crate::instpage::play_into(ui, &sh2, &inst, QuickPlay::Server(a));
                dismiss(&sh2, id);
            }
            Err(e) => show_toast(ui, &sh2, &format!("Couldn't reach the world: {e:#}")),
        });
    });
}

fn wire(ui: &AppWindow, sh: &Arc<Shared>) {
    let w = ui.global::<WorldInvite>();
    let (weak, sh2) = (ui.as_weak(), sh.clone());
    w.on_join(move || if let Some(ui) = weak.upgrade() { join(&ui, &sh2); });
    let (weak, sh2) = (ui.as_weak(), sh.clone());
    w.on_decline(move || if let Some(ui) = weak.upgrade() {
        if let Some(s) = CUR.with(|c| c.borrow().clone()) { dismiss(&sh2, s.inv.id); }
        close(&ui);
    });
    let weak = ui.as_weak();
    w.on_later(move || if let Some(ui) = weak.upgrade() { close(&ui); });
}

/// One-click hosting: an invite picked while the link starts waits for it, then goes out (here the friends service
/// refuses the fake session, so it ends as failed: nothing real is sent).
/// `cargo test -p nether-app one_click_invite -- --ignored --nocapture`
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicU32};

    #[test]
    #[ignore]
    fn one_click_invite() {
        let sh = Arc::new(Shared {
            paths: nether_core::paths::Paths { root: std::env::temp_dir().join("nether-share-test") }, client_id: None,
            session: Mutex::new(Some(Session { name: "Alice".into(), uuid: "a".repeat(32), access_token: "x".into(), user_type: "msa".into() })),
            cancel: AtomicBool::new(false), toast_id: AtomicU32::new(0), running: Mutex::new(Default::default()), launching: Mutex::new(Default::default()), stopping: Mutex::new(Default::default()),
        });
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        open(&sh, port, "World".into(), "1.21.11".into(), "Fabric".into());
        invite(&sh, 1.0, "Bob".into(), &"b".repeat(32));
        {
            let s = st();
            let h = s.host.as_ref().unwrap();
            assert!(h.ticket.is_empty() && h.queue.len() == 1, "queued while starting");
            assert_eq!(s.answer.as_ref().map(|a| a.1), Some(true));
        }
        let end = Instant::now() + Duration::from_secs(60);
        loop {
            std::thread::sleep(Duration::from_millis(200));
            let s = st();
            let h = s.host.as_ref().unwrap();
            if let Some(x) = h.sent.first().filter(|x| x.msg != "Invited: sent when your world is ready") {
                println!("link {} -> invite sent: ok {} ({})", h.phase, x.ok, x.msg);
                assert!(!h.ticket.is_empty() && h.queue.is_empty() && x.uuid == "b".repeat(32));
                break;
            }
            assert!(Instant::now() < end, "queued invite never went out (phase {})", h.phase);
        }
        println!("{}", state_json()["host"]["sent"]);
        stop(&sh);
    }
}
