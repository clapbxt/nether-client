// Nether player API: who plays Nether (nametag icon) and which Nether cape they wear. Cloudflare Workers + D1, free plan.
// Auth never sees a Minecraft access token: the launcher proves its account through Mojang's join / hasJoined handshake
// (/v1/auth/start -> sessionserver join -> /v1/auth/finish) and gets a 24 h HMAC session token bound to its UUID.
// Secret: `wrangler secret put SESSION_KEY` (32+ random bytes, any string).
const UUID = /^[0-9a-f]{32}$/;
const NAME = /^[A-Za-z0-9_]{1,16}$/;
const NONCE = /^[0-9a-f]{32}$/;
const SESSION_SECS = 24 * 3600;
const PURGE_DAYS = 180;
const enc = new TextEncoder();
const json = (body, status = 200, extra = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...extra } });
const fail = (status, msg, extra = {}) => json({ error: msg }, status, extra);
const now = () => Math.floor(Date.now() / 1000);

// cape ids from the content repo, cached per isolate for 10 minutes; a failed refresh keeps the last good list
let catalog = { at: 0, ids: new Set() };
async function capeIds(env) {
  if (Date.now() - catalog.at < 600_000) return catalog.ids;
  try {
    const r = await env.ASSETS.fetch("https://assets.local/capes/index.json");
    if (!r.ok) throw new Error(`cape index ${r.status}`);
    const list = await r.json();
    if (!Array.isArray(list)) throw new Error("cape index not a list");
    catalog = { at: Date.now(), ids: new Set(list.map((c) => c && c.id).filter((id) => typeof id === "string" && /^[a-z0-9_-]{1,32}$/.test(id))) };
  } catch (e) {
    console.error("cape catalog", e && e.message);
    if (!catalog.ids.size) throw e;
    catalog.at = Date.now() - 540_000; // retry in a minute
  }
  return catalog.ids;
}

// ---- session tokens: v1.<base64url "uuid:exp">.<base64url HMAC-SHA256> ----
const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
const unb64 = (s) => Uint8Array.from(atob(s.replaceAll("-", "+").replaceAll("_", "/")), (c) => c.charCodeAt(0));
const hmacKey = (env, use) => crypto.subtle.importKey("raw", enc.encode(env.SESSION_KEY), { name: "HMAC", hash: "SHA-256" }, false, [use]);
async function mint(env, uuid) {
  const exp = now() + SESSION_SECS;
  const body = b64(enc.encode(`${uuid}:${exp}`));
  const mac = await crypto.subtle.sign("HMAC", await hmacKey(env, "sign"), enc.encode(`v1.${body}`));
  return { token: `v1.${body}.${b64(mac)}`, exp };
}
// the caller's UUID from their session token (uuid is never read from a request body)
async function caller(req, env) {
  const auth = req.headers.get("authorization") || "";
  if (auth.length > 512 || !auth.startsWith("Bearer v1.") || !env.SESSION_KEY) return null;
  const [, body, mac] = auth.slice(7).split(".");
  if (!body || !mac) return null;
  try {
    if (!(await crypto.subtle.verify("HMAC", await hmacKey(env, "verify"), unb64(mac), enc.encode(`v1.${body}`)))) return null;
    const [uuid, exp] = new TextDecoder().decode(unb64(body)).split(":");
    return UUID.test(uuid) && Number(exp) > now() ? uuid : null;
  } catch { return null; }
}

// rate limits: Workers Rate Limiting bindings when configured (see wrangler.toml); absent = no limit (local dev)
async function limited(env, binding, key) {
  const l = env[binding];
  if (!l) return false;
  try { return !(await l.limit({ key })).success; } catch { return false; }
}
const tooMany = () => fail(429, "slow down", { "retry-after": "30" });

async function readJson(req, max = 512) {
  const text = await req.text();
  if (text.length > max) return null;
  try { const v = JSON.parse(text); return v && typeof v === "object" ? v : null; } catch { return null; }
}

