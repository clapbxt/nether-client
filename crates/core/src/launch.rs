use std::collections::HashMap;
use std::fs;
use std::io::Read;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};

use anyhow::{anyhow, Context, Result};
use rayon::prelude::*;

use crate::download::{check_cancel, client, fetch, Check};
use crate::java;
use crate::mojang::{self, rules_allow, Arg, AssetIndex, VersionJson};
use crate::paths::Paths;
use crate::{Progress, Session};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Loader {
    Vanilla,
    Fabric,
    Quilt,
    LegacyFabric,
    Forge,
    NeoForge,
}

impl Loader {
    /// Pick a loader from an instance's loader label ("Legacy Fabric" is checked before "Fabric").
    pub fn from_label(label: &str) -> Self {
        let l = label.to_lowercase();
        if l.contains("neoforge") { Loader::NeoForge }
        else if l.contains("forge") && !l.contains("fabric") { Loader::Forge }
        else if l.contains("quilt") { Loader::Quilt }
        else if l.contains("legacy") && l.contains("fabric") { Loader::LegacyFabric }
        else if l.contains("fabric") { Loader::Fabric }
        else { Loader::Vanilla }
    }

    /// The label stored in an instance's `loader` field (round-trips through `from_label`).
    pub fn label(self) -> &'static str {
        match self { Loader::Vanilla => "Vanilla", Loader::Fabric => "Fabric", Loader::Quilt => "Quilt", Loader::LegacyFabric => "Legacy Fabric", Loader::Forge => "Forge", Loader::NeoForge => "NeoForge" }
    }

    /// Modrinth loader facet, None for vanilla.
    pub fn modrinth(self) -> Option<&'static str> {
        match self {
            Loader::Vanilla => None,
            Loader::Fabric | Loader::LegacyFabric => Some("fabric"),
            Loader::Quilt => Some("quilt"),
            Loader::Forge => Some("forge"),
            Loader::NeoForge => Some("neoforge"),
        }
    }

    /// The Fabric-family meta flavor, None for the others.
    pub fn flavor(self) -> Option<crate::fabric::Flavor> {
        match self {
            Loader::Fabric => Some(crate::fabric::Flavor::Fabric),
            Loader::Quilt => Some(crate::fabric::Flavor::Quilt),
            Loader::LegacyFabric => Some(crate::fabric::Flavor::LegacyFabric),
            _ => None,
        }
    }
}

/// File in an instance folder that pins the Fabric/Quilt loader version (written by modpack import; absent = newest).
pub const LOADER_PIN: &str = "nether-loader.txt";

/// A loader version as loader metas write them ("0.16.9", "0.26.4-beta.5"); it ends up in meta URLs, so nothing else.
pub fn valid_loader_version(v: &str) -> bool {
    (1..=48).contains(&v.len()) && v.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'+' | b'_')) && !v.contains("..")
}

pub struct LaunchOpts {
    pub loader: Loader,
    pub version: String,
    pub game_dir: PathBuf,
    pub session: Session,
    pub ram_mb: u32,
    /// Force a Java major (8/17/21/25); None = what the version asks for.
    pub java_major: Option<u32>,
    /// Instance-specific client config (only when the instance opts out of the global one).
    pub client_config: Option<PathBuf>,
    /// Inject the Nether client at all, and which module groups (None = all).
    pub client: bool,
    pub groups: Option<String>,
    /// Extra JVM arguments (per instance). A GC flag here replaces the default G1 tuning.
    pub extra_jvm: Vec<String>,
    /// Window size (width, height); None = the game's default.
    pub window: Option<(u32, u32)>,
    pub fullscreen: bool,
    /// Jump straight into a world or server.
    pub quick_play: Option<QuickPlay>,
    /// Set to stop the preparation (downloads); `launch` then fails with `download::CANCELLED`.
    pub cancel: Option<std::sync::Arc<std::sync::atomic::AtomicBool>>,
    /// Run with OptiFine (Vanilla on 1.8.9 / 1.12.2): downloaded and set up on the first launch (see `optifine`).
    pub optifine: bool,
}

