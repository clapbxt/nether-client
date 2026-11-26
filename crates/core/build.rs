//! Bundles a build of the in-game client (repo github.com/clapbxt/nether-agent) into the launcher, as the offline
//! fallback: the launcher itself installs newer signed client releases at runtime (src/agent.rs). See [`client_dir`]
//! for where the build comes from. None found -> empty placeholders, and the game then launches without a client
//! until the launcher downloads one; a Windows release build fails instead.
//! The now-playing helper (`crates/media` -> nether-media.exe) is built here, so a release build never ships an
//! empty one: Windows release builds fail when it can't be built.
use std::{env, fs, path::{Path, PathBuf}, process::Command};

const CLIENT_REPO: &str = "clapbxt/nether-agent";
const CLIENT_FILES: [&str; 3] = ["nether-agent.jar", "nether-agent-boot.jar", "client.json"];

fn main() {
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let out = PathBuf::from(env::var("OUT_DIR").unwrap());
    let windows = env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows");
    let release = env::var("PROFILE").as_deref() == Ok("release");
    let src = client_dir(&manifest, release);
    match &src {
        Some(d) => println!("cargo:warning=bundling the in-game client from {}", d.display()),
        None if windows && release => panic!("no in-game client build to bundle: pin a release in client.lock, build the client repo next to this one, or set NETHER_CLIENT_BUILD"),
        None => println!("cargo:warning=no in-game client build found; this build bundles none (see crates/core/build.rs)"),
    }
    for (name, empty) in [("nether-agent.jar", &b""[..]), ("nether-agent-boot.jar", b""), ("client.json", b"{}"), ("schema.json", br#"{"modules":[]}"#)] {
        let copied = src.as_ref().is_some_and(|d| fs::copy(d.join(name), out.join(name)).is_ok());
        if !copied { fs::write(out.join(name), empty).unwrap(); }
    }
    media(&manifest, &out);
    // tests that read the private backend's content (backend/, not in the public repo) only build when it is there
    println!("cargo::rustc-check-cfg=cfg(backend_content)");
    let content = manifest.join("../../backend/content/manifest.json");
    println!("cargo:rerun-if-changed={}", content.display());
    if content.exists() { println!("cargo:rustc-cfg=backend_content"); }
}

/// nether-media.exe for the agent's Media module. Built with a nested cargo into its own target dir
/// (`target/media`, so it never waits on this build's lock), release profile, whenever its sources change.
/// Windows release: a failed build stops this build. Elsewhere / debug: an empty placeholder (the module then
/// says the helper is unavailable) with a warning.
fn media(manifest: &Path, out: &Path) {
    let dst = out.join("nether-media.exe");
    let windows = env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows");
    let release = env::var("PROFILE").as_deref() == Ok("release");
    let crate_dir = manifest.join("../media");
    for p in ["Cargo.toml", "src"] { println!("cargo:rerun-if-changed={}", crate_dir.join(p).display()); }
    println!("cargo:rerun-if-env-changed=NETHER_SKIP_MEDIA");
    if !windows || env::var_os("NETHER_SKIP_MEDIA").is_some() && !release {
        fs::write(&dst, b"").unwrap();
        return;
    }
    let target_dir = manifest.join("../../target/media");
    let cargo = env::var("CARGO").unwrap_or_else(|_| "cargo".into());
    let mut cmd = Command::new(cargo);
    cmd.args(["build", "-p", "nether-media", "--release", "--target-dir"]).arg(&target_dir)
        .arg("--manifest-path").arg(manifest.join("../../Cargo.toml"));
    // the outer build's settings are not the helper's (wrappers, flags for this crate, jobserver stays)
    for k in ["RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER", "CARGO_TARGET_DIR", "CARGO_BUILD_TARGET_DIR"] { cmd.env_remove(k); }
    let built = target_dir.join("release/nether-media.exe");
    let ok = cmd.status().map(|s| s.success()).unwrap_or(false) && fs::metadata(&built).map(|m| m.len() > 0).unwrap_or(false);
    if ok {
        fs::copy(&built, &dst).unwrap();
    } else if release {
        panic!("nether-media.exe could not be built (cargo build -p nether-media --release); a release must not ship without it");
    } else {
        println!("cargo:warning=nether-media.exe not built; this debug build bundles an empty placeholder");
        fs::write(&dst, b"").unwrap();
    }
}

/// The folder holding the client build to bundle (`nether-agent.jar`, `nether-agent-boot.jar`, `client.json`):
/// 1. `NETHER_CLIENT_BUILD`, if set;
/// 2. a release build: the release pinned in `client.lock` (downloaded once into `target/client/<version>/` and
///    checked against its sha256), so players get exactly that release;
/// 3. the client repo checked out next to this one, built (`../nether_agent/build` or `../nether-agent/build`);
/// 4. a debug build: the pinned release, as in 2.
fn client_dir(manifest: &Path, release: bool) -> Option<PathBuf> {
    println!("cargo:rerun-if-env-changed=NETHER_CLIENT_BUILD");
    if let Some(d) = env::var_os("NETHER_CLIENT_BUILD").map(PathBuf::from) {
        if complete(&d) { return Some(d); }
        panic!("NETHER_CLIENT_BUILD={} doesn't hold {CLIENT_FILES:?}", d.display());
    }
    let root = manifest.join("../..");
    let sibling = ["nether_agent", "nether-agent"].iter().map(|n| root.join("..").join(n).join("build")).find(|d| {
        for f in CLIENT_FILES { println!("cargo:rerun-if-changed={}", d.join(f).display()); }
        complete(d)
    });
    let lock = root.join("client.lock");
    println!("cargo:rerun-if-changed={}", lock.display());
    let pinned = || pinned(&root, &lock);
    if release { pinned().or(sibling) } else { sibling.or_else(pinned) }
}

fn complete(d: &Path) -> bool { CLIENT_FILES.iter().all(|f| d.join(f).is_file()) }

/// The release named in `client.lock`, downloaded with curl into `target/client/<version>/` once. Every file is
/// checked against the sha256 in the lock, so a changed release asset never gets bundled.
fn pinned(root: &Path, lock: &Path) -> Option<PathBuf> {
    let text = fs::read_to_string(lock).ok()?;
    let mut version = None;
    let mut sums = std::collections::BTreeMap::new();
    for line in text.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#')) {
        let (k, v) = line.split_once('=')?;
        let (k, v) = (k.trim(), v.trim());
        if k == "version" { version = Some(v.to_string()); } else { sums.insert(k.to_string(), v.to_lowercase()); }
    }
    let version = version.filter(|v| !v.is_empty() && v.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-'))?;
    let dir = root.join("target").join("client").join(&version);
    fs::create_dir_all(&dir).ok()?;
    for f in CLIENT_FILES {
        let want = sums.get(f).unwrap_or_else(|| panic!("client.lock has no sha256 for {f}"));
        let p = dir.join(f);
        if sha256(&p).as_deref() == Some(want.as_str()) { continue; }
        let url = format!("https://github.com/{CLIENT_REPO}/releases/download/v{version}/{f}");
        let ok = Command::new("curl").args(["-fsSL", "--retry", "3", "-o"]).arg(&p).arg(&url).status().map(|s| s.success()).unwrap_or(false);
        if !ok { println!("cargo:warning=couldn't download {url}"); return None; }
        let got = sha256(&p);
        if got.as_deref() != Some(want.as_str()) {
            let _ = fs::remove_file(&p);
            panic!("{url} has sha256 {got:?}, client.lock pins {want}");
        }
    }
    Some(dir)
}

fn sha256(p: &Path) -> Option<String> {
    use sha2::Digest;
    Some(sha2::Sha256::digest(fs::read(p).ok()?).iter().map(|b| format!("{b:02x}")).collect())
}
