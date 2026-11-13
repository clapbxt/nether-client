//! Host page: standalone servers (nether_core::server, `<root>/servers/`), several running at once. The page shows
//! one selected server: console, players, add-ons (Paper plugins / Fabric mods), settings, world, invite. New servers
//! come from the setup wizard. All disk and network work runs on worker threads; a UI timer polls running servers and
//! exists only while one does (fast on the Host page, 1 s elsewhere so the bottom deck stays current).
use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime};

use nether_core::download::client;
use nether_core::net::{self, Invite};
use nether_core::server::{self, Entry, Meta, Server, ServerOpts, Settings, Status, UpnpState};
use nether_core::tunnel::{self, Tunnel, TunnelState};
use nether_core::modrinth;
use slint::{ComponentHandle, Model, ModelRc, SharedString, VecModel};

use crate::mods::{cached_icon, count, icon_image, icon_px, update_rows};
use crate::{job, show_toast, ui_do, AppWindow, ConLine, HostBackup, HostPlayer, HostServer, HostState, Row, Shared};

const DIFFICULTY: [&str; 4] = ["peaceful", "easy", "normal", "hard"];
const GAMEMODE: [&str; 4] = ["survival", "creative", "adventure", "spectator"];
const CONSOLE_MAX: usize = 600;

struct Live { server: Server, name: String, started: Instant }

thread_local! {
    static LIVE: RefCell<HashMap<PathBuf, Live>> = RefCell::new(HashMap::new());
    static BUSY: RefCell<HashSet<PathBuf>> = RefCell::new(HashSet::new());
    // stop pressed while the server was still being prepared: stop it as soon as it spawns
    static CANCEL: RefCell<HashSet<PathBuf>> = RefCell::new(HashSet::new());
    static LIST: RefCell<Vec<Entry>> = const { RefCell::new(Vec::new()) };
    static SAVES: RefCell<Vec<(String, String)>> = const { RefCell::new(Vec::new()) };
    static SEL: RefCell<Option<PathBuf>> = const { RefCell::new(None) };
    static TIMER: slint::Timer = slint::Timer::default();
    static FAST: Cell<bool> = const { Cell::new(false) };
    static CONSOLE: Rc<VecModel<ConLine>> = Rc::new(VecModel::default());
    static HIST: RefCell<(Vec<String>, usize)> = const { RefCell::new((Vec::new(), 0)) };
    static SAVE_GEN: Cell<u32> = const { Cell::new(0) };
    static SEARCH_GEN: Cell<u32> = const { Cell::new(0) };
    static EULA: Cell<bool> = const { Cell::new(false) };
    static LAN_IP: RefCell<String> = const { RefCell::new(String::new()) };
    static LAST_UPNP: RefCell<Option<UpnpState>> = const { RefCell::new(None) };
    static LAST_TUN: RefCell<Option<TunnelState>> = const { RefCell::new(None) };
    // outside check per running server; one playit.gg agent for all of them
    static REACH: RefCell<HashMap<PathBuf, Reach>> = RefCell::new(HashMap::new());
    static TUNNEL: RefCell<Option<Tunnel>> = const { RefCell::new(None) };
    static PAPER: Cell<bool> = const { Cell::new(false) };
    static SHARED: RefCell<Option<Arc<Shared>>> = const { RefCell::new(None) };
    // dev snapshot fixture: keep the page as fed, never reload
    static FIXTURE: Cell<bool> = const { Cell::new(false) };
}

fn kind_label(k: &str) -> &'static str { match k { "fabric" => "FABRIC", "paper" => "PAPER", _ => "VANILLA" } }

fn sel_dir() -> Option<PathBuf> { SEL.with(|s| s.borrow().clone()) }

/// The selected server.
fn selected() -> Option<Entry> {
    let d = sel_dir()?;
    LIST.with(|l| l.borrow().iter().find(|e| e.dir == d).cloned())
}

fn live_of(dir: &Path) -> Option<Server> { LIVE.with(|l| l.borrow().get(dir).map(|l| l.server.clone())) }
fn live_sel() -> Option<Server> { sel_dir().and_then(|d| live_of(&d)) }

fn set_busy(ui: &AppWindow, dir: &Path, on: bool) {
    BUSY.with(|b| { let mut b = b.borrow_mut(); if on { b.insert(dir.to_path_buf()); } else { b.remove(dir); } });
    if sel_dir().as_deref() == Some(dir) { ui.global::<HostState>().set_busy(on); }
}

fn fmt_size(b: u64) -> String {
    if b >= 1 << 30 { format!("{:.1} GB", b as f64 / (1u64 << 30) as f64) } else if b >= 1 << 20 { format!("{:.1} MB", b as f64 / (1u64 << 20) as f64) } else { format!("{} KB", (b / 1024).max(1)) }
}

fn ago(t: SystemTime) -> String {
    let s = SystemTime::now().duration_since(t).map(|d| d.as_secs()).unwrap_or(0);
    match s {
        0..=59 => "Just now".into(),
        60..=3599 => format!("{} min ago", s / 60),
        3600..=86_399 => format!("{} h ago", s / 3600),
        _ if s < 2 * 86_400 => "Yesterday".into(),
        _ => format!("{} days ago", s / 86_400),
    }
}

fn uptime(d: Duration) -> String {
    let s = d.as_secs();
    if s >= 3600 { format!("{}:{:02}:{:02}", s / 3600, s % 3600 / 60, s % 60) } else { format!("{:02}:{:02}", s / 60, s % 60) }
}

fn status_code(s: Status) -> i32 {
    match s { Status::Stopped => 0, Status::Starting => 1, Status::Running => 2, Status::Stopping => 3, Status::Crashed => 4 }
}

/// `[10:42:01] [Server thread/INFO]: msg` -> `10:42:01  msg`, with a kind (1 = warning / error).
fn con_line(raw: &str) -> ConLine {
    let warn = raw.contains("/WARN]") || raw.contains("/ERROR]") || raw.contains("Exception");
    let t = match (raw.strip_prefix('['), raw.find("]: ")) {
        (Some(rest), Some(i)) => match rest.find(']') { Some(j) if j < 12 => format!("{}  {}", &rest[..j], &raw[i + 3..]), _ => raw.to_string() },
        _ => raw.to_string(),
    };
    ConLine { t: t.into(), k: if warn { 1 } else { 0 } }
}

fn push_console(ui: &AppWindow, lines: Vec<ConLine>) {
    if lines.is_empty() { return; }
    CONSOLE.with(|m| {
        for l in lines { m.push(l); }
        let extra = m.row_count().saturating_sub(CONSOLE_MAX);
        if extra > 0 { let keep: Vec<ConLine> = m.iter().skip(extra).collect(); m.set_vec(keep); }
    });
    let h = ui.global::<HostState>();
    h.set_console_rev(h.get_console_rev() + 1);
}

fn strings(v: Vec<String>) -> ModelRc<SharedString> { ModelRc::new(VecModel::from(v.into_iter().map(SharedString::from).collect::<Vec<_>>())) }

