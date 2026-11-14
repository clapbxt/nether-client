// WebGL preview of the player wearing a cape or cosmetic: the same model / aura rules as core cosmetics.rs and the
// agent's CosModel / CosAura (Blockbench Java model JSON, pivot (8,0,8), front -Z; stateless aura particles).
// Preview space: skin pixels, Y up, origin between the feet, the player faces +Z.

const num = (v, d) => (typeof v === "number" && isFinite(v) ? v : d);
const vec3 = (v, d) => [0, 1, 2].map((i) => num(Array.isArray(v) ? v[i] : undefined, d));
const DIRS = ["north", "south", "east", "west", "up", "down"];
const NORMALS = [[0, 0, -1], [0, 0, 1], [1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0]];

function corners(d, [x1, y1, z1], [x2, y2, z2]) {
  return [
    [[x2, y2, z1], [x2, y1, z1], [x1, y1, z1], [x1, y2, z1]],
    [[x1, y2, z2], [x1, y1, z2], [x2, y1, z2], [x2, y2, z2]],
    [[x2, y2, z2], [x2, y1, z2], [x2, y1, z1], [x2, y2, z1]],
    [[x1, y2, z1], [x1, y1, z1], [x1, y1, z2], [x1, y2, z2]],
    [[x1, y2, z1], [x1, y2, z2], [x2, y2, z2], [x2, y2, z1]],
    [[x1, y1, z2], [x1, y1, z1], [x2, y1, z1], [x2, y1, z2]],
  ][d];
}
function defaultUv(d, [x1, y1, z1], [x2, y2, z2]) {
  return [[16 - x2, 16 - y2, 16 - x1, 16 - y1], [x1, 16 - y2, x2, 16 - y1], [16 - z2, 16 - y2, 16 - z1, 16 - y1], [z1, 16 - y2, z2, 16 - y1], [x1, z1, x2, z2], [x1, 16 - z2, x2, 16 - z1]][d];
}
function around(o, rx, ry, rz) {
  const [cx, sx, cy, sy, cz, sz] = [Math.cos(rx), Math.sin(rx), Math.cos(ry), Math.sin(ry), Math.cos(rz), Math.sin(rz)];
  const r = [cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx, 0, sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx, 0, -sy, cy * sx, cy * cx, 0];
  r[3] = o[0] - (r[0] * o[0] + r[1] * o[1] + r[2] * o[2]);
  r[7] = o[1] - (r[4] * o[0] + r[5] * o[1] + r[6] * o[2]);
  r[11] = o[2] - (r[8] * o[0] + r[9] * o[1] + r[10] * o[2]);
  return r;
}
function mul(a, b) {
  const o = new Array(12);
  for (let r = 0; r < 3; r++) for (let c = 0; c < 4; c++) o[r * 4 + c] = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c] + (c === 3 ? a[r * 4 + 3] : 0);
  return o;
}
const xf = (m, p, pos = true) => [0, 1, 2].map((r) => m[r * 4] * p[0] + m[r * 4 + 1] * p[1] + m[r * 4 + 2] * p[2] + (pos ? m[r * 4 + 3] : 0));
const rad = (d) => (d * Math.PI) / 180;

function keyList(v) {
  return (Array.isArray(v) ? v : []).filter((e) => Array.isArray(e) && e.length >= 4).slice(0, 256).map((e) => e.map((x) => num(x, 0))).sort((a, b) => a[0] - b[0]);
}
function sample(k, t, out) {
  if (!k.length) return;
  let i = 0;
  while (i + 1 < k.length && k[i + 1][0] <= t) i++;
  let j = i, f = 0;
  if (t > k[i][0] && i + 1 < k.length) { j = i + 1; f = (t - k[i][0]) / Math.max(1e-4, k[j][0] - k[i][0]); }
  for (let c = 0; c < 3; c++) out[c] += k[i][1 + c] + (k[j][1 + c] - k[i][1 + c]) * f;
}