/// Where the game goes right after the title screen.
#[derive(Clone, Debug, PartialEq)]
pub enum QuickPlay {
    /// A singleplayer world by folder name (1.20+).
    World(String),
    /// A server address "host[:port]".
    Server(String),
}

/// Does this version know the Quick Play arguments (1.20, 23w14a and newer)?
pub fn supports_quick_play(v: &VersionJson) -> bool {
    v.arguments.as_ref().map(|a| a.game.iter().any(|g| match g {
        Arg::Plain(s) => s.contains("quickPlay"),
        Arg::Ruled { value, .. } => value.to_string().contains("quickPlay"),
    })).unwrap_or(false)
}

/// Game arguments for Quick Play; older versions can still join a server with `--server/--port`.
pub fn quick_play_args(q: &QuickPlay, modern: bool) -> Vec<String> {
    match (q, modern) {
        (QuickPlay::World(w), true) => vec!["--quickPlaySingleplayer".into(), w.clone()],
        (QuickPlay::World(_), false) => vec![],
        (QuickPlay::Server(s), true) => vec!["--quickPlayMultiplayer".into(), s.clone()],
        (QuickPlay::Server(s), false) => {
            let (host, port) = s.rsplit_once(':').filter(|(_, p)| p.parse::<u16>().is_ok()).unwrap_or((s.as_str(), "25565"));
            vec!["--server".into(), host.into(), "--port".into(), port.into()]
        }
    }
}

/// Windows "Graphics performance preference" = High performance for this Java, unless the user already chose one for
/// it (Settings > System > Display > Graphics). On a laptop with integrated + dedicated graphics the game otherwise
/// often lands on the integrated GPU. Per user and per program, the same value Windows' own settings page writes.
#[cfg(windows)]
fn prefer_fast_gpu(exe: &std::path::Path) {
    use windows_sys::Win32::System::Registry::{RegCloseKey, RegCreateKeyExW, RegQueryValueExW, RegSetValueExW, HKEY, HKEY_CURRENT_USER, KEY_READ, KEY_WRITE, REG_SZ};
    fn w(s: &str) -> Vec<u16> { s.encode_utf16().chain(Some(0)).collect() }
    let name = w(&exe.to_string_lossy());
    let mut key: HKEY = std::ptr::null_mut();
    unsafe {
        if RegCreateKeyExW(HKEY_CURRENT_USER, w(r"Software\Microsoft\DirectX\UserGpuPreferences").as_ptr(), 0, std::ptr::null(), 0, KEY_READ | KEY_WRITE, std::ptr::null(), &mut key, std::ptr::null_mut()) != 0 { return; }
        let exists = RegQueryValueExW(key, name.as_ptr(), std::ptr::null(), std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut()) == 0;
        if !exists {
            let v = w("GpuPreference=2;");
            RegSetValueExW(key, name.as_ptr(), 0, REG_SZ, v.as_ptr() as *const u8, (v.len() * 2) as u32);
        }
        RegCloseKey(key);
    }
}

/// Opts the game out of Windows power throttling (EcoQoS: efficiency cores, lower clocks), which Windows 11 applies to
/// processes it thinks are in the background. Microsoft: StateMask 0 with the execution-speed bit in ControlMask = always
/// high QoS ("SetProcessInformation", ProcessPowerThrottling). Ignored where unsupported.
#[cfg(windows)]
fn full_speed(child: &Child) {
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::System::Threading::{ProcessPowerThrottling, SetProcessInformation, PROCESS_POWER_THROTTLING_CURRENT_VERSION, PROCESS_POWER_THROTTLING_EXECUTION_SPEED, PROCESS_POWER_THROTTLING_STATE};
    let st = PROCESS_POWER_THROTTLING_STATE { Version: PROCESS_POWER_THROTTLING_CURRENT_VERSION, ControlMask: PROCESS_POWER_THROTTLING_EXECUTION_SPEED, StateMask: 0 };
    unsafe { SetProcessInformation(child.as_raw_handle() as _, ProcessPowerThrottling, &st as *const _ as *const _, std::mem::size_of::<PROCESS_POWER_THROTTLING_STATE>() as u32); }
}

