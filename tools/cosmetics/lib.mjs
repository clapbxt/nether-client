// Pixel-art + Blockbench model toolkit for the Nether cosmetics generator (Node 18+, no dependencies).
// Models are Blockbench "Java Block/Item" JSON (what the agent's CosModel and the launcher's cosmetics.rs read):
// units = skin pixels, (8, 0, 8) = attachment pivot, the player looks north (-Z). Textures are painted per face at
// D texels per model pixel; animated textures stack frames vertically.
import { deflateSync } from "node:zlib";

// ---------------- PNG ----------------
const CRC = new Uint32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc32 = (buf) => { let c = 0xffffffff; for (const b of buf) c = CRC[(c ^ b) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, "latin1"), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
export function png(w, h, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; rgba.copy ? rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4) : raw.set(rgba.subarray(y * w * 4, (y + 1) * w * 4), y * (w * 4 + 1) + 1); }
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr), chunk("IDAT", deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}

// ---------------- colour ----------------
export const hex = (s, a = 255) => { const v = parseInt(s.replace("#", ""), 16); return [(v >> 16) & 255, (v >> 8) & 255, v & 255, a]; };
export const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * Math.max(0, Math.min(1, t))));
export const shade = (c, f) => [Math.max(0, Math.min(255, Math.round(c[0] * f))), Math.max(0, Math.min(255, Math.round(c[1] * f))), Math.max(0, Math.min(255, Math.round(c[2] * f))), c[3]];
export const alpha = (c, a) => [c[0], c[1], c[2], Math.round(a)];
/** colour ramp: stops = [[t, colour], ...] sorted by t */
export function ramp(stops, t) {
  if (t <= stops[0][0]) return stops[0][1];
  for (let i = 1; i < stops.length; i++) if (t <= stops[i][0]) return mix(stops[i - 1][1], stops[i][1], (t - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0]));
  return stops[stops.length - 1][1];
}

// ---------------- noise ----------------
export function hash(x, y, s = 0) {
  let h = (Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263) + Math.imul(s | 0, 1442695041)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}
const fade = (t) => t * t * (3 - 2 * t);
export function noise(x, y, s = 0) {
  const xi = Math.floor(x), yi = Math.floor(y), xf = fade(x - xi), yf = fade(y - yi);
  const a = hash(xi, yi, s), b = hash(xi + 1, yi, s), c = hash(xi, yi + 1, s), d = hash(xi + 1, yi + 1, s);
  return a + (b - a) * xf + (c - a) * yf + (a - b - c + d) * xf * yf;
}
export const fbm = (x, y, s = 0, o = 3) => { let v = 0, a = 0.5, f = 1, t = 0; for (let i = 0; i < o; i++) { v += noise(x * f, y * f, s + i * 17) * a; t += a; a *= 0.5; f *= 2; } return v / t; };

// ---------------- canvas ----------------
export class Canvas {
  constructor(w, h) { this.w = w; this.h = h; this.d = new Uint8Array(w * h * 4); }
  set(x, y, c) {
    if (!c || x < 0 || y < 0 || x >= this.w || y >= this.h) return;
    const i = (y * this.w + x) * 4;
    if (c[3] >= 255 || this.d[i + 3] === 0) { this.d.set(c, i); return; }
    const a = c[3] / 255, b = this.d[i + 3] / 255, o = a + b * (1 - a);
    for (let k = 0; k < 3; k++) this.d[i + k] = Math.round((c[k] * a + this.d[i + k] * b * (1 - a)) / o);
    this.d[i + 3] = Math.round(o * 255);
  }
  get(x, y) { const i = (y * this.w + x) * 4; return [this.d[i], this.d[i + 1], this.d[i + 2], this.d[i + 3]]; }
  png() { return png(this.w, this.h, Buffer.from(this.d.buffer)); }
}

/** Draw ASCII art (rows of palette keys, '.' = transparent) at (x, y). */
export function art(cv, x, y, rows, pal) {
  rows.forEach((r, j) => [...r].forEach((ch, i) => { if (ch !== "." && ch !== " " && pal[ch]) cv.set(x + i, y + j, pal[ch]); }));
}

