// Generates the starter Nether cosmetics into backend/content/cosmetics/ (models, textures, index.json).
//   node tools/cosmetics/gen.mjs
// Then sign the content (node backend/content/sign.mjs sign). Hand-made Blockbench models drop into the same folder:
// <id>.json (Java Block/Item model, see CosModel.java) + <id>.png, plus an index.json entry.
import { mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Model, Canvas, art, hex, mix, shade, alpha, ramp, hash, noise, fbm } from "./lib.mjs";

const out = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "backend", "content", "cosmetics");
mkdirSync(out, { recursive: true });
const index = [];
function save(meta, built) {
  writeFileSync(join(out, `${meta.id}.json`), JSON.stringify(built.json));
  writeFileSync(join(out, `${meta.id}.png`), built.png);
  index.push(meta);
  console.log(`${meta.type.padEnd(8)} ${meta.id.padEnd(16)} ${built.w}x${built.h}`);
}

const clamp = (v, a = 0, b = 1) => Math.max(a, Math.min(b, v));
const GOLD = [[0, hex("#5c3405")], [0.3, hex("#a8650f")], [0.6, hex("#e2a524")], [0.85, hex("#ffd965")], [1, hex("#fff4c2")]];
const gold = (t) => ramp(GOLD, clamp(t));
const STEEL = [[0, hex("#2b2f36")], [0.4, hex("#5d646e")], [0.75, hex("#9aa3ad")], [1, hex("#e3e8ee")]];
const steel = (t) => ramp(STEEL, clamp(t));

// ============================================================== HATS
function topHat() {
  const m = new Model({ D: 4, W: 256 });
  const felt = (d, i, j, w, h, [x, y, z]) => {
    const base = hex("#24242e");
    const streak = noise(x * 2.2 + z * 2.2, y * 0.35, 3) * 0.18 + fbm(x * 3, z * 3 + y, 5) * 0.12;
    let c = shade(base, 0.85 + streak + (y - 9) * 0.018);
    if (d === "up") { const r = Math.hypot(x - 8, z - 8); c = mix(c, hex("#3a3a48"), clamp(0.55 - r * 0.12)); }
    return c;
  };
  const brim = (d, i, j, w, h, [x, y, z]) => {
    const edge = Math.min(x - 2.6, 13.4 - x, z - 2.6, 13.4 - z);
    let c = shade(hex("#17171d"), 0.9 + fbm(x * 2, z * 2, 9) * 0.25);
    if (d === "up" && edge < 0.55) c = mix(c, hex("#3b3b4a"), 0.7);
    if (d === "down") c = shade(c, 0.7);
    return c;
  };
  const band = (d, i, j, w, h, [x, y, z]) => {
    const t = (y - 9.3) / 1.6;
    let c = mix(hex("#7d0b14"), hex("#d0222f"), 0.35 + 0.5 * Math.sin(t * Math.PI));
    if (j === 1 || j === h - 2) c = shade(c, 0.75);
    return shade(c, 0.95 + noise(x * 6 + z * 6, y * 6, 2) * 0.1);
  };
  const buckle = (d, i, j, w, h, [x, y]) => {
    const fx = (x - 6.9) / 2.2, fy = (y - 9.15) / 1.9;
    const frame = fx < 0.22 || fx > 0.78 || fy < 0.24 || fy > 0.76;
    if (d === "north" && !frame) return null;
    return gold(0.45 + (1 - fy) * 0.45 + (frame && (i === 0 || j === 0) ? 0.15 : 0));
  };
  m.box([2.6, 8.4, 2.6], [13.4, 9.3, 13.4], brim);
  m.box([4.4, 9.3, 4.4], [11.6, 16.8, 11.6], felt, { faces: ["north", "south", "east", "west", "up"] });
  m.box([4.25, 9.3, 4.25], [11.75, 10.9, 11.75], band, { faces: ["north", "south", "east", "west"], bevel: 0 });
  m.box([6.9, 9.15, 4.0], [9.1, 11.05, 4.3], buckle, { faces: ["north", "east", "west", "up", "down"] });
  save({ id: "top_hat", name: "Top Hat", type: "hat", rarity: "rare", desc: "Felt, silk band, gold buckle." }, m.build());
}

