//! L2: command palette (Ctrl+K). An in-memory index built when it opens: pages, actions, Settings sections,
//! instances (open / play), their worlds (Quick Play where the version has it) and installed mods.
//! Fuzzy matched as you type; arrows + Enter; the last runs come first on an empty query.
use std::cell::RefCell;
use std::sync::Arc;

use nether_core::launch::QuickPlay;
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::{ui_do, AppWindow, Backend, PalRow, Shared};

#[derive(Clone, PartialEq)]
enum Act {
    Tab(i32),
    Settings(i32),
    /// instance name, instance page tab
    Open(String, i32),
    Play(String),
    /// instance, world folder
    World(String, String),
    /// instance, mod name (filters its Mods tab)
    Mod(String, String),
    Crash(String),
    New,
    Import,
    Updates,
    Logs,
    SysInfo,
    SignIn,
    Calm,
    Data,
}

#[derive(Clone)]
struct Item { kind: &'static str, title: String, sub: String, keys: String, act: Act }

fn item(kind: &'static str, title: &str, sub: &str, keys: &str, act: Act) -> Item {
    Item { kind, title: title.into(), sub: sub.into(), keys: keys.to_lowercase(), act }
}

thread_local! {
    static INDEX: RefCell<Vec<Item>> = const { RefCell::new(Vec::new()) };
    static SHOWN: RefCell<Vec<Item>> = const { RefCell::new(Vec::new()) };
    /// last runs (kind + title), newest first
    static RECENT: RefCell<Vec<(String, String)>> = const { RefCell::new(Vec::new()) };
    static GEN: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
}

/// Settings sections, in page order (settings.slint `tops`).
const SECTIONS: [(&str, &str, &str); 7] = [
    ("Shared game settings", "Keybinds, video and audio that follow you between instances", "options keybinds fov video audio sync"),
    ("Accounts", "Microsoft accounts on this PC", "sign in microsoft switch account remove"),
    ("Java", "Runtimes per Java version, custom java.exe", "runtime jre jdk javaw"),
    ("Launcher", "GPU, start with Windows, tray, reduced motion", "tray startup minimize close gpu hardware acceleration motion"),
    ("Personalization", "Accent colour, text size, your icons and wallpapers", "theme colour color accent text size font icons wallpapers"),
    ("Storage & about", "Folders, download cache, updates, support", "folders cache disk updates version support logs"),
    ("Privacy", "What the launcher and client connect to; capes and cosmetics in game", "network telemetry capes cosmetics endpoints data servers"),
];

fn statics(ui: &AppWindow) -> Vec<Item> {
    let b = ui.global::<Backend>();
    let mut v = vec![
        item("PAGE", "Home", "Launch target, recent instances, player", "start", Act::Tab(0)),
        item("PAGE", "Instances", "Every instance, groups and search", "list", Act::Tab(1)),
        item("PAGE", "Install packs", "Modrinth and Nether modpacks", "modpacks discover browse", Act::Tab(2)),
        item("PAGE", "Wardrobe", "Skins and capes", "skin cape", Act::Tab(5)),
        item("ACTION", "New instance", "A new Minecraft setup", "create add make", Act::New),
        item("ACTION", "Import an instance", ".mrpack, .zip or another launcher's folder", "prism multimc curseforge modrinth", Act::Import),
        item("ACTION", "Check for launcher updates", "Nether Client releases on GitHub", "update version", Act::Updates),
        item("ACTION", "Open launcher logs", "The launcher's own log folder", "log support", Act::Logs),
        item("ACTION", "Copy system info", "For a bug report", "support clipboard", Act::SysInfo),
        item("ACTION", "Open data folder", "Where instances, Java and caches live", "folder files", Act::Data),
        item("ACTION", if b.get_calm() { "Turn reduced motion off" } else { "Turn reduced motion on" }, "Page transitions and pulsing indicators", "animation calm", Act::Calm),
    ];
    if b.get_acct_state() != 2 { v.push(item("ACTION", "Sign in with Microsoft", "Needed to play", "login account", Act::SignIn)); }
    for (i, (t, s, k)) in SECTIONS.iter().enumerate() { v.push(item("SETTING", t, s, k, Act::Settings(i as i32))); }
    for i in b.get_instances().iter() {
        let (n, sub) = (i.name.to_string(), format!("{} · {}", i.ver, i.loader));
        v.push(item("INSTANCE", &n, &sub, &format!("{} {}", i.group, "open edit"), Act::Open(n.clone(), 0)));
        v.push(item("PLAY", &format!("Play {n}"), &sub, "launch start", Act::Play(n.clone())));
        if i.crashed { v.push(item("ACTION", &format!("View the crash of {n}"), "The last session crashed", "crash log error", Act::Crash(n.clone()))); }
    }
    v
}

/// Worlds and mods of every instance (disk; runs off the UI thread).
fn scan(sh: &Shared, insts: &[(String, String)]) -> Vec<Item> {
    let mut v = vec![];
    for (name, ver) in insts {
        let dir = sh.paths.instance(name);
        if let Ok(rd) = std::fs::read_dir(dir.join("saves")) {
            for e in rd.flatten().filter(|e| e.path().join("level.dat").is_file()) {
                let f = e.file_name().to_string_lossy().to_string();
                let sub = if crate::instpage::quick_play_ok(ver) { format!("World in {name} · Enter plays it") } else { format!("World in {name}") };
                v.push(item("WORLD", &f, &sub, name, Act::World(name.clone(), f.clone())));
            }
        }
        for p in nether_core::instances::mod_files(&sh.paths, name) {
            let file = p.file_name().map(|f| f.to_string_lossy().to_string()).unwrap_or_default();
            let off = file.ends_with(".disabled");
            let title = nether_core::preflight::mod_name(&p).unwrap_or_else(|| file.trim_end_matches(".disabled").trim_end_matches(".jar").to_string());
            let sub = format!("Mod in {name}{}", if off { " · disabled" } else { "" });
            v.push(item("MOD", &title, &sub, &format!("{name} {file}"), Act::Mod(name.clone(), title.clone())));
        }
    }
    v
}

/// Ctrl+K: build the index, show recent / default rows, then add worlds and mods when the scan is done.
pub fn show(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    if b.get_pal_open() { return; }
    INDEX.with(|x| *x.borrow_mut() = statics(ui));
    b.set_pal_query("".into());
    search(ui);
    b.set_pal_open(true);
    let gen = GEN.with(|g| { g.set(g.get() + 1); g.get() });
    let insts: Vec<(String, String)> = b.get_instances().iter().map(|i| (i.name.to_string(), i.ver.to_string())).collect();
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let more = scan(&sh, &insts);
        ui_do(&weak, move |ui| {
            if GEN.with(|g| g.get()) != gen || !ui.global::<Backend>().get_pal_open() { return; }
            INDEX.with(|x| x.borrow_mut().extend(more));
            if !ui.global::<Backend>().get_pal_query().trim().is_empty() { search(ui); }
        });
    });
}

