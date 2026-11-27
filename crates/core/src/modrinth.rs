//! Modrinth: search mods for an instance's version/loader and install them (with required dependencies).
use std::collections::HashSet;
use std::fs;
use std::path::Path;

use anyhow::{anyhow, bail, Result};
use reqwest::blocking::Client;
use serde_json::{json, Value};
use sha2::{Digest, Sha512};

const API: &str = "https://api.modrinth.com/v2";
// how long cached Modrinth answers are reused (see download::cached_json)
const MIN10: std::time::Duration = std::time::Duration::from_secs(600);
const MIN30: std::time::Duration = std::time::Duration::from_secs(1800);
const HOURS6: std::time::Duration = std::time::Duration::from_secs(6 * 3600);

use crate::download::{get_cached, post_cached};

#[derive(Clone, Debug, Default)]
pub struct Hit {
    pub id: String,
    pub title: String,
    pub author: String,
    pub desc: String,
    pub downloads: u64,
    pub icon_url: String,
    /// newest game version the project lists, and its loaders (from search categories)
    pub mc: String,
    pub loaders: Vec<String>,
}

#[derive(Clone, Debug, Default)]
pub struct VersionInfo {
    pub id: String,
    pub project_id: String,
    pub number: String,
    pub name: String,
    pub date: String,
    pub filename: String,
    pub size_kb: u64,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    /// "release", "beta" or "alpha"
    pub kind: String,
    pub downloads: u64,
}

#[derive(Clone, Debug, Default)]
pub struct Detail {
    pub id: String,
    pub title: String,
    pub description: String,
    pub body: String,
    pub icon_url: String,
    pub downloads: u64,
    pub followers: u64,
    pub categories: Vec<String>,
    pub loaders: Vec<String>,
    pub mc_versions: Vec<String>,
    pub license: String,
    pub page_url: String,
    pub author: String,
    pub published: String,
    pub updated: String,
    pub client_side: String,
    pub server_side: String,
    /// (label, url) for source / issues / wiki / discord, only the ones the project has
    pub links: Vec<(String, String)>,
    /// screenshot URLs (featured first)
    pub gallery: Vec<String>,
}

fn strs(v: &Value) -> Vec<String> {
    v.as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default()
}

fn version_info(v: &Value) -> VersionInfo {
    let files = v["files"].as_array().cloned().unwrap_or_default();
    let f = files.iter().find(|f| f["primary"].as_bool().unwrap_or(false)).or_else(|| files.first());
    VersionInfo {
        id: v["id"].as_str().unwrap_or_default().into(),
        project_id: v["project_id"].as_str().unwrap_or_default().into(),
        number: v["version_number"].as_str().unwrap_or_default().into(),
        name: v["name"].as_str().unwrap_or_default().into(),
        date: v["date_published"].as_str().unwrap_or_default().chars().take(10).collect(),
        filename: f.and_then(|f| f["filename"].as_str()).unwrap_or_default().into(),
        size_kb: f.and_then(|f| f["size"].as_u64()).unwrap_or(0) / 1024,
        game_versions: strs(&v["game_versions"]),
        loaders: strs(&v["loaders"]),
        kind: v["version_type"].as_str().unwrap_or("release").into(),
        downloads: v["downloads"].as_u64().unwrap_or(0),
    }
}

/// Newest release of `project` for `mc` (+ loader), if any.
pub fn latest_compatible(c: &Client, project: &str, mc: &str, loader: Option<&str>) -> Result<Option<VersionInfo>> {
    let mut q = vec![("game_versions", json!([mc]).to_string())];
    if let Some(l) = loader { q.push(("loaders", json!(loader_set(l)).to_string())); }
    let vs: Vec<Value> = serde_json::from_value(get_cached(c, &format!("{API}/project/{project}/version"), &q.iter().map(|(k, v)| (*k, v.clone())).collect::<Vec<_>>(), MIN30)?)?;
    Ok(vs.first().map(version_info))
}

