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

## 2. Cape API (Cloudflare)
```
npm i -g wrangler
wrangler login
cd backend/worker
wrangler d1 create nether            # paste the database_id into wrangler.toml
wrangler d1 execute nether --remote --file schema.sql
# set CONTENT_URL in wrangler.toml to the content URL above
wrangler deploy                      # prints https://nether-api.<you>.workers.dev
```
Put that URL in `nether_core::backend::DEFAULT_API_URL` (or `config.json` `"api_url"` / env `NETHER_API_URL`).

Endpoints:
- `GET /v1/players?u=<uuid>,<uuid>` (max 100) -> `{"<uuid>": "<cape id>" | ""}` for the Nether players among them (nametag icon + cape)
- `POST /v1/hello` (launcher, every game start) marks the caller as a Nether player
- `PUT /v1/cape` body `{"cape": "<id>" | null}`
- Writes need `Authorization: Bearer <Minecraft access token>`; it is checked against Minecraft Services and only used to learn the caller's UUID (never stored).

Free limits: Workers 100k requests/day, D1 5 GB + 5M reads/day.
