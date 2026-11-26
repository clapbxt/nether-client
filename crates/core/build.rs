//! Bundles the built agent jars (agent/build, made by the Gradle build in agent/) into the launcher.
//! Missing jars -> empty placeholders, and the game then launches without the agent.
//! The now-playing helper (`crates/media` -> nether-media.exe) is built here, so a release build never ships an
//! empty one: Windows release builds fail when it can't be built.
use std::{env, fs, path::{Path, PathBuf}, process::Command};

fn main() {
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let src = manifest.join("../../agent/build");
    let out = PathBuf::from(env::var("OUT_DIR").unwrap());
    for name in ["nether-agent.jar", "nether-agent-boot.jar"] {
        let from = src.join(name);
        println!("cargo:rerun-if-changed={}", from.display());
        if fs::copy(&from, out.join(name)).is_err() {
            fs::write(out.join(name), b"").unwrap();
        }
    }
    // version, protocol and file hashes of the bundled build (crates/core/src/agent.rs)
    let from = src.join("client.json");
    println!("cargo:rerun-if-changed={}", from.display());
    if fs::copy(&from, out.join("client.json")).is_err() { fs::write(out.join("client.json"), b"{}").unwrap(); }
    // module/settings schema dumped by the agent build (the launcher's Client page)
    let from = src.join("schema.json");
    println!("cargo:rerun-if-changed={}", from.display());
    if fs::copy(&from, out.join("schema.json")).is_err() { fs::write(out.join("schema.json"), br#"{"modules":[]}"#).unwrap(); }
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