/// Project page data plus the newest release compatible with the instance.
pub fn detail(c: &Client, project: &str, mc: &str, loader: Option<&str>) -> Result<(Detail, Option<VersionInfo>)> {
    let p: Value = get_cached(c, &format!("{API}/project/{project}"), &[], HOURS6)?;
    let kind = p["project_type"].as_str().unwrap_or("mod");
    let d = Detail {
        id: p["id"].as_str().unwrap_or_default().into(),
        title: p["title"].as_str().unwrap_or_default().into(),
        description: p["description"].as_str().unwrap_or_default().into(),
        body: p["body"].as_str().unwrap_or_default().into(),
        icon_url: p["icon_url"].as_str().unwrap_or_default().into(),
        downloads: p["downloads"].as_u64().unwrap_or(0),
        followers: p["followers"].as_u64().unwrap_or(0),
        categories: strs(&p["categories"]),
        loaders: strs(&p["loaders"]),
        mc_versions: strs(&p["game_versions"]),
        license: p["license"]["name"].as_str().unwrap_or_default().into(),
        page_url: format!("https://modrinth.com/{kind}/{}", p["slug"].as_str().unwrap_or(project)),
        author: owner(c, project).unwrap_or_default(),
        published: p["published"].as_str().unwrap_or_default().chars().take(10).collect(),
        updated: p["updated"].as_str().unwrap_or_default().chars().take(10).collect(),
        client_side: p["client_side"].as_str().unwrap_or_default().into(),
        server_side: p["server_side"].as_str().unwrap_or_default().into(),
        links: [("Source code", "source_url"), ("Issues", "issues_url"), ("Wiki", "wiki_url"), ("Discord", "discord_url")]
            .iter()
            .filter_map(|(k, f)| p[*f].as_str().filter(|u| !u.is_empty()).map(|u| (k.to_string(), u.to_string())))
            .collect(),
        gallery: {
            let mut g: Vec<&Value> = p["gallery"].as_array().map(|a| a.iter().collect()).unwrap_or_default();
            g.sort_by_key(|x| !x["featured"].as_bool().unwrap_or(false));
            g.iter().filter_map(|x| x["url"].as_str().map(String::from)).take(8).collect()
        },
    };
    let compat = latest_compatible(c, project, mc, loader)?;
    Ok((d, compat))
}

/// Username of the project owner (first member if there is no owner role).
fn owner(c: &Client, project: &str) -> Option<String> {
    let m: Vec<Value> = serde_json::from_value(get_cached(c, &format!("{API}/project/{project}/members"), &[], HOURS6).ok()?).ok()?;
    let o = m.iter().find(|x| x["role"].as_str() == Some("Owner")).or_else(|| m.first())?;
    o["user"]["username"].as_str().map(String::from)
}

/// sha512 of a file as lowercase hex.
pub fn file_sha512(p: &Path) -> Result<String> {
    Ok(hex::encode(Sha512::digest(fs::read(p)?)))
}

/// Identify installed jars by hash: sha512 -> the Modrinth version it is.
pub fn lookup(c: &Client, hashes: &[String]) -> Result<std::collections::HashMap<String, VersionInfo>> {
    let v: Value = post_cached(c, &format!("{API}/version_files"), &json!({"hashes": hashes, "algorithm": "sha512"}), HOURS6)?;
    Ok(v.as_object().map(|o| o.iter().map(|(k, v)| (k.clone(), version_info(v))).collect()).unwrap_or_default())
}

