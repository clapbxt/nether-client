//! `cargo run -p nether-core --release --example content -- pack <project> [version]`   install a Modrinth modpack into `test-<slug>`
//! `cargo run -p nether-core --release --example content -- update <instance> <mc> <loader|->`   update every mod of an instance
//! Only touches instances whose folder name starts with `test-`.
use std::sync::Arc;

use nether_core::download::client;
use nether_core::paths::Paths;
use nether_core::{instances, modpack, modrinth, worlds, Progress};

fn main() -> anyhow::Result<()> {
    let a: Vec<String> = std::env::args().collect();
    let paths = Paths::new();
    let c = client();
    let progress: Progress = Arc::new(|m, f| println!("[{:>3}%] {m}", (f * 100.0) as i32));
    match a.get(1).map(String::as_str) {
        Some("pack") => {
            let d = modpack::install_project(&c, &paths, &a[2], a.get(3).map(String::as_str), Some(&format!("test-{}", a[2])), &progress)?;
            println!("installed {d:?}");
            println!("worlds: {}", worlds::list(&paths, &d.name).len());
        }
        Some("old") => {
            // install the 3rd-newest compatible version of a project into test-<name> (to have something to update)
            let (project, mc, loader, name) = (&a[2], &a[3], a[4].as_str(), &a[5]);
            anyhow::ensure!(name.starts_with("test-"), "only test- instances");
            let vs = modrinth::versions(&c, project, mc, Some(loader))?;
            let v = vs.get(2).or(vs.last()).ok_or_else(|| anyhow::anyhow!("no versions"))?;
            let dir = instances::content_dir(&paths, name, 0);
            println!("{:?}", modrinth::install(&c, project, Some(&v.id), mc, Some(loader), &dir, &|_, _, _| {})?);
            if a.get(6).map(String::as_str) == Some("disable") {
                for p in instances::mod_files(&paths, name) { instances::set_mod_enabled(&p, false)?; }
            }
        }
        Some("update") => {
            let name = &a[2];
            anyhow::ensure!(name.starts_with("test-"), "only test- instances");
            let loader = (a[4] != "-").then_some(a[4].as_str());
            let files = instances::mod_files(&paths, name);
            for (p, r) in modrinth::update_all(&c, &files, &a[3], loader, &|_, _, _| {})? {
                println!("{} -> {:?}", p.display(), r.map_err(|e| e.to_string()));
            }
        }
        _ => println!("usage: content pack <project> [version] | update <instance> <mc> <loader|->"),
    }
    Ok(())
}
