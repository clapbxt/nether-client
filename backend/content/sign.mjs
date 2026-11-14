// Content signing for the Nether content repo (Node 18+, no dependencies).
//   node sign.mjs keygen   once: private key -> %USERPROFILE%\.nether-signing\content-ed25519.key (passphrase-protected,
//                          never commit it), public key (hex) -> crates/core/res/content-ed25519.pub (embedded in the launcher)
//   (keys made before passphrases existed: node tools/release-sign.mjs encrypt)
//   node sign.mjs sign     after every content change: writes manifest.json + manifest.sig next to this script
//                          (sha256 + size of every news/packs/capes/strict-servers... file; valid 90 days)
// Publish manifest.json, manifest.sig and the content files together. The launcher rejects anything that does not
// match the signed manifest and falls back to bundled defaults (no news / packs / capes).
import { createHash, sign } from "node:crypto";
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { keyDir, keygen, loadKey, pubHex } from "../../tools/signing-key.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const keyFile = join(keyDir, "content-ed25519.key");
const pubFile = join(here, "..", "..", "crates", "core", "res", "content-ed25519.pub");
const SKIP = new Set(["manifest.json", "manifest.sig", "sign.mjs", "README.md"]);
const VALID_DAYS = 90;

function files(dir) {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? files(p) : [p];
  });
}

// Cape PNG layout (same rule in core backend.rs and the agent's Capes.java): frames of 64:32 (cape + elytra) or 22:17
// (cape only; the game adds its own elytra), any whole scale, stacked vertically = animated. -> {s, n, full} or null.
function capeLayout(w, h) {
  for (const [bw, bh, full] of [[64, 32, true], [22, 17, false]]) {
    if (w % bw !== 0 || w === 0) continue;
    const s = w / bw, fh = s * bh;
    if (h % fh !== 0) continue;
    const n = h / fh;
    if (s <= 32 && n >= 1 && n <= 128 && n * s * s <= 2048) return { s, n, full };
  }
  return null;
}