fn server_row(e: &Entry) -> HostServer {
    let live = live_of(&e.dir);
    HostServer {
        name: e.meta.name.as_str().into(),
        meta: format!("{} · {}", e.meta.mc, kind_label(&e.meta.kind)).into(),
        status: live.as_ref().map(|s| status_code(s.status())).unwrap_or(0),
        players: live.map(|s| s.players().len() as i32).unwrap_or(0),
    }
}

fn phase_note(m: &Meta) -> String {
    match m.kind.as_str() {
        "paper" => format!("Downloads the latest Paper build for Minecraft {}. Add plugins on the Plugins tab.", m.mc),
        "fabric" => format!("Downloads Minecraft {} with the Fabric server launcher. Add mods on the Mods tab.", m.mc),
        _ => format!("Downloads the official Minecraft {} server into this server's folder.", m.mc),
    }
}

// ---------------- loading ----------------

/// Re-read every server and every instance world, then keep (or pick) a selection.
fn refresh_list(ui: &AppWindow, sh: &Arc<Shared>, want: Option<PathBuf>) {
    if FIXTURE.with(|f| f.get()) { return; }
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let list = server::list(&sh.paths);
        let saves = server::list_saves(&sh.paths);
        let lan = net::lan_ip().map(|i| i.to_string()).unwrap_or_default();
        ui_do(&weak, move |ui| {
            let h = ui.global::<HostState>();
            LAN_IP.with(|l| *l.borrow_mut() = lan);
            h.set_saves(ModelRc::new(VecModel::from(saves.iter().map(|(i, w)| Row { name: w.as_str().into(), sub: i.as_str().into(), ..Default::default() }).collect::<Vec<_>>())));
            SAVES.with(|s| *s.borrow_mut() = saves);
            h.set_servers(ModelRc::new(VecModel::from(list.iter().map(server_row).collect::<Vec<_>>())));
            let cur = want.or_else(sel_dir);
            let idx = cur.and_then(|d| list.iter().position(|e| e.dir == d))
                .or_else(|| list.iter().position(|e| live_of(&e.dir).is_some()))
                .or((!list.is_empty()).then_some(0));
            LIST.with(|l| *l.borrow_mut() = list);
            match idx {
                Some(i) => select(ui, &sh, i as i32),
                None => { SEL.with(|s| *s.borrow_mut() = None); h.set_idx(-1); h.set_live(false); }
            }
        });
    });
}

/// Everything the page shows for one server, read on a worker thread.
struct Snapshot {
    installed: bool,
    eula: bool,
    settings: Settings,
    whitelist: Vec<String>,
    ops: Vec<String>,
    backups: Vec<(String, String, String)>,
    addons: Vec<(String, String)>,
}

fn read_snapshot(e: &Entry) -> Snapshot {
    let dir = &e.dir;
    let backups = server::list_backups(dir).into_iter().map(|p| {
        let meta = std::fs::metadata(&p).ok();
        (p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default(),
         meta.as_ref().map(|m| fmt_size(m.len())).unwrap_or_default(),
         meta.and_then(|m| m.modified().ok()).map(ago).unwrap_or_default())
    }).collect();
    Snapshot {
        installed: server::is_installed(dir, &e.meta.mc, &e.meta.kind),
        eula: server::eula_accepted(dir),
        settings: server::read_settings(dir).unwrap_or_default(),
        whitelist: server::whitelist(dir).unwrap_or_default().into_iter().map(|p| p.name).collect(),
        ops: server::ops(dir).unwrap_or_default().into_iter().map(|p| p.name).collect(),
        backups,
        addons: server::addons(dir, &e.meta.kind).iter().map(|p| (
            p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default(),
            std::fs::metadata(p).map(|m| fmt_size(m.len())).unwrap_or_default(),
        )).collect(),
    }
}

/// Reload the selected server's state (lists, settings) in the background.
fn reload(ui: &AppWindow) {
    if FIXTURE.with(|f| f.get()) { return; }
    let Some(e) = selected() else { return };
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let s = read_snapshot(&e);
        ui_do(&weak, move |ui| {
            if sel_dir().as_ref() != Some(&e.dir) || FIXTURE.with(|f| f.get()) { return; } // switched meanwhile
            let h = ui.global::<HostState>();
            EULA.with(|x| x.set(s.eula));
            if live_of(&e.dir).is_none() {
                h.set_phase(if s.installed { 3 } else { 2 });
                h.set_phase_note(phase_note(&e.meta).into());
            }
            let st = &s.settings;
            h.set_port(st.port.to_string().into());
            h.set_motd(st.motd.as_str().into());
            h.set_max_players(st.max_players.to_string().into());
            h.set_difficulty(DIFFICULTY.iter().position(|d| *d == st.difficulty).or_else(|| st.difficulty.parse().ok()).unwrap_or(1) as i32);
            h.set_gamemode(GAMEMODE.iter().position(|d| *d == st.gamemode).or_else(|| st.gamemode.parse().ok()).unwrap_or(0) as i32);
            h.set_pvp(st.pvp);
            h.set_online(st.online_mode);
            h.set_wl_on(st.white_list);
            h.set_ram(e.meta.ram_gb.clamp(1, 16) as i32);
            h.set_upnp(e.meta.upnp);
            h.set_whitelist(strings(s.whitelist));
            h.set_ops(strings(s.ops));
            h.set_backups(ModelRc::new(VecModel::from(s.backups.into_iter().map(|(n, sz, d)| HostBackup { name: n.into(), size: sz.into(), date: d.into() }).collect::<Vec<_>>())));
            h.set_addons(ModelRc::new(VecModel::from(s.addons.into_iter().map(|(n, sz)| Row { id: n.as_str().into(), name: n.trim_end_matches(".jar").into(), meta: sz.into(), ..Default::default() }).collect::<Vec<_>>())));
            share(ui);
            mark_players(ui);
        });
    });
}

/// Pick the server the page shows (index into the server list).
fn select(ui: &AppWindow, sh: &Arc<Shared>, idx: i32) {
    if FIXTURE.with(|f| f.get()) { return; }
    let Some(e) = LIST.with(|l| l.borrow().get(idx.max(0) as usize).cloned()) else { return };
    let h = ui.global::<HostState>();
    let changed = sel_dir().as_ref() != Some(&e.dir);
    SEL.with(|s| *s.borrow_mut() = Some(e.dir.clone()));
    h.set_idx(idx);
    h.set_inst_name(e.meta.name.as_str().into());
    h.set_inst_meta(format!("{}  ·  {}", e.meta.mc, kind_label(&e.meta.kind)).into());
    h.set_kind(e.meta.kind.as_str().into());
    h.set_busy(BUSY.with(|b| b.borrow().contains(&e.dir)));
    let live = LIVE.with(|l| l.borrow().get(&e.dir).map(|l| (l.server.clone(), l.started)));
    h.set_live(live.is_some());
    if changed {
        LAST_UPNP.with(|u| *u.borrow_mut() = None);
        h.set_hits(ModelRc::default());
        match &live {
            Some((s, started)) => {
                s.drain_console();
                let hist: Vec<ConLine> = s.console_history().iter().rev().take(CONSOLE_MAX).rev().map(|l| con_line(l)).collect();
                CONSOLE.with(|m| m.set_vec(hist));
                h.set_console_rev(h.get_console_rev() + 1);
                h.set_status(status_code(s.status()));
                h.set_uptime(if s.status() == Status::Running { uptime(started.elapsed()) } else { "—".into() }.into());
                h.set_phase(3);
            }
            None => {
                CONSOLE.with(|m| m.set_vec(vec![]));
                h.set_status(0);
                h.set_uptime("—".into());
                h.set_phase(0);
            }
        }
        h.set_players(ModelRc::default());
        if h.get_tab() == 2 && e.meta.kind != "vanilla" { addon_search(ui, sh, ""); }
    }
    reload(ui);
}

