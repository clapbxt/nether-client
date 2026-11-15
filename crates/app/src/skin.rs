//! Player preview: a small software renderer for the Minecraft player model (skin + outer layer + cape),
//! plus loading the account's skin/capes and equipping capes. Rendered on the UI thread by a timer.
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;

use image::RgbaImage;
use nether_core::download::client;
use serde_json::Value;
use slint::{ComponentHandle, Image, Model, ModelRc, Rgba8Pixel, SharedPixelBuffer, VecModel};

use nether_core::cosmetics::{Kind, Loaded, SLOTS};

use crate::{show_toast, ui_do, AppWindow, Backend, Cape, CosCard, Shared, SkinLib};

/// Default skin (Steve) when signed out.
const STEVE: &str = "https://textures.minecraft.net/texture/31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb";
/// Output size in pixels (2x the largest on-screen preview, so Slint's downscale smooths the edges).
pub const W: u32 = 600;
pub const H: u32 = 780;

pub struct Look {
    pub skin: RgbaImage,
    pub slim: bool,
    pub cape: Option<Rc<RgbaImage>>,
}

/// A cape texture in the 64:32 layout; Nether capes may have animation frames (`img` = the first).
#[derive(Clone)]
struct CapeTex { id: String, img: Rc<RgbaImage>, frames: Rc<[Rc<RgbaImage>]>, ms: u32 }

thread_local! {
    static LOOK: RefCell<Option<Look>> = const { RefCell::new(None) };
    static CAPES: RefCell<Vec<CapeTex>> = const { RefCell::new(Vec::new()) };
    // Nether cape textures by id, and the one worn (shown on the preview over the account cape)
    static NCAPES: RefCell<Vec<CapeTex>> = const { RefCell::new(Vec::new()) };
    static NWORN: RefCell<Option<CapeTex>> = const { RefCell::new(None) };
    // the previewed cape's animation: frames, ms per frame, frame shown
    static ANIM: RefCell<Option<(Rc<[Rc<RgbaImage>]>, u32, usize)>> = const { RefCell::new(None) };
    static NUUID: RefCell<Option<String>> = const { RefCell::new(None) };
    static YAW: RefCell<f32> = const { RefCell::new(0.5) };
    static DIRTY: RefCell<bool> = const { RefCell::new(true) };
    // the account's own skin, and whether a library skin is being previewed instead
    static ACCOUNT: RefCell<Option<(RgbaImage, bool)>> = const { RefCell::new(None) };
    static PREVIEW: RefCell<bool> = const { RefCell::new(false) };
    // Nether cosmetics: loaded (model + texture), worn by slot, and the card hovered (previewed over the worn one)
    static NCOS: RefCell<Vec<Rc<Loaded>>> = const { RefCell::new(Vec::new()) };
    static COS_META: RefCell<Vec<nether_core::backend::Cosmetic>> = const { RefCell::new(Vec::new()) };
    static COS_CARDS: RefCell<Vec<(String, RgbaImage)>> = const { RefCell::new(Vec::new()) };
    static COS_WORN: RefCell<[String; SLOTS]> = RefCell::new(Default::default());
    static COS_HOVER: RefCell<Option<Rc<Loaded>>> = const { RefCell::new(None) };
    // the look being put together (cards clicked, not applied yet): what the preview shows and APPLY sends
    static SEL: RefCell<Sel> = RefCell::new(Sel::default());
    // answer to the game's last request (seq, ok, message), published in state.json
    static ANSWER: RefCell<Option<(u64, bool, String)>> = const { RefCell::new(None) };
}

/// A whole look: Nether cape id, account cape (index into Backend.capes, 0 = none), cosmetic ids by slot ("" = none).
#[derive(Clone, Default, PartialEq, Eq)]
pub struct Sel { ncape: String, cape: i32, cos: [String; SLOTS] }

/// What the account wears now (applied at the services).
fn applied(ui: &AppWindow) -> Sel {
    let b = ui.global::<Backend>();
    Sel { ncape: b.get_ncape_worn().to_string(), cape: b.get_cape_idx(), cos: COS_WORN.with(|w| w.borrow().clone()) }
}

/// Make `s` the look being edited: cards, APPLY state and the preview follow it.
fn push_sel(ui: &AppWindow, s: Sel) {
    let b = ui.global::<Backend>();
    b.set_ncape_sel(s.ncape.as_str().into());
    b.set_cape_sel(s.cape);
    b.set_cos_sel(ModelRc::new(VecModel::from(s.cos.iter().map(|x| slint::SharedString::from(x.as_str())).collect::<Vec<_>>())));
    b.set_look_dirty(s != applied(ui));
    SEL.with(|v| *v.borrow_mut() = s);
    set_preview_cape();
}

/// Drop unapplied picks: back to what the account wears.
fn sync_sel(ui: &AppWindow) { push_sel(ui, applied(ui)); }

fn edit_sel(ui: &AppWindow, f: impl FnOnce(&mut Sel)) {
    let mut s = SEL.with(|v| v.borrow().clone());
    f(&mut s);
    push_sel(ui, s);
}

// ---------------- loading ----------------

fn fetch_png(sh: &Shared, url: &str) -> Option<RgbaImage> { fetch_png_as(sh, url, url.rsplit('/').next().unwrap_or("x")) }

