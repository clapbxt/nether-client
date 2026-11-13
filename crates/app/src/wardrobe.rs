//! Wardrobe → Skins: the local skin library (nether_core::skins) as rendered tiles, previews on the 3D model,
//! and "Wear" (upload to the signed-in account). Tiles are rendered once by the software renderer and cached.
use std::cell::RefCell;
use std::path::PathBuf;
use std::sync::Arc;

use nether_core::download::client;
use nether_core::skins::{self, Model as SkinModel, Skin};
use slint::{ComponentHandle, Image, Model, ModelRc, Rgba8Pixel, SharedPixelBuffer, VecModel};

use crate::{show_toast, skin, ui_do, AppWindow, Shared, SkinLib, SkinTile};

type Then = Box<dyn FnOnce(&AppWindow) + Send>;

thread_local! {
    // id of the previewed tile (indices shift when the library changes)
    static SEL_ID: RefCell<Option<String>> = const { RefCell::new(None) };
}

fn thumb_file(sh: &Shared, id: &str, slim: bool) -> PathBuf {
    sh.paths.root.join("cache").join("skin-thumbs").join(format!("{id}-{}.png", if slim { "s" } else { "c" }))
}
fn worn_file(sh: &Shared) -> PathBuf { sh.paths.root.join("cache").join("worn-skin.txt") }

/// "user:Notch" -> "From Notch" etc.
fn source_label(src: &str) -> String {
    match src.split_once(':') {
        Some(("user", n)) => format!("From player {n}"),
        Some(("file", _)) => "Uploaded file".into(),
        Some(("account", n)) => format!("From your account ({n})"),
        Some(("url", u)) => format!("From {}", u.trim_start_matches("https://").split('/').next().unwrap_or("a link")),
        _ => String::new(),
    }
}

/// Tile image: cached PNG, or rendered once from the skin and cached. Worker thread.
fn tile_px(sh: &Shared, s: &Skin) -> Option<SharedPixelBuffer<Rgba8Pixel>> {
    let slim = s.model == SkinModel::Slim;
    let f = thumb_file(sh, &s.id, slim);
    let img = match image::open(&f) {
        Ok(i) => i.to_rgba8(),
        Err(_) => {
            let png = image::open(skins::png_path(&sh.paths, &s.id)).ok()?.to_rgba8();
            let t = skin::thumb(&png, slim);
            if let Some(d) = f.parent() { let _ = std::fs::create_dir_all(d); }
            let _ = t.save(&f);
            t
        }
    };
    Some(SharedPixelBuffer::clone_from_slice(img.as_raw(), img.width(), img.height()))
}

/// Re-read the library and publish tiles; keeps the preview on the same skin when it still exists.
fn refresh(weak: slint::Weak<AppWindow>, sh: Arc<Shared>, then: Option<Then>) {
    std::thread::spawn(move || {
        let list = skins::list(&sh.paths);
        let tiles: Vec<(Skin, Option<SharedPixelBuffer<Rgba8Pixel>>)> = list.into_iter().map(|s| { let px = tile_px(&sh, &s); (s, px) }).collect();
        let worn = std::fs::read_to_string(worn_file(&sh)).unwrap_or_default().trim().to_string();
        ui_do(&weak, move |ui| {
            let l = ui.global::<SkinLib>();
            let sel = SEL_ID.with(|s| s.borrow().clone());
            let rows: Vec<SkinTile> = tiles.iter().map(|(s, px)| SkinTile {
                id: s.id.as_str().into(), name: s.name.as_str().into(), slim: s.model == SkinModel::Slim,
                img: px.clone().map(Image::from_rgba8).unwrap_or_default(), has_img: px.is_some(), src: source_label(&s.source).into(),
            }).collect();
            let at = sel.as_ref().and_then(|id| tiles.iter().position(|(s, _)| &s.id == id));
            let mut names: Vec<slint::SharedString> = vec!["Account skin".into()];
            names.extend(tiles.iter().map(|(s, _)| slint::SharedString::from(s.name.as_str())));
            l.set_names(ModelRc::new(VecModel::from(names)));
            l.set_home_idx(tiles.iter().position(|(s, _)| s.id == worn).map_or(0, |i| i as i32 + 1));
            l.set_skins(ModelRc::new(VecModel::from(rows)));
            l.set_worn_id(worn.into());
            l.set_loaded(true);
            match at {
                Some(i) => l.set_sel(i as i32),
                None if sel.is_some() => { SEL_ID.with(|s| *s.borrow_mut() = None); skin::preview_account(ui); }
                None => {}
            }
            if let Some(f) = then { f(ui); }
        });
    });
}

fn tile(ui: &AppWindow, i: i32) -> Option<SkinTile> { ui.global::<SkinLib>().get_skins().row_data(i.max(0) as usize).filter(|_| i >= 0) }