function emberCrown() {
  const F = 6;
  const m = new Model({ D: 4, W: 256, frames: F });
  const wall = (d, i, j, w, h, [x, y, z]) => {
    const t = (y - 7.2) / 2.4;
    let v = 0.35 + t * 0.45 + fbm(x * 3 + z * 3, y * 3, 4) * 0.12;
    if (d === "north" || d === "south" || d === "east" || d === "west") {
      if (Math.abs(t - 0.28) < 0.06 || Math.abs(t - 0.86) < 0.05) v -= 0.25; // engraved lines
      const a = (d === "north" || d === "south" ? x : z) * 1.4;
      if (t > 0.38 && t < 0.78 && Math.abs((a % 1.4) - 0.7) < 0.2) v += 0.2; // studs
    }
    if (d === "up") v += 0.25;
    if (d === "down") v -= 0.2;
    return gold(v);
  };
  const spike = (d, i, j, w, h, [x, y, z]) => gold(0.45 + (y - 9.6) * 0.14 + (d === "up" ? 0.3 : 0) + noise(x * 5 + z * 5, y * 5, 7) * 0.12 - (d === "down" ? 0.3 : 0));
  const gem = (hue) => (d, i, j, w, h, p, f) => {
    const cx = (i + 0.5) / w - 0.4, cy = (j + 0.5) / h - 0.35, r = Math.hypot(cx, cy);
    const pulse = 0.5 + 0.5 * Math.sin((f / F) * Math.PI * 2 + hue * 3);
    const core = clamp(1 - r * 2.4 + pulse * 0.2);
    const c = hue < 0.5 ? ramp([[0, hex("#6e0610")], [0.45, hex("#d4202a")], [0.75, hex("#ff7a2a")], [1, hex("#fff1b0")]], core)
      : ramp([[0, hex("#7a2a02")], [0.45, hex("#e26a10")], [0.8, hex("#ffc340")], [1, hex("#fffbe0")]], core);
    return d === "down" ? shade(c, 0.7) : c;
  };
  // ring
  m.box([3.3, 7.2, 3.3], [12.7, 9.6, 4.0], wall);
  m.box([3.3, 7.2, 12.0], [12.7, 9.6, 12.7], wall);
  m.box([3.3, 7.2, 4.0], [4.0, 9.6, 12.0], wall);
  m.box([12.0, 7.2, 4.0], [12.7, 9.6, 12.0], wall);
  // corner posts
  for (const [cx, cz] of [[3.9, 3.9], [12.1, 3.9], [3.9, 12.1], [12.1, 12.1]]) {
    m.box([cx - 0.6, 9.6, cz - 0.6], [cx + 0.6, 10.9, cz + 0.6], spike);
    m.box([cx - 0.35, 10.9, cz - 0.35], [cx + 0.35, 11.7, cz + 0.35], spike);
    m.box([cx - 0.42, 11.7, cz - 0.42], [cx + 0.42, 12.4, cz + 0.42], gem(0.8), { glow: true, bevel: 0 });
  }
  // centre spikes (front tallest)
  const centres = [[8, 3.65, 1.25], [8, 12.35, 1], [3.65, 8, 1], [12.35, 8, 1]];
  for (const [cx, cz, k] of centres) {
    const hw = 0.8, hz = 0.35;
    const ax = cz === 3.65 || cz === 12.35;
    const [wx, wz] = ax ? [hw, hz] : [hz, hw];
    m.box([cx - wx, 9.6, cz - wz], [cx + wx, 9.6 + 1.7 * k, cz + wz], spike);
    m.box([cx - wx * 0.55, 9.6 + 1.7 * k, cz - wz], [cx + wx * 0.55, 9.6 + 2.9 * k, cz + wz], spike);
    m.box([cx - 0.5, 9.6 + 2.9 * k, cz - 0.5], [cx + 0.5, 9.6 + 3.9 * k, cz + 0.5], gem(0.8), { glow: true, bevel: 0 });
  }
  // front ruby
  m.box([7.1, 7.75, 3.0], [8.9, 9.25, 3.35], gem(0.2), { glow: true, bevel: 0 });
  for (const sx of [5.2, 10.8]) m.box([sx - 0.45, 8.05, 3.05], [sx + 0.45, 8.95, 3.35], gem(0.2), { glow: true, bevel: 0 });
  save({ id: "ember_crown", name: "Ember Crown", type: "hat", rarity: "legendary", desc: "Forged gold, smouldering rubies.", frametime: 140 }, m.build());
}

function wizardHat() {
  const F = 4;
  const m = new Model({ D: 4, W: 256, frames: F });
  const cloth = (d, i, j, w, h, [x, y, z], f) => {
    let c = mix(hex("#1d1147"), hex("#3c2589"), clamp(0.35 + fbm(x * 1.6 + z * 1.3, y * 1.2, 11) * 0.7));
    c = shade(c, 0.9 + (y - 9) * 0.012);
    const s = hash(Math.floor(x * 4 + z * 4 * 1.7), Math.floor(y * 4), 21);
    if (s > 0.986) { // stars twinkle
      const tw = 0.5 + 0.5 * Math.sin((f / F) * Math.PI * 2 + s * 400);
      c = mix(c, hex("#ffe9a0"), 0.4 + tw * 0.6);
    } else if (s > 0.975) c = mix(c, hex("#9c8cff"), 0.35);
    if (d === "down") c = shade(c, 0.6);
    return c;
  };
  const brim = (d, i, j, w, h, [x, y, z], f) => {
    let c = cloth(d, i, j, w, h, [x, y, z], f);
    const edge = Math.min(x - 1.8, 14.2 - x, z - 1.8, 14.2 - z);
    if (d === "up" && edge < 0.5) c = mix(c, hex("#5b45c4"), 0.5);
    return c;
  };
  const band = (d, i, j, w, h, [x, y]) => gold(0.4 + (1 - (y - 9) / 1) * 0.1 + (j < 2 ? 0.35 : 0) + noise(x * 8, y * 8, 5) * 0.1);
  const star = (d, i, j, w, h, p, f) => {
    const cx = (i + 0.5) / w * 2 - 1, cy = (j + 0.5) / h * 2 - 1;
    const a = Math.atan2(cy, cx), r = Math.hypot(cx, cy);
    const lim = 0.45 + 0.5 * Math.pow(Math.abs(Math.cos(a * 2.5)), 3);
    if (r > lim) return null;
    return mix(hex("#ffb92e"), hex("#fffbe6"), clamp(1 - r * 1.3 + 0.1 * Math.sin(f * 1.6)));
  };
  m.box([1.8, 8.2, 1.8], [14.2, 9.0, 14.2], brim);
  m.box([3.9, 9.0, 3.9], [12.1, 11.6, 12.1], cloth, { faces: ["north", "south", "east", "west", "up"] });
  m.box([3.8, 9.0, 3.8], [12.2, 10.0, 12.2], band, { faces: ["north", "south", "east", "west"], bevel: 0 });
  m.box([6.85, 9.95, 3.62], [9.15, 12.0, 3.72], star, { faces: ["north"], glow: true, bevel: 0 });
  m.box([4.9, 11.6, 5.1], [11.1, 14.0, 11.3], cloth, { faces: ["north", "south", "east", "west", "up"] });
  m.group("tip", [8, 14, 8.6]);
  m.box([5.9, 14.0, 6.4], [10.1, 16.2, 10.6], cloth, { group: "tip", faces: ["north", "south", "east", "west", "up"] });
  m.box([6.8, 16.0, 7.6], [9.2, 18.0, 10.0], cloth, { group: "tip", rotation: { angle: -12, axis: "x", origin: [8, 16.0, 8.8] } });
  m.box([7.3, 17.4, 9.0], [8.7, 18.8, 11.6], cloth, { group: "tip", rotation: { angle: -35, axis: "x", origin: [8, 17.4, 9.6] } });
  m.anim({ group: "tip", sway: [6, 0, 5], period: 3.2 });
  save({ id: "wizard_hat", name: "Starweaver", type: "hat", rarity: "epic", desc: "Twinkling stars, a tip with a mind of its own.", frametime: 220 }, m.build());
}

