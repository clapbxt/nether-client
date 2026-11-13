//! `cargo run -p nether-core --release --example signin` : full device-code sign-in from the terminal.
use std::sync::atomic::AtomicBool;

use nether_core::{auth, download::client, paths::Paths};

fn main() -> anyhow::Result<()> {
    let paths = Paths::new();
    let cid = auth::client_id(&paths).ok_or_else(|| anyhow::anyhow!("no ms_client_id configured"))?;
    println!("client id: {cid}");
    let c = client();
    let dc = auth::start_device_flow(&c, &cid)?;
    println!("Open {} and enter code {}", dc.verification_uri, dc.user_code);
    if std::env::args().any(|a| a == "--poll") {
        let t = auth::poll_device_flow(&c, &cid, &dc, &AtomicBool::new(false))?;
        println!("microsoft token ok");
        match auth::minecraft_login(&c, &t.access_token) {
            Ok(l) => { auth::remember(&paths, &l, &t.refresh_token)?; println!("signed in as {}", l.profile.name) }
            Err(e) => println!("minecraft step: {e}"),
        }
    }
    Ok(())
}