/// `fetch_png` cached under `cache/textures/<name>.png`.
fn fetch_png_as(sh: &Shared, url: &str, name: &str) -> Option<RgbaImage> {
    let dir = sh.paths.root.join("cache").join("textures");
    let file = dir.join(format!("{name}.png"));
    let bytes = match std::fs::read(&file) {
        Ok(b) => b,
        Err(_) => {
            let b = client().get(url.replace("http://textures.minecraft.net", "https://textures.minecraft.net")).send().ok()?.error_for_status().ok()?.bytes().ok()?.to_vec();
            let _ = std::fs::create_dir_all(&dir);
            let _ = std::fs::write(&file, &b);
            b
        }
    };
    Some(image::load_from_memory(&bytes).ok()?.to_rgba8())
}

/// 64x32 (pre-1.8) skins -> 64x64 with mirrored left limbs, the way the game converts them.
pub fn upgrade_legacy(src: &RgbaImage) -> RgbaImage {
    if src.height() >= src.width() { return src.clone(); }
    let mut out = RgbaImage::new(64, 64);
    image::imageops::overlay(&mut out, src, 0, 0);
    // old skins often fill the hat layer with solid colour; the game hides a fully opaque hat layer
    if (32..64).all(|x| (0..16).all(|y| out.get_pixel(x, y)[3] == 255)) {
        for x in 32..64 { for y in 0..16 { out.get_pixel_mut(x, y)[3] = 0; } }
    }
    let mut copy = |sx: u32, sy: u32, dx: u32, dy: u32, w: u32, h: u32| {
        for y in 0..h { for x in 0..w { out.put_pixel(dx + (w - 1 - x), dy + y, *src.get_pixel(sx + x, sy + y)); } }
    };
    for (sx, sy, dx, dy, w, h) in [
        (4, 16, 20, 48, 4, 4), (8, 16, 24, 48, 4, 4), (0, 20, 24, 52, 4, 12), (4, 20, 20, 52, 4, 12), (8, 20, 16, 52, 4, 12), (12, 20, 28, 52, 4, 12),
        (44, 16, 36, 48, 4, 4), (48, 16, 40, 48, 4, 4), (40, 20, 40, 52, 4, 12), (44, 20, 36, 52, 4, 12), (48, 20, 32, 52, 4, 12), (52, 20, 44, 52, 4, 12),
    ] { copy(sx, sy, dx, dy, w, h); }
    out
}

/// A Nether cape PNG as 64:32 frames (stacked frames = animation); 22:17 cape-only art is placed into the 64:32 layout.
fn cape_frames(img: &RgbaImage) -> Vec<RgbaImage> {
    let Some(l) = nether_core::backend::CapeLayout::of(img.width(), img.height()) else { return vec![] };
    let (s, w) = (l.scale, img.width());
    let fh = if l.full { 32 * s } else { 17 * s };
    (0..l.frames).map(|i| {
        let f = image::imageops::crop_imm(img, 0, i * fh, w, fh).to_image();
        if l.full { return f; }
        let mut out = RgbaImage::new(64 * s, 32 * s);
        image::imageops::overlay(&mut out, &f, 0, 0);
        out
    }).collect()
}

/// The outside face of a cape (for cards and the Home preview).
fn cape_front(img: &RgbaImage) -> Image {
    let s = (img.width() / 64).max(1);
    let crop = image::imageops::crop_imm(img, s, s, 10 * s, 16 * s).to_image();
    Image::from_rgba8(SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(crop.as_raw(), crop.width(), crop.height()))
}

/// Load the skin + capes of a signed-in profile (or Steve with no capes when `profile` is None).
fn look_file(sh: &Shared) -> std::path::PathBuf { sh.paths.root.join("cache").join("look.json") }

/// Startup: show the last signed-in look straight from the cache (textures are cached too) while the
/// session is restored; falls back to Steve.
pub fn load_cached(weak: slint::Weak<AppWindow>, sh: Arc<Shared>) {
    let v: Value = std::fs::read(look_file(&sh)).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or(Value::Null);
    let arr = |k: &str| v[k].as_array().cloned().unwrap_or_default();
    load(weak, sh, arr("skins"), arr("capes"));
}

/// Face + hat layer of a skin as a small image (account chip).
fn head(skin: &RgbaImage) -> Image {
    let mut face = image::imageops::crop_imm(skin, 8, 8, 8, 8).to_image();
    let hat = image::imageops::crop_imm(skin, 40, 8, 8, 8).to_image();
    image::imageops::overlay(&mut face, &hat, 0, 0);
    let big = image::imageops::resize(&face, 32, 32, image::imageops::FilterType::Nearest);
    Image::from_rgba8(SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(big.as_raw(), 32, 32))
}

static SHARED: std::sync::OnceLock<Arc<Shared>> = std::sync::OnceLock::new();

/// Delete the player's entry at the Nether cape service (Nether capes turned off). `mark` records the request so it
/// is retried at the next sign-in when there is no session or no network now.
pub fn forget_nether(paths: &nether_core::paths::Paths, mark: bool) {
    let f = paths.root.join("nether-forget.pending");
    if mark { let _ = std::fs::write(&f, b"1"); }
    if !f.exists() { return; }
    let Some(sh) = SHARED.get().cloned() else { return };
    std::thread::spawn(move || {
        if crate::prefs::load(&sh.paths).capes { let _ = std::fs::remove_file(&f); return; } // switched back on meanwhile
        let Some(s) = sh.session.lock().ok().and_then(|s| s.clone()).filter(|s| s.user_type == "msa") else { return };
        match nether_core::backend::forget_me(&client(), &s) {
            Ok(()) => { let _ = std::fs::remove_file(&f); crate::applog::line("cape service: your Nether entry was deleted"); }
            Err(e) => crate::applog::line(&format!("cape service: couldn't delete your Nether entry yet ({e:#}); trying again at the next sign-in")),
        }
    });
}

