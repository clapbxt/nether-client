//! Instance icons (block textures) and covers / wallpapers (version title panoramas) for the UI.
use std::cell::RefCell;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use nether_core::art;
use nether_core::download::client;
use slint::{ComponentHandle, Image, Model, ModelRc, Rgba8Pixel, SharedPixelBuffer, VecModel};

use crate::{ui_do, AppWindow, Art, Backend, Shared};

/// Icon used when an instance has none picked.
pub const DEFAULT_ICON: &str = "grass_block_side";

thread_local! {
    static IMAGES: RefCell<HashMap<PathBuf, Image>> = RefCell::new(HashMap::new());
    // picker thumbnails (256 px): the full panoramas are only decoded for instances that use them
    static THUMBS: RefCell<HashMap<PathBuf, Image>> = RefCell::new(HashMap::new());
    // built-in choices (block textures, version panoramas); uploads are appended when published
    static BASE_ICONS: RefCell<Vec<Art>> = const { RefCell::new(Vec::new()) };
    static BASE_COVERS: RefCell<Vec<Art>> = const { RefCell::new(Vec::new()) };
}

/// Uploaded art: kind 0 icons, 1 wallpapers. Keys look like "custom:<file>".
fn custom_dir(sh: &Shared, kind: i32) -> PathBuf { sh.paths.root.join("custom").join(if kind == 0 { "icons" } else { "wallpapers" }) }

fn icon_path(sh: &Shared, key: &str) -> Option<PathBuf> {
    match key.strip_prefix("custom:") {
        Some(f) => Some(custom_dir(sh, 0).join(f)).filter(|p| p.exists()),
        None => art::block_icon_path(&sh.paths, key),
    }
}

/// Cover keys: "custom:<file>" (uploaded), "key:<family>" (official update key art), else a version (its panorama).
fn cover_path(sh: &Shared, key: &str) -> PathBuf {
    if let Some(f) = key.strip_prefix("custom:") { return custom_dir(sh, 1).join(f); }
    if let Some(f) = key.strip_prefix("key:") { return sh.paths.root.join("cache").join("art").join(format!("key-{f}.jpg")); }
    pano_path(sh, key)
}

/// An instance's cover when none was picked: its update's key art, else its version's panorama.
fn default_cover(ver: &str) -> String { art::key_family(ver).map(|f| format!("key:{f}")).unwrap_or_else(|| ver.to_string()) }

/// Fetch a cover key that isn't cached yet (network).
fn fetch_cover(sh: &Shared, key: &str) -> bool {
    let c = &client();
    match key.strip_prefix("key:") { Some(f) => art::key_art(c, &sh.paths, f).is_ok(), None => art::panorama(c, &sh.paths, key).is_ok() }
}

fn customs(sh: &Shared, kind: i32) -> Vec<Art> {
    let mut files: Vec<PathBuf> = std::fs::read_dir(custom_dir(sh, kind)).map(|r| r.flatten().map(|e| e.path()).collect()).unwrap_or_default();
    files.sort();
    files.iter().filter_map(|p| {
        let f = p.file_name()?.to_str()?.to_string();
        thumb(p).map(|i| Art { key: format!("custom:{f}").into(), label: "YOURS".into(), img: i })
    }).collect()
}

/// Publish built-in + uploaded choices to the pickers and the Settings page.
pub fn push_choices(ui: &AppWindow, sh: &Shared) {
    let b = ui.global::<Backend>();
    let (ci, cc) = (customs(sh, 0), customs(sh, 1));
    let mut icons = BASE_ICONS.with(|v| v.borrow().clone());
    icons.extend(ci.iter().cloned());
    let mut covers = BASE_COVERS.with(|v| v.borrow().clone());
    if covers.is_empty() { covers.push(Art { key: "".into(), label: "THIS VERSION".into(), img: Image::default() }); }
    covers.extend(cc.iter().cloned());
    b.set_icon_choices(ModelRc::new(VecModel::from(icons)));
    b.set_cover_choices(ModelRc::new(VecModel::from(covers)));
    b.set_custom_icons(ModelRc::new(VecModel::from(ci)));
    b.set_custom_covers(ModelRc::new(VecModel::from(cc)));
}

/// Pick an image file and add it as an icon (kind 0) or wallpaper (kind 1).
pub fn upload(ui: &AppWindow, sh: &Arc<Shared>, kind: i32) -> Result<(), String> {
    let Some(src) = rfd::FileDialog::new().add_filter("Images", &["png", "jpg", "jpeg", "webp", "gif"]).set_title(if kind == 0 { "Choose an icon image" } else { "Choose a wallpaper image" }).pick_file() else { return Ok(()) };
    let im = image::open(&src).map_err(|e| format!("Not an image this launcher can read ({e})"))?;
    let im = if kind == 0 {
        // small pixel art stays crisp, big images are scaled down to 64px
        if im.width().max(im.height()) > 64 { im.resize(64, 64, image::imageops::FilterType::Lanczos3) } else { im }
    } else if im.width() > 1280 { im.resize(1280, 1280, image::imageops::FilterType::Triangle) } else { im };
    let dir = custom_dir(sh, kind);
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
    im.save(dir.join(format!("{stamp}.png"))).map_err(|e| e.to_string())?;
    push_choices(ui, sh);
    Ok(())
}

