//! Resource pack combiner: build one pack from a base pack plus per-category picks from other packs
//! (swords from one, blocks from another, GUI from a third). Models picked from a pack bring the textures
//! and models they reference from that same pack.
//!
//! Ported from Lumes Texture Combiner (Fabric mod) — MIT License, Copyright (c) 2026 Kzxmi.
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};

/// (title, hint) per category, in display order. Index = category id.
pub const CATEGORIES: [(&str, &str); 22] = [
    ("Swords", "Every sword texture and model"),
    ("Tools", "Pickaxes, axes, shovels, hoes, rods, shears, mace"),
    ("Armor", "Armor items, worn armor layers and trims"),
    ("Bows & arrows", "Bows, crossbows, tridents, arrows and projectiles"),
    ("Shield", "Shield item and shield patterns"),
    ("Totem", "Totem of Undying"),
    ("Pearls", "Ender pearls and eyes of ender"),
    ("Potions", "Potions, splash and lingering bottles, XP bottles"),
    ("Food", "Golden apples, steak, carrots and other food"),
    ("Crystal & anchor", "End crystals and respawn anchors"),
    ("Other items", "Every item not covered by a category above"),
    ("Blocks", "Block textures, block models and connected textures"),
    ("Ores", "Ore blocks and ancient debris"),
    ("Mobs & entities", "Mob skins, chests, signs and other entity textures"),
    ("Menus & GUI", "Inventories, containers, buttons, title screen"),
    ("HUD", "Hotbar, hearts, hunger, XP bar and effect icons"),
    ("Crosshair", "The crosshair only"),
    ("Particles", "Particle textures and definitions"),
    ("Font", "Fonts and glyph textures"),
    ("Sky", "Sun, moon, clouds and custom skies"),
    ("Sounds", "Sound files and sounds.json"),
    ("Everything else", "Language files, shaders, colormaps and the rest"),
];
const SWORDS: usize = 0; const TOOLS: usize = 1; const ARMOR: usize = 2; const RANGED: usize = 3; const SHIELD: usize = 4;
const TOTEM: usize = 5; const PEARLS: usize = 6; const POTIONS: usize = 7; const FOOD: usize = 8; const CRYSTAL: usize = 9;
const ITEMS: usize = 10; const BLOCKS: usize = 11; const ORES: usize = 12; const ENTITIES: usize = 13; const GUI: usize = 14;
const HUD: usize = 15; const CROSSHAIR: usize = 16; const PARTICLES: usize = 17; const FONT: usize = 18; const SKY: usize = 19;
const SOUNDS: usize = 20; const MISC: usize = 21;

const ITEM_ROOTS: [&str; 6] = ["textures/item/", "models/item/", "items/", "optifine/cit/", "mcpatcher/cit/", "citresewn/cit/"];
const BLOCK_ROOTS: [&str; 5] = ["textures/block/", "models/block/", "blockstates/", "optifine/ctm/", "mcpatcher/ctm/"];
const TOOL_WORDS: [&str; 8] = ["pickaxe", "axe", "shovel", "hoe", "shears", "mace", "brush", "spyglass"];
const ARMOR_WORDS: [&str; 8] = ["helmet", "chestplate", "leggings", "boots", "elytra", "armor", "trim", "turtle"];
const RANGED_WORDS: [&str; 7] = ["bow", "crossbow", "arrow", "trident", "firework", "projectile", "quiver"];
const FOOD_WORDS: [&str; 25] = ["apple", "bread", "beef", "steak", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon", "potato", "carrot",
    "melon", "cookie", "pie", "stew", "soup", "berries", "honey", "chorus", "kelp", "cake", "beetroot", "pufferfish", "tropical"];
/// Built-in pack the launcher writes itself; never a combiner source.
const SKIP: [&str; 1] = [crate::agent::PACK];
const MAX_DEPENDENCY_FILES: usize = 4000;

