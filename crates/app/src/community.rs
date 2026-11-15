//! Wardrobe COMMUNITY (ui/community.slint): browse, report, delete and upload community capes and cosmetics
//! (nether_core::ugc, files converted by nether_core::cosimport), plus the loaded items the preview (skin.rs) and the
//! in-game wardrobe (skin::export) use. Lists and pictures load off the UI thread; results from an older list are
//! dropped (GEN).
use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};
use std::rc::Rc;
use std::sync::{Arc, Mutex, OnceLock};

use image::RgbaImage;
use nether_core::cosimport::{self, What};
use nether_core::cosmetics::{Kind, Loaded, SLOTS};
use nether_core::download::client;
use nether_core::ugc::{self, Filter, Item, Sort};
use nether_core::Session;
use serde_json::Value;
use slint::{ComponentHandle, Image, Model, ModelRc, Rgba8Pixel, SharedPixelBuffer, VecModel};

use crate::{show_toast, ui_do, AppWindow, Backend, CommItem, Community, Shared};

/// A community cape: 64:32 frames and ms per frame.
pub type CapeFrames = (Rc<[Rc<RgbaImage>]>, u32);

thread_local! {
    static LOADED: RefCell<HashMap<String, Rc<Loaded>>> = RefCell::new(HashMap::new());
    static CAPES: RefCell<HashMap<String, CapeFrames>> = RefCell::new(HashMap::new());
    // card pictures (the grid, and the in-game wardrobe's item list)
    static CARDS: RefCell<HashMap<String, RgbaImage>> = RefCell::new(HashMap::new());
    // the list shown, newest page last
    static ITEMS: RefCell<Vec<Item>> = const { RefCell::new(Vec::new()) };
    // single items being (or already) looked up for the preview / in-game wardrobe
    static ASKED: RefCell<HashSet<String>> = RefCell::new(HashSet::new());
    static GEN: Cell<u32> = const { Cell::new(0) };
    static PAGE_NO: Cell<u32> = const { Cell::new(0) };
    static UI: RefCell<Option<slint::Weak<AppWindow>>> = const { RefCell::new(None) };
}
static SHARED: OnceLock<Arc<Shared>> = OnceLock::new();
/// the upload being prepared: its files (kept to convert again as another type), the result and the safety renders
static UP: Mutex<Option<Prepared>> = Mutex::new(None);
struct Prepared { files: cosimport::Files, out: Option<cosimport::Imported>, renders: Vec<Vec<u8>> }

/// The wardrobe page of a type (1 capes, 2.. cosmetic slots) and back.
fn what_of(page: i32) -> Option<What> {
    if page == 1 { return Some(What::Cape); }
    Kind::ALL.get(usize::try_from(page - 2).ok()?).map(|k| What::Item(*k))
}
fn page_of(w: What) -> i32 { match w { What::Cape => 1, What::Item(k) => k.slot() as i32 + 2 } }
fn type_index(w: What) -> i32 { What::ALL.iter().position(|x| *x == w).unwrap_or(1) as i32 }

fn session(sh: &Shared) -> Option<Session> { sh.session.lock().ok().and_then(|s| s.clone()).filter(|s| s.user_type == "msa") }
fn rgba(img: &RgbaImage) -> Image { Image::from_rgba8(SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(img.as_raw(), img.width(), img.height())) }

fn uses_text(n: u64) -> String {
    if n == 0 { return "not equipped yet".into(); }
    let s = n.to_string();
    let mut out = String::new();
    for (i, c) in s.chars().enumerate() { if i > 0 && (s.len() - i) % 3 == 0 { out.push(','); } out.push(c); }
    format!("{out} equipped")
}

fn status(it: &Item) -> i32 { match it.status.as_str() { "live" => 0, "private" => 1, "pending" => 2, _ => 3 } }

fn row(it: &Item) -> CommItem {
    let card = CARDS.with(|c| c.borrow().get(&it.id).map(rgba));
    let note = match (status(it), it.reason.as_deref()) {
        (0, _) => String::new(),
        (_, Some(r)) => { let mut c = r.chars(); c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default() }
        (2, None) => "Waiting for the safety check".into(),
        _ => String::new(),
    };
    CommItem {
        id: it.id.as_str().into(), name: it.name.as_str().into(), by: if it.mine { "you".into() } else { it.by.as_str().into() }, uses: uses_text(it.uses).into(),
        has_img: card.is_some(), img: card.unwrap_or_default(), mine: it.mine, status: status(it), note: note.into(),
    }
}

