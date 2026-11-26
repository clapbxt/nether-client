# Repository layout

This repo is the launcher. The in-game client is in [clapbxt/nether-agent](https://github.com/clapbxt/nether-agent), with its own layout in its README.

```
crates/
  app/          launcher: src/*.rs (one file per area), ui/*.slint (theme, components, one file per screen)
  ui/           compiles crates/app/ui into its own crate, so app changes don't rebuild the generated UI
  common/       nether-common: data folder paths, safe JSON files, Recycle Bin, Progress, Session (no other crate deps)
  net/          nether-net: HTTP downloads with hash checks, UPnP / public IP, launcher auto-update; res/ = release key
  auth/         nether-auth: Microsoft account sign-in and the launch ownership gate
  game/         nether-game: installing the game (Mojang files, Java, Fabric, Forge, OptiFine, options, packs)
  social/       nether-social: parties and peer-to-peer links, cosmetic models, skins
  core/         nether-core: the rest of the launcher logic, no UI, and a facade re-exporting the crates above under
                their old module names; res/ = content key and resource pack; examples/ = dev harnesses
  media/        nether-media.exe (Windows media session helper)
  installer/    NetherClientSetup.exe (setup wizard, uninstall, update applier)
art/            brand art used by the README, launcher and site
docs/           this documentation
client.lock     the in-game client release bundled as the offline fallback (crates/core/build.rs)
.github/        CI (build and test), dependency audit, Dependabot
```

The website (`site/`) and the icon sources (`design/icon`, `design/module-icons`) are referenced by the build and the
Pages workflow but are not in the public repository yet.

## Top-level documents

| File | For |
|---|---|
| `README.md` | What Nether Client is, and where to go next |
| `CONTRIBUTING.md` | Reporting bugs and sending pull requests |
| `SAFETY.md` | Fair play, for players and for anticheat developers |
| `PRIVACY.md` | Every network destination |
| `TERMS.md`, `LICENSE`, `NOTICE.md` | Legal and third-party notices |
| `SECURITY.md` | Reporting security problems |
