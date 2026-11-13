//! Restore points: the whole instance (mods, config, worlds, packs, options, client settings) zipped into
//! `<instance>/.nether/restore/<kind>_<UTC stamp>.zip`, plus its launcher entry (version, loader, memory…).
//! Left out: `.nether` (restore points, update snapshots), world `backups/`, `logs/`, loader caches.
//! Restoring as a new instance never touches the original. Restoring in place first makes a "pre-restore" point,
//! builds the restored folder beside the instance and swaps it in, so a failure leaves the instance as it was.
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use anyhow::{anyhow, bail, Context, Result};

use crate::instances::InstanceData;
use crate::modpack::safe_join;
use crate::paths::Paths;
use crate::worlds::{now, split_stamp, stamp};

/// Made by the player, before a launch (automatic), or right before an in-place restore.
pub const MANUAL: &str = "manual";
pub const AUTO: &str = "auto";
pub const PRE_RESTORE: &str = "pre-restore";
/// Automatic restore points are made when the newest point is older than this.
pub const AUTO_EVERY_SECS: u64 = 7 * 86_400;

const META: &str = "nether-restore.json";
const ROOT: &str = "instance/";
/// Top-level entries of an instance folder that are never in a restore point (and survive an in-place restore).
const KEEP_OUT: [&str; 5] = [".nether", "backups", "logs", ".fabric", ".cache"];

#[derive(Clone, Debug)]
pub struct Point {
    pub file: PathBuf,
    pub kind: String,
    /// Unix seconds (UTC).
    pub created: u64,
    pub size_bytes: u64,
}

fn dir(paths: &Paths, instance: &str) -> PathBuf { paths.instance(instance).join(".nether").join("restore") }

/// The instance's restore points, newest first.
pub fn list(paths: &Paths, instance: &str) -> Vec<Point> {
    let mut v: Vec<Point> = fs::read_dir(dir(paths, instance)).map(|rd| rd.flatten().filter_map(|e| {
        let p = e.path();
        if p.extension().and_then(|x| x.to_str()) != Some("zip") { return None; }
        let (kind, created) = split_stamp(&p.file_stem()?.to_string_lossy())?;
        Some(Point { kind, created, size_bytes: e.metadata().ok()?.len(), file: p })
    }).collect()).unwrap_or_default();
    v.sort_by(|a, b| b.created.cmp(&a.created).then(b.file.cmp(&a.file)));
    v
}

/// An automatic point is due (the option is on and the newest point is a week old, or there is none).
pub fn due(paths: &Paths, d: &InstanceData) -> bool {
    d.backup_auto && paths.instance(&d.name).is_dir() && list(paths, &d.name).first().is_none_or(|p| now().saturating_sub(p.created) >= AUTO_EVERY_SECS)
}

/// Every file to back up: (path, name in the zip under `instance/`, size).
fn files(root: &Path) -> Vec<(PathBuf, String, u64)> {
    fn walk(root: &Path, dir: &Path, out: &mut Vec<(PathBuf, String, u64)>) {
        let Ok(rd) = fs::read_dir(dir) else { return };
        for e in rd.flatten() {
            let p = e.path();
            let Ok(t) = e.file_type() else { continue };
            let Ok(rel) = p.strip_prefix(root) else { continue };
            let rel = rel.to_string_lossy().replace('\\', "/");
            if dir == root && KEEP_OUT.iter().any(|k| rel.eq_ignore_ascii_case(k)) { continue; }
            if t.is_symlink() { continue; }
            if t.is_dir() { walk(root, &p, out); continue; }
            let name = e.file_name().to_string_lossy().to_lowercase();
            if name == "session.lock" || name.ends_with(".part") || name.ends_with(".tmp") { continue; }
            out.push((p.clone(), rel, e.metadata().map(|m| m.len()).unwrap_or(0)));
        }
    }
    let mut v = vec![];
    walk(root, root, &mut v);
    v
}

/// Already-compressed files are stored, the rest deflated (jars and region files barely shrink).
fn stored(rel: &str) -> bool {
    let ext = rel.rsplit('.').next().unwrap_or_default().to_ascii_lowercase();
    matches!(ext.as_str(), "jar" | "zip" | "png" | "jpg" | "jpeg" | "ogg" | "mca" | "mcr" | "gz" | "dat" | "mrpack" | "webp")
}