fn set_rows(ui: &AppWindow) {
    let rows: Vec<CommItem> = ITEMS.with(|v| v.borrow().iter().map(row).collect());
    ui.global::<Community>().set_items(ModelRc::new(VecModel::from(rows)));
}

/// Refresh one card in place (its picture arrived).
fn update_row(ui: &AppWindow, id: &str) {
    let Some(it) = ITEMS.with(|v| v.borrow().iter().find(|i| i.id == id).cloned()) else { return };
    let m = ui.global::<Community>().get_items();
    if let Some(i) = m.iter().position(|r| r.id == id) { m.set_row_data(i, row(&it)); }
}

// ---------------- pictures (worker threads) ----------------

/// A downloaded, checked item ready for the UI thread.
enum Ready { Cos(Box<Loaded>, RgbaImage), Cape(Vec<RgbaImage>, u32, RgbaImage) }

const CARD_W: u32 = 300;
const CARD_H: u32 = 390;

/// The player from behind wearing a cape (cards and the upload preview).
fn cape_card(skin: &RgbaImage, slim: bool, frame: &RgbaImage) -> RgbaImage {
    use nether_core::preview::{render, Look, View};
    let look = Look { skin, slim, cape: Some(frame), items: [None; SLOTS] };
    let focus: Vec<[f32; 3]> = (0..8).map(|i| [if i & 1 == 0 { -6. } else { 6. }, if i & 2 == 0 { 0. } else { 32.5 }, if i & 4 == 0 { -4.5 } else { 4.5 }]).collect();
    render(&look, View::fit(&look, std::f32::consts::PI + 0.45, 0.1, &focus, CARD_W, CARD_H, 1.25), CARD_W, CARD_H, 1.7, 0)
}

fn prepare(it: &Item, skin: &RgbaImage, slim: bool) -> anyhow::Result<Ready> {
    let c = client();
    match it.what() {
        Some(What::Cape) => {
            let png = ugc::cape_png(&c, it)?;
            let frames = crate::skin::cape_frames(&image::load_from_memory_with_format(&png, image::ImageFormat::Png)?.to_rgba8());
            let first = frames.first().ok_or_else(|| anyhow::anyhow!("bad cape"))?;
            let card = cape_card(skin, slim, first);
            Ok(Ready::Cape(frames, it.frame_ms(), card))
        }
        Some(What::Item(_)) => {
            let l = ugc::load(&c, it)?;
            let card = crate::skin::card(skin, slim, &l);
            Ok(Ready::Cos(Box::new(l), card))
        }
        None => anyhow::bail!("unknown type"),
    }
}

fn store(id: &str, r: Ready) {
    match r {
        Ready::Cos(l, card) => {
            LOADED.with(|m| m.borrow_mut().insert(id.to_string(), Rc::new(*l)));
            CARDS.with(|m| m.borrow_mut().insert(id.to_string(), card));
        }
        Ready::Cape(frames, ms, card) => {
            let f: Rc<[Rc<RgbaImage>]> = frames.into_iter().map(Rc::new).collect();
            CAPES.with(|m| m.borrow_mut().insert(id.to_string(), (f, ms)));
            CARDS.with(|m| m.borrow_mut().insert(id.to_string(), card));
        }
    }
}

/// Pictures for these items, 4 at a time; each card updates as soon as it is ready.
fn pictures(ui: &AppWindow, items: Vec<Item>, gen: u32) {
    if items.is_empty() { return; }
    let (skin, slim) = crate::skin::account_skin();
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let queue = Mutex::new(items.into_iter());
        std::thread::scope(|s| for _ in 0..4 {
            s.spawn(|| loop {
                let Some(it) = queue.lock().ok().and_then(|mut q| q.next()) else { break };
                let r = prepare(&it, &skin, slim);
                let w = weak.clone();
                ui_do(&w, move |ui| {
                    match r {
                        Ok(r) => { store(&it.id, r); crate::skin::community_ready(); }
                        Err(e) => { eprintln!("community item {}: {e:#}", it.id); }
                    }
                    if GEN.get() == gen { update_row(ui, &it.id); }
                });
            });
        });
    });
}

