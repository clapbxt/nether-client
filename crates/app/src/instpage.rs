//! Instance page extras: Worlds tab (Quick Play, backups, saved servers), Logs tab (view + share link),
//! crash report dialog, import from another launcher, and stopping a running game.
use std::sync::Arc;

use nether_core::{crash, import, launch::QuickPlay, worlds, Progress};
use slint::{ComponentHandle, Image, Model, ModelRc, Rgba8Pixel, SharedPixelBuffer, VecModel};

use crate::{show_toast, ui_do, AppWindow, Backend, CrashItem, CrashProblem, Inst, Shared, Shot, WorldRow};

fn inst_at(ui: &AppWindow, idx: i32) -> Option<Inst> { ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) }

/// Quick Play into worlds exists from 1.20 (and every year-numbered 26.x release).
pub fn quick_play_ok(ver: &str) -> bool {
    let mut it = ver.split(['.', '-', ' ']).map(|p| p.parse::<u32>().unwrap_or(0));
    match (it.next().unwrap_or(0), it.next().unwrap_or(0)) {
        (1, minor) => minor >= 20,
        (major, _) => major >= 20,
    }
}

fn ago_ms(ms: u64) -> String { if ms == 0 { "never played".into() } else { crate::ago((ms / 1000) as i64).to_lowercase() } }

pub(crate) fn size(b: u64) -> String {
    match b {
        b if b >= 1 << 30 => format!("{:.1} GB", b as f64 / (1u64 << 30) as f64),
        b if b >= 1 << 20 => format!("{} MB", b >> 20),
        b => format!("{} KB", (b >> 10).max(1)),
    }
}

struct Px(u32, u32, Vec<u8>);

fn decode(bytes: &[u8]) -> Option<Px> {
    let img = image::load_from_memory(bytes).ok()?.thumbnail(64, 64).to_rgba8();
    Some(Px(img.width(), img.height(), img.into_raw()))
}

fn to_image(p: &Option<Px>) -> (Image, bool) {
    match p {
        Some(p) => (Image::from_rgba8(SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(&p.2, p.0, p.1)), true),
        None => (Image::default(), false),
    }
}

fn b64(s: &str) -> Option<Vec<u8>> {
    // servers.dat icons are plain base64 PNGs (sometimes with a data: prefix)
    const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let s = s.rsplit(',').next()?;
    let (mut out, mut buf, mut bits) = (Vec::with_capacity(s.len() * 3 / 4), 0u32, 0u32);
    for c in s.bytes().filter(|c| !c.is_ascii_whitespace() && *c != b'=') {
        buf = (buf << 6) | T.iter().position(|t| *t == c)? as u32;
        bits += 6;
        if bits >= 8 { bits -= 8; out.push((buf >> bits) as u8); }
    }
    Some(out)
}

thread_local! {
    /// Bumped on every Worlds / Screenshots load: results of an older load are dropped.
    static WORLDS_GEN: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
    static SHOTS_GEN: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
    /// Every world/server row of the opened instance (the shown list is this, filtered by the search box).
    static ALL_WORLDS: std::cell::RefCell<Vec<WorldRow>> = Default::default();
    /// What happens when the yes/no dialog is answered "yes".
    static ASK: std::cell::RefCell<Option<crate::UiAction>> = Default::default();
}

/// Open the yes/no dialog; `yes` runs on the UI thread if the user confirms.
pub fn ask(ui: &AppWindow, title: &str, text: &str, yes_label: &str, danger: bool, yes: impl FnOnce(&AppWindow) + 'static) {
    let b = ui.global::<Backend>();
    b.set_ask_title(title.into());
    b.set_ask_text(text.into());
    b.set_ask_yes(yes_label.into());
    b.set_ask_danger(danger);
    ASK.with(|a| *a.borrow_mut() = Some(Box::new(yes)));
    b.set_ask_open(true);
}

pub fn ask_answer(ui: &AppWindow, yes: bool) {
    ui.global::<Backend>().set_ask_open(false);
    if let Some(f) = ASK.with(|a| a.borrow_mut().take()) { if yes { f(ui); } }
}

/// Show the rows whose name or address contains the search text.
pub fn filter_worlds(ui: &AppWindow) {
    let b = ui.global::<Backend>();
    let q = b.get_world_query().to_lowercase();
    let v: Vec<WorldRow> = ALL_WORLDS.with(|a| a.borrow().iter().filter(|w| q.is_empty() || w.name.to_lowercase().contains(&q) || w.folder.to_lowercase().contains(&q)).cloned().collect());
    b.set_worlds(ModelRc::new(VecModel::from(v)));
}