/// Mark online players who are operators / whitelisted.
fn mark_players(ui: &AppWindow) {
    let h = ui.global::<HostState>();
    let names = live_sel().map(|s| s.players()).unwrap_or_default();
    let ops: Vec<String> = h.get_ops().iter().map(|s| s.to_lowercase()).collect();
    let wl: Vec<String> = h.get_whitelist().iter().map(|s| s.to_lowercase()).collect();
    let list: Vec<HostPlayer> = names.iter().map(|n| HostPlayer { name: n.into(), op: ops.contains(&n.to_lowercase()), wl: wl.contains(&n.to_lowercase()) }).collect();
    let cur = h.get_players();
    if cur.row_count() == list.len() && cur.iter().zip(list.iter()).all(|(a, b)| a == *b) { return; }
    h.set_players(ModelRc::new(VecModel::from(list)));
}

/// Addresses, UPnP state, outside check, tunnel and the invite of the selected server.
fn share(ui: &AppWindow) {
    let h = ui.global::<HostState>();
    let live = live_sel();
    let port: u16 = live.as_ref().map(|s| s.port()).or_else(|| h.get_port().trim().parse().ok()).unwrap_or(25565);
    let lan_ip = LAN_IP.with(|l| l.borrow().clone());
    let lan = if lan_ip.is_empty() { String::new() } else { Invite { host: lan_ip.clone(), port }.address() };
    h.set_lan(lan.as_str().into());
    let state = live.as_ref().map(|s| s.upnp());
    let (code, msg) = match &state {
        None => (if h.get_upnp() { 0 } else { 4 }, String::new()),
        Some(UpnpState::Off) => (4, String::new()),
        Some(UpnpState::Pending) => (1, String::new()),
        Some(UpnpState::Open { .. }) => (2, String::new()),
        Some(UpnpState::Failed(e)) => (3, format!("Your router didn't open the port: {e}.")),
    };
    h.set_upnp_state(code);
    h.set_upnp_msg(msg.into());
    let reach = live.as_ref().and(sel_dir()).and_then(|d| REACH.with(|r| r.borrow().get(&d).cloned())).unwrap_or_default();
    h.set_reach(reach.code);
    h.set_reach_msg(reach.msg.as_str().into());
    h.set_pub_addr(reach.public.as_str().into());
    let tun = live.as_ref().and_then(|_| TUNNEL.with(|t| t.borrow().clone())).filter(|t| t.wants(port)).map(|t| t.state(port));
    let (tc, tmsg, turl, taddr) = match &tun {
        None => (0, "", "", ""),
        Some(TunnelState::Starting(m)) => (1, m.as_str(), "", ""),
        Some(TunnelState::Claim(u)) => (2, "", u.as_str(), ""),
        Some(TunnelState::NoTunnel(r, u)) => (3, r.as_str(), u.as_str(), ""),
        Some(TunnelState::Online(a)) => (4, "", "", a.as_str()),
        Some(TunnelState::Failed(e)) => (5, e.as_str(), "", ""),
    };
    h.set_tun(tc);
    h.set_tun_msg(tmsg.into());
    h.set_tun_url(turl.into());
    h.set_tun_addr(taddr.into());
    // only an address that is known to work for friends elsewhere beats the LAN one
    let addr = if live.is_none() { String::new() } else if tc == 4 { taddr.to_string() } else if reach.code == 2 { reach.public.clone() } else { lan };
    h.set_addr(addr.into());
    LAST_UPNP.with(|u| *u.borrow_mut() = state);
    LAST_TUN.with(|u| *u.borrow_mut() = tun);
}

/// Result of the outside check for one running server.
#[derive(Clone, Default)]
struct Reach { code: i32, msg: String, public: String }

/// Look up the public address and ask an outside service whether the server answers there (worker thread).
fn reach_of(port: u16, upnp: &UpnpState) -> Reach {
    let c = client();
    if let UpnpState::Open { external_ip: None } = upnp {
        return Reach { code: 3, msg: "Blocked from the internet: your router has no public address of its own (your provider shares one, CGNAT, or a second router sits in front).".into(), public: String::new() };
    }
    let known = match upnp { UpnpState::Open { external_ip: Some(ip) } => ip.parse().ok(), _ => None };
    let ip = match known.map(Ok).unwrap_or_else(|| net::echo_ip(&c)) {
        Ok(ip) => ip,
        Err(e) => return Reach { code: 4, msg: format!("Couldn't look up your public address ({e:#}). Friends on your network can join; the free tunnel works for everyone else."), public: String::new() },
    };
    let public = Invite { host: ip.to_string(), port }.address();
    match net::reachable(&c, &ip.to_string(), port) {
        Ok(true) => Reach { code: 2, msg: String::new(), public },
        Ok(false) => Reach { code: 3, public, msg: match upnp {
            UpnpState::Open { .. } => "Blocked from the internet even with the port open: likely Windows Firewall, a second router or your provider.",
            UpnpState::Failed(_) => "Blocked from the internet: your router refused UPnP and no port forward reaches this PC.",
            _ => "Blocked from the internet: UPnP is off and no port forward reaches this PC.",
        }.into() },
        Err(e) => Reach { code: 4, msg: format!("Couldn't check from outside ({e:#}). Friends may still reach {public}; the free tunnel always works."), public },
    }
}

fn check_reach(ui: &AppWindow, dir: PathBuf, port: u16, upnp: UpnpState) {
    REACH.with(|r| r.borrow_mut().insert(dir.clone(), Reach { code: 1, ..Default::default() }));
    share(ui);
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let r = reach_of(port, &upnp);
        ui_do(&weak, move |ui| {
            if live_of(&dir).is_none() { return; }
            REACH.with(|m| m.borrow_mut().insert(dir.clone(), r));
            if sel_dir().as_ref() == Some(&dir) { share(ui); }
        });
    });
}

/// START FREE TUNNEL (0) / STOP TUNNEL (1) for the selected running server.
fn tunnel_action(ui: &AppWindow, sh: &Arc<Shared>, action: i32) {
    if action != 0 { stop_tunnel(); share(ui); return; }
    let Some(server) = live_sel() else { return };
    if server::read_settings(server.dir()).map(|s| !s.online_mode).unwrap_or(false) {
        show_toast(ui, sh, "Online-mode is off, so this server can't go on the internet: anyone could join as any player. Turn it on in Settings first");
        return;
    }
    match TUNNEL.with(|t| t.borrow().clone()).filter(|t| t.alive()) {
        Some(t) => t.want(server.port()),
        None => {
            let t = tunnel::start(client(), &sh.paths, server.port());
            TUNNEL.with(|x| *x.borrow_mut() = Some(t));
        }
    }
    share(ui);
}

