//! Safe small data files (instances.json, accounts.json, config.json, ...).
//! Writes are atomic (unique temp file + rename), and loads tell "missing" from "can't read right now"
//! (locked by antivirus / OneDrive) from "damaged". A damaged file is moved aside, never overwritten.
use std::fs;
use std::io::{ErrorKind, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use anyhow::{anyhow, Result};
use serde::de::DeserializeOwned;
use serde_json::{json, Value};

use crate::paths::Paths;

/// Result of reading a data file.
#[derive(Debug)]
pub enum Load<T> {
    /// The file does not exist (first run).
    Missing,
    Ok(T),
    /// The file did not parse; it was moved to this path so nothing overwrites it.
    Damaged(PathBuf),
}

static SEQ: AtomicU32 = AtomicU32::new(0);

/// Write `bytes` to `path` atomically: readers see the old or the new file, never half of one.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    if let Some(d) = path.parent() { fs::create_dir_all(d)?; }
    let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("file");
    let tmp = path.with_file_name(format!("{name}.{}-{}.tmp", std::process::id(), SEQ.fetch_add(1, Ordering::Relaxed)));
    {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
    }
    // antivirus / indexers can hold the target for a moment
    let mut last = None;
    for i in 0..5u64 {
        match fs::rename(&tmp, path) {
            Ok(()) => return Ok(()),
            Err(e) => { last = Some(e); std::thread::sleep(Duration::from_millis(40 * (i + 1))); }
        }
    }
    let _ = fs::remove_file(&tmp);
    Err(last.unwrap_or_else(|| std::io::Error::other("rename failed")))
}

/// Read a file, retrying briefly while another program holds it. NotFound -> Ok(None).
fn read_retry(path: &Path) -> std::io::Result<Option<Vec<u8>>> {
    let mut last = None;
    for i in 0..5u64 {
        match fs::read(path) {
            Ok(b) => return Ok(Some(b)),
            Err(e) if e.kind() == ErrorKind::NotFound => return Ok(None),
            Err(e) => { last = Some(e); std::thread::sleep(Duration::from_millis(60 * (i + 1))); }
        }
    }
    Err(last.unwrap_or_else(|| std::io::Error::other("read failed")))
}

/// Move a damaged file out of the way (`<name>.damaged-<unix time>`); returns where it went.
pub fn quarantine(path: &Path) -> PathBuf {
    let ts = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("file");
    let aside = path.with_file_name(format!("{name}.damaged-{ts}"));
    if fs::rename(path, &aside).is_err() { let _ = fs::copy(path, &aside); }
    aside
}

/// Read and parse a JSON file. Err = it exists but can't be read right now (do not write over it).
pub fn read_json<T: DeserializeOwned>(path: &Path) -> Result<Load<T>> {
    let Some(bytes) = read_retry(path).map_err(|e| anyhow!("{} can't be read right now ({e})", path.display()))? else { return Ok(Load::Missing) };
    match serde_json::from_slice::<T>(&bytes) {
        Ok(v) => Ok(Load::Ok(v)),
        Err(_) => Ok(Load::Damaged(quarantine(path))),
    }
}

/// Like `read_json`, but never moves anything (for read-only lookups).
pub fn peek_json<T: DeserializeOwned>(path: &Path) -> Option<T> {
    read_retry(path).ok().flatten().and_then(|b| serde_json::from_slice(&b).ok())
}

/// Serialize as pretty JSON and write atomically.
pub fn write_json<T: serde::Serialize>(path: &Path, v: &T) -> Result<()> {
    write_atomic(path, &serde_json::to_vec_pretty(v)?)?;
    Ok(())
}

// ---- config.json: one serialized read-modify-write path for every setting ----

static CONFIG: Mutex<()> = Mutex::new(());

/// The whole config.json (`{}` when missing or unreadable). Read-only.
pub fn config(paths: &Paths) -> Value {
    peek_json::<Value>(&paths.config()).filter(|v| v.is_object()).unwrap_or_else(|| json!({}))
}

/// Change config.json under a process-wide lock and save it atomically. Refuses to write when the
/// file exists but can't be read (so other settings are never lost); a damaged file is moved aside first.
pub fn update_config(paths: &Paths, f: impl FnOnce(&mut serde_json::Map<String, Value>)) -> Result<()> {
    let _g = CONFIG.lock().unwrap_or_else(|e| e.into_inner());
    let file = paths.config();
    let mut v = match read_json::<Value>(&file)? {
        Load::Ok(v) if v.is_object() => v,
        Load::Ok(_) => { quarantine(&file); json!({}) }
        Load::Missing | Load::Damaged(_) => json!({}),
    };
    if let Some(o) = v.as_object_mut() { f(o); }
    write_json(&file, &v)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("nc-store-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn missing_ok_damaged() {
        let d = tmp("load");
        let f = d.join("a.json");
        assert!(matches!(read_json::<Vec<u32>>(&f).unwrap(), Load::Missing));
        write_json(&f, &vec![1u32, 2]).unwrap();
        assert!(matches!(read_json::<Vec<u32>>(&f).unwrap(), Load::Ok(v) if v == vec![1, 2]));
        fs::write(&f, b"[1, 2").unwrap();
        let Load::Damaged(aside) = read_json::<Vec<u32>>(&f).unwrap() else { panic!("expected damaged") };
        assert!(!f.exists() && fs::read(&aside).unwrap() == b"[1, 2");
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn config_keeps_other_keys_and_damaged_files() {
        let p = Paths { root: tmp("cfg") };
        update_config(&p, |o| { o.insert("a".into(), json!(1)); }).unwrap();
        update_config(&p, |o| { o.insert("b".into(), json!(2)); }).unwrap();
        assert_eq!(config(&p), json!({"a": 1, "b": 2}));
        fs::write(p.config(), b"{\"a\": 1, broken").unwrap();
        update_config(&p, |o| { o.insert("c".into(), json!(3)); }).unwrap();
        assert_eq!(config(&p), json!({"c": 3}));
        let kept = fs::read_dir(&p.root).unwrap().flatten().any(|e| e.file_name().to_string_lossy().starts_with("config.json.damaged-"));
        assert!(kept, "the damaged config is kept aside");
        let _ = fs::remove_dir_all(&p.root);
    }
}
