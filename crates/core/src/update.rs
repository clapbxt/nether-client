//! Launcher auto-update: the newest release in the public releases repo, its setup downloaded (sha256 from GitHub) and
//! checked against the Ed25519 release key embedded here (`tools/release-sign.mjs`). Only a verified setup is returned.
use std::path::PathBuf;

use anyhow::{anyhow, bail, Context, Result};
use reqwest::blocking::Client;

use crate::download::{fetch, Check};
use crate::paths::Paths;

/// The public source repo; its GitHub releases carry the signed setups.
pub const RELEASES_REPO: &str = "clapbxt/nether-client";
const RELEASE_KEY_HEX: &str = include_str!("../res/release-ed25519.pub");

#[derive(Clone, Debug)]
pub struct Release {
    pub version: String,
    /// release page (notes) on GitHub
    pub page: String,
    setup_url: String,
    setup_sha256: Option<String>,
    sig_url: String,
}

/// Numeric parts of a version tag ("v0.2.0-beta" -> [0, 2, 0]).
fn parts(v: &str) -> Vec<u32> {
    v.trim().trim_start_matches(['v', 'V']).split(['-', '+', ' ']).next().unwrap_or("").split('.').map(|p| p.parse().unwrap_or(0)).collect()
}
pub fn newer(tag: &str, current: &str) -> bool {
    let (a, b) = (parts(tag), parts(current));
    let at = |v: &[u32], i: usize| v.get(i).copied().unwrap_or(0);
    (0..a.len().max(b.len())).map(|i| at(&a, i).cmp(&at(&b, i))).find(|o| o.is_ne()) == Some(std::cmp::Ordering::Greater)
}

/// The latest release if it is newer than `current` (None = up to date or nothing published yet).
pub fn latest(c: &Client, current: &str) -> Result<Option<Release>> {
    let r = c.get(format!("https://api.github.com/repos/{RELEASES_REPO}/releases/latest")).header("Accept", "application/vnd.github+json").send()?;
    if r.status().as_u16() == 404 { return Ok(None); }
    let v: serde_json::Value = r.error_for_status()?.json()?;
    let tag = v["tag_name"].as_str().unwrap_or_default();
    let version = tag.trim_start_matches(['v', 'V']).to_string();
    if version.is_empty() || !newer(tag, current) { return Ok(None); }
    let page = v["html_url"].as_str().unwrap_or_default().to_string();
    let assets = v["assets"].as_array().cloned().unwrap_or_default();
    let find = |suffix: &str| assets.iter().find(|a| a["name"].as_str().is_some_and(|n| n.starts_with("NetherClientSetup") && n.ends_with(suffix))).cloned();
    let (Some(setup), Some(sig)) = (find(".exe"), find(".exe.sig")) else { bail!("release {version} has no signed setup") };
    let url = |a: &serde_json::Value| a["browser_download_url"].as_str().unwrap_or_default().to_string();
    let (setup_url, sig_url) = (url(&setup), url(&sig));
    let ok = |u: &str| u.starts_with(&format!("https://github.com/{RELEASES_REPO}/releases/download/"));
    if !ok(&setup_url) || !ok(&sig_url) || !page.starts_with("https://github.com/") { bail!("unexpected release download address"); }
    let setup_sha256 = setup["digest"].as_str().and_then(|d| d.strip_prefix("sha256:")).filter(|h| h.len() == 64).map(str::to_string);
    Ok(Some(Release { version, page, setup_url, setup_sha256, sig_url }))
}

fn verify(key_hex: &str, data: &[u8], sig_hex: &str) -> Result<()> {
    let hex = |s: &str| -> Result<Vec<u8>> {
        let s = s.trim();
        if s.len() % 2 != 0 { bail!("bad hex"); }
        (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).map_err(|_| anyhow!("bad hex"))).collect()
    };
    let (key, sig) = (hex(key_hex)?, hex(sig_hex)?);
    ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, key).verify(data, &sig).map_err(|_| anyhow!("the update's signature doesn't match: not installing it"))
}

/// Download the release's setup into `<data>/updates/` and verify it. Returns the verified setup path.
pub fn download(c: &Client, paths: &Paths, rel: &Release) -> Result<PathBuf> {
    let dir = paths.updates();
    std::fs::create_dir_all(&dir)?;
    // old downloads are useless once a newer one is here
    if let Ok(rd) = std::fs::read_dir(&dir) { for e in rd.flatten() { if !e.file_name().to_string_lossy().contains(&rel.version) { let _ = std::fs::remove_file(e.path()); } } }
    let dest = dir.join(format!("NetherClientSetup-{}.exe", rel.version.replace(|ch: char| !ch.is_ascii_alphanumeric() && ch != '.' && ch != '-', "")));
    let check = rel.setup_sha256.as_deref().map(Check::Sha256).unwrap_or(Check::None);
    fetch(c, &rel.setup_url, &dest, check).context("couldn't download the update")?;
    let sig = c.get(&rel.sig_url).send()?.error_for_status()?.text()?;
    if sig.len() > 512 { bail!("bad update signature file"); }
    let data = std::fs::read(&dest)?;
    if let Err(e) = verify(RELEASE_KEY_HEX, &data, &sig) { let _ = std::fs::remove_file(&dest); return Err(e); }
    Ok(dest)
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
        let rel = latest(&c, &from).unwrap().expect("a newer release");
        let path = download(&c, &Paths::new(), &rel).unwrap();
        println!("{from} -> {} verified: {} ({} bytes)", rel.version, path.display(), std::fs::metadata(&path).unwrap().len());
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn versions_compare() {
        assert!(newer("v0.2.0", "0.1.9"));
        assert!(newer("0.10.0", "0.9.3"));
        assert!(!newer("v0.1.0", "0.1.0"));
        assert!(!newer("0.1.0-beta", "0.1.0"));
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
        assert!(verify(RELEASE_KEY_HEX, b"setup bytes", &sig).is_err()); // other key
        assert_eq!(RELEASE_KEY_HEX.trim().len(), 64);
    }
}
