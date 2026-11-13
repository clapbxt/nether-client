//! L2: restore points of the opened instance (Overview > RESTORE POINTS): make one now, restore as a new
//! instance or in place, delete (Recycle Bin), and the weekly automatic one before a launch.
use std::cell::RefCell;
use std::path::PathBuf;
use std::sync::Arc;

use nether_core::instances::{self, InstanceData};
use nether_core::restore;
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::{show_toast, ui_do, AppWindow, Backend, Inst, RestorePt, Shared};

thread_local! {
    /// files behind the shown rows
    static FILES: RefCell<Vec<PathBuf>> = const { RefCell::new(Vec::new()) };
}

fn current(ui: &AppWindow) -> Option<Inst> {
    let b = ui.global::<Backend>();
    b.get_instances().row_data(b.get_current_inst().max(0) as usize).filter(|_| b.get_current_inst() >= 0)
}

/// Local (y, m, d, h, min) of a unix time; Windows applies the time zone rules of that date.
#[cfg(windows)]
fn local(unix: u64) -> Option<(u16, u16, u16, u16, u16)> {
    use windows_sys::Win32::Foundation::{FILETIME, SYSTEMTIME};
    use windows_sys::Win32::System::Time::{FileTimeToSystemTime, SystemTimeToTzSpecificLocalTime};
    let t = (unix + 11_644_473_600) * 10_000_000;
    let ft = FILETIME { dwLowDateTime: t as u32, dwHighDateTime: (t >> 32) as u32 };
    let (mut utc, mut loc): (SYSTEMTIME, SYSTEMTIME) = unsafe { (std::mem::zeroed(), std::mem::zeroed()) };
    // SAFETY: plain value structs, valid pointers for the duration of the calls
    let ok = unsafe { FileTimeToSystemTime(&ft, &mut utc) != 0 && SystemTimeToTzSpecificLocalTime(std::ptr::null(), &utc, &mut loc) != 0 };
    ok.then_some((loc.wYear, loc.wMonth, loc.wDay, loc.wHour, loc.wMinute))
}
#[cfg(not(windows))]
fn local(unix: u64) -> Option<(u16, u16, u16, u16, u16)> {
    let d = (unix / 86_400) as i64;
    let (s, z) = (unix % 86_400, d + 719_468);
    let (era, doe) = (z.div_euclid(146_097), z.rem_euclid(146_097));
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let (dd, m) = (doy - (153 * mp + 2) / 5 + 1, if mp < 10 { mp + 3 } else { mp - 9 });
    Some(((yoe + era * 400 + (m <= 2) as i64) as u16, m as u16, dd as u16, (s / 3600) as u16, (s / 60 % 60) as u16))
}

/// "Today · 13:40", "Yesterday · 09:12", "12 Nov 2026 · 13:40".
fn date(unix: u64) -> String {
    const MON: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    let Some((y, m, d, h, mi)) = local(unix) else { return crate::ago(unix as i64) };
    let now = crate::now().max(0) as u64;
    let day = |t: u64| local(t).map(|(y, m, d, ..)| (y, m, d));
    let when = if day(now) == Some((y, m, d)) { "Today".to_string() }
        else if day(now.saturating_sub(86_400)) == Some((y, m, d)) { "Yesterday".to_string() }
        else { format!("{d} {} {y}", MON[(m.clamp(1, 12) - 1) as usize]) };
    format!("{when} · {h:02}:{mi:02}")
}

fn kind_label(k: &str) -> &'static str {
    match k { restore::AUTO => "AUTO", restore::PRE_RESTORE => "BEFORE RESTORE", _ => "MANUAL" }
}

/// List the opened instance's restore points.
pub fn load(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    let pts = current(ui).map(|i| restore::list(&sh.paths, &i.name)).unwrap_or_default();
    let rows: Vec<RestorePt> = pts.iter().map(|p| RestorePt {
        file: p.file.to_string_lossy().to_string().into(), date: date(p.created).into(), when: crate::ago(p.created as i64).into(),
        kind: kind_label(&p.kind).into(), size: crate::instpage::size(p.size_bytes).into(),
    }).collect();
    FILES.with(|f| *f.borrow_mut() = pts.into_iter().map(|p| p.file).collect());
    b.set_rpoints(ModelRc::new(VecModel::from(rows)));
}

fn busy(ui: &AppWindow, on: bool) { ui.global::<Backend>().set_rp_busy(on); }