/// Zip the instance into a new restore point of `kind`; afterwards keep only the newest `keep` points of that
/// kind (0 = keep all; manual points are never pruned). `progress` gets 0..1.
pub fn create(paths: &Paths, d: &InstanceData, kind: &str, keep: usize, progress: &dyn Fn(f32)) -> Result<PathBuf> {
    let root = paths.instance(&d.name);
    if !root.is_dir() { bail!("{} has no folder yet", d.name); }
    let out_dir = dir(paths, &d.name);
    fs::create_dir_all(&out_dir)?;
    let mut t = now();
    while out_dir.join(format!("{kind}_{}.zip", stamp(t))).exists() { t += 1; }
    let out = out_dir.join(format!("{kind}_{}.zip", stamp(t)));
    let part = out.with_extension("zip.part");
    let list = files(&root);
    let total = list.iter().map(|f| f.2).sum::<u64>().max(1);
    let res = (|| -> Result<()> {
        let mut zip = zip::ZipWriter::new(fs::File::create(&part)?);
        let opt = zip::write::SimpleFileOptions::default();
        zip.start_file(META, opt.compression_method(zip::CompressionMethod::Deflated))?;
        zip.write_all(&serde_json::to_vec_pretty(d)?)?;
        let (mut done, mut shown) = (0u64, -1i32);
        for (p, rel, size) in &list {
            let o = opt.compression_method(if stored(rel) { zip::CompressionMethod::Stored } else { zip::CompressionMethod::Deflated }).large_file(*size >= u32::MAX as u64);
            zip.start_file(format!("{ROOT}{rel}"), o)?;
            // a file the game holds open (it may be running) is skipped rather than failing the whole point
            match fs::File::open(p) {
                Ok(mut f) => { std::io::copy(&mut f, &mut zip).with_context(|| format!("reading {}", p.display()))?; }
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => return Err(anyhow!("reading {}: {e}", p.display())),
            }
            done += size;
            let pct = (done * 100 / total) as i32;
            if pct != shown { shown = pct; progress(done as f32 / total as f32); }
        }
        zip.finish()?;
        Ok(())
    })();
    if let Err(e) = res { let _ = fs::remove_file(&part); return Err(e); }
    fs::rename(&part, &out)?;
    if keep > 0 && kind != MANUAL {
        for old in list_kind(paths, &d.name, kind).into_iter().skip(keep) { let _ = fs::remove_file(old.file); }
    }
    Ok(out)
}

fn list_kind(paths: &Paths, instance: &str, kind: &str) -> Vec<Point> { list(paths, instance).into_iter().filter(|p| p.kind == kind).collect() }

/// The launcher entry saved in a restore point.
pub fn meta(file: &Path) -> Result<InstanceData> {
    let mut zip = zip::ZipArchive::new(fs::File::open(file).with_context(|| format!("opening {}", file.display()))?)?;
    let mut s = String::new();
    zip.by_name(META).map_err(|_| anyhow!("not a restore point"))?.read_to_string(&mut s)?;
    Ok(serde_json::from_str(&s)?)
}

/// Unpack the point's instance files into `dest` (created).
fn extract(file: &Path, dest: &Path, progress: &dyn Fn(f32)) -> Result<()> {
    let mut zip = zip::ZipArchive::new(fs::File::open(file).with_context(|| format!("opening {}", file.display()))?)?;
    let total = (0..zip.len()).filter_map(|i| zip.by_index_raw(i).ok().map(|f| f.size())).sum::<u64>().max(1);
    fs::create_dir_all(dest)?;
    let (mut done, mut shown) = (0u64, -1i32);
    for i in 0..zip.len() {
        let mut f = zip.by_index(i)?;
        let name = f.name().replace('\\', "/");
        let Some(rel) = name.strip_prefix(ROOT) else { continue };
        if rel.is_empty() { continue; }
        let out = safe_join(dest, rel)?;
        if f.is_dir() { fs::create_dir_all(&out)?; continue; }
        if let Some(p) = out.parent() { fs::create_dir_all(p)?; }
        std::io::copy(&mut f, &mut fs::File::create(&out)?)?;
        done += f.size();
        let pct = (done * 100 / total) as i32;
        if pct != shown { shown = pct; progress(done as f32 / total as f32); }
    }
    Ok(())
}

