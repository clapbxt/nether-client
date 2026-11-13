//! Instance icons (block textures) and covers / wallpapers (version title panoramas) for the UI.
use std::cell::RefCell;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use nether_core::art;
use nether_core::download::client;
use slint::{ComponentHandle, Image, Model, ModelRc, VecModel};

use crate::{ui_do, AppWindow, Art, Backend, Shared};

/// Icon used when an instance has none picked.
pub const DEFAULT_ICON: &str = "grass_block_side";

thread_local! {
    static IMAGES: RefCell<HashMap<PathBuf, Image>> = RefCell::new(HashMap::new());
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

fn cover_path(sh: &Shared, key: &str) -> PathBuf {
    match key.strip_prefix("custom:") { Some(f) => custom_dir(sh, 1).join(f), None => pano_path(sh, key) }
}

fn customs(sh: &Shared, kind: i32) -> Vec<Art> {
    let mut files: Vec<PathBuf> = std::fs::read_dir(custom_dir(sh, kind)).map(|r| r.flatten().map(|e| e.path()).collect()).unwrap_or_default();
    files.sort();
    files.iter().filter_map(|p| {
        let f = p.file_name()?.to_str()?.to_string();
        img(p).map(|i| Art { key: format!("custom:{f}").into(), label: "YOURS".into(), img: i })
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

/// Re-apply icon and cover images to every instance card; fetch covers that aren't cached yet.
pub fn refresh(ui: &AppWindow, sh: &Arc<Shared>) {
    let m = ui.global::<Backend>().get_instances();
    let mut missing = vec![];
    for n in 0..m.row_count() {
        let Some(mut i) = m.row_data(n) else { continue };
        let icon = if i.icon.is_empty() { DEFAULT_ICON.to_string() } else { i.icon.to_string() };
        let cover = if i.cover.is_empty() { i.ver.to_string() } else { i.cover.to_string() };
        let icon_img = icon_path(sh, &icon).or_else(|| art::block_icon_path(&sh.paths, DEFAULT_ICON)).and_then(|p| img(&p));
        let p = cover_path(sh, &cover);
        let cover_img = if p.exists() { img(&p) } else if cover.starts_with("custom:") { img(&pano_path(sh, &i.ver)) } else { missing.push(cover.clone()); None };
        let changed = icon_img.is_some() != (i.icon_img.size().width > 0) || cover_img.is_some() != i.has_cover;
        if let Some(x) = icon_img { i.icon_img = x; }
        i.has_cover = cover_img.is_some();
        if let Some(x) = cover_img { i.cover_img = x; }
        if changed { m.set_row_data(n, i); }
    }
    missing.sort();
    missing.dedup();
    if missing.is_empty() { return; }
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let c = client();
        let got = missing.iter().filter(|v| art::panorama(&c, &sh.paths, v).is_ok()).count();
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
        let found: Vec<(String, PathBuf)> = versions.into_iter().filter_map(|v| {
            let p = art::panorama(&c, &sh.paths, &v).ok()?;
            let bytes = std::fs::read(&p).ok()?;
            seen.insert(bytes.len()).then_some((v, p))
        }).collect();
        ui_do(&w2, move |ui| {
            let mut list = vec![Art { key: "".into(), label: "THIS VERSION".into(), img: Image::default() }];
            list.extend(found.iter().filter_map(|(v, p)| img(p).map(|i| Art { key: v.as_str().into(), label: v.as_str().into(), img: i })));
            BASE_COVERS.with(|v| *v.borrow_mut() = list);
            push_choices(ui, &sh);
        });
    });
}

/// Show the panorama of `version` (the create dialog's "this version" wallpaper).
pub fn cover_for(ui: &AppWindow, sh: &Arc<Shared>, version: String) {
    let p = pano_path(sh, &version);
    if p.exists() {
        if let Some(i) = img(&p) { ui.global::<Backend>().set_version_cover(i); }
        return;
    }
    ui.global::<Backend>().set_version_cover(Image::default());
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        if let Ok(p) = art::panorama(&client(), &sh.paths, &version) {
            ui_do(&weak, move |ui| if let Some(i) = img(&p) { ui.global::<Backend>().set_version_cover(i) });
        }
    });
}
