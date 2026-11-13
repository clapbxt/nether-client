//! Mod-update snapshots: before an update the old files are moved (not copied) into
//! `<instance>/.nether/snapshots/<id>/<kind dir>/`, the folder listing and (for mods) `config/` are recorded, so
//! one restore puts the folder and the configs back exactly. Plain folders + `snapshot.json`; the last 5 are kept.
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};

use crate::paths::Paths;

const KEEP: usize = 5;

/// One mod (or pack) that changed: shown in History.
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct Change { pub name: String, pub from: String, pub to: String }

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct Snap {
    pub id: String,
    /// unix seconds
    pub ts: u64,
    /// "Updated 6 mods"
    pub reason: String,
    /// content kind: 0 mods, 1 resource packs, 2 shader packs
    pub kind: i32,
    /// file names in the content folder when the snapshot was taken
    pub files: Vec<String>,
    /// of those, the ones moved into the snapshot (replaced by the update)
    pub moved: Vec<String>,
    /// `config/` was copied
    pub config: bool,
    pub changes: Vec<Change>,
}

/// What a restore did.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Restored { pub files: usize, pub removed: usize, pub config: bool }

fn root(paths: &Paths, instance: &str) -> PathBuf { paths.instance(instance).join(".nether").join("snapshots") }
fn kind_dir(kind: i32) -> &'static str { match kind { 1 => "resourcepacks", 2 => "shaderpacks", _ => "mods" } }
/// Order of snapshots taken in the same second ("<ts>" = 1, "<ts>-N" = N).
fn seq(id: &str) -> u32 { id.split_once('-').and_then(|(_, n)| n.parse().ok()).unwrap_or(1) }
fn now() -> u64 { std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) }

fn listing(dir: &Path) -> Vec<String> {
    let mut v: Vec<String> = fs::read_dir(dir).map(|r| r.flatten().filter(|e| e.path().is_file()).filter_map(|e| e.file_name().to_str().map(String::from)).collect()).unwrap_or_default();
    v.sort();
    v
}

fn copy_dir(from: &Path, to: &Path) -> Result<()> {
    fs::create_dir_all(to)?;
    for e in fs::read_dir(from)?.flatten() {
        let (src, dst) = (e.path(), to.join(e.file_name()));
        if e.file_type()?.is_dir() { copy_dir(&src, &dst)?; } else { fs::copy(&src, &dst).with_context(|| format!("copying {}", src.display()))?; }
    }
    Ok(())
}

fn save(paths: &Paths, instance: &str, s: &Snap) -> Result<()> { crate::store::write_json(&root(paths, instance).join(&s.id).join("snapshot.json"), s) }

/// Start a snapshot of content kind `kind` (records the folder listing, copies `config/` for mods).
pub fn create(paths: &Paths, instance: &str, kind: i32, reason: &str) -> Result<Snap> {
    let ts = now();
    let base = root(paths, instance);
    // ids never repeat (a pruned id is not reused): "<ts>" then "<ts>-2", "<ts>-3", ...
    let n = fs::read_dir(&base).map(|r| r.flatten().filter_map(|e| e.file_name().to_str().map(String::from)).filter(|x| x.split('-').next() == Some(ts.to_string().as_str())).map(|x| seq(&x)).max().unwrap_or(0)).unwrap_or(0);
    let id = if n == 0 { ts.to_string() } else { format!("{ts}-{}", n + 1) };
    let dir = base.join(&id);
    fs::create_dir_all(dir.join(kind_dir(kind)))?;
    let inst = paths.instance(instance);
    let cfg = inst.join("config");
    let config = kind == 0 && cfg.is_dir();
    if config { copy_dir(&cfg, &dir.join("config"))?; }
    let s = Snap { id, ts, reason: reason.into(), kind, files: listing(&inst.join(kind_dir(kind))), moved: vec![], config, changes: vec![] };
    save(paths, instance, &s)?;
    Ok(s)
}

/// Move `file` (inside the content folder) into the snapshot instead of deleting it.
pub fn stash(paths: &Paths, instance: &str, s: &mut Snap, file: &Path) -> Result<()> {
    let name = file.file_name().and_then(|n| n.to_str()).context("bad file name")?.to_string();
    fs::rename(file, root(paths, instance).join(&s.id).join(kind_dir(s.kind)).join(&name))?;
    s.moved.push(name);
    save(paths, instance, s)
}

/// Record what changed (after the update ran) and keep the newest 5. A snapshot with no changes is removed again.
pub fn finish(paths: &Paths, instance: &str, s: &mut Snap, changes: Vec<Change>) -> Result<()> {
    if changes.is_empty() && s.moved.is_empty() { let _ = fs::remove_dir_all(root(paths, instance).join(&s.id)); return Ok(()); }
    s.changes = changes;
    save(paths, instance, s)?;
    prune(paths, instance);
    Ok(())
}

/// Snapshots of an instance, newest first.
pub fn list(paths: &Paths, instance: &str) -> Vec<Snap> {
    let mut v: Vec<Snap> = fs::read_dir(root(paths, instance)).map(|r| r.flatten().filter_map(|e| crate::store::peek_json::<Snap>(&e.path().join("snapshot.json"))).collect()).unwrap_or_default();
    v.sort_by(|a, b| b.ts.cmp(&a.ts).then(seq(&b.id).cmp(&seq(&a.id))));
    v
}

fn prune(paths: &Paths, instance: &str) {
    for s in list(paths, instance).into_iter().skip(KEEP) { let _ = fs::remove_dir_all(root(paths, instance).join(&s.id)); }
}

