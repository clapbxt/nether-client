// Signing keys for releases (tools/release-sign.mjs) and content (backend/content/sign.mjs). Node 18+, no dependencies.
// Keys live in %USERPROFILE%\.nether-signing as passphrase-encrypted PKCS8 PEM (AES-256). The passphrase comes from a
// hidden prompt, or NETHER_SIGNING_PASS for scripted use. Keep an offline backup of the folder.
import { createPrivateKey, createPublicKey, generateKeyPairSync } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

export const keyDir = join(homedir(), ".nether-signing");
const CIPHER = "aes-256-cbc";

/** Raw 32-byte Ed25519 public key (hex) of a private key. */
export const pubHex = (key) => createPublicKey(key).export({ type: "spki", format: "der" }).subarray(-32).toString("hex");

function readHidden(prompt) {
  if (process.env.NETHER_SIGNING_PASS) return Promise.resolve(process.env.NETHER_SIGNING_PASS);
  if (!process.stdin.isTTY) return Promise.reject(new Error("no terminal to ask for the key passphrase (set NETHER_SIGNING_PASS)"));
  return new Promise((resolve, reject) => {
    const stdin = process.stdin;
    let s = "";
    const done = (err) => {
      stdin.setRawMode(false);
      stdin.pause();
      stdin.removeListener("data", on);
      process.stdout.write("\n");
      if (err) reject(err); else resolve(s);
    };
    const on = (chunk) => {
      for (const ch of chunk) {
        if (ch === "\r" || ch === "\n") return done();
        if (ch === "\u0003") return done(new Error("cancelled"));
        if (ch === "\u0008" || ch === "\u007f") s = s.slice(0, -1); else s += ch;
      }
    };
    process.stdout.write(prompt);
    stdin.setRawMode(true);
    stdin.setEncoding("utf8");
    stdin.on("data", on);
    stdin.resume();
  });
}

/** The private key in `file`, asking for its passphrase. A plain (unencrypted) key still works but is warned about. */
export async function loadKey(file) {
  const pem = readFileSync(file, "utf8");
  if (!pem.includes("ENCRYPTED")) {
    console.warn(`WARNING: ${file} is not passphrase-protected. Run: node tools/release-sign.mjs encrypt`);
    return createPrivateKey(pem);
  }
  return createPrivateKey({ key: pem, format: "pem", passphrase: await readHidden(`Passphrase for ${file}: `) });
}

async function newPassphrase(what) {
  if (process.env.NETHER_SIGNING_PASS) return process.env.NETHER_SIGNING_PASS;
  const a = await readHidden(`New passphrase for ${what} (12+ characters): `);
  if (a.length < 12) throw new Error("passphrase too short (12+ characters)");
  if (a !== (await readHidden("Repeat it: "))) throw new Error("the passphrases differ");
  return a;
}

// encrypted copy is decrypted and compared before it replaces anything
function writeEncrypted(file, key, passphrase) {
  const pem = key.export({ type: "pkcs8", format: "pem", cipher: CIPHER, passphrase });
  if (pubHex(createPrivateKey({ key: pem, format: "pem", passphrase })) !== pubHex(key)) throw new Error(`encrypting ${file} failed its check`);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file + ".tmp", pem, { mode: 0o600 });
  renameSync(file + ".tmp", file);
}

/** A new encrypted key in `file` (never overwrites). Returns its public key (hex). */
export async function keygen(file) {
  if (existsSync(file)) throw new Error(`refusing to overwrite ${file}`);
  const { privateKey } = generateKeyPairSync("ed25519");
  writeEncrypted(file, privateKey, await newPassphrase(file));
  return pubHex(privateKey);
}

/** Passphrase-protect every plain key in the signing folder. */
export async function encryptAll() {
  const plain = existsSync(keyDir) ? readdirSync(keyDir).filter((n) => n.endsWith(".key")).map((n) => join(keyDir, n))
    .filter((f) => !readFileSync(f, "utf8").includes("ENCRYPTED")) : [];
  if (!plain.length) { console.log("every key is already passphrase-protected"); return; }
  const pass = await newPassphrase(`${plain.length} key(s)`);
  for (const f of plain) { writeEncrypted(f, createPrivateKey(readFileSync(f, "utf8")), pass); console.log(`encrypted ${f}`); }
}
