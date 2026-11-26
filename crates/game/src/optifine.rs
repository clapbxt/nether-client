//! OptiFine for Nether profiles on 1.8.9 and 1.12.2 ("Nether + OptiFine"), installed the way HMCL and XMCL do it
//! (research/OptiFine install):
//! 1. the build list and the installer jar come from BMCLAPI, the mirror those launchers use for OptiFine:
//!    `<root>/optifine/<mc>` (entries: type "HD_U", patch "M5", filename) and `<root>/optifine/<mc>/<type>/<patch>`;
//! 2. OptiFine's own `optifine.Patcher <client jar> <installer> <out>` builds the library jar against this version's
//!    client jar (when the installer has no Patcher, the installer itself is the library);
//! 3. LaunchWrapper: the one OptiFine ships (`launchwrapper-2.0.jar`, or `launchwrapper-of.txt` naming
//!    `launchwrapper-of-<v>.jar`), else Mojang's `net.minecraft:launchwrapper:1.12`; main class
//!    `net.minecraft.launchwrapper.Launch` and `--tweakClass optifine.OptiFineTweaker`.
//!
//! Files live in the libraries folder like any library: `optifine:OptiFine:<mc>_<type>_<patch>` (+ `:installer`).
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{anyhow, bail, Context, Result};
use serde::Deserialize;

use crate::download::{fetch, Check};
use crate::mojang::{Artifact, LibDownloads, Library, VersionJson};
use crate::paths::Paths;

/// Versions with OptiFine profiles (newer versions get Fabric and Sodium instead).
pub const VERSIONS: [&str; 2] = ["1.8.9", "1.12.2"];
const BMCL: &str = "https://bmclapi2.bangbang93.com";
const TWEAKER: &str = "optifine.OptiFineTweaker";

pub fn supported(mc: &str) -> bool { VERSIONS.contains(&mc) }

#[derive(Deserialize)]
struct Entry {
    #[serde(default)]
    mcversion: String,
    #[serde(rename = "type", default)]
    kind: String,
    #[serde(default)]
    patch: String,
    #[serde(default)]
    filename: String,
}