fn stop_tunnel() {
    if let Some(t) = TUNNEL.with(|t| t.borrow_mut().take()) { std::thread::spawn(move || t.stop()); }
}

// ---------------- polling ----------------

fn arm_timer(ui: &AppWindow) {
    let fast = ui.get_tab() == 4;
    FAST.with(|f| f.set(fast));
    let weak = ui.as_weak();
    TIMER.with(|t| t.start(slint::TimerMode::Repeated, Duration::from_millis(if fast { 250 } else { 1000 }), move || {
        if let Some(ui) = weak.upgrade() { poll(&ui); }
    }));
}

fn poll(ui: &AppWindow) {
    let lives: Vec<(PathBuf, Server, String, Instant)> = LIVE.with(|l| l.borrow().iter().map(|(d, l)| (d.clone(), l.server.clone(), l.name.clone(), l.started)).collect());
    if lives.is_empty() { TIMER.with(|t| t.stop()); stop_tunnel(); return; }
    if FAST.with(|f| f.get()) != (ui.get_tab() == 4) { arm_timer(ui); }
    let h = ui.global::<HostState>();
    let sel = sel_dir();
    let rows = h.get_servers();
    let (mut online, mut players) = (0, 0);
    let mut single = String::new();
    for (dir, server, name, started) in lives {
        let is_sel = sel.as_ref() == Some(&dir);
        let lines = server.drain_console();
        if is_sel { push_console(ui, lines.iter().map(|l| con_line(l)).collect()); }
        let st = server.status();
        let n = server.players().len();
        if let Some(i) = LIST.with(|l| l.borrow().iter().position(|e| e.dir == dir)) {
            if let Some(mut r) = rows.row_data(i) {
                if r.status != status_code(st) || r.players != n as i32 { r.status = status_code(st); r.players = n as i32; rows.set_row_data(i, r); }
            }
        }
        if is_sel {
            if h.get_status() != status_code(st) { h.set_status(status_code(st)); }
            mark_players(ui);
            let up = if st == Status::Running { uptime(started.elapsed()) } else { "—".into() };
            if h.get_uptime() != up.as_str() { h.set_uptime(up.into()); }
            let upnp = server.upnp();
            if st == Status::Running && upnp != UpnpState::Pending && REACH.with(|r| !r.borrow().contains_key(&dir)) { check_reach(ui, dir.clone(), server.port(), upnp.clone()); }
            let tun = TUNNEL.with(|t| t.borrow().clone()).filter(|t| t.wants(server.port())).map(|t| t.state(server.port()));
            if LAST_UPNP.with(|u| u.borrow().as_ref() != Some(&upnp)) || LAST_TUN.with(|u| *u.borrow() != tun) { share(ui); }
        }
        if matches!(st, Status::Stopped | Status::Crashed) {
            LIVE.with(|l| l.borrow_mut().remove(&dir));
            REACH.with(|r| r.borrow_mut().remove(&dir));
            if is_sel {
                h.set_live(false);
                h.set_busy(false);
                h.set_exit_code(server.exit_code().unwrap_or(-1));
                h.set_uptime("—".into());
                mark_players(ui);
                share(ui);
            }
            BUSY.with(|b| b.borrow_mut().remove(&dir));
            if let Some(sh) = SHARED.with(|s| s.borrow().clone()) {
                if st == Status::Crashed {
                    show_toast(ui, &sh, &format!("The {name} server crashed (exit code {}). The console shows why.", server.exit_code().map(|c| c.to_string()).unwrap_or_else(|| "unknown".into())));
                } else {
                    show_toast(ui, &sh, &format!("{name} server stopped"));
                }
                if is_sel { reload(ui); }
            }
            continue;
        }
        online += 1;
        players += n;
        single = format!("{name} server {}", match st {
            Status::Starting => "starting…".to_string(),
            Status::Stopping => "stopping…".to_string(),
            _ => format!("online · {n} {}", if n == 1 { "player" } else { "players" }),
        });
    }
    let deck = if online > 1 { format!("{online} servers online · {players} {}", if players == 1 { "player" } else { "players" }) } else { single };
    if h.get_running() != online { h.set_running(online); }
    if h.get_deck_text() != deck.as_str() { h.set_deck_text(deck.into()); }
    if online == 0 && LIVE.with(|l| l.borrow().is_empty()) { TIMER.with(|t| t.stop()); stop_tunnel(); }
}

// ---------------- actions ----------------

fn progress(weak: &slint::Weak<AppWindow>, label: String) -> nether_core::Progress {
    let weak = weak.clone();
    let last = std::sync::Mutex::new((String::new(), -1i32));
    Arc::new(move |msg: &str, f: f32| {
        let pct = (f * 100.0) as i32;
        if let Ok(mut l) = last.lock() { if l.0 == msg && l.1 == pct { return; } *l = (msg.to_string(), pct); }
        let (text, key) = (format!("{label} · {msg}"), label.clone());
        ui_do(&weak, move |ui| job(ui, &key, &text, f));
    })
}

fn setup(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some(e) = selected() else { return };
    set_busy(ui, &e.dir, true);
    job(ui, &format!("{} server", e.meta.name), &format!("{} server · Preparing", e.meta.name), 0.0);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let r = server::install(&client(), &sh.paths, &e.dir, &e.meta.mc, &e.meta.kind, &progress(&weak, format!("{} server", e.meta.name)));
        ui_do(&weak, move |ui| {
            crate::job_end(ui, &format!("{} server", e.meta.name));
            set_busy(ui, &e.dir, false);
            match r {
                Ok(_) => { show_toast(ui, &sh, "Server set up. Start it when you're ready"); reload(ui); }
                Err(err) => show_toast(ui, &sh, &format!("Couldn't set up the server: {err:#}")),
            }
        });
    });
}

