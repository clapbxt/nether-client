//! Profile page > Overview > FPS BOOSTERS (2026-11-20): the curated performance mods of the profile's loader
//! (nether_core::perf) with a toggle each. Local state first, then which have a build for the version (Modrinth,
//! cached per loader + version for the session). On = enable the jar or download it; mods it can't run with are
//! turned off. Off = disable the jar (and mods that need it). Files are only renamed, never deleted.
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};

use nether_core::launch::Loader;
use nether_core::perf::{self, State};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::{show_toast, ui_do, AppWindow, Backend, Boost, Inst, Shared};

type Avail = HashMap<&'static str, Option<bool>>;

/// "<loader>|<version>" -> which boosters have a build (filled off the UI thread)
static AVAIL: Mutex<Option<HashMap<String, Avail>>> = Mutex::new(None);

thread_local! {
    /// "<instance>|<slug>" being switched (its row says so, its toggle waits)
    static BUSY: RefCell<HashSet<String>> = RefCell::new(HashSet::new());
}

fn cur(ui: &AppWindow) -> Option<Inst> { let b = ui.global::<Backend>(); b.get_instances().row_data(b.get_current_inst().max(0) as usize) }

fn avail_get(key: &str) -> Option<Avail> { AVAIL.lock().unwrap_or_else(|e| e.into_inner()).as_ref().and_then(|m| m.get(key).cloned()) }

/// Fill the section for the profile on screen: local rows now, Modrinth availability when known.
pub fn load(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some(i) = cur(ui) else { return };
    let loader = Loader::from_label(&i.loader);
    let b = ui.global::<Backend>();
    b.set_boost_foot(foot(&i, loader, false).into());
    if perf::catalog(loader).is_empty() {
        b.set_boosts(ModelRc::new(VecModel::from(Vec::<Boost>::new())));
        b.set_boost_note("".into());
        b.set_boost_more(false);
        return;
    }
    let key = format!("{}|{}", perf::modrinth_loader(loader), i.ver);
    let (weak, sh, name, ver) = (ui.as_weak(), sh.clone(), i.name.to_string(), i.ver.to_string());
    std::thread::spawn(move || {
        let jars = perf::jars(&sh.paths, &name);
        let known = avail_get(&key);
        let (j1, n1, k1) = (jars.clone(), name.clone(), known.clone());
        ui_do(&weak, move |ui| show(ui, &n1, loader, &j1, k1.as_ref()));
        if known.is_some() { return; }
        let a = perf::available(&nether_core::download::client(), loader, &ver);
        // keep it only if Modrinth answered for every booster (offline = ask again next time)
        if a.values().all(|v| v.is_some()) { AVAIL.lock().unwrap_or_else(|e| e.into_inner()).get_or_insert_with(HashMap::new).insert(key, a.clone()); }
        ui_do(&weak, move |ui| show(ui, &name, loader, &jars, Some(&a)));
    });
}

/// The footnote under the list: why a loader has no boosters, and the agent's background limiter.
fn foot(i: &Inst, loader: Loader, dynfps: bool) -> String {
    let v = i.ver.as_str();
    let legacy = v == "1.8.9" || v == "1.12.2";
    let mut s = match loader {
        Loader::Vanilla if legacy => "Plain Minecraft can't load FPS mods. On this version OptiFine is the FPS booster.".to_string(),
        Loader::Vanilla => "Plain Minecraft can't load FPS mods. Make a Nether + Fabric profile for Sodium and the rest.".to_string(),
        Loader::LegacyFabric => "Legacy Fabric has no established FPS mods. For FPS on this version, use Nether + OptiFine.".to_string(),
        _ => String::new(),
    };
    if i.nether && !dynfps {
        if !s.is_empty() { s.push(' '); }
        s.push_str("The Nether client lowers FPS while the game is in the background (in game: Right Shift > Background FPS).");
    }
    s
}

