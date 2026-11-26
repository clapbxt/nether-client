# Repository layout

```
crates/
  app/          launcher: src/*.rs (one file per area), ui/*.slint (theme, components, one file per screen)
  ui/           compiles crates/app/ui into its own crate, so app changes don't rebuild the generated UI
  core/         launcher logic, no UI; res/ = embedded public keys and resource pack; examples/ = dev harnesses
  media/        nether-media.exe (Windows media session helper)
  installer/    NetherClientSetup.exe (setup wizard, uninstall, update applier)
agent/          in-game client
  src/dev/nether/agent   bootstrap and hooks
  src/dev/nether/ov      overlay, modules, menus, title screen, party, wardrobe
  res/                   fonts, icons, name tables, brand art, panoramas
  tools/                 names.js (name tables), Relocate.java
  build.ps1              builds nether-agent.jar and schema.json
art/            brand art used by the README, launcher and site
docs/           this documentation
.github/        dependency audit and website workflows, Dependabot
```

The website (`site/`) and the icon sources (`design/icon`, `design/module-icons`) are referenced by the build and the
Pages workflow but are not in the public repository yet.

## Top-level documents

| File | For |
|---|---|
| `README.md` | What Nether Client is, and where to go next |
| `CONTRIBUTING.md` | Reporting bugs and sending pull requests |
| `SAFETY.md`, `agent/SAFETY.md` | Fair play, for players and for anticheat developers |
| `PRIVACY.md` | Every network destination |
| `TERMS.md`, `LICENSE`, `NOTICE.md`, `agent/NOTICE.md` | Legal and third-party notices |
| `SECURITY.md` | Reporting security problems |
