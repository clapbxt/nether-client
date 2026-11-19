//! Instance art pulled from the game itself: each version's title-screen panorama (cover / wallpaper)
//! and block textures (icons). Files are read straight out of Mojang's client jar with HTTP range
//! requests (only the zip directory and the wanted entries are downloaded), or from a local jar.
use std::collections::HashMap;
use std::io::Read;
use std::path::PathBuf;

use anyhow::{anyhow, bail, Result};
use reqwest::blocking::Client;

use crate::paths::Paths;

const PANORAMA: &str = "assets/minecraft/textures/gui/title/background/panorama_0.png";

/// Wallpaper choices: versions whose title panoramas differ (plus the newest release, added at runtime).
pub const LANDMARKS: &[&str] = &["1.8.9", "1.12.2", "1.13.2", "1.14.4", "1.15.2", "1.16.5", "1.17.1", "1.18.2", "1.19.4", "1.20.6", "1.21.1", "1.21.4"];

/// Block textures offered as instance icons (modern names; missing ones are skipped).
pub const BLOCKS: &[&str] = &[
    "grass_block_side", "dirt", "stone", "cobblestone", "oak_planks", "oak_log", "crafting_table_front", "furnace_front_on",
    "bookshelf", "tnt_side", "sand", "gravel", "diamond_ore", "diamond_block", "emerald_block", "gold_block",
    "iron_block", "redstone_block", "lapis_block", "netherrack", "soul_sand", "glowstone", "obsidian", "crying_obsidian",
    "ancient_debris_side", "netherite_block", "end_stone", "purpur_block", "sea_lantern", "magma", "shroomlight", "slime_block",
    "honeycomb_block", "amethyst_block", "cherry_planks", "sculk", "copper_block", "mud_bricks", "jack_o_lantern", "hay_block_side",
    "melon_side", "note_block", "beacon", "spawner", "prismarine_bricks", "blue_ice", "pale_oak_planks", "creaking_heart_awake",
];

fn cache(paths: &Paths) -> PathBuf { paths.root.join("cache").join("art") }

/// Where a version's jar lives: a local file if it was installed, else its download URL.
fn jar_source(c: &Client, paths: &Paths, version: &str) -> Result<(Option<PathBuf>, String)> {
    let local = paths.versions().join(version).join(format!("{version}.jar"));
    if local.exists() { return Ok((Some(local), String::new())); }
    let v = crate::mojang::version_json(c, paths, version)?;
    Ok((None, v.downloads.client.url.clone()))
}

fn range(c: &Client, url: &str, r: &str) -> Result<Vec<u8>> {
    let resp = c.get(url).header("Range", r).send()?.error_for_status()?;
    if resp.status().as_u16() != 206 { bail!("server ignored the range request"); }
    Ok(resp.bytes()?.to_vec())
}

// bounds-checked reads: the bytes come from the network (a short reply reads as 0, never panics)
fn u16le(b: &[u8], o: usize) -> usize { b.get(o..o + 2).map(|s| u16::from_le_bytes([s[0], s[1]]) as usize).unwrap_or(0) }
fn u32le(b: &[u8], o: usize) -> usize { b.get(o..o + 4).map(|s| u32::from_le_bytes([s[0], s[1], s[2], s[3]]) as usize).unwrap_or(0) }