/// Download info for files Modrinth knows: sha512 -> (url, sha1, size).
pub fn file_meta(c: &Client, hashes: &[String]) -> Result<std::collections::HashMap<String, (String, String, u64)>> {
    if hashes.is_empty() { return Ok(Default::default()); }
    let v: Value = post_cached(c, &format!("{API}/version_files"), &json!({"hashes": hashes, "algorithm": "sha512"}), HOURS6)?;
    let mut out = std::collections::HashMap::new();
    for (h, ver) in v.as_object().into_iter().flatten() {
        let f = ver["files"].as_array().into_iter().flatten().find(|f| f["hashes"]["sha512"].as_str() == Some(h.as_str()));
        if let Some(f) = f {
            out.insert(h.clone(), (f["url"].as_str().unwrap_or_default().into(), f["hashes"]["sha1"].as_str().unwrap_or_default().into(), f["size"].as_u64().unwrap_or(0)));
        }
    }
    Ok(out)
}

/// Newest compatible release for each installed hash (for update checks).
pub fn updates(c: &Client, hashes: &[String], mc: &str, loader: Option<&str>) -> Result<std::collections::HashMap<String, VersionInfo>> {
    let loaders: Vec<&str> = loader.map(loader_set).unwrap_or_default();
    let v: Value = post_cached(c, &format!("{API}/version_files/update"), &json!({"hashes": hashes, "algorithm": "sha512", "loaders": loaders, "game_versions": [mc]}), MIN30)?;
    Ok(v.as_object().map(|o| o.iter().map(|(k, v)| (k.clone(), version_info(v))).collect()).unwrap_or_default())
}

/// project id -> (title, icon url)
pub fn projects(c: &Client, ids: &[String]) -> Result<std::collections::HashMap<String, (String, String)>> {
    if ids.is_empty() { return Ok(Default::default()); }
    let v: Vec<Value> = serde_json::from_value(get_cached(c, &format!("{API}/projects"), &[("ids", json!(ids).to_string())], HOURS6)?)?;
    Ok(v.iter().map(|p| (p["id"].as_str().unwrap_or_default().to_string(), (p["title"].as_str().unwrap_or_default().to_string(), p["icon_url"].as_str().unwrap_or_default().to_string()))).collect())
}

/// slug or id -> the project as a search hit (title, description, downloads, icon), for every project Modrinth knows.
pub fn hits(c: &Client, slugs: &[String]) -> Result<std::collections::HashMap<String, Hit>> {
    if slugs.is_empty() { return Ok(Default::default()); }
    let v: Vec<Value> = serde_json::from_value(get_cached(c, &format!("{API}/projects"), &[("ids", json!(slugs).to_string())], HOURS6)?)?;
    let mut out = std::collections::HashMap::new();
    for p in &v {
        let s = |k: &str| p[k].as_str().unwrap_or_default().to_string();
        let h = Hit { id: s("id"), title: s("title"), desc: s("description"), downloads: p["downloads"].as_u64().unwrap_or(0), icon_url: s("icon_url"), ..Default::default() };
        for k in slugs { if k.eq_ignore_ascii_case(&s("slug")) || *k == h.id { out.insert(k.clone(), h.clone()); } }
    }
    Ok(out)
}

/// slug or id -> (project id, title), for every project Modrinth knows (slugs are matched case-insensitively).
pub fn project_ids(c: &Client, slugs: &[String]) -> Result<std::collections::HashMap<String, (String, String)>> {
    if slugs.is_empty() { return Ok(Default::default()); }
    let v: Vec<Value> = serde_json::from_value(get_cached(c, &format!("{API}/projects"), &[("ids", json!(slugs).to_string())], HOURS6)?)?;
    let mut out = std::collections::HashMap::new();
    for p in &v {
        let (id, slug, title) = (p["id"].as_str().unwrap_or_default(), p["slug"].as_str().unwrap_or_default(), p["title"].as_str().unwrap_or_default());
        for s in slugs { if s.eq_ignore_ascii_case(slug) || s == id { out.insert(s.clone(), (id.to_string(), title.to_string())); } }
    }
    Ok(out)
}

