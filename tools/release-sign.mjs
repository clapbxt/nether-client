// Release signing for the launcher's auto-updater (Node 18+, no dependencies).
//   node tools/release-sign.mjs keygen       once: private key -> %USERPROFILE%\.nether-signing\release-ed25519.key
//                                            (never commit it, back it up), public key (hex) -> crates/core/res/release-ed25519.pub
//   node tools/release-sign.mjs sign <file>  writes <file>.sig: hex Ed25519 signature over the file's bytes
// The launcher only installs an update whose .sig verifies against the embedded public key.
import { createPrivateKey, createPublicKey, generateKeyPairSync, sign } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const keyFile = join(homedir(), ".nether-signing", "release-ed25519.key");
const pubFile = join(here, "..", "crates", "core", "res", "release-ed25519.pub");

const [cmd, file] = process.argv.slice(2);
if (cmd === "keygen") {
  if (existsSync(keyFile)) { console.error(`refusing to overwrite ${keyFile}`); process.exit(1); }
  const { privateKey, publicKey } = generateKeyPairSync("ed25519");
  mkdirSync(dirname(keyFile), { recursive: true });
  writeFileSync(keyFile, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600 });
  writeFileSync(pubFile, publicKey.export({ type: "spki", format: "der" }).subarray(-32).toString("hex") + "\n");
  console.log(`private key: ${keyFile}\npublic key:  ${pubFile}`);
} else if (cmd === "sign" && file) {
  const key = createPrivateKey(readFileSync(keyFile));
  if (createPublicKey(key).export({ type: "spki", format: "der" }).subarray(-32).toString("hex") !== readFileSync(pubFile, "utf8").trim())
    throw new Error("private key does not match crates/core/res/release-ed25519.pub");
  writeFileSync(file + ".sig", sign(null, readFileSync(file), key).toString("hex") + "\n");
  console.log(`signed ${file}`);
} else {
  console.error("usage: node tools/release-sign.mjs keygen | sign <file>");
  process.exit(2);
}
