use std::fs;
use std::io::Read;
use std::path::Path;
use std::time::Duration;

use anyhow::{bail, Context, Result};
use reqwest::blocking::Client;
use serde::de::DeserializeOwned;
use sha1::{Digest, Sha1};
use sha2::Sha256;

/// The shared HTTP client (one connection pool for the whole launcher). The timeout applies per read while
/// streaming, so a big file on a slow line keeps going as long as bytes keep arriving.
pub fn client() -> Client {
    static C: std::sync::OnceLock<Client> = std::sync::OnceLock::new();
    C.get_or_init(|| Client::builder()
        .user_agent(concat!("NetherClient/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(45))
        // https only, also across redirects, and at most 5 hops
        .redirect(reqwest::redirect::Policy::custom(|a| {
            if a.previous().len() >= 5 { a.error("too many redirects") }
            else if a.url().scheme() != "https" { a.error("redirect to a non-https address refused") }
            else { a.follow() }
        }))
        .build()
        .expect("http client")).clone()
}

/// Error text for work the user cancelled (`is_cancelled` recognises it).
pub const CANCELLED: &str = "cancelled";

/// Stop work when the user cancelled.
pub fn check_cancel(cancel: Option<&std::sync::atomic::AtomicBool>) -> Result<()> {
    if cancel.is_some_and(|c| c.load(std::sync::atomic::Ordering::Relaxed)) { bail!(CANCELLED); }
    Ok(())
}

pub fn is_cancelled(e: &anyhow::Error) -> bool { e.chain().any(|c| c.to_string() == CANCELLED) }

/// No single download (jar, asset, runtime archive) is bigger than this.
const MAX_DOWNLOAD: u64 = 1536 * 1024 * 1024;
/// API responses (version manifests, search results) are parsed in memory: capped.
const MAX_JSON: u64 = 64 * 1024 * 1024;

static PART: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

/// A temp name next to `dest` that no other download (or launcher process) uses.
fn part_path(dest: &Path) -> std::path::PathBuf {
    let name = dest.file_name().and_then(|n| n.to_str()).unwrap_or("download");
    dest.with_file_name(format!("{name}.{}-{}.part", std::process::id(), PART.fetch_add(1, std::sync::atomic::Ordering::Relaxed)))
}

/// Stream `url` into `dest` through a temp file, checking it on the way. Never leaves a partial `dest`.
fn stream_to(c: &Client, url: &str, dest: &Path, check: Check) -> Result<()> {
    let mut resp = c.get(url).send()?.error_for_status()?;
    let tmp = part_path(dest);
    let res = (|| -> Result<()> {
        let mut out = std::io::BufWriter::new(fs::File::create(&tmp)?);
        let (mut h, mut h2, mut n, mut buf) = (Sha1::new(), Sha256::new(), 0u64, vec![0u8; 64 * 1024]);
        let cap = match check { Check::Size(s) => s.min(MAX_DOWNLOAD), _ => MAX_DOWNLOAD };
        loop {
            let k = resp.read(&mut buf)?;
            if k == 0 { break; }
            if n + k as u64 > cap { bail!("download from {url} is larger than expected"); }
            match check { Check::Sha1(_) => h.update(&buf[..k]), Check::Sha256(_) => h2.update(&buf[..k]), _ => {} }
            std::io::Write::write_all(&mut out, &buf[..k])?;
            n += k as u64;
        }
        std::io::Write::flush(&mut out)?;
        drop(out);
        match check {
            Check::Sha1(want) if !hex::encode(h.finalize()).eq_ignore_ascii_case(want) => bail!("sha1 mismatch for {url}"),
            Check::Sha256(want) if !hex::encode(h2.finalize()).eq_ignore_ascii_case(want) => bail!("sha256 mismatch for {url}"),
            Check::Size(s) if n != s => bail!("size mismatch for {url}"),
            _ => {}
        }
        fs::rename(&tmp, dest)?;
        Ok(())
    })();
    if res.is_err() { let _ = fs::remove_file(&tmp); }
    res
}

/// Client errors (404, 403...) won't change on a retry.
fn permanent(e: &anyhow::Error) -> bool {
    e.chain().any(|c| c.downcast_ref::<reqwest::Error>().and_then(|r| r.status()).is_some_and(|s| s.is_client_error()))
}

pub fn sha1_file(p: &Path) -> Result<String> {
    let mut f = fs::File::open(p)?;
    let mut h = Sha1::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 { break; }
        h.update(&buf[..n]);
    }
    Ok(hex::encode(h.finalize()))
}

/// How to decide an existing file is already good.
#[derive(Clone, Copy)]
pub enum Check<'a> {
    None,
    Sha1(&'a str),
    Sha256(&'a str),
    Size(u64),
}

/// The file exists and passes `check`.
pub fn is_good(dest: &Path, check: Check) -> bool {
    dest.exists() && match check {
        Check::None => true,
        Check::Size(s) => fs::metadata(dest).map(|m| m.len() == s).unwrap_or(false),
        Check::Sha1(h) => sha1_file(dest).map(|x| x.eq_ignore_ascii_case(h)).unwrap_or(false),
        Check::Sha256(h) => fs::read(dest).map(|b| hex::encode(Sha256::digest(&b)).eq_ignore_ascii_case(h)).unwrap_or(false),
    }
}

/// Download `url` to `dest` (atomic, verified, 3 tries). Skips when the file already matches.
pub fn fetch(c: &Client, url: &str, dest: &Path, check: Check) -> Result<()> {
    if is_good(dest, check) { return Ok(()); }
    if let Some(p) = dest.parent() { fs::create_dir_all(p)?; }
    let mut last = None;
    for i in 0..3u64 {
        match stream_to(c, url, dest, check) {
            Ok(()) => return Ok(()),
            Err(e) => {
                let stop = permanent(&e);
                last = Some(e);
                if stop { break; }
                std::thread::sleep(Duration::from_millis(700 * (i + 1)));
            }
        }
    }
    Err(last.unwrap()).with_context(|| format!("downloading {url}"))
}

pub fn get_json<T: DeserializeOwned>(c: &Client, url: &str) -> Result<T> {
    Ok(serde_json::from_reader(c.get(url).send()?.error_for_status()?.take(MAX_JSON))?)
}

// ---- response cache: memory + disk (cache/http), per-request lifetime ----

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Instant, SystemTime};

static MEM: Mutex<Option<HashMap<String, (Instant, serde_json::Value)>>> = Mutex::new(None);

fn cache_file(key: &str) -> std::path::PathBuf {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    key.hash(&mut h);
    crate::paths::Paths::new().root.join("cache").join("http").join(format!("{:016x}.json", h.finish()))
}

/// Cached JSON request. `key` identifies the request (URL + body); results younger than `ttl` are reused
/// from memory, then disk. On a network error a stale disk copy is used rather than failing.
pub fn cached_json(key: &str, ttl: Duration, fetch: impl FnOnce() -> Result<serde_json::Value>) -> Result<serde_json::Value> {
    if let Some((t, v)) = MEM.lock().unwrap_or_else(|e| e.into_inner()).as_ref().and_then(|m| m.get(key).cloned()) {
        if t.elapsed() < ttl { return Ok(v); }
    }
    let file = cache_file(key);
    let fresh = fs::metadata(&file).ok().and_then(|m| m.modified().ok()).and_then(|t| SystemTime::now().duration_since(t).ok()).map(|age| age < ttl).unwrap_or(false);
    let disk = || fs::read(&file).ok().and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok());
    if fresh {
        if let Some(v) = disk() {
            MEM.lock().unwrap_or_else(|e| e.into_inner()).get_or_insert_with(HashMap::new).insert(key.to_string(), (Instant::now(), v.clone()));
            return Ok(v);
        }
    }
    match fetch() {
        Ok(v) => {
            let _ = crate::store::write_atomic(&file, &serde_json::to_vec(&v).unwrap_or_default());
            MEM.lock().unwrap_or_else(|e| e.into_inner()).get_or_insert_with(HashMap::new).insert(key.to_string(), (Instant::now(), v.clone()));
            Ok(v)
        }
        Err(e) => disk().ok_or(e),
    }
}

/// GET `url` (with query pairs) as JSON through the cache.
pub fn get_cached(c: &Client, url: &str, query: &[(&str, String)], ttl: Duration) -> Result<serde_json::Value> {
    let full = reqwest::Url::parse_with_params(url, query.iter().map(|(k, v)| (*k, v.as_str())))?;
    cached_json(full.as_str(), ttl, || Ok(c.get(full.clone()).send()?.error_for_status()?.json()?))
}

/// POST JSON `body` to `url` through the cache.
pub fn post_cached(c: &Client, url: &str, body: &serde_json::Value, ttl: Duration) -> Result<serde_json::Value> {
    let key = format!("{url}#{body}");
    cached_json(&key, ttl, || Ok(c.post(url).json(body).send()?.error_for_status()?.json()?))
}
