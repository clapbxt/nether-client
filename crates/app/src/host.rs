//! Host page: one local server per profile (nether_core::server), its console, players, settings, worlds and the
//! address to share. All disk and network work runs on worker threads; a UI timer polls the running server and exists
//! only while a server process does (fast on the Host page, 1 s elsewhere so the bottom deck stays current).
//! Friends join by address (Direct Connect); an outside check (`net::reachable`) says whether the internet can reach it.
use std::cell::{Cell, RefCell};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime};

use nether_core::download::client;
use nether_core::launch::Loader;
use nether_core::net::{self, Invite};

use nether_core::server::{self, Server, ServerOpts, Settings, Status, UpnpState};
use slint::{ComponentHandle, Model, ModelRc, SharedString, VecModel};

use crate::{job, job_end, show_toast, ui_do, AppWindow, Backend, ConLine, HostBackup, HostPlayer, HostState, Shared};

const DIFFICULTY: [&str; 4] = ["peaceful", "easy", "normal", "hard"];
const GAMEMODE: [&str; 4] = ["survival", "creative", "adventure", "spectator"];
const CONSOLE_MAX: usize = 600;
/// bottom-deck job key for setup / start / world copy / backup
const JOB: &str = "host";

struct Live { server: Server, inst: String, started: Instant }

thread_local! {
    static LIVE: RefCell<Option<Live>> = const { RefCell::new(None) };
    static TIMER: slint::Timer = slint::Timer::default();
    static FAST: Cell<bool> = const { Cell::new(false) };
    static CONSOLE: Rc<VecModel<ConLine>> = Rc::new(VecModel::default());
    static HIST: RefCell<(Vec<String>, usize)> = const { RefCell::new((Vec::new(), 0)) };
    static SAVE_GEN: Cell<u32> = const { Cell::new(0) };
    static EULA: Cell<bool> = const { Cell::new(false) };
    static LAN_IP: RefCell<String> = const { RefCell::new(String::new()) };
    static LAST_UPNP: RefCell<Option<UpnpState>> = const { RefCell::new(None) };
    static SEL: RefCell<String> = const { RefCell::new(String::new()) };
    // dev snapshot fixture: keep the page as fed, never reload
    static FIXTURE: Cell<bool> = const { Cell::new(false) };
}

/// The selected profile: (name, version, loader label). Found by name, so a profile added or removed while the page
/// is open (the list shifts) never points a server action at the wrong folder; the picker index follows along.
fn selected(ui: &AppWindow) -> Option<(String, String, String)> {
    let name = SEL.with(|n| n.borrow().clone());
    if name.is_empty() { return None; }
    let insts = ui.global::<Backend>().get_instances();
    let (n, i) = insts.iter().enumerate().find(|(_, i)| i.name == name.as_str())?;
    let h = ui.global::<HostState>();
    if h.get_idx() != n as i32 { h.set_idx(n as i32); }
    Some((i.name.to_string(), i.ver.to_string(), i.loader.to_string()))
}

/// Loaders a server can be set up for (the server engine installs vanilla and Fabric servers).
fn hostable(l: Loader) -> bool { matches!(l, Loader::Vanilla | Loader::Fabric) }

fn live_server() -> Option<Server> { LIVE.with(|l| l.borrow().as_ref().map(|l| l.server.clone())) }

fn host_file(dir: &Path) -> PathBuf { dir.join("nether-host.json") }

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

// ---------------- loading ----------------

/// Everything the page shows for one instance, read on a worker thread.
struct Snapshot {
    installed: bool,
    eula: bool,
    settings: Settings,
    whitelist: Vec<String>,
    ops: Vec<String>,
    saves: Vec<String>,
    backups: Vec<HostBackupRaw>,
    ram: i32,
    upnp: bool,
    lan: String,
}
struct HostBackupRaw { name: String, size: String, date: String }

