//! Fabric-family loader support (Fabric, Quilt, Legacy Fabric): fetch the loader profile from the loader's meta API and merge it into the vanilla version.
use anyhow::{anyhow, Result};
use reqwest::blocking::Client;
use serde_json::Value;

use crate::download::get_json;
use crate::mojang::{Arguments, Library, VersionJson};

/// Which meta service to use; all three share the same API shape.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Flavor {
    Fabric,
    Quilt,
    LegacyFabric,
}

impl Flavor {
    fn meta(self) -> &'static str {
        match self {
            Flavor::Fabric => "https://meta.fabricmc.net/v2/versions/loader",
            Flavor::Quilt => "https://meta.quiltmc.org/v3/versions/loader",
            Flavor::LegacyFabric => "https://meta.legacyfabric.net/v2/versions/loader",
        }
    }
    pub fn name(self) -> &'static str {
        match self { Flavor::Fabric => "Fabric", Flavor::Quilt => "Quilt", Flavor::LegacyFabric => "Legacy Fabric" }
    }
}

/// Pick the loader version to use from a meta `versions/loader/<mc>` list: first stable one, else first non-pre-release, else first.
fn pick(list: &[Value]) -> Option<String> {
    let ver = |e: &Value| e["loader"]["version"].as_str().map(String::from);
    let pre = |v: &str| v.contains("beta") || v.contains("alpha") || v.contains("rc") || v.contains("pre");
    list.iter().find(|e| e["loader"]["stable"].as_bool() == Some(true)).and_then(ver)
        .or_else(|| list.iter().filter_map(ver).find(|v| !pre(v)))
        .or_else(|| list.first().and_then(ver))
}

/// Latest stable loader version of `flavor` for a Minecraft version.
pub fn latest(c: &Client, flavor: Flavor, mc: &str) -> Result<String> {
    let list: Vec<Value> = get_json(c, &format!("{}/{mc}", flavor.meta()))?;
    pick(&list).ok_or_else(|| anyhow!("{} does not support Minecraft {mc}", flavor.name()))
}

/// Latest stable Fabric loader version for a Minecraft version.
pub fn latest_loader(c: &Client, mc: &str) -> Result<String> { latest(c, Flavor::Fabric, mc) }

/// Merge the profile of `flavor` for `mc` (+ optional pinned loader) into `v`. Returns the merged version and loader version.
pub fn apply_flavor(c: &Client, flavor: Flavor, mut v: VersionJson, mc: &str, loader: Option<&str>) -> Result<(VersionJson, String)> {
    let lv = match loader { Some(l) => l.to_string(), None => latest(c, flavor, mc)? };
    let p: Value = get_json(c, &format!("{}/{mc}/{lv}/profile/json", flavor.meta()))?;
    v.main_class = p["mainClass"].as_str().ok_or_else(|| anyhow!("{} profile has no mainClass", flavor.name()))?.to_string();
    if let Some(a) = p.get("arguments") {
        let extra: Arguments = serde_json::from_value(a.clone())?;
        let base = v.arguments.get_or_insert_with(Default::default);
        base.jvm.extend(extra.jvm);
        base.game.extend(extra.game);
    }
    let libs: Vec<Library> = serde_json::from_value(p["libraries"].clone())?;
    // the loader's own build of a library replaces the game's (Legacy Fabric ships its LWJGL 2): never both on the classpath
    let ids: std::collections::HashSet<String> = libs.iter().map(|l| lib_id(&l.name)).collect();
    v.libraries.retain(|l| !ids.contains(&lib_id(&l.name)));
    v.libraries.extend(libs);
    Ok((v, lv))
}

/// `group:artifact[:classifier]` of a `group:artifact:version[:classifier]` name.
fn lib_id(name: &str) -> String {
    let p: Vec<&str> = name.split(':').collect();
    match p.as_slice() { [g, a, _, c, ..] => format!("{g}:{a}:{c}"), [g, a, ..] => format!("{g}:{a}"), _ => name.to_string() }
}

/// Merge the Fabric profile for `mc` (+ optional pinned loader) into `v`. Returns the merged version and loader version.
pub fn apply(c: &Client, v: VersionJson, mc: &str, loader: Option<&str>) -> Result<(VersionJson, String)> {
    apply_flavor(c, Flavor::Fabric, v, mc, loader)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn library_ids_ignore_versions() {
        assert_eq!(lib_id("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209"), lib_id("org.lwjgl.lwjgl:lwjgl:2.9.4+legacyfabric.17"));
        assert_ne!(lib_id("org.lwjgl:lwjgl:3.3.3"), lib_id("org.lwjgl:lwjgl:3.3.3:natives-windows"));
    }

    #[test]
    fn picks_stable_then_non_prerelease() {
        let l = vec![json!({"loader": {"version": "0.9.0-beta.1"}}), json!({"loader": {"version": "0.8.2"}})];
        assert_eq!(pick(&l).as_deref(), Some("0.8.2"));
        let l = vec![json!({"loader": {"version": "0.16.0", "stable": false}}), json!({"loader": {"version": "0.15.0", "stable": true}})];
        assert_eq!(pick(&l).as_deref(), Some("0.15.0"));
        assert_eq!(pick(&[]), None);
    }
}