fn start(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some(e) = selected() else { return };
    if !EULA.with(|x| x.get()) { ui.global::<HostState>().set_eula_open(true); return; }
    let h = ui.global::<HostState>();
    set_busy(ui, &e.dir, true);
    h.set_status(1);
    CONSOLE.with(|m| m.set_vec(vec![]));
    job(ui, &format!("{} server", e.meta.name), &format!("{} server · Starting", e.meta.name), -1.0);
    let opts = ServerOpts { dir: e.dir.clone(), version: e.meta.mc.clone(), kind: e.meta.kind.clone(), ram_mb: (h.get_ram().clamp(1, 16) * 1024) as u32, port: 0, java_major: None, upnp: h.get_upnp() };
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let r = server::start(&client(), &sh.paths, &opts, progress(&weak, format!("{} server", e.meta.name)));
        ui_do(&weak, move |ui| {
            let h = ui.global::<HostState>();
            set_busy(ui, &e.dir, false);
            crate::job_end(ui, &format!("{} server", e.meta.name));
            let is_sel = sel_dir().as_ref() == Some(&e.dir);
            let cancel = CANCEL.with(|c| c.borrow_mut().remove(&e.dir));
            match r {
                Ok(server) if cancel => {
                    if is_sel { h.set_status(3); }
                    let (weak, dir) = (ui.as_weak(), e.dir.clone());
                    std::thread::spawn(move || {
                        let _ = server.stop(Duration::from_secs(30));
                        ui_do(&weak, move |ui| if sel_dir().as_ref() == Some(&dir) { ui.global::<HostState>().set_status(0); });
                    });
                }
                Ok(server) => {
                    LIVE.with(|l| l.borrow_mut().insert(e.dir.clone(), Live { server, name: e.meta.name.clone(), started: Instant::now() }));
                    if is_sel { h.set_live(true); h.set_phase(3); share(ui); }
                    h.set_running(h.get_running() + 1);
                    h.set_deck_text(format!("{} server starting…", e.meta.name).into());
                    arm_timer(ui);
                }
                Err(err) if server::is_eula_error(&err) => { if is_sel { h.set_status(0); EULA.with(|x| x.set(false)); h.set_eula_open(true); } }
                Err(_) if cancel => { if is_sel { h.set_status(0); } }
                Err(err) => { if is_sel { h.set_status(0); } show_toast(ui, &sh, &format!("Couldn't start {}: {err:#}", e.meta.name)); }
            }
        });
    });
}

fn stop(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some(server) = live_sel() else { return };
    ui.global::<HostState>().set_status(3);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        if let Err(e) = server.stop(Duration::from_secs(30)) {
            ui_do(&weak, move |ui| show_toast(ui, &sh, &format!("Couldn't stop the server cleanly: {e:#}")));
        }
    });
}

/// Run a console command on the selected live server and echo it.
fn command(ui: &AppWindow, sh: &Arc<Shared>, cmd: &str) -> bool {
    let Some(server) = live_sel() else { show_toast(ui, sh, "Start the server first"); return false };
    match server.send(cmd) {
        Ok(()) => { push_console(ui, vec![ConLine { t: format!("> {}", cmd.trim().trim_start_matches('/')).into(), k: 2 }]); true }
        Err(e) => { show_toast(ui, sh, &format!("Couldn't send the command: {e:#}")); false }
    }
}

/// After a command that edits ops / whitelist, re-read the lists once the server has written them.
fn reload_soon(ui: &AppWindow) {
    let weak = ui.as_weak();
    slint::Timer::single_shot(Duration::from_millis(1500), move || if let Some(ui) = weak.upgrade() { reload(&ui) });
}

fn list_edit(ui: &AppWindow, sh: &Arc<Shared>, kind: i32, name: &str, add: bool) {
    let name = name.trim().to_string();
    if name.is_empty() { return; }
    if live_sel().is_some() {
        let cmd = match (kind, add) { (0, true) => "whitelist add", (0, false) => "whitelist remove", (_, true) => "op", (_, false) => "deop" };
        if command(ui, sh, &format!("{cmd} {name}")) { reload_soon(ui); }
        return;
    }
    let Some(e) = selected() else { return };
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let dir = &e.dir;
        let r = match (kind, add) {
            (0, true) => server::whitelist_add(&client(), dir, &name).map(|_| ()),
            (0, false) => server::whitelist_remove(dir, &name).map(|_| ()),
            (_, true) => server::ops_add(&client(), dir, &name, 4).map(|_| ()),
            (_, false) => server::ops_remove(dir, &name).map(|_| ()),
        };
        ui_do(&weak, move |ui| match r {
            Ok(()) => reload(ui),
            Err(err) => show_toast(ui, &sh, &format!("Couldn't change the list: {err:#}")),
        });
    });
}

/// Write settings half a second after the last edit (fields save as you type).
fn save_settings(ui: &AppWindow, sh: &Arc<Shared>) {
    let gen = SAVE_GEN.with(|g| { g.set(g.get() + 1); g.get() });
    let (weak, sh) = (ui.as_weak(), sh.clone());
    slint::Timer::single_shot(Duration::from_millis(500), move || {
        if SAVE_GEN.with(|g| g.get()) != gen { return; }
        let Some(ui) = weak.upgrade() else { return };
        let Some(e) = selected() else { return };
        let h = ui.global::<HostState>();
        let Some(port) = h.get_port().trim().parse::<u16>().ok().filter(|p| *p > 0) else { show_toast(&ui, &sh, "Couldn't save: the port must be a number from 1 to 65535"); return };
        let Some(max) = h.get_max_players().trim().parse::<u32>().ok().filter(|m| (1..=1000).contains(m)) else { show_toast(&ui, &sh, "Couldn't save: max players must be a number from 1 to 1000"); return };
        let s = Settings {
            port, max_players: max, motd: h.get_motd().to_string(), online_mode: h.get_online(), white_list: h.get_wl_on(), pvp: h.get_pvp(),
            difficulty: DIFFICULTY[h.get_difficulty().clamp(0, 3) as usize].into(), gamemode: GAMEMODE[h.get_gamemode().clamp(0, 3) as usize].into(),
        };
        let meta = Meta { ram_gb: h.get_ram().clamp(1, 16) as u32, upnp: h.get_upnp(), ..e.meta.clone() };
        LIST.with(|l| if let Some(x) = l.borrow_mut().iter_mut().find(|x| x.dir == e.dir) { x.meta = meta.clone(); });
        if live_sel().is_none() { share(&ui); }
        std::thread::spawn(move || {
            let r = server::write_settings(&e.dir, &s).and_then(|_| server::write_meta(&e.dir, &meta));
            if let Err(err) = r { ui_do(&weak, move |ui| show_toast(ui, &sh, &format!("Couldn't save the server settings: {err:#}"))); }
        });
    });
}

fn import_save(ui: &AppWindow, sh: &Arc<Shared>, i: i32) {
    if live_sel().is_some() { show_toast(ui, sh, "Stop the server before swapping its world"); return; }
    let Some(e) = selected() else { return };
    let Some((inst, world)) = SAVES.with(|s| s.borrow().get(i.max(0) as usize).cloned()) else { return };
    set_busy(ui, &e.dir, true);
    job(ui, "host:copy", &format!("Copying {world} into {}…", e.meta.name), -1.0);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let r = server::import_save(&sh.paths, &e.dir, &inst, &world);
        ui_do(&weak, move |ui| {
            set_busy(ui, &e.dir, false);
            crate::job_end(ui, "host:copy");
            match r {
                Ok(_) => { show_toast(ui, &sh, &format!("{world} copied in as the server world")); reload(ui); }
                Err(err) => show_toast(ui, &sh, &format!("Couldn't copy the world: {err:#}")),
            }
        });
    });
}

fn backup(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some(e) = selected() else { return };
    set_busy(ui, &e.dir, true);
    job(ui, "host:backup", "Backing up the server world…", -1.0);
    let live = live_of(&e.dir);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let r = match live { Some(s) => s.backup(), None => server::backup_world(&e.dir) };
        ui_do(&weak, move |ui| {
            set_busy(ui, &e.dir, false);
            crate::job_end(ui, "host:backup");
            match r {
                Ok(p) => { show_toast(ui, &sh, &format!("Backup saved: {}", p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default())); reload(ui); }
                Err(err) => show_toast(ui, &sh, &format!("Couldn't back up: {err:#}")),
            }
        });
    });
}

