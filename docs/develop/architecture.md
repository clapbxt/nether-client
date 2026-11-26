# How it fits together

```
NetherClientSetup.exe ──installs──▶ nether-app.exe (launcher, Slint UI)
                                        │  uses
                                        ▼
                                    nether-core (no UI): accounts, installs, launching, mods, hosting, backend clients
                                        │  starts java with
                                        ▼
                          Minecraft  +  -javaagent:<instance>/.nether/client/nether-agent.jar
                                        ▲
                                        └─ newest of: the build bundled into the launcher, or a signed client release
                                        │
                                        └─ nether-media.exe (Windows media session over stdio, exits with the game)
```

| Component | Folder | Language | Output |
|---|---|---|---|
| Launcher | `crates/app` | Rust + Slint | `nether-app.exe` |
| Launcher screens | `crates/ui` (compiles `crates/app/ui/*.slint`) | Slint | library |
| Core library | `crates/core` | Rust | library, used by the launcher and the dev examples |
| Shared base, network, accounts | `crates/common`, `crates/net`, `crates/auth` | Rust | libraries under `nether-core` |
| In-game client | `agent/` | Java (bytecode level 8) | `nether-agent.jar` + `schema.json` |
| Media helper | `crates/media` | Rust | `nether-media.exe` |
| Installer / updater | `crates/installer` | Rust + Slint | `NetherClientSetup.exe` |

## Launcher (`crates/app`)

One Rust module per screen or area in `src/` and one `.slint` file per screen in `ui/`. `ui/theme.slint` holds the
design tokens and `ui/components.slint` the shared widgets. Rust owns all state and pushes it into Slint globals;
long-running work runs on background threads and reports through a jobs list, so the UI thread never blocks.

## Core (`crates/core` and the crates under it)

Everything that isn't UI. `nether-core` is being split into crates that each have one job, lowest layer first:

| Crate | Folder | Job | Depends on |
|---|---|---|---|
| `nether-common` | `crates/common` | `paths`, `store` (safe JSON files), `trash`, `Progress`, `Session` | nothing of ours |
| `nether-net` | `crates/net` | `download` (shared client, hash-checked fetches), `net` (UPnP, IPs), `update` (signed auto-update) | common |
| `nether-auth` | `crates/auth` | Microsoft sign-in, accounts file, launch ownership gate | common |
| `nether-game` | `crates/game` | installing the game: `mojang`, `versions`, `java`, `fabric`, `forge`, `optifine`, `gamesettings`, `rpacks` | common, net |
| `nether-social` | `crates/social` | `party`, `party_net`, `p2p` (iroh QUIC), `cosmetics`, `preview`, `skins` | common, net |
| `nether-core` | `crates/core` | launching, instances, content, backend clients, Horizon | all of the above |

Code that differs by operating system goes in `nether_common::platform` (console-less helper processes, game
process priority, GPU preference, power throttling, RAM size, class path separator), not in `#[cfg(windows)]`
blocks elsewhere. Nether ships on Windows only, but macOS and Linux are planned, so a port should mean filling in
that module. What's still Windows-only outside it: AVIF decoding in `cosimport.rs` (has a fallback), Java discovery
in `java.rs`, the media helper (`crates/media`) and the launcher window code in `crates/app`.

`nether-core` re-exports the moved modules under their old names (`nether_core::paths`, `nether_core::download`,
`nether_core::auth`...), so code that uses them doesn't change. A crate may only depend on crates above it in the
table. What is still in core is tangled: `launch` needs `backend` for cape settings, `backend` and `ugc`,
`cosimport` and `netherpack` call each other, and `instances`, `modpack`, `crash` and `preflight` lean on `launch`.
Those have to be untangled before the rest can move.

- **Accounts:** Microsoft device-code OAuth → Xbox Live → Minecraft services. Tokens are stored in Windows Credential
  Manager, never in plain files.
- **Installs:** version manifests, libraries, assets, mod loaders (Fabric, Quilt, Forge, NeoForge, OptiFine) and Java
  runtimes, all verified by hash.
- **Launching:** builds the JVM and game arguments, adds the agent, supports Quick Play, runs the pre-launch check.
- **Content:** Modrinth search and installs, modpack and other-launcher import, update snapshots, restore points,
  verify and repair, crash analysis.