fn show(ui: &AppWindow, name: &str, loader: Loader, jars: &[perf::Jar], avail: Option<&Avail>) {
    let Some(i) = cur(ui) else { return };
    if i.name != name { return; } // another profile is on screen now
    let rows = perf::rows(jars, loader);
    let busy = |slug: &str| BUSY.with(|b| b.borrow().contains(&format!("{name}|{slug}")));
    let (mut on_n, mut more) = (0, false);
    let out: Vec<Boost> = rows.iter().map(|r| {
        let built = avail.and_then(|a| a.get(r.b.slug).copied().flatten());
        let on = matches!(r.state, State::On(_));
        if on { on_n += 1; }
        let names = |v: &[(String, std::path::PathBuf)]| v.iter().map(|x| x.0.clone()).collect::<Vec<_>>().join(", ");
        let (sub, can, warn) = if busy(r.b.slug) {
            (if matches!(r.state, State::Absent) { "Downloading from Modrinth…".to_string() } else { "Switching…".to_string() }, false, false)
        } else if on && !r.blockers.is_empty() {
            (format!("Can't run with {}: the game won't start. Turn one of them off.", names(&r.blockers)), true, true)
        } else if on {
            (format!("{}.", r.b.does), true, false)
        } else if matches!(r.state, State::Absent) && built == Some(false) {
            (format!("Not out for {} {} yet.", i.loader, i.ver), false, false)
        } else if !r.blockers.is_empty() {
            (format!("{}. Turning it on turns off {} (they can't run together).", r.b.does, names(&r.blockers)), true, false)
        } else {
            (format!("{}.", r.b.does), true, false)
        };
        if !on && can && r.blockers.is_empty() { more = true; }
        Boost { slug: r.b.slug.into(), name: r.b.name.into(), sub: sub.into(), on, can, warn, busy: busy(r.b.slug) }
    }).collect();
    let b = ui.global::<Backend>();
    b.set_boost_note(format!("{on_n} of {} on", out.len()).into());
    b.set_boost_more(more);
    b.set_boost_foot(foot(&i, loader, perf::dynamic_fps_on_jars(jars)).into());
    b.set_boosts(ModelRc::new(VecModel::from(out)));
}

/// Turn one booster on or off for the profile on screen.
pub fn set(ui: &AppWindow, sh: &Arc<Shared>, slug: &str, on: bool) { run(ui, sh, vec![slug.to_string()], on); }

/// Turn on every booster that has a build and clashes with nothing installed.
pub fn all_on(ui: &AppWindow, sh: &Arc<Shared>) {
    let slugs: Vec<String> = ui.global::<Backend>().get_boosts().iter().filter(|r| !r.on && r.can && !r.sub.contains("turns off")).map(|r| r.slug.to_string()).collect();
    if !slugs.is_empty() { run(ui, sh, slugs, true); }
}

fn run(ui: &AppWindow, sh: &Arc<Shared>, slugs: Vec<String>, on: bool) {
    let Some(i) = cur(ui) else { return };
    let (name, ver, loader, running) = (i.name.to_string(), i.ver.to_string(), Loader::from_label(&i.loader), i.running || i.starting);
    let slugs: Vec<String> = slugs.into_iter().filter(|s| BUSY.with(|b| b.borrow_mut().insert(format!("{name}|{s}")))).collect();
    if slugs.is_empty() { return; }
    load(ui, sh);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let c = nether_core::download::client();
        let mut done: Vec<(String, anyhow::Result<perf::Change>)> = vec![];
        for s in &slugs { done.push((s.clone(), perf::set(&c, &sh.paths, &name, loader, &ver, s, on))); }
        let count = nether_core::instances::mod_files(&sh.paths, &name).len() as i32;
        ui_do(&weak, move |ui| {
            BUSY.with(|b| { let mut b = b.borrow_mut(); for s in &slugs { b.remove(&format!("{name}|{s}")); } });
            crate::edit_inst_quiet(ui, &name, |i| i.mods = count);
            let title = |s: &str| perf::catalog(loader).iter().find(|b| b.slug == s).map(|b| b.name).unwrap_or(s).to_string();
            let ok: Vec<String> = done.iter().filter(|d| d.1.is_ok()).map(|d| title(&d.0)).collect();
            let off: Vec<String> = done.iter().filter_map(|d| d.1.as_ref().ok()).flat_map(|c| c.also_off.clone()).collect::<std::collections::BTreeSet<_>>().into_iter().collect();
            let mut msg = match (ok.len(), on) {
                (0, _) => String::new(),
                (1, true) => format!("{} is on for {name}", ok[0]),
                (1, false) => format!("{} is off for {name}", ok[0]),
                (n, true) => format!("{n} FPS boosters are on for {name}"),
                (n, false) => format!("{n} FPS boosters are off for {name}"),
            };
            if !off.is_empty() { msg.push_str(&format!(" · turned off {}", off.join(", "))); }
            if let Some((s, Err(e))) = done.iter().find(|d| d.1.is_err()) { msg = format!("{}Couldn't switch {}: {e}", if msg.is_empty() { String::new() } else { format!("{msg} · ") }, title(s)); }
            if running && !ok.is_empty() { msg.push_str(" · restart the game to use it"); }
            if !msg.is_empty() { show_toast(ui, &sh, &msg); }
            load(ui, &sh);
        });
    });
}