/// Fill the Worlds tab for the opened instance (read on a worker thread: world sizes can take a moment),
/// then ping every saved server and fill in players / latency as answers come back.
pub fn load_worlds(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    let Some(i) = inst_at(ui, b.get_current_inst()) else { return };
    b.set_worlds_quick(quick_play_ok(&i.ver));
    let gen = WORLDS_GEN.with(|g| { g.set(g.get() + 1); g.get() });
    let (weak, sh, name) = (ui.as_weak(), sh.clone(), i.name.to_string());
    std::thread::spawn(move || {
        let mut rows: Vec<(String, String, String, Option<Px>, bool, bool)> = worlds::list(&sh.paths, &name).into_iter().map(|w| {
            let px = w.icon.as_ref().and_then(|p| std::fs::read(p).ok()).and_then(|b| decode(&b));
            let ver = if w.version.is_empty() { String::new() } else { format!(" · {}", w.version) };
            let sub = format!("{}{}{} · {} · {}", if w.hardcore { "Hardcore " } else { "" }, w.game_mode, ver, ago_ms(w.last_played), size(w.size_bytes));
            (w.folder, w.name, sub, px, w.hardcore, false)
        }).collect();
        let servers = worlds::servers(&sh.paths, &name);
        rows.extend(servers.iter().map(|(n, ip, icon)| {
            let px = b64(icon).and_then(|b| decode(&b));
            (ip.clone(), n.clone(), format!("Server · {ip}"), px, false, true)
        }));
        ui_do(&weak, move |ui| {
            if WORLDS_GEN.with(|g| g.get()) != gen { return; }
            let v: Vec<WorldRow> = rows.iter().map(|(folder, name, sub, px, hc, server)| {
                let (icon, has) = to_image(px);
                let status = if *server { "Pinging…".into() } else { Default::default() };
                WorldRow { folder: folder.as_str().into(), name: name.as_str().into(), sub: sub.as_str().into(), icon, has_icon: has, hardcore: *hc, server: *server, status, online: false }
            }).collect();
            ALL_WORLDS.with(|a| *a.borrow_mut() = v);
            filter_worlds(ui);
        });
        // server status, all at once (each times out after 3 s); at most the first 24 saved servers
        for (_, ip, _) in servers.into_iter().take(24) {
            let weak = weak.clone();
            std::thread::spawn(move || {
                let (status, online, motd) = match nether_core::net::ping_server(&ip) {
                    Ok(s) => (format!("{}/{} · {} ms", s.online, s.max, s.ping_ms), true, s.motd),
                    Err(_) => ("Offline".to_string(), false, String::new()),
                };
                ui_do(&weak, move |ui| {
                    if WORLDS_GEN.with(|g| g.get()) != gen { return; }
                    ALL_WORLDS.with(|a| for w in a.borrow_mut().iter_mut().filter(|w| w.server && w.folder == ip.as_str()) {
                        w.status = status.as_str().into();
                        w.online = online;
                        if !motd.is_empty() { w.sub = format!("{ip} · {motd}").into(); }
                    });
                    filter_worlds(ui);
                });
            });
        }
    });
}

/// Pick a world folder (opens in .minecraft/saves) and copy it into the instance.
pub fn import_world(ui: &AppWindow, sh: &Arc<Shared>, idx: i32) {
    let Some(i) = inst_at(ui, idx) else { return };
    let start = std::env::var("APPDATA").map(|a| std::path::PathBuf::from(a).join(".minecraft").join("saves")).ok().filter(|p| p.is_dir());
    let mut dlg = rfd::FileDialog::new().set_title("Pick a world folder to import");
    if let Some(s) = start { dlg = dlg.set_directory(s); }
    let Some(dir) = dlg.pick_folder() else { return };
    crate::job(ui, "import-world", "Importing world…", -1.0);
    let (weak, sh, name) = (ui.as_weak(), sh.clone(), i.name.to_string());
    std::thread::spawn(move || {
        let res = worlds::import_world(&sh.paths, &name, &dir);
        let sh2 = sh.clone();
        ui_do(&weak, move |ui| {
            crate::job_end(ui, "import-world");
            match res {
                Ok(f) => show_toast(ui, &sh2, &format!("Imported {f} into {name}")),
                Err(e) => show_toast(ui, &sh2, &format!("Couldn't import the world: {e}")),
            }
            load_worlds(ui, &sh2);
        });
    });
}

/// Start an instance with Quick Play (a world or a server).
pub(crate) fn play_into(ui: &AppWindow, sh: &Arc<Shared>, i: &Inst, q: QuickPlay) {
    if i.running { show_toast(ui, sh, &format!("{} is already running", i.name)); return; }
    crate::start_launch(ui.as_weak(), sh.clone(), i.name.to_string(), i.ver.to_string(), i.ram, i.loader.to_string(), crate::JAVAS[i.java.clamp(0, 4) as usize], i.shared, Some(q), true);
}

/// Worlds tab row action: 0 play / join, 1 back up, 2 open the world folder, 3 delete (asks first), 4 copy server address.
pub fn world_action(ui: &AppWindow, sh: &Arc<Shared>, idx: i32, row: i32, action: i32) {
    let Some(i) = inst_at(ui, idx) else { return };
    let Some(w) = ui.global::<Backend>().get_worlds().row_data(row.max(0) as usize) else { return };
    match action {
        0 => play_into(ui, sh, &i, if w.server { QuickPlay::Server(w.folder.to_string()) } else { QuickPlay::World(w.folder.to_string()) }),
        1 => {
            let key = format!("backup:{}:{}", i.name, w.folder);
            crate::job(ui, &key, &format!("Backing up {}…", w.name), -1.0);
            let (weak, sh, name, folder, title) = (ui.as_weak(), sh.clone(), i.name.to_string(), w.folder.to_string(), w.name.to_string());
            std::thread::spawn(move || {
                let res = worlds::backup_world(&sh.paths, &name, &folder);
                let sh2 = sh.clone();
                ui_do(&weak, move |ui| match crate::job_done(ui, &key, res) {
                    Ok(p) => show_toast(ui, &sh2, &format!("{title} backed up to {}", p.file_name().and_then(|n| n.to_str()).unwrap_or("backups"))),
                    Err(e) => show_toast(ui, &sh2, &format!("Couldn't back up {title}: {e}")),
                });
            });
        }
        3 => {
            if i.running || i.starting { show_toast(ui, sh, &format!("Close {} before deleting a world", i.name)); return; }
            let (sh, dir, title) = (sh.clone(), sh.paths.instance(&i.name).join("saves").join(w.folder.as_str()), w.name.to_string());
            ask(ui, &format!("Delete {title}?"), "The world goes to the Recycle Bin, so you can still get it back from there.", "DELETE WORLD", true, move |ui| {
                let (weak, sh) = (ui.as_weak(), sh.clone());
                std::thread::spawn(move || {
                    let res = nether_core::trash::recycle(&dir);
                    ui_do(&weak, move |ui| {
                        match res {
                            Ok(()) => show_toast(ui, &sh, &format!("{title} moved to the Recycle Bin")),
                            Err(e) => show_toast(ui, &sh, &format!("Couldn't delete {title}: {e}")),
                        }
                        load_worlds(ui, &sh);
                    });
                });
            });
        }
        4 => {
            if let Ok(mut c) = arboard::Clipboard::new() { let _ = c.set_text(w.folder.to_string()); }
            show_toast(ui, sh, &format!("Copied {}", w.folder));
        }
        _ => { let _ = std::process::Command::new("explorer").arg(sh.paths.instance(&i.name).join("saves").join(w.folder.as_str())).spawn(); }
    }
}