- **Shared settings:** one global `options.txt` merged into each instance before launch and read back after.
- **Social:** friends, parties (`party.rs`, `party_net.rs`) and world invites (`share.rs`), linked peer-to-peer over
  QUIC with iroh (`p2p.rs`).
- **Backend clients:** signed content, capes and cosmetics, community uploads, friends, auto-update. The backend
  authenticates players with Mojang's `join` / `hasJoined` handshake, so it never receives a Minecraft token.

## In-game client (`agent/`)

A `java.lang.instrument` agent, not a mod, so it runs the same on every loader.

- `dev.nether.agent` bootstraps it and installs a small number of ASM hooks (frame end, input, chat, game arguments).
- `dev.nether.ov` holds everything else. It reads game state by reflection through per-version name tables
  (`res/names/*.txt`) and draws its own overlay on the finished frame (GL core/compat; LWJGL 2, LWJGL 3 and SDL3
  back ends). It is split into layers, lowest first, each a package with a `package-info.java` saying what it holds:

  | Package | Job |
  |---|---|
  | `ov` | `Overlay` (entry point, started by the agent) and `Schema` (build-time settings dump) |
  | `ov.platform` | windowing, input and GL per game generation; the only code that touches LWJGL |
  | `ov.game` | reading the game by reflection, name tables, resource-pack assets |
  | `ov.framework` | `Module`, `Setting`, the module registry and HUD loop, config, input routing, HTTP |
  | `ov.ui` | the 2D renderer (`Gfx`), theme, icons, toasts, shared widgets |
  | `ov.menu`, `ov.menu.title` | the in-game menu and settings pages; the title screen |
  | `ov.features.*` | one package per client feature: `hud`, `performance`, `visual`, `lyrics`, `media`, `cosmetics`, `social`, `packs`, `screenshots` |

  A package should only use the ones above it in the table. A few older paths still break that (the registry and
  the menu reach into features); don't add new ones.
- It never patches renderer classes, so Sodium, Iris, Embeddium and Distant Horizons are unaffected.
- Lookups are null-safe: a field missing on a version hides that feature instead of crashing the game. See
  [`agent/SAFETY.md`](../../agent/SAFETY.md).

### Launcher ↔ agent

- **Delivery.** Each instance gets its own copy of the client in `<instance>/.nether/client/` (both jars, `client.json`
  and `nether-media.exe`), installed at launch by `nether_core::agent::ensure`. See [Client updates](#client-updates).
- **Protocol.** `PROTOCOL` in `crates/core/src/agent.rs` and `clientProtocol` in `agent/gradle.properties` name the
  launcher ↔ client contract: the `-Dnether.*` properties, the boot jar beside the agent jar, and the media helper
  beside it. A launcher only installs a client with its own protocol number. Raise both together, and only for a
  breaking change.
- **Settings schema.** The agent build writes `schema.json` describing every module and setting (`ov.Schema`).
- **Config.** Both sides read and write the same JSON config files (see [Tweaking and dev tools](dev-tools.md#client-config)).
- **Runtime.** The launcher and the running game exchange small JSON files in the instance (for example world
  invites), and the media helper talks to the agent over stdio.

## Client updates

The in-game client updates on its own, always (there is no setting for it):

1. Every launcher start checks the latest GitHub release of the client repo (`CLIENT_REPO` in
   `crates/core/src/agent.rs`). A release carries `nether-agent.jar`, `nether-agent-boot.jar`, `client.json`
   (version, protocol, sha256 of both jars) and `client.json.sig`.
2. If it is newer than every client the launcher has, the launcher downloads `client.json` and checks its Ed25519
   signature against `crates/core/res/client-ed25519.pub` (the signed message is `nether-client\n` followed by the
   file's bytes). A release with another protocol number is skipped: it is for a newer launcher.
3. The jars are downloaded and checked against the hashes in `client.json`, stored in
   `%APPDATA%\NetherClient\client\builds\<version>\` (the two newest are kept), and installed into every instance
   that already has a client. An instance whose game is running gets it at its next launch.
4. At launch, the newest usable build is installed into that instance. If it can't be, the launcher falls back to an
   older build, then to the copy already in the instance. The build bundled into the launcher is always one of the
   candidates, so a launcher with no network still launches with a client.

## Backend

The service that signs content and serves capes, cosmetics, friends and community uploads is private and isn't in
this repository. Every service the launcher and the client contact is listed in [PRIVACY.md](../../PRIVACY.md).