pub fn load(weak: slint::Weak<AppWindow>, sh: Arc<Shared>, skins: Vec<Value>, capes: Vec<Value>) {
    let _ = SHARED.set(sh.clone());
    forget_nether(&sh.paths, false);
    if !skins.is_empty() {
        let _ = std::fs::create_dir_all(sh.paths.root.join("cache"));
        let _ = std::fs::write(look_file(&sh), serde_json::to_vec(&serde_json::json!({"skins": skins, "capes": capes})).unwrap_or_default());
    }
    std::thread::spawn(move || {
        let active = skins.iter().find(|s| s["state"] == "ACTIVE").or_else(|| skins.first());
        let url = active.and_then(|s| s["url"].as_str()).unwrap_or(STEVE).to_string();
        let slim = active.and_then(|s| s["variant"].as_str()).map(|v| v.eq_ignore_ascii_case("slim")).unwrap_or(false);
        let skin = fetch_png(&sh, &url).or_else(|| fetch_png(&sh, STEVE)).map(|s| upgrade_legacy(&s));
        // the account's real skin goes into the library once (deduplicated by content)
        let account = sh.session.lock().ok().and_then(|s| s.as_ref().map(|s| s.name.clone())).unwrap_or_else(|| "Account".into());
        let added = url != STEVE && active.is_some() && std::fs::read(sh.paths.root.join("cache").join("textures").join(format!("{}.png", url.rsplit('/').next().unwrap_or("x"))))
            .ok().and_then(|png| nether_core::skins::add_account(&sh.paths, &png, if slim { nether_core::skins::Model::Slim } else { nether_core::skins::Model::Classic }, &account).ok()).unwrap_or(false);
        let mut list: Vec<(String, String, bool, RgbaImage)> = vec![];
        for c in &capes {
            let (Some(id), Some(u)) = (c["id"].as_str(), c["url"].as_str()) else { continue };
            if let Some(img) = fetch_png(&sh, u) {
                list.push((id.into(), c["alias"].as_str().unwrap_or("Cape").into(), c["state"] == "ACTIVE", img));
            }
        }
        ui_do(&weak, move |ui| {
            let Some(skin) = skin else { return };
            let active = list.iter().position(|c| c.2);
            let mut cards = vec![Cape { id: "".into(), name: "No cape".into(), img: Image::default() }];
            cards.extend(list.iter().map(|c| Cape { id: c.0.as_str().into(), name: c.1.as_str().into(), img: cape_front(&c.3) }));
            let cape = NWORN.with(|n| n.borrow().as_ref().map(|c| c.img.clone())).or_else(|| active.map(|i| Rc::new(list[i].3.clone())));
            CAPES.with(|v| *v.borrow_mut() = list.into_iter().map(|c| CapeTex { id: c.0, img: Rc::new(c.3), frames: Rc::from([]), ms: 0 }).collect());
            // another account: look its Nether cape up again
            if ui.global::<Backend>().get_ncape_state() == 2 && NUUID.with(|u| *u.borrow() != msa_uuid(&sh)) {
                NWORN.with(|n| *n.borrow_mut() = None);
                ui.global::<Backend>().set_ncape_state(0);
                load_ncapes(ui, &sh);
            }
            ACCOUNT.with(|a| *a.borrow_mut() = Some((skin.clone(), slim)));
            PREVIEW.with(|p| *p.borrow_mut() = false);
            ui.global::<SkinLib>().set_sel(-1);
            LOOK.with(|l| *l.borrow_mut() = Some(Look { skin, slim, cape }));
            mark_dirty();
            let b = ui.global::<Backend>();
            let names: Vec<slint::SharedString> = cards.iter().map(|c| c.name.clone()).collect();
            b.set_cape_names(ModelRc::new(VecModel::from(names)));
            b.set_capes(ModelRc::new(VecModel::from(cards)));
            b.set_cape_idx(active.map(|i| i as i32 + 1).unwrap_or(0));
            sync_sel(ui);
            b.set_skin_slim(slim);
            b.set_acct_head(LOOK.with(|l| l.borrow().as_ref().map(|l| head(&l.skin))).unwrap_or_default());
            b.set_skin_ready(true);
            if added { ui.global::<SkinLib>().invoke_refresh(); }
            export(ui, &sh);
            load_ncapes(ui, &sh); // the in-game wardrobe needs the Nether cape list even if this window never opens the Wardrobe
            if ui.global::<Backend>().get_cos_state() != 1 { ui.global::<Backend>().set_cos_state(0); } // cards show this account's skin
            load_cosmetics(ui, &sh);
        });
    });
}

/// Pick an account cape (index into Backend.capes; 0 = no cape); APPLY sends it.
pub fn equip(ui: &AppWindow, _sh: &Arc<Shared>, idx: i32) {
    if ui.global::<Backend>().get_capes().row_data(idx.max(0) as usize).is_none() { return; }
    edit_sel(ui, |s| s.cape = idx);
}

/// Show / hide a cape on the Minecraft account (Minecraft Services); true = done.
fn mojang_cape(token: &str, id: &str) -> bool {
    let c = client();
    let url = "https://api.minecraftservices.com/minecraft/profile/capes/active";
    let r = if id.is_empty() { c.delete(url).bearer_auth(token).send() } else { c.put(url).bearer_auth(token).json(&serde_json::json!({"capeId": id})).send() };
    r.map(|r| r.status().is_success()).unwrap_or(false)
}

