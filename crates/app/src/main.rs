#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
#![deny(clippy::correctness, clippy::suspicious)]
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use nether_core::instances::{self, InstanceData};
use nether_core::launch::{launch, LaunchOpts, Loader};
use nether_core::paths::Paths;
use nether_core::{auth, gamesettings, Progress, Session};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

mod applog;
mod artui;
mod drop;
mod client;
mod winchrome;
mod md;
mod prefs;
mod skin;
mod mods;
mod host;
mod deeplink;
mod wardrobe;
mod packs;
mod wizard;
mod combine;
mod instpage;
mod settings;
// L1: pre-launch check, safe updates / history
mod preflight;
mod snapshot;
mod restore;
mod palette;
mod discord;
slint::include_modules!();

#[allow(clippy::too_many_arguments)]
fn inst(name: &str, ver: &str, loader: &str, hours: i32, mods: i32, ram: i32, client: &str, last: &str, running: bool) -> Inst {
    Inst { name: name.into(), title: name.to_uppercase().into(), kind: loader.to_uppercase().into(), ver: ver.into(), loader: loader.into(), hours, mods, ram, client: client.into(), last: last.into(), running, java: 0, secs: hours * 3600, last_ts: 0, shared: true, bk_keep: 5, ..Default::default() }
}

/// "42m", "1h 05m".
fn span(secs: i64) -> String {
    let m = secs / 60;
    if m < 60 { format!("{}m", m.max(1)) } else { format!("{}h {:02}m", m / 60, m % 60) }
}

/// Bottom deck: which games are running ("Survival running", "Survival + Creative running").
fn running_label(ui: &AppWindow) {
    let b = ui.global::<Backend>();
    let names: Vec<String> = b.get_instances().iter().filter(|i| i.running).map(|i| i.name.to_string()).collect();
    let label = match names.len() { 0 => String::new(), 1 | 2 => format!("{} running", names.join(" + ")), n => format!("{n} games running") };
    b.set_running_label(label.into());
}

fn now() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// "Just now", "12m ago", "3h ago", "Yesterday", "4 days ago", "2 weeks ago", "5 months ago".
fn ago(ts: i64) -> String {
    let s = (now() - ts).max(0);
    match s {
        s if s < 120 => "Just now".into(),
        s if s < 3600 => format!("{}m ago", s / 60),
        s if s < 86_400 => format!("{}h ago", s / 3600),
        s if s < 172_800 => "Yesterday".into(),
        s if s < 1_209_600 => format!("{} days ago", s / 86_400),
        s if s < 5_184_000 => format!("{} weeks ago", s / 604_800),
        s => format!("{} months ago", s / 2_592_000),
    }
}

/// Java choice index in the UI (0 Auto, then 8/17/21/25) <-> major version.
const JAVAS: [u32; 5] = [0, 8, 17, 21, 25];

fn inst_from(d: &InstanceData) -> Inst {
    let secs = if d.played_secs > 0 { d.played_secs } else { d.hours as u64 * 3600 };
    let mut i = inst(&d.name, &d.version, &d.loader, (secs / 3600) as i32, d.mods as i32, d.ram_gb as i32, if d.client == "Off" { "Global" } else { &d.client }, &d.last, false);
    i.java = JAVAS.iter().position(|j| *j == d.java).unwrap_or(0) as i32;
    i.secs = secs as i32;
    i.last_ts = d.last_ts as i32;
    i.icon = d.icon.as_str().into();
    i.cover = d.cover.as_str().into();
    i.shared = d.shared_settings;
    i.jvm = d.jvm_args.as_str().into();
    i.win_w = d.width as i32;
    i.win_h = d.height as i32;
    i.fullscreen = d.fullscreen;
    i.group = d.group.as_str().into();
    i.notes = d.notes.as_str().into();
    i.crashed = d.crashed;
    i.bk_auto = d.backup_auto;
    i.bk_keep = if d.backup_keep == 0 { 5 } else { d.backup_keep as i32 };
    if d.last_ts > 0 { i.last = ago(d.last_ts as i64).into(); }
    i
}

/// Set when instances.json couldn't be read at startup: nothing is saved over it this session.
static READ_ONLY: AtomicBool = AtomicBool::new(false);

pub(crate) fn persist(paths: &Paths, m: &dyn Model<Data = Inst>) {
    if READ_ONLY.load(Ordering::Relaxed) { return; }
    let list: Vec<InstanceData> = (0..m.row_count()).filter_map(|n| m.row_data(n)).map(|i| data_of(&i)).collect();
    if let Err(e) = instances::save(paths, &list) { applog::line(&format!("saving instances.json failed: {e:#}")); }
}

/// The saved form of an instance row.
pub(crate) fn data_of(i: &Inst) -> InstanceData {
    InstanceData {
        name: i.name.to_string(), version: i.ver.to_string(), loader: i.loader.to_string(), ram_gb: i.ram as u32,
        hours: i.hours as u32, mods: i.mods as u32, client: i.client.to_string(), last: i.last.to_string(),
        java: JAVAS[i.java.clamp(0, 4) as usize], played_secs: i.secs.max(0) as u64, last_ts: i.last_ts.max(0) as u64,
        icon: i.icon.to_string(), cover: i.cover.to_string(), shared_settings: i.shared,
        jvm_args: i.jvm.to_string(), width: i.win_w.max(0) as u32, height: i.win_h.max(0) as u32, fullscreen: i.fullscreen,
        group: i.group.to_string(), notes: i.notes.to_string(), crashed: i.crashed, backup_auto: i.bk_auto, backup_keep: i.bk_keep.max(0) as u32,
    }
}

/// Browse category dropdown index -> Modrinth category slug ("" = any). Must match the Slint lists.
fn category_slug(kind: i32, idx: i32) -> String {
    const MODS: &[&str] = &["", "optimization", "utility", "decoration", "adventure", "magic", "technology", "storage", "worldgen", "mobs", "equipment", "food", "game-mechanics", "library", "social", "transportation"];
    const PACKS: &[&str] = &["", "vanilla-like", "simplistic", "realistic", "themed", "decoration", "combat", "tweaks", "utility", "16x", "32x", "64x"];
    const SHADERS: &[&str] = &["", "vanilla-like", "semi-realistic", "realistic", "fantasy", "cartoon", "potato", "low", "medium", "high"];
    let list = match kind { 1 => PACKS, 2 => SHADERS, _ => MODS };
    list.get(idx.max(0) as usize).unwrap_or(&"").to_string()
}

/// Indices of the instances shown on the Instances page (filter 0 all, 1 modded, 2 vanilla; search text; sort
/// 0 last played, 1 name, 2 playtime, 3 created) and the Home order (last played first).
fn refilter(ui: &AppWindow) {
    let b = ui.global::<Backend>();
    let (f, q, sort) = (b.get_inst_filter(), b.get_inst_query().trim().to_lowercase(), b.get_inst_sort());
    let all: Vec<Inst> = b.get_instances().iter().collect();
    let mut v: Vec<usize> = (0..all.len())
        .filter(|n| match f { 1 => all[*n].loader != "Vanilla", 2 => all[*n].loader == "Vanilla", _ => true })
        .filter(|n| q.is_empty() || [&all[*n].name, &all[*n].ver, &all[*n].loader].iter().any(|s| s.to_lowercase().contains(&q)))
        .collect();
    match sort {
        0 => v.sort_by_key(|n| std::cmp::Reverse(all[*n].last_ts)),
        1 => v.sort_by_key(|n| all[*n].name.to_lowercase()),
        2 => v.sort_by_key(|n| std::cmp::Reverse(all[*n].secs)),
        _ => {}
    }
    // L2: groups: ungrouped first, then each group (A-Z) under a header; a folded group shows only its header
    let mut names: Vec<String> = all.iter().map(|i| i.group.to_string()).filter(|g| !g.is_empty()).collect();
    names.sort_by_key(|g| g.to_lowercase());
    names.dedup();
    let mut rows: Vec<InstRow> = vec![];
    let mut num = 0;
    let mut push = |rows: &mut Vec<InstRow>, n: usize| { num += 1; rows.push(InstRow { idx: n as i32, num, ..Default::default() }); };
    for n in v.iter().filter(|n| all[**n].group.is_empty()) { push(&mut rows, *n); }
    for g in &names {
        let members: Vec<usize> = v.iter().copied().filter(|n| all[*n].group == g.as_str()).collect();
        if members.is_empty() { continue; }
        let folded = FOLDED.with(|f| f.borrow().contains(g));
        rows.push(InstRow { idx: -1, head: g.as_str().into(), n: members.len() as i32, folded, num: 0 });
        if !folded { for n in members { push(&mut rows, n); } }
    }
    b.set_inst_rows(ModelRc::new(VecModel::from(rows)));
    b.set_groups(ModelRc::new(VecModel::from(names.iter().map(|g| g.as_str().into()).collect::<Vec<slint::SharedString>>())));
    b.set_visible_insts(ModelRc::new(VecModel::from(v.into_iter().map(|n| n as i32).collect::<Vec<_>>())));
    let mut recent: Vec<usize> = (0..all.len()).collect();
    recent.sort_by_key(|n| std::cmp::Reverse(all[*n].last_ts));
    b.set_recent(ModelRc::new(VecModel::from(recent.into_iter().map(|n| n as i32).collect::<Vec<_>>())));
}

/// Update one instance row by name (UI thread), then save.
fn edit_inst(ui: &AppWindow, sh: &Shared, name: &str, f: impl FnOnce(&mut Inst)) {
    let m = ui.global::<Backend>().get_instances();
    if let Some(n) = m.iter().position(|i| i.name == name) {
        let mut i = m.row_data(n).unwrap();
        f(&mut i);
        m.set_row_data(n, i);
        persist(&sh.paths, &m);
        refilter(ui);
    }
}

/// Change a row's screen-only state (starting / stopping), without saving.
pub(crate) fn edit_inst_quiet(ui: &AppWindow, name: &str, f: impl FnOnce(&mut Inst)) {
    let m = ui.global::<Backend>().get_instances();
    if let Some(n) = m.iter().position(|i| i.name == name) {
        let mut i = m.row_data(n).unwrap();
        f(&mut i);
        m.set_row_data(n, i);
    }
}




struct Shared {
    paths: Paths,
    client_id: Option<String>,
    session: Mutex<Option<Session>>,
    cancel: AtomicBool,
    toast_id: AtomicU32,
    /// running games: instance name -> process id (for Stop)
    running: Mutex<HashMap<String, u32>>,
    /// instances being prepared / started (Play is ignored for them) -> their cancel switch
    launching: Mutex<HashMap<String, Arc<AtomicBool>>>,
    /// games asked to close by Stop (their exit is not a crash)
    stopping: Mutex<HashSet<String>>,
}

/// Run `f` on the UI thread.
fn ui_do(weak: &slint::Weak<AppWindow>, f: impl FnOnce(&AppWindow) + Send + 'static) {
    let w = weak.clone();
    let _ = slint::invoke_from_event_loop(move || {
        if let Some(ui) = w.upgrade() { f(&ui); }
    });
}

