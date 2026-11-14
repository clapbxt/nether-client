//! Combine packs dialog (instance Packs tab): base pack + per-category picks -> one new resource pack zip.
use std::cell::RefCell;
use std::sync::Arc;

use nether_core::combine::{self, Pack, Recipe, CATEGORIES};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::{show_toast, ui_do, AppWindow, Backend, CombineCat, Combiner, Shared};

thread_local! {
    /// Packs of the instance the dialog is open for (UI thread); dropdown index 2+ = pack index.
    static PACKS: RefCell<(String, Arc<Vec<Pack>>)> = Default::default();
}

/// Open the dialog for instance `idx`: read its resource packs on a worker thread.
pub fn start(ui: &AppWindow, sh: &Arc<Shared>, idx: i32) {
    let Some(i) = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize) else { return };
    let c = ui.global::<Combiner>();
    c.set_open(true);
    c.set_loading(true);
    c.set_busy(false);
    c.set_base(0);
    c.set_cats(ModelRc::new(VecModel::<CombineCat>::default()));
    let dir = nether_core::instances::content_dir(&sh.paths, &i.name, 1);
    let (weak, name) = (ui.as_weak(), i.name.to_string());
    std::thread::spawn(move || {
        let packs = combine::scan(&dir);
        ui_do(&weak, move |ui| {
            let c = ui.global::<Combiner>();
            let names: Vec<slint::SharedString> = packs.iter().map(|p| p.name().into()).collect();
            let mut base = vec![slint::SharedString::from("None (vanilla)")];
            base.extend(names.iter().cloned());
            let mut choices = vec![slint::SharedString::from("From base"), "Vanilla".into()];
            choices.extend(names);
            c.set_packs(ModelRc::new(VecModel::from(base)));
            c.set_choices(ModelRc::new(VecModel::from(choices)));
            c.set_base(if packs.is_empty() { 0 } else { 1 });
            PACKS.with(|p| *p.borrow_mut() = (name, Arc::new(packs)));
            refresh(ui);
            c.set_loading(false);
        });
    });
}

/// Rebuild the category rows (keeps picks; the "from" text follows the base pack).
fn refresh(ui: &AppWindow) {
    let c = ui.global::<Combiner>();
    let old = c.get_cats();
    let base = c.get_base();
    PACKS.with(|p| {
        let packs = &p.borrow().1;
        let rows: Vec<CombineCat> = CATEGORIES.iter().enumerate().map(|(k, (t, h))| {
            let pick = old.row_data(k).map_or(0, |r| r.pick);
            let src = match pick { 0 => (base > 0).then(|| (base - 1) as usize), 1 => None, n => Some((n - 2) as usize) };
            let n = src.and_then(|i| packs.get(i)).map_or(0, |p| p.count(k));
            let note = match (pick, src) {
                (1, _) | (_, None) => "Vanilla".to_string(),
                _ if n == 0 => "Nothing in this pack: vanilla".to_string(),
                _ => format!("{n} files"),
            };
            CombineCat { title: (*t).into(), hint: (*h).into(), pick, note: note.into() }
        }).collect();
        c.set_cats(ModelRc::new(VecModel::from(rows)));
    });
}

pub fn pick(ui: &AppWindow, cat: i32, v: i32) {
    let c = ui.global::<Combiner>();
    if cat < 0 {
        c.set_base(v);
    } else if let Some(mut r) = c.get_cats().row_data(cat as usize) {
        r.pick = v;
        c.get_cats().set_row_data(cat as usize, r);
    }
    refresh(ui);
}

/// CREATE: write `<name>.zip` into the instance's resourcepacks folder.
pub fn create(ui: &AppWindow, sh: &Arc<Shared>) {
    let c = ui.global::<Combiner>();
    if c.get_busy() { return; }
    let (inst, packs) = PACKS.with(|p| p.borrow().clone());
    let base = c.get_base();
    let recipe = Recipe {
        base: (base > 0).then(|| (base - 1) as usize),
        picks: c.get_cats().iter().map(|r| match r.pick { 0 => None, 1 => Some(None), n => Some(Some((n - 2) as usize)) }).collect(),
    };
    let name = c.get_name().to_string();
    c.set_busy(true);
    c.set_prog(0.0);
    let dir = nether_core::instances::content_dir(&sh.paths, &inst, 1);
    let (weak, sh, w2) = (ui.as_weak(), sh.clone(), ui.as_weak());
    std::thread::spawn(move || {
        let prog = move |f: f32| { ui_do(&w2, move |ui| ui.global::<Combiner>().set_prog(f)); };
        let res = combine::create(&dir, &packs, &recipe, &name, &prog);
        ui_do(&weak, move |ui| {
            let c = ui.global::<Combiner>();
            c.set_busy(false);
            match res {
                Ok((p, n)) => {
                    c.set_open(false);
                    let file = p.file_name().map(|f| f.to_string_lossy().to_string()).unwrap_or_default();
                    show_toast(ui, &sh, &format!("{file} created ({n} files). Turn it on in the game's Resource Packs menu"));
                    ui.global::<Backend>().invoke_refresh_installed();
                }
                Err(e) => crate::toast(ui, &sh, &format!("Couldn't combine packs: {e}"), true, None),
            }
        });
    });
}
