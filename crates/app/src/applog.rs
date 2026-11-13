//! The launcher's own log (`<data>/logs/launcher.log`, kept under ~1 MB with one older copy) and a panic hook
//! that records what happened, so a crash in a windowed build (no console) is never silent.
use std::io::Write;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

static DIR: OnceLock<PathBuf> = OnceLock::new();
static LOCK: Mutex<()> = Mutex::new(());
const PANIC_NOTE: &str = "last-crash.txt";

pub fn dir() -> PathBuf { DIR.get().cloned().unwrap_or_else(|| std::env::temp_dir().join("NetherClient-logs")) }

pub fn file() -> PathBuf { dir().join("launcher.log") }

/// "2026-11-12 14:03:07" (UTC).
pub fn stamp() -> String {
    let s = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
    let (days, rem) = (s.div_euclid(86_400), s.rem_euclid(86_400));
    // civil date from days since 1970-01-01 (Howard Hinnant's algorithm)
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02} {:02}:{:02}:{:02}", rem / 3600, rem % 3600 / 60, rem % 60)
}

/// Append one line (rotating the file past 1 MB). Never fails loudly.
pub fn line(msg: &str) {
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let f = file();
    let _ = std::fs::create_dir_all(dir());
    if std::fs::metadata(&f).map(|m| m.len() > 1 << 20).unwrap_or(false) { let _ = std::fs::rename(&f, dir().join("launcher.1.log")); }
    if let Ok(mut o) = std::fs::OpenOptions::new().create(true).append(true).open(&f) {
        let _ = writeln!(o, "[{}] {msg}", stamp());
    }
}

/// Start logging under `root/logs` and record panics (message, place, backtrace) there.
pub fn init(root: &std::path::Path) {
    let _ = DIR.set(root.join("logs"));
    line(&format!("Nether Client {} starting ({} {})", env!("CARGO_PKG_VERSION"), std::env::consts::OS, std::env::consts::ARCH));
    let prev = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let bt = std::backtrace::Backtrace::force_capture();
        let thread = std::thread::current().name().unwrap_or("worker").to_string();
        let text = format!("panic on thread '{thread}': {info}\n{bt}");
        line(&text);
        // a worker panic only ends that task; the UI thread's takes the launcher down
        if thread == "main" { let _ = std::fs::write(dir().join(PANIC_NOTE), format!("{}\n{info}", stamp())); }
        prev(info);
    }));
}

/// The launcher crashed last time: the short note (and it is cleared).
pub fn take_crash_note() -> Option<String> {
    let p = dir().join(PANIC_NOTE);
    let t = std::fs::read_to_string(&p).ok()?;
    let _ = std::fs::remove_file(&p);
    Some(t)
}

#[cfg(test)]
mod tests {
    #[test]
    fn stamp_format() {
        let s = super::stamp();
        assert_eq!(s.len(), 19);
        assert!(s.starts_with("20") && &s[4..5] == "-" && &s[13..14] == ":");
    }
}
