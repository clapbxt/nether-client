//! Nether's own backend (see `backend/README.md`): signed static content from a public GitHub repo (news, cape
//! directory, Nether packs) and the cape API (Cloudflare Worker). URLs: env `NETHER_CONTENT_URL` / `NETHER_API_URL`,
//! then `config.json` `content_url` / `api_url`, then the defaults below. Empty = not set up yet: every call is a no-op.
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use anyhow::{anyhow, bail, Result};
use reqwest::blocking::{Client, RequestBuilder, Response};
use serde::Deserialize;
use sha2::{Digest, Sha256};

use crate::netherpack::NetherPack;
use crate::paths::Paths;
use crate::Session;

/// Signed content served by the Nether API Worker (backend/content, bundled at deploy).
pub const DEFAULT_CONTENT_URL: &str = "https://nether-api.nbhowell1.workers.dev/content";
/// Fill in after `wrangler deploy`, e.g. `https://nether-api.<user>.workers.dev`.
pub const DEFAULT_API_URL: &str = "https://nether-api.nbhowell1.workers.dev";

const TTL: Duration = Duration::from_secs(1800);
/// Content public key (hex, raw Ed25519). The private half lives outside the repo; see `backend/content/sign.mjs`.
const CONTENT_KEY_HEX: &str = include_str!("../res/content-ed25519.pub");
const MAX_FILE: u64 = 4 * 1024 * 1024;

fn setting(env: &str, key: &str, default: &str) -> String {
    let v = std::env::var(env).ok().filter(|v| !v.is_empty()).or_else(|| {
        crate::store::config(&Paths::new())[key].as_str().map(str::to_string)
    });
    v.unwrap_or_else(|| default.to_string()).trim_end_matches('/').to_string()
}
pub fn content_url() -> String { setting("NETHER_CONTENT_URL", "content_url", DEFAULT_CONTENT_URL) }
pub fn api_url() -> String { setting("NETHER_API_URL", "api_url", DEFAULT_API_URL) }

// ---------------- signed content ----------------
// Everything from the content repo is trusted only through `manifest.json` + `manifest.sig` (Ed25519, key embedded
// above): the manifest lists every file's sha256 and size, has a version (never goes backwards) and an expiry. A file
// that is missing from the manifest, fails its hash, or comes with a bad or expired manifest is treated as absent.

#[derive(Deserialize)]
struct Entry { sha256: String, size: u64 }
#[derive(Deserialize)]
struct Manifest {
    version: u64,
    expires: i64,
    files: HashMap<String, Entry>,
}

fn is_hex64(s: &str) -> bool { s.len() == 64 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) }

/// Check `sig_hex` over `body` with `key` and parse the manifest; rejects expired manifests and versions below `min_version`.
fn verify_manifest(key: &[u8], body: &[u8], sig_hex: &str, now: i64, min_version: u64) -> Result<Manifest> {
    let sig = hex::decode(sig_hex.trim()).map_err(|_| anyhow!("bad signature encoding"))?;
    ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, key).verify(body, &sig).map_err(|_| anyhow!("content signature mismatch"))?;
    let m: Manifest = serde_json::from_slice(body)?;
    if m.expires <= now { bail!("content manifest expired"); }
    if m.version < min_version { bail!("content manifest older than one already seen"); }
    if m.files.values().any(|e| !is_hex64(&e.sha256) || e.size > MAX_FILE) { bail!("bad manifest entry"); }
    Ok(m)
}

fn now_secs() -> i64 { std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0) }
fn cache_dir() -> std::path::PathBuf { Paths::new().root.join("cache").join("content") }

/// GET `url` with a body cap (https redirects only, see `download::client`).
fn get_capped(c: &Client, url: &str, max: u64) -> Result<Vec<u8>> {
    use std::io::Read;
    let r = c.get(url).send()?.error_for_status()?;
    if r.content_length().is_some_and(|n| n > max) { bail!("response too large"); }
    let mut out = Vec::new();
    r.take(max + 1).read_to_end(&mut out)?;
    if out.len() as u64 > max { bail!("response too large"); }
    Ok(out)
}