/// Fuzzy score of `q` (lowercase) in `text`: substring beats scattered letters; word starts and early hits count more.
fn score(q: &str, text: &str) -> Option<i32> {
    let t = text.to_lowercase();
    if let Some(p) = t.find(q) {
        let start = p == 0 || !t[..p].chars().next_back().is_some_and(|c| c.is_alphanumeric());
        return Some(1000 - p as i32 + if start { 300 } else { 0 } + if t.len() == q.len() { 200 } else { 0 });
    }
    let tc: Vec<char> = t.chars().collect();
    let (mut i, mut s, mut last) = (0usize, 0i32, None::<usize>);
    for c in q.chars().filter(|c| !c.is_whitespace()) {
        let found = (i..tc.len()).find(|&k| tc[k] == c)?;
        let word_start = found == 0 || !tc[found - 1].is_alphanumeric();
        let gap = if last.is_some_and(|l| found == l + 1) { 0 } else { 3 };
        s += (if word_start { 30 } else { 10 }) - gap;
        last = Some(found);
        i = found + 1;
    }
    Some(s - tc.len() as i32 / 8)
}

fn rank(kind: &str) -> i32 { match kind { "INSTANCE" => 40, "PLAY" => 30, "PAGE" | "SETTING" => 25, "ACTION" => 20, "WORLD" => 10, _ => 0 } }

/// Re-rank for the current query (empty = recent runs, then pages, instances and actions).
pub fn search(ui: &AppWindow) {
    let b = ui.global::<Backend>();
    let q = b.get_pal_query().trim().to_lowercase();
    let rows: Vec<Item> = INDEX.with(|x| {
        let x = x.borrow();
        if q.is_empty() {
            let recent: Vec<Item> = RECENT.with(|r| r.borrow().iter().filter_map(|(k, t)| x.iter().find(|i| i.kind == k && i.title == *t).cloned()).collect());
            let rest = x.iter().filter(|i| matches!(i.kind, "PAGE" | "INSTANCE" | "ACTION") && !recent.iter().any(|r| r.title == i.title && r.kind == i.kind)).cloned();
            recent.iter().cloned().chain(rest).take(40).collect()
        } else {
            let mut hits: Vec<(i32, &Item)> = x.iter().filter_map(|i| {
                let best = score(&q, &i.title).map(|s| s + 200).into_iter().chain(score(&q, &i.keys)).chain(score(&q, &i.sub).map(|s| s / 2)).max()?;
                Some((best + rank(i.kind), i))
            }).collect();
            hits.sort_by_key(|a| std::cmp::Reverse(a.0));
            hits.into_iter().take(50).map(|(_, i)| i.clone()).collect()
        }
    });
    let model: Vec<PalRow> = rows.iter().map(|i| PalRow { kind: i.kind.into(), title: i.title.as_str().into(), sub: i.sub.as_str().into() }).collect();
    SHOWN.with(|s| *s.borrow_mut() = rows);
    b.set_pal_rows(ModelRc::new(VecModel::from(model)));
    b.set_pal_sel(0);
}

