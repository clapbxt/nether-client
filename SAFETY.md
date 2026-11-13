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
  it changes is the vanilla mouse sensitivity, while you hold Precision Mouse or Zoom, and it puts yours back after.
- **writes vanilla options** (camera tilt, FOV effects, gamma for Fullbright) only while a module needs them, and
  restores your own values on pause and exit.

## Three badges

Every module carries one, in the in-game menu and on the launcher's Client page.

| Badge | Meaning |
|---|---|
| **SAFE** | Shows information the game already gives you. Fine on any server. |
| **COSMETIC** | Changes how things look. Fine on any server. |
| **CHECK RULES** | Can be an advantage some servers forbid. Off in **server-safe mode**. |

**Server-safe mode** is the pill at the top of the in-game menu. It switches every CHECK RULES module off while it's on.

**Auto Safe Mode** turns server-safe mode on by itself when you join a strict server. The built-in list is Hypixel,
The Hive, CubeCraft, Mineplex and Minemen, and you can add any address in its settings. It puts your own setting back
when you leave. It only ever turns safe mode **on**, never off.

## Module by module

| Module | Badge | Off in safe mode | Why |
|---|---|:---:|---|
| Toggle Sprint, Toggle Sneak | CHECK RULES | ✓ | Holds the vanilla key state for you. Allowed on most servers, not all. |
| Zoom, Fullbright | CHECK RULES | ✓ | Vanilla FOV and gamma options, but some servers treat them as an advantage. |
| Reach Display | CHECK RULES (default off) | ✓ | Distance of your own last hit, from the game's own crosshair result. Display only. |
| Combo Counter | SAFE (default off) | | Counts your own landed clicks until you get hit. Your own events only. |
| Crosshair, Camera Comfort | COSMETIC | | Draws a crosshair, and sets vanilla camera options (damage tilt, FOV effects, bobbing). |
| FPS, Frame Graph, CPS, Ping, Keystrokes, Stopwatch | SAFE | | Local frame times, your own key and mouse presses, the ping the tab list shows. |
| Coordinates, Compass, Waypoints, Speed, Day Counter, Clock, Session, Memory | SAFE | | Your own position and time. Coordinates hide where the server hides F3 data. |
| Vitals, Item Counter, Armor Status, Potion Effects | SAFE | | Your own health, hunger, inventory, armor and effects. |
| Pack Display, Server Address | SAFE | | Your resource packs and the address you joined. |
| Media Player, Lyrics, Precision Mouse | SAFE | | Your PC's media session, lyrics for it, and a sensitivity drop while held. |
| Streamer Mode, Auto Safe Mode | SAFE | | They only hide things or switch safe mode on. |

What Nether Client does **not** have, and won't: ESP or entity highlighting through walls, hitboxes, target HUDs
with other players' health, damage numbers, auto-clicking or any automation, minimaps, freecam, x-ray, or anything that
reads data the server didn't send you.

## Server owners

If you want a module off on your server, open [an issue](https://github.com/clapbxt/nether-client/issues) with your
server address and the rule, and we'll add your server to the Auto Safe Mode list.
