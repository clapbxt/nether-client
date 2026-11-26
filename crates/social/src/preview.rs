//! Software renderer for the player preview (launcher Home/Wardrobe, cosmetic cards): the player model with skin,
//! outer layer and cape, plus worn cosmetics (models on their body parts, aura particles). Perspective-correct,
//! depth-buffered, nearest texel sampling; opaque texels first, then translucent texels and particles blended back to
//! front over a transparent background. Space: skin pixels, Y up, origin between the feet, the player faces +Z.
use image::RgbaImage;

use crate::cosmetics::{Loaded, SLOTS};

type V3 = [f32; 3];

/// Camera: model turn (radians), downward tilt, the model height looked at, zoom, sideways pan (model px).
#[derive(Clone, Copy, Debug)]
pub struct View { pub yaw: f32, pub tilt: f32, pub center: f32, pub zoom: f32, pub pan: f32 }

const DIST: f32 = 90.;
/// share of the frame's half size the fitted content may fill
const FILL: f32 = 0.9;

impl View {
    pub fn full(yaw: f32) -> View { View { yaw, tilt: 0.12, center: 16.5, zoom: 1., pan: 0. } }
    /// head and shoulders (hat / headband cards)
    pub fn head(yaw: f32) -> View { View { yaw, tilt: 0.16, center: 29.6, zoom: 1.6, pan: 0. } }
    /// whole body, a little closer (aura cards)
    pub fn aura(yaw: f32) -> View { View { yaw, tilt: 0.1, center: 17.0, zoom: 1.22, pan: 0. } }

    /// The turning preview: everything the look draws (over a few seconds of animation) stays in a `w` x `h` frame from
    /// every side; never closer than `max_zoom`.
    pub fn fit_turning(look: &Look, yaw: f32, w: u32, h: u32, max_zoom: f32) -> View {
        let pts = points(look, None);
        let (mut r, mut y0, mut y1) = (0f32, f32::MAX, f32::MIN);
        for p in &pts { r = r.max(p[0].hypot(p[2])); y0 = y0.min(p[1]); y1 = y1.max(p[1]); }
        let tilt = 0.12f32;
        let half_h = (y1 - y0) / 2. + r * tilt.sin();
        let m = DIST / (DIST - r);
        View { yaw, tilt, center: (y0 + y1) / 2., zoom: zoom_for(half_h * m, r * m, w, h).min(max_zoom), pan: 0. }
    }

    /// A still card from `yaw`: frames the worn items plus `focus` (model-space corners of the body part to keep in
    /// view, e.g. the head); never closer than `max_zoom`.
    pub fn fit(look: &Look, yaw: f32, tilt: f32, focus: &[V3], w: u32, h: u32, max_zoom: f32) -> View {
        let mut pts = points(look, Some(focus));
        pts.extend_from_slice(focus);
        let (mut x0, mut x1, mut y0, mut y1, mut z1) = (f32::MAX, f32::MIN, f32::MAX, f32::MIN, f32::MIN);
        for p in &pts {
            let c = rot_x(rot_y(*p, yaw), tilt);
            x0 = x0.min(c[0]); x1 = x1.max(c[0]); y0 = y0.min(c[1]); y1 = y1.max(c[1]); z1 = z1.max(c[2]);
        }
        let m = DIST / (DIST - z1.max(0.));
        let zoom = zoom_for((y1 - y0) / 2. * m, (x1 - x0) / 2. * m, w, h).min(max_zoom);
        View { yaw, tilt, center: (y0 + y1) / 2. / tilt.cos(), zoom, pan: (x0 + x1) / 2. }
    }
}

/// The largest zoom showing half extents `hh` x `hw` (model px, perspective included) inside `w` x `h`.
fn zoom_for(hh: f32, hw: f32, w: u32, h: u32) -> f32 {
    let base = h as f32 * 0.86 / 34.; // screen px per model px at zoom 1
    let zh = FILL * h as f32 / 2. / (hh.max(1.) * base);
    let zw = FILL * w as f32 / 2. / (hw.max(1.) * base);
    zh.min(zw)
}