/// Markdown -> readable plain text (drops images/html/headings marks), capped in length.
pub fn plain(md: &str, max: usize) -> String {
    let mut out = String::new();
    let mut gap = false;
    for line in md.lines() {
        let l = line.trim();
        if l.is_empty() { gap = true; continue; }
        if l.starts_with("![") || l.starts_with("[![") || l.starts_with("<img") || l.starts_with("---") || l.starts_with("```") || l.starts_with('|') { continue; }
        let heading = l.starts_with('#');
        let mut s = String::new();
        let mut in_tag = false;
        for ch in l.trim_start_matches(['#', '>', '-', '*', ' ']).chars() {
            match ch { '<' => in_tag = true, '>' => in_tag = false, c if !in_tag && c != '*' && c != '`' => s.push(c), _ => {} }
        }
        s = s.replace("~~", "");
        // ![alt](url) -> nothing
        while let Some(a) = s.find("![") {
            match s[a..].find(')') { Some(e) => s.replace_range(a..a + e + 1, ""), None => break }
        }
        // [text](url) -> text
        while let (Some(a), Some(b)) = (s.find("]("), s.find('[')) {
            if b >= a { break; }
            match s[a..].find(')') { Some(e) => s.replace_range(a..a + e + 1, ""), None => break }
            s.remove(b);
        }
        let s = s.trim();
        if s.is_empty() { continue; }
        // paragraphs and headings get breathing room; headings read as uppercase labels
        if (gap || heading) && !out.is_empty() { out.push('\n'); }
        gap = false;
        if heading { out.push_str(&s.to_uppercase()); } else { out.push_str(s); }
        out.push('\n');
        if out.chars().count() > max { break; }
    }
    let mut t: String = out.chars().take(max).collect();
    if out.chars().count() > max { t.push('…'); }
    t.trim().to_string()
}

/// Search projects of `kind` ("mod", "resourcepack", "shader") compatible with `mc` (and `loader` if given, e.g. "fabric").
/// `sort` is a Modrinth index ("relevance", "downloads", "follows", "newest", "updated"); `category` narrows further.
pub fn search(c: &Client, query: &str, kind: &str, mc: &str, loader: Option<&str>, sort: &str, category: Option<&str>) -> Result<Vec<Hit>> {
    let mut facets = vec![json!([format!("project_type:{kind}")])];
    if !mc.is_empty() { facets.push(json!([format!("versions:{mc}")])); }
    if let Some(l) = loader { facets.push(json!(loader_set(l).iter().map(|x| format!("categories:{x}")).collect::<Vec<_>>())); }
    if let Some(cat) = category { facets.push(json!([format!("categories:{cat}")])); }
    let sort = if query.is_empty() && sort == "relevance" { "downloads" } else { sort };
    let v: Value = get_cached(c, &format!("{API}/search"), &[("query", query.to_string()), ("facets", Value::Array(facets).to_string()), ("limit", "30".into()), ("index", sort.to_string())], MIN10)?;
    let hits = v["hits"].as_array().ok_or_else(|| anyhow!("unexpected Modrinth response"))?;
    Ok(hits
        .iter()
        .map(|h| Hit {
            id: h["project_id"].as_str().unwrap_or_default().into(),
            title: h["title"].as_str().unwrap_or_default().into(),
            author: h["author"].as_str().unwrap_or_default().into(),
            desc: h["description"].as_str().unwrap_or_default().into(),
            downloads: h["downloads"].as_u64().unwrap_or(0),
            icon_url: h["icon_url"].as_str().unwrap_or_default().into(),
            mc: h["versions"].as_array().and_then(|a| a.iter().rev().filter_map(|x| x.as_str()).find(|x| !x.contains('-') && !x.contains('w'))).unwrap_or_default().into(),
            loaders: strs(&h["categories"]).into_iter().filter(|c| LOADERS.contains(&c.as_str())).collect(),
        })
        .collect())
}

const LOADERS: [&str; 5] = ["fabric", "forge", "neoforge", "quilt", "vanilla"];

