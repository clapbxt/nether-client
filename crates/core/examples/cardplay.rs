//! `cargo run -p nether-core --release --example cardplay -- <version> <way 0|1>`
//! Prepares a fresh card profile the way Play's first PLAY does (`app/src/presets.rs play`): a new instance folder,
//! the PvP HUD client settings on 1.8.9, the Essentials pack on Fabric (way 1). Then launch it with
//! `NETHER_INSTANCE=card-<ver>-<way>` and the `launch` example.
use std::sync::Arc;

use nether_core::paths::Paths;
use nether_core::{netherpack, Progress};

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let ver = args.get(1).cloned().unwrap_or_else(|| "1.21.11".into());
    let way: i32 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(1);
    let paths = Paths::new();
    let name = format!("card-{ver}-{way}");
    let dir = paths.instance(&name);
    if dir.exists() { std::fs::remove_dir_all(&dir)?; }
    std::fs::create_dir_all(&dir)?;
    if ver == "1.8.9" {
        let pvp = netherpack::find(&paths, "pvp").expect("pvp pack");
        let mut root = serde_json::json!({ "modules": {} });
        if let Some(serde_json::Value::Object(pm)) = &pvp.client { for (id, kv) in pm { root["modules"][id] = kv.clone(); } }
        std::fs::write(dir.join("nether-client.json"), serde_json::to_vec_pretty(&root)?)?;
        println!("pvp client settings written");
    }
    if way == 1 {
        let pack = netherpack::find(&paths, "essentials").expect("essentials pack");
        let prog: Progress = Arc::new(|m: &str, f: f32| println!("{:>3}% {m}", (f * 100.0) as i32));
        let (ok, miss) = netherpack::install(&nether_core::download::client(), &pack, &ver, &dir.join("mods"), &prog)?;
        println!("installed: {ok:?}\nmissing: {miss:?}");
        for f in std::fs::read_dir(dir.join("mods"))?.flatten() { println!("  {}", f.file_name().to_string_lossy()); }
    }
    println!("ready: {}", dir.display());
    Ok(())
}
