//! Embeds the release launcher (`target/release/nether-app.exe`, deflated) and its version. Build the launcher first:
//! `cargo build -p nether-app --release` then `cargo build -p nether-setup --release` (tools/release.ps1 does both).
//! Without the launcher exe the setup still builds, with no payload, and says so when run.
use std::io::Write;
use std::path::{Path, PathBuf};

fn main() {
    slint_build::compile("ui/setup.slint").unwrap();
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("..").join("..");
    let toml = std::fs::read_to_string(root.join("crates/app/Cargo.toml")).expect("crates/app/Cargo.toml");
    let ver = toml.lines().find_map(|l| l.trim().strip_prefix("version = \"").and_then(|v| v.strip_suffix('"'))).expect("launcher version").to_string();
    println!("cargo:rerun-if-changed=../app/Cargo.toml");
    println!("cargo:rustc-env=NETHER_VERSION={ver}");

    let target = std::env::var_os("CARGO_TARGET_DIR").map(PathBuf::from).unwrap_or_else(|| root.join("target"));
    let exe = target.join("release").join("nether-app.exe");
    println!("cargo:rerun-if-changed={}", exe.display());
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("payload.bin");
    match std::fs::read(&exe) {
        Ok(bytes) => {
            let mut z = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::best());
            z.write_all(&bytes).unwrap();
            std::fs::write(&out, z.finish().unwrap()).unwrap();
            println!("cargo:rustc-env=NETHER_PAYLOAD_SIZE={}", bytes.len());
        }
        Err(_) => {
            println!("cargo:warning=no {} yet: this setup has no launcher inside (build nether-app --release first)", exe.display());
            std::fs::write(&out, []).unwrap();
            println!("cargo:rustc-env=NETHER_PAYLOAD_SIZE=0");
        }
    }

    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let mut res = winresource::WindowsResource::new();
        res.set_icon("../app/ui/assets/nether-icon.ico")
            .set("ProductName", "Nether Client")
            .set("FileDescription", "Nether Client Setup")
            .set("CompanyName", "Nether Client")
            .set("ProductVersion", &ver)
            // asInvoker: per-user install, never elevated (without a manifest Windows guesses "setup" = admin)
            .set_manifest(r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v3"><security><requestedPrivileges>
    <requestedExecutionLevel level="asInvoker" uiAccess="false"/>
  </requestedPrivileges></security></trustInfo>
  <application xmlns="urn:schemas-microsoft-com:asm.v3"><windowsSettings>
    <dpiAwareness xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">PerMonitorV2</dpiAwareness>
  </windowsSettings></application>
</assembly>"#);
        res.compile().expect("embed setup resources");
    }
}