/// Delete an uploaded icon or wallpaper; instances using it fall back to the default.
pub fn remove(ui: &AppWindow, sh: &Arc<Shared>, kind: i32, key: &str) {
    if let Some(f) = key.strip_prefix("custom:") {
        let p = custom_dir(sh, kind).join(f);
        IMAGES.with(|m| m.borrow_mut().remove(&p));
        let _ = std::fs::remove_file(p);
    }
    push_choices(ui, sh);
    refresh(ui, sh);
}

/// Decode an image file to at most `max` px on its longest side (call off the UI thread).
fn decode(p: &Path, max: u32) -> Option<SharedPixelBuffer<Rgba8Pixel>> {
    let im = image::open(p).ok()?;
    let im = if im.width().max(im.height()) > max { im.thumbnail(max, max) } else { im };
    let rgba = im.to_rgba8();
    Some(SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(rgba.as_raw(), rgba.width(), rgba.height()))
}

/// Small picker thumbnail of an image file (cached; uploads are small, so this decodes inline).
fn thumb(p: &Path) -> Option<Image> {
    THUMBS.with(|m| {
        if let Some(i) = m.borrow().get(p) { return Some(i.clone()); }
        let i = Image::from_rgba8(decode(p, 256)?);
        m.borrow_mut().insert(p.to_path_buf(), i.clone());
        Some(i)
    })
}

fn cached(p: &Path) -> Option<Image> { IMAGES.with(|m| m.borrow().get(p).cloned()) }

/// Load (once) an image file on the UI thread.
pub(crate) fn img(p: &Path) -> Option<Image> {
    IMAGES.with(|m| {
        if let Some(i) = m.borrow().get(p) { return Some(i.clone()); }
        let i = Image::load_from_path(p).ok()?;
        m.borrow_mut().insert(p.to_path_buf(), i.clone());
        Some(i)
    })
}

fn pano_path(sh: &Shared, version: &str) -> PathBuf { sh.paths.root.join("cache").join("art").join(format!("pano-{version}.png")) }

thread_local! {
    /// Play cards: version panoramas decoded to 512 px, and the versions being fetched / decoded right now
    static CARD_PANOS: RefCell<HashMap<String, Image>> = RefCell::new(HashMap::new());
    static CARD_BUSY: RefCell<std::collections::HashSet<String>> = RefCell::new(std::collections::HashSet::new());
}

/// A version's art for the Play cards: its update's official key art (minecraft.net), else its title panorama.
/// None while it downloads / decodes off the UI thread (`done` runs on the UI thread when it is ready).
pub fn card_art(ui: &AppWindow, sh: &Arc<Shared>, version: &str, done: fn(&AppWindow, &Arc<Shared>)) -> Option<Image> {
    if let Some(i) = CARD_PANOS.with(|m| m.borrow().get(version).cloned()) { return Some(i); }
    if !CARD_BUSY.with(|b| b.borrow_mut().insert(version.to_string())) { return None; }
    let (weak, sh, v) = (ui.as_weak(), sh.clone(), version.to_string());
    std::thread::spawn(move || {
        let c = client();
        let key = art::key_family(&v).and_then(|f| art::key_art(&c, &sh.paths, f).ok());
        let buf = match key {
            Some(p) => decode(&p, 1024),
            None => { let p = pano_path(&sh, &v); if p.exists() || art::panorama(&c, &sh.paths, &v).is_ok() { decode(&p, 512) } else { None } }
        };
        ui_do(&weak, move |ui| {
            CARD_BUSY.with(|b| b.borrow_mut().remove(&v));
            let Some(buf) = buf else { return };
            CARD_PANOS.with(|m| m.borrow_mut().insert(v, Image::from_rgba8(buf)));
            done(ui, &sh);
        });
    });
    None
}

