//! Nether cosmetics (hats, headbands, masks, back items, wings, auras, pets): the model and aura formats, the same rules
//! as the agent's CosModel.java / CosAura.java. Models are Blockbench "Java Block/Item" JSON: units are skin pixels,
//! (8, 0, 8) is the attachment pivot (the body part's pivot: the neck for head and body), the player looks north (-Z);
//! one texture, vertical frames = animated. Extensions (tools/cosmetics/import.mjs writes them): `"type": "mesh"`
//! elements (`vertices` [[x, y, z, u, v]], `faces` [[i, j, k(, l)]] counter-clockwise from outside), `"anchor"` on a
//! group or in `"nether"` (a body part from ANCHORS, else the kind's), group animations in `"nether": {"anim": [...]}`
//! (keyframe loops with rotation / position / scale). Auras are particle emitters over a sprite sheet (columns = frames,
//! rows = variants), stateless: every particle is a function of the time; an aura may also be a model (on the body).
use anyhow::{bail, Result};
use image::RgbaImage;
use serde_json::Value;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind { Hat, Headband, Mask, Back, Wings, Aura, Pet }

/// Number of cosmetic slots in a look.
pub const SLOTS: usize = 7;
/// Body parts a model (or one of its groups) is worn on; Bedrock `anchor_<part>` bones map here.
pub const ANCHORS: [&str; 6] = ["head", "body", "right_arm", "left_arm", "right_leg", "left_leg"];

impl Kind {
    pub fn parse(s: &str) -> Option<Kind> {
        Some(match s {
            "hat" => Kind::Hat, "headband" => Kind::Headband, "mask" => Kind::Mask, "back" => Kind::Back,
            "wings" => Kind::Wings, "aura" => Kind::Aura, "pet" => Kind::Pet, _ => return None,
        })
    }
    pub fn as_str(self) -> &'static str {
        match self {
            Kind::Hat => "hat", Kind::Headband => "headband", Kind::Mask => "mask", Kind::Back => "back",
            Kind::Wings => "wings", Kind::Aura => "aura", Kind::Pet => "pet",
        }
    }
    /// Slot index in a look (the order of ALL).
    pub fn slot(self) -> usize { self as usize }
    pub const ALL: [Kind; SLOTS] = [Kind::Hat, Kind::Headband, Kind::Mask, Kind::Back, Kind::Wings, Kind::Aura, Kind::Pet];
    /// ANCHORS index a model of this kind is worn on when it does not say: head items on the head, the rest on the body.
    pub fn anchor(self) -> i8 { match self { Kind::Hat | Kind::Headband | Kind::Mask => 0, _ => 1 } }
}

fn num(v: &Value, d: f32) -> f32 { v.as_f64().map(|x| x as f32).unwrap_or(d) }
fn vec3(v: &Value, d: f32) -> [f32; 3] {
    let mut o = [d; 3];
    if let Some(a) = v.as_array() { for (i, x) in a.iter().take(3).enumerate() { o[i] = num(x, d); } }
    o
}

/// One face: corners TL, BL, BR, TR seen from outside (counter-clockwise), uv per corner (0..1 in a frame), normal.
#[derive(Clone, Debug)]
/// `anchor`: ANCHORS index (-1 = the kind's, resolved by `Loaded`). Triangles repeat their last corner.
pub struct Quad { pub p: [[f32; 3]; 4], pub uv: [[f32; 2]; 4], pub n: [f32; 3], pub group: i32, pub glow: bool, pub anchor: i8 }

/// A keyframe loop: length (s), rotation, position and scale keys [t, x, y, z].
type Keys = (f32, Vec<[f32; 4]>, Vec<[f32; 4]>, Vec<[f32; 4]>);

#[derive(Clone, Debug, Default)]
struct Group { o: [f32; 3], rot: [f32; 3], parent: i32, anchor: i8, anims: Vec<[f32; 6]>, keys: Vec<Keys> } // rest rotation (deg); anims: kind 0 spin / 1 sway / 2 bob, x, y, z, period, phase

#[derive(Clone, Debug)]
pub struct Model { pub quads: Vec<Quad>, groups: Vec<Group>, pub tw: u32, pub th: u32, moving: bool, posed: bool }

