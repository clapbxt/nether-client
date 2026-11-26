# How it fits together

```
NetherClientSetup.exe ──installs──▶ nether-app.exe (launcher, Slint UI)
                                        │  uses
                                        ▼
                                    nether-core (no UI): accounts, installs, launching, mods, hosting, backend clients
                                        │  starts java with
                                        ▼
                          Minecraft  +  -javaagent:nether-agent.jar  ◀── embedded in the launcher at build time
                                        │
                                        └─ nether-media.exe (Windows media session over stdio, exits with the game)
```

| Component | Folder | Language | Output |
|---|---|---|---|
| Launcher | `crates/app` | Rust + Slint | `nether-app.exe` |
| Launcher screens | `crates/ui` (compiles `crates/app/ui/*.slint`) | Slint | library |
| Core library | `crates/core` | Rust | library, used by the launcher and the dev examples |
| In-game client | `agent/` | Java (bytecode level 8) | `nether-agent.jar` + `schema.json` |
| Media helper | `crates/media` | Rust | `nether-media.exe` |
| Installer / updater | `crates/installer` | Rust + Slint | `NetherClientSetup.exe` |

## Launcher (`crates/app`)

One Rust module per screen or area in `src/` and one `.slint` file per screen in `ui/`. `ui/theme.slint` holds the
design tokens and `ui/components.slint` the shared widgets. Rust owns all state and pushes it into Slint globals;
long-running work runs on background threads and reports through a jobs list, so the UI thread never blocks.

## Core (`crates/core`)

Everything that isn't UI:

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
  back ends).
- It never patches renderer classes, so Sodium, Iris, Embeddium and Distant Horizons are unaffected.
- Lookups are null-safe: a field missing on a version hides that feature instead of crashing the game. See
  [`agent/SAFETY.md`](../../agent/SAFETY.md).

### Launcher ↔ agent

- **Module settings UI.** The agent build writes `schema.json` describing every module and setting. The launcher reads
  it at compile time and generates its client settings pages from it, so a new module needs no launcher UI code.
- **Config.** Both sides read and write the same JSON config files (see [Tweaking and dev tools](dev-tools.md#client-config)).
- **Runtime.** The launcher and the running game exchange small JSON files in the instance (for example world
  invites), and the media helper talks to the agent over stdio.

## Backend

The service that signs content and serves capes, cosmetics, friends and community uploads is private and isn't in
this repository. Every service the launcher and the client contact is listed in [PRIVACY.md](../../PRIVACY.md).