/// Category of a pack entry; `None` outside `assets/<namespace>/` (pack.mcmeta, pack.png, ...).
pub fn classify(entry: &str) -> Option<usize> {
    let p = entry.replace('\\', "/").to_lowercase();
    let rest = p.strip_prefix("assets/")?;
    let (ns, p) = rest.split_once('/')?;
    if ns != "minecraft" && ns != "realms" { return Some(MISC); }
    let any = |xs: &[&str]| xs.iter().any(|x| p.starts_with(x));
    Some(if p.starts_with("sounds/") || p == "sounds.json" { SOUNDS }
    else if any(&["font/", "textures/font/"]) { FONT }
    else if any(&["textures/particle/", "particles/"]) { PARTICLES }
    else if any(&["textures/environment/", "optifine/sky/", "mcpatcher/sky/"]) { SKY }
    else if p.starts_with("textures/gui/sprites/hud/crosshair") { CROSSHAIR }
    else if any(&["textures/gui/sprites/hud/", "textures/mob_effect/", "textures/gui/sprites/mob_effect"]) || p == "textures/gui/icons.png" { HUD }
    else if p.starts_with("textures/gui/") { GUI }
    else if p.starts_with("textures/entity/end_crystal/") { CRYSTAL }
    else if any(&["textures/entity/equipment/", "textures/models/armor/", "textures/trims/"]) { ARMOR }
    else if p.starts_with("textures/entity/projectiles/") { RANGED }
    else if p.starts_with("textures/entity/shield") { SHIELD }
    else if p.starts_with("textures/entity/") { ENTITIES }
    else if let Some(r) = ITEM_ROOTS.iter().find_map(|r| p.strip_prefix(r)) { classify_item(strip_ext(r)) }
    else if let Some(r) = BLOCK_ROOTS.iter().find_map(|r| p.strip_prefix(r)) { classify_block(strip_ext(r)) }
    else { MISC })
}

fn words(name: &str) -> Vec<&str> { name.split(['/', '_', '\\', '-', '.', ' ']).filter(|w| !w.is_empty()).collect() }

fn has_word(ws: &[&str], w: &str) -> bool { ws.iter().any(|x| *x == w || x.strip_suffix('s') == Some(w)) }

fn has_any(ws: &[&str], set: &[&str]) -> bool {
    ws.iter().any(|w| set.contains(w) || w.strip_suffix('s').is_some_and(|x| set.contains(&x)) || w.strip_suffix("es").is_some_and(|x| set.contains(&x)))
}

fn classify_item(n: &str) -> usize {
    let ws = words(n);
    if n.contains("sword") { SWORDS }
    else if n.contains("totem") { TOTEM }
    else if n.contains("end_crystal") || n.contains("respawn_anchor") { CRYSTAL }
    else if n.contains("pearl") || n.contains("ender_eye") { PEARLS }
    else if n.contains("potion") || n.contains("glass_bottle") || n.contains("experience_bottle") { POTIONS }
    else if has_word(&ws, "shield") { SHIELD }
    else if has_any(&ws, &ARMOR_WORDS) { ARMOR }
    else if n.contains("carrot_on_a_stick") || n.contains("fishing_rod") || n.contains("flint_and_steel") { TOOLS }
    else if has_any(&ws, &RANGED_WORDS) || n.contains("wind_charge") { RANGED }
    else if has_any(&ws, &TOOL_WORDS) { TOOLS }
    else if has_any(&ws, &FOOD_WORDS) { FOOD }
    else { ITEMS }
}

fn classify_block(n: &str) -> usize {
    if n.contains("respawn_anchor") { CRYSTAL }
    else if has_word(&words(n), "ore") || n.contains("ancient_debris") { ORES }
    else { BLOCKS }
}

/// "a/b/c.png.mcmeta" -> "a/b/c" (extensions of the last segment only).
fn strip_ext(p: &str) -> &str {
    let s = p.rfind('/').map_or(0, |i| i + 1);
    match p[s..].find('.') { Some(d) => &p[..s + d], None => p }
}

/// One source pack: a zip or a folder in `resourcepacks/`.
pub struct Pack {
    /// File or folder name, the pack's id.
    pub id: String,
    path: PathBuf,
    entries: HashSet<String>,
    /// Entries per category.
    files: Vec<Vec<String>>,
}

impl Pack {
    pub fn name(&self) -> &str { self.id.strip_suffix(".zip").or_else(|| self.id.strip_suffix(".ZIP")).unwrap_or(&self.id) }
    pub fn count(&self, cat: usize) -> usize { self.files[cat].len() }

    fn load(path: &Path) -> Result<Pack> {
        let id = path.file_name().and_then(|n| n.to_str()).context("bad name")?.to_string();
        let names: Vec<String> = if path.is_dir() {
            let mut out = Vec::new();
            walk(path, path, &mut out);
            out
        } else {
            let z = zip::ZipArchive::new(std::fs::File::open(path)?)?;
            z.file_names().filter_map(|n| n.ok()).filter(|n| !n.ends_with('/')).map(|n| n.to_string()).collect()
        };
        let mut files = vec![Vec::new(); CATEGORIES.len()];
        for n in &names { if let Some(c) = classify(n) { files[c].push(n.clone()); } }
        for f in &mut files { f.sort(); }
        Ok(Pack { id, path: path.to_path_buf(), entries: names.into_iter().collect(), files })
    }