/// Account cape id currently selected (for the preview when a Nether cape comes off).
fn account_cape_id(ui: &AppWindow) -> String {
    let b = ui.global::<Backend>();
    b.get_capes().row_data(b.get_cape_idx().max(0) as usize).map(|c| c.id.to_string()).unwrap_or_default()
}

fn set_worn(ui: &AppWindow, id: &str) {
    let tex = NCAPES.with(|v| v.borrow().iter().find(|c| c.id == id).cloned());
    NWORN.with(|n| *n.borrow_mut() = tex);
    ui.global::<Backend>().set_ncape_worn(id.into());
}

fn msa_uuid(sh: &Shared) -> Option<String> { sh.session.lock().ok().and_then(|s| s.clone()).filter(|s| s.user_type == "msa").map(|s| s.uuid) }

/// Load the Nether cape directory (+ textures) and which one the signed-in player wears.
pub fn load_ncapes(ui: &AppWindow, sh: &Arc<Shared>) { load_ncapes_as(ui, sh, false) }

/// `again`: re-read even when loaded (Refresh); the cards stay up meanwhile.
fn load_ncapes_as(ui: &AppWindow, sh: &Arc<Shared>, again: bool) {
    let b = ui.global::<Backend>();
    let st = b.get_ncape_state();
    if st == 1 || (st == 2 && !again) { return; }
    if st != 2 { b.set_ncape_state(1); }
    let uuid = msa_uuid(sh);
    NUUID.with(|u| *u.borrow_mut() = uuid.clone());
    let weak = ui.as_weak();
    let sh = sh.clone();
    std::thread::spawn(move || {
        let c = client();
        let list: Vec<(nether_core::backend::Cape, Vec<RgbaImage>)> = nether_core::backend::capes(&c).into_iter()
            .filter_map(|cape| { let f = cape_frames(&image::load_from_memory(&cape.png(&c)?).ok()?.to_rgba8()); (!f.is_empty()).then_some((cape, f)) }).collect(); // signed + layout-checked bytes only
        let worn = uuid.as_deref().and_then(|u| nether_core::backend::players(&c, &[u]).ok()).and_then(|m| m.into_values().next()).unwrap_or_default();
        ui_do(&weak, move |ui| {
            let b = ui.global::<Backend>();
            if list.is_empty() { b.set_ncape_state(3); return; }
            b.set_ncapes(ModelRc::new(VecModel::from(list.iter().map(|(c, f)| Cape { id: c.id.as_str().into(), name: c.name.as_str().into(), img: cape_front(&f[0]) }).collect::<Vec<_>>())));
            NCAPES.with(|v| *v.borrow_mut() = list.into_iter().map(|(c, f)| {
                let ms = c.frame_ms();
                let frames: Rc<[Rc<RgbaImage>]> = f.into_iter().map(Rc::new).collect();
                CapeTex { id: c.id, img: frames[0].clone(), frames, ms }
            }).collect());
            set_worn(ui, &worn);
            b.set_ncape_state(2);
            sync_sel(ui);
            export(ui, &sh);
        });
    });
}

/// Pick a Nether cape (the picked one again = none); APPLY sends it.
pub fn wear_ncape(ui: &AppWindow, _sh: &Arc<Shared>, id: &str) {
    edit_sel(ui, |s| s.ncape = if s.ncape == id { String::new() } else { id.to_string() });
}

// ---------------- Nether cosmetics ----------------

const CARD_W: u32 = 300;
const CARD_H: u32 = 390;

/// A cosmetic card picture: the player wearing just that item, framed for its type.
fn card(skin: &RgbaImage, slim: bool, it: &Loaded) -> RgbaImage {
    use nether_core::preview::{render, Look as PLook, View};
    let mut items: [Option<&Loaded>; SLOTS] = [None; SLOTS];
    items[it.kind.slot()] = Some(it);
    let view = match it.kind {
        Kind::Hat | Kind::Headband | Kind::Mask => View::head(0.42),
        Kind::Back | Kind::Wings => View::aura(std::f32::consts::PI + 0.55), // from behind
        Kind::Aura | Kind::Pet => View::aura(0.4),
    };
    render(&PLook { skin, slim, cape: None, items }, view, CARD_W, CARD_H, 1.7, 0)
}

fn rgba_image(img: &RgbaImage) -> Image { Image::from_rgba8(SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(img.as_raw(), img.width(), img.height())) }

fn cos_card(m: &nether_core::backend::Cosmetic, img: &RgbaImage) -> CosCard {
    CosCard { id: m.id.as_str().into(), name: m.name.as_str().into(), kind: m.kind.as_str().into(), desc: m.desc.as_str().into(), img: rgba_image(img) }
}

/// Load the cosmetics directory (verified models + textures), render the cards and read what the player wears.
pub fn load_cosmetics(ui: &AppWindow, sh: &Arc<Shared>) { load_cosmetics_as(ui, sh, false) }