// ---------------- add-ons: Paper plugins / Fabric server mods (Modrinth) ----------------

fn addon_search(ui: &AppWindow, sh: &Arc<Shared>, q: &str) {
    let Some(e) = selected() else { return };
    if server::addon_dir(&e.dir, &e.meta.kind).is_none() { return; }
    let gen = SEARCH_GEN.with(|g| { g.set(g.get() + 1); g.get() });
    let h = ui.global::<HostState>();
    h.set_hits_loading(true);
    let (weak, sh, q) = (ui.as_weak(), sh.clone(), q.trim().to_string());
    std::thread::spawn(move || {
        let r = modrinth::search_server(&client(), &q, &e.meta.mc, &e.meta.kind);
        let hits = r.as_ref().map(|v| v.iter().map(|h| (h.id.clone(), h.title.clone(), h.desc.clone(), count(h.downloads), h.icon_url.clone())).collect::<Vec<_>>()).unwrap_or_default();
        let icons: Vec<(String, String)> = hits.iter().filter(|h| !h.4.is_empty()).map(|h| (h.0.clone(), h.4.clone())).collect();
        let failed = r.err().map(|e| format!("{e:#}"));
        let sh2 = sh.clone();
        ui_do(&weak, move |ui| {
            if SEARCH_GEN.with(|g| g.get()) != gen { return; }
            let h = ui.global::<HostState>();
            h.set_hits_loading(false);
            if let Some(f) = failed { show_toast(ui, &sh2, &format!("Couldn't search Modrinth: {f}")); }
            let rows: Vec<Row> = hits.iter().map(|(id, t, d, n, icon)| {
                let img = cached_icon(icon);
                Row { id: id.as_str().into(), name: t.as_str().into(), sub: d.as_str().into(), meta: n.as_str().into(), has_icon: img.is_some(), icon: img.unwrap_or_default(), ..Default::default() }
            }).collect();
            h.set_hits(ModelRc::new(VecModel::from(rows)));
        });
        for (id, url) in icons {
            let (weak, sh) = (weak.clone(), sh.clone());
            std::thread::spawn(move || {
                let Some(px) = icon_px(&sh, &url) else { return };
                ui_do(&weak, move |ui| {
                    let img = cached_icon(&url).unwrap_or_else(|| icon_image(&url, &px));
                    update_rows(&ui.global::<HostState>().get_hits(), &id, &|r| { r.icon = img.clone(); r.has_icon = true; });
                });
            });
        }
    });
}

fn addon_install(ui: &AppWindow, sh: &Arc<Shared>, id: &str) {
    let Some(e) = selected() else { return };
    let Some(dir) = server::addon_dir(&e.dir, &e.meta.kind) else { return };
    let h = ui.global::<HostState>();
    let title = h.get_hits().iter().find(|r| r.id == id).map(|r| r.name.to_string()).unwrap_or_else(|| id.to_string());
    update_rows(&h.get_hits(), id, &|r| r.busy = true);
    let key = format!("addon:{id}");
    job(ui, &key, &format!("Installing {title}"), 0.0);
    let (weak, sh, id) = (ui.as_weak(), sh.clone(), id.to_string());
    std::thread::spawn(move || {
        let w2 = weak.clone();
        let t2 = title.clone();
        let prog = move |file: &str, done: u64, total: u64| {
            let (f, t) = (file.to_string(), t2.clone());
            let frac = if total > 0 { done as f32 / total as f32 } else { -1.0 };
            let k = format!("addon:{}", t);
            ui_do(&w2, move |ui| job(ui, &k, &format!("Installing {t} · {f}"), frac));
        };
        let r = modrinth::install(&client(), &id, None, &e.meta.mc, Some(e.meta.kind.as_str()), &dir, &prog);
        ui_do(&weak, move |ui| {
            crate::job_end(ui, &key);
            crate::job_end(ui, &format!("addon:{title}"));
            update_rows(&ui.global::<HostState>().get_hits(), &id, &|r| r.busy = false);
            let live = live_of(&e.dir).is_some();
            match r {
                Ok(files) if files.is_empty() => show_toast(ui, &sh, &format!("{title} is already installed")),
                Ok(_) => show_toast(ui, &sh, &if live { format!("{title} installed. Restart the server to load it") } else { format!("{title} installed") }),
                Err(err) => show_toast(ui, &sh, &format!("Couldn't install {title}: {err:#}")),
            }
            reload(ui);
        });
    });
}

fn addon_remove(ui: &AppWindow, sh: &Arc<Shared>, file: &str) {
    let Some(e) = selected() else { return };
    if live_of(&e.dir).is_some() { show_toast(ui, sh, "Stop the server before removing add-ons"); return; }
    let Some(dir) = server::addon_dir(&e.dir, &e.meta.kind) else { return };
    if file.is_empty() || file.contains(['/', '\\']) { return; }
    match std::fs::remove_file(dir.join(file)) {
        Ok(()) => { show_toast(ui, sh, &format!("Removed {}", file.trim_end_matches(".jar"))); reload(ui); }
        Err(err) => show_toast(ui, sh, &format!("Couldn't remove {file}: {err}")),
    }
}

// ---------------- setup wizard ----------------

/// Open the wizard: free port, a fresh name, Paper's version list (once).
fn new_server(ui: &AppWindow, sh: &Arc<Shared>) {
    let h = ui.global::<HostState>();
    let names: Vec<String> = LIST.with(|l| l.borrow().iter().map(|e| e.meta.name.to_lowercase()).collect());
    let name = (1..).map(|n| if n == 1 { "My Server".to_string() } else { format!("My Server {n}") }).find(|n| !names.contains(&n.to_lowercase())).unwrap_or_default();
    h.set_wiz_name(name.into());
    h.set_wiz_open(true);
    let (weak, sh2) = (ui.as_weak(), sh.clone());
    let paper = !PAPER.with(|p| p.replace(true));
    std::thread::spawn(move || {
        let port = server::next_port(&sh2.paths);
        let vers = if paper { server::paper_versions(&client()).ok() } else { None };
        ui_do(&weak, move |ui| {
            let h = ui.global::<HostState>();
            h.set_wiz_port(port.to_string().into());
            match vers {
                Some(v) => h.set_paper_versions(strings(v)),
                None if paper => PAPER.with(|p| p.set(false)), // try again next time
                None => {}
            }
        });
    });
}