    fn open(&self) -> Result<Reader> {
        Ok(if self.path.is_dir() { Reader::Dir(self.path.clone()) } else { Reader::Zip(zip::ZipArchive::new(std::fs::File::open(&self.path)?)?) })
    }
}

enum Reader { Dir(PathBuf), Zip(zip::ZipArchive<std::fs::File>) }

impl Reader {
    fn read(&mut self, entry: &str) -> Result<Vec<u8>> {
        match self {
            Reader::Dir(d) => Ok(std::fs::read(d.join(entry))?),
            Reader::Zip(z) => { let mut b = Vec::new(); z.by_name(entry)?.read_to_end(&mut b)?; Ok(b) }
        }
    }
}

fn walk(root: &Path, dir: &Path, out: &mut Vec<String>) {
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    for e in rd.flatten() {
        let p = e.path();
        if p.is_dir() { walk(root, &p, out); }
        else if let Ok(r) = p.strip_prefix(root) { out.push(r.to_string_lossy().replace('\\', "/")); }
    }
}

/// Packs in an instance's `resourcepacks/` folder (zips and folders with pack.mcmeta), sorted by name.
pub fn scan(dir: &Path) -> Vec<Pack> {
    let Ok(rd) = std::fs::read_dir(dir) else { return Vec::new() };
    let mut out: Vec<Pack> = rd.flatten().map(|e| e.path()).filter(|p| {
        let n = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
        !n.starts_with('.') && !SKIP.contains(&n)
            && ((p.is_file() && n.to_lowercase().ends_with(".zip")) || p.join("pack.mcmeta").is_file())
    }).filter_map(|p| Pack::load(&p).ok()).filter(|p| p.files.iter().any(|f| !f.is_empty())).collect();
    out.sort_by_key(|p| p.name().to_lowercase());
    out
}

/// What goes where: `base` = pack index for every category without a pick; `picks[cat]` = None (use base),
/// Some(None) (vanilla: leave the category out), Some(Some(i)) (pack i).
pub struct Recipe {
    pub base: Option<usize>,
    pub picks: Vec<Option<Option<usize>>>,
}

/// Output entry -> (pack index, source entry).
type Plan = BTreeMap<String, (usize, String)>;

fn plan(packs: &[Pack], r: &Recipe) -> Plan {
    let mut files = Plan::new();
    let mut picked: HashMap<usize, Vec<String>> = HashMap::new();
    for cat in 0..CATEGORIES.len() {
        let src = match r.picks.get(cat).copied().flatten() { None => r.base, Some(p) => p };
        let Some(i) = src.filter(|i| *i < packs.len()) else { continue };
        let explicit = r.picks.get(cat).copied().flatten().is_some();
        for f in &packs[i].files[cat] {
            files.insert(f.clone(), (i, f.clone()));
            if explicit && f.ends_with(".json") { picked.entry(i).or_default().push(f.clone()); }
        }
    }
    for (i, jsons) in picked { pull_dependencies(&packs[i], i, jsons, &mut files); }
    files
}

/// Follow resource references in a picked pack's JSON models so textures/models they need come along
/// (only where nothing else already supplies that path).
fn pull_dependencies(pack: &Pack, i: usize, jsons: Vec<String>, files: &mut Plan) {
    let Ok(mut rd) = pack.open() else { return };
    let mut queue: VecDeque<String> = jsons.into();
    let mut seen: HashSet<String> = queue.iter().cloned().collect();
    let mut added = 0;
    while let Some(j) = queue.pop_front() {
        if added >= MAX_DEPENDENCY_FILES { break; }
        let Ok(v) = rd.read(&j).and_then(|b| Ok(serde_json::from_slice::<serde_json::Value>(&b)?)) else { continue };
        for s in collect_refs(&v) {
            for c in candidates(&s) {
                if !pack.entries.contains(&c) || files.contains_key(&c) { continue; }
                files.insert(c.clone(), (i, c.clone()));
                added += 1;
                if c.ends_with(".json") && seen.insert(c.clone()) { queue.push_back(c); }
            }
        }
    }
}

fn collect_refs(v: &serde_json::Value) -> Vec<String> {
    let mut out = Vec::new();
    let mut stack = vec![v];
    while let Some(v) = stack.pop() {
        match v {
            serde_json::Value::String(s) if is_ref(s) => out.push(s.clone()),
            serde_json::Value::Array(a) => stack.extend(a),
            serde_json::Value::Object(o) => stack.extend(o.values()),
            _ => {}
        }
    }
    out
}

