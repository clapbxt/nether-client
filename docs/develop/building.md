# Building from source

Nether Client builds and runs on **Windows 10/11 x64** only.

A self-built launcher works for your own use. It can't replace itself with official updates unless it is signed with
the release key (see [Releases and signing](releasing.md)), and a few online features need the official service.

## Tools

| Tool | Version | Used for |
|---|---|---|
| Rust (MSVC toolchain) | stable | all crates |
| Visual Studio Build Tools 2022 | C++ workload + Windows SDK | linking, resources |
| JDK | 17 or newer | only to build the in-game client yourself (its own repo) |

The in-game client lives in its own repo, [clapbxt/nether-agent](https://github.com/clapbxt/nether-agent), which has its own build instructions. The
launcher bundles one client build as the offline fallback (`crates/core/build.rs` picks it):

1. `NETHER_CLIENT_BUILD=<folder>`, if set (a folder with `nether-agent.jar`, `nether-agent-boot.jar`, `client.json`).
2. Release builds: the client release pinned in `client.lock`, downloaded once into `target\client\` and checked
   against its sha256.
3. The client repo checked out and built next to this one: `..\nether_agent\build` or `..\nether-agent\build`.
4. Debug builds: the pinned release, as in 2.

With none of these, a debug build bundles no client (the launcher still downloads one at runtime), and a Windows
release build stops with an error.

## Build order

Each output embeds the one before it, so the order matters:

```powershell
# 1. Launcher (core's build.rs also builds nether-media.exe and embeds it and the client build above)
cargo build -p nether-app --release

# 2. Installer (embeds target\release\nether-app.exe)
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
cargo test -p nether-common -p nether-net -p nether-auth -p nether-game -p nether-social -p nether-core
cargo clippy --workspace --release
```

To try a client change, build the client repo next to this one and rebuild the launcher, or point a dev build at it
with `NETHER_AGENT`. Content tests need the private backend's content (`backend/content`) in the repository folder;
without it they are left out of the build.

The same checks run on every push and pull request in GitHub Actions (`.github/workflows/ci.yml`): the launcher
libraries' tests on Linux and a type check of the whole workspace on Windows. The client repo has its own CI.

Next: [Tweaking and dev tools](dev-tools.md) for screenshots, scripted launches and more.