const DIRS: [&str; 6] = ["north", "south", "east", "west", "up", "down"];
const NORMALS: [[f32; 3]; 6] = [[0., 0., -1.], [0., 0., 1.], [1., 0., 0.], [-1., 0., 0.], [0., 1., 0.], [0., -1., 0.]];

fn corners(d: usize, a: [f32; 3], b: [f32; 3]) -> [[f32; 3]; 4] {
    let ([x1, y1, z1], [x2, y2, z2]) = (a, b);
    match d {
        0 => [[x2, y2, z1], [x2, y1, z1], [x1, y1, z1], [x1, y2, z1]],
        1 => [[x1, y2, z2], [x1, y1, z2], [x2, y1, z2], [x2, y2, z2]],
        2 => [[x2, y2, z2], [x2, y1, z2], [x2, y1, z1], [x2, y2, z1]],
        3 => [[x1, y2, z1], [x1, y1, z1], [x1, y1, z2], [x1, y2, z2]],
        4 => [[x1, y2, z1], [x1, y2, z2], [x2, y2, z2], [x2, y2, z1]],
        _ => [[x1, y1, z2], [x1, y1, z1], [x2, y1, z1], [x2, y1, z2]],
    }
}

fn default_uv(d: usize, a: [f32; 3], b: [f32; 3]) -> [f32; 4] {
    let ([x1, y1, z1], [x2, y2, z2]) = (a, b);
    match d {
        0 => [16. - x2, 16. - y2, 16. - x1, 16. - y1],
        1 => [x1, 16. - y2, x2, 16. - y1],
        2 => [16. - z2, 16. - y2, 16. - z1, 16. - y1],
        3 => [z1, 16. - y2, z2, 16. - y1],
        4 => [x1, z1, x2, z2],
        _ => [x1, 16. - z2, x2, 16. - z1],
    }
}

/// rot = (origin, axis 0/1/2, sin, cos, rescale)
fn rotate(p: [f32; 3], o: [f32; 3], axis: usize, s: f32, c: f32, k: f32) -> [f32; 3] {
    let (x, y, z) = (p[0] - o[0], p[1] - o[1], p[2] - o[2]);
    let (mut nx, mut ny, mut nz) = (x, y, z);
    match axis {
        0 => { ny = (y * c - z * s) * k; nz = (y * s + z * c) * k; }
        1 => { nx = (x * c + z * s) * k; nz = (-x * s + z * c) * k; }
        _ => { nx = (x * c - y * s) * k; ny = (x * s + y * c) * k; }
    }
    [nx + o[0], ny + o[1], nz + o[2]]
}

fn anchor_of(v: &Value) -> Option<i8> { v.as_str().and_then(|s| ANCHORS.iter().position(|a| *a == s)).map(|i| i as i8) }

fn walk(g: &Value, parent: i32, anchor: i8, out: &mut Vec<Group>, names: &mut Vec<(String, usize)>, owner: &mut [i32]) {
    if let Some(i) = g.as_u64() { if (i as usize) < owner.len() { owner[i as usize] = parent; } return; }
    if !g.is_object() || out.len() > 512 { return; }
    let id = out.len();
    let anchor = anchor_of(&g["anchor"]).unwrap_or(anchor);
    out.push(Group { o: vec3(&g["origin"], 8.), rot: vec3(&g["rotation"], 0.), parent, anchor, anims: vec![], keys: vec![] });
    if let Some(n) = g["name"].as_str() { if !names.iter().any(|(x, _)| x == n) { names.push((n.to_string(), id)); } }
    if let Some(ch) = g["children"].as_array() { for c in ch { walk(c, id as i32, anchor, out, names, owner); } }
}

