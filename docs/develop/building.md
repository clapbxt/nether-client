# Building from source

Nether Client builds and runs on **Windows 10/11 x64** only.

A self-built launcher works for your own use. It can't replace itself with official updates unless it is signed with
the release key (see [Releases and signing](releasing.md)), and a few online features need the official service.

## Tools

| Tool | Version | Used for |
|---|---|---|
| Rust (MSVC toolchain) | stable | all crates |
| Visual Studio Build Tools 2022 | C++ workload + Windows SDK | linking, resources |
| JDK | 17 or newer | building the in-game client with Gradle (its output targets Java 8) |
| Node.js | 18 or newer | `agent/tools/names.js`, only when updating name tables |

The in-game client is built with Gradle (`agent/build.gradle.kts`). The Gradle wrapper downloads Gradle itself, and
the LWJGL and ASM versions it compiles against are pinned in `agent/gradle/libs.versions.toml` and come from Maven
Central, so you don't need to launch the game first. Why each version is the one it is is written at the top of that
file.

## Build order

Each output embeds the one before it, so the order matters:

```powershell
# 1. In-game client -> agent\build\nether-agent.jar, nether-agent-boot.jar, schema.json
agent\gradlew.bat -p agent build      # or: powershell -File agent\build.ps1 (same thing)

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
cargo test -p nether-common -p nether-net -p nether-auth -p nether-core
cargo clippy --workspace --release
```

Rebuild the agent (`agent\gradlew.bat -p agent build`) whenever you change anything under `agent/`. Gradle only
rewrites the jars when an input changed, so an unchanged agent doesn't make the launcher rebuild. Content tests need
the private backend's content (`backend/content`) next to the repository; without it they are left out of the build.

The same checks run on every push and pull request in GitHub Actions (`.github/workflows/ci.yml`): the agent build,
the launcher libraries' tests on Linux, and a type check of the whole workspace on Windows.

Next: [Tweaking and dev tools](dev-tools.md) for screenshots, scripted launches and more.
