//! Friends panel (L3, opt-in): your friend code, requests, friends who are hosting (one-click join), sharing your own
//! hosted server. Hidden unless the Nether API reports Friends enabled. All network work runs on worker threads.
use std::sync::Arc;

use nether_core::friends::{self, State};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::{show_toast, ui_do, AppWindow, Backend, FriendRow, HostState, Shared};

/// Ask the service whether Friends is on (startup); shows the top-bar button when it is.
pub fn probe(ui: &AppWindow) {
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let on = friends::enabled(&nether_core::download::client());
        ui_do(&weak, move |ui| ui.global::<Backend>().set_fr_enabled(on));
    });
}

fn apply(ui: &AppWindow, s: &State) {
    let b = ui.global::<Backend>();
    b.set_fr_on(s.on);
    b.set_fr_code(if s.code.len() == 8 { format!("{}-{}", &s.code[..4], &s.code[4..]) } else { s.code.clone() }.into());
    let rows: Vec<FriendRow> = s.friends.iter().map(|f| FriendRow {
        uuid: f.uuid.as_str().into(),
        name: f.name.as_str().into(),
        state: match f.state.as_str() { "incoming" => 1, "outgoing" => 2, _ => 0 },
        host: f.host.clone().unwrap_or_default().into(),
    }).collect();
    b.set_fr_list(ModelRc::new(VecModel::from(rows)));
}

/// Run one Friends call off the UI thread, then show its result (or its error in the panel).
fn run(ui: &AppWindow, sh: &Arc<Shared>, call: impl FnOnce(&nether_core::Session) -> anyhow::Result<State> + Send + 'static) {
    let Some(session) = sh.session.lock().unwrap_or_else(|e| e.into_inner()).clone() else {
        ui.global::<Backend>().set_fr_msg("Sign in with Microsoft to use Friends.".into());
        return;
    };
    let b = ui.global::<Backend>();
    if b.get_fr_busy() { return; }
    b.set_fr_busy(true);
    b.set_fr_msg("".into());
    let weak = ui.as_weak();
    std::thread::spawn(move || {
        let r = call(&session);
        ui_do(&weak, move |ui| {
            let b = ui.global::<Backend>();
            b.set_fr_busy(false);
            b.set_fr_loaded(true);
            match r {
                Ok(s) => apply(ui, &s),
                Err(e) => b.set_fr_msg(cap_first(&format!("{e:#}")).into()),
            }
        });
    });
}

fn cap_first(s: &str) -> String {
    let mut c = s.chars();
    c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default()
}

pub fn wire(ui: &AppWindow, sh: &Arc<Shared>) {
    let b = ui.global::<Backend>();
    let (w, s) = (ui.as_weak(), sh.clone());
    b.on_fr_refresh(move || if let Some(ui) = w.upgrade() { run(&ui, &s, |ss| friends::state(&nether_core::download::client(), ss)) });
    let (w, s) = (ui.as_weak(), sh.clone());
    b.on_fr_turn_on(move || if let Some(ui) = w.upgrade() { run(&ui, &s, |ss| friends::turn_on(&nether_core::download::client(), ss)) });
    let (w, s) = (ui.as_weak(), sh.clone());
    b.on_fr_turn_off(move || if let Some(ui) = w.upgrade() {
        let s2 = s.clone();
        crate::instpage::ask(&ui, "Turn off Friends?", "Your friend code, friends and requests are deleted from the Nether service. You can turn Friends on again later, but you'll need to add everyone again.", "TURN OFF", true,
            move |ui| run(ui, &s2, |ss| friends::turn_off(&nether_core::download::client(), ss)));
    });
    let (w, s) = (ui.as_weak(), sh.clone());
    b.on_fr_new_code(move || if let Some(ui) = w.upgrade() { run(&ui, &s, |ss| friends::new_code(&nether_core::download::client(), ss)) });
    let (w, s) = (ui.as_weak(), sh.clone());
    b.on_fr_add(move |code| if let Some(ui) = w.upgrade() {
        let code = code.to_string();
        run(&ui, &s, move |ss| friends::add(&nether_core::download::client(), ss, &code));
        ui.global::<Backend>().set_fr_input("".into());
    });
    let (w, s) = (ui.as_weak(), sh.clone());
    b.on_fr_respond(move |uuid, action| if let Some(ui) = w.upgrade() {
        let (uuid, action) = (uuid.to_string(), action.to_string());
        let name = ui.global::<Backend>().get_fr_list().iter().find(|r| r.uuid.as_str() == uuid).map(|r| r.name.to_string()).unwrap_or_default();
        let confirm = match action.as_str() {
            "remove" => Some((format!("Remove {name}?"), format!("{name} is removed from your friends and stops seeing your server."), "REMOVE")),
            "block" => Some((format!("Block {name}?"), format!("{name}'s requests are dropped from now on. They aren't told."), "BLOCK")),
            _ => None,
        };
        let s2 = s.clone();
        let go = move |ui: &AppWindow| run(ui, &s2, move |ss| friends::respond(&nether_core::download::client(), ss, &uuid, &action));
        match confirm {
            Some((title, text, yes)) => crate::instpage::ask(&ui, &title, &text, yes, true, go),
            None => go(&ui),
        }
    });
    let (w, s) = (ui.as_weak(), sh.clone());
    b.on_fr_join(move |name, host| if let Some(ui) = w.upgrade() {
        let (s2, addr) = (s.clone(), host.to_string());
        let text = format!("Nether will ask {addr} for its version, then start a fitting instance. The server learns your IP, as with any server you join.");
        crate::instpage::ask(&ui, &format!("Join {name}'s server?"), &text, "JOIN", false, move |ui| crate::deeplink::check(ui, &s2, addr));
    });
    let (w, s) = (ui.as_weak(), sh.clone());
    b.on_fr_share(move |on| if let Some(ui) = w.upgrade() {
        let addr = ui.global::<HostState>().get_addr().to_string();
        let Some(session) = s.session.lock().unwrap_or_else(|e| e.into_inner()).clone() else { return };
        if on && addr.is_empty() { show_toast(&ui, &s, "Start a server on the Host page first"); ui.global::<Backend>().set_fr_sharing(false); return; }
        let (weak, s2) = (ui.as_weak(), s.clone());
        std::thread::spawn(move || {
            let r = friends::share_host(&nether_core::download::client(), &session, on.then_some(addr.as_str()), 240);
            ui_do(&weak, move |ui| match r {
                Ok(()) => show_toast(ui, &s2, if on { "Friends can see your server for the next 4 hours" } else { "Stopped sharing your server" }),
                Err(e) => { ui.global::<Backend>().set_fr_sharing(!on); show_toast(ui, &s2, &format!("{e:#}")); }
            });
        });
    });
}
