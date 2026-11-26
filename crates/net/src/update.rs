//! Launcher auto-update: the newest release in the public releases repo, its setup downloaded (sha256 from GitHub) and
//! checked against the Ed25519 release keys embedded here (`tools/release-sign.mjs`). The signature (`.vsig`) covers
//! the version and the setup's sha256, so an older setup can never pass for a newer release. Only a verified setup is
//! returned, and it is checked again right before it runs (`check`).
use std::path::PathBuf;

use anyhow::{anyhow, bail, Context, Result};
use reqwest::blocking::Client;
use sha2::{Digest, Sha256};

use crate::download::{fetch, Check};
use crate::paths::Paths;

/// The public source repo; its GitHub releases carry the signed setups.
pub const RELEASES_REPO: &str = "clapbxt/nether-client";
/// One hex Ed25519 public key per line (main + offline backup); a signature from any of them is accepted.
const RELEASE_KEYS: &str = include_str!("../res/release-ed25519.pub");

#[derive(Clone, Debug)]
pub struct Release {
    pub version: String,
    /// release page (notes) on GitHub
    pub page: String,
    setup_url: String,
    setup_sha256: Option<String>,
    vsig_url: String,
}

/// A downloaded, verified setup waiting to be run.
#[derive(Clone, Debug)]
pub struct Ready {
    pub path: PathBuf,
    version: String,
    sig: String,
}

/// Numeric core and prerelease numbers of a version tag ("v0.4.0-beta.2" -> ([0, 4, 0], Some([2]))).
fn parts(v: &str) -> (Vec<u32>, Option<Vec<u32>>) {
    let v = v.trim().trim_start_matches(['v', 'V']).split(['+', ' ']).next().unwrap_or("");
    let (core, pre) = match v.split_once('-') { Some((c, p)) => (c, Some(p)), None => (v, None) };
    let nums = |s: &str| s.split(|c: char| !c.is_ascii_digit()).filter(|p| !p.is_empty()).map(|p| p.parse().unwrap_or(0)).collect::<Vec<u32>>();
    (core.split('.').map(|p| p.parse().unwrap_or(0)).collect(), pre.map(nums))
}
/// `tag` is a higher version than `current`; a prerelease sorts below its release ("0.4.0-beta.2" < "0.4.0").
pub fn newer(tag: &str, current: &str) -> bool {
    use std::cmp::Ordering::*;
    let ((a, ap), (b, bp)) = (parts(tag), parts(current));
    let at = |v: &[u32], i: usize| v.get(i).copied().unwrap_or(0);
    let core = (0..a.len().max(b.len())).map(|i| at(&a, i).cmp(&at(&b, i))).find(|o| o.is_ne()).unwrap_or(Equal);
    let ord = core.then(match (ap, bp) { (None, Some(_)) => Greater, (Some(_), None) => Less, (Some(x), Some(y)) => x.cmp(&y), (None, None) => Equal });
    ord == Greater
}