export function parseModel(m) {
  const els = m.elements;
  if (!Array.isArray(els) || !els.length) throw new Error("model has no elements");
  const ts = vec3(m.texture_size, 16);
  const nether = m.nether || {};
  const groups = [], names = {}, owner = new Array(els.length).fill(-1);
  const walk = (g, parent) => {
    if (typeof g === "number") { if (g < owner.length) owner[g] = parent; return; }
    if (!g || typeof g !== "object") return;
    const id = groups.length;
    groups.push({ o: vec3(g.origin, 8), rot: vec3(g.rotation, 0), parent, anims: [], keys: [] });
    if (typeof g.name === "string" && !(g.name in names)) names[g.name] = id;
    for (const c of g.children || []) walk(c, id);
  };
  for (const g of m.groups || []) walk(g, -1);
  for (const a of nether.anim || []) {
    let gi = names[a.group];
    if (gi === undefined) {
      gi = groups.length;
      groups.push({ o: vec3(a.origin, 8), rot: [0, 0, 0], parent: -1, anims: [], keys: [] });
      groups.forEach((g, i) => { if (i !== gi && g.parent < 0) g.parent = gi; });
      owner.forEach((o, i) => { if (o < 0) owner[i] = gi; });
    }
    if (a.length !== undefined) { groups[gi].keys.push([Math.max(0.05, num(a.length, 1)), keyList(a.rotation), keyList(a.position)]); continue; }
    const period = Math.max(0.05, num(a.period, 2)), phase = num(a.phase, 0);
    ["spin", "sway", "bob"].forEach((k, kind) => { if (a[k] !== undefined) groups[gi].anims.push([kind, ...vec3(a[k], 0), period, phase]); });
  }
  const quads = [];
  els.forEach((e, ei) => {
    const a = vec3(e.from, 0), b = vec3(e.to, 0);
    const lo = [0, 1, 2].map((i) => Math.min(a[i], b[i])), hi = [0, 1, 2].map((i) => Math.max(a[i], b[i]));
    const glow = !!nether.glow || num(e.light_emission, 0) > 0;
    let rot = null;
    const r = e.rotation;
    if (r && typeof r === "object" && r.angle === undefined) {
      const x = num(r.x, 0), y = num(r.y, 0), z = num(r.z, 0);
      if (x || y || z) rot = around(vec3(r.origin, 8), rad(x), rad(y), rad(z));
    } else if (r && num(r.angle, 0)) {
      let ang = rad(r.angle);
      const k = r.rescale ? 1 / Math.cos(Math.min(Math.abs(ang), Math.PI / 4)) : 1;
      const o = vec3(r.origin, 8), ax = r.axis === "x" ? 0 : r.axis === "y" ? 1 : 2;
      rot = around(o, ax === 0 ? ang : 0, ax === 1 ? ang : 0, ax === 2 ? ang : 0);
      if (k !== 1) { // rescale the two other axes, around the origin
        const s = [ax === 0 ? 1 : k, ax === 1 ? 1 : k, ax === 2 ? 1 : k];
        const sm = [s[0], 0, 0, o[0] - s[0] * o[0], 0, s[1], 0, o[1] - s[1] * o[1], 0, 0, s[2], o[2] - s[2] * o[2]];
        rot = mul(sm, rot);
      }
    }
    const faces = e.faces || {};
    DIRS.forEach((dn, d) => {
      const f = faces[dn];
      if (!f || typeof f !== "object" || f.texture === undefined || f.texture === null) return;
      const c = corners(d, lo, hi);
      const u = Array.isArray(f.uv) ? f.uv.map((x) => num(x, 0)) : defaultUv(d, lo, hi);
      const k = (((Math.round(num(f.rotation, 0)) / 90) | 0) % 4 + 4) % 4;
      const q = { p: [], uv: [], n: NORMALS[d], group: owner[ei], glow };
      for (let i = 0; i < 4; i++) {
        q.p.push(rot ? xf(rot, c[i]) : c[i]);
        const j = (i + k) % 4;
        q.uv.push([(j < 2 ? u[0] : u[2]) / 16, (j === 0 || j === 3 ? u[1] : u[3]) / 16]);
      }
      if (rot) q.n = xf(rot, q.n, false);
      quads.push(q);
    });
  });
  if (!quads.length) throw new Error("model has no textured faces");
  const moving = groups.some((g) => g.anims.length || g.keys.length);
  return { quads, groups, tw: Math.max(1, Math.round(ts[0])), th: Math.max(1, Math.round(ts[1])), moving, posed: moving || groups.some((g) => g.rot.some((v) => v)) };
}

