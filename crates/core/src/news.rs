//! Home page news: the newest Minecraft release and snapshot (Mojang's launcher feed) and the newest Fabric loader.
//! Nether's own news (backend content repo) comes first. Cached; every source is optional.
use std::time::Duration;

use reqwest::blocking::Client;
#[cfg(test)]
use serde_json::Value;

use crate::download::get_cached;

pub struct Item {
    pub tag: String,
    pub title: String,
    pub url: String,
    /// card image (https URL; empty = the UI's own art)
    pub image: String,
}

const TTL: Duration = Duration::from_secs(3600);

fn wiki(version: &str) -> String { format!("https://minecraft.wiki/?search=Java+Edition+{}", version.replace(' ', "+")) }

/// Newest entries from the feeds; empty when offline.
pub fn latest(c: &Client) -> Vec<Item> {
    let mut out: Vec<Item> = crate::backend::news(c).into_iter().take(2).map(|n| Item { tag: n.tag, title: n.title, url: n.url, image: String::new() }).collect();
    if let Ok(v) = get_cached(c, "https://launchercontent.mojang.com/v2/javaPatchNotes.json", &[], TTL) {
        let entries = v["entries"].as_array().cloned().unwrap_or_default();
        // the feed is newest-first; a release older than the current snapshot cycle is still the newest release
        for (kind, tag) in [("release", "RELEASE"), ("snapshot", "SNAPSHOT")] {
            if let Some(e) = entries.iter().find(|e| e["type"].as_str() == Some(kind)) {
                let ver = e["version"].as_str().unwrap_or_default();
                let title = e["title"].as_str().unwrap_or(ver);
                // the art Mojang's own launcher shows with these patch notes
                let image = e["image"]["url"].as_str().filter(|u| u.starts_with('/')).map(|u| format!("https://launchercontent.mojang.com{u}")).unwrap_or_default();
                out.push(Item { tag: tag.into(), title: title.into(), url: wiki(ver), image });
            }
        }
    }
    if let Ok(v) = get_cached(c, "https://meta.fabricmc.net/v2/versions/loader", &[], TTL) {
        if let Some(l) = v.as_array().and_then(|a| a.iter().find(|l| l["stable"].as_bool().unwrap_or(false))) {
            let ver = l["version"].as_str().unwrap_or_default();
            out.push(Item { tag: "FABRIC".into(), title: format!("Fabric Loader {ver}"), url: "https://fabricmc.net/".into(), image: String::new() });
        }
    }
    out
}

/// `[(entries…)]` helper for tests: picks from a parsed feed the same way `latest` does.
#[cfg(test)]
fn pick(v: &Value, kind: &str) -> Option<String> {
    v["entries"].as_array()?.iter().find(|e| e["type"].as_str() == Some(kind)).and_then(|e| e["version"].as_str()).map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn picks_newest_of_each_kind() {
        let v: Value = serde_json::json!({"entries": [
            {"type": "snapshot", "version": "26.4-snapshot-8"},
            {"type": "snapshot", "version": "26.4-snapshot-7"},
            {"type": "release", "version": "26.3"},
            {"type": "release", "version": "26.2"},
        ]});
        assert_eq!(pick(&v, "release").as_deref(), Some("26.3"));
        assert_eq!(pick(&v, "snapshot").as_deref(), Some("26.4-snapshot-8"));
        assert!(wiki("26.3").ends_with("Java+Edition+26.3"));
    }
}
