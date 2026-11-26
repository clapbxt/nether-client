# Changelog

What changed in each release, newest first. Add a line under **Unreleased** in the same pull request as the change:
what a player or developer notices, not how it was done. The commit messages hold the details. At release, the
Unreleased lines move under the new version (see [Releasing](docs/develop/releasing.md)).

This is the launcher's changelog. The in-game client has its own, in
[clapbxt/nether-agent](https://github.com/clapbxt/nether-agent/blob/main/CHANGELOG.md).

## Unreleased

### Launcher
- The in-game client now updates by itself. When the launcher starts, it installs a newer client release into all
  your instances, so a client fix no longer needs a launcher update. Each instance keeps its own copy of the client.

### In-game client
- The client has its own version number (1.0.0) and a compatibility number, written into `client.json` by its build.
- Fixed: Minecraft 1.13 could crash when drawing the overlay. The overlay called an OpenGL function through a
  class that LWJGL 3.1.6 doesn't have.

### Development
- The in-game client moved to its own repo, [clapbxt/nether-agent](https://github.com/clapbxt/nether-agent), with its
  full history. This repo is the launcher only. Launcher builds bundle the client release pinned in `client.lock`, or
  a client built next to this repo (`..\nether_agent\build`). See [Building](docs/develop/building.md).
- The in-game client builds with Gradle (`agent\gradlew.bat -p agent build`), with LWJGL and ASM versions pinned.
  You no longer need to launch the game before the first build. `agent\build.ps1` still works and calls Gradle.
- The in-game client's code is split into packages: `platform`, `game`, `framework`, `ui`, `menu`, and one
  `features.*` package per feature.
- The launcher library is being split into crates: `nether-common`, `nether-net`, `nether-auth`, `nether-game` and
  `nether-social` are out of `nether-core`. `nether-core` re-exports them under their old names, so existing imports keep working.
- Windows-only launcher code lives in `nether_common::platform`.
- CI builds the in-game client, tests the launcher libraries on Linux, and type-checks the workspace on Windows on
  every push and pull request.

## 0.4.3 and earlier

See the [GitHub releases](https://github.com/clapbxt/nether-client/releases).