/// Every version of `project` (any game version), newest first.
pub fn all_versions(c: &Client, project: &str) -> Result<Vec<VersionInfo>> {
    let vs: Vec<Value> = serde_json::from_value(get_cached(c, &format!("{API}/project/{project}/version"), &[], MIN30)?)?;
    Ok(vs.iter().map(version_info).collect())
}

/// Every version of `project` built for `mc` (+ loader), newest first.
pub fn versions(c: &Client, project: &str, mc: &str, loader: Option<&str>) -> Result<Vec<VersionInfo>> {
    let mut q = vec![("game_versions", json!([mc]).to_string())];
    if let Some(l) = loader { q.push(("loaders", json!(loader_set(l)).to_string())); }
    let vs: Vec<Value> = serde_json::from_value(get_cached(c, &format!("{API}/project/{project}/version"), &q.iter().map(|(k, v)| (*k, v.clone())).collect::<Vec<_>>(), MIN30)?)?;
    Ok(vs.iter().map(version_info).collect())
}

/// A modpack's `.mrpack` file on Modrinth.
#[derive(Clone, Debug, Default)]
pub struct PackDownload {
    pub title: String,
    pub version: String,
    pub filename: String,
    pub url: String,
    pub sha1: String,
    pub sha512: String,
}

/// Resolve the `.mrpack` of a modpack project: `version` is a version id, None = newest version.
pub fn pack_file(c: &Client, project: &str, version: Option<&str>) -> Result<PackDownload> {
    let v: Value = match version {
        Some(id) => get_cached(c, &format!("{API}/version/{id}"), &[], HOURS6)?,
        None => {
            let list: Vec<Value> = serde_json::from_value(get_cached(c, &format!("{API}/project/{project}/version"), &[], MIN10)?)?;
            list.into_iter().next().ok_or_else(|| anyhow!("{project} has no versions"))?
        }
    };
    let pid = v["project_id"].as_str().unwrap_or(project);
    let files = v["files"].as_array().ok_or_else(|| anyhow!("version has no files"))?;
    let f = files.iter().find(|f| f["filename"].as_str().is_some_and(|n| n.ends_with(".mrpack")))
        .ok_or_else(|| anyhow!("{project} is not a modpack (no .mrpack file)"))?;
    let proj: Value = get_cached(c, &format!("{API}/project/{pid}"), &[], HOURS6)?;
    let s = |x: &Value| x.as_str().unwrap_or_default().to_string();
    Ok(PackDownload {
        title: s(&proj["title"]), version: s(&v["version_number"]), filename: s(&f["filename"]), url: s(&f["url"]),
        sha1: s(&f["hashes"]["sha1"]), sha512: s(&f["hashes"]["sha512"]),
    })
}

/// Stream a version file to `dest` (sha512-checked, temp file + rename).
fn download_file(c: &Client, f: &Value, dest: &Path, prog: &dyn Fn(&str, u64, u64)) -> Result<()> {
    let name = f["filename"].as_str().ok_or_else(|| anyhow!("file has no name"))?;
    let url = f["url"].as_str().ok_or_else(|| anyhow!("file has no url"))?;
    let mut resp = c.get(url).send()?.error_for_status()?;
    let total = resp.content_length().or_else(|| f["size"].as_u64()).unwrap_or(0);
    const MAX: usize = 512 << 20; // a mod or shader pack is never bigger
    let mut bytes = Vec::with_capacity((total as usize).min(64 << 20));
    let mut buf = [0u8; 32 * 1024];
    loop {
        let n = std::io::Read::read(&mut resp, &mut buf)?;
        if n == 0 { break; }
        bytes.extend_from_slice(&buf[..n]);
        if bytes.len() > MAX { bail!("{name} is larger than expected"); }
        prog(name, bytes.len() as u64, total);
    }
    // never unchecked: sha512 (always sent by Modrinth), else sha1
    match (f["hashes"]["sha512"].as_str(), f["hashes"]["sha1"].as_str()) {
        (Some(want), _) => if !hex::encode(Sha512::digest(&bytes)).eq_ignore_ascii_case(want) { bail!("hash mismatch for {name}"); },
        (None, Some(want)) => if !hex::encode(sha1::Sha1::digest(&bytes)).eq_ignore_ascii_case(want) { bail!("hash mismatch for {name}"); },
        (None, None) => bail!("{name} comes without a hash, so it can't be checked"),
    }
    crate::store::write_atomic(dest, &bytes)?;
    Ok(())
}

