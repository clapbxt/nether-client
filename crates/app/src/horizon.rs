//! Play > Horizon: one ready-made way to play Horizon (Minecraft 26.3 rewritten in Rust, horizon-rust/horizon). PLAY
//! installs or updates its signed build, prepares it from the player's own 26.3 the first time, and starts it with the
//! signed-in account. It has its own game folder (`<data>/horizon/game`) and is never a Java profile: no Nether
//! client, no Java mods. See `nether_core::horizon`.

use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

use nether_core::horizon;
use nether_core::Progress;
use slint::ComponentHandle;

use crate::{applog, auth, discord, friendly, job, job_cancelable, job_end, play_session, prefs, show_toast, span, toast_action, ui_do, AppWindow, Backend, Shared};

/// Key in `Shared.running` / `launching` / `stopping` (instance names can't hold ':').
pub const KEY: &str = "horizon:game";

pub fn init(ui: &AppWindow, sh: Arc<Shared>) {
    show_state(ui, &sh);
    let b = ui.global::<Backend>();
    let (weak, s2) = (ui.as_weak(), sh.clone());
    b.on_hz_play(move || if let Some(ui) = weak.upgrade() { play(&ui, &s2) });
    let (weak, s2) = (ui.as_weak(), sh.clone());
    b.on_hz_stop(move || if let Some(ui) = weak.upgrade() {
        if s2.running.lock().unwrap_or_else(|e| e.into_inner()).contains_key(KEY) {
            ui.global::<Backend>().set_hz_state(3);
            crate::instpage::stop(&ui, &s2, KEY);
        }
    });
    let s2 = sh.clone();
    b.on_hz_folder(move || {
        let dir = horizon::game_dir(&s2.paths);
        let _ = std::fs::create_dir_all(&dir);
        let _ = std::process::Command::new("explorer").arg(&dir).spawn();
    });
    b.on_hz_site(|| { let _ = webbrowser::open(horizon::PROJECT_URL); });
    // the card art: Minecraft 26.x's key art, like the Nether 26 card
    art(ui, &sh);
}

fn art(ui: &AppWindow, sh: &Arc<Shared>) {
    if let Some(i) = crate::artui::card_art(ui, sh, horizon::MINECRAFT, art) {
        let b = ui.global::<Backend>();
        b.set_hz_art(i);
        b.set_hz_has_art(true);
    }
}

fn show_state(ui: &AppWindow, sh: &Arc<Shared>) {
    let st = horizon::state(&sh.paths);
    let b = ui.global::<Backend>();
    b.set_hz_build(horizon::installed(&sh.paths).map(|(v, _)| horizon::pretty(&v)).unwrap_or_default().into());
    b.set_hz_played(if st.secs >= 60 { format!("{} played", span(st.secs)) } else { String::new() }.into());
}

/// Clears the "starting" state however the start thread ends.
struct Starting { weak: slint::Weak<AppWindow>, sh: Arc<Shared> }
impl Drop for Starting {
    fn drop(&mut self) {
        self.sh.launching.lock().unwrap_or_else(|e| e.into_inner()).remove(KEY);
        let sh = self.sh.clone();
        ui_do(&self.weak, move |ui| {
            job_end(ui, KEY);
            let b = ui.global::<Backend>();
            if b.get_hz_state() == 1 { b.set_hz_state(if sh.running.lock().unwrap_or_else(|e| e.into_inner()).contains_key(KEY) { 2 } else { 0 }); }
            show_state(ui, &sh);
        });
    }
}

