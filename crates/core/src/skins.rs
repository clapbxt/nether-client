//! Skin library: PNG files + `index.json` under `<root>/skins/`, import (file, username, URL), and
//! upload/reset on the signed-in Minecraft account.
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, bail, Context, Result};
use base64::Engine;
use image::RgbaImage;
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha1::{Digest, Sha1};

use crate::paths::Paths;

const MAX_FILE: u64 = 1024 * 1024;
const MAX_URL: u64 = 512 * 1024;
const SKIN_API: &str = "https://api.minecraftservices.com/minecraft/profile/skins";
static INDEX_LOCK: Mutex<()> = Mutex::new(());

/// Arm style.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Model { Classic, Slim }

impl Model {
    /// `classic` / `slim`, as the Mojang API spells it.
    pub fn as_str(self) -> &'static str { match self { Model::Classic => "classic", Model::Slim => "slim" } }
}

/// One library entry.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Skin {
    /// File stem of the PNG in the library.
    pub id: String,
    pub name: String,
    pub model: Model,
    /// Unix seconds.
    pub added: u64,
    /// "file:<name>", "user:<name>", "url:<url>".
    pub source: String,
}

fn dir(paths: &Paths) -> PathBuf { paths.root.join("skins") }
fn index_file(paths: &Paths) -> PathBuf { dir(paths).join("index.json") }
fn unix_now() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) }

/// Path of a library skin's PNG.
pub fn png_path(paths: &Paths, id: &str) -> PathBuf { dir(paths).join(format!("{id}.png")) }

/// All skins, newest first.
pub fn list(paths: &Paths) -> Vec<Skin> {
    let mut v: Vec<Skin> = fs::read(index_file(paths)).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
    v.sort_by_key(|a| std::cmp::Reverse(a.added));
    v
}

fn save(paths: &Paths, list: &[Skin]) -> Result<()> {
    fs::create_dir_all(dir(paths))?;
    let tmp = dir(paths).join("index.json.part");
    fs::write(&tmp, serde_json::to_vec_pretty(list)?)?;
    fs::rename(&tmp, index_file(paths))?;
    Ok(())
}

/// Decode and check a skin PNG: must be 64x64 or legacy 64x32.
pub fn validate(png: &[u8]) -> Result<RgbaImage> {
    if png.len() as u64 > MAX_FILE { bail!("that file is too large to be a skin"); }
    let img = image::load_from_memory_with_format(png, image::ImageFormat::Png).map_err(|_| anyhow!("not a valid PNG image"))?.to_rgba8();
    match img.dimensions() {
        (64, 64) | (64, 32) => Ok(img),
        (w, h) => bail!("skins must be 64x64 or 64x32 pixels (this one is {w}x{h})"),
    }
}

/// Slim ("Alex") arms: the outer column of both arm textures is fully transparent. Legacy 64x32 skins are classic.
pub fn detect_model(img: &RgbaImage) -> Model {
    if img.height() < 64 { return Model::Classic; }
    let clear = |x0: u32, y0: u32| (x0..x0 + 2).all(|x| (y0..y0 + 12).all(|y| img.get_pixel(x, y)[3] == 0));
    // right arm back face spills into x 54-55 (y 20-31) on classic; left arm into x 46-47 (y 52-63)
    if clear(54, 20) && clear(46, 52) { Model::Slim } else { Model::Classic }
}

fn add(paths: &Paths, png: &[u8], name: &str, model: Option<Model>, source: String) -> Result<Skin> {
    let img = validate(png)?;
    let model = model.unwrap_or_else(|| detect_model(&img));
    let mut h = Sha1::new();
    h.update(png);
    h.update(model.as_str());
    let id = hex::encode(h.finalize())[..12].to_string();
    let _g = INDEX_LOCK.lock().unwrap();
    let mut all = list(paths);
    if let Some(s) = all.iter().find(|s| s.id == id) { return Ok(s.clone()); }
    fs::create_dir_all(dir(paths))?;
    fs::write(png_path(paths, &id), png)?;
    let name = name.trim();
    let skin = Skin { id, name: if name.is_empty() { "Skin".into() } else { name.chars().take(48).collect() }, model, added: unix_now(), source };
    all.push(skin.clone());
    save(paths, &all)?;
    Ok(skin)
}