// ---- bottom deck jobs: several long tasks at once, each with its own key; the newest one is shown ----

struct Job { key: String, text: String, prog: f32, cancel: Option<Arc<AtomicBool>> }

thread_local! {
    static JOBS: RefCell<Vec<Job>> = const { RefCell::new(Vec::new()) };
    /// what the toast's action button does
    static TOAST_ACT: RefCell<Option<UiAction>> = const { RefCell::new(None) };
    /// L2: instance groups folded on the Instances page (this session)
    static FOLDED: RefCell<HashSet<String>> = RefCell::new(HashSet::new());
}

fn show_jobs(ui: &AppWindow) {
    let b = ui.global::<Backend>();
    JOBS.with(|j| {
        let j = j.borrow();
        match j.last() {
            Some(top) => {
                b.set_job_text(top.text.as_str().into());
                b.set_job_prog(top.prog);
                b.set_job_cancel(top.cancel.is_some());
                b.set_job_more(j.len() as i32 - 1);
                b.set_job_on(true);
            }
            None => { b.set_job_on(false); b.set_job_more(0); b.set_job_cancel(false); }
        }
    });
}

/// Start or update the long task `key` in the bottom deck (`f` 0-1, or negative for no percentage).
pub(crate) fn job(ui: &AppWindow, key: &str, msg: &str, f: f32) {
    JOBS.with(|j| {
        let mut j = j.borrow_mut();
        match j.iter_mut().find(|x| x.key == key) {
            Some(x) => { x.text = msg.to_string(); x.prog = f; }
            None => j.push(Job { key: key.to_string(), text: msg.to_string(), prog: f, cancel: None }),
        }
    });
    show_jobs(ui);
}

/// Like `job`, with a CANCEL link that sets `cancel`.
pub(crate) fn job_cancelable(ui: &AppWindow, key: &str, msg: &str, f: f32, cancel: Arc<AtomicBool>) {
    job(ui, key, msg, f);
    JOBS.with(|j| if let Some(x) = j.borrow_mut().iter_mut().find(|x| x.key == key) { x.cancel = Some(cancel); });
    show_jobs(ui);
}

/// Something to run later on the UI thread (a toast action, a dialog's "yes").
pub(crate) type UiAction = Box<dyn FnOnce(&AppWindow)>;

/// End job `key` and pass `v` through (for `match job_done(ui, key, result) { .. }`).
pub(crate) fn job_done<T>(ui: &AppWindow, key: &str, v: T) -> T { job_end(ui, key); v }

/// The task `key` finished (or failed): take it off the deck.
pub(crate) fn job_end(ui: &AppWindow, key: &str) {
    JOBS.with(|j| j.borrow_mut().retain(|x| x.key != key));
    show_jobs(ui);
}

/// Failures read as failures: messages that start with "Couldn't", "Can't", "... failed".
fn is_error(msg: &str) -> bool {
    let l = msg.trim_start().to_lowercase();
    ["couldn't", "could not", "can't", "cannot"].iter().any(|k| l.starts_with(k)) || l.contains(" failed:")
}

/// Toast that hides itself after a moment (failures stay longer and are marked). UI thread only.
fn show_toast(ui: &AppWindow, sh: &Arc<Shared>, msg: &str) {
    toast(ui, sh, msg, is_error(msg), None);
}

/// Toast with an action button (VIEW LOG, OPEN SETTINGS ...); clicking it runs `act` and closes the toast.
pub(crate) fn toast_action(ui: &AppWindow, sh: &Arc<Shared>, msg: &str, err: bool, label: &str, act: impl FnOnce(&AppWindow) + 'static) {
    toast(ui, sh, msg, err, Some((label.to_string(), Box::new(act))));
}

fn toast(ui: &AppWindow, sh: &Arc<Shared>, msg: &str, err: bool, act: Option<(String, UiAction)>) {
    let b = ui.global::<Backend>();
    b.set_toast_text(msg.into());
    b.set_toast_err(err);
    b.set_toast_action(act.as_ref().map(|a| a.0.as_str()).unwrap_or("").into());
    TOAST_ACT.with(|t| *t.borrow_mut() = act.map(|a| a.1));
    b.set_toast_on(true);
    if err { applog::line(&format!("shown: {msg}")); }
    let n = sh.toast_id.fetch_add(1, Ordering::SeqCst) + 1;
    let long = err || !b.get_toast_action().is_empty();
    arm_toast(ui.as_weak(), sh.clone(), n, if long { 7000 } else { 2600 });
}

/// Hide toast `n` after `ms`, unless it was replaced or the pointer rests on it.
fn arm_toast(weak: slint::Weak<AppWindow>, sh: Arc<Shared>, n: u32, ms: u64) {
    slint::Timer::single_shot(std::time::Duration::from_millis(ms), move || {
        if sh.toast_id.load(Ordering::SeqCst) != n { return; }
        let Some(ui) = weak.upgrade() else { return };
        if ui.global::<Backend>().get_toast_hover() { arm_toast(weak, sh, n, 1200); return; }
        ui.global::<Backend>().set_toast_on(false);
    });
}

/// A failure for the user: the short, plain reason first; the full chain goes to the launcher log.
fn friendly(e: &anyhow::Error) -> String {
    applog::line(&format!("error: {e:#}"));
    let net = auth::is_network_error(e);
    let io = e.chain().find_map(|c| c.downcast_ref::<std::io::Error>());
    if net { return "Couldn't reach the download servers. Check your connection and try again.".into(); }
    if let Some(io) = io {
        if io.kind() == std::io::ErrorKind::PermissionDenied || io.raw_os_error() == Some(32) { return "A file it needs is in use or blocked. Close the game (and any antivirus scan of the launcher folder) and try again.".into(); }
        if io.raw_os_error() == Some(112) { return "The disk is full. Free some space and try again.".into(); }
    }
    let s = format!("{e:#}");
    if s.chars().count() > 180 { format!("{}…", s.chars().take(180).collect::<String>()) } else { s }
}

/// Fill the account panel's list from accounts.json. UI thread only.
fn show_accounts(ui: &AppWindow, sh: &Shared) {
    let a = auth::accounts(&sh.paths);
    let active = a.active().map(|x| x.uuid.clone()).unwrap_or_default();
    let rows: Vec<Acct> = a.list.iter().map(|x| Acct { uuid: x.uuid.as_str().into(), name: x.name.as_str().into(), active: x.uuid == active }).collect();
    ui.global::<Backend>().set_accounts(ModelRc::new(VecModel::from(rows)));
}

/// Apply a restored or new login: session, skin, account list, chip. Worker thread.
fn apply_login(weak: &slint::Weak<AppWindow>, sh: &Arc<Shared>, login: auth::McLogin, offline: bool, toast: bool) {
    *sh.session.lock().unwrap() = Some(login.session.clone());
    if offline { skin::load_cached(weak.clone(), sh.clone()); } else { skin::load(weak.clone(), sh.clone(), login.profile.skins.clone(), login.profile.capes.clone()); }
    let (name, sh2) = (login.profile.name.clone(), sh.clone());
    ui_do(weak, move |ui| {
        set_signed_in(ui, &name);
        ui.global::<Backend>().set_acct_offline(offline);
        show_accounts(ui, &sh2);
        if toast { show_toast(ui, &sh2, &format!("Signed in as {name}")); }
    });
}

/// Restore the active saved account on a worker thread (startup, switch, after signing one out).
fn start_restore(weak: slint::Weak<AppWindow>, sh: Arc<Shared>, toast: bool) {
    let Some(cid) = sh.client_id.clone() else { return };
    ui_do(&weak, |ui| ui.global::<Backend>().set_acct_state(3));
    std::thread::spawn(move || match auth::restore(&nether_core::download::client(), &sh.paths, &cid) {
        Ok(r) => apply_login(&weak, &sh, r.login, r.offline, toast),
        Err(e) => {
            let msg = e.to_string();
            *sh.session.lock().unwrap() = None;
            skin::load(weak.clone(), sh.clone(), vec![], vec![]);
            let sh2 = sh.clone();
            ui_do(&weak, move |ui| { set_signed_out(ui, &msg); show_accounts(ui, &sh2); });
        }
    });
}

fn set_signed_in(ui: &AppWindow, name: &str) {
    let b = ui.global::<Backend>();
    b.set_acct_state(2);
    b.set_acct_name(name.into());
    b.set_acct_msg("".into());
    b.set_gate_open(false);
}

fn set_signed_out(ui: &AppWindow, msg: &str) {
    let b = ui.global::<Backend>();
    b.set_acct_state(0);
    b.set_acct_msg(msg.into());
    b.set_gate_open(true); // the launcher needs a Microsoft account: no browsing signed out
}

/// Sign in through the Microsoft device-code flow on a worker thread.
fn start_sign_in(weak: slint::Weak<AppWindow>, sh: Arc<Shared>) {
    let Some(cid) = sh.client_id.clone() else {
        ui_do(&weak, |ui| set_signed_out(ui, "No Microsoft app is configured yet. Put your Azure client id in %APPDATA%\\NetherClient\\config.json as {\"ms_client_id\": \"...\"}."));
        return;
    };
    sh.cancel.store(false, Ordering::SeqCst);
    std::thread::spawn(move || {
        let c = nether_core::download::client();
        let result = (|| -> anyhow::Result<auth::McLogin> {
            let dc = auth::start_device_flow(&c, &cid)?;
            let (code, url) = (dc.user_code.clone(), dc.verification_uri.clone());
            ui_do(&weak, move |ui| {
                let b = ui.global::<Backend>();
                b.set_acct_state(1);
                b.set_acct_code(code.into());
                b.set_acct_url(url.into());
                b.set_acct_msg("".into());
            });
            let tokens = auth::poll_device_flow(&c, &cid, &dc, &sh.cancel)?;
            let login = auth::minecraft_login(&c, &tokens.access_token)?;
            auth::remember(&sh.paths, &login, &tokens.refresh_token)?;
            Ok(login)
        })();
        match result {
            Ok(login) => apply_login(&weak, &sh, login, false, true),
            Err(e) => {
                let msg = e.to_string();
                let shown = if msg.contains("cancelled") { String::new() } else { msg };
                // adding a second account failed or was cancelled: stay on the current one
                let current = sh.session.lock().unwrap().as_ref().map(|s| s.name.clone());
                let sh2 = sh.clone();
                ui_do(&weak, move |ui| match current {
                    Some(name) => { set_signed_in(ui, &name); if !shown.is_empty() { show_toast(ui, &sh2, &format!("Couldn't add the account: {shown}")); } }
                    None => set_signed_out(ui, &shown),
                });
            }
        }
    });
}

/// The session to play with, decided by `auth::launch_gate`: a real sign-in; a sign-in restored without network
/// (the account owns the game); offline only in `dev` builds with NETHER_DEV_OFFLINE=1. Err = why not.
fn play_session(sh: &Shared) -> Result<Session, String> {
    let current = sh.session.lock().unwrap_or_else(|e| e.into_inner()).clone();
    match auth::launch_gate(&sh.paths, current.as_ref()) {
        auth::LaunchGate::Allowed => current.ok_or_else(|| "Sign in with your Microsoft account to play".to_string()),
        auth::LaunchGate::AllowedDevOffline => Ok(current.unwrap_or_else(|| Session::offline("Player"))),
        // owners play singleplayer with their saved sign-in when Microsoft can't be reached; signed out never plays
        auth::LaunchGate::AllowedOfflineOwner => current.ok_or_else(|| "Sign in with your Microsoft account to play".to_string()),
        auth::LaunchGate::Denied(m) => Err(m),
    }
}

