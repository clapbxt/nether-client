// Blockbench -> Nether cosmetic.
//   node tools/cosmetics/import.mjs <model.bbmodel | model.json> --id <id> --type hat|headband|shield --name "<name>"
//        [--rarity common|uncommon|rare|epic|legendary] [--desc "..."] [--frametime ms] [--offset x,y,z] [--animation <name>]
//   node tools/cosmetics/import.mjs --template hat|headband|shield <out.bbmodel>
// Writes backend/content/cosmetics/<id>.json + <id>.png and adds/updates the index entry; then sign the content.
//
// Build in Blockbench with the template (File > Open): the grey reference (player head / shield outline, elements named
// "_ref...") shows where things go and is dropped on import. Conventions: 1 unit = 1 skin pixel, the player looks north
// (-Z); hats and headbands: the head is the 8x8x8 box at (4,0,4)-(12,8,12) (the neck is (8,0,8)); shields: centred on
// (8,0,8), face to the north, and they replace the shield the player holds.
// Supported: cubes (any rotation, inflate, box or per-face UV, face rotation), groups with pivots and rotations, any
// number of textures (packed into one; a single texture may be animated, frames stacked vertically like Minecraft's),
// "light_emission" on cubes = glows, and one looping animation (rotation / position keyframes on groups; linear).
// .json: a Blockbench "Java Block/Item" export with its PNGs next to it (named like the texture paths).
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { inflateSync } from "node:zlib";
import { Canvas } from "./lib.mjs";

const out = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "backend", "content", "cosmetics");
const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(`--${k}`); return i >= 0 ? args[i + 1] : d; };
const die = (m) => { console.error(m); process.exit(1); };
const warn = (m) => console.warn(`warning: ${m}`);

// ---------------- PNG decode (8-bit, non-interlaced; grey / RGB / palette / grey+alpha / RGBA)
function decodePng(buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error("not a PNG");
  let o = 8, w = 0, h = 0, depth = 0, type = 0, inter = 0, pal = null, trns = null;
  const idat = [];
  while (o < buf.length) {
    const len = buf.readUInt32BE(o), t = buf.toString("latin1", o + 4, o + 8), d = buf.subarray(o + 8, o + 8 + len);
    if (t === "IHDR") { w = d.readUInt32BE(0); h = d.readUInt32BE(4); depth = d[8]; type = d[9]; inter = d[12]; }
    else if (t === "PLTE") pal = d;
    else if (t === "tRNS") trns = d;
    else if (t === "IDAT") idat.push(d);
    else if (t === "IEND") break;
    o += 12 + len;
  }
  if (depth !== 8 || inter) throw new Error("PNG must be 8-bit and not interlaced (re-save it from Blockbench)");
  const ch = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[type];
  const raw = inflateSync(Buffer.concat(idat)), stride = w * ch, px = Buffer.alloc(stride * h);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)], src = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)), row = px.subarray(y * stride, (y + 1) * stride), up = y ? px.subarray((y - 1) * stride, y * stride) : null;
    for (let i = 0; i < stride; i++) {
      const a = i >= ch ? row[i - ch] : 0, b = up ? up[i] : 0, c = up && i >= ch ? up[i - ch] : 0;
      let v = src[i];
      if (f === 1) v += a; else if (f === 2) v += b; else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      row[i] = v & 255;
    }
  }
  const cv = new Canvas(w, h);
  for (let i = 0; i < w * h; i++) {
    let r, g, b, al = 255;
    if (type === 6) { [r, g, b, al] = px.subarray(i * 4, i * 4 + 4); }
    else if (type === 2) { [r, g, b] = px.subarray(i * 3, i * 3 + 3); }
    else if (type === 0) { r = g = b = px[i]; }
    else if (type === 4) { r = g = b = px[i * 2]; al = px[i * 2 + 1]; }
    else { const k = px[i]; r = pal[k * 3]; g = pal[k * 3 + 1]; b = pal[k * 3 + 2]; al = trns && k < trns.length ? trns[k] : 255; }
    cv.d.set([r, g, b, al], i * 4);
  }
  return cv;
}

