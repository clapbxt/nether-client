//! Community uploads: capes and cosmetics made by players (the Worker's `/v1/ugc`, backend/worker/src/ugc.js).
//! Every upload is screened by the service before anyone else sees it (public, or private to its uploader when the
//! check is unsure); files are content-addressed (sha256), checked here and cached on disk forever.
//! Ids are `u-` + 12 [a-z0-9], so they never clash with the signed catalog's.
use std::collections::HashMap;
use std::sync::Mutex;

use anyhow::{bail, Result};
use reqwest::blocking::{multipart, Client, Response};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::backend::{api_url, authed};
use crate::cosimport::What;
use crate::paths::Paths;
use crate::Session;

/// `u-` + 12 lowercase letters / digits.
pub fn is_ugc(id: &str) -> bool { id.len() == 14 && id.starts_with("u-") && id[2..].bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit()) }
fn is_hex64(s: &str) -> bool { s.len() == 64 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) }

/// A community item as the service describes it.
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct Item {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub name: String,
    /// the uploader's Minecraft name
    #[serde(default)]
    pub by: String,
    /// sha256 of the model JSON (None for capes) and of the texture PNG
    #[serde(default)]
    pub model: Option<String>,
    pub png: String,
    #[serde(default = "ms100")]
    pub ms: u32,
    #[serde(default)]
    pub uses: u64,
    #[serde(default)]
    pub favs: u64,
    #[serde(default)]
    pub created: i64,
    /// live (everyone), private (only its uploader: the safety check was unsure), pending (check not run yet), removed
    #[serde(default)]
    pub status: String,
    /// why it is private or removed (own items only)
    #[serde(default)]
    pub reason: Option<String>,
    /// hearted by the signed-in player
    #[serde(default)]
    pub fav: bool,
    /// uploaded by the signed-in player
    #[serde(default)]
    pub mine: bool,
}
fn ms100() -> u32 { 100 }