fn read_snapshot(sh: &Shared, name: &str, ver: &str) -> Snapshot {
    let dir = server::server_dir(&sh.paths, name);
    let installed = std::fs::read(dir.join("nether-server.json")).ok()
        .and_then(|b| serde_json::from_slice::<server::Installed>(&b).ok())
        .map(|m| m.mc == ver && dir.join(&m.jar).exists()).unwrap_or(false);
    let prefs: serde_json::Value = std::fs::read(host_file(&dir)).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
    let backups = server::list_backups(&dir).into_iter().map(|p| {
        let meta = std::fs::metadata(&p).ok();
        HostBackupRaw {
            name: p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default(),
            size: meta.as_ref().map(|m| fmt_size(m.len())).unwrap_or_default(),
            date: meta.and_then(|m| m.modified().ok()).map(ago).unwrap_or_default(),
        }
    }).collect();
    Snapshot {
        installed,
        eula: server::eula_accepted(&dir),
        settings: server::read_settings(&dir).unwrap_or_default(),
        whitelist: server::whitelist(&dir).unwrap_or_default().into_iter().map(|p| p.name).collect(),
        ops: server::ops(&dir).unwrap_or_default().into_iter().map(|p| p.name).collect(),
        saves: server::list_saves(&sh.paths, name),
        backups,
        ram: prefs["ram_gb"].as_i64().map(|r| r.clamp(1, 16) as i32).unwrap_or(2),
        upnp: prefs["upnp"].as_bool().unwrap_or(true),
        lan: net::lan_ip().map(|i| i.to_string()).unwrap_or_default(),
    }
}

/// Reload the selected instance's server state (lists, settings) in the background.
fn reload(ui: &AppWindow, sh: &Arc<Shared>) {
    if FIXTURE.with(|f| f.get()) { return; }
    let Some((name, ver, loader)) = selected(ui) else { return };
    if !hostable(Loader::from_label(&loader)) { return; }
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let s = read_snapshot(&sh, &name, &ver);
        ui_do(&weak, move |ui| {
            if SEL.with(|n| *n.borrow() != name) || FIXTURE.with(|f| f.get()) { return; } // switched meanwhile
            let h = ui.global::<HostState>();
            EULA.with(|e| e.set(s.eula));
            if live_server().is_none() {
                h.set_phase(if s.installed { 3 } else { 2 });
                h.set_phase_note(format!("Downloads the official Minecraft {ver} server{} into this profile's folder. Your worlds and mods stay as they are.", if Loader::from_label(&loader) == Loader::Fabric { " with Fabric" } else { "" }).into());
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
            h.set_ram(s.ram);
            h.set_upnp(s.upnp);
            h.set_whitelist(strings(s.whitelist));
            h.set_ops(strings(s.ops));
            h.set_saves(strings(s.saves));
            h.set_backups(ModelRc::new(VecModel::from(s.backups.into_iter().map(|b| HostBackup { name: b.name.into(), size: b.size.into(), date: b.date.into() }).collect::<Vec<_>>())));
            LAN_IP.with(|l| *l.borrow_mut() = s.lan);
            share(ui);
            mark_players(ui);
        });
    });
}

/// Pick the instance the page shows.
fn select(ui: &AppWindow, sh: &Arc<Shared>, idx: i32) {
    if FIXTURE.with(|f| f.get()) { return; }
    let h = ui.global::<HostState>();
    if live_server().is_some() && idx != h.get_idx() { show_toast(ui, sh, "Stop the server before switching profiles"); return; }
    let Some(i) = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) else { return };
    let changed = SEL.with(|n| *n.borrow() != i.name.as_str());
    SEL.with(|n| *n.borrow_mut() = i.name.to_string());
    h.set_idx(idx);
    h.set_inst_name(i.name.clone());
    h.set_inst_meta(format!("{}  ·  {}", i.ver, i.loader.to_uppercase()).into());
    let loader = Loader::from_label(&i.loader);
    if live_server().is_none() {
        if changed { h.set_status(0); h.set_uptime("—".into()); CONSOLE.with(|m| m.set_vec(vec![])); }
        if !hostable(loader) {
            h.set_phase(1);
            h.set_phase_note(format!("Hosting {} profiles is not supported yet. Vanilla and Fabric profiles can run a server.", loader.label()).into());
            return;
        }
        if changed { h.set_phase(0); }
    }
    reload(ui, sh);
}

