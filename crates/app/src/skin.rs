//! Player preview: a small software renderer for the Minecraft player model (skin + outer layer + cape),
//! plus loading the account's skin/capes and equipping capes. Rendered on the UI thread by a timer.
use std::cell::RefCell;
use std::sync::Arc;

use image::RgbaImage;
use nether_core::download::client;
use serde_json::Value;
use slint::{ComponentHandle, Image, Model, ModelRc, Rgba8Pixel, SharedPixelBuffer, VecModel};

use crate::{show_toast, ui_do, AppWindow, Backend, Cape, Shared, SkinLib};

/// Default skin (Steve) when signed out.
const STEVE: &str = "https://textures.minecraft.net/texture/31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb";
/// Output size in pixels (2x the largest on-screen preview, so Slint's downscale smooths the edges).
pub const W: u32 = 600;
pub const H: u32 = 780;

pub struct Look {
    pub skin: RgbaImage,
    pub slim: bool,
    pub cape: Option<RgbaImage>,
}

struct CapeTex { id: String, img: RgbaImage }

thread_local! {
    static LOOK: RefCell<Option<Look>> = const { RefCell::new(None) };
    static CAPES: RefCell<Vec<CapeTex>> = const { RefCell::new(Vec::new()) };
    // Nether cape textures by id, and the one worn (shown on the preview over the account cape)
    static NCAPES: RefCell<Vec<CapeTex>> = const { RefCell::new(Vec::new()) };
    static NWORN: RefCell<Option<RgbaImage>> = const { RefCell::new(None) };
    static NUUID: RefCell<Option<String>> = const { RefCell::new(None) };
    static YAW: RefCell<f32> = const { RefCell::new(0.5) };
    static DIRTY: RefCell<bool> = const { RefCell::new(true) };
    // the account's own skin, and whether a library skin is being previewed instead
    static ACCOUNT: RefCell<Option<(RgbaImage, bool)>> = const { RefCell::new(None) };
    static PREVIEW: RefCell<bool> = const { RefCell::new(false) };
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

pub fn load(weak: slint::Weak<AppWindow>, sh: Arc<Shared>, skins: Vec<Value>, capes: Vec<Value>) {
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
            let cape = NWORN.with(|n| n.borrow().clone()).or_else(|| active.map(|i| list[i].3.clone()));
            CAPES.with(|v| *v.borrow_mut() = list.into_iter().map(|c| CapeTex { id: c.0, img: c.3 }).collect());
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
            b.set_skin_slim(slim);
            b.set_acct_head(LOOK.with(|l| l.borrow().as_ref().map(|l| head(&l.skin))).unwrap_or_default());
            b.set_skin_ready(true);
            if added { ui.global::<SkinLib>().invoke_refresh(); }
        });
    });
}

/// Show / hide a cape on the account (index into Backend.capes; 0 = no cape).
pub fn equip(ui: &AppWindow, sh: &Arc<Shared>, idx: i32) {
    let b = ui.global::<Backend>();
    let Some(card) = b.get_capes().row_data(idx.max(0) as usize) else { return };
    let prev = b.get_cape_idx();
    set_preview_cape(&card.id);
    b.set_cape_idx(idx);
    let Some(session) = sh.session.lock().unwrap().clone().filter(|s| s.user_type == "msa") else {
        show_toast(ui, sh, "Sign in to change your cape");
        return;
    };
    let (weak, sh, id, name) = (ui.as_weak(), sh.clone(), card.id.to_string(), card.name.to_string());
    std::thread::spawn(move || {
        let url = "https://api.minecraftservices.com/minecraft/profile/capes/active";
        let c = client();
        let r = if id.is_empty() { c.delete(url).bearer_auth(&session.access_token).send() } else { c.put(url).bearer_auth(&session.access_token).json(&serde_json::json!({"capeId": id})).send() };
        let ok = r.map(|r| r.status().is_success()).unwrap_or(false);
        ui_do(&weak, move |ui| {
            if ok {
                show_toast(ui, &sh, &if id.is_empty() { "Cape hidden".to_string() } else { format!("{name} equipped") });
            } else {
                let b = ui.global::<Backend>();
                if let Some(p) = b.get_capes().row_data(prev.max(0) as usize) { set_preview_cape(&p.id); }
                b.set_cape_idx(prev);
                show_toast(ui, &sh, "Couldn't change your cape, try again");
            }
        });
    });
}