thread_local! {
    static SHOT_PATHS: std::cell::RefCell<Vec<std::path::PathBuf>> = Default::default();
}

/// Screenshots tab: thumbnails, newest first (decoded on a worker thread).
pub fn load_shots(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some(i) = inst_at(ui, ui.global::<Backend>().get_current_inst()) else { return };
    let gen = SHOTS_GEN.with(|g| { g.set(g.get() + 1); g.get() });
    let (weak, sh, name) = (ui.as_weak(), sh.clone(), i.name.to_string());
    std::thread::spawn(move || {
        let files = worlds::screenshots(&sh.paths, &name);
        let thumbs: Vec<(std::path::PathBuf, String, Option<Px>)> = files.into_iter().take(200).map(|p| {
            let px = image::open(&p).ok().map(|im| { let t = im.thumbnail(640, 360).to_rgba8(); Px(t.width(), t.height(), t.into_raw()) });
            let secs = std::fs::metadata(&p).and_then(|m| m.modified()).ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map(|d| d.as_secs() as i64).unwrap_or(0);
            (p, crate::ago(secs), px)
        }).collect();
        ui_do(&weak, move |ui| {
            if SHOTS_GEN.with(|g| g.get()) != gen { return; }
            let rows: Vec<Shot> = thumbs.iter().map(|(p, d, px)| Shot { path: p.to_string_lossy().to_string().into(), date: d.as_str().into(), img: to_image(px).0 }).collect();
            SHOT_PATHS.with(|s| *s.borrow_mut() = thumbs.into_iter().map(|t| t.0).collect());
            ui.global::<Backend>().set_shots(ModelRc::new(VecModel::from(rows)));
        });
    });
}

/// Screenshot action: 0 open, 1 copy the image, 2 show in folder (-1 = open the folder), 3 delete (asks first).
pub fn shot_action(ui: &AppWindow, sh: &Arc<Shared>, row: i32, a: i32) {
    let Some(i) = inst_at(ui, ui.global::<Backend>().get_current_inst()) else { return };
    let dir = sh.paths.instance(&i.name).join("screenshots");
    let Some(p) = SHOT_PATHS.with(|s| s.borrow().get(row.max(0) as usize).cloned()).filter(|_| row >= 0) else {
        let _ = std::fs::create_dir_all(&dir);
        let _ = std::process::Command::new("explorer").arg(&dir).spawn();
        return;
    };
    match a {
        0 => { let _ = std::process::Command::new("explorer").arg(&p).spawn(); }
        1 => {
            let res = image::open(&p).map_err(anyhow::Error::from).and_then(|im| {
                let rgba = im.to_rgba8();
                let img = arboard::ImageData { width: rgba.width() as usize, height: rgba.height() as usize, bytes: std::borrow::Cow::Owned(rgba.into_raw()) };
                arboard::Clipboard::new()?.set_image(img)?;
                Ok(())
            });
            match res { Ok(()) => show_toast(ui, sh, "Screenshot copied"), Err(e) => show_toast(ui, sh, &format!("Couldn't copy the screenshot: {e}")) }
        }
        2 => { let _ = std::process::Command::new("explorer").arg(format!("/select,{}", p.display())).spawn(); }
        _ => {
            let sh = sh.clone();
            let file = p.file_name().and_then(|n| n.to_str()).unwrap_or("screenshot").to_string();
            ask(ui, "Delete screenshot?", &format!("{file} goes to the Recycle Bin."), "DELETE", true, move |ui| {
                match nether_core::trash::recycle(&p) { Ok(()) => show_toast(ui, &sh, "Screenshot moved to the Recycle Bin"), Err(e) => show_toast(ui, &sh, &format!("Couldn't delete it: {e}")) }
                load_shots(ui, &sh);
            });
        }
    }
}

pub fn join_server(ui: &AppWindow, sh: &Arc<Shared>, idx: i32, addr: &str) {
    let addr = addr.trim().trim_start_matches("minecraft://").to_string();
    if addr.is_empty() || addr.contains(char::is_whitespace) { show_toast(ui, sh, "That doesn't look like a server address"); return; }
    if let Some(i) = inst_at(ui, idx) { play_into(ui, sh, &i, QuickPlay::Server(addr)); }
}

fn log_path(sh: &Shared, name: &str, kind: i32) -> Option<std::path::PathBuf> {
    let l = crash::find_logs(&sh.paths, name);
    match kind { 1 => l.launcher_log, 2 => l.crash_report.or(l.hs_err), _ => l.latest_log }
}

/// Logs tab: show the last lines of the chosen log (read and filtered on a worker; an older read never wins).
pub fn load_log(ui: &AppWindow, sh: &Arc<Shared>, kind: i32) {
    let b = ui.global::<Backend>();
    let Some(i) = inst_at(ui, b.get_current_inst()) else { return };
    let gen = LOG_GEN.with(|g| { g.set(g.get() + 1); g.get() });
    let (q, issues, name, sh) = (b.get_log_filter().to_lowercase(), b.get_log_issues(), i.name.to_string(), sh.clone());
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let (raw, note) = match log_path(&sh, &name, kind) {
            Some(p) => (crash::tail(&p, 5000).unwrap_or_default(), String::new()),
            None => (String::new(), match kind {
                2 => "No crash reports. Nice.".to_string(),
                _ => format!("No log yet. Play {name} once and its log shows up here."),
            }),
        };
        let total = raw.lines().count();
        let kept: Vec<&str> = raw.lines().filter(|l| (q.is_empty() || l.to_lowercase().contains(&q)) && (!issues || is_issue(l))).collect();
        // keep the view light: the newest 1500 matching lines
        let text = kept[kept.len().saturating_sub(1500)..].join("\n");
        let count = if q.is_empty() && !issues { format!("{total} lines") } else { format!("{} of {total} lines match", kept.len()) };
        let note = if total > 0 && kept.is_empty() { "No lines match the filter.".to_string() } else { note };
        ui_do(&weak, move |ui| {
            if LOG_GEN.with(|g| g.get()) != gen { return; }
            let b = ui.global::<Backend>();
            if b.get_log_text().as_str() != text { b.set_log_text(text.into()); }
            b.set_log_note(note.into());
            b.set_log_count(count.into());
        });
    });
}

