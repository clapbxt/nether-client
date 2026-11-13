//! `cargo run -p nether-core --release --example host -- <root> [version] [vanilla|fabric|paper]`
//! Live check of Host + skins + UPnP against a throwaway data root (never the real one). Never accepts the EULA.
use std::sync::Arc;

use nether_core::{download::client, net, paths::Paths, server, skins};

fn main() -> anyhow::Result<()> {
    let mut a = std::env::args().skip(1);
    let root = a.next().ok_or_else(|| anyhow::anyhow!("usage: host <temp-root> [mc-version]"))?;
    let ver = a.next().unwrap_or_else(|| "1.21.11".into());
    let kind = a.next().unwrap_or_else(|| "vanilla".into());
    let paths = Paths { root: root.into() };
    let c = client();
    let progress: nether_core::Progress = Arc::new(|m, f| println!("  {m} {:.0}%", f * 100.0));

    println!("== server: {ver}");
    let dir = server::servers_root(&paths).join("demo");
    let opts = server::ServerOpts { dir: dir.clone(), version: ver, kind, ram_mb: 1024, port: 25565, java_major: None, upnp: false };
    match server::start(&c, &paths, &opts, progress) {
        Ok(_) => println!("UNEXPECTED: server started without EULA"),
        Err(e) if server::is_eula_error(&e) => println!("stopped at EULA as expected: {e}"),
        Err(e) => println!("FAILED: {e:#}"),
    }
    println!("eula accepted: {}", server::eula_accepted(&dir));
    println!("files: {:?}", std::fs::read_dir(&dir)?.flatten().map(|e| e.file_name().to_string_lossy().to_string()).collect::<Vec<_>>());
    if let Ok(t) = std::fs::read_to_string(dir.join("eula.txt")) { println!("eula.txt:\n{t}"); }

    println!("== skins");
    match skins::import_username(&c, &paths, "Notch") {
        Ok(s) => println!("imported {s:?}; library has {}", skins::list(&paths).len()),
        Err(e) => println!("FAILED: {e:#}"),
    }

    println!("== net");
    println!("lan ip: {:?}", net::lan_ip());
    match net::Upnp::open(25565, "Nether Client test") {
        Ok(mut u) => { println!("UPnP mapping ok, external ip {:?}", u.external_ip()); u.close(); println!("mapping removed"); }
        Err(e) => println!("UPnP: {e:#}"),
    }
    match net::public_ip() { Ok(ip) => println!("public ip from router: {ip:?}"), Err(e) => println!("public ip: {e:#}") }
    Ok(())
}