/// Model-space points the look draws, sampled over a few seconds of animation (poses, aura particles). With `focus`,
/// the player's own boxes are left out (only the items count).
fn points(look: &Look, focus: Option<&[V3]>) -> Vec<V3> {
    let mut out = vec![];
    let mut world = vec![];
    if focus.is_none() { player(look, &mut world); }
    for it in look.items.iter().flatten() {
        for i in 0..16 {
            let t = i as f64 * 0.25;
            cosmetic(it, t, (t * 1000.) as u64, &mut world);
            if let Some(a) = &it.aura {
                for p in a.eval(t) {
                    if p.alpha <= 0.01 || p.size <= 0. { continue; }
                    let s = p.size / 2.;
                    out.extend([[p.p[0] - s, p.p[1] - s, p.p[2]], [p.p[0] + s, p.p[1] + s, p.p[2]], [p.p[0], p.p[1], p.p[2] - s], [p.p[0], p.p[1], p.p[2] + s]]);
                }
            }
        }
    }
    for q in &world { out.extend(q.p); }
    out
}

/// What to draw: skin (64x64), model, cape frame (64:32 layout, any scale), worn cosmetics by slot.
pub struct Look<'a> { pub skin: &'a RgbaImage, pub slim: bool, pub cape: Option<&'a RgbaImage>, pub items: [Option<&'a Loaded>; SLOTS] }

#[derive(Clone, Copy, PartialEq)]
enum Mode { Skin, Cut, Cos, Glow }

struct Q<'a> { p: [V3; 4], uv: [[f32; 2]; 4], clamp: [f32; 4], tex: &'a RgbaImage, mode: Mode, alpha: f32, n: Option<V3> }

fn rot_x(p: V3, a: f32) -> V3 { let (s, c) = a.sin_cos(); [p[0], p[1] * c - p[2] * s, p[1] * s + p[2] * c] }
fn rot_y(p: V3, a: f32) -> V3 { let (s, c) = a.sin_cos(); [p[0] * c + p[2] * s, p[1], -p[0] * s + p[2] * c] }
fn rot_z(p: V3, a: f32) -> V3 { let (s, c) = a.sin_cos(); [p[0] * c - p[1] * s, p[0] * s + p[1] * c, p[2]] }
fn sub(a: V3, b: V3) -> V3 { [a[0] - b[0], a[1] - b[1], a[2] - b[2]] }
fn cross(a: V3, b: V3) -> V3 { [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]] }

/// A skin box: `min`/`size` in model pixels, texture offset `uv` in 64x64 units.
#[allow(clippy::too_many_arguments)]
fn cuboid<'a>(out: &mut Vec<Q<'a>>, tex: &'a RgbaImage, min: V3, size: V3, grow: f32, uv: (f32, f32), xf: &dyn Fn(V3) -> V3, mode: Mode) {
    let (w, h, d) = (size[0], size[1], size[2]);
    let (x0, y0, z0) = (min[0] - grow, min[1] - grow, min[2] - grow);
    let (x1, y1, z1) = (min[0] + w + grow, min[1] + h + grow, min[2] + d + grow);
    let (u, v) = uv;
    let faces: [([V3; 4], [f32; 4]); 6] = [
        ([[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]], [u + d, v + d, u + d + w, v + d + h]),
        ([[x1, y1, z0], [x0, y1, z0], [x0, y0, z0], [x1, y0, z0]], [u + 2. * d + w, v + d, u + 2. * d + 2. * w, v + d + h]),
        ([[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]], [u, v + d, u + d, v + d + h]),
        ([[x1, y1, z1], [x1, y1, z0], [x1, y0, z0], [x1, y0, z1]], [u + d + w, v + d, u + 2. * d + w, v + d + h]),
        ([[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]], [u + d, v, u + d + w, v + d]),
        ([[x0, y0, z1], [x1, y0, z1], [x1, y0, z0], [x0, y0, z0]], [u + d + w, v, u + d + 2. * w, v + d]),
    ];
    // texel units of the 64-wide layout -> 0..1 of this texture (HD capes keep the same proportions)
    let (sw, sh) = (64., if mode == Mode::Cut && tex.height() * 2 == tex.width() { 32. } else { 64. * tex.height() as f32 / tex.width() as f32 });
    for (p, t) in faces {
        let [a, b, c, e] = [t[0] / sw, t[1] / sh, t[2] / sw, t[3] / sh];
        out.push(Q { p: p.map(xf), uv: [[a, b], [c, b], [c, e], [a, e]], clamp: [a, b, c, e], tex, mode, alpha: 1., n: None });
    }
}

