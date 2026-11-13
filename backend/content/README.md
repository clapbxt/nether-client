# Nether content: news, capes, packs

The files in this folder ship with the cape API worker. They're served at
`https://nether-api.nbhowell1.workers.dev/content/<file>`.

The launcher only trusts files listed in the signed `manifest.json`. If you edit a file and don't re-sign, launchers
reject it.

## Publishing (every change)

1. Edit the files below.
2. Run `nether.bat`, then **7 Publish content**. It checks the JSON and the capes, signs (`manifest.json` +
   `manifest.sig`) and deploys. To do it by hand:
   ```
   node backend\content\sign.mjs sign
   cd backend\worker
   npx wrangler deploy
   ```
3. Commit with `nether.bat` **8**. The launcher's tests check the committed manifest against these files.

The signature is valid for **90 days**. Publish again before it runs out, even with no changes. Otherwise launchers
fall back to no news, no Nether capes and only the built-in packs.

## News: `news.json`

A list, **newest first**.

```json
[
  { "tag": "UPDATE", "title": "Nether 0.2 is out: new HUD", "url": "https://github.com/clapbxt/nether-client/releases" },
  { "tag": "NETHER", "title": "Welcome to Nether Client", "url": "https://clapbxt.com", "date": "2026-11-11" }
]
```

| Field | | |
|---|---|---|
| `title` | required | The headline. |
| `tag` | optional | Short uppercase label. Default `NETHER`. |
| `url` | optional | Opens when the item is clicked. |
| `date` | ignored | For your own records. |

## Capes: `capes/`

1. Draw the texture in the standard Minecraft cape layout: 64×32 PNG, the same as a vanilla/OptiFine cape template.
   `capes/obsidian.png` is a working example. Limits: at most 64 KB and 256×128 px.
2. Save it as `capes/<id>.png`. The id uses lowercase `a-z`, `0-9`, `_` and `-`, up to 32 characters.
3. Add it to `capes/index.json`. The order here is the order in the Wardrobe.
   ```json
   [
     { "id": "obsidian", "name": "Obsidian", "desc": "Black with a racing-red stripe." },
     { "id": "lava", "name": "Lava", "desc": "Molten orange on obsidian." }
   ]
   ```
4. Publish. The cape API refreshes its list of capes within 10 minutes. After that, players can **Wear** the new cape.

Keep ids stable, because renaming an id is the same as removing the cape. To remove a cape, delete its entry and its
PNG. Players who were wearing it show no Nether cape until they pick another.

## Packs: `packs.json`

A list of one-click mod packs, added after the launcher's built-in packs. A pack with the same `id` as a built-in pack
(`essentials`, `shaders`, …) replaces it.

```json
[
  {
    "id": "pvp",
    "name": "Nether PvP",
    "desc": "Essentials plus PvP HUD defaults.",
    "loader": "fabric",
    "mods": ["sodium", "lithium", "ferrite-core", "modmenu"],
    "groups": ["hud", "pvp"],
    "client": { "keys": { "enabled": true }, "cps": { "enabled": true } }
  }
]
```

| Field | | |
|---|---|---|
| `id`, `name` | required | |
| `mods` | required, not empty | Modrinth slugs or project ids. |
| `desc` | optional | |
| `loader` | optional | Modrinth loader. Default `fabric`. |
| `groups` | optional | In-game module groups the pack turns on: `hud`, `pvp`, `qol`, `menu`. |
| `client` | optional | In-game client settings laid over the player's own: a `modules` object, keyed by module id. |

## Signing key

The content key is at `%USERPROFILE%\.nether-signing\content-ed25519.key`. Back it up and never commit it. If you lose
it, you need a new key (`node backend\content\sign.mjs keygen`) and a launcher release that embeds it.
