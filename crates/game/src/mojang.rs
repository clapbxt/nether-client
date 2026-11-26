use std::collections::HashMap;

use anyhow::{anyhow, bail, Result};
use reqwest::blocking::Client;
use serde::Deserialize;
use serde_json::Value;

use crate::download::{fetch, Check};
use crate::paths::Paths;

pub const MANIFEST_URL: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

#[derive(Deserialize)]
pub struct Manifest {
    pub versions: Vec<ManifestVersion>,
}

#[derive(Deserialize, Clone)]
pub struct ManifestVersion {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub url: String,
    #[serde(default)]
    pub sha1: String,
}

#[derive(Deserialize, Clone)]
pub struct VersionJson {
    pub id: String,
    #[serde(rename = "mainClass")]
    pub main_class: String,
    #[serde(default)]
    pub arguments: Option<Arguments>,
    #[serde(rename = "minecraftArguments", default)]
    pub legacy_args: Option<String>,
    pub libraries: Vec<Library>,
    #[serde(rename = "assetIndex")]
    pub asset_index: AssetIndexRef,
    pub downloads: Downloads,
    #[serde(rename = "javaVersion", default)]
    pub java: Option<JavaVer>,
    #[serde(rename = "type", default)]
    pub kind: String,
    /// Mojang's log4j config (the Log4Shell-patched one for 1.7 to 1.18.1).
    #[serde(default)]
    pub logging: Option<Logging>,
}

#[derive(Deserialize, Clone, Default)]
pub struct Logging {
    #[serde(default)]
    pub client: Option<LogClient>,
}

#[derive(Deserialize, Clone)]
pub struct LogClient {
    pub argument: String,
    pub file: LogFile,
}

#[derive(Deserialize, Clone)]
pub struct LogFile {
    pub id: String,
    pub sha1: String,
    pub url: String,
}

#[derive(Deserialize, Clone, Default)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<Arg>,
    #[serde(default)]
    pub jvm: Vec<Arg>,
}

#[derive(Deserialize, Clone)]
#[serde(untagged)]
pub enum Arg {
    Plain(String),
    Ruled { rules: Vec<Rule>, value: Value },
}

#[derive(Deserialize, Clone)]
pub struct Rule {
    pub action: String,
    #[serde(default)]
    pub os: Option<OsRule>,
    #[serde(default)]
    pub features: Option<HashMap<String, bool>>,
}

#[derive(Deserialize, Clone)]
pub struct OsRule {
    pub name: Option<String>,
    pub arch: Option<String>,
}

#[derive(Deserialize, Clone)]
pub struct Library {
    pub name: String,
    #[serde(default)]
    pub downloads: Option<LibDownloads>,
    #[serde(default)]
    pub rules: Option<Vec<Rule>>,
    #[serde(default)]
    pub natives: Option<HashMap<String, String>>,
    /// Maven-style libraries (Fabric): base repository URL, optional checksum.
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub sha1: Option<String>,
}

/// `group:artifact:version[:classifier]` -> repository-relative jar path.
pub fn maven_path(name: &str) -> String {
    let mut p = name.split(':');
    let (g, a, v) = (p.next().unwrap_or(""), p.next().unwrap_or(""), p.next().unwrap_or(""));
    let cls = p.next().map(|c| format!("-{c}")).unwrap_or_default();
    format!("{}/{a}/{v}/{a}-{v}{cls}.jar", g.replace('.', "/"))
}

#[derive(Deserialize, Clone)]
pub struct LibDownloads {
    pub artifact: Option<Artifact>,
    pub classifiers: Option<HashMap<String, Artifact>>,
}

#[derive(Deserialize, Clone)]
pub struct Artifact {
    pub path: Option<String>,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

#[derive(Deserialize, Clone)]
pub struct AssetIndexRef {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

#[derive(Deserialize, Clone)]
pub struct Downloads {
    pub client: Artifact,
    /// The dedicated server jar (Horizon's preparer reads it once).
    #[serde(default)]
    pub server: Option<Artifact>,
}

#[derive(Deserialize, Clone)]
pub struct JavaVer {
    pub component: String,
    #[serde(rename = "majorVersion")]
    pub major: u32,
}

#[derive(Deserialize)]
pub struct AssetIndex {
    pub objects: HashMap<String, AssetObject>,
}

#[derive(Deserialize)]
pub struct AssetObject {
    pub hash: String,
    pub size: u64,
}

fn rule_matches(r: &Rule) -> bool {
    if let Some(os) = &r.os {
        if let Some(n) = &os.name {
            if n != "windows" { return false; }
        }
        if let Some(a) = &os.arch {
            if a != "x86_64" && a != "x64" { return false; } // we only target 64-bit x86 (skips x86 and arm64 natives)
        }
    }
    if let Some(f) = &r.features {
        // none of the optional launcher features (demo, custom resolution, quick play) are enabled
        if f.values().any(|v| *v) { return false; }
    }
    true
}

/// Mojang rule semantics: no rules = allowed; last matching rule wins; default deny once rules exist.
pub fn rules_allow(rules: &Option<Vec<Rule>>) -> bool {
    let Some(rules) = rules else { return true };
    let mut allowed = false;
    for r in rules {
        if rule_matches(r) { allowed = r.action == "allow"; }
    }
    allowed
}

impl Arg {
    pub fn values(&self) -> Vec<String> {
        match self {
            Arg::Plain(s) => vec![s.clone()],
            Arg::Ruled { rules, value } => {
                if !rules_allow(&Some(rules.clone())) { return vec![]; }
                match value {
                    Value::String(s) => vec![s.clone()],
                    Value::Array(a) => a.iter().filter_map(|v| v.as_str().map(String::from)).collect(),
                    _ => vec![],
                }
            }
        }
    }
}

pub fn manifest(c: &Client) -> Result<Manifest> {
    Ok(serde_json::from_value(crate::download::get_cached(c, MANIFEST_URL, &[], std::time::Duration::from_secs(3600))?)?)
}

/// Fetch (and cache) the version JSON for `id`.
/// A Minecraft version id that is safe as a folder name (ids come from packs and imports too): no separators, `:`,
/// `..` or control characters. Mojang's own ids include spaces ("1.14 Pre-Release 1"), so those stay allowed.
pub fn valid_version_id(id: &str) -> bool {
    id.len() <= 64 && crate::download::safe_file_name(id)
}

pub fn version_json(c: &Client, paths: &Paths, id: &str) -> Result<VersionJson> {
    if !valid_version_id(id) { bail!("'{id}' is not a Minecraft version"); }
    let file = paths.versions().join(id).join(format!("{id}.json"));
    if file.exists() {
        if let Ok(v) = serde_json::from_slice::<VersionJson>(&std::fs::read(&file)?) { return Ok(v); }
    }
    let m = manifest(c)?;
    let mv = m.versions.iter().find(|v| v.id == id).ok_or_else(|| anyhow!("unknown Minecraft version {id}"))?;
    let sha = mv.sha1.clone();
    fetch(c, &mv.url, &file, if sha.is_empty() { Check::None } else { Check::Sha1(&sha) })?;
    Ok(serde_json::from_slice(&std::fs::read(&file)?)?)
}

/// A loader version as loader metas write them ("0.16.9", "0.26.4-beta.5"); it ends up in meta URLs, so nothing else.
pub fn valid_loader_version(v: &str) -> bool {
    (1..=48).contains(&v.len()) && v.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'+' | b'_')) && !v.contains("..")
}