/// Read entries out of a remote zip using range requests.
fn remote_entries(c: &Client, url: &str, want: &dyn Fn(&str) -> bool) -> Result<HashMap<String, Vec<u8>>> {
    let tail = range(c, url, "bytes=-65558")?;
    let eocd = (0..tail.len().saturating_sub(21)).rev().find(|&i| tail[i..i + 4] == [0x50, 0x4b, 0x05, 0x06]).ok_or_else(|| anyhow!("no zip directory"))?;
    let (cd_size, cd_off) = (u32le(&tail, eocd + 12), u32le(&tail, eocd + 16));
    if cd_size == 0 { bail!("empty zip directory"); }
    let cd = range(c, url, &format!("bytes={cd_off}-{}", cd_off + cd_size - 1))?;
    let mut hits = vec![];
    let mut p = 0;
    while p + 46 <= cd.len() && cd[p..p + 4] == [0x50, 0x4b, 0x01, 0x02] {
        let (method, csize) = (u16le(&cd, p + 10), u32le(&cd, p + 20));
        let (nlen, elen, clen) = (u16le(&cd, p + 28), u16le(&cd, p + 30), u16le(&cd, p + 32));
        let off = u32le(&cd, p + 42);
        let Some(raw) = cd.get(p + 46..p + 46 + nlen) else { break };
        let name = String::from_utf8_lossy(raw).to_string();
        if want(&name) { hits.push((name, method, csize, off)); }
        p += 46 + nlen + elen + clen;
    }
    let mut out = HashMap::new();
    for (name, method, csize, off) in hits {
        // local header (30 bytes + name + extra) followed by the data; extra length can differ from the directory's
        let head = range(c, url, &format!("bytes={off}-{}", off + 29))?;
        let start = off + 30 + u16le(&head, 26) + u16le(&head, 28);
        if csize == 0 { continue; }
        let data = range(c, url, &format!("bytes={start}-{}", start + csize - 1))?;
        let bytes = match method {
            0 => data,
            8 => { let mut v = vec![]; flate2::read::DeflateDecoder::new(&data[..]).read_to_end(&mut v)?; v }
            m => bail!("unsupported zip compression {m}"),
        };
        out.insert(name, bytes);
    }
    Ok(out)
}

fn local_entries(jar: &PathBuf, want: &dyn Fn(&str) -> bool) -> Result<HashMap<String, Vec<u8>>> {
    let mut z = zip::ZipArchive::new(std::fs::File::open(jar)?)?;
    let names: Vec<String> = z.file_names().filter_map(|n| n.ok()).filter(|n| want(n)).map(String::from).collect();
    let mut out = HashMap::new();
    for n in names {
        let mut v = vec![];
        z.by_name(&n)?.read_to_end(&mut v)?;
        out.insert(n, v);
    }
    Ok(out)
}

fn entries(c: &Client, paths: &Paths, version: &str, want: &dyn Fn(&str) -> bool) -> Result<HashMap<String, Vec<u8>>> {
    match jar_source(c, paths, version)? {
        (Some(jar), _) => local_entries(&jar, want),
        (None, url) => remote_entries(c, url.as_str(), want),
    }
}

/// The title-screen panorama of `version` as a cached PNG (cover art / wallpaper).
pub fn panorama(c: &Client, paths: &Paths, version: &str) -> Result<PathBuf> {
    let file = cache(paths).join(format!("pano-{version}.png"));
    if file.exists() { return Ok(file); }
    // newer versions ship the panorama through the asset index (the jar only holds a 1x1 placeholder)
    let v = crate::mojang::version_json(c, paths, version)?;
    let idx: crate::mojang::AssetIndex = crate::download::get_json(c, &v.asset_index.url)?;
    let png = match idx.objects.get(&PANORAMA["assets/".len()..]) {
        Some(o) => c.get(format!("https://resources.download.minecraft.net/{}/{}", &o.hash[..2], o.hash)).send()?.error_for_status()?.bytes()?.to_vec(),
        None => {
            let mut e = entries(c, paths, version, &|n| n == PANORAMA)?;
            e.remove(PANORAMA).ok_or_else(|| anyhow!("{version} has no title panorama"))?
        }
    };
    if png.len() < 512 { bail!("{version} has no title panorama"); }
    std::fs::create_dir_all(cache(paths))?;
    let img = image::load_from_memory(&png)?;
    let img = if img.width() > 512 { img.resize(512, 512, image::imageops::FilterType::Triangle) } else { img };
    img.save(&file)?;
    Ok(file)
}