/// BACK UP NOW.
pub fn create(ui: &AppWindow, sh: &Arc<Shared>) {
    let Some(i) = current(ui) else { return };
    let (d, key, weak, sh) = (crate::data_of(&i), format!("restore:{}", i.name), ui.as_weak(), sh.clone());
    busy(ui, true);
    crate::job(ui, &key, &format!("Saving a restore point of {}…", i.name), 0.0);
    std::thread::spawn(move || {
        let (w2, k2, n2) = (weak.clone(), key.clone(), d.name.clone());
        let res = restore::create(&sh.paths, &d, restore::MANUAL, 0, &move |f| {
            let (k, n) = (k2.clone(), n2.clone());
            ui_do(&w2, move |ui| crate::job(ui, &k, &format!("Saving a restore point of {n}…"), f));
        });
        ui_do(&weak, move |ui| {
            busy(ui, false);
            match crate::job_done(ui, &key, res) {
                Ok(_) => show_toast(ui, &sh, &format!("Restore point of {} saved", d.name)),
                Err(e) => show_toast(ui, &sh, &format!("Couldn't save a restore point: {e}")),
            }
            load(ui, &sh);
        });
    });
}

/// Restore row `row`: as a new instance (`in_place` false, the original stays as it is) or in place.
pub fn restore(ui: &AppWindow, sh: &Arc<Shared>, row: i32, in_place: bool) {
    let b = ui.global::<Backend>();
    b.set_rp_ask(-1);
    let Some(i) = current(ui) else { return };
    let Some(file) = FILES.with(|f| f.borrow().get(row.max(0) as usize).cloned()) else { return };
    if in_place && (i.running || i.starting) { show_toast(ui, sh, &format!("Close {} before restoring it", i.name)); return; }
    let taken: Vec<String> = b.get_instances().iter().map(|x| x.name.to_string()).collect();
    let new_name = instances::free_name(&sh.paths, &format!("{} (restored)", i.name), &taken);
    let (cur, key, weak, sh) = (crate::data_of(&i), format!("restore:{}", i.name), ui.as_weak(), sh.clone());
    let label = if in_place { format!("Restoring {}…", i.name) } else { format!("Restoring as {new_name}…") };
    busy(ui, true);
    crate::job(ui, &key, &label, 0.0);
    std::thread::spawn(move || {
        let (w2, k2, l2) = (weak.clone(), key.clone(), label.clone());
        let progress = move |f: f32| { let (k, l) = (k2.clone(), l2.clone()); ui_do(&w2, move |ui| crate::job(ui, &k, &l, f)); };
        let res = if in_place { restore::restore_in_place(&sh.paths, &cur, &file, &progress) } else { restore::restore_as_new(&sh.paths, &file, &new_name, &progress) };
        ui_do(&weak, move |ui| {
            busy(ui, false);
            match crate::job_done(ui, &key, res) {
                Ok(d) => {
                    put(ui, &sh, &d, in_place.then_some(cur.name.as_str()));
                    show_toast(ui, &sh, &if in_place { format!("{} is back to that restore point", d.name) } else { format!("Restored as {}", d.name) });
                }
                Err(e) => show_toast(ui, &sh, &format!("Couldn't restore: {e}")),
            }
            load(ui, &sh);
        });
    });
}

/// Put the restored entry in the list: replace `replace` (in place) or add it.
fn put(ui: &AppWindow, sh: &Arc<Shared>, d: &InstanceData, replace: Option<&str>) {
    let m = ui.global::<Backend>().get_instances();
    let mut row = crate::inst_from(d);
    row.mods = instances::mod_files(&sh.paths, &d.name).len() as i32;
    match replace.and_then(|n| m.iter().position(|x| x.name == n)) {
        Some(p) => m.set_row_data(p, row),
        None => if let Some(v) = m.as_any().downcast_ref::<VecModel<Inst>>() { v.push(row) },
    }
    crate::persist(&sh.paths, &m);
    crate::refilter(ui);
    crate::artui::refresh(ui, sh);
}

/// Delete row `row` (asks; the zip goes to the Recycle Bin).
pub fn delete(ui: &AppWindow, sh: &Arc<Shared>, row: i32) {
    let Some(i) = current(ui) else { return };
    let Some(file) = FILES.with(|f| f.borrow().get(row.max(0) as usize).cloned()) else { return };
    let when = ui.global::<Backend>().get_rpoints().row_data(row.max(0) as usize).map(|r| r.date.to_string()).unwrap_or_default();
    let sh = sh.clone();
    crate::instpage::ask(ui, "Delete this restore point?", &format!("The restore point from {when} goes to the Recycle Bin. {} itself doesn't change.", i.name), "DELETE", true, move |ui| {
        let res = restore::delete(&sh.paths, &i.name, &file);
        if let Err(e) = res { show_toast(ui, &sh, &format!("Couldn't delete the restore point: {e}")); }
        load(ui, &sh);
    });
}

/// Before a launch (launch thread): the weekly automatic point, when the instance asks for one.
pub fn auto_before_launch(sh: &Shared, name: &str, progress: &dyn Fn(f32)) {
    let Some(d) = instances::load(&sh.paths).and_then(|l| l.into_iter().find(|d| d.name == name)) else { return };
    if !restore::due(&sh.paths, &d) { return; }
    let keep = if d.backup_keep == 0 { 5 } else { d.backup_keep as usize };
    if let Err(e) = restore::create(&sh.paths, &d, restore::AUTO, keep, progress) { crate::applog::line(&format!("automatic restore point of {name} failed: {e:#}")); }
}
