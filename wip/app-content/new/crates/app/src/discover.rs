//! Discover page: Modrinth modpacks (search, project page with version picker) and one-click install as a NEW
//! instance (nether_core::modpack). All network and disk work runs on worker threads; results are posted back.
use std::cell::{Cell, RefCell};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use nether_core::download::client;
use nether_core::{instances, modpack, modrinth};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::mods::{count, environment, icon_px, load_detail_images, nice_date, title_case, to_block, to_image};
use crate::{job, show_toast, ui_do, AppWindow, Backend, Detail, DiscoverState, Inst, Link, Pack, Shared, VerOpt};

const SORTS: [&str; 5] = ["relevance", "downloads", "follows", "newest", "updated"];
/// Category / loader dropdown index -> Modrinth slug ("" = any). Must match the lists in discover.slint.
const CATS: [&str; 11] = ["", "adventure", "challenging", "combat", "kitchen-sink", "lightweight", "magic", "multiplayer", "optimization", "quests", "technology"];
const LOADERS: [&str; 5] = ["", "fabric", "forge", "neoforge", "quilt"];

thread_local! {
    // newest search; older answers are dropped
    static GEN: Cell<u32> = const { Cell::new(0) };
    // names of instances being installed right now (so two installs never pick the same folder)
    static PENDING: RefCell<Vec<String>> = const { RefCell::new(Vec::new()) };
}

/// The loader a pack is tagged with, as an instance label.
fn loader_of(tags: &[String]) -> String {
    for (k, l) in [("fabric", "Fabric"), ("quilt", "Quilt"), ("neoforge", "NeoForge"), ("forge", "Forge")] {
        if tags.iter().any(|t| t == k) { return l.into(); }
    }
    "Vanilla".into()
}

/// Newest full release among game versions ("1.21.1", not snapshots or pre-releases).
fn newest_release(v: &[String]) -> String {
    let rel = |s: &&String| s.chars().all(|c| c.is_ascii_digit() || c == '.') && s.contains('.');
    let key = |s: &String| s.split('.').map(|p| p.parse::<u32>().unwrap_or(0)).collect::<Vec<_>>();
    v.iter().filter(rel).max_by_key(|s| key(s)).cloned().or_else(|| v.last().cloned()).unwrap_or_default()
}

fn update_pack(ui: &AppWindow, id: &str, f: impl Fn(&mut Pack)) {
    let m = ui.global::<DiscoverState>().get_packs();
    for i in 0..m.row_count() {
        if let Some(mut p) = m.row_data(i) { if p.id == id { f(&mut p); m.set_row_data(i, p); } }
    }
}

// ---------------- search ----------------

fn search(ui: &AppWindow, sh: &Arc<Shared>) {
    let d = ui.global::<DiscoverState>();
    if d.get_src() != 0 { return; }
    let gen = GEN.with(|g| { g.set(g.get() + 1); g.get() });
    d.set_state(0);
    d.set_packs(ModelRc::new(VecModel::<Pack>::default()));
    let query = d.get_query().trim().to_string();
    let sort = SORTS[d.get_sort().clamp(0, 4) as usize];
    let cat = CATS.get(d.get_cat().max(0) as usize).copied().unwrap_or("");
    let loader = LOADERS.get(d.get_loader().max(0) as usize).copied().unwrap_or("");
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let res = modrinth::search(&client(), &query, "modpack", "", (!loader.is_empty()).then_some(loader), sort, (!cat.is_empty()).then_some(cat));
        match res {
            Err(e) => {
                let msg = format!("Couldn't load modpacks from Modrinth: {e}. Check your connection and try again.");
                ui_do(&weak, move |ui| {
                    if GEN.with(|g| g.get()) != gen { return; }
                    let d = ui.global::<DiscoverState>();
                    d.set_error(msg.into());
                    d.set_state(2);
                });
            }
            Ok(hits) => {
                // plain strings here (slint images aren't Send); the Pack rows are built on the UI thread
                let rows: Vec<[String; 8]> = hits.iter().map(|h| [
                    h.id.clone(), h.title.clone(), h.author.clone(), h.desc.clone(), count(h.downloads), count(h.follows),
                    newest_release(&h.game_versions), loader_of(&h.categories),
                ]).collect();
                let icons: Vec<(String, String)> = hits.iter().map(|h| (h.id.clone(), h.icon_url.clone())).collect();
                ui_do(&weak, move |ui| {
                    if GEN.with(|g| g.get()) != gen { return; }
                    let d = ui.global::<DiscoverState>();
                    // an install still running keeps showing its progress
                    let pending = PENDING.with(|p| !p.borrow().is_empty());
                    let old = d.get_packs();
                    let rows: Vec<Pack> = rows.into_iter().map(|[id, title, author, desc, dl, follows, mc, loader]| {
                        let mut r = Pack { id: id.into(), title: title.into(), author: author.into(), desc: desc.into(), dl: dl.into(), follows: follows.into(), mc: mc.into(), loader: loader.into(), ..Default::default() };
                        if pending { if let Some(o) = old.iter().find(|o| o.id == r.id) { r.busy = o.busy; r.prog = o.prog; r.done = o.done; } }
                        r
                    }).collect();
                    d.set_packs(ModelRc::new(VecModel::from(rows)));
                    d.set_state(1);
                });
                for (id, url) in icons {
                    let (weak, sh) = (weak.clone(), sh.clone());
                    std::thread::spawn(move || {
                        let Some(px) = icon_px(&sh, &url) else { return };
                        ui_do(&weak, move |ui| {
                            if GEN.with(|g| g.get()) != gen { return; }
                            update_pack(ui, &id, |p| { p.icon = to_image(&px); p.has_icon = true; });
                        });
                    });
                }
            }
        }
    });
}

