//! Install Packs page: Modrinth modpacks and Nether packs, opened in the shared project page.
//! Rows use `Row` (id "nether:<id>" for Nether packs); the page sets `Backend.detail-pack`.
use std::rc::Rc;
use std::sync::Arc;

use nether_core::download::client;
use nether_core::{instances, modpack, modrinth, netherpack};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::mods::{cached_icon, count, icon_image, icon_px, nice_date, title_case, update_rows};
use crate::{show_toast, ui_do, AppWindow, Backend, Detail, Inst, Link, Row, Shared, VerOpt};

/// Sort dropdown order. Relevance only means something with search words; with none, Modrinth search falls back to downloads.
const SORTS: [&str; 5] = ["relevance", "downloads", "follows", "newest", "updated"];
/// Modrinth modpack categories in the Install Packs dropdown order ("" = all).
const PACK_CATS: [&str; 11] = ["", "adventure", "challenging", "combat", "kitchen-sink", "lightweight", "magic", "multiplayer", "optimization", "quests", "technology"];
/// Loader dropdown order ("" = any). Quilt is left out: Modrinth's Quilt filter also matches Fabric packs.
const PACK_LOADERS: [&str; 4] = ["", "fabric", "forge", "neoforge"];

fn loaders_text(l: &[String]) -> String {
    l.iter().map(|x| title_case(x)).collect::<Vec<_>>().join(" · ")
}

/// Fill `Backend.pack-rows` for the selected source (0 Modrinth, 2 Nether; CurseForge is hidden until there is an API key).
pub fn load(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    match b.get_pack_src() {
        2 => {
            let rows: Vec<Row> = netherpack::all(&sh.paths).iter().map(|p| Row {
                id: format!("nether:{}", p.id).into(), name: p.name.as_str().into(), sub: p.desc.as_str().into(),
                meta: format!("{} mods", p.mods.len()).into(), ver: title_case(&p.loader).into(), on: true, ..Default::default()
            }).collect();
            b.set_pack_loading(false);
            b.set_pack_rows(ModelRc::new(VecModel::from(rows)));
        }
        _ => {
            b.set_pack_loading(true);
            let (weak, sh, q, sort) = (ui.as_weak(), sh.clone(), b.get_pack_query().to_string(), SORTS[b.get_pack_sort().clamp(0, SORTS.len() as i32 - 1) as usize]);
            let cat = PACK_CATS[b.get_pack_cat().clamp(0, PACK_CATS.len() as i32 - 1) as usize];
            let loader = PACK_LOADERS[b.get_pack_loader().clamp(0, PACK_LOADERS.len() as i32 - 1) as usize];
            // a slower, older search never replaces a newer one (typing fires a search per pause)
            let gen = SEARCH_GEN.with(|g| { g.set(g.get() + 1); g.get() });
            std::thread::spawn(move || {
                let res = modrinth::search(&client(), q.trim(), "modpack", "", (!loader.is_empty()).then_some(loader), sort, (!cat.is_empty()).then_some(cat));
                let hits = match res {
                    Ok(h) => h,
                    Err(e) => {
                        let (sh, msg) = (sh.clone(), format!("Modrinth search failed: {e}"));
                        ui_do(&weak, move |ui| { if SEARCH_GEN.with(|g| g.get()) != gen { return; } ui.global::<Backend>().set_pack_loading(false); show_toast(ui, &sh, &msg); });
                        return;
                    }
                };
                let meta: Vec<(String, String, String, String, String, String, String)> = hits.iter().map(|h| (h.id.clone(), h.title.clone(), format!("{} · by {}", h.desc, h.author), count(h.downloads), h.mc.clone(), loaders_text(&h.loaders), h.icon_url.clone())).collect();
                ui_do(&weak, move |ui| {
                    let b = ui.global::<Backend>();
                    if b.get_pack_src() != 0 || SEARCH_GEN.with(|g| g.get()) != gen { return; }
                    let rows: Vec<Row> = meta.iter().map(|(id, t, s, d, mc, l, icon)| {
                        let img = cached_icon(icon);
                        let (t, s) = (crate::mods::glyphs(t), crate::mods::glyphs(s));
                        Row { id: id.as_str().into(), cjk: crate::mods::cjk(&t) || crate::mods::cjk(&s), name: t.into(), sub: s.into(), meta: d.as_str().into(), mc: mc.as_str().into(), ver: l.as_str().into(), has_icon: img.is_some(), icon: img.unwrap_or_default(), on: true, ..Default::default() }
                    }).collect();
                    b.set_pack_loading(false);
                    b.set_pack_rows(ModelRc::new(VecModel::from(rows)));
                });
                for h in hits.into_iter().filter(|h| !h.icon_url.is_empty()) {
                    let (weak, sh) = (weak.clone(), sh.clone());
                    std::thread::spawn(move || {
                        let Some(px) = icon_px(&sh, &h.icon_url) else { return };
                        ui_do(&weak, move |ui| {
                            let img = cached_icon(&h.icon_url).unwrap_or_else(|| icon_image(&h.icon_url, &px));
                            update_rows(&ui.global::<Backend>().get_pack_rows(), &h.id, &|r| { r.icon = img.clone(); r.has_icon = true; });
                        });
                    });
                }
            });
        }
    }
}

