//! Worlds of an instance (read from `level.dat`) and zip backups under `<instance>/backups/`.
//! Backups only ever add files; restoring never overwrites an existing world; only `delete_backup` removes
//! anything, and only a zip inside the instance's own backups folder.
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

use anyhow::{anyhow, bail, Context, Result};

use crate::modpack::{extract_prefix, safe_join};
use crate::paths::Paths;

/// One world in `saves/`.
#[derive(Clone, Debug)]
pub struct World {
    /// Folder name inside `saves/` (use this for the other functions).
    pub folder: String,
    pub path: PathBuf,
    /// Display name from level.dat (`LevelName`), the folder name when level.dat is unreadable.
    pub name: String,
    /// Unix time in milliseconds of the last time it was played (0 = unknown).
    pub last_played: u64,
    /// "Survival", "Creative", "Adventure", "Spectator" or "Unknown".
    pub game_mode: String,
    pub hardcore: bool,
    /// Minecraft version that last saved it ("" when unknown).
    pub version: String,
    pub size_bytes: u64,
    /// `icon.png` if the world has one.
    pub icon: Option<PathBuf>,
    /// False when level.dat could not be read (corrupt or missing fields).
    pub readable: bool,
}

/// A world backup zip.
#[derive(Clone, Debug)]
pub struct Backup {
    pub file: PathBuf,
    /// World folder name it was made from.
    pub world: String,
    /// Unix seconds (UTC) the backup was made.
    pub created: u64,
    pub size_bytes: u64,
}

// ---- minimal NBT ----

#[derive(Debug, Clone)]
enum Nbt {
    Byte(i8),
    Short(i16),
    Int(i32),
    Long(i64),
    Str(String),
    Compound(Vec<(String, Nbt)>),
    /// lists of compounds (e.g. servers.dat); other lists are skipped
    List(Vec<Nbt>),
    /// floats, doubles, other lists and arrays are skipped
    Other,
}

impl Nbt {
    fn get(&self, key: &str) -> Option<&Nbt> {
        match self { Nbt::Compound(v) => v.iter().find(|(k, _)| k == key).map(|(_, v)| v), _ => None }
    }
    fn int(&self) -> Option<i64> {
        match self { Nbt::Byte(v) => Some(*v as i64), Nbt::Short(v) => Some(*v as i64), Nbt::Int(v) => Some(*v as i64), Nbt::Long(v) => Some(*v), _ => None }
    }
    fn str(&self) -> Option<&str> { if let Nbt::Str(s) = self { Some(s) } else { None } }
}

struct Rd<'a>(&'a [u8]);

impl<'a> Rd<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        if self.0.len() < n { bail!("truncated NBT"); }
        let (a, b) = self.0.split_at(n);
        self.0 = b;
        Ok(a)
    }
    fn u16(&mut self) -> Result<u16> { Ok(u16::from_be_bytes(self.take(2)?.try_into()?)) }
    fn i32(&mut self) -> Result<i32> { Ok(i32::from_be_bytes(self.take(4)?.try_into()?)) }
    fn i64(&mut self) -> Result<i64> { Ok(i64::from_be_bytes(self.take(8)?.try_into()?)) }
    fn string(&mut self) -> Result<String> { let n = self.u16()? as usize; Ok(String::from_utf8_lossy(self.take(n)?).into_owned()) }
    fn len(&mut self) -> Result<usize> { let n = self.i32()?; if n < 0 { bail!("negative NBT length"); } Ok(n as usize) }

    fn payload(&mut self, ty: u8, depth: u32) -> Result<Nbt> {
        if depth > 64 { bail!("NBT too deep"); }
        Ok(match ty {
            1 => Nbt::Byte(self.take(1)?[0] as i8),
            2 => Nbt::Short(self.u16()? as i16),
            3 => Nbt::Int(self.i32()?),
            4 => Nbt::Long(self.i64()?),
            5 => { self.take(4)?; Nbt::Other }
            6 => { self.take(8)?; Nbt::Other }
            7 => { let n = self.len()?; self.take(n)?; Nbt::Other }
            8 => Nbt::Str(self.string()?),
            9 => {
                let t = self.take(1)?[0];
                let n = self.len()?;
                if t == 0 && n > 0 { bail!("bad NBT list"); }
                let mut v = vec![];
                for _ in 0..n { let x = self.payload(t, depth + 1)?; if t == 10 { v.push(x); } }
                if t == 10 { Nbt::List(v) } else { Nbt::Other }
            }
            10 => {
                let mut v = vec![];
                loop {
                    let t = self.take(1)?[0];
                    if t == 0 { break; }
                    let k = self.string()?;
                    v.push((k, self.payload(t, depth + 1)?));
                }
                Nbt::Compound(v)
            }
            11 => { let n = self.len()?; self.take(n.checked_mul(4).ok_or_else(|| anyhow!("bad NBT"))?)?; Nbt::Other }
            12 => { let n = self.len()?; self.take(n.checked_mul(8).ok_or_else(|| anyhow!("bad NBT"))?)?; Nbt::Other }
            t => bail!("unknown NBT tag {t}"),
        })
    }
}