// ---------------- what the preview and the game look up ----------------

/// A community cosmetic ready to draw, or None (then it is fetched in the background; the preview redraws when it arrives).
pub fn loaded(id: &str) -> Option<Rc<Loaded>> {
    if !ugc::is_ugc(id) { return None; }
    let l = LOADED.with(|m| m.borrow().get(id).cloned());
    if l.is_none() { fetch(id); }
    l
}

/// A community cape's frames, or None (fetched in the background like `loaded`).
pub fn cape(id: &str) -> Option<CapeFrames> {
    if !ugc::is_ugc(id) { return None; }
    let c = CAPES.with(|m| m.borrow().get(id).cloned());
    if c.is_none() { fetch(id); }
    c
}

/// Look one item up (public, or your own) and load it, once per session.
fn fetch(id: &str) {
    if !ASKED.with(|a| a.borrow_mut().insert(id.to_string())) { return; }
    let (Some(ui), Some(sh)) = (UI.with(|u| u.borrow().as_ref().and_then(|w| w.upgrade())), SHARED.get().cloned()) else { return };
    let (skin, slim) = crate::skin::account_skin();
    let sess = session(&sh);
    let (weak, id) = (ui.as_weak(), id.to_string());
    std::thread::spawn(move || {
        let c = client();
        let it = ugc::item(&c, sess.as_ref(), &id).ok().flatten().or_else(|| ugc::known(&id));
        let Some(it) = it else { return };
        let r = prepare(&it, &skin, slim);
        ui_do(&weak, move |ui| {
            let Ok(r) = r else { return };
            store(&it.id, r);
            crate::skin::community_ready();
            // your own / picked items show in the in-game wardrobe once they have a picture
            if ugc::known(&it.id).is_some() { crate::skin::export(ui, &sh); }
        });
    });
}

/// In-game wardrobe (skin::export): your uploads, likes and picks go into state.json next to the Nether items.
pub fn export(dir: &std::path::Path, st: &mut Value, wanted: &mut Vec<String>) {
    for it in ugc::known_all() {
        if !ugc::is_ugc(&it.id) { continue; }
        match it.what() {
            Some(What::Cape) => {
                let Some((frames, _)) = cape(&it.id) else { continue };
                let f = format!("n_{}.png", it.id);
                if frames[0].save(dir.join(&f)).is_ok() {
                    if let Some(a) = st["nether"].as_array_mut() { a.push(serde_json::json!({ "id": it.id, "name": it.name, "png": f })); }
                    wanted.push(f);
                }
            }
            Some(What::Item(k)) => {
                if loaded(&it.id).is_none() { continue; }
                let Some(card) = CARDS.with(|m| m.borrow().get(&it.id).cloned()) else { continue };
                let f = format!("c_{}.png", it.id);
                if card.save(dir.join(&f)).is_ok() {
                    if let Some(a) = st["items"].as_array_mut() { a.push(serde_json::json!({ "id": it.id, "name": it.name, "type": k.as_str(), "png": f })); }
                    wanted.push(f);
                }
            }
            None => {}
        }
    }
}

// ---------------- the list ----------------

