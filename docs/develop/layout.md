# Repository layout

```
crates/
  app/          launcher: src/*.rs (one file per area), ui/*.slint (theme, components, one file per screen)
  ui/           compiles crates/app/ui into its own crate, so app changes don't rebuild the generated UI
  common/       nether-common: data folder paths, safe JSON files, Recycle Bin, Progress, Session (no other crate deps)
  net/          nether-net: HTTP downloads with hash checks, UPnP / public IP, launcher auto-update; res/ = release key
  auth/         nether-auth: Microsoft account sign-in and the launch ownership gate
  core/         nether-core: the rest of the launcher logic, no UI, and a facade re-exporting the crates above under
                their old module names; res/ = content key and resource pack; examples/ = dev harnesses
  media/        nether-media.exe (Windows media session helper)
  installer/    NetherClientSetup.exe (setup wizard, uninstall, update applier)
agent/          in-game client
  src/dev/nether/agent   bootstrap and hooks
  src/dev/nether/ov      the overlay: platform/, game/, framework/, ui/, menu/ (title/), features/<area>/
  res/                   fonts, icons, name tables, brand art, panoramas
  tools/                 names.js (name tables), Relocate.java (bundles ASM as dev.nether.shaded.asm)
  build.gradle.kts       builds nether-agent.jar, nether-agent-boot.jar and schema.json into agent/build
  gradle/                libs.versions.toml (pinned LWJGL and ASM), the Gradle wrapper
  build.ps1              calls the Gradle build (kept for existing commands)
art/            brand art used by the README, launcher and site
docs/           this documentation
.github/        CI (build and test), dependency audit, Dependabot
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