#[allow(clippy::too_many_arguments)]
fn create(ui: &AppWindow, sh: &Arc<Shared>, name: &str, kind: i32, ver: &str, ram: i32, port: &str, world: i32, wl: bool, online: bool, upnp: bool) {
    let name = name.trim().to_string();
    if name.is_empty() { show_toast(ui, sh, "Give the server a name"); return; }
    if ver.is_empty() { show_toast(ui, sh, "Pick a Minecraft version"); return; }
    let Some(port) = port.trim().parse::<u16>().ok().filter(|p| *p > 0) else { show_toast(ui, sh, "The port must be a number from 1 to 65535"); return };
    let h = ui.global::<HostState>();
    h.set_wiz_open(false);
    let kind = server::KINDS[kind.clamp(0, 2) as usize].to_string();
    let meta = Meta { name: name.clone(), mc: ver.to_string(), kind, ram_gb: ram.clamp(1, 16) as u32, upnp };
    let settings = Settings { port, white_list: wl, online_mode: online, ..Settings::default() };
    let src = (world >= 0).then(|| SAVES.with(|s| s.borrow().get(world as usize).cloned())).flatten();
    job(ui, &format!("{name} server"), &format!("{name} server · Preparing"), 0.0);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let c = client();
        let r = (|| -> anyhow::Result<PathBuf> {
            let dir = server::create(&sh.paths, &meta, &settings)?;
            // the wizard's last step is the user's own "I agree" to the EULA; CREATE is disabled without it
            server::accept_eula(&dir)?;
            Ok(dir)
        })();
        let dir = match r {
            Ok(d) => d,
            Err(e) => { ui_do(&weak, move |ui| { crate::job_end(ui, &format!("{} server", meta.name)); show_toast(ui, &sh, &format!("Couldn't create the server: {e:#}")); }); return; }
        };
        let mut warn = None;
        if let Some((inst, w)) = &src {
            if let Err(e) = server::import_save(&sh.paths, &dir, inst, w) { warn = Some(format!("Couldn't copy {w}: {e:#}")); }
        }
        let inst = server::install(&c, &sh.paths, &dir, &meta.mc, &meta.kind, &progress(&weak, format!("{} server", meta.name)));
        ui_do(&weak, move |ui| {
            crate::job_end(ui, &format!("{} server", meta.name));
            match (inst, warn) {
                (Err(e), _) => show_toast(ui, &sh, &format!("Server created, but the download failed: {e:#}. Try SET UP SERVER again")),
                (Ok(_), Some(w)) => show_toast(ui, &sh, &w),
                (Ok(_), None) => show_toast(ui, &sh, &format!("{} is ready. Start it when you're ready", meta.name)),
            }
            refresh_list(ui, &sh, Some(dir));
        });
    });
}

fn delete_server(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some(e) = selected() else { return };
    if live_of(&e.dir).is_some() { show_toast(ui, sh, "Stop the server before deleting it"); return; }
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let r = server::delete(&sh.paths, &e.dir);
        ui_do(&weak, move |ui| match r {
            Ok(()) => { SEL.with(|s| *s.borrow_mut() = None); show_toast(ui, &sh, &format!("{} deleted", e.meta.name)); refresh_list(ui, &sh, None); }
            Err(err) => show_toast(ui, &sh, &format!("Couldn't delete {}: {err:#}", e.meta.name)),
        });
    });
}

fn open_dir(dir: PathBuf) {
    std::thread::spawn(move || { let _ = std::fs::create_dir_all(&dir); let _ = std::process::Command::new("explorer").arg(&dir).spawn(); });
}

// ---------------- wiring ----------------

pub fn init(ui: &AppWindow, sh: Arc<Shared>) {
    SHARED.with(|s| *s.borrow_mut() = Some(sh.clone()));
    let h = ui.global::<HostState>();
    CONSOLE.with(|m| h.set_console(ModelRc::from(m.clone())));
    macro_rules! on {
        ($set:ident, |$ui:ident, $sh:ident $(, $a:ident : $t:ty)*| $body:expr) => {{
            let (weak, sh) = (ui.as_weak(), sh.clone());
            h.$set(move |$($a),*| { let Some($ui) = weak.upgrade() else { return Default::default() }; let $sh = &sh; let _ = $sh; $body });
        }};
    }
    on!(on_opened, |ui, sh| refresh_list(&ui, sh, None));
    on!(on_select, |ui, sh, i: i32| select(&ui, sh, i));
    on!(on_primary, |ui, sh| match ui.global::<HostState>().get_phase() {
        2 => setup(&ui, sh),
        3 if live_sel().is_some() => stop(&ui, sh),
        3 if ui.global::<HostState>().get_busy() => if let Some(d) = sel_dir() {
            CANCEL.with(|c| c.borrow_mut().insert(d));
            ui.global::<HostState>().set_status(3);
        },
        3 => start(&ui, sh),
        _ => {}
    });
    // the only path to accept_eula besides the wizard's agree step: the user's click on I AGREE in the EULA dialog
    on!(on_accept_eula, |ui, sh| {
        let Some(e) = selected() else { return };
        ui.global::<HostState>().set_eula_open(false);
        let (weak, sh) = (ui.as_weak(), sh.clone());
        std::thread::spawn(move || {
            let r = server::accept_eula(&e.dir);
            ui_do(&weak, move |ui| match r {
                Ok(()) => { EULA.with(|x| x.set(true)); start(ui, &sh); }
                Err(err) => show_toast(ui, &sh, &format!("Couldn't save the EULA answer: {err:#}")),
            });
        });
    });
    on!(on_send, |ui, sh, cmd: SharedString| {
        let cmd = cmd.trim().to_string();
        if cmd.is_empty() { return; }
        HIST.with(|h| { let mut h = h.borrow_mut(); if h.0.last() != Some(&cmd) { h.0.push(cmd.clone()); }
            if h.0.len() > 100 { h.0.remove(0); } h.1 = h.0.len(); });
        command(&ui, sh, &cmd);
    });
    h.on_history(|dir| HIST.with(|h| {
        let mut h = h.borrow_mut();
        let n = h.0.len();
        h.1 = if dir < 0 { h.1.saturating_sub(1) } else { (h.1 + 1).min(n) };
        h.0.get(h.1).cloned().unwrap_or_default().into()
    }));
    on!(on_player, |ui, sh, name: SharedString, action: i32| {
        let cmd = match action { 0 => "kick", 1 => "op", 2 => "deop", _ => "whitelist add" };
        if command(&ui, sh, &format!("{cmd} {name}")) && action > 0 { reload_soon(&ui); }
    });
    on!(on_list_add, |ui, sh, k: i32, n: SharedString| list_edit(&ui, sh, k, &n, true));
    on!(on_list_remove, |ui, sh, k: i32, n: SharedString| list_edit(&ui, sh, k, &n, false));
    on!(on_save_settings, |ui, sh| if !FIXTURE.with(|f| f.get()) { save_settings(&ui, sh) });
    on!(on_import_save, |ui, sh, i: i32| import_save(&ui, sh, i));
    on!(on_backup, |ui, sh| backup(&ui, sh));
    h.on_open_backups(|| if let Some(e) = selected() { open_dir(e.dir.join("backups")) });
    h.on_open_folder(|| if let Some(e) = selected() { open_dir(e.dir) });
    on!(on_new_server, |ui, sh| new_server(&ui, sh));
    on!(on_create, |ui, sh, name: SharedString, kind: i32, ver: SharedString, ram: i32, port: SharedString, world: i32, wl: bool, online: bool, upnp: bool| create(&ui, sh, &name, kind, &ver, ram, &port, world, wl, online, upnp));
    on!(on_delete_server, |ui, sh| delete_server(&ui, sh));
    on!(on_tunnel, |ui, sh, a: i32| tunnel_action(&ui, sh, a));
    on!(on_recheck, |ui, sh| if let Some(d) = sel_dir() { REACH.with(|r| r.borrow_mut().remove(&d)); share(&ui); });
    on!(on_addon_search, |ui, sh, q: SharedString| addon_search(&ui, sh, &q));
    on!(on_addon_install, |ui, sh, id: SharedString| addon_install(&ui, sh, &id));
    on!(on_addon_remove, |ui, sh, f: SharedString| addon_remove(&ui, sh, &f));
}