/// Account cape id currently selected (for the preview when a Nether cape comes off).
fn account_cape_id(ui: &AppWindow) -> String {
    let b = ui.global::<Backend>();
    b.get_capes().row_data(b.get_cape_idx().max(0) as usize).map(|c| c.id.to_string()).unwrap_or_default()
}

fn set_worn(ui: &AppWindow, id: &str) {
    let img = NCAPES.with(|v| v.borrow().iter().find(|c| c.id == id).map(|c| c.img.clone()));
    NWORN.with(|n| *n.borrow_mut() = img);
    ui.global::<Backend>().set_ncape_worn(id.into());
    set_preview_cape(&account_cape_id(ui));
}

fn msa_uuid(sh: &Shared) -> Option<String> { sh.session.lock().ok().and_then(|s| s.clone()).filter(|s| s.user_type == "msa").map(|s| s.uuid) }

/// Load the Nether cape directory (+ textures) and which one the signed-in player wears.
pub fn load_ncapes(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    if matches!(b.get_ncape_state(), 1 | 2) { return; }
    b.set_ncape_state(1);
    let uuid = msa_uuid(sh);
    NUUID.with(|u| *u.borrow_mut() = uuid.clone());
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let c = client();
        let list: Vec<(nether_core::backend::Cape, RgbaImage)> = nether_core::backend::capes(&c).into_iter()
            .filter_map(|cape| { let img = image::load_from_memory(&cape.png(&c)?).ok()?.to_rgba8(); Some((cape, img)) }).collect(); // signed + size-checked bytes only
        let worn = uuid.as_deref().and_then(|u| nether_core::backend::players(&c, &[u]).ok()).and_then(|m| m.into_values().next()).unwrap_or_default();
        ui_do(&weak, move |ui| {
            let b = ui.global::<Backend>();
            if list.is_empty() { b.set_ncape_state(3); return; }
            b.set_ncapes(ModelRc::new(VecModel::from(list.iter().map(|(c, img)| Cape { id: c.id.as_str().into(), name: c.name.as_str().into(), img: cape_front(img) }).collect::<Vec<_>>())));
            NCAPES.with(|v| *v.borrow_mut() = list.into_iter().map(|(c, img)| CapeTex { id: c.id, img }).collect());
            set_worn(ui, &worn);
            b.set_ncape_state(2);
        });
    });
}

/// Wear a Nether cape, or take it off when it is the one worn.
pub fn wear_ncape(ui: &AppWindow, sh: &Arc<Shared>, id: &str) {
    let b = ui.global::<Backend>();
    if b.get_ncape_busy() { return; }
    let Some(session) = sh.session.lock().ok().and_then(|s| s.clone()).filter(|s| s.user_type == "msa") else {
        show_toast(ui, sh, "Sign in with Microsoft to wear Nether capes");
        return;
    };
    let off = b.get_ncape_worn() == id;
    let name = b.get_ncapes().iter().find(|c| c.id == id).map(|c| c.name.to_string()).unwrap_or_default();
    b.set_ncape_busy(true);
    let (weak, sh, id) = (ui.as_weak(), sh.clone(), id.to_string());
    std::thread::spawn(move || {
        let r = nether_core::backend::set_cape(&client(), &session, (!off).then_some(id.as_str()));
        ui_do(&weak, move |ui| {
            ui.global::<Backend>().set_ncape_busy(false);
            match r {
                Ok(()) => {
                    set_worn(ui, if off { "" } else { &id });
                    show_toast(ui, &sh, &if off { format!("{name} taken off") } else { format!("You're now wearing {name}") });
                }
                Err(e) => show_toast(ui, &sh, &format!("Couldn't change your Nether cape: {e:#}")),
            }
        });
    });
}

