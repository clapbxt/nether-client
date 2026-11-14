// Nether Content Studio: see, upload, edit, remove and publish capes and cosmetics from a browser on this PC.
//   node tools/studio/server.mjs        (or nether.bat > S)
// Security: listens on 127.0.0.1 only; a random token (in the URL it opens, kept in the page) is required on every
// request, the Host header must be our loopback address (stops DNS rebinding) and cross-site requests are refused, so
// other websites and other users' browsers can't drive it. Ids and file names are checked against strict patterns and
// only backend/content/{capes,cosmetics} is ever written. Child processes get argument lists (no shell, except npx
// on Windows with fixed arguments). The signing passphrase is only passed to the signing process for that one run.
import { createServer } from "node:http";
import { randomBytes, createHash, timingSafeEqual } from "node:crypto";
import { readFileSync, writeFileSync, existsSync, unlinkSync, mkdtempSync, rmSync, readdirSync, statSync, renameSync } from "node:fs";
import { tmpdir, homedir } from "node:os";
import { dirname, join, extname } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const content = join(root, "backend", "content");
const pub = join(here, "public");
const TOKEN = randomBytes(24).toString("hex");
const PORT = Number(process.env.STUDIO_PORT || 47821);
const MAX_BODY = 24 * 1024 * 1024;
const ID = /^[a-z0-9_-]{1,32}$/;
const KINDS = ["cape", "hat", "headband", "aura", "shield"];
const RARITY = ["common", "uncommon", "rare", "epic", "legendary"];

// ---------------- content helpers
const readJson = (f, d) => { try { return JSON.parse(readFileSync(f, "utf8")); } catch { return d; } };
const writeAtomic = (f, data) => { writeFileSync(f + ".tmp", data); renameSync(f + ".tmp", f); };
const capeIdx = () => join(content, "capes", "index.json");
const cosIdx = () => join(content, "cosmetics", "index.json");
const sha = (b) => createHash("sha256").update(b).digest("hex");

function capeLayout(w, h) { // same rule as sign.mjs / backend.rs / Capes.java
  for (const [bw, bh, full] of [[64, 32, true], [22, 17, false]]) {
    if (!w || w % bw) continue;
    const s = w / bw, fh = s * bh;
    if (h % fh) continue;
    const n = h / fh;
    if (s <= 32 && n >= 1 && n <= 128 && n * s * s <= 2048) return { s, n, full };
  }
  return null;
}
const pngSize = (b) => (b.length > 33 && b.readUInt32BE(0) === 0x89504e47 && b.toString("latin1", 12, 16) === "IHDR" ? [b.readUInt32BE(16), b.readUInt32BE(20)] : null);

/** files changed since the last signature (the manifest lists what is live after publishing) */
function dirty() {
  const m = readJson(join(content, "manifest.json"), { files: {} }).files || {};
  const now = {};
  const walk = (d, rel) => { for (const n of readdirSync(d)) { const p = join(d, n), r = rel ? `${rel}/${n}` : n; if (statSync(p).isDirectory()) walk(p, r); else if (!["manifest.json", "manifest.sig", "sign.mjs", "README.md"].includes(r) && !n.startsWith(".")) now[r] = sha(readFileSync(p)); } };
  walk(content, "");
  const changed = Object.keys(now).filter((k) => !m[k] || m[k].sha256 !== now[k]);
  const removed = Object.keys(m).filter((k) => !now[k]);
  return { changed, removed };
}

function list() {
  const capes = readJson(capeIdx(), []).map((c) => ({ kind: "cape", ...c, png: `capes/${c.id}.png` }));
  const cos = readJson(cosIdx(), []).map((c) => ({ ...c, kind: c.type, png: `cosmetics/${c.id}.png`, model: `cosmetics/${c.id}.json` }));
  return { items: [...capes, ...cos], pending: dirty() };
}

// ---------------- jobs (sign + deploy, migration): one at a time, log kept for the page
let job = null;
function run(title, steps) {
  if (job && job.running) throw new Error("another task is still running");
  job = { title, running: true, ok: null, log: "" };
  const say = (s) => { job.log += s; if (job.log.length > 200_000) job.log = job.log.slice(-200_000); };
  (async () => {
    for (const s of steps) {
      say(`\n> ${s.label}\n`);
      const code = await new Promise((resolve) => {
        const p = spawn(s.cmd, s.args, { cwd: s.cwd || root, env: { ...process.env, ...(s.env || {}) }, shell: !!s.shell, windowsHide: true });
        p.stdout.on("data", (d) => say(String(d)));
        p.stderr.on("data", (d) => say(String(d)));
        p.on("error", (e) => { say(`${e.message}\n`); resolve(-1); });
        p.on("close", resolve);
      });
      if (code !== 0) { say(`\nFAILED (${s.label}, exit ${code})\n`); job.running = false; job.ok = false; return; }
    }
    say("\nDone.\n");
    job.running = false;
    job.ok = true;
  })();
}
const npx = process.platform === "win32" ? { cmd: "npx.cmd", shell: true } : { cmd: "npx", shell: false };

