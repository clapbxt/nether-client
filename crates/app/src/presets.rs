//! Play > Nether (Lunar's system, 2026-11-19): every version family the Nether client supports is a card. A card is
//! played as "Nether" (plain Minecraft with the Nether client), "Nether + Fabric" (Fabric plus the Nether Essentials
//! performance pack, 1.14 and newer) or, on 1.8.9 / 1.12.2, "Nether + OptiFine" (the first launch downloads and sets
//! up OptiFine; see nether_core::optifine). The first PLAY of a version + way makes an ordinary, fully
//! editable instance (one per patch, so worlds and mods never move between versions); later PLAYs just play it.
//! `<root>/cards.json` remembers the instance each one made and the patch last played per card. Home's first-run
//! buttons ("presets") are two of these cards.
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use nether_core::download::client;
use nether_core::{instances, netherpack};
use serde::{Deserialize, Serialize};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::{show_toast, ui_do, AppWindow, Backend, Card, PresetRow, Shared};

struct Fam { fam: &'static str, name: &'static str, tag: &'static str }

/// Popular first (Lunar's order); 1.8 and 1.12 are 1.8.9 / 1.12.2 only, without Fabric. 1.14 and 1.15 have no card:
/// Sodium and the performance mods were never made for them (custom Nether profiles still offer them).
const FAMS: [Fam; 9] = [
    Fam { fam: "26", name: "The 2026 game drops", tag: "NEW" },
    Fam { fam: "1.21", name: "Tricky Trials", tag: "" },
    Fam { fam: "1.8", name: "Bountiful Update", tag: "PVP" },
    Fam { fam: "1.20", name: "Trails & Tales", tag: "" },
    Fam { fam: "1.19", name: "The Wild Update", tag: "" },
    Fam { fam: "1.18", name: "Caves & Cliffs II", tag: "" },
    Fam { fam: "1.16", name: "Nether Update", tag: "" },
    Fam { fam: "1.17", name: "Caves & Cliffs I", tag: "" },
    Fam { fam: "1.12", name: "World of Color", tag: "" },
];

/// Essentials mods worth a toast when a version has no build of them (the rest, e.g. ModernFix on the newest
/// versions, are extras that simply come later).
const CORE: [&str; 4] = ["fabric-api", "sodium", "lithium", "modmenu"];

/// Home's first-run buttons: (id, title, sub, family, way, patch).
const PRESETS: [(&str, &str, &str, &str, i32, &str); 2] = [
    ("modern", "Nether 1.21.11", "Fabric · Sodium, Lithium and performance mods", "1.21", 1, "1.21.11"),
    ("pvp189", "Nether 1.8.9", "Classic PvP · Nether HUD, keystrokes and CPS", "1.8", 0, "1.8.9"),
];

#[derive(Serialize, Deserialize, Default)]
struct Saved {
    /// "<patch>/<way>" -> the instance it made
    #[serde(default)]
    made: HashMap<String, String>,
    /// "<family>/<way>" -> the patch last played
    #[serde(default)]
    pick: HashMap<String, String>,
    /// instance -> Essentials mods the player said no to (never offered again for that profile)
    #[serde(default)]
    declined: HashMap<String, Vec<String>>,
}

thread_local! {
    /// card profile -> Essentials mods it lacks that have a build for its version now: (slug, title)
    static ADDS: RefCell<HashMap<String, Vec<(String, String)>>> = RefCell::new(HashMap::new());
    /// instance names the cards made (Play > Nether lists them on the cards, not as your own profiles)
    static MADE: RefCell<HashSet<String>> = RefCell::new(HashSet::new());
}

fn file(sh: &Shared) -> std::path::PathBuf { sh.paths.root.join("cards.json") }

fn load(sh: &Shared) -> Saved {
    if let Some(s) = nether_core::store::peek_json::<Saved>(&file(sh)) { return s; }
    // presets.json (first slice): {"modern": name, "pvp189": name}
    let old: HashMap<String, String> = nether_core::store::peek_json(&sh.paths.root.join("presets.json")).unwrap_or_default();
    let mut s = Saved::default();
    for (id, _, _, _, way, ver) in PRESETS { if let Some(n) = old.get(id) { s.made.insert(format!("{ver}/{way}"), n.clone()); } }
    s
}

fn save(sh: &Shared, s: &Saved) { let _ = nether_core::store::write_json(&file(sh), s); }

/// The profile a Home preset ("modern", "pvp189") made, if it made one.
pub fn preset_profile(sh: &Shared, id: &str) -> Option<String> {
    let (_, _, _, _, way, ver) = PRESETS.iter().find(|p| p.0 == id)?;
    load(sh).made.get(&format!("{ver}/{way}")).cloned()
}

/// A card made this instance (it isn't listed under "Your Nether profiles").
pub fn is_card(name: &str) -> bool { MADE.with(|m| m.borrow().contains(name)) }

/// Patches of a family, newest first (`all` = releases newest first).
fn patches(fam: &str, all: &[String]) -> Vec<String> {
    match fam {
        "1.8" => vec!["1.8.9".into()],
        "1.12" => vec!["1.12.2".into()],
        f => all.iter().filter(|v| v.as_str() == f || v.starts_with(&format!("{f}."))).cloned().collect(),
    }
}

fn names(m: &ModelRc<slint::SharedString>) -> Vec<String> { m.iter().map(|s| s.to_string()).collect() }

/// The version lists came in: the wizard's Nether lists and the cards.
pub fn versions_loaded(ui: &AppWindow) {
    let b = ui.global::<Backend>();
    let rel = names(&b.get_mc_versions());
    let fab = names(&b.get_fabric_versions());
    let mut nv: Vec<slint::SharedString> = rel.iter().filter(|v| instances::nether_ok(v, "Vanilla")).map(|v| v.as_str().into()).collect();
    if !nv.iter().any(|v| v == "1.8.9") { nv.push("1.8.9".into()); }
    let nf: Vec<slint::SharedString> = fab.iter().filter(|v| instances::nether_ok(v, "Fabric")).map(|v| v.as_str().into()).collect();
    // Forge: the releases it has builds for (all releases until that list has loaded)
    let forge = names(&b.get_forge_versions());
    let ng: Vec<slint::SharedString> = rel.iter().filter(|v| instances::nether_ok(v, "Forge") && (forge.is_empty() || forge.contains(v))).map(|v| v.as_str().into()).collect();
    b.set_nether_forge_versions(ModelRc::new(VecModel::from(ng)));
    b.set_nether_versions(ModelRc::new(VecModel::from(nv)));
    b.set_nether_fabric_versions(ModelRc::new(VecModel::from(nf)));
    SH.with(|s| if let Some(sh) = s.borrow().as_ref() { refresh(ui, sh) });
}

thread_local! { static SH: RefCell<Option<Arc<Shared>>> = const { RefCell::new(None) }; }

/// Fill the cards, Home's presets and the picked card.
pub fn refresh(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    let rel = names(&b.get_mc_versions());
    let fab: HashSet<String> = names(&b.get_fabric_versions()).into_iter().collect();
    let have: HashSet<String> = b.get_instances().iter().map(|i| i.name.to_string()).collect();
    let saved = load(sh);
    MADE.with(|m| *m.borrow_mut() = saved.made.values().filter(|n| have.contains(*n)).cloned().collect());
    let rows: Vec<PresetRow> = PRESETS.iter().map(|(id, title, sub, _, way, ver)| PresetRow {
        id: (*id).into(), title: (*title).into(), sub: (*sub).into(), ver: (*ver).into(),
        have: saved.made.get(&format!("{ver}/{way}")).is_some_and(|n| have.contains(n)),
    }).collect();
    if b.get_presets().iter().collect::<Vec<_>>() != rows { b.set_presets(ModelRc::new(VecModel::from(rows))); }
    // the cards wait for the version list (so the grid never shows half of them)
    if rel.is_empty() { return; }

    let cards: Vec<Card> = FAMS.iter().filter_map(|f| {
        let vers = patches(f.fam, &rel);
        if vers.is_empty() { return None; }
        let fvers: Vec<String> = if matches!(f.fam, "1.8" | "1.12") { vec![] } else { vers.iter().filter(|v| fab.contains(*v)).cloned().collect() };
        let pano = crate::artui::card_art(ui, sh, &vers[0], refresh);
        Some(Card {
            fam: f.fam.into(), name: f.name.into(), tag: f.tag.into(),
            vers: ModelRc::new(VecModel::from(vers.iter().map(|v| v.as_str().into()).collect::<Vec<slint::SharedString>>())),
            fvers: ModelRc::new(VecModel::from(fvers.iter().map(|v| v.as_str().into()).collect::<Vec<slint::SharedString>>())),
            has_pano: pano.is_some(), pano: pano.unwrap_or_default(),
            optifine: vers.iter().any(|v| nether_core::optifine::supported(v)),
        })
    }).collect();
    let changed = b.get_cards().row_count() != cards.len()
        || b.get_cards().iter().zip(&cards).any(|(a, c)| a.fam != c.fam || a.has_pano != c.has_pano || a.vers.row_count() != c.vers.row_count() || a.fvers.row_count() != c.fvers.row_count());
    if changed { b.set_cards(ModelRc::new(VecModel::from(cards))); }

    // keep the pick valid (the first time: the card last played, else the first)
    let fam = b.get_card_fam().to_string();
    let fam = if !fam.is_empty() { fam } else {
        let last = b.get_instances().iter().filter(|i| saved.made.values().any(|n| n == i.name.as_str())).max_by_key(|i| i.last_ts)
            .and_then(|i| FAMS.iter().find(|f| patches(f.fam, &rel).iter().any(|v| v == i.ver.as_str())).map(|f| f.fam.to_string()));
        last.unwrap_or_else(|| "1.21".into())
    };
    pick(ui, sh, &fam, b.get_card_loader(), &b.get_card_ver());
}

/// Pick a card, a way to play (0 Nether, 1 Nether + Fabric, 2 Nether + OptiFine, -1 = the card's last, else Fabric)
/// and a patch ("" = the one last played, else the newest).
fn pick(ui: &AppWindow, sh: &Arc<Shared>, fam: &str, way: i32, ver: &str) {
    let b = ui.global::<Backend>();
    let cards: Vec<Card> = b.get_cards().iter().collect();
    let Some(c) = cards.iter().find(|c| c.fam == fam).or(cards.first()).cloned() else { return };
    let fam = c.fam.to_string();
    let saved = load(sh);
    let way = if way < 0 { saved.pick.get(&fam).and_then(|w| w.parse().ok()).unwrap_or(1) } else { way };
    let way = match way { 1 if c.fvers.row_count() > 0 => 1, 2 if c.optifine => 2, _ => 0 };
    let list = names(if way == 1 { &c.fvers } else { &c.vers });
    let list: Vec<String> = if way == 2 { list.into_iter().filter(|v| nether_core::optifine::supported(v)).collect() } else { list };
    let ver = if list.iter().any(|v| v == ver) && b.get_card_fam() == fam.as_str() { ver.to_string() }
        else if let Some(v) = saved.pick.get(&format!("{fam}/{way}")).filter(|v| list.contains(v)) { v.clone() }
        else if list.iter().any(|v| v == ver) { ver.to_string() }
        else { list.first().cloned().unwrap_or_default() };
    let inst = saved.made.get(&format!("{ver}/{way}")).cloned().unwrap_or_default();
    let idx = b.get_instances().iter().position(|i| i.name.as_str() == inst).map(|i| i as i32).unwrap_or(-1);
    b.set_card_cur(c);
    b.set_card_fam(fam.as_str().into());
    b.set_card_loader(way);
    b.set_card_ver(ver.as_str().into());
    b.set_card_vi(list.iter().position(|v| *v == ver).unwrap_or(0) as i32);
    b.set_card_inst(if idx >= 0 { inst.as_str().into() } else { "".into() });
    b.set_card_idx(idx);
    let adds = ADDS.with(|a| a.borrow().get(&inst).map(|v| v.iter().map(|m| m.1.clone()).collect::<Vec<_>>().join(", ")).unwrap_or_default());
    b.set_card_adds(if idx >= 0 { adds.into() } else { "".into() });
}

/// Re-pick the current card (after the offers changed).
fn repick(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    pick(ui, sh, &b.get_card_fam(), b.get_card_loader(), &b.get_card_ver());
}

/// Card profile update / repair (2026-11-20): Essentials mods a Fabric card profile lacks that have a build for
/// its version now, e.g. ModernFix once it is out for 1.21.11 (it was skipped when the profile was made), or a
/// pack mod that went missing. Checked 20 s after start and then twice a day; offered on the card panel and with
/// one toast per new find, never added without a click.
fn check_adds(ui: &AppWindow, sh: &Arc<Shared>) {
    let saved = load(sh);
    let b = ui.global::<Backend>();
    let have: HashMap<String, (String, String)> = b.get_instances().iter().map(|i| (i.name.to_string(), (i.ver.to_string(), i.loader.to_string()))).collect();
    let todo: Vec<(String, String, Vec<String>)> = saved.made.iter()
        .filter(|(k, _)| k.ends_with("/1"))
        .filter_map(|(_, n)| have.get(n).filter(|(_, l)| l == "Fabric").map(|(v, _)| (n.clone(), v.clone(), saved.declined.get(n).cloned().unwrap_or_default())))
        .collect();
    if todo.is_empty() { return; }
    let Some(pack) = netherpack::find(&sh.paths, "essentials") else { return };
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let c = client();
        let mut found: Vec<(String, String, Vec<(String, String)>)> = vec![];
        for (name, ver, skip) in todo {
            match netherpack::missing(&c, &pack, &ver, &instances::content_dir(&sh.paths, &name, 0), &skip) {
                Ok(m) => found.push((name, ver, m)),
                Err(e) => crate::applog::line(&format!("card check {name}: {e:#}")),
            }
        }
        ui_do(&weak, move |ui| {
            let mut fresh = vec![];
            ADDS.with(|a| {
                let mut a = a.borrow_mut();
                for (name, ver, m) in found {
                    let old: HashSet<String> = a.get(&name).map(|v| v.iter().map(|x| x.0.clone()).collect()).unwrap_or_default();
                    if m.iter().any(|x| !old.contains(&x.0)) { fresh.push((name.clone(), ver, m.iter().map(|x| x.1.clone()).collect::<Vec<_>>())); }
                    if m.is_empty() { a.remove(&name); } else { a.insert(name, m); }
                }
            });
            repick(ui, &sh);
            if let Some((name, ver, titles)) = fresh.into_iter().next() {
                let (n2, s2) = (name.clone(), sh.clone());
                let verb = if titles.len() == 1 { "is" } else { "are" };
                crate::toast_action(ui, &sh, &format!("{} {verb} out for {ver}. Add to {name}?", titles.join(", ")), false, "ADD", move |ui| add(ui, &s2, &n2));
            }
        });
    });
}