fn safe(s: &str) -> bool { !s.is_empty() && s.len() <= 24 && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_') }

/// The newest stable build in a BMCLAPI list ("HD_U", "M5"); previews / pre / alpha builds are skipped.
fn newest(list: &[Entry], mc: &str) -> Option<(String, String)> {
    list.iter().rev().find(|e| e.mcversion == mc && !e.filename.starts_with("preview") && !e.patch.starts_with("pre") && !e.patch.starts_with("alpha")
        && safe(&e.kind) && safe(&e.patch)).map(|e| (e.kind.clone(), e.patch.clone()))
}

fn coord(id: &str) -> String { format!("optifine:OptiFine:{id}") }
fn lib_path(paths: &Paths, name: &str) -> PathBuf { paths.libraries().join(crate::mojang::maven_path(name)) }
/// The patched library jar of a build ("1.8.9_HD_U_M5").
pub fn jar(paths: &Paths, id: &str) -> PathBuf { lib_path(paths, &coord(id)) }
fn installer(paths: &Paths, id: &str) -> PathBuf { lib_path(paths, &format!("{}:installer", coord(id))) }

/// The newest build for `mc` already downloaded.
fn have(paths: &Paths, mc: &str) -> Option<String> {
    let mut b: Vec<String> = fs::read_dir(paths.libraries().join("optifine").join("OptiFine")).ok()?.flatten()
        .filter_map(|e| e.file_name().to_str().map(str::to_string))
        .filter(|b| b.strip_prefix(mc).is_some_and(|r| r.starts_with('_')) && installer(paths, b).exists()).collect();
    b.sort();
    b.pop()
}

fn entry(zip: &Path, name: &str) -> Option<Vec<u8>> {
    let mut z = zip::ZipArchive::new(fs::File::open(zip).ok()?).ok()?;
    let mut e = z.by_name(name).ok()?;
    let mut b = vec![];
    e.read_to_end(&mut b).ok()?;
    Some(b)
}

fn has(zip: &Path, name: &str) -> bool {
    zip::ZipArchive::new(match fs::File::open(zip) { Ok(f) => f, Err(_) => return false }).is_ok_and(|mut z| z.by_name(name).is_ok())
}

/// What one launch with OptiFine needs.
pub struct Setup { pub id: String, installer: PathBuf, launchwrapper: Library }

fn local_lib(name: &str) -> Library {
    Library {
        name: name.into(), rules: None, natives: None, url: None, sha1: None,
        // no URL: the file is made here (downloaded installer / its contents / the Patcher)
        downloads: Some(LibDownloads { artifact: Some(Artifact { path: Some(crate::mojang::maven_path(name)), sha1: String::new(), size: 0, url: String::new() }), classifiers: None }),
    }
}

/// Make sure an OptiFine installer for `mc` is here (downloading the newest stable build from BMCLAPI the first
/// time) and work out its LaunchWrapper.
pub fn prepare(c: &reqwest::blocking::Client, paths: &Paths, mc: &str, progress: &crate::Progress) -> Result<Setup> {
    if !supported(mc) { bail!("OptiFine profiles are for 1.8.9 and 1.12.2"); }
    let id = match have(paths, mc) {
        Some(id) => id,
        None => {
            progress("Finding OptiFine", 0.01);
            let list: Vec<Entry> = crate::download::get_json(c, &format!("{BMCL}/optifine/{mc}")).context("couldn't list OptiFine builds")?;
            let (kind, patch) = newest(&list, mc).ok_or_else(|| anyhow!("no OptiFine build for {mc}"))?;
            let id = format!("{mc}_{kind}_{patch}");
            let dest = installer(paths, &id);
            progress(&format!("Downloading OptiFine {}", id.replace('_', " ")), 0.02);
            fetch(c, &format!("{BMCL}/optifine/{mc}/{kind}/{patch}"), &dest, Check::None).context("couldn't download OptiFine")?;
            if !has(&dest, "optifine/OptiFineTweaker.class") { let _ = fs::remove_file(&dest); bail!("the OptiFine download is damaged; try again"); }
            id
        }
    };
    let inst = installer(paths, &id);
    // the LaunchWrapper OptiFine ships, else Mojang's 1.12
    let lw = if let Some(b) = entry(&inst, "launchwrapper-2.0.jar") {
        let name = "optifine:launchwrapper:2.0";
        write_once(&lib_path(paths, name), &b)?;
        local_lib(name)
    } else if let Some(v) = entry(&inst, "launchwrapper-of.txt").map(|b| String::from_utf8_lossy(&b).trim().to_string()).filter(|v| safe(&v.replace(['.', '-'], "_"))) {
        let name = format!("optifine:launchwrapper-of:{v}");
        let b = entry(&inst, &format!("launchwrapper-of-{v}.jar")).ok_or_else(|| anyhow!("OptiFine's LaunchWrapper is missing"))?;
        write_once(&lib_path(paths, &name), &b)?;
        local_lib(&name)
    } else {
        Library { name: "net.minecraft:launchwrapper:1.12".into(), downloads: None, rules: None, natives: None, url: Some("https://libraries.minecraft.net/".into()), sha1: None }
    };
    Ok(Setup { id, installer: inst, launchwrapper: lw })
}

fn write_once(dest: &Path, bytes: &[u8]) -> Result<()> {
    if dest.exists() { return Ok(()); }
    if let Some(d) = dest.parent() { fs::create_dir_all(d)?; }
    Ok(crate::store::write_atomic(dest, bytes)?)
}

/// The version with OptiFine: its LaunchWrapper and library on the classpath, OptiFine's tweaker.
pub fn apply(mut v: VersionJson, s: &Setup) -> Result<VersionJson> {
    let Some(args) = v.legacy_args.clone() else { bail!("OptiFine profiles are for 1.8.9 and 1.12.2") };
    v.libraries.retain(|l| !l.name.contains("launchwrapper") && !l.name.starts_with("optifine:"));
    v.libraries.push(s.launchwrapper.clone());
    v.libraries.push(local_lib(&coord(&s.id)));
    v.main_class = "net.minecraft.launchwrapper.Launch".into();
    if !args.contains(TWEAKER) { v.legacy_args = Some(format!("{args} --tweakClass {TWEAKER}")); }
    Ok(v)
}

/// Build the OptiFine library jar for this client jar (once): OptiFine's Patcher, or the installer as-is.
pub fn patch(paths: &Paths, s: &Setup, java: &Path, client_jar: &Path, progress: &crate::Progress) -> Result<()> {
    let out = jar(paths, &s.id);
    if out.exists() { return Ok(()); }
    if let Some(d) = out.parent() { fs::create_dir_all(d)?; }
    let tmp = out.with_extension("part");
    if has(&s.installer, "optifine/Patcher.class") {
        progress("Setting up OptiFine", 0.93);
        let mut cmd = Command::new(java);
        nether_common::platform::no_window(&mut cmd);
        let o = cmd.arg("-cp").arg(&s.installer).arg("optifine.Patcher").arg(client_jar).arg(&s.installer).arg(&tmp).output().context("running OptiFine's Patcher")?;
        if !o.status.success() || !has(&tmp, "optifine/OptiFineTweaker.class") {
            let _ = fs::remove_file(&tmp);
            let log = format!("{}{}", String::from_utf8_lossy(&o.stdout), String::from_utf8_lossy(&o.stderr));
            bail!("OptiFine's Patcher failed: {}", log.lines().rev().take(6).collect::<Vec<_>>().into_iter().rev().collect::<Vec<_>>().join(" / "));
        }
    } else {
        fs::copy(&s.installer, &tmp)?;
    }
    fs::rename(&tmp, &out)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn picks_newest_stable() {
        let list: Vec<Entry> = serde_json::from_str(r#"[
            {"mcversion":"1.8.9","type":"HD_U","patch":"L5","filename":"OptiFine_1.8.9_HD_U_L5.jar"},
            {"mcversion":"1.8.9","type":"HD_U","patch":"M5","filename":"OptiFine_1.8.9_HD_U_M5.jar"},
            {"mcversion":"1.8.9","type":"HD_U_M6","patch":"pre2","filename":"preview_OptiFine_1.8.9_HD_U_M6_pre2.jar"}]"#).unwrap();
        assert_eq!(newest(&list, "1.8.9"), Some(("HD_U".into(), "M5".into())));
        assert_eq!(newest(&list, "1.12.2"), None);
        assert_eq!(crate::mojang::maven_path(&coord("1.8.9_HD_U_M5")), "optifine/OptiFine/1.8.9_HD_U_M5/OptiFine-1.8.9_HD_U_M5.jar");
        assert_eq!(crate::mojang::maven_path(&format!("{}:installer", coord("1.8.9_HD_U_M5"))), "optifine/OptiFine/1.8.9_HD_U_M5/OptiFine-1.8.9_HD_U_M5-installer.jar");
    }
}
