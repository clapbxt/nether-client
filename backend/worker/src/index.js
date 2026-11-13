// Nether player API: who plays Nether (nametag icon) and which Nether cape they wear. Cloudflare Workers + D1, free plan.
// Auth never sees a Minecraft access token and never calls Mojang (Mojang answers 403 to Cloudflare's egress): the
// launcher gets its Mojang player certificate (the chat-signing key pair, signed by Mojang for its UUID) and signs our
// one-time nonce with it. /v1/auth/start -> nonce; /v1/auth/finish checks Mojang's signature over (uuid, expiry,
// public key) with Mojang's published certificate keys and the nonce signature with that public key, then returns a
// 24 h HMAC session token bound to the UUID.
// Secret: `wrangler secret put SESSION_KEY` (32+ random bytes, any string).
const UUID = /^[0-9a-f]{32}$/;
const NAME = /^[A-Za-z0-9_]{1,16}$/;
const NONCE = /^[0-9a-f]{32}$/;
const SESSION_SECS = 24 * 3600;
// api.minecraftservices.com/publickeys -> playerCertificateKeys (X.509 SPKI, base64). Rotate here if Mojang does.
const MOJANG_CERT_KEYS = [
  "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAylB4B6m5lz7jwrcFz6Fd/fnfUhcvlxsTSn5kIK/2aGG1C3kMy4VjhwlxF6BFUSnfxhNswPjh3ZitkBxEAFY25uzkJFRwHwVA9mdwjashXILtR6OqdLXXFVyUPIURLOSWqGNBtb08EN5fMnG8iFLgEJIBMxs9BvF3s3/FhuHyPKiVTZmXY0WY4ZyYqvoKR+XjaTRPPvBsDa4WI2u1zxXMeHlodT3lnCzVvyOYBLXL6CJgByuOxccJ8hnXfF9yY4F0aeL080Jz/3+EBNG8RO4ByhtBf4Ny8NQ6stWsjfeUIvH7bU/4zCYcYOq4WrInXHqS8qruDmIl7P5XXGcabuzQstPf/h2CRAUpP/PlHXcMlvewjmGU6MfDK+lifScNYwjPxRo4nKTGFZf/0aqHCh/EAsQyLKrOIYRE0lDG3bzBh8ogIMLAugsAfBb6M3mqCqKaTMAf/VAjh5FFJnjS+7bE+bZEV0qwax1CEoPPJL1fIQjOS8zj086gjpGRCtSy9+bTPTfTR/SJ+VUB5G2IeCItkNHpJX2ygojFZ9n5Fnj7R9ZnOM+L8nyIjPu3aePvtcrXlyLhH/hvOfIOjPxOlqW+O5QwSFP4OEcyLAUgDdUgyW36Z5mB285uKW/ighzZsOTevVUG2QwDItObIV6i8RCxFbN2oDHyPaO5j1tTaBNyVt8CAwEAAQ==",
  "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAt4t9NPuu7cktclnaH7eZj0omkLcJHeLz5MKsyJEntHZ0INtuBjSSul3Pp3pBeJN8k3ADdcdBLUN90bcAi7WsQqTx3Ft363q3W7TbM8j2iTEdp/0uVspoRt/DP1tkaWFs/w2WwUv9jbVoBUzfUc4pSTIxRwdjmqjZQfvjwKNDbOx3IhP2H0WXodbISejPi1wBZqNW4m1rnZAXp/EpUguxA8mobCa4vUCBkyFDyXdl69/wUSJHyCPmgcMJ364OlAhIqtwVPShBZObvrK/f0BYk6ShJD3N7TFDatSYsIIdcTKRknaIm91s+EsMrdB9U4Yw+ZJ/pyCB4S3vk8zfDCnb0DWIxYH3/EMzaxl77djmTmMzi/JDITup5z3jfWtRZmrAhU2/+W5IO5hEpo3/bCS9PXIY5xb41Lmp2ZO8dXKtyD66Chchy0W129n8vPl2GIruOdrxsjZAHnneyAb9jm0uaGaphwnEnuecX/qgHY6ZMtayvLLsPst8PO6R1vufMy8WqjK+j7LnC1krL7CPDg0NEhyQTmw5l+NCNjSlvB1juM9V4PARg0bYCOkGXm7ydRCjSSH8CJXZpwnd5cBB5WKAX3KPzutRgMi/LFwNSMZzFuUyXaYOZPpD259yqph1LmGqegEdDriACVU+dVEONFMm8eIuBofe7ljmsAFKW9BINwK0CAwEAAQ==",
];
const B64 = /^[A-Za-z0-9+/_-]+={0,2}$/;
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

// ---- Mojang player certificate check ----
// expiresAt as Java's Instant.toEpochMilli (fractions past milliseconds are cut, not rounded)
function isoMillis(iso) {
  const m = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(iso);
  if (!m) return NaN;
  return Date.parse(m[1] + "Z") + Number(((m[2] || "") + "000").slice(0, 3));
}
async function certificateOk(b, uuid) {
  try {
    const exp = isoMillis(b.expiresAt);
    if (!(exp > Date.now())) return false;
    const spki = unb64(b.publicKey);
    // Mojang signed (uuid msb/lsb, expiry ms, public key) with SHA1withRSA, all big-endian
    const payload = new Uint8Array(24 + spki.length);
    for (let i = 0; i < 16; i++) payload[i] = parseInt(uuid.slice(i * 2, i * 2 + 2), 16);
    new DataView(payload.buffer).setBigUint64(16, BigInt(exp));
    payload.set(spki, 24);
    const keySig = unb64(b.keySig);
    let signedByMojang = false;
    for (const k of MOJANG_CERT_KEYS) {
      const key = await crypto.subtle.importKey("spki", unb64(k), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" }, false, ["verify"]);
      if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, keySig, payload)) { signedByMojang = true; break; }
    }
    if (!signedByMojang) return false;
    // the launcher holds the private key: it signed our nonce (SHA256withRSA)
    const player = await crypto.subtle.importKey("spki", spki, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    return await crypto.subtle.verify("RSASSA-PKCS1-v1_5", player, unb64(b.sig), enc.encode(`nether-auth:${b.serverId}:${uuid}`));
  } catch { return false; }
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

  // step 1 of the handshake: a one-time nonce the launcher signs with its Mojang player certificate key
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

  // step 2: the nonce signed with the player's Mojang certificate key -> session token
  if (req.method === "POST" && url.pathname === "/v1/auth/finish") {
    if (await limited(env, "RL_AUTH", ip)) return tooMany();
    if (!env.SESSION_KEY) return fail(503, "service unavailable");
    const b = await readJson(req, 4096);
    if (!b || !NONCE.test(b.serverId || "") || !UUID.test(b.uuid || "") || typeof b.expiresAt !== "string" || b.expiresAt.length > 40
      || ![b.publicKey, b.keySig, b.sig].every((v) => typeof v === "string" && v.length < 1400 && B64.test(v))) return fail(400, "bad request");
    const used = await env.DB.prepare("DELETE FROM nonces WHERE id = ? AND exp >= ? RETURNING id").bind(b.serverId, now()).first();
    if (!used) return fail(401, "sign-in step expired, try again");
    const id = b.uuid;
    if (!(await certificateOk(b, id))) return fail(401, "Mojang's certificate didn't check out, sign in again");
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