async function handle(req, env, ctx) {
  const url = new URL(req.url);
  // signed content (news, packs, capes, manifest): static files bundled with the Worker from backend/content
  if (req.method === "GET" && url.pathname.startsWith("/content/")) {
    const path = url.pathname.slice("/content".length);
    if (!/^\/[a-z0-9_\-\/]+\.(json|sig|png)$/.test(path) || path.includes("..")) return fail(404, "not found");
    const r = await env.ASSETS.fetch(new Request(`https://assets.local${path}`));
    if (!r.ok) return fail(404, "not found");
    const res = new Response(r.body, r);
    res.headers.set("cache-control", "public, max-age=300");
    return res;
  }
  const ip = req.headers.get("cf-connecting-ip") || "anon";
  if (req.url.length > 4096) return fail(414, "too long");

  // Nether players among these UUIDs -> their cape id ("" = none); others are left out. Public, edge-cached 5 min.
  if (req.method === "GET" && url.pathname === "/v1/players") {
    const ids = [...new Set((url.searchParams.get("u") || "").toLowerCase().replaceAll("-", "").split(",").filter((u) => UUID.test(u)))].sort().slice(0, 100);
    if (!ids.length) return json({});
    const key = new Request(`https://cache.nether/v1/players?u=${ids.join(",")}`);
    const hit = await caches.default.match(key);
    if (hit) return hit;
    if (await limited(env, "RL_READ", ip)) return tooMany();
    const { results } = await env.DB.prepare(`SELECT uuid, cape FROM players WHERE uuid IN (${ids.map(() => "?").join(",")})`).bind(...ids).all();
    const res = json(Object.fromEntries(results.map((r) => [r.uuid, r.cape || ""])), 200, { "cache-control": "public, max-age=60, s-maxage=300" });
    ctx.waitUntil(caches.default.put(key, res.clone()));
    return res;
  }

  // step 1 of the handshake: a one-time server id the launcher passes to Mojang's session server
  if (req.method === "POST" && url.pathname === "/v1/auth/start") {
    if (await limited(env, "RL_AUTH", ip)) return tooMany();
    const serverId = [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, "0")).join("");
    const t = now();
    await env.DB.batch([
      env.DB.prepare("DELETE FROM nonces WHERE exp < ?").bind(t),
      env.DB.prepare("INSERT INTO nonces (id, exp) VALUES (?, ?)").bind(serverId, t + 60),
    ]);
    return json({ serverId });
  }

  // step 2: Mojang confirms (hasJoined) the launcher joined that server id under that name -> session token
  if (req.method === "POST" && url.pathname === "/v1/auth/finish") {
    if (await limited(env, "RL_AUTH", ip)) return tooMany();
    if (!env.SESSION_KEY) return fail(503, "service unavailable");
    const b = await readJson(req);
    if (!b || !NAME.test(b.name || "") || !NONCE.test(b.serverId || "")) return fail(400, "bad request");
    const used = await env.DB.prepare("DELETE FROM nonces WHERE id = ? AND exp >= ? RETURNING id").bind(b.serverId, now()).first();
    if (!used) return fail(401, "sign-in step expired, try again");
    // Mojang's session servers can take a moment to show a fresh join: 204 gets two more tries
    let r;
    for (let i = 0; i < 3; i++) {
      if (i) await new Promise((ok) => setTimeout(ok, 500));
      try { r = await fetch(`https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${b.name}&serverId=${b.serverId}`, { signal: AbortSignal.timeout(4000) }); }
      catch { return fail(503, "try again later", { "retry-after": "30" }); }
      if (r.status !== 204) break;
    }
    if (r.status === 204 || r.status === 403 || r.status === 401) return fail(401, "Mojang didn't confirm the join (" + r.status + ")");
    if (!r.ok) return fail(503, "try again later", { "retry-after": "30" });
    const id = String((await r.json().catch(() => ({}))).id || "").toLowerCase();
    if (!UUID.test(id)) return fail(401, "sign in with Minecraft");
    const s = await mint(env, id);
    return json({ token: s.token, uuid: id, exp: s.exp });
  }

  // the launcher checks in at every game start (marks you as a Nether player; writes at most once a day)
  if (req.method === "POST" && url.pathname === "/v1/hello") {
    const uuid = await caller(req, env);
    if (!uuid) return fail(401, "sign in with Minecraft");
    if (await limited(env, "RL_WRITE", uuid)) return tooMany();
    await env.DB.prepare("INSERT INTO players (uuid, cape, seen) VALUES (?, NULL, ?) ON CONFLICT(uuid) DO UPDATE SET seen = excluded.seen WHERE players.seen < excluded.seen - 86400")
      .bind(uuid, now()).run();
    const row = await env.DB.prepare("SELECT cape FROM players WHERE uuid = ?").bind(uuid).first();
    return json({ uuid, cape: row?.cape ?? null });
  }

  if (req.method === "PUT" && url.pathname === "/v1/cape") {
    const uuid = await caller(req, env);
    if (!uuid) return fail(401, "sign in with Minecraft");
    if (await limited(env, "RL_WRITE", uuid)) return tooMany();
    const b = await readJson(req, 256);
    if (!b) return fail(400, "bad request");
    const cape = b.cape ?? null;
    if (cape !== null && (typeof cape !== "string" || !(await capeIds(env)).has(cape))) return fail(400, "unknown cape");
    await env.DB.prepare("INSERT INTO players (uuid, cape, seen) VALUES (?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET cape = excluded.cape, seen = excluded.seen")
      .bind(uuid, cape, now()).run();
    return json({ uuid, cape });
  }

  // forget me: removes the caller's rows
  if (req.method === "DELETE" && url.pathname === "/v1/me") {
    const uuid = await caller(req, env);
    if (!uuid) return fail(401, "sign in with Minecraft");
    if (await limited(env, "RL_WRITE", uuid)) return tooMany();
    await env.DB.batch([
      env.DB.prepare("DELETE FROM players WHERE uuid = ?").bind(uuid),
    ]);
    return json({ ok: true });
  }
  return fail(404, "not found");
}

export default {
  async fetch(req, env, ctx) {
    try {
      // no CORS on purpose: authenticated routes need an Authorization header, so browsers cannot call them cross-origin
      if (req.method === "OPTIONS") return new Response(null, { status: 204 });
      return await handle(req, env, ctx);
    } catch (e) {
      console.error("worker error", e && e.message); // never the request headers
      return fail(500, "server error");
    }
  },
  // daily purge (see wrangler.toml triggers): players unseen for 180 days, expired nonces
  async scheduled(_event, env) {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM players WHERE seen < ?").bind(now() - PURGE_DAYS * 86400),
      env.DB.prepare("DELETE FROM nonces WHERE exp < ?").bind(now()),
    ]);
  },
};
