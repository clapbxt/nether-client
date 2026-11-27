//! New-instance walkthrough (ui/newflow.slint, 2026-11-26). QUICK makes "Nether <version>": Fabric, the Nether client
//! and the Nether Essentials performance pack. MANUAL picks version, loader (Vanilla, Fabric, Forge, NeoForge), Nether
//! on / off (with the reason when it can't run), first mods from Modrinth plus the performance mods, and resource
//! packs. The instance is made by `create-instance`; downloads run after it in the background, then PLAY if asked.
use std::cell::RefCell;
use std::sync::Arc;

use nether_core::download::client;
use nether_core::launch::Loader;
use nether_core::{instances, modpack, modrinth, netherpack, perf};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::mods::{cached_icon, count, icon_image, icon_px, update_rows};
use crate::{show_toast, ui_do, AppWindow, Backend, NewInst, Row, Shared, Wizard};

thread_local! {
    /// the search the mods list on screen belongs to (a late answer for an older one is dropped)
    static SEARCH: RefCell<String> = const { RefCell::new(String::new()) };
    /// the same for the modpack list
    static PACK_SEARCH: RefCell<String> = const { RefCell::new(String::new()) };
}

const NAMES: [&str; 4] = ["Vanilla", "Fabric", "Forge", "NeoForge"];

fn list(m: ModelRc<slint::SharedString>) -> Vec<String> { m.iter().map(|s| s.to_string()).collect() }

/// The walkthrough's loader on this version: a version only Legacy Fabric builds for (1.8.9, 1.12.2…) runs on it.
fn loader_of(b: &Backend, ver: &str, loader: i32) -> Loader {
    match loader {
        1 => {
            let (fab, legacy) = (list(b.get_fabric_versions()), list(b.get_legacy_versions()));
            if !fab.iter().any(|v| v == ver) && legacy.iter().any(|v| v == ver) { Loader::LegacyFabric } else { Loader::Fabric }
        }
        2 => Loader::Forge,
        3 => Loader::NeoForge,
        _ => Loader::Vanilla,
    }
}

/// `create-instance`'s loader number for a launch loader.
fn create_code(l: Loader) -> i32 {
    match l { Loader::Fabric => 1, Loader::Forge => 2, Loader::NeoForge => 3, Loader::Quilt => 4, Loader::LegacyFabric => 5, Loader::Vanilla => 0 }
}

/// The loader has a build for this version. A list that hasn't loaded yet doesn't block anything.
fn loader_ok(b: &Backend, ver: &str, loader: i32) -> bool {
    let has = |l: Vec<String>| l.is_empty() || l.iter().any(|v| v == ver);
    match loader {
        1 => {
            let (fab, legacy) = (list(b.get_fabric_versions()), list(b.get_legacy_versions()));
            (fab.is_empty() && legacy.is_empty()) || fab.iter().any(|v| v == ver) || legacy.iter().any(|v| v == ver)
        }
        2 => has(list(b.get_forge_versions())),
        3 => has(list(b.get_neoforge_versions())),
        _ => true,
    }
}

/// "A", "A or B", "A, B or C".
fn or_list(v: &[&str]) -> String {
    match v {
        [] => String::new(),
        [a] => a.to_string(),
        [rest @ .., last] => format!("{} or {last}", rest.join(", ")),
    }
}

/// Why the Nether client can't run on this version + loader, in words the player can act on ("" = it can).
fn nether_why(b: &Backend, ver: &str, loader: i32) -> String {
    if ver.is_empty() || instances::nether_ok(ver, loader_of(b, ver, loader).label()) { return String::new(); }
    let ok: Vec<&str> = (0..4).filter(|i| loader_ok(b, ver, *i) && instances::nether_ok(ver, loader_of(b, ver, *i).label())).map(|i| NAMES[i as usize]).collect();
    let name = NAMES[loader.clamp(0, 3) as usize];
    if ok.is_empty() {
        format!("The Nether client doesn't support Minecraft {ver}. It runs on 1.8.9, 1.12.2 and 1.14 or newer.")
    } else if loader == 3 {
        format!("The Nether client doesn't run on {name} yet. On {ver} it runs with {}.", or_list(&ok))
    } else {
        format!("On {ver}, the Nether client runs with {} only, not {name}.", or_list(&ok))
    }
}