fn load(ui: &AppWindow, sh: &Arc<Shared>, page: i32, more: bool) {
    let c = ui.global::<Community>();
    let Some(what) = what_of(page) else { return };
    let filter = if c.get_filter() == 1 { Filter::Mine } else { Filter::All };
    let sort = if c.get_sort() == 1 { Sort::New } else { Sort::Top };
    let q = c.get_query().to_string();
    let sess = session(sh);
    if more {
        if c.get_busy_more() || !c.get_more() || c.get_page() != page { return; }
        c.set_busy_more(true);
    } else {
        GEN.set(GEN.get() + 1);
        c.set_page(page);
        c.set_more(false);
        c.set_busy_more(false);
        ITEMS.with(|v| v.borrow_mut().clear());
        c.set_items(ModelRc::default());
        if filter != Filter::All && sess.is_none() {
            c.set_state(3);
            c.set_message("Sign in with your Microsoft account to see your uploads and likes.".into());
            return;
        }
        c.set_state(1);
    }
    let (gen, pageno) = (GEN.get(), if more { PAGE_NO.get() + 1 } else { 0 });
    let weak = ui.as_weak();
    let _ = sh;
    std::thread::spawn(move || {
        let r = ugc::browse(&client(), sess.as_ref(), what, filter, sort, &q, pageno);
        ui_do(&weak, move |ui| {
            if GEN.get() != gen { return; }
            let c = ui.global::<Community>();
            c.set_busy_more(false);
            match r {
                Ok(p) => {
                    PAGE_NO.set(pageno);
                    let need: Vec<Item> = p.items.iter().filter(|i| i.status != "removed" && CARDS.with(|m| !m.borrow().contains_key(&i.id))).cloned().collect();
                    ITEMS.with(|v| { let mut v = v.borrow_mut(); for it in p.items { if !v.iter().any(|x| x.id == it.id) { v.push(it); } } });
                    set_rows(ui);
                    c.set_more(p.more);
                    c.set_state(2);
                    pictures(ui, need, gen);
                }
                Err(e) if !more => { c.set_state(3); c.set_message(format!("Couldn't load community uploads: {e:#}").into()); }
                Err(e) => { if let Some(sh) = SHARED.get() { show_toast(ui, sh, &format!("Couldn't load more: {e:#}")); } }
            }
        });
    });
}

fn find(id: &str) -> Option<Item> { ITEMS.with(|v| v.borrow().iter().find(|i| i.id == id).cloned()) }

/// Card clicked: select it like a Nether item (APPLY sends it).
fn pick(ui: &AppWindow, sh: &Arc<Shared>, id: &str) {
    let page = ui.global::<Community>().get_page();
    if let Some(it) = find(id) { ugc::remember(std::slice::from_ref(&it)); }
    match what_of(page) {
        Some(What::Cape) => crate::skin::wear_ncape(ui, sh, id),
        Some(What::Item(k)) => crate::skin::wear_cosmetic(ui, sh, k.slot() as i32, id),
        None => {}
    }
}

fn remove(ui: &AppWindow, sh: &Arc<Shared>, id: &str) {
    let Some(sess) = session(sh) else { return };
    let (weak, sh, id) = (ui.as_weak(), sh.clone(), id.to_string());
    std::thread::spawn(move || {
        let r = ugc::delete(&client(), &sess, &id);
        ui_do(&weak, move |ui| match r {
            Ok(()) => {
                // picked but not applied: drop the pick
                let b = ui.global::<Backend>();
                if b.get_ncape_sel() == id.as_str() { crate::skin::wear_ncape(ui, &sh, &id); }
                for k in Kind::ALL { if b.get_cos_sel().row_data(k.slot()).is_some_and(|s| s == id.as_str()) { crate::skin::wear_cosmetic(ui, &sh, k.slot() as i32, &id); } }
                ITEMS.with(|v| v.borrow_mut().retain(|i| i.id != id));
                set_rows(ui);
                show_toast(ui, &sh, "Upload deleted");
            }
            Err(e) => show_toast(ui, &sh, &format!("Couldn't delete it: {e:#}")),
        });
    });
}

fn report(ui: &AppWindow, sh: &Arc<Shared>, id: &str, reason: &str) {
    let Some(sess) = session(sh) else { show_toast(ui, sh, "Sign in with Microsoft to report uploads"); return };
    let (weak, sh, id, reason) = (ui.as_weak(), sh.clone(), id.to_string(), reason.to_string());
    std::thread::spawn(move || {
        let r = ugc::report(&client(), &sess, &id, &reason);
        ui_do(&weak, move |ui| show_toast(ui, &sh, &match r { Ok(()) => "Reported. Thanks for keeping it clean".to_string(), Err(e) => format!("Couldn't report it: {e:#}") }));
    });
}

// ---------------- upload ----------------