function propellerCap() {
  const m = new Model({ D: 4, W: 256 });
  const cols = [hex("#e8323a"), hex("#ffc93a"), hex("#2f7df0"), hex("#3cc35a")];
  const dome = (d, i, j, w, h, [x, y, z]) => {
    const a = (Math.atan2(z - 8, x - 8) + Math.PI * 2 + Math.PI / 4) % (Math.PI * 2);
    const q = Math.floor(a / (Math.PI / 2));
    const seam = Math.abs((a % (Math.PI / 2)) - Math.PI / 4) > Math.PI / 4 - 0.07;
    let c = seam ? hex("#f4f4f4") : cols[q];
    c = shade(c, 0.88 + fbm(x * 4 + z * 4, y * 4, 3) * 0.2 + (y - 6) * 0.03);
    if (d === "down") c = shade(c, 0.6);
    return c;
  };
  const bill = (d, i, j, w, h, [x, y, z]) => shade(hex("#d42a33"), d === "up" ? 1.05 : 0.75);
  const metal = (d, i, j, w, h, [x, y, z]) => steel(0.5 + (d === "up" ? 0.3 : 0) + noise(x * 6, y * 6 + z * 6, 4) * 0.2);
  const blade = (d, i, j, w, h, [x, y, z]) => {
    const r = Math.hypot(x - 8, z - 8);
    let c = r > 3.2 ? hex("#ffc93a") : hex("#e8323a");
    if (r > 4.6) c = hex("#f4f4f4");
    return shade(c, d === "down" ? 0.75 : 1);
  };
  m.box([3.4, 6.2, 3.4], [12.6, 9.4, 12.6], dome);
  m.box([4.4, 9.4, 4.4], [11.6, 10.0, 11.6], dome, { faces: ["north", "south", "east", "west", "up"] });
  m.box([4.4, 6.2, 1.1], [11.6, 6.75, 3.4], bill, { rotation: { angle: 10, axis: "x", origin: [8, 6.5, 3.4] } });
  m.box([7.6, 10.0, 7.6], [8.4, 11.6, 8.4], metal);
  m.group("prop", [8, 11.9, 8]);
  m.box([7.3, 11.6, 7.3], [8.7, 12.25, 8.7], metal, { group: "prop" });
  m.box([3.0, 11.75, 7.45], [13.0, 12.05, 8.55], blade, { group: "prop" });
  m.box([7.45, 11.75, 3.0], [8.55, 12.05, 13.0], blade, { group: "prop" });
  m.anim({ group: "prop", spin: [0, 540, 0] });
  save({ id: "propeller_cap", name: "Propeller Cap", type: "hat", rarity: "uncommon", desc: "Spins forever. Lift not included." }, m.build());
}

// ============================================================== HEADBANDS
/** the four walls of a band around the head at height y0..y1, `t` thick, pushed out by `o` */
function ring(m, y0, y1, t, o, paint, opts = {}) {
  const a = 3.4 - o, b = 12.6 + o;
  m.box([a, y0, a], [b, y1, a + t], paint, opts);
  m.box([a, y0, b - t], [b, y1, b], paint, opts);
  m.box([a, y0, a + t], [a + t, y1, b - t], paint, opts);
  m.box([b - t, y0, a + t], [b, y1, b - t], paint, opts);
}