/// A copy of the point as a new instance called `name` (must be free). Returns its launcher entry
/// (the saved one, renamed, play stats reset).
pub fn restore_as_new(paths: &Paths, file: &Path, name: &str, progress: &dyn Fn(f32)) -> Result<InstanceData> {
    let mut d = meta(file)?;
    let dest = paths.instance(name);
    if dest.exists() { bail!("an instance called {name} already exists"); }
    if let Err(e) = extract(file, &dest, progress) { let _ = fs::remove_dir_all(&dest); return Err(e); }
    d.name = name.to_string();
    d.played_secs = 0;
    d.hours = 0;
    d.last_ts = 0;
    d.last = "Never".into();
    d.crashed = false;
    Ok(d)
}

/// Put the instance back to the point. A "pre-restore" point of the current state is made first (the 3 newest
/// are kept). Returns the saved launcher entry under the instance's current name, play stats and group kept.
pub fn restore_in_place(paths: &Paths, cur: &InstanceData, file: &Path, progress: &dyn Fn(f32)) -> Result<InstanceData> {
    let saved = meta(file)?;
    let root = paths.instance(&cur.name);
    let parent = root.parent().ok_or_else(|| anyhow!("bad instance folder"))?;
    let base = root.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    create(paths, cur, PRE_RESTORE, 0, &|f| progress(f * 0.45))?;
    let staged = parent.join(format!("{base}.restoring"));
    let old = parent.join(format!("{base}.replaced"));
    for p in [&staged, &old] { if p.exists() { fs::remove_dir_all(p).with_context(|| format!("clearing {}", p.display()))?; } }
    if let Err(e) = extract(file, &staged, &|f| progress(0.45 + f * 0.5)) { let _ = fs::remove_dir_all(&staged); return Err(e); }
    // what restore points leave out stays with the instance
    let mut moved = vec![];
    let back = |moved: &[String]| for k in moved { let _ = fs::rename(staged.join(k), root.join(k)); };
    let entries: Vec<fs::DirEntry> = fs::read_dir(&root)?.flatten().collect();
    for e in entries {
        let n = e.file_name().to_string_lossy().to_string();
        if !KEEP_OUT.iter().any(|k| n.eq_ignore_ascii_case(k)) { continue; }
        let _ = fs::remove_dir_all(staged.join(&n));
        if let Err(err) = fs::rename(e.path(), staged.join(&n)) {
            back(&moved);
            let _ = fs::remove_dir_all(&staged);
            bail!("{n} is in use ({err}); close the game and any open files first");
        }
        moved.push(n);
    }
    if let Err(err) = fs::rename(&root, &old) {
        back(&moved);
        let _ = fs::remove_dir_all(&staged);
        bail!("the instance folder is in use ({err}); close the game and any open files first");
    }
    if let Err(err) = fs::rename(&staged, &root) {
        let _ = fs::rename(&old, &root);
        back(&moved);
        let _ = fs::remove_dir_all(&staged);
        bail!("couldn't put the restored files in place: {err}");
    }
    let _ = fs::remove_dir_all(&old);
    // only now (the point restored from may be one of them): keep the 3 newest pre-restore points
    for p in list_kind(paths, &cur.name, PRE_RESTORE).into_iter().skip(3) { let _ = fs::remove_file(p.file); }
    progress(1.0);
    Ok(InstanceData {
        name: cur.name.clone(), played_secs: cur.played_secs, hours: cur.hours, last_ts: cur.last_ts, last: cur.last.clone(),
        group: cur.group.clone(), notes: cur.notes.clone(), backup_auto: cur.backup_auto, backup_keep: cur.backup_keep, crashed: false,
        ..saved
    })
}