/// Parse a (gzip-compressed or raw) NBT file's root compound.
fn parse_nbt(raw: &[u8]) -> Result<Nbt> {
    let data = if raw.starts_with(&[0x1f, 0x8b]) {
        let mut out = vec![];
        flate2::read::GzDecoder::new(raw).take(64 << 20).read_to_end(&mut out).context("level.dat is not valid gzip")?;
        out
    } else { raw.to_vec() };
    let mut r = Rd(&data);
    if r.take(1)?[0] != 10 { bail!("NBT root is not a compound"); }
    r.string()?;
    r.payload(10, 0)
}

fn mode_name(m: i64) -> &'static str {
    match m { 0 => "Survival", 1 => "Creative", 2 => "Adventure", 3 => "Spectator", _ => "Unknown" }
}

/// Total size of every file under `p`.
pub fn dir_size(p: &Path) -> u64 {
    fs::read_dir(p).map(|rd| rd.flatten().map(|e| match e.file_type() {
        Ok(t) if t.is_dir() => dir_size(&e.path()),
        Ok(t) if t.is_file() => e.metadata().map(|m| m.len()).unwrap_or(0),
        _ => 0,
    }).sum()).unwrap_or(0)
}

fn saves_dir(paths: &Paths, instance: &str) -> PathBuf { paths.instance(instance).join("saves") }
fn backups_dir(paths: &Paths, instance: &str) -> PathBuf { paths.instance(instance).join("backups") }

fn read_world(path: &Path) -> Option<World> {
    let folder = path.file_name()?.to_string_lossy().to_string();
    let dat = path.join("level.dat");
    if !dat.is_file() { return None; }
    let mut w = World {
        name: folder.clone(), folder, path: path.to_path_buf(), last_played: 0, game_mode: "Unknown".into(), hardcore: false, version: String::new(),
        size_bytes: dir_size(path), icon: Some(path.join("icon.png")).filter(|p| p.is_file()), readable: false,
    };
    if let Some(d) = fs::read(&dat).ok().and_then(|b| parse_nbt(&b).ok()).and_then(|n| n.get("Data").cloned()) {
        w.readable = true;
        if let Some(s) = d.get("LevelName").and_then(Nbt::str) { w.name = s.to_string(); }
        w.last_played = d.get("LastPlayed").and_then(Nbt::int).unwrap_or(0).max(0) as u64;
        w.game_mode = mode_name(d.get("GameType").and_then(Nbt::int).unwrap_or(-1)).into();
        w.hardcore = d.get("hardcore").and_then(Nbt::int) == Some(1);
        w.version = d.get("Version").and_then(|v| v.get("Name")).and_then(Nbt::str).unwrap_or_default().to_string();
    }
    Some(w)
}