impl Model {
    pub fn parse(m: &Value) -> Result<Model> {
        let Some(els) = m["elements"].as_array().filter(|e| !e.is_empty()) else { bail!("model has no elements") };
        if els.len() > 2048 { bail!("model too large"); }
        let ts = vec3(&m["texture_size"], 16.);
        let (tw, th) = (ts[0].round().max(1.) as u32, ts[1].round().max(1.) as u32);
        let nether = &m["nether"];
        let all_glow = nether["glow"].as_bool().unwrap_or(false);
        let model_anchor = anchor_of(&nether["anchor"]).unwrap_or(-1);
        let mut groups = vec![];
        let mut names = vec![];
        let mut owner = vec![-1i32; els.len()];
        if let Some(gs) = m["groups"].as_array() { for g in gs { walk(g, -1, model_anchor, &mut groups, &mut names, &mut owner); } }
        if let Some(anims) = nether["anim"].as_array() {
            for a in anims {
                let gi = match a["group"].as_str().and_then(|n| names.iter().find(|(x, _)| x == n)) {
                    Some((_, i)) => *i,
                    None => {
                        let gi = groups.len();
                        groups.push(Group { o: vec3(&a["origin"], 8.), rot: [0.; 3], parent: -1, anchor: model_anchor, anims: vec![], keys: vec![] });
                        for (i, g) in groups.iter_mut().enumerate() { if i != gi && g.parent < 0 { g.parent = gi as i32; } }
                        for o in owner.iter_mut() { if *o < 0 { *o = gi as i32; } }
                        gi
                    }
                };
                if !a["length"].is_null() {
                    groups[gi].keys.push((num(&a["length"], 1.).max(0.05), key_list(&a["rotation"]), key_list(&a["position"]), key_list(&a["scale"])));
                    continue;
                }
                let (period, phase) = (num(&a["period"], 2.).max(0.05), num(&a["phase"], 0.));
                for (k, key) in ["spin", "sway", "bob"].iter().enumerate() {
                    if !a[*key].is_null() { let v = vec3(&a[*key], 0.); groups[gi].anims.push([k as f32, v[0], v[1], v[2], period, phase]); }
                }
            }
        }
        let mut quads = vec![];
        for (ei, e) in els.iter().enumerate() {
            let anchor = if owner[ei] >= 0 { groups[owner[ei] as usize].anchor } else { model_anchor };
            let glow = all_glow || num(&e["light_emission"], 0.) > 0.;
            if e["type"].as_str() == Some("mesh") {
                mesh(e, owner[ei], glow, anchor, &mut quads);
                if quads.len() > 32768 { bail!("model too large"); }
                continue;
            }
            let (a, b) = (vec3(&e["from"], 0.), vec3(&e["to"], 0.));
            let lo = [a[0].min(b[0]), a[1].min(b[1]), a[2].min(b[2])];
            let hi = [a[0].max(b[0]), a[1].max(b[1]), a[2].max(b[2])];
            // Blockbench free rotation: {"x", "y", "z", "origin"} (degrees, Z*Y*X)
            let free = e["rotation"].as_object().filter(|r| r.get("angle").is_none()).and_then(|r| {
                let g = |k: &str| r.get(k).map_or(0., |v| num(v, 0.));
                let (x, y, z) = (g("x"), g("y"), g("z"));
                (x != 0. || y != 0. || z != 0.).then(|| around(vec3(r.get("origin").unwrap_or(&Value::Null), 8.), x.to_radians(), y.to_radians(), z.to_radians()))
            });
            let rot = e["rotation"].as_object().filter(|r| r.get("angle").is_some()).and_then(|r| {
                let ang = num(r.get("angle").unwrap_or(&Value::Null), 0.).to_radians();
                if ang == 0. { return None; }
                let axis = match r.get("axis").and_then(|v| v.as_str()) { Some("x") => 0, Some("y") => 1, _ => 2 };
                let k = if r.get("rescale").and_then(|v| v.as_bool()) == Some(true) { 1. / ang.abs().min(std::f32::consts::FRAC_PI_4).cos() } else { 1. };
                Some((vec3(r.get("origin").unwrap_or(&Value::Null), 8.), axis, ang.sin(), ang.cos(), k))
            });
            let Some(faces) = e["faces"].as_object() else { continue };
            for (d, dn) in DIRS.iter().enumerate() {
                let Some(f) = faces.get(*dn).filter(|f| f.is_object()) else { continue };
                if f["texture"].is_null() { continue; }
                let c = corners(d, lo, hi);
                let u = match f["uv"].as_array() { Some(l) => { let mut u = [0f32; 4]; for (i, x) in l.iter().take(4).enumerate() { u[i] = num(x, 0.); } u } None => default_uv(d, lo, hi) };
                let k = ((num(&f["rotation"], 0.).round() as i32 / 90) % 4 + 4) % 4;
                let mut q = Quad { p: c, uv: [[0.; 2]; 4], n: NORMALS[d], group: owner[ei], glow, anchor };
                for i in 0..4 {
                    if let Some((o, ax, s, co, kk)) = rot { q.p[i] = rotate(c[i], o, ax, s, co, kk); }
                    if let Some(m) = &free { q.p[i] = xf(m, q.p[i], true); }
                    let j = (i as i32 + k) % 4;
                    q.uv[i] = [(if j < 2 { u[0] } else { u[2] }) / 16., (if j == 0 || j == 3 { u[1] } else { u[3] }) / 16.];
                }
                if let Some((_, ax, s, co, _)) = rot { q.n = rotate(q.n, [0.; 3], ax, s, co, 1.); }
                if let Some(m) = &free { q.n = xf(m, q.n, false); }
                quads.push(q);
            }
        }
        if quads.is_empty() { bail!("model has no textured faces"); }
        let moving = groups.iter().any(|g| !g.anims.is_empty() || !g.keys.is_empty());
        let posed = moving || groups.iter().any(|g| g.rot != [0.; 3]);
        Ok(Model { quads, groups, tw, th, moving, posed })
    }

