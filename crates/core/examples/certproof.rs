//! `cargo run -p nether-core --release --example certproof -- <out.json>`: the saved account's Nether API sign-in
//! proof (Mojang player certificate + signed test nonce), for checking the Worker's verifier offline.
use nether_core::{auth, backend, download::client, paths::Paths};

fn main() -> anyhow::Result<()> {
    let paths = Paths::new();
    let cid = auth::client_id(&paths).ok_or_else(|| anyhow::anyhow!("no ms_client_id configured"))?;
    let c = client();
    let s = auth::restore(&c, &paths, &cid)?.login.session;
    let proof = backend::certificate_proof(&c, &s, "0123456789abcdef0123456789abcdef")?;
    let out = std::env::args().nth(1).unwrap_or_else(|| "proof.json".into());
    std::fs::write(&out, serde_json::to_vec_pretty(&proof)?)?;
    println!("proof for {} written to {out}", s.name);
    Ok(())
}