/// Keep the account's current skin in the library (so changing skins never loses it). true = it was new.
pub fn add_account(paths: &Paths, png: &[u8], model: Model, account: &str) -> Result<bool> {
    let before = list(paths).len();
    add(paths, png, &format!("{account} skin"), Some(model), format!("account:{account}"))?;
    Ok(list(paths).len() > before)
}

/// Add a skin from a PNG file. Model is auto-detected unless `model` is given; name defaults to the file name.
pub fn add_file(paths: &Paths, file: &Path, name: Option<&str>, model: Option<Model>) -> Result<Skin> {
    let len = fs::metadata(file).with_context(|| format!("reading {}", file.display()))?.len();
    if len > MAX_FILE { bail!("that file is too large to be a skin"); }
    let stem = file.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let fname = file.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    add(paths, &fs::read(file)?, name.unwrap_or(&stem), model, format!("file:{fname}"))
}

/// Decode a `textures` property value into (skin url, model).
pub fn parse_textures(b64: &str) -> Result<(String, Model)> {
    let raw = base64::engine::general_purpose::STANDARD.decode(b64.trim()).context("bad textures property")?;
    let v: Value = serde_json::from_slice(&raw).context("bad textures property")?;
    let skin = v["textures"]["SKIN"].as_object().ok_or_else(|| anyhow!("this player has no custom skin"))?;
    let url = skin["url"].as_str().ok_or_else(|| anyhow!("skin has no url"))?.to_string();
    let model = if skin.get("metadata").and_then(|m| m["model"].as_str()) == Some("slim") { Model::Slim } else { Model::Classic };
    Ok((url, model))
}

fn read_capped(r: reqwest::blocking::Response, cap: u64) -> Result<Vec<u8>> {
    if r.content_length().map(|l| l > cap).unwrap_or(false) { bail!("the image is too large"); }
    let mut buf = Vec::new();
    r.take(cap + 1).read_to_end(&mut buf)?;
    if buf.len() as u64 > cap { bail!("the image is too large"); }
    Ok(buf)
}