/// Block textures from `version`'s jar as cached 16x16 PNGs (first frame of animated ones), in `BLOCKS` order.
pub fn block_icons(c: &Client, paths: &Paths, version: &str) -> Result<Vec<(String, PathBuf)>> {
    let dir = cache(paths).join("blocks");
    let have: Vec<(String, PathBuf)> = BLOCKS.iter().map(|b| (b.to_string(), dir.join(format!("{b}.png")))).filter(|(_, p)| p.exists()).collect();
    if !have.is_empty() { return Ok(have); }
    let path = |b: &str| format!("assets/minecraft/textures/block/{b}.png");
    let wanted: Vec<String> = BLOCKS.iter().map(|b| path(b)).collect();
    let e = entries(c, paths, version, &|n| wanted.iter().any(|w| w == n))?;
    std::fs::create_dir_all(&dir)?;
    let mut out = vec![];
    for b in BLOCKS {
        let Some(bytes) = e.get(&path(b)) else { continue };
        let Ok(img) = image::load_from_memory(bytes) else { continue };
        let w = img.width();
        let frame = img.crop_imm(0, 0, w, w.min(img.height()));
        let p = dir.join(format!("{b}.png"));
        frame.save(&p)?;
        out.push((b.to_string(), p));
    }
    Ok(out)
}

/// Item textures (`textures/item/<name>.png`) from `version`'s jar as cached PNGs (first frame); skips ones already cached.
pub fn item_icons(c: &Client, paths: &Paths, version: &str, names: &[String]) -> Result<()> {
    let dir = cache(paths).join("items");
    let missing: Vec<&String> = names.iter().filter(|n| !n.is_empty() && !dir.join(format!("{n}.png")).exists()).collect();
    if missing.is_empty() { return Ok(()); }
    let path = |b: &str| format!("assets/minecraft/textures/item/{b}.png");
    let wanted: Vec<String> = missing.iter().map(|b| path(b)).collect();
    let e = entries(c, paths, version, &|n| wanted.iter().any(|w| w == n))?;
    std::fs::create_dir_all(&dir)?;
    for b in missing {
        let Some(bytes) = e.get(&path(b)) else { continue };
        let Ok(img) = image::load_from_memory(bytes) else { continue };
        let w = img.width();
        img.crop_imm(0, 0, w, w.min(img.height())).save(dir.join(format!("{b}.png")))?;
    }
    Ok(())
}

/// Path of a cached item icon (None if not downloaded yet).
pub fn item_icon_path(paths: &Paths, name: &str) -> Option<PathBuf> {
    let p = cache(paths).join("items").join(format!("{name}.png"));
    (!name.is_empty() && p.exists()).then_some(p)
}

/// Path of a cached block icon (None if not downloaded yet).
pub fn block_icon_path(paths: &Paths, name: &str) -> Option<PathBuf> {
    let p = cache(paths).join("blocks").join(format!("{name}.png"));
    p.exists().then_some(p)
}