/// Convert the files as `what` and render the preview (+ the renders the safety check also looks at).
fn convert(files: &cosimport::Files, what: What, skin: &RgbaImage, slim: bool) -> anyhow::Result<(cosimport::Imported, RgbaImage, Vec<Vec<u8>>)> {
    use nether_core::preview::{render, Look, View};
    let out = cosimport::import(files, what)?;
    let png_of = |img: &RgbaImage| -> anyhow::Result<Vec<u8>> {
        let mut b = std::io::Cursor::new(Vec::new());
        img.write_to(&mut b, image::ImageFormat::Png)?;
        Ok(b.into_inner())
    };
    let small = |img: &RgbaImage| image::imageops::resize(img, 240, 312, image::imageops::FilterType::Triangle);
    match what {
        What::Cape => {
            let frames = crate::skin::cape_frames(&image::load_from_memory_with_format(&out.png, image::ImageFormat::Png)?.to_rgba8());
            let first = frames.first().ok_or_else(|| anyhow::anyhow!("not a cape"))?;
            let card = cape_card(skin, slim, first);
            let renders = vec![png_of(&small(&card))?];
            Ok((out, card, renders))
        }
        What::Item(kind) => {
            let l = Loaded::from_bytes("upload", kind, out.model.as_deref().unwrap_or_default(), &out.png, out.frame_ms.unwrap_or(100))?;
            let card = crate::skin::card(skin, slim, &l);
            // the safety check also sees it from the front and the back, whole
            let mut items: [Option<&Loaded>; SLOTS] = [None; SLOTS];
            items[kind.slot()] = Some(&l);
            let look = Look { skin, slim, cape: None, items };
            let mut renders = vec![png_of(&small(&card))?];
            for yaw in [0.25f32, std::f32::consts::PI + 0.25] {
                let v = View::fit_turning(&look, yaw, 240, 312, 1.6);
                renders.push(png_of(&render(&look, v, 240, 312, 1.0, 0))?);
            }
            Ok((out, card, renders))
        }
    }
}

/// Fill the dialog with a conversion (or its error).
fn show_converted(ui: &AppWindow, what: What, r: anyhow::Result<(cosimport::Imported, RgbaImage, Vec<Vec<u8>>)>, keep_name: bool) {
    let c = ui.global::<Community>();
    c.set_up_type(type_index(what));
    c.set_up_state(2);
    match r {
        Ok((out, card, renders)) => {
            if !keep_name || c.get_up_name().trim().is_empty() { c.set_up_name(out.name.as_str().into()); }
            c.set_up_img(rgba(&card));
            c.set_up_has_img(true);
            c.set_up_summary(out.summary.as_str().into());
            c.set_up_warn(out.warnings.iter().take(3).cloned().collect::<Vec<_>>().join(" · ").into());
            c.set_up_error("".into());
            c.set_up_ready(true);
            if let Ok(mut u) = UP.lock() { if let Some(p) = u.as_mut() { p.out = Some(out); p.renders = renders; } }
        }
        Err(e) => {
            c.set_up_has_img(false);
            c.set_up_summary("".into());
            c.set_up_warn("".into());
            c.set_up_error(format!("{e:#}").into());
            c.set_up_ready(false);
            if let Ok(mut u) = UP.lock() { if let Some(p) = u.as_mut() { p.out = None; } }
        }
    }
}

fn upload_pick(ui: &AppWindow, sh: &Arc<Shared>) {
    let c = ui.global::<Community>();
    if matches!(c.get_up_state(), 1 | 3) { return; }
    if session(sh).is_none() { show_toast(ui, sh, "Sign in with Microsoft to upload"); return; }
    let page = ui.get_ward_page();
    let (skin, slim) = crate::skin::account_skin();
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let Some(paths) = rfd::FileDialog::new().set_title("Choose what to upload (model + texture, an image, or a zip)")
            .add_filter("Cosmetic files", &["zip", "bbmodel", "json", "obj", "png", "webp", "gif", "avif"]).pick_files() else { return };
        ui_do(&weak, |ui| {
            let c = ui.global::<Community>();
            c.set_up_state(1);
            c.set_up_has_img(false);
            c.set_up_error("".into());
            c.set_up_warn("".into());
            c.set_up_summary("".into());
            c.set_up_ready(false);
            c.set_up_agree(false);
            c.set_up_name("".into());
        });
        let files = match cosimport::Files::from_paths(&paths) {
            Ok(f) => f,
            Err(e) => { ui_do(&weak, move |ui| { let c = ui.global::<Community>(); c.set_up_state(2); c.set_up_ready(false); c.set_up_error(format!("{e:#}").into()); }); return; }
        };
        let what = cosimport::guess(&files).or_else(|| what_of(page)).unwrap_or(What::Item(Kind::Hat));
        let r = convert(&files, what, &skin, slim);
        if let Ok(mut u) = UP.lock() { *u = Some(Prepared { files, out: None, renders: vec![] }); }
        ui_do(&weak, move |ui| show_converted(ui, what, r, false));
    });
}

