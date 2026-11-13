# Nether Client: safety

Nether Client is built around one rule: **the client shows you what the game already knows. It never plays for you.**
This page is for players, server owners and anticheat developers who want to know exactly what each module does.

## How the client works

The in-game client is a small Java agent the launcher adds to the game. It is not a Fabric or Forge mod, so it works
the same on every loader. It:

- **draws its own overlay on top of the finished frame.** It never patches the renderer. Sodium, Iris, Embeddium and
  Distant Horizons run untouched.
- **reads only local state**: your own player, your inventory, the crosshair target the game already computed, and
  the tab list the server already sent.
- **never sends, delays or changes packets**, never moves your player, and never presses keys for you. The one input
  it changes is the vanilla mouse sensitivity while you Zoom, and it puts yours back after.
- **writes vanilla options** (FOV for Zoom, gamma for Fullbright) only while a module needs them, and restores your own
  values on pause and exit.

## Module by module

| Module | Why it's fine / what to check |
|---|---|
| Zoom, Fullbright | Vanilla FOV and gamma options, but some servers treat them as an advantage. Check the rules. |
| Reach Display (default off) | Distance of your own last hit, from the game's own crosshair result. Display only; some servers forbid it. |
| Combo Counter (default off) | Counts your own landed clicks until you get hit. Your own events only. |
| Crosshair, Own Nametag | Looks only. The crosshair is a texture in the built-in resource pack, drawn by the game. |
| FPS, CPS, Ping, Keystrokes, Stopwatch | Local frame times, your own key and mouse presses, the ping the tab list shows. |
| Coordinates, Compass, Waypoints, Speed, Day Counter, Clock, Session, Memory | Your own position and time. Coordinates hide where the server hides F3 data. |
| Item Counter, Armor Status, Potion Effects | Your own inventory, armor and effects. |
| Server Address | The address you joined. |
| Media Player, Lyrics | Your PC's media session and lyrics for it. |

What Nether Client does **not** have, and won't: ESP or entity highlighting through walls, hitboxes, target HUDs
with other players' health, damage numbers, auto-clicking or any automation, minimaps, freecam, x-ray, or anything that
reads data the server didn't send you.