// ---------------- project page ----------------

/// "v5.4.1 · MC 1.21.1 · Fabric · Mar 7, 2025"
fn ver_label(v: &modrinth::VersionInfo) -> String {
    let tag = if v.kind == "release" { String::new() } else { format!(" · {}", v.kind.to_uppercase()) };
    let mc = newest_release(&v.game_versions);
    format!("v{}{tag} · MC {mc} · {} · {}", v.number.trim_start_matches('v'), loader_of(&v.loaders), nice_date(&v.date))
}

fn open(ui: &AppWindow, sh: &Arc<Shared>, row: i32) {
    let ds = ui.global::<DiscoverState>();
    let Some(p) = ds.get_packs().row_data(row.max(0) as usize) else { return };
    let b = ui.global::<Backend>();
    let mut d = Detail::default();
    d.id = p.id.clone();
    d.title = p.title.clone();
    d.summary = p.desc.clone();
    d.icon = p.icon.clone();
    d.has_icon = p.has_icon;
    d.loading = true;
    d.installed = p.done;
    d.busy = p.busy;
    d.prog = p.prog;
    d.compat_ok = 1;
    d.mod_loader = p.loader.clone();
    b.set_detail(d);
    ds.set_detail_open(true);
    let (weak, sh, id) = (ui.as_weak(), sh.clone(), p.id.to_string());
    std::thread::spawn(move || {
        let c = client();
        let res = modrinth::detail(&c, &id, "", None);
        let vers = modrinth::versions(&c, &id, "", None).unwrap_or_default();
        match res {
            Err(e) => {
                let msg = format!("Couldn't load this modpack's page: {e}");
                ui_do(&weak, move |ui| {
                    let b = ui.global::<Backend>();
                    let mut d = b.get_detail();
                    if d.id != id.as_str() { return; }
                    d.loading = false;
                    d.desc = msg.as_str().into();
                    d.compat_ok = 2;
                    d.compat_text = msg.into();
                    b.set_detail(d);
                });
            }
            Ok((det, _)) => {
                let px = icon_px(&sh, &det.icon_url);
                let rel: Vec<String> = det.mc_versions.iter().filter(|x| x.chars().all(|c| c.is_ascii_digit() || c == '.')).rev().cloned().collect();
                let more = if rel.len() > 12 { format!("+{} older versions", rel.len() - 12) } else { String::new() };
                let shown: Vec<String> = rel.into_iter().take(12).collect();
                let loaders: Vec<String> = det.loaders.iter().map(|l| title_case(l)).collect();
                let tags: Vec<String> = det.categories.iter().filter(|c| !det.loaders.contains(c)).take(4).map(|c| title_case(c)).collect();
                let env = environment(&det.client_side, &det.server_side);
                let blocks = crate::md::blocks(if det.body.is_empty() { &det.description } else { &det.body });
                ui_do(&weak, move |ui| {
                    let b = ui.global::<Backend>();
                    let mut d = b.get_detail();
                    if d.id != det.id.as_str() { return; }
                    let strs = |v: &[String]| ModelRc::new(VecModel::from(v.iter().map(|s| s.as_str().into()).collect::<Vec<slint::SharedString>>()));
                    d.title = det.title.as_str().into();
                    d.summary = det.description.as_str().into();
                    d.desc = det.description.as_str().into();
                    d.author = det.author.as_str().into();
                    d.downloads = count(det.downloads).into();
                    d.followers = count(det.followers).into();
                    d.tags = strs(&tags);
                    d.loaders = strs(&loaders);
                    d.versions = strs(&shown);
                    d.more_versions = more.into();
                    d.env = env.into();
                    d.license = if det.license.is_empty() { "Unknown".into() } else { det.license.as_str().into() };
                    d.published = nice_date(&det.published).into();
                    d.updated = nice_date(&det.updated).into();
                    d.page_url = det.page_url.as_str().into();
                    d.links = ModelRc::new(VecModel::from(det.links.iter().map(|(k, u)| Link { k: k.as_str().into(), url: u.as_str().into() }).collect::<Vec<_>>()));
                    d.compat_ok = if vers.is_empty() { 2 } else { 1 };
                    d.compat_text = if vers.is_empty() { "This project has no downloadable versions.".into() } else { "Creates a new instance named after the pack. Your other instances aren't touched.".into() };
                    d.can_install = !vers.is_empty();
                    d.vers = ModelRc::new(VecModel::from(vers.iter().map(|v| VerOpt { id: v.id.as_str().into(), label: "".into() }).collect::<Vec<_>>()));
                    d.vlabels = ModelRc::new(VecModel::from(vers.iter().map(|v| slint::SharedString::from(ver_label(v))).collect::<Vec<_>>()));
                    d.pick = vers.iter().position(|v| v.kind == "release").unwrap_or(0) as i32;
                    d.blocks = ModelRc::new(VecModel::from(blocks.iter().map(to_block).collect::<Vec<_>>()));
                    d.gallery = ModelRc::new(VecModel::<slint::Image>::default());
                    if let Some(p) = &px { d.icon = to_image(p); d.has_icon = true; }
                    d.loading = false;
                    b.set_detail(d);
                    load_detail_images(ui.as_weak(), sh.clone(), det.id.clone(), blocks, det.gallery.clone());
                });
            }
        }
    });
}

