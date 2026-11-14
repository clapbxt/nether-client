# Nether backend (free tier only)

Two parts, both $0:

| Part | What | Host |
|---|---|---|
| `content/` | news, cape directory (+ PNGs), Nether packs. Read-only, edited by you. | Public GitHub repo, served by jsDelivr |
| `worker/` | who plays Nether (nametag icon) + which Nether cape they wear | Cloudflare Workers + D1 (free plan) |

## 1. Content (GitHub)
1. Create a **public** GitHub repo, e.g. `nether-content`.
2. Copy everything in `content/` to the repo root and push.
3. Content URL = `https://cdn.jsdelivr.net/gh/<github-user>/nether-content@main`
   (jsDelivr caches ~12 h; purge after a push: `https://purge.jsdelivr.net/gh/<github-user>/nether-content@main/news.json`).
4. Put it in `nether_core::backend::DEFAULT_CONTENT_URL` (or `config.json` `"content_url"` / env `NETHER_CONTENT_URL` for testing).

Files: `news.json` (newest first), `packs.json` (same schema as `netherpack::NetherPack`), `capes/index.json` + `capes/<id>.png`. Cape PNG: frames of 64:32 (cape + elytra, Minecraft layout) or 22:17 (cape only; the game keeps its own elytra) at any whole scale (HD up to 2048 px wide); frames stacked vertically = animated, `"frametime"` in index.json = ms per frame (20-2000, default 100). Max 4 MB, 128 frames, 4 Mpx after conversion to 64:32. `node sign.mjs sign` rejects anything else.

Cosmetics: `cosmetics/index.json` (`id`, `name`, `type` hat|headband|aura|shield, `rarity` common..legendary, `desc`,
`frametime`) + `cosmetics/<id>.json` + `cosmetics/<id>.png`. Hats, headbands and shields are Blockbench "Java
Block/Item" models: units = skin pixels, `(8, 0, 8)` = pivot (hats/headbands: the neck, the head is the 8x8x8 box at
`(4,0,4)-(12,8,12)`; shields: their centre), the player looks north (-Z); one texture, any resolution, vertical frames
= animated (frame height = width x texture_size h / w); `light_emission` > 0 on an element = glows; moving groups via
`"nether": {"anim": [{"group", "spin" [x,y,z] deg/s | "sway" [x,y,z] deg | "bob" [x,y,z] px, "period", "phase"}]}`.
Auras: an emitter JSON (`count`, `life`, `size`, `shape`, `radius`, `height`, `velocity`, `jitter`, `gravity`, `orbit`,
`wobble`, `fade`, `grow`, `frames`, `fps`, `glow`, `cell`) over a sprite sheet (columns = frames, rows = variants).
`node tools/cosmetics/gen.mjs` regenerates the starter set; then sign.

Blockbench: start from `tools/cosmetics/templates/<hat|headband|shield>.bbmodel` (grey `_ref` cubes = player head /
vanilla shield plate, dropped on import), then
`node tools/cosmetics/import.mjs my.bbmodel --id my_hat --type hat --name "My Hat" --rarity epic` (also takes a "Java
Block/Item" .json export with its PNGs beside it). Imports cubes with any rotation, inflate, box/per-face UV, groups with
pivots/rotations, all textures (packed into one; a single one may be animated), `light_emission` = glow, and one looping
animation (group rotation/position keyframes, played linear). Not supported: mesh elements, molang expressions.

## 2. Cape API (Cloudflare)
```
npm i -g wrangler
wrangler login
cd backend/worker
wrangler d1 create nether            # paste the database_id into wrangler.toml
wrangler d1 execute nether --remote --file schema.sql
wrangler secret put SESSION_KEY      # 32+ random bytes
wrangler deploy                      # prints https://nether-api.<you>.workers.dev
```
Existing database: run each new file in `migrations/` once BEFORE deploying the Worker that needs it
(`wrangler d1 execute nether --remote --file migrations/0001_player_hash.sql`, then `0002_cosmetics.sql`).

Put that URL in `nether_core::backend::DEFAULT_API_URL` (or `config.json` `"api_url"` / env `NETHER_API_URL`).
Use a custom domain on a Cloudflare zone for production: WAF / rate-limit rules and the edge cache (`caches.default`)
don't work on `*.workers.dev`.

Endpoints:
- `GET /v1/players?p=<hex4>,<hex4>` (max 100) -> `{"<sha256 hex>": "<cape id>" | ""}` for every Nether player whose
  `sha256("nether-player:" + uuid)` starts with one of the prefixes; clients match locally (the service never learns
  whom they play with). `&v=2` answers `{"<sha256 hex>": {"c": cape, "h": hat, "b": headband, "a": aura, "s": shield}}`.
  `?u=<uuid>,...` is the old form (launcher 0.1.5 and older).
- `POST /v1/auth/start` -> one-time nonce; `POST /v1/auth/finish` (Mojang player certificate + nonce signature) -> 24 h session token
- `POST /v1/hello` (launcher, every game start) marks the caller as a Nether player
- `PUT /v1/cape` body `{"cape": "<id>" | null}`; `PUT /v1/cosmetic` body `{"slot": "hat|headband|aura|shield", "id": "<id>" | null}`; `DELETE /v1/me` forgets the caller (launcher: turning Nether capes off)
- Writes need `Authorization: Bearer <session token>`; the Minecraft access token never reaches the service.

Free limits: Workers 100k requests/day, D1 5 GB + 5M reads/day.