// ---------------- uploads
const safeName = (n) => { const b = String(n || "").replace(/^.*[\\/]/, ""); return /^[A-Za-z0-9._ ()-]{1,80}$/.test(b) && !b.startsWith(".") ? b : null; };

function upload(b) {
  const kind = b.kind, id = b.id, name = String(b.name || "").trim().slice(0, 48);
  if (!KINDS.includes(kind)) throw new Error("pick what it is (cape, hat, headband, aura, shield)");
  if (!ID.test(id || "")) throw new Error("id: 1-32 of a-z 0-9 _ -");
  if (!name) throw new Error("a name is needed");
  const rarity = RARITY.includes(b.rarity) ? b.rarity : "rare";
  const desc = String(b.desc || "").trim().slice(0, 120);
  const frametime = b.frametime ? Math.max(20, Math.min(2000, parseInt(b.frametime, 10) || 100)) : undefined;
  const files = (b.files || []).map((f) => ({ name: safeName(f.name), data: Buffer.from(String(f.data || ""), "base64") }));
  if (!files.length || files.some((f) => !f.name)) throw new Error("add the files (plain file names only)");
  if (files.reduce((n, f) => n + f.data.length, 0) > 20 * 1024 * 1024) throw new Error("files too large");
  const exists = readJson(capeIdx(), []).some((c) => c.id === id) || readJson(cosIdx(), []).some((c) => c.id === id);
  if (exists && !b.replace) throw new Error(`"${id}" exists already (tick Replace to overwrite it)`);
  const pngs = files.filter((f) => extname(f.name).toLowerCase() === ".png");
  const jsons = files.filter((f) => [".json", ".bbmodel"].includes(extname(f.name).toLowerCase()));

  if (kind === "cape") {
    if (pngs.length !== 1 || files.length !== 1) throw new Error("a cape is one PNG");
    const d = pngs[0].data, sz = pngSize(d);
    if (!sz) throw new Error("not a PNG");
    if (d.length > 4 * 1024 * 1024) throw new Error("PNG over 4 MB");
    if (!capeLayout(sz[0], sz[1])) throw new Error(`${sz[0]}x${sz[1]} isn't a cape layout: frames of 64:32 (cape + elytra) or 22:17 (cape only), stacked vertically when animated`);
    writeAtomic(join(content, "capes", `${id}.png`), d);
    const idx = readJson(capeIdx(), []).filter((c) => c.id !== id);
    idx.push({ id, name, desc, ...(frametime ? { frametime } : {}) });
    writeAtomic(capeIdx(), JSON.stringify(idx, null, 1) + "\n");
    return `cape ${id} added`;
  }
  if (kind === "aura") {
    if (pngs.length !== 1 || jsons.length !== 1 || extname(jsons[0].name) !== ".json") throw new Error("an aura is its emitter .json + sprite sheet .png");
    const p = JSON.parse(jsons[0].data.toString("utf8"));
    if (!p || typeof p !== "object" || Array.isArray(p)) throw new Error("aura JSON must be an object");
    const sz = pngSize(pngs[0].data);
    if (!sz || sz[0] > 4096 || sz[1] > 8192 || pngs[0].data.length > 4 * 1024 * 1024) throw new Error("sprite sheet: a PNG up to 4096 x 8192 and 4 MB");
    writeAtomic(join(content, "cosmetics", `${id}.json`), JSON.stringify(p));
    writeAtomic(join(content, "cosmetics", `${id}.png`), pngs[0].data);
    const idx = readJson(cosIdx(), []).filter((c) => c.id !== id);
    idx.push({ id, name, type: "aura", rarity, desc });
    writeAtomic(cosIdx(), JSON.stringify(idx, null, 1) + "\n");
    return `aura ${id} added`;
  }
  // hats, headbands, shields: through the Blockbench importer (.bbmodel, or a Java Block/Item .json with its PNGs)
  if (jsons.length !== 1) throw new Error("one model: a .bbmodel, or a Blockbench Java .json export with its PNGs");
  const dir = mkdtempSync(join(tmpdir(), "nether-studio-"));
  try {
    for (const f of files) writeFileSync(join(dir, f.name), f.data);
    const a = [join(root, "tools", "cosmetics", "import.mjs"), join(dir, jsons[0].name), "--id", id, "--type", kind, "--name", name, "--rarity", rarity, "--desc", desc];
    if (frametime) a.push("--frametime", String(frametime));
    if (b.animation) a.push("--animation", String(b.animation).slice(0, 64));
    const r = spawnSyncNode(a);
    if (r.code !== 0) throw new Error((r.err || r.out || "import failed").trim().split("\n").slice(-3).join(" "));
    return r.out.trim().split("\n").slice(-2).join(" ");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}
import { spawnSync } from "node:child_process";
function spawnSyncNode(args) {
  const r = spawnSync(process.execPath, args, { cwd: root, encoding: "utf8", windowsHide: true, timeout: 60_000 });
  return { code: r.status, out: r.stdout || "", err: r.stderr || "" };
}

function remove(items) {
  if (!Array.isArray(items) || !items.length) throw new Error("nothing selected");
  let capes = readJson(capeIdx(), []), cos = readJson(cosIdx(), []);
  const done = [];
  for (const it of items) {
    if (!ID.test(it.id || "") || !KINDS.includes(it.kind)) continue;
    if (it.kind === "cape") {
      capes = capes.filter((c) => c.id !== it.id);
      const f = join(content, "capes", `${it.id}.png`);
      if (existsSync(f)) unlinkSync(f);
    } else {
      cos = cos.filter((c) => c.id !== it.id);
      for (const e of [".json", ".png"]) { const f = join(content, "cosmetics", `${it.id}${e}`); if (existsSync(f)) unlinkSync(f); }
    }
    done.push(it.id);
  }
  writeAtomic(capeIdx(), JSON.stringify(capes, null, 1) + "\n");
  writeAtomic(cosIdx(), JSON.stringify(cos, null, 1) + "\n");
  return `removed ${done.join(", ")}`;
}

function update(b) {
  if (!ID.test(b.id || "") || !KINDS.includes(b.kind)) throw new Error("bad item");
  const f = b.kind === "cape" ? capeIdx() : cosIdx();
  const idx = readJson(f, []);
  const it = idx.find((c) => c.id === b.id);
  if (!it) throw new Error("not found");
  const name = String(b.name || "").trim().slice(0, 48);
  if (name) it.name = name;
  it.desc = String(b.desc || "").trim().slice(0, 120);
  if (b.kind !== "cape" && RARITY.includes(b.rarity)) it.rarity = b.rarity;
  if (b.frametime !== undefined && b.kind !== "aura") { const n = parseInt(b.frametime, 10); if (n) it.frametime = Math.max(20, Math.min(2000, n)); else delete it.frametime; }
  writeAtomic(f, JSON.stringify(idx, null, 1) + "\n");
  return `${b.id} saved`;
}

// ---------------- HTTP
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".png": "image/png", ".json": "application/json" };
const HEAD = {
  "x-content-type-options": "nosniff", "referrer-policy": "no-referrer", "x-frame-options": "DENY", "cache-control": "no-store",
  "content-security-policy": "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
};
const send = (res, code, body, type = "application/json") => { res.writeHead(code, { ...HEAD, "content-type": type }); res.end(typeof body === "string" || Buffer.isBuffer(body) ? body : JSON.stringify(body)); };
const okToken = (t) => { const a = Buffer.from(String(t || "")), b = Buffer.from(TOKEN); return a.length === b.length && timingSafeEqual(a, b); };