/// `^([a-z0-9_.-]+:)?[a-z0-9_.-]+(/[a-z0-9_.-]+)+$`
fn is_ref(s: &str) -> bool {
    let ok = |x: &str| !x.is_empty() && x.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"_.-".contains(&b));
    let path = match s.split_once(':') { Some((ns, p)) => { if !ok(ns) { return false; } p } None => s };
    let segs: Vec<&str> = path.split('/').collect();
    segs.len() >= 2 && segs.iter().all(|x| ok(x))
}

fn candidates(r: &str) -> [String; 3] {
    let (ns, p) = r.split_once(':').unwrap_or(("minecraft", r));
    [format!("assets/{ns}/models/{p}.json"), format!("assets/{ns}/textures/{p}.png"), format!("assets/{ns}/textures/{p}.png.mcmeta")]
}

/// Relative, no "..", no drive or absolute paths.
fn is_safe(e: &str) -> bool {
    !e.is_empty() && !e.starts_with('/') && !e.contains(':') && !e.contains('\\') && e.split('/').all(|s| s != ".." && s != ".")
}

/// Windows-safe pack name, at most 48 chars.
pub fn clean_name(s: &str) -> String {
    let mut n: String = s.chars().filter(|c| !"\\/:*?\"<>|".contains(*c) && !c.is_control()).collect();
    n = n.split_whitespace().collect::<Vec<_>>().join(" ");
    if n.chars().count() > 48 { n = n.chars().take(48).collect::<String>().trim().to_string(); }
    while n.ends_with('.') { n.pop(); n = n.trim().to_string(); }
    n
}

/// Write the combined pack to `<dir>/<name>.zip` (replacing one with that name). Returns (path, file count).
pub fn create(dir: &Path, packs: &[Pack], r: &Recipe, name: &str, progress: &dyn Fn(f32)) -> Result<(PathBuf, usize)> {
    let name = clean_name(name);
    if name.is_empty() { bail!("Give the pack a name"); }
    let files = plan(packs, r);
    if files.is_empty() { bail!("Nothing to combine yet: choose a base pack or a category first"); }
    let out = dir.join(format!("{name}.zip"));
    let tmp = dir.join(format!(".{name}.zip.tmp"));
    let res = write(&tmp, packs, r, &name, &files, progress);
    if let Err(e) = res { let _ = std::fs::remove_file(&tmp); return Err(e); }
    std::fs::rename(&tmp, &out).or_else(|_| { std::fs::copy(&tmp, &out)?; std::fs::remove_file(&tmp) })?;
    Ok((out, files.len()))
}

fn write(tmp: &Path, packs: &[Pack], r: &Recipe, name: &str, files: &Plan, progress: &dyn Fn(f32)) -> Result<()> {
    let mut z = zip::ZipWriter::new(std::fs::File::create(tmp)?);
    let opt = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    // the base pack (else the first pack used) lends its pack_format and icon
    let lead = r.base.or_else(|| files.values().next().map(|v| v.0)).and_then(|i| packs.get(i));
    let mut meta_src = None;
    let mut icon = None;
    if let Some(p) = lead {
        if let Ok(mut rd) = p.open() {
            meta_src = rd.read("pack.mcmeta").ok().and_then(|b| serde_json::from_slice::<serde_json::Value>(strip_bom(&b)).ok());
            icon = rd.read("pack.png").ok();
        }
    }
    let format = meta_src.as_ref().and_then(|m| m["pack"]["pack_format"].as_i64()).unwrap_or(75);
    let meta = serde_json::json!({"pack": {"description": "Combined with Nether Client", "pack_format": format,
        "supported_formats": [1, 999], "min_format": 1, "max_format": 999}});
    z.start_file("pack.mcmeta", opt)?;
    z.write_all(serde_json::to_string_pretty(&meta)?.as_bytes())?;
    if let Some(b) = icon { z.start_file("pack.png", opt)?; z.write_all(&b)?; }
    z.start_file("combined.txt", opt)?;
    z.write_all(credits(packs, r, name, files).as_bytes())?;

    let mut by_pack: BTreeMap<usize, Vec<(&String, &String)>> = BTreeMap::new();
    for (out, (i, src)) in files { by_pack.entry(*i).or_default().push((out, src)); }
    let total = files.len().max(1) as f32;
    let mut done = 0usize;
    for (i, list) in by_pack {
        let mut rd = packs[i].open()?;
        for (out, src) in list {
            done += 1;
            if !is_safe(out) { continue; }
            if let Ok(b) = rd.read(src) { z.start_file(out.as_str(), opt)?; z.write_all(&b)?; }
            if done.is_multiple_of(64) { progress(done as f32 / total); }
        }
    }
    z.finish()?;
    progress(1.0);
    Ok(())
}