export function poseModel(model, t) {
  if (!model.posed) return model.quads;
  const done = [];
  const mat = (i) => {
    if (done[i]) return done[i];
    const g = model.groups[i];
    const r = [...g.rot], p = [0, 0, 0];
    for (const [len, rk, pk] of g.keys) { const tt = t % len; sample(rk, tt, r); sample(pk, tt, p); }
    let m = r.some((v) => v) ? around(g.o, rad(r[0]), rad(r[1]), rad(r[2])) : [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0];
    m[3] += p[0]; m[7] += p[1]; m[11] += p[2];
    for (const a of g.anims) {
      const w = Math.sin(2 * Math.PI * (t / a[4] + a[5]));
      if (a[0] === 2) { m[3] += a[1] * w; m[7] += a[2] * w; m[11] += a[3] * w; continue; }
      const k = a[0] === 0 ? t : w;
      m = mul(m, around(g.o, rad(a[1] * k), rad(a[2] * k), rad(a[3] * k)));
    }
    if (g.parent >= 0 && g.parent !== i) m = mul(mat(g.parent), m);
    return (done[i] = m);
  };
  return model.quads.map((q) => (q.group < 0 ? q : { ...q, p: q.p.map((v) => xf(mat(q.group), v)), n: xf(mat(q.group), q.n, false) }));
}

// ---------------- auras (BigInt: the same 64-bit hash as CosAura.rnd / cosmetics.rs)
const M64 = (1n << 64n) - 1n;
export function rnd(a, b) {
  let z = ((BigInt.asUintN(64, BigInt(a)) * 0x9E3779B97F4A7C15n) & M64) ^ ((BigInt.asUintN(64, BigInt(b)) * 0xC2B2AE3D27D4EB4Fn) & M64) ^ 0x165667B19E3779F9n;
  z = ((z ^ (z >> 30n)) * 0xBF58476D1CE4E5B9n) & M64;
  z = ((z ^ (z >> 27n)) * 0x94D049BB133111EBn) & M64;
  z ^= z >> 31n;
  return Number(z >> 40n) / 16777216;
}
export function parseAura(m) {
  const at = (k, i, d) => (Array.isArray(m[k]) ? num(m[k][i], d) : num(m[k], d));
  const pair = (k, d0, lo) => { const a = Math.max(lo, at(k, 0, d0)); return [a, Math.max(a, at(k, 1, a))]; };
  return {
    count: Math.max(1, Math.min(64, Math.round(num(m.count, 16)))), life: pair("life", 1.5, 0.2), size: pair("size", 1.5, 0.2),
    rad: [at("radius", 0, 6), Math.max(at("radius", 0, 6), at("radius", 1, at("radius", 0, 6)))],
    h: [at("height", 0, 0), Math.max(at("height", 0, 0), at("height", 1, 30))],
    vel: [at("velocity", 0, 0), at("velocity", 1, 4), at("velocity", 2, 0)], jit: [at("jitter", 0, 0), at("jitter", 1, 0), at("jitter", 2, 0)],
    grav: num(m.gravity, 0), orbit: num(m.orbit, 0), wob: [at("wobble", 0, 0), at("wobble", 1, 1)],
    fade: [Math.max(0.01, at("fade", 0, 0.15)), Math.max(0.01, at("fade", 1, 0.3))], grow: [at("grow", 0, 1), at("grow", 1, 1), at("grow", 2, 1)],
    shape: m.shape === "ring" ? 1 : m.shape === "feet" ? 2 : 0, frames: m.frames === "loop" ? 1 : m.frames === "random" ? 2 : 0,
    fps: Math.max(0.1, num(m.fps, 8)), glow: m.glow !== false, cell: Math.max(0, Math.round(num(m.cell, 0))),
  };
}
const seeds = new Map();
function cached(k, f) { let v = seeds.get(k); if (v === undefined) { v = f(); if (seeds.size > 50000) seeds.clear(); seeds.set(k, v); } return v; }
export function evalAura(a, t) {
  const out = [];
  for (let i = 0; i < a.count; i++) {
    const ph = cached(`${i}:1`, () => rnd(i, 1)), life = a.life[0] + (a.life[1] - a.life[0]) * cached(`${i}:2`, () => rnd(i, 2));
    const c = t / life + ph, k = Math.floor(c), f = c - k, age = f * life;
    const s = Number(BigInt.asIntN(32, BigInt(k) * 1000003n + BigInt(i * 7919)));
    const r = Array.from({ length: 10 }, (_, j) => cached(`${s}:${j + 3}`, () => rnd(s, j + 3)));
    const ang = r[0] * Math.PI * 2;
    const rr = a.shape === 1 ? a.rad[1] : a.rad[0] + (a.rad[1] - a.rad[0]) * (a.shape === 0 ? Math.sqrt(r[1]) : r[1]);
    let y = a.shape === 2 ? a.h[0] : a.h[0] + (a.h[1] - a.h[0]) * r[2];
    let x = Math.cos(ang) * rr, z = Math.sin(ang) * rr;
    x += (a.vel[0] + (r[3] * 2 - 1) * a.jit[0]) * age;
    y += (a.vel[1] + (r[4] * 2 - 1) * a.jit[1]) * age + 0.5 * a.grav * age * age;
    z += (a.vel[2] + (r[5] * 2 - 1) * a.jit[2]) * age;
    if (a.orbit) { const o = rad(a.orbit * age), cx = Math.cos(o), sx = Math.sin(o), nx = x * cx - z * sx; z = x * sx + z * cx; x = nx; }
    if (a.wob[0]) { const w = a.wob[0] * Math.sin(2 * Math.PI * (a.wob[1] * age + r[6])); x += w * Math.cos(ang + 1.5708); z += w * Math.sin(ang + 1.5708); }
    const g = f < 0.5 ? a.grow[0] + (a.grow[1] - a.grow[0]) * f * 2 : a.grow[1] + (a.grow[2] - a.grow[1]) * (f - 0.5) * 2;
    out.push({ p: [x, y, z], size: (a.size[0] + (a.size[1] - a.size[0]) * r[7]) * Math.max(0, g), alpha: Math.max(0, Math.min(1, Math.min(f / a.fade[0], (1 - f) / a.fade[1]))),
      frame: a.frames === 0 ? f : a.frames === 1 ? age * a.fps + r[8] * 64 : r[8], row: cached(`${s}:12`, () => rnd(s, 12)) });
  }
  return out;
}
const auraCol = (a, v, cols) => (cols <= 1 ? 0 : a.frames === 1 ? ((Math.trunc(v) % cols) + cols) % cols : Math.min(cols - 1, Math.floor(v * cols)));