    pub fn moving(&self) -> bool { self.moving }

    fn mat(&self, i: usize, t: f32, done: &mut [Option<[f32; 12]>]) -> [f32; 12] {
        if let Some(m) = done[i] { return m; }
        let g = &self.groups[i];
        let (mut r, mut p, mut s) = (g.rot, [0f32; 3], [1f32; 3]);
        for (len, rk, pk, sk) in &g.keys {
            let tt = t % len;
            sample(rk, tt, &mut r);
            sample(pk, tt, &mut p);
            if !sk.is_empty() { let mut v = [0f32; 3]; sample(sk, tt, &mut v); for c in 0..3 { s[c] *= v[c]; } }
        }
        let mut m = if r != [0.; 3] || s != [1.; 3] { around_s(g.o, r[0].to_radians(), r[1].to_radians(), r[2].to_radians(), s) } else { [1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0.] };
        m[3] += p[0]; m[7] += p[1]; m[11] += p[2];
        for a in &g.anims {
            let w = (2. * std::f32::consts::PI * (t / a[4] + a[5])).sin();
            if a[0] == 2. { m[3] += a[1] * w; m[7] += a[2] * w; m[11] += a[3] * w; continue; }
            let k = if a[0] == 0. { t } else { w };
            m = mul(&m, &around(g.o, (a[1] * k).to_radians(), (a[2] * k).to_radians(), (a[3] * k).to_radians()));
        }
        if g.parent >= 0 && g.parent as usize != i { m = mul(&self.mat(g.parent as usize, t, done), &m); }
        done[i] = Some(m);
        m
    }

    /// The quads at time t (seconds), in model pixels (Blockbench space).
    pub fn pose(&self, t: f32) -> Vec<Quad> {
        let mut done = vec![None; self.groups.len()];
        let mats: Vec<[f32; 12]> = if self.posed { (0..self.groups.len()).map(|i| self.mat(i, t, &mut done)).collect() } else { vec![] };
        self.quads.iter().map(|q| {
            let Some(m) = (q.group >= 0 && self.posed).then(|| mats[q.group as usize]) else { return q.clone() };
            let mut o = q.clone();
            for i in 0..4 { o.p[i] = xf(&m, q.p[i], true); }
            let n = xf(&m, q.n, false);
            let l = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt();
            o.n = if l > 1e-6 { [n[0] / l, n[1] / l, n[2] / l] } else { q.n };
            o
        }).collect()
    }
}

/// [[t, x, y, z], ...] sorted by time (at most 256 keys)
fn key_list(v: &Value) -> Vec<[f32; 4]> {
    let mut k: Vec<[f32; 4]> = v.as_array().map(|a| a.iter().filter_map(|e| {
        let e = e.as_array().filter(|e| e.len() >= 4)?;
        Some([num(&e[0], 0.), num(&e[1], 0.), num(&e[2], 0.), num(&e[3], 0.)])
    }).take(256).collect()).unwrap_or_default();
    k.sort_by(|a, b| a[0].partial_cmp(&b[0]).unwrap_or(std::cmp::Ordering::Equal));
    k
}