/// Mark online players who are operators / whitelisted.
fn mark_players(ui: &AppWindow) {
    let h = ui.global::<HostState>();
    let names = live_server().map(|s| s.players()).unwrap_or_default();
    let ops: Vec<String> = h.get_ops().iter().map(|s| s.to_lowercase()).collect();
    let wl: Vec<String> = h.get_whitelist().iter().map(|s| s.to_lowercase()).collect();
    let list: Vec<HostPlayer> = names.iter().map(|n| HostPlayer { name: n.into(), op: ops.contains(&n.to_lowercase()), wl: wl.contains(&n.to_lowercase()) }).collect();
    let cur = h.get_players();
    if cur.row_count() == list.len() && cur.iter().zip(list.iter()).all(|(a, b)| a == *b) { return; }
    h.set_players(ModelRc::new(VecModel::from(list)));
}

/// Addresses, UPnP state and the invite code.
fn share(ui: &AppWindow) {
    let h = ui.global::<HostState>();
    let port: u16 = live_server().map(|s| s.port()).or_else(|| h.get_port().trim().parse().ok()).unwrap_or(25565);
    let lan_ip = LAN_IP.with(|l| l.borrow().clone());
    let lan = (!lan_ip.is_empty()).then(|| Invite { host: lan_ip.clone(), port });
    h.set_lan(lan.as_ref().map(|i| i.address()).unwrap_or_default().into());
    let state = live_server().map(|s| s.upnp());
    let mut public = None;
    let (code, msg) = match &state {
        None => (if h.get_upnp() { 0 } else { 4 }, String::new()),
        Some(UpnpState::Off) => (4, String::new()),
        Some(UpnpState::Pending) => (1, String::new()),
        Some(UpnpState::Open { external_ip: Some(ip) }) => { public = Some(Invite { host: ip.clone(), port }); (2, String::new()) }
        Some(UpnpState::Open { external_ip: None }) => (2, "The port is open on your router, but the router has no public address (another router or your provider sits in front of it).".into()),
        Some(UpnpState::Failed(e)) => (3, format!("Your router didn't open the port: {e}.")),
    };
    h.set_upnp_state(code);
    h.set_upnp_msg(msg.into());
    h.set_pub_addr(public.as_ref().map(|i| i.address()).unwrap_or_default().into());
    let share = match &state { Some(_) => public.as_ref().or(lan.as_ref()).map(|i| i.address()).unwrap_or_default(), None => String::new() };
    if h.get_share_addr() != share.as_str() { h.set_share_addr(share.into()); h.set_reach(0); }
    LAST_UPNP.with(|u| *u.borrow_mut() = state);
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
    let Some((server, inst, started)) = LIVE.with(|l| l.borrow().as_ref().map(|l| (l.server.clone(), l.inst.clone(), l.started))) else {
        TIMER.with(|t| t.stop());
        return;
    };
    if FAST.with(|f| f.get()) != (ui.get_tab() == 4) { arm_timer(ui); }
    let h = ui.global::<HostState>();
    push_console(ui, server.drain_console().iter().map(|l| con_line(l)).collect());
    let st = server.status();
    if h.get_status() != status_code(st) { h.set_status(status_code(st)); }
    mark_players(ui);
    let up = if st == Status::Running { uptime(started.elapsed()) } else { "—".into() };
    if h.get_uptime() != up.as_str() { h.set_uptime(up.into()); }
    if LAST_UPNP.with(|u| u.borrow().as_ref() != Some(&server.upnp())) { share(ui); }
    let n = h.get_players().row_count();
    let deck = format!("{inst} server {}", match st {
        Status::Starting => "starting…".to_string(),
        Status::Stopping => "stopping…".to_string(),
        _ => format!("online · {n} {}", if n == 1 { "player" } else { "players" }),
    });
    if h.get_deck_text() != deck.as_str() { h.set_deck_text(deck.into()); }

    if matches!(st, Status::Stopped | Status::Crashed) {
        LIVE.with(|l| *l.borrow_mut() = None);
        TIMER.with(|t| t.stop());
        h.set_live(false);
        h.set_busy(false);
        h.set_exit_code(server.exit_code().unwrap_or(-1));
        h.set_uptime("—".into());
        mark_players(ui);
        share(ui);
        if let Some(sh) = SHARED.with(|s| s.borrow().clone()) {
            if st == Status::Crashed {
                show_toast(ui, &sh, &format!("The {inst} server crashed (exit code {}). The console shows why.", server.exit_code().map(|c| c.to_string()).unwrap_or_else(|| "unknown".into())));
            } else {
                show_toast(ui, &sh, &format!("{inst} server stopped"));
            }
            reload(ui, &sh);
        }
    }
}