fn play(ui: &AppWindow, sh: &Arc<Shared>) {
    if sh.running.lock().unwrap_or_else(|e| e.into_inner()).contains_key(KEY) { return; }
    let cancel = Arc::new(AtomicBool::new(false));
    {
        let mut l = sh.launching.lock().unwrap_or_else(|e| e.into_inner());
        if l.contains_key(KEY) { return; }
        l.insert(KEY.into(), cancel.clone());
    }
    ui.global::<Backend>().set_hz_state(1);
    job_cancelable(ui, KEY, "Horizon · Preparing", 0.0, cancel.clone());
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let guard = Starting { weak: weak.clone(), sh: sh.clone() };
        // Horizon is for signed-in owners of Java Edition only (its Launch API, step 0), like every launch here
        let session = match play_session(&sh) {
            Ok(s) => s,
            Err(msg) => {
                let sh3 = sh.clone();
                ui_do(&weak, move |ui| { ui.global::<Backend>().set_gate_open(true); show_toast(ui, &sh3, &msg); });
                return;
            }
        };
        let session = match (&sh.client_id, session.user_type == "legacy") {
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
        let w2 = weak.clone();
        let progress: Progress = Arc::new(move |msg, f| {
            let pct = (f * 100.0) as i32;
            let mut l = last.lock().unwrap_or_else(|e| e.into_inner());
            if l.0 == msg && pct == l.1 { return; }
            *l = (msg.to_string(), pct);
            let text = format!("Horizon · {msg}");
            ui_do(&w2, move |ui| job(ui, KEY, &text, f));
        });
        let c = nether_core::download::client();
        let started = (|| -> anyhow::Result<std::process::Child> {
            // the newest signed build; offline (or GitHub down) the installed one still plays
            let build = match horizon::latest(&c) {
                Ok(b) => match horizon::installed(&sh.paths) {
                    Some((v, dir)) if v == b.version => dir,
                    have => match horizon::install(&c, &sh.paths, &b, &progress, Some(&cancel)) {
                        Ok(dir) => dir,
                        Err(e) if have.is_some() && !nether_core::download::is_cancelled(&e) => {
                            applog::line(&format!("horizon: update to {} failed, playing the installed build: {e:#}", b.version));
                            have.unwrap().1
                        }
                        Err(e) => return Err(e),
                    },
                },
                Err(e) => match horizon::installed(&sh.paths) {
                    Some((_, dir)) => { applog::line(&format!("horizon: no update check ({e:#})")); dir }
                    None => return Err(e.context("Couldn't reach Horizon's downloads on GitHub")),
                },
            };
            progress("Checking the game files", 0.56);
            if !horizon::ready(&sh.paths, &build) { horizon::prepare(&c, &sh.paths, &build, &progress, Some(&cancel))?; }
            nether_core::download::check_cancel(Some(&cancel))?;
            progress("Starting Horizon", 0.99);
            horizon::launch(&sh.paths, &build, &session, None)
        })();
        match started {
            Ok(mut child) => {
                sh.running.lock().unwrap_or_else(|e| e.into_inner()).insert(KEY.into(), child.id());
                applog::line(&format!("started Horizon, pid {}", child.id()));
                discord::idle(&sh.paths, false);
                let sh3 = sh.clone();
                ui_do(&weak, move |ui| {
                    let b = ui.global::<Backend>();
                    b.set_hz_state(2);
                    b.set_running_count(b.get_running_count() + 1);
                    match prefs::load(&sh3.paths).on_launch { 1 => ui.window().set_minimized(true), 2 => prefs::hide_to_tray(ui, &sh3.paths), _ => {} }
                    crate::running_label(ui);
                    show_toast(ui, &sh3, "Horizon started");
                });
                drop(guard);
                let t0 = std::time::Instant::now();
                let status = child.wait();
                let stopped = sh.stopping.lock().unwrap_or_else(|e| e.into_inner()).remove(KEY);
                sh.running.lock().unwrap_or_else(|e| e.into_inner()).remove(KEY);
                let played = t0.elapsed().as_secs() as i64;
                let mut st = horizon::state(&sh.paths);
                st.secs += played;
                st.last_ts = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
                let _ = horizon::save_state(&sh.paths, &st);
                let code = status.as_ref().ok().and_then(|s| s.code());
                applog::line(&format!("Horizon exited ({code:?}) after {played} s{}", if stopped { ", stopped by the user" } else { "" }));
                let (report, refused) = (horizon::crash_report(&sh.paths), horizon::refused(&sh.paths));
                let sh3 = sh.clone();
                ui_do(&weak, move |ui| {
                    let b = ui.global::<Backend>();
                    b.set_hz_state(0);
                    b.set_running_count((b.get_running_count() - 1).max(0));
                    if b.get_running_count() == 0 { discord::idle(&sh3.paths, true); }
                    if prefs::load(&sh3.paths).on_launch != 0 && b.get_running_count() == 0 { prefs::show(ui); }
                    crate::running_label(ui);
                    show_state(ui, &sh3);
                    match code {
                        _ if stopped => show_toast(ui, &sh3, "Stopped Horizon"),
                        Some(0) => if played >= 60 { show_toast(ui, &sh3, &format!("Played Horizon for {}", span(played))) },
                        Some(2) => show_toast(ui, &sh3, &format!("Horizon refused to start: {}", refused.unwrap_or_default())),
                        _ => {
                            let log = horizon::game_dir(&sh3.paths).join("logs").join("launcher.log");
                            let file = report.unwrap_or(log);
                            toast_action(ui, &sh3, "Horizon stopped on an error", true, "VIEW REPORT", move |_| { let _ = std::process::Command::new("explorer").arg(&file).spawn(); });
                        }
                    }
                });
            }
            Err(e) if nether_core::download::is_cancelled(&e) => {
                let sh3 = sh.clone();
                ui_do(&weak, move |ui| show_toast(ui, &sh3, "Stopped starting Horizon. Downloads so far are kept for next time"));
            }
            Err(e) => {
                applog::line(&format!("horizon: couldn't start: {e:#}"));
                let (sh3, msg) = (sh.clone(), format!("Couldn't start Horizon: {}", friendly(&e)));
                ui_do(&weak, move |ui| toast_action(ui, &sh3, &msg, true, "VIEW LOG", |_| { let _ = std::process::Command::new("explorer").arg(applog::file()).spawn(); }));
            }
        }
    });
}