/// Install the already-resolved version `id` over `old` (same folder, keeps the `.disabled` state). Ok(None) = already that file.
/// Where a replaced file goes instead of being deleted (a snapshot).
type Keep<'a> = &'a mut dyn FnMut(&Path) -> Result<()>;

fn apply_version(c: &Client, old: &Path, old_hash: &str, id: &str, prog: &dyn Fn(&str, u64, u64), keep: Option<Keep>) -> Result<Option<std::path::PathBuf>> {
    let v: Value = get_cached(c, &format!("{API}/version/{id}"), &[], HOURS6)?;
    let files = v["files"].as_array().ok_or_else(|| anyhow!("version has no files"))?;
    let f = files.iter().find(|f| f["primary"].as_bool().unwrap_or(false)).or_else(|| files.first()).ok_or_else(|| anyhow!("version has no files"))?;
    if f["hashes"]["sha512"].as_str().is_some_and(|h| h.eq_ignore_ascii_case(old_hash)) { return Ok(None); }
    let name = f["filename"].as_str().ok_or_else(|| anyhow!("file has no name"))?;
    if !crate::download::safe_file_name(name) { bail!("unsafe file name {name}"); }
    let dir = old.parent().ok_or_else(|| anyhow!("no parent folder"))?;
    let disabled = old.to_string_lossy().ends_with(".disabled");
    let new = dir.join(if disabled { format!("{name}.disabled") } else { name.to_string() });
    // download beside the old file first; the old one is only removed once the new one is verified in place
    let staged = dir.join(format!("{name}.update"));
    download_file(c, f, &staged, prog)?;
    if new != old && new.exists() { let _ = fs::remove_file(&staged); bail!("{} already exists", new.display()); }
    match keep { Some(k) => if let Err(e) = k(old) { let _ = fs::remove_file(&staged); return Err(e); }, None => fs::remove_file(old)? }
    fs::rename(&staged, &new)?;
    Ok(Some(new))
}

/// Update one installed mod/pack file to the newest compatible version: downloads (verified), then replaces the old
/// file, keeping `.disabled`. Returns the new path, or None when it is already up to date.
pub fn update_file(c: &Client, file: &Path, mc: &str, loader: Option<&str>, prog: &dyn Fn(&str, u64, u64)) -> Result<Option<std::path::PathBuf>> {
    let h = file_sha512(file)?;
    let found = updates(c, std::slice::from_ref(&h), mc, loader)?;
    let Some(info) = found.get(&h) else { return Ok(None) };
    apply_version(c, file, &h, &info.id, prog, None)
}

/// Update every file in `files` (one batched lookup). One entry per file that has an update; a failure on one file
/// does not stop the rest. `Ok(Some(new_path))` = updated; `Ok(None)` = already current.
pub fn update_all(c: &Client, files: &[std::path::PathBuf], mc: &str, loader: Option<&str>, prog: &dyn Fn(&str, u64, u64)) -> Result<Vec<(std::path::PathBuf, Result<Option<std::path::PathBuf>>)>> {
    let hashes: Vec<(std::path::PathBuf, String)> = files.iter().filter_map(|p| file_sha512(p).ok().map(|h| (p.clone(), h))).collect();
    let found = updates(c, &hashes.iter().map(|(_, h)| h.clone()).collect::<Vec<_>>(), mc, loader)?;
    Ok(hashes.into_iter().filter_map(|(p, h)| {
        let info = found.get(&h)?;
        let r = apply_version(c, &p, &h, &info.id, prog, None);
        Some((p, r))
    }).collect())
}