/// The newest release if it is newer than `current` (None = up to date or nothing published yet). `beta` also
/// considers GitHub prereleases (Settings > Updates > beta updates); otherwise only the latest full release counts.
pub fn latest(c: &Client, current: &str, beta: bool) -> Result<Option<Release>> {
    let get = |u: String| c.get(u).header("Accept", "application/vnd.github+json").send();
    let v: serde_json::Value = if beta {
        let all: serde_json::Value = get(format!("https://api.github.com/repos/{RELEASES_REPO}/releases?per_page=20"))?.error_for_status()?.json()?;
        let list = all.as_array().cloned().unwrap_or_default();
        let best = list.into_iter().filter(|r| !r["draft"].as_bool().unwrap_or(false)).fold(None::<serde_json::Value>, |best, r| {
            let t = r["tag_name"].as_str().unwrap_or_default();
            match &best { Some(b) if !newer(t, b["tag_name"].as_str().unwrap_or_default()) => best, _ => Some(r) }
        });
        match best { Some(b) => b, None => return Ok(None) }
    } else {
        let r = get(format!("https://api.github.com/repos/{RELEASES_REPO}/releases/latest"))?;
        if r.status().as_u16() == 404 { return Ok(None); }
        r.error_for_status()?.json()?
    };
    let tag = v["tag_name"].as_str().unwrap_or_default();
    let version = tag.trim_start_matches(['v', 'V']).to_string();
    if version.is_empty() || !newer(tag, current) { return Ok(None); }
    let page = v["html_url"].as_str().unwrap_or_default().to_string();
    let assets = v["assets"].as_array().cloned().unwrap_or_default();
    let find = |suffix: &str| assets.iter().find(|a| a["name"].as_str().is_some_and(|n| n.starts_with("NetherClientSetup") && n.ends_with(suffix))).cloned();
    if !version.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-') { bail!("unexpected release tag {tag}"); }
    let (Some(setup), Some(vsig)) = (find(".exe"), find(".exe.vsig")) else { bail!("release {version} has no version-signed setup") };
    let url = |a: &serde_json::Value| a["browser_download_url"].as_str().unwrap_or_default().to_string();
    let (setup_url, vsig_url) = (url(&setup), url(&vsig));
    let ok = |u: &str| u.starts_with(&format!("https://github.com/{RELEASES_REPO}/releases/download/"));
    if !ok(&setup_url) || !ok(&vsig_url) || !page.starts_with("https://github.com/") { bail!("unexpected release download address"); }
    let setup_sha256 = setup["digest"].as_str().and_then(|d| d.strip_prefix("sha256:")).filter(|h| h.len() == 64).map(str::to_string);
    Ok(Some(Release { version, page, setup_url, setup_sha256, vsig_url }))
}

/// What a release signature covers: the version and the setup's sha256 (hex).
fn signed_message(version: &str, sha256_hex: &str) -> String { format!("nether-update\n{version}\n{sha256_hex}\n") }

fn keys() -> impl Iterator<Item = &'static str> {
    RELEASE_KEYS.lines().map(str::trim).filter(|l| l.len() == 64 && l.bytes().all(|b| b.is_ascii_hexdigit()))
}

fn verify(key_hex: &str, data: &[u8], sig_hex: &str) -> Result<()> {
    let key = hex::decode(key_hex.trim()).map_err(|_| anyhow!("bad key"))?;
    let sig = hex::decode(sig_hex.trim()).map_err(|_| anyhow!("bad signature"))?;
    ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, key).verify(data, &sig).map_err(|_| anyhow!("the update's signature doesn't match: not installing it"))
}

/// The setup at `r.path` still is the signed one for `r.version` (call right before running it).
pub fn check(r: &Ready) -> Result<()> {
    let data = std::fs::read(&r.path)?;
    let msg = signed_message(&r.version, &hex::encode(Sha256::digest(&data)));
    if keys().any(|k| verify(k, msg.as_bytes(), &r.sig).is_ok()) { Ok(()) } else { bail!("the update's signature doesn't match: not installing it") }
}