static LAST_FAIL: Mutex<Option<Instant>> = Mutex::new(None);

/// The verified manifest: refreshed from the network when the cached one is older than the TTL (and `c` is given),
/// otherwise the verified disk copy. None = no trustworthy content (callers fall back to nothing).
fn manifest(c: Option<&Client>) -> Option<Manifest> {
    let dir = cache_dir();
    let (mf, sf, vf) = (dir.join("manifest.json"), dir.join("manifest.sig"), dir.join("version"));
    let min = std::fs::read_to_string(&vf).ok().and_then(|s| s.trim().parse::<u64>().ok()).unwrap_or(0);
    let age = std::fs::metadata(&mf).ok().and_then(|m| m.modified().ok()).and_then(|t| t.elapsed().ok());
    let base = content_url();
    let key = hex::decode(CONTENT_KEY_HEX.trim()).ok()?;
    let recently_failed = LAST_FAIL.lock().ok().and_then(|l| *l).is_some_and(|t| t.elapsed() < Duration::from_secs(120));
    if let Some(c) = c.filter(|_| !base.is_empty() && !recently_failed && age.map_or(true, |a| a >= TTL)) {
        let got = get_capped(c, &format!("{base}/manifest.json"), 512 * 1024).and_then(|body| {
            let sig = String::from_utf8(get_capped(c, &format!("{base}/manifest.sig"), 1024)?)?;
            let m = verify_manifest(&key, &body, &sig, now_secs(), min)?;
            Ok((body, sig, m))
        });
        match got {
            Ok((body, sig, m)) => {
                let _ = std::fs::create_dir_all(&dir);
                let _ = crate::store::write_atomic(&mf, &body);
                let _ = crate::store::write_atomic(&sf, sig.as_bytes());
                let _ = crate::store::write_atomic(&vf, m.version.to_string().as_bytes());
            }
            Err(_) => { if let Ok(mut l) = LAST_FAIL.lock() { *l = Some(Instant::now()); } }
        }
    }
    verify_manifest(&key, &std::fs::read(&mf).ok()?, &std::fs::read_to_string(&sf).ok()?, now_secs(), min).ok()
}

/// A content file's bytes, only if the signed manifest lists it and the bytes match (content-addressed disk cache).
fn content_file(c: Option<&Client>, path: &str) -> Option<Vec<u8>> {
    let m = manifest(c)?;
    let e = m.files.get(path)?;
    let file = cache_dir().join(format!("{}.bin", e.sha256));
    let good = |b: &[u8]| b.len() as u64 == e.size && hex::encode(Sha256::digest(b)) == e.sha256;
    if let Some(b) = std::fs::read(&file).ok().filter(|b| good(b)) { return Some(b); }
    let base = content_url();
    let b = get_capped(c?, &format!("{base}/{path}"), e.size).ok().filter(|b| good(b))?;
    let _ = std::fs::create_dir_all(cache_dir());
    let _ = crate::store::write_atomic(&file, &b);
    Some(b)
}

fn content<T: for<'de> Deserialize<'de>>(c: &Client, file: &str) -> Vec<T> {
    content_file(Some(c), file).and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}

#[derive(Clone, Debug, Deserialize)]
pub struct NewsItem {
    #[serde(default = "nether_tag")]
    pub tag: String,
    pub title: String,
    #[serde(default)]
    pub url: String,
}
fn nether_tag() -> String { "NETHER".into() }

#[derive(Clone, Debug, Deserialize)]
pub struct Cape {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub desc: String,
}