fn load_cosmetics_as(ui: &AppWindow, sh: &Arc<Shared>, again: bool) {
    let b = ui.global::<Backend>();
    let st = b.get_cos_state();
    if st == 1 || (st == 2 && !again) { return; }
    if st != 2 { b.set_cos_state(1); }
    let uuid = msa_uuid(sh);
    let (skin, slim) = ACCOUNT.with(|a| a.borrow().clone()).unwrap_or_else(|| (RgbaImage::new(64, 64), false));
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let c = client();
        let list: Vec<(nether_core::backend::Cosmetic, Loaded, RgbaImage)> = nether_core::backend::cosmetics(&c).into_iter()
            .filter_map(|m| { let l = m.load(&c)?; let img = card(&skin, slim, &l); Some((m, l, img)) }).collect();
        let worn = uuid.as_deref().and_then(|u| nether_core::backend::looks(&c, &[u]).ok()).and_then(|m| m.into_values().next()).map(|l| l.slots).unwrap_or_default();
        ui_do(&weak, move |ui| {
            let b = ui.global::<Backend>();
            if list.is_empty() { b.set_cos_state(3); b.set_look_refreshing(false); return; }
            let mut by: [Vec<CosCard>; SLOTS] = Default::default();
            for (m, l, img) in &list { by[l.kind.slot()].push(cos_card(m, img)); }
            let pages: Vec<ModelRc<CosCard>> = by.into_iter().map(|v| ModelRc::new(VecModel::from(v))).collect();
            b.set_cos_items(ModelRc::new(VecModel::from(pages)));
            COS_META.with(|v| *v.borrow_mut() = list.iter().map(|(m, _, _)| m.clone()).collect());
            COS_CARDS.with(|v| *v.borrow_mut() = list.iter().map(|(m, _, img)| (m.id.clone(), img.clone())).collect());
            NCOS.with(|v| *v.borrow_mut() = list.into_iter().map(|(_, l, _)| Rc::new(l)).collect());
            set_cos_worn(ui, worn);
            b.set_cos_state(2);
            sync_sel(ui);
            b.set_look_refreshing(false);
            export(ui, &sh);
        });
    });
}

fn set_cos_worn(ui: &AppWindow, worn: [String; SLOTS]) {
    let v: Vec<slint::SharedString> = worn.iter().map(|s| s.as_str().into()).collect();
    ui.global::<Backend>().set_cos_worn(ModelRc::new(VecModel::from(v)));
    COS_WORN.with(|w| *w.borrow_mut() = worn);
    mark_dirty();
}

/// Show a cosmetic on the preview while its card is hovered ("" = back to what is worn).
pub fn hover_cosmetic(id: &str) {
    let it = if id.is_empty() { None } else { NCOS.with(|v| v.borrow().iter().find(|l| l.id == id).cloned()) };
    COS_HOVER.with(|h| *h.borrow_mut() = it);
    mark_dirty();
}

/// Pick a cosmetic in slot `slot` (`Kind::ALL` order); the picked one again (or "") empties the slot. APPLY sends it.
pub fn wear_cosmetic(ui: &AppWindow, _sh: &Arc<Shared>, slot: i32, id: &str) {
    let Some(kind) = Kind::ALL.get(slot.max(0) as usize).copied() else { return };
    edit_sel(ui, |s| { let c = &mut s.cos[kind.slot()]; *c = if id.is_empty() || c == id { String::new() } else { id.to_string() }; });
}

/// Send a look to the services in as few requests as possible: one to Minecraft when the account cape changes, one
/// to the Nether API for the Nether cape + every changed cosmetic (its write limit is 5 a minute). `game` = the seq of
/// the in-game request it answers.
pub fn apply_look(ui: &AppWindow, sh: &Arc<Shared>, target: Sel, game: Option<u64>) {
    let b = ui.global::<Backend>();
    let answer = |ui: &AppWindow, ok: bool, msg: &str| if let Some(seq) = game { ANSWER.with(|a| *a.borrow_mut() = Some((seq, ok, msg.to_string()))); export(ui, sh); };
    if b.get_look_busy() { answer(ui, false, "Still applying the last change"); return; }
    let cur = applied(ui);
    if target == cur { answer(ui, true, ""); if game.is_none() { sync_sel(ui); } return; }
    let Some(session) = sh.session.lock().ok().and_then(|s| s.clone()).filter(|s| s.user_type == "msa") else {
        show_toast(ui, sh, "Sign in with Microsoft to change your look");
        answer(ui, false, "Sign in to the launcher with Microsoft first");
        return;
    };
    let cape = (target.cape != cur.cape).then(|| b.get_capes().row_data(target.cape.max(0) as usize).map(|c| c.id.to_string()).unwrap_or_default());
    let ncape = (target.ncape != cur.ncape).then(|| target.ncape.clone());
    let cos: Vec<(Kind, String)> = Kind::ALL.iter().filter(|k| target.cos[k.slot()] != cur.cos[k.slot()]).map(|k| (*k, target.cos[k.slot()].clone())).collect();
    b.set_look_busy(true);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let c = client();
        let cape_ok = cape.as_ref().map(|id| mojang_cape(&session.access_token, id));
        let slots: Vec<(Kind, Option<&str>)> = cos.iter().map(|(k, id)| (*k, (!id.is_empty()).then_some(id.as_str()))).collect();
        let nether = nether_core::backend::set_look(&c, &session, ncape.as_deref().map(|n| (!n.is_empty()).then_some(n)), &slots);
        ui_do(&weak, move |ui| {
            let b = ui.global::<Backend>();
            b.set_look_busy(false);
            if cape_ok == Some(true) { b.set_cape_idx(target.cape); }
            let mut err = Vec::new();
            if cape_ok == Some(false) { err.push("Couldn't change your Minecraft cape".to_string()); }
            match nether {
                Ok(()) => {
                    if let Some(n) = &ncape { set_worn(ui, n); }
                    if !cos.is_empty() {
                        let mut w = COS_WORN.with(|w| w.borrow().clone());
                        for (k, id) in &cos { w[k.slot()] = id.clone(); }
                        set_cos_worn(ui, w);
                    }
                }
                Err(e) => err.push(format!("Couldn't change your look: {e:#}")),
            }
            let ok = err.is_empty();
            let msg = if ok { "Look applied".to_string() } else { err.join(" · ") };
            // done: the editor shows the account again; failed: the picks stay so APPLY can retry
            if ok { sync_sel(ui) } else { push_sel(ui, SEL.with(|v| v.borrow().clone())) }
            if let Some(seq) = game { ANSWER.with(|a| *a.borrow_mut() = Some((seq, ok, msg.clone()))); }
            export(ui, &sh);
            show_toast(ui, &sh, &msg);
        });
    });
}