/// Download the release's setup into `<data>/updates/` and verify it.
pub fn download(c: &Client, paths: &Paths, rel: &Release) -> Result<Ready> {
    let dir = paths.updates();
    std::fs::create_dir_all(&dir)?;
    // old downloads are useless once a newer one is here
    if let Ok(rd) = std::fs::read_dir(&dir) { for e in rd.flatten() { if !e.file_name().to_string_lossy().contains(&rel.version) { let _ = std::fs::remove_file(e.path()); } } }
    let dest = dir.join(format!("NetherClientSetup-{}.exe", rel.version.replace(|ch: char| !ch.is_ascii_alphanumeric() && ch != '.' && ch != '-', "")));
    let hash = rel.setup_sha256.as_deref().map(Check::Sha256).unwrap_or(Check::None);
    fetch(c, &rel.setup_url, &dest, hash).context("couldn't download the update")?;
    let mut sig = String::new();
    { use std::io::Read; c.get(&rel.vsig_url).send()?.error_for_status()?.take(513).read_to_string(&mut sig)?; }
    if sig.len() > 512 { bail!("bad update signature file"); }
    let ready = Ready { path: dest, version: rel.version.clone(), sig: sig.trim().to_string() };
    if let Err(e) = check(&ready) { let _ = std::fs::remove_file(&ready.path); return Err(e); }
    Ok(ready)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::signature::{Ed25519KeyPair, KeyPair};

    /// Live check against the published releases (network): what an installed `NETHER_UPDATE_FROM` (default 0.1.1)
    /// would find, download and verify. `cargo test -p nether-core --release live_release -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn live_release() {
        let from = std::env::var("NETHER_UPDATE_FROM").unwrap_or_else(|_| "0.1.1".into());
        let tmp = std::env::temp_dir().join(format!("nether-update-test-{}", std::process::id()));
        std::env::set_var("NETHER_HOME", &tmp);
        let c = crate::download::client();
        let rel = latest(&c, &from, std::env::var("NETHER_UPDATE_BETA").is_ok()).unwrap().expect("a newer release");
        let r = download(&c, &Paths::new(), &rel).unwrap();
        println!("{from} -> {} verified: {} ({} bytes)", rel.version, r.path.display(), std::fs::metadata(&r.path).unwrap().len());
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn versions_compare() {
        assert!(newer("v0.2.0", "0.1.9"));
        assert!(newer("0.10.0", "0.9.3"));
        assert!(!newer("v0.1.0", "0.1.0"));
        assert!(!newer("0.1.0-beta", "0.1.0"));
        assert!(newer("0.4.0", "0.4.0-beta.3"), "the release replaces its betas");
        assert!(newer("v0.4.0-beta.2", "0.4.0-beta.1") && !newer("0.4.0-beta.1", "0.4.0-beta.1"));
        assert!(newer("0.4.0-beta.1", "0.3.5") && !newer("0.3.5", "0.4.0-beta.1"));
        assert!(newer("0.4.0-beta.10", "0.4.0-beta.9"));
    }

    #[test]
    fn signature_checks() {
        let pkcs8 = Ed25519KeyPair::generate_pkcs8(&ring::rand::SystemRandom::new()).unwrap();
        let kp = Ed25519KeyPair::from_pkcs8(pkcs8.as_ref()).unwrap();
        let hex = |b: &[u8]| b.iter().map(|x| format!("{x:02x}")).collect::<String>();
        let key = hex(kp.public_key().as_ref());
        let sig = hex(kp.sign(b"setup bytes").as_ref());
        assert!(verify(&key, b"setup bytes", &sig).is_ok());
        assert!(verify(&key, b"tampered bytes", &sig).is_err());
        assert!(keys().all(|k| verify(k, b"setup bytes", &sig).is_err())); // not an embedded key
        assert!(keys().count() >= 1);
    }

    #[test]
    fn version_is_signed_too() {
        let pkcs8 = Ed25519KeyPair::generate_pkcs8(&ring::rand::SystemRandom::new()).unwrap();
        let kp = Ed25519KeyPair::from_pkcs8(pkcs8.as_ref()).unwrap();
        let key = hex::encode(kp.public_key().as_ref());
        let sha = hex::encode(Sha256::digest(b"setup"));
        let sig = hex::encode(kp.sign(signed_message("0.2.0", &sha).as_bytes()).as_ref());
        assert!(verify(&key, signed_message("0.2.0", &sha).as_bytes(), &sig).is_ok());
        assert!(verify(&key, signed_message("0.3.0", &sha).as_bytes(), &sig).is_err(), "an old setup relabelled as newer");
    }
}