fn player<'a>(look: &Look<'a>, out: &mut Vec<Q<'a>>) {
    let id = |p: V3| p;
    let aw = if look.slim { 3. } else { 4. };
    let arm = |pivot: V3, a: f32| move |p: V3| { let r = rot_z(sub(p, pivot), a); [r[0] + pivot[0], r[1] + pivot[1], r[2] + pivot[2]] };
    let ra = arm([-5., 22., 0.], -0.06);
    let la = arm([5., 22., 0.], 0.06);
    let layers = [
        (Mode::Skin, 0.0, 0.0, [(0., 0.), (16., 16.), (40., 16.), (32., 48.), (0., 16.), (16., 48.)]),
        (Mode::Cut, 0.5, 0.25, [(32., 0.), (16., 32.), (40., 32.), (48., 48.), (0., 32.), (0., 48.)]),
    ];
    for (mode, gh, gb, uv) in layers {
        cuboid(out, look.skin, [-4., 24., -4.], [8., 8., 8.], gh, uv[0], &id, mode);
        cuboid(out, look.skin, [-4., 12., -2.], [8., 12., 4.], gb, uv[1], &id, mode);
        cuboid(out, look.skin, [-4. - aw, 12., -2.], [aw, 12., 4.], gb, uv[2], &ra, mode);
        cuboid(out, look.skin, [4., 12., -2.], [aw, 12., 4.], gb, uv[3], &la, mode);
        cuboid(out, look.skin, [-4., 0., -2.], [4., 12., 4.], gb, uv[4], &id, mode);
        cuboid(out, look.skin, [0., 0., -2.], [4., 12., 4.], gb, uv[5], &id, mode);
    }
    if let Some(cape) = look.cape {
        let xf = |p: V3| { let r = rot_x(rot_y(p, std::f32::consts::PI), 0.14); [r[0], r[1] + 24., r[2] - 2.9] };
        cuboid(out, cape, [-5., -16., -0.5], [10., 16., 1.], 0., (0., 0.), &xf, Mode::Cut);
    }
}

fn cosmetic<'a>(it: &'a Loaded, t: f64, now_ms: u64, out: &mut Vec<Q<'a>>) {
    let Some(m) = &it.model else { return };
    let frame = if it.frames > 1 { (now_ms / it.ms.max(1) as u64 % it.frames as u64) as f32 } else { 0. };
    let fr = it.frames as f32;
    for q in m.pose(t as f32) {
        // Blockbench pixels -> preview space at the body part's pivot (same pivots and arm tilt as `player`)
        let (piv, tilt): (V3, f32) = match q.anchor { 2 => ([-5., 22., 0.], -0.06), 3 => ([5., 22., 0.], 0.06), 4 => ([-1.9, 12., 0.], 0.), 5 => ([1.9, 12., 0.], 0.), _ => ([0., 24., 0.], 0.) };
        let place = |p: V3| -> V3 { let r = rot_z([8. - p[0], p[1], 8. - p[2]], tilt); [r[0] + piv[0], r[1] + piv[1], r[2] + piv[2]] };
        let uv = q.uv.map(|u| [u[0], (frame + u[1]) / fr]);
        let (mut a, mut b, mut c, mut d) = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
        for u in uv { a = a.min(u[0]); b = b.min(u[1]); c = c.max(u[0]); d = d.max(u[1]); }
        out.push(Q { p: q.p.map(place), uv, clamp: [a, b, c, d], tex: &it.tex, mode: if q.glow { Mode::Glow } else { Mode::Cos }, alpha: 1., n: Some(rot_z([-q.n[0], q.n[1], -q.n[2]], tilt)) });
    }
}

/// Camera-space billboards for aura particles.
fn aura<'a>(it: &'a Loaded, t: f64, view: &View, out: &mut Vec<(Q<'a>, bool)>) {
    let Some(a) = &it.aura else { return };
    for p in a.eval(t) {
        if p.alpha <= 0.01 || p.size <= 0. { continue; }
        let c = cam(p.p, view);
        let s = p.size / 2.;
        let col = a.col(p.frame, it.cols) as f32;
        let row = ((p.row * it.rows as f32) as u32).min(it.rows - 1) as f32;
        let (u0, u1, v0, v1) = (col / it.cols as f32, (col + 1.) / it.cols as f32, row / it.rows as f32, (row + 1.) / it.rows as f32);
        let q = Q {
            p: [[c[0] - s, c[1] + s, c[2]], [c[0] + s, c[1] + s, c[2]], [c[0] + s, c[1] - s, c[2]], [c[0] - s, c[1] - s, c[2]]],
            uv: [[u0, v0], [u1, v0], [u1, v1], [u0, v1]], clamp: [u0, v0, u1, v1], tex: &it.tex,
            mode: if a.glow { Mode::Glow } else { Mode::Cos }, alpha: p.alpha, n: Some([0., 0., 1.]),
        };
        out.push((q, true));
    }
}

