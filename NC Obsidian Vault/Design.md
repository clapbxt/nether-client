# Design  (direction: **v6**, approved 2026-11-13; replaces "Pit Wall"; ref `design/direction-v4.html`)
Calm, dark, rounded: OLED by default, one accent, Nether Pixel type, thin line icons. The user's own imagery carries Home.
## Brief
- **One accent action per screen: PLAY.** Accent fill, radius 8. Accent = action, selection, live/error; never decoration.
- **Rows over cards.** Lists are rows with hairlines; in game: River sidebar + Console list rows with inline settings. Cards only where an image is the content.
- **Shape:** radius 4-6 controls/rows, 8 panels, 10-12 floating glass (Home). No glow, no gradients on controls, no slanted shapes (`Slash` is now a flat bar, `PlayButton` a flat button).
- **Imagery is the user's:** instance wallpaper (Home full-bleed), block icon, skin. Player preview always turns on Home/Wardrobe (Reduce motion or the Auto-rotate toggle stop it).
- **Case:** Nether Pixel for titles, nav, buttons, names, labels; sentence case for what you read.
## Tokens (`ui/theme.slint`, `Th.java` in game)
- Themes (picker in launcher Settings + in-game Settings > General, shared via `client.json` `modules.client.theme`): **OLED** (default) bg #000 s1 #0B0B0D s2 #141418 s3 #1D1D23 text #F1F2F5 dim #A3A8B3 mut #6B707B · **Obsidian** #14151A/#1B1D23/#23262E/#2D313B · **Slate** #1A2130/#202839/#283245/#334057 · **Ice** (light) #EEF1F5/#FFF/#F4F6F9/#E4E8EE, text #171A21. Hairlines 8% / 16%. Glass = s1 at ~66% + 10% hairline (no backdrop blur in Slint).
- Accent default #E5372D, bright #FF6157 (5 swatches in Settings, also written to client.json `accent`/`accent2`); set from Rust (`settings::apply_look`/`apply_theme`), never hard-code accent hex.
- Type: **Nether Pixel** (`design/fonts`, OFL, 8 px per em) at 16/24/32/48/64 only (game: sizes snap to multiples of 8 in `Gfx.pxSize`); **Barlow** only for small secondary text (<16) and paragraphs. Min size in launcher 11px for Barlow.
- Icons: `design/module-icons/*.svg`, 20 grid, 1.6 stroke, round caps/joins, one colour; the same files in launcher (Image `colorize`) and agent (`ModIcons`, one filled mask per size). No item-texture fallback.
- HUD modules keep fixed colours (they read over the world); only menu/wardrobe/editor chrome follows the theme.
- Motion: 110 hover · 180 state · 260 page. Reduce motion stops loops and transitions.
## Components (`components.slint`)
`PlayButton` (flat accent) · `Btn` (outline / solid / danger) · `IconBtn` · `LinkBtn` · `Seg` · `Toggle` · `Dropdown` · `Slider` · `Field` · `UTabs` · `SectionHead` · `Stat` · `SetRow` · `Glass` · `TabBtn` (pill tab, active = ln2 fill). In game: `Menu.pill` (30x17), row/expand renderer, sidebar items.
## Shell
- Top bar 56: logo + "Nether", centred glass tab pill (Home, Instances, Discover, Host, Wardrobe; active = ln2), account chip, Client, settings cog. Transparent on Home.
- Bottom deck 56 on every screen except Home (job progress there is a small glass bar above the dock).
- In-game menu (Right Shift): ~86% centred translucent panel; sidebar 210 (brand, MODULES: Favorites/All/HUD/Visual/PvP/Utility, CLIENT: Wardrobe/HUD Editor/Settings, profile chip), top bar (title, "N of M on", search), list rows 44 (icon box, name + description, star, key chip, pill), click row = inline settings (one open), footer hints.
## Screens
- **Home = Dock:** wallpaper full-bleed; bottom-left "LAST PLAYED" label, title 64, spec line, PLAY + "Change instance"; bottom-centre glass dock of instance icons (+ new); right glass News card; skin right of centre.
- Other screens keep their v5 layouts, restyled with the tokens, font and radii only (Instances list, Instance page, Create wizard, Discover, Client, Host, Wardrobe, Settings).
## Identity (unchanged)
- **Logo** (v4.2): original ghast mark, flat 2D, white/grey on black chamfered badge with nether dust and lava. `design/icon/ghast-mark.svg`, `nether-logo.svg`. Deliberately unlike Lunar/Feather.
- Palette identity: obsidian black, ice grey, racing red.
## Slint gotchas
- No text-transform: uppercase titles come from Rust (`.to-uppercase()` works in expressions).
- Path scales with preserve-aspect: build path strings from width/height (see `Slash`).
- Unpositioned Rectangle centres itself; a Text with `y` set needs `height` to centre vertically.
- `mouse-cursor: text` clashes with a `text` property: write `MouseCursor.text`.
- Text `stroke` (outline type) does not render with a transparent fill here; use a faint fill instead.
- **Chrome:** no native title bar/border; min/max/close (46px, close red) at the top bar's right, empty bar + letterbox drag the window; no double-click maximise.
- **Install Packs:** Seg MODRINTH/CURSEFORGE/NETHER, search+sort, 68px rows (icon, MC, loader, downloads, VIEW) -> shared project page (strip says "BUILDS A NEW INSTANCE").
- **Create wizard:** step rail (01 LOADER … 06 FINISH), big selectable cards/tiles, red border = picked; footer CREATE RAW INSTANCE link left, BACK/NEXT right.
- **Client page:** modules grid left; right Seg SETTINGS | HUD LAYOUT; settings rows by type (toggle, slider + value, 9-swatch palette, Seg/Dropdown, key chip). Instance: client toggle + group chips row.
- **In-game title screen:** obsidian backdrop (solid top, fading over the panorama), flat ghast mark, NETHER wordmark, 230px flat buttons with red hover bar, NETHER CLIENT button red.