/// Delete one restore point (only a zip in this instance's restore folder) to the Recycle Bin.
pub fn delete(paths: &Paths, instance: &str, file: &Path) -> Result<()> {
    if file.parent() != Some(dir(paths, instance).as_path()) || file.extension().and_then(|x| x.to_str()) != Some("zip") {
        bail!("not a restore point of this instance");
    }
    crate::trash::recycle(file)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::modpack::tmpdir;

    fn tree(root: &Path) -> Vec<(String, Vec<u8>)> {
        let mut v: Vec<(String, Vec<u8>)> = files(root).into_iter().map(|(p, rel, _)| (rel, fs::read(p).unwrap())).collect();
        v.sort();
        v
    }

    #[test]
    fn backup_restore_new_and_in_place() {
        let root = tmpdir("restore");
        let paths = Paths { root: root.clone() };
        let d = InstanceData { name: "Survival".into(), version: "1.21.11".into(), loader: "Fabric".into(), ram_gb: 6, group: "PvP".into(), ..Default::default() };
        let inst = paths.instance("Survival");
        for (rel, body) in [("mods/sodium.jar", "jar"), ("config/sodium.json", "{\"a\":1}"), ("saves/W/level.dat", "lvl"), ("options.txt", "fov:90")] {
            fs::create_dir_all(inst.join(rel).parent().unwrap()).unwrap();
            fs::write(inst.join(rel), body).unwrap();
        }
        for skip in ["logs/latest.log", "backups/W_x.zip", ".nether/snapshots/1/x", "saves/W/session.lock"] {
            fs::create_dir_all(inst.join(skip).parent().unwrap()).unwrap();
            fs::write(inst.join(skip), "x").unwrap();
        }
        let before = tree(&inst);
        let p = create(&paths, &d, MANUAL, 0, &|_| {}).unwrap();
        assert_eq!(list(&paths, "Survival").len(), 1);
        assert!(!due(&paths, &InstanceData { backup_auto: true, ..d.clone() }));
        assert_eq!(meta(&p).unwrap().loader, "Fabric");

        // as new: identical files, entry renamed, the original untouched
        let n = restore_as_new(&paths, &p, "Survival (restored)", &|_| {}).unwrap();
        assert_eq!((n.name.as_str(), n.version.as_str(), n.ram_gb), ("Survival (restored)", "1.21.11", 6));
        assert_eq!(tree(&paths.instance("Survival (restored)")), before);
        assert!(restore_as_new(&paths, &p, "Survival (restored)", &|_| {}).is_err());

        // in place: changes undone, logs/backups/.nether kept, a pre-restore point made
        fs::write(inst.join("mods/sodium.jar"), "new").unwrap();
        fs::write(inst.join("mods/extra.jar"), "x").unwrap();
        fs::remove_file(inst.join("options.txt")).unwrap();
        let cur = InstanceData { played_secs: 99, ram_gb: 2, ..d.clone() };
        let r = restore_in_place(&paths, &cur, &p, &|_| {}).unwrap();
        assert_eq!((r.ram_gb, r.played_secs), (6, 99));
        assert_eq!(tree(&inst), before);
        assert!(inst.join("logs/latest.log").is_file() && inst.join("backups/W_x.zip").is_file());
        let pts = list(&paths, "Survival");
        assert_eq!(pts.iter().filter(|p| p.kind == PRE_RESTORE).count(), 1);
        assert!(p.is_file());
        assert!(!root.join("instances").read_dir().unwrap().flatten().any(|e| e.file_name().to_string_lossy().contains(".re")));

        // deleting is confined to the restore folder
        assert!(delete(&paths, "Survival", &inst.join("options.txt")).is_err());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn auto_points_are_pruned() {
        let root = tmpdir("restore-prune");
        let paths = Paths { root: root.clone() };
        let d = InstanceData { name: "A".into(), backup_auto: true, ..Default::default() };
        fs::create_dir_all(paths.instance("A")).unwrap();
        fs::write(paths.instance("A").join("options.txt"), "x").unwrap();
        assert!(due(&paths, &d));
        create(&paths, &d, MANUAL, 0, &|_| {}).unwrap();
        for _ in 0..4 { create(&paths, &d, AUTO, 2, &|_| {}).unwrap(); }
        let pts = list(&paths, "A");
        assert_eq!(pts.iter().filter(|p| p.kind == AUTO).count(), 2);
        assert_eq!(pts.iter().filter(|p| p.kind == MANUAL).count(), 1);
        let _ = fs::remove_dir_all(root);
    }
}
