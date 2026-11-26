# Adding an in-game module

A module is one class in the in-game client. The launcher builds its settings page from the agent's `schema.json`, so
you don't write any launcher UI.

## Steps

1. Create `agent/src/dev/nether/ov/features/<area>/<Name>.java` (a public class) extending `Module` (from
   `dev.nether.ov.framework`), in the feature package it belongs to (`hud`, `visual`, `lyrics`...; a new area gets a
   new package with a `package-info.java`), with its id, name, group (`HUD`, `Visual` or
   `Utility`), a one-sentence description a player understands, its default state and HUD position.
2. Give every `Setting` a `hint` (one plain sentence) and a sensible default. Implement `icon()` for its card.
3. Register it in `framework/Modules.java`. Add its id to the `PVP` set in `menu/Menu.java` if it belongs on the PvP
   tab.
4. Read game state only through `game.Data` / `game.Game`, and draw only through `ui.Gfx`. Never use `platform`
   classes directly from a feature.
5. Run `agent\gradlew.bat -p agent build`. The launcher picks the module up from `schema.json` on its next build.
6. Document it: a row in [the player module list](../guide/modules.md), [SAFETY.md](../../SAFETY.md) and
   [`agent/SAFETY.md`](../../agent/SAFETY.md).

## Rules a module must follow

- **Never touch the renderer.** Draw on the finished frame; don't patch rendering classes.
- **Fair play.** Nothing that automates input, sends or changes packets, or reads data the server didn't send.
- **Fail safe.** Every lookup is null-safe. If something the module needs is missing on a version, hide the module
  rather than crash.
- **Put options back.** If the module changes a vanilla option (like FOV or gamma), restore the player's value on
  pause and exit.
- **Java 8.** The agent targets Java 8 bytecode so it runs on 1.8.9.
