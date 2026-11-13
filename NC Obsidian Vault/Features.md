# Features
★ MVP · ◆ v1 · ○ v2. **Auth: Microsoft account login only** (OAuth → Xbox → Minecraft; multi-account; tokens in OS keyring; offline mode ◆).
## Pages
**Home** (simple): last-played instance + Play, skin dropdown, cape dropdown, 3 stats.
**Instances**: grid → open instance (selection exists only here). Instance tabs: **Overview** (stats, Java/RAM/JVM args/window size, client scope summary, folder/duplicate/export/delete) · **Mods** (update/update all) · **Packs** (resource packs, shaders, data packs) · **Worlds** (Quick Play, servers, backups) · **Screenshots** · **Logs** (filter, issues only, live, share via mclo.gs) · **Client** (per-instance overrides).
 - Mods/Packs each: *Installed* (toggle, update badge) | *Browse* (Modrinth ★ / CurseForge ◆, auto-filtered to instance version+loader, one-click install w/ progress).
 - ★ isolated instances, any version/loader, auto Java (8/17/21/25), logs ◆ import/export (.mrpack, CF zip, Prism), backups, worlds, crash analyzer.
**Discover**: modpacks from Modrinth + CurseForge, source filter, one-click install → creates an instance. ◆ categories.
**Client** (in-game, injected agent): module list, each tri-state Inherit/On/Off; HUD layout editor + per-module settings; scope switcher Global | Instance (| Profile ◆).
 - ★ FPS, CPS, ping, keystrokes, coords, armor/potion HUD, toggle sprint, zoom, fullbright ◆ drag HUD editor, waypoints, crosshair, freelook (Tier B), replay ○ plugins.
**Host**: standalone local servers (Vanilla/Fabric/Paper, setup wizard, several at once, Modrinth plugins/mods), status, console, whitelist/backup, invite address + `nether://join/<address>` (UPnP, outside check, free playit.gg tunnel fallback), players, perf.
**Wardrobe**: *Skins* — library, upload, import by username/URL, select, download, delete, classic/slim, 3D preview ★. *Capes* — vanilla capes from account (equip/none) ★, custom image capes (upload) ◆; sharing/community ○ (later).
**Settings**: accounts, Java, paths, themes, updater.
**Friends** ◆ presence, join, party (ties to Host).
## Client scope model
Global → Profile → Instance, last wins. Instance stores only overrides (`client.toml`). Overrides = red dot + reset.
## Versions (for client, not launcher)
Launch: 1.8.9, 1.21.11, 26.3. Then 1.12.2, 1.16.5, 1.18.2, 1.19.4, 1.20.x, 1.21.x, 26.1.