/// Launch options for an instance (shared by Play and the launcher-path test).
fn launch_opts(sh: &Shared, name: &str, version: &str, ram_gb: i32, loader_label: &str, java: u32, shared: bool, session: Session) -> LaunchOpts {
    client::migrate(sh, name);
    let saved = instances::load(&sh.paths).and_then(|l| l.into_iter().find(|d| d.name == name)).unwrap_or_default();
    LaunchOpts {
        loader: Loader::from_label(loader_label), version: version.into(), game_dir: sh.paths.instance(name), session, ram_mb: (ram_gb.max(1) * 1024) as u32,
        java_major: (java > 0).then_some(java), client_config: client::instance_file(sh, name, shared), client: true, groups: None,
        extra_jvm: nether_core::launch::split_args(&saved.jvm_args),
        window: (saved.width >= 320 && saved.height >= 240).then_some((saved.width, saved.height)),
        fullscreen: saved.fullscreen,
        quick_play: None,
        cancel: None,
    }
}

/// Ends an instance's "starting" state however its launch thread exits (error, cancel, panic, game started).
struct Starting { weak: slint::Weak<AppWindow>, sh: Arc<Shared>, name: String, key: String }
impl Drop for Starting {
    fn drop(&mut self) {
        self.sh.launching.lock().unwrap_or_else(|e| e.into_inner()).remove(&self.name);
        let (n, k) = (self.name.clone(), self.key.clone());
        ui_do(&self.weak, move |ui| { edit_inst_quiet(ui, &n, |i| i.starting = false); job_end(ui, &k); });
    }
}

/// Install + launch an instance's Minecraft version on a worker thread, with progress (and CANCEL) in the deck.
/// One launch per instance at a time: a second Play while it is starting or running does nothing.
#[allow(clippy::too_many_arguments)]
fn start_launch(weak: slint::Weak<AppWindow>, sh: Arc<Shared>, name: String, version: String, ram_gb: i32, loader_label: String, java: u32, shared: bool, quick: Option<nether_core::launch::QuickPlay>, check: bool) {
    if sh.running.lock().unwrap_or_else(|e| e.into_inner()).contains_key(&name) { return; }
    let cancel = Arc::new(AtomicBool::new(false));
    {
        let mut l = sh.launching.lock().unwrap_or_else(|e| e.into_inner());
        if l.contains_key(&name) { return; }
        l.insert(name.clone(), cancel.clone());
    }
    let key = format!("launch:{name}");
    if let Some(ui) = weak.upgrade() {
        edit_inst_quiet(&ui, &name, |i| i.starting = true);
        job_cancelable(&ui, &key, &format!("{name} · Preparing"), 0.0, cancel.clone());
    }
    std::thread::spawn(move || {
        let guard = Starting { weak: weak.clone(), sh: sh.clone(), name: name.clone(), key: key.clone() };
        // Playing needs a signed-in Microsoft account (offline only in dev builds, see play_session)
        let session = match play_session(&sh) {
            Ok(s) => s,
            Err(msg) => {
                let sh3 = sh.clone();
                ui_do(&weak, move |ui| {
                    ui.global::<Backend>().set_gate_open(true);
                    show_toast(ui, &sh3, &msg);
                });
                return;
            }
        };
        // L1: pre-launch check (local, cached): problems that would stop the game get a dialog with fixes first
        if check {
            let t = nether_core::preflight::Target { name: &name, version: &version, loader: Loader::from_label(&loader_label), java, ram_gb: ram_gb.max(1) as u32 };
            let found = nether_core::preflight::pending(&sh.paths, &t);
            if !found.is_empty() {
                let held = preflight::Held { name: name.clone(), quick };
                ui_do(&weak, move |ui| preflight::show(ui, held, found));
                return;
            }
        }
        // L2: the weekly automatic restore point (only when the instance has it on and one is due)
        {
            let (w2, k2, l2) = (weak.clone(), key.clone(), name.clone());
            restore::auto_before_launch(&sh, &name, &move |f| {
                let (k, t) = (k2.clone(), format!("{l2} · Saving a restore point"));
                ui_do(&w2, move |ui| job(ui, &k, &t, f));
            });
        }
        let offline = session.user_type == "legacy";
        // a launcher left open past the token's ~24 h life would start the game with a dead token
        let session = match (&sh.client_id, offline) {
            (Some(cid), false) => match auth::fresh_session(&nether_core::download::client(), &sh.paths, cid, &session) {
                Ok(s) => { *sh.session.lock().unwrap() = Some(s.clone()); s }
                Err(e) => {
                    let (sh3, msg) = (sh.clone(), format!("Couldn't renew your sign-in: {}", friendly(&e)));
                    ui_do(&weak, move |ui| show_toast(ui, &sh3, &msg));
                    return;
                }
            },
            _ => session,
        };
        let last = Mutex::new((String::new(), -1i32));
        let (w2, label, k2) = (weak.clone(), name.clone(), key.clone());
        let progress: Progress = Arc::new(move |msg, f| {
            let pct = (f * 100.0) as i32;
            let mut l = last.lock().unwrap_or_else(|e| e.into_inner());
            if l.0 == msg && pct == l.1 { return; }
            *l = (msg.to_string(), pct);
            let (text, k) = (format!("{label} · {msg}"), k2.clone());
            ui_do(&w2, move |ui| job(ui, &k, &text, f));
        });
        // shared game settings (keybinds, video, audio…) go into this instance before it starts
        let game_dir = sh.paths.instance(&name);
        if shared { let _ = std::fs::create_dir_all(&game_dir); let _ = gamesettings::before_launch(&sh.paths, &game_dir, &version, &loader_label); }
        let mut opts = launch_opts(&sh, &name, &version, ram_gb, &loader_label, java, shared, session);
        opts.quick_play = quick;
        opts.cancel = Some(cancel);
        match launch(&sh.paths, &opts, progress) {
            Ok(mut child) => {
                sh.running.lock().unwrap_or_else(|e| e.into_inner()).insert(name.clone(), child.id());
                applog::line(&format!("started {name} ({version}, {loader_label}), pid {}", child.id()));
                discord::activity(&sh.paths, Some(discord::Activity { version: version.clone(), loader: loader_label.clone(), since: now().max(0) as u64 }));
                let (sh3, n3) = (sh.clone(), name.clone());
                let text = if offline { format!("{name} started (offline: sign in for multiplayer)") } else { format!("{name} started") };
                ui_do(&weak, move |ui| {
                    let b = ui.global::<Backend>();
                    b.set_running_count(b.get_running_count() + 1);
                    match prefs::load(&sh3.paths).on_launch { 1 => ui.window().set_minimized(true), 2 => prefs::hide_to_tray(ui, &sh3.paths), _ => {} }
                    edit_inst(ui, &sh3, &n3, |i| { i.running = true; i.starting = false; i.last_ts = now() as i32; i.last = "Just now".into(); i.crashed = false; });
                    running_label(ui);
                    show_toast(ui, &sh3, &text);
                });
                drop(guard);
                // play time is saved every minute, so closing the launcher before the game loses at most one
                let started = std::time::Instant::now();
                let mut credited = 0u64;
                let status = loop {
                    match child.try_wait() {
                        Ok(Some(s)) => break Ok(s),
                        Ok(None) => {}
                        Err(e) => break Err(e),
                    }
                    std::thread::sleep(std::time::Duration::from_millis(1000));
                    let el = started.elapsed().as_secs();
                    if el - credited >= 60 {
                        let (add, sh3, n3) = ((el - credited) as i32, sh.clone(), name.clone());
                        credited = el;
                        ui_do(&weak, move |ui| edit_inst(ui, &sh3, &n3, |i| { i.secs += add; i.hours = i.secs / 3600; }));
                    }
                };
                let stopped = sh.stopping.lock().unwrap_or_else(|e| e.into_inner()).remove(&name);
                sh.running.lock().unwrap_or_else(|e| e.into_inner()).remove(&name);
                if shared { let _ = gamesettings::after_exit(&sh.paths, &game_dir, &version); }
                let played = started.elapsed().as_secs() as i32;
                let rest = played - credited as i32;
                let crashed = !stopped && status.as_ref().map(|s| !s.success()).unwrap_or(false);
                applog::line(&format!("{name} exited ({}) after {played} s{}", status.as_ref().map(|s| s.to_string()).unwrap_or_default(), if stopped { ", stopped by the user" } else { "" }));
                if let Ok(st) = &status {
                    if crashed { instpage::report_crash(&weak, &sh, &name, st.code()); }
                }
                let sh3 = sh.clone();
                ui_do(&weak, move |ui| {
                    let b = ui.global::<Backend>();
                    b.set_running_count((b.get_running_count() - 1).max(0));
                    if b.get_running_count() == 0 { discord::activity(&sh3.paths, None); }
                    // bring the launcher back if it stepped aside for the game
                    if prefs::load(&sh3.paths).on_launch != 0 && b.get_running_count() == 0 { prefs::show(ui); }
                    edit_inst(ui, &sh3, &name, |i| { i.running = false; i.stopping = false; i.secs += rest; i.hours = i.secs / 3600; });
                    running_label(ui);
                    if stopped { show_toast(ui, &sh3, &format!("Stopped {name}")); }
                    else if !crashed && played >= 60 { show_toast(ui, &sh3, &format!("Played {name} for {}", span(played as i64))); }
                });
            }
            Err(e) if nether_core::download::is_cancelled(&e) => {
                let sh3 = sh.clone();
                ui_do(&weak, move |ui| show_toast(ui, &sh3, &format!("Stopped starting {name}. Downloads so far are kept for next time")));
            }
            Err(e) => {
                let (sh3, msg) = (sh.clone(), format!("Couldn't start {name}: {}", friendly(&e)));
                ui_do(&weak, move |ui| toast_action(ui, &sh3, &msg, true, "VIEW LOG", |_| { let _ = std::process::Command::new("explorer").arg(applog::file()).spawn(); }));
            }
        }
    });
}

/// Rename an instance: its folder moves with it (worlds, mods, client settings). UI thread.
fn rename_instance(ui: &AppWindow, sh: &Arc<Shared>, insts: &Rc<VecModel<Inst>>, idx: i32, name: &str) -> bool {
    let Some(i) = insts.row_data(idx.max(0) as usize) else { return false };
    if name.is_empty() { show_toast(ui, sh, "Give the instance a name"); return false; }
    if name == i.name.as_str() { return true; }
    if i.running || i.starting { show_toast(ui, sh, &format!("Close {} before renaming it", i.name)); return false; }
    let same_dir = sh.paths.instance(name) == sh.paths.instance(&i.name);
    if insts.iter().enumerate().any(|(n, o)| n != idx as usize && (o.name == name || sh.paths.instance(&o.name) == sh.paths.instance(name))) || (!same_dir && sh.paths.instance(name).exists()) {
        show_toast(ui, sh, &format!("Couldn't rename: an instance called {name} already exists"));
        return false;
    }
    if let Err(e) = instances::rename(&sh.paths, &i.name, name) { show_toast(ui, sh, &format!("Couldn't rename {}: {e}", i.name)); return false; }
    let mut n = i.clone();
    n.name = name.into();
    n.title = name.to_uppercase().into();
    insts.set_row_data(idx as usize, n);
    persist(&sh.paths, &**insts);
    refilter(ui);
    show_toast(ui, sh, &format!("Renamed to {name}"));
    true
}