fn upload_type(ui: &AppWindow, i: i32) {
    let Some(what) = What::ALL.get(i.max(0) as usize).copied() else { return };
    let c = ui.global::<Community>();
    if c.get_up_state() != 2 { return; }
    c.set_up_state(1);
    c.set_up_type(i);
    let (skin, slim) = crate::skin::account_skin();
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let r = match UP.lock() { Ok(u) => match u.as_ref() { Some(p) => convert(&p.files, what, &skin, slim), None => return }, Err(_) => return };
        ui_do(&weak, move |ui| show_converted(ui, what, r, true));
    });
}

fn upload_send(ui: &AppWindow, sh: &Arc<Shared>) {
    let c = ui.global::<Community>();
    if c.get_up_state() != 2 || !c.get_up_ready() || !c.get_up_agree() { return; }
    let name: String = c.get_up_name().trim().chars().take(32).collect();
    if name.chars().count() < 2 { c.set_up_error("Give it a name (2 to 32 characters).".into()); return; }
    let Some(sess) = session(sh) else { c.set_up_error("Sign in with your Microsoft account first.".into()); return };
    c.set_up_state(3);
    c.set_up_error("".into());
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let r = {
            let u = UP.lock();
            match u.as_ref().ok().and_then(|u| u.as_ref()).and_then(|p| p.out.as_ref().map(|o| (o.clone(), p.renders.clone()))) {
                Some((out, renders)) => ugc::upload(&client(), &sess, &ugc::Upload { what: out.what, name: &name, frame_ms: out.frame_ms, model: out.model.as_deref(), png: &out.png, previews: &renders }).map(|r| (r, out)),
                None => Err(anyhow::anyhow!("convert the files again")),
            }
        };
        ui_do(&weak, move |ui| {
            let c = ui.global::<Community>();
            match r {
                Ok(((it, dup), out)) => {
                    c.set_up_state(0);
                    if let Ok(mut u) = UP.lock() { *u = None; }
                    // what we just converted is what the service has: no download needed to show it
                    let ready = match out.what {
                        What::Cape => image::load_from_memory_with_format(&out.png, image::ImageFormat::Png).ok().map(|i| crate::skin::cape_frames(&i.to_rgba8())).filter(|f| !f.is_empty())
                            .map(|f| { let (skin, slim) = crate::skin::account_skin(); let card = cape_card(&skin, slim, &f[0]); Ready::Cape(f, out.frame_ms.unwrap_or(100), card) }),
                        What::Item(k) => out.model.as_deref().and_then(|m| Loaded::from_bytes(&it.id, k, m, &out.png, out.frame_ms.unwrap_or(100)).ok())
                            .map(|l| { let (skin, slim) = crate::skin::account_skin(); let card = crate::skin::card(&skin, slim, &l); Ready::Cos(Box::new(l), card) }),
                    };
                    if let Some(r) = ready { store(&it.id, r); }
                    let msg = if dup && !it.mine { format!("That's already up: “{}” by {}, selected for you", it.name, it.by) }
                        else { match it.status.as_str() {
                            "live" => format!("Uploaded. Everyone can see “{}” now: select it and APPLY to wear it", it.name),
                            "pending" => "Uploaded. It goes public once the safety check has run".to_string(),
                            _ => format!("Uploaded. {}", it.reason.as_deref().map(|r| { let mut c = r.chars(); c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default() }).unwrap_or_else(|| "Only you can see it".into())),
                        } };
                    show_toast(ui, &sh, &msg);
                    // show it: its page, COMMUNITY, MINE (ALL when it's someone else's), picked
                    let page = page_of(out.what);
                    ui.set_ward_page(page);
                    c.set_source(1);
                    c.set_filter(if dup && !it.mine { 0 } else { 1 });
                    c.set_query("".into());
                    load(ui, &sh, page, false);
                    let b = ui.global::<Backend>();
                    let picked = if page == 1 { b.get_ncape_sel() == it.id.as_str() } else { b.get_cos_sel().row_data((page - 2) as usize).is_some_and(|s| s == it.id.as_str()) };
                    if !picked { ITEMS.with(|v| v.borrow_mut().push(it.clone())); pick(ui, &sh, &it.id); }
                    crate::skin::export(ui, &sh);
                }
                Err(e) => { c.set_up_state(2); c.set_up_error(format!("{e:#}").into()); }
            }
        });
    });
}

