// Release signing for the launcher's auto-updater (Node 18+, no dependencies). Keys: see tools/signing-key.mjs.
//   node tools/release-sign.mjs keygen                 once: release key -> %USERPROFILE%\.nether-signing\release-ed25519.key
//   node tools/release-sign.mjs keygen-backup          once: a backup key to keep OFFLINE (USB stick, not this PC); its
//                                                      public half is embedded too, so a lost or leaked main key can be replaced
//   node tools/release-sign.mjs encrypt                passphrase-protect keys made before encryption existed
//   node tools/release-sign.mjs sign <file> <version> [--backup]
//       <file>.vsig: Ed25519 over "nether-update\n<version>\n<sha256 of file>\n" (launchers 0.1.6+ require it, so an old
//                    setup can never pass for a newer version)
//       <file>.sig:  Ed25519 over the file's bytes (only for launchers up to 0.1.5)
// crates/core/res/release-ed25519.pub: one hex public key per line; the launcher accepts a signature from any of them.
import { createHash, sign } from "node:crypto";
import { appendFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { encryptAll, keyDir, keygen, loadKey, pubHex } from "./signing-key.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const mainKey = join(keyDir, "release-ed25519.key"), backupKey = join(keyDir, "release-backup-ed25519.key");
const pubFile = join(here, "..", "crates", "core", "res", "release-ed25519.pub");
const embedded = () => (existsSync(pubFile) ? readFileSync(pubFile, "utf8") : "").split(/\r?\n/).map((l) => l.trim()).filter((l) => /^[0-9a-f]{64}$/.test(l));

const [cmd, file, version, flag] = process.argv.slice(2);
try {
  if (cmd === "keygen") {
    const pub = await keygen(mainKey);
    writeFileSync(pubFile, [pub, ...embedded().slice(1)].join("\n") + "\n");
    console.log(`private key: ${mainKey}\npublic key:  ${pubFile}`);
  } else if (cmd === "keygen-backup") {
    const pub = await keygen(backupKey);
    appendFileSync(pubFile, pub + "\n");
    console.log(`backup key: ${backupKey}\nIts public key was added to ${pubFile}. Move the key file OFF this PC now (keep the passphrase separately).`);
  } else if (cmd === "encrypt") {
    await encryptAll();
  } else if (cmd === "sign" && file && /^\d+\.\d+\.\d+$/.test(version || "")) {
    const key = await loadKey(flag === "--backup" ? backupKey : mainKey);
    if (!embedded().includes(pubHex(key))) throw new Error(`this key is not listed in ${pubFile}`);
    if (embedded().length < 2) console.warn("WARNING: no backup release key is embedded; run: node tools/release-sign.mjs keygen-backup");
    const bytes = readFileSync(file);
    const sha = createHash("sha256").update(bytes).digest("hex");
    writeFileSync(file + ".vsig", sign(null, Buffer.from(`nether-update\n${version}\n${sha}\n`), key).toString("hex") + "\n");
    writeFileSync(file + ".sig", sign(null, bytes, key).toString("hex") + "\n");
    console.log(`signed ${file} as ${version}`);
  } else {
    console.error("usage: node tools/release-sign.mjs keygen | keygen-backup | encrypt | sign <file> <x.y.z> [--backup]");
    process.exit(2);
  }
} catch (e) {
  console.error(e.message || e);
  process.exit(1);
}
