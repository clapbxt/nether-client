//! Mod browsing glue: Modrinth search/installed/detail with icons and compatibility checks.
use std::collections::{HashMap, HashSet};
use std::hash::{Hash, Hasher};
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;

use nether_core::download::client;
use nether_core::instances;
use nether_core::launch::Loader;
use nether_core::modrinth;
use slint::{ComponentHandle, Image, Model, ModelRc, Rgba8Pixel, SharedPixelBuffer, VecModel};

use crate::{show_toast, ui_do, AppWindow, Backend, Detail, Inst, Link, MdBlock, Row, Shared, VerOpt};

/// Loader filter for Modrinth: only mods (kind 0) depend on the instance's loader.
fn loader_for(kind: i32, label: &str) -> Option<&'static str> {
    if kind == 0 { loader_name(label) } else { None }
}

fn project_type(kind: i32) -> &'static str {
    match kind { 1 => "resourcepack", 2 => "shader", _ => "mod" }
}

fn loader_name(label: &str) -> Option<&'static str> {
    Loader::from_label(label).modrinth()
}

pub(crate) fn short(v: &str, mc: &str) -> String {
    // pick the real mod version out of strings like "mc1.21.11-0.21.4-fabric" or "fabric-1.21.11-0.18.1"
    let seg: Vec<&str> = v.split(['-', '+', '_']).collect();
    for s in &seg {
        let s = s.trim_start_matches('v');
        if s.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false) && s.contains('.') && s != mc && !s.starts_with("1.21") && !s.starts_with("26.") {
            return s.to_string();
        }
    }
    v.split('+').next().unwrap_or(v).to_string()
}

pub(crate) fn count(n: u64) -> String {
    match n {
        n if n >= 1_000_000 => format!("{:.1}M", n as f64 / 1e6),
        n if n >= 1_000 => format!("{}K", n / 1000),
        n => n.to_string(),
    }
}

// ---- icons ----

pub(crate) struct Px(u32, u32, Vec<u8>);

/// Decoded icons by URL (process lifetime) so revisits never re-read or re-decode.
fn px_cache() -> &'static std::sync::Mutex<HashMap<String, Arc<Px>>> {
    static C: std::sync::OnceLock<std::sync::Mutex<HashMap<String, Arc<Px>>>> = std::sync::OnceLock::new();
    C.get_or_init(Default::default)
}

thread_local! {
    /// UI-thread Images by icon URL (slint Images are cheap to clone, not Send).
    static IMG: std::cell::RefCell<HashMap<String, Image>> = Default::default();
    /// Last fully resolved installed list per "instance|kind", shown instantly on revisit.
    static SNAP: std::cell::RefCell<HashMap<String, Vec<Row>>> = Default::default();
}

pub(crate) fn icon_px(sh: &Shared, url: &str) -> Option<Arc<Px>> {
    if url.is_empty() { return None; }
    if let Some(p) = px_cache().lock().unwrap().get(url) { return Some(p.clone()); }
    let p = Arc::new(fetch_px(sh, url, 64)?);
    px_cache().lock().unwrap().insert(url.to_string(), p.clone());
    Some(p)
}

/// UI thread: an already-decoded icon for `url`, if any.
pub(crate) fn cached_icon(url: &str) -> Option<Image> {
    if url.is_empty() { return None; }
    if let Some(i) = IMG.with(|m| m.borrow().get(url).cloned()) { return Some(i); }
    let p = px_cache().lock().unwrap().get(url).cloned()?;
    Some(icon_image(url, &p))
}

/// UI thread: turn decoded pixels into an Image, remembering it by URL.
pub(crate) fn icon_image(url: &str, p: &Px) -> Image {
    let img = to_image(p);
    IMG.with(|m| m.borrow_mut().insert(url.to_string(), img.clone()));
    img
}

/// sha512 by (path, size, mtime) so re-listing a mods folder doesn't re-hash every jar.
fn sha_cached(p: &PathBuf) -> Option<String> {
    type HashCache = std::sync::Mutex<HashMap<(PathBuf, u64, std::time::SystemTime), String>>;
    static C: std::sync::OnceLock<HashCache> = std::sync::OnceLock::new();
    let md = std::fs::metadata(p).ok()?;
    let key = (p.clone(), md.len(), md.modified().ok()?);
    let c = C.get_or_init(Default::default);
    if let Some(h) = c.lock().unwrap().get(&key) { return Some(h.clone()); }
    let h = modrinth::file_sha512(p).ok()?;
    c.lock().unwrap().insert(key, h.clone());
    Some(h)
}

fn snap_save(key: &str, m: &ModelRc<Row>) {
    let rows: Vec<Row> = m.iter().collect();
    SNAP.with(|s| s.borrow_mut().insert(key.to_string(), rows));
}