// ---------------- install ----------------

fn set_progress(ui: &AppWindow, id: &str, busy: bool, f: f32, done: bool) {
    update_pack(ui, id, |p| { p.busy = busy; p.prog = f; p.done = done || p.done; });
    let b = ui.global::<Backend>();
    let mut d = b.get_detail();
    if d.id == id && ui.global::<DiscoverState>().get_detail_open() { d.busy = busy; d.prog = f; d.installed = done || d.installed; b.set_detail(d); }
}

/// Install a Modrinth modpack (`version` None = newest) as a new instance. A free name is picked from the pack's title.
pub fn install(ui: &AppWindow, sh: &Arc<Shared>, id: String, version: Option<String>, title: String) {
    if PENDING.with(|p| !p.borrow().is_empty()) && ui.global::<DiscoverState>().get_packs().iter().any(|p| p.id == id.as_str() && p.busy) { return; }
    let b = ui.global::<Backend>();
    let mut taken: Vec<String> = b.get_instances().iter().map(|i| i.name.to_string()).collect();
    PENDING.with(|p| taken.extend(p.borrow().iter().cloned()));
    let base = title.trim();
    let name = instances::free_name(&sh.paths, if base.is_empty() { "Modpack" } else { base }, &taken);
    PENDING.with(|p| p.borrow_mut().push(name.clone()));
    set_progress(ui, &id, true, 0.0, false);
    job(ui, &format!("Installing {title} · Finding modpack"), 0.0);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let last = Mutex::new(Instant::now() - Duration::from_secs(1));
        let (w2, id2, t2) = (weak.clone(), id.clone(), title.clone());
        let progress: nether_core::Progress = Arc::new(move |msg: &str, f: f32| {
            if let Ok(mut l) = last.lock() { if l.elapsed() < Duration::from_millis(80) && f < 1.0 { return; } *l = Instant::now(); }
            let (id, text) = (id2.clone(), format!("Installing {t2} · {msg}"));
            ui_do(&w2, move |ui| { set_progress(ui, &id, true, f.clamp(0.0, 1.0), false); job(ui, &text, f); });
        });
        // no version picked: the newest full release (Modrinth's newest may be a beta)
        let c = client();
        let version = version.or_else(|| {
            let vs = modrinth::versions(&c, &id, "", None).ok()?;
            vs.iter().find(|v| v.kind == "release").or(vs.first()).map(|v| v.id.clone())
        });
        let res = modpack::install_project(&c, &sh.paths, &id, version.as_deref(), Some(&name), &progress);
        ui_do(&weak, move |ui| {
            PENDING.with(|p| p.borrow_mut().retain(|n| *n != name));
            match res {
                Ok(data) => {
                    set_progress(ui, &id, false, 1.0, true);
                    add_instance(ui, &sh, &data);
                    show_toast(ui, &sh, &format!("{} installed · it's in Instances, ready to play", data.name));
                    if std::env::var("NC_SNAP_STATE").ok().as_deref() == Some("discover-install") { ui.set_open_idx(-1); ui.set_tab(1); }
                }
                Err(e) => {
                    set_progress(ui, &id, false, 0.0, false);
                    let mut msg = format!("Couldn't install {title}: {e:#}. Nothing was left behind.");
                    msg = msg.chars().take(220).collect();
                    eprintln!("{msg}");
                    show_toast(ui, &sh, &msg);
                }
            }
        });
    });
}