fn is_name(s: &str) -> bool { (3..=16).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_') }

/// Import the current skin of a Minecraft account by username (Mojang name lookup -> session server textures).
pub fn import_username(c: &Client, paths: &Paths, username: &str) -> Result<Skin> {
    let username = username.trim();
    if !is_name(username) { bail!("'{username}' is not a valid Minecraft name"); }
    let r = c.get(format!("https://api.mojang.com/users/profiles/minecraft/{username}")).send()?;
    let p: Value = match r.status().as_u16() {
        200 => r.json()?,
        204 | 404 => bail!("no Minecraft account named '{username}'"),
        429 => bail!("Mojang is rate limiting lookups, try again in a minute"),
        s => bail!("Mojang lookup failed (HTTP {s})"),
    };
    let id = p["id"].as_str().ok_or_else(|| anyhow!("lookup returned no id"))?;
    let name = p["name"].as_str().unwrap_or(username).to_string();
    let r = c.get(format!("https://sessionserver.mojang.com/session/minecraft/profile/{id}")).send()?;
    if r.status().as_u16() == 429 { bail!("Mojang is rate limiting lookups, try again in a minute"); }
    let prof: Value = r.error_for_status()?.json()?;
    let tex = prof["properties"].as_array().and_then(|a| a.iter().find(|p| p["name"] == "textures")).and_then(|p| p["value"].as_str())
        .ok_or_else(|| anyhow!("{name} has no skin textures"))?;
    let (url, model) = parse_textures(tex)?;
    if !url.starts_with("https://") && !url.starts_with("http://textures.minecraft.net/") { bail!("unexpected skin host"); }
    let url = url.replacen("http://", "https://", 1);
    let png = read_capped(c.get(&url).send()?.error_for_status()?, MAX_URL)?;
    add(paths, &png, &name, Some(model), format!("user:{name}"))
}

/// True for https URLs whose host is not localhost or a private/loopback address literal.
pub fn url_allowed(url: &str) -> bool {
    let Ok(u) = reqwest::Url::parse(url) else { return false };
    if u.scheme() != "https" { return false; }
    match u.host() {
        Some(url::Host::Domain(d)) => { let d = d.to_ascii_lowercase(); d != "localhost" && !d.ends_with(".localhost") && !d.ends_with(".local") }
        Some(url::Host::Ipv4(ip)) => crate::net::is_public_ipv4(ip),
        Some(url::Host::Ipv6(_)) | None => false,
    }
}

/// Import a skin from a direct image URL (https only, 512 KiB cap, redirects must stay on https).
pub fn import_url(paths: &Paths, url: &str, name: Option<&str>) -> Result<Skin> {
    if !url_allowed(url) { bail!("only public https image links can be imported"); }
    let c = Client::builder()
        .user_agent("NetherClient/0.1")
        .timeout(Duration::from_secs(30))
        .redirect(reqwest::redirect::Policy::custom(|a| {
            if a.previous().len() < 5 && url_allowed(a.url().as_str()) { a.follow() } else { a.stop() }
        }))
        .build()?;
    let r = c.get(url).send()?;
    if !r.status().is_success() { bail!("the link answered HTTP {}", r.status().as_u16()); }
    let png = read_capped(r, MAX_URL)?;
    let guess = reqwest::Url::parse(url).ok().and_then(|u| u.path_segments().and_then(|mut s| s.next_back().map(String::from))).unwrap_or_default();
    let guess = guess.trim_end_matches(".png").to_string();
    add(paths, &png, name.unwrap_or(if guess.is_empty() { "Skin" } else { &guess }), None, format!("url:{url}"))
}

/// Rename a skin.
pub fn rename(paths: &Paths, id: &str, name: &str) -> Result<()> {
    let _g = INDEX_LOCK.lock().unwrap();
    let mut all = list(paths);
    let s = all.iter_mut().find(|s| s.id == id).ok_or_else(|| anyhow!("no such skin"))?;
    let name = name.trim();
    if name.is_empty() { bail!("the name cannot be empty"); }
    s.name = name.chars().take(48).collect();
    save(paths, &all)
}

/// Change the stored arm style (when auto-detection guessed wrong). Takes effect for the next upload.
pub fn set_model(paths: &Paths, id: &str, model: Model) -> Result<()> {
    let _g = INDEX_LOCK.lock().unwrap();
    let mut all = list(paths);
    all.iter_mut().find(|s| s.id == id).ok_or_else(|| anyhow!("no such skin"))?.model = model;
    save(paths, &all)
}

/// Delete a skin from the library (the account's active skin is unaffected).
pub fn delete(paths: &Paths, id: &str) -> Result<()> {
    let _g = INDEX_LOCK.lock().unwrap();
    let mut all = list(paths);
    let n = all.len();
    all.retain(|s| s.id != id);
    if all.len() == n { bail!("no such skin"); }
    save(paths, &all)?;
    let _ = fs::remove_file(png_path(paths, id));
    Ok(())
}

/// Copy a skin's PNG to `dest` (a file path, or a folder: then `<name>.png` is created in it).
pub fn export(paths: &Paths, id: &str, dest: &Path) -> Result<PathBuf> {
    let s = list(paths).into_iter().find(|s| s.id == id).ok_or_else(|| anyhow!("no such skin"))?;
    let out = if dest.is_dir() {
        let safe: String = s.name.chars().map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == ' ' { c } else { '_' }).collect();
        dest.join(format!("{safe}.png"))
    } else { dest.to_path_buf() };
    fs::copy(png_path(paths, id), &out).with_context(|| format!("writing {}", out.display()))?;
    Ok(out)
}

// ---- account (Minecraft Services) ----