fn cam(p: V3, v: &View) -> V3 { let c = rot_x(rot_y([p[0], p[1] - v.center, p[2]], v.yaw), v.tilt); [c[0] - v.pan, c[1], c[2]] }

/// Render into an RGBA buffer of w x h (cleared to transparent). `depth` is scratch reused between frames.
#[allow(clippy::too_many_arguments)]
pub fn render_into(px: &mut [u8], w: u32, h: u32, depth: &mut Vec<f32>, look: &Look, view: View, t: f64, now_ms: u64) {
    let (wu, hu) = (w as usize, h as usize);
    px.fill(0);
    depth.clear();
    depth.resize(wu * hu, 0.);
    let mut world = Vec::with_capacity(400);
    player(look, &mut world);
    for it in look.items.iter().flatten() { cosmetic(it, t, now_ms, &mut world); }
    // to camera space; already-camera-space particles join afterwards
    let mut all: Vec<(Q, bool)> = world.into_iter().map(|q| (q, false)).collect();
    for it in look.items.iter().flatten() { aura(it, t, &view, &mut all); }
    let light = { let l = [-0.45f32, 0.6, 0.75]; let n = (l[0] * l[0] + l[1] * l[1] + l[2] * l[2]).sqrt(); [l[0] / n, l[1] / n, l[2] / n] };
    let dist = DIST;
    let k = h as f32 * 0.86 / 34. * view.zoom;
    let (cx, cy) = (w as f32 / 2., h as f32 / 2.);
    struct P<'b> { s: [(f32, f32, f32); 4], q: &'b Q<'b>, shade: f32, z: f32 }
    let mut opaque = vec![];
    let mut blend = vec![];
    for (q, in_cam) in &all {
        let c: [V3; 4] = if *in_cam { q.p } else { q.p.map(|p| cam(p, &view)) };
        let shade = match q.mode {
            Mode::Glow => 1.,
            _ => {
                let mut n = match q.n { Some(n) if !*in_cam => cam_dir(n, &view), Some(n) => n, None => { let n = cross(sub(c[1], c[0]), sub(c[2], c[1])); [-n[0], -n[1], -n[2]] } };
                if q.mode == Mode::Cos && n[2] < 0. { n = [-n[0], -n[1], -n[2]]; } // two-sided: the side we see
                let l = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt().max(1e-6);
                let nd = (n[0] * light[0] + n[1] * light[1] + n[2] * light[2]) / l;
                0.5 + 0.5 * nd.max(0.)
            }
        };
        let s = c.map(|p| { let iw = 1. / (dist - p[2]); (cx + p[0] * dist * iw * k, cy - p[1] * dist * iw * k, iw) });
        let z = c.iter().map(|p| p[2]).sum::<f32>() / 4.;
        let p = P { s, q, shade, z };
        if matches!(q.mode, Mode::Skin | Mode::Cut) { opaque.push(p); } else { blend.push(p); }
    }
    // opaque texels of everything (cosmetics too), then translucent texels / particles far to near
    for p in opaque.iter().chain(blend.iter().filter(|p| p.q.alpha >= 1.)) { raster(px, depth, wu, hu, p.s, p.q, p.shade, 0); }
    blend.sort_by(|a, b| a.z.partial_cmp(&b.z).unwrap_or(std::cmp::Ordering::Equal));
    for p in &blend { raster(px, depth, wu, hu, p.s, p.q, p.shade, 1); }
}

fn cam_dir(n: V3, v: &View) -> V3 { rot_x(rot_y(n, v.yaw), v.tilt) }

