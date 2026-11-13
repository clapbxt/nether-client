//! Logs and crash analysis: find an instance's latest log / crash report, tail it, and turn a crash into plain-English findings.
use std::fs;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use anyhow::Result;

use crate::paths::Paths;

/// What the launcher can do about a finding with one click (crash page and pre-launch check share these).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Action {
    /// +2 GB, capped at what the PC can spare
    MoreRam,
    /// set the instance's RAM to this many GB
    SetRam(u32),
    /// let the launcher pick Java
    AutoJava,
    /// turn a mod jar off (rename to `.disabled`)
    Disable(PathBuf),
    /// install a Modrinth project (slug or id) into the instance
    Install(String),
    /// put a mod-update snapshot back (`snapshot::restore`)
    Revert(String),
}

/// One diagnosis: what happened, in plain English, and what to try.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Finding {
    /// Stable rule id (e.g. "oom", "java-version", "missing-dependency") for icons/tests.
    pub id: &'static str,
    pub title: String,
    pub explanation: String,
    pub fix: String,
    /// one-click fix, if the launcher has one
    pub action: Option<Action>,
}

/// The log files an instance currently has.
#[derive(Clone, Debug, Default)]
pub struct LogFiles {
    /// `logs/latest.log` (the game's own log)
    pub latest_log: Option<PathBuf>,
    /// newest file in `crash-reports/`
    pub crash_report: Option<PathBuf>,
    /// newest `hs_err_pid*.log` (JVM crash)
    pub hs_err: Option<PathBuf>,
    /// `logs/launcher.log` (stdout/stderr of the last launch)
    pub launcher_log: Option<PathBuf>,
}

fn mtime(p: &Path) -> SystemTime { fs::metadata(p).and_then(|m| m.modified()).unwrap_or(SystemTime::UNIX_EPOCH) }

fn newest(dir: &Path, ok: impl Fn(&str) -> bool) -> Option<PathBuf> {
    fs::read_dir(dir).ok()?.flatten().filter(|e| ok(&e.file_name().to_string_lossy())).map(|e| e.path()).filter(|p| p.is_file()).max_by_key(|p| mtime(p))
}

/// Locate the instance's logs and crash reports.
pub fn find_logs(paths: &Paths, instance: &str) -> LogFiles {
    let dir = paths.instance(instance);
    let file = |p: PathBuf| Some(p).filter(|p| p.is_file());
    LogFiles {
        latest_log: file(dir.join("logs").join("latest.log")),
        crash_report: newest(&dir.join("crash-reports"), |n| n.ends_with(".txt")),
        hs_err: newest(&dir, |n| n.starts_with("hs_err_pid") && n.ends_with(".log")),
        launcher_log: file(dir.join("logs").join("launcher.log")),
    }
}

/// The last `lines` lines of a text file (reads at most the last 4 MB).
pub fn tail(path: &Path, lines: usize) -> Result<String> {
    let mut f = fs::File::open(path)?;
    let len = f.metadata()?.len();
    let cap = 4u64 << 20;
    f.seek(SeekFrom::Start(len.saturating_sub(cap)))?;
    let mut buf = vec![];
    f.read_to_end(&mut buf)?;
    let text = String::from_utf8_lossy(&buf);
    let all: Vec<&str> = text.lines().collect();
    Ok(all[all.len().saturating_sub(lines)..].join("\n"))
}

/// Diagnose the instance's most recent run: reads the fresh crash report / hs_err (only if newer than the last launch),
/// the end of latest.log and launcher.log, and applies `analyze`. `exit_code` is the game's exit status if known.
pub fn diagnose(paths: &Paths, instance: &str, exit_code: Option<i32>) -> Vec<Finding> {
    let l = find_logs(paths, instance);
    let launched = l.launcher_log.as_deref().map(mtime);
    let fresh = |p: &Option<PathBuf>| p.as_deref().filter(|p| launched.is_none_or(|t| mtime(p) + std::time::Duration::from_secs(5) >= t)).map(Path::to_path_buf);
    let mut text = String::new();
    for p in [fresh(&l.crash_report), fresh(&l.hs_err)].into_iter().flatten() {
        if let Ok(t) = tail(&p, 3000) { text.push_str(&t); text.push('\n'); }
    }
    for p in [&l.latest_log, &l.launcher_log].into_iter().flatten() {
        if let Ok(t) = tail(p, 2500) { text.push_str(&t); text.push('\n'); }
    }
    analyze(&text, exit_code)
}