function ninjaBand() {
  const m = new Model({ D: 4, W: 256 });
  const cloth = (d, i, j, w, h, [x, y, z]) => {
    const fold = Math.sin((y - 4.9) * 4 + fbm(x + z, y, 2) * 3) * 0.08;
    return shade(hex("#a3121e"), 0.9 + fold + fbm(x * 3 + z * 3, y * 8, 7) * 0.18 - (d === "down" ? 0.25 : 0));
  };
  const plate = (d, i, j, w, h) => {
    const u = (i + 0.5) / w, v = (j + 0.5) / h;
    if (d !== "north") return steel(0.45);
    let c = steel(0.55 + (1 - v) * 0.3 + noise(i * 0.4, j * 0.4, 8) * 0.08);
    // rivets
    for (const [rx, ry] of [[0.07, 0.2], [0.93, 0.2], [0.07, 0.8], [0.93, 0.8]]) if (Math.hypot((u - rx) * w, (v - ry) * h) < 1.3) c = steel(0.95);
    // engraved flame mark
    const cx = (u - 0.5) * w / h, cy = v - 0.55;
    const r = Math.hypot(cx * 1.15, cy + 0.08);
    const flame = r < 0.3 && cy > -0.38 + Math.abs(cx) * 0.9;
    const inner = Math.hypot(cx * 1.2, cy - 0.02) < 0.15;
    if (flame && !inner) c = steel(0.18);
    return c;
  };
  const knot = (d, i, j, w, h, [x, y, z]) => shade(hex("#8e0f19"), 0.85 + fbm(x * 5, y * 5 + z * 5, 1) * 0.3);
  ring(m, 4.9, 6.5, 0.28, 0, cloth);
  m.box([5.4, 4.65, 3.0], [10.6, 6.75, 3.32], plate);
  m.box([7.0, 4.55, 12.55], [9.0, 6.85, 13.35], knot);
  m.group("tail1", [7.4, 5.6, 13.1]);
  m.group("tail2", [8.6, 5.6, 13.1]);
  m.box([6.7, 0.4, 12.95], [7.9, 5.6, 13.15], cloth, { group: "tail1", rotation: { angle: 22.5, axis: "x", origin: [7.3, 5.6, 13.05] } });
  m.box([8.1, 1.0, 12.95], [9.3, 5.6, 13.15], cloth, { group: "tail2", rotation: { angle: 30, axis: "x", origin: [8.7, 5.6, 13.05] } });
  m.anim({ group: "tail1", sway: [14, 0, 6], period: 1.5 });
  m.anim({ group: "tail2", sway: [12, 0, -7], period: 1.7, phase: 0.3 });
  save({ id: "ninja_band", name: "Shinobi Band", type: "headband", rarity: "rare", desc: "Steel plate, tails that catch the wind." }, m.build());
}

function flameBand() {
  const F = 8;
  const m = new Model({ D: 4, W: 256, frames: F });
  const band = (d, i, j, w, h, [x, y, z]) => {
    let c = shade(hex("#231716"), 0.9 + fbm(x * 4 + z * 4, y * 6, 3) * 0.3);
    const crack = Math.abs(fbm(x * 1.6 + z * 1.6, y * 2, 13) - 0.5) < 0.035;
    if (crack && d !== "down") c = mix(c, hex("#ff7a1a"), 0.85);
    return c;
  };
  // flames: scrolling noise shaped into tongues, white core -> orange -> red, transparent above
  const fire = (d, i, j, w, h, [x, y, z], f) => {
    const u = (d === "north" || d === "south" ? x : z) * 1.0, v = (y - 6.2) / 4.4; // 0 bottom .. 1 top
    const n = fbm(u * 1.3 + (d === "east" || d === "west" ? 40 : 0), v * 2.2 - (f / F) * 2.0, 31, 4);
    const tongue = 0.5 + 0.5 * Math.sin(u * 2.4 + Math.sin(v * 3 + f * 0.8) * 0.6);
    const heat = n * 1.25 + tongue * 0.35 - v * 1.25 + 0.15;
    if (heat < 0.18) return null;
    const c = ramp([[0.18, hex("#7a0d05", 120)], [0.3, hex("#d8340c", 220)], [0.5, hex("#ff8a1c")], [0.72, hex("#ffd24a")], [1, hex("#fff8dc")]], heat);
    return c;
  };
  ring(m, 4.9, 6.5, 0.28, 0, band);
  const a = 3.15, b = 12.85;
  m.box([a, 6.1, a], [b, 10.6, a], fire, { faces: ["north", "south"], glow: true, bevel: 0 });
  m.box([a, 6.1, b], [b, 10.6, b], fire, { faces: ["north", "south"], glow: true, bevel: 0 });
  m.box([a, 6.1, a], [a, 10.6, b], fire, { faces: ["east", "west"], glow: true, bevel: 0 });
  m.box([b, 6.1, a], [b, 10.6, b], fire, { faces: ["east", "west"], glow: true, bevel: 0 });
  save({ id: "flame_band", name: "Inferno Band", type: "headband", rarity: "legendary", desc: "Living flame, wrapped around a charred band.", frametime: 90 }, m.build());
}

function neonBand() {
  const F = 8;
  const m = new Model({ D: 4, W: 256, frames: F });
  const band = (d, i, j, w, h, [x, y, z]) => shade(hex("#101015"), 0.9 + (d === "up" ? 0.4 : 0) + noise(x * 6 + z * 6, y * 6, 5) * 0.15);
  const neon = (d, i, j, w, h, [x, y, z], f) => {
    const s = ((x + z) / 18 + f / F) % 1;
    const c = ramp([[0, hex("#29f0ff")], [0.33, hex("#7b5cff")], [0.66, hex("#ff3fd2")], [1, hex("#29f0ff")]], s);
    const core = Math.abs((j + 0.5) / h - 0.5) < 0.25;
    return core ? mix(c, hex("#ffffff"), 0.55) : c;
  };
  const gem = (d, i, j, w, h, p, f) => {
    const cx = Math.abs((i + 0.5) / w - 0.5), cy = Math.abs((j + 0.5) / h - 0.5);
    if (cx + cy > 0.5) return null;
    return mix(hex("#ff3fd2"), hex("#ffffff"), clamp(0.8 - (cx + cy) * 2 + 0.2 * Math.sin(f / F * Math.PI * 2)));
  };
  ring(m, 5.05, 6.35, 0.25, 0, band);
  ring(m, 5.45, 5.95, 0.12, 0.13, neon, { glow: true, bevel: 0 });
  m.box([7.25, 5.0, 3.05], [8.75, 6.5, 3.15], gem, { faces: ["north"], glow: true, bevel: 0 });
  save({ id: "neon_band", name: "Synthwave Band", type: "headband", rarity: "epic", desc: "A ribbon of light that never stops moving.", frametime: 110 }, m.build());
}