fn perf_text(sh: &Shared, loader: i32) -> String {
    match loader {
        0 => "Vanilla doesn't load mods.".into(),
        1 => netherpack::find(&sh.paths, "essentials").map(|p| format!("{} Recommended.", p.desc)).unwrap_or_else(|| "Sodium, Lithium, FerriteCore, ModernFix and more. Recommended.".into()),
        2 => "Embeddium, FerriteCore, ModernFix and more, where this version has them. Recommended.".into(),
        _ => "Sodium, Lithium, FerriteCore, ModernFix and more. Recommended.".into(),
    }
}

pub fn init(ui: &AppWindow, sh: &Arc<Shared>) {
    let n = ui.global::<NewInst>();
    let weak = ui.as_weak();
    n.on_loader_ok(move |ver, l| weak.upgrade().is_none_or(|ui| loader_ok(&ui.global::<Backend>(), &ver, l)));
    let weak = ui.as_weak();
    n.on_nether_why(move |ver, l| weak.upgrade().map(|ui| nether_why(&ui.global::<Backend>(), &ver, l)).unwrap_or_default().into());
    let (weak, s) = (ui.as_weak(), sh.clone());
    n.on_free_name(move |base| {
        let Some(ui) = weak.upgrade() else { return base };
        let taken: Vec<String> = ui.global::<Backend>().get_instances().iter().map(|i| i.name.to_string()).collect();
        instances::free_name(&s.paths, base.trim(), &taken).into()
    });
    let s = sh.clone();
    n.on_perf_text(move |l| perf_text(&s, l).into());
    let (weak, s) = (ui.as_weak(), sh.clone());
    n.on_load_perf(move |ver, l| if let Some(ui) = weak.upgrade() { load_perf(&ui, &s, &ver, l) });
    let weak = ui.as_weak();
    n.on_perf_set(move |on| if let Some(ui) = weak.upgrade() { perf_set(&ui, None, on) });
    let (weak, s) = (ui.as_weak(), sh.clone());
    n.on_load_mods(move |ver, l, q| if let Some(ui) = weak.upgrade() { load_mods(&ui, &s, &ver, l, &q) });
    let weak = ui.as_weak();
    n.on_toggle_mod(move |id| if let Some(ui) = weak.upgrade() { toggle(&ui, &id) });
    let weak = ui.as_weak();
    n.on_reset(move || if let Some(ui) = weak.upgrade() {
        let n = ui.global::<NewInst>();
        n.set_mods(ModelRc::default());
        n.set_picked(ModelRc::default());
        n.set_perf_mods(ModelRc::default());
        n.set_perf_all(true);
        n.set_packs(ModelRc::default());
        SEARCH.with(|s| s.borrow_mut().clear());
        PACK_SEARCH.with(|s| s.borrow_mut().clear());
    });
    let (weak, s) = (ui.as_weak(), sh.clone());
    n.on_load_packs(move |q| if let Some(ui) = weak.upgrade() { load_packs(&ui, &s, &q) });
    let (weak, s) = (ui.as_weak(), sh.clone());
    n.on_create_pack(move |id, title, nether, play| weak.upgrade().is_some_and(|ui| create_pack(&ui, &s, &id, &title, nether, play)));
    let (weak, s) = (ui.as_weak(), sh.clone());
    n.on_create(move |name, ver, l, nether, fast, play| weak.upgrade().is_some_and(|ui| create(&ui, &s, &name, &ver, l, nether, fast, play)));
}

/// The performance mods offered for a loader, as (Modrinth slug, name). Fabric gets the Nether Essentials pack (the
/// backend's copy when published) minus Fabric API, which is always installed; the other loaders get perf.rs's set.
fn perf_list(sh: &Shared, l: Loader) -> Vec<(String, String)> {
    let name = |slug: &str| -> String {
        let known = perf::catalog(Loader::Fabric).iter().chain(perf::catalog(Loader::Forge)).find(|b| b.slug == slug).map(|b| b.name.to_string());
        known.unwrap_or_else(|| match slug { "modmenu" => "Mod Menu".into(), "iris" => "Iris".into(), s => s.to_string() })
    };
    if l == Loader::Fabric {
        if let Some(p) = netherpack::find(&sh.paths, "essentials") {
            return p.mods.iter().filter(|m| *m != "fabric-api").map(|m| (m.clone(), name(m))).collect();
        }
    }
    perf::catalog(l).iter().map(|b| (b.slug.to_string(), b.name.to_string())).collect()
}