// ---------------- model builder ----------------
const DIRS = ["north", "south", "east", "west", "up", "down"];
/** corners TL, BL, BR, TR (vanilla FaceInfo order) of a face */
function corners(d, [x1, y1, z1], [x2, y2, z2]) {
  switch (d) {
    case "north": return [[x2, y2, z1], [x2, y1, z1], [x1, y1, z1], [x1, y2, z1]];
    case "south": return [[x1, y2, z2], [x1, y1, z2], [x2, y1, z2], [x2, y2, z2]];
    case "east": return [[x2, y2, z2], [x2, y1, z2], [x2, y1, z1], [x2, y2, z1]];
    case "west": return [[x1, y2, z1], [x1, y1, z1], [x1, y1, z2], [x1, y2, z2]];
    case "up": return [[x1, y2, z1], [x1, y2, z2], [x2, y2, z2], [x2, y2, z1]];
    default: return [[x1, y1, z2], [x1, y1, z1], [x2, y1, z1], [x2, y1, z2]];
  }
}
function faceSize(d, s) { return d === "north" || d === "south" ? [s[0], s[1]] : d === "east" || d === "west" ? [s[2], s[1]] : [s[0], s[2]]; }

export class Model {
  /** D = texels per model pixel, W = atlas width, frames = animation frames */
  constructor({ D = 4, W = 256, frames = 1 } = {}) {
    this.D = D; this.W = W; this.frames = frames;
    this.els = []; this.groups = []; this.faces = []; this.anims = [];
    this.cx = 0; this.cy = 0; this.rowH = 0;
  }
  alloc(w, h) {
    w = Math.max(1, Math.ceil(w)); h = Math.max(1, Math.ceil(h));
    if (this.cx + w > this.W) { this.cx = 0; this.cy += this.rowH + 1; this.rowH = 0; }
    const r = [this.cx, this.cy, w, h];
    this.cx += w + 1; this.rowH = Math.max(this.rowH, h);
    return r;
  }
  /**
   * A box. paint(face, i, j, w, h, world [x,y,z], frame) -> colour | null (transparent). opts: faces (list),
   * rotation {angle, axis, origin}, glow, group (name), bevel (edge light/dark), inflate.
   */
  box(from, to, paint, opts = {}) {
    const size = [to[0] - from[0], to[1] - from[1], to[2] - from[2]];
    const faces = {};
    for (const d of opts.faces || DIRS) {
      const [fw, fh] = faceSize(d, size);
      if (fw <= 0 || fh <= 0) continue;
      const r = this.alloc(fw * this.D, fh * this.D);
      const c = corners(d, from, to);
      this.faces.push({ d, r, c, paint, bevel: opts.bevel ?? 0.1 });
      faces[d] = { uv: r, texture: "#0" };
    }
    const el = { from, to, faces };
    if (opts.rotation) el.rotation = { angle: 0, axis: "y", origin: [8, 8, 8], ...opts.rotation };
    if (opts.glow) el.light_emission = 15;
    if (opts.name) el.name = opts.name;
    this.els.push(el);
    if (opts.group) (this.groups.find((g) => g.name === opts.group) || this.group(opts.group)).children.push(this.els.length - 1);
    return this.els.length - 1;
  }
  group(name, origin = [8, 8, 8]) { const g = { name, origin, color: 0, children: [] }; this.groups.push(g); return g; }
  anim(a) { this.anims.push(a); }
  /** -> { json, png } */
  build(extra = {}) {
    const W = this.W, H = Math.max(1, this.cy + this.rowH);
    const cv = new Canvas(W, H * this.frames);
    for (let f = 0; f < this.frames; f++) for (const fc of this.faces) {
      const [x0, y0, w, h] = fc.r, [tl, bl, , tr] = fc.c;
      for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) {
        const s = (i + 0.5) / w, t = (j + 0.5) / h;
        const wp = [0, 1, 2].map((k) => tl[k] + (tr[k] - tl[k]) * s + (bl[k] - tl[k]) * t);
        let c = fc.paint(fc.d, i, j, w, h, wp, f);
        if (!c) continue;
        if (fc.bevel && c[3] > 0) {
          if (i === 0 || j === 0) c = shade(c, 1 + fc.bevel * 0.8);
          else if (i === w - 1 || j === h - 1) c = shade(c, 1 - fc.bevel);
        }
        cv.set(x0 + i, y0 + j + f * H, c);
      }
    }
    const els = this.els.map((e) => ({ ...e, faces: Object.fromEntries(Object.entries(e.faces).map(([d, f]) => {
      const [x, y, w, h] = f.uv;
      const r4 = (v) => Math.round(v * 10000) / 10000;
      return [d, { uv: [r4(x / W * 16), r4(y / H * 16), r4((x + w) / W * 16), r4((y + h) / H * 16)], texture: "#0" }];
    })) }));
    const json = { credit: "Nether Client", texture_size: [W, H], textures: { 0: "texture", particle: "texture" }, elements: els };
    if (this.groups.length) json.groups = this.groups;
    const nether = { ...extra };
    if (this.anims.length) nether.anim = this.anims;
    if (Object.keys(nether).length) json.nether = nether;
    return { json, png: cv.png(), w: W, h: H * this.frames };
  }
}
