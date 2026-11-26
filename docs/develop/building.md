# Building from source

Nether Client builds and runs on **Windows 10/11 x64** only.

A self-built launcher works for your own use. It can't replace itself with official updates unless it is signed with
the release key (see [Releases and signing](releasing.md)), and a few online features need the official service.

## Tools

| Tool | Version | Used for |
|---|---|---|
| Rust (MSVC toolchain) | stable | all crates |
| Visual Studio Build Tools 2022 | C++ workload + Windows SDK | linking, resources |
| JDK | 17 or newer | compiling the in-game client (its output targets Java 8) |
| Node.js | 18 or newer | `agent/tools/names.js`, only when updating name tables |

The in-game client compiles against the LWJGL and ASM jars the launcher downloads. Before the first agent build,
launch **Fabric 1.21.x**, **1.8.9** and **26.x** once from an installed Nether Client (or the
[dev harness](dev-tools.md#launch-the-game-without-the-launcher)) so those jars are in
`%APPDATA%\NetherClient\libraries`.

## Build order

Each output embeds the one before it, so the order matters:

```powershell
# 1. In-game client -> agent\build\nether-agent.jar + schema.json
powershell -File agent\build.ps1

# 2. Launcher (core's build.rs also builds and embeds nether-media.exe and the agent jar)
cargo build -p nether-app --release

# 3. Installer (embeds target\release\nether-app.exe)
cargo build -p nether-setup --release
```

Outputs: `target\release\nether-app.exe` and `target\release\NetherClientSetup.exe`.

### Build times

- The launcher's screens are compiled by their own crate (`crates/ui`), so changing app code or bumping the version
  doesn't recompile the ~30 MB of generated UI code.
- Release builds use per-crate optimisation without LTO for the same reason. See the comments in the root
  `Cargo.toml`.

## Run it

```powershell
cargo run -p nether-app --release
```

Your build uses the same data folder as an installed Nether Client (`%APPDATA%\NetherClient`), so it sees your
profiles and accounts.

## Check your change

```powershell
cargo test -p nether-core
cargo clippy --workspace --release
```

Rebuild the agent (`agent\build.ps1`) whenever you change anything under `agent/`. Content tests need the private
backend's content next to the repository and are skipped otherwise.

Next: [Tweaking and dev tools](dev-tools.md) for screenshots, scripted launches and more.