fn inst_idx(ui: &AppWindow, name: &str) -> Option<i32> {
    ui.global::<Backend>().get_instances().iter().position(|i| i.name == name).map(|p| p as i32)
}

fn open_inst(ui: &AppWindow, name: &str, itab: i32) {
    let Some(i) = inst_idx(ui, name) else { return };
    ui.set_open_tab(itab);
    ui.set_open_mode(0);
    ui.set_open_idx(i);
    ui.set_tab(1);
}

/// Run row `row` and close.
pub fn run(ui: &AppWindow, sh: &Arc<Shared>, row: i32) {
    let b = ui.global::<Backend>();
    let Some(it) = SHOWN.with(|s| s.borrow().get(row.max(0) as usize).cloned()) else { return };
    b.set_pal_open(false);
    RECENT.with(|r| {
        let mut r = r.borrow_mut();
        r.retain(|(k, t)| !(k == it.kind && *t == it.title));
        r.insert(0, (it.kind.to_string(), it.title.clone()));
        r.truncate(6);
    });
    match it.act {
        Act::Tab(t) => { ui.set_open_idx(-1); ui.set_tab(t); }
        Act::Settings(s) => { b.set_settings_goto(s); ui.set_open_idx(-1); ui.set_tab(6); }
        Act::Open(n, t) => open_inst(ui, &n, t),
        Act::Play(n) => b.invoke_play(n.into()),
        Act::World(n, f) => {
            let Some(i) = inst_idx(ui, &n).and_then(|i| b.get_instances().row_data(i as usize)) else { return };
            if crate::instpage::quick_play_ok(&i.ver) && !i.running && !i.starting { crate::instpage::play_into(ui, sh, &i, QuickPlay::World(f)); } else { open_inst(ui, &n, 3); }
        }
        Act::Mod(n, title) => { b.set_mod_query(title.into()); b.set_pack_kind(0); open_inst(ui, &n, 1); }
        Act::Crash(n) => { b.set_log_kind(2); open_inst(ui, &n, 5); }
        Act::New => { b.set_create_open(true); ui.set_open_idx(-1); ui.set_tab(1); }
        Act::Import => b.invoke_import_instance(),
        Act::Updates => { b.invoke_check_updates(); b.set_settings_goto(5); ui.set_open_idx(-1); ui.set_tab(6); }
        Act::Logs => b.invoke_open_logs(),
        Act::SysInfo => b.invoke_copy_sysinfo(),
        Act::SignIn => { b.set_acct_open(true); b.invoke_sign_in(); }
        Act::Calm => { b.set_calm(!b.get_calm()); b.invoke_save_launcher(); }
        Act::Data => b.invoke_open_data(),
    }
}

pub fn wire(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    let (weak, s) = (ui.as_weak(), sh.clone());
    b.on_pal_show(move || if let Some(ui) = weak.upgrade() { show(&ui, &s) });
    let weak = ui.as_weak();
    b.on_pal_search(move || if let Some(ui) = weak.upgrade() { search(&ui) });
    let (weak, s) = (ui.as_weak(), sh.clone());
    b.on_pal_run(move |r| if let Some(ui) = weak.upgrade() { run(&ui, &s, r) });
}

#[cfg(test)]
mod tests {
    use super::score;

    #[test]
    fn fuzzy_ranks_substrings_and_word_starts() {
        assert!(score("surv", "Survival").unwrap() > score("surv", "Big survival pack").unwrap());
        assert!(score("sv", "Survival").is_some());
        assert!(score("xyz", "Survival").is_none());
        assert!(score("cl", "Clear cache").unwrap() > score("cl", "Uncle").unwrap());
        assert!(score("fsp", "Fabric survival pack").unwrap() > score("fsp", "fsxxpxxx").unwrap_or(i32::MIN));
    }
}
