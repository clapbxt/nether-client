# Tweaking and dev tools

## Client config

The in-game client keeps its settings in JSON, in two layers with the same shape:

| Layer | Default path | Notes |
|---|---|---|
| Global | `%APPDATA%\NetherClient\client\global.json` | Used when a profile has no file of its own |
| Instance | passed by the launcher | Keys here override the global file |

```jsonc
{ "menuKey": 344,                     // GLFW key code of the menu key (Right Shift)
  "modules": {
    "fps": { "enabled": true,
             "anchor": "tr",          // HUD box anchor: t|m|b + l|c|r, or "auto" (stacks in its corner)
             "ox": -2.0, "oy": 2.0,   // offset from the anchor, in GUI pixels
             "scale": 1.0,
             "color": "#EAEEF3" } } } // module settings: bool, number, "#rrggbb[aa]" or "chroma", string, key code
```

You can edit these by hand while the game runs: the client notices the change and reloads. A file that can't be parsed
is never overwritten straight away; if it stays broken it is kept as `*.bad-<time>` and a fresh one is written.

Shared Minecraft settings live in the launcher's data folder as one global `options.txt` (see
[How it fits together](architecture.md#core-cratescore)). **Settings → Shared game settings → Open settings file**
opens it.

## Developer switches

Developer-only behaviour sits behind the `dev` cargo feature and is never compiled into release builds.

| Task | Command |
|---|---|
| Launcher screenshot | `cargo build -p nether-app --release --features dev`, then `target\release\nether-app.exe --snapshot out.png <tab> [inst] [instTab] [mode] [gate]` |
| Installer screens | `cargo build -p nether-setup --release --features dev`, then `$env:NC_SETUP_SNAP="out.png\|<mode>\|<step>\|<installed>"` |
| Installer end to end | `$env:NC_SETUP_AUTO="install:<dir>"` or `"uninstall"`, with `NC_SETUP_AUTO_OUT=<file>` |

## Launch the game without the launcher

```powershell
$env:NETHER_DEV_OFFLINE=1
cargo run -p nether-core --release --features dev --example launch -- 1.21.11 Player 60 fabric
```

| Variable / property | Effect |
|---|---|
| `NETHER_INSTANCE=<name>` | use an existing instance folder |
| `NETHER_AGENT=<jar>` | use another agent jar |
| `NETHER_NO_AGENT=1` | launch without the agent |
| `NETHER_SNAPSHOT=<png>` | the agent writes a frame to this file (`<png>.req` requests more) |
| `NC_TEST_SIZE=<w>x<h>` | window size |
| `-Dnether.world=<save>` | join a singleplayer world |
| `-Dnether.cmd=a;b` | run commands as the integrated server (`+` = space) |
| `-Dnether.keys=t:key:action,…`, `-Dnether.shots=t,…`, `-Dnether.quitAt=t` | scripted input, timed snapshots, quit |

Pass `-D` properties through `JAVA_TOOL_OPTIONS`.

## Updating name tables

`agent/res/names/<version>.txt` maps the intermediary names the agent uses to each version's obfuscated names
(`-srg` files for Forge, `-moj` for Mojang-mapped versions). After adding a version or a new lookup, regenerate them
with `agent/tools/names.js` (`--official`, `--obf` and `--srg` inputs; usage is in the file header).

## Tests

```powershell
cargo test -p nether-core
cargo clippy --workspace --release
```