fn after<'a>(s: &'a str, needle: &str) -> Option<&'a str> { s.find(needle).map(|i| &s[i + needle.len()..]) }

fn number_at(s: &str) -> Option<u32> {
    let t = s.trim_start_matches(|c: char| !c.is_ascii_digit());
    // only skip a few filler chars such as "version " / "a "
    if s.len() - t.len() > 12 { return None; }
    t.split(|c: char| !c.is_ascii_digit()).next()?.parse().ok()
}

/// Java major version a log says is needed, if any.
fn java_needed(text: &str, lower: &str) -> Option<u32> {
    if let Some(n) = after(lower, "class file version ").and_then(number_at) { return Some(n.saturating_sub(44)); }
    if let Some(n) = after(lower, "unsupported class file major version ").and_then(number_at) { return Some(n.saturating_sub(44)); }
    for pat in ["requires java ", "requires a java ", "needs java ", "java runtime environment (jre) ", "requires at least java "] {
        if let Some(n) = after(lower, pat).and_then(number_at) { if (8..=40).contains(&n) { return Some(n); } }
    }
    let _ = text;
    None
}

/// Indented lines that follow line `i` (up to `max`).
fn block(lines: &[&str], i: usize, max: usize) -> Vec<String> {
    lines[i + 1..].iter().take_while(|l| l.starts_with(char::is_whitespace) && !l.trim().is_empty()).take(max).map(|l| l.trim().to_string()).collect()
}

fn list(items: &[String], max: usize) -> String {
    let mut v: Vec<String> = items.iter().take(max).cloned().collect();
    if items.len() > max { v.push(format!("and {} more", items.len() - max)); }
    v.join("; ")
}

/// Mod name behind a mixin failure line.
fn mixin_mod(line: &str) -> Option<String> {
    let low = line.to_lowercase();
    if let Some(r) = after(&low, "mixin apply for mod ") { return r.split_whitespace().next().map(String::from); }
    for tok in line.split(|c: char| c.is_whitespace() || "[]()\"',:".contains(c)) {
        let t = tok.to_lowercase();
        if let Some(n) = t.strip_suffix(".mixins.json").or(t.strip_suffix(".mixin.json")) { return Some(n.to_string()); }
        if let Some(n) = t.strip_prefix("mixins.").and_then(|r| r.strip_suffix(".json")) { return Some(n.to_string()); }
    }
    None
}