/// Cape ids are path and URL components: lowercase letters, digits, `_`, `-`, 1 to 32 characters.
pub fn valid_cape_id(id: &str) -> bool { (1..=32).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-') }

/// A cape texture: PNG signature, a sane size (at most 64 KB, 256x128 px) before anything decodes it.
fn valid_cape_png(b: &[u8]) -> bool {
    if b.len() < 33 || b.len() > 64 * 1024 || b[..8] != [0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a] || &b[12..16] != b"IHDR" { return false; }
    let (w, h) = (u32::from_be_bytes([b[16], b[17], b[18], b[19]]), u32::from_be_bytes([b[20], b[21], b[22], b[23]]));
    (22..=256).contains(&w) && (17..=128).contains(&h)
}

impl Cape {
    /// The verified, size-checked PNG bytes of this cape (64x32 Minecraft cape texture), or None.
    pub fn png(&self, c: &Client) -> Option<Vec<u8>> {
        if !valid_cape_id(&self.id) { return None; }
        content_file(Some(c), &format!("capes/{}.png", self.id)).filter(|b| valid_cape_png(b))
    }
}

/// Launcher news, newest first.
pub fn news(c: &Client) -> Vec<NewsItem> { content(c, "news.json") }
/// Nether packs published in the content repo (network; call off the UI thread).
pub fn packs(c: &Client) -> Vec<NetherPack> { content(c, "packs.json") }
/// The last downloaded packs without touching the network (UI-thread safe); `packs` refreshes them.
pub fn packs_cached() -> Vec<NetherPack> {
    content_file(None, "packs.json").and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}
/// The Nether cape directory (invalid ids dropped).
pub fn capes(c: &Client) -> Vec<Cape> { content::<Cape>(c, "capes/index.json").into_iter().filter(|x| valid_cape_id(&x.id)).collect() }

/// Hand the game agent the verified cape hashes (`<id> <sha256>` per line); it only accepts a cape PNG that matches.
/// Returns the file path, None when there is no verified manifest (the agent then shows no capes).
pub fn cape_sums_file(c: &Client) -> Option<std::path::PathBuf> {
    let m = manifest(Some(c))?;
    let mut out = String::new();
    for (path, e) in &m.files {
        if let Some(id) = path.strip_prefix("capes/").and_then(|p| p.strip_suffix(".png")) {
            if valid_cape_id(id) { out.push_str(&format!("{id} {}\n", e.sha256)); }
        }
    }
    let f = cache_dir().join("cape-sums.txt");
    std::fs::create_dir_all(cache_dir()).ok()?;
    crate::store::write_atomic(&f, out.as_bytes()).ok()?;
    Some(f)
}

// ---------------- cape API ----------------

/// Nether players among these UUIDs -> their Nether cape id ("" = none). UUIDs with or without dashes; at most 100.
pub fn players(c: &Client, uuids: &[&str]) -> Result<HashMap<String, String>> {
    let api = api_url();
    if api.is_empty() || uuids.is_empty() { return Ok(Default::default()); }
    let ids: Vec<String> = uuids.iter().take(100).map(|u| u.replace('-', "").to_lowercase()).collect();
    Ok(c.get(format!("{api}/v1/players")).query(&[("u", ids.join(","))]).send()?.error_for_status()?.json()?)
}

// The API never sees the Minecraft access token. The launcher fetches its Mojang player certificate (the chat-signing
// key pair Mojang signs for this UUID; the token goes only to Mojang) and signs the API's one-time nonce with it; the
// API checks Mojang's signature and ours offline and returns a 24 h session token bound to the UUID. (It used to ask
// Mojang's hasJoined, which answers 403 to Cloudflare Workers.) Kept in memory only; a 401 re-runs the handshake once.
struct Tok { uuid: String, token: String, exp: i64 }
static TOKEN: Mutex<Option<Tok>> = Mutex::new(None);

/// The response if it succeeded; else an error that says which step failed and what the service answered (its JSON `error`).
fn step(r: Response, what: &str) -> Result<Response> {
    if r.status().is_success() { return Ok(r); }
    let code = r.status();
    let msg = r.text().ok().and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok()).and_then(|v| v["error"].as_str().map(String::from)).unwrap_or_default();
    bail!("{what} failed (HTTP {code}{})", if msg.is_empty() { String::new() } else { format!(": {msg}") })
}