/// The instance's multiplayer server list (`servers.dat`): (name, address, base64 PNG icon or ""), in the game's order.
pub fn servers(paths: &Paths, instance: &str) -> Vec<(String, String, String)> {
    let Ok(raw) = fs::read(paths.instance(instance).join("servers.dat")) else { return vec![] };
    let Ok(root) = parse_nbt(&raw) else { return vec![] };
    let Some(Nbt::List(list)) = root.get("servers") else { return vec![] };
    list.iter().filter_map(|s| {
        let ip = s.get("ip")?.str()?.trim().to_string();
        if ip.is_empty() { return None; }
        let icon = s.get("icon").and_then(Nbt::str).unwrap_or("").to_string();
        Some((s.get("name").and_then(Nbt::str).unwrap_or(&ip).to_string(), ip, icon))
    }).collect()
}

/// The instance's worlds, most recently played first.
pub fn list(paths: &Paths, instance: &str) -> Vec<World> {
    let mut v: Vec<World> = fs::read_dir(saves_dir(paths, instance)).map(|rd| rd.flatten().filter(|e| e.path().is_dir()).filter_map(|e| read_world(&e.path())).collect()).unwrap_or_default();
    v.sort_by(|a, b| b.last_played.cmp(&a.last_played).then(a.folder.cmp(&b.folder)));
    v
}

// ---- time (UTC, no chrono) ----

fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719468;
    let era = z.div_euclid(146097);
    let doe = z.rem_euclid(146097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (yoe + era * 400 + (m <= 2) as i64, m, d)
}

fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y.rem_euclid(400);
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) as i64 + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

pub(crate) fn stamp(unix: u64) -> String {
    let (y, m, d) = civil_from_days((unix / 86400) as i64);
    let s = unix % 86400;
    format!("{y:04}-{m:02}-{d:02}_{:02}-{:02}-{:02}", s / 3600, s / 60 % 60, s % 60)
}

/// "name_YYYY-MM-DD_HH-MM-SS" -> (name, unix seconds)
pub(crate) fn split_stamp(stem: &str) -> Option<(String, u64)> {
    let (name, ts) = stem.char_indices().rev().nth(18).map(|(i, _)| stem.split_at(i))?;
    let name = name.strip_suffix('_')?;
    let (date, time) = ts.split_once('_')?;
    let n = |s: &str| s.parse::<i64>().ok();
    let d: Vec<i64> = date.split('-').filter_map(n).collect();
    let t: Vec<i64> = time.split('-').filter_map(n).collect();
    if d.len() != 3 || t.len() != 3 { return None; }
    let days = days_from_civil(d[0], d[1] as u32, d[2] as u32);
    Some((name.to_string(), (days * 86400 + t[0] * 3600 + t[1] * 60 + t[2]).max(0) as u64))
}

pub(crate) fn now() -> u64 { std::time::SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) }

// ---- backups ----

fn add_dir(zip: &mut zip::ZipWriter<fs::File>, root: &Path, dir: &Path, opt: zip::write::SimpleFileOptions) -> Result<()> {
    for e in fs::read_dir(dir)? {
        let e = e?;
        let p = e.path();
        let t = e.file_type()?;
        if t.is_symlink() { continue; }
        let rel = p.strip_prefix(root.parent().unwrap_or(root))?.to_string_lossy().replace('\\', "/");
        if t.is_dir() {
            zip.add_directory(format!("{rel}/"), opt)?;
            add_dir(zip, root, &p, opt)?;
        } else if e.file_name() != "session.lock" {
            zip.start_file(rel, opt)?;
            let mut f = fs::File::open(&p).with_context(|| format!("reading {}", p.display()))?;
            std::io::copy(&mut f, zip)?;
        }
    }
    Ok(())
}

