//! First-run setup walkthrough (2026-11-20; research: vault `research/Launcher onboarding research.md`, brief
//! `projects/Launcher setup.md`). Five steps over the whole window: welcome, Microsoft account, Java & memory,
//! the first profile, the first launch. Everything is chosen *for* the profile being made (its memory, which
//! ready-made card, or the Nether profile maker), never inside a game. Shown once, on a fresh install only: no
//! profiles, no saved sign-in and not finished before (prefs `setup_done`).
use std::cell::Cell;
use std::sync::Arc;

use nether_core::{auth, instances};
use slint::{ComponentHandle, Model};

use crate::{prefs, presets, AppWindow, Backend, Setup, Shared};

/// The ready-made profiles step 4 offers (Home's presets, presets.rs PRESETS ids) and the Java each runs on.
const PICKS: [(&str, &str, u32); 2] = [("modern", "1.21", 21), ("pvp189", "1.8", 8)];

thread_local! {
    /// the launch the walkthrough started was seen starting (so "not starting any more" means it stopped)
    static SEEN: Cell<bool> = const { Cell::new(false) };
}

pub fn init(ui: &AppWindow, sh: Arc<Shared>) {
    let (b, s) = (ui.global::<Backend>(), ui.global::<Setup>());
    let sys = (instances::system_ram_mb() / 1024) as i32;
    let p = prefs::load(&sh.paths);
    s.set_rec_ram(prefs::rec_ram(sys.max(1)));
    b.set_new_ram(prefs::new_profile_ram(&sh.paths, sys.max(1)));
    let fresh = !p.setup_done && b.get_instances().row_count() == 0 && !auth::has_saved_login(&sh.paths);
    // NC_SNAP_SETUP=<step>[,<phase>]: open it on a step for snapshots
    let snap = std::env::var("NC_SNAP_SETUP").ok();
    if let Some(v) = &snap {
        let mut it = v.split(',').filter_map(|n| n.trim().parse::<i32>().ok());
        s.set_step(it.next().unwrap_or(0));
        s.set_phase(it.next().unwrap_or(0));
    }
    if fresh || snap.is_some() { s.set_open(true); }

    let (weak, sh2) = (ui.as_weak(), sh.clone());
    s.on_play(move || if let Some(ui) = weak.upgrade() { play(&ui, &sh2) });
    let (weak, sh2) = (ui.as_weak(), sh.clone());
    s.on_finish(move |wizard| if let Some(ui) = weak.upgrade() { finish(&ui, &sh2, wizard) });

    // card art for the profile step, and the launch's progress, while the walkthrough is open
    let t: &'static slint::Timer = Box::leak(Box::new(slint::Timer::default()));
    let (weak, sh2) = (ui.as_weak(), sh.clone());
    t.start(slint::TimerMode::Repeated, std::time::Duration::from_millis(400), move || {
        let Some(ui) = weak.upgrade() else { return };
        if ui.global::<Setup>().get_open() { tick(&ui, &sh2); }
    });
}

/// Java lines for the two ready-made profiles ("Java 21 · on this PC already"), from the Settings > Java rows.
pub fn java_notes(ui: &AppWindow, rows: &[(i32, String, String, bool, bool)]) {
    let s = ui.global::<Setup>();
    for (k, (_, _, major)) in PICKS.iter().enumerate() {
        let ready = rows.iter().any(|r| r.0 == *major as i32 && r.4);
        let note = format!("Java {major} · {}", if ready { "on this PC already" } else { "downloads with the game" });
        if k == 0 { s.set_java0(note.into()) } else { s.set_java1(note.into()) }
    }
}

fn tick(ui: &AppWindow, sh: &Arc<Shared>) {
    let (b, s) = (ui.global::<Backend>(), ui.global::<Setup>());
    for (k, (_, fam, _)) in PICKS.iter().enumerate() {
        let Some(c) = b.get_cards().iter().find(|c| c.fam == *fam) else { continue };
        if !c.has_pano { continue; }
        if k == 0 && !s.get_has_art0() { s.set_art0(c.pano.clone()); s.set_has_art0(true); }
        if k == 1 && !s.get_has_art1() { s.set_art1(c.pano.clone()); s.set_has_art1(true); }
    }
    if s.get_phase() != 1 { return; }
    let Some(id) = PICKS.get(s.get_pick().max(0) as usize).map(|p| p.0) else { return };
    // the card writes its profile into cards.json the moment it makes it; Essentials downloads come first
    let Some(name) = presets::preset_profile(sh, id) else { return };
    let Some(i) = b.get_instances().iter().find(|i| i.name.as_str() == name) else { return };
    if i.running { s.set_phase(2); SEEN.with(|c| c.set(false)); return; }
    if i.starting { SEEN.with(|c| c.set(true)); return; }
    // it was starting and stopped without running (an error toast, the pre-launch check or a crash)
    if SEEN.with(|c| c.get()) && !b.get_pre_open() {
        SEEN.with(|c| c.set(false));
        s.set_crashed(i.crashed);
        s.set_phase(3);
    }
}

fn save(sh: &Shared, b: &Backend, done: bool) {
    let mut p = prefs::load(&sh.paths);
    p.ram = b.get_new_ram().clamp(1, 16);
    if done { p.setup_done = true; }
    let _ = prefs::save(&sh.paths, &p);
}

/// Make (or play) the picked ready-made profile with the chosen memory.
fn play(ui: &AppWindow, sh: &Arc<Shared>) {
    let (b, s) = (ui.global::<Backend>(), ui.global::<Setup>());
    let Some(id) = PICKS.get(s.get_pick().max(0) as usize).map(|p| p.0) else { return };
    save(sh, &b, false);
    // a profile made by an earlier try keeps its memory unless this one changed it
    if let Some(name) = presets::preset_profile(sh, id) {
        if let Some(idx) = b.get_instances().iter().position(|i| i.name.as_str() == name) {
            if b.get_instances().row_data(idx).is_some_and(|i| i.ram != b.get_new_ram()) { b.invoke_set_inst_ram(idx as i32, b.get_new_ram()); }
        }
    }
    SEEN.with(|c| c.set(false));
    s.set_crashed(false);
    s.set_phase(1);
    b.invoke_preset_play(id.into());
}

fn finish(ui: &AppWindow, sh: &Arc<Shared>, wizard: bool) {
    let (b, s) = (ui.global::<Backend>(), ui.global::<Setup>());
    save(sh, &b, true);
    // a signed-out player lands on the sign-in gate (it was only hidden behind the walkthrough)
    s.set_open(false);
    if wizard {
        b.set_create_nether(true);
        b.set_play_tab(0);
        b.set_create_open(true);
    }
}