/// Home dropdown row of the worn skin (0 = account skin / not in the library).
fn worn_row(ui: &AppWindow) -> i32 {
    let l = ui.global::<SkinLib>();
    let worn = l.get_worn_id();
    l.get_skins().iter().position(|t| t.id == worn).map_or(0, |r| r as i32 + 1)
}

fn select(ui: &AppWindow, sh: &Arc<Shared>, i: i32) {
    let Some(t) = tile(ui, i) else { return };
    if ui.global::<SkinLib>().get_sel() == i {
        SEL_ID.with(|s| *s.borrow_mut() = None);
        skin::preview_account(ui);
        return;
    }
    SEL_ID.with(|s| *s.borrow_mut() = Some(t.id.to_string()));
    ui.global::<SkinLib>().set_sel(i);
    let (weak, sh, id, slim) = (ui.as_weak(), sh.clone(), t.id.to_string(), t.slim);
    std::thread::spawn(move || {
        let img = image::open(skins::png_path(&sh.paths, &id)).map(|i| i.to_rgba8());
        ui_do(&weak, move |ui| match img {
            // still the selected one?
            Ok(img) if SEL_ID.with(|s| s.borrow().as_deref() == Some(id.as_str())) => skin::preview(ui, img, slim),
            Ok(_) => {}
            Err(e) => show_toast(ui, &sh, &format!("Couldn't read that skin: {e}")),
        });
    });
}

/// Upload a library skin to the signed-in account (Minecraft Services), then show it as the account's skin.
fn wear(ui: &AppWindow, sh: &Arc<Shared>, i: i32) {
    let Some(t) = tile(ui, i) else { return };
    let Some(session) = sh.session.lock().ok().and_then(|s| s.clone()).filter(|s| s.user_type == "msa") else {
        show_toast(ui, sh, "Sign in with Microsoft to wear skins on your account");
        ui.global::<SkinLib>().set_home_idx(worn_row(ui));
        return;
    };
    let l = ui.global::<SkinLib>();
    let home = worn_row(ui);
    l.set_wearing(true);
    let (weak, sh, id, name, slim) = (ui.as_weak(), sh.clone(), t.id.to_string(), t.name.to_string(), t.slim);
    std::thread::spawn(move || {
        let r = skins::apply(&client(), &sh.paths, &session.access_token, &id)
            .and_then(|_| Ok(image::open(skins::png_path(&sh.paths, &id))?.to_rgba8()));
        if r.is_ok() {
            if let Some(d) = worn_file(&sh).parent() { let _ = std::fs::create_dir_all(d); }
            let _ = std::fs::write(worn_file(&sh), &id);
        }
        ui_do(&weak, move |ui| {
            let l = ui.global::<SkinLib>();
            l.set_wearing(false);
            match r {
                Ok(img) => {
                    skin::set_account(ui, img, slim);
                    l.set_home_idx(i + 1);
                    l.set_worn_id(id.into());
                    show_toast(ui, &sh, &format!("You're now wearing {name}"));
                }
                Err(e) => { l.set_home_idx(home); show_toast(ui, &sh, &format!("Couldn't wear {name}: {e:#}")); }
            }
        });
    });
}

/// Run `f` on a worker with the library marked busy; toast its message, then refresh.
fn busy(ui: &AppWindow, sh: &Arc<Shared>, f: impl FnOnce(&Shared) -> Option<Result<String, String>> + Send + 'static) {
    ui.global::<SkinLib>().set_busy(true);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let r = f(&sh);
        let ok = matches!(r, Some(Ok(_)));
        let sh2 = sh.clone();
        ui_do(&weak, move |ui| {
            ui.global::<SkinLib>().set_busy(false);
            match &r { Some(Ok(m)) | Some(Err(m)) => show_toast(ui, &sh2, m), None => {} }
        });
        if ok { refresh(weak, sh, None); }
    });
}

