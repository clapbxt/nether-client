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
