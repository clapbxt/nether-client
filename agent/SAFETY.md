# Nether Client: module safety

Server-safe mode (header pill in the in-game menu) switches off every module that can be an advantage some servers forbid. Those modules are marked `safe()` in code and are **forced off while it is on**. Nothing else changes with it; there are no per-module badges.

| Module | Forced off in server-safe mode |
|---|---|
| Zoom, Fullbright | yes |
| Reach Display (default off) | yes |
| Crosshair (incl. colour on target), Camera Comfort | no, looks only |
| Combo Counter (own clicks and own hurt flash only; default off), Item Counter (own inventory) | no |
| FPS, CPS, Ping, Keystrokes, Stopwatch | no |
| Coordinates, Compass, Waypoints (incl. death point), Speed, Day Counter, Clock, Session, Memory (coordinates hide themselves where the server hides F3 data) | no |
| Potion Effects, Armor Status, Pack Display, Server Address | no |
| Media Player, Lyrics, Streamer Mode | no |