/// The vanilla launcher's G1 tuning, unless the user picked a collector themselves.
pub fn gc_args(extra: &[String]) -> Vec<String> {
    let custom = extra.iter().any(|a| a.starts_with("-XX:+Use") && a.ends_with("GC"));
    if custom { return vec![]; }
    ["-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:G1NewSizePercent=20", "-XX:G1ReservePercent=20", "-XX:MaxGCPauseMillis=50", "-XX:G1HeapRegionSize=32M"]
        .iter().map(|s| s.to_string()).collect()
}

/// JIT and runtime tuning for every Java the game runs on (the user's own arguments come after these and win).
/// Huge methods are compiled (Minecraft's render loops are past HotSpot's 8000-byte limit and otherwise stay
/// interpreted), a larger code cache with C2 allowed bigger inlining graphs (brucethemoose/Minecraft-Performance-Flags-
/// Benchmarks), System.gc() from the game (1.8.9 on every world change) runs concurrently instead of a full pause, no
/// hsperfdata file, and from Java 24 compact 8-byte object headers (JEP 519: smaller heap, fewer collections).
/// IgnoreUnrecognizedVMOptions keeps a custom or older Java starting even if it lacks one of them.
pub fn perf_args(major: u32) -> Vec<String> {
    let mut a: Vec<String> = ["-XX:+IgnoreUnrecognizedVMOptions", "-XX:+UnlockExperimentalVMOptions", "-XX:+UnlockDiagnosticVMOptions", "-XX:-DontCompileHugeMethods",
        "-XX:ReservedCodeCacheSize=400M", "-XX:MaxNodeLimit=240000", "-XX:NodeLimitFudgeFactor=8000", "-XX:+ExplicitGCInvokesConcurrent",
        "-XX:+AlwaysActAsServerClassMachine", "-XX:+PerfDisableSharedMem"].iter().map(|s| s.to_string()).collect();
    if major >= 24 { a.push("-XX:+UseCompactObjectHeaders".into()); }
    a
}

/// Split a user-typed argument line, keeping "quoted parts" together.
pub fn split_args(s: &str) -> Vec<String> {
    let (mut out, mut cur, mut q) = (vec![], String::new(), false);
    for ch in s.chars() {
        match ch {
            '"' => q = !q,
            c if c.is_whitespace() && !q => { if !cur.is_empty() { out.push(std::mem::take(&mut cur)); } }
            c => cur.push(c),
        }
    }
    if !cur.is_empty() { out.push(cur); }
    out
}

#[cfg(test)]
mod arg_tests {
    use super::*;