const cmd = process.argv[2];
if (cmd === "keygen") {
  writeFileSync(pubFile, (await keygen(keyFile)) + "\n");
  console.log(`private key: ${keyFile}\npublic key:  ${pubFile}`);
} else if (cmd === "sign") {
  const key = await loadKey(keyFile);
  if (pubHex(key) !== readFileSync(pubFile, "utf8").trim()) throw new Error("private key does not match crates/core/res/content-ed25519.pub");
  // refuse to sign broken content: every .json must parse, every cape needs a valid id and a small PNG
  for (const p of files(here).filter((f) => f.endsWith(".json") && !SKIP.has(relative(here, f)))) {
    try { JSON.parse(readFileSync(p, "utf8")); } catch (e) { throw new Error(`${relative(here, p)} is not valid JSON: ${e.message}`); }
  }
  for (const c of JSON.parse(readFileSync(join(here, "capes", "index.json"), "utf8"))) {
    if (!/^[a-z0-9_-]{1,32}$/.test(c.id || "") || typeof c.name !== "string") throw new Error(`cape ${JSON.stringify(c)}: needs an "id" (a-z 0-9 _ -, max 32) and a "name"`);
    const png = join(here, "capes", `${c.id}.png`);
    if (!existsSync(png)) throw new Error(`cape "${c.id}": capes/${c.id}.png is missing`);
    const b = readFileSync(png);
    if (b.length > 4 * 1024 * 1024) throw new Error(`cape "${c.id}": PNG is over 4 MB`);
    if (b.length < 33 || b.readUInt32BE(0) !== 0x89504e47 || b.toString("latin1", 12, 16) !== "IHDR") throw new Error(`cape "${c.id}": not a PNG`);
    const L = capeLayout(b.readUInt32BE(16), b.readUInt32BE(20));
    if (!L) throw new Error(`cape "${c.id}": ${b.readUInt32BE(16)}x${b.readUInt32BE(20)} is not a cape layout (frames of 64:32 cape+elytra or 22:17 cape only, `
      + "stacked vertically when animated; at most 2048 px wide, 128 frames, 4 Mpx after conversion to 64:32)");
    if (c.frametime !== undefined && !(Number.isInteger(c.frametime) && c.frametime >= 20 && c.frametime <= 2000)) throw new Error(`cape "${c.id}": "frametime" is ms per frame, 20..2000`);
  }
  // cosmetics (hats, headbands, auras, shields): same rules as core cosmetics.rs and the agent's CosModel / CosAura
  const cosIdx = join(here, "cosmetics", "index.json");
  if (existsSync(cosIdx)) {
    const seen = new Set();
    for (const c of JSON.parse(readFileSync(cosIdx, "utf8"))) {
      const at = `cosmetic ${JSON.stringify(c.id)}`;
      if (!/^[a-z0-9_-]{1,32}$/.test(c.id || "") || typeof c.name !== "string" || !c.name.trim()) throw new Error(`${at}: needs an "id" (a-z 0-9 _ -, max 32) and a "name"`);
      if (seen.has(c.id)) throw new Error(`${at}: duplicate id`);
      seen.add(c.id);
      if (!["hat", "headband", "aura", "shield"].includes(c.type)) throw new Error(`${at}: "type" must be hat, headband, aura or shield`);
      if (c.rarity !== undefined && !["common", "uncommon", "rare", "epic", "legendary"].includes(c.rarity)) throw new Error(`${at}: "rarity" must be common, uncommon, rare, epic or legendary`);
      if (c.frametime !== undefined && !(Number.isInteger(c.frametime) && c.frametime >= 20 && c.frametime <= 2000)) throw new Error(`${at}: "frametime" is ms per frame, 20..2000`);
      const js = join(here, "cosmetics", `${c.id}.json`), png = join(here, "cosmetics", `${c.id}.png`);
      if (!existsSync(js) || !existsSync(png)) throw new Error(`${at}: cosmetics/${c.id}.json and cosmetics/${c.id}.png are both needed`);
      if (statSync(js).size > 512 * 1024) throw new Error(`${at}: model JSON is over 512 KB`);
      const model = JSON.parse(readFileSync(js, "utf8"));
      if (c.type === "aura") { if (!model || typeof model !== "object" || Array.isArray(model)) throw new Error(`${at}: aura JSON must be an object`); }
      else if (!Array.isArray(model.elements) || !model.elements.length || model.elements.length > 2048) throw new Error(`${at}: model needs 1..2048 "elements" (Blockbench Java Block/Item format)`);
      const b = readFileSync(png);
      if (b.length > 4 * 1024 * 1024) throw new Error(`${at}: PNG is over 4 MB`);
      if (b.length < 33 || b.readUInt32BE(0) !== 0x89504e47 || b.toString("latin1", 12, 16) !== "IHDR") throw new Error(`${at}: not a PNG`);
      const w = b.readUInt32BE(16), h = b.readUInt32BE(20);
      if (!w || !h || w > 4096 || h > 8192) throw new Error(`${at}: texture ${w}x${h} is out of range (max 4096 x 8192)`);
    }
  }
  const map = {};
  for (const p of files(here).sort()) {
    const rel = relative(here, p).split(sep).join("/");
    if (SKIP.has(rel) || rel.startsWith(".")) continue;
    const b = readFileSync(p);
    map[rel] = { sha256: createHash("sha256").update(b).digest("hex"), size: b.length };
  }
  const t = Math.floor(Date.now() / 1000);
  const manifest = JSON.stringify({ version: t, issued: t, expires: t + VALID_DAYS * 86400, files: map }, null, 1);
  writeFileSync(join(here, "manifest.json"), manifest);
  writeFileSync(join(here, "manifest.sig"), sign(null, Buffer.from(manifest), key).toString("hex") + "\n");
  console.log(`signed ${Object.keys(map).length} files, valid ${VALID_DAYS} days (re-sign before then)`);
} else {
  console.error("usage: node sign.mjs keygen | sign");
  process.exit(2);
}