// ---------------- templates
function template(kind) {
  const ref = (name, from, to) => ({ name, type: "cube", from, to, origin: [8, 0, 8], rotation: [0, 0, 0], color: 8, faces: Object.fromEntries(["north", "east", "south", "west", "up", "down"].map((d) => [d, { uv: [0, 0, 1, 1], texture: null }])), uuid: crypto.randomUUID() });
  const els = kind === "shield"
    ? [ref("_ref_vanilla_shield_plate", [2, -11, 7], [14, 11, 8])]
    : [ref("_ref_head", [4, 0, 4], [12, 8, 12]), ref("_ref_hat_layer", [3.5, -0.5, 3.5], [12.5, 8.5, 12.5])];
  return { meta: { format_version: "4.10", model_format: "free", box_uv: false }, name: `nether_${kind}`, resolution: { width: 64, height: 64 },
    elements: els, outliner: [{ name: kind, origin: [8, 0, 8], rotation: [0, 0, 0], uuid: crypto.randomUUID(), children: els.map((e) => e.uuid) }], textures: [] };
}
if (args[0] === "--template") {
  const kind = args[1], f = args[2];
  if (!["hat", "headband", "shield"].includes(kind) || !f) die("usage: --template hat|headband|shield <out.bbmodel>");
  writeFileSync(f, JSON.stringify(template(kind), null, 1));
  console.log(`template -> ${f} (open it in Blockbench; elements named _ref... are left out on import)`);
  process.exit(0);
}

// ---------------- read the model
const file = args[0];
const id = opt("id"), type = opt("type"), name = opt("name");
if (!file || !existsSync(file)) die("usage: import.mjs <model.bbmodel|model.json> --id <id> --type hat|headband|shield --name <name> [...]");
if (!/^[a-z0-9_-]{1,32}$/.test(id || "")) die("--id: a-z 0-9 _ -, max 32");
if (!["hat", "headband", "shield"].includes(type)) die("--type: hat, headband or shield (auras are particle files, see backend/README.md)");
if (!name) die("--name is required");
const off = (opt("offset", "0,0,0")).split(",").map(Number);
const src = JSON.parse(readFileSync(file, "utf8"));
const bb = !!src.meta; // .bbmodel project

// textures: [{img: Canvas (one frame), uvW, uvH, frames}]
const texes = [];
if (bb) {
  for (const t of src.textures || []) {
    const m = /^data:image\/png;base64,(.*)$/.exec(t.source || "");
    if (!m) die(`texture "${t.name}" is not embedded; save the project with textures embedded`);
    const img = decodePng(Buffer.from(m[1], "base64"));
    texes.push({ img, uvW: t.uv_width || src.resolution?.width || 16, uvH: t.uv_height || src.resolution?.height || 16, frameTime: t.frame_time });
  }
} else {
  const names = Object.entries(src.textures || {}).filter(([k]) => k !== "particle");
  for (const [, path] of names) {
    const base = basename(String(path).replace(/^.*[:/]/, ""));
    const f = [join(dirname(file), `${base}.png`), join(dirname(file), base)].find(existsSync);
    if (!f) die(`texture "${path}": put ${base}.png next to ${basename(file)}`);
    texes.push({ img: decodePng(readFileSync(f)), uvW: 16, uvH: 16, key: names.length });
  }
}
if (!texes.length) die("the model has no texture");