/// Warnings, errors and stack traces.
fn is_issue(l: &str) -> bool {
    let t = l.trim_start();
    l.contains("/WARN]") || l.contains("/ERROR]") || l.contains("/FATAL]") || t.starts_with("at ") || t.starts_with("Caused by") || t.contains("Exception") || t.starts_with("Error")
}

/// Hide the local user folder and the session token before a log leaves the machine.
pub(crate) fn redact(text: &str, sh: &Shared) -> String {
    let mut t = text.to_string();
    if let Some(s) = sh.session.lock().unwrap_or_else(|e| e.into_inner()).as_ref() { if s.access_token.len() > 8 { t = t.replace(&s.access_token, "<token>"); } }
    // the JVM command line (hs_err files, some loaders) carries the launched account's token and ids
    for flag in ["--accessToken", "--uuid", "--xuid", "--username", "Session ID is token:"] { t = mask_after(&t, flag); }
    t = mask_jwts(&t);
    t = mask_ipv4(&t);
    if let Some(s) = sh.session.lock().unwrap_or_else(|e| e.into_inner()).as_ref().filter(|s| s.user_type == "msa") {
        for id in [s.uuid.clone(), format!("{}-{}-{}-{}-{}", &s.uuid.get(..8).unwrap_or(""), s.uuid.get(8..12).unwrap_or(""), s.uuid.get(12..16).unwrap_or(""), s.uuid.get(16..20).unwrap_or(""), s.uuid.get(20..).unwrap_or(""))] {
            if id.len() >= 32 { t = t.replace(&id, "<uuid>"); }
        }
    }
    if let Some(h) = dirs_home() {
        for form in [h.clone(), h.replace('\\', "/"), h.replace('\\', "\\\\"), h.to_lowercase(), h.replace('\\', "/").to_lowercase(), h.replace('\\', "\\\\").to_lowercase()] { t = t.replace(&form, "~"); }
        if let Some(user) = std::path::Path::new(&h).file_name().and_then(|n| n.to_str()).filter(|u| u.len() > 2) { t = t.replace(user, "<user>"); }
    }
    t
}

/// "--flag value" -> "--flag <hidden>".
/// Replace IPv4 addresses (server and LAN addresses in logs) except loopback / 0.0.0.0.
fn mask_ipv4(t: &str) -> String {
    let b = t.as_bytes();
    let (mut out, mut i, mut last) = (String::with_capacity(t.len()), 0, 0);
    while i < b.len() {
        if b[i].is_ascii_digit() && (i == 0 || !(b[i - 1].is_ascii_alphanumeric() || b[i - 1] == b'.')) {
            let mut j = i;
            while j < b.len() && (b[j].is_ascii_digit() || b[j] == b'.') { j += 1; }
            let cand = &t[i..j];
            let parts: Vec<&str> = cand.split('.').collect();
            if parts.len() == 4 && !b.get(j).is_some_and(|c| c.is_ascii_alphabetic()) && parts.iter().all(|p| !p.is_empty() && p.len() <= 3 && p.parse::<u16>().is_ok_and(|n| n <= 255)) && !cand.starts_with("127.") && cand != "0.0.0.0" {
                out.push_str(&t[last..i]);
                out.push_str("<ip>");
                last = j;
            }
            i = j;
        } else { i += 1; }
    }
    out.push_str(&t[last..]);
    out
}

fn mask_after(t: &str, flag: &str) -> String {
    let mut out = String::with_capacity(t.len());
    let mut rest = t;
    while let Some(p) = rest.find(flag) {
        let after = &rest[p + flag.len()..];
        let gap = after.len() - after.trim_start_matches([' ', ',', '=']).len();
        let val = after[gap..].find(|c: char| c.is_whitespace() || c == ',' || c == ']').unwrap_or(after.len() - gap);
        out.push_str(&rest[..p + flag.len() + gap]);
        if val > 0 { out.push_str("<hidden>"); }
        rest = &after[gap + val..];
    }
    out.push_str(rest);
    out
}

/// Minecraft / Microsoft tokens are JWTs ("eyJ..."): hide any long one.
fn mask_jwts(t: &str) -> String {
    let mut out = String::with_capacity(t.len());
    let mut rest = t;
    while let Some(p) = rest.find("eyJ") {
        let tail = &rest[p..];
        let len = tail.find(|c: char| !(c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))).unwrap_or(tail.len());
        out.push_str(&rest[..p]);
        out.push_str(if len >= 40 { "<token>" } else { &tail[..len] });
        rest = &tail[len..];
    }
    out.push_str(rest);
    out
}

fn dirs_home() -> Option<String> { std::env::var("USERPROFILE").ok().filter(|h| h.len() > 3) }