/// Analyse a crash report and/or log text (and the exit code if known) into findings, most specific first.
pub fn analyze(text: &str, exit_code: Option<i32>) -> Vec<Finding> {
    let lower = text.to_lowercase();
    let lines: Vec<&str> = text.lines().collect();
    let mut out: Vec<Finding> = vec![];
    macro_rules! add { ($id:expr, $t:expr, $e:expr, $f:expr) => { out.push(Finding { id: $id, title: $t.into(), explanation: $e, fix: $f, action: None }) }; }

    // missing dependency / wrong version (Fabric, Quilt)
    let mut missing = vec![];
    let mut wrong_mc = vec![];
    let mut wrong_ver = vec![];
    let mut suggestions = vec![];
    for l in &lines {
        let t = l.trim().trim_start_matches("- ").trim();
        let tl = t.to_lowercase();
        if tl.contains(" requires ") && tl.contains("which is missing") { missing.push(t.to_string()); }
        else if tl.contains(" requires ") && tl.contains("wrong version is present") {
            if tl.contains("of minecraft") || tl.contains("of mod 'minecraft'") { wrong_mc.push(t.to_string()); } else { wrong_ver.push(t.to_string()); }
        }
        else if tl.starts_with("install ") || tl.starts_with("replace mod ") || tl.starts_with("remove mod ") { suggestions.push(t.to_string()); }
    }
    let hint = |sug: &[String]| if sug.is_empty() { String::new() } else { format!(" Suggested by the loader: {}.", list(sug, 4)) };
    if !missing.is_empty() {
        let names: Vec<String> = missing.iter().filter_map(|l| {
            let head = l.to_lowercase().find(", which")?;
            let seg = &l[..head];
            let dep = seg.rsplit_once(" of ").map_or_else(|| seg.rsplit(' ').next().unwrap_or(seg), |x| x.1);
            Some(dep.trim().trim_start_matches("mod ").trim_matches('\'').to_string())
        }).collect();
        add!("missing-dependency", "A mod is missing something it needs",
            format!("The loader stopped because required mods are not installed: {}.", list(&missing, 4)),
            format!("Install the missing mod{} ({}) in the same version family as your other mods.{}", if names.len() == 1 { "" } else { "s" }, names.join(", "), hint(&suggestions)));
    }
    if !wrong_mc.is_empty() {
        add!("wrong-minecraft-version", "A mod is built for another Minecraft version",
            list(&wrong_mc, 4).to_string(),
            format!("Download the build of that mod for this instance's Minecraft version, or remove it.{}", hint(&suggestions)));
    }
    if !wrong_ver.is_empty() {
        add!("wrong-dependency-version", "A mod needs a different version of another mod",
            list(&wrong_ver, 4).to_string(),
            format!("Update (or downgrade) the mod named as the dependency so the versions match.{}", hint(&suggestions)));
    }

    // Forge / NeoForge
    if let Some(i) = lines.iter().position(|l| l.to_lowercase().contains("missing or unsupported mandatory dependencies")) {
        let mut items = block(&lines, i, 8);
        if items.is_empty() { items = lines[i + 1..].iter().map(|l| l.trim()).filter(|l| l.contains("Mod ID:")).take(8).map(String::from).collect(); }
        add!("missing-dependency-forge", "A mod is missing something it needs",
            format!("Forge/NeoForge refused to start: {}.", if items.is_empty() { "mandatory dependencies are missing or the wrong version".to_string() } else { list(&items, 4) }),
            "Install the listed mods (matching your Minecraft version and loader) or update the ones whose version does not fit.".into());
    }

    // duplicates
    if lower.contains("duplicate mod") || lower.contains("duplicatemodsfound") || lower.contains("duplicate id") || lower.contains("found duplicate") {
        let l = lines.iter().find(|l| { let x = l.to_lowercase(); x.contains("duplicate mod") || x.contains("duplicate id") || x.contains("found duplicate") }).map(|l| l.trim().to_string()).unwrap_or_default();
        add!("duplicate-mods", "The same mod is installed twice",
            if l.is_empty() { "Two jars in the mods folder provide the same mod.".into() } else { format!("Two jars provide the same mod: {l}") },
            "Open the Mods tab and delete or disable the older copy so only one file per mod remains.".into());
    }

    // wrong loader
    let forge_toml = lower.contains("mods.toml") && (lower.contains("missing") || lower.contains("not found") || lower.contains("invalid mod file"));
    if forge_toml || lower.contains("invalid mod file found") || lower.contains("has no fabric.mod.json") || lower.contains("not a valid fabric mod") {
        let l = lines.iter().find(|l| { let x = l.to_lowercase(); x.contains("mods.toml") || x.contains("invalid mod file") || x.contains("fabric.mod.json") }).map(|l| l.trim().to_string()).unwrap_or_default();
        add!("wrong-loader", "A mod was built for a different loader",
            format!("A jar in the mods folder is not made for this instance's mod loader (Fabric, Quilt, Forge and NeoForge mods are not interchangeable).{}", if l.is_empty() { String::new() } else { format!(" Log line: {l}") }),
            "Replace that mod with its build for this loader, or remove it. The Mods tab only offers compatible files.".into());
    }

    // mixin
    if let Some(l) = lines.iter().find(|l| { let x = l.to_lowercase(); x.contains("mixin apply") || x.contains("mixinapplyerror") || x.contains("invalidmixinexception") || x.contains("critical injection failure") || x.contains("mixin transformation") || x.contains("mixintransformererror") }) {
        let who = mixin_mod(l).or_else(|| lines.iter().find_map(|x| mixin_mod(x)));
        add!("mixin-failure", "A mod failed to patch the game (mixin error)",
            match &who { Some(m) => format!("The mod \"{m}\" could not apply its changes to Minecraft, usually because it is for another game version or clashes with another mod."), None => "A mod could not apply its changes to Minecraft, usually because it is for another game version or clashes with another mod.".into() },
            match &who { Some(m) => format!("Update or remove \"{m}\". If it was just added, remove it first; if you updated Minecraft or other mods, update \"{m}\" too."), None => "Remove the most recently added mod, or update all mods to versions for this Minecraft version.".into() });
    }

    // suspected mods
    let mut suspects: Vec<String> = vec![];
    for (i, l) in lines.iter().enumerate() {
        let t = l.trim();
        if t.starts_with("Suspected Mod") {
            let rest = t.split_once(':').map(|x| x.1.trim()).unwrap_or("");
            if !rest.is_empty() && !rest.eq_ignore_ascii_case("none") { suspects.push(rest.to_string()); }
            suspects.extend(block(&lines, i, 8).into_iter().filter(|s| !s.eq_ignore_ascii_case("none")));
        }
    }
    suspects.dedup();
    if !suspects.is_empty() {
        add!("suspected-mods", "The crash report names the mods involved",
            format!("Minecraft's crash report points at: {}.", list(&suspects, 5)),
            "Update those mods first; if that does not help, disable them one at a time until the crash stops.".into());
    }

    // memory
    if lower.contains("outofmemoryerror") || lower.contains("java heap space") || lower.contains("gc overhead limit") || lower.contains("unable to create native thread") {
        let metaspace = lower.contains("metaspace");
        add!("oom", "Minecraft ran out of memory",
            if metaspace { "The JVM ran out of class metadata space, which happens with very large mod lists.".into() } else { "The game used up all the RAM it was allowed.".to_string() },
            "Raise the instance's RAM in its settings (4-8 GB suits most modpacks; keep it below your free system RAM), lower render distance, or remove heavy mods.".into());
    }

    // java version
    if lower.contains("unsupportedclassversionerror") || lower.contains("unsupported class file major version") || (java_needed(text, &lower).is_some() && (lower.contains("requires java") || lower.contains("needs java") || lower.contains("class file version"))) {
        let n = java_needed(text, &lower);
        add!("java-version", "Wrong Java version",
            match n { Some(n) => format!("The game or a mod needs Java {n} (or newer), but the instance is running an older Java."), None => "The game or a mod needs a newer Java than the instance is running.".into() },
            match n { Some(n) => format!("In the instance settings set Java to {n} (or Auto). Nether Client downloads it if it is missing."), None => "In the instance settings set Java to Auto or the newest version.".into() });
    }
    if lower.contains("inaccessibleobjectexception") || (lower.contains("module java.base") && lower.contains("does not \"opens")) || (lower.contains("unable to make field") && lower.contains("accessible: module")) {
        add!("java-too-new", "Java is too new for this setup",
            "An older mod loader or mod tried to reach into Java internals that newer Java versions lock down.".into(),
            "Set the instance's Java to 8 (Minecraft 1.16 and older) or 17 (1.17-1.20.4) in its settings.".into());
    }

    // graphics
    if lower.contains("glfw error 65542") || lower.contains("glfw error 65543") || lower.contains("pixel format not accelerated") || lower.contains("the driver does not appear to support opengl") || lower.contains("failed to create window") || lower.contains("glfw_version_unavailable") || lower.contains("opengl 3.2 is not supported") {
        add!("graphics-driver", "Graphics driver problem",
            "Windows could not give Minecraft a hardware-accelerated OpenGL window. This is almost always an outdated or missing graphics driver, or the game running on a GPU without OpenGL support (remote desktop, virtual machine, wrong GPU).".into(),
            "Update your graphics driver from NVIDIA, AMD or Intel, make sure javaw.exe uses your main GPU in Windows graphics settings, and do not launch through Remote Desktop.".into());
    } else if lower.contains("opengl error") || lower.contains("glerror") || lower.contains("gl_invalid") {
        add!("opengl-error", "OpenGL error",
            "The game reported an OpenGL error, which usually comes from a graphics driver bug or a shader/rendering mod conflict.".into(),
            "Update your graphics driver; if you use shaders or rendering mods, try without them.".into());
    }

    // corrupt level.dat / world
    if lower.contains("level.dat") && (lower.contains("eofexception") || lower.contains("not in gzip format") || lower.contains("unexpected end of zlib") || lower.contains("corrupt") || lower.contains("failed to read level") || lower.contains("zipexception")) {
        add!("corrupt-world", "A world's level.dat is damaged",
            "Minecraft could not read a world's level.dat, so that world cannot load.".into(),
            "Restore the world from a backup in the Worlds tab. Otherwise, in the world folder rename level.dat_old to level.dat (after keeping a copy of the damaged file).".into());
    }

    // native crash
    let code_native = matches!(exit_code, Some(-1073741819) | Some(-1073740791) | Some(-1073740940));
    if code_native || lower.contains("exception_access_violation") || lower.contains("0xc0000005") || lower.contains("a fatal error has been detected by the java runtime environment") {
        let dll = lines.iter().position(|l| l.contains("Problematic frame")).and_then(|i| lines.get(i + 1)).and_then(|l| l.split('[').nth(1)).and_then(|s| s.split(['+', ']']).next()).map(|s| s.trim().to_string());
        let driver = dll.as_deref().map(str::to_lowercase).filter(|d| ["nvoglv", "nvwgf", "atio", "amdxx", "ig9icd", "ig75icd", "igd", "ig11icd", "ig4icd", "aticfx", "atig6"].iter().any(|p| d.contains(p)));
        let (explain, fix) = match (&driver, &dll) {
            (Some(d), _) => (format!("Minecraft's Java process crashed inside the graphics driver ({d})."), "Update (or cleanly reinstall) your graphics driver; disable overlays and shader or rendering mods to test.".to_string()),
            (None, Some(d)) => (format!("Minecraft's Java process crashed inside native code ({d}), not in Java itself."), "If that file belongs to an overlay, recorder or antivirus, close or exclude it; otherwise update graphics drivers and remove recently added mods.".into()),
            _ => ("Minecraft's Java process crashed in native code (access violation, exit code 0xC0000005), not in Java itself.".to_string(), "Update graphics drivers, close overlays/recorders (Discord, MSI Afterburner, etc.), and test without shaders or newly added mods.".into()),
        };
        add!("native-crash", "The game crashed outside Java (native crash)", explain, fix);
    }

    // fallback: surface the last exception so there is always something to read
    if out.is_empty() {
        if let Some(l) = lines.iter().rev().find(|l| l.trim_start().starts_with("Caused by:")).or_else(|| lines.iter().rev().find(|l| l.contains("Exception") && !l.trim_start().starts_with("at "))) {
            out.push(Finding { id: "unknown", title: "Crash cause not recognised".into(), explanation: format!("The last error in the log is: {}", l.trim()), fix: "Search that line online or share the crash report when asking for help; try removing the most recently added mod.".into(), action: None });
        }
    }
    for f in &mut out {
        f.action = match f.id { "oom" => Some(Action::MoreRam), "java-version" | "java-too-new" => Some(Action::AutoJava), _ => None };
    }
    out
}