// pack: one texture keeps its frames (animated); several are stacked (first frame each)
let atlas, aw, ah, frameH, place;
if (texes.length === 1) {
  const t = texes[0];
  const fh = Math.round(t.img.w * t.uvH / t.uvW) || t.img.h; // frame height from the UV aspect
  atlas = t.img; aw = t.img.w; frameH = Math.min(fh, t.img.h); ah = frameH;
  place = [{ x: 0, y: 0, w: aw, h: frameH }];
  if (t.img.h > frameH) console.log(`animated texture: ${Math.floor(t.img.h / frameH)} frames`);
} else {
  aw = Math.max(...texes.map((t) => t.img.w));
  place = [];
  let y = 0;
  for (const t of texes) {
    const fh = Math.min(t.img.h, Math.round(t.img.w * t.uvH / t.uvW) || t.img.h);
    if (t.img.h > fh) warn("several textures: only the first frame of animated ones is used");
    place.push({ x: 0, y, w: t.img.w, h: fh });
    y += fh;
  }
  ah = y; frameH = ah;
  atlas = new Canvas(aw, ah);
  texes.forEach((t, i) => { for (let yy = 0; yy < place[i].h; yy++) for (let x = 0; x < t.img.w; x++) atlas.set(x, place[i].y + yy, t.img.get(x, yy)); });
}
const r4 = (v) => Math.round(v * 10000) / 10000;
/** face uv (texture units) of texture ti -> our 0..16 over the packed atlas frame */
function uvOut(ti, uv) {
  const t = texes[ti], p = place[ti];
  const sx = p.w / (bb ? t.uvW : 16), sy = p.h / (bb ? t.uvH : 16);
  return [r4((p.x + uv[0] * sx) / aw * 16), r4((p.y + uv[1] * sy) / frameH * 16), r4((p.x + uv[2] * sx) / aw * 16), r4((p.y + uv[3] * sy) / frameH * 16)];
}

// ---------------- elements
const DIRS = ["north", "south", "east", "west", "up", "down"];
const els = [], byUuid = {};
function boxUv(e) {
  const [u, v] = e.uv_offset || [0, 0];
  const dx = e.to[0] - e.from[0], dy = e.to[1] - e.from[1], dz = e.to[2] - e.from[2];
  return { east: [u, v + dz, u + dz, v + dz + dy], north: [u + dz, v + dz, u + dz + dx, v + dz + dy], west: [u + dz + dx, v + dz, u + 2 * dz + dx, v + dz + dy],
    south: [u + 2 * dz + dx, v + dz, u + 2 * dz + 2 * dx, v + dz + dy], up: [u + dz + dx, v + dz, u + dz, v], down: [u + dz + 2 * dx, v, u + dz + dx, v + dz] };
}
const add = (v) => [v[0] + off[0], v[1] + off[1], v[2] + off[2]];
if (bb) {
  for (const e of src.elements || []) {
    if (e.type && e.type !== "cube") { warn(`"${e.name}" is a ${e.type}, only cubes are supported: skipped`); continue; }
    if (e.visibility === false || String(e.name || "").startsWith("_ref")) continue;
    const g = e.inflate || 0;
    const from = add(e.from.map((v) => v - g)), to = add(e.to.map((v, i) => v + g));
    const box = src.meta.box_uv || e.box_uv ? boxUv(e) : null;
    const faces = {};
    for (const d of DIRS) {
      const f = e.faces?.[d];
      if (!f || f.texture === null || f.texture === undefined || f.enabled === false) continue;
      const ti = typeof f.texture === "number" ? f.texture : texes.findIndex((t, i) => (src.textures[i].uuid === f.texture));
      if (ti < 0 || ti >= texes.length) continue;
      const uv = f.uv && !(box && f.uv.every((x) => x === 0)) ? f.uv : box ? box[d] : null;
      if (!uv) continue;
      faces[d] = { uv: uvOut(ti, uv), texture: "#0", ...(f.rotation ? { rotation: f.rotation } : {}) };
    }
    if (!Object.keys(faces).length) continue;
    const out = { name: e.name, from, to, faces };
    const rot = e.rotation || [0, 0, 0];
    if (rot.some((v) => v)) out.rotation = { x: rot[0], y: rot[1], z: rot[2], origin: add(e.origin || [8, 8, 8]) };
    if (e.light_emission) out.light_emission = e.light_emission;
    byUuid[e.uuid] = els.length;
    els.push(out);
  }
} else {
  const keyIdx = Object.keys(src.textures || {}).filter((k) => k !== "particle");
  for (const e of src.elements || []) {
    if (String(e.name || "").startsWith("_ref")) continue;
    const faces = {};
    for (const d of DIRS) {
      const f = e.faces?.[d];
      if (!f || !f.texture) continue;
      const ti = Math.max(0, keyIdx.indexOf(String(f.texture).replace("#", "")));
      faces[d] = { uv: uvOut(ti, f.uv || [0, 0, 16, 16]), texture: "#0", ...(f.rotation ? { rotation: f.rotation } : {}) };
    }
    if (!Object.keys(faces).length) continue;
    const out = { name: e.name, from: add(e.from), to: add(e.to), faces };
    if (e.rotation?.angle) out.rotation = { ...e.rotation, origin: add(e.rotation.origin || [8, 8, 8]) };
    if (e.light_emission) out.light_emission = e.light_emission;
    els.push(out);
  }
}
if (!els.length) die("nothing to import (only reference cubes?)");