/// Multipart body for the skin upload: `variant` and `file` parts. Returns (content-type, body).
pub fn upload_body(png: &[u8], model: Model) -> (String, Vec<u8>) {
    let mut h = Sha1::new();
    h.update(png);
    let boundary = format!("----NetherClient{}", &hex::encode(h.finalize())[..16]);
    let mut b = Vec::new();
    b.extend_from_slice(format!("--{boundary}\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n{}\r\n", model.as_str()).as_bytes());
    b.extend_from_slice(format!("--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n").as_bytes());
    b.extend_from_slice(png);
    b.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    (format!("multipart/form-data; boundary={boundary}"), b)
}

fn upload_request(c: &Client, token: &str, png: &[u8], model: Model) -> Result<reqwest::blocking::Request> {
    let (ct, body) = upload_body(png, model);
    Ok(c.post(SKIN_API).bearer_auth(token).header("Content-Type", ct).body(body).build()?)
}

fn check(r: reqwest::blocking::Response) -> Result<()> {
    match r.status().as_u16() {
        200..=299 => Ok(()),
        400 => bail!("Mojang rejected that skin image"),
        401 | 403 => bail!("your Minecraft session expired, please sign in again"),
        429 => bail!("Mojang is rate limiting skin changes, try again in a minute"),
        s => bail!("Mojang answered HTTP {s}"),
    }
}

/// Set the signed-in account's skin to PNG bytes (needs a Minecraft access token).
pub fn upload(c: &Client, token: &str, png: &[u8], model: Model) -> Result<()> {
    validate(png)?;
    check(c.execute(upload_request(c, token, png, model)?)?)
}

/// Set the signed-in account's skin to a library skin.
pub fn apply(c: &Client, paths: &Paths, token: &str, id: &str) -> Result<()> {
    let s = list(paths).into_iter().find(|s| s.id == id).ok_or_else(|| anyhow!("no such skin"))?;
    upload(c, token, &fs::read(png_path(paths, id))?, s.model)
}