fn base_name(n: &str) -> &str { n.strip_suffix(".disabled").unwrap_or(n) }

/// Replaced files go to the Recycle Bin (tests delete them instead).
fn discard(p: &Path) -> Result<()> {
    if cfg!(test) { if p.is_dir() { fs::remove_dir_all(p)?; } else { fs::remove_file(p)?; } return Ok(()); }
    crate::trash::recycle(p)
}

/// Put snapshot `id` back: files added since go to the Recycle Bin, moved files return, files that were only
/// enabled/disabled since get their old state, `config/` is replaced by the copy. The snapshot is removed after.
pub fn restore(paths: &Paths, instance: &str, id: &str) -> Result<Restored> {
    let Some(s) = list(paths, instance).into_iter().find(|s| s.id == id) else { bail!("that snapshot no longer exists") };
    let sdir = root(paths, instance).join(&s.id);
    let inst = paths.instance(instance);
    let dir = inst.join(kind_dir(s.kind));
    fs::create_dir_all(&dir)?;
    let mut out = Restored::default();
    let want: std::collections::HashSet<&str> = s.files.iter().map(String::as_str).collect();
    // newer files out (or back to their old enabled state)
    for name in listing(&dir) {
        if want.contains(name.as_str()) { continue; }
        let same = s.files.iter().find(|w| base_name(w) == base_name(&name) && !s.moved.contains(w));
        match same {
            Some(w) => { fs::rename(dir.join(&name), dir.join(w))?; }
            None => { discard(&dir.join(&name))?; out.removed += 1; }
        }
    }
    // the old files in
    for name in &s.moved {
        let src = sdir.join(kind_dir(s.kind)).join(name);
        if src.exists() { fs::rename(&src, dir.join(name))?; out.files += 1; }
    }
    if s.config {
        let cfg = inst.join("config");
        let tmp = inst.join(format!("config.restore-{}", s.id));
        let _ = fs::remove_dir_all(&tmp);
        copy_dir(&sdir.join("config"), &tmp)?;
        if cfg.exists() { discard(&cfg)?; }
        fs::rename(&tmp, &cfg)?;
        out.config = true;
    }
    fs::remove_dir_all(&sdir)?;
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tree(d: &Path) -> Vec<(String, Vec<u8>)> {
        let mut v = vec![];
        fn walk(base: &Path, d: &Path, v: &mut Vec<(String, Vec<u8>)>) {
            for e in fs::read_dir(d).unwrap().flatten() {
                let p = e.path();
                if p.is_dir() { walk(base, &p, v) } else { v.push((p.strip_prefix(base).unwrap().to_string_lossy().replace('\\', "/"), fs::read(&p).unwrap())) }
            }
        }
        walk(d, d, &mut v);
        v.sort();
        v
    }

    #[test]
    fn update_then_restore_is_identical() {
        let rootd = crate::modpack::tmpdir("snap");
        let paths = Paths { root: rootd.clone() };
        let inst = paths.instance("t");
        let mods = inst.join("mods");
        fs::create_dir_all(&mods).unwrap();
        fs::create_dir_all(inst.join("config/sub")).unwrap();
        fs::write(mods.join("a-1.0.jar"), b"old a").unwrap();
        fs::write(mods.join("b-1.0.jar"), b"b").unwrap();
        fs::write(mods.join("c.jar.disabled"), b"c").unwrap();
        fs::write(inst.join("config/a.toml"), b"x = 1").unwrap();
        fs::write(inst.join("config/sub/b.json"), b"{}").unwrap();
        let (before_mods, before_cfg) = (tree(&mods), tree(&inst.join("config")));

        // "update" a: old moved into the snapshot, new file in; config changed; b toggled off; a new jar added
        let mut s = create(&paths, "t", 0, "Updated 1 mod").unwrap();
        stash(&paths, "t", &mut s, &mods.join("a-1.0.jar")).unwrap();
        fs::write(mods.join("a-2.0.jar"), b"new a").unwrap();
        finish(&paths, "t", &mut s, vec![Change { name: "A".into(), from: "1.0".into(), to: "2.0".into() }]).unwrap();
        fs::write(inst.join("config/a.toml"), b"x = 2").unwrap();
        fs::write(inst.join("config/new.cfg"), b"n").unwrap();
        fs::rename(mods.join("b-1.0.jar"), mods.join("b-1.0.jar.disabled")).unwrap();
        assert_eq!(list(&paths, "t").len(), 1);
        assert_eq!(list(&paths, "t")[0].changes[0].to, "2.0");

        let r = restore(&paths, "t", &s.id).unwrap();
        assert_eq!((r.files, r.removed, r.config), (1, 1, true));
        assert_eq!(tree(&mods), before_mods);
        assert_eq!(tree(&inst.join("config")), before_cfg);
        assert!(list(&paths, "t").is_empty());
        let _ = fs::remove_dir_all(rootd);
    }

    #[test]
    fn keeps_five_and_drops_empty() {
        let rootd = crate::modpack::tmpdir("snap5");
        let paths = Paths { root: rootd.clone() };
        fs::create_dir_all(paths.instance("t").join("mods")).unwrap();
        let mut s = create(&paths, "t", 0, "nothing").unwrap();
        finish(&paths, "t", &mut s, vec![]).unwrap();
        assert!(list(&paths, "t").is_empty());
        for _ in 0..7 { let mut s = create(&paths, "t", 0, "u").unwrap(); finish(&paths, "t", &mut s, vec![Change::default()]).unwrap(); }
        assert_eq!(list(&paths, "t").len(), 5);
        let _ = fs::remove_dir_all(rootd);
    }
}