/// Games closed after the launcher (or while it was not running) never synced their settings back:
/// take any instance options.txt newer than the shared one into the shared settings. Worker thread.
fn settings_catch_up(sh: &Arc<Shared>) {
    let sh = sh.clone();
    std::thread::spawn(move || {
        if !gamesettings::load_prefs(&sh.paths).enabled { return; }
        let global = std::fs::metadata(gamesettings::global_file(&sh.paths)).and_then(|m| m.modified()).ok();
        let mut newer: Vec<(std::time::SystemTime, InstanceData)> = instances::load(&sh.paths).unwrap_or_default().into_iter().filter(|d| d.shared_settings)
            .filter_map(|d| std::fs::metadata(sh.paths.instance(&d.name).join("options.txt")).and_then(|m| m.modified()).ok().map(|t| (t, d)))
            .filter(|(t, _)| global.is_none_or(|g| *t > g)).collect();
        newer.sort_by_key(|(t, _)| *t);
        for (_, d) in newer { let _ = gamesettings::after_exit(&sh.paths, &sh.paths.instance(&d.name), &d.version); }
    });
}

thread_local! {
    /// The window and shared state, for events that arrive through plain function pointers (window procedure).
    static APP: RefCell<Option<(slint::Weak<AppWindow>, Arc<Shared>)>> = const { RefCell::new(None) };
}

fn with_app(f: impl FnOnce(&AppWindow, &Arc<Shared>)) {
    APP.with(|a| if let Some((w, sh)) = a.borrow().as_ref() { if let Some(ui) = w.upgrade() { f(&ui, sh) } });
}

/// An absolute http(s) URL with a host (exact scheme check; no custom protocol handlers).
fn is_web_url(u: &str) -> bool {
    let rest = u.strip_prefix("https://").or_else(|| u.strip_prefix("http://"));
    rest.is_some_and(|r| r.split(['/', '?', '#']).next().is_some_and(|h| !h.is_empty() && !h.contains(char::is_whitespace)))
}