pub fn init(ui: &AppWindow, sh: Arc<Shared>) {
    let l = ui.global::<SkinLib>();
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        l.on_refresh(move || refresh(weak.clone(), sh.clone(), None));
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        l.on_select(move |i| if let Some(ui) = weak.upgrade() { select(&ui, &sh, i) });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        l.on_wear(move |i| if let Some(ui) = weak.upgrade() { wear(&ui, &sh, i) });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        l.on_add_file(move || {
            let Some(ui) = weak.upgrade() else { return };
            busy(&ui, &sh, |sh| {
                let f = rfd::FileDialog::new().add_filter("Skin image", &["png"]).set_title("Choose a skin (64×64 PNG)").pick_file()?;
                Some(skins::add_file(&sh.paths, &f, None, None).map(|s| format!("Added {}", s.name)).map_err(|e| format!("Couldn't add that skin: {e:#}")))
            });
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        l.on_import(move |text| {
            let Some(ui) = weak.upgrade() else { return };
            let t = text.trim().to_string();
            if t.is_empty() { return; }
            busy(&ui, &sh, move |sh| {
                let r = if t.contains("://") { skins::import_url(&sh.paths, &t, None) } else { skins::import_username(&client(), &sh.paths, &t) };
                Some(r.map(|s| format!("Imported {}", s.name)).map_err(|e| format!("Couldn't import: {e:#}")))
            });
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        l.on_rename(move |i, name| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(t) = tile(&ui, i) else { return };
            let (id, name) = (t.id.to_string(), name.to_string());
            busy(&ui, &sh, move |sh| Some(skins::rename(&sh.paths, &id, &name).map(|_| format!("Renamed to {}", name.trim())).map_err(|e| format!("Couldn't rename: {e:#}"))));
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        l.on_set_slim(move |i, slim| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(t) = tile(&ui, i) else { return };
            if t.slim == slim { return; }
            let id = t.id.to_string();
            let (weak, sh) = (weak.clone(), sh.clone());
            std::thread::spawn(move || {
                match skins::set_model(&sh.paths, &id, if slim { SkinModel::Slim } else { SkinModel::Classic }) {
                    Ok(()) => refresh(weak, sh, None),
                    Err(e) => ui_do(&weak, move |ui| show_toast(ui, &sh, &format!("Couldn't change the arm style: {e:#}"))),
                }
            });
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        l.on_export(move |i| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(t) = tile(&ui, i) else { return };
            let (id, name) = (t.id.to_string(), t.name.to_string());
            let (weak, sh) = (weak.clone(), sh.clone());
            std::thread::spawn(move || {
                let safe: String = name.chars().map(|c| if c.is_alphanumeric() || c == '-' || c == '_' || c == ' ' { c } else { '_' }).collect();
                let Some(dest) = rfd::FileDialog::new().add_filter("Skin image", &["png"]).set_file_name(format!("{safe}.png")).set_title("Save skin").save_file() else { return };
                let r = skins::export(&sh.paths, &id, &dest);
                ui_do(&weak, move |ui| match r {
                    Ok(p) => show_toast(ui, &sh, &format!("Saved {}", p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default())),
                    Err(e) => show_toast(ui, &sh, &format!("Couldn't save the skin: {e:#}")),
                });
            });
        });
    }
    {
        let (weak, sh) = (ui.as_weak(), sh.clone());
        l.on_delete(move |i| {
            let Some(ui) = weak.upgrade() else { return };
            let Some(t) = tile(&ui, i) else { return };
            let (id, name) = (t.id.to_string(), t.name.to_string());
            if SEL_ID.with(|s| s.borrow().as_deref() == Some(id.as_str())) {
                SEL_ID.with(|s| *s.borrow_mut() = None);
                skin::preview_account(&ui);
            }
            busy(&ui, &sh, move |sh| {
                let r = skins::delete(&sh.paths, &id);
                if r.is_ok() { for slim in [false, true] { let _ = std::fs::remove_file(thumb_file(sh, &id, slim)); } }
                Some(r.map(|_| format!("{name} deleted")).map_err(|e| format!("Couldn't delete: {e:#}")))
            });
        });
    }
}

/// Dev snapshots only (`--snapshot` with NC_SNAP_STATE "ward-*"): NC_SNAP_IMPORT=Notch,jeb_ imports those players'
/// skins into the library (use a temporary NETHER_HOME); "ward-sel" previews the first tile.
pub fn snap(ui: &AppWindow, sh: &Arc<Shared>, state: &str) {
    if !state.starts_with("ward") { return; }
    if state == "ward-capes" { ui.set_ward_page(1); }
    let names: Vec<String> = std::env::var("NC_SNAP_IMPORT").unwrap_or_default().split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect();
    let (weak, sh, sel) = (ui.as_weak(), sh.clone(), state == "ward-sel");
    std::thread::spawn(move || {
        for n in names.iter().rev() {
            if let Err(e) = skins::import_username(&client(), &sh.paths, n) { eprintln!("import {n}: {e:#}"); }
            std::thread::sleep(std::time::Duration::from_millis(1100)); // distinct `added` seconds keep the order
        }
        let then: Option<Then> = sel.then(|| Box::new(|ui: &AppWindow| ui.global::<SkinLib>().invoke_select(0)) as Then);
        refresh(weak, sh, then);
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::RgbaImage;

    #[test]
    fn labels() {
        assert_eq!(source_label("user:Notch"), "From player Notch");
        assert_eq!(source_label("url:https://example.com/a/b.png"), "From example.com");
        assert_eq!(source_label("file:me.png"), "Uploaded file");
    }

    #[test]
    fn thumb_is_drawn() {
        let mut s = RgbaImage::new(64, 64);
        for p in s.pixels_mut() { *p = image::Rgba([200, 40, 40, 255]); }
        let t = skin::thumb(&s, false);
        assert_eq!(t.dimensions(), (230, 360));
        assert!(t.pixels().any(|p| p[3] > 0));
    }
}