/// Download (cached) and decode an image, scaled to fit `max` px (first frame of animations).
fn fetch_px(sh: &Shared, url: &str, max: u32) -> Option<Px> {
    if url.is_empty() { return None; }
    let mut h = std::collections::hash_map::DefaultHasher::new();
    url.hash(&mut h);
    let dir = sh.paths.root.join("cache").join("icons");
    let file = dir.join(format!("{:x}", h.finish()));
    let bytes = match std::fs::read(&file) {
        Ok(b) => b,
        Err(_) => {
            // project bodies are written by mod authors: public https hosts only, 8 MB at most
            let b = nether_core::skins::get_public(url, 8 << 20).ok()?;
            let _ = std::fs::create_dir_all(&dir);
            static TRIM: std::sync::Once = std::sync::Once::new();
            TRIM.call_once(|| trim_cache(&dir, 200 << 20));
            let _ = std::fs::write(&file, &b);
            b
        }
    };
    let img = decode_limited(&bytes)?;
    let img = if img.width() > max || img.height() > max { img.thumbnail(max, max) } else { img }.to_rgba8();
    Some(Px(img.width(), img.height(), img.into_raw()))
}

/// Decode with limits: a small file that claims a huge picture is refused before it allocates.
fn decode_limited(b: &[u8]) -> Option<image::DynamicImage> {
    let mut r = image::ImageReader::new(std::io::Cursor::new(b)).with_guessed_format().ok()?;
    let mut l = image::Limits::default();
    l.max_image_width = Some(4096);
    l.max_image_height = Some(4096);
    l.max_alloc = Some(96 << 20);
    r.limits(l);
    r.decode().ok()
}

/// Keep the icon cache under `max` bytes (oldest files go first).
fn trim_cache(dir: &std::path::Path, max: u64) {
    let mut files: Vec<(std::time::SystemTime, u64, std::path::PathBuf)> = std::fs::read_dir(dir).into_iter().flatten().flatten()
        .filter_map(|e| { let m = e.metadata().ok()?; m.is_file().then(|| (m.modified().unwrap_or(std::time::UNIX_EPOCH), m.len(), e.path())) }).collect();
    let mut total: u64 = files.iter().map(|f| f.1).sum();
    files.sort();
    for (_, len, p) in files {
        if total <= max * 3 / 4 { break; }
        if std::fs::remove_file(&p).is_ok() { total -= len; }
    }
}

fn to_image(p: &Px) -> Image {
    Image::from_rgba8(SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(&p.2, p.0, p.1))
}

pub(crate) fn update_rows(m: &ModelRc<Row>, id: &str, f: &dyn Fn(&mut Row)) {
    for i in 0..m.row_count() {
        if let Some(mut r) = m.row_data(i) {
            if r.id == id {
                f(&mut r);
                m.set_row_data(i, r);
            }
        }
    }
}

// ---- rows ----

#[derive(Clone, Default)]
struct PRow {
    id: String,
    name: String,
    sub: String,
    meta: String,
    ver: String,
    mc: String,
    ok: i32,
    on: bool,
    upd: bool,
    vid: String,
    icon: String,
    path: String,
}

/// Text with Chinese / Japanese / Korean characters: Barlow has none, and the system's script fallback can pick a
/// Japanese face without simplified Chinese glyphs (boxes). Such text is drawn with a full CJK system font.
pub(crate) fn cjk(s: &str) -> bool {
    s.chars().any(|c| matches!(c as u32, 0x2E80..=0x9FFF | 0xAC00..=0xD7AF | 0xF900..=0xFAFF | 0xFF00..=0xFFEF | 0x20000..=0x2FA1F))
}