/// A version's changelog (Markdown, may be empty).
pub fn changelog(c: &Client, version_id: &str) -> Result<String> {
    let v: Value = get_cached(c, &format!("{API}/version/{version_id}"), &[], HOURS6)?;
    Ok(v["changelog"].as_str().unwrap_or_default().to_string())
}

/// Replace `file` with version `version_id`; the old file is handed to `keep` (a snapshot) instead of deleted.
/// Ok(None) = it already is that version.
pub fn update_to(c: &Client, file: &Path, version_id: &str, prog: &dyn Fn(&str, u64, u64), keep: &mut dyn FnMut(&Path) -> Result<()>) -> Result<Option<std::path::PathBuf>> {
    let h = file_sha512(file)?;
    apply_version(c, file, &h, version_id, prog, Some(keep))
}

/// Install `version` of `project` (or the newest compatible one) into `dir`, plus required dependencies.
/// Returns the file names that were newly downloaded.
/// `prog(file, bytes_done, bytes_total)` is called while each file downloads.
pub fn install(c: &Client, project: &str, version: Option<&str>, mc: &str, loader: Option<&str>, dir: &Path, prog: &dyn Fn(&str, u64, u64)) -> Result<Vec<String>> {
    let (mut done, mut seen) = (vec![], HashSet::new());
    if let Some(id) = version {
        let v: Value = get_cached(c, &format!("{API}/version/{id}"), &[], HOURS6)?;
        seen.insert(project.to_string());
        install_version(c, &v, mc, loader, dir, &mut done, &mut seen, 0, prog)?;
        return Ok(done);
    }
    install_rec(c, project, mc, loader, dir, &mut done, &mut seen, 0, prog)?;
    Ok(done)
}

#[allow(clippy::too_many_arguments)]
fn install_rec(c: &Client, project: &str, mc: &str, loader: Option<&str>, dir: &Path, done: &mut Vec<String>, seen: &mut HashSet<String>, depth: u32, prog: &dyn Fn(&str, u64, u64)) -> Result<()> {
    if depth > 3 || !seen.insert(project.to_string()) { return Ok(()); }
    let mut q = vec![("game_versions", json!([mc]).to_string())];
    if let Some(l) = loader { q.push(("loaders", json!(loader_set(l)).to_string())); }
    let versions: Vec<Value> = serde_json::from_value(get_cached(c, &format!("{API}/project/{project}/version"), &q.iter().map(|(k, v)| (*k, v.clone())).collect::<Vec<_>>(), MIN30)?)?;
    let v = versions.first().ok_or_else(|| anyhow!("no version of {project} for Minecraft {mc}"))?;
    install_version(c, v, mc, loader, dir, done, seen, depth, prog)
}

