//! Client page: the in-game agent's modules and every one of their settings, edited in the launcher.
//! Module/setting definitions come from the agent itself (`agent/build/schema.json`, embedded at build time),
//! so this page and the in-game menu always show the same thing.
//! Values: global `client/global.json`; an instance may opt out (`use_global: false`) into `<instance>/nether-client.json`.
use std::path::PathBuf;
use std::sync::Arc;

use serde::Deserialize;
use serde_json::{json, Value};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::{AppWindow, Backend, ClientMod, ClientState, SetVal, Shared};

#[derive(Deserialize, Clone)]
struct SSet { t: i32, key: String, label: String, min: f64, max: f64, step: f64, def: Value, #[serde(default)] opts: Vec<String>, #[serde(default)] hint: String }

#[derive(Deserialize, Clone)]
struct SMod { id: String, name: String, cat: String, group: String, desc: String, hud: bool, on: bool, x: f64, y: f64, settings: Vec<SSet>, #[serde(default)] icon: String }

#[derive(Deserialize)]
struct Schema { modules: Vec<SMod> }

/// Nether's own module glyph (`design/module-icons/<id>.svg`, ice + red, also bundled in the agent jar).
fn mod_icon(id: &str) -> Option<slint::Image> {
    macro_rules! svgs { ($($i:literal),*) => { match id { $($i => include_bytes!(concat!("../../../design/module-icons/", $i, ".svg")).as_slice(),)* _ => return None } } }
    let b = svgs!("fps", "cps", "coords", "clock", "session", "memory", "keys", "crosshair", "zoom",
        "fullbright", "armor", "potions", "ping", "packs", "server", "worldtime", "speed", "compass", "waypoints");
    slint::Image::load_from_svg_data(b).ok()
}

/// Item icon names of every module (for art::item_icons; fallback for ids without a glyph).
pub fn icon_names() -> Vec<String> { schema().iter().map(|m| m.icon.clone()).filter(|i| !i.is_empty()).collect() }

fn schema() -> &'static [SMod] {
    static S: std::sync::OnceLock<Vec<SMod>> = std::sync::OnceLock::new();
    S.get_or_init(|| serde_json::from_str::<Schema>(nether_core::agent::SCHEMA).map(|s| s.modules).unwrap_or_default())
}

fn file(sh: &Shared, inst: Option<&str>) -> PathBuf {
    match inst {
        Some(name) => sh.paths.instance(name).join("nether-client.json"),
        None => sh.paths.root.join("client").join("global.json"),
    }
}

/// The config as an object; a damaged file is moved aside (kept, never silently overwritten).
fn read_json(p: &std::path::Path) -> Value {
    match nether_core::store::read_json::<Value>(p) {
        Ok(nether_core::store::Load::Ok(v)) if v.is_object() => v,
        _ => json!({}),
    }
}

/// Atomic write (temp file + rename): the agent, which reloads this file live, never reads half of it.
fn write_json(p: &std::path::Path, v: &Value) {
    if let Err(e) = nether_core::store::write_atomic(p, &serde_json::to_vec_pretty(v).unwrap_or_default()) { crate::applog::line(&format!("saving {} failed: {e}", p.display())); }
}

fn uses_global(v: &Value) -> bool { v.get("use_global").and_then(|b| b.as_bool()).unwrap_or(true) || !v["modules"].is_object() }

/// The instance's own module config, if it opted out of the global one (what the agent gets as `nether.instanceConfig`).
pub fn instance_file(sh: &Shared, name: &str) -> Option<PathBuf> {
    let p = file(sh, Some(name));
    (!uses_global(&read_json(&p))).then_some(p)
}

/// Old instance configs carried `inject` / `groups` (client on/off, module groups): every instance now gets the whole client, so drop them.
pub fn migrate(sh: &Shared, name: &str) {
    let p = file(sh, Some(name));
    let mut v = read_json(&p);
    let Some(o) = v.as_object_mut() else { return };
    if o.remove("inject").is_some() | o.remove("groups").is_some() { write_json(&p, &v); }
}

/// "#RRGGBB" / "#RRGGBBAA" (alpha last); anything else = transparent.
fn parse_hex(h: &str) -> slint::Color {
    let d = h.trim_start_matches('#');
    let b = |i: usize| u8::from_str_radix(d.get(i..i + 2).unwrap_or(""), 16).ok();
    match (d.len(), b(0), b(2), b(4)) {
        (6 | 8, Some(r), Some(g), Some(bl)) => slint::Color::from_argb_u8(if d.len() == 8 { b(6).unwrap_or(255) } else { 255 }, r, g, bl),
        _ => slint::Color::default(),
    }
}

fn glfw_label(code: i64) -> String {
    match code {
        -1 | 0 => "NONE".into(),
        340 => "LEFT SHIFT".into(), 344 => "RIGHT SHIFT".into(), 341 => "LEFT CTRL".into(), 345 => "RIGHT CTRL".into(),
        342 => "LEFT ALT".into(), 346 => "RIGHT ALT".into(), 258 => "TAB".into(), 32 => "SPACE".into(), 280 => "CAPS LOCK".into(),
        65..=90 | 48..=57 => ((code as u8) as char).to_string(),
        290..=301 => format!("F{}", code - 289),
        n => format!("KEY {n}"),
    }
}

/// Value of modules.<id>.<key>: instance (when it has its own) over global over the schema default.
fn value<'a>(own: Option<&'a Value>, global: &'a Value, id: &str, key: &str) -> Option<&'a Value> {
    own.and_then(|o| o["modules"][id].get(key)).filter(|v| !v.is_null()).or_else(|| global["modules"][id].get(key).filter(|v| !v.is_null()))
}

fn rows(own: Option<&Value>, global: &Value) -> Vec<ClientMod> {
    let mods = schema();
    mods.iter().enumerate().map(|(n, m)| {
        let get = |k: &str| value(own, global, &m.id, k);
        let num = |k: &str, d: f64| get(k).and_then(|v| v.as_f64()).unwrap_or(d);
        // ACTION buttons run inside the game, so they are not listed here
        let sets: Vec<SetVal> = m.settings.iter().filter(|s| s.t != 7).map(|s| {
            let v = get(&s.key).unwrap_or(&s.def);
            let str_v = v.as_str().map(String::from).unwrap_or_else(|| s.def.as_str().unwrap_or_default().to_string());
            let n = v.as_f64().or_else(|| s.def.as_f64()).unwrap_or(0.0);
            let hex = if s.t == 2 { str_v.trim().to_uppercase() } else { String::new() };
            let rainbow = hex == "CHROMA";
            SetVal {
                hint: s.hint.as_str().into(), unset: s.t == 4 && n < 0.0, rainbow, col: if rainbow { slint::Color::default() } else { parse_hex(&hex) },
                t: s.t, key: s.key.as_str().into(), label: s.label.as_str().into(),
                min: s.min as f32, max: s.max as f32, step: s.step as f32, num: n as f32,
                on: v.as_bool().or_else(|| s.def.as_bool()).unwrap_or(false),
                str: if s.t == 4 { glfw_label(n as i64).into() } else if s.t == 2 { hex.as_str().into() } else { str_v.as_str().into() },
                opts: ModelRc::new(VecModel::from(s.opts.iter().map(|o| o.as_str().into()).collect::<Vec<slint::SharedString>>())),
                pick: s.opts.iter().position(|o| *o == str_v).unwrap_or(0) as i32,
            }
        }).collect();
        let ci = mods[..n].iter().filter(|e| e.cat == m.cat).count() as i32;
        let own = mod_icon(&m.id);
        let item = if own.is_some() { None } else { nether_core::art::item_icon_path(&nether_core::paths::Paths::new(), &m.icon).and_then(|p| crate::artui::img(&p)) };
        let pixel = item.is_some();
        let icon = own.or(item);
        let variant = if m.id == "armor" { get("layout").and_then(|v| v.as_str()).unwrap_or("List").to_string() } else { String::new() };
        ClientMod {
            id: m.id.as_str().into(), name: m.name.as_str().into(), desc: m.desc.as_str().into(), cat: m.cat.to_uppercase().into(), ci, hud: m.hud,
            on: get("enabled").and_then(|v| v.as_bool()).unwrap_or(m.on), moved: false,
            x: num("x", m.x) as f32, y: num("y", m.y) as f32, scale: num("scale", 1.0) as f32,
            group: m.group.as_str().into(), variant: variant.into(),
            has_icon: icon.is_some(), pixel_icon: pixel, icon: icon.unwrap_or_default(),
            sets: ModelRc::new(VecModel::from(sets)),
        }
    }).collect()
}

fn inst_name(ui: &AppWindow, scope: i32) -> Option<String> {
    (scope >= 0).then(|| ui.global::<Backend>().get_instances().row_data(scope as usize).map(|i| i.name.to_string())).flatten()
}

/// Show the modules for `scope` (-1 global, else instance index).
pub fn load(ui: &AppWindow, sh: &Arc<Shared>, scope: i32) {
    let cs = ui.global::<ClientState>();
    let global = read_json(&file(sh, None));
    let name = inst_name(ui, scope);
    if let Some(n) = &name { migrate(sh, n); }
    let inst = name.as_ref().map(|n| read_json(&file(sh, Some(n))));
    let own = inst.as_ref().filter(|v| !uses_global(v));
    cs.set_scope(scope);
    cs.set_use_global(own.is_none());
    cs.set_sel(-1);
    let mut cats: Vec<String> = vec!["ALL".into()];
    for m in schema() { let c = m.cat.to_uppercase(); if !cats.contains(&c) { cats.push(c); } }
    cs.set_cats(ModelRc::new(VecModel::from(cats.iter().map(|c| c.as_str().into()).collect::<Vec<slint::SharedString>>())));
    cs.set_modules(ModelRc::new(VecModel::from(rows(own, &global))));
    cs.set_menu_key(glfw_label(global.get("menuKey").and_then(|k| k.as_i64()).unwrap_or(344)).into());
}

/// The file edits go to: the instance's own config, else the global one.
fn target(ui: &AppWindow, sh: &Shared) -> PathBuf {
    let cs = ui.global::<ClientState>();
    match inst_name(ui, cs.get_scope()) {
        Some(n) if !cs.get_use_global() => file(sh, Some(&n)),
        _ => file(sh, None),
    }
}

fn put(ui: &AppWindow, sh: &Shared, id: &str, key: &str, v: Value) {
    let p = target(ui, sh);
    let mut root = read_json(&p);
    if !root["modules"].is_object() { root["modules"] = json!({}); }
    if !root["modules"][id].is_object() { root["modules"][id] = json!({}); }
    root["modules"][id][key] = v;
    write_json(&p, &root);
}

/// Save module on/off + HUD position/size of every row (after a toggle or a drag).
pub fn save(ui: &AppWindow, sh: &Arc<Shared>) {
    let p = target(ui, sh);
    let mut root = read_json(&p);
    if !root["modules"].is_object() { root["modules"] = json!({}); }
    for m in ui.global::<ClientState>().get_modules().iter() {
        let id = m.id.as_str();
        if !root["modules"][id].is_object() { root["modules"][id] = json!({}); }
        let e = &mut root["modules"][id];
        // only what the user changed: defaults stay unwritten, and an unmoved box keeps its auto layout / anchor
        let def = schema().iter().find(|s| s.id == id).map(|s| s.on);
        if e.get("enabled").is_some() || Some(m.on) != def { e["enabled"] = m.on.into(); }
        if m.hud && m.moved {
            e["x"] = (m.x as f64).into(); e["y"] = (m.y as f64).into();
            // the agent re-derives its anchor from x/y; a stale anchor would win over them
            if let Some(o) = e.as_object_mut() { for k in ["anchor", "ox", "oy"] { o.remove(k); } }
        }
        if m.hud && (e.get("scale").is_some() || m.scale != 1.0) { e["scale"] = (m.scale as f64).into(); }
    }
    write_json(&p, &root);
    let mods = ui.global::<ClientState>().get_modules();
    for n in 0..mods.row_count() { if let Some(mut m) = mods.row_data(n) { if m.moved { m.moved = false; mods.set_row_data(n, m); } } }
}

/// One setting changed on the settings panel: update the row and write it.
pub fn set(ui: &AppWindow, sh: &Arc<Shared>, row: i32, key: &str, v: Value) {
    let cs = ui.global::<ClientState>();
    let mods = cs.get_modules();
    let Some(mut m) = mods.row_data(row.max(0) as usize) else { return };
    for i in 0..m.sets.row_count() {
        let Some(mut s) = m.sets.row_data(i) else { continue };
        if s.key != key { continue; }
        match &v {
            Value::Bool(b) => s.on = *b,
            Value::Number(n) => { s.num = n.as_f64().unwrap_or(0.0) as f32; }
            Value::String(t) => { s.str = t.as_str().into(); s.pick = s.opts.iter().position(|o| o == t.as_str()).unwrap_or(0) as i32; }
            _ => {}
        }
        m.sets.set_row_data(i, s);
    }
    if key == "scale" { m.scale = v.as_f64().unwrap_or(1.0) as f32; }
    if m.id == "armor" && key == "layout" { m.variant = v.as_str().unwrap_or("List").into(); }
    mods.set_row_data(row as usize, m.clone());
    put(ui, sh, m.id.as_str(), key, v);
}

/// Instance: follow the global setup, or take a copy of it as its own.
pub fn set_use_global(ui: &AppWindow, sh: &Arc<Shared>, on: bool) {
    let cs = ui.global::<ClientState>();
    let Some(n) = inst_name(ui, cs.get_scope()) else { return };
    let p = file(sh, Some(&n));
    let mut root = read_json(&p);
    root["use_global"] = on.into();
    if !on && !root["modules"].is_object() { root["modules"] = read_json(&file(sh, None))["modules"].clone(); }
    if !root["modules"].is_object() { root["modules"] = json!({}); }
    write_json(&p, &root);
    load(ui, sh, cs.get_scope());
}

/// New instance (setup wizard): where its module settings come from:
/// 0 the global setup, 1 its own copy of the global setup, 2 the pack's built-in settings laid over the global setup.
pub fn setup_instance(paths: &nether_core::paths::Paths, inst: &str, mode: i32, preset: Option<&Value>) {
    let p = paths.instance(inst).join("nether-client.json");
    let mut root = read_json(&p);
    root.as_object_mut().map(|o| { o.remove("inject"); o.remove("groups"); });
    root["use_global"] = (mode == 0).into();
    if mode != 0 {
        let mut m = read_json(&paths.root.join("client").join("global.json"))["modules"].clone();
        if !m.is_object() { m = json!({}); }
        if let (2, Some(Value::Object(pm))) = (mode, preset) {
            for (id, kv) in pm {
                if !m[id].is_object() { m[id] = json!({}); }
                if let Value::Object(kv) = kv { for (k, v) in kv { m[id][k] = v.clone(); } }
            }
        }
        root["modules"] = m;
    }
    write_json(&p, &root);
}
