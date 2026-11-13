# Nether Client: module safety

One model, used by the menu badges, the launcher Client page (schema `safety`) and server-safe mode (`Module.safety()`).

- **SAFE**: shows information the game already gives you. Fine on any server.
- **COSMETIC**: changes how things look. Fine on any server.
- **CHECK RULES**: can be an advantage some servers forbid. Marked `safe()` in code, **forced off while server-safe mode is on** (header pill, or automatically by Auto Safe Mode).

Server-safe mode only ever turns these off. Auto Safe Mode only ever turns safe mode ON (strict list: bundled names + `strict-servers.json` under `-Dnether.content` + your own entries, suffix match) and puts your own value back when you leave; it never switches safe mode off on its own.

| Module | Safety | Forced off in safe mode |
|---|---|---|
| Toggle Sprint, Toggle Sneak | CHECK RULES | yes |
| Zoom, Fullbright | CHECK RULES | yes |
| Reach Display | CHECK RULES (default off) | yes |
| Crosshair (incl. colour on target), Camera Comfort | COSMETIC | no |
| Combo Counter | SAFE (own clicks and own hurt flash only; default off) | no |
| Vitals (+ low-health vignette), Item Counter (own inventory) | SAFE | no |
| FPS, Frame Graph, CPS, Ping, Keystrokes, Stopwatch | SAFE | no |
| Coordinates, Compass, Waypoints (incl. death point), Speed, Day Counter, Clock, Session, Memory | SAFE (coordinates hide themselves where the server hides F3 data) | no |
| Potion Effects, Armor Status, Pack Display, Server Address | SAFE | no |
| Media Player, Lyrics, Precision Mouse | SAFE | no |
| Streamer Mode, Auto Safe Mode | SAFE | no |

Notes
- Combo and Reach read only your own attack click, the game's own crosshair result and your own hurt flash. No packets, no other player's state.
- Reach and the "hits" Combo counts are display only; Hypixel's position on both is unverified: recheck their rules before changing a default.
- Camera Comfort writes vanilla options (damage tilt, FOV effects, distortion, bobbing, glint) only while you play and restores yours on pause and exit.