pub fn init(ui: &AppWindow, sh: Arc<Shared>) {
    let _ = SHARED.set(sh.clone());
    UI.with(|u| *u.borrow_mut() = Some(ui.as_weak()));
    let c = ui.global::<Community>();
    macro_rules! on {
        ($set:ident, |$ui:ident, $sh:ident $(, $a:ident: $t:ty)*| $body:expr) => {{
            let (weak, sh) = (ui.as_weak(), sh.clone());
            c.$set(move |$($a: $t),*| { let Some($ui) = weak.upgrade() else { return }; let $sh = &sh; let _ = $sh; $body; });
        }};
    }
    on!(on_load, |ui, sh, page: i32| load(&ui, sh, page, false));
    on!(on_load_more, |ui, sh| { let p = ui.global::<Community>().get_page(); load(&ui, sh, p, true) });
    on!(on_pick, |ui, sh, id: slint::SharedString| pick(&ui, sh, &id));
    on!(on_remove, |ui, sh, id: slint::SharedString| remove(&ui, sh, &id));
    on!(on_report, |ui, sh, id: slint::SharedString, reason: slint::SharedString| report(&ui, sh, &id, &reason));
    on!(on_upload_pick, |ui, sh| upload_pick(&ui, sh));
    on!(on_upload_type, |ui, sh, i: i32| upload_type(&ui, i));
    on!(on_upload_send, |ui, sh| upload_send(&ui, sh));
    on!(on_upload_close, |ui, sh| { if ui.global::<Community>().get_up_state() != 3 { ui.global::<Community>().set_up_state(0); if let Ok(mut u) = UP.lock() { *u = None; } } });
}

