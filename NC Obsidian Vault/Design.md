# Design  (direction: **v6**, approved 2026-11-13; replaces "Pit Wall"; ref `design/direction-v4.html`)
Calm, dark, rounded: OLED by default, one accent, Nether Pixel type, thin line icons. The user's own imagery carries Home.
## Brief
- **One accent action per screen: PLAY.** Accent fill, radius 8. Accent = action, selection, live/error; never decoration.
- **Rows over cards** in the launcher; **in game the mod list is a Lunar/Feather card grid** (2026-11-14, refs: Lunar mod menu, Feather mod menu/title).
- **Shape:** radius 4-6 controls/rows, 8 panels, 10-12 floating glass (Home). No glow, no gradients on controls, no slanted shapes (`Slash` is now a flat bar, `PlayButton` a flat button).
- **Imagery is the user's:** instance wallpaper (Home full-bleed), block icon, skin. Player preview always turns on Home/Wardrobe (Reduce motion or the Auto-rotate toggle stop it).
- **Case:** Nether Pixel for titles, nav, buttons, names, labels; sentence case for what you read.
## Tokens (`ui/theme.slint`, `Th.java` in game)
- Themes (picker in launcher Settings + in-game Settings > General, shared via `client.json` `modules.client.theme`): **OLED** bg #000 s1 #0B0B0D s2 #141418 s3 #1D1D23 text #F1F2F5 dim #A3A8B3 mut #6B707B · **Obsidian** (default since 2026-11-14, charcoal like Lunar/Feather) #14151A/#1B1D23/#23262E/#2D313B · **Slate** #1A2130/#202839/#283245/#334057 · **Ice** (light) #EEF1F5/#FFF/#F4F6F9/#E4E8EE, text #171A21. Hairlines 8% / 16%. Glass = s1 at ~66% + 10% hairline (no backdrop blur in Slint).
- Accent default **fire** #F76A05, bright #FF9A2E (from the logo; racing red #E5372D is swatch 2; 5 swatches in Settings, also written to client.json `accent`/`accent2`); set from Rust (`settings::apply_look`/`apply_theme`), never hard-code accent hex.
- Type: **Nether Pixel** (`design/fonts`, OFL, 8 px per em) at 16/24/32/48/64 only (game: sizes snap to multiples of 8 in `Gfx.pxSize`); **Barlow** only for small secondary text (<16) and paragraphs. Min size in launcher 11px for Barlow.
- Icons: `design/module-icons/*.svg`, 20 grid, 1.6 stroke, round caps/joins, one colour; the same files in launcher (Image `colorize`) and agent (`ModIcons`, one filled mask per size). No item-texture fallback.
- HUD modules keep fixed colours (they read over the world); only menu/wardrobe/editor chrome follows the theme.
- Motion: 110 hover · 180 state · 260 page. Reduce motion stops loops and transitions.
## Components (`components.slint`)
`PlayButton` (flat accent) · `Btn` (outline / solid / danger) · `IconBtn` · `LinkBtn` · `Seg` · `Toggle` · `Dropdown` · `Slider` · `Field` · `UTabs` · `SectionHead` · `Stat` · `SetRow` · `Glass` · `TabBtn` (pill tab, active = ln2 fill). In game: `Menu.pill` (30x17), row/expand renderer, category chips.
## Shell
- **Shell (Lunar-style, 2026-11-14):** left rail 76 (s1): fireball logo, icon buttons 48 r10 for Home/Instances/Discover/Host/Wardrobe (active = accent 16% + 3px accent bar, tooltip right), Settings cog at the bottom. Top bar 56 right of it: QUICK PLAY strip (instance icons, click = launch), account chip, window buttons. Stage scales from 1516x900 so pages keep their 1440 design width.
- Bottom deck 56 right of the rail on every screen except Home (job progress there sits bottom-left in the banner).
- In-game menu (Right Shift) opens on the **hub** (`Menu.hub`): pixel fireball 120 tall (5 units/sprite px), below it a row of glass r6 buttons 52 tall: settings cog | MODS (232 wide, Nether Pixel 16) | wardrobe; only the hub closes on Esc; typing jumps to module search.
- Hub buttons open **separate screens** (no shared sidebar), each with back arrow (to hub) + mark + title + close, Esc steps back to the hub: **Mods** (logo + MODS, "N of M on", search, HUD-editor button; pill category chips Favorites/All/HUD/Visual/PvP/Utility, active = accent; card grid min 172 x 132, r8: name + star, 32 icon, cog + ENABLED/DISABLED bar; click = toggle, cog/Enter/right-click = settings page), **Settings** (gear, narrower ~72%, General/Controls/Profiles), **Wardrobe** (hanger, preview + cosmetics).
- Menu motion (ease-out cubic, off in calm mode): hub logo drops in, buttons rise centre-out; Settings swipes from the left, Wardrobe from the right, Mods rises (~0.32 s, fade); category chips stagger in and one accent pill slides between them; cards cascade in (fade + rise, 25 ms apart) on every category change; module settings slide in from the right; cards lift 2 on hover.
- Module settings pages show only settings that apply (`Setting.when`; empty section headers hide); every HUD box's shared look sits last in a folded **Style** group (closed by default).
## Screens
- **Home = Lunar hero:** banner r12 (wallpaper, skin right, centred status + title 48 + spec + LAUNCH <ver> accent button 420x68 with chevron instance picker); below (190 tall): LATEST NEWS 3 cards left, INSTANCES list right (hover play, double-click opens).
- **Other pages (v6 pass 2):** Console list rows with hover fill + 3px accent bar (Instances, Discover, Client modules, mods); River-style left nav where a page has sections (Settings, Client categories); Instances = list + right detail pane (wallpaper, PLAY, icon actions); Wardrobe = left preview plate (drag spin, CLASSIC/SLIM, WEAR) + right skin/cape grid, worn = accent border + check + "Equipped"; Host invite card dark; installer same language (OLED, Nether Pixel, PLAY-style button). Panels radius 8, no hatch/ticks/slash, section heads unnumbered.
- **Warmth pass (2026-11-14):** launch target wallpaper at 18% behind the top of every non-Home page; Home greets by name, dock shows names on hover, player stands on a `GroundShadow`; lists end in an add row; SetRow titles are Barlow (Nether Pixel = headings, buttons, names); danger icons neutral until hover; Discover rows show a chevron, VIEW on hover; `UTabs` size to labels; deck PLAY hidden on Instances and Host (they own the accent action); Settings side column has notes for every section; Wardrobe library ends in an add tile.
- Nether Pixel is wide (~13 px per char at 16): give buttons auto width (`Btn` without `width`, chain with `self.width`); never place two buttons at fixed x (chain `a.x + a.width + gap`); `Seg` items size to their labels (`item-w` = minimum); stat values in tight spots use size 16; `if`-scoped ids are not reachable outside the `if`.
## Identity (unchanged)
- **Logo** (v6, 2026-11-14): **pixel fireball** (flame + charred ball), 17x24 sprite `design/icon/fireball-pixel.json` -> `fireball-pixel.py` writes `nether-logo.svg` (+ app copy) and the `Logo.java` rows; icon via `cargo run -p nether-app --example icon`. Agent draws it nearest-filtered (`Logo.draw`, `MainMenu.mark`); integer px multiples only. Ghast marks retired.
- Palette identity: obsidian black, ice grey, racing red.
## Slint gotchas
- No text-transform: uppercase titles come from Rust (`.to-uppercase()` works in expressions).
- Path scales with preserve-aspect: build path strings from width/height (see `Slash`).
- Unpositioned Rectangle centres itself; a Text with `y` set needs `height` to centre vertically.
- `mouse-cursor: text` clashes with a `text` property: write `MouseCursor.text`.
- Text `stroke` (outline type) does not render with a transparent fill here; use a faint fill instead.
- **Chrome:** no native title bar/border; min/max/close (46px, close red) at the top bar's right; only the top 56px strip drags the window (sign-in gate and crash page too); no double-click maximise.
- **Install Packs:** Seg MODRINTH/CURSEFORGE/NETHER, search+sort, 68px rows (icon, MC, loader, downloads, VIEW) -> shared project page (strip says "BUILDS A NEW INSTANCE").
- **Create wizard:** step rail (01 LOADER … 06 FINISH), big selectable cards/tiles, red border = picked; footer CREATE RAW INSTANCE link left, BACK/NEXT right.
- **In-game title screen (v6):** fixed OLED palette over the panorama (not the theme), flat ghast mark, NETHER wordmark, untracked Nether Pixel; buttons radius 6 glass/outline/minimal, no NETHER CLIENT button (Right Shift / pause chip open the menu); account/quick join/customise = glass radius 10; CUSTOMISE panel = DIM Seg + SHOW pill toggles only.
- **HUD (v6):** drawn rounded (`Gfx.round`): boxes radius 6 + 3px accent bar (`Th.ac`), keys radius 6, toasts/hint = glass radius 8, sentence case; no letter spacing.