/// Linear value of keys at t, added to out.
fn sample(k: &[[f32; 4]], t: f32, out: &mut [f32; 3]) {
    if k.is_empty() { return; }
    let mut i = 0;
    while i + 1 < k.len() && k[i + 1][0] <= t { i += 1; }
    let (j, f) = if t > k[i][0] && i + 1 < k.len() { (i + 1, (t - k[i][0]) / (k[i + 1][0] - k[i][0]).max(1e-4)) } else { (i, 0.) };
    for c in 0..3 { out[c] += k[i][1 + c] + (k[j][1 + c] - k[i][1 + c]) * f; }
}

/// Mesh element: `vertices` [[x, y, z, u, v]] (uv 0..16 in a frame), `faces` [[i, j, k(, l)]] counter-clockwise from outside.
fn mesh(e: &Value, group: i32, glow: bool, anchor: i8, out: &mut Vec<Quad>) {
    let vs: Vec<[f32; 5]> = e["vertices"].as_array().map(|a| a.iter().take(65536).map(|v| {
        let mut o = [0f32; 5];
        if let Some(l) = v.as_array() { for (i, x) in l.iter().take(5).enumerate() { o[i] = num(x, 0.); } }
        o
    }).collect()).unwrap_or_default();
    let Some(fs) = e["faces"].as_array() else { return };
    for f in fs.iter().take(32768) {
        let Some(ix) = f.as_array() else { continue };
        let ids: Vec<usize> = ix.iter().filter_map(|i| i.as_u64().map(|i| i as usize)).filter(|&i| i < vs.len()).collect();
        if !(3..=4).contains(&ids.len()) || ids.len() != ix.len() { continue; }
        let c: [[f32; 5]; 4] = std::array::from_fn(|k| vs[ids[k.min(ids.len() - 1)]]);
        let p = c.map(|v| [v[0], v[1], v[2]]);
        let (a, b) = ([p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]], [p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2]]);
        let n = [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
        let l = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt();
        if l < 1e-9 { continue; }
        out.push(Quad { p, uv: c.map(|v| [v[3] / 16., v[4] / 16.]), n: [n[0] / l, n[1] / l, n[2] / l], group, glow, anchor });
    }
}

fn around(o: [f32; 3], rx: f32, ry: f32, rz: f32) -> [f32; 12] { around_s(o, rx, ry, rz, [1.; 3]) }

/// translate(o) * Rz * Ry * Rx * scale(s) * translate(-o)
fn around_s(o: [f32; 3], rx: f32, ry: f32, rz: f32, s: [f32; 3]) -> [f32; 12] {
    let (cx, sx, cy, sy, cz, sz) = (rx.cos(), rx.sin(), ry.cos(), ry.sin(), rz.cos(), rz.sin());
    let mut r = [cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx, 0., sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx, 0., -sy, cy * sx, cy * cx, 0.];
    for row in 0..3 { for c in 0..3 { r[row * 4 + c] *= s[c]; } }
    r[3] = o[0] - (r[0] * o[0] + r[1] * o[1] + r[2] * o[2]);
    r[7] = o[1] - (r[4] * o[0] + r[5] * o[1] + r[6] * o[2]);
    r[11] = o[2] - (r[8] * o[0] + r[9] * o[1] + r[10] * o[2]);
    r
}

fn mul(a: &[f32; 12], b: &[f32; 12]) -> [f32; 12] {
    let mut o = [0f32; 12];
    for r in 0..3 { for c in 0..4 {
        let mut v = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c];
        if c == 3 { v += a[r * 4 + 3]; }
        o[r * 4 + c] = v;
    } }
    o
}

fn xf(m: &[f32; 12], p: [f32; 3], pos: bool) -> [f32; 3] {
    let w = if pos { 1. } else { 0. };
    [m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3] * w, m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7] * w, m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11] * w]
}

// ---------------- auras ----------------

#[derive(Clone, Debug)]
pub struct Aura {
    pub count: usize,
    life: (f32, f32), size: (f32, f32), rad: (f32, f32), h: (f32, f32),
    vel: [f32; 3], jit: [f32; 3], grav: f32, orbit: f32, wob: (f32, f32), fade: (f32, f32), grow: [f32; 3],
    shape: u8, frames: u8, fps: f32,
    pub glow: bool,
    pub cell: u32,
}

/// One particle: position (skin pixels from the feet, Y up), size (px), alpha, frame value, variant pick, age (s).
#[derive(Clone, Copy, Debug)]
pub struct Particle { pub p: [f32; 3], pub size: f32, pub alpha: f32, pub frame: f32, pub row: f32, pub age: f32 }

