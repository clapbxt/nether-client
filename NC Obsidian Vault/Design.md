# Design  (direction: **"Pit Wall"**, 2026-11; before/after in `design/screens/`)
Motorsport timing wall crossed with a mech garage: dense tables, one slanted red action, hard edges, the user's own imagery. `design/mockup.html` (v3 "Ice & Ember") is history, not the target.
## Brief
- **One focal action per screen: PLAY.** Red slanted slab on a dark offset plate; pressing seats it onto the plate. Nothing else is solid red.
- **Rows over cards.** Lists are timing-tower tables (position number, column heads, hairlines, red left bar on hover/selection). Cards only where an image is the content.
- **Asymmetry:** content left, a fixed side column right (preview / player / notes). No centred layouts, no card grids.
- **Signature shape = the slash** (parallelogram): Play, tab marker, nameplate, section ticks. Zero corner radius everywhere. Logo keeps its chamfer; the UI does not use chamfers.
- **Imagery is the user's:** instance wallpaper, block icon, skin. Where nothing exists: hatch plate (`assets/hatch.svg`) + corner ticks, never a "placeholder" box.
- **No ambient decoration:** no orbs, embers, glass gradients, blur. One static red glow only behind the player and on the gate. Player preview auto-rotates always on Home/Wardrobe (off with Reduce motion or the Auto-rotate toggle); every other screen idles at zero redraws.
- **Case:** condensed caps for titles, buttons, section labels; sentence case for everything you read. No wide-tracked mono eyebrows.
## Tokens (`crates/app/ui/theme.slint`)
- Surfaces: bg #060608 · s1 #0D0D11 · s2 #15151B · s3 #1F1F27 · hairlines white 8% / 17%.
- Text: tx #ECEFF4 · dim #A7AFBC · mut #707988 · ice #E9EDF2 with ink #09090C (one ice slab max per screen: selected segment, nameplate, invite ticket).
- Red: #E10600 · bright #FF3B2F · deep #8F0400 · red text on dark #FF7A70. Red = action, selection marker, live/error. Never decoration.
- Accent (`red`/`red2`/`red3`/`redtx`/`press`/`plate`) + `Theme.ts` text scale (1/1.1/1.2, text ≤16px only) set from Rust (`settings::apply_look`); never hard-code accent hex. Ice focus ring on keyboard focus only; Esc closes dialogs.
- Type: **Barlow Condensed** 600/700 + 700/800 italic (display: titles 44-132 italic, buttons 15, tabs 18, labels 12-13) · **Barlow** 400/500/600 (text 12-16) · **JetBrains Mono** (versions, numbers, 11-13). All OFL, `ui/fonts/README.txt`. Min size 11px.
- In-game menu text never below 9 px (`Gfx.minPx`); menu key shown on title screen, pause button and a one-time world toast (`client.hintShown`).
- Spacing: 56 page gutter, 32 column gap, rows 52-64, controls 34-40 high. Motion: 110 hover · 180 state · 260 page/slide (ease-out-quint). No loops except the live pulse and skeletons; "Reduce motion" stops those and page transitions.
## Components (`components.slint`)
`PlayButton` · `Btn` (outline / solid ice / danger, optional icon, 1px press) · `IconBtn` · `LinkBtn` · `Seg` (ice thumb) · `Toggle` (square, red on) · `Dropdown` · `Slider` (ticks) · `Field` · `UTabs` · `SectionHead` (01 + rule) · `Stat` · `SetRow` · `Meter` · `Hatch` · `Ticks` · `Slash` · `Scrim` · `Page` · `Pulse`. Icons: 12px line set in `Ico`. **Module icons** (2026-11-11): own set, `design/module-icons/<id>.svg` (24 grid, 2.5 stroke, square ends, ice glyph + one red #E10600 accent); agent rasterises the same SVGs (`ov/ModIcons` -> `Gfx.mask`, atlas, tinted), launcher Client page loads them; item textures only for ids without one.
## Shell
- Top bar 56: logo, italic wordmark, tabs with sliding slash marker, account chip, settings cog.
- **Bottom deck 56 (every screen):** launch target (last played; click to change) + PLAY, then what is running / downloading with a progress bar (`Backend.job-*`). On Home the deck shows status only (the hero is the launch control).
- Toasts above the deck; failures are red-marked, wrap to two lines and stay 7s.
## Screens
- **Home:** hero = launch target on its wallpaper (huge italic title, spec strip, PLAY, target picker), "Jump back in" tower with per-row play; right column = player on hatch plate, skin/cape, news as text rows.
- **Instances:** tower list left (click = edit, right-click = menu, hover = preview) · preview column right (wallpaper, specs, PLAY, EDIT, folder/duplicate/export/delete). `NEW INSTANCE` button top right.
- **Instance:** wallpaper banner, tabs Overview / Mods / Packs / Client. Overview = spec strip + numbered sections of setting rows; Manage list right.
- **Create dialog:** setup left (name, loader Vanilla/Fabric/Forge/NeoForge, version, memory), look right (live preview, icon, wallpaper).
- **Discover:** featured plate + chart table. **Client:** module grid + 16:9 HUD editor. **Host:** server picker (status dots, NEW SERVER) + folder/delete + START/STOP slab, status plate, ice invite ticket; tabs Console/Players/Plugins-Mods/Settings/World left; 5-step wizard (type, version, world, players, finish + EULA agree), addresses + UPnP or port-forward hint right; EULA dialog before first start. **Wardrobe:** player plate (WEAR slab while a library skin is previewed) + rendered skin tiles. **Settings:** section index left, rows centre, "how sharing works" right. **Gate:** split, wordmark left, logo plate right.
## Identity (unchanged)
- **Logo** (v4.2): original ghast mark, flat 2D, white/grey on black chamfered badge with nether dust and lava. `design/icon/ghast-mark.svg`, `nether-logo.svg`. Deliberately unlike Lunar/Feather.
- Palette identity: obsidian black, ice grey, racing red.
## Slint gotchas
- No text-transform: uppercase titles come from Rust (`.to-uppercase()` works in expressions).
- Path scales with preserve-aspect: build path strings from width/height (see `Slash`).
- Unpositioned Rectangle centres itself; a Text with `y` set needs `height` to centre vertically.
- `mouse-cursor: text` clashes with a `text` property: write `MouseCursor.text`.
- Text `stroke` (outline type) does not render with a transparent fill here; use a faint fill instead.
- **Chrome:** no native title bar/border; min/max/close (46px, close red) at the top bar's right, empty bar + letterbox drag the window.
- **Install Packs:** Seg MODRINTH/CURSEFORGE/NETHER, search+sort, 68px rows (icon, MC, loader, downloads, VIEW) -> shared project page (strip says "BUILDS A NEW INSTANCE").
- **Create wizard:** step rail (01 LOADER … 06 FINISH), big selectable cards/tiles, red border = picked; footer CREATE RAW INSTANCE link left, BACK/NEXT right.
- **Client page:** modules grid left; right Seg SETTINGS | HUD LAYOUT; settings rows by type (toggle, slider + value, 9-swatch palette, Seg/Dropdown, key chip). Instance: client toggle + group chips row.
- **In-game title screen:** obsidian backdrop (solid top, fading over the panorama), flat ghast mark, NETHER wordmark, 230px flat buttons with red hover bar, NETHER CLIENT button red.