// ---------------- groups
const groups = [], boneByUuid = {};
function group(g) {
  if (typeof g === "string") return byUuid[g] ?? null;
  if (typeof g === "number") return g;
  if (!g || typeof g !== "object") return null;
  if (String(g.name || "").startsWith("_ref")) return null;
  const node = { name: g.name, origin: add(g.origin || [8, 8, 8]), color: 0, children: [] };
  if ((g.rotation || []).some((v) => v)) node.rotation = g.rotation;
  if (g.uuid) boneByUuid[g.uuid] = g.name;
  for (const c of g.children || []) { const r = group(c); if (r !== null && r !== undefined) node.children.push(r); }
  return node;
}
for (const g of (bb ? src.outliner : src.groups) || []) { const r = group(g); if (r && typeof r === "object") groups.push(r); }

// ---------------- animation (one loop)
const anim = [];
if (bb && src.animations?.length) {
  const want = opt("animation");
  const a = src.animations.find((x) => x.name === want) || src.animations.find((x) => x.loop === "loop") || src.animations[0];
  const num = (v) => { const n = parseFloat(v); if (Number.isNaN(n)) { warn(`keyframe value "${v}" is not a number: 0 used`); return 0; } return n; };
  for (const [uuid, an] of Object.entries(a.animators || {})) {
    const bone = boneByUuid[uuid] || an.name;
    if (!bone || an.type === "effect") continue;
    const ch = (c) => (an.keyframes || []).filter((k) => k.channel === c).map((k) => { const p = k.data_points?.[0] || {}; return [r4(k.time), num(p.x), num(p.y), num(p.z)]; }).sort((x, y) => x[0] - y[0]);
    const rot = ch("rotation"), pos = ch("position");
    if (!rot.length && !pos.length) continue;
    if ((an.keyframes || []).some((k) => k.interpolation && k.interpolation !== "linear")) warn(`"${bone}": smooth/step keyframes are played linear`);
    anim.push({ group: bone, length: Math.max(0.05, a.length || 1), ...(rot.length ? { rotation: rot } : {}), ...(pos.length ? { position: pos } : {}) });
  }
  if (anim.length) console.log(`animation "${a.name}": ${anim.length} bone(s), ${a.length}s loop`);
}

// ---------------- write
const model = { credit: "Nether Client (Blockbench import)", texture_size: [aw, frameH], textures: { 0: "texture", particle: "texture" }, elements: els };
if (groups.length) model.groups = groups;
if (anim.length) model.nether = { anim };
mkdirSync(out, { recursive: true });
writeFileSync(join(out, `${id}.json`), JSON.stringify(model));
writeFileSync(join(out, `${id}.png`), atlas.png());
const idxFile = join(out, "index.json");
const index = existsSync(idxFile) ? JSON.parse(readFileSync(idxFile, "utf8")) : [];
const entry = { id, name, type, rarity: opt("rarity", "rare"), desc: opt("desc", "") };
const ft = opt("frametime") || (texes.length === 1 && texes[0].frameTime ? String(Math.round(texes[0].frameTime * 50)) : null);
if (ft) entry.frametime = Math.max(20, Math.min(2000, parseInt(ft, 10)));
const at = index.findIndex((c) => c.id === id);
if (at >= 0) index[at] = entry; else index.push(entry);
writeFileSync(idxFile, JSON.stringify(index, null, 1) + "\n");
console.log(`${type} "${name}" -> cosmetics/${id}.json + ${id}.png (${els.length} cubes, ${aw}x${atlas.h} texture). Next: node backend/content/sign.mjs sign`);
