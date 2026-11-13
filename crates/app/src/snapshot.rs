//! L1: safe updates (changelogs first, old files + configs kept in a snapshot, one-click undo) and update history.
use std::path::PathBuf;
use std::sync::Arc;

use nether_core::download::client;
use nether_core::{modrinth, snapshot};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::{show_toast, ui_do, AppWindow, Backend, HistRow, Shared, UpdRow};

/// One file the dialog offers to update.
#[derive(Clone)]
pub(crate) struct Cand { pub name: String, pub path: PathBuf, pub from: String }

struct Plan { gen: u64, inst: String, kind: i32, files: Vec<(PathBuf, String)> }

thread_local! {
    static PLAN: std::cell::RefCell<Option<Plan>> = const { std::cell::RefCell::new(None) };
    static GEN: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

fn what(kind: i32, n: usize) -> &'static str {
    match (kind, n == 1) { (1, true) => "resource pack", (1, false) => "resource packs", (2, true) => "shader pack", (2, false) => "shader packs", (_, true) => "mod", _ => "mods" }
}

/// Open the update dialog for `cands` and load each new version + changelog on a worker.
pub(crate) fn open(ui: &AppWindow, sh: &Arc<Shared>, inst: &str, ver: &str, loader: &str, kind: i32, cands: Vec<Cand>) {
    if cands.is_empty() { return; }
    let b = ui.global::<Backend>();
    let gen = GEN.with(|g| { g.set(g.get() + 1); g.get() });
    PLAN.with(|p| *p.borrow_mut() = None);
    b.set_upd_inst(inst.into());
    b.set_upd_state(0);
    b.set_upd_picked(0);
    b.set_upd_rows(ModelRc::new(VecModel::from(cands.iter().map(|c| UpdRow { name: c.name.as_str().into(), from: c.from.as_str().into(), to: "…".into(), ..Default::default() }).collect::<Vec<_>>())));
    b.set_upd_open(true);
    let (weak, sh, inst, ver) = (ui.as_weak(), sh.clone(), inst.to_string(), ver.to_string());
    let ln = if kind == 0 { nether_core::launch::Loader::from_label(loader).modrinth() } else { None };
    std::thread::spawn(move || {
        let c = client();
        let hashes: Vec<Option<String>> = cands.iter().map(|x| modrinth::file_sha512(&x.path).ok()).collect();
        let found = modrinth::updates(&c, &hashes.iter().flatten().cloned().collect::<Vec<_>>(), &ver, ln);
        let res: anyhow::Result<Vec<(Cand, String, String, String)>> = found.map(|found| cands.into_iter().zip(hashes).filter_map(|(x, h)| {
            let v = found.get(&h?)?;
            let notes = modrinth::changelog(&c, &v.id).map(|m| modrinth::plain(&m, 1500)).unwrap_or_default();
            Some((x, v.id.clone(), format!("v{}", crate::mods::short(&v.number, &ver)), notes))
        }).collect());
        let _ = &sh;
        ui_do(&weak, move |ui| {
            if GEN.with(|g| g.get()) != gen { return; }
            let b = ui.global::<Backend>();
            match res {
                Ok(list) if list.is_empty() => { b.set_upd_open(false); show_toast(ui, &sh, "Everything is already up to date"); }
                Ok(list) => {
                    let rows: Vec<UpdRow> = list.iter().map(|(x, _, to, notes)| UpdRow { name: x.name.as_str().into(), from: x.from.as_str().into(), to: to.as_str().into(), notes: notes.as_str().into(), on: true, open: false }).collect();
                    b.set_upd_picked(rows.len() as i32);
                    b.set_upd_rows(ModelRc::new(VecModel::from(rows)));
                    b.set_upd_state(1);
                    PLAN.with(|p| *p.borrow_mut() = Some(Plan { gen, inst, kind, files: list.into_iter().map(|(x, id, _, _)| (x.path, id)).collect() }));
                }
                Err(e) => { b.set_upd_open(false); show_toast(ui, &sh, &format!("Couldn't check for updates: {}", crate::friendly(&e))); }
            }
        });
    });
}

/// Pick / unpick row `i`; `i` < 0 shows or hides the changelog of row `-1 - i`.
pub fn pick(ui: &AppWindow, i: i32, v: bool) {
    let b = ui.global::<Backend>();
    let m = b.get_upd_rows();
    let n = if i < 0 { -1 - i } else { i } as usize;
    if let Some(mut r) = m.row_data(n) { if i < 0 { r.open = v } else { r.on = v } m.set_row_data(n, r); }
    b.set_upd_picked(m.iter().filter(|r| r.on).count() as i32);
}