/// Proof for `/v1/auth/finish`: the player's Mojang certificate (public key + Mojang's signature + expiry) and the
/// nonce signed with its private key (SHA256withRSA over "nether-auth:<nonce>:<uuid>").
pub fn certificate_proof(c: &Client, s: &Session, nonce: &str) -> Result<serde_json::Value> {
    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD;
    let cert: serde_json::Value = step(c.post("https://api.minecraftservices.com/player/certificates").bearer_auth(&s.access_token).send()?,
        "Mojang certificate")?.json()?;
    let pem = |k: &str| cert["keyPair"][k].as_str().unwrap_or_default().lines().filter(|l| !l.starts_with("-----")).collect::<String>();
    let (private, public) = (b64.decode(pem("privateKey"))?, pem("publicKey"));
    let key_sig = cert["publicKeySignatureV2"].as_str().unwrap_or_default();
    let expires = cert["expiresAt"].as_str().unwrap_or_default();
    if public.is_empty() || key_sig.is_empty() || expires.is_empty() { bail!("Mojang sent no certificate"); }
    let kp = ring::signature::RsaKeyPair::from_pkcs8(&private).or_else(|_| ring::signature::RsaKeyPair::from_der(&private))
        .map_err(|e| anyhow!("Mojang certificate key unusable ({e})"))?;
    let uuid = s.uuid.replace('-', "").to_lowercase();
    let mut sig = vec![0u8; kp.public().modulus_len()];
    kp.sign(&ring::signature::RSA_PKCS1_SHA256, &ring::rand::SystemRandom::new(), format!("nether-auth:{nonce}:{uuid}").as_bytes(), &mut sig)
        .map_err(|_| anyhow!("signing failed"))?;
    Ok(serde_json::json!({ "serverId": nonce, "uuid": uuid, "name": s.name, "publicKey": public, "keySig": key_sig, "expiresAt": expires, "sig": b64.encode(sig) }))
}

fn handshake(c: &Client, api: &str, s: &Session) -> Result<String> {
    let started: serde_json::Value = step(c.post(format!("{api}/v1/auth/start")).send()?, "Nether sign-in")?.json()?;
    let server_id = started["serverId"].as_str().filter(|v| v.len() == 32).ok_or_else(|| anyhow!("bad auth start"))?;
    let proof = certificate_proof(c, s, server_id)?;
    let done: serde_json::Value = step(c.post(format!("{api}/v1/auth/finish")).json(&proof).send()?, "Nether sign-in")?.json()?;
    let (token, uuid) = (done["token"].as_str().unwrap_or_default(), done["uuid"].as_str().unwrap_or_default());
    if token.is_empty() || uuid != s.uuid.replace('-', "").to_lowercase() { bail!("bad auth reply"); }
    *TOKEN.lock().unwrap_or_else(|e| e.into_inner()) = Some(Tok { uuid: uuid.into(), token: token.into(), exp: done["exp"].as_i64().unwrap_or(0) });
    Ok(token.to_string())
}

fn session_token(c: &Client, api: &str, s: &Session, force: bool) -> Result<String> {
    let uuid = s.uuid.replace('-', "").to_lowercase();
    if !force {
        if let Some(t) = TOKEN.lock().unwrap_or_else(|e| e.into_inner()).as_ref().filter(|t| t.uuid == uuid && t.exp - 300 > now_secs()) { return Ok(t.token.clone()); }
    }
    handshake(c, api, s)
}

/// Send an authenticated API request; on 401 sign in again once and retry.
pub(crate) fn authed(c: &Client, s: &Session, build: impl Fn(&str) -> RequestBuilder) -> Result<Response> {
    let api = api_url();
    if api.is_empty() { bail!("the Nether cape service isn't set up yet"); }
    let r = build(&session_token(c, &api, s, false)?).send()?;
    if r.status() != reqwest::StatusCode::UNAUTHORIZED { return Ok(r); }
    Ok(build(&session_token(c, &api, s, true)?).send()?)
}

/// Check in as a Nether player (game start), so others see your nametag icon. Best effort: errors when the API is down.
pub fn hello(c: &Client, s: &Session) -> Result<()> {
    let api = api_url();
    authed(c, s, |t| c.post(format!("{api}/v1/hello")).bearer_auth(t))?.error_for_status()?;
    Ok(())
}