/// Reset the signed-in account to its default skin.
pub fn reset(c: &Client, token: &str) -> Result<()> {
    check(c.delete(format!("{SKIN_API}/active")).bearer_auth(token).send()?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::Rgba;

    fn png(img: &RgbaImage) -> Vec<u8> {
        let mut out = Vec::new();
        img.write_to(&mut std::io::Cursor::new(&mut out), image::ImageFormat::Png).unwrap();
        out
    }
    fn opaque(w: u32, h: u32) -> RgbaImage { RgbaImage::from_pixel(w, h, Rgba([120, 80, 40, 255])) }
    fn slim() -> RgbaImage {
        let mut i = opaque(64, 64);
        for y in 20..32 { for x in 54..56 { i.put_pixel(x, y, Rgba([0, 0, 0, 0])); } }
        for y in 52..64 { for x in 46..48 { i.put_pixel(x, y, Rgba([0, 0, 0, 0])); } }
        i
    }
    fn root(tag: &str) -> Paths { Paths { root: std::env::temp_dir().join(format!("nc-skins-{tag}-{}", std::process::id())) } }

    #[test]
    fn slim_detection() {
        assert_eq!(detect_model(&opaque(64, 64)), Model::Classic);
        assert_eq!(detect_model(&slim()), Model::Slim);
        assert_eq!(detect_model(&opaque(64, 32)), Model::Classic);
        let mut half = slim();
        half.put_pixel(54, 25, Rgba([1, 2, 3, 255]));
        assert_eq!(detect_model(&half), Model::Classic);
    }

    #[test]
    fn validation() {
        assert!(validate(&png(&opaque(64, 64))).is_ok());
        assert!(validate(&png(&opaque(64, 32))).is_ok());
        assert!(validate(&png(&opaque(128, 128))).is_err());
        assert!(validate(&png(&opaque(32, 32))).is_err());
        assert!(validate(b"not a png").is_err());
    }

    #[test]
    fn textures_property() {
        let json = r#"{"timestamp":1,"profileId":"x","profileName":"Notch","textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/abc","metadata":{"model":"slim"}}}}"#;
        let b64 = base64::engine::general_purpose::STANDARD.encode(json);
        assert_eq!(parse_textures(&b64).unwrap(), ("http://textures.minecraft.net/texture/abc".into(), Model::Slim));
        let json = r#"{"textures":{"SKIN":{"url":"https://textures.minecraft.net/texture/abc"}}}"#;
        assert_eq!(parse_textures(&base64::engine::general_purpose::STANDARD.encode(json)).unwrap().1, Model::Classic);
        assert!(parse_textures(&base64::engine::general_purpose::STANDARD.encode(r#"{"textures":{}}"#)).is_err());
        assert!(parse_textures("!!!").is_err());
    }

    #[test]
    fn url_rules() {
        assert!(url_allowed("https://example.com/skin.png"));
        assert!(!url_allowed("http://example.com/skin.png"));
        assert!(!url_allowed("https://localhost/skin.png"));
        assert!(!url_allowed("https://192.168.1.1/skin.png"));
        assert!(!url_allowed("https://127.0.0.1/skin.png"));
        assert!(!url_allowed("file:///c:/skin.png"));
        assert!(!url_allowed("not a url"));
    }

    #[test]
    fn library_lifecycle() {
        let p = root("life");
        let src = p.root.join("in");
        fs::create_dir_all(&src).unwrap();
        fs::write(src.join("alex.png"), png(&slim())).unwrap();
        fs::write(src.join("steve.png"), png(&opaque(64, 64))).unwrap();
        fs::write(src.join("bad.png"), png(&opaque(10, 10))).unwrap();
        let a = add_file(&p, &src.join("alex.png"), None, None).unwrap();
        assert_eq!((a.name.as_str(), a.model), ("alex", Model::Slim));
        assert_eq!(add_file(&p, &src.join("alex.png"), None, None).unwrap().id, a.id, "duplicates collapse");
        let s = add_file(&p, &src.join("steve.png"), Some("Mine"), None).unwrap();
        assert_eq!(s.model, Model::Classic);
        assert!(add_file(&p, &src.join("bad.png"), None, None).is_err());
        assert_eq!(list(&p).len(), 2);
        rename(&p, &s.id, "Renamed").unwrap();
        assert!(rename(&p, &s.id, "  ").is_err());
        set_model(&p, &s.id, Model::Slim).unwrap();
        let l = list(&p);
        let got = l.iter().find(|x| x.id == s.id).unwrap();
        assert_eq!((got.name.as_str(), got.model), ("Renamed", Model::Slim));
        let out = export(&p, &s.id, &src).unwrap();
        assert_eq!(fs::read(out).unwrap(), fs::read(png_path(&p, &s.id)).unwrap());
        delete(&p, &a.id).unwrap();
        assert!(delete(&p, &a.id).is_err());
        assert!(!png_path(&p, &a.id).exists());
        assert_eq!(list(&p).len(), 1);
        fs::remove_dir_all(&p.root).unwrap();
    }

    #[test]
    fn account_request_shape() {
        let c = Client::new();
        let data = png(&slim());
        let r = upload_request(&c, "TOKEN", &data, Model::Slim).unwrap();
        assert_eq!(r.method(), reqwest::Method::POST);
        assert_eq!(r.url().as_str(), "https://api.minecraftservices.com/minecraft/profile/skins");
        assert_eq!(r.headers()["authorization"], "Bearer TOKEN");
        let ct = r.headers()["content-type"].to_str().unwrap().to_string();
        assert!(ct.starts_with("multipart/form-data; boundary="));
        let (ct2, body) = upload_body(&data, Model::Slim);
        assert_eq!(ct, ct2);
        let boundary = ct.split("boundary=").nth(1).unwrap();
        let text = String::from_utf8_lossy(&body).to_string();
        assert!(text.starts_with(&format!("--{boundary}\r\n")));
        assert!(text.contains("name=\"variant\"\r\n\r\nslim\r\n"));
        assert!(text.contains("name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n"));
        assert!(text.ends_with(&format!("\r\n--{boundary}--\r\n")));
        assert!(body.windows(data.len()).any(|w| w == &data[..]));
        let (_, classic) = upload_body(&data, Model::Classic);
        assert!(String::from_utf8_lossy(&classic).contains("\r\n\r\nclassic\r\n"));
    }
}