/// Update the picked rows: snapshot first (old files moved into it), then a toast with UNDO.
pub fn apply(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    let Some(plan) = PLAN.with(|p| p.borrow_mut().take()) else { return };
    if plan.gen != GEN.with(|g| g.get()) { return; }
    let rows: Vec<UpdRow> = b.get_upd_rows().iter().collect();
    b.set_upd_open(false);
    if b.get_instances().iter().any(|i| i.name == plan.inst.as_str() && (i.running || i.starting)) { show_toast(ui, sh, &format!("Close {} before updating its files", plan.inst)); return; }
    let picked: Vec<(PathBuf, String, UpdRow)> = plan.files.into_iter().zip(rows).filter(|(_, r)| r.on).map(|((p, id), r)| (p, id, r)).collect();
    if picked.is_empty() { return; }
    let (kind, inst) = (plan.kind, plan.inst);
    let key = format!("update:{inst}:{kind}");
    crate::job(ui, &key, &format!("Updating {} {}…", picked.len(), what(kind, picked.len())), 0.0);
    let (weak, sh) = (ui.as_weak(), sh.clone());
    std::thread::spawn(move || {
        let c = client();
        let total = picked.len();
        let reason = if total == 1 { format!("Updated {}", picked[0].2.name) } else { format!("Updated {total} {}", what(kind, total)) };
        let res = (|| -> anyhow::Result<(String, usize, Vec<String>)> {
            let mut snap = snapshot::create(&sh.paths, &inst, kind, &reason)?;
            let (mut changes, mut errs) = (vec![], vec![]);
            for (n, (path, id, r)) in picked.iter().enumerate() {
                let (w, k, t) = (weak.clone(), key.clone(), format!("Updating {} ({}/{total})", r.name, n + 1));
                let f = n as f32 / total as f32;
                ui_do(&w, move |ui| crate::job(ui, &k, &t, f));
                let mut keep = |old: &std::path::Path| snapshot::stash(&sh.paths, &inst, &mut snap, old);
                match modrinth::update_to(&c, path, id, &|_, _, _| {}, &mut keep) {
                    Ok(Some(_)) => changes.push(snapshot::Change { name: r.name.to_string(), from: r.from.to_string(), to: r.to.to_string() }),
                    Ok(None) => {}
                    Err(e) => errs.push(format!("{}: {e}", r.name)),
                }
            }
            let done = changes.len();
            snapshot::finish(&sh.paths, &inst, &mut snap, changes)?;
            Ok((snap.id, done, errs))
        })();
        ui_do(&weak, move |ui| {
            crate::job_end(ui, &key);
            ui.global::<Backend>().invoke_refresh_installed();
            match res {
                Ok((_, 0, errs)) if !errs.is_empty() => show_toast(ui, &sh, &format!("Couldn't update: {}", errs[0])),
                Ok((_, 0, _)) => show_toast(ui, &sh, "Everything is already up to date"),
                Ok((id, done, errs)) => {
                    let msg = if errs.is_empty() { format!("Updated {done} {}", what(kind, done)) } else { format!("Updated {done} of {total}; {} failed ({})", errs.len(), errs[0]) };
                    let (sh2, inst2) = (sh.clone(), inst.clone());
                    crate::toast_action(ui, &sh, &msg, false, "UNDO", move |ui| revert(ui, &sh2, &inst2, &id));
                }
                Err(e) => show_toast(ui, &sh, &format!("Couldn't update: {}", crate::friendly(&e))),
            }
        });
    });
}

/// Fill and open the History dialog for instance `inst`.
pub fn history(ui: &AppWindow, sh: &Arc<Shared>, inst: &str) {
    let b = ui.global::<Backend>();
    let rows: Vec<HistRow> = snapshot::list(&sh.paths, inst).into_iter().map(|s| {
        let mut parts: Vec<String> = s.changes.iter().take(2).map(|c| format!("{} {} → {}", c.name, c.from, c.to)).collect();
        if s.changes.len() > 2 { parts.push(format!("+{} more", s.changes.len() - 2)); }
        HistRow { id: s.id.as_str().into(), title: s.reason.as_str().into(), sub: parts.join("  ·  ").into(), when: crate::ago(s.ts as i64).into() }
    }).collect();
    b.set_hist_inst(inst.into());
    b.set_hist_rows(ModelRc::new(VecModel::from(rows)));
    b.set_hist_open(true);
}

/// Undo update `id` of `inst` (worker thread), then refresh lists.
pub fn revert(ui: &AppWindow, sh: &Arc<Shared>, inst: &str, id: &str) {
    if ui.global::<Backend>().get_instances().iter().any(|i| i.name == inst && (i.running || i.starting)) { show_toast(ui, sh, &format!("Close {inst} before undoing an update")); return; }
    let key = format!("revert:{inst}");
    crate::job(ui, &key, &format!("Undoing the update of {inst}…"), -1.0);
    let (weak, sh, inst, id) = (ui.as_weak(), sh.clone(), inst.to_string(), id.to_string());
    std::thread::spawn(move || {
        let res = snapshot::restore(&sh.paths, &inst, &id);
        ui_do(&weak, move |ui| {
            crate::job_end(ui, &key);
            let b = ui.global::<Backend>();
            b.invoke_refresh_installed();
            if b.get_hist_open() && b.get_hist_inst() == inst.as_str() { history(ui, &sh, &inst); }
            match res {
                Ok(r) => show_toast(ui, &sh, &format!("Put back {} file{}{}", r.files, if r.files == 1 { "" } else { "s" }, if r.config { " and the configs" } else { "" })),
                Err(e) => show_toast(ui, &sh, &format!("Couldn't undo the update: {}", crate::friendly(&e))),
            }
        });
    });
}