fn strs(v: &[String]) -> ModelRc<slint::SharedString> {
    ModelRc::new(VecModel::from(v.iter().map(|s| s.as_str().into()).collect::<Vec<slint::SharedString>>()))
}

/// Release game versions the Nether pack can target (from the create dialog's Fabric list).
fn pack_versions(ui: &AppWindow) -> Vec<String> {
    ui.global::<Backend>().get_fabric_versions().iter().map(|s| s.to_string()).filter(|v| !v.contains('-') && !v.contains('w')).take(40).collect()
}

/// Open the project page for pack row `row`.
pub fn open(ui: &AppWindow, sh: &Arc<Shared>, row: i32) {
    let b = ui.global::<Backend>();
    let Some(r) = b.get_pack_rows().row_data(row.max(0) as usize) else { return };
    let mut d = Detail { id: r.id.clone(), title: r.name.clone(), summary: r.sub.clone(), icon: r.icon.clone(), has_icon: r.has_icon, loading: true, ..Default::default() };
    b.set_detail_pack(true);

    if let Some(pid) = r.id.strip_prefix("nether:") {
        let Some(p) = netherpack::find(&sh.paths, pid) else { return };
        let vers = pack_versions(ui);
        let groups: Vec<String> = p.groups.iter().map(|g| match g.as_str() { "hud" => "HUD".into(), "pvp" => "PvP".into(), "qol" => "QoL".into(), x => title_case(x) }).collect();
        let mods: Vec<String> = p.mods.iter().map(|m| title_case(m)).collect();
        let md = format!("{}\n\n**Included mods**\n\n{}\n\n**Client modules**\n\n{}\n\nMods are downloaded from Modrinth for the version you pick. A mod with no build for that version is skipped.",
            p.desc, mods.iter().map(|m| format!("- {m}")).collect::<Vec<_>>().join("\n"), groups.join(" · "));
        let blocks = crate::md::blocks(&md);
        d.loading = false;
        d.author = "Nether Client".into();
        d.desc = md.as_str().into();
        d.blocks = ModelRc::new(VecModel::from(blocks.iter().map(crate::mods::to_block).collect::<Vec<_>>()));
        d.tags = strs(&groups);
        d.loaders = strs(&[title_case(&p.loader)]);
        d.versions = strs(&vers.iter().take(12).cloned().collect::<Vec<_>>());
        d.env = "Client-side".into();
        d.license = "Per mod".into();
        d.mod_loader = title_case(&p.loader).into();
        d.mod_ver = format!("{} mods", p.mods.len()).into();
        d.mod_mc = vers.first().cloned().unwrap_or_default().into();
        d.compat_ok = 1;
        d.compat_text = format!("Creates a new {} instance with {} mods", title_case(&p.loader), p.mods.len()).into();
        d.vers = ModelRc::new(VecModel::from(vers.iter().map(|v| VerOpt { id: v.as_str().into(), label: "".into() }).collect::<Vec<_>>()));
        d.vlabels = strs(&vers.iter().map(|v| format!("Minecraft {v}")).collect::<Vec<_>>());
        d.can_install = !vers.is_empty();
        b.set_detail(d);
        b.set_detail_open(true);
        return;
    }

    b.set_detail(d);
    b.set_detail_open(true);
    let (weak, sh, id) = (ui.as_weak(), sh.clone(), r.id.to_string());
    std::thread::spawn(move || {
        let c = client();
        let det = modrinth::detail(&c, &id, "", None).map(|(d, _)| d);
        let vers = modrinth::all_versions(&c, &id).unwrap_or_default();
        let det = match det {
            Ok(d) => d,
            Err(e) => {
                let msg = format!("Couldn't load details: {e}");
                ui_do(&weak, move |ui| { let b = ui.global::<Backend>(); let mut d = b.get_detail(); d.loading = false; d.desc = msg.into(); b.set_detail(d); });
                return;
            }
        };
        let px = icon_px(&sh, &det.icon_url);
        let rel: Vec<String> = det.mc_versions.iter().filter(|x| !x.contains('-') && !x.contains('w')).rev().cloned().collect();
        let more = if rel.len() > 12 { format!("+{} older versions", rel.len() - 12) } else { String::new() };
        let md_blocks = crate::md::blocks(if det.body.is_empty() { &det.description } else { &det.body });
        ui_do(&weak, move |ui| {
            let b = ui.global::<Backend>();
            let mut d = b.get_detail();
            if d.id != det.id.as_str() { return; }
            d.title = det.title.as_str().into();
            d.summary = det.description.as_str().into();
            d.desc = det.description.as_str().into();
            d.author = det.author.as_str().into();
            d.downloads = count(det.downloads).into();
            d.followers = count(det.followers).into();
            d.tags = strs(&det.categories.iter().filter(|c| !det.loaders.contains(c)).take(4).map(|c| title_case(c)).collect::<Vec<_>>());
            d.loaders = strs(&det.loaders.iter().map(|l| title_case(l)).collect::<Vec<_>>());
            d.versions = strs(&rel.iter().take(12).cloned().collect::<Vec<_>>());
            d.more_versions = more.into();
            d.env = crate::mods::environment(&det.client_side, &det.server_side).into();
            d.license = if det.license.is_empty() { "Unknown".into() } else { det.license.as_str().into() };
            d.published = nice_date(&det.published).into();
            d.updated = nice_date(&det.updated).into();
            d.page_url = det.page_url.as_str().into();
            d.links = ModelRc::new(VecModel::from(det.links.iter().map(|(k, u)| Link { k: k.as_str().into(), url: u.as_str().into() }).collect::<Vec<_>>()));
            if let Some(p) = &px { d.icon = cached_icon(&det.icon_url).unwrap_or_else(|| icon_image(&det.icon_url, p)); d.has_icon = true; }
            d.vers = ModelRc::new(VecModel::from(vers.iter().map(|v| VerOpt { id: v.id.as_str().into(), label: "".into() }).collect::<Vec<_>>()));
            d.vlabels = strs(&vers.iter().map(ver_label).collect::<Vec<_>>());
            d.pick = 0;
            d.can_install = !vers.is_empty();
            d.compat_ok = if vers.is_empty() { 2 } else { 1 };
            set_pick_text(&mut d, vers.first());
            d.blocks = ModelRc::new(VecModel::from(md_blocks.iter().map(crate::mods::to_block).collect::<Vec<_>>()));
            d.gallery = ModelRc::new(VecModel::<slint::Image>::default());
            d.loading = false;
            b.set_detail(d);
            // cache the picked version's info for pick-version
            PACK_VERS.with(|p| *p.borrow_mut() = vers);
            crate::mods::load_detail_images(ui.as_weak(), sh.clone(), det.id.clone(), md_blocks, det.gallery.clone());
        });
    });
}

