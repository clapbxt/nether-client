//! `cargo run -p nether-core --release --example modinstall -- <project> <mc> <loader|-> <dir>`
use nether_core::{download::client, modrinth};

fn main() -> anyhow::Result<()> {
    let a: Vec<String> = std::env::args().collect();
    let loader = (a[3] != "-").then_some(a[3].as_str());
    let files = modrinth::install(&client(), &a[1], None, &a[2], loader, std::path::Path::new(&a[4]), &|_, _, _| {})?;
    println!("installed: {files:?}");
    Ok(())
}
