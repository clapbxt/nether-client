# Module list

Every module in the Nether client. Turn them on and off from the **Mods** page of the Nether menu (**Right Shift**).
See [In game](in-game.md) for how the menu works.

The **Servers** column flags modules that some servers don't allow. When in doubt, check the server's rules.
[Fair play](../../SAFETY.md) explains what each module reads and why.

## HUD

Information boxes on your screen. Move them in the HUD Editor.

| Module | What it shows | Servers |
|---|---|---|
| FPS | Frames per second, updated every second | |
| CPS | Left and right clicks per second | |
| Keystrokes | W, A, S, D, space and mouse buttons, with live CPS | |
| Coordinates | Your position and the direction you face | Hidden where the server hides F3 data |
| Compass | A heading strip with the cardinal directions | |
| Speed | How fast you are moving | |
| Clock | Your local time of day | |
| Day Counter | The in-game day and time of day | |
| Session Timer | How long you've been in this world or server | |
| Memory | How much memory the game is using | |
| Ping | Your latency to the server, from the player list | |
| Server Address | The server you're connected to | |
| Armor Status | Your armor and how much durability it has left | |
| Potion Effects | Active effects with icon, level and time left | |
| Item Counter | How many of chosen items you carry, such as pearls and golden apples | |
| Reach Display | How far away your last hit on a player or mob was (off by default) | Some forbid it |
| Combo Counter | Hits in a row on your target, reset when you get hit (off by default) | |
| Stopwatch | A stopwatch with lap times, driven by keys | |
| Media Player | What's playing in Spotify, your browser or any app in the Windows media controls, with album art and controls. Nothing leaves your PC. | |
| Lyrics | Synced lyrics for the song in Media Player, lit word by word as they're sung, as a karaoke panel or floating in the world (off by default) | |

## Visual

| Module | What it does | Servers |
|---|---|---|
| Zoom | Hold a key to zoom in smoothly. Scroll while zooming to change how far. | Some treat it as an advantage |
| Fullbright | Full brightness everywhere. Your own brightness comes back in Options and when you leave. | Some treat it as an advantage |
| Freelook | Hold a key to look around your player in third person without turning | Some forbid it |
| Crosshair | Your own crosshair shape | |
| Own Nametag | See your own nametag in third person, as others see it | |
| 3D Skin Layers | The outer layer of every player's skin (hat, jacket, sleeves, trousers) in 3D up close | |
| Custom Sky | Skies from your resource packs (OptiFine / MCPatcher sky packs) | |
| TNT Timer | Time left on every lit TNT, floating above it | |
| Hand View Editor | Move and resize your first-person hand and held item | |
| Shield View Editor | Lower the shield in first person so it covers less of your view | |
| Fire View Editor | Lower or fade the burning overlay, or hide it while you're fire resistant | |
| Ride View Editor | Fade the mob you ride so you can see past it | |

The view editors show the real game behind their settings, so you see exactly how it will look.

## Utility

| Module | What it does |
|---|---|
| Performance | Nether's own fixes that make each frame faster: skips hidden entities and block entities, avoids GPU stalls, keeps frame caps even. On by default. |
| Background FPS | Lowers the frame rate while the game is in the background, so your PC runs cooler and quieter |
| Background Reload | Resource packs reload in the background, without a loading screen |
| Waypoints | Mark places and see them on screen with their distance. Saved per server, world and dimension. |
| Screenshots | Browse, favorite, copy and delete screenshots without leaving the game |

## PvP tab

The **PvP** tab in the menu gathers the modules most PvP players want in one place: CPS, Keystrokes, Reach Display,
Combo Counter, Crosshair, Armor Status, Potion Effects and Item Counter.

## Not available on every version

Every module is built to fail safely: if something it needs doesn't exist on a Minecraft version, the module hides
itself instead of crashing the game. A few visual modules are newer than others and may not reach every version yet.

## What the Nether client will never have

ESP or seeing entities through walls, hitboxes, other players' health, damage numbers, auto-clicking or any
automation, minimaps, freecam, x-ray, or anything that reads data the server didn't send you.