// ---------------- the player
function cuboid(out, min, size, grow, [u, v], xfn, sh, cut) {
  const [w, h, d] = size;
  const [x0, y0, z0] = [min[0] - grow, min[1] - grow, min[2] - grow], [x1, y1, z1] = [min[0] + w + grow, min[1] + h + grow, min[2] + d + grow];
  const faces = [
    [[[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]], [u + d, v + d, u + d + w, v + d + h], [0, 0, 1]],
    [[[x1, y1, z0], [x0, y1, z0], [x0, y0, z0], [x1, y0, z0]], [u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h], [0, 0, -1]],
    [[[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]], [u, v + d, u + d, v + d + h], [-1, 0, 0]],
    [[[x1, y1, z1], [x1, y1, z0], [x1, y0, z0], [x1, y0, z1]], [u + d + w, v + d, u + 2 * d + w, v + d + h], [1, 0, 0]],
    [[[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]], [u + d, v, u + d + w, v + d], [0, 1, 0]],
    [[[x0, y0, z1], [x1, y0, z1], [x1, y0, z0], [x0, y0, z0]], [u + d + w, v, u + d + 2 * w, v + d], [0, -1, 0]],
  ];
  for (const [p, t, n] of faces) out.push({ p: p.map((v) => xfn(v)), uv: [[t[0] / sh[0], t[1] / sh[1]], [t[2] / sh[0], t[1] / sh[1]], [t[2] / sh[0], t[3] / sh[1]], [t[0] / sh[0], t[3] / sh[1]]], n: xfn(n, true), cut });
}
const rotZ = (p, a) => [p[0] * Math.cos(a) - p[1] * Math.sin(a), p[0] * Math.sin(a) + p[1] * Math.cos(a), p[2]];
const rotX = (p, a) => [p[0], p[1] * Math.cos(a) - p[2] * Math.sin(a), p[1] * Math.sin(a) + p[2] * Math.cos(a)];
const rotY = (p, a) => [p[0] * Math.cos(a) + p[2] * Math.sin(a), p[1], -p[0] * Math.sin(a) + p[2] * Math.cos(a)];
function player(slim, cape) {
  const out = [];
  const aw = slim ? 3 : 4;
  const arm = (piv, a) => (p, dir) => (dir ? rotZ(p, a) : rotZ([p[0] - piv[0], p[1] - piv[1], p[2] - piv[2]], a).map((v, i) => v + piv[i]));
  const id = (p) => p, ra = arm([-5, 22, 0], -0.06), la = arm([5, 22, 0], 0.06);
  const sh = [64, 64];
  for (const [cut, gh, gb, uv] of [[false, 0, 0, [[0, 0], [16, 16], [40, 16], [32, 48], [0, 16], [16, 48]]], [true, 0.5, 0.25, [[32, 0], [16, 32], [40, 32], [48, 48], [0, 32], [0, 48]]]]) {
    cuboid(out, [-4, 24, -4], [8, 8, 8], gh, uv[0], id, sh, cut);
    cuboid(out, [-4, 12, -2], [8, 12, 4], gb, uv[1], id, sh, cut);
    cuboid(out, [-4 - aw, 12, -2], [aw, 12, 4], gb, uv[2], ra, sh, cut);
    cuboid(out, [4, 12, -2], [aw, 12, 4], gb, uv[3], la, sh, cut);
    cuboid(out, [-4, 0, -2], [4, 12, 4], gb, uv[4], id, sh, cut);
    cuboid(out, [0, 0, -2], [4, 12, 4], gb, uv[5], id, sh, cut);
  }
  if (cape) {
    const capeXf = (p, dir) => { const r = rotX(rotY(p, Math.PI), 0.14); return dir ? r : [r[0], r[1] + 24, r[2] - 2.9]; };
    const before = out.length;
    cuboid(out, [-5, -16, -0.5], [10, 16, 1], 0, [0, 0], capeXf, [64, 32], true);
    for (const q of out.slice(before)) q.cape = true;
  }
  return out;
}