/// Upload the shown log (or, from the crash dialog, the crash report / game log) to mclo.gs and copy the link.
pub fn share_log(ui: &AppWindow, sh: &Arc<Shared>, idx: i32) {
    let Some(i) = inst_at(ui, idx) else { return };
    let b = ui.global::<Backend>();
    let kind = if b.get_crash_open() { 2 } else { b.get_log_kind() };
    let Some(p) = log_path(sh, &i.name, kind).or_else(|| log_path(sh, &i.name, 0)) else { show_toast(ui, sh, "There's no log to share yet"); return };
    crate::job(ui, "mclogs", "Uploading log to mclo.gs…", -1.0);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let res = (|| -> anyhow::Result<String> {
            let text = redact(&crash::tail(&p, 25_000)?, &sh);
            Ok(nether_core::mclogs::upload(&nether_core::download::client(), &text)?.1)
        })();
        let sh2 = sh.clone();
        ui_do(&weak, move |ui| match crate::job_done(ui, "mclogs", res) {
            Ok(url) => {
                if let Ok(mut c) = arboard::Clipboard::new() { let _ = c.set_text(url.clone()); }
                show_toast(ui, &sh2, &format!("Log link copied: {url}"));
            }
            Err(e) => show_toast(ui, &sh2, &format!("Couldn't upload the log: {e}")),
        });
    });
}

/// After an abnormal exit: diagnose the logs and open the crash dialog. Worker thread.
pub fn report_crash(weak: &slint::Weak<AppWindow>, sh: &Arc<Shared>, name: &str, code: Option<i32>) {
    let mut found = crash::diagnose(&sh.paths, name, code);
    found.extend(crash::recent_changes(&sh.paths, name));
    let tail = crash_log(sh, name).and_then(|p| crash::tail(&p, 80).ok()).map(|t| redact(&t, sh)).unwrap_or_default();
    let (name, sh) = (name.to_string(), sh.clone());
    ui_do(weak, move |ui| {
        let b = ui.global::<Backend>();
        b.set_crash_state(0);
        b.set_crash_url("".into());
        b.set_crash_title("".into());
        b.set_crash_problems(ModelRc::new(VecModel::from(Vec::<CrashProblem>::new())));
        b.set_crash_facts(ModelRc::new(VecModel::from(Vec::<slint::SharedString>::new())));
        b.set_crash_tail(tail.into());
        let items: Vec<CrashItem> = crate::preflight::rows(&found);
        CRASH_FOUND.with(|c| *c.borrow_mut() = found);
        let idx = b.get_instances().iter().position(|i| i.name == name).map(|p| p as i32).unwrap_or(-1);
        b.set_crash_items(ModelRc::new(VecModel::from(items)));
        b.set_crash_inst(name.as_str().into());
        b.set_crash_idx(idx);
        b.set_crash_code(code.map(|c| c.to_string()).unwrap_or_default().into());
        b.set_log_kind(2);
        b.set_crash_open(true);
        // L2: Home and the list show it until the next launch
        crate::edit_inst(ui, &sh, &name, |i| i.crashed = true);
    });
}

/// The log that explains a crash best: the crash report (or JVM crash file), else the game log.
fn crash_log(sh: &Shared, name: &str) -> Option<std::path::PathBuf> { log_path(sh, name, 2).or_else(|| log_path(sh, name, 0)) }

/// Crash page: upload the crash log to mclo.gs, then show its link and automatic analysis.
pub fn crash_upload(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    let name = b.get_crash_inst().to_string();
    let Some(p) = crash_log(sh, &name) else { b.set_crash_err("there is no log to upload".into()); b.set_crash_state(3); return };
    b.set_crash_state(1);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let c = nether_core::download::client();
        let res = (|| -> anyhow::Result<(String, nether_core::mclogs::Insights)> {
            let text = redact(&crash::tail(&p, 25_000)?, &sh);
            let (id, url) = nether_core::mclogs::upload(&c, &text)?;
            let ins = nether_core::mclogs::insights(&c, &id).unwrap_or_default();
            Ok((url, ins))
        })();
        ui_do(&weak, move |ui| {
            let b = ui.global::<Backend>();
            match res {
                Ok((url, ins)) => {
                    let probs: Vec<CrashProblem> = ins.problems.iter().map(|(t, s)| CrashProblem {
                        title: t.as_str().into(),
                        fixes: if s.is_empty() { "No automatic fix known; share the link.".into() } else { s.iter().map(|x| format!("→ {x}")).collect::<Vec<_>>().join("\n").into() },
                    }).collect();
                    let facts: Vec<slint::SharedString> = ins.info.iter().take(6).map(|(k, v)| format!("{k}: {v}").into()).collect();
                    b.set_crash_url(url.into());
                    b.set_crash_title(ins.title.to_uppercase().into());
                    b.set_crash_problems(ModelRc::new(VecModel::from(probs)));
                    b.set_crash_facts(ModelRc::new(VecModel::from(facts)));
                    b.set_crash_state(2);
                }
                Err(e) => { b.set_crash_err(e.to_string().into()); b.set_crash_state(3); }
            }
        });
    });
}

/// Crash page: start the same instance again.
pub fn crash_relaunch(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    b.set_crash_open(false);
    let name = b.get_crash_inst();
    b.invoke_play(name);
    let _ = sh;
}


/// Stop a running game: close its window like the X button would (Minecraft saves the world on the way out),
/// and only force the process tree down if it is still there after 20 seconds. The launch thread notices the exit.
pub fn stop(ui: &AppWindow, sh: &Arc<Shared>, name: &str) {
    let pid = sh.running.lock().unwrap_or_else(|e| e.into_inner()).get(name).copied();
    let Some(pid) = pid else { show_toast(ui, sh, &format!("{name} isn't running")); return };
    if !sh.stopping.lock().unwrap_or_else(|e| e.into_inner()).insert(name.to_string()) { return; }
    crate::edit_inst_quiet(ui, name, |i| i.stopping = true);
    std::thread::spawn(move || {
        let kill = |force: bool| {
            let mut cmd = std::process::Command::new("taskkill");
            #[cfg(windows)]
            { use std::os::windows::process::CommandExt; cmd.creation_flags(0x0800_0000); }
            cmd.args(["/PID", &pid.to_string(), "/T"]);
            if force { cmd.arg("/F"); }
            let _ = cmd.output();
        };
        kill(false);
        if !wait_exit(pid, std::time::Duration::from_secs(20)) { kill(true); }
    });
}