/// Zip `saves/<folder>` into `<instance>/backups/<folder>_<UTC timestamp>.zip`. Returns the zip path.
pub fn backup_world(paths: &Paths, instance: &str, folder: &str) -> Result<PathBuf> {
    let saves = saves_dir(paths, instance);
    let src = safe_join(&saves, folder)?;
    if !src.join("level.dat").is_file() { bail!("{folder} is not a world"); }
    let dir = backups_dir(paths, instance);
    fs::create_dir_all(&dir)?;
    let mut t = now();
    while dir.join(format!("{folder}_{}.zip", stamp(t))).exists() { t += 1; }
    let out = dir.join(format!("{folder}_{}.zip", stamp(t)));
    let part = out.with_extension("zip.part");
    let res = (|| -> Result<()> {
        let mut zip = zip::ZipWriter::new(fs::File::create(&part)?);
        let opt = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        add_dir(&mut zip, &src, &src, opt)?;
        zip.finish()?;
        Ok(())
    })();
    match res {
        Ok(()) => { fs::rename(&part, &out)?; Ok(out) }
        Err(e) => { let _ = fs::remove_file(&part); Err(e) }
    }
}

/// The instance's world backups, newest first.
pub fn list_backups(paths: &Paths, instance: &str) -> Vec<Backup> {
    let mut v: Vec<Backup> = fs::read_dir(backups_dir(paths, instance)).map(|rd| rd.flatten().filter_map(|e| {
        let p = e.path();
        if p.extension().and_then(|x| x.to_str()) != Some("zip") { return None; }
        let stem = p.file_stem()?.to_string_lossy().to_string();
        let md = e.metadata().ok()?;
        let (world, created) = split_stamp(&stem)
            .unwrap_or_else(|| (stem.clone(), md.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map_or(0, |d| d.as_secs())));
        Some(Backup { file: p, world, created, size_bytes: md.len() })
    }).collect()).unwrap_or_default();
    v.sort_by(|a, b| b.created.cmp(&a.created).then(a.file.cmp(&b.file)));
    v
}

/// Restore a backup zip into `saves/`. If the world's folder name is taken it goes to "<name> (restored)", "(restored 2)"…
/// Existing worlds are never touched. Returns the folder name created.
pub fn restore_backup(paths: &Paths, instance: &str, backup: &Path) -> Result<String> {
    let mut zip = zip::ZipArchive::new(fs::File::open(backup).with_context(|| format!("opening {}", backup.display()))?)?;
    let mut top: Option<String> = None;
    for i in 0..zip.len() {
        let name = zip.by_index_raw(i)?.name()?.replace('\\', "/");
        let first = name.split('/').next().unwrap_or_default().to_string();
        match &top { None => top = Some(first), Some(t) if *t != first => bail!("backup does not contain a single world folder"), _ => {} }
    }
    let top = top.filter(|t| !t.is_empty()).ok_or_else(|| anyhow!("backup is empty"))?;
    let saves = saves_dir(paths, instance);
    safe_join(&saves, &top)?;
    if zip.by_name(&format!("{top}/level.dat")).is_err() { bail!("backup has no level.dat"); }
    fs::create_dir_all(&saves)?;
    let mut name = top.clone();
    for i in 1.. {
        if !saves.join(&name).exists() { break; }
        name = if i == 1 { format!("{top} (restored)") } else { format!("{top} (restored {i})") };
    }
    let dest = saves.join(&name);
    fs::create_dir_all(&dest)?;
    if let Err(e) = extract_prefix(&mut zip, &format!("{top}/"), &dest) {
        let _ = fs::remove_dir_all(&dest);
        return Err(e);
    }
    Ok(name)
}