// ---------------- WebGL
const VS = `attribute vec3 p; attribute vec2 uv; attribute vec4 c; uniform mat4 mvp; varying vec2 vu; varying vec4 vc;
void main(){ gl_Position = mvp * vec4(p, 1.0); vu = uv; vc = c; }`;
const FS = `precision mediump float; uniform sampler2D tex; uniform float cut; varying vec2 vu; varying vec4 vc;
void main(){ vec4 t = texture2D(tex, vu); if (t.a < cut) discard; gl_FragColor = vec4(t.rgb * vc.rgb, t.a * vc.a); }`;

export class Viewer {
  constructor(canvas) {
    const gl = (this.gl = canvas.getContext("webgl", { premultipliedAlpha: false, antialias: true }));
    if (!gl) throw new Error("WebGL is not available");
    const sh = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return s; };
    const pr = (this.prog = gl.createProgram());
    gl.attachShader(pr, sh(gl.VERTEX_SHADER, VS));
    gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, FS));
    gl.linkProgram(pr);
    this.loc = { p: gl.getAttribLocation(pr, "p"), uv: gl.getAttribLocation(pr, "uv"), c: gl.getAttribLocation(pr, "c"), mvp: gl.getUniformLocation(pr, "mvp"), tex: gl.getUniformLocation(pr, "tex"), cut: gl.getUniformLocation(pr, "cut") };
    this.buf = gl.createBuffer();
    this.canvas = canvas;
    this.yaw = 0.5; this.pitch = 0.12; this.spin = true; this.zoom = 1;
    this.item = null; this.skin = null; this.slim = false;
    let drag = null;
    canvas.addEventListener("pointerdown", (e) => { drag = e.clientX; canvas.setPointerCapture(e.pointerId); this.spin = false; });
    canvas.addEventListener("pointermove", (e) => { if (drag !== null) { this.yaw += (e.clientX - drag) * 0.012; drag = e.clientX; } });
    canvas.addEventListener("pointerup", () => { drag = null; });
    canvas.addEventListener("wheel", (e) => { e.preventDefault(); this.zoom = Math.max(0.6, Math.min(3, this.zoom * (e.deltaY < 0 ? 1.1 : 0.9))); }, { passive: false });
    this.t0 = performance.now();
    const loop = () => { this.frame(); requestAnimationFrame(loop); };
    requestAnimationFrame(loop);
  }
  texture(img) {
    const gl = this.gl, t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
    for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.NEAREST], [gl.TEXTURE_MAG_FILTER, gl.NEAREST], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
    return t;
  }
  setSkin(img) { this.skin = this.texture(img); }
  /** item: {kind, img (Image), json (parsed or null), frametime} */
  show(item) {
    this.item = null;
    if (!item) return;
    const it = { kind: item.kind, tex: this.texture(item.img), w: item.img.width, h: item.img.height, ms: item.frametime || 100 };
    if (item.kind === "cape") {
      const L = [[64, 32, 1], [22, 17, 0]].map(([bw, bh, full]) => { if (it.w % bw) return null; const s = it.w / bw, fh = s * bh; return it.h % fh ? null : { s, n: it.h / fh, full, fh }; }).find(Boolean);
      if (!L) throw new Error("not a cape layout");
      it.cape = L;
    } else if (item.kind === "aura") {
      it.aura = parseAura(item.json);
      const cell = it.aura.cell || it.h;
      it.cols = Math.max(1, Math.floor(it.w / cell)); it.rows = Math.max(1, Math.floor(it.h / cell));
    } else {
      it.model = parseModel(item.json);
      const fh = Math.max(1, Math.round(it.w * it.model.th / it.model.tw));
      it.frames = Math.max(1, Math.floor(it.h / fh));
      it.zMax = Math.max(...it.model.quads.flatMap((q) => q.p.map((p) => p[2])));
    }
    this.item = it;
  }
  frame() {
    const gl = this.gl, c = this.canvas;
    const dpr = window.devicePixelRatio || 1, W = Math.round(c.clientWidth * dpr), H = Math.round(c.clientHeight * dpr);
    if (c.width !== W || c.height !== H) { c.width = W; c.height = H; }
    gl.viewport(0, 0, W, H);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    if (!this.skin) return;
    const t = (performance.now() - this.t0) / 1000;
    if (this.spin) this.yaw += 0.006;
    const it = this.item;
    // camera: turn, tilt, perspective
    const k = this.zoom, center = it && (it.kind === "hat" || it.kind === "headband") ? 26 : 16.5, zz = it && (it.kind === "hat" || it.kind === "headband") ? 1.8 : 1;
    const cam = (p) => rotX(rotY([p[0], p[1] - center, p[2]], this.yaw), this.pitch);
    const f = 1 / Math.tan(rad(18) / (k * zz)), a = W / H, n = 1, fa = 400;
    const proj = [f / a, 0, 0, 0, 0, f, 0, 0, 0, 0, (fa + n) / (n - fa), -1, 0, 0, (2 * fa * n) / (n - fa), 0];
    const view = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, -95, 1]; // column-major: translate z -95
    const mvp = new Float32Array(16);
    for (let i = 0; i < 4; i++) for (let j = 0; j < 4; j++) { let s = 0; for (let q = 0; q < 4; q++) s += proj[q * 4 + j] * view[i * 4 + q]; mvp[i * 4 + j] = s; }
    gl.useProgram(this.prog);
    gl.uniformMatrix4fv(this.loc.mvp, false, mvp);
    gl.enable(gl.DEPTH_TEST);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    const L = (() => { const l = [-0.45, 0.6, 0.75], m = Math.hypot(...l); return l.map((v) => v / m); })();
    const shade = (n, glow) => { if (glow) return 1; const cn = rotX(rotY(n, this.yaw), this.pitch), m = Math.hypot(...cn) || 1; let d = (cn[0] * L[0] + cn[1] * L[1] + cn[2] * L[2]) / m; if (cn[2] < 0) d = -d; return 0.5 + 0.5 * Math.max(0, d); };
    const draw = (quads, tex, cut, inCam) => {
      if (!quads.length) return;
      const v = [];
      for (const q of quads) {
        const s = shade(q.n, q.glow), al = q.alpha ?? 1;
        const ps = q.p.map((p) => (inCam ? p : cam(p)));
        for (const i of [0, 1, 2, 0, 2, 3]) v.push(...ps[i], ...q.uv[i], s, s, s, al);
      }
      gl.bindBuffer(gl.ARRAY_BUFFER, this.buf);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(v), gl.STREAM_DRAW);
      const st = 9 * 4;
      gl.enableVertexAttribArray(this.loc.p); gl.vertexAttribPointer(this.loc.p, 3, gl.FLOAT, false, st, 0);
      gl.enableVertexAttribArray(this.loc.uv); gl.vertexAttribPointer(this.loc.uv, 2, gl.FLOAT, false, st, 12);
      gl.enableVertexAttribArray(this.loc.c); gl.vertexAttribPointer(this.loc.c, 4, gl.FLOAT, false, st, 20);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex); gl.uniform1i(this.loc.tex, 0);
      gl.uniform1f(this.loc.cut, cut);
      gl.drawArrays(gl.TRIANGLES, 0, v.length / 9);
    };
    const body = player(this.slim, it && it.cape);
    gl.depthMask(true);
    draw(body.filter((q) => !q.cape && !q.cut), this.skin, 0.01, false);
    draw(body.filter((q) => !q.cape && q.cut), this.skin, 0.5, false);
    if (!it) return;
    const ms = performance.now();
    if (it.cape) {
      const fr = Math.floor(ms / it.ms) % it.cape.n, frac = it.cape.fh / it.h;
      const qs = body.filter((q) => q.cape).map((q) => {
        const uv = q.uv.map(([u, v]) => (it.cape.full ? [u, (fr + v) * frac] : [u * 64 / 22, (fr + v * 32 / 17) * frac]));
        return { ...q, uv };
      });
      draw(qs, it.tex, 0.5, false);
    }
    if (it.model) {
      const fr = it.frames > 1 ? Math.floor(ms / it.ms) % it.frames : 0;
      const place = it.kind === "shield"
        ? (p) => [8 - p[0] + 4 + (this.slim ? 3 : 4) / 2 + 1, p[1] + 16, 8 - p[2] + it.zMax - 5.7]
        : (p) => [8 - p[0], p[1] + 24, 8 - p[2]];
      const qs = poseModel(it.model, t).map((q) => ({ p: q.p.map(place), n: [-q.n[0], q.n[1], -q.n[2]], glow: q.glow, uv: q.uv.map(([u, v]) => [u, (fr + v) / it.frames]) }));
      draw(qs, it.tex, 0.04, false);
    }
    if (it.aura) {
      gl.depthMask(false);
      const qs = [];
      for (const p of evalAura(it.aura, t)) {
        if (p.alpha <= 0.01 || p.size <= 0) continue;
        const c3 = cam(p.p), s = p.size / 2;
        const col = auraCol(it.aura, p.frame, it.cols), row = Math.min(it.rows - 1, Math.floor(p.row * it.rows));
        const [u0, u1, v0, v1] = [col / it.cols, (col + 1) / it.cols, row / it.rows, (row + 1) / it.rows];
        qs.push({ p: [[c3[0] - s, c3[1] + s, c3[2]], [c3[0] + s, c3[1] + s, c3[2]], [c3[0] + s, c3[1] - s, c3[2]], [c3[0] - s, c3[1] - s, c3[2]]], uv: [[u0, v0], [u1, v0], [u1, v1], [u0, v1]], n: [0, 0, 1], glow: it.aura.glow, alpha: p.alpha, z: c3[2] });
      }
      qs.sort((x, y) => x.z - y.z);
      // particles are already in camera space: undo nothing, draw with the camera identity
      draw(qs.map((q) => ({ ...q, n: [0, 0, 1] })), it.tex, 0.04, true);
      gl.depthMask(true);
    }
  }
}