/// Wait up to `max` for a process to end. True when it did (or is already gone).
#[cfg(windows)]
fn wait_exit(pid: u32, max: std::time::Duration) -> bool {
    use windows_sys::Win32::Foundation::{CloseHandle, WAIT_OBJECT_0};
    use windows_sys::Win32::System::Threading::{OpenProcess, WaitForSingleObject, PROCESS_SYNCHRONIZE};
    unsafe {
        let h = OpenProcess(PROCESS_SYNCHRONIZE, 0, pid);
        if h.is_null() { return true; }
        let r = WaitForSingleObject(h, max.as_millis() as u32);
        CloseHandle(h);
        r == WAIT_OBJECT_0
    }
}
#[cfg(not(windows))]
fn wait_exit(_pid: u32, max: std::time::Duration) -> bool { std::thread::sleep(max); false }

/// Import a modpack file (.mrpack, CurseForge zip, Prism export) or a Prism/MultiMC instance folder.
pub fn import_instance(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some(file) = rfd::FileDialog::new()
        .set_title("Import an instance or modpack")
        .set_directory(dirs_downloads())
        .add_filter("Modpack or instance", &["mrpack", "zip", "cfg", "json"])
        .pick_file() else { return };
    // picking instance.cfg / mmc-pack.json inside a Prism or MultiMC folder imports that folder
    let path = match file.file_name().and_then(|n| n.to_str()) {
        Some("instance.cfg" | "mmc-pack.json") => file.parent().map(|p| p.to_path_buf()).unwrap_or(file),
        _ => file,
    };
    import_path(ui, sh, path);
}

/// The user's Downloads folder (follows OneDrive / a moved folder).
pub(crate) fn dirs_downloads() -> std::path::PathBuf {
    known_downloads().or_else(|| std::env::var("USERPROFILE").ok().map(|h| std::path::PathBuf::from(h).join("Downloads"))).unwrap_or_default()
}

#[cfg(windows)]
fn known_downloads() -> Option<std::path::PathBuf> {
    use std::os::windows::ffi::OsStringExt;
    use windows_sys::Win32::UI::Shell::{FOLDERID_Downloads, SHGetKnownFolderPath};
    unsafe {
        let mut p: windows_sys::core::PWSTR = std::ptr::null_mut();
        if SHGetKnownFolderPath(&FOLDERID_Downloads, 0, std::ptr::null_mut(), &mut p) != 0 || p.is_null() { return None; }
        let len = (0..).take_while(|&i| *p.add(i) != 0).count();
        let s = std::ffi::OsString::from_wide(std::slice::from_raw_parts(p, len));
        windows_sys::Win32::System::Com::CoTaskMemFree(p as _);
        Some(std::path::PathBuf::from(s)).filter(|d| d.is_dir())
    }
}
#[cfg(not(windows))]
fn known_downloads() -> Option<std::path::PathBuf> { None }

/// Import a modpack file or instance folder as a new instance (worker thread, progress in the deck).
pub fn import_path(ui: &AppWindow, sh: &Arc<Shared>, path: std::path::PathBuf) {
    let key = format!("import:{}", path.display());
    let label = path.file_name().and_then(|n| n.to_str()).unwrap_or("modpack").to_string();
    crate::job(ui, &key, &format!("Importing {label}…"), 0.0);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let (w2, k2) = (weak.clone(), key.clone());
        let progress: Progress = Arc::new(move |m, f| { let (t, k) = (format!("Importing · {m}"), k2.clone()); ui_do(&w2, move |ui| crate::job(ui, &k, &t, f)); });
        let res = import::import(&nether_core::download::client(), &sh.paths, &path, None, &progress);
        let sh2 = sh.clone();
        ui_do(&weak, move |ui| { crate::job_end(ui, &key); match res {
            Ok(r) => {
                let m = ui.global::<Backend>().get_instances();
                if let Some(v) = m.as_any().downcast_ref::<VecModel<Inst>>() {
                    v.push(crate::inst_from(&r.data));
                    crate::persist(&sh2.paths, v);
                }
                crate::refilter(ui);
                crate::artui::refresh(ui, &sh2);
                let extra = if r.missing.is_empty() { String::new() } else { format!(" ({} files need a manual download)", r.missing.len()) };
                show_toast(ui, &sh2, &format!("Imported {}{extra}", r.data.name));
            }
            Err(e) => show_toast(ui, &sh2, &format!("Couldn't import {label}: {e}")),
        } });
    });
}

/// Overview: how much disk the opened instance uses (measured on a worker thread).
pub fn measure_inst(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    b.set_inst_size("".into());
    let Some(i) = inst_at(ui, b.get_current_inst()) else { return };
    let (weak, dir, name) = (ui.as_weak(), sh.paths.instance(&i.name), i.name.to_string());
    std::thread::spawn(move || {
        let n = worlds::dir_size(&dir);
        ui_do(&weak, move |ui| {
            let b = ui.global::<Backend>();
            // still the same instance on screen
            if inst_at(ui, b.get_current_inst()).is_some_and(|i| i.name == name.as_str()) && n > 0 { b.set_inst_size(size(n).into()); }
        });
    });
}

/// Open the folder of the content kind on screen (mods, resource packs or shaders).
pub fn content_folder(ui: &AppWindow, sh: &Arc<Shared>, idx: i32) {
    let Some(i) = inst_at(ui, idx) else { return };
    let dir = nether_core::instances::content_dir(&sh.paths, &i.name, ui.global::<Backend>().get_pack_kind());
    let _ = std::fs::create_dir_all(&dir);
    let _ = std::process::Command::new("explorer").arg(&dir).spawn();
}

