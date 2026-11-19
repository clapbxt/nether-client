//! `cargo run -p nether-core --release --example launch -- <version> [player] [seconds]`
//! Offline test launch: installs the version, starts it, watches it for N seconds, then stops it.
use std::sync::Arc;
use std::time::{Duration, Instant};

use nether_core::launch::{launch, LaunchOpts, Loader};
use nether_core::paths::Paths;
use nether_core::{Progress, Session};

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let version = args.get(1).cloned().unwrap_or_else(|| "1.21.11".into());
    let player = args.get(2).cloned().unwrap_or_else(|| "Player".into());
    let secs: u64 = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(25);

    let paths = Paths::new();
    let game_dir = match std::env::var("NETHER_INSTANCE") { Ok(n) => paths.instance(&n), Err(_) => paths.instance(&format!("test-{version}{}", args.get(4).map(|l| format!("-{l}")).unwrap_or_default())) };
    let last = std::sync::Mutex::new((String::new(), -1i32));
    let progress: Progress = Arc::new(move |msg, f| {
        let mut l = last.lock().unwrap();
        let pct = (f * 100.0) as i32;
        if l.0 != msg || pct / 10 != l.1 / 10 {
            println!("[{pct:>3}%] {msg}");
            *l = (msg.to_string(), pct);
        }
    });
    let loader = args.get(4).map(|s| Loader::from_label(s)).unwrap_or(Loader::Vanilla);
    // NC_TEST_SHARED=1: merge the shared game settings in first, as the launcher's Play does
    if std::env::var("NC_TEST_SHARED").is_ok() { std::fs::create_dir_all(&game_dir)?; nether_core::gamesettings::before_launch(&paths, &game_dir, &version, loader.label())?; }
    let mut child = launch(&paths, &LaunchOpts { loader, version, game_dir: game_dir.clone(), session: Session::offline(&player), ram_mb: 2048, java_major: None, client_config: None, client: std::env::var("NETHER_NO_AGENT").is_err(), groups: None, extra_jvm: vec![], window: std::env::var("NC_TEST_SIZE").ok().and_then(|s| { let (w, h) = s.split_once('x')?; Some((w.parse().ok()?, h.parse().ok()?)) }), fullscreen: false, quick_play: std::env::var("NC_TEST_WORLD").ok().map(nether_core::launch::QuickPlay::World), cancel: None, optifine: std::env::var("NC_TEST_OPTIFINE").is_ok() }, progress)?;

    let end = Instant::now() + Duration::from_secs(secs);
    let mut exited = None;
    while Instant::now() < end {
        if let Some(st) = child.try_wait()? { exited = Some(st); break; }
        std::thread::sleep(Duration::from_millis(500));
    }
    match exited {
        Some(st) => println!("Minecraft exited early: {st}"),
        None => { println!("Minecraft still running after {secs}s -> stopping it"); let _ = child.kill(); }
    }
    let log = std::fs::read_to_string(game_dir.join("logs").join("launcher.log")).unwrap_or_default();
    let tail: Vec<&str> = log.lines().rev().take(25).collect();
    println!("---- log tail ----");
    for l in tail.iter().rev() { println!("{l}"); }
    Ok(())
}