/// Launcher APPLY.
pub fn apply(ui: &AppWindow, sh: &Arc<Shared>) { apply_look(ui, sh, SEL.with(|v| v.borrow().clone()), None) }

/// Refresh (launcher or game): re-read the signed content (capes, cosmetics) and what the account wears now.
pub fn refresh(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    if b.get_look_refreshing() { return; }
    b.set_look_refreshing(true);
    nether_core::backend::refresh();
    load_ncapes_as(ui, sh, true);
    load_cosmetics_as(ui, sh, true);
    if b.get_cos_state() != 2 && b.get_cos_state() != 1 { b.set_look_refreshing(false); }
}

// ---------------- in-game wardrobe bridge ----------------
// The game's WARDROBE tab never gets a token: the launcher publishes the account's skin and capes to
// `<root>/client/wardrobe/` (state.json + PNGs) and applies the requests the game writes there (request.json).

fn safe_id(id: &str) -> bool { !id.is_empty() && id.len() <= 64 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') }

/// Publish skin + capes + what is worn for the in-game wardrobe.
pub fn export(ui: &AppWindow, sh: &Shared) {
    let dir = sh.paths.root.join("client").join("wardrobe");
    let Some((skin, slim)) = ACCOUNT.with(|a| a.borrow().clone()).or_else(|| LOOK.with(|l| l.borrow().as_ref().map(|l| (l.skin.clone(), l.slim)))) else { return };
    let b = ui.global::<Backend>();
    let mut st = serde_json::json!({ "v": 1, "uuid": msa_uuid(sh).unwrap_or_default(), "slim": slim, "skin": "skin.png", "netherWorn": b.get_ncape_worn().to_string(), "nether": [], "account": [] });
    if let Some((seq, ok, msg)) = ANSWER.with(|a| a.borrow().clone()) { st["answer"] = serde_json::json!({ "seq": seq, "ok": ok, "msg": msg }); }
    let ok = std::fs::create_dir_all(&dir).is_ok() && skin.save(dir.join("skin.png")).is_ok();
    if !ok { return; }
    let mut wanted = vec!["skin.png".to_string(), "state.json".to_string()];
    for c in b.get_ncapes().iter() {
        let tex = NCAPES.with(|v| v.borrow().iter().find(|t| t.id == c.id.as_str()).map(|t| t.img.clone()));
        let (Some(img), true) = (tex, safe_id(&c.id)) else { continue };
        let f = format!("n_{}.png", c.id);
        if img.save(dir.join(&f)).is_ok() { st["nether"].as_array_mut().unwrap().push(serde_json::json!({ "id": c.id.as_str(), "name": c.name.as_str(), "png": f })); wanted.push(f); }
    }
    let worn = COS_WORN.with(|w| w.borrow().clone());
    st["cosmetics"] = serde_json::Value::Object(Kind::ALL.iter().map(|k| (k.as_str().to_string(), serde_json::json!(worn[k.slot()]))).collect());
    st["items"] = serde_json::json!([]);
    COS_META.with(|meta| COS_CARDS.with(|cards| {
        for m in meta.borrow().iter() {
            let Some((_, img)) = cards.borrow().iter().find(|(id, _)| *id == m.id).cloned() else { continue };
            if !safe_id(&m.id) { continue; }
            let f = format!("c_{}.png", m.id);
            if img.save(dir.join(&f)).is_ok() {
                st["items"].as_array_mut().unwrap().push(serde_json::json!({ "id": m.id, "name": m.name, "type": m.kind, "png": f }));
                wanted.push(f);
            }
        }
    }));
    let active = account_cape_id(ui);
    st["accountWorn"] = active.into();
    for c in b.get_capes().iter().filter(|c| !c.id.is_empty()) {
        let tex = CAPES.with(|v| v.borrow().iter().find(|t| t.id == c.id.as_str()).map(|t| t.img.clone()));
        let (Some(img), true) = (tex, safe_id(&c.id)) else { continue };
        let f = format!("a_{}.png", c.id);
        if img.save(dir.join(&f)).is_ok() { st["account"].as_array_mut().unwrap().push(serde_json::json!({ "id": c.id.as_str(), "name": c.name.as_str(), "png": f })); wanted.push(f); }
    }
    if let Ok(rd) = std::fs::read_dir(&dir) {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().to_string();
            if (n.starts_with("n_") || n.starts_with("a_") || n.starts_with("c_")) && n.ends_with(".png") && !wanted.contains(&n) { let _ = std::fs::remove_file(e.path()); }
        }
    }
    let _ = nether_core::store::write_atomic(&dir.join("state.json"), &serde_json::to_vec_pretty(&st).unwrap_or_default());
}

/// Apply what the game asks for (a request.json in the wardrobe folder), polled while the launcher runs.
pub fn watch(ui: &AppWindow, sh: Arc<Shared>) {
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let f = sh.paths.root.join("client").join("wardrobe").join("request.json");
        loop {
            std::thread::sleep(std::time::Duration::from_millis(1500));
            let Ok(bytes) = std::fs::read(&f) else { continue };
            let _ = std::fs::remove_file(&f);
            let Ok(req) = serde_json::from_slice::<Value>(&bytes) else { continue };
            let sh = sh.clone();
            ui_do(&weak, move |ui| apply_request(ui, &sh, &req));
        }
    });
}