/// Install the offered Essentials mods into a card profile.
fn add(ui: &AppWindow, sh: &Arc<Shared>, name: &str) {
    let Some(mods) = ADDS.with(|a| a.borrow_mut().remove(name)) else { return };
    let b = ui.global::<Backend>();
    let Some(i) = b.get_instances().iter().find(|i| i.name.as_str() == name) else { return };
    let (ver, running) = (i.ver.to_string(), i.running);
    repick(ui, sh);
    let (weak, sh, name) = (ui.as_weak(), sh.clone(), name.to_string());
    std::thread::spawn(move || {
        let key = format!("cardadd:{name}");
        let dir = instances::content_dir(&sh.paths, &name, 0);
        let c = client();
        let (mut ok, mut bad) = (vec![], vec![]);
        let n = mods.len().max(1) as f32;
        for (k, (slug, title)) in mods.iter().enumerate() {
            let (w2, k2, t, f) = (weak.clone(), key.clone(), format!("{name} · Adding {title}"), k as f32 / n);
            ui_do(&w2, move |ui| crate::job(ui, &k2, &t, f));
            match nether_core::modrinth::install(&c, slug, None, &ver, Some("fabric"), &dir, &|_, _, _| {}) {
                Ok(_) => ok.push(title.clone()),
                Err(e) => { crate::applog::line(&format!("card add {slug} to {name}: {e:#}")); bad.push((slug.clone(), title.clone())); }
            }
        }
        ui_do(&weak, move |ui| {
            crate::job_end(ui, &key);
            let names = |v: &[(String, String)]| v.iter().map(|b| b.1.clone()).collect::<Vec<_>>().join(", ");
            let msg = match (ok.is_empty(), bad.is_empty()) {
                (false, true) => format!("Added {} to {name}{}", ok.join(", "), if running { ". Restart the game to use it" } else { "" }),
                (false, false) => format!("Added {}; couldn't download {}", ok.join(", "), names(&bad)),
                _ => format!("Couldn't download {}. Check your connection", names(&bad)),
            };
            if !bad.is_empty() { ADDS.with(|a| a.borrow_mut().insert(name.clone(), bad)); }
            repick(ui, &sh);
            show_toast(ui, &sh, &msg);
        });
    });
}