// ============================================================== SHIELDS
/**
 * A shield slab: rows of 1 px boxes following a symmetric silhouette (half width hw(Y), Y = 0 at the centre),
 * front/back faces cut to the exact outline at texel resolution, ledges and edges only where they show.
 */
function slab(m, hw, y0, y1, z0, z1, front, back, rim, opts = {}) {
  const inside = (x, y) => Math.abs(x - 8) <= hw(y) + 1e-6;
  const step = opts.step || 1;
  for (let y = y0; y < y1 - 1e-6; y += step) {
    const ya = y, yb = Math.min(y1, y + step);
    let mx = 0;
    for (let k = 0; k <= 8; k++) mx = Math.max(mx, hw(ya + (yb - ya) * k / 8));
    if (mx <= 0.05) continue;
    const above = yb < y1 ? Math.max(...[0, 0.25, 0.5, 0.75, 1].map((k) => hw(yb + step * k))) : -1;
    const below = ya > y0 ? Math.max(...[0, 0.25, 0.5, 0.75, 1].map((k) => hw(ya - step * k))) : -1;
    const paint = (d, i, j, w, h, p, f) => {
      const [x, yy, z] = p;
      if (d === "north") return inside(x, yy) ? front(x, yy, f) : null;
      if (d === "south") return inside(x, yy) ? back(x, yy, f) : null;
      if (d === "east" || d === "west") return hw(yy) >= mx - 0.45 ? rim(x, yy, z, f) : null;
      const lim = d === "up" ? above : below; // a ledge shows only past the neighbour row
      return Math.abs(x - 8) <= mx && Math.abs(x - 8) > lim - 0.02 && inside(x, d === "up" ? yb - 0.01 : ya + 0.01) ? rim(x, yy, z, f) : null;
    };
    m.box([8 - mx, ya, z0], [8 + mx, yb, z1], paint, { bevel: 0 });
  }
}

/** emissive details in front of a shield: a plane cut to `fn` (null = transparent) */
function overlay(m, x0, y0, x1, y1, z, fn) {
  m.box([x0, y0, z], [x1, y1, z + 0.02], (d, i, j, w, h, [x, y], f) => fn(x, y, f), { faces: ["north"], glow: true, bevel: 0 });
}

function knightShield() {
  const m = new Model({ D: 4, W: 256 });
  const hw = (y) => (y >= 1 ? (y <= 7 ? 6 : 0) : y < -8 ? 0 : 6 * Math.sqrt(clamp(1 - Math.pow((1 - y) / 9, 2))));
  const dist = (x, y) => { // distance to the outline (px), approximate
    let d = Math.min(7 - y, hw(y) - Math.abs(x - 8));
    for (let k = 1; k <= 12 && d > 0; k++) { const e = k * 0.15; if (Math.abs(x - 8) > hw(y - e) || Math.abs(x - 8) > hw(y + e)) { d = Math.min(d, e); break; } }
    return d;
  };
  const front = (x, y) => {
    const e = dist(x, y), X = x - 8;
    if (e < 0.85) return steel(0.45 + (e < 0.3 ? 0.35 : 0.1) + noise(x * 4, y * 4, 3) * 0.1);
    if (e < 1.15) return steel(0.22);
    const boss = Math.hypot(X, y - 1.2);
    if (boss < 1.5) return gold(0.95 - boss * 0.35);
    if (boss < 1.8) return gold(0.3);
    const q = (X < 0) !== (y < 1.2);
    const base = q ? hex("#b8141f") : hex("#ece4d2");
    let c = shade(base, 0.9 + fbm(x * 2, y * 2, q ? 5 : 6) * 0.2);
    if (Math.abs(X) < 0.35 || Math.abs(y - 1.2) < 0.35) c = gold(0.55); // gold cross
    // rivets along the rim
    for (let k = -5; k <= 6; k += 2.2) if (Math.abs(e - 1.55) < 0.4 && Math.abs(((x + y) * 0.5) % 2.2) < 0.35) c = steel(0.85);
    return c;
  };
  const back = (x, y) => {
    const plank = Math.floor((x + 0.3) / 2.4);
    let c = shade(hex("#6b4424"), 0.8 + hash(plank, 0, 2) * 0.25 + fbm(x * 0.8, y * 3, 1) * 0.2);
    if (Math.abs(y - 2.5) < 0.6 || Math.abs(y + 2.5) < 0.6) c = shade(hex("#3a2414"), 1.1);
    return c;
  };
  const rim = (x, y, z) => steel(0.55 + noise(y * 5, z * 5, 9) * 0.2);
  slab(m, hw, -8, 7, 7.2, 8.8, front, back, rim);
  save({ id: "knight_shield", name: "Crusader Shield", type: "shield", rarity: "rare", desc: "Quartered heater, iron rim, gold boss." }, m.build());
}

