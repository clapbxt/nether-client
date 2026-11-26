//! Resource packs as the launcher manages them, carried into the game. The game only follows options.txt's
//! `resourcePacks` list; the launcher turns a pack off by renaming it to `.zip.disabled`, and downloads / combines drop
//! new zips in the folder. Before each launch [`sync`] reconciles the two: zips that are gone (off or removed) leave
//! the list, zips new since the last launch (downloaded, combined, turned back on) join it on top, and packs a combine
//! replaced go off. A pack the player turned off in the game's own menu stays off (it is not new).

use std::collections::BTreeSet;
use std::path::Path;

/// In `resourcepacks/`: the zips seen at the last launch, and packs to turn off at the next one.
const STATE: &str = ".nether-packs.json";

#[derive(serde::Serialize, serde::Deserialize, Default)]
struct State {
    /// false until the first sync recorded what was already there (those keep the player's choice)
    #[serde(default)]
    init: bool,
    #[serde(default)]
    known: BTreeSet<String>,
    #[serde(default)]
    off: BTreeSet<String>,
}

fn load(dir: &Path) -> Option<State> { serde_json::from_slice(&std::fs::read(dir.join(STATE)).ok()?).ok() }

fn save(dir: &Path, s: &State) {
    if let Ok(b) = serde_json::to_vec(s) { let _ = crate::store::write_atomic(&dir.join(STATE), &b); }
}

fn is_zip(n: &str) -> bool { n.to_lowercase().ends_with(".zip") }

fn zips(dir: &Path) -> BTreeSet<String> {
    let Ok(rd) = std::fs::read_dir(dir) else { return BTreeSet::new() };
    rd.flatten().filter(|e| e.path().is_file()).filter_map(|e| e.file_name().to_str().map(str::to_string))
        .filter(|n| !n.starts_with('.') && is_zip(n)).collect()
}

/// After a combine wrote `made`: it switches on at the next launch and its source packs (file or folder names in
/// `dir`) go off under it.
pub fn replace(dir: &Path, made: &str, sources: &[String]) {
    let mut s = load(dir).unwrap_or_default();
    if !s.init { s.known = zips(dir); s.init = true; }
    s.known.remove(made);
    s.off.extend(sources.iter().filter(|n| *n != made).cloned());
    save(dir, &s);
}

/// Bring options.txt in line with the folder (call before launching). `legacy` = before 1.13 (bare names, no `file/`).
pub fn sync(game_dir: &Path, legacy: bool) {
    let dir = game_dir.join("resourcepacks");
    if !dir.is_dir() { return; }
    let zips = zips(&dir);
    let opts = game_dir.join("options.txt");
    // first run: the game builds its own list; new zips are still "new" at the next launch
    let Ok(text) = std::fs::read_to_string(&opts) else { return };
    let mut st = load(&dir).unwrap_or_default();
    let first = !st.init;
    let entry = |n: &str| if legacy { n.to_string() } else { format!("file/{n}") };
    let name = |e: &str| if legacy { e.to_string() } else { e.strip_prefix("file/").unwrap_or("").to_string() };
    let mut seen = false;
    let mut lines: Vec<String> = text.lines().map(|l| {
        let Some(v) = l.strip_prefix("resourcePacks:") else { return l.to_string() };
        seen = true;
        let Ok(mut list) = serde_json::from_str::<Vec<String>>(v) else { return l.to_string() };
        list.retain(|e| { let n = name(e); n.is_empty() || ((!is_zip(&n) || zips.contains(&n)) && !st.off.contains(&n)) });
        // packs from before this existed keep the player's choice; only later arrivals switch on
        if !first {
            for n in zips.iter().filter(|n| !st.known.contains(*n) && !st.off.contains(*n)) {
                let e = entry(n);
                if !list.contains(&e) { list.push(e); }
            }
        }
        format!("resourcePacks:{}", serde_json::to_string(&list).unwrap_or_default())
    }).collect();
    if !seen && !first {
        let mut list: Vec<String> = if legacy { Vec::new() } else { vec!["vanilla".into()] };
        list.extend(zips.iter().filter(|n| !st.known.contains(*n) && !st.off.contains(*n)).map(|n| entry(n)));
        if list.len() > usize::from(!legacy) { lines.push(format!("resourcePacks:{}", serde_json::to_string(&list).unwrap_or_default())); }
    }
    let mut out = lines.join("\n");
    out.push('\n');
    if out != text { let _ = crate::store::write_atomic(&opts, out.as_bytes()); }
    st.known = zips;
    st.init = true;
    st.off.clear();
    save(&dir, &st);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn opts(d: &Path) -> String { std::fs::read_to_string(d.join("options.txt")).unwrap() }

    #[test]
    fn new_on_gone_off_player_choice_kept() {
        let d = std::env::temp_dir().join(format!("nc-rpacks-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        let rp = d.join("resourcepacks");
        std::fs::create_dir_all(rp.join("Folder")).unwrap();
        for f in ["A.zip", "B.zip", "Old.zip"] { std::fs::write(rp.join(f), b"z").unwrap(); }
        std::fs::write(d.join("options.txt"), "version:4671\nresourcePacks:[\"vanilla\",\"file/A.zip\",\"file/Folder\"]\n").unwrap();
        // first sync: B and Old existed before (player left them off), so nothing switches on
        sync(&d, false);
        assert!(opts(&d).contains(r#"resourcePacks:["vanilla","file/A.zip","file/Folder"]"#), "{}", opts(&d));
        // A turned off in the launcher, C downloaded, Old combined away into Mix
        std::fs::rename(rp.join("A.zip"), rp.join("A.zip.disabled")).unwrap();
        std::fs::write(rp.join("C.zip"), b"z").unwrap();
        std::fs::write(rp.join("Mix.zip"), b"z").unwrap();
        // the combine itself added the state before any sync: Mix still counts as new
        replace(&rp, "Mix.zip", &["Folder".into()]);
        sync(&d, false);
        assert!(opts(&d).contains(r#"resourcePacks:["vanilla","file/C.zip","file/Mix.zip"]"#), "{}", opts(&d));
        // A back on joins on top; B (never on) stays off
        std::fs::rename(rp.join("A.zip.disabled"), rp.join("A.zip")).unwrap();
        sync(&d, false);
        assert!(opts(&d).contains(r#"resourcePacks:["vanilla","file/C.zip","file/Mix.zip","file/A.zip"]"#), "{}", opts(&d));
        let _ = std::fs::remove_dir_all(&d);
    }
}