/// Wear a Nether cape (None = take it off).
pub fn set_cape(c: &Client, s: &Session, cape: Option<&str>) -> Result<()> {
    let api = api_url();
    let r = authed(c, s, |t| c.put(format!("{api}/v1/cape")).bearer_auth(t).json(&serde_json::json!({ "cape": cape })))
        .map_err(|e| if e.chain().any(|c| c.to_string().contains("404")) { anyhow!("the Nether cape service isn't available yet") } else { e })?;
    if !r.status().is_success() {
        let msg = r.json::<serde_json::Value>().ok().and_then(|v| v["error"].as_str().map(str::to_string)).unwrap_or_else(|| "request failed".into());
        bail!("{msg}");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::signature::{Ed25519KeyPair, KeyPair};

    #[test]
    fn content_files_parse() {
        let news: Vec<NewsItem> = serde_json::from_str(include_str!("../../../backend/content/news.json")).unwrap();
        assert_eq!(news[0].tag, "NETHER");
        let capes: Vec<Cape> = serde_json::from_str(include_str!("../../../backend/content/capes/index.json")).unwrap();
        assert!(capes.iter().any(|c| c.id == "obsidian"));
        let _: Vec<NetherPack> = serde_json::from_str(include_str!("../../../backend/content/packs.json")).unwrap();
    }

    fn signed(body: &str) -> (Vec<u8>, String) {
        let pkcs8 = Ed25519KeyPair::generate_pkcs8(&ring::rand::SystemRandom::new()).unwrap();
        let kp = Ed25519KeyPair::from_pkcs8(pkcs8.as_ref()).unwrap();
        (kp.public_key().as_ref().to_vec(), hex::encode(kp.sign(body.as_bytes()).as_ref()))
    }

    #[test]
    fn manifest_verify() {
        let h = "a".repeat(64);
        let body = format!(r#"{{"version":5,"expires":2000,"files":{{"news.json":{{"sha256":"{h}","size":10}}}}}}"#);
        let (key, sig) = signed(&body);
        assert!(verify_manifest(&key, body.as_bytes(), &sig, 1000, 0).is_ok());
        assert!(verify_manifest(&key, body.as_bytes(), &sig, 3000, 0).is_err(), "expired");
        assert!(verify_manifest(&key, body.as_bytes(), &sig, 1000, 6).is_err(), "rollback");
        assert!(verify_manifest(&key, body.replace("10", "11").as_bytes(), &sig, 1000, 0).is_err(), "tampered");
        assert!(verify_manifest(&key, body.as_bytes(), "zz", 1000, 0).is_err(), "bad sig");
        let (other, _) = signed("x");
        assert!(verify_manifest(&other, body.as_bytes(), &sig, 1000, 0).is_err(), "wrong key");
        let bad = body.replace(&h, "../x");
        let (k2, s2) = signed(&bad);
        assert!(verify_manifest(&k2, bad.as_bytes(), &s2, 1000, 0).is_err(), "bad hash entry");
    }

    #[test]
    fn shipped_manifest_matches_embedded_key() {
        let body = include_bytes!("../../../backend/content/manifest.json");
        let sig = include_str!("../../../backend/content/manifest.sig");
        let key = hex::decode(CONTENT_KEY_HEX.trim()).unwrap();
        let m = verify_manifest(&key, body, sig, 0, 0).unwrap();
        assert!(m.files.contains_key("capes/obsidian.png"));
        for (p, e) in &m.files {
            let b = std::fs::read(std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../backend/content").join(p)).unwrap();
            assert_eq!(hex::encode(Sha256::digest(&b)), e.sha256, "{p}");
        }
    }

    #[test]
    fn cape_ids_and_pngs() {
        assert!(valid_cape_id("obsidian") && valid_cape_id("a_b-9"));
        for bad in ["", "A", "x/../y", "..", "a.b", "a b", "x\\y", &"a".repeat(33)] { assert!(!valid_cape_id(bad), "{bad}"); }
        let png = std::fs::read(std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../backend/content/capes/obsidian.png")).unwrap();
        assert!(valid_cape_png(&png));
        let mut huge = png.clone();
        huge[16..20].copy_from_slice(&20000u32.to_be_bytes());
        assert!(!valid_cape_png(&huge));
        assert!(!valid_cape_png(b"not a png at all, definitely not a png file"));
        assert!(!valid_cape_png(&vec![0u8; 70_000]));
    }
}
