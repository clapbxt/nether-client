//! Files dropped on the window (or opened with the launcher): modpacks (.mrpack, CurseForge / Prism zips,
//! Prism/MultiMC folders) import as new instances; mod jars and resource / shader pack zips go into the
//! instance open on screen.
use std::path::{Path, PathBuf};
use std::sync::Arc;

use slint::{ComponentHandle, Model};

use crate::{show_toast, AppWindow, Backend, Shared};

#[derive(PartialEq, Debug)]
enum Kind { Import, Mod, ResourcePack, Shader, Unknown }

/// Entry names of a zip (empty when it isn't one).
fn zip_names(p: &Path) -> Vec<String> {
    std::fs::File::open(p).ok().and_then(|f| zip::ZipArchive::new(f).ok()).map(|z| z.file_names().filter_map(|n| n.ok().map(|n| n.to_string())).collect()).unwrap_or_default()
}

fn kind_of(p: &Path) -> Kind {
    if p.is_dir() {
        let has = |n: &str| p.join(n).is_file();
        return if has("instance.cfg") || has("mmc-pack.json") { Kind::Import } else { Kind::Unknown };
    }
    let ext = p.extension().and_then(|e| e.to_str()).unwrap_or("").to_ascii_lowercase();
    match ext.as_str() {
        "mrpack" => Kind::Import,
        "jar" => Kind::Mod,
        "zip" => {
            let names = zip_names(p);
            let top = |n: &str| names.iter().any(|e| e == n || e.split_once('/').is_some_and(|(_, rest)| rest == n));
            if top("modrinth.index.json") || top("manifest.json") || top("instance.cfg") || top("mmc-pack.json") { Kind::Import }
            else if names.iter().any(|e| e.starts_with("shaders/")) { Kind::Shader }
            else if names.iter().any(|e| e == "pack.mcmeta") { Kind::ResourcePack }
            else { Kind::Unknown }
        }
        _ => Kind::Unknown,
    }
}

/// Route dropped files. UI thread.
pub fn dropped(ui: &AppWindow, sh: &Arc<Shared>, files: Vec<PathBuf>) {
    if files.is_empty() { return; }
    let open = (ui.get_tab() == 1 && ui.get_open_idx() >= 0).then(|| ui.get_open_idx());
    let (mut content, mut unknown): ([Vec<PathBuf>; 3], usize) = (Default::default(), 0);
    for f in files {
        match kind_of(&f) {
            Kind::Import => crate::instpage::import_path(ui, sh, f),
            Kind::Mod => content[0].push(f),
            Kind::ResourcePack => content[1].push(f),
            Kind::Shader => content[2].push(f),
            Kind::Unknown => unknown += 1,
        }
    }
    let any = content.iter().any(|c| !c.is_empty());
    match open {
        Some(idx) if any => {
            let inst = ui.global::<Backend>().get_instances().row_data(idx.max(0) as usize);
            for (kind, list) in content.into_iter().enumerate() {
                if list.is_empty() { continue; }
                if kind == 0 && inst.as_ref().is_some_and(|i| i.loader == "Vanilla") {
                    show_toast(ui, sh, "Couldn't add the mods: this is a vanilla instance, which doesn't load mods");
                    continue;
                }
                crate::instpage::add_files(ui, sh, idx, kind as i32, list);
            }
        }
        None if any => show_toast(ui, sh, "Open an instance first, then drop its mods and packs onto the window"),
        _ => {}
    }
    if unknown > 0 { show_toast(ui, sh, &format!("Couldn't use {unknown} dropped file{}: drop modpacks, mod jars or pack zips", if unknown == 1 { "" } else { "s" })); }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies() {
        let d = std::env::temp_dir().join(format!("nc-drop-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        let zip = |name: &str, entries: &[&str]| {
            let p = d.join(name);
            let mut z = zip::ZipWriter::new(std::fs::File::create(&p).unwrap());
            for e in entries { z.start_file(*e, zip::write::SimpleFileOptions::default()).unwrap(); }
            z.finish().unwrap();
            p
        };
        assert_eq!(kind_of(&zip("a.zip", &["shaders/final.fsh"])), Kind::Shader);
        assert_eq!(kind_of(&zip("b.zip", &["pack.mcmeta", "assets/x.png"])), Kind::ResourcePack);
        assert_eq!(kind_of(&zip("c.zip", &["manifest.json", "overrides/x"])), Kind::Import);
        assert_eq!(kind_of(&zip("d.zip", &["My Pack/instance.cfg"])), Kind::Import);
        assert_eq!(kind_of(&d.join("x.mrpack")), Kind::Import);
        assert_eq!(kind_of(&d.join("sodium.JAR")), Kind::Mod);
        assert_eq!(kind_of(&zip("e.zip", &["readme.txt"])), Kind::Unknown);
        let _ = std::fs::remove_dir_all(&d);
    }
}