thread_local! {
    /// Newest Install Packs search (UI thread); older answers are dropped.
    static SEARCH_GEN: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
    /// Versions of the open Modrinth pack (UI thread), for the strip text when another version is picked.
    static PACK_VERS: std::cell::RefCell<Vec<modrinth::VersionInfo>> = Default::default();
}

fn ver_label(v: &modrinth::VersionInfo) -> String {
    let tag = if v.kind == "release" { String::new() } else { format!(" · {}", v.kind.to_uppercase()) };
    format!("{}{tag} · MC {} · {}", v.number, v.game_versions.last().cloned().unwrap_or_default(), nice_date(&v.date))
}

fn set_pick_text(d: &mut Detail, v: Option<&modrinth::VersionInfo>) {
    let Some(v) = v else { d.compat_text = "No downloadable versions".into(); return };
    let mc = v.game_versions.last().cloned().unwrap_or_default();
    let ld = loaders_text(&v.loaders);
    d.mod_ver = format!("v{}", v.number.trim_start_matches('v')).into();
    d.mod_mc = mc.as_str().into();
    d.mod_loader = ld.as_str().into();
    d.mod_size = format!("{} KB", v.size_kb).into();
    d.mod_date = nice_date(&v.date).into();
    d.compat_text = format!("Creates a new instance · Minecraft {mc} · {ld}").into();
}

