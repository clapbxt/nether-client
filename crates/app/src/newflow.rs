//! New-instance walkthrough (ui/newflow.slint, 2026-11-26). QUICK makes "Nether <version>": Fabric, the Nether client
//! and the Nether Essentials performance pack. MANUAL picks version, loader (Vanilla, Fabric, Forge, NeoForge), Nether
//! on / off (with the reason when it can't run), first mods from Modrinth plus the performance mods, and resource
//! packs. The instance is made by `create-instance`; downloads run after it in the background, then PLAY if asked.
use std::cell::RefCell;
use std::sync::Arc;

use nether_core::download::client;
use nether_core::launch::Loader;
use nether_core::{instances, modrinth, netherpack, perf};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::mods::{cached_icon, count, icon_image, icon_px, update_rows};
use crate::{show_toast, ui_do, AppWindow, Backend, NewInst, Row, Shared, Wizard};

thread_local! {
    /// the search the mods list on screen belongs to (a late answer for an older one is dropped)
    static SEARCH: RefCell<String> = const { RefCell::new(String::new()) };
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
    n.on_load_mods(move |ver, l, q| if let Some(ui) = weak.upgrade() { load_mods(&ui, &s, &ver, l, &q) });
    let weak = ui.as_weak();
    n.on_toggle_mod(move |id| if let Some(ui) = weak.upgrade() { toggle(&ui, &id) });
    let weak = ui.as_weak();
    n.on_reset(move || if let Some(ui) = weak.upgrade() {
        let n = ui.global::<NewInst>();
        n.set_mods(ModelRc::default());
        n.set_picked(ModelRc::default());
        SEARCH.with(|s| s.borrow_mut().clear());
    });
    let (weak, s) = (ui.as_weak(), sh.clone());
    n.on_create(move |name, ver, l, nether, fast, play| weak.upgrade().is_some_and(|ui| create(&ui, &s, &name, &ver, l, nether, fast, play)));
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
    let (weak, sh, ver, q) = (ui.as_weak(), sh.clone(), ver.to_string(), query.trim().to_string());
    std::thread::spawn(move || {
        let sort = if q.is_empty() { "downloads" } else { "relevance" };
        let hits = modrinth::search(&client(), &q, "mod", &ver, Some(&ml), sort, None).unwrap_or_default();
        let hits: Vec<_> = hits.into_iter().take(24).collect();
        let meta: Vec<(String, String, String, String, String)> = hits.iter().map(|h| (h.id.clone(), h.title.clone(), h.desc.clone(), count(h.downloads), h.icon_url.clone())).collect();
        let k2 = key.clone();
        ui_do(&weak, move |ui| {
            if SEARCH.with(|s| *s.borrow() != k2) { return; }
            let n = ui.global::<NewInst>();
            let picked: Vec<String> = n.get_picked().iter().map(|r| r.id.to_string()).collect();
            let rows: Vec<Row> = meta.iter().map(|(id, t, d, c, icon)| {
                let img = cached_icon(icon);
                Row { id: id.as_str().into(), name: t.as_str().into(), sub: d.as_str().into(), meta: c.as_str().into(), path: icon.as_str().into(),
                    on: picked.contains(id), has_icon: img.is_some(), icon: img.unwrap_or_default(), ..Default::default() }
            }).collect();
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

/// Pick or unpick a mod on screen.
fn toggle(ui: &AppWindow, id: &str) {
    let n = ui.global::<NewInst>();
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
fn create(ui: &AppWindow, sh: &Arc<Shared>, name: &str, ver: &str, loader: i32, nether: bool, fast: bool, play: bool) -> bool {
    let b = ui.global::<Backend>();
    let (name, ver) = (name.trim().to_string(), ver.to_string());
    let l = loader_of(&b, &ver, loader);
    b.set_create_nether(nether);
    if !b.invoke_create_instance(name.as_str().into(), ver.as_str().into(), create_code(l), b.get_new_ram(), "".into(), "".into()) { return false; }
    crate::client::setup_instance(&sh.paths, &name, None);
    let mods: Vec<(String, String)> = if l == Loader::Vanilla { vec![] } else { ui.global::<NewInst>().get_picked().iter().map(|r| (r.id.to_string(), r.name.to_string())).collect() };
    let rps: Vec<(String, String)> = ui.global::<Wizard>().get_rps().iter().filter(|r| r.on).map(|r| (r.id.to_string(), r.name.to_string())).collect();
    // Vanilla and Legacy Fabric (1.8.9 / 1.12.2) have no performance mods to add
    let (wanted, fast) = (fast, fast && !perf::catalog(l).is_empty());
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
                // the Nether Essentials pack (published by the backend, built-in copy as fallback)
                if let Some(p) = netherpack::find(&sh.paths, "essentials") {
                    match netherpack::install(&c, &p, &ver, &mods_dir, &prog) {
                        Ok((ok, _)) => perf_none = ok.iter().all(|m| m == "fabric-api"),
                        Err(_) => perf_none = true,
                    }
                }
            } else {
                let mut any = false;
                for bo in perf::catalog(l) {
                    if perf::set(&c, &sh.paths, &name, l, &ver, bo.slug, true).is_ok() { any = true; }
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