/// Delete one backup zip (must be inside the instance's `backups/` folder).
pub fn delete_backup(paths: &Paths, instance: &str, backup: &Path) -> Result<()> {
    if backup.parent() != Some(backups_dir(paths, instance).as_path()) || backup.extension().and_then(|x| x.to_str()) != Some("zip") {
        bail!("not a backup of this instance");
    }
    fs::remove_file(backup)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use crate::modpack::tmpdir;

    fn s(out: &mut Vec<u8>, t: u8, k: &str) { out.push(t); out.extend((k.len() as u16).to_be_bytes()); out.extend(k.as_bytes()); }

    /// Gzipped level.dat with a Data compound.
    fn level_dat(name: &str, last: i64, mode: i32) -> Vec<u8> {
        let mut o = vec![];
        s(&mut o, 10, "");
        s(&mut o, 10, "Data");
        s(&mut o, 8, "LevelName"); o.extend((name.len() as u16).to_be_bytes()); o.extend(name.as_bytes());
        s(&mut o, 4, "LastPlayed"); o.extend(last.to_be_bytes());
        s(&mut o, 3, "GameType"); o.extend(mode.to_be_bytes());
        s(&mut o, 1, "hardcore"); o.push(1);
        s(&mut o, 7, "Junk"); o.extend(2i32.to_be_bytes()); o.extend([1, 2]);
        s(&mut o, 10, "Version"); s(&mut o, 8, "Name"); o.extend(6u16.to_be_bytes()); o.extend(b"1.21.1"); o.push(0);
        o.push(0); o.push(0);
        let mut e = flate2::write::GzEncoder::new(vec![], flate2::Compression::fast());
        e.write_all(&o).unwrap();
        e.finish().unwrap()
    }

    #[test]
    fn lists_worlds() {
        let root = tmpdir("worlds");
        let paths = Paths { root: root.clone() };
        let saves = saves_dir(&paths, "t");
        fs::create_dir_all(saves.join("a")).unwrap();
        fs::create_dir_all(saves.join("b")).unwrap();
        fs::create_dir_all(saves.join("notworld")).unwrap();
        fs::write(saves.join("a/level.dat"), level_dat("Alpha", 1_700_000_000_000, 1)).unwrap();
        fs::write(saves.join("a/icon.png"), "png").unwrap();
        fs::write(saves.join("b/level.dat"), b"garbage").unwrap();
        let w = list(&paths, "t");
        assert_eq!(w.len(), 2);
        assert_eq!((w[0].name.as_str(), w[0].game_mode.as_str(), w[0].last_played, w[0].hardcore, w[0].version.as_str()), ("Alpha", "Creative", 1_700_000_000_000, true, "1.21.1"));
        assert!(w[0].icon.is_some() && w[0].readable && w[0].size_bytes > 0);
        assert!(!w[1].readable && w[1].name == "b");
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn backup_restore_delete() {
        let root = tmpdir("backup");
        let paths = Paths { root: root.clone() };
        let w = saves_dir(&paths, "t").join("My World");
        fs::create_dir_all(w.join("region")).unwrap();
        fs::write(w.join("level.dat"), level_dat("My World", 5, 0)).unwrap();
        fs::write(w.join("region/r.0.0.mca"), vec![7u8; 5000]).unwrap();
        fs::write(w.join("session.lock"), "x").unwrap();
        let b = backup_world(&paths, "t", "My World").unwrap();
        let again = backup_world(&paths, "t", "My World").unwrap();
        assert_ne!(b, again);
        let bs = list_backups(&paths, "t");
        assert_eq!(bs.len(), 2);
        assert_eq!(bs[0].world, "My World");
        assert!(bs[0].created > 1_700_000_000);
        // restore while the original exists -> new folder, original untouched
        let name = restore_backup(&paths, "t", &b).unwrap();
        assert_eq!(name, "My World (restored)");
        assert_eq!(fs::read(saves_dir(&paths, "t").join(&name).join("region/r.0.0.mca")).unwrap().len(), 5000);
        assert!(!saves_dir(&paths, "t").join(&name).join("session.lock").exists());
        assert_eq!(restore_backup(&paths, "t", &b).unwrap(), "My World (restored 2)");
        // deleting is confined to the backups folder
        assert!(delete_backup(&paths, "t", &w.join("level.dat")).is_err());
        delete_backup(&paths, "t", &b).unwrap();
        assert_eq!(list_backups(&paths, "t").len(), 1);
        assert!(backup_world(&paths, "t", "../x").is_err());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn time_roundtrip() {
        assert_eq!(stamp(1_700_000_000), "2023-11-14_22-13-20");
        assert_eq!(split_stamp("World_2023-11-14_22-13-20"), Some(("World".into(), 1_700_000_000)));
        assert_eq!(split_stamp("nope"), None);
    }
}

// ---- world management ----

/// Delete `saves/<folder>` (only real worlds: it must hold a level.dat).
pub fn delete_world(paths: &Paths, instance: &str, folder: &str) -> Result<()> {
    let src = safe_join(&saves_dir(paths, instance), folder)?;
    if !src.join("level.dat").is_file() { bail!("{folder} is not a world"); }
    fs::remove_dir_all(&src).with_context(|| format!("deleting {}", src.display()))
}

fn copy_tree(from: &Path, to: &Path) -> Result<()> {
    fs::create_dir_all(to)?;
    for e in fs::read_dir(from)? {
        let e = e?;
        let t = e.file_type()?;
        if t.is_symlink() || e.file_name() == "session.lock" { continue; }
        let dest = to.join(e.file_name());
        if t.is_dir() { copy_tree(&e.path(), &dest)?; } else { fs::copy(e.path(), dest)?; }
    }
    Ok(())
}

/// Copy a world folder (anything holding a level.dat, e.g. from `.minecraft/saves`) into the instance.
/// Returns the folder name it got (a free one when the name is taken).
pub fn import_world(paths: &Paths, instance: &str, src: &Path) -> Result<String> {
    if !src.join("level.dat").is_file() { bail!("that folder is not a Minecraft world (no level.dat)"); }
    let saves = saves_dir(paths, instance);
    fs::create_dir_all(&saves)?;
    let base = src.file_name().and_then(|n| n.to_str()).unwrap_or("World").to_string();
    let name = std::iter::once(base.clone()).chain((2..).map(|i| format!("{base} ({i})"))).find(|n| !saves.join(n).exists()).unwrap();
    let dest = saves.join(&name);
    if let Err(e) = copy_tree(src, &dest) { let _ = fs::remove_dir_all(&dest); return Err(e); }
    Ok(name)
}

/// Copy a world into another instance (keeps the folder name when free).
pub fn copy_world(paths: &Paths, from_instance: &str, folder: &str, to_instance: &str) -> Result<String> {
    let src = safe_join(&saves_dir(paths, from_instance), folder)?;
    import_world(paths, to_instance, &src)
}

/// The instance's screenshots, newest first.
pub fn screenshots(paths: &Paths, instance: &str) -> Vec<PathBuf> {
    let dir = paths.instance(instance).join("screenshots");
    let mut v: Vec<(std::time::SystemTime, PathBuf)> = fs::read_dir(dir).map(|rd| rd.flatten().map(|e| e.path())
        .filter(|p| p.extension().and_then(|x| x.to_str()).is_some_and(|x| x.eq_ignore_ascii_case("png") || x.eq_ignore_ascii_case("jpg")))
        .map(|p| (fs::metadata(&p).and_then(|m| m.modified()).unwrap_or(UNIX_EPOCH), p)).collect()).unwrap_or_default();
    v.sort_by_key(|a| std::cmp::Reverse(a.0));
    v.into_iter().map(|(_, p)| p).collect()
}

#[cfg(test)]
mod manage_tests {
    use super::*;

    #[test]
    fn import_copy_delete() {
        let p = Paths { root: std::env::temp_dir().join(format!("nc-worlds-{}", std::process::id())) };
        let src = p.root.join("ext").join("My World");
        fs::create_dir_all(src.join("region")).unwrap();
        fs::write(src.join("level.dat"), b"x").unwrap();
        fs::write(src.join("region").join("r.0.0.mca"), b"y").unwrap();
        assert_eq!(import_world(&p, "a", &src).unwrap(), "My World");
        assert_eq!(import_world(&p, "a", &src).unwrap(), "My World (2)");
        assert_eq!(copy_world(&p, "a", "My World", "b").unwrap(), "My World");
        assert!(saves_dir(&p, "b").join("My World").join("region").join("r.0.0.mca").is_file());
        delete_world(&p, "a", "My World (2)").unwrap();
        assert!(!saves_dir(&p, "a").join("My World (2)").exists());
        assert!(delete_world(&p, "a", "..").is_err());
        assert!(import_world(&p, "a", &p.root).is_err());
        let _ = fs::remove_dir_all(&p.root);
    }
}