/// The performance mods for this version + loader, every one on (the toggle starts on). They show in the mods list
/// below the toggle (id = Modrinth slug), so the list must be filled after this.
fn load_perf(ui: &AppWindow, sh: &Arc<Shared>, ver: &str, loader: i32) {
    let l = loader_of(&ui.global::<Backend>(), ver, loader);
    let rows: Vec<Row> = perf_list(sh, l).into_iter().map(|(id, name)| Row { id: id.into(), name: name.into(), on: true, ..Default::default() }).collect();
    let n = ui.global::<NewInst>();
    n.set_perf_all(!rows.is_empty());
    n.set_perf_n(rows.len() as i32);
    n.set_perf_mods(ModelRc::new(VecModel::from(rows)));
}

/// Turn one performance mod (`id`) or all of them (None) on or off, here and in the mods list. The toggle is on only
/// while every one is on, so turning one off turns it off and turning the last one on turns it back on. The toggle
/// also re-sorts the list so what's on stays at the top; a single row keeps its place under the cursor.
fn perf_set(ui: &AppWindow, id: Option<&str>, on: bool) {
    let n = ui.global::<NewInst>();
    let m = n.get_perf_mods();
    for k in 0..m.row_count() {
        let Some(mut r) = m.row_data(k) else { continue };
        if id.is_some_and(|id| r.id != id) || r.on == on { continue; }
        r.on = on;
        m.set_row_data(k, r);
    }
    let ticked = m.iter().filter(|r| r.on).count();
    n.set_perf_n(ticked as i32);
    n.set_perf_all(ticked > 0 && ticked == m.row_count());
    let mods = n.get_mods();
    let mut rows: Vec<Row> = mods.iter().collect();
    for r in rows.iter_mut() {
        if let Some(p) = m.iter().find(|p| p.id == r.id) { r.on = p.on; }
    }
    if id.is_none() { rows.sort_by_key(|r| !r.on); }
    for (k, r) in rows.into_iter().enumerate() { mods.set_row_data(k, r); }
}

/// Popular (or searched) mods for this version + loader, icons streamed in. Picks survive a new search.
fn load_mods(ui: &AppWindow, sh: &Arc<Shared>, ver: &str, loader: i32, query: &str) {
    let b = ui.global::<Backend>();
    let ml = perf::modrinth_loader(loader_of(&b, ver, loader)).to_string();
    let key = format!("{ver}|{ml}|{}", query.trim());
    if SEARCH.with(|s| *s.borrow() == key) && ui.global::<NewInst>().get_mods().row_count() > 0 { return; }
    SEARCH.with(|s| *s.borrow_mut() = key.clone());
    let n = ui.global::<NewInst>();
    n.set_mods_loading(true);
    // the performance mods are rows of this list too (id = slug, on = the toggle's state for it)
    let perf: Vec<(String, String)> = n.get_perf_mods().iter().map(|r| (r.id.to_string(), r.name.to_string())).collect();
    let (weak, sh, ver, q) = (ui.as_weak(), sh.clone(), ver.to_string(), query.trim().to_string());
    std::thread::spawn(move || {
        let c = client();
        let sort = if q.is_empty() { "downloads" } else { "relevance" };
        let found = modrinth::search(&c, &q, "mod", &ver, Some(&ml), sort, None).unwrap_or_default();
        let slugs: Vec<String> = perf.iter().map(|(s, _)| s.clone()).collect();
        let known = modrinth::hits(&c, &slugs).unwrap_or_default();
        let slug_of = |id: &str| known.iter().find(|(_, h)| h.id == id).map(|(s, _)| s.clone());
        // no search: every performance mod first; a search: its hits, a performance mod's under its slug
        let mut hits: Vec<modrinth::Hit> = if q.is_empty() {
            perf.iter().map(|(s, name)| {
                let mut h = known.get(s).cloned().unwrap_or_else(|| modrinth::Hit { title: name.clone(), ..Default::default() });
                h.id = s.clone();
                h
            }).collect()
        } else { vec![] };
        for mut h in found.into_iter().take(24) {
            match slug_of(&h.id) {
                Some(_) if q.is_empty() => continue,
                Some(s) => h.id = s,
                None => {}
            }
            hits.push(h);
        }
        let meta: Vec<(String, String, String, String, String)> = hits.iter().map(|h| (h.id.clone(), h.title.clone(), h.desc.clone(), if h.downloads > 0 { count(h.downloads) } else { String::new() }, h.icon_url.clone())).collect();
        let k2 = key.clone();
        ui_do(&weak, move |ui| {
            if SEARCH.with(|s| *s.borrow() != k2) { return; }
            let n = ui.global::<NewInst>();
            let picked: Vec<String> = n.get_picked().iter().map(|r| r.id.to_string()).collect();
            let perf = n.get_perf_mods();
            let mut rows: Vec<Row> = meta.iter().map(|(id, t, d, c, icon)| {
                let img = cached_icon(icon);
                let on = perf.iter().find(|p| p.id == id.as_str()).map_or(picked.contains(id), |p| p.on);
                Row { id: id.as_str().into(), name: t.as_str().into(), sub: d.as_str().into(), meta: c.as_str().into(), path: icon.as_str().into(),
                    on, has_icon: img.is_some(), icon: img.unwrap_or_default(), ..Default::default() }
            }).collect();
            // what's on at the top
            rows.sort_by_key(|r| !r.on);
            n.set_mods_loading(false);
            n.set_mods(ModelRc::new(VecModel::from(rows)));
        });
        for h in hits.into_iter().filter(|h| !h.icon_url.is_empty()) {
            let (weak, sh, key) = (weak.clone(), sh.clone(), key.clone());
            std::thread::spawn(move || {
                let Some(px) = icon_px(&sh, &h.icon_url) else { return };
                ui_do(&weak, move |ui| {
                    if SEARCH.with(|s| *s.borrow() != key) { return; }
                    let img = cached_icon(&h.icon_url).unwrap_or_else(|| icon_image(&h.icon_url, &px));
                    update_rows(&ui.global::<NewInst>().get_mods(), &h.id, &|r| { r.icon = img.clone(); r.has_icon = true; });
                });
            });
        }
    });
}