/// "Not for this profile": never offer these mods to it again.
fn decline(ui: &AppWindow, sh: &Arc<Shared>, name: &str) {
    let Some(mods) = ADDS.with(|a| a.borrow_mut().remove(name)) else { return };
    let mut saved = load(sh);
    let d = saved.declined.entry(name.to_string()).or_default();
    for (slug, _) in mods { if !d.contains(&slug) { d.push(slug); } }
    save(sh, &saved);
    repick(ui, sh);
}

pub fn init(ui: &AppWindow, sh: Arc<Shared>) {
    SH.with(|s| *s.borrow_mut() = Some(sh.clone()));
    refresh(ui, &sh);
    // a profile renamed or deleted from the Play page frees its card (the next PLAY makes a new one)
    let t: &'static slint::Timer = Box::leak(Box::new(slint::Timer::default()));
    let (weak, s3) = (ui.as_weak(), sh.clone());
    t.start(slint::TimerMode::Repeated, std::time::Duration::from_secs(3), move || if let Some(ui) = weak.upgrade() { refresh(&ui, &s3) });
    let b = ui.global::<Backend>();
    let (weak, s2) = (ui.as_weak(), sh.clone());
    b.on_preset_play(move |id| if let Some(ui) = weak.upgrade() {
        if let Some((_, _, _, fam, way, ver)) = PRESETS.iter().find(|p| p.0 == id.as_str()) { pick(&ui, &s2, fam, *way, ver); play(&ui, &s2); }
    });
    let (weak, s2) = (ui.as_weak(), sh.clone());
    b.on_card_pick(move |fam, way, ver| if let Some(ui) = weak.upgrade() { pick(&ui, &s2, &fam, way, &ver) });
    let (weak, s2) = (ui.as_weak(), sh.clone());
    b.on_card_play(move || if let Some(ui) = weak.upgrade() { play(&ui, &s2) });
    let (weak, s2) = (ui.as_weak(), sh.clone());
    b.on_card_add(move || if let Some(ui) = weak.upgrade() { let n = ui.global::<Backend>().get_card_inst().to_string(); add(&ui, &s2, &n) });
    let (weak, s2) = (ui.as_weak(), sh.clone());
    b.on_card_add_skip(move || if let Some(ui) = weak.upgrade() { let n = ui.global::<Backend>().get_card_inst().to_string(); decline(&ui, &s2, &n) });
    // update / repair check: 20 s after start, then every 12 h
    let t2: &'static slint::Timer = Box::leak(Box::new(slint::Timer::default()));
    let (weak, s3) = (ui.as_weak(), sh.clone());
    t2.start(slint::TimerMode::SingleShot, std::time::Duration::from_secs(20), move || {
        if let Some(ui) = weak.upgrade() { check_adds(&ui, &s3); }
        let t3: &'static slint::Timer = Box::leak(Box::new(slint::Timer::default()));
        let (w4, s4) = (weak.clone(), s3.clone());
        t3.start(slint::TimerMode::Repeated, std::time::Duration::from_secs(12 * 3600), move || if let Some(ui) = w4.upgrade() { check_adds(&ui, &s4) });
    });
}