    #[test]
    fn quick_play_and_gc() {
        assert_eq!(quick_play_args(&QuickPlay::Server("mc.example.net".into()), false), ["--server", "mc.example.net", "--port", "25565"]);
        assert_eq!(quick_play_args(&QuickPlay::Server("1.2.3.4:25570".into()), false), ["--server", "1.2.3.4", "--port", "25570"]);
        assert_eq!(quick_play_args(&QuickPlay::World("New World".into()), true), ["--quickPlaySingleplayer", "New World"]);
        assert!(quick_play_args(&QuickPlay::World("w".into()), false).is_empty());
        assert!(gc_args(&["-XX:+UseZGC".into()]).is_empty());
        assert!(perf_args(25).contains(&"-XX:+UseCompactObjectHeaders".to_string()) && !perf_args(21).iter().any(|a| a.contains("CompactObjectHeaders")));
        assert!(gc_args(&["-Dfoo=1".into()]).contains(&"-XX:+UseG1GC".to_string()));
        assert_eq!(split_args(r#"-Xss2M  "-Dname=a b" -Dx"#), ["-Xss2M", "-Dname=a b", "-Dx"]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loader_labels() {
        for l in [Loader::Vanilla, Loader::Fabric, Loader::Quilt, Loader::LegacyFabric, Loader::Forge, Loader::NeoForge] { assert_eq!(Loader::from_label(l.label()), l); }
        assert_eq!(Loader::from_label("Legacy Fabric"), Loader::LegacyFabric);
        assert!(pre_fabric("1.8.9") && pre_fabric("1.12.2") && pre_fabric("1.13"));
        assert!(!pre_fabric("1.14") && !pre_fabric("1.21.11") && !pre_fabric("26.3") && !pre_fabric("25w14a"));
        assert_eq!(Loader::from_label("quilt"), Loader::Quilt);
        assert_eq!(Loader::LegacyFabric.modrinth(), Some("fabric"));
        assert_eq!(Loader::Quilt.modrinth(), Some("quilt"));
    }
}

/// Mojang runtime component for a Java major.
fn java_component(major: u32) -> &'static str {
    java::component(major)
}

const LAUNCHER_NAME: &str = "NetherClient";
const LAUNCHER_VERSION: &str = env!("CARGO_PKG_VERSION");

fn natives_key(v: &mojang::Library) -> Option<String> {
    v.natives.as_ref()?.get("windows").map(|s| s.replace("${arch}", "64"))
}

/// Download everything a version needs (client jar, libraries, natives, assets) and return the classpath parts.
fn install(c: &reqwest::blocking::Client, paths: &Paths, v: &VersionJson, progress: &Progress, cancel: Option<&std::sync::atomic::AtomicBool>) -> Result<(Vec<PathBuf>, PathBuf)> {
    install_mode(c, paths, v, progress, cancel, None)
}

/// `repaired` = verify mode: assets are checked by hash too, natives rewritten, and replaced files counted.
fn install_mode(c: &reqwest::blocking::Client, paths: &Paths, v: &VersionJson, progress: &Progress, cancel: Option<&std::sync::atomic::AtomicBool>, repaired: Option<&AtomicUsize>) -> Result<(Vec<PathBuf>, PathBuf)> {
    // verify mode: note a file that is missing or fails its check before fetch replaces it
    let get = |url: &str, dest: &std::path::Path, check: Check| -> Result<()> {
        if let Some(r) = repaired { if crate::download::is_good(dest, check) { return Ok(()); } r.fetch_add(1, Ordering::Relaxed); }
        fetch(c, url, dest, check)
    };
    let vdir = paths.versions().join(&v.id);
    let client_jar = vdir.join(format!("{}.jar", v.id));
    progress("Downloading game", 0.02);
    get(&v.downloads.client.url, &client_jar, Check::Sha1(&v.downloads.client.sha1))?;

    // libraries
    struct Job { url: String, dest: PathBuf, sha1: String, natives: bool }
    let mut jobs: Vec<Job> = vec![];
    for lib in v.libraries.iter().filter(|l| rules_allow(&l.rules)) {
        let Some(d) = &lib.downloads else {
            if let Some(base) = &lib.url {
                let base = if base.ends_with('/') { base.clone() } else { format!("{base}/") };
                if let Some(key) = natives_key(lib) {
                    // natives-only Maven library (Legacy Fabric's lwjgl-platform): just the classifier jar
                    let path = mojang::maven_path(&format!("{}:{key}", lib.name));
                    jobs.push(Job { url: format!("{base}{path}"), dest: paths.libraries().join(&path), sha1: String::new(), natives: true });
                } else if lib.natives.is_none() {
                    let path = mojang::maven_path(&lib.name);
                    jobs.push(Job { url: format!("{base}{path}"), dest: paths.libraries().join(&path), sha1: lib.sha1.clone().unwrap_or_default(), natives: false });
                }
            }
            continue;
        };
        if let Some(a) = &d.artifact {
            let path = a.path.clone().unwrap_or_else(|| lib.name.replace(':', "/"));
            jobs.push(Job { url: a.url.clone(), dest: paths.libraries().join(path), sha1: a.sha1.clone(), natives: false });
        }
        if let (Some(key), Some(cl)) = (natives_key(lib), &d.classifiers) {
            if let Some(a) = cl.get(&key) {
                let path = a.path.clone().unwrap_or_else(|| format!("{}-{key}.jar", lib.name.replace(':', "/")));
                jobs.push(Job { url: a.url.clone(), dest: paths.libraries().join(path), sha1: a.sha1.clone(), natives: true });
            }
        }
    }
    let pool = rayon::ThreadPoolBuilder::new().num_threads(16).build()?;
    let done = AtomicUsize::new(0);
    let total = jobs.len().max(1);
    progress("Downloading libraries", 0.05);
    pool.install(|| {
        jobs.par_iter().try_for_each(|j| -> Result<()> {
            check_cancel(cancel)?;
            // no URL = built locally by the loader's installer (Forge/NeoForge), after this step
            if !j.url.is_empty() { get(&j.url, &j.dest, if j.sha1.is_empty() { Check::None } else { Check::Sha1(&j.sha1) })?; }
            let n = done.fetch_add(1, Ordering::Relaxed) + 1;
            progress("Downloading libraries", 0.05 + 0.15 * n as f32 / total as f32);
            Ok(())
        })
    })?;

    // natives (legacy classifier jars) -> versions/<id>/natives
    let natives_dir = vdir.join("natives");
    fs::create_dir_all(&natives_dir)?;
    for j in jobs.iter().filter(|j| j.natives) {
        let mut zip = zip::ZipArchive::new(fs::File::open(&j.dest)?)?;
        for i in 0..zip.len() {
            let mut f = zip.by_index(i)?;
            let name = f.name()?.to_string();
            if f.is_dir() || name.starts_with("META-INF") { continue; }
            let out = natives_dir.join(PathBuf::from(&name).file_name().ok_or_else(|| anyhow!("bad native entry"))?);
            // a DLL cut off mid-write (crash, full disk) has the wrong size: write it again
            if repaired.is_some() || fs::metadata(&out).map(|m| m.len()).ok() != Some(f.size()) {
                let mut buf = vec![];
                f.read_to_end(&mut buf)?;
                crate::store::write_atomic(&out, &buf)?;
            }
        }
    }

    // assets
    let idx_path = paths.assets().join("indexes").join(format!("{}.json", v.asset_index.id));
    get(&v.asset_index.url, &idx_path, Check::Sha1(&v.asset_index.sha1))?;
    let idx: AssetIndex = serde_json::from_slice(&fs::read(&idx_path)?)?;
    let objs: Vec<_> = idx.objects.values().collect();
    let done = AtomicUsize::new(0);
    let total = objs.len().max(1);
    pool.install(|| {
        objs.par_iter().try_for_each(|o| -> Result<()> {
            check_cancel(cancel)?;
            let pre = o.hash.get(..2).ok_or_else(|| anyhow!("bad asset hash"))?;
            let dest = paths.assets().join("objects").join(pre).join(&o.hash);
            get(&format!("https://resources.download.minecraft.net/{pre}/{}", o.hash), &dest, if repaired.is_some() { Check::Sha1(&o.hash) } else { Check::Size(o.size) })?;
            let n = done.fetch_add(1, Ordering::Relaxed) + 1;
            if n.is_multiple_of(25) || n == total { progress("Downloading assets", 0.2 + 0.7 * n as f32 / total as f32); }
            Ok(())
        })
    })?;

    // classpath: allowed libs (excluding legacy natives) + client jar
    let mut cp: Vec<PathBuf> = jobs.iter().filter(|j| !j.natives).map(|j| j.dest.clone()).collect();
    cp.push(client_jar);
    Ok((cp, natives_dir))
}

fn subst(s: &str, vars: &HashMap<&str, String>) -> String {
    let mut out = s.to_string();
    for (k, v) in vars { out = out.replace(&format!("${{{k}}}"), v); }
    out
}

/// A release older than 1.14, where official Fabric has no builds (Legacy Fabric covers 1.3-1.13.2).
pub fn pre_fabric(version: &str) -> bool {
    let mut it = version.split('.');
    it.next() == Some("1") && it.next().and_then(|m| m.split(|c: char| !c.is_ascii_digit()).next()?.parse::<u32>().ok()).is_some_and(|m| m < 14)
}

/// The full version JSON for an instance (vanilla merged with its loader) and the loader's pending install step.
fn resolve(c: &reqwest::blocking::Client, paths: &Paths, loader: Loader, version: &str, game_dir: &std::path::Path, progress: &Progress) -> Result<(VersionJson, Option<crate::forge::Pending>)> {
    let v = mojang::version_json(c, paths, version)?;
    Ok(match loader {
        Loader::Vanilla => (v, None),
        Loader::Fabric | Loader::Quilt | Loader::LegacyFabric => {
            // Fabric itself starts at 1.14: a "Fabric" 1.8.9-1.13.2 instance runs on Legacy Fabric (same mods facet)
            let flavor = if loader == Loader::Fabric && pre_fabric(version) { crate::fabric::Flavor::LegacyFabric } else { loader.flavor().unwrap() };
            progress(&format!("Preparing {}", flavor.name()), 0.01);
            let pin = fs::read_to_string(game_dir.join(LOADER_PIN)).ok().map(|s| s.trim().to_string()).filter(|s| valid_loader_version(s));
            (crate::fabric::apply_flavor(c, flavor, v, version, pin.as_deref())?.0, None)
        }
        Loader::Forge | Loader::NeoForge => {
            let kind = if loader == Loader::Forge { crate::forge::Kind::Forge } else { crate::forge::Kind::NeoForge };
            progress(&format!("Preparing {}", kind.name()), 0.01);
            crate::forge::prepare(c, paths, v, version, kind)?
        }
    })
}

/// Verify and repair an instance's game files: every library, the client jar and every asset is hashed in full
/// (normal starts only check asset sizes) and anything missing or damaged is downloaded again; native DLLs are
/// rewritten. Returns how many files were replaced.
pub fn verify(paths: &Paths, loader: Loader, version: &str, game_dir: &std::path::Path, progress: Progress, cancel: Option<&std::sync::atomic::AtomicBool>) -> Result<usize> {
    let c = client();
    progress("Checking the version", 0.0);
    let (v, _) = resolve(&c, paths, loader, version, game_dir, &progress)?;
    let repaired = AtomicUsize::new(0);
    let shown: Progress = std::sync::Arc::new(move |m, f| progress(&m.replace("Downloading", "Checking"), f));
    install_mode(&c, paths, &v, &shown, cancel, Some(&repaired))?;
    Ok(repaired.load(Ordering::Relaxed))
}

/// Install (if needed) and start Minecraft. Returns the running process.
pub fn launch(paths: &Paths, opts: &LaunchOpts, progress: Progress) -> Result<Child> {
    let c = client();
    progress("Preparing", 0.0);
    let (v, pending) = resolve(&c, paths, opts.loader, &opts.version, &opts.game_dir, &progress)?;
    let of = if opts.optifine && opts.loader == Loader::Vanilla { Some(crate::optifine::prepare(&c, paths, &opts.version, &progress)?) } else { None };
    let v = match &of { Some(s) => crate::optifine::apply(v, s)?, None => v };
    let cancel = opts.cancel.as_deref();
    check_cancel(cancel)?;
    let (cp, natives) = install(&c, paths, &v, &progress, cancel)?;

    let (component, major) = match opts.java_major {
        Some(m) => (java_component(m).to_string(), m),
        None => v.java.as_ref().map(|j| (j.component.clone(), j.major)).unwrap_or(("jre-legacy".into(), 8)),
    };
    let java = java::find_or_install(&c, paths, &component, major, &progress, cancel)?;
    check_cancel(cancel)?;
    if let (Some(p), Some(jar)) = (pending, cp.last()) { p.run(&c, paths, &java, jar, &progress)?; }
    // OptiFine's Patcher needs this version's client jar (the last classpath entry) and Java
    if let (Some(s), Some(jar)) = (&of, cp.last()) { crate::optifine::patch(paths, s, &java, jar, &progress)?; }

    check_cancel(cancel)?;
    progress("Starting Minecraft", 0.95);
    fs::create_dir_all(&opts.game_dir)?;
    let sep = ";";
    let classpath = cp.iter().map(|p| p.to_string_lossy().to_string()).collect::<Vec<_>>().join(sep);
    let mut vars: HashMap<&str, String> = HashMap::new();
    vars.insert("auth_player_name", opts.session.name.clone());
    vars.insert("version_name", v.id.clone());
    vars.insert("game_directory", opts.game_dir.to_string_lossy().to_string());
    vars.insert("assets_root", paths.assets().to_string_lossy().to_string());
    vars.insert("assets_index_name", v.asset_index.id.clone());
    vars.insert("auth_uuid", opts.session.uuid.clone());
    vars.insert("auth_access_token", opts.session.access_token.clone());
    vars.insert("auth_xuid", String::new());
    vars.insert("clientid", String::new());
    vars.insert("user_type", opts.session.user_type.clone());
    vars.insert("version_type", if v.kind.is_empty() { "release".into() } else { v.kind.clone() });
    vars.insert("user_properties", "{}".into());
    vars.insert("natives_directory", natives.to_string_lossy().to_string());
    vars.insert("launcher_name", LAUNCHER_NAME.into());
    vars.insert("launcher_version", LAUNCHER_VERSION.into());
    vars.insert("classpath", classpath.clone());
    vars.insert("classpath_separator", sep.into());
    vars.insert("library_directory", paths.libraries().to_string_lossy().to_string());
    vars.insert("game_assets", paths.assets().join("virtual").join("legacy").to_string_lossy().to_string());

    let mut jvm: Vec<String> = vec![format!("-Xmx{}M", opts.ram_mb), "-Xms512M".into()];
    jvm.extend(gc_args(&opts.extra_jvm));
    // dev benchmarks: NETHER_STOCK=1 launches like a stock launcher (no JIT tuning, normal priority, Windows defaults)
    let stock = cfg!(feature = "dev") && std::env::var_os("NETHER_STOCK").is_some();
    if !stock { jvm.extend(perf_args(major)); }
    jvm.extend(opts.extra_jvm.iter().cloned());
    // Log4Shell: no message lookups or JNDI codebases on any version, plus Mojang's patched log4j config when the
    // version ships one (needed on 1.7 to 1.18.1, where the lookup flag alone is not honoured by old log4j)
    jvm.extend(["-Dlog4j2.formatMsgNoLookups=true", "-Dlog4j2.disable.jmx=true", "-Dcom.sun.jndi.ldap.object.trustURLCodebase=false", "-Dcom.sun.jndi.rmi.object.trustURLCodebase=false"].map(String::from));
    if let Some(l) = v.logging.as_ref().and_then(|l| l.client.as_ref()) {
        let id = l.file.id.rsplit(['/', '\\']).next().unwrap_or("log4j.xml");
        if l.file.url.starts_with("https://") && !id.is_empty() && id != ".." {
            let dest = paths.assets().join("log_configs").join(id);
            if crate::download::fetch(&c, &l.file.url, &dest, Check::Sha1(&l.file.sha1)).is_ok() {
                jvm.push(l.argument.replace("${path}", &dest.to_string_lossy()));
            }
        }
    }
    let mut game: Vec<String> = vec![];
    if let Some(a) = &v.arguments {
        let flat = |args: &Vec<Arg>| -> Vec<String> { args.iter().flat_map(|a| a.values()).map(|s| subst(&s, &vars)).collect() };
        jvm.extend(flat(&a.jvm));
        game.extend(flat(&a.game));
    } else {
        jvm.push(format!("-Djava.library.path={}", natives.display()));
        jvm.push("-cp".into());
        jvm.push(classpath.clone());
        if let Some(l) = &v.legacy_args {
            game.extend(l.split_whitespace().map(|s| subst(s, &vars)));
        }
    }
    if let Some((w, h)) = opts.window { game.extend(["--width".into(), w.to_string(), "--height".into(), h.to_string()]); }
    if opts.fullscreen { game.push("--fullscreen".into()); }
    if let Some(q) = &opts.quick_play { game.extend(quick_play_args(q, supports_quick_play(&v))); }

    // client agent; dev builds may point NETHER_AGENT at another jar, NETHER_SNAPSHOT=out.png dumps a frame
    // The bundled agent targets Java 8, so every version (LWJGL2 and LWJGL3) gets it.
    let dev_agent = if cfg!(feature = "dev") { std::env::var("NETHER_AGENT").ok() } else { None };
    let agent = match dev_agent {
        Some(a) if std::path::Path::new(&a).exists() => Some(PathBuf::from(a)),
        _ if !opts.client => None,
        _ => crate::agent::ensure(paths),
    };
    if let Some(a) = agent {
        if let Some(g) = &opts.groups { jvm.insert(0, format!("-Dnether.groups={g}")); }
        jvm.insert(0, format!("-Dnether.config={}", crate::agent::global_config(paths).display()));
        crate::agent::ensure_pack(&opts.game_dir, if v.legacy_args.is_some() { cp.last().map(|p| p.as_path()) } else { None });
        // Nether capes (backend content + cape API); unset = the agent skips capes. The agent looks up the players
        // it sees (UUIDs only) at the API, so Settings > Privacy can turn capes off (config.json launcher.capes).
        let capes = crate::store::config(paths)["launcher"]["capes"].as_bool().unwrap_or(true);
        let (content, api) = if capes { (crate::backend::content_url(), crate::backend::api_url()) } else { (String::new(), String::new()) };
        if !content.is_empty() { jvm.insert(0, format!("-Dnether.capeContent={content}")); }
        if !api.is_empty() { jvm.insert(0, format!("-Dnether.capeApi={api}")); }
        // the agent only accepts cape PNGs whose sha256 is in the signed manifest (none verified = no capes)
        if !content.is_empty() {
            if let Some(f) = crate::backend::cape_sums_file(&c) { jvm.insert(0, format!("-Dnether.capeSums={}", f.display())); }
            if let Some(f) = crate::backend::cosmetic_sums_file(&c) { jvm.insert(0, format!("-Dnether.cosSums={}", f.display())); }
        }
        // check in as a Nether player so others see the nametag icon (background, best effort; the API never gets the
        // Minecraft token, see backend::hello)
        if opts.session.user_type == "msa" && !api.is_empty() {
            let session = opts.session.clone();
            std::thread::spawn(move || { let _ = crate::backend::hello(&crate::download::client(), &session); });
        }
        if let Some(i) = &opts.client_config { jvm.insert(0, format!("-Dnether.instanceConfig={}", i.display())); }
        jvm.insert(0, format!("-javaagent:{}", a.display()));
    }
    // dev only: the agent writes a frame to this path
    if cfg!(feature = "dev") { if let Ok(s) = std::env::var("NETHER_SNAPSHOT") { jvm.insert(0, format!("-Dnether.snapshot={s}")); } }

    let log_dir = opts.game_dir.join("logs");
    fs::create_dir_all(&log_dir)?;
    let log = fs::File::create(log_dir.join("launcher.log")).context("creating launcher.log")?;
    #[cfg(windows)]
    use std::os::windows::process::CommandExt;
    let mut cmd = Command::new(&java);
    #[cfg(windows)]
    {
        // no console window; above-normal priority so background programs don't take the game's CPU time
        cmd.creation_flags(if stock { 0x0800_0000 } else { 0x0800_0000 | 0x0000_8000 });
        if !stock { prefer_fast_gpu(&java); }
    }
    let child = cmd
        .args(&jvm)
        .arg(&v.main_class)
        .args(&game)
        .current_dir(&opts.game_dir)
        .stdout(Stdio::from(log.try_clone()?))
        .stderr(Stdio::from(log))
        .spawn()
        .with_context(|| format!("starting {}", java.display()))?;
    #[cfg(windows)]
    if !stock { full_speed(&child); }
    progress("Minecraft started", 1.0);
    Ok(child)
}