/// A modpack's loader as the walkthrough names it, from its Modrinth categories ("" = none we know).
fn pack_loader(loaders: &[String]) -> &'static str {
    for (key, label) in [("fabric", "Fabric"), ("forge", "Forge"), ("neoforge", "NeoForge"), ("quilt", "Quilt")] {
        if loaders.iter().any(|l| l == key) { return label; }
    }
    ""
}

/// Why the Nether client can't run in a modpack on this version + loader ("" = it can, or we can't tell yet).
fn pack_why(mc: &str, loader: &str) -> String {
    if mc.is_empty() || loader.is_empty() || instances::nether_ok(mc, loader) { return String::new(); }
    format!("The Nether client doesn't run in {loader} packs on {mc}. It runs in Fabric and Forge packs on 1.14 or newer.")
}

/// MODPACK step: Modrinth modpacks, most downloaded first (or a search), icons streamed in.
fn load_packs(ui: &AppWindow, sh: &Arc<Shared>, query: &str) {
    let key = query.trim().to_string();
    if PACK_SEARCH.with(|s| *s.borrow() == key) && ui.global::<NewInst>().get_packs().row_count() > 0 { return; }
    PACK_SEARCH.with(|s| *s.borrow_mut() = key.clone());
    ui.global::<NewInst>().set_packs_loading(true);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let sort = if key.is_empty() { "downloads" } else { "relevance" };
        let hits: Vec<_> = modrinth::search(&client(), &key, "modpack", "", None, sort, None).unwrap_or_default().into_iter().take(24).collect();
        let rows: Vec<(String, String, String, String, String, String, String)> = hits.iter().map(|h| {
            let l = pack_loader(&h.loaders);
            // the row's left line: "1.21.11 · Fabric · <description>"; its right corner: downloads
            let sub = [h.mc.as_str(), l, h.desc.as_str()].iter().filter(|s| !s.is_empty()).copied().collect::<Vec<_>>().join(" · ");
            (h.id.clone(), h.title.clone(), sub, count(h.downloads), h.mc.clone(), l.to_string(), h.icon_url.clone())
        }).collect();
        let k2 = key.clone();
        ui_do(&weak, move |ui| {
            if PACK_SEARCH.with(|s| *s.borrow() != k2) { return; }
            let rows: Vec<Row> = rows.iter().map(|(id, t, sub, dls, mc, l, icon)| {
                let img = cached_icon(icon);
                Row { id: id.as_str().into(), name: t.as_str().into(), sub: sub.as_str().into(), meta: dls.as_str().into(), mc: mc.as_str().into(),
                    ver: l.as_str().into(), path: pack_why(mc, l).into(), has_icon: img.is_some(), icon: img.unwrap_or_default(), ..Default::default() }
            }).collect();
            let n = ui.global::<NewInst>();
            n.set_packs_loading(false);
            n.set_packs(ModelRc::new(VecModel::from(rows)));
        });
        for h in hits.into_iter().filter(|h| !h.icon_url.is_empty()) {
            let (weak, sh, key) = (weak.clone(), sh.clone(), key.clone());
            std::thread::spawn(move || {
                let Some(px) = icon_px(&sh, &h.icon_url) else { return };
                ui_do(&weak, move |ui| {
                    if PACK_SEARCH.with(|s| *s.borrow() != key) { return; }
                    let img = cached_icon(&h.icon_url).unwrap_or_else(|| icon_image(&h.icon_url, &px));
                    update_rows(&ui.global::<NewInst>().get_packs(), &h.id, &|r| { r.icon = img.clone(); r.has_icon = true; });
                });
            });
        }
    });
}

