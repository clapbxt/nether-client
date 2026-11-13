// Content signing for the Nether content repo (Node 18+, no dependencies).
//   node sign.mjs keygen   once: private key -> %USERPROFILE%\.nether-signing\content-ed25519.key (never commit it),
//                          public key (hex) -> crates/core/res/content-ed25519.pub (embedded in the launcher)
//   node sign.mjs sign     after every content change: writes manifest.json + manifest.sig next to this script
//                          (sha256 + size of every news/packs/capes/strict-servers... file; valid 90 days)
// Publish manifest.json, manifest.sig and the content files together. The launcher rejects anything that does not
// match the signed manifest and falls back to bundled defaults (no news / packs / capes).
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const keyFile = join(homedir(), ".nether-signing", "content-ed25519.key");
const pubFile = join(here, "..", "..", "crates", "core", "res", "content-ed25519.pub");
const SKIP = new Set(["manifest.json", "manifest.sig", "sign.mjs", "README.md"]);
const VALID_DAYS = 90;

function files(dir) {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? files(p) : [p];
  });
}

const cmd = process.argv[2];
if (cmd === "keygen") {
  if (existsSync(keyFile)) { console.error(`refusing to overwrite ${keyFile}`); process.exit(1); }
  const { privateKey, publicKey } = generateKeyPairSync("ed25519");
  mkdirSync(dirname(keyFile), { recursive: true });
  writeFileSync(keyFile, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600 });
  const raw = publicKey.export({ type: "spki", format: "der" }).subarray(-32); // raw 32-byte key
  writeFileSync(pubFile, raw.toString("hex") + "\n");
  console.log(`private key: ${keyFile}\npublic key:  ${pubFile}`);
} else if (cmd === "sign") {
  const key = createPrivateKey(readFileSync(keyFile));
  if (createPublicKey(key).export({ type: "spki", format: "der" }).subarray(-32).toString("hex") !== readFileSync(pubFile, "utf8").trim())
    throw new Error("private key does not match crates/core/res/content-ed25519.pub");
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