/// A request from the in-game wardrobe: {"seq", "look": {"nether", "account", "cosmetics": {slot: id}}} (keys present
/// = wanted, "" = none) or {"seq", "refresh": true}.
fn apply_request(ui: &AppWindow, sh: &Arc<Shared>, req: &Value) {
    let seq = req["seq"].as_f64().unwrap_or(0.0) as u64;
    if req["refresh"].as_bool() == Some(true) { refresh(ui, sh); return; }
    let l = &req["look"];
    if !l.is_object() { return; }
    let b = ui.global::<Backend>();
    let mut t = applied(ui);
    if let Some(n) = l["nether"].as_str() { if n.is_empty() || b.get_ncapes().iter().any(|c| c.id == n) { t.ncape = n.to_string(); } }
    if let Some(a) = l["account"].as_str() { if let Some(i) = b.get_capes().iter().position(|c| c.id == a) { t.cape = i as i32; } }
    if let Some(m) = l["cosmetics"].as_object() {
        for (slot, id) in m { if let (Some(k), Some(id)) = (Kind::parse(slot), id.as_str()) { t.cos[k.slot()] = id.to_string(); } }
    }
    apply_look(ui, sh, t, Some(seq));
}

/// The preview wears the picked cape: the Nether one, else the account one.
fn set_preview_cape() {
    let s = SEL.with(|v| v.borrow().clone());
    let tex = NCAPES.with(|v| v.borrow().iter().find(|c| !s.ncape.is_empty() && c.id == s.ncape).cloned())
        .or_else(|| if s.cape > 0 { CAPES.with(|v| v.borrow().get(s.cape as usize - 1).cloned()) } else { None });
    ANIM.with(|a| *a.borrow_mut() = tex.as_ref().filter(|t| t.frames.len() > 1).map(|t| (t.frames.clone(), t.ms, 0)));
    LOOK.with(|l| if let Some(l) = l.borrow_mut().as_mut() { l.cape = tex.map(|t| t.img); });
    mark_dirty();
}

thread_local! {
    /// Frame timer for the preview: runs only while something changes (a new skin, a drag) or the model spins, so an idle launcher draws nothing (Design: idle = zero redraws).
    static TIMER: slint::Timer = slint::Timer::default();
    static UI: RefCell<Option<slint::Weak<AppWindow>>> = const { RefCell::new(None) };
    static DEPTH: RefCell<Vec<f32>> = const { RefCell::new(Vec::new()) };
    static DIMS: std::cell::Cell<(u32, u32)> = const { std::cell::Cell::new((0, 0)) };
    static LAST: RefCell<std::time::Instant> = RefCell::new(std::time::Instant::now());
    static START: std::time::Instant = std::time::Instant::now();
}

pub fn init(ui: &AppWindow) { UI.with(|u| *u.borrow_mut() = Some(ui.as_weak())); wake(); }

/// The preview needs frames (hover, drag, tab change, new skin): start the timer if it is stopped.
pub fn wake() {
    TIMER.with(|t| {
        if t.running() { return; }
        LAST.with(|l| *l.borrow_mut() = std::time::Instant::now());
        t.start(slint::TimerMode::Repeated, std::time::Duration::from_millis(40), || {
            let dt = LAST.with(|l| { let mut l = l.borrow_mut(); let d = l.elapsed().as_secs_f32().min(0.1); *l = std::time::Instant::now(); d });
            let ui = UI.with(|u| u.borrow().as_ref().and_then(|w| w.upgrade()));
            let keep = ui.map(|ui| tick(&ui, dt)).unwrap_or(false);
            if !keep { TIMER.with(|t| t.stop()); }
        });
    });
}

fn mark_dirty() {
    DIRTY.with(|d| *d.borrow_mut() = true);
    wake();
}

/// One preview frame on the UI thread. Returns whether more frames are needed.
fn tick(ui: &AppWindow, dt: f32) -> bool {
    // leaving the Wardrobe ends a library preview
    if ui.get_tab() != 5 && PREVIEW.with(|p| *p.borrow()) { preview_account(ui); }
    if !matches!(ui.get_tab(), 0 | 5) || !ui.window().is_visible() || ui.window().is_minimized() || !crate::winchrome::is_active() { return false; } // background windows draw nothing; WM_ACTIVATE wakes the timer again
    let b = ui.global::<Backend>();
    // auto spin always (while the tab shows), never with reduced motion
    let spin = b.get_skin_spin() && !b.get_skin_held() && !b.get_calm();
    if spin { YAW.with(|y| *y.borrow_mut() += dt * std::f32::consts::TAU / 14.0); }
    let slim = b.get_skin_slim();
    // an animated cape or cosmetic keeps the preview drawing (first frame only with reduced motion)
    let mut anim = !b.get_calm() && shown_items().iter().flatten().any(|l| l.animated());
    LOOK.with(|l| {
        let mut l = l.borrow_mut();
        let Some(look) = l.as_mut() else { return };
        let mut dirty = DIRTY.with(|d| std::mem::replace(&mut *d.borrow_mut(), false));
        ANIM.with(|a| if let Some((f, ms, cur)) = a.borrow_mut().as_mut() {
            anim = !b.get_calm();
            let i = if anim { (START.with(|s| s.elapsed().as_millis()) / *ms as u128 % f.len() as u128) as usize } else { 0 };
            if i != *cur { *cur = i; look.cape = Some(f[i].clone()); dirty = true; }
        });
        // pixels actually shown: the tallest preview (Wardrobe, 490 stage px) at the window's stage scale
        let win = ui.window().size();
        let fh = ((490.0 * (win.width as f32 / 1440.0).min(win.height as f32 / 900.0)).round() as u32).clamp(360, H);
        let dims = (fh * W / H, fh);
        let resized = DIMS.with(|d| d.replace(dims)) != dims;
        if !spin && !dirty && !resized && !anim && look.slim == slim { return; }
        look.slim = slim;
        let yaw = YAW.with(|y| *y.borrow());
        b.set_skin_frame(Image::from_rgba8(render_at(look, yaw, dims.0, dims.1)));
    });
    spin || anim || b.get_skin_held()
}