/// On quit: stop every running server cleanly (in parallel, bounded wait, then `Server::stop` kills it).
pub fn shutdown() {
    let all: Vec<Server> = LIVE.with(|l| l.borrow().values().map(|l| l.server.clone()).collect());
    let hs: Vec<_> = all.into_iter().map(|s| std::thread::spawn(move || { let _ = s.stop(Duration::from_secs(25)); })).collect();
    for h in hs { let _ = h.join(); }
    if let Some(t) = TUNNEL.with(|t| t.borrow_mut().take()) { t.stop(); }
}

/// Dev snapshots only (`--snapshot` with NC_SNAP_STATE): "host-eula" opens the EULA dialog (nothing is accepted),
/// "host-wizard" opens the setup wizard, "host-running" feeds a fixture of two servers, one running (no process).
pub fn snap(ui: &AppWindow, state: &str) {
    let h = ui.global::<HostState>();
    if let Some(t) = std::env::var("NC_SNAP_TAB").ok().and_then(|t| t.parse().ok()) { h.set_tab(t); }
    match state {
        "host-eula" => h.set_eula_open(true),
        "host-wizard" => { FIXTURE.with(|f| f.set(true)); h.set_wiz_name("My Server".into()); h.set_wiz_port("25566".into()); h.set_wiz_open(true); }
        "host-running" => {
            FIXTURE.with(|f| f.set(true));
            h.set_servers(ModelRc::new(VecModel::from(vec![
                HostServer { name: "Friends SMP".into(), meta: "1.21.11 · FABRIC".into(), status: 2, players: 3 },
                HostServer { name: "Plugin Lab".into(), meta: "1.21.11 · PAPER".into(), status: 2, players: 0 },
                HostServer { name: "Old Survival".into(), meta: "1.20.1 · VANILLA".into(), status: 0, players: 0 },
            ])));
            h.set_idx(0);
            h.set_inst_name("Friends SMP".into());
            h.set_inst_meta("1.21.11  ·  FABRIC".into());
            h.set_kind("fabric".into());
            h.set_phase(3);
            h.set_status(2);
            h.set_live(true);
            h.set_running(2);
            h.set_uptime("1:12:08".into());
            h.set_deck_text("2 servers online · 3 players".into());
            let lines = [
                "[16:01:58] [Server thread/INFO]: Starting minecraft server version 1.21.11",
                "[16:01:58] [Server thread/INFO]: Loading properties",
                "[16:01:59] [Server thread/INFO]: Preparing level \"world\"",
                "[16:02:04] [Server thread/INFO]: Done (5.912s)! For help, type \"help\"",
                "[16:02:31] [Server thread/INFO]: Steve joined the game",
                "[16:03:10] [Server thread/INFO]: Alex joined the game",
                "[16:03:12] [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 2034ms or 40 ticks behind",
                "[16:04:47] [Server thread/INFO]: Mika joined the game",
                "[16:09:02] [Server thread/INFO]: <Mika> portal is ready",
                "[16:12:40] [Server thread/INFO]: Saving the game (this may take a moment!)",
                "[16:12:40] [Server thread/INFO]: Saved the game",
            ];
            let mut v: Vec<ConLine> = lines.iter().map(|l| con_line(l)).collect();
            v.insert(8, ConLine { t: "> say welcome back".into(), k: 2 });
            CONSOLE.with(|m| m.set_vec(v));
            h.set_players(ModelRc::new(VecModel::from(vec![
                HostPlayer { name: "Alex".into(), op: false, wl: true },
                HostPlayer { name: "Mika".into(), op: false, wl: false },
                HostPlayer { name: "Steve".into(), op: true, wl: true },
            ])));
            h.set_whitelist(strings(vec!["Alex".into(), "Steve".into()]));
            h.set_ops(strings(vec!["Steve".into()]));
            h.set_saves(ModelRc::new(VecModel::from(vec![
                Row { name: "New World".into(), sub: "Fabric 1.21.11".into(), ..Default::default() },
                Row { name: "Survival 2".into(), sub: "Vanilla 1.20.1".into(), ..Default::default() },
            ])));
            h.set_addons(ModelRc::new(VecModel::from(vec![
                Row { id: "fabric-api.jar".into(), name: "fabric-api-0.141.0+1.21.11".into(), meta: "2.3 MB".into(), ..Default::default() },
                Row { id: "lithium.jar".into(), name: "lithium-fabric-0.21.0+mc1.21.11".into(), meta: "740 KB".into(), ..Default::default() },
            ])));
            h.set_hits(ModelRc::new(VecModel::from(vec![
                Row { id: "a".into(), name: "Chunky".into(), sub: "Pre-generates chunks, quickly and efficiently".into(), meta: "4.1M".into(), ..Default::default() },
                Row { id: "b".into(), name: "spark".into(), sub: "A performance profiler for Minecraft clients, servers and proxies".into(), meta: "9.8M".into(), ..Default::default() },
                Row { id: "c".into(), name: "FerriteCore".into(), sub: "Memory usage optimizations".into(), meta: "62M".into(), ..Default::default() },
            ])));
            h.set_backups(ModelRc::new(VecModel::from(vec![HostBackup { name: "world-20261110-150212.zip".into(), size: "38.4 MB".into(), date: "1 h ago".into() }])));
            h.set_max_players("20".into());
            h.set_motd("Friends SMP · season 3".into());
            h.set_lan("192.168.1.24".into());
            // the common case this page exists for: home network blocked from outside, tunnel carrying friends in
            h.set_pub_addr("203.0.113.7".into());
            h.set_upnp_state(3);
            h.set_reach(3);
            h.set_reach_msg("Blocked from the internet: your router refused UPnP and no port forward reaches this PC.".into());
            h.set_tun(4);
            h.set_tun_addr("lumen-ridge.gl.joinmc.link".into());
            let inv = Invite { host: "lumen-ridge.gl.joinmc.link".into(), port: 25565 };
            h.set_addr(inv.address().into());
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn console_lines() {
        let l = con_line("[16:02:31] [Server thread/INFO]: Steve joined the game");
        assert_eq!(l.t.as_str(), "16:02:31  Steve joined the game");
        assert_eq!(l.k, 0);
        assert_eq!(con_line("[16:03:12] [Server thread/WARN]: Can't keep up!").k, 1);
        assert_eq!(con_line("plain output").t.as_str(), "plain output");
    }

    #[test]
    fn formatting() {
        assert_eq!(uptime(Duration::from_secs(75)), "01:15");
        assert_eq!(uptime(Duration::from_secs(4328)), "1:12:08");
        assert_eq!(fmt_size(3 * 1024 * 1024), "3.0 MB");
    }
}