/// Install a Modrinth modpack (its newest version) as a new instance, with the Nether client on or off, then play
/// it when asked. The download runs in the background with a progress row; the instance appears when it is done.
fn create_pack(ui: &AppWindow, sh: &Arc<Shared>, id: &str, title: &str, nether: bool, play: bool) -> bool {
    if id.is_empty() { return false; }
    let key = format!("pack:{id}");
    let (weak, sh, id, title) = (ui.as_weak(), sh.clone(), id.to_string(), title.to_string());
    let (w2, k2) = (weak.clone(), key.clone());
    let prog: nether_core::Progress = Arc::new(move |m: &str, f: f32| { let (m, k) = (m.to_string(), k2.clone()); ui_do(&w2, move |ui| crate::job(ui, &k, &m, f)); });
    prog(&format!("Installing {title}"), 0.0);
    std::thread::spawn(move || {
        let res = modpack::install_project(&client(), &sh.paths, &id, None, None, &prog);
        ui_do(&weak, move |ui| {
            crate::job_end(ui, &key);
            match res {
                Ok(mut data) => {
                    data.nether = Some(nether);
                    let name = data.name.clone();
                    let b = ui.global::<Backend>();
                    if let Some(v) = b.get_instances().as_any().downcast_ref::<VecModel<crate::Inst>>() {
                        v.push(crate::inst_from(&data));
                        crate::persist(&sh.paths, v);
                    }
                    crate::refilter(ui);
                    crate::artui::refresh(ui, &sh);
                    show_toast(ui, &sh, &format!("{title} is ready as {name}"));
                    if play { b.invoke_play(name.into()); }
                }
                Err(e) => show_toast(ui, &sh, &format!("Modpack install failed: {e}")),
            }
        });
    });
    true
}

/// Pick or unpick a mod on screen.
fn toggle(ui: &AppWindow, id: &str) {
    let n = ui.global::<NewInst>();
    if let Some(p) = n.get_perf_mods().iter().find(|p| p.id == id) { return perf_set(ui, Some(id), !p.on); }
    let mods = n.get_mods();
    let Some(k) = mods.iter().position(|r| r.id == id) else { return };
    let Some(mut r) = mods.row_data(k) else { return };
    r.on = !r.on;
    mods.set_row_data(k, r.clone());
    let mut picked: Vec<Row> = n.get_picked().iter().filter(|p| p.id != id).collect();
    if r.on { picked.push(r); }
    n.set_picked(ModelRc::new(VecModel::from(picked)));
}