/// [0, 1) from two integers (same as CosAura.rnd).
pub fn rnd(a: i32, b: i32) -> f32 {
    let mut z = (a as i64 as u64).wrapping_mul(0x9E3779B97F4A7C15) ^ (b as i64 as u64).wrapping_mul(0xC2B2AE3D27D4EB4F) ^ 0x165667B19E3779F9;
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
    z ^= z >> 31;
    (z >> 40) as f32 / (1u64 << 24) as f32
}

impl Aura {
    pub fn parse(m: &Value) -> Result<Aura> {
        if !m.is_object() { bail!("aura is not an object"); }
        let at = |k: &str, i: usize, d: f32| -> f32 {
            let v = &m[k];
            if let Some(a) = v.as_array() { a.get(i).map_or(d, |x| num(x, d)) } else { num(v, d) }
        };
        let pair = |k: &str, d0: f32, d1: f32, lo: f32| { let a = at(k, 0, d0).max(lo); (a, at(k, 1, d1.max(a)).max(a)) };
        Ok(Aura {
            count: num(&m["count"], 16.).round().clamp(1., 64.) as usize,
            life: pair("life", 1.5, 1.5, 0.2), size: pair("size", 1.5, 1.5, 0.2),
            rad: { let a = at("radius", 0, 6.); (a, at("radius", 1, a).max(a)) },
            h: { let a = at("height", 0, 0.); (a, at("height", 1, 30.).max(a)) },
            vel: [at("velocity", 0, 0.), at("velocity", 1, 4.), at("velocity", 2, 0.)],
            jit: [at("jitter", 0, 0.), at("jitter", 1, 0.), at("jitter", 2, 0.)],
            grav: num(&m["gravity"], 0.), orbit: num(&m["orbit"], 0.),
            wob: (at("wobble", 0, 0.), at("wobble", 1, 1.)),
            fade: (at("fade", 0, 0.15).max(0.01), at("fade", 1, 0.3).max(0.01)),
            grow: [at("grow", 0, 1.), at("grow", 1, 1.), at("grow", 2, 1.)],
            shape: match m["shape"].as_str() { Some("ring") => 1, Some("feet") => 2, _ => 0 },
            frames: match m["frames"].as_str() { Some("loop") => 1, Some("random") => 2, _ => 0 },
            fps: num(&m["fps"], 8.).max(0.1),
            glow: m["glow"].as_bool() != Some(false),
            cell: num(&m["cell"], 0.).round().max(0.) as u32,
        })
    }

    pub fn eval(&self, t: f64) -> Vec<Particle> {
        (0..self.count as i32).map(|i| {
            let ph = rnd(i, 1);
            let life = self.life.0 + (self.life.1 - self.life.0) * rnd(i, 2);
            let c = t / life as f64 + ph as f64;
            let k = c.floor() as i64;
            let a = (c - k as f64) as f32;
            let age = a * life;
            let s = (k.wrapping_mul(1000003).wrapping_add(i as i64 * 7919)) as i32;
            let r: Vec<f32> = (3..13).map(|j| rnd(s, j)).collect();
            let ang = r[0] * std::f32::consts::TAU;
            let rad = if self.shape == 1 { self.rad.1 } else { self.rad.0 + (self.rad.1 - self.rad.0) * if self.shape == 0 { r[1].sqrt() } else { r[1] } };
            let mut y = if self.shape == 2 { self.h.0 } else { self.h.0 + (self.h.1 - self.h.0) * r[2] };
            let (mut x, mut z) = (ang.cos() * rad, ang.sin() * rad);
            x += (self.vel[0] + (r[3] * 2. - 1.) * self.jit[0]) * age;
            y += (self.vel[1] + (r[4] * 2. - 1.) * self.jit[1]) * age + 0.5 * self.grav * age * age;
            z += (self.vel[2] + (r[5] * 2. - 1.) * self.jit[2]) * age;
            if self.orbit != 0. {
                let o = (self.orbit * age).to_radians();
                let (cx, sx) = (o.cos(), o.sin());
                let nx = x * cx - z * sx;
                z = x * sx + z * cx;
                x = nx;
            }
            if self.wob.0 != 0. {
                let w = self.wob.0 * (std::f32::consts::TAU * (self.wob.1 * age + r[6])).sin();
                x += w * (ang + 1.5708).cos();
                z += w * (ang + 1.5708).sin();
            }
            let g = if a < 0.5 { self.grow[0] + (self.grow[1] - self.grow[0]) * a * 2. } else { self.grow[1] + (self.grow[2] - self.grow[1]) * (a - 0.5) * 2. };
            Particle {
                p: [x, y, z],
                size: (self.size.0 + (self.size.1 - self.size.0) * r[7]) * g.max(0.),
                alpha: (a / self.fade.0).min((1. - a) / self.fade.1).clamp(0., 1.),
                frame: match self.frames { 0 => a, 1 => age * self.fps + r[8] * 64., _ => r[8] },
                row: rnd(s, 12),
                age,
            }
        }).collect()
    }