/// Add a freshly installed / imported instance to the list, save it and make it the launch target.
pub(crate) fn add_instance(ui: &AppWindow, sh: &Arc<Shared>, data: &instances::InstanceData) {
    let b = ui.global::<Backend>();
    let m = b.get_instances();
    if let Some(v) = m.as_any().downcast_ref::<VecModel<Inst>>() {
        v.push(crate::inst_from(data));
        b.set_active_inst(v.row_count() as i32 - 1);
    }
    crate::persist(&sh.paths, &m);
    crate::refilter(ui);
    crate::artui::refresh(ui, sh);
}

// ---------------- wiring ----------------

pub fn init(ui: &AppWindow, sh: Arc<Shared>) {
    let d = ui.global::<DiscoverState>();
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        d.on_search(move || if let Some(ui) = weak.upgrade() { search(&ui, &sh) });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        d.on_open(move |i| if let Some(ui) = weak.upgrade() { open(&ui, &sh, i) });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        d.on_install(move |i| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(p) = ui.global::<DiscoverState>().get_packs().row_data(i.max(0) as usize) else { return };
            install(&ui, &sh, p.id.to_string(), None, p.title.to_string());
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        d.on_install_detail(move || {
            let Some(ui) = weak.upgrade() else { return };
            let det = ui.global::<Backend>().get_detail();
            let picked = det.vers.row_data(det.pick.max(0) as usize).map(|v| v.id.to_string());
            install(&ui, &sh, det.id.to_string(), picked, det.title.to_string());
        });
    }
}

/// Dev snapshots only (`--snapshot` with NC_SNAP_STATE): "discover-detail" opens the first result's page;
/// "discover-install" really installs a small Modrinth modpack (NC_SNAP_PACK, default "opti-mized") into the
/// data root (use a temporary NETHER_HOME) and switches to Instances when it is done.
pub fn snap(ui: &AppWindow, sh: &Arc<Shared>, state: &str) {
    match state {
        "discover-detail" => {
            let weak = ui.as_weak();
            slint::Timer::single_shot(Duration::from_millis(2500), move || if let Some(ui) = weak.upgrade() { ui.global::<DiscoverState>().invoke_open(0) });
        }
        "discover-install" => {
            let pack = std::env::var("NC_SNAP_PACK").unwrap_or_else(|_| "opti-mized".into());
            install(ui, sh, pack.clone(), None, title_case(&pack));
        }
        "discover-curseforge" => ui.global::<DiscoverState>().set_src(1),
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pack_labels() {
        let v = |s: &[&str]| s.iter().map(|x| x.to_string()).collect::<Vec<_>>();
        assert_eq!(newest_release(&v(&["1.20.1", "1.21.1", "24w10a", "1.21.10", "1.21.2"])), "1.21.10");
        assert_eq!(loader_of(&v(&["optimization", "fabric", "quilt"])), "Fabric");
        assert_eq!(loader_of(&v(&["neoforge"])), "NeoForge");
        assert_eq!(loader_of(&v(&["adventure"])), "Vanilla");
    }
}