/// The project page's version picker moved (pack pages only).
pub fn picked(ui: &AppWindow, p: i32) {
    let b = ui.global::<Backend>();
    let mut d = b.get_detail();
    d.pick = p;
    if d.id.starts_with("nether:") {
        if let Some(v) = d.vers.row_data(p.max(0) as usize) { d.mod_mc = v.id.clone(); }
    } else {
        PACK_VERS.with(|vs| set_pick_text(&mut d, vs.borrow().get(p.max(0) as usize)));
    }
    b.set_detail(d);
}

fn add_instance(ui: &AppWindow, sh: &Arc<Shared>, insts: &Rc<VecModel<Inst>>, i: Inst) {
    insts.push(i);
    crate::persist(&sh.paths, &**insts);
    crate::refilter(ui);
    crate::artui::refresh(ui, sh);
}

/// INSTALL on a pack page: build a new instance from the picked version.
pub fn install(ui: &AppWindow, sh: &Arc<Shared>, insts: &Rc<VecModel<Inst>>) {
    let b = ui.global::<Backend>();
    let mut d = b.get_detail();
    let Some(pick) = d.vers.row_data(d.pick.max(0) as usize).map(|v| v.id.to_string()) else { return };
    if d.busy { return; }
    d.busy = true;
    d.prog = 0.0;
    b.set_detail(d.clone());
    let (weak, sh2, id, title) = (ui.as_weak(), sh.clone(), d.id.to_string(), d.title.to_string());
    let w2 = weak.clone();
    let id2 = id.clone();
    let prog: nether_core::Progress = Arc::new(move |m: &str, f: f32| {
        let (m, id) = (m.to_string(), id2.clone());
        ui_do(&w2, move |ui| {
            crate::job(ui, &format!("pack:{id}"), &m, f);
            let b = ui.global::<Backend>();
            let mut d = b.get_detail();
            if d.id == id.as_str() { d.prog = f.clamp(0.0, 1.0); b.set_detail(d); }
        });
    });
    let finish = move |ui: &AppWindow, sh: &Arc<Shared>, id: &str, msg: String| {
        crate::job_end(ui, &format!("pack:{id}"));
        let b = ui.global::<Backend>();
        let mut d = b.get_detail();
        if d.id == id { d.busy = false; b.set_detail(d); }
        show_toast(ui, sh, &msg);
    };

    if let Some(pid) = id.strip_prefix("nether:") {
        let Some(pack) = netherpack::find(&sh.paths, pid) else { return };
        let taken: Vec<String> = insts.iter().map(|i| i.name.to_string()).collect();
        let name = instances::free_name(&sh.paths, &pack.name, &taken);
        if let Err(e) = std::fs::create_dir_all(sh.paths.instance(&name)) { finish(ui, sh, &id, format!("Couldn't create the folder: {e}")); return; }
        let mut i = crate::inst(&name, &pick, "Fabric", 0, 0, 4, "Global", "Never", false);
        i.secs = 0;
        add_instance(ui, sh, insts, i);
        std::thread::spawn(move || {
            let dir = instances::content_dir(&sh2.paths, &name, 0);
            let res = netherpack::install(&client(), &pack, &pick, &dir, &prog);
            ui_do(&weak, move |ui| {
                let msg = match res {
                    Ok((_, miss)) if miss.is_empty() => format!("{name} is ready"),
                    Ok((_, miss)) => format!("{name} is ready (no {pick} build: {})", miss.join(", ")),
                    Err(e) => format!("Pack install failed: {e}"),
                };
                finish(ui, &sh2, &id, msg);
            });
        });
        return;
    }

    std::thread::spawn(move || {
        let res = modpack::install_project(&client(), &sh2.paths, &id, Some(&pick), None, &prog);
        ui_do(&weak, move |ui| match res {
            Ok(data) => {
                let m = ui.global::<Backend>().get_instances();
                if let Some(v) = m.as_any().downcast_ref::<VecModel<Inst>>() {
                    v.push(crate::inst_from(&data));
                    crate::persist(&sh2.paths, v);
                }
                crate::refilter(ui);
                crate::artui::refresh(ui, &sh2);
                finish(ui, &sh2, &id, format!("{} installed as {}", title, data.name));
            }
            Err(e) => finish(ui, &sh2, &id, format!("Modpack install failed: {e}")),
        });
    });
}