function body(req) {
  return new Promise((resolve, reject) => {
    let n = 0;
    const parts = [];
    req.on("data", (c) => { n += c.length; if (n > MAX_BODY) { reject(new Error("request too large")); req.destroy(); } else parts.push(c); });
    req.on("end", () => { try { resolve(JSON.parse(Buffer.concat(parts).toString("utf8") || "{}")); } catch { reject(new Error("bad JSON")); } });
    req.on("error", reject);
  });
}

const server = createServer(async (req, res) => {
  try {
    // only our own loopback origin: blocks DNS rebinding and other sites
    const host = req.headers.host || "";
    if (host !== `127.0.0.1:${PORT}` && host !== `localhost:${PORT}`) return send(res, 421, { error: "wrong host" });
    const origin = req.headers.origin;
    if (origin && origin !== `http://127.0.0.1:${PORT}` && origin !== `http://localhost:${PORT}`) return send(res, 403, { error: "cross-site request refused" });
    if (req.headers["sec-fetch-site"] && !["same-origin", "none"].includes(req.headers["sec-fetch-site"])) return send(res, 403, { error: "cross-site request refused" });
    const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
    const p = url.pathname;

    // the page itself (static, no data in it); everything with data needs the token
    if (req.method === "GET" && (p === "/" || /^\/(app|render|style)\.(js|css)$/.test(p))) {
      const f = join(pub, p === "/" ? "index.html" : p.slice(1));
      return send(res, 200, readFileSync(f), TYPES[extname(f)]);
    }
    if (!okToken(req.headers["x-studio-token"])) return send(res, 401, { error: "missing token: open the link the studio printed" });

    if (req.method === "GET" && p === "/api/list") return send(res, 200, list());
    if (req.method === "GET" && p === "/api/job") return send(res, 200, job || { title: "", running: false, ok: null, log: "" });
    if (req.method === "GET" && p === "/api/skin") {
      const cands = [join(process.env.APPDATA || join(homedir(), ".config"), "NetherClient", "client", "wardrobe", "skin.png")];
      const tex = join(process.env.APPDATA || "", "NetherClient", "cache", "textures", "31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb.png");
      cands.push(tex);
      const f = cands.find(existsSync);
      return f ? send(res, 200, readFileSync(f), "image/png") : send(res, 404, { error: "no skin" });
    }
    let m;
    if (req.method === "GET" && (m = /^\/api\/file\/(capes|cosmetics)\/([a-z0-9_-]{1,32})\.(png|json)$/.exec(p))) {
      const f = join(content, m[1], `${m[2]}.${m[3]}`);
      return existsSync(f) ? send(res, 200, readFileSync(f), TYPES[`.${m[3]}`]) : send(res, 404, { error: "not found" });
    }
    if (req.method !== "POST") return send(res, 404, { error: "not found" });
    if ((req.headers["content-type"] || "").split(";")[0] !== "application/json") return send(res, 415, { error: "JSON only" });
    const b = await body(req);
    if (p === "/api/upload") return send(res, 200, { msg: upload(b), ...list() });
    if (p === "/api/remove") return send(res, 200, { msg: remove(b.items), ...list() });
    if (p === "/api/update") return send(res, 200, { msg: update(b), ...list() });
    if (p === "/api/publish") {
      const pass = String(b.passphrase || "");
      if (b.confirm !== "PUBLISH") throw new Error("type PUBLISH to confirm: this goes live for every player");
      if (b.migrated !== true) throw new Error("run the database migrations this Worker needs first (Migrations…), then tick the box");
      const keyFile = join(homedir(), ".nether-signing", "content-ed25519.key");
      if (existsSync(keyFile) && readFileSync(keyFile, "utf8").includes("ENCRYPTED") && !pass) throw new Error("enter the content key passphrase");
      run("Publish", [
        { label: "sign content (backend/content/sign.mjs)", cmd: process.execPath, args: [join(content, "sign.mjs"), "sign"], env: { NETHER_SIGNING_PASS: pass } },
        { label: "deploy (wrangler deploy)", cmd: npx.cmd, args: ["wrangler", "deploy"], cwd: join(root, "backend", "worker"), shell: npx.shell },
      ]);
      return send(res, 200, { msg: "publishing" });
    }
    if (p === "/api/migrate") {
      const f = String(b.file || "");
      if (!/^\d{4}_[a-z0-9_]+\.sql$/.test(f) || !existsSync(join(root, "backend", "worker", "migrations", f))) throw new Error("unknown migration");
      run(`Migration ${f}`, [{ label: `d1 execute ${f}`, cmd: npx.cmd, args: ["wrangler", "d1", "execute", "nether", "--remote", `--file=migrations/${f}`], cwd: join(root, "backend", "worker"), shell: npx.shell }]);
      return send(res, 200, { msg: "running" });
    }
    if (p === "/api/migrations") return send(res, 200, { files: readdirSync(join(root, "backend", "worker", "migrations")).filter((f) => f.endsWith(".sql")) });
    return send(res, 404, { error: "not found" });
  } catch (e) {
    return send(res, 400, { error: e.message });
  }
});

server.listen(PORT, "127.0.0.1", () => {
  const link = `http://127.0.0.1:${PORT}/#${TOKEN}`;
  console.log(`\n  Nether Content Studio\n  ${link}\n\n  (this link is the key: don't share it. Ctrl+C stops the studio.)\n`);
  if (!process.env.STUDIO_NO_OPEN) {
    if (process.platform === "win32") spawn("rundll32", ["url.dll,FileProtocolHandler", link], { detached: true, stdio: "ignore", windowsHide: true }).unref();
    else spawn(process.platform === "darwin" ? "open" : "xdg-open", [link], { detached: true, stdio: "ignore" }).unref();
  }
});