function obsidianShield() {
  const F = 6;
  const m = new Model({ D: 4, W: 256, frames: F });
  const hw = (y) => { const a = Math.abs(y - 0.5); return a > 8.2 ? 0 : 6.2 * Math.min(1, (8.2 - a) / 4.2); };
  const facet = (x, y) => {
    const X = x - 8, Y = y - 0.5;
    const a = Math.atan2(Y, X), k = Math.floor(((a + Math.PI) / (Math.PI * 2)) * 6);
    return 0.75 + hash(k, 3, 1) * 0.5 - Math.hypot(X, Y) * 0.02;
  };
  const vein = (x, y, f) => {
    const v = Math.abs(fbm(x * 0.55, y * 0.55, 23, 4) - 0.5);
    return v < 0.045 ? 1 - v / 0.045 : 0;
  };
  const front = (x, y, f) => {
    const e = hw(y) - Math.abs(x - 8);
    let c = shade(mix(hex("#0e0818"), hex("#2c1650"), fbm(x * 0.9, y * 0.9, 4)), facet(x, y));
    if (e < 0.7) c = mix(c, hex("#5a2d9c"), 0.6);
    if (vein(x, y) > 0) c = mix(c, hex("#7a2cc8"), 0.6);
    return c;
  };
  const back = (x, y) => shade(hex("#140b22"), 0.9 + fbm(x, y, 2) * 0.3);
  const rim = (x, y, z) => mix(hex("#1a0d2e"), hex("#6a3ab8"), 0.25 + noise(y * 4, z * 4, 2) * 0.3);
  slab(m, hw, -7.7, 8.7, 7.2, 8.8, front, back, rim, { step: 0.5 });
  overlay(m, 1.6, -7.7, 14.4, 8.7, 7.17, (x, y, f) => {
    if (Math.abs(x - 8) > hw(y) - 0.4) return null;
    const v = vein(x, y);
    if (v <= 0) return null;
    const pulse = 0.55 + 0.45 * Math.sin((f / F) * Math.PI * 2 - (y + 8) * 0.35);
    return alpha(mix(hex("#a640ff"), hex("#f2c8ff"), v * pulse), 120 + 135 * v * pulse);
  });
  save({ id: "obsidian_shield", name: "Voidglass", type: "shield", rarity: "legendary", desc: "Cut obsidian, veins that pulse with violet light.", frametime: 120 }, m.build());
}

function emberShield() {
  const F = 6;
  const m = new Model({ D: 4, W: 256, frames: F });
  const R = 7;
  const hw = (y) => { const a = Math.abs(y - 0.5); return a >= R ? 0 : Math.sqrt(R * R - a * a); };
  const front = (x, y) => {
    const X = x - 8, Y = y - 0.5, r = Math.hypot(X, Y);
    if (r > R - 0.9) return steel(0.4 + (r > R - 0.35 ? 0.35 : 0.12) + noise(x * 4, y * 4, 1) * 0.1);
    if (r > R - 1.2) return steel(0.15);
    const a = Math.atan2(Y, X);
    let c = shade(hex("#2a2324"), 0.85 + fbm(x * 1.5, y * 1.5, 6) * 0.35);
    if (Math.abs(r - (R - 2)) < 0.35 && Math.abs(((a / (Math.PI * 2)) * 12 + 12) % 1 - 0.5) < 0.12) c = steel(0.85); // rivets
    return c;
  };
  const back = (x, y) => shade(hex("#3a2a20"), 0.9 + fbm(x * 0.8, y * 2, 3) * 0.3);
  const rim = (x, y, z) => steel(0.5 + noise(y * 4, z * 4, 7) * 0.2);
  slab(m, hw, -6.5, 7.5, 7.2, 8.8, front, back, rim, { step: 0.5 });
  // glowing fireball emblem (the Nether mark) + ember cracks
  const ball = [
    "......aab...",
    ".....abbba..",
    "....abbcba..",
    "...abccccba.",
    "..abccddcba.",
    ".abccdeedcba",
    ".abcdeeeedca",
    ".abcdeeeedca",
    "..abcdeedcba",
    "..abccddcba.",
    "...abcccba..",
    ".....aaa....",
  ];
  const lv = { a: 0.15, b: 0.38, c: 0.6, d: 0.8, e: 1 };
  overlay(m, 1.2, -6.3, 14.8, 7.3, 7.17, (x, y, f) => {
    const X = x - 8, Y = y - 0.5, r = Math.hypot(X, Y);
    if (r > R - 1.3) return null;
    const flick = 0.85 + 0.15 * Math.sin((f / F) * Math.PI * 2);
    // emblem: 12x12 art over 8x8 px in the centre
    const gx = Math.floor((X + 4) / 8 * 12), gy = Math.floor((4.2 - Y) / 8 * 12);
    if (gx >= 0 && gx < 12 && gy >= 0 && gy < 12) {
      const ch = ball[gy][gx];
      if (ch !== ".") return ramp([[0, hex("#8c1406")], [0.4, hex("#e8460f")], [0.7, hex("#ff9a26")], [0.9, hex("#ffd75a")], [1, hex("#fff8e0")]], lv[ch] * flick);
    }
    const crack = Math.abs(fbm(Math.atan2(Y, X) * 2.2, r * 0.45, 41) - 0.5) < 0.03 && r > 4.8;
    if (crack) return alpha(hex("#ff6a1a"), 200 * flick);
    return null;
  });
  save({ id: "ember_shield", name: "Hearthguard", type: "shield", rarity: "legendary", desc: "Blackened steel around a burning Nether heart.", frametime: 130 }, m.build());
}

