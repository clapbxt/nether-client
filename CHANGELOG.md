# Changelog

What changed in each release, newest first. Add a line under **Unreleased** in the same pull request as the change:
what a player or developer notices, not how it was done. The commit messages hold the details. At release, the
Unreleased lines move under the new version (see [Releasing](docs/develop/releasing.md)).

The launcher and the in-game client are listed separately because they are planned to ship on separate release
trains (the launcher will fetch newer client builds on its own).

## Unreleased

### In-game client
- Fixed: Minecraft 1.13 could crash when drawing the overlay. The overlay called an OpenGL function through a
  class that LWJGL 3.1.6 doesn't have.

### Development
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
