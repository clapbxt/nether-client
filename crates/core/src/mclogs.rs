//! mclo.gs: upload a log (public link) and read its automatic analysis (problems + solutions).
use anyhow::{anyhow, Result};
use reqwest::blocking::Client;
use serde_json::Value;

const API: &str = "https://api.mclo.gs/1";

/// What mclo.gs recognised in a log.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Insights {
    /// e.g. "Fabric 1.21.1 Client Log"
    pub title: String,
    /// (problem, solutions)
    pub problems: Vec<(String, Vec<String>)>,
    /// (label, value) facts such as Java version or mod count
    pub info: Vec<(String, String)>,
}

/// Upload log text; returns (id, public url).
pub fn upload(c: &Client, text: &str) -> Result<(String, String)> {
    let v: Value = c.post(format!("{API}/log")).form(&[("content", text)]).send()?.error_for_status()?.json()?;
    if v["success"].as_bool() != Some(true) { return Err(anyhow!("{}", v["error"].as_str().unwrap_or("mclo.gs refused the log"))); }
    let id = v["id"].as_str().ok_or_else(|| anyhow!("no id returned"))?.to_string();
    let url = v["url"].as_str().map(str::to_string).unwrap_or_else(|| format!("https://mclo.gs/{id}"));
    Ok((id, url))
}

/// Analysis of an uploaded log.
pub fn insights(c: &Client, id: &str) -> Result<Insights> {
    let v: Value = c.get(format!("{API}/insights/{id}")).send()?.error_for_status()?.json()?;
    Ok(parse(&v))
}

/// Analyse without storing (nothing is published).
pub fn analyse(c: &Client, text: &str) -> Result<Insights> {
    let v: Value = c.post(format!("{API}/analyse")).form(&[("content", text)]).send()?.error_for_status()?.json()?;
    Ok(parse(&v))
}

/// Write a support bundle (zip of plain text files) to `out`: the given summary, the instance's mod/pack list with
/// SHA-1 hashes, the end of its game log, last start log, newest crash report and JVM crash file, and the end of
/// the launcher's own log. Every text passes through `redact` (home folder, account tokens). Nothing is sent.
pub fn bundle(paths: &crate::paths::Paths, instance: &str, summary: &str, launcher_log: &std::path::Path, redact: &dyn Fn(&str) -> String, out: &std::path::Path) -> Result<()> {
    use std::io::Write;
    let mut z = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    let opt = zip::write::SimpleFileOptions::default();
    let mut add = |name: &str, text: &str| -> Result<()> { z.start_file(name, opt)?; z.write_all(redact(text).as_bytes())?; Ok(()) };
    add("summary.txt", summary)?;
    let mut list = String::new();
    for (kind, title) in [(0, "MODS"), (1, "RESOURCE PACKS"), (2, "SHADER PACKS")] {
        let files = crate::instances::content_files(paths, instance, kind);
        list.push_str(&format!("{title} ({})\n", files.len()));
        for f in files {
            let size = std::fs::metadata(&f).map(|m| m.len()).unwrap_or(0);
            let sha = crate::download::sha1_file(&f).unwrap_or_default();
            list.push_str(&format!("{}\t{} KB\tsha1 {sha}\n", f.file_name().and_then(|n| n.to_str()).unwrap_or(""), size / 1024));
        }
        list.push('\n');
    }
    add("mods.txt", &list)?;
    let l = crate::crash::find_logs(paths, instance);
    for (name, p, lines) in [("latest.log", &l.latest_log, 20_000), ("last-start.log", &l.launcher_log, 5_000), ("crash-report.txt", &l.crash_report, 5_000), ("jvm-crash.log", &l.hs_err, 5_000)] {
        if let Some(t) = p.as_deref().and_then(|p| crate::crash::tail(p, lines).ok()) { add(&format!("logs/{name}"), &t)?; }
    }
    if let Ok(t) = crate::crash::tail(launcher_log, 2_000) { add("logs/launcher.log", &t)?; }
    let bytes = z.finish()?.into_inner();
    crate::store::write_atomic(out, &bytes)?;
    Ok(())
}

pub fn parse(v: &Value) -> Insights {
    let a = &v["analysis"];
    let problems = a["problems"].as_array().map(|ps| ps.iter().filter_map(|p| {
        let m = p["message"].as_str()?.trim().to_string();
        let sol = p["solutions"].as_array().map(|s| s.iter().filter_map(|x| x["message"].as_str().map(|t| t.trim().to_string())).collect()).unwrap_or_default();
        Some((m, sol))
    }).collect()).unwrap_or_default();
    let info = a["information"].as_array().map(|is| is.iter().filter_map(|i| {
        Some((i["label"].as_str()?.to_string(), i["value"].as_str().map(str::to_string).or_else(|| i["value"].as_i64().map(|n| n.to_string()))?))
    }).collect()).unwrap_or_default();
    Insights { title: v["title"].as_str().unwrap_or_default().to_string(), problems, info }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bundle_redacts_every_file() {
        use std::io::Read;
        let root = crate::modpack::tmpdir("bundle");
        let paths = crate::paths::Paths { root: root.clone() };
        let d = paths.instance("t");
        std::fs::create_dir_all(d.join("logs")).unwrap();
        std::fs::create_dir_all(d.join("mods")).unwrap();
        std::fs::write(d.join("logs/latest.log"), "loading C:\\Users\\Bob\\x --accessToken SECRET").unwrap();
        std::fs::write(d.join("mods/a.jar"), "a").unwrap();
        let out = root.join("b.zip");
        let red = |t: &str| t.replace("Bob", "<user>").replace("SECRET", "<hidden>");
        bundle(&paths, "t", "Instance: t (Bob)", &root.join("none.log"), &red, &out).unwrap();
        let mut z = zip::ZipArchive::new(std::fs::File::open(&out).unwrap()).unwrap();
        let mut names = vec![];
        for i in 0..z.len() {
            let mut f = z.by_index(i).unwrap();
            let mut s = String::new();
            f.read_to_string(&mut s).unwrap();
            let n = f.name().unwrap().to_string();
            assert!(!s.contains("Bob") && !s.contains("SECRET"), "{n}: {s}");
            names.push(n);
        }
        assert_eq!(names, ["summary.txt", "mods.txt", "logs/latest.log"]);
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn parses_analysis() {
        let v = serde_json::json!({"title": "Fabric 1.21.1 Client Log", "analysis": {
            "problems": [{"message": "The mod 'Sodium Extra' is missing the required mod 'fabric-api'.", "solutions": [{"message": "Install the mod 'fabric-api'."}]}],
            "information": [{"label": "Java version", "value": "21"}, {"label": "Mods", "value": 42}]}});
        let i = parse(&v);
        assert_eq!(i.title, "Fabric 1.21.1 Client Log");
        assert_eq!(i.problems[0].1, vec!["Install the mod 'fabric-api'.".to_string()]);
        assert_eq!(i.info, vec![("Java version".into(), "21".into()), ("Mods".into(), "42".into())]);
    }
}