/// Re-apply icon and cover images to every instance card; fetch covers that aren't cached yet.
pub fn refresh(ui: &AppWindow, sh: &Arc<Shared>) {
    let m = ui.global::<Backend>().get_instances();
    let mut missing = vec![];
    let mut undecoded: Vec<PathBuf> = vec![];
    for n in 0..m.row_count() {
        let Some(mut i) = m.row_data(n) else { continue };
        let icon = if i.icon.is_empty() { DEFAULT_ICON.to_string() } else { i.icon.to_string() };
        let cover = if i.cover.is_empty() { default_cover(&i.ver) } else { i.cover.to_string() };
        let icon_img = icon_path(sh, &icon).or_else(|| art::block_icon_path(&sh.paths, DEFAULT_ICON)).and_then(|p| img(&p));
        let p = cover_path(sh, &cover);
        // covers decode off the UI thread (a miss is filled in by the follow-up refresh below)
        let cp = if p.exists() { Some(p) } else if cover.starts_with("custom:") { Some(pano_path(sh, &i.ver)) } else { None };
        if cp.is_none() { missing.push(cover.clone()); }
        let cover_img = cp.and_then(|cp| { let c = cached(&cp); if c.is_none() && cp.exists() { undecoded.push(cp); } c });
        let changed = icon_img.is_some() != (i.icon_img.size().width > 0) || cover_img.is_some() != i.has_cover;
        if let Some(x) = icon_img { i.icon_img = x; }
        i.has_cover = cover_img.is_some();
        if let Some(x) = cover_img { i.cover_img = x; }
        if changed { m.set_row_data(n, i); }
    }
    undecoded.sort();
    undecoded.dedup();
    if !undecoded.is_empty() {
        let weak = ui.as_weak();
        let sh2 = sh.clone();
        std::thread::spawn(move || {
            let done: Vec<(PathBuf, SharedPixelBuffer<Rgba8Pixel>)> = undecoded.into_iter().filter_map(|p| decode(&p, 1600).map(|b| (p, b))).collect();
            if done.is_empty() { return; }
            ui_do(&weak, move |ui| {
                IMAGES.with(|m| { let mut m = m.borrow_mut(); for (p, b) in done { m.insert(p, Image::from_rgba8(b)); } });
                refresh(ui, &sh2);
            });
        });
    }
    missing.sort();
    missing.dedup();
    if missing.is_empty() { return; }
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let got = missing.iter().filter(|v| fetch_cover(&sh, v)).count();
        if got > 0 { ui_do(&weak, move |ui| refresh(ui, &sh)); }
    });
}

/// Startup: block icons from the newest release and the wallpaper choices (landmark versions + newest).
pub fn init(weak: slint::Weak<AppWindow>, sh: Arc<Shared>) {
    std::thread::spawn(move || {
        let c = client();
        let latest = nether_core::mojang::manifest(&c).ok()
            .and_then(|m| m.versions.iter().find(|v| v.kind == "release").map(|v| v.id.clone()))
            .unwrap_or_else(|| "1.21.4".into());
        let icons = art::block_icons(&c, &sh.paths, &latest).unwrap_or_default();
        // module icons for the Client page (same items the in-game menu shows)
        let _ = art::item_icons(&c, &sh.paths, &latest, &crate::client::icon_names());
        let (sh2, w2) = (sh.clone(), weak.clone());
        ui_do(&weak, move |ui| {
            let list: Vec<Art> = icons.iter().filter_map(|(k, p)| img(p).map(|i| Art { key: k.as_str().into(), label: k.replace('_', " ").into(), img: i })).collect();
            BASE_ICONS.with(|v| *v.borrow_mut() = list);
            push_choices(ui, &sh2);
            refresh(ui, &sh2);
        });
        let mut versions: Vec<String> = art::LANDMARKS.iter().map(|s| s.to_string()).collect();
        if !versions.contains(&latest) { versions.push(latest); }
        // identical panoramas (e.g. minor updates) are offered once
        let mut seen = std::collections::HashSet::new();
        let found: Vec<(String, SharedPixelBuffer<Rgba8Pixel>)> = versions.into_iter().filter_map(|v| {
            let p = art::panorama(&c, &sh.paths, &v).ok()?;
            let bytes = std::fs::read(&p).ok()?;
            if !seen.insert(bytes.len()) { return None; }
            let b = decode(&p, 256)?;
            Some((v, b))
        }).collect();
        ui_do(&w2, move |ui| {
            let mut list = vec![Art { key: "".into(), label: "THIS VERSION".into(), img: Image::default() }];
            list.extend(found.into_iter().map(|(v, b)| Art { key: v.as_str().into(), label: v.as_str().into(), img: Image::from_rgba8(b) }));
            BASE_COVERS.with(|v| *v.borrow_mut() = list);
            push_choices(ui, &sh);
        });
    });
}

/// Show the default cover of `version` (the create dialog's "this version" wallpaper): key art, else its panorama.
pub fn cover_for(ui: &AppWindow, sh: &Arc<Shared>, version: String) {
    let key = default_cover(&version);
    let p = cover_path(sh, &key);
    if p.exists() {
        if let Some(i) = img(&p) { ui.global::<Backend>().set_version_cover(i); }
        return;
    }
    ui.global::<Backend>().set_version_cover(Image::default());
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        if fetch_cover(&sh, &key) {
            ui_do(&weak, move |ui| if let Some(i) = img(&cover_path(&sh, &key)) { ui.global::<Backend>().set_version_cover(i) });
        }
    });
}

/// A web image (news art) downloaded once into the art cache and decoded to at most `max` px (call off the UI thread).
pub fn web_image(sh: &Shared, url: &str, max: u32) -> Option<SharedPixelBuffer<Rgba8Pixel>> {
    let name: String = url.rsplit('/').next()?.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '.').collect();
    if name.is_empty() { return None; }
    let p = sh.paths.root.join("cache").join("art").join(format!("web-{name}"));
    if !p.exists() {
        let bytes = client().get(url).send().ok()?.error_for_status().ok()?.bytes().ok()?;
        image::load_from_memory(&bytes).ok()?;
        std::fs::create_dir_all(p.parent()?).ok()?;
        std::fs::write(&p, &bytes).ok()?;
    }
    decode(&p, max)
}
