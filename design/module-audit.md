# In-game module & menu audit (2026-11-14)

**Status:** Top 10 #1 and #2 and the "Simplify" cuts are done (2026-11-14), except the Items tag list, the Armor order/direction merge and the Lyrics items, which the lyrics pass covered separately. Second pass (same day) also fixed: #4 mouse binds (middle/side buttons, code 400+b), #7 zoom (scroll ×1.25 up to 50×, per zoom only, key swallowed), #9 combo (living targets, 500 ms, new target resets; Flash setting cut), Lyrics retry (10/20/40 s), clean() split and empty-answer TTL (1 day, in Net), Item Counter held duplicate, Stopwatch default F6, Death waypoint clears on arrival, Server Address streamer mode. Still open: #3 crosshair reload, #5 nudge buttons, #6 word-reveal default, #8 toggle keys, #10 tabs, menu colours, splash = Instant Health, potion "+N".

This audit is from a code read of `agent/src/dev/nether/ov/*`. Nothing was run in game. Competitor notes come from a quick web pass (Ok Zoomer, Zoomify, Lunar, Feather, Laby, Xaero's, SkyLyrics/Melodify), so spot-check them before they set scope.

## Top 10 (do these first)
1. **Hide settings that don't apply.** The settings page lists every setting all the time. Examples: Lyrics shows its 8 "In world" sliders while set to "On screen", FPS shows its colour thresholds with colouring off, Crosshair shows Gap for "Dot", Media shows "Album art size" with art off, and Keystrokes shows 3 switches that a preset overrides. Add `Setting.when(() -> …)` and skip the row in `Menu.settingRows`/`expH`. This is the biggest simplification and needs no feature cuts.
2. **Generic HUD style buries module settings.** Every HUD module adds 10 style rows (scale, colours, background, opacity, shadow, accent, outline, rainbow, labels) *before* its own settings (`Module` constructor). Move them into a collapsed "Style" group at the bottom, or make them a global HUD style in Settings with a per-module "Custom style" toggle.
3. **Crosshair edits force a full resource reload.** After closing the menu, the game shows the loading overlay for several seconds on every change. The crosshair also silently does nothing if the user disables or reorders the "Nether Client" pack, and `reloadGame()` picks the *first* public no-arg method that returns a `Future`, so the result depends on method order. At minimum, show "Applies when you close the menu (reloads resources)" and warn when the pack is off.
4. **Mouse buttons can't be bound.** `Input.onKey` is the only key-capture path, so Zoom on mouse 4/5 (very common) is impossible. `keyName` also has no mouse names.
5. **Per-song lyric offset needs a hidden keybind.** "Nudge ±100 ms" defaults to unbound and there is no button, so the global slider (which shifts every song) is all most users will find. Add −/+ buttons on the Lyrics settings page, and on the panel when the cursor is free.
6. **Word-by-word lyric reveal is the default but usually guessed.** LRCLIB rarely has word stamps, so "Words" mostly runs on estimated timing and visibly drifts. Default to Line sweep, and use word reveal automatically only when the LRC has `<mm:ss>` word stamps.
7. **Zoom scroll permanently changes the saved level.** `Modules.onScroll` writes `zoom.level`, so the next zoom starts wherever you last scrolled. Ok Zoomer/Zoomify reset on release. Also: the cap is 10× (competitors go 50×+), the scroll step is linear 0.5 (sluggish at high zoom; use ×1.25 steps), and there is no cinematic-camera option.
8. **No module toggle hotkeys.** Fullbright, Lyrics, Media etc. can only be toggled from the menu. Add one optional "Toggle key" per module, shown as the existing key chip on the list row.
9. **Combo counter over-counts.** Any click on an entity hit-result counts: item frames, armour stands, mobs, and a different target. The 400 ms gate is under the 500 ms (10-tick) hurt-immunity, so a click between 0.4 and 0.5 s counts as a hit when it isn't. Use 500 ms and reset when the target entity changes.
10. **Thin "Utility" tab.** Only Waypoints sits in it, while 15+ modules sit in HUD. Re-group: **HUD**, **PvP**, **Music** (Media, Lyrics), **World** (Waypoints, Fullbright, Zoom, Compass, Own Nametag).

## Menus: bugs & visual inconsistencies
- **Hard-coded colours ignore theme and accent.** These are in `Menu.java`:
  - Primary button pressed colour `0xFFB80500` (l.375): a Cobalt or Acid accent still flashes red.
  - Slider track `0xFF23232C` (l.448) and knob `0xFFEAEEF3` (l.451): wrong on the light theme.
  - Pill and switch knobs are pure white.
- **The sidebar profile chip always says "Default profile"** (l.648). Profiles has no "active" concept, so the label is wrong after applying a profile. Show the last-applied profile, or drop the line.
- **The "x of y on" count is drawn twice**, in the top bar and in the footer. Keep one.
- **The settings list opens inline under the row**, so Lyrics expands to about 32 rows × 46 units and the module list scrolls away. With item 1 done, this becomes acceptable. Otherwise, use a dedicated settings page with a back arrow.
- **Waypoints draws its own toast** at y=54, while the first-run hint uses y=84 and `Toast` has its own spot. Use one toast helper and one position.
- **HUD boxes don't match each other.** Content sits 1, 2 or 3 px from the accent bar depending on the module (`RowsModule` +1, Potions/Media +2, Lyrics +3+2). Fonts also differ: Rows use SEMI 10 labels with MONO 13 values, Potions SEMI 11 with MONO 11, Armor UI 12 with MONO 11. Put one inset and one type scale in `Module`.
- **Search is capped at 24 characters and only matches name, description and category.** Settings labels are not searched, so typing "durability" finds nothing.
- **The title-screen "Image file" setting is a typed path** (`MainMenu`). Users will expect a browse button or a drop-in folder ("put images in `<client>/backgrounds`").
- **The HUD editor is in good shape**: snapping, guides, multi-select, 50-step undo and anchors. No issues found.

## Per module
Format: **Bugs**: things that are wrong · **Dislikes**: what users may not like · **Simplify**: settings to cut or merge · **Gap**: what competitors do better.

### Lyrics (22 own + 10 style = 32 settings → target ~10)
- **Bugs:**
  - A network error sets OFFLINE with no retry until the track changes, so one Wi-Fi blip loses a whole song.
  - An empty search result is a 200 cached for 30 days, so lyrics added to LRCLIB later stay "No lyrics found" for a month. Use a short TTL for empty lists.
  - For browser sources, `clean()` splits any title on " - " even when the artist is already known. "Song - Remastered 2011" then becomes artist "Song", title "Remastered 2011". Split only when the artist looks like a channel name or is empty.
- **Dislikes:**
  - Long lines shrink to 8 px and are then cut with "...", so words are lost. Wrap to 2 lines instead.
  - "Uppercase" applies only in world, not to the panel.
  - In-world words draw through walls and sit 4 blocks ahead, which can block PvP view. Hide them while a weapon is swung or you were hit recently, or offer a "Panel only in combat" option.
  - In-world performance: each word draws up to 16 glow + 4 blur + 16 outline + 1 text passes per frame, with up to 32 pieces on screen.
- **Simplify:**
  - Glow / Outline / Motion blur / Bounce → one **Effects** choice: Off / Subtle / Full.
  - Closest + Farthest → one **Distance** slider.
  - Reveal + Current line → one **Highlight** choice: Auto / Line sweep / Fade / Plain.
  - Drop Panel width (auto-size to the HUD box) and Align (always centre).
  - Keep "Unsynced lyrics", but default it **off**: proportional scrolling of plain lyrics is usually wrong.
- **Gap:** translation and romanization (Spicetify lyrics-plus), and a second provider fallback such as NetEase. Already ahead of every PvP client: none of Lunar, Feather, Badlion or Laby have synced lyrics.

### Media Player (16 + 10 = 26 → ~10)
- **Simplify:** drop Scroll speed (fixed), Album art size (tie it to layout), and Source app. "When paused" + "Controls" are fine.
- **Dislikes:** with no media playing, nothing shows and there is no hint. Users don't know the module needs Windows media controls (the Spotify desktop app, or a browser with media session). Show "Nothing playing" in the settings preview.
- **Gap:** volume and like buttons (Lunar/Laby Spotify). Using SMTC with no login is already better than Spotify-OAuth implementations.

### Zoom (7)
- **Bugs:** see Top 10 #7. The zoom key is not swallowed, so in creative on 1.12+ the C key also fires the vanilla "save hotbar" activator.
- **Gap:** cinematic smoothing toggle, 50× cap, multiplicative scroll, mouse-button bind, spyglass-style overlay (optional).

### Crosshair (5)
- **Bugs:** see Top 10 #3.
- **Dislikes:** no colour, because vanilla's invert blend makes colours come out inverted. Lunar users expect colour. The style names are unclear: "Cross" vs "Plus" differ only by gap.
- **Simplify:** merge Cross/Plus (Gap 0 = Plus).
- **Gap:** pixel-grid editor with an import/export code (Lunar's best-known feature), hit-marker flash.

### Keystrokes (5 + style)
- **Simplify:** remove the Layout preset. It overrides the three switches and confuses users (Top 10 #1). Keep Mouse / Space / CPS.
- **Gap:** sneak/sprint key row, per-state colours (pressed fill and text colour; currently fixed to ice/inverted), rounded-corner amount.

### Armor Status (13 + 10 = 23 → ~9)
- **Simplify:**
  - Names, Icons and Durability bar only matter in some layouts. Hide them per layout, and drop "Item names" (List always shows names, other layouts never do).
  - Fold Order into Direction (4 combos → 1 choice).
  - Warning colour → fixed red.
  - The arrow count here duplicates Item Counter's Arrows. Keep it in one place.
- **Gap:** enchant glint (renderer, out of scope by Rule 11; fine).

### Item Counter (11 + 10)
- **Bugs:** "Splash potions" counts every splash type (fire resistance, poison…). PvP users want Instant Health only. When "Held item total" is on and the held item is also a preset, it shows twice.
- **Simplify:** the 5 preset switches + custom text → one tag-style list ("pearl, gapple, arrows, totem, instant health, +custom").

### Potion Effects (6 + 10)
- Settings are fine. The 8-effect cap is silent, so show "+N" when hit.
- **Gap:** hide vanilla's own top-right effect icons when this module is on (Lunar does).

### Reach / Combo (5 / 4)
- **Reach:** fine. Simplify by fixing the colour thresholds (3.0 / 3.5) and keeping only "Color by range".
- **Combo:** see Top 10 #9. "Show best" resets on game restart, which is expected.

### Waypoints (6)
- **Dislikes:**
  - The add key creates "Waypoint N" at once, with no name prompt. Renaming means opening the menu.
  - The Death waypoint never clears itself. Remove it on reaching it or after respawn + pickup, or add a "remove when reached" option.
- **Gap (all three are standard in Xaero's/JourneyMap):**
  - Share a waypoint to chat, and parse coordinates from chat into a waypoint.
  - Nether ↔ Overworld paired display (Coords already computes 1:8).
  - Optional beacon beam.

### Fullbright (1)
- **Dislikes:** no toggle key (most-requested). It sets gamma, which shader packs (Iris/OptiFine shaders) ignore, so with shaders on it appears broken. Detect shaders and say so in the description or lock note.

### Smaller modules
- **FPS:** 5 settings → 2 (Frame time, Stats). Make colouring a single toggle with fixed 60/30 thresholds. Ping: same treatment (80/160).
- **CPS:** the "Show right click" switch duplicates the "Show" choice (Split + right off = Left). Drop the switch.
- **Stopwatch:** the default F8 is vanilla's cinematic-camera key on 1.8.9, and is swallowed while the module is on. Default to unbound, or F6/F7/F9.
- **Server Address:** add a "streamer mode" mask (show `mc.***.net`). Showing the full IP is a common leak.
- **Compass, Clock, Session, Memory, Speed, Day Counter, Coords, Own Nametag:** no issues.

### Title screen (`MainMenu`, 17 settings)
- **Simplify:** Dim + Vignette → one "Darken" slider. Image fit / blur / slideshow should show only when Background = Image (item 1).

## Settings count after the cuts (own settings, style moved to a group)
| Module | Now | Target |
|---|---|---|
| Lyrics | 22 | 10 |
| Media Player | 16 | 10 |
| Armor | 13 | 8 |
| Item Counter | 11 | 6 |
| FPS | 5 | 2 |
| Keystrokes | 5 | 4 |
| Reach | 5 | 3 |
| Ping | 3 | 1 |
| CPS | 3 | 2 |
| Crosshair | 5 | 4 |