impl Item {
    fn valid(&self) -> bool { is_ugc(&self.id) && is_hex64(&self.png) && self.model.as_deref().is_none_or(is_hex64) && self.what().is_some() }
    pub fn what(&self) -> Option<What> { What::parse(&self.kind) }
    pub fn frame_ms(&self) -> u32 { self.ms.clamp(20, 2000) }
    /// Everyone can see and wear it.
    pub fn public(&self) -> bool { self.status == "live" }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Filter { All, Mine, Favs }
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Sort { Top, New }

/// One page of a browse (24 items) and whether there are more.
#[derive(Clone, Debug, Default, Deserialize)]
pub struct Page { pub items: Vec<Item>, #[serde(default)] pub more: bool }

/// The service's `{"error": ...}` message, or what went wrong.
fn check(r: Response) -> Result<Response> {
    if r.status().is_success() { return Ok(r); }
    let code = r.status();
    let msg = r.json::<serde_json::Value>().ok().and_then(|v| v["error"].as_str().map(str::to_string));
    match (code.as_u16(), msg) {
        (_, Some(m)) => bail!("{m}"),
        (404, None) => bail!("the community service isn't available yet"),
        (429, None) => bail!("too many requests, wait a moment"),
        (c, None) => bail!("the community service answered {c}"),
    }
}

fn api() -> Result<String> {
    let a = api_url();
    if a.is_empty() { bail!("the Nether service isn't set up"); }
    Ok(a)
}

/// Browse the community items of a type: everyone's public ones (`All`), yours (any status), or your hearted ones.
/// Signed in (`s`) = items come with your hearts.
pub fn browse(c: &Client, s: Option<&Session>, what: What, filter: Filter, sort: Sort, q: &str, page: u32) -> Result<Page> {
    let api = api()?;
    let f = match filter { Filter::All => "all", Filter::Mine => "mine", Filter::Favs => "favs" };
    let query = [("type", what.as_str()), ("filter", f), ("sort", if sort == Sort::New { "new" } else { "top" }), ("q", q.trim()), ("page", &page.to_string())];
    let url = format!("{api}/v1/ugc");
    // signed in = hearts come back; if the sign-in fails, everyone's list still loads (without them)
    let r = match s {
        Some(s) if filter == Filter::All => authed(c, s, |t| c.get(&url).query(&query).bearer_auth(t)).or_else(|_| Ok::<_, anyhow::Error>(c.get(&url).query(&query).send()?))?,
        Some(s) => authed(c, s, |t| c.get(&url).query(&query).bearer_auth(t))?,
        None if filter == Filter::All => c.get(&url).query(&query).send()?,
        None => bail!("sign in with Microsoft first"),
    };
    let mut p: Page = check(r)?.json()?;
    p.items.retain(Item::valid);
    if filter != Filter::All { remember(&p.items); }
    Ok(p)
}

/// One item (a public one, or your own with `s`). None = gone or not public.
pub fn item(c: &Client, s: Option<&Session>, id: &str) -> Result<Option<Item>> {
    if !is_ugc(id) { return Ok(None); }
    let url = format!("{}/v1/ugc/{id}", api()?);
    let r = match s { Some(s) => authed(c, s, |t| c.get(&url).bearer_auth(t))?, None => c.get(&url).send()? };
    if r.status() == reqwest::StatusCode::NOT_FOUND { return Ok(None); }
    let it: Item = check(r)?.json()?;
    Ok(it.valid().then_some(it))
}

fn cache_dir() -> std::path::PathBuf { Paths::new().root.join("cache").join("ugc") }

/// A file by its sha256: from the disk cache, else downloaded and checked.
pub fn blob(c: &Client, sha: &str, max: usize) -> Result<Vec<u8>> {
    if !is_hex64(sha) { bail!("bad file id"); }
    let f = cache_dir().join(format!("{sha}.bin"));
    let good = |b: &[u8]| b.len() <= max && hex::encode(Sha256::digest(b)) == sha;
    if let Some(b) = std::fs::read(&f).ok().filter(|b| good(b)) { return Ok(b); }
    use std::io::Read;
    let r = check(c.get(format!("{}/v1/ugc/blob/{sha}", api()?)).send()?)?;
    if r.content_length().is_some_and(|n| n as usize > max) { bail!("file too large"); }
    let mut b = Vec::new();
    r.take(max as u64 + 1).read_to_end(&mut b)?;
    if !good(&b) { bail!("a downloaded file didn't match its checksum"); }
    let _ = std::fs::create_dir_all(cache_dir());
    let _ = crate::store::write_atomic(&f, &b);
    Ok(b)
}

/// Model JSON (cosmetics) and texture PNG of an item, checked.
pub fn files(c: &Client, it: &Item) -> Result<(Option<Vec<u8>>, Vec<u8>)> {
    let png = blob(c, &it.png, crate::cosimport::MAX_PNG)?;
    if !png.starts_with(&[0x89, b'P', b'N', b'G']) { bail!("the texture isn't a PNG"); }
    let model = match &it.model { Some(m) => Some(blob(c, m, crate::cosimport::MAX_MODEL)?), None => None };
    Ok((model, png))
}

/// A community cosmetic, ready to draw.
pub fn load(c: &Client, it: &Item) -> Result<crate::cosmetics::Loaded> {
    let Some(What::Item(kind)) = it.what() else { bail!("not a cosmetic") };
    let (Some(model), png) = files(c, it)? else { bail!("the model is missing") };
    crate::cosmetics::Loaded::from_bytes(&it.id, kind, &model, &png, it.frame_ms())
}

/// A community cape's PNG (layout-checked).
pub fn cape_png(c: &Client, it: &Item) -> Result<Vec<u8>> {
    if it.what() != Some(What::Cape) { bail!("not a cape"); }
    let png = files(c, it)?.1;
    if png.len() < 24 { bail!("bad cape"); }
    let (w, h) = (u32::from_be_bytes([png[16], png[17], png[18], png[19]]), u32::from_be_bytes([png[20], png[21], png[22], png[23]]));
    if crate::backend::CapeLayout::of(w, h).is_none() { bail!("bad cape size"); }
    Ok(png)
}

/// What gets uploaded: the converted files (cosimport) and renders of the player wearing it (the safety check looks at
/// them too; cosmetics need at least one).
pub struct Upload<'a> {
    pub what: What,
    pub name: &'a str,
    pub frame_ms: Option<u32>,
    pub model: Option<&'a [u8]>,
    pub png: &'a [u8],
    pub previews: &'a [Vec<u8>],
}

/// Upload: the service checks it and answers with the new item (or the same item someone uploaded before: `true`).
pub fn upload(c: &Client, s: &Session, up: &Upload) -> Result<(Item, bool)> {
    let url = format!("{}/v1/ugc", api()?);
    let meta = serde_json::json!({ "type": up.what.as_str(), "name": up.name, "ms": up.frame_ms.unwrap_or(100), "by": s.name }).to_string();
    let form = || -> Result<multipart::Form> {
        let mut f = multipart::Form::new().text("meta", meta.clone())
            .part("texture", multipart::Part::bytes(up.png.to_vec()).file_name("texture.png").mime_str("image/png")?);
        if let Some(m) = up.model { f = f.part("model", multipart::Part::bytes(m.to_vec()).file_name("model.json").mime_str("application/json")?); }
        for p in up.previews.iter().take(3) { f = f.part("preview", multipart::Part::bytes(p.clone()).file_name("preview.png").mime_str("image/png")?); }
        Ok(f)
    };
    form()?;
    let r = authed(c, s, |t| c.post(&url).bearer_auth(t).timeout(std::time::Duration::from_secs(90)).multipart(form().expect("form")))?;
    let v: serde_json::Value = check(r)?.json()?;
    let it: Item = serde_json::from_value(v["item"].clone())?;
    if !it.valid() { bail!("the service sent back a bad item"); }
    remember(std::slice::from_ref(&it));
    Ok((it, v["duplicate"].as_bool() == Some(true)))
}

/// Delete one of your uploads.
pub fn delete(c: &Client, s: &Session, id: &str) -> Result<()> {
    if !is_ugc(id) { bail!("bad id"); }
    let url = format!("{}/v1/ugc/{id}", api()?);
    check(authed(c, s, |t| c.delete(&url).bearer_auth(t))?)?;
    forget(id);
    Ok(())
}

/// Heart (or un-heart) an item.
pub fn favorite(c: &Client, s: &Session, id: &str, on: bool) -> Result<()> {
    if !is_ugc(id) { bail!("bad id"); }
    let url = format!("{}/v1/ugc/{id}/fav", api()?);
    check(authed(c, s, |t| if on { c.put(&url) } else { c.delete(&url) }.bearer_auth(t))?)?;
    Ok(())
}

/// Report reasons the service takes.
pub const REPORT_REASONS: [(&str, &str); 4] = [("sexual", "Nudity or sexual content"), ("hate", "Hate symbol or slur"), ("gore", "Gore"), ("other", "Something else that breaks the rules")];

/// Report an item (enough different players remove it).
pub fn report(c: &Client, s: &Session, id: &str, reason: &str) -> Result<()> {
    if !is_ugc(id) || !REPORT_REASONS.iter().any(|(k, _)| *k == reason) { bail!("bad report"); }
    let url = format!("{}/v1/ugc/{id}/report", api()?);
    check(authed(c, s, |t| c.post(&url).bearer_auth(t).json(&serde_json::json!({ "reason": reason })))?)?;
    Ok(())
}

// ---------------- items this player has (own uploads, hearts, worn): the game gets their checksums at launch,
// so your own private uploads show on you in game too (others' public ones it looks up itself)

static KNOWN: Mutex<Option<HashMap<String, Item>>> = Mutex::new(None);
fn known_file() -> std::path::PathBuf { cache_dir().join("known.json") }

fn with_known<T>(f: impl FnOnce(&mut HashMap<String, Item>) -> T) -> T {
    let mut g = KNOWN.lock().unwrap_or_else(|e| e.into_inner());
    let m = g.get_or_insert_with(|| std::fs::read(known_file()).ok().and_then(|b| serde_json::from_slice::<Vec<Item>>(&b).ok()).unwrap_or_default()
        .into_iter().filter(Item::valid).map(|i| (i.id.clone(), i)).collect());
    f(m)
}

fn save_known(m: &HashMap<String, Item>) {
    let mut v: Vec<&Item> = m.values().collect();
    v.sort_by(|a, b| b.created.cmp(&a.created));
    let _ = std::fs::create_dir_all(cache_dir());
    let _ = crate::store::write_atomic(&known_file(), &serde_json::to_vec(&v.into_iter().take(300).collect::<Vec<_>>()).unwrap_or_default());
}

/// Keep these items' details (checksums) for the game.
pub fn remember(items: &[Item]) {
    if items.is_empty() { return; }
    with_known(|m| {
        let mut changed = false;
        for it in items.iter().filter(|i| i.valid() && i.status != "removed") {
            if m.get(&it.id) != Some(it) { m.insert(it.id.clone(), it.clone()); changed = true; }
        }
        if changed { save_known(m); }
    });
}

/// Drop a deleted / removed item.
pub fn forget(id: &str) { with_known(|m| if m.remove(id).is_some() { save_known(m); }); }

/// A remembered item by id.
pub fn known(id: &str) -> Option<Item> { with_known(|m| m.get(id).cloned()) }

/// Every remembered item.
pub fn known_all() -> Vec<Item> { with_known(|m| m.values().cloned().collect()) }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids() {
        assert!(is_ugc("u-abcdefghij12"));
        for bad in ["u-ABCDEFGHIJ12", "u-abc", "x-abcdefghij12", "u-abcdefghij1/", "snowman"] { assert!(!is_ugc(bad), "{bad}"); }
        // community ids pass the agent's and launcher's id checks
        assert!(crate::backend::valid_cape_id("u-abcdefghij12"));
    }

    #[test]
    fn item_json() {
        let it: Item = serde_json::from_str(r#"{"id":"u-abcdefghij12","type":"hat","name":"Top","by":"Alice","model":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","png":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","ms":100,"uses":3,"favs":1,"created":1,"status":"live"}"#).unwrap();
        assert!(it.valid() && it.public() && it.what() == Some(What::Item(crate::cosmetics::Kind::Hat)));
        let cape: Item = serde_json::from_str(r#"{"id":"u-abcdefghij13","type":"cape","name":"C","png":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","model":null,"status":"private","mine":true,"reason":"only you can see it"}"#).unwrap();
        assert!(cape.valid() && !cape.public() && cape.mine);
    }
}