/// Official update key art: the hero images of Mojang's release articles on minecraft.net, one per version family
/// (the art Lunar's version cards use). `top` / `bottom` (fractions of the height) crop away the logo and title Mojang
/// paints into some of them, so our own type can sit on the art. 1.8 never had key art; its card uses the Elder
/// Guardian art from Mojang's Bountiful-era Mob Menagerie article (ocean monuments were 1.8's headline).
pub struct KeyArt { pub fam: &'static str, pub update: &'static str, url: &'static str, top: f32, bottom: f32 }

const MCNET: &str = "https://www.minecraft.net/content/dam/minecraftnet";

pub const KEY_ART: &[KeyArt] = &[
    KeyArt { fam: "26", update: "Wilderness Bound", url: "games/minecraft/screenshots/MC-Updates_Carousel-N-0_2026_FGD-26_01_1000x563.png", top: 0.18, bottom: 0.76 },
    KeyArt { fam: "1.21", update: "Tricky Trials", url: "games/minecraft/game-updates/Editorial_article_TrickyTrials_launch_1170x500.jpg", top: 0.0, bottom: 1.0 },
    KeyArt { fam: "1.20", update: "Trails & Tales", url: "games/minecraft/screenshots/1_20_header.jpg", top: 0.0, bottom: 1.0 },
    KeyArt { fam: "1.19", update: "The Wild Update", url: "games/minecraft/key-art/Xbox_Minecraft_WildUpdate_Main_.Net_1170x500.jpg", top: 0.28, bottom: 1.0 },
    KeyArt { fam: "1.18", update: "Caves & Cliffs: Part II", url: "games/minecraft/key-art/CC2_KeyArt_1140x565.jpg", top: 0.28, bottom: 0.83 },
    KeyArt { fam: "1.17", update: "Caves & Cliffs: Part I", url: "games/minecraft/screenshots/1-17-release-header.jpg", top: 0.0, bottom: 1.0 },
    KeyArt { fam: "1.16", update: "Nether Update", url: "games/minecraft/key-art/nether-header.jpg", top: 0.0, bottom: 1.0 },
    KeyArt { fam: "1.12", update: "World of Color", url: "archive/b1c06cfe1e13734b3da8912f424e75ac-mc112_header.png", top: 0.0, bottom: 1.0 },
    KeyArt { fam: "1.8", update: "Bountiful Update", url: "games/minecraft/game-characters/mm_eg_header.jpg", top: 0.0, bottom: 1.0 },
];

/// The key-art family of a version ("1.21.4" -> "1.21", "26.1.2" -> "26"), if it has art.
pub fn key_family(version: &str) -> Option<&'static str> {
    let mut it = version.split(['.', '-', ' ']);
    let major = it.next()?;
    let fam = if major == "1" { format!("1.{}", it.next()?) } else { major.to_string() };
    KEY_ART.iter().find(|k| k.fam == fam).map(|k| k.fam)
}

/// A family's key art as a cached JPEG (cropped, at most 1600 px wide). Network on the first call; call off the UI thread.
pub fn key_art(c: &Client, paths: &Paths, fam: &str) -> Result<PathBuf> {
    let k = KEY_ART.iter().find(|k| k.fam == fam).ok_or_else(|| anyhow!("no key art for {fam}"))?;
    let file = cache(paths).join(format!("key-{fam}.jpg"));
    if file.exists() { return Ok(file); }
    let bytes = c.get(format!("{MCNET}/{}", k.url)).send()?.error_for_status()?.bytes()?;
    let img = image::load_from_memory(&bytes)?;
    let (w, h) = (img.width(), img.height());
    let (y0, y1) = ((h as f32 * k.top) as u32, ((h as f32 * k.bottom) as u32).min(h));
    let img = img.crop_imm(0, y0, w, y1.saturating_sub(y0).max(1));
    let img = if img.width() > 1600 { img.resize(1600, 1600, image::imageops::FilterType::Lanczos3) } else { img };
    std::fs::create_dir_all(cache(paths))?;
    let tmp = file.with_extension("part");
    img.to_rgb8().save_with_format(&tmp, image::ImageFormat::Jpeg)?;
    std::fs::rename(&tmp, &file)?;
    Ok(file)
}

/// Path of a family's cached key art (None until `key_art` fetched it).
pub fn key_art_path(paths: &Paths, fam: &str) -> Option<PathBuf> {
    let p = cache(paths).join(format!("key-{fam}.jpg"));
    p.exists().then_some(p)
}

#[cfg(test)]
mod key_tests {
    #[test]
    fn families() {
        assert_eq!(super::key_family("1.21.11"), Some("1.21"));
        assert_eq!(super::key_family("26.1.2"), Some("26"));
        assert_eq!(super::key_family("1.8.9"), Some("1.8"));
        assert_eq!(super::key_family("1.20"), Some("1.20"));
        assert_eq!(super::key_family("1.7.10"), None);
    }
}