/// Drop emoji and pictographs (no launcher font draws them; they showed as boxes) and tidy the spaces left behind.
pub(crate) fn glyphs(s: &str) -> String {
    let t: String = s.chars().filter(|&c| !matches!(c as u32, 0x2190..=0x21FF | 0x2300..=0x23FF | 0x2460..=0x27BF | 0x2B00..=0x2BFF | 0xFE00..=0xFE0F | 0x200D | 0xE000..=0xF8FF | 0x1F000..=0x1FAFF)).collect();
    t.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn to_row(p: &PRow) -> Row {
    let icon = cached_icon(&p.icon);
    Row { has_icon: icon.is_some(), icon: icon.unwrap_or_default(), id: p.id.clone().into(), name: p.name.clone().into(), sub: p.sub.clone().into(), meta: p.meta.clone().into(), ver: p.ver.clone().into(), mc: p.mc.clone().into(), ok: p.ok, on: p.on, upd: p.upd, vid: p.vid.clone().into(), path: p.path.clone().into(), cjk: cjk(&p.name) || cjk(&p.sub), ..Default::default() }
}

fn fname(p: &std::path::Path) -> String {
    p.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string()
}

fn basic_row(p: &std::path::Path) -> PRow {
    let f = fname(p);
    let kb = std::fs::metadata(p).map(|m| m.len() / 1024).unwrap_or(0);
    PRow { name: f.trim_end_matches(".disabled").trim_end_matches(".jar").trim_end_matches(".zip").to_string(), sub: format!("{f} · {kb} KB"), on: !f.ends_with(".disabled"), path: p.to_string_lossy().to_string(), ..Default::default() }
}

// ---- installed list: search, picks for bulk actions, enable/disable by file ----

thread_local! {
    /// The whole installed list; the screen shows it filtered by the search text.
    static INSTALLED: std::cell::RefCell<Vec<Row>> = Default::default();
    static BROWSE_GEN: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

fn set_installed(ui: &AppWindow, rows: Vec<Row>) {
    INSTALLED.with(|i| *i.borrow_mut() = rows);
    filter_installed(ui);
}

/// Show the installed rows matching the search box (name or file name).
pub fn filter_installed(ui: &AppWindow) {
    let b = ui.global::<Backend>();
    let q = b.get_mod_query().trim().to_lowercase();
    let rows: Vec<Row> = INSTALLED.with(|i| i.borrow().iter().filter(|r| q.is_empty() || r.name.to_lowercase().contains(&q) || r.sub.to_lowercase().contains(&q)).cloned().collect());
    b.set_mod_picked(INSTALLED.with(|i| i.borrow().iter().filter(|r| r.sel).count()) as i32);
    b.set_installed_rows(ModelRc::new(VecModel::from(rows)));
}

/// Change one installed row (by file) in the full list and on screen.
fn edit_installed(ui: &AppWindow, path: &str, f: &dyn Fn(&mut Row)) {
    INSTALLED.with(|i| for r in i.borrow_mut().iter_mut().filter(|r| r.path == path) { f(r); });
    let m = ui.global::<Backend>().get_installed_rows();
    for n in 0..m.row_count() {
        if let Some(mut r) = m.row_data(n) { if r.path == path { f(&mut r); m.set_row_data(n, r); } }
    }
}

/// Pick / unpick a row for a bulk action.
pub fn pick(ui: &AppWindow, path: &str, on: bool) {
    edit_installed(ui, path, &|r| r.sel = on);
    ui.global::<Backend>().set_mod_picked(INSTALLED.with(|i| i.borrow().iter().filter(|r| r.sel).count()) as i32);
}

/// Enable / disable one installed file (rename to and from `.disabled`).
pub fn set_enabled(ui: &AppWindow, sh: &Arc<Shared>, path: &str, on: bool) {
    if path.is_empty() { return; }
    match instances::set_mod_enabled(&PathBuf::from(path), on) {
        Ok(np) => { let np = np.to_string_lossy().to_string(); edit_installed(ui, path, &|r| { r.on = on; r.path = np.as_str().into(); }); }
        Err(e) => { show_toast(ui, sh, &format!("Couldn't change {}: {e}", PathBuf::from(path).file_name().and_then(|n| n.to_str()).unwrap_or("the file"))); ui.global::<Backend>().invoke_refresh_installed(); }
    }
}

/// Bulk action on the picked rows: 0 enable, 1 disable, 2 remove (Recycle Bin), 3 pick every shown row, 4 pick none.
pub fn bulk(ui: &AppWindow, sh: &Arc<Shared>, a: i32) {
    let b = ui.global::<Backend>();
    if a == 3 || a == 4 {
        let shown: Vec<String> = b.get_installed_rows().iter().map(|r| r.path.to_string()).collect();
        for p in &shown { edit_installed(ui, p, &|r| r.sel = a == 3); }
        if a == 4 { INSTALLED.with(|i| for r in i.borrow_mut().iter_mut() { r.sel = false; }); filter_installed(ui); }
        b.set_mod_picked(INSTALLED.with(|i| i.borrow().iter().filter(|r| r.sel).count()) as i32);
        return;
    }
    let picked: Vec<String> = INSTALLED.with(|i| i.borrow().iter().filter(|r| r.sel).map(|r| r.path.to_string()).collect());
    if picked.is_empty() { return; }
    let n = picked.len();
    if a == 2 {
        let (sh, list) = (sh.clone(), picked);
        crate::instpage::ask(ui, &format!("Remove {n} file{}?", if n == 1 { "" } else { "s" }), "They go to the Recycle Bin, so you can still get them back from there.", "REMOVE", true, move |ui| {
            let failed = list.iter().filter(|p| nether_core::trash::recycle(std::path::Path::new(p)).is_err()).count();
            show_toast(ui, &sh, &if failed == 0 { format!("Moved {} to the Recycle Bin", list.len()) } else { format!("Couldn't remove {failed} of {}: close the game first", list.len()) });
            ui.global::<Backend>().invoke_refresh_installed();
        });
        return;
    }
    for p in &picked { set_enabled(ui, sh, p, a == 0); }
    show_toast(ui, sh, &format!("{} {n} file{}", if a == 0 { "Enabled" } else { "Disabled" }, if n == 1 { "" } else { "s" }));
}

/// Search Modrinth for the opened instance; icons and version badges stream in afterwards.
/// Sort indexes of the browse dropdown -> Modrinth search index.
const SORTS: [&str; 5] = ["relevance", "downloads", "follows", "newest", "updated"];

#[allow(clippy::too_many_arguments)]
pub fn spawn_browse(weak: slint::Weak<AppWindow>, sh: Arc<Shared>, ver: String, label: String, kind: i32, query: String, sort: i32, cat: String) {
    // a slower, older search never replaces a newer one
    let gen = BROWSE_GEN.with(|g| { g.set(g.get() + 1); g.get() });
    std::thread::spawn(move || {
        let ln = loader_for(kind, &label);
        match modrinth::search(&client(), query.trim(), project_type(kind), &ver, ln, SORTS[sort.clamp(0, 4) as usize], (!cat.is_empty()).then_some(cat.as_str())) {
            Err(e) => {
                let (sh2, msg) = (sh.clone(), format!("Modrinth search failed: {e}"));
                ui_do(&weak, move |ui| { if BROWSE_GEN.with(|g| g.get()) != gen { return; } ui.global::<Backend>().set_browse_loading(false); show_toast(ui, &sh2, &msg); });
            }
            Ok(hits) => {
                let prows: Vec<PRow> = hits.iter().map(|h| {
                    let d: String = h.desc.chars().take(90).collect();
                    PRow { id: h.id.clone(), name: h.title.clone(), sub: format!("{d} · by {}", h.author), meta: count(h.downloads), icon: h.icon_url.clone(), ..Default::default() }
                }).collect();
                ui_do(&weak, move |ui| {
                    if BROWSE_GEN.with(|g| g.get()) != gen { return; }
                    let b = ui.global::<Backend>();
                    b.set_browse_loading(false);
                    b.set_browse_rows(ModelRc::new(VecModel::from(prows.iter().map(to_row).collect::<Vec<_>>())));
                });
                // version chips + icons, 6 at a time
                let queue = Arc::new(std::sync::Mutex::new(hits.into_iter().collect::<std::collections::VecDeque<_>>()));
                for _ in 0..6 {
                    let (weak, sh, ver, queue) = (weak.clone(), sh.clone(), ver.clone(), queue.clone());
                    std::thread::spawn(move || while let Some(h) = queue.lock().ok().and_then(|mut q| q.pop_front()) {
                        let (weak, sh, ver) = (weak.clone(), sh.clone(), ver.clone());
                        let vs = modrinth::versions(&client(), &h.id, &ver, ln).unwrap_or_default();
                        let v = vs.first().cloned();
                        let labels: Vec<slint::SharedString> = vs.iter().map(|v| ver_label(v, &ver).into()).collect();
                        let ids: Vec<slint::SharedString> = vs.iter().map(|v| v.id.as_str().into()).collect();
                        let px = icon_px(&sh, &h.icon_url);
                        let url = h.icon_url.clone();
                        let _ = &sh;
                        ui_do(&weak, move |ui| {
                            if BROWSE_GEN.with(|g| g.get()) != gen { return; }
                            let img = px.as_ref().map(|p| cached_icon(&url).unwrap_or_else(|| icon_image(&url, p)));
                            let m = ui.global::<Backend>().get_browse_rows();
                            update_rows(&m, &h.id, &|r| {
                                match &v { Some(v) => { r.ver = format!("v{}", short(&v.number, &ver)).into(); r.mc = ver.clone().into(); r.ok = 1; } None => { r.mc = ver.clone().into(); r.ok = 2; } }
                                if let Some(i) = &img { r.icon = i.clone(); r.has_icon = true; }
                                r.vlabels = ModelRc::new(VecModel::from(labels.clone()));
                                r.vids = ModelRc::new(VecModel::from(ids.clone()));
                                r.pick = 0;
                            });
                        });
                    });
                }
            }
        }
    });
}

/// Rebuild the installed list for the opened instance: files first, then names/versions/icons/updates from Modrinth hashes.
pub fn refresh_installed(ui: &AppWindow, sh: &Arc<Shared>, insts: &Rc<VecModel<Inst>>) {
    let b = ui.global::<Backend>();
    let (idx, kind) = (b.get_current_inst(), b.get_pack_kind());
    let Some(i) = (idx >= 0).then(|| insts.row_data(idx as usize)).flatten() else { return };
    let files = instances::content_files(&sh.paths, &i.name, kind);
    let key = format!("{}|{kind}", i.name);
    // last resolved list (names, icons) when the folder is unchanged: no pop-in on revisit
    let names: Vec<String> = files.iter().map(|p| basic_row(p).sub).collect();
    let snap = SNAP.with(|s| s.borrow().get(&key).cloned()).filter(|r| r.len() == names.len() && r.iter().zip(&names).all(|(r, n)| r.sub.as_str() == n));
    match &snap {
        Some(rows) => set_installed(ui, rows.clone()),
        None => set_installed(ui, files.iter().map(|p| to_row(&basic_row(p))).collect::<Vec<_>>()),
    }
    b.set_update_count(snap.as_ref().map(|r| r.iter().filter(|x| x.upd && x.on).count()).unwrap_or(0) as i32);
    if kind == 0 && i.mods != files.len() as i32 { let mut n = i.clone(); n.mods = files.len() as i32; insts.set_row_data(idx as usize, n); crate::persist(&sh.paths, &**insts); }
    if files.is_empty() { return; }
    let (weak, sh, ver, label) = (ui.as_weak(), sh.clone(), i.ver.to_string(), i.loader.to_string());
    std::thread::spawn(move || {
        let (c, ln) = (client(), loader_for(kind, &label));
        let hashes: Vec<Option<String>> = files.iter().map(sha_cached).collect();
        let hs: Vec<String> = hashes.iter().flatten().cloned().collect();
        let known = modrinth::lookup(&c, &hs).unwrap_or_default();
        let latest = modrinth::updates(&c, &hs, &ver, ln).unwrap_or_default();
        let ids: Vec<String> = known.values().map(|v| v.project_id.clone()).collect::<HashSet<_>>().into_iter().collect();
        let projs = modrinth::projects(&c, &ids).unwrap_or_default();
        let rows: Vec<PRow> = files.iter().zip(&hashes).map(|(p, h)| {
            let mut r = basic_row(p);
            if let Some(v) = h.as_ref().and_then(|h| known.get(h)) {
                r.id = v.project_id.clone();
                if let Some((title, url)) = projs.get(&v.project_id) { r.name = title.clone(); r.icon = url.clone(); }
                r.ver = format!("v{}", short(&v.number, &ver));
                let mc_ok = v.game_versions.contains(&ver);
                r.mc = if mc_ok { ver.clone() } else { v.game_versions.last().cloned().unwrap_or_default() };
                let loader_ok = ln.map(|l| modrinth::loader_fits(l, &v.loaders)).unwrap_or(true);
                r.ok = if mc_ok && loader_ok { 1 } else { 2 };
                r.upd = h.as_ref().and_then(|h| latest.get(h)).map(|n| n.id != v.id).unwrap_or(false);
                r.vid = v.id.clone();
            }
            r
        }).collect();
        // decode icons up front (memory/disk cached, parallel for misses) so the list lands complete
        let urls: Vec<String> = projs.values().map(|(_, u)| u.clone()).filter(|u| !u.is_empty()).collect();
        let decoded: Vec<(String, Arc<Px>)> = std::thread::scope(|s| {
            let hs: Vec<_> = urls.chunks(urls.len().div_ceil(8).max(1)).map(|ch| { let sh = &sh; s.spawn(move || ch.iter().filter_map(|u| icon_px(sh, u).map(|p| (u.clone(), p))).collect::<Vec<_>>()) }).collect();
            hs.into_iter().flat_map(|h| h.join().unwrap_or_default()).collect()
        });
        ui_do(&weak, move |ui| {
            let b = ui.global::<Backend>();
            if b.get_current_inst() != idx || b.get_pack_kind() != kind { return; }
            for (u, p) in &decoded { if cached_icon(u).is_none() { icon_image(u, p); } }
            let picked: HashSet<String> = INSTALLED.with(|i| i.borrow().iter().filter(|r| r.sel).map(|r| r.path.to_string()).collect());
            let list: Vec<Row> = rows.iter().map(to_row).map(|mut r| { r.sel = picked.contains(r.path.as_str()); r }).collect();
            snap_save(&key, &ModelRc::new(VecModel::from(list.clone())));
            b.set_update_count(rows.iter().filter(|r| r.upd && r.on).count() as i32);
            set_installed(ui, list);
        });
    });
}

/// Update one installed project (`row`) or every enabled one with a newer compatible build (`row` < 0).
/// The new file replaces the old one only after it downloaded.
pub fn update(ui: &AppWindow, sh: &Arc<Shared>, insts: &Rc<VecModel<Inst>>, row: i32) {
    let b = ui.global::<Backend>();
    let Some(i) = (b.get_current_inst() >= 0).then(|| insts.row_data(b.get_current_inst() as usize)).flatten() else { return };
    let kind = b.get_pack_kind();
    let rows = b.get_installed_rows();
    // the row's own file, never the n-th file of a fresh folder listing (that may have changed since)
    let picked: Vec<Row> = if row >= 0 { rows.row_data(row as usize).into_iter().collect() } else { INSTALLED.with(|l| l.borrow().iter().filter(|r| r.upd && r.on).cloned().collect()) };
    // L1: changelogs first, then a snapshot-backed update with UNDO
    let cands: Vec<crate::snapshot::Cand> = picked.iter().filter(|r| !r.id.is_empty() && r.upd && !r.path.is_empty())
        .map(|r| crate::snapshot::Cand { name: r.name.to_string(), path: PathBuf::from(r.path.as_str()), from: r.ver.to_string() }).collect();
    crate::snapshot::open(ui, sh, &i.name, &i.ver, &i.loader, kind, cands);
}

pub(crate) fn title_case(s: &str) -> String {
    s.split(['-', '_']).map(|w| { let mut c = w.chars(); c.next().map(|f| f.to_uppercase().chain(c).collect::<String>()).unwrap_or_default() }).collect::<Vec<_>>().join(" ")
}

/// "2025-03-07" -> "Mar 7, 2025"
pub(crate) fn nice_date(d: &str) -> String {
    const M: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    let p: Vec<&str> = d.split('-').collect();
    match (p.first(), p.get(1).and_then(|m| m.parse::<usize>().ok()), p.get(2).and_then(|x| x.parse::<u32>().ok())) {
        (Some(y), Some(m), Some(day)) if (1..=12).contains(&m) => format!("{} {day}, {y}", M[m - 1]),
        _ => d.to_string(),
    }
}

pub(crate) fn environment(client: &str, server: &str) -> String {
    let (c, s) = (client != "unsupported", server != "unsupported");
    match (c, s) {
        (true, false) => "Client-side".into(),
        (false, true) => "Server-side".into(),
        _ if client == "required" && server == "required" => "Client and server".into(),
        _ => "Client or server".into(),
    }
}

/// Open the Modrinth-style detail page for a browse (src 0) or installed (src 1) row.
#[allow(clippy::field_reassign_with_default)]
pub fn open_detail(ui: &AppWindow, sh: &Arc<Shared>, insts: &Rc<VecModel<Inst>>, src: i32, row: i32) {
    let b = ui.global::<Backend>();
    let rows = if src == 0 { b.get_browse_rows() } else { b.get_installed_rows() };
    let Some(r) = rows.row_data(row as usize) else { return };
    let (idx, kind) = (b.get_current_inst(), b.get_pack_kind());
    let Some(i) = (idx >= 0).then(|| insts.row_data(idx as usize)).flatten() else { return };
    let (ver, label, inst_name) = (i.ver.to_string(), i.loader.to_string(), i.name.to_string());
    let installed_ids: HashSet<String> = b.get_installed_rows().iter().map(|x| x.id.to_string()).collect();

    let mut d = Detail::default();
    d.id = r.id.clone();
    d.title = r.name.clone();
    d.icon = r.icon.clone();
    d.has_icon = r.has_icon;
    d.loading = true;
    d.installed = src == 1 || installed_ids.contains(r.id.as_str());
    // an installed row: remember its version and file so the page can switch version or uninstall
    if src == 1 {
        d.cur_vid = r.vid.clone();
        d.file = r.path.clone();
    }
    d.mod_ver = r.ver.clone();
    d.mod_mc = r.mc.clone();
    d.mod_loader = label.clone().into();
    d.compat_ok = r.ok;
    b.set_detail(d);
    b.set_detail_pack(false);
    b.set_detail_open(true);

    if r.id.is_empty() {
        let mut d = b.get_detail();
        d.loading = false;
        d.summary = r.sub.clone();
        d.desc = "This file wasn't found on Modrinth, so there is no project page for it. It may be a custom, private or renamed mod.".into();
        d.compat_text = "Not on Modrinth, so it can't be checked against this instance.".into();
        d.compat_ok = 0;
        b.set_detail(d);
        return;
    }
    let (weak, sh) = (ui.as_weak(), sh.clone());
    let id = r.id.to_string();
    std::thread::spawn(move || {
        let ln = loader_for(kind, &label);
        let res = modrinth::detail(&client(), &id, &ver, ln);
        let vers = modrinth::versions(&client(), &id, &ver, ln).unwrap_or_default();
        // description parsed here, not on the UI thread; text from the internet must never take the launcher down
        let md_blocks: Vec<crate::md::Block> = res.as_ref().ok().map(|(det, _)| {
            let body = if det.body.is_empty() { det.description.clone() } else { det.body.clone() };
            std::panic::catch_unwind(move || crate::md::blocks(&body)).unwrap_or_default()
        }).unwrap_or_default();
        let loader_txt = label.clone();
        match res {
            Err(e) => {
                let msg = format!("Couldn't load details: {e}");
                ui_do(&weak, move |ui| { let b = ui.global::<Backend>(); let mut d = b.get_detail(); d.loading = false; d.desc = msg.into(); b.set_detail(d); });
            }
            Ok((det, compat)) => {
                let px = icon_px(&sh, &det.icon_url);
                let icon_url = det.icon_url.clone();
                // newest release versions first; the sidebar grid shows 12
                let rel: Vec<String> = det.mc_versions.iter().filter(|x| !x.contains('-') && !x.contains("snapshot") && !x.contains('w')).rev().cloned().collect();
                let more = if rel.len() > 12 { format!("+{} older versions", rel.len() - 12) } else { String::new() };
                let shown: Vec<String> = rel.into_iter().take(12).collect();
                let loaders: Vec<String> = det.loaders.iter().map(|l| title_case(l)).collect();
                let tags: Vec<String> = det.categories.iter().filter(|c| !det.loaders.contains(c)).take(4).map(|c| title_case(c)).collect();
                let desc = modrinth::plain(if det.body.is_empty() { &det.description } else { &det.body }, 6000);
                let env = environment(&det.client_side, &det.server_side);
                let (compat_ok, compat_text, mod_ver, mod_mc, size, date, can) = match &compat {
                    Some(v) => (1, format!("Ready for {inst_name} · {ver} · {loader_txt}"), format!("v{}", short(&v.number, &ver)), ver.clone(), format!("{} KB", v.size_kb), nice_date(&v.date), true),
                    None => (2, format!("No release for {ver} · {loader_txt}, so {inst_name} can't use it."), String::new(), ver.clone(), String::new(), String::new(), false),
                };
                let installed_text = format!("Installed in {inst_name}");
                ui_do(&weak, move |ui| {
                    let b = ui.global::<Backend>();
                    let mut d = b.get_detail();
                    if d.id != det.id { return; }
                    let strs = |v: &[String]| ModelRc::new(VecModel::from(v.iter().map(|s| s.as_str().into()).collect::<Vec<slint::SharedString>>()));
                    d.title = det.title.clone().into();
                    d.summary = det.description.clone().into();
                    d.desc = if desc.is_empty() { det.description.clone().into() } else { desc.into() };
                    d.author = det.author.clone().into();
                    d.downloads = count(det.downloads).into();
                    d.followers = count(det.followers).into();
                    d.tags = strs(&tags);
                    d.loaders = strs(&loaders);
                    d.versions = strs(&shown);
                    d.more_versions = more.into();
                    d.env = env.into();
                    d.license = if det.license.is_empty() { "Unknown".into() } else { det.license.clone().into() };
                    d.published = nice_date(&det.published).into();
                    d.updated = nice_date(&det.updated).into();
                    d.page_url = det.page_url.clone().into();
                    d.links = ModelRc::new(VecModel::from(det.links.iter().map(|(k, u)| Link { k: k.as_str().into(), url: u.as_str().into() }).collect::<Vec<_>>()));
                    // an installed row keeps its own file's version; otherwise show the newest build for this instance
                    if !d.installed || d.mod_ver.is_empty() {
                        d.mod_ver = mod_ver.into();
                        d.mod_mc = mod_mc.into();
                        d.mod_size = size.into();
                        d.mod_date = date.into();
                        d.compat_ok = compat_ok;
                        d.compat_text = compat_text.into();
                    } else {
                        d.compat_text = installed_text.into();
                    }
                    d.can_install = can;
                    d.loading = false;
                    if let Some(p) = &px { d.icon = cached_icon(&icon_url).unwrap_or_else(|| icon_image(&icon_url, p)); d.has_icon = true; }
                    // version picker: every build for this game version, newest first
                    d.vers = ModelRc::new(VecModel::from(vers.iter().map(|v| VerOpt { id: v.id.as_str().into(), label: "".into() }).collect::<Vec<_>>()));
                    d.vlabels = ModelRc::new(VecModel::from(vers.iter().map(|v| {
                        let tag = if v.kind == "release" { String::new() } else { format!(" · {}", v.kind.to_uppercase()) };
                        slint::SharedString::from(format!("v{}{tag} · {}", short(&v.number, &ver), nice_date(&v.date)))
                    }).collect::<Vec<_>>()));
                    d.pick = vers.iter().position(|v| v.id == d.cur_vid.as_str()).unwrap_or(0) as i32;
                    // description as rendered blocks; images arrive afterwards
                    d.cjk = cjk(&det.description) || cjk(&det.body);
                    d.blocks = ModelRc::new(VecModel::from(md_blocks.iter().map(to_block).collect::<Vec<_>>()));
                    d.gallery = ModelRc::new(VecModel::<Image>::default());
                    b.set_detail(d);
                    load_detail_images(ui.as_weak(), sh.clone(), det.id.clone(), md_blocks, det.gallery.clone());
                });
            }
        }
    });
}

/// Download a mod (and required dependencies) into the opened instance.
/// "v0.6.3 · BETA · Mar 7, 2025"
fn ver_label(v: &modrinth::VersionInfo, mc: &str) -> String {
    let tag = if v.kind == "release" { String::new() } else { format!(" · {}", v.kind.to_uppercase()) };
    format!("v{}{tag} · {}", short(&v.number, mc), nice_date(&v.date))
}

/// Show download progress on the browse row, the project page and the toast.
fn set_progress(ui: &AppWindow, id: &str, title: &str, busy: bool, f: f32) {
    let b = ui.global::<Backend>();
    update_rows(&b.get_browse_rows(), id, &|r| { r.busy = busy; r.prog = f; });
    let mut d = b.get_detail();
    if d.id == id { d.busy = busy; d.prog = f; b.set_detail(d); }
    let key = format!("install:{id}");
    if busy { crate::job(ui, &key, &format!("Installing {title}"), f); } else { crate::job_end(ui, &key); }
}

/// Download a project version (and required dependencies) into the opened instance, with live progress.
/// `replace` = the file of the version being switched away from (removed after a successful install).
#[allow(clippy::too_many_arguments)]
pub fn install(weak: slint::Weak<AppWindow>, sh: Arc<Shared>, id: String, version: Option<String>, title: String, ver: String, label: String, instance: String, kind: i32, replace: Option<String>) {
    if let Some(ui) = weak.upgrade() { set_progress(&ui, &id, &title, true, 0.0); }
    std::thread::spawn(move || {
        let dir = instances::content_dir(&sh.paths, &instance, kind);
        let last = std::sync::Mutex::new(std::time::Instant::now() - std::time::Duration::from_secs(1));
        let (w2, id2, t2) = (weak.clone(), id.clone(), title.clone());
        let prog = move |_file: &str, done: u64, total: u64| {
            let mut l = last.lock().unwrap();
            if l.elapsed().as_millis() < 60 && done < total { return; }
            *l = std::time::Instant::now();
            let f = if total > 0 { done as f32 / total as f32 } else { 0.5 };
            let (id, t) = (id2.clone(), t2.clone());
            ui_do(&w2, move |ui| set_progress(ui, &id, &t, true, f.min(1.0)));
        };
        let res = modrinth::install(&client(), &id, version.as_deref(), &ver, loader_for(kind, &label), &dir, &prog);
        // switching versions: drop the old file once the new one is in place
        if let (Ok(files), Some(old)) = (&res, &replace) {
            let old_name = std::path::Path::new(old).file_name().and_then(|n| n.to_str()).unwrap_or("").trim_end_matches(".disabled").to_string();
            if !files.is_empty() && !files.contains(&old_name) { let _ = nether_core::trash::recycle(std::path::Path::new(old)); }
        }
        let sh2 = sh.clone();
        ui_do(&weak, move |ui| {
            set_progress(ui, &id, &title, false, 1.0);
            let msg = match &res {
                Ok(f) if f.len() > 1 => format!("{title} installed (+{} required)", f.len() - 1),
                Ok(f) if f.is_empty() => format!("{title} is already installed"),
                Ok(_) if replace.is_some() => format!("{title} switched version"),
                Ok(_) => format!("{title} installed"),
                Err(e) => format!("Install failed: {e}"),
            };
            show_toast(ui, &sh2, &msg);
            if res.is_ok() {
                let b = ui.global::<Backend>();
                let mut d = b.get_detail();
                if d.id == id { d.installed = true; b.set_detail(d); }
                update_rows(&b.get_browse_rows(), &id, &|r| r.on = true);
                b.invoke_refresh_installed();
                if kind == 2 { if let Some(i) = b.get_instances().iter().find(|i| i.name == instance.as_str()) { crate::instpage::offer_iris(ui, &sh2, &i); } }
            }
        });
    });
}


pub(crate) fn to_block(b: &crate::md::Block) -> MdBlock {
    let text = if b.kind == 4 || b.kind == 5 || b.kind == 7 {
        slint::StyledText::default()
    } else {
        let md = if (1..=3).contains(&b.kind) { format!("**{}**", b.text) } else { b.text.clone() };
        slint::StyledText::from_markdown(&md).unwrap_or_else(|_| slint::StyledText::from_plain_text(&modrinth::plain(&b.text, 4000)))
    };
    MdBlock { kind: b.kind, text, plain: if b.kind == 4 { b.text.as_str().into() } else { modrinth::plain(&b.text, 4000).into() }, img: Image::default(), ratio: 0.0, has_img: false }
}

/// Fill in description images and the screenshot gallery for the open project (one thread per image).
pub(crate) fn load_detail_images(weak: slint::Weak<AppWindow>, sh: Arc<Shared>, id: String, blocks: Vec<crate::md::Block>, gallery: Vec<String>) {
    // one worker for the description and one for the gallery: at most two downloads / decodes at a time
    let images: Vec<(usize, String)> = blocks.into_iter().enumerate().filter(|(_, b)| b.kind == 5).map(|(i, b)| (i, b.img)).collect();
    {
        let (weak, sh, id) = (weak.clone(), sh.clone(), id.clone());
        std::thread::spawn(move || {
            for (i, url) in images {
                let Some(px) = fetch_px(&sh, &url, 900) else { continue };
                let id = id.clone();
                ui_do(&weak, move |ui| {
                    let d = ui.global::<Backend>().get_detail();
                    if d.id != id { return; }
                    if let Some(mut row) = d.blocks.row_data(i) {
                        row.ratio = px.1 as f32 / px.0.max(1) as f32;
                        row.img = to_image(&px);
                        row.has_img = true;
                        d.blocks.set_row_data(i, row);
                    }
                });
            }
        });
    }
    let (weak, sh) = (weak.clone(), sh.clone());
    std::thread::spawn(move || {
        for url in gallery {
            let Some(px) = fetch_px(&sh, &url, 640) else { continue };
            let id = id.clone();
            ui_do(&weak, move |ui| {
                let d = ui.global::<Backend>().get_detail();
                if d.id != id { return; }
                if let Some(m) = d.gallery.as_any().downcast_ref::<VecModel<Image>>() { m.push(to_image(&px)); }
            });
        }
    });
}
