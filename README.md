<p align="center"><img src="art/wide_banner.png" alt="Nether Client"></p>

<p align="center">
  <a href="https://github.com/clapbxt/nether-client/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/clapbxt/nether-client?label=release&color=e10600"></a>
  <a href="https://github.com/clapbxt/nether-client/actions/workflows/audit.yml"><img alt="Dependency audit" src="https://img.shields.io/github/actions/workflow/status/clapbxt/nether-client/audit.yml?label=audit"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-source--available-15151b"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Windows%2010%20%C2%B7%2011%20x64-15151b">
</p>

Nether Client is a Minecraft: Java Edition launcher with a built-in in-game client. One app to install Minecraft,
manage profiles, mods and modpacks, and play with a clean HUD, cosmetics, friends and parties.

- **Fast, native launcher.** Written in Rust with a [Slint](https://slint.dev) UI, no web view.
- **One client on every loader.** The in-game client isn't a mod, so it runs the same on Vanilla, Fabric, Forge and
  OptiFine, on 1.8.9, 1.12.2 and 1.14 or newer, and never clashes with Sodium or Iris.
- **Play together.** Friends, a party lobby with voice, and one-click invites into your singleplayer world.
- **Safe by design.** Fair-play modules only, no telemetry, signed updates, and backups before anything changes.

## Get it

Download the installer from [Releases](https://github.com/clapbxt/nether-client/releases/latest) or the
[website](https://clapbxt.github.io/nether-client/). Windows 10/11 x64. Help and community:
[Discord](https://discord.gg/R5vxAxVjwu).

## Documentation

All documentation is in [`docs/`](docs/README.md).

**Playing**

- [Install and first launch](docs/guide/install.md)
- [The launcher](docs/guide/launcher.md) · [Profiles](docs/guide/profiles.md)
- [In game](docs/guide/in-game.md) · [Module list](docs/guide/modules.md)
- [Friends, parties and world invites](docs/guide/friends.md) · [Skins, capes and cosmetics](docs/guide/wardrobe.md)
- [Troubleshooting and FAQ](docs/guide/troubleshooting.md)

**Building and contributing**

- [Building from source](docs/develop/building.md)
- [How it fits together](docs/develop/architecture.md) · [Repository layout](docs/develop/layout.md)
- [Tweaking and dev tools](docs/develop/dev-tools.md) · [Adding an in-game module](docs/develop/modules.md)
- [Releases and signing](docs/develop/releasing.md) · [Contributing](CONTRIBUTING.md)

This repository holds the launcher, the in-game client and the installer. The backend that serves signed content,
capes and cosmetics is private.

## Project rules

These apply to every change:

1. **Never touch the renderer.** Client hooks stay outside rendering code.
2. **Fair play.** Nothing that automates input, sends packets, or reads data the server did not send.
3. **Never block the UI.** Network and disk work run off the UI thread.
4. **Never break a user's instance or worlds.** Snapshot before changing files; prefer hiding a feature over crashing.
5. **No telemetry.** Every network destination is listed in [PRIVACY.md](PRIVACY.md); add new ones there.
6. **Smallest complete change.** No unrelated refactors in a pull request.

## Legal

- **License:** [Nether Client Source-Available License](LICENSE). You may use official builds and read, study and
  privately modify the source for personal, non-commercial use, and contribute changes back. You may not
  redistribute it or publish alternatives or derivatives. Third-party components: [NOTICE.md](NOTICE.md).
- **Terms of Service:** [TERMS.md](TERMS.md)
- **Privacy:** [PRIVACY.md](PRIVACY.md)
- **Fair play:** [SAFETY.md](SAFETY.md)
- **Security reports:** [SECURITY.md](SECURITY.md)

Nether Client is not an official Minecraft product. It is not approved by or associated with Mojang or Microsoft.