/// Copy local files (a mod jar, a pack zip) into the instance's content folder of the kind on screen.
pub fn add_content_files(ui: &AppWindow, sh: &Arc<Shared>, idx: i32) {
    let Some(i) = inst_at(ui, idx) else { return };
    let b = ui.global::<Backend>();
    let kind = b.get_pack_kind();
    let (what, exts): (&str, &[&str]) = match kind { 0 => ("mods", &["jar"]), _ => (if kind == 1 { "resource packs" } else { "shader packs" }, &["zip"]) };
    let Some(files) = rfd::FileDialog::new().set_title(format!("Add {what} to {}", i.name)).add_filter(what, exts).pick_files() else { return };
    add_files(ui, sh, idx, kind, files);
}

/// Copy files into an instance's mods / resource packs / shader packs (worker thread). A file with the same
/// name already there is kept, not overwritten (it may be a newer build).
pub fn add_files(ui: &AppWindow, sh: &Arc<Shared>, idx: i32, kind: i32, files: Vec<std::path::PathBuf>) {
    let Some(i) = inst_at(ui, idx) else { return };
    let what = match kind { 0 => "mods", 1 => "resource packs", _ => "shader packs" };
    let dir = nether_core::instances::content_dir(&sh.paths, &i.name, kind);
    let key = format!("add:{}:{kind}", i.name);
    let iname = i.name.to_string();
    crate::job(ui, &key, &format!("Adding {what} to {}…", i.name), -1.0);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let _ = std::fs::create_dir_all(&dir);
        let (mut ok, mut kept, mut err) = (0, vec![], String::new());
        for f in &files {
            let Some(n) = f.file_name() else { continue };
            let dest = dir.join(n);
            let disabled = dir.join(format!("{}.disabled", n.to_string_lossy()));
            if dest.exists() || disabled.exists() { kept.push(n.to_string_lossy().to_string()); continue; }
            match std::fs::read(f).map_err(anyhow::Error::from).and_then(|b| Ok(nether_core::store::write_atomic(&dest, &b)?)) { Ok(()) => ok += 1, Err(e) => err = e.to_string() }
        }
        ui_do(&weak, move |ui| {
            crate::job_end(ui, &key);
            let noun = |n: usize| if n == 1 { what.trim_end_matches('s').to_string() } else { what.to_string() };
            let mut msg = if ok > 0 { format!("Added {ok} {} to {}", noun(ok), iname) } else { String::new() };
            if !kept.is_empty() { msg = format!("{msg}{}{} already there, kept as is", if msg.is_empty() { "" } else { " · " }, if kept.len() == 1 { kept[0].clone() } else { format!("{} files", kept.len()) }); }
            if !err.is_empty() { msg = format!("Couldn't add every file: {err}"); }
            if !msg.is_empty() { show_toast(ui, &sh, &msg); }
            let b = ui.global::<Backend>();
            if b.get_current_inst() == idx && b.get_pack_kind() == kind { b.invoke_refresh_installed(); }
            if kind == 2 && ok > 0 { if let Some(i) = inst_at(ui, idx) { offer_iris(ui, &sh, &i); } }
        });
    });
}

