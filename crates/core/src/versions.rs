//! The Minecraft versions offered when making a profile, per loader. Refreshed while the launcher runs (see the app's
//! version watcher), cached on disk so profiles can still be made offline, and only versions a loader really
//! supports are listed for it: Fabric / Quilt / Legacy Fabric from their meta services, Forge from its promotions,
//! NeoForge from its Maven metadata.
use std::time::Duration;

use reqwest::blocking::Client;
use serde_json::Value;

use crate::download::{cached_json, get_cached};
use crate::mojang::MANIFEST_URL;

/// How long a fetched list is reused before asking again (the watcher refreshes about this often).
pub const TTL: Duration = Duration::from_secs(20 * 60);

#[derive(Default, Clone, Debug, PartialEq)]
pub struct Lists {
    /// Releases, newest first.
    pub release: Vec<String>,
    /// Mojang's newest release.
    pub latest: String,
    pub fabric: Vec<String>,
    pub quilt: Vec<String>,
    pub legacy: Vec<String>,
    pub forge: Vec<String>,
    pub neoforge: Vec<String>,
    /// Sources that answered neither now nor ever before (their list is empty): "Mojang", "Fabric"…
    pub failed: Vec<&'static str>,
}

/// Every list (blocking, a few small requests; each falls back to its last copy on disk).
pub fn load(c: &Client) -> Lists {
    let mut l = Lists::default();
    match get_cached(c, MANIFEST_URL, &[], TTL) {
        Ok(m) => {
            l.release = m["versions"].as_array().map(|a| a.iter().filter(|v| v["type"] == "release").filter_map(|v| v["id"].as_str().map(String::from)).collect()).unwrap_or_default();
            l.latest = m["latest"]["release"].as_str().unwrap_or_default().to_string();
        }
        Err(_) => l.failed.push("Mojang"),
    }
    // stable game versions from a Fabric-family meta service (same shape for all three)
    let mut games = |url: &str, who: &'static str| -> Vec<String> {
        match get_cached(c, url, &[], TTL) {
            Ok(v) => stable(&v),
            Err(_) => { l.failed.push(who); vec![] }
        }
    };
    let fabric = games("https://meta.fabricmc.net/v2/versions/game", "Fabric");
    let quilt = games("https://meta.quiltmc.org/v3/versions/game", "Quilt");
    let legacy = games("https://meta.legacyfabric.net/v2/versions/game", "Legacy Fabric");
    (l.fabric, l.quilt, l.legacy) = (fabric, quilt, legacy);
    match get_cached(c, crate::forge::FORGE_PROMOS, &[], TTL) {
        Ok(p) => l.forge = forge_versions(&l.release, &p),
        Err(_) => l.failed.push("Forge"),
    }
    let neo = cached_json(&format!("{}/maven-metadata.xml", crate::forge::NEO_MAVEN), TTL, || {
        Ok(Value::String(c.get(format!("{}/maven-metadata.xml", crate::forge::NEO_MAVEN)).send()?.error_for_status()?.text()?))
    });
    match neo {
        Ok(Value::String(xml)) => l.neoforge = neoforge_versions(&l.release, &xml),
        _ => l.failed.push("NeoForge"),
    }
    l
}

fn stable(v: &Value) -> Vec<String> {
    v.as_array().map(|a| a.iter().filter(|g| g["stable"].as_bool().unwrap_or(false)).filter_map(|g| g["version"].as_str().map(String::from)).collect()).unwrap_or_default()
}

/// Releases Forge has a build for (promotions "<mc>-latest" / "<mc>-recommended"), newest first.
fn forge_versions(release: &[String], promos: &Value) -> Vec<String> {
    let Some(p) = promos["promos"].as_object() else { return vec![] };
    release.iter().filter(|mc| p.contains_key(&format!("{mc}-latest")) || p.contains_key(&format!("{mc}-recommended"))).cloned().collect()
}

/// Releases NeoForge has a build for (a Maven version under the release's prefix), newest first.
fn neoforge_versions(release: &[String], xml: &str) -> Vec<String> {
    let all: Vec<&str> = xml.split("<version>").skip(1).filter_map(|s| s.split('<').next()).collect();
    release.iter().filter(|mc| { let pre = crate::forge::neo_prefix(mc); all.iter().any(|v| v.starts_with(&pre)) }).cloned().collect()
}

/// A release newer than `known` came out: `latest` is listed before `known` (Mojang lists newest first).
pub fn is_newer(release: &[String], latest: &str, known: &str) -> bool {
    if latest.is_empty() || known.is_empty() || latest == known { return false; }
    match (release.iter().position(|v| v == latest), release.iter().position(|v| v == known)) {
        (Some(a), Some(b)) => a < b,
        (Some(_), None) => false, // known isn't a release we can place: don't announce
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn s(a: &[&str]) -> Vec<String> { a.iter().map(|x| x.to_string()).collect() }

    #[test]
    fn loader_lists_follow_their_builds() {
        let rel = s(&["26.4", "26.3", "1.21.11", "1.21.1", "1.20.1"]);
        let promos = json!({"promos": {"1.21.11-latest": "61.0.1", "1.20.1-recommended": "47.3.0", "1.20.1-latest": "47.4.0"}});
        assert_eq!(forge_versions(&rel, &promos), s(&["1.21.11", "1.20.1"]));
        let xml = "<metadata><versioning><versions><version>21.1.77</version><version>21.11.3-beta</version><version>26.3.0.4</version></versions></versioning></metadata>";
        assert_eq!(neoforge_versions(&rel, xml), s(&["26.3", "1.21.11", "1.21.1"]));
        assert_eq!(stable(&json!([{"version": "26.4-rc1", "stable": false}, {"version": "26.3", "stable": true}])), s(&["26.3"]));
    }

    #[test]
    fn new_release_detection() {
        let rel = s(&["26.4", "26.3", "1.21.11"]);
        assert!(is_newer(&rel, "26.4", "26.3"));
        assert!(!is_newer(&rel, "26.3", "26.3") && !is_newer(&rel, "26.3", "26.4") && !is_newer(&rel, "26.4", ""));
    }
}