fn set_preview_cape(id: &str) {
    let img = NWORN.with(|n| n.borrow().clone()).or_else(|| CAPES.with(|v| v.borrow().iter().find(|c| c.id == id).map(|c| c.img.clone())));
    LOOK.with(|l| if let Some(l) = l.borrow_mut().as_mut() { l.cape = img; });
    mark_dirty();
}

thread_local! {
    /// Frame timer for the preview: runs only while something changes (a new skin, a drag) or the model spins under
    /// the pointer, so an idle launcher draws nothing (Design: idle = zero redraws).
    static TIMER: slint::Timer = slint::Timer::default();
    static UI: RefCell<Option<slint::Weak<AppWindow>>> = const { RefCell::new(None) };
    static LAST: RefCell<std::time::Instant> = RefCell::new(std::time::Instant::now());
}

pub fn init(ui: &AppWindow) { UI.with(|u| *u.borrow_mut() = Some(ui.as_weak())); wake(); }

/// The preview needs frames (hover, drag, tab change, new skin): start the timer if it is stopped.
pub fn wake() {
    TIMER.with(|t| {
        if t.running() { return; }
        LAST.with(|l| *l.borrow_mut() = std::time::Instant::now());
        t.start(slint::TimerMode::Repeated, std::time::Duration::from_millis(33), || {
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
    if !matches!(ui.get_tab(), 0 | 5) || !ui.window().is_visible() || ui.window().is_minimized() { return false; }
    let b = ui.global::<Backend>();
    // auto spin only while the pointer rests on the model, never with reduced motion
    let spin = b.get_skin_spin() && b.get_skin_hover() && !b.get_skin_held() && !b.get_calm();
    if spin { YAW.with(|y| *y.borrow_mut() += dt * std::f32::consts::TAU / 14.0); }
    let slim = b.get_skin_slim();
    LOOK.with(|l| {
        let mut l = l.borrow_mut();
        let Some(look) = l.as_mut() else { return };
        let dirty = DIRTY.with(|d| std::mem::replace(&mut *d.borrow_mut(), false));
        if !spin && !dirty && look.slim == slim { return; }
        look.slim = slim;
        let yaw = YAW.with(|y| *y.borrow());
        b.set_skin_frame(Image::from_rgba8(render(look, yaw)));
    });
    spin || b.get_skin_held()
}

// ---------------- renderer ----------------

type V3 = [f32; 3];

struct Quad { p: [V3; 4], uv: [f32; 4], cape: bool, cut: bool }

fn rot_x(p: V3, a: f32) -> V3 { let (s, c) = a.sin_cos(); [p[0], p[1] * c - p[2] * s, p[1] * s + p[2] * c] }
fn rot_y(p: V3, a: f32) -> V3 { let (s, c) = a.sin_cos(); [p[0] * c + p[2] * s, p[1], -p[0] * s + p[2] * c] }
fn rot_z(p: V3, a: f32) -> V3 { let (s, c) = a.sin_cos(); [p[0] * c - p[1] * s, p[0] * s + p[1] * c, p[2]] }

/// A textured box: `min`/`size` in model pixels, `uv` = texture offset, `dims` = texture box size.
#[allow(clippy::too_many_arguments)]
fn cuboid(out: &mut Vec<Quad>, min: V3, size: V3, grow: f32, uv: (f32, f32), xf: &dyn Fn(V3) -> V3, cape: bool, cut: bool) {
    let (w, h, d) = (size[0], size[1], size[2]);
    let (x0, y0, z0) = (min[0] - grow, min[1] - grow, min[2] - grow);
    let (x1, y1, z1) = (min[0] + w + grow, min[1] + h + grow, min[2] + d + grow);
    let (u, v) = uv;
    let faces: [([V3; 4], [f32; 4]); 6] = [
        ([[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]], [u + d, v + d, u + d + w, v + d + h]), // front
        ([[x1, y1, z0], [x0, y1, z0], [x0, y0, z0], [x1, y0, z0]], [u + 2.0 * d + w, v + d, u + 2.0 * d + 2.0 * w, v + d + h]), // back
        ([[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]], [u, v + d, u + d, v + d + h]), // right side
        ([[x1, y1, z1], [x1, y1, z0], [x1, y0, z0], [x1, y0, z1]], [u + d + w, v + d, u + 2.0 * d + w, v + d + h]), // left side
        ([[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]], [u + d, v, u + d + w, v + d]), // top
        ([[x0, y0, z1], [x1, y0, z1], [x1, y0, z0], [x0, y0, z0]], [u + d + w, v, u + d + 2.0 * w, v + d]), // bottom
    ];
    for (p, t) in faces {
        out.push(Quad { p: p.map(xf), uv: t, cape, cut });
    }
}

fn model(look: &Look) -> Vec<Quad> {
    let mut q = Vec::with_capacity(80);
    let id = |p: V3| p;
    let aw = if look.slim { 3.0 } else { 4.0 };
    let arm = |pivot: V3, a: f32| move |p: V3| { let r = rot_z([p[0] - pivot[0], p[1] - pivot[1], p[2] - pivot[2]], a); [r[0] + pivot[0], r[1] + pivot[1], r[2] + pivot[2]] };
    let ra = arm([-5.0, 22.0, 0.0], -0.06);
    let la = arm([5.0, 22.0, 0.0], 0.06);
    // base layer, then the outer layer (slightly inflated, alpha cut-out)
    // (head, body, right arm, left arm, right leg, left leg) texture offsets per layer
    let layers = [
        (false, 0.0, 0.0, [(0.0, 0.0), (16.0, 16.0), (40.0, 16.0), (32.0, 48.0), (0.0, 16.0), (16.0, 48.0)]),
        (true, 0.5, 0.25, [(32.0, 0.0), (16.0, 32.0), (40.0, 32.0), (48.0, 48.0), (0.0, 32.0), (0.0, 48.0)]),
    ];
    for (cut, gh, gb, uv) in layers {
        cuboid(&mut q, [-4.0, 24.0, -4.0], [8.0, 8.0, 8.0], gh, uv[0], &id, false, cut);
        cuboid(&mut q, [-4.0, 12.0, -2.0], [8.0, 12.0, 4.0], gb, uv[1], &id, false, cut);
        cuboid(&mut q, [-4.0 - aw, 12.0, -2.0], [aw, 12.0, 4.0], gb, uv[2], &ra, false, cut);
        cuboid(&mut q, [4.0, 12.0, -2.0], [aw, 12.0, 4.0], gb, uv[3], &la, false, cut);
        cuboid(&mut q, [-4.0, 0.0, -2.0], [4.0, 12.0, 4.0], gb, uv[4], &id, false, cut);
        cuboid(&mut q, [0.0, 0.0, -2.0], [4.0, 12.0, 4.0], gb, uv[5], &id, false, cut);
    }
    if look.cape.is_some() {
        let xf = |p: V3| { let r = rot_x(rot_y(p, std::f32::consts::PI), 0.14); [r[0], r[1] + 24.0, r[2] - 2.9] };
        cuboid(&mut q, [-5.0, -16.0, -0.5], [10.0, 16.0, 1.0], 0.0, (0.0, 0.0), &xf, true, true);
    }
    q
}

/// Render the player at `yaw` (radians) into a W x H transparent buffer.
pub fn render(look: &Look, yaw: f32) -> SharedPixelBuffer<Rgba8Pixel> {
    let mut buf = SharedPixelBuffer::<Rgba8Pixel>::new(W, H);
    let (w, h) = (W as usize, H as usize);
    let mut depth = vec![0f32; w * h];
    let px = buf.make_mut_slice();
    let dist = 90.0f32;
    let k = H as f32 * 0.86 / 34.0;
    let (cx, cy) = (W as f32 / 2.0, H as f32 / 2.0);
    let light = { let l = [-0.45f32, 0.6, 0.75]; let n = (l[0] * l[0] + l[1] * l[1] + l[2] * l[2]).sqrt(); [l[0] / n, l[1] / n, l[2] / n] };

    for quad in model(look) {
        let tex = if quad.cape { match &look.cape { Some(c) => c, None => continue } } else { &look.skin };
        let scale = tex.width() as f32 / 64.0;
        // camera space: centre the model, spin, tilt slightly down
        let cam: Vec<V3> = quad.p.iter().map(|&p| rot_x(rot_y([p[0], p[1] - 16.5, p[2]], yaw), 0.12)).collect();
        // lighting from the face normal (cross product of the edges points inward with our winding)
        let (e1, e2) = (sub(cam[1], cam[0]), sub(cam[2], cam[1]));
        let n = cross(e1, e2);
        let nl = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt().max(1e-6);
        let nd = -(n[0] * light[0] + n[1] * light[1] + n[2] * light[2]) / nl;
        let shade = 0.5 + 0.5 * nd.max(0.0);
        // project
        let sp: Vec<(f32, f32, f32)> = cam.iter().map(|p| { let iw = 1.0 / (dist - p[2]); (cx + p[0] * dist * iw * k, cy - p[1] * dist * iw * k, iw) }).collect();
        let [u0, v0, u1, v1] = quad.uv;
        let uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)];
        let (umin, umax) = ((u0 * scale).floor() as i32, (u1 * scale).ceil() as i32 - 1);
        let (vmin, vmax) = ((v0 * scale).floor() as i32, (v1 * scale).ceil() as i32 - 1);
        for tri in [[0usize, 1, 2], [0, 2, 3]] {
            let (a, b, c) = (sp[tri[0]], sp[tri[1]], sp[tri[2]]);
            let area = (b.0 - a.0) * (c.1 - a.1) - (c.0 - a.0) * (b.1 - a.1);
            if area <= 0.0 { continue; } // facing away
            let (ta, tb, tc) = (uvs[tri[0]], uvs[tri[1]], uvs[tri[2]]);
            let x0 = a.0.min(b.0).min(c.0).floor().max(0.0) as usize;
            let x1 = (a.0.max(b.0).max(c.0).ceil() as usize).min(w - 1);
            let y0 = a.1.min(b.1).min(c.1).floor().max(0.0) as usize;
            let y1 = (a.1.max(b.1).max(c.1).ceil() as usize).min(h - 1);
            for y in y0..=y1 {
                let fy = y as f32 + 0.5;
                for x in x0..=x1 {
                    let fx = x as f32 + 0.5;
                    let wa = (b.0 - fx) * (c.1 - fy) - (c.0 - fx) * (b.1 - fy);
                    let wb = (c.0 - fx) * (a.1 - fy) - (a.0 - fx) * (c.1 - fy);
                    let wc = area - wa - wb;
                    if wa < 0.0 || wb < 0.0 || wc < 0.0 { continue; }
                    let (la, lb, lc) = (wa / area, wb / area, wc / area);
                    let iw = la * a.2 + lb * b.2 + lc * c.2;
                    let i = y * w + x;
                    if iw <= depth[i] { continue; }
                    let u = (la * ta.0 * a.2 + lb * tb.0 * b.2 + lc * tc.0 * c.2) / iw;
                    let v = (la * ta.1 * a.2 + lb * tb.1 * b.2 + lc * tc.1 * c.2) / iw;
                    let tx = ((u * scale) as i32).clamp(umin, umax).clamp(0, tex.width() as i32 - 1) as u32;
                    let ty = ((v * scale) as i32).clamp(vmin, vmax).clamp(0, tex.height() as i32 - 1) as u32;
                    let t = tex.get_pixel(tx, ty).0;
                    if quad.cut && t[3] < 128 { continue; }
                    depth[i] = iw;
                    px[i] = Rgba8Pixel::new((t[0] as f32 * shade) as u8, (t[1] as f32 * shade) as u8, (t[2] as f32 * shade) as u8, 255);
                }
            }
        }
    }
    buf
}

fn sub(a: V3, b: V3) -> V3 { [a[0] - b[0], a[1] - b[1], a[2] - b[2]] }
fn cross(a: V3, b: V3) -> V3 { [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]] }


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