thread_local! {
    static SHARED: RefCell<Option<Arc<Shared>>> = const { RefCell::new(None) };
}

// ---------------- actions ----------------

fn progress(weak: &slint::Weak<AppWindow>, label: String) -> nether_core::Progress {
    let weak = weak.clone();
    let last = std::sync::Mutex::new((String::new(), -1i32));
    Arc::new(move |msg: &str, f: f32| {
        let pct = (f * 100.0) as i32;
        if let Ok(mut l) = last.lock() { if l.0 == msg && l.1 == pct { return; } *l = (msg.to_string(), pct); }
        let text = format!("{label} · {msg}");
        ui_do(&weak, move |ui| job(ui, JOB, &text, f));
    })
}

fn setup(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some((name, ver, loader)) = selected(ui) else { return };
    let h = ui.global::<HostState>();
    h.set_busy(true);
    job(ui, JOB, &format!("{name} server · Preparing"), 0.0);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let r = server::install(&client(), &sh.paths, &name, &ver, Loader::from_label(&loader), &progress(&weak, format!("{name} server")));
        ui_do(&weak, move |ui| {
            ui.global::<HostState>().set_busy(false);
            job_end(ui, JOB);
            match r {
                Ok(_) => { show_toast(ui, &sh, "Server set up. Start it when you're ready"); reload(ui, &sh); }
                Err(e) => show_toast(ui, &sh, &format!("Couldn't set up the server: {e:#}")),
            }
        });
    });
}

fn start(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some((name, ver, loader)) = selected(ui) else { return };
    if !EULA.with(|e| e.get()) { ui.global::<HostState>().set_eula_open(true); return; }
    let h = ui.global::<HostState>();
    h.set_busy(true);
    h.set_status(1);
    CONSOLE.with(|m| m.set_vec(vec![]));
    job(ui, JOB, &format!("{name} server · Starting"), -1.0);
    let opts = ServerOpts { instance: name.clone(), version: ver, loader: Loader::from_label(&loader), ram_mb: (h.get_ram().clamp(1, 16) * 1024) as u32, port: 0, java_major: None, upnp: h.get_upnp() };
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let r = server::start(&client(), &sh.paths, &opts, progress(&weak, format!("{name} server")));
        ui_do(&weak, move |ui| {
            let h = ui.global::<HostState>();
            h.set_busy(false);
            job_end(ui, JOB);
            match r {
                Ok(server) => {
                    LIVE.with(|l| *l.borrow_mut() = Some(Live { server, inst: name.clone(), started: Instant::now() }));
                    h.set_live(true);
                    h.set_deck_text(format!("{name} server starting…").into());
                    share(ui);
                    arm_timer(ui);
                }
                Err(e) if server::is_eula_error(&e) => { h.set_status(0); EULA.with(|x| x.set(false)); h.set_eula_open(true); }
                Err(e) => { h.set_status(0); show_toast(ui, &sh, &format!("Couldn't start the server: {e:#}")); }
            }
        });
    });
}

fn stop(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some(server) = live_server() else { return };
    ui.global::<HostState>().set_status(3);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        if let Err(e) = server.stop(Duration::from_secs(30)) {
            ui_do(&weak, move |ui| show_toast(ui, &sh, &format!("Couldn't stop the server cleanly: {e:#}")));
        }
    });
}

/// Run a console command on the live server and echo it.
fn command(ui: &AppWindow, sh: &Arc<Shared>, cmd: &str) -> bool {
    let Some(server) = live_server() else { show_toast(ui, sh, "Start the server first"); return false };
    match server.send(cmd) {
        Ok(()) => { push_console(ui, vec![ConLine { t: format!("> {}", cmd.trim().trim_start_matches('/')).into(), k: 2 }]); true }
        Err(e) => { show_toast(ui, sh, &format!("Couldn't send the command: {e:#}")); false }
    }
}