/// Dev captures (`NC_SNAP_STATE=comm | comm-mine | comm-up | comm-report | comm-delete`, page from NC_SNAP_WPAGE): sample
/// community cards made from the catalog files on disk, and the dialogs (the upload one converts a cosmetics_raw zip).
pub fn snap(ui: &AppWindow, state: &str) {
    if !state.starts_with("comm") { return; }
    let (weak, state) = (ui.as_weak(), state.to_string());
    slint::Timer::single_shot(std::time::Duration::from_millis(1200), move || {
        let Some(ui) = weak.upgrade() else { return };
        let page = ui.get_ward_page().max(1);
        ui.set_ward_page(page);
        let c = ui.global::<Community>();
        GEN.set(GEN.get() + 1);
        c.set_page(page);
        c.set_source(1);
        c.set_filter(if state == "comm-mine" { 1 } else { 0 });
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        let content = root.join("backend/content");
        let (skin, slim) = crate::skin::account_skin();
        let by = ["DerKyzo", "1onur", "Vreez_", "ToyaQX", "WinniePat", "Zeltix", "moon_dust", "Kaiju"];
        let mut items = vec![];
        let sha = "0".repeat(64);
        let mk = |i: usize, id: &str, kind: &str, name: &str| Item { id: id.into(), kind: kind.into(), name: name.into(), by: by[i % by.len()].into(), model: Some(sha.clone()), png: sha.clone(), ms: 100,
            uses: [79675u64, 47988, 36798, 30037, 29609, 1204, 312, 9][i % 8], created: 0, status: "live".into(), reason: None, mine: false };
        if page == 1 {
            let idx: Vec<Value> = std::fs::read(content.join("capes/index.json")).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
            for (i, e) in idx.iter().enumerate() {
                let Some(id) = e["id"].as_str() else { continue };
                let Some(img) = std::fs::read(content.join(format!("capes/{id}.png"))).ok().and_then(|b| image::load_from_memory(&b).ok()) else { continue };
                let frames = crate::skin::cape_frames(&img.to_rgba8());
                let Some(f) = frames.first() else { continue };
                let uid = format!("u-snapcape{i:04}");
                CARDS.with(|m| m.borrow_mut().insert(uid.clone(), cape_card(&skin, slim, f)));
                let mut it = mk(i, &uid, "cape", e["name"].as_str().unwrap_or(id));
                it.model = None;
                items.push(it);
            }
        } else if let Some(What::Item(kind)) = what_of(page) {
            let idx: Vec<Value> = std::fs::read(content.join("cosmetics/index.json")).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
            for (i, e) in idx.iter().filter(|e| e["type"].as_str() == Some(kind.as_str())).enumerate() {
                let Some(id) = e["id"].as_str() else { continue };
                let (Ok(js), Ok(png)) = (std::fs::read(content.join(format!("cosmetics/{id}.json"))), std::fs::read(content.join(format!("cosmetics/{id}.png")))) else { continue };
                let uid = format!("u-snapitem{i:04}");
                let Ok(l) = Loaded::from_bytes(&uid, kind, &js, &png, 100) else { continue };
                CARDS.with(|m| m.borrow_mut().insert(uid.clone(), crate::skin::card(&skin, slim, &l)));
                LOADED.with(|m| m.borrow_mut().insert(uid.clone(), Rc::new(l)));
                items.push(mk(i, &uid, kind.as_str(), e["name"].as_str().unwrap_or(id)));
            }
        }
        if state == "comm-mine" {
            for (i, it) in items.iter_mut().enumerate() {
                it.mine = true;
                (it.status, it.reason) = match i % 4 { 1 => ("private".into(), Some("only you can see it: it looks like nudity or sexual content".into())), 2 => ("pending".into(), None), _ => ("live".into(), None) };
            }
        }
        items.truncate(11);
        ITEMS.with(|v| *v.borrow_mut() = items.clone());
        set_rows(&ui);
        c.set_more(items.len() >= 11);
        c.set_state(2);
        if let Some(first) = items.get(1) { pick(&ui, SHARED.get().unwrap(), &first.id); }
        match state.as_str() {
            "comm-report" => { c.set_report_id(items[0].id.as_str().into()); c.set_report_name(items[0].name.as_str().into()); }
            "comm-delete" => { c.set_delete_id(items[0].id.as_str().into()); c.set_delete_name(items[0].name.as_str().into()); }
            "comm-up" => {
                let src = std::env::var("NC_SNAP_UPLOAD").map(std::path::PathBuf::from).unwrap_or_else(|_| root.join("cosmetics_raw/pets/black_dragon_2025_.zip"));
                if let Ok(files) = cosimport::Files::from_paths(&[src]) {
                    let what = cosimport::guess(&files).unwrap_or(What::Item(Kind::Hat));
                    let r = convert(&files, what, &skin, slim);
                    if let Ok(mut u) = UP.lock() { *u = Some(Prepared { files, out: None, renders: vec![] }); }
                    show_converted(&ui, what, r, false);
                    c.set_up_agree(true);
                }
            }
            _ => {}
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pages_and_uses() {
        assert_eq!(what_of(1), Some(What::Cape));
        assert_eq!(what_of(2), Some(What::Item(Kind::Hat)));
        assert_eq!(what_of(8), Some(What::Item(Kind::Pet)));
        assert_eq!(what_of(0), None);
        assert_eq!(what_of(9), None);
        for p in 1..=8 { assert_eq!(page_of(what_of(p).unwrap()), p); }
        assert_eq!(uses_text(0), "not equipped yet");
        assert_eq!(uses_text(1), "1 equipped");
        assert_eq!(uses_text(79675), "79,675 equipped");
        assert_eq!(uses_text(1000000), "1,000,000 equipped");
    }
}
