//! L1: pre-launch check dialog and the one-click fixes it shares with the crash page.
use std::sync::Arc;

use nether_core::crash::{Action, Finding};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::{show_toast, AppWindow, Backend, CrashItem, Shared};

/// A start that waits for the user's answer (quick play target kept).
pub(crate) struct Held { pub name: String, pub quick: Option<nether_core::launch::QuickPlay> }

thread_local! {
    static PENDING: std::cell::RefCell<Option<(Held, Vec<Finding>)>> = const { std::cell::RefCell::new(None) };
}

/// Button text for a fix ("" = none).
pub fn act_label(a: &Option<Action>) -> String {
    match a {
        Some(Action::MoreRam) => "GIVE IT MORE RAM".into(),
        Some(Action::SetRam(n)) => format!("USE {n} GB"),
        Some(Action::AutoJava) => "USE AUTO JAVA".into(),
        Some(Action::Disable(_)) => "DISABLE IT".into(),
        Some(Action::Install(s)) => { let s = s.to_uppercase(); if s.chars().count() > 18 { "INSTALL IT".into() } else { format!("INSTALL {s}") } }
        Some(Action::Revert(_)) => "UNDO UPDATE".into(),
        None => String::new(),
    }
}

pub fn rows(f: &[Finding]) -> Vec<CrashItem> {
    f.iter().map(|f| CrashItem { title: f.title.as_str().into(), text: f.explanation.as_str().into(), fix: f.fix.as_str().into(), act: act_label(&f.action).into() }).collect()
}

/// Open the dialog for a start that found problems. UI thread.
pub(crate) fn show(ui: &AppWindow, held: Held, found: Vec<Finding>) {
    let b = ui.global::<Backend>();
    b.set_pre_inst(held.name.as_str().into());
    b.set_pre_items(ModelRc::new(VecModel::from(rows(&found))));
    PENDING.with(|p| *p.borrow_mut() = Some((held, found)));
    b.set_pre_open(true);
}

/// Run fix `i` and take its row off the list.
pub fn fix(ui: &AppWindow, sh: &Arc<Shared>, i: i32) {
    let Some((name, f)) = PENDING.with(|p| p.borrow_mut().as_mut().and_then(|(h, f)| (i >= 0 && (i as usize) < f.len()).then(|| (h.name.clone(), f.remove(i as usize))))) else { return };
    if let Some(a) = &f.action { apply(ui, sh, &name, a); }
    let rest = PENDING.with(|p| p.borrow().as_ref().map(|(_, f)| rows(f)).unwrap_or_default());
    ui.global::<Backend>().set_pre_items(ModelRc::new(VecModel::from(rest)));
}

/// LAUNCH ANYWAY (remaining findings stay quiet for this instance) / PLAY once everything is fixed.
pub fn launch(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    b.set_pre_open(false);
    let Some((held, rest)) = PENDING.with(|p| p.borrow_mut().take()) else { return };
    let keys: Vec<String> = rest.iter().map(nether_core::preflight::key).collect();
    if !keys.is_empty() { if let Err(e) = nether_core::preflight::ignore(&sh.paths, &held.name, &keys) { crate::applog::line(&format!("preflight ignore: {e:#}")); } }
    // current settings (a fix may just have changed RAM or Java)
    let Some(i) = b.get_instances().iter().find(|i| i.name == held.name.as_str()) else { return };
    crate::start_launch(ui.as_weak(), sh.clone(), i.name.to_string(), i.ver.to_string(), i.ram, i.loader.to_string(), crate::JAVAS[i.java.clamp(0, 4) as usize], i.shared, held.quick, false);
}

/// Apply a one-click fix to instance `name`. UI thread.
pub fn apply(ui: &AppWindow, sh: &Arc<Shared>, name: &str, a: &Action) {
    let b = ui.global::<Backend>();
    let mut msg = String::new();
    match a {
        Action::MoreRam => {
            let cap = (b.get_sys_ram() * 3 / 4).max(4);
            crate::edit_inst(ui, sh, name, |x| {
                let new = (x.ram + 2).min(cap);
                msg = if new > x.ram { format!("{name} now gets {new} GB") } else { format!("{name} already has as much RAM as this PC can spare; remove heavy mods instead") };
                x.ram = new;
            });
        }
        Action::SetRam(n) => { let n = *n as i32; crate::edit_inst(ui, sh, name, |x| x.ram = n); msg = format!("{name} now gets {n} GB"); }
        Action::AutoJava => { crate::edit_inst(ui, sh, name, |x| x.java = 0); msg = format!("{name} now picks Java automatically"); }
        Action::Disable(p) => {
            let file = p.file_name().and_then(|n| n.to_str()).unwrap_or("the mod").to_string();
            msg = match nether_core::instances::set_mod_enabled(p, false) { Ok(_) => format!("Disabled {file}"), Err(e) => format!("Couldn't disable {file}: {e}") };
            b.invoke_refresh_installed();
        }
        Action::Install(slug) => {
            if let Some(i) = b.get_instances().iter().find(|i| i.name == name) {
                crate::mods::install(ui.as_weak(), sh.clone(), slug.clone(), None, slug.clone(), i.ver.to_string(), i.loader.to_string(), name.to_string(), 0, None);
            }
        }
        Action::Revert(id) => crate::snapshot::revert(ui, sh, name, id),
    }
    if !msg.is_empty() { show_toast(ui, sh, &msg); }
}