function frostShield() {
  const m = new Model({ D: 4, W: 256 });
  const ice = (len) => (d, i, j, w, h, [x, y, z]) => {
    const along = d === "north" || d === "south" ? (j + 0.5) / h : 0.5;
    const edge = Math.min(i, w - 1 - i, j, h - 1 - j);
    let c = mix(hex("#78c3ec", 235), hex("#e6faff", 245), clamp(0.3 + fbm(x * 1.2, y * 1.2, 3) * 0.5 + (1 - Math.abs(along - 0.5) * 2) * 0.15));
    if (edge === 0) c = hex("#ffffff", 250);
    else if (d === "north" && edge === 1) c = mix(c, hex("#ffffff"), 0.4);
    if (d === "south") c = shade(c, 0.85);
    return c;
  };
  // three crossing arms, each with two pairs of branches (rotations around the shield centre)
  const O = [8, 0.5, 8];
  for (let k = 0; k < 3; k++) {
    const ang = k * 60, dz = k * 0.02;
    m.box([7.15, -7.4, 7.5 + dz], [8.85, 8.4, 8.5 - dz], ice(16), { rotation: { angle: ang, axis: "z", origin: O } });
    for (const sgn of [1, -1]) for (const r of [3.2, 5.6]) for (const side of [1, -1]) {
      // branch at distance r along the arm (both ends), angled 45 deg outward
      const cy = O[1] + sgn * r, len = r > 4 ? 2.4 : 3.2;
      const b = ang + side * sgn * 45;
      // position the branch so that rotating by b about O lands its centre at (arm-local) (side * len/2 * sin45, ...)
      const lx = -side * (len / 2) * Math.SQRT1_2, ly = sgn * r + sgn * (len / 2) * Math.SQRT1_2;
      const rr = (-side * sgn * 45) * Math.PI / 180; // undo the extra 45 so the final centre is right
      const px = lx * Math.cos(rr) - (ly - 0) * Math.sin(rr), py = lx * Math.sin(rr) + (ly - 0) * Math.cos(rr);
      m.box([O[0] + px - 0.55, O[1] + py - len / 2, 7.6 + dz], [O[0] + px + 0.55, O[1] + py + len / 2, 8.4 - dz], ice(len), { rotation: { angle: b, axis: "z", origin: O } });
      void cy;
    }
  }
  // crystal hex core
  const core = (d, i, j, w, h, [x, y]) => {
    const X = x - 8, Y = y - 0.5;
    const hx = Math.max(Math.abs(X) * 0.866 + Math.abs(Y) * 0.5, Math.abs(Y));
    if (hx > 2.3) return null;
    return mix(hex("#bdeeff", 250), hex("#ffffff", 255), clamp(1 - hx / 2.3 + (d === "north" ? 0.1 : -0.2)));
  };
  m.box([5.3, -2.2, 7.2], [10.7, 3.2, 8.8], core, { bevel: 0, faces: ["north", "south"] });
  m.box([6.5, -0.7, 7.05], [9.5, 1.7, 7.2], (d, i, j, w, h, [x, y]) => {
    const X = x - 8, Y = y - 0.5, hx = Math.max(Math.abs(X) * 0.866 + Math.abs(Y) * 0.5, Math.abs(Y));
    return hx > 1.15 ? null : mix(hex("#7fe3ff"), hex("#ffffff"), clamp(1 - hx));
  }, { faces: ["north"], glow: true, bevel: 0 });
  save({ id: "frost_shield", name: "Winter's Ward", type: "shield", rarity: "epic", desc: "A snowflake of living ice." }, m.build());
}

// ============================================================== AURAS
/** sprite sheet: rows = variants, columns = frames; draw(cv, x, y, row, col) */
function sheet(cell, rows, cols, draw) {
  const cv = new Canvas(cell * cols, cell * rows);
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) draw(cv, c * cell, r * cell, r, c);
  return cv.png();
}
function saveAura(meta, params, png) {
  writeFileSync(join(out, `${meta.id}.json`), JSON.stringify(params));
  writeFileSync(join(out, `${meta.id}.png`), png);
  index.push(meta);
  console.log(`aura     ${meta.id.padEnd(16)}`);
}
/** intensity mask (digits 1-5) recoloured through a palette function */
function mask(cv, x, y, rows, col) { rows.forEach((r, j) => [...r].forEach((ch, i) => { if (ch >= "1" && ch <= "9") cv.set(x + i, y + j, col(+ch / 5)); })); }

function emberAura() {
  const shapes = [
    ["........", "........", "...1....", "..131...", "...1....", "........", "........", "........"],
    ["........", "...11...", "..1331..", ".135531.", ".135531.", "..1331..", "...11...", "........"],
    ["...1....", "...21...", "..242...", "..3531..", ".25553..", ".24542..", "..232...", "........"],
  ];
  const life = [[0, hex("#fffbe0")], [0.2, hex("#ffd34a")], [0.45, hex("#ff8a1c")], [0.7, hex("#d8340c")], [0.88, hex("#5a1c12", 200)], [1, hex("#3a3434", 120)]];
  const png = sheet(8, 3, 6, (cv, x, y, r, c) => mask(cv, x, y, shapes[r], (k) => { const base = ramp(life, c / 5); return mix(shade(base, 0.6), mix(base, hex("#ffffff"), c < 2 ? 0.3 : 0), k); }));
  saveAura({ id: "ember_aura", name: "Ember Aura", type: "aura", rarity: "legendary", desc: "Cinders rise and swirl around you." },
    { count: 30, life: [1.1, 2.1], size: [2.0, 3.6], shape: "column", radius: [3, 10], height: [0, 14], velocity: [0, 11, 0], jitter: [2, 3, 2], orbit: 25, wobble: [1.2, 1.5], fade: [0.08, 0.45], grow: [0.7, 1, 0.5], frames: "life", glow: true, cell: 8 }, png);
}