thread_local! {
    static CRASH_FOUND: std::cell::RefCell<Vec<crash::Finding>> = Default::default();
    static LOG_GEN: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

/// Crash page: run finding `i`'s one-click fix and take its row off (the page stays, PLAY AGAIN is right there).
pub fn crash_fix(ui: &AppWindow, sh: &Arc<Shared>, i: i32) {
    let b = ui.global::<Backend>();
    let Some(f) = CRASH_FOUND.with(|c| { let mut c = c.borrow_mut(); (i >= 0 && (i as usize) < c.len()).then(|| c.remove(i as usize)) }) else { return };
    if let Some(a) = &f.action { crate::preflight::apply(ui, sh, b.get_crash_inst().as_str(), a); }
    let rest = CRASH_FOUND.with(|c| crate::preflight::rows(&c.borrow()));
    b.set_crash_items(ModelRc::new(VecModel::from(rest)));
}

/// L1: re-check and repair an instance's game files (worker, progress + CANCEL in the deck).
pub fn verify(ui: &AppWindow, sh: &Arc<Shared>, i: &Inst) {
    if i.running || i.starting { show_toast(ui, sh, &format!("Close {} before checking its files", i.name)); return; }
    let key = format!("verify:{}", i.name);
    let cancel = Arc::new(std::sync::atomic::AtomicBool::new(false));
    crate::job_cancelable(ui, &key, &format!("Verifying {}…", i.name), 0.0, cancel.clone());
    let (weak, sh, name, ver, loader) = (ui.as_weak(), sh.clone(), i.name.to_string(), i.ver.to_string(), i.loader.to_string());
    std::thread::spawn(move || {
        let (w2, k2, n2) = (weak.clone(), key.clone(), name.clone());
        let last = std::sync::Mutex::new(-1i32);
        let progress: Progress = Arc::new(move |m, f| {
            let pct = (f * 100.0) as i32;
            { let mut l = last.lock().unwrap_or_else(|e| e.into_inner()); if *l == pct { return; } *l = pct; }
            let (t, k) = (format!("Verifying {n2} · {m}"), k2.clone());
            ui_do(&w2, move |ui| crate::job(ui, &k, &t, f));
        });
        let res = nether_core::launch::verify(&sh.paths, nether_core::launch::Loader::from_label(&loader), &ver, &sh.paths.instance(&name), progress, Some(&cancel));
        ui_do(&weak, move |ui| match crate::job_done(ui, &key, res) {
            Ok(0) => show_toast(ui, &sh, &format!("All game files of {name} are intact")),
            Ok(n) => show_toast(ui, &sh, &format!("Repaired {n} damaged or missing file{} of {name}", if n == 1 { "" } else { "s" })),
            Err(e) if nether_core::download::is_cancelled(&e) => show_toast(ui, &sh, "Stopped verifying"),
            Err(e) => show_toast(ui, &sh, &format!("Couldn't verify {name}: {}", crate::friendly(&e))),
        });
    });
}

/// L1: save a redacted support bundle (zip) to Downloads and show it in Explorer. Nothing is uploaded.
pub fn support_bundle(ui: &AppWindow, sh: &Arc<Shared>, i: &Inst) {
    let key = format!("bundle:{}", i.name);
    crate::job(ui, &key, &format!("Collecting a support bundle for {}…", i.name), -1.0);
    let sys_ram = ui.global::<Backend>().get_sys_ram();
    let java = match crate::JAVAS[i.java.clamp(0, 4) as usize] { 0 => "Auto".to_string(), j => format!("Java {j}") };
    // plain owned copies: the checks and the Windows version query run on the worker, not the UI thread
    struct Facts { name: String, ver: String, loader: String, jvm: String, ram: i32, jv: u32, fullscreen: bool, w: i32, h: i32 }
    let inst = Facts { name: i.name.to_string(), ver: i.ver.to_string(), loader: i.loader.to_string(), jvm: i.jvm.to_string(), ram: i.ram, jv: crate::JAVAS[i.java.clamp(0, 4) as usize], fullscreen: i.fullscreen, w: i.win_w, h: i.win_h };
    let safe: String = i.name.chars().map(|c| if c.is_alphanumeric() || c == '-' { c } else { '_' }).collect();
    let out = Some(dirs_downloads()).filter(|p| p.is_dir()).unwrap_or_else(|| sh.paths.root.join("exports")).join(format!("NetherClient-support-{safe}-{}.zip", crate::now()));
    let (weak, sh, name) = (ui.as_weak(), sh.clone(), i.name.to_string());
    std::thread::spawn(move || {
        let red = |t: &str| redact(t, &sh);
        let t = nether_core::preflight::Target { name: &inst.name, version: &inst.ver, loader: nether_core::launch::Loader::from_label(&inst.loader), java: inst.jv, ram_gb: inst.ram.max(1) as u32 };
        let checks: Vec<String> = nether_core::preflight::check(&sh.paths, &t).iter().map(|f| format!("- {}: {}", f.title, f.explanation)).collect();
        let summary = format!("Nether Client {}\n{}\nInstance: {}\nMinecraft: {}\nLoader: {}\nMemory: {} GB (PC: {} GB)\nJava: {java}\nJVM arguments: {}\nWindow: {}\n\nPre-launch check:\n{}\n",
            env!("CARGO_PKG_VERSION"), windows_version(), inst.name, inst.ver, inst.loader, inst.ram, sys_ram, if inst.jvm.is_empty() { "(default)" } else { inst.jvm.as_str() },
            if inst.fullscreen { "fullscreen".to_string() } else if inst.w > 0 { format!("{}x{}", inst.w, inst.h) } else { "default".into() },
            if checks.is_empty() { "nothing found".to_string() } else { checks.join("\n") });
        let res = nether_core::mclogs::bundle(&sh.paths, &name, &summary, &crate::applog::file(), &red, &out);
        ui_do(&weak, move |ui| match crate::job_done(ui, &key, res) {
            Ok(()) => {
                show_toast(ui, &sh, "Support bundle saved to Downloads. Nothing was sent; share it with whoever helps you");
                let _ = std::process::Command::new("explorer").arg(format!("/select,{}", out.display())).spawn();
            }
            Err(e) => show_toast(ui, &sh, &format!("Couldn't make the support bundle: {}", crate::friendly(&e))),
        });
    });
}

/// "Windows 10.0.26100" (from `ver`), or just "Windows".
fn windows_version() -> String {
    let mut cmd = std::process::Command::new("cmd");
    #[cfg(windows)]
    { use std::os::windows::process::CommandExt; cmd.creation_flags(0x0800_0000); }
    cmd.args(["/c", "ver"]).output().ok().map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).filter(|s| !s.is_empty()).unwrap_or_else(|| "Windows".into())
}


/// After installing a shader pack: offer Iris when the instance has no shader mod (Fabric, Quilt, NeoForge).
pub fn offer_iris(ui: &AppWindow, sh: &Arc<Shared>, inst: &Inst) {
    let loader = inst.loader.to_lowercase();
    // Iris exists for Fabric, Quilt and NeoForge; Legacy Fabric versions predate it
    if loader.contains("legacy") || !(loader.contains("fabric") || loader.contains("quilt") || loader.contains("neoforge")) { return; }
    let has = nether_core::instances::mod_files(&sh.paths, &inst.name).iter().any(|p| {
        let n = p.file_name().and_then(|n| n.to_str()).unwrap_or("").to_lowercase();
        n.starts_with("iris") || n.starts_with("oculus") || n.contains("optifine")
    });
    if has { return; }
    let (sh2, i2) = (sh.clone(), inst.clone());
    ask(ui, "Shaders need Iris", "Shader packs only show up with a shader mod. Install Iris (and Sodium, which it needs) into this instance now?", "INSTALL IRIS", false, move |ui| {
        crate::mods::install(ui.as_weak(), sh2.clone(), "iris".into(), None, "Iris Shaders".into(), i2.ver.to_string(), i2.loader.to_string(), i2.name.to_string(), 0, None);
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quick_play_versions() {
        assert!(quick_play_ok("1.20.1") && quick_play_ok("1.21.11") && quick_play_ok("26.3"));
        assert!(!quick_play_ok("1.8.9") && !quick_play_ok("1.19.4"));
    }

    #[test]
    fn redacts_ips() {
        assert_eq!(mask_ipv4("connect 203.0.113.7:25565 and 127.0.0.1 v1.21.11.2x 192.168.1.20"), "connect <ip>:25565 and 127.0.0.1 v1.21.11.2x <ip>");
        assert_eq!(mask_after("--username Steve --x", "--username"), "--username <hidden> --x");
    }

    #[test]
    fn base64_icons() {
        assert_eq!(b64("aGVsbG8=").unwrap(), b"hello");
        assert_eq!(b64("data:image/png;base64,aGk=").unwrap(), b"hi");
    }
}