/// pass 0: opaque texels (depth write); pass 1: translucent texels and faded particles (blend, depth test only).
fn raster(px: &mut [u8], depth: &mut [f32], w: usize, h: usize, sp: [(f32, f32, f32); 4], q: &Q, shade: f32, pass: u8) {
    let tex = q.tex;
    let (tw, th) = (tex.width() as f32, tex.height() as f32);
    let (umin, vmin) = ((q.clamp[0] * tw).floor() as i32, (q.clamp[1] * th).floor() as i32);
    let (umax, vmax) = (((q.clamp[2] * tw).ceil() as i32 - 1).max(umin), ((q.clamp[3] * th).ceil() as i32 - 1).max(vmin));
    let two_sided = !matches!(q.mode, Mode::Skin | Mode::Cut);
    for tri in [[0usize, 1, 2], [0, 2, 3]] {
        let (a, mut b, mut c) = (sp[tri[0]], sp[tri[1]], sp[tri[2]]);
        let (ta, mut tb, mut tc) = (q.uv[tri[0]], q.uv[tri[1]], q.uv[tri[2]]);
        let mut area = (b.0 - a.0) * (c.1 - a.1) - (c.0 - a.0) * (b.1 - a.1);
        if area.abs() < 1e-6 { continue; }
        if area < 0. {
            if !two_sided { continue; }
            std::mem::swap(&mut b, &mut c);
            std::mem::swap(&mut tb, &mut tc);
            area = -area;
        }
        let x0 = a.0.min(b.0).min(c.0).floor().max(0.) as usize;
        let x1 = (a.0.max(b.0).max(c.0).ceil().max(0.) as usize).min(w - 1);
        let y0 = a.1.min(b.1).min(c.1).floor().max(0.) as usize;
        let y1 = (a.1.max(b.1).max(c.1).ceil().max(0.) as usize).min(h - 1);
        if x0 > x1 || y0 > y1 { continue; }
        for y in y0..=y1 {
            let fy = y as f32 + 0.5;
            for x in x0..=x1 {
                let fx = x as f32 + 0.5;
                let wa = (b.0 - fx) * (c.1 - fy) - (c.0 - fx) * (b.1 - fy);
                let wb = (c.0 - fx) * (a.1 - fy) - (a.0 - fx) * (c.1 - fy);
                let wc = area - wa - wb;
                if wa < 0. || wb < 0. || wc < 0. { continue; }
                let (la, lb, lc) = (wa / area, wb / area, wc / area);
                let iw = la * a.2 + lb * b.2 + lc * c.2;
                let i = y * w + x;
                if iw <= depth[i] { continue; }
                let u = (la * ta[0] * a.2 + lb * tb[0] * b.2 + lc * tc[0] * c.2) / iw;
                let v = (la * ta[1] * a.2 + lb * tb[1] * b.2 + lc * tc[1] * c.2) / iw;
                let tx = ((u * tw) as i32).clamp(umin, umax).clamp(0, tw as i32 - 1) as u32;
                let ty = ((v * th) as i32).clamp(vmin, vmax).clamp(0, th as i32 - 1) as u32;
                let t = tex.get_pixel(tx, ty).0;
                let al = t[3] as f32 / 255. * q.alpha;
                let o = i * 4;
                let col = [(t[0] as f32 * shade).min(255.), (t[1] as f32 * shade).min(255.), (t[2] as f32 * shade).min(255.)];
                match q.mode {
                    Mode::Skin => { if pass != 0 { continue; } }
                    Mode::Cut => { if pass != 0 || t[3] < 128 { continue; } }
                    _ => {
                        if pass == 0 { if al < 0.98 { continue; } }
                        else if al >= 0.98 || al < 0.04 { continue; }
                    }
                }
                if pass == 0 {
                    depth[i] = iw;
                    px[o] = col[0] as u8; px[o + 1] = col[1] as u8; px[o + 2] = col[2] as u8; px[o + 3] = 255;
                } else {
                    let da = px[o + 3] as f32 / 255.;
                    let oa = al + da * (1. - al);
                    if oa <= 0. { continue; }
                    for k in 0..3 { px[o + k] = ((col[k] * al + px[o + k] as f32 * da * (1. - al)) / oa).round().min(255.) as u8; }
                    px[o + 3] = (oa * 255.).round() as u8;
                }
            }
        }
    }
}

/// A finished frame as an image (cards, thumbnails, tests).
pub fn render(look: &Look, view: View, w: u32, h: u32, t: f64, now_ms: u64) -> RgbaImage {
    let mut buf = vec![0u8; (w * h * 4) as usize];
    let mut depth = vec![];
    render_into(&mut buf, w, h, &mut depth, look, view, t, now_ms);
    RgbaImage::from_raw(w, h, buf).unwrap_or_else(|| RgbaImage::new(w, h))
}