// ---------------- renderer (nether_core::preview) ----------------

/// The cosmetics on the preview: the picked ones, with a hovered card replacing its slot.
fn shown_items() -> [Option<Rc<Loaded>>; SLOTS] {
    let worn = SEL.with(|w| w.borrow().cos.clone());
    let hover = COS_HOVER.with(|h| h.borrow().clone());
    NCOS.with(|v| {
        let v = v.borrow();
        std::array::from_fn(|i| {
            if let Some(h) = hover.as_ref().filter(|h| h.kind.slot() == i) { return Some(h.clone()); }
            v.iter().find(|l| !worn[i].is_empty() && l.id == worn[i] && l.kind.slot() == i).cloned()
        })
    })
}

/// Render the player at `yaw` (radians) into a W x H transparent buffer.
pub fn render(look: &Look, yaw: f32) -> SharedPixelBuffer<Rgba8Pixel> { render_at(look, yaw, W, H) }

/// Same frame at another size (keep the 600:780 ratio); the live preview renders only as many pixels as it shows.
pub fn render_at(look: &Look, yaw: f32, fw: u32, fh: u32) -> SharedPixelBuffer<Rgba8Pixel> {
    let mut buf = SharedPixelBuffer::<Rgba8Pixel>::new(fw, fh);
    // the depth buffer is reused between frames (a fresh 2 MB zeroed Vec per frame at 30 fps is pure churn)
    let mut depth = DEPTH.with(|d| std::mem::take(&mut *d.borrow_mut()));
    let shown = shown_items();
    let items: [Option<&Loaded>; SLOTS] = std::array::from_fn(|i| shown[i].as_deref());
    let t = START.with(|s| s.elapsed());
    let plook = nether_core::preview::Look { skin: &look.skin, slim: look.slim, cape: look.cape.as_deref(), items };
    nether_core::preview::render_into(buf.make_mut_bytes(), fw, fh, &mut depth, &plook, nether_core::preview::View::full(yaw), t.as_secs_f64(), t.as_millis() as u64);
    DEPTH.with(|d| *d.borrow_mut() = depth);
    buf
}

/// Turn the model by a mouse drag of `dx` logical pixels.
pub fn drag(dx: f32) {
    YAW.with(|y| *y.borrow_mut() += dx * 0.012);
    mark_dirty();
}

// ---------------- library previews ----------------

/// Show a library skin on the 3D model without applying it to the account.
pub fn preview(ui: &AppWindow, skin: RgbaImage, slim: bool) {
    LOOK.with(|l| if let Some(l) = l.borrow_mut().as_mut() { l.skin = upgrade_legacy(&skin); l.slim = slim; });
    PREVIEW.with(|p| *p.borrow_mut() = true);
    mark_dirty();
    ui.global::<Backend>().set_skin_slim(slim);
}

/// Back to the account's own skin (ends a preview, clears the library selection).
pub fn preview_account(ui: &AppWindow) {
    if let Some((skin, slim)) = ACCOUNT.with(|a| a.borrow().clone()) {
        LOOK.with(|l| if let Some(l) = l.borrow_mut().as_mut() { l.skin = skin; l.slim = slim; });
        ui.global::<Backend>().set_skin_slim(slim);
    }
    PREVIEW.with(|p| *p.borrow_mut() = false);
    mark_dirty();
    ui.global::<SkinLib>().set_sel(-1);
}

/// The account now wears `skin` (after a successful upload): update the model and the account chip head.
pub fn set_account(ui: &AppWindow, skin: RgbaImage, slim: bool) {
    let skin = upgrade_legacy(&skin);
    ui.global::<Backend>().set_acct_head(head(&skin));
    ACCOUNT.with(|a| *a.borrow_mut() = Some((skin.clone(), slim)));
    LOOK.with(|l| if let Some(l) = l.borrow_mut().as_mut() { l.skin = skin; l.slim = slim; });
    mark_dirty();
    ui.global::<Backend>().set_skin_slim(slim);
}

/// Static front view of a skin for a library tile (any thread): 230x360, transparent background.
pub fn thumb(skin: &RgbaImage, slim: bool) -> RgbaImage {
    let look = Look { skin: upgrade_legacy(skin), slim, cape: None };
    let buf = render(&look, 0.32);
    let full = RgbaImage::from_raw(W, H, buf.as_bytes().to_vec()).unwrap_or_else(|| RgbaImage::new(W, H));
    // trim the empty margin around the model, then scale down once
    let crop = image::imageops::crop_imm(&full, 70, 30, W - 140, H - 60).to_image();
    image::imageops::resize(&crop, 230, 360, image::imageops::FilterType::Triangle)
}