fn strip_bom(b: &[u8]) -> &[u8] { b.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(b) }

fn credits(packs: &[Pack], r: &Recipe, name: &str, files: &Plan) -> String {
    let mut s = format!("Combined with Nether Client (pack combiner based on Lumes Texture Combiner by Kzxmi)\nPack: {name}\n\n");
    let used: HashSet<usize> = files.values().map(|v| v.0).collect();
    for (c, (title, _)) in CATEGORIES.iter().enumerate() {
        let src = match r.picks.get(c).copied().flatten() { None => r.base, Some(p) => p };
        let from = src.and_then(|i| packs.get(i)).filter(|p| p.count(c) > 0).map_or("vanilla", |p| p.name());
        s.push_str(&format!("{title:<18} {from}\n"));
    }
    s.push_str(&format!("\n{} files from {} pack(s).\nTextures, models and sounds belong to the creators of the packs listed above.\n", files.len(), used.len()));
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies() {
        assert_eq!(classify("assets/minecraft/textures/item/diamond_sword.png"), Some(SWORDS));
        assert_eq!(classify("assets/minecraft/models/item/netherite_pickaxe.json"), Some(TOOLS));
        assert_eq!(classify("assets/minecraft/textures/item/diamond_helmet.png"), Some(ARMOR));
        assert_eq!(classify("assets/minecraft/textures/item/golden_apple.png"), Some(FOOD));
        assert_eq!(classify("assets/minecraft/textures/block/diamond_ore.png"), Some(ORES));
        assert_eq!(classify("assets/minecraft/textures/block/stone.png"), Some(BLOCKS));
        assert_eq!(classify("assets/minecraft/textures/gui/sprites/hud/crosshair.png"), Some(CROSSHAIR));
        assert_eq!(classify("assets/minecraft/textures/gui/sprites/hud/heart/full.png"), Some(HUD));
        assert_eq!(classify("assets/minecraft/textures/gui/container/inventory.png"), Some(GUI));
        assert_eq!(classify("assets/other/textures/x.png"), Some(MISC));
        assert_eq!(classify("pack.mcmeta"), None);
        assert!(is_ref("minecraft:item/diamond_sword") && is_ref("block/stone") && !is_ref("stone") && !is_ref("Item/X"));
        assert!(!is_safe("../x") && !is_safe("/x") && is_safe("assets/a/b.png"));
        assert_eq!(clean_name("  my: mix??  pack. "), "my mix pack");
    }

    #[test]
    fn combines_with_dependencies() {
        let d = std::env::temp_dir().join(format!("nc-combine-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        let put = |p: &str, b: &[u8]| { let f = d.join(p); std::fs::create_dir_all(f.parent().unwrap()).unwrap(); std::fs::write(f, b).unwrap(); };
        put("A/pack.mcmeta", br#"{"pack":{"pack_format":46,"description":"A"}}"#);
        put("A/assets/minecraft/textures/item/diamond_sword.png", b"a-sword");
        put("A/assets/minecraft/textures/block/stone.png", b"a-stone");
        put("B/pack.mcmeta", br#"{"pack":{"pack_format":46,"description":"B"}}"#);
        put("B/assets/minecraft/models/item/diamond_sword.json", br#"{"textures":{"layer0":"minecraft:custom/blade"}}"#);
        put("B/assets/minecraft/textures/custom/blade.png", b"b-blade");
        put("B/assets/minecraft/textures/block/stone.png", b"b-stone");
        let packs = scan(&d);
        assert_eq!(packs.len(), 2);
        let mut picks = vec![None; CATEGORIES.len()];
        picks[SWORDS] = Some(Some(1));
        let (out, n) = create(&d, &packs, &Recipe { base: Some(0), picks }, "Mix", &|_| {}).unwrap();
        let mut z = zip::ZipArchive::new(std::fs::File::open(&out).unwrap()).unwrap();
        let mut read = |e: &str| { let mut s = String::new(); z.by_name(e).unwrap().read_to_string(&mut s).unwrap(); s };
        assert_eq!(read("assets/minecraft/textures/block/stone.png"), "a-stone");
        assert_eq!(read("assets/minecraft/textures/custom/blade.png"), "b-blade");
        assert!(read("pack.mcmeta").contains("46"));
        assert_eq!(n, 3);
        let _ = std::fs::remove_dir_all(&d);
    }
}