/// After a command that edits ops / whitelist, re-read the lists once the server has written them.
fn reload_soon(ui: &AppWindow, sh: &Arc<Shared>) {
    let (weak, sh) = (ui.as_weak(), sh.clone());
    slint::Timer::single_shot(Duration::from_millis(1500), move || if let Some(ui) = weak.upgrade() { reload(&ui, &sh) });
}

fn list_edit(ui: &AppWindow, sh: &Arc<Shared>, kind: i32, name: &str, add: bool) {
    let name = name.trim().to_string();
    if name.is_empty() { return; }
    if live_server().is_some() {
        let cmd = match (kind, add) { (0, true) => "whitelist add", (0, false) => "whitelist remove", (_, true) => "op", (_, false) => "deop" };
        if command(ui, sh, &format!("{cmd} {name}")) { reload_soon(ui, sh); }
        return;
    }
    let Some((inst, ..)) = selected(ui) else { return };
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let dir = server::server_dir(&sh.paths, &inst);
        let r = match (kind, add) {
            (0, true) => server::whitelist_add(&client(), &dir, &name).map(|_| ()),
            (0, false) => server::whitelist_remove(&dir, &name).map(|_| ()),
            (_, true) => server::ops_add(&client(), &dir, &name, 4).map(|_| ()),
            (_, false) => server::ops_remove(&dir, &name).map(|_| ()),
        };
        ui_do(&weak, move |ui| match r {
            Ok(()) => reload(ui, &sh),
            Err(e) => show_toast(ui, &sh, &format!("Couldn't change the list: {e:#}")),
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
        let Some((inst, ..)) = selected(&ui) else { return };
        let h = ui.global::<HostState>();
        let Ok(port) = h.get_port().trim().parse::<u16>() else { show_toast(&ui, &sh, "Couldn't save: the port must be a number from 1 to 65535"); return };
        let Some(max) = h.get_max_players().trim().parse::<u32>().ok().filter(|m| (1..=1000).contains(m)) else { show_toast(&ui, &sh, "Couldn't save: max players must be a number from 1 to 1000"); return };
        if port == 0 { show_toast(&ui, &sh, "Couldn't save: the port must be a number from 1 to 65535"); return; }
        let s = Settings {
            port, max_players: max, motd: h.get_motd().to_string(), online_mode: h.get_online(), white_list: h.get_wl_on(), pvp: h.get_pvp(),
            difficulty: DIFFICULTY[h.get_difficulty().clamp(0, 3) as usize].into(), gamemode: GAMEMODE[h.get_gamemode().clamp(0, 3) as usize].into(),
        };
        let prefs = serde_json::json!({ "ram_gb": h.get_ram().clamp(1, 16), "upnp": h.get_upnp() });
        if live_server().is_none() { share(&ui); }
        std::thread::spawn(move || {
            let dir = server::server_dir(&sh.paths, &inst);
            let r = server::write_settings(&dir, &s).and_then(|_| Ok(std::fs::write(host_file(&dir), serde_json::to_vec_pretty(&prefs)?)?));
            if let Err(e) = r { ui_do(&weak, move |ui| show_toast(ui, &sh, &format!("Couldn't save the server settings: {e:#}"))); }
        });
    });
}

fn import_save(ui: &AppWindow, sh: &Arc<Shared>, i: i32) {
    if live_server().is_some() { show_toast(ui, sh, "Stop the server before swapping its world"); return; }
    let Some((inst, ..)) = selected(ui) else { return };
    let h = ui.global::<HostState>();
    let Some(world) = h.get_saves().row_data(i.max(0) as usize).map(|s| s.to_string()) else { return };
    h.set_busy(true);
    job(ui, JOB, &format!("Copying {world} into the server…"), -1.0);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let r = server::import_save(&sh.paths, &inst, &world);
        ui_do(&weak, move |ui| {
            ui.global::<HostState>().set_busy(false);
            job_end(ui, JOB);
            match r {
                Ok(_) => { show_toast(ui, &sh, &format!("{world} copied in as the server world")); reload(ui, &sh); }
                Err(e) => show_toast(ui, &sh, &format!("Couldn't copy the world: {e:#}")),
            }
        });
    });
}