function frostAura() {
  const flakes = [
    ["....1....", ".1..2..1.", "..1.3.1..", "...242...", "1234543 21".replace(" ", ""), "...242...", "..1.3.1..", ".1..2..1.", "....1...."],
    [".........", "....2....", "..1.3.1..", "...343...", ".2345432.", "...343...", "..1.3.1..", "....2....", "........."],
    [".........", ".........", "...1.1...", "....3....", "..13531..", "....3....", "...1.1...", ".........", "........."],
  ];
  const png = sheet(9, 3, 2, (cv, x, y, r, c) => mask(cv, x, y, flakes[r], (k) => mix(hex("#8fd6ff", 220), c === 1 ? hex("#ffffff") : hex("#e8f8ff"), k)));
  saveAura({ id: "frost_aura", name: "Frost Aura", type: "aura", rarity: "epic", desc: "Your own private snowfall." },
    { count: 26, life: [2.2, 3.4], size: [2.0, 3.2], shape: "column", radius: [4, 12], height: [20, 36], velocity: [0, -9, 0], jitter: [1.5, 1.5, 1.5], orbit: 18, wobble: [1.8, 0.7], fade: [0.15, 0.3], grow: [0.85, 1, 0.8], frames: "random", glow: true, cell: 9 }, png);
}

function enchantAura() {
  const runes = [
    ["..3....", ".3.3...", "3.3.3..", ".3.3...", "..3....", "..3....", "......."],
    ["33333..", "3...3..", "..3....", ".333...", "..3....", ".......", "......."],
    ["3...3..", ".333...", ".3.3...", ".333...", "3...3..", ".......", "......."],
    ["..3....", "..3....", "33333..", "3.3.3..", "..3....", ".3.3...", "......."],
    [".333...", "3.3.3..", "3.3.3..", "..3....", ".333...", ".......", "......."],
    ["3.3....", ".3.3...", "..3.3..", ".3.3...", "3.3....", ".......", "......."],
  ];
  const png = sheet(8, 6, 2, (cv, x, y, r, c) => mask(cv, x + 1, y + 1, runes[r], () => (c === 0 ? hex("#f4e9ff") : hex("#b07cff"))));
  saveAura({ id: "enchant_aura", name: "Arcane Aura", type: "aura", rarity: "epic", desc: "Runes of power orbit you." },
    { count: 20, life: [2.4, 3.6], size: [2.6, 3.4], shape: "ring", radius: [9, 11], height: [2, 28], velocity: [0, 2, 0], orbit: 55, wobble: [0.8, 0.5], fade: [0.25, 0.35], grow: [0.8, 1, 0.9], frames: "loop", fps: 2, glow: true, cell: 8 }, png);
}

function soulAura() {
  const wisps = [
    ["...1....", "...21...", "..131...", "..242...", ".13531..", ".24542..", "..343...", "...2...."],
    ["....1...", "...12...", "...131..", "..242...", "..3531..", ".24542..", "..343...", "...2...."],
    ["...1....", "..12....", "..131...", "...242..", "..1353..", ".24542..", "..343...", "...2...."],
    ["....1...", "...21...", "..131...", "..242...", ".13531..", "..4542..", "..343...", "...2...."],
  ];
  const png = sheet(8, 1, 4, (cv, x, y, r, c) => mask(cv, x, y, wisps[c], (k) => ramp([[0, hex("#0b4f6c", 160)], [0.4, hex("#1fb4d6")], [0.7, hex("#6ff2ff")], [1, hex("#e8ffff")]], k)));
  saveAura({ id: "soul_aura", name: "Soul Aura", type: "aura", rarity: "epic", desc: "Cold blue soulfire, drifting upward." },
    { count: 18, life: [1.6, 2.6], size: [2.6, 4.2], shape: "column", radius: [4, 10], height: [0, 10], velocity: [0, 7, 0], jitter: [1, 1.5, 1], wobble: [1.5, 1.1], fade: [0.15, 0.4], grow: [0.6, 1, 0.4], frames: "loop", fps: 10, glow: true, cell: 8 }, png);
}

for (const f of [emberCrown, topHat, wizardHat, propellerCap, flameBand, neonBand, ninjaBand, emberAura, enchantAura, soulAura, frostAura, obsidianShield, emberShield, frostShield, knightShield]) f();
// keep cosmetics added by hand / by import.mjs
const idxFile = join(out, "index.json");
const kept = existsSync(idxFile) ? JSON.parse(readFileSync(idxFile, "utf8")).filter((c) => !index.some((g) => g.id === c.id)) : [];
writeFileSync(idxFile, JSON.stringify([...index, ...kept], null, 1) + "\n");
console.log(`${index.length} cosmetics -> ${out}`);
void readFileSync; void existsSync; void art;
