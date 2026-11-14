//! The bundled client agent (agent/): written to `<root>/agent/` before launch and passed as `-javaagent`.
use std::fs;
use std::path::{Path, PathBuf};

use crate::paths::Paths;

static AGENT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/nether-agent.jar"));
static BOOT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/nether-agent-boot.jar"));
/// Every module and setting of the bundled agent (JSON, from `agent/build/schema.json`).
pub static SCHEMA: &str = include_str!(concat!(env!("OUT_DIR"), "/schema.json"));
/// `nether-media.exe` (crates/media), written next to the jars; the agent finds it beside its `-javaagent` jar.
static MEDIA: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/nether-media.exe"));

/// Global client config shared by all instances (see the Client page).
pub fn global_config(paths: &Paths) -> PathBuf { paths.root.join("client").join("global.json") }

/// Make sure the agent jars on disk match the bundled ones; None if none are bundled or writing fails.
pub fn ensure(paths: &Paths) -> Option<PathBuf> {
    if AGENT.is_empty() || BOOT.is_empty() { return None; }
    let dir = paths.root.join("agent");
    fs::create_dir_all(&dir).ok()?;
    for (name, bytes) in [("nether-agent-boot.jar", BOOT), ("nether-agent.jar", AGENT)] {
        let p = dir.join(name);
        if fs::read(&p).ok().as_deref() != Some(bytes) { fs::write(&p, bytes).ok()?; }
    }
    // optional: a running helper locks the exe (write fails) and keeps the identical old copy
    let p = dir.join("nether-media.exe");
    if cfg!(windows) && !MEDIA.is_empty() && fs::read(&p).ok().as_deref() != Some(MEDIA) { let _ = fs::write(&p, MEDIA); }
    Some(dir.join("nether-agent.jar"))
}

/// Folder name of the built-in resource pack (nametag icon font) in an instance's `resourcepacks/`.
pub const PACK: &str = "Nether Client";
const PACK_META: &str = r#"{"pack":{"description":"Nether Client icons","pack_format":75,"supported_formats":[1,999],"min_format":1,"max_format":999}}"#;
const PACK_FONT: &str = r#"{"providers":[{"type":"bitmap","file":"nether:font/icon.png","ascent":7,"height":8,"chars":[""]}]}"#;
const PACK_ICON: &[u8] = include_bytes!("../res/nether-pack/icon.png");

/// Unicode page e0 for 1.8.9-1.12.2 (no font providers): the 17x24 logo in a 384 px page (one 16-unit cell = 24 px).
const PACK_PAGE: &[u8] = include_bytes!("../res/nether-pack/unicode_page_e0.png");

/// Write the built-in pack into the instance and keep it switched on in options.txt (the agent only shows the
/// nametag icon once its font is loaded). Missing options.txt = first run: the pack is enabled on the next launch.
/// `legacy_jar` = client jar of a pre-1.13 version: the icon goes on unicode page e0 with the jar's glyph_sizes.bin
/// (U+E000 given a full-cell width); those files are removed for newer versions (their unicode font must stay vanilla).
pub fn ensure_pack(game_dir: &Path, legacy_jar: Option<&Path>) {
    let dir = game_dir.join("resourcepacks").join(PACK);
    let (page, sizes) = (dir.join("assets/minecraft/textures/font/unicode_page_e0.png"), dir.join("assets/minecraft/font/glyph_sizes.bin"));
    let glyphs = legacy_jar.and_then(glyph_sizes);
    let mut files: Vec<(PathBuf, &[u8])> = vec![
        (dir.join("pack.mcmeta"), PACK_META.as_bytes()),
        (dir.join("assets/nether/font/icon.json"), PACK_FONT.as_bytes()),
        (dir.join("assets/nether/textures/font/icon.png"), PACK_ICON),
    ];
    match &glyphs {
        Some(g) => { files.push((page, PACK_PAGE)); files.push((sizes, g)); }
        None => { let _ = std::fs::remove_file(&page); let _ = std::fs::remove_file(&sizes); }
    }
    for (f, b) in files {
        if std::fs::read(&f).ok().as_deref() == Some(b) { continue; }
        if let Some(d) = f.parent() { let _ = std::fs::create_dir_all(d); }
        let _ = std::fs::write(&f, b);
    }
    let opts = game_dir.join("options.txt");
    let Ok(text) = std::fs::read_to_string(&opts) else { return };
    // 1.13+: "file/<name>" next to "vanilla". Pre-1.13: folder names, and the pack must also be listed as
    // incompatible or the game drops it (its pack_format is not the version's own).
    let (entry, keys, base): (String, &[&str], &[&str]) = if legacy_jar.is_some() { (PACK.into(), &["resourcePacks", "incompatibleResourcePacks"], &[]) }
        else { (format!("file/{PACK}"), &["resourcePacks"], &["vanilla"]) };
    let mut found = vec![false; keys.len()];
    let lines: Vec<String> = text.lines().map(|l| {
        let Some((k, v)) = l.split_once(':') else { return l.to_string() };
        let Some(i) = keys.iter().position(|x| *x == k) else { return l.to_string() };
        found[i] = true;
        let mut list = serde_json::from_str::<Vec<String>>(v).unwrap_or_else(|_| base.iter().map(|s| s.to_string()).collect());
        if !list.contains(&entry) { list.push(entry.clone()); }
        format!("{k}:{}", serde_json::to_string(&list).unwrap_or_default())
    }).collect();
    let mut out = lines.join("\n");
    for (k, f) in keys.iter().zip(found) {
        if f { continue; }
        let mut list: Vec<String> = base.iter().map(|s| s.to_string()).collect();
        list.push(entry.clone());
        out.push_str(&format!("\n{k}:{}", serde_json::to_string(&list).unwrap_or_default()));
    }
    out.push('\n');
    if out != text { let _ = crate::store::write_atomic(&opts, out.as_bytes()); }
}

/// The jar's font/glyph_sizes.bin with U+E000 spanning the whole cell (columns 0-15).
fn glyph_sizes(jar: &Path) -> Option<Vec<u8>> {
    use std::io::Read;
    let mut z = zip::ZipArchive::new(std::fs::File::open(jar).ok()?).ok()?;
    let mut b = Vec::new();
    z.by_name("assets/minecraft/font/glyph_sizes.bin").ok()?.read_to_end(&mut b).ok()?;
    *b.get_mut(0xE000)? = 0x0B; // columns 0..11 of 16: the logo's 17 of 24 px
    Some(b)
}

#[cfg(test)]
mod pack_tests {
    #[test]
    fn enables_pack_once() {
        let d = std::env::temp_dir().join(format!("nether-pack-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join("options.txt"), "version:4671\nresourcePacks:[\"vanilla\",\"file/Faithful.zip\"]\nfov:0.0\n").unwrap();
        super::ensure_pack(&d, None);
        super::ensure_pack(&d, None);
        let t = std::fs::read_to_string(d.join("options.txt")).unwrap();
        assert!(t.contains(r#"resourcePacks:["vanilla","file/Faithful.zip","file/Nether Client"]"#), "{t}");
        assert!(d.join("resourcepacks/Nether Client/assets/nether/font/icon.json").exists());
        let _ = std::fs::remove_dir_all(&d);
    }
}