#[allow(clippy::too_many_arguments)]
fn install_version(c: &Client, v: &Value, mc: &str, loader: Option<&str>, dir: &Path, done: &mut Vec<String>, seen: &mut HashSet<String>, depth: u32, prog: &dyn Fn(&str, u64, u64)) -> Result<()> {
    let files = v["files"].as_array().ok_or_else(|| anyhow!("version has no files"))?;
    let f = files.iter().find(|f| f["primary"].as_bool().unwrap_or(false)).or_else(|| files.first()).ok_or_else(|| anyhow!("version has no files"))?;
    let name = f["filename"].as_str().ok_or_else(|| anyhow!("file has no name"))?;
    if !crate::download::safe_file_name(name) { bail!("unsafe file name {name}"); }
    let dest = dir.join(name);
    // a file cut off by a crash or a full disk is replaced rather than kept forever
    let intact = |p: &Path| f["hashes"]["sha512"].as_str().is_none_or(|want| file_sha512(p).is_ok_and(|h| h.eq_ignore_ascii_case(want)));
    let disabled = dir.join(format!("{name}.disabled"));
    let have = (dest.exists() && intact(&dest)) || (disabled.exists() && intact(&disabled));
    if !have {
        let url = f["url"].as_str().ok_or_else(|| anyhow!("file has no url"))?;
        // stream in chunks so the UI can show progress
        let mut resp = c.get(url).send()?.error_for_status()?;
        let total = resp.content_length().or_else(|| f["size"].as_u64()).unwrap_or(0);
        let mut bytes = Vec::with_capacity(total as usize);
        let mut buf = [0u8; 32 * 1024];
        loop {
            let n = std::io::Read::read(&mut resp, &mut buf)?;
            if n == 0 { break; }
            bytes.extend_from_slice(&buf[..n]);
            prog(name, bytes.len() as u64, total);
        }
        if let Some(want) = f["hashes"]["sha512"].as_str() {
            if !hex::encode(Sha512::digest(&bytes)).eq_ignore_ascii_case(want) { bail!("hash mismatch for {name}"); }
        }
        crate::store::write_atomic(&dest, &bytes)?;
        done.push(name.to_string());
    }
    if let Some(deps) = v["dependencies"].as_array() {
        for d in deps {
            if d["dependency_type"].as_str() == Some("required") {
                if let Some(p) = d["project_id"].as_str() { install_rec(c, p, mc, loader, dir, done, seen, depth + 1, prog)?; }
            }
        }
    }
    Ok(())
}


/// Loaders whose builds run on `l`: a Paper server also runs Spigot and Bukkit plugins.
/// Loaders whose builds run on `l`: Paper takes Spigot/Bukkit plugins, Quilt runs Fabric mods.
fn loader_set(l: &str) -> Vec<&str> {
    match l { "paper" => vec!["paper", "spigot", "bukkit"], "quilt" => vec!["quilt", "fabric"], _ => vec![l] }
}

/// True when a version built for `loaders` runs on loader `l`.
pub fn loader_fits(l: &str, loaders: &[String]) -> bool {
    loader_set(l).iter().any(|x| loaders.iter().any(|y| y == x))
}

/// Search add-ons for a dedicated server: Paper -> plugins (Paper/Spigot/Bukkit), Fabric -> mods that run on servers.
pub fn search_server(c: &Client, query: &str, mc: &str, kind: &str) -> Result<Vec<Hit>> {
    let mut facets = vec![json!([format!("versions:{mc}")])];
    if kind == "paper" {
        facets.push(json!(["project_type:plugin"]));
        facets.push(json!(["categories:paper", "categories:spigot", "categories:bukkit"]));
    } else {
        facets.push(json!(["project_type:mod"]));
        facets.push(json!([format!("categories:{kind}")]));
        facets.push(json!(["server_side:required", "server_side:optional"]));
    }
    let sort = if query.is_empty() { "downloads" } else { "relevance" };
    let v: Value = get_cached(c, &format!("{API}/search"), &[("query", query.to_string()), ("facets", Value::Array(facets).to_string()), ("limit", "30".into()), ("index", sort.to_string())], MIN10)?;
    let hits = v["hits"].as_array().ok_or_else(|| anyhow!("unexpected Modrinth response"))?;
    Ok(hits.iter().map(|h| Hit {
        id: h["project_id"].as_str().unwrap_or_default().into(),
        title: h["title"].as_str().unwrap_or_default().into(),
        author: h["author"].as_str().unwrap_or_default().into(),
        desc: h["description"].as_str().unwrap_or_default().into(),
        downloads: h["downloads"].as_u64().unwrap_or(0),
        icon_url: h["icon_url"].as_str().unwrap_or_default().into(),
        mc: mc.into(),
        loaders: vec![],
    }).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quilt_runs_fabric_builds() {
        let f = vec!["fabric".to_string()];
        assert!(loader_fits("quilt", &f) && loader_fits("fabric", &f));
        assert!(!loader_fits("fabric", &["quilt".to_string()]) && !loader_fits("forge", &f));
    }
}