fn main() -> Result<(), slint::PlatformError> {
    let snapshot = cfg!(feature = "dev") && std::env::args().any(|a| a == "--snapshot"); // dev builds only
    let paths = Paths::new();
    applog::init(&paths.root);
    // one launcher at a time: a second start (shortcut, a file opened with it) goes to the first
    if !snapshot && !deeplink::first_instance() {
        if !deeplink::hand_over(&deeplink::file_args()) { deeplink::busy_notice(); }
        return Ok(());
    }
    prefs::apply_renderer(&paths);
    let ui = AppWindow::new()?;
    let shared = Arc::new(Shared {
        client_id: auth::client_id(&paths),
        paths,
        session: Mutex::new(None),
        cancel: AtomicBool::new(false),
        toast_id: AtomicU32::new(0),
        running: Mutex::new(Default::default()),
        launching: Mutex::new(Default::default()),
        stopping: Mutex::new(Default::default()),
    });
    APP.with(|a| *a.borrow_mut() = Some((ui.as_weak(), shared.clone())));

    // persisted instances (instances.json); a locked or damaged file is never overwritten
    let mut startup_note: Option<String> = None;
    let initial: Vec<Inst> = match instances::open(&shared.paths) {
        instances::Opened::Fresh => vec![],
        instances::Opened::List(l) => l.iter().map(inst_from).collect(),
        instances::Opened::Restored { list, aside } => {
            applog::line(&format!("instances.json was damaged or missing; restored {} from the backup (damaged copy: {aside:?})", list.len()));
            startup_note = Some(if aside.is_some() { "Your instance list was damaged, so the last backup was loaded. The damaged file is kept next to it.".into() } else { "Your instance list was missing, so the last backup was loaded.".into() });
            list.iter().map(inst_from).collect()
        }
        instances::Opened::Unreadable(e) => {
            applog::line(&format!("instances.json unreadable: {e}"));
            READ_ONLY.store(true, Ordering::Relaxed);
            startup_note = Some("Couldn't read your instance list (another program has it open). Restart the launcher; nothing was changed.".into());
            vec![]
        }
    };
    let insts = Rc::new(VecModel::from(initial));

    let backend = ui.global::<Backend>();
    backend.set_instances(ModelRc::from(insts.clone()));
    // the launch target starts on the instance played most recently
    if let Some((n, _)) = insts.iter().enumerate().filter(|(_, i)| i.last_ts > 0).max_by_key(|(_, i)| i.last_ts) { backend.set_active_inst(n as i32); }
    // Modrinth search / installed list / detail drawer / install
    {
        let (weak, insts, sh) = (ui.as_weak(), insts.clone(), shared.clone());
        backend.on_search_mods(move || {
            let Some(ui) = weak.upgrade() else { return };
            let b = ui.global::<Backend>();
            let Some(i) = (b.get_current_inst() >= 0).then(|| insts.row_data(b.get_current_inst() as usize)).flatten() else { return };
            b.set_browse_loading(true);
            b.set_browse_rows(ModelRc::new(VecModel::from(vec![])));
            mods::spawn_browse(weak.clone(), sh.clone(), i.ver.to_string(), i.loader.to_string(), b.get_pack_kind(), b.get_browse_query().to_string(), b.get_browse_sort(), category_slug(b.get_pack_kind(), b.get_browse_cat()));
        });
    }
    {
        let (weak, insts, sh) = (ui.as_weak(), insts.clone(), shared.clone());
        backend.on_refresh_installed(move || { if let Some(ui) = weak.upgrade() { mods::refresh_installed(&ui, &sh, &insts); } });
    }
    {
        let (weak, insts, sh) = (ui.as_weak(), insts.clone(), shared.clone());
        backend.on_update_mods(move |row| { if let Some(ui) = weak.upgrade() { mods::update(&ui, &sh, &insts, row); } });
    }
    {
        let (weak, insts, sh) = (ui.as_weak(), insts.clone(), shared.clone());
        backend.on_open_detail(move |src, row| { if let Some(ui) = weak.upgrade() { mods::open_detail(&ui, &sh, &insts, src, row); } });
    }
    {
        let (weak, insts, sh) = (ui.as_weak(), insts.clone(), shared.clone());
        backend.on_install_mod(move |row_i| {
            let Some(ui) = weak.upgrade() else { return };
            let b = ui.global::<Backend>();
            let Some(i) = (b.get_current_inst() >= 0).then(|| insts.row_data(b.get_current_inst() as usize)).flatten() else { return };
            let Some(r) = b.get_browse_rows().row_data(row_i as usize) else { return };
            let picked = r.vids.row_data(r.pick.max(0) as usize).map(|v| v.to_string());
            mods::install(weak.clone(), sh.clone(), r.id.to_string(), picked, r.name.to_string(), i.ver.to_string(), i.loader.to_string(), i.name.to_string(), b.get_pack_kind(), None);
        });
    }
    {
        let weak = ui.as_weak();
        backend.on_pick_row_version(move |row_i, p| {
            let Some(ui) = weak.upgrade() else { return };
            let m = ui.global::<Backend>().get_browse_rows();
            if let Some(mut r) = m.row_data(row_i as usize) { r.pick = p; m.set_row_data(row_i as usize, r); }
        });
    }
    // uninstall: from the installed list (by row) or from the project page (its file)
    {
        let (weak, insts, sh) = (ui.as_weak(), insts.clone(), shared.clone());
        backend.on_uninstall(move |row_i| {
            let Some(ui) = weak.upgrade() else { return };
            let b = ui.global::<Backend>();
            let _ = &insts;
            let Some(r) = b.get_installed_rows().row_data(row_i as usize) else { return };
            if r.path.is_empty() { return; }
            match nether_core::trash::recycle(std::path::Path::new(r.path.as_str())) {
                Ok(()) => { show_toast(&ui, &sh, &format!("{} moved to the Recycle Bin", r.name)); b.invoke_refresh_installed(); }
                Err(e) => show_toast(&ui, &sh, &format!("Couldn't uninstall: {e}")),
            }
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_uninstall_detail(move || {
            let Some(ui) = weak.upgrade() else { return };
            let b = ui.global::<Backend>();
            let d = b.get_detail();
            if d.file.is_empty() { return; }
            match nether_core::trash::recycle(std::path::Path::new(d.file.as_str())) {
                Ok(()) => {
                    show_toast(&ui, &sh, &format!("{} moved to the Recycle Bin", d.title));
                    b.set_detail_open(false);
                    b.invoke_refresh_installed();
                }
                Err(e) => show_toast(&ui, &sh, &format!("Couldn't uninstall: {e}")),
            }
        });
    }
    {
        let (weak, insts, sh) = (ui.as_weak(), insts.clone(), shared.clone());
        backend.on_install_detail(move || {
            let Some(ui) = weak.upgrade() else { return };
            let b = ui.global::<Backend>();
            if b.get_detail_pack() { packs::install(&ui, &sh, &insts); return; }
            let Some(i) = (b.get_current_inst() >= 0).then(|| insts.row_data(b.get_current_inst() as usize)).flatten() else { return };
            let d = b.get_detail();
            let picked = d.vers.row_data(d.pick.max(0) as usize).map(|v| v.id.to_string());
            // on an installed project this switches version (the old file is replaced)
            let replace = (d.installed && !d.file.is_empty()).then(|| d.file.to_string());
            mods::install(weak.clone(), sh.clone(), d.id.to_string(), picked, d.title.to_string(), i.ver.to_string(), i.loader.to_string(), i.name.to_string(), b.get_pack_kind(), replace);
        });
    }
    // enable/disable an installed mod (by its file)
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_toggle_mod(move |row_i, on| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(r) = ui.global::<Backend>().get_installed_rows().row_data(row_i as usize) else { return };
            mods::set_enabled(&ui, &sh, r.path.as_str(), on);
        });
        let weak = ui.as_weak();
        backend.on_filter_installed(move || if let Some(ui) = weak.upgrade() { mods::filter_installed(&ui) });
        let weak = ui.as_weak();
        backend.on_pick_installed(move |path, on| if let Some(ui) = weak.upgrade() { mods::pick(&ui, path.as_str(), on) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_bulk_installed(move |a| if let Some(ui) = weak.upgrade() { mods::bulk(&ui, &sh, a) });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_toast(move |m| if let Some(ui) = weak.upgrade() { show_toast(&ui, &sh, m.as_str()) });
    }
    {
        let (weak, sh, insts) = (ui.as_weak(), shared.clone(), insts.clone());
        backend.on_play(move |n| {
            let found = insts.iter().find(|i| i.name == n && !i.starting && !i.stopping);
            if let (Some(p), Some(ui)) = (insts.iter().position(|i| i.name == n), weak.upgrade()) { ui.global::<Backend>().set_active_inst(p as i32); }
            if let Some(i) = found { start_launch(weak.clone(), sh.clone(), i.name.to_string(), i.ver.to_string(), i.ram, i.loader.to_string(), JAVAS[i.java.clamp(0, 4) as usize], i.shared, None, true); }
        });
    }
    // ---- combine resource packs ----
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        ui.global::<Combiner>().on_start(move |i| if let Some(ui) = weak.upgrade() { combine::start(&ui, &sh, i) });
        let weak = ui.as_weak();
        ui.global::<Combiner>().on_pick(move |c, v| if let Some(ui) = weak.upgrade() { combine::pick(&ui, c, v) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        ui.global::<Combiner>().on_create(move || if let Some(ui) = weak.upgrade() { combine::create(&ui, &sh) });
    }
    // ---- new-instance wizard ----
    {
        wizard::init(&ui, &shared);
        let (weak, sh) = (ui.as_weak(), shared.clone());
        ui.global::<Wizard>().on_pick_pack(move |id| if let Some(ui) = weak.upgrade() { wizard::pick_pack(&ui, &sh, &id) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        ui.global::<Wizard>().on_load_rps(move |v| if let Some(ui) = weak.upgrade() { wizard::load_rps(&ui, &sh, &v) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        ui.global::<Wizard>().on_finish(move |n, v| if let Some(ui) = weak.upgrade() { wizard::finish(&ui, &sh, &n, &v) });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_load_packs(move || if let Some(ui) = weak.upgrade() { packs::load(&ui, &sh) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_open_pack(move |r| if let Some(ui) = weak.upgrade() { packs::open(&ui, &sh, r) });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_sign_in(move || start_sign_in(weak.clone(), sh.clone()));
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_sign_out(move || {
            let gone = sh.session.lock().unwrap().take();
            if let Some(s) = &gone { let _ = auth::remove(&sh.paths, &s.uuid); }
            let Some(ui) = weak.upgrade() else { return };
            show_accounts(&ui, &sh);
            show_toast(&ui, &sh, &format!("Signed out{}", gone.map(|s| format!(" of {}", s.name)).unwrap_or_default()));
            // another saved account takes over
            if !auth::accounts(&sh.paths).list.is_empty() { start_restore(weak.clone(), sh.clone(), false); return; }
            skin::load(weak.clone(), sh.clone(), vec![], vec![]);
            set_signed_out(&ui, "");
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_switch_account(move |uuid| {
            if auth::set_active(&sh.paths, &uuid).is_err() { return; }
            start_restore(weak.clone(), sh.clone(), true);
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_cancel_sign_in(move || {
            sh.cancel.store(true, Ordering::SeqCst);
            let current = sh.session.lock().unwrap().as_ref().map(|s| s.name.clone());
            if let Some(ui) = weak.upgrade() { match current { Some(n) => set_signed_in(&ui, &n), None => set_signed_out(&ui, "") } }
        });
    }
    backend.on_open_url(|u| { if is_web_url(u.as_str()) { let _ = webbrowser::open(u.as_str()); } });
    backend.on_copy_text(|t| { if let Ok(mut c) = arboard::Clipboard::new() { let _ = c.set_text(t.to_string()); } });

    // silent sign-in from the stored token
    match shared.client_id.clone() {
        None => ui.global::<Backend>().set_acct_msg("No Microsoft app is configured. Put your Azure client id in %APPDATA%\\NetherClient\\config.json as {\"ms_client_id\": \"...\"}.".into()),
        Some(_) => {
            show_accounts(&ui, &shared);
            if auth::has_saved_login(&shared.paths) {
                // a returning player lands on Home with their name in the chip while the sign-in refreshes (no gate flash)
                let b = ui.global::<Backend>();
                b.set_acct_state(3);
                b.set_gate_open(false);
                if let Some(a) = auth::accounts(&shared.paths).active() { b.set_acct_name(a.name.as_str().into()); }
                start_restore(ui.as_weak(), shared.clone(), false);
            }
        }
    }

    // ---- player preview (3D skin + cape): renders only while it changes or spins under the pointer ----
    if backend.get_acct_state() == 3 { skin::load_cached(ui.as_weak(), shared.clone()); } else { skin::load(ui.as_weak(), shared.clone(), vec![], vec![]); }
    backend.on_skin_drag(skin::drag);
    skin::init(&ui);
    skin::watch(&ui, shared.clone());
    backend.on_skin_wake(skin::wake);
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_equip_cape(move |i| if let Some(ui) = weak.upgrade() { skin::equip(&ui, &sh, i) });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_load_ncapes(move || if let Some(ui) = weak.upgrade() { skin::load_ncapes(&ui, &sh) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_wear_ncape(move |id| if let Some(ui) = weak.upgrade() { skin::wear_ncape(&ui, &sh, &id) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_load_cos(move || if let Some(ui) = weak.upgrade() { skin::load_cosmetics(&ui, &sh) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_wear_cos(move |slot, id| if let Some(ui) = weak.upgrade() { skin::wear_cosmetic(&ui, &sh, slot, &id) });
        backend.on_hover_cos(|id| skin::hover_cosmetic(&id));
    }

    // ---- instances: versions for the create dialog, create/duplicate/delete/export, settings ----
    {
        let weak = ui.as_weak();
        std::thread::spawn(move || {
            let c = nether_core::download::client();
            let rel: Vec<slint::SharedString> = nether_core::mojang::manifest(&c).map(|m| m.versions.iter().filter(|v| v.kind == "release").map(|v| v.id.as_str().into()).collect()).unwrap_or_default();
            // stable game versions from a Fabric-family meta service (same shape for all three)
            let games = |url: &str| -> Vec<slint::SharedString> {
                nether_core::download::get_json::<Vec<serde_json::Value>>(&c, url)
                    .map(|v| v.iter().filter(|g| g["stable"].as_bool().unwrap_or(false)).filter_map(|g| g["version"].as_str().map(Into::into)).collect()).unwrap_or_default()
            };
            let fab = games("https://meta.fabricmc.net/v2/versions/game");
            let quilt = games("https://meta.quiltmc.org/v3/versions/game");
            let legacy = games("https://meta.legacyfabric.net/v2/versions/game");
            ui_do(&weak, move |ui| {
                let b = ui.global::<Backend>();
                b.set_mc_versions(ModelRc::new(VecModel::from(rel)));
                b.set_fabric_versions(ModelRc::new(VecModel::from(fab)));
                b.set_quilt_versions(ModelRc::new(VecModel::from(quilt)));
                b.set_legacy_versions(ModelRc::new(VecModel::from(legacy)));
            });
        });
    }
    // settings: storage & about
    {
        let b = ui.global::<Backend>();
        b.set_app_version(format!("{} beta", env!("CARGO_PKG_VERSION")).into());
        b.set_data_path(shared.paths.root.display().to_string().into());
        let cache = shared.paths.root.join("cache");
        // measured on a worker: the cache holds thousands of files and must not hold up the first frame
        {
            let (weak, cache) = (ui.as_weak(), cache.clone());
            std::thread::spawn(move || {
                let n = nether_core::worlds::dir_size(&cache);
                ui_do(&weak, move |ui| ui.global::<Backend>().set_cache_size(instpage::size(n).into()));
            });
        }
        let root = shared.paths.root.clone();
        b.on_open_data(move || { let _ = std::fs::create_dir_all(&root); let _ = std::process::Command::new("explorer").arg(&root).spawn(); });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        b.on_clear_cache(move || {
            let (weak, sh, cache) = (weak.clone(), sh.clone(), cache.clone());
            std::thread::spawn(move || {
                let freed = nether_core::worlds::dir_size(&cache);
                let _ = std::fs::remove_dir_all(&cache);
                ui_do(&weak, move |ui| {
                    ui.global::<Backend>().set_cache_size(instpage::size(0).into());
                    show_toast(ui, &sh, &format!("Cleared {} of cache", instpage::size(freed)));
                });
            });
        });
        let logs = applog::dir();
        b.on_open_logs(move || { let _ = std::fs::create_dir_all(&logs); let _ = std::process::Command::new("explorer").arg(&logs).spawn(); });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        b.on_copy_sysinfo(move || if let Some(ui) = weak.upgrade() { settings::copy_sysinfo(&ui, &sh) });
    }
    // Home news: newest Minecraft release/snapshot and Fabric loader
    {
        let weak = ui.as_weak();
        std::thread::spawn(move || {
            let items = nether_core::news::latest(&nether_core::download::client());
            // refresh published Nether packs so the (UI-thread) pack lists can read them from cache
            let _ = nether_core::backend::packs(&nether_core::download::client());
            ui_do(&weak, move |ui| {
                let rows: Vec<News> = items.iter().map(|n| News { tag: n.tag.as_str().into(), title: n.title.as_str().into(), url: n.url.as_str().into() }).collect();
                // L2: nothing fetched (offline): Home hides the news section instead of a placeholder row
                ui.global::<Backend>().set_news_failed(rows.is_empty());
                ui.global::<Backend>().set_news(ModelRc::new(VecModel::from(rows)));
            });
        });
    }
    refilter(&ui);
    // ---- launcher settings ----
    {
        let p = prefs::load(&shared.paths);
        backend.set_lp_gpu(p.gpu);
        backend.set_lp_autostart(p.autostart);
        backend.set_lp_on_launch(p.on_launch);
        backend.set_lp_on_close(p.on_close);
        backend.set_calm(p.calm);
        ui.global::<Theme>().set_calm(p.calm);
        backend.set_lp_accent(p.accent);
        backend.set_lp_theme(p.theme);
        backend.set_lp_text(p.text);
        backend.set_lp_capes(p.capes);
        backend.set_lp_discord(p.discord);
        backend.set_lp_auto_update(p.auto_update);
        backend.set_discord_ready(discord::app_id(&shared.paths).is_some());
        discord::enable(&shared.paths, p.discord);
        settings::apply_look(&ui, p.accent, p.text);
        settings::apply_theme(&ui, p.theme);
        client::set_look(&shared, settings::THEME_NAMES[p.theme.clamp(0, 3) as usize], settings::accent_hex(p.accent));
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_save_launcher(move || {
            let Some(ui) = weak.upgrade() else { return };
            let b = ui.global::<Backend>();
            let old = prefs::load(&sh.paths);
            let p = prefs::LauncherPrefs { gpu: b.get_lp_gpu(), autostart: b.get_lp_autostart(), on_launch: b.get_lp_on_launch(), on_close: b.get_lp_on_close(), calm: b.get_calm(), accent: b.get_lp_accent(), theme: b.get_lp_theme(), text: b.get_lp_text(), capes: b.get_lp_capes(), discord: b.get_lp_discord(), auto_update: b.get_lp_auto_update() };
            settings::apply_look(&ui, p.accent, p.text);
            settings::apply_theme(&ui, p.theme);
            client::set_look(&sh, settings::THEME_NAMES[p.theme.clamp(0, 3) as usize], settings::accent_hex(p.accent));
            discord::enable(&sh.paths, p.discord);
            ui.global::<Theme>().set_calm(p.calm);
            if p.autostart != old.autostart {
                if let Err(e) = prefs::set_autostart(p.autostart) { show_toast(&ui, &sh, &format!("Couldn't change Windows startup: {e}")); b.set_lp_autostart(old.autostart); return; }
            }
            if let Err(e) = prefs::save(&sh.paths, &p) { show_toast(&ui, &sh, &format!("Couldn't save the setting: {e}")); }
            skin::wake();
            if p.gpu != old.gpu { show_toast(&ui, &sh, "Restart the launcher to switch renderer"); }
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_upload_art(move |kind| {
            let Some(ui) = weak.upgrade() else { return };
            match artui::upload(&ui, &sh, kind) {
                Ok(()) => {}
                Err(e) => show_toast(&ui, &sh, &e),
            }
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_remove_art(move |kind, key| if let Some(ui) = weak.upgrade() { artui::remove(&ui, &sh, kind, &key) });
    }
    // close button: quit, or hide to the tray
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        ui.window().on_close_requested(move || {
            if prefs::load(&sh.paths).on_close == 1 {
                if let Some(ui) = weak.upgrade() { prefs::hide_to_tray(&ui, &sh.paths); }
            } else {
                let _ = slint::quit_event_loop();
            }
            slint::CloseRequestResponse::HideWindow
        });
    }
    artui::init(ui.as_weak(), shared.clone());
    host::init(&ui, shared.clone());
    if !snapshot {
        deeplink::unregister_old();
        deeplink::listen(&ui, shared.clone());
        // files the launcher was started with (a modpack opened with it)
        let files = deeplink::file_args();
        if !files.is_empty() {
            let (w, sh) = (ui.as_weak(), shared.clone());
            slint::Timer::single_shot(std::time::Duration::from_millis(600), move || if let Some(ui) = w.upgrade() { drop::dropped(&ui, &sh, files) });
        }
        // news about the last session: a damaged instance list, a launcher crash
        let crash = applog::take_crash_note();
        let (w, sh) = (ui.as_weak(), shared.clone());
        slint::Timer::single_shot(std::time::Duration::from_millis(900), move || if let Some(ui) = w.upgrade() {
            if let Some(n) = startup_note { show_toast(&ui, &sh, &n); }
            else if crash.is_some() { toast_action(&ui, &sh, "Nether Client closed unexpectedly last time. The log says why.", true, "VIEW LOG", |_| { let _ = std::process::Command::new("explorer").arg(applog::file()).spawn(); }); }
        });
        settings_catch_up(&shared);
        // look for a newer release a moment after start (quiet unless there is one)
        let (w, sh) = (ui.as_weak(), shared.clone());
        slint::Timer::single_shot(std::time::Duration::from_secs(4), move || if let Some(ui) = w.upgrade() { settings::check_updates(&ui, &sh, false) });
    }
    wardrobe::init(&ui, shared.clone());
    settings::init(&ui, shared.clone());
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_cover_for(move |v| if let Some(ui) = weak.upgrade() { artui::cover_for(&ui, &sh, v.to_string()) });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_set_inst_art(move |idx, icon, cover| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(i) = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) else { return };
            edit_inst(&ui, &sh, &i.name, |i| { i.icon = icon; i.cover = cover; });
            artui::refresh(&ui, &sh);
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_set_inst_shared(move |idx, on| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(i) = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) else { return };
            edit_inst(&ui, &sh, &i.name, |i| i.shared = on);
        });
    }
    {
        let weak = ui.as_weak();
        backend.on_pick_version(move |p| {
            let Some(ui) = weak.upgrade() else { return };
            let b = ui.global::<Backend>();
            if b.get_detail_pack() { packs::picked(&ui, p); return; }
            let mut d = b.get_detail();
            d.pick = p;
            b.set_detail(d);
        });
    }
    // ---- shared game settings ----
    {
        let p = gamesettings::load_prefs(&shared.paths);
        backend.set_sync_on(p.enabled);
        backend.set_sync_controls(p.controls);
        backend.set_sync_video(p.video);
        backend.set_sync_audio(p.audio);
        backend.set_sync_other(p.other);
        backend.set_sync_mods(p.mods);
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_save_sync(move || {
            let Some(ui) = weak.upgrade() else { return };
            let b = ui.global::<Backend>();
            let p = gamesettings::SyncPrefs { enabled: b.get_sync_on(), controls: b.get_sync_controls(), video: b.get_sync_video(), audio: b.get_sync_audio(), other: b.get_sync_other(), mods: b.get_sync_mods() };
            if let Err(e) = gamesettings::save_prefs(&sh.paths, &p) { show_toast(&ui, &sh, &format!("Couldn't save: {e}")); }
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_import_settings(move || {
            let Some(ui) = weak.upgrade() else { return };
            match gamesettings::import_official(&sh.paths) {
                Ok(0) => show_toast(&ui, &sh, "No Minecraft Launcher settings found to import"),
                Ok(n) => show_toast(&ui, &sh, &format!("Imported {n} settings from the Minecraft Launcher")),
                Err(e) => show_toast(&ui, &sh, &format!("Import failed: {e}")),
            }
        });
    }
    {
        let sh = shared.clone();
        backend.on_open_settings_file(move || {
            let f = gamesettings::global_file(&sh.paths);
            if f.exists() { let _ = std::process::Command::new("explorer").arg(format!("/select,{}", f.display())).spawn(); }
            else if let Some(d) = f.parent() { let _ = std::fs::create_dir_all(d); let _ = std::process::Command::new("explorer").arg(d).spawn(); }
        });
    }
    {
        let weak = ui.as_weak();
        backend.on_refilter(move || if let Some(ui) = weak.upgrade() { refilter(&ui) });
    }
    {
        let (weak, sh, ins2) = (ui.as_weak(), shared.clone(), insts.clone());
        backend.on_rename_instance(move |idx, name| {
            let Some(ui) = weak.upgrade() else { return false };
            rename_instance(&ui, &sh, &ins2, idx, name.trim())
        });
        let (weak, sh, insts) = (ui.as_weak(), shared.clone(), insts.clone());
        backend.on_create_instance(move |name, ver, loader, ram, icon, cover| {
            let Some(ui) = weak.upgrade() else { return false };
            let name = name.trim().to_string();
            if name.is_empty() { show_toast(&ui, &sh, "Give the instance a name"); return false; }
            if ver.is_empty() { show_toast(&ui, &sh, "Pick a Minecraft version"); return false; }
            if insts.iter().any(|i| sh.paths.instance(&i.name) == sh.paths.instance(&name)) || sh.paths.instance(&name).exists() {
                show_toast(&ui, &sh, &format!("An instance called {name} already exists"));
                return false;
            }
            if let Err(e) = std::fs::create_dir_all(sh.paths.instance(&name)) { show_toast(&ui, &sh, &format!("Couldn't create the folder: {e}")); return false; }
            let label = match loader { 1 => Loader::Fabric, 2 => Loader::Forge, 3 => Loader::NeoForge, 4 => Loader::Quilt, 5 => Loader::LegacyFabric, _ => Loader::Vanilla }.label();
            let mut i = inst(&name, &ver, label, 0, 0, ram.clamp(1, 16), "Global", "Never", false);
            i.secs = 0;
            i.icon = icon;
            i.cover = cover;
            insts.push(i);
            persist(&sh.paths, &*insts);
            refilter(&ui);
            artui::refresh(&ui, &sh);
            show_toast(&ui, &sh, &format!("{name} created"));
            true
        });
    }
    {
        let (weak, sh, insts) = (ui.as_weak(), shared.clone(), insts.clone());
        backend.on_inst_action(move |idx, action| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(i) = insts.row_data(idx.max(0) as usize) else { return };
            let name = i.name.to_string();
            let dir = sh.paths.instance(&name);
            match action {
                // open folder
                0 => {
                    let _ = std::fs::create_dir_all(&dir);
                    let _ = std::process::Command::new("explorer").arg(&dir).spawn();
                }
                // duplicate (copies worlds, mods and settings)
                1 => {
                    let taken: Vec<String> = insts.iter().map(|i| i.name.to_string()).collect();
                    let copy = instances::free_name(&sh.paths, &format!("{name} copy"), &taken);
                    let key = format!("duplicate:{name}");
                    job(&ui, &key, &format!("Duplicating {name}…"), -1.0);
                    let (weak, sh) = (weak.clone(), sh.clone());
                    std::thread::spawn(move || {
                        let res = instances::duplicate(&sh.paths, &name, &copy);
                        ui_do(&weak, move |ui| match job_done(ui, &key, res) {
                            Ok(()) => {
                                let m = ui.global::<Backend>().get_instances();
                                let src = m.iter().find(|x| x.name == name);
                                if let (Some(v), Some(mut n)) = (m.as_any().downcast_ref::<VecModel<Inst>>(), src) {
                                    n.name = copy.as_str().into();
                                    n.title = copy.to_uppercase().into();
                                    n.running = false;
                                    v.push(n);
                                }
                                persist(&sh.paths, &m);
                                refilter(ui);
                                artui::refresh(ui, &sh);
                                show_toast(ui, &sh, &format!("{copy} created"));
                            }
                            Err(e) => show_toast(ui, &sh, &format!("Couldn't duplicate: {e}")),
                        });
                    });
                }
                // export as .mrpack into Downloads
                2 => {
                    let key = format!("export:{name}");
                    job(&ui, &key, &format!("Exporting {name}…"), -1.0);
                    let data = InstanceData { name: name.clone(), version: i.ver.to_string(), loader: i.loader.to_string(), ram_gb: i.ram as u32, hours: 0, mods: 0, client: String::new(), last: String::new(), java: 0, played_secs: 0, last_ts: 0, icon: String::new(), cover: String::new(), shared_settings: true, ..Default::default() };
                    let (weak, sh) = (weak.clone(), sh.clone());
                    std::thread::spawn(move || {
                        let base = Some(instpage::dirs_downloads()).filter(|p| p.is_dir()).unwrap_or_else(|| sh.paths.root.join("exports"));
                        let safe: String = name.chars().map(|c| if c.is_alphanumeric() || c == ' ' || c == '-' { c } else { '_' }).collect();
                        let out = base.join(format!("{safe}.mrpack"));
                        let res = instances::export_mrpack(&nether_core::download::client(), &sh.paths, &data, &out);
                        ui_do(&weak, move |ui| match job_done(ui, &key, res) {
                            Ok(()) => {
                                show_toast(ui, &sh, &format!("Exported {}", out.file_name().and_then(|n| n.to_str()).unwrap_or("")));
                                let _ = std::process::Command::new("explorer").arg(format!("/select,{}", out.display())).spawn();
                            }
                            Err(e) => show_toast(ui, &sh, &format!("Export failed: {e}")),
                        });
                    });
                }
                // delete (the UI asks for confirmation first)
                3 => {
                    if i.running || i.starting { show_toast(&ui, &sh, "Close the game before deleting this instance"); return; }
                    insts.remove(idx as usize);
                    persist(&sh.paths, &*insts);
                    let b = ui.global::<Backend>();
                    let a = b.get_active_inst();
                    b.set_active_inst(if a > idx { a - 1 } else if a == idx { 0 } else { a });
                    refilter(&ui);
                    let (weak, sh) = (weak.clone(), sh.clone());
                    std::thread::spawn(move || {
                        let res = instances::delete(&sh.paths, &name);
                        ui_do(&weak, move |ui| show_toast(ui, &sh, &match res { Ok(()) => format!("{name} moved to the Recycle Bin"), Err(e) => format!("Couldn't remove all files: {e}") }));
                    });
                }
                // L1: verify game files, update history, support bundle
                4 => instpage::verify(&ui, &sh, &i),
                5 => snapshot::history(&ui, &sh, &name),
                6 => instpage::support_bundle(&ui, &sh, &i),
                _ => {}
            }
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_set_inst_ram(move |idx, gb| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(i) = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) else { return };
            edit_inst(&ui, &sh, &i.name, |i| i.ram = gb.clamp(1, 16));
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_set_inst_java(move |idx, j| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(i) = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) else { return };
            edit_inst(&ui, &sh, &i.name, |i| i.java = j.clamp(0, 4));
        });
    }
    // launch options, worlds, logs, crash dialog, import, stop
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_set_inst_jvm(move |idx, s| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(i) = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) else { return };
            edit_inst(&ui, &sh, &i.name, |i| i.jvm = s.trim().into());
        });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_set_inst_window(move |idx, w, h| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(i) = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) else { return };
            let num = |s: &str| s.trim().parse::<i32>().unwrap_or(0).clamp(0, 16384);
            let (w, h) = (num(&w), num(&h));
            edit_inst(&ui, &sh, &i.name, |i| { i.win_w = w; i.win_h = h; });
        });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_load_worlds(move || if let Some(ui) = weak.upgrade() { instpage::load_worlds(&ui, &sh) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_world_action(move |idx, row, a| if let Some(ui) = weak.upgrade() { instpage::world_action(&ui, &sh, idx, row, a) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_join_server(move |idx, addr| if let Some(ui) = weak.upgrade() { instpage::join_server(&ui, &sh, idx, &addr) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_load_log(move |k| if let Some(ui) = weak.upgrade() { instpage::load_log(&ui, &sh, k) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_share_log(move |idx| if let Some(ui) = weak.upgrade() { instpage::share_log(&ui, &sh, idx) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_import_instance(move || if let Some(ui) = weak.upgrade() { instpage::import_instance(&ui, &sh) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_stop(move |n| if let Some(ui) = weak.upgrade() { instpage::stop(&ui, &sh, &n) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_measure_inst(move || if let Some(ui) = weak.upgrade() { instpage::measure_inst(&ui, &sh) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_content_folder(move |idx| if let Some(ui) = weak.upgrade() { instpage::content_folder(&ui, &sh, idx) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_add_content_files(move |idx| if let Some(ui) = weak.upgrade() { instpage::add_content_files(&ui, &sh, idx) });
        let weak = ui.as_weak();
        backend.on_filter_worlds(move || if let Some(ui) = weak.upgrade() { instpage::filter_worlds(&ui) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_import_world(move |idx| if let Some(ui) = weak.upgrade() { instpage::import_world(&ui, &sh, idx) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_load_shots(move || if let Some(ui) = weak.upgrade() { instpage::load_shots(&ui, &sh) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_shot_action(move |r, a| if let Some(ui) = weak.upgrade() { instpage::shot_action(&ui, &sh, r, a) });
        let weak = ui.as_weak();
        backend.on_ask_answer(move |yes| if let Some(ui) = weak.upgrade() { instpage::ask_answer(&ui, yes) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_crash_fix(move |i| if let Some(ui) = weak.upgrade() { instpage::crash_fix(&ui, &sh, i) });
        // L1 callbacks
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_pre_fix(move |i| if let Some(ui) = weak.upgrade() { preflight::fix(&ui, &sh, i) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_pre_launch(move || if let Some(ui) = weak.upgrade() { preflight::launch(&ui, &sh) });
        let weak = ui.as_weak();
        backend.on_upd_pick(move |i, v| if let Some(ui) = weak.upgrade() { snapshot::pick(&ui, i, v) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_upd_apply(move || if let Some(ui) = weak.upgrade() { snapshot::apply(&ui, &sh) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_hist_revert(move |id| if let Some(ui) = weak.upgrade() { let n = ui.global::<Backend>().get_hist_inst().to_string(); snapshot::revert(&ui, &sh, &n, &id) });
        // dialogs keep Tab inside: a sentinel asks for one more Tab / Shift+Tab step (ignored when it repeats at once)
        let weak = ui.as_weak();
        let last = std::cell::Cell::new(std::time::Instant::now() - std::time::Duration::from_secs(1));
        backend.on_focus_step(move |back| {
            if last.get().elapsed() < std::time::Duration::from_millis(25) { return; }
            last.set(std::time::Instant::now());
            let w = weak.clone();
            slint::Timer::single_shot(std::time::Duration::ZERO, move || if let Some(ui) = w.upgrade() {
                let k: slint::SharedString = if back { slint::platform::Key::Backtab } else { slint::platform::Key::Tab }.into();
                ui.window().dispatch_event(slint::platform::WindowEvent::KeyPressed { text: k.clone() });
                ui.window().dispatch_event(slint::platform::WindowEvent::KeyReleased { text: k });
            });
        });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_crash_upload(move || if let Some(ui) = weak.upgrade() { instpage::crash_upload(&ui, &sh) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_crash_relaunch(move || if let Some(ui) = weak.upgrade() { instpage::crash_relaunch(&ui, &sh) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_set_inst_fullscreen(move |idx, v| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(i) = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) else { return };
            edit_inst(&ui, &sh, &i.name, |i| i.fullscreen = v);
        });
        let gb = (instances::system_ram_mb() / 1024) as i32;
        if gb > 0 { backend.set_sys_ram(gb); }
    }
    // L2: restore points, groups + notes, command palette
    {
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_load_rpoints(move || if let Some(ui) = weak.upgrade() { restore::load(&ui, &sh) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_rp_create(move || if let Some(ui) = weak.upgrade() { restore::create(&ui, &sh) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_rp_restore(move |r, in_place| if let Some(ui) = weak.upgrade() { restore::restore(&ui, &sh, r, in_place) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_rp_delete(move |r| if let Some(ui) = weak.upgrade() { restore::delete(&ui, &sh, r) });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_set_inst_backup(move |idx, auto, keep| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(i) = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) else { return };
            edit_inst(&ui, &sh, &i.name, |i| { i.bk_auto = auto; i.bk_keep = keep.clamp(1, 50); });
        });
        let (weak, sh) = (ui.as_weak(), shared.clone());
        backend.on_set_inst_group(move |idx, g| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(i) = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) else { return };
            let g: String = g.trim().chars().filter(|c| !c.is_control()).take(40).collect();
            edit_inst(&ui, &sh, &i.name, |i| i.group = g.as_str().into());
        });
        let weak = ui.as_weak();
        backend.on_fold_group(move |g| if let Some(ui) = weak.upgrade() {
            FOLDED.with(|f| { let mut f = f.borrow_mut(); if !f.remove(g.as_str()) { f.insert(g.to_string()); } });
            refilter(&ui);
        });
        // notes: the row changes as you type; saved once typing pauses
        let (weak, sh) = (ui.as_weak(), shared.clone());
        let save_timer = Rc::new(slint::Timer::default());
        backend.on_set_inst_notes(move |idx, text| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(i) = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) else { return };
            edit_inst_quiet(&ui, &i.name, |i| i.notes = text);
            let (w, sh) = (weak.clone(), sh.clone());
            save_timer.start(slint::TimerMode::SingleShot, std::time::Duration::from_millis(600), move || if let Some(ui) = w.upgrade() {
                persist(&sh.paths, &ui.global::<Backend>().get_instances());
            });
        });
        palette::wire(&ui, &shared);
    }
    // toast action / dismiss, deck cancel
    {
        let weak = ui.as_weak();
        backend.on_toast_act(move || if let Some(ui) = weak.upgrade() {
            ui.global::<Backend>().set_toast_on(false);
            if let Some(f) = TOAST_ACT.with(|t| t.borrow_mut().take()) { f(&ui); }
        });
        let weak = ui.as_weak();
        backend.on_toast_close(move || if let Some(ui) = weak.upgrade() { ui.global::<Backend>().set_toast_on(false); });
        backend.on_cancel_job(|| JOBS.with(|j| if let Some(c) = j.borrow().last().and_then(|x| x.cancel.clone()) { c.store(true, Ordering::SeqCst); }));
    }

    // `--snapshot out.png [tab] [open-instance]`: render one frame to PNG and exit (visual checks).
    let args: Vec<String> = std::env::args().collect();
    if let Some(i) = args.iter().position(|a| a == "--snapshot").filter(|_| snapshot) {
        let path = args.get(i + 1).cloned().unwrap_or_else(|| "snapshot.png".into());
        let tab: i32 = args.get(i + 2).and_then(|t| t.parse().ok()).unwrap_or(0);
        let open: i32 = args.get(i + 3).and_then(|t| t.parse().ok()).unwrap_or(-1);
        ui.set_tab(tab);
        if open >= 0 { ui.set_open_idx(open); }
        ui.set_open_tab(args.get(i + 4).and_then(|t| t.parse().ok()).unwrap_or(0));
        ui.set_open_mode(args.get(i + 5).and_then(|t| t.parse().ok()).unwrap_or(0));
        // snapshot extras: [gate 0/1] [detail row] -> hide/show the sign-in gate, open the mod detail drawer
        let gate: i32 = args.get(i + 6).and_then(|t| t.parse().ok()).unwrap_or(0);
        let det: i32 = args.get(i + 7).and_then(|t| t.parse().ok()).unwrap_or(-1);
        ui.global::<Backend>().set_gate_open(gate == 1);
        // NC_SNAP_WPAGE=n: Wardrobe page (0 skins, 1 capes, 2.. cosmetic slots in Kind::ALL order)
        if let Some(n) = std::env::var("NC_SNAP_WPAGE").ok().and_then(|n| n.parse::<i32>().ok()) { ui.set_ward_page(n); }
        // NC_SNAP_STATE: dev-only page states for captures (see host::snap, wardrobe::snap)
        if let Ok(st) = std::env::var("NC_SNAP_STATE") {
            host::snap(&ui, &st);
            wardrobe::snap(&ui, &shared, &st);
            // L1 dialogs with sample rows (captures only)
            if st == "preflight" {
                use nether_core::crash::{Action, Finding};
                let f = vec![
                    Finding { id: "duplicate-mods", title: "Sodium is installed twice".into(), explanation: "sodium-0.6.0.jar and sodium-0.6.3.jar both contain the mod \"sodium\". The game refuses to start with two copies.".into(), fix: "Disable the older copy (sodium-0.6.0.jar).".into(), action: Some(Action::Disable("a".into())) },
                    Finding { id: "missing-dependency", title: "fabric-api is missing".into(), explanation: "Sodium Extra and Reese's Sodium Options need \"fabric-api\", which is not installed. The game stops while loading without it.".into(), fix: "Install fabric-api from Modrinth (the build for Fabric 1.21.11).".into(), action: Some(Action::Install("fabric-api".into())) },
                    Finding { id: "ram", title: "Too little memory left for Windows".into(), explanation: "14 of this PC's 16 GB go to the game. Windows and other apps need the rest, or the game stutters and can crash.".into(), fix: "Use 12 GB. 4-8 GB suits most modpacks.".into(), action: Some(Action::SetRam(12)) },
                ];
                preflight::show(&ui, preflight::Held { name: "Survival".into(), quick: None }, f);
            }
            if st == "update" {
                let b = ui.global::<Backend>();
                let row = |n: &str, f: &str, t: &str, notes: &str, open: bool| UpdRow { name: n.into(), from: f.into(), to: t.into(), notes: notes.into(), on: true, open };
                b.set_upd_rows(ModelRc::new(VecModel::from(vec![
                    row("Sodium", "v0.6.1", "v0.6.3", "Fixed a crash with some AMD drivers when resizing the window. Improved chunk meshing speed by about 10%. Translations updated.", true),
                    row("Lithium", "v0.14.3", "v0.15.0", "Faster entity collisions; fixes a rare desync with hoppers.", false),
                    row("Iris Shaders", "v1.8.0", "v1.8.1", "", false),
                ])));
                b.set_upd_picked(3); b.set_upd_inst("Survival".into()); b.set_upd_state(1); b.set_upd_open(true);
            }
            if st == "history" {
                let b = ui.global::<Backend>();
                b.set_hist_rows(ModelRc::new(VecModel::from(vec![
                    HistRow { id: "1".into(), title: "Updated 3 mods".into(), sub: "Sodium v0.6.1 → v0.6.3  ·  Lithium v0.14.3 → v0.15.0  ·  +1 more".into(), when: "2h ago".into() },
                    HistRow { id: "2".into(), title: "Updated Iris Shaders".into(), sub: "Iris Shaders v1.7.6 → v1.8.0".into(), when: "4 days ago".into() },
                ])));
                b.set_hist_inst("Survival".into()); b.set_hist_open(true);
            }
            if st == "ask" { instpage::ask(&ui, "Delete My World?", "The world is removed for good. Back it up first if you might want it again.", "DELETE WORLD", true, |_| {}); }
            if st == "crash" {
                let b = ui.global::<Backend>();
                let mut f = nether_core::crash::analyze("java.lang.OutOfMemoryError: Java heap space", Some(1));
                f.push(nether_core::crash::Finding { id: "last-mod", title: "Try without the newest mod".into(), explanation: "sodium-extra-0.6.1.jar is the most recently added mod. A crash right after adding a mod usually comes from that mod.".into(), fix: "Disable it and play again. Turn it back on any time in the Mods tab.".into(), action: Some(nether_core::crash::Action::Disable("x.jar".into())) });
                b.set_crash_items(ModelRc::new(VecModel::from(preflight::rows(&f))));
                b.set_crash_inst("Survival".into()); b.set_crash_code("1".into()); b.set_crash_tail("[12:00:01] [Render thread/ERROR]: Unreported exception thrown!
java.lang.OutOfMemoryError: Java heap space
	at net.minecraft.class_1234.method_5678(class_1234.java:42)".into()); b.set_crash_open(true);
                if std::env::var("NC_SNAP_CRASH2").is_ok() { b.set_crash_state(2); b.set_crash_url("https://mclo.gs/AbC123x".into()); b.set_crash_title("FABRIC 1.21.11 CLIENT LOG".into()); b.set_crash_facts(ModelRc::new(VecModel::from(vec![slint::SharedString::from("Java version: 21"), "Mods: 42".into()]))); b.set_crash_problems(ModelRc::new(VecModel::from(vec![CrashProblem { title: "The mod Sodium Extra is missing the required mod fabric-api.".into(), fixes: "→ Install the mod fabric-api.".into() }]))); }
            }
        }
        // [overlay]: 1 create-instance dialog, 2 account panel, 3 delete confirmation; NC_SNAP_SIZE=WxH sets the window size
        match args.get(i + 8).and_then(|t| t.parse::<i32>().ok()).unwrap_or(0) {
            1 => ui.global::<Backend>().set_create_open(true),
            2 => ui.global::<Backend>().set_acct_open(true),
            3 => ui.set_snap_confirm(true),
            _ => {}
        }
        if let Some((w, h)) = std::env::var("NC_SNAP_SIZE").ok().and_then(|s| s.split_once('x').map(|(w, h)| (w.parse::<u32>().unwrap_or(1440), h.parse::<u32>().unwrap_or(900)))) {
            ui.window().set_size(slint::PhysicalSize::new(w, h));
        }
        if let Some(n) = std::env::var("NC_SNAP_STEP").ok().and_then(|n| n.parse::<i32>().ok()) { ui.global::<Wizard>().set_snap_step(n); }
        // NC_SNAP_TABS=n: press Tab n times before the capture (keyboard focus checks)
        if let Some(n) = std::env::var("NC_SNAP_TABS").ok().and_then(|n| n.parse::<u32>().ok()) {
            let w = ui.as_weak();
            slint::Timer::single_shot(std::time::Duration::from_millis(2500), move || if let Some(ui) = w.upgrade() {
                for _ in 0..n {
                    let k: slint::SharedString = slint::platform::Key::Tab.into();
                    ui.window().dispatch_event(slint::platform::WindowEvent::KeyPressed { text: k.clone() });
                    ui.window().dispatch_event(slint::platform::WindowEvent::KeyReleased { text: k });
                }
            });
        }
        // NC_SNAP_LOOK=accent,text: preview the accent colour / text size without saving them
        if let Some((a, t)) = std::env::var("NC_SNAP_LOOK").ok().and_then(|s| s.split_once(',').map(|(a, t)| (a.parse().unwrap_or(0), t.parse().unwrap_or(0)))) {
            ui.global::<Backend>().set_lp_accent(a);
            ui.global::<Backend>().set_lp_text(t);
            settings::apply_look(&ui, a, t);
        }
        let src = if args.get(i + 5).map(|s| s == "1").unwrap_or(false) { 0 } else { 1 };
        if det >= 0 {
            let w = ui.as_weak();
            slint::Timer::single_shot(std::time::Duration::from_millis(2500), move || {
                if let Some(ui) = w.upgrade() { ui.global::<Backend>().invoke_open_detail(src, det); }
            });
        }
        let weak = ui.as_weak();
        slint::Timer::single_shot(std::time::Duration::from_millis(std::env::var("NC_SNAP_DELAY").ok().and_then(|d| d.parse().ok()).unwrap_or(if det >= 0 { 6000 } else { 3500 })), move || {
            if let Some(ui) = weak.upgrade() {
                match ui.window().take_snapshot() {
                    Ok(buf) => if let Err(e) = image::save_buffer(&path, buf.as_bytes(), buf.width(), buf.height(), image::ColorType::Rgba8) { eprintln!("snapshot save failed: {e}") },
                    Err(e) => eprintln!("snapshot failed: {e}"),
                }
                let _ = slint::quit_event_loop();
            }
        });
    }

    // window procedure events: maximize state (snap, double-click) and files dropped from Explorer
    winchrome::set_handlers(winchrome::Handlers {
        maxed: |m| with_app(|ui, _| if ui.global::<Backend>().get_win_maxed() != m { ui.global::<Backend>().set_win_maxed(m) }),
        dropped: |files| with_app(|ui, sh| drop::dropped(ui, sh, files)),
        activated: skin::wake,
    });
    // keep running while hidden in the tray; quit explicitly
    ui.show()?;
    // the native window only exists once the event loop runs: style it from there (retry until it does)
    let chrome_timer = Rc::new(slint::Timer::default());
    {
        let (w, t) = (ui.as_weak(), chrome_timer.clone());
        chrome_timer.start(slint::TimerMode::Repeated, std::time::Duration::from_millis(10), move || {
            if w.upgrade().is_some_and(|ui| winchrome::borderless(ui.window())) { t.stop(); }
        });
    }
    {
        let w = ui.as_weak();
        ui.global::<Backend>().on_win_drag(move || if let Some(ui) = w.upgrade() { winchrome::drag(ui.window()) });
        let w = ui.as_weak();
        ui.global::<Backend>().on_win_min(move || if let Some(ui) = w.upgrade() { winchrome::minimize(ui.window()) });
        let w = ui.as_weak();
        ui.global::<Backend>().on_win_max(move || if let Some(ui) = w.upgrade() { winchrome::toggle_max(ui.window()); ui.global::<Backend>().set_win_maxed(winchrome::is_max(ui.window())); });
        let w = ui.as_weak();
        ui.global::<Backend>().on_win_close(move || if let Some(ui) = w.upgrade() { ui.window().dispatch_event(slint::platform::WindowEvent::CloseRequested); });
    }
    let r = slint::run_event_loop_until_quit();
    host::shutdown();
    // a downloaded update installs now that the launcher is closing (Setup waits for this process to exit)
    settings::apply_pending_update(false);
    r
}


/// Launcher-path check (manual): `NC_TEST_NAME=.. NC_TEST_VER=1.21.11 NC_TEST_LOADER=Fabric NC_TEST_SECS=60 NETHER_DEV_OFFLINE=1
/// cargo test -p nether-app --release --features dev launcher_path -- --ignored --nocapture`
/// Builds the options exactly as Play does (`play_session` + `launch_opts`), starts the game, stops it after the timeout.
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[ignore]
    fn launcher_path() {
        let env = |k: &str, d: &str| std::env::var(k).unwrap_or_else(|_| d.into());
        let sh = Shared { paths: Paths::new(), client_id: None, session: Mutex::new(None), cancel: AtomicBool::new(false), toast_id: AtomicU32::new(0), running: Mutex::new(Default::default()), launching: Mutex::new(Default::default()), stopping: Mutex::new(Default::default()) };
        let (name, ver, loader) = (env("NC_TEST_NAME", "nctest"), env("NC_TEST_VER", "1.21.11"), env("NC_TEST_LOADER", "Fabric"));
        let secs: u64 = env("NC_TEST_SECS", "60").parse().unwrap();
        let session = play_session(&sh).expect("build with --features dev and set NETHER_DEV_OFFLINE=1");
        let opts = launch_opts(&sh, &name, &ver, 2, &loader, 0, true, session);
        println!("client_config = {:?}", opts.client_config);
        let progress: Progress = Arc::new(|m, f| println!("[{:>3}%] {m}", (f * 100.0) as i32));
        let mut child = launch(&sh.paths, &opts, progress).unwrap();
        let end = std::time::Instant::now() + std::time::Duration::from_secs(secs);
        while std::time::Instant::now() < end {
            if let Some(st) = child.try_wait().unwrap() { println!("exited early: {st}"); return; }
            std::thread::sleep(std::time::Duration::from_millis(500));
        }
        let _ = child.kill();
        let _ = child.wait();
    }
}
