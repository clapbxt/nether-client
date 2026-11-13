//! New-instance setup wizard: loader -> version -> Nether pack -> client modules -> settings source -> resource packs -> look.
//! The instance itself is made by `create-instance`; `Wizard.finish` then applies the choices and downloads in the background.
use std::sync::Arc;

use nether_core::download::client;
use nether_core::{instances, modrinth, netherpack};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::mods::{cached_icon, count, icon_image, icon_px, update_rows};
use crate::{show_toast, ui_do, AppWindow, Backend, Row, Shared, Wizard};

/// Pack list (built-ins + user packs) for the Nether pack step.
pub fn init(ui: &AppWindow, sh: &Arc<Shared>) {
    let rows: Vec<Row> = netherpack::all(&sh.paths).iter().map(|p| Row {
        id: p.id.as_str().into(), name: p.name.as_str().into(), sub: p.desc.as_str().into(),
        meta: format!("{} mods", p.mods.len()).into(), ok: p.client.is_some() as i32, ..Default::default()
    }).collect();
    ui.global::<Wizard>().set_packs(ModelRc::new(VecModel::from(rows)));
}

/// A pack was picked ("" = none): offer its built-in settings.
pub fn pick_pack(ui: &AppWindow, sh: &Arc<Shared>, id: &str) {
    let w = ui.global::<Wizard>();
    w.set_pack(id.into());
    let p = (!id.is_empty()).then(|| netherpack::find(&sh.paths, id)).flatten();
    let preset = p.as_ref().is_some_and(|p| p.client.is_some());
    w.set_pack_has_client(preset);
    w.set_mode(if preset { 2 } else if w.get_mode() == 2 { 0 } else { w.get_mode() });
}

/// Popular resource packs for `ver` (the resource pack step), icons streamed in.
pub fn load_rps(ui: &AppWindow, sh: &Arc<Shared>, ver: &str) {
    let w = ui.global::<Wizard>();
    // "" = the wizard opened: forget the last picks
    if ver.is_empty() { w.set_rp_ver("".into()); w.set_rps(ModelRc::default()); return; }
    if w.get_rp_ver() == ver && w.get_rps().row_count() > 0 { return; }
    w.set_rp_ver(ver.into());
    w.set_rp_loading(true);
    w.set_rps(ModelRc::default());
    let (weak, sh, ver) = (ui.as_weak(), sh.clone(), ver.to_string());
    std::thread::spawn(move || {
        let hits = modrinth::search(&client(), "", "resourcepack", &ver, None, "downloads", None).unwrap_or_default();
        let meta: Vec<(String, String, String, String, String)> = hits.iter().take(18).map(|h| (h.id.clone(), h.title.clone(), h.desc.clone(), count(h.downloads), h.icon_url.clone())).collect();
        let v2 = ver.clone();
        ui_do(&weak, move |ui| {
            let w = ui.global::<Wizard>();
            if w.get_rp_ver() != v2.as_str() { return; }
            let rows: Vec<Row> = meta.iter().map(|(id, t, d, n, icon)| {
                let img = cached_icon(icon);
                Row { id: id.as_str().into(), name: t.as_str().into(), sub: d.as_str().into(), meta: n.as_str().into(), has_icon: img.is_some(), icon: img.unwrap_or_default(), ..Default::default() }
            }).collect();
            w.set_rp_loading(false);
            w.set_rps(ModelRc::new(VecModel::from(rows)));
        });
        for h in hits.into_iter().take(18).filter(|h| !h.icon_url.is_empty()) {
            let (weak, sh) = (weak.clone(), sh.clone());
            std::thread::spawn(move || {
                let Some(px) = icon_px(&sh, &h.icon_url) else { return };
                ui_do(&weak, move |ui| {
                    let img = cached_icon(&h.icon_url).unwrap_or_else(|| icon_image(&h.icon_url, &px));
                    update_rows(&ui.global::<Wizard>().get_rps(), &h.id, &|r| { r.icon = img.clone(); r.has_icon = true; });
                });
            });
        }
    });
}

/// After `create-instance` made `name`: client setup, then pack mods + resource packs in the background.
pub fn finish(ui: &AppWindow, sh: &Arc<Shared>, name: &str, ver: &str) {
    let name = name.trim();
    let w = ui.global::<Wizard>();
    let pack = (!w.get_pack().is_empty()).then(|| netherpack::find(&sh.paths, w.get_pack().as_str())).flatten();
    let mode = w.get_mode();
    crate::client::setup_instance(&sh.paths, name, mode, pack.as_ref().and_then(|p| p.client.as_ref()));
    // instance card label
    let b = ui.global::<Backend>();
    let m = b.get_instances();
    if let Some(i) = m.iter().position(|i| i.name == name) {
        let mut r = m.row_data(i).unwrap();
        r.client = (if mode == 0 { "Global" } else { "Custom" }).into();
        m.set_row_data(i, r);
        crate::persist(&sh.paths, &m);
    }
    let rps: Vec<(String, String)> = w.get_rps().iter().filter(|r| r.on).map(|r| (r.id.to_string(), r.name.to_string())).collect();
    if pack.is_none() && rps.is_empty() { return; }
    let (weak, sh, name, ver) = (ui.as_weak(), sh.clone(), name.to_string(), ver.to_string());
    std::thread::spawn(move || {
        let c = client();
        let w2 = weak.clone();
        let key = format!("setup:{name}");
        let k2 = key.clone();
        let prog: nether_core::Progress = Arc::new(move |m: &str, f: f32| { let (m, k) = (m.to_string(), k2.clone()); ui_do(&w2, move |ui| crate::job(ui, &k, &m, f)); });
        let mut missing: Vec<String> = vec![];
        if let Some(p) = &pack {
            match netherpack::install(&c, p, &ver, &instances::content_dir(&sh.paths, &name, 0), &prog) {
                Ok((_, miss)) => missing.extend(miss),
                Err(e) => missing.push(format!("{} ({e})", p.name)),
            }
        }
        let dir = instances::content_dir(&sh.paths, &name, 1);
        for (i, (id, title)) in rps.iter().enumerate() {
            prog(&format!("Installing {title}"), i as f32 / rps.len().max(1) as f32);
            if modrinth::install(&c, id, None, &ver, None, &dir, &|_, _, _| {}).is_err() { missing.push(title.clone()); }
        }
        ui_do(&weak, move |ui| {
            crate::job_end(ui, &key);
            let msg = if missing.is_empty() { format!("{name} is ready") } else { format!("{name} is ready (skipped: {})", missing.join(", ")) };
            show_toast(ui, &sh, &msg);
        });
    });
}