/// Play the picked card: its profile when made, else make it (with its mods), then play.
fn play(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    let (fam, way, ver) = (b.get_card_fam().to_string(), b.get_card_loader(), b.get_card_ver().to_string());
    if ver.is_empty() { return; }
    let mut saved = load(sh);
    saved.pick.insert(format!("{fam}/{way}"), ver.clone());
    saved.pick.insert(fam.clone(), way.to_string());
    let key = format!("{ver}/{way}");
    let inst = b.get_card_inst().to_string();
    if !inst.is_empty() {
        save(sh, &saved);
        b.invoke_play(inst.into());
        return;
    }
    let taken: Vec<String> = b.get_instances().iter().map(|i| i.name.to_string()).collect();
    let name = instances::free_name(&sh.paths, &format!("Nether {ver}{}", match way { 1 => " Fabric", 2 => " OptiFine", _ => "" }), &taken);
    b.set_create_nether(true);
    // way 2 makes a Vanilla profile with OptiFine on (create-instance loader 6)
    if !b.invoke_create_instance(name.as_str().into(), ver.as_str().into(), if way == 2 { 6 } else { way }, b.get_new_ram(), "".into(), "".into()) { return; }
    saved.made.insert(key, name.clone());
    save(sh, &saved);
    // 1.8.9 starts with the PvP HUD (keystrokes, CPS); Fabric gets the performance pack
    let client_pack = (ver == "1.8.9").then(|| netherpack::find(&sh.paths, "pvp")).flatten();
    crate::client::setup_instance(&sh.paths, &name, client_pack.as_ref().and_then(|c| c.client.as_ref()));
    refresh(ui, sh);
    let pack = (way == 1).then(|| netherpack::find(&sh.paths, "essentials")).flatten();
    let Some(pack) = pack else { b.invoke_play(name.into()); return };
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let key = format!("preset:{name}");
        let (w2, k2) = (weak.clone(), key.clone());
        let prog: nether_core::Progress = Arc::new(move |m: &str, f: f32| { let (m, k) = (m.to_string(), k2.clone()); ui_do(&w2, move |ui| crate::job(ui, &k, &m, f)); });
        let missing = match netherpack::install(&client(), &pack, &ver, &instances::content_dir(&sh.paths, &name, 0), &prog) {
            Ok((_, miss)) => miss.into_iter().filter(|m| CORE.contains(&m.as_str())).collect(),
            Err(e) => vec![format!("{} ({e:#})", pack.name)],
        };
        ui_do(&weak, move |ui| {
            crate::job_end(ui, &key);
            if !missing.is_empty() { show_toast(ui, &sh, &format!("{name}: no {ver} build yet for {}", missing.join(", "))); }
            ui.global::<Backend>().invoke_play(name.into());
        });
    });
}