fn backup(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some((inst, ..)) = selected(ui) else { return };
    let h = ui.global::<HostState>();
    h.set_busy(true);
    job(ui, JOB, "Backing up the server world…", -1.0);
    let live = live_server();
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let r = match live { Some(s) => s.backup(), None => server::backup_world(&server::server_dir(&sh.paths, &inst)) };
        ui_do(&weak, move |ui| {
            ui.global::<HostState>().set_busy(false);
            job_end(ui, JOB);
            match r {
                Ok(p) => { show_toast(ui, &sh, &format!("Backup saved: {}", p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default())); reload(ui, &sh); }
                Err(e) => show_toast(ui, &sh, &format!("Couldn't back up: {e:#}")),
            }
        });
    });
}

// ---------------- wiring ----------------

pub fn init(ui: &AppWindow, sh: Arc<Shared>) {
    SHARED.with(|s| *s.borrow_mut() = Some(sh.clone()));
    let h = ui.global::<HostState>();
    CONSOLE.with(|m| h.set_console(ModelRc::from(m.clone())));
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        h.on_opened(move || {
            let Some(ui) = weak.upgrade() else { return };
            let b = ui.global::<Backend>();
            let insts = b.get_instances();
            // keep the selection on the same instance even if the list changed
            let name = SEL.with(|n| n.borrow().clone());
            // first visit: a profile that already has a server, else the launch target when it can host, else the
            // first profile that can
            let can = |i: &crate::Inst| hostable(Loader::from_label(&i.loader));
            let active = b.get_active_inst().max(0) as usize;
            let idx = insts.iter().position(|i| i.name == name.as_str())
                .or_else(|| insts.iter().position(|i| can(&i) && server::server_dir(&sh.paths, &i.name).join("nether-server.json").exists()))
                .or_else(|| insts.row_data(active).filter(|i| can(i)).map(|_| active))
                .or_else(|| insts.iter().position(|i| can(&i)))
                .unwrap_or(active);
            if insts.row_count() > 0 { select(&ui, &sh, idx.min(insts.row_count() - 1) as i32); }
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        h.on_select(move |i| if let Some(ui) = weak.upgrade() { select(&ui, &sh, i) });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        h.on_primary(move || {
            let Some(ui) = weak.upgrade() else { return };
            let h = ui.global::<HostState>();
            match h.get_phase() {
                2 => setup(&ui, &sh),
                3 if live_server().is_some() => stop(&ui, &sh),
                3 => start(&ui, &sh),
                _ => {}
            }
        });
    }
    {
        // the only path to accept_eula: the user's click on I AGREE in the EULA dialog
        let (weak, sh) = (ui.as_weak(), sh.clone());
        h.on_accept_eula(move || {
            let Some(ui) = weak.upgrade() else { return };
            let Some((inst, ..)) = selected(&ui) else { return };
            ui.global::<HostState>().set_eula_open(false);
            let (weak, sh) = (weak.clone(), sh.clone());
            std::thread::spawn(move || {
                let r = server::accept_eula(&server::server_dir(&sh.paths, &inst));
                ui_do(&weak, move |ui| match r {
                    Ok(()) => { EULA.with(|e| e.set(true)); start(ui, &sh); }
                    Err(e) => show_toast(ui, &sh, &format!("Couldn't save the EULA answer: {e:#}")),
                });
            });
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        h.on_send(move |cmd| {
            let Some(ui) = weak.upgrade() else { return };
            let cmd = cmd.trim().to_string();
            if cmd.is_empty() { return; }
            HIST.with(|h| { let mut h = h.borrow_mut(); if h.0.last() != Some(&cmd) { h.0.push(cmd.clone()); } if h.0.len() > 100 { h.0.remove(0); } h.1 = h.0.len(); });
            command(&ui, &sh, &cmd);
        });
    }
    h.on_history(|dir| HIST.with(|h| {
        let mut h = h.borrow_mut();
        let n = h.0.len();
        h.1 = if dir < 0 { h.1.saturating_sub(1) } else { (h.1 + 1).min(n) };
        h.0.get(h.1).cloned().unwrap_or_default().into()
    }));
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        h.on_player(move |name, action| {
            let Some(ui) = weak.upgrade() else { return };
            let cmd = match action { 0 => "kick", 1 => "op", 2 => "deop", _ => "whitelist add" };
            if command(&ui, &sh, &format!("{cmd} {name}")) && action > 0 { reload_soon(&ui, &sh); }
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        h.on_list_add(move |k, n| if let Some(ui) = weak.upgrade() { list_edit(&ui, &sh, k, &n, true) });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        h.on_list_remove(move |k, n| if let Some(ui) = weak.upgrade() { list_edit(&ui, &sh, k, &n, false) });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        h.on_save_settings(move || if let Some(ui) = weak.upgrade() { if !FIXTURE.with(|f| f.get()) { save_settings(&ui, &sh) } });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        h.on_import_save(move |i| if let Some(ui) = weak.upgrade() { import_save(&ui, &sh, i) });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        h.on_backup(move || if let Some(ui) = weak.upgrade() { backup(&ui, &sh) });
    }
    {
        // outside check of the public address (the free mcstatus.io API pings it like a Minecraft client would)
        let weak = ui.as_weak();
        h.on_check_reach(move || {
            let Some(ui) = weak.upgrade() else { return };
            let h = ui.global::<HostState>();
            let Some(port) = live_server().map(|s| s.port()) else { return };
            let host = h.get_pub_addr().split(':').next().unwrap_or_default().to_string();
            if host.is_empty() { return; }
            h.set_reach(1);
            let weak = weak.clone();
            std::thread::spawn(move || {
                let ok = net::reachable(&client(), &host, port).unwrap_or(false);
                ui_do(&weak, move |ui| { let h = ui.global::<HostState>(); if h.get_reach() == 1 { h.set_reach(if ok { 2 } else { 3 }); } });
            });
        });
    }
    {
        let weak = ui.as_weak();
        h.on_open_backups(move || {
            let Some(ui) = weak.upgrade() else { return };
            let Some((inst, ..)) = selected(&ui) else { return };
            let dir = server::server_dir(&sh.paths, &inst).join("backups");
            std::thread::spawn(move || { let _ = std::fs::create_dir_all(&dir); let _ = std::process::Command::new("explorer").arg(&dir).spawn(); });
        });
    }
}

/// On quit: stop a running server cleanly (bounded wait, then it is killed by `Server::stop`).
pub fn shutdown() {
    if let Some(s) = live_server() { let _ = s.stop(Duration::from_secs(25)); }
}

/// Dev snapshots only (`--snapshot` with NC_SNAP_STATE): "host-eula" opens the EULA dialog (nothing is accepted),
/// "host-running" feeds a fixture of a running server (no process, no timer).
pub fn snap(ui: &AppWindow, state: &str) {
    let h = ui.global::<HostState>();
    if let Some(t) = std::env::var("NC_SNAP_TAB").ok().and_then(|t| t.parse().ok()) { h.set_tab(t); }
    match state {
        "host-eula" => h.set_eula_open(true),
        "host-running" => {
            FIXTURE.with(|f| f.set(true));
            let b = ui.global::<Backend>();
            let idx = b.get_instances().iter().position(|i| i.name == "Friends SMP").unwrap_or(0);
            let i = b.get_instances().row_data(idx).unwrap_or_default();
            h.set_idx(idx as i32);
            h.set_inst_name(i.name.clone());
            h.set_inst_meta(format!("{}  ·  {}", i.ver, i.loader.to_uppercase()).into());
            h.set_phase(3);
            h.set_status(2);
            h.set_live(true);
            h.set_uptime("1:12:08".into());
            h.set_deck_text(format!("{} server online · 3 players", i.name).into());
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
            h.set_saves(strings(vec!["New World".into(), "Survival 2".into()]));
            h.set_backups(ModelRc::new(VecModel::from(vec![HostBackup { name: "world-20261110-150212.zip".into(), size: "38.4 MB".into(), date: "1 h ago".into() }])));
            h.set_max_players("20".into());
            h.set_motd("Friends SMP · season 3".into());
            h.set_lan("192.168.1.24".into());
            h.set_pub_addr("203.0.113.7".into());
            h.set_upnp_state(2);
            h.set_share_addr("203.0.113.7".into());
            h.set_reach(2);
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