    /// Frame column for a particle's frame value with `cols` columns.
    pub fn col(&self, v: f32, cols: u32) -> u32 {
        if cols <= 1 { return 0; }
        match self.frames { 1 => (v as i64).rem_euclid(cols as i64) as u32, _ => ((v * cols as f32) as u32).min(cols - 1) }
    }
}

// ---------------- a loaded cosmetic ----------------

/// A cosmetic ready to draw: its model or aura and texture (frames stacked vertically / sprite cells).
#[derive(Clone)]
pub struct Loaded {
    pub id: String,
    pub kind: Kind,
    pub model: Option<Model>,
    pub aura: Option<Aura>,
    pub tex: RgbaImage,
    /// model texture frames (vertical), aura sprite columns/rows
    pub frames: u32,
    pub cols: u32,
    pub rows: u32,
    pub ms: u32,
}

impl Loaded {
    pub fn from_bytes(id: &str, kind: Kind, json: &[u8], png: &[u8], ms: u32) -> Result<Loaded> {
        let v: Value = serde_json::from_slice(json)?;
        let tex = image::load_from_memory_with_format(png, image::ImageFormat::Png)?.to_rgba8();
        if tex.width() == 0 || tex.height() == 0 || tex.width() > 4096 || tex.height() > 8192 { bail!("bad texture size"); }
        let mut l = Loaded { id: id.into(), kind, model: None, aura: None, frames: 1, cols: 1, rows: 1, ms: ms.clamp(20, 2000), tex };
        if kind == Kind::Aura && v.get("elements").is_none() {
            let a = Aura::parse(&v)?;
            let cell = if a.cell > 0 { a.cell } else { l.tex.height() };
            l.cols = (l.tex.width() / cell).max(1);
            l.rows = (l.tex.height() / cell).max(1);
            l.aura = Some(a);
        } else {
            let mut m = Model::parse(&v)?;
            for q in &mut m.quads { if q.anchor < 0 { q.anchor = kind.anchor(); } }
            let fh = ((l.tex.width() as f32 * m.th as f32 / m.tw as f32).round() as u32).max(1);
            l.frames = (l.tex.height() / fh).clamp(1, 256);
            l.model = Some(m);
        }
        Ok(l)
    }

    /// Animated (texture frames, moving groups or particles): the preview keeps drawing.
    pub fn animated(&self) -> bool { self.frames > 1 || self.aura.is_some() || self.model.as_ref().is_some_and(Model::moving) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rnd_matches_agent() {
        // values from the agent's CosAura.rnd (Java)
        let v = rnd(0, 1);
        assert!((0. ..1.).contains(&v));
        assert_ne!(rnd(1, 2), rnd(2, 1));
    }

    #[test]
    fn generated_content_parses() {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../backend/content/cosmetics");
        let Ok(idx) = std::fs::read(dir.join("index.json")) else { return };
        let list: Vec<Value> = serde_json::from_slice(&idx).unwrap();
        for c in list {
            let id = c["id"].as_str().unwrap();
            let kind = Kind::parse(c["type"].as_str().unwrap()).unwrap();
            let l = Loaded::from_bytes(id, kind, &std::fs::read(dir.join(format!("{id}.json"))).unwrap(), &std::fs::read(dir.join(format!("{id}.png"))).unwrap(), 100).unwrap();
            if let Some(m) = &l.model { assert!(!m.pose(1.0).is_empty(), "{id}"); }
            if let Some(a) = &l.aura { assert_eq!(a.eval(3.0).len(), a.count, "{id}"); }
        }
    }
}