/// Crash-page extras for a modded instance: the newest mod (added in the last week) and the newest mod-update
/// snapshot (last week), each with a one-click way back.
pub fn recent_changes(paths: &Paths, instance: &str) -> Vec<Finding> {
    let week = std::time::Duration::from_secs(7 * 86_400);
    let recent = |t: SystemTime| t.elapsed().is_ok_and(|e| e < week);
    let mut out = vec![];
    if let Some(s) = crate::snapshot::list(paths, instance).into_iter().next().filter(|s| recent(SystemTime::UNIX_EPOCH + std::time::Duration::from_secs(s.ts))) {
        out.push(Finding { id: "recent-update", title: "Undo the last mod update".into(),
            explanation: format!("{} shortly before this crash. If it worked before that, the update is the likely cause.", s.reason),
            fix: "Put the previous mod files and configs back, then play again.".into(), action: Some(Action::Revert(s.id)) });
    }
    let newest = crate::instances::mod_files(paths, instance).into_iter()
        .filter(|p| p.extension().is_some_and(|e| e == "jar"))
        .filter_map(|p| Some((fs::metadata(&p).ok()?.modified().ok()?, p)))
        .filter(|(t, _)| recent(*t)).max_by_key(|(t, _)| *t);
    if let Some((_, p)) = newest {
        let n = p.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string();
        out.push(Finding { id: "last-mod", title: "Try without the newest mod".into(),
            explanation: format!("{n} is the most recently added mod. A crash right after adding a mod usually comes from that mod."),
            fix: "Disable it and play again. Turn it back on any time in the Mods tab.".into(), action: Some(Action::Disable(p)) });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(t: &str, code: Option<i32>) -> Vec<&'static str> { analyze(t, code).iter().map(|f| f.id).collect() }
    fn one(t: &str, id: &str) -> Finding { analyze(t, None).into_iter().find(|f| f.id == id).unwrap_or_else(|| panic!("no {id} in {:?}", ids(t, None))) }

    #[test]
    fn out_of_memory() {
        let f = one("Caused by: java.lang.OutOfMemoryError: Java heap space\n\tat foo", "oom");
        assert!(f.fix.contains("RAM"));
    }

    #[test]
    fn java_version() {
        let t = "java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a more recent version of the Java Runtime (class file version 65.0), this version of the Java Runtime only recognizes class file versions up to 61.0";
        assert!(one(t, "java-version").explanation.contains("Java 21"));
        assert!(one("Minecraft 1.20.5 requires Java 21 or newer", "java-version").fix.contains("21"));
        assert!(one("Unsupported class file major version 61", "java-version").explanation.contains("Java 17"));
    }

    #[test]
    fn fabric_missing_dependency() {
        let t = "net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!\nA potential solution has been determined, this may resolve your problem:\n\t - Install fabric-api, version 0.97.0 or later.\nMore details:\n\t - Mod 'Sodium Extra' (sodium-extra) 0.5.0 requires version 0.97.0 or later of fabric-api, which is missing!";
        let f = one(t, "missing-dependency");
        assert!(f.explanation.contains("sodium-extra") && f.fix.contains("Install fabric-api"), "{f:?}");
        assert!(f.fix.contains("fabric-api"));
    }

    #[test]
    fn fabric_wrong_versions() {
        let t = "\t - Mod 'Lithium' (lithium) 0.12.0 requires version 1.20.4 of minecraft, but only the wrong version is present: 1.21.1!";
        let f = one(t, "wrong-minecraft-version");
        assert!(f.explanation.contains("Lithium"));
        let t2 = "- Mod 'A' (a) 1 requires version 2.0 of mod 'b', but only the wrong version is present: 1.0!";
        assert!(analyze(t2, None).iter().any(|f| f.id == "wrong-dependency-version"));
    }

    #[test]
    fn forge_missing_dependency() {
        let t = "net.minecraftforge.fml.ModLoadingException: Missing or unsupported mandatory dependencies:\n\tMod ID: 'geckolib', Requested by: 'alexsmobs', Expected range: '[4.4,)', Actual version: '[MISSING]'\n\tMod ID: 'citadel', Requested by: 'alexsmobs', Expected range: '[2.6,)', Actual version: '[MISSING]'\n\nat foo";
        let f = one(t, "missing-dependency-forge");
        assert!(f.explanation.contains("geckolib") && f.explanation.contains("citadel"));
    }

    #[test]
    fn duplicates() {
        assert!(analyze("net.fabricmc.loader.impl.discovery.ModResolutionException: Duplicate mod: sodium", None).iter().any(|f| f.id == "duplicate-mods"));
        assert!(analyze("cpw.mods.modlauncher.ModLauncherException: DuplicateModsFoundException: Mod ID: 'jei' from mod files: a.jar, b.jar", None).iter().any(|f| f.id == "duplicate-mods"));
    }

    #[test]
    fn wrong_loader() {
        let t = "net.minecraftforge.fml.loading.moddiscovery.ModDiscoverer: Mod file C:\\mods\\sodium-fabric.jar is missing mods.toml file";
        assert!(analyze(t, None).iter().any(|f| f.id == "wrong-loader"));
        assert!(analyze("Invalid mod file found /mods/x.jar", None).iter().any(|f| f.id == "wrong-loader"));
    }

    #[test]
    fn mixin() {
        let f = one("[main/ERROR]: Mixin apply for mod sodium failed sodium.mixins.json:core.MixinFoo from mod sodium -> net.minecraft.Foo: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException", "mixin-failure");
        assert!(f.explanation.contains("\"sodium\""));
        let f = one("org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error was encountered\nCaused by: org.spongepowered.asm.mixin.injection.throwables.InjectionError: Critical injection failure: @Inject annotation on foo in cool-mod.mixins.json", "mixin-failure");
        assert!(f.explanation.contains("cool-mod"), "{f:?}");
    }

    #[test]
    fn suspected_mods() {
        let t = "-- System Details --\nDetails:\n\tSuspected Mods: \n\t\tIris (iris), Version: 1.7.0\n\t\tSodium (sodium), Version: 0.5.8\n\tMinecraft Version: 1.21";
        let f = one(t, "suspected-mods");
        assert!(f.explanation.contains("Iris") && f.explanation.contains("Sodium"));
        assert!(!analyze("Suspected Mods: None\n", None).iter().any(|f| f.id == "suspected-mods"));
        assert!(one("Suspected Mod: Create (create), Version: 0.5.1", "suspected-mods").explanation.contains("Create"));
    }

    #[test]
    fn graphics() {
        assert!(one("[Render thread/ERROR]: GLFW error 65542: WGL: The driver does not appear to support OpenGL", "graphics-driver").fix.contains("driver"));
        assert!(analyze("java.lang.IllegalStateException: Pixel format not accelerated", None).iter().any(|f| f.id == "graphics-driver"));
    }

    #[test]
    fn corrupt_level_dat() {
        let t = "java.io.EOFException: Unexpected end of ZLIB input stream\n\tat ...\nFailed to read level.dat for world New World";
        assert!(analyze(t, None).iter().any(|f| f.id == "corrupt-world"));
        assert!(!analyze("Loaded level.dat fine", None).iter().any(|f| f.id == "corrupt-world"));
    }

    #[test]
    fn native_crash() {
        assert!(analyze("", Some(-1073741819)).iter().any(|f| f.id == "native-crash"));
        let hs = "# A fatal error has been detected by the Java Runtime Environment:\n#\n#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ffa\n# Problematic frame:\n# C  [nvoglv64.dll+0x1234abc]\n";
        let f = one(hs, "native-crash");
        assert!(f.explanation.contains("graphics driver") && f.explanation.contains("nvoglv64"), "{f:?}");
        assert!(analyze("", Some(0)).is_empty());
    }

    #[test]
    fn java_too_new_and_fallback() {
        assert!(analyze("java.lang.reflect.InaccessibleObjectException: Unable to make field private final java.util.Map accessible: module java.base does not \"opens java.util\"", None).iter().any(|f| f.id == "java-too-new"));
        assert_eq!(ids("foo\nCaused by: java.lang.NullPointerException: x\n\tat a.b", None), vec!["unknown"]);
        assert!(analyze("all good", None).is_empty());
    }

    #[test]
    fn finds_and_tails_logs() {
        let root = crate::modpack::tmpdir("crash");
        let paths = Paths { root: root.clone() };
        let d = paths.instance("t");
        fs::create_dir_all(d.join("logs")).unwrap();
        fs::create_dir_all(d.join("crash-reports")).unwrap();
        fs::write(d.join("logs/latest.log"), "a\nb\nc\nd\n").unwrap();
        fs::write(d.join("crash-reports/crash-1.txt"), "Caused by: java.lang.OutOfMemoryError: Java heap space").unwrap();
        fs::write(d.join("hs_err_pid42.log"), "x").unwrap();
        let l = find_logs(&paths, "t");
        assert!(l.latest_log.is_some() && l.crash_report.is_some() && l.hs_err.is_some() && l.launcher_log.is_none());
        assert_eq!(tail(l.latest_log.as_ref().unwrap(), 2).unwrap(), "c\nd");
        assert!(diagnose(&paths, "t", None).iter().any(|f| f.id == "oom"));
        let _ = fs::remove_dir_all(root);
    }
}