/// Make the instance, then download its mods and packs in the background (and play when asked).
#[allow(clippy::too_many_arguments)]
fn create(ui: &AppWindow, sh: &Arc<Shared>, name: &str, ver: &str, loader: i32, nether: bool, perf_mode: i32, play: bool) -> bool {
    let b = ui.global::<Backend>();
    let (name, ver) = (name.trim().to_string(), ver.to_string());
    let l = loader_of(&b, &ver, loader);
    b.set_create_nether(nether);
    if !b.invoke_create_instance(name.as_str().into(), ver.as_str().into(), create_code(l), b.get_new_ram(), "".into(), "".into()) { return false; }
    crate::client::setup_instance(&sh.paths, &name, None);
    let mods: Vec<(String, String)> = if l == Loader::Vanilla { vec![] } else { ui.global::<NewInst>().get_picked().iter().map(|r| (r.id.to_string(), r.name.to_string())).collect() };
    let rps: Vec<(String, String)> = ui.global::<Wizard>().get_rps().iter().filter(|r| r.on).map(|r| (r.id.to_string(), r.name.to_string())).collect();
    // the performance mods to add: all of them (quick setup), the ticked boxes (manual), or none
    let boosts: Vec<String> = match perf_mode {
        1 => perf_list(sh, l).into_iter().map(|(id, _)| id).collect(),
        2 => ui.global::<NewInst>().get_perf_mods().iter().filter(|r| r.on).map(|r| r.id.to_string()).collect(),
        _ => vec![],
    };
    // Vanilla and Legacy Fabric (1.8.9 / 1.12.2) have no performance mods to add
    let (wanted, fast) = (!boosts.is_empty(), !boosts.is_empty() && !perf::catalog(l).is_empty());
    if !fast && mods.is_empty() && rps.is_empty() {
        if wanted && l != Loader::Vanilla { show_toast(ui, sh, &format!("No performance mods are made for {ver}")); }
        if play { b.invoke_play(name.into()); }
        return true;
    }
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let c = client();
        let key = format!("setup:{name}");
        let (w2, k2) = (weak.clone(), key.clone());
        let prog: nether_core::Progress = Arc::new(move |m: &str, f: f32| { let (m, k) = (m.to_string(), k2.clone()); ui_do(&w2, move |ui| crate::job(ui, &k, &m, f)); });
        let mods_dir = instances::content_dir(&sh.paths, &name, 0);
        let mut skipped: Vec<String> = vec![];
        let mut perf_none = wanted && !fast;
        if fast {
            prog("Adding performance mods", 0.0);
            if l == Loader::Fabric {
                // the Nether Essentials pack (published by the backend, built-in copy as fallback), cut down to the
                // ticked mods; Fabric API comes with it either way
                if let Some(mut p) = netherpack::find(&sh.paths, "essentials") {
                    p.mods.retain(|m| m == "fabric-api" || boosts.contains(m));
                    match netherpack::install(&c, &p, &ver, &mods_dir, &prog) {
                        Ok((ok, _)) => perf_none = ok.iter().all(|m| m == "fabric-api"),
                        Err(_) => perf_none = true,
                    }
                }
            } else {
                let mut any = false;
                for slug in &boosts {
                    if perf::set(&c, &sh.paths, &name, l, &ver, slug, true).is_ok() { any = true; }
                }
                perf_none = !any;
            }
        }
        let ml = perf::modrinth_loader(l);
        for (i, (id, title)) in mods.iter().enumerate() {
            prog(&format!("Installing {title}"), i as f32 / mods.len().max(1) as f32);
            if modrinth::install(&c, id, None, &ver, Some(ml), &mods_dir, &|_, _, _| {}).is_err() { skipped.push(title.clone()); }
        }
        let rp_dir = instances::content_dir(&sh.paths, &name, 1);
        for (i, (id, title)) in rps.iter().enumerate() {
            prog(&format!("Installing {title}"), i as f32 / rps.len().max(1) as f32);
            if modrinth::install(&c, id, None, &ver, None, &rp_dir, &|_, _, _| {}).is_err() { skipped.push(title.clone()); }
        }
        ui_do(&weak, move |ui| {
            crate::job_end(ui, &key);
            let mut msg = format!("{name} is ready");
            if perf_none { msg.push_str(&format!(". No performance mods for {ver} yet")); }
            if !skipped.is_empty() { msg.push_str(&format!(". Skipped: {}", skipped.join(", "))); }
            show_toast(ui, &sh, &msg);
            if play { ui.global::<Backend>().invoke_play(name.into()); }
        });
    });
    true
}

#[cfg(test)]
mod tests {
    #[test]
    fn or_list_reads_well() {
        use super::or_list;
        assert_eq!(or_list(&["Vanilla"]), "Vanilla");
        assert_eq!(or_list(&["Vanilla", "Fabric"]), "Vanilla or Fabric");
        assert_eq!(or_list(&["Vanilla", "Fabric", "Forge"]), "Vanilla, Fabric or Forge");
    }
}
