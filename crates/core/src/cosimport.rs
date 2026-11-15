//! Cosmetic source files -> Nether's cosmetic format, in the launcher (community uploads). The same rules as the
//! owner's importer (tools/cosmetics/import.mjs + formats.mjs), so a file imports the same either way:
//! Blockbench `.bbmodel` / "Java Block/Item" `.json`, Bedrock / GeckoLib `.geo.json` (+ `.animation.json`, + Lunar
//! `.gek.json`), Wavefront `.obj`, particle aura emitters, capes; textures PNG (incl. APNG), WebP, GIF (animated ones
//! and vertical strips become animated textures); zips and folders of any of these.
//! Spaces: Bedrock models are in player space (pixels, Y up, origin between the feet, the player faces -Z, X mirrored
//! like the game's ModelPart); ours are Blockbench pixels around the body part's pivot at (8, 0, 8): x = 8 - (bx - px),
//! y = by - py, z = 8 + (bz - pz); rotations [x, y, z] -> [-x, -y, z].
use std::collections::{HashMap, HashSet};
use std::io::{Cursor, Read};
use std::path::Path;

use anyhow::{anyhow, bail, Result};
use image::{AnimationDecoder, ImageDecoder, RgbaImage};
use serde_json::{json, Map, Value};

use crate::cosmetics::Kind;

/// What an upload is: a cape (one image) or a cosmetic of a slot.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum What { Cape, Item(Kind) }

impl What {
    pub fn as_str(self) -> &'static str { match self { What::Cape => "cape", What::Item(k) => k.as_str() } }
    pub fn parse(s: &str) -> Option<What> { if s == "cape" { Some(What::Cape) } else { Kind::parse(s).map(What::Item) } }
    /// Every type, in the wardrobe's page order.
    pub const ALL: [What; 8] = [What::Cape, What::Item(Kind::Hat), What::Item(Kind::Headband), What::Item(Kind::Mask), What::Item(Kind::Back), What::Item(Kind::Wings), What::Item(Kind::Aura), What::Item(Kind::Pet)];
}

/// Largest community model JSON and texture PNG (the API's limits).
pub const MAX_MODEL: usize = 256 * 1024;
pub const MAX_PNG: usize = 1024 * 1024;
const MAX_INPUT: usize = 96 * 1024 * 1024;

/// A converted upload: the model JSON (None for capes), the texture PNG and its frame time.
#[derive(Clone, Debug)]
pub struct Imported {
    pub what: What,
    pub model: Option<Vec<u8>>,
    pub png: Vec<u8>,
    pub frame_ms: Option<u32>,
    /// A name from the files (the model's or the image's), for the name field.
    pub name: String,
    pub warnings: Vec<String>,
    pub summary: String,
}

// ---------------------------------------------------------------- inputs

/// Source files by path (zips unpacked, `\` -> `/`).
#[derive(Default)]
pub struct Files { names: Vec<String>, data: HashMap<String, Vec<u8>>, total: usize, label: String }

impl Files {
    fn add(&mut self, name: &str, bytes: Vec<u8>, depth: u32) -> Result<()> {
        let name = name.replace('\\', "/");
        if name.to_ascii_lowercase().ends_with(".zip") && depth < 2 {
            let mut z = zip::ZipArchive::new(Cursor::new(bytes)).map_err(|_| anyhow!("{name}: not a zip archive"))?;
            for i in 0..z.len().min(1024) {
                let mut f = z.by_index(i).map_err(|e| anyhow!("{name}: {e}"))?;
                let n = String::from_utf8_lossy(f.name_raw()).to_string();
                if f.is_dir() || n.starts_with("__MACOSX/") { continue; }
                let mut b = Vec::new();
                (&mut f).take((MAX_INPUT - self.total.min(MAX_INPUT)) as u64 + 1).read_to_end(&mut b)?;
                self.add(&n, b, depth + 1)?;
            }
            return Ok(());
        }
        self.total += bytes.len();
        if self.total > MAX_INPUT { bail!("the files are over 96 MB together"); }
        if !self.data.contains_key(&name) { self.names.push(name.clone()); }
        self.data.insert(name, bytes);
        Ok(())
    }

    /// Files, folders (their contents) and zips from disk.
    pub fn from_paths(paths: &[std::path::PathBuf]) -> Result<Files> {
        let mut f = Files::default();
        fn walk(f: &mut Files, dir: &Path, pre: &str, depth: u32) -> Result<()> {
            if depth > 4 { return Ok(()); }
            for e in std::fs::read_dir(dir)?.flatten().take(512) {
                let n = e.file_name().to_string_lossy().to_string();
                let p = e.path();
                if p.is_dir() { walk(f, &p, &format!("{pre}{n}/"), depth + 1)?; } else if p.metadata().map(|m| m.len() as usize).unwrap_or(0) <= MAX_INPUT { f.add(&format!("{pre}{n}"), std::fs::read(&p)?, 0)?; }
            }
            Ok(())
        }
        if let Some(p) = paths.first() { f.label = p.file_stem().map(|n| n.to_string_lossy().to_string()).unwrap_or_default(); }
        for p in paths {
            if p.is_dir() { walk(&mut f, p, "", 0)?; continue; }
            let n = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            if p.metadata()?.len() as usize > MAX_INPUT { bail!("{n} is over 96 MB"); }
            f.add(&n, std::fs::read(p)?, 0)?;
        }
        if f.names.is_empty() { bail!("no files"); }
        Ok(f)
    }

    /// Files given as (name, bytes).
    pub fn from_bytes(list: Vec<(String, Vec<u8>)>) -> Result<Files> {
        let mut f = Files::default();
        for (n, b) in list { f.add(&n, b, 0)?; }
        Ok(f)
    }
}

fn base(n: &str) -> String { n.rsplit(['/', '\\', ':']).next().unwrap_or(n).to_lowercase() }
fn stem(n: &str) -> String { let b = base(n); match b.rfind('.') { Some(i) if i > 0 => b[..i].to_string(), _ => b } }
fn is_image(n: &str) -> bool { let b = base(n); [".png", ".webp", ".gif", ".avif"].iter().any(|e| b.ends_with(e)) }
fn r4(v: f64) -> f64 { (v * 10000.).round() / 10000. }
fn f(v: &Value) -> f64 { v.as_f64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok())).unwrap_or(0.) }
fn arr3(v: &Value, d: f64) -> [f64; 3] {
    let mut o = [d; 3];
    if let Some(a) = v.as_array() { for (i, x) in a.iter().take(3).enumerate() { o[i] = x.as_f64().unwrap_or(d); } }
    o
}
fn nz(a: [f64; 3]) -> bool { a.iter().any(|v| *v != 0.) }

// ---------------------------------------------------------------- images

/// A decoded image: every frame stacked vertically.
#[derive(Clone)]
struct Img { img: RgbaImage, frames: u32, ms: Option<u32> }

fn stack(frames: Vec<image::Frame>) -> Result<Img> {
    let n = frames.len().min(256);
    if n == 0 { bail!("the image has no frames"); }
    let (w, h) = frames[0].buffer().dimensions();
    if (w as u64) * (h as u64) * n as u64 > 64 * 1024 * 1024 { bail!("the animation is too big"); }
    let mut out = RgbaImage::new(w, h * n as u32);
    let mut total = 0u32;
    for (i, fr) in frames.into_iter().take(n).enumerate() {
        let (a, b) = fr.delay().numer_denom_ms();
        total += if b == 0 { 100 } else { a / b.max(1) };
        let buf = fr.into_buffer();
        image::imageops::overlay(&mut out, &image::imageops::crop_imm(&buf, 0, 0, w, h).to_image(), 0, i as i64 * h as i64);
    }
    let ms = (n > 1).then(|| (total / n as u32).clamp(20, 2000));
    Ok(Img { img: out, frames: n as u32, ms })
}

fn decode_image(name: &str, b: &[u8]) -> Result<Img> {
    let limits = |(w, h): (u32, u32)| w as u64 * h as u64 <= 64 * 1024 * 1024;
    if b.len() > 12 && (&b[4..12] == b"ftypavif" || &b[4..12] == b"ftypavis") { return avif(name, b); }
    let fmt = image::guess_format(b).map_err(|_| anyhow!("{name}: not a PNG, WebP or GIF image"))?;
    match fmt {
        image::ImageFormat::Png => {
            let d = image::codecs::png::PngDecoder::new(Cursor::new(b))?;
            if !limits(d.dimensions()) { bail!("{name}: the image is too big"); }
            if d.is_apng()? { return stack(d.apng()?.into_frames().collect_frames()?); }
            Ok(Img { img: image::DynamicImage::from_decoder(d)?.to_rgba8(), frames: 1, ms: None })
        }
        image::ImageFormat::Gif => stack(image::codecs::gif::GifDecoder::new(Cursor::new(b))?.into_frames().collect_frames()?),
        image::ImageFormat::WebP => {
            let d = image::codecs::webp::WebPDecoder::new(Cursor::new(b))?;
            if !limits(d.dimensions()) { bail!("{name}: the image is too big"); }
            if d.has_animation() { return stack(d.into_frames().collect_frames()?); }
            Ok(Img { img: image::DynamicImage::from_decoder(d)?.to_rgba8(), frames: 1, ms: None })
        }
        _ => bail!("{name}: use a PNG, WebP or GIF image"),
    }
}

/// AVIF through Windows' own image decoder (needs the AV1 Video Extension, preinstalled on most Windows 11 PCs); every
/// frame the decoder exposes.
#[cfg(windows)]
fn avif(name: &str, b: &[u8]) -> Result<Img> {
    use windows::Graphics::Imaging::{BitmapAlphaMode, BitmapDecoder, BitmapPixelFormat, BitmapTransform, ColorManagementMode, ExifOrientationMode};
    use windows::Storage::Streams::{DataWriter, InMemoryRandomAccessStream};
    let go = || -> windows::core::Result<Vec<(u32, u32, Vec<u8>)>> {
        let stream = InMemoryRandomAccessStream::new()?;
        let w = DataWriter::CreateDataWriter(&stream.GetOutputStreamAt(0)?)?;
        w.WriteBytes(b)?;
        w.StoreAsync()?.join()?;
        w.FlushAsync()?.join()?;
        w.DetachStream()?;
        stream.Seek(0)?;
        let dec = BitmapDecoder::CreateAsync(&stream)?.join()?;
        let mut out = vec![];
        for i in 0..dec.FrameCount()?.clamp(1, 256) {
            let fr = dec.GetFrameAsync(i)?.join()?;
            let (fw, fh) = (fr.PixelWidth()?, fr.PixelHeight()?);
            if fw as u64 * fh as u64 > 16 * 1024 * 1024 { break; }
            let px = fr.GetPixelDataTransformedAsync(BitmapPixelFormat::Rgba8, BitmapAlphaMode::Straight, &BitmapTransform::new()?, ExifOrientationMode::IgnoreExifOrientation, ColorManagementMode::DoNotColorManage)?.join()?.DetachPixelData()?;
            out.push((fw, fh, px.to_vec()));
        }
        Ok(out)
    };
    let frames = go().map_err(|_| anyhow!("{name}: Windows can't read this AVIF texture: install the free \"AV1 Video Extension\" from the Microsoft Store, or save the texture as PNG"))?;
    let (w, h) = (frames[0].0, frames[0].1);
    let same: Vec<Vec<u8>> = frames.into_iter().filter(|f| f.0 == w && f.1 == h).map(|f| f.2).collect();
    let n = same.len() as u32;
    let img = RgbaImage::from_raw(w, h * n, same.concat()).ok_or_else(|| anyhow!("{name}: unreadable AVIF"))?;
    Ok(Img { img, frames: n, ms: (n > 1).then_some(100) })
}

#[cfg(not(windows))]
fn avif(name: &str, _: &[u8]) -> Result<Img> { bail!("{name}: AVIF textures can't be read here: save it as PNG or WebP") }

fn png_bytes(img: &RgbaImage) -> Result<Vec<u8>> {
    use image::ImageEncoder;
    let mut out = Vec::new();
    image::codecs::png::PngEncoder::new_with_quality(&mut out, image::codecs::png::CompressionType::Best, image::codecs::png::FilterType::Adaptive)
        .write_image(img.as_raw(), img.width(), img.height(), image::ExtendedColorType::Rgba8)?;
    Ok(out)
}

// ---------------------------------------------------------------- Molang (the subset cosmetics use)

#[derive(Clone, Debug)]
enum Ex { N(f64), T, Var(String), Neg(Box<Ex>), Not(Box<Ex>), Bin(&'static str, Box<Ex>, Box<Ex>), If(Box<Ex>, Box<Ex>, Box<Ex>), Call(String, Vec<Ex>), Block(Vec<St>) }
#[derive(Clone, Debug)]
enum St { Ret(Ex), Set(String, Ex), Ex(Ex) }

#[derive(Clone, Debug)]
struct Molang { e: Ex, timed: bool }

#[derive(Debug, Clone, PartialEq)]
enum Tok { N(f64), Id(String), Str, Op(&'static str) }

const OPS: [&str; 26] = ["&&", "||", "==", "!=", "<=", ">=", "??", "->", "-", "+", "*", "/", "%", "(", ")", "?", ":", "<", ">", "!", ",", ";", "=", "[", "]", "{"];
// queries for a player standing still: time is the only input
const QUERY: [(&str, f64); 26] = [("delta_time", 0.05), ("ground_speed", 0.), ("modified_distance_moved", 0.), ("modified_move_speed", 0.), ("walk_distance", 0.),
    ("is_on_ground", 1.), ("is_alive", 1.), ("is_moving", 0.), ("is_sneaking", 0.), ("is_sprinting", 0.), ("is_swimming", 0.), ("is_flying", 0.), ("is_gliding", 0.),
    ("is_in_gui", 0.), ("is_first_person", 0.), ("head_x_rotation", 0.), ("head_y_rotation", 0.), ("body_x_rotation", 0.), ("body_y_rotation", 0.), ("yaw_speed", 0.),
    ("vertical_speed", 0.), ("frame_alpha", 0.), ("time_of_day", 0.5), ("health", 20.), ("max_health", 20.), ("scale", 1.)];

impl Molang {
    fn num(v: f64) -> Molang { Molang { e: Ex::N(v), timed: false } }

    fn of(v: &Value, warn: &mut Vec<String>) -> Result<Molang> {
        match v {
            Value::Number(n) => Ok(Molang::num(n.as_f64().unwrap_or(0.))),
            Value::String(s) => Molang::parse(s, warn),
            Value::Null => Ok(Molang::num(0.)),
            Value::Bool(b) => Ok(Molang::num(if *b { 1. } else { 0. })),
            _ => bail!("molang: can't read {v}"),
        }
    }

    fn parse(src: &str, warn: &mut Vec<String>) -> Result<Molang> {
        let s = src.trim().to_lowercase();
        if s.is_empty() { return Ok(Molang::num(0.)); }
        if let Ok(v) = s.parse::<f64>() { return Ok(Molang::num(v)); }
        let toks = lex(&s).map_err(|e| anyhow!("molang: {e} in {src}"))?;
        let mut p = P { t: toks, i: 0, timed: false, warn, src };
        let e = p.block(None)?;
        if p.i < p.t.len() { bail!("molang: unexpected {:?} in {src}", p.t[p.i]); }
        Ok(Molang { e, timed: p.timed })
    }

    fn eval(&self, t: f64) -> f64 {
        let mut vars = HashMap::new();
        let v = ev(&self.e, t, &mut vars).0;
        if v.is_finite() { v } else { 0. }
    }
}

fn lex(s: &str) -> std::result::Result<Vec<Tok>, String> {
    let b = s.as_bytes();
    let (mut i, mut out) = (0, vec![]);
    while i < b.len() {
        let c = b[i];
        if c.is_ascii_whitespace() { i += 1; continue; }
        if c.is_ascii_digit() || (c == b'.' && i + 1 < b.len() && b[i + 1].is_ascii_digit()) {
            let st = i;
            while i < b.len() && (b[i].is_ascii_digit() || b[i] == b'.') { i += 1; }
            if i < b.len() && b[i] == b'e' && i + 1 < b.len() && (b[i + 1].is_ascii_digit() || (b[i + 1] == b'-' && i + 2 < b.len() && b[i + 2].is_ascii_digit())) {
                i += 2;
                while i < b.len() && b[i].is_ascii_digit() { i += 1; }
            }
            out.push(Tok::N(s[st..i].parse().map_err(|_| format!("bad number {}", &s[st..i]))?));
            continue;
        }
        if c.is_ascii_alphabetic() || c == b'_' {
            let st = i;
            while i < b.len() && (b[i].is_ascii_alphanumeric() || b[i] == b'_' || (b[i] == b'.' && i + 1 < b.len() && (b[i + 1].is_ascii_alphabetic() || b[i + 1] == b'_'))) { i += 1; }
            out.push(Tok::Id(s[st..i].to_string()));
            continue;
        }
        if c == b'\'' {
            let e = s[i + 1..].find('\'').ok_or("unclosed string")?;
            i += e + 2;
            out.push(Tok::Str);
            continue;
        }
        let op = OPS.iter().chain(["}"].iter()).find(|o| s[i..].starts_with(**o)).ok_or_else(|| format!("can't read \"{}\"", &s[i..(i + 12).min(s.len())]))?;
        out.push(Tok::Op(op));
        i += op.len();
    }
    Ok(out)
}

struct P<'a> { t: Vec<Tok>, i: usize, timed: bool, warn: &'a mut Vec<String>, src: &'a str }

impl P<'_> {
    fn peek(&self, o: &str) -> bool { matches!(self.t.get(self.i), Some(Tok::Op(x)) if *x == o) }
    fn eat(&mut self, o: &str) -> Result<()> { if !self.peek(o) { bail!("molang: expected \"{o}\" in {}", self.src); } self.i += 1; Ok(()) }
    fn name(id: &str) -> String {
        let mut p: Vec<&str> = id.split('.').collect();
        p[0] = match p[0] { "q" => "query", "v" => "variable", "t" => "temp", "c" => "context", x => x };
        p.join(".")
    }
    fn primary(&mut self) -> Result<Ex> {
        let k = self.t.get(self.i).cloned().ok_or_else(|| anyhow!("molang: unexpected end of {}", self.src))?;
        self.i += 1;
        Ok(match k {
            Tok::N(n) => Ex::N(n),
            Tok::Str => Ex::N(0.),
            Tok::Op("(") => { let e = self.expr()?; self.eat(")")?; e }
            Tok::Op("-") => Ex::Neg(Box::new(self.primary()?)),
            Tok::Op("+") => self.primary()?,
            Tok::Op("!") => Ex::Not(Box::new(self.primary()?)),
            Tok::Op("{") => self.block(Some("}"))?,
            Tok::Id(id) => {
                let id = P::name(&id);
                if self.peek("(") {
                    self.i += 1;
                    let mut args = vec![];
                    while !self.peek(")") { args.push(self.expr()?); if self.peek(",") { self.i += 1; } else { break; } }
                    self.eat(")")?;
                    match id.strip_prefix("math.") {
                        Some(f) if MATH.contains(&f) => Ex::Call(f.to_string(), args),
                        _ => { self.warn.push(format!("molang: {id}() is not supported, 0 used")); Ex::N(0.) }
                    }
                } else {
                    match id.as_str() {
                        "math.pi" => Ex::N(std::f64::consts::PI),
                        "true" => Ex::N(1.),
                        "false" | "this" => Ex::N(0.),
                        "query.anim_time" | "query.life_time" | "query.time_stamp" | "query.key_frame_lerp_time" => { self.timed = true; Ex::T }
                        _ if id.starts_with("query.") => {
                            let q = &id[6..];
                            match QUERY.iter().find(|(k, _)| *k == q) { Some((_, v)) => Ex::N(*v), None => { self.warn.push(format!("molang: {id} is not known, 0 used")); Ex::N(0.) } }
                        }
                        _ if id.starts_with("variable.") || id.starts_with("temp.") || id.starts_with("context.") => Ex::Var(id),
                        _ => { self.warn.push(format!("molang: \"{id}\" is not known, 0 used")); Ex::N(0.) }
                    }
                }
            }
            Tok::Op(o) => bail!("molang: unexpected \"{o}\" in {}", self.src),
        })
    }
    fn bin(&mut self, level: usize) -> Result<Ex> {
        const LV: [&[&str]; 7] = [&["??"], &["||"], &["&&"], &["==", "!="], &["<", "<=", ">", ">="], &["+", "-"], &["*", "/", "%"]];
        if level == LV.len() { return self.primary(); }
        let mut a = self.bin(level + 1)?;
        while let Some(Tok::Op(o)) = self.t.get(self.i).cloned() {
            if !LV[level].contains(&o) { break; }
            self.i += 1;
            let b = self.bin(level + 1)?;
            a = Ex::Bin(o, Box::new(a), Box::new(b));
        }
        Ok(a)
    }
    fn expr(&mut self) -> Result<Ex> {
        let c = self.bin(0)?;
        if self.peek("?") {
            self.i += 1;
            let a = self.expr()?;
            let b = if self.peek(":") { self.i += 1; self.expr()? } else { Ex::N(0.) };
            return Ok(Ex::If(Box::new(c), Box::new(a), Box::new(b)));
        }
        Ok(c)
    }
    fn statement(&mut self) -> Result<St> {
        if let Some(Tok::Id(k)) = self.t.get(self.i).cloned() {
            if k == "return" { self.i += 1; return Ok(St::Ret(self.expr()?)); }
            if matches!(self.t.get(self.i + 1), Some(Tok::Op("="))) { self.i += 2; return Ok(St::Set(P::name(&k), self.expr()?)); }
        }
        Ok(St::Ex(self.expr()?))
    }
    fn block(&mut self, end: Option<&str>) -> Result<Ex> {
        let mut st = vec![];
        while self.i < self.t.len() && !end.is_some_and(|e| self.peek(e)) {
            st.push(self.statement()?);
            if self.peek(";") { self.i += 1; } else { break; }
        }
        if let Some(e) = end { self.eat(e)?; }
        Ok(Ex::Block(st))
    }
}

const MATH: [&str; 29] = ["abs", "sin", "cos", "asin", "acos", "atan", "atan2", "ceil", "floor", "round", "trunc", "clamp", "lerp", "max", "min", "mod", "pow", "sqrt",
    "exp", "ln", "sign", "hermite_blend", "min_angle", "lerprotate", "copy_sign", "random", "random_integer", "die_roll", "die_roll_integer"];

fn wrap180(a: f64) -> f64 { ((a + 180.) % 360. + 360.) % 360. - 180. }

/// value, and whether a `return` ended the block
fn ev(e: &Ex, t: f64, vars: &mut HashMap<String, f64>) -> (f64, bool) {
    let v = |e: &Ex, vars: &mut HashMap<String, f64>| ev(e, t, vars).0;
    let r = match e {
        Ex::N(n) => *n,
        Ex::T => t,
        Ex::Var(n) => vars.get(n).copied().unwrap_or(0.),
        Ex::Neg(a) => -v(a, vars),
        Ex::Not(a) => if v(a, vars) != 0. { 0. } else { 1. },
        Ex::Bin(o, a, b) => {
            let (x, y) = (v(a, vars), v(b, vars));
            let tf = |c: bool| if c { 1. } else { 0. };
            match *o {
                "*" => x * y, "/" => if y != 0. { x / y } else { 0. }, "%" => if y != 0. { x % y } else { 0. },
                "+" => x + y, "-" => x - y, "<" => tf(x < y), "<=" => tf(x <= y), ">" => tf(x > y), ">=" => tf(x >= y),
                "==" => tf(x == y), "!=" => tf(x != y), "&&" => tf(x != 0. && y != 0.), "||" => tf(x != 0. || y != 0.),
                _ => if x != 0. { x } else { y },
            }
        }
        Ex::If(c, a, b) => if v(c, vars) != 0. { v(a, vars) } else { v(b, vars) },
        Ex::Call(f, args) => {
            let a: Vec<f64> = args.iter().map(|x| v(x, vars)).collect();
            let g = |i: usize| a.get(i).copied().unwrap_or(0.);
            let (d, r) = (std::f64::consts::PI / 180., 180. / std::f64::consts::PI);
            match f.as_str() {
                "abs" => g(0).abs(), "sin" => (g(0) * d).sin(), "cos" => (g(0) * d).cos(), "asin" => g(0).asin() * r, "acos" => g(0).acos() * r,
                "atan" => g(0).atan() * r, "atan2" => g(0).atan2(g(1)) * r, "ceil" => g(0).ceil(), "floor" => g(0).floor(), "round" => g(0).round(),
                "trunc" => g(0).trunc(), "clamp" => g(0).max(g(1)).min(g(2)), "lerp" => g(0) + (g(1) - g(0)) * g(2),
                "max" => a.iter().copied().fold(f64::NEG_INFINITY, f64::max), "min" => a.iter().copied().fold(f64::INFINITY, f64::min),
                "mod" => if g(1) != 0. { g(0) % g(1) } else { 0. }, "pow" => g(0).powf(g(1)), "sqrt" => g(0).sqrt(), "exp" => g(0).exp(),
                "ln" => g(0).ln(), "sign" => if g(0) > 0. { 1. } else if g(0) < 0. { -1. } else { 0. },
                "hermite_blend" => { let x = g(0); 3. * x * x - 2. * x * x * x }
                "min_angle" => wrap180(g(0)), "lerprotate" => g(0) + wrap180(g(1) - g(0)) * g(2),
                "copy_sign" => g(0).abs() * if g(1) < 0. { -1. } else { 1. },
                "random" => (g(0) + g(1)) / 2., "random_integer" => ((g(0) + g(1)) / 2.).round(),
                "die_roll" => g(0) * (g(1) + g(2)) / 2., _ => (g(0) * (g(1) + g(2)) / 2.).round(),
            }
        }
        Ex::Block(st) => {
            let mut last = 0.;
            for s in st {
                match s {
                    St::Ret(e) => return (v(e, vars), true),
                    St::Set(n, e) => { let x = v(e, vars); vars.insert(n.clone(), x); }
                    St::Ex(e) => { let (x, ret) = ev(e, t, vars); if ret { return (x, true); } last = x; }
                }
            }
            last
        }
    };
    (r, false)
}

// ---------------------------------------------------------------- Bedrock / GeckoLib animations

#[derive(Clone, Copy, Debug)]
struct Ease { fam: u8, mode: u8, arg: Option<f64> }

impl Ease {
    /// GeckoLib easing name (+ args), None for linear
    fn of(name: &Value, args: &Value) -> Option<Ease> {
        let n = name.as_str()?.to_lowercase();
        let rest = n.strip_prefix("ease")?;
        let (mode, fam) = if let Some(f) = rest.strip_prefix("inout") { (2, f) } else if let Some(f) = rest.strip_prefix("in") { (0, f) } else if let Some(f) = rest.strip_prefix("out") { (1, f) } else { return None };
        let fam = ["sine", "quad", "cubic", "quart", "quint", "expo", "circ", "back", "elastic", "bounce"].iter().position(|x| *x == fam)? as u8;
        Some(Ease { fam, mode, arg: args.as_array().and_then(|a| a.first()).and_then(|v| v.as_f64()) })
    }
    fn base(&self, t: f64) -> f64 {
        let pi = std::f64::consts::PI;
        match self.fam {
            0 => 1. - (t * pi / 2.).cos(), 1 => t * t, 2 => t * t * t, 3 => t.powi(4), 4 => t.powi(5),
            5 => if t == 0. { 0. } else { 2f64.powf(10. * t - 10.) }, 6 => 1. - (1. - t * t).max(0.).sqrt(),
            7 => { let k = self.arg.unwrap_or(1.70158); t * t * ((k + 1.) * t - k) }
            _ => { let k = self.arg.filter(|k| *k != 0.).unwrap_or(3.); if t == 0. || t == 1. { t } else { -2f64.powf(10. * t - 10.) * ((t * 10. - 10.75) * (2. * pi) / k).sin() } }
        }
    }
    fn apply(&self, t: f64) -> f64 {
        if self.fam == 9 {
            let out = |t: f64| { let (n, d) = (7.5625, 2.75); if t < 1. / d { n * t * t } else if t < 2. / d { let t = t - 1.5 / d; n * t * t + 0.75 } else if t < 2.5 / d { let t = t - 2.25 / d; n * t * t + 0.9375 } else { let t = t - 2.625 / d; n * t * t + 0.984375 } };
            return match self.mode { 0 => 1. - out(1. - t), 1 => out(t), _ => if t < 0.5 { (1. - out(1. - 2. * t)) / 2. } else { (1. + out(2. * t - 1.)) / 2. } };
        }
        match self.mode { 0 => self.base(t), 1 => 1. - self.base(1. - t), _ => if t < 0.5 { self.base(2. * t) / 2. } else { 1. - self.base(2. - 2. * t) / 2. } }
    }
}

#[derive(Clone, Copy, PartialEq)]
enum Lerp { Linear, Step, Catmull }

struct Key { t: f64, pre: [Molang; 3], post: [Molang; 3], lerp: Lerp, ease: Option<Ease> }

/// one bone channel: a constant (`keys` empty) or keyframes
struct Channel { cst: [Molang; 3], keys: Vec<Key>, timed: bool, end: f64, plain: bool }

fn vec3m(v: &Value, warn: &mut Vec<String>) -> Result<[Molang; 3]> {
    let v = if v.is_object() && !v["vector"].is_null() { &v["vector"] } else { v };
    let one = |i: usize, warn: &mut Vec<String>| -> Result<Molang> {
        match v.as_array() { Some(a) => Molang::of(a.get(i).unwrap_or(&Value::Null), warn), None => Molang::of(v, warn) }
    };
    Ok([one(0, warn)?, one(1, warn)?, one(2, warn)?])
}

impl Channel {
    fn of(v: &Value, warn: &mut Vec<String>) -> Result<Channel> {
        let keyed = v.as_object().is_some_and(|o| o.get("vector").is_none() && !o.is_empty() && o.keys().all(|k| k.trim().parse::<f64>().is_ok()));
        if !keyed {
            let c = vec3m(v, warn)?;
            let timed = c.iter().any(|m| m.timed);
            return Ok(Channel { cst: c, keys: vec![], timed, end: 0., plain: true });
        }
        let mut keys = vec![];
        for (t, k) in v.as_object().unwrap() {
            let o = if k.is_object() { k.clone() } else { json!({ "vector": k }) };
            let pre = if !o["pre"].is_null() { vec3m(&o["pre"], warn)? } else if !o["vector"].is_null() { vec3m(&o["vector"], warn)? } else { vec3m(&o["post"], warn)? };
            let post = if !o["post"].is_null() { vec3m(&o["post"], warn)? } else { pre.clone() };
            let e = o["easing"].as_str().unwrap_or("").to_lowercase();
            let lerp = match o["lerp_mode"].as_str() { Some("catmullrom") => Lerp::Catmull, Some("step") => Lerp::Step, Some(_) => Lerp::Linear, None => if e == "catmullrom" { Lerp::Catmull } else if e == "step" { Lerp::Step } else { Lerp::Linear } };
            keys.push(Key { t: t.trim().parse().unwrap_or(0.), pre, post, lerp, ease: Ease::of(&o["easing"], &o["easingArgs"]) });
        }
        keys.sort_by(|a, b| a.t.partial_cmp(&b.t).unwrap_or(std::cmp::Ordering::Equal));
        let timed = keys.iter().any(|k| k.pre.iter().chain(k.post.iter()).any(|m| m.timed));
        let plain = keys.iter().all(|k| k.lerp == Lerp::Linear && k.ease.is_none() && !k.pre.iter().chain(k.post.iter()).any(|m| m.timed));
        let end = keys.last().map_or(0., |k| k.t);
        Ok(Channel { cst: [Molang::num(0.), Molang::num(0.), Molang::num(0.)], keys, timed, end, plain })
    }

    fn at(&self, t: f64) -> [f64; 3] {
        let val = |s: &[Molang; 3]| [s[0].eval(t), s[1].eval(t), s[2].eval(t)];
        if self.keys.is_empty() { return val(&self.cst); }
        let k = &self.keys;
        if t <= k[0].t { return val(&k[0].pre); }
        let last = k.last().unwrap();
        if t >= last.t { return val(&last.post); }
        let mut i = 0;
        while k[i + 1].t <= t { i += 1; }
        let (a, b) = (&k[i], &k[i + 1]);
        let mut f = (t - a.t) / (b.t - a.t).max(1e-6);
        if a.lerp == Lerp::Step || b.lerp == Lerp::Step { return val(&a.post); }
        if let Some(e) = b.ease { f = e.apply(f); }
        let (va, vb) = (val(&a.post), val(&b.pre));
        if a.lerp == Lerp::Catmull || b.lerp == Lerp::Catmull {
            let p0 = if i > 0 { val(&k[i - 1].post) } else { va };
            let p3 = if i + 2 < k.len() { val(&k[i + 2].pre) } else { vb };
            let (t2, t3) = (f * f, f * f * f);
            return std::array::from_fn(|c| 0.5 * (2. * va[c] + (-p0[c] + vb[c]) * f + (2. * p0[c] - 5. * va[c] + 4. * vb[c] - p3[c]) * t2 + (-p0[c] + 3. * va[c] - 3. * vb[c] + p3[c]) * t3));
        }
        std::array::from_fn(|c| va[c] + (vb[c] - va[c]) * f)
    }
}

/// the animation a worn cosmetic plays: `want` (name or suffix), else idle, walk, move, loop, default, then the first that isn't for menus
fn pick_animation(anims: &Map<String, Value>, want: Option<&str>) -> Option<String> {
    let names: Vec<&String> = anims.keys().collect();
    if names.is_empty() { return None; }
    let suf = |n: &str| n.rsplit('.').next().unwrap_or(n).to_lowercase();
    if let Some(w) = want.map(str::to_lowercase) {
        if let Some(f) = names.iter().find(|n| n.to_lowercase() == w || suf(n) == w) { return Some((*f).clone()); }
    }
    for s in ["idle", "walk", "move", "loop", "default"] { if let Some(f) = names.iter().find(|n| suf(n) == s) { return Some((*f).clone()); } }
    let menu = |n: &str| { let l = n.to_lowercase(); l.contains("gui") || l.contains("menu") || l.contains("inventory") };
    Some(names.iter().find(|n| !menu(n)).unwrap_or(&names[0]).to_string())
}

/// Bedrock / GeckoLib animation -> our looping keyframe groups ("nether.anim"), baked to linear keys where needed
fn bake_animation(an: &Value, bones: &HashSet<String>, warn: &mut Vec<String>) -> Result<(f64, Vec<Value>)> {
    let mut chans: Vec<(String, &str, Channel)> = vec![];
    if let Some(bs) = an["bones"].as_object() {
        for (bone, ch) in bs {
            if !bones.contains(bone) { warn.push(format!("animation moves \"{bone}\", which the model doesn't have: skipped")); continue; }
            for k in ["rotation", "position", "scale"] {
                if !ch[k].is_null() { chans.push((bone.clone(), k, Channel::of(&ch[k], warn)?)); }
            }
        }
    }
    if chans.is_empty() { return Ok((0., vec![])); }
    let key_end = chans.iter().map(|c| c.2.end).fold(0., f64::max);
    let timed = chans.iter().any(|c| c.2.timed);
    let mut l = f(&an["animation_length"]);
    let looping = an["loop"].as_bool() != Some(false);
    if l == 0. && timed {
        // Molang over time with no length: the smallest period that repeats every channel
        let eval_all = |t: f64| -> Vec<f64> { chans.iter().flat_map(|c| c.2.at(if key_end > 0. && looping { t % key_end.max(1e-6) } else { t })).collect() };
        let step = if key_end > 0.05 { key_end } else { 0.05 };
        let mut t_ = if key_end > 0. { key_end } else { 0.05 };
        while t_ <= 30. + 1e-9 && l == 0. {
            let ok = (0..40).all(|j| {
                let t = j as f64 / 40. * t_ * 1.37;
                let (a, b) = (eval_all(t), eval_all(t + t_));
                a.iter().zip(&b).all(|(x, y)| (x - y).abs() <= 0.02 + 1e-3 * x.abs())
            });
            if ok { l = r4(t_); }
            t_ += step;
        }
        if l == 0. { l = key_end.max(4.); warn.push(format!("animation never repeats exactly: it loops every {l}s")); }
    }
    if l == 0. { l = if key_end > 0. { key_end } else { 1. }; }
    let n = ((l * 20.).ceil() as usize).clamp(8, 240);
    let wrap = |t: f64| if key_end > 0. && looping { t % l.max(1e-6) } else { t };
    let mut by: Vec<(String, Map<String, Value>)> = vec![];
    for (bone, k, c) in &chans {
        let conv = |v: [f64; 3]| match *k { "rotation" => [-v[0], -v[1], v[2]], "position" => [-v[0], v[1], v[2]], _ => v };
        let row = |t: f64, v: [f64; 3]| { let v = conv(v); json!([r4(t), r4(v[0]), r4(v[1]), r4(v[2])]) };
        let keys: Vec<Value> = if !c.timed && c.keys.is_empty() {
            vec![row(0., c.at(0.))]
        } else if c.plain && !c.timed {
            let mut out = vec![];
            for kf in &c.keys {
                let pre = [kf.pre[0].eval(kf.t), kf.pre[1].eval(kf.t), kf.pre[2].eval(kf.t)];
                let post = [kf.post[0].eval(kf.t), kf.post[1].eval(kf.t), kf.post[2].eval(kf.t)];
                out.push(row(kf.t, pre));
                if post != pre { out.push(row(kf.t + 1e-3, post)); }
            }
            out
        } else {
            (0..=n).map(|j| { let t = j as f64 / n as f64 * l; row(t, c.at(wrap(t))) }).collect()
        };
        let i = match by.iter().position(|(b, _)| b == bone) {
            Some(i) => i,
            None => { let mut m = Map::new(); m.insert("group".into(), json!(bone)); m.insert("length".into(), json!(r4(l))); by.push((bone.clone(), m)); by.len() - 1 }
        };
        by[i].1.insert(k.to_string(), Value::Array(keys.into_iter().take(256).collect()));
    }
    Ok((l, by.into_iter().map(|(_, m)| Value::Object(m)).collect()))
}

// ---------------------------------------------------------------- Bedrock / GeckoLib geometry

const ANCHORS: [&str; 6] = crate::cosmetics::ANCHORS;
/// the game's body part pivots in Bedrock player space (pixels)
fn pivot(a: &str) -> [f64; 3] {
    match a { "right_arm" => [-5., 22., 0.], "left_arm" => [5., 22., 0.], "right_leg" => [-1.9, 12., 0.], "left_leg" => [1.9, 12., 0.], _ => [0., 24., 0.] }
}
const DIRS: [&str; 6] = ["north", "south", "east", "west", "up", "down"];

/// a bone name -> ANCHORS entry: Lunar's anchor_<part>, GeckoLib armor bones (armorHead, ...)
fn anchor_name(bone: &str) -> Option<&'static str> {
    let l = bone.to_lowercase();
    let cut = ["anchor_", "anchor-", "anchor", "armor_", "armor-", "armor"].iter().find(|p| l.starts_with(**p) && l.len() > p.len())?.len();
    let raw = &bone[cut..];
    let mut s = String::new();
    let cs: Vec<char> = raw.chars().collect();
    for (i, c) in cs.iter().enumerate() {
        if i > 0 && c.is_ascii_uppercase() && cs[i - 1].is_ascii_lowercase() { s.push('_'); }
        s.push(c.to_ascii_lowercase());
    }
    let s: String = s.chars().filter(|c| c.is_ascii_lowercase() || *c == '_').collect();
    let s = if s == "chest" || s == "torso" { "body".to_string() } else { s };
    ANCHORS.iter().find(|a| **a == s).copied()
}

struct Geo { tex_w: f64, tex_h: f64, bones: Vec<Value> }

/// the first geometry of a Bedrock .geo.json (any format_version)
fn geometry_of(j: &Value) -> Option<Geo> {
    let g = match j["minecraft:geometry"].as_array() {
        Some(a) => a.first()?.clone(),
        None => j.as_object()?.iter().find(|(k, v)| k.starts_with("geometry.") && v.is_object())?.1.clone(),
    };
    let d = &g["description"];
    let num = |a: &Value, b: &Value| a.as_f64().or_else(|| b.as_f64()).filter(|v| *v > 0.).unwrap_or(64.);
    Some(Geo { tex_w: num(&d["texture_width"], &g["texturewidth"]), tex_h: num(&d["texture_height"], &g["textureheight"]), bones: g["bones"].as_array().cloned().unwrap_or_default() })
}

fn box_uv(u: f64, v: f64, dx: f64, dy: f64, dz: f64) -> HashMap<&'static str, [f64; 4]> {
    HashMap::from([
        ("east", [u, v + dz, u + dz, v + dz + dy]), ("north", [u + dz, v + dz, u + dz + dx, v + dz + dy]), ("west", [u + dz + dx, v + dz, u + 2. * dz + dx, v + dz + dy]),
        ("south", [u + 2. * dz + dx, v + dz, u + 2. * dz + 2. * dx, v + dz + dy]), ("up", [u + dz + dx, v + dz, u + dz, v]), ("down", [u + dz + 2. * dx, v, u + dz + dx, v + dz]),
    ])
}

struct Src { texes: Vec<Tex>, els: Vec<Value>, groups: Vec<Value>, anim: Vec<Value>, anchor: Option<&'static str> }
#[derive(Clone)]
struct Tex { img: Img, uv_w: f64, uv_h: f64 }

/// Bedrock geometry -> elements (uv in texture units, `tex` 0), groups, bone names and the anchors used
fn bedrock_model(geo: &Geo, anchor: &'static str, warn: &mut Vec<String>) -> (Vec<Value>, Vec<Value>, HashSet<String>, HashSet<&'static str>) {
    let bones: Vec<&Value> = geo.bones.iter().filter(|b| b["name"].is_string()).collect();
    let names: HashSet<String> = bones.iter().map(|b| b["name"].as_str().unwrap().to_string()).collect();
    let mut kids: HashMap<Option<String>, Vec<&Value>> = HashMap::new();
    for b in &bones {
        let p = b["parent"].as_str().filter(|p| names.contains(*p)).map(str::to_string);
        kids.entry(p).or_default().push(b);
    }
    let canon = |v: [f64; 3], p: [f64; 3]| [r4(8. - (v[0] - p[0])), r4(v[1] - p[1]), r4(8. + (v[2] - p[2]))];
    let rot = |r: &Value| { let a = arr3(r, 0.); [-a[0], -a[1], a[2]] };
    let mut els: Vec<Value> = vec![];
    let mut used = HashSet::new();

    fn cube(c: &Value, b: &Value, p: [f64; 3], canon: &dyn Fn([f64; 3], [f64; 3]) -> [f64; 3], rot: &dyn Fn(&Value) -> [f64; 3]) -> Option<Value> {
        let (o, s) = (arr3(&c["origin"], 0.), arr3(&c["size"], 0.));
        let inf = c["inflate"].as_f64().or_else(|| b["inflate"].as_f64()).unwrap_or(0.);
        let mirror = c["mirror"].as_bool().or_else(|| b["mirror"].as_bool()).unwrap_or(false);
        let a = canon([o[0] + s[0], o[1], o[2]], p);
        let z = canon([o[0], o[1] + s[1], o[2] + s[2]], p);
        let from: Vec<f64> = (0..3).map(|k| r4(a[k].min(z[k]) - inf)).collect();
        let to: Vec<f64> = (0..3).map(|k| r4(a[k].max(z[k]) + inf)).collect();
        let (dx, dy, dz) = (s[0].abs(), s[1].abs(), s[2].abs());
        let mut uvs: HashMap<&str, ([f64; 4], Option<f64>)> = HashMap::new();
        if let Some(uv) = c["uv"].as_array() {
            let mut bx = box_uv(uv.first().map_or(0., f), uv.get(1).map_or(0., f), dx, dy, dz);
            if mirror {
                let e = bx["east"];
                bx.insert("east", bx["west"]);
                bx.insert("west", e);
                for d in DIRS { let u = bx[d]; bx.insert(d, [u[2], u[1], u[0], u[3]]); }
            }
            for d in DIRS { uvs.insert(d, (bx[d], None)); }
        } else if c["uv"].is_object() {
            for d in DIRS {
                let fc = &c["uv"][d];
                let Some(u) = fc["uv"].as_array() else { continue };
                let sz = fc["uv_size"].as_array().map(|a| [a.first().map_or(0., f), a.get(1).map_or(0., f)]).unwrap_or(match d { "north" | "south" => [dx, dy], "east" | "west" => [dz, dy], _ => [dx, dz] });
                let (u0, v0) = (u.first().map_or(0., f), u.get(1).map_or(0., f));
                let mut q = [u0, v0, u0 + sz[0], v0 + sz[1]];
                if d == "up" || d == "down" { q = [q[2], q[3], q[0], q[1]]; }
                uvs.insert(d, (q, fc["uv_rotation"].as_f64()));
            }
        }
        let area = |d: &str| match d { "north" | "south" => dx * dy, "east" | "west" => dz * dy, _ => dx * dz };
        let mut faces = Map::new();
        for d in DIRS {
            let Some((uv, r)) = uvs.get(d) else { continue };
            if area(d) < 1e-6 && inf <= 0. { continue; }
            let mut fc = json!({ "uv": uv, "tex": 0 });
            if let Some(r) = r.filter(|r| *r != 0.) { fc["rotation"] = json!(r); }
            faces.insert(d.into(), fc);
        }
        if faces.is_empty() { return None; }
        let mut e = json!({ "from": from, "to": to, "faces": faces });
        let r = rot(&c["rotation"]);
        if nz(r) {
            let pv = if c["pivot"].is_array() { arr3(&c["pivot"], 0.) } else { [o[0] + s[0] / 2., o[1] + s[1] / 2., o[2] + s[2] / 2.] };
            e["rotation"] = json!({ "x": r[0], "y": r[1], "z": r[2], "origin": canon(pv, p) });
        }
        Some(e)
    }

    fn poly_mesh(pm: &Value, p: [f64; 3], canon: &dyn Fn([f64; 3], [f64; 3]) -> [f64; 3]) -> Option<Value> {
        let pos = pm["positions"].as_array().cloned().unwrap_or_default();
        let uvs = pm["uvs"].as_array().cloned().unwrap_or_default();
        let norm = pm["normalized_uvs"].as_bool().unwrap_or(false);
        let polys: Vec<Vec<[usize; 3]>> = match &pm["polys"] {
            Value::String(s) if s == "tri_list" || s == "quad_list" => {
                let n = if s == "tri_list" { 3 } else { 4 };
                (0..pos.len() / n).map(|i| (0..n).map(|k| [i * n + k; 3]).collect()).collect()
            }
            Value::Array(a) => a.iter().map(|poly| poly.as_array().map(|c| c.iter().map(|x| { let a = arr3(x, 0.); [a[0] as usize, a[1] as usize, a[2] as usize] }).collect()).unwrap_or_default()).collect(),
            _ => return None,
        };
        let (mut verts, mut faces, mut key): (Vec<Value>, Vec<Value>, HashMap<(usize, usize), usize>) = (vec![], vec![], HashMap::new());
        for poly in polys.iter().take(32768) {
            let mut ids = vec![];
            for [pi, _, ui] in poly {
                let id = *key.entry((*pi, *ui)).or_insert_with(|| {
                    let q = canon(arr3(pos.get(*pi).unwrap_or(&Value::Null), 0.), p);
                    let u = uvs.get(*ui).and_then(|v| v.as_array()).map(|a| [a.first().map_or(0., f), a.get(1).map_or(0., f)]).unwrap_or([0., 0.]);
                    verts.push(json!([q[0], q[1], q[2], u[0], if norm { 1. - u[1] } else { u[1] }]));
                    verts.len() - 1
                });
                ids.push(id);
            }
            ids.reverse(); // Bedrock space is mirrored: keep counter-clockwise from outside
            if ids.len() == 4 { faces.push(json!(ids)); } else { for j in 1..ids.len().saturating_sub(1) { faces.push(json!([ids[0], ids[j], ids[j + 1]])); } }
        }
        (!faces.is_empty()).then(|| json!({ "type": "mesh", "vertices": verts, "faces": faces, "tex": 0, "normalized": norm }))
    }

    #[allow(clippy::too_many_arguments)]
    fn bone(b: &Value, inherited: &'static str, kids: &HashMap<Option<String>, Vec<&Value>>, els: &mut Vec<Value>, used: &mut HashSet<&'static str>,
            canon: &dyn Fn([f64; 3], [f64; 3]) -> [f64; 3], rot: &dyn Fn(&Value) -> [f64; 3], depth: u32) -> Value {
        let name = b["name"].as_str().unwrap_or("");
        let own = anchor_name(name);
        let a = own.unwrap_or(inherited);
        let p = pivot(a);
        used.insert(a);
        let mut g = json!({ "name": name, "origin": canon(arr3(&b["pivot"], 0.), p), "color": 0, "children": [] });
        if let Some(o) = own { g["anchor"] = json!(o); }
        let r = rot(&b["rotation"]);
        if nz(r) { g["rotation"] = json!(r); }
        let mut ch = vec![];
        for c in b["cubes"].as_array().into_iter().flatten() { if let Some(e) = cube(c, b, p, canon, rot) { ch.push(json!(els.len())); els.push(e); } }
        if b["poly_mesh"].is_object() { if let Some(e) = poly_mesh(&b["poly_mesh"], p, canon) { ch.push(json!(els.len())); els.push(e); } }
        if depth < 64 { for k in kids.get(&Some(name.to_string())).into_iter().flatten() { ch.push(bone(k, a, kids, els, used, canon, rot, depth + 1)); } }
        g["children"] = Value::Array(ch);
        g
    }

    let groups: Vec<Value> = kids.get(&None).into_iter().flatten().map(|b| {
        let mut g = bone(b, anchor, &kids, &mut els, &mut used, &canon, &rot, 0);
        if g["anchor"].is_null() { g["anchor"] = json!(anchor); }
        g
    }).collect();
    if bones.iter().any(|b| !b["locators"].is_null()) { warn.push("locators (particle / item points) are not used".into()); }
    (els, groups, names, used)
}

// ---------------------------------------------------------------- Wavefront OBJ

/// .obj (units = blocks, Y up, the player faces +Z) -> one mesh element, uv 0..1 per frame (v up). `cy`: height of the
/// .obj origin above the body part's pivot (pixels); `flip`: stored upside down with its back towards +X (Lunar
/// bandannas), turned (x, y, z) -> (-z, -y, -x).
fn obj_model(text: &str, warn: &mut Vec<String>, cy: f64, flip: bool) -> Result<Value> {
    let (mut v, mut vt, mut verts, mut faces): (Vec<[f64; 3]>, Vec<[f64; 2]>, Vec<Value>, Vec<Value>) = (vec![], vec![], vec![], vec![]);
    let mut key: HashMap<(usize, i64), usize> = HashMap::new();
    let mut skipped = 0;
    let idx = |s: &str, n: usize| -> i64 { let i: i64 = s.parse().unwrap_or(0); if i < 0 { n as i64 + i } else { i - 1 } };
    for line in text.lines() {
        let p: Vec<&str> = line.split_whitespace().collect();
        let n = |i: usize| p.get(i).and_then(|x| x.parse::<f64>().ok()).unwrap_or(0.);
        match p.first().copied() {
            Some("v") => v.push(if flip { [-n(3), -n(2), -n(1)] } else { [n(1), n(2), n(3)] }),
            Some("vt") => vt.push([n(1), n(2)]),
            Some("f") => {
                let mut ids = vec![];
                for c in &p[1..] {
                    let mut it = c.split('/');
                    let vi = idx(it.next().unwrap_or(""), v.len());
                    let ti = it.next().filter(|s| !s.is_empty()).map_or(-1, |s| idx(s, vt.len()));
                    if vi < 0 || vi as usize >= v.len() { skipped += 1; continue; }
                    let id = *key.entry((vi as usize, ti)).or_insert_with(|| {
                        let q = v[vi as usize];
                        let u = if ti >= 0 { vt.get(ti as usize).copied().unwrap_or([0., 0.]) } else { [0., 0.] };
                        verts.push(json!([r4(8. - q[0] * 16.), r4(q[1] * 16. + cy), r4(8. - q[2] * 16.), u[0], 1. - u[1]]));
                        verts.len() - 1
                    });
                    ids.push(id);
                }
                if ids.len() < 3 { continue; }
                if ids.len() == 4 { faces.push(json!(ids)); } else { for j in 1..ids.len() - 1 { faces.push(json!([ids[0], ids[j], ids[j + 1]])); } }
                if faces.len() > 32768 { bail!("the .obj has too many faces (32768 at most)"); }
            }
            _ => {}
        }
    }
    if skipped > 0 { warn.push(format!("{skipped} face corners point at missing vertices: skipped")); }
    if faces.is_empty() { bail!("the .obj has no faces"); }
    if vt.is_empty() { warn.push("the .obj has no texture coordinates".into()); }
    Ok(json!({ "type": "mesh", "vertices": verts, "faces": faces, "tex": 0, "normalized": true }))
}

// ---------------------------------------------------------------- Lunar .gek.json

/// gek "attached_bone" -> ANCHORS entry
fn gek_anchor(bone: &Value) -> Option<&'static str> {
    let b = bone.as_str()?.to_lowercase();
    if b.is_empty() { return None; }
    let has = |a: &str, z: &str| b.find(a).is_some_and(|i| b[i + a.len()..].trim_start_matches(|c: char| !c.is_ascii_alphanumeric()).starts_with(z));
    Some(if b.contains("head") || b.contains("hat") || b.contains("face") { "head" }
        else if has("right", "arm") { "right_arm" } else if has("left", "arm") { "left_arm" }
        else if has("right", "leg") { "right_leg" } else if has("left", "leg") { "left_leg" } else { "body" })
}

/// gek state machine -> the animation it plays normally
fn gek_animation(gek: &Value) -> Option<String> {
    let states: Vec<&Value> = gek["state_machine"]["controllers"].as_array().into_iter().flatten()
        .flat_map(|c| c["states"].as_array().into_iter().flatten())
        .filter(|s| s["anim"].is_string() && { let t = format!("{}{}", s["plays_when"].as_str().unwrap_or(""), s["anim"].as_str().unwrap_or("")).to_lowercase(); !(t.contains("gui") || t.contains("menu") || t.contains("inventory")) })
        .collect();
    let when = |s: &Value| s["plays_when"].as_str().map(str::to_string).or_else(|| s["plays_when"].as_f64().map(|v| v.to_string())).unwrap_or_default();
    let always = states.iter().find(|s| { let w = when(s).trim().to_lowercase(); w.is_empty() || w == "true" || w == "1" || (w.starts_with("1.") && w[2..].chars().all(|c| c == '0')) });
    let calm = || states.iter().find(|s| { let t = format!("{}{}", when(s), s["anim"].as_str().unwrap_or("")).to_lowercase(); !["swim", "elytra", "fly", "sneak", "crouch", "sleep", "ride", "riding"].iter().any(|w| t.contains(w)) });
    always.or_else(calm).or(states.first()).and_then(|s| s["anim"].as_str()).map(str::to_string)
}

// ---------------------------------------------------------------- the importer

struct Ctx<'a> { files: &'a Files, json: HashMap<String, Value>, images: Vec<String>, warn: Vec<String> }

impl Ctx<'_> {
    fn find(&self, r: &str) -> Option<String> {
        let b = base(r);
        let s = stem(r);
        self.files.names.iter().find(|n| base(n) == b).or_else(|| self.files.names.iter().find(|n| stem(n) == s)).cloned()
    }
    fn image(&self, n: &str) -> Result<Img> { decode_image(&base(n), &self.files.data[n]) }

    /// the texture for a model file: named by `r`, else sharing the model's name, else the only (or largest) image
    fn texture_for(&mut self, model: &str, r: Option<&str>) -> Option<String> {
        if let Some(r) = r.filter(|r| !r.is_empty()) {
            if let Some(f) = self.find(r).filter(|f| is_image(f)) { return Some(f); }
            self.warn.push(format!("texture \"{r}\" not found among the files"));
        }
        if self.images.is_empty() { return None; }
        let st = base(model).trim_end_matches(".geo.json").trim_end_matches(".json").trim_end_matches(".obj").trim_end_matches(".bbmodel").to_string();
        if let Some(own) = self.images.iter().find(|n| stem(n) == st) { return Some(own.clone()); }
        if self.images.len() > 1 {
            let pick = self.images.iter().max_by_key(|n| self.files.data[n.as_str()].len()).cloned()?;
            self.warn.push(format!("several textures; {} is used (name the texture like the model to pick another)", base(&pick)));
            return Some(pick);
        }
        Some(self.images[0].clone())
    }

    /// Blockbench .bbmodel / Java Block/Item .json
    fn blockbench(&mut self, file: &str) -> Result<Src> {
        let src = self.json[file].clone();
        let bb = file.to_lowercase().ends_with(".bbmodel") || !src["meta"].is_null();
        let mut texes = vec![];
        if bb {
            for t in src["textures"].as_array().into_iter().flatten() {
                let s = t["source"].as_str().unwrap_or("");
                let d = if let Some(data) = ["data:image/png;base64,", "data:image/webp;base64,", "data:image/gif;base64,"].iter().find_map(|p| s.strip_prefix(*p)) {
                    use base64::Engine;
                    decode_image(t["name"].as_str().unwrap_or("texture"), &base64::engine::general_purpose::STANDARD.decode(data.trim()).map_err(|_| anyhow!("a texture in the .bbmodel is damaged"))?)?
                } else {
                    let want = t["name"].as_str().or(t["path"].as_str()).unwrap_or("");
                    let f = self.find(want).filter(|f| is_image(f)).ok_or_else(|| anyhow!("texture \"{want}\" isn't embedded: save the project in Blockbench with its textures embedded"))?;
                    self.image(&f)?
                };
                let ms = d.ms.or_else(|| t["frame_time"].as_f64().filter(|v| *v > 0.).map(|v| (v * 50.) as u32));
                let num = |a: &Value, b: &Value| a.as_f64().or_else(|| b.as_f64()).filter(|v| *v > 0.).unwrap_or(16.);
                texes.push(Tex { img: Img { ms, ..d }, uv_w: num(&t["uv_width"], &src["resolution"]["width"]), uv_h: num(&t["uv_height"], &src["resolution"]["height"]) });
            }
        } else {
            for (k, path) in src["textures"].as_object().into_iter().flatten() {
                if k == "particle" { continue; }
                let p = path.as_str().unwrap_or("");
                let named = self.find(&format!("{}.png", base(p))).or_else(|| self.find(&base(p))).filter(|f| is_image(f));
                // our own (and many exported) models call it just "texture": then the model's own or the only image
                let f = match named { Some(f) => f, None => self.texture_for(file, None).ok_or_else(|| anyhow!("texture \"{p}\": add {}.png to the files", base(p)))? };
                texes.push(Tex { img: self.image(&f)?, uv_w: 16., uv_h: 16. });
            }
        }
        let tex_ids: Vec<String> = src["textures"].as_array().map(|a| a.iter().map(|t| t["uuid"].as_str().unwrap_or("").to_string()).collect()).unwrap_or_default();
        let tex_index = |v: &Value| -> Option<usize> { if let Some(n) = v.as_u64() { Some(n as usize) } else { v.as_str().and_then(|u| tex_ids.iter().position(|x| x == u)) } };
        let mut els: Vec<Value> = vec![];
        let mut by_uuid: HashMap<String, usize> = HashMap::new();
        let dirs2 = ["north", "east", "south", "west", "up", "down"];
        if bb {
            for e in src["elements"].as_array().into_iter().flatten() {
                let name = e["name"].as_str().unwrap_or("");
                if e["visibility"].as_bool() == Some(false) || name.starts_with("_ref") { continue; }
                if e["type"].as_str() == Some("mesh") {
                    // Blockbench mesh: vertices {key: [x,y,z]}, faces {key: {vertices: [keys], uv: {key: [u,v]}, texture}}
                    let o = arr3(&e["origin"], 0.);
                    let (mut verts, mut faces, mut tex): (Vec<[f64; 5]>, Vec<Vec<usize>>, usize) = (vec![], vec![], 0);
                    for fc in e["faces"].as_object().into_iter().flatten().map(|(_, v)| v) {
                        let Some(ti) = tex_index(&fc["texture"]) else { continue };
                        tex = ti;
                        let ids: Vec<usize> = fc["vertices"].as_array().into_iter().flatten().filter_map(|k| {
                            let k = k.as_str()?;
                            let p = arr3(&e["vertices"][k], 0.);
                            let uv = fc["uv"][k].as_array().map(|a| [a.first().map_or(0., f), a.get(1).map_or(0., f)]).unwrap_or([0., 0.]);
                            verts.push([r4(p[0] + o[0]), r4(p[1] + o[1]), r4(p[2] + o[2]), uv[0], uv[1]]);
                            Some(verts.len() - 1)
                        }).collect();
                        if ids.len() == 3 { faces.push(ids); } else if ids.len() == 4 {
                            // Blockbench keeps quad corners unordered; its normal comes from the first three: sort them counter-clockwise around it
                            let p: Vec<[f64; 3]> = ids.iter().map(|i| [verts[*i][0], verts[*i][1], verts[*i][2]]).collect();
                            let s = |a: [f64; 3], b: [f64; 3]| [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
                            let x = |a: [f64; 3], b: [f64; 3]| [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
                            let dot = |a: [f64; 3], b: [f64; 3]| a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
                            let n = x(s(p[1], p[0]), s(p[2], p[0]));
                            let c = [0, 1, 2].map(|k| (p[0][k] + p[1][k] + p[2][k] + p[3][k]) / 4.);
                            let ax = s(p[0], c);
                            let ay = x(n, ax);
                            let mut o: Vec<(usize, f64)> = ids.iter().enumerate().map(|(k, i)| (*i, dot(s(p[k], c), ay).atan2(dot(s(p[k], c), ax)))).collect();
                            o.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal));
                            faces.push(o.into_iter().map(|a| a.0).collect());
                        }
                    }
                    if !verts.is_empty() && !faces.is_empty() {
                        if nz(arr3(&e["rotation"], 0.)) { self.warn.push(format!("mesh \"{name}\": its own rotation is not applied (apply it in Blockbench)")); }
                        if let Some(u) = e["uuid"].as_str() { by_uuid.insert(u.into(), els.len()); }
                        let mut m = json!({ "type": "mesh", "vertices": verts, "faces": faces, "tex": tex });
                        if f(&e["light_emission"]) > 0. { m["light_emission"] = e["light_emission"].clone(); }
                        els.push(m);
                    }
                    continue;
                }
                if let Some(t) = e["type"].as_str().filter(|t| *t != "cube") { self.warn.push(format!("\"{name}\" is a {t}: skipped")); continue; }
                let g = f(&e["inflate"]);
                let (a, b) = (arr3(&e["from"], 0.), arr3(&e["to"], 0.));
                let from = a.map(|v| v - g);
                let to = b.map(|v| v + g);
                let boxed = src["meta"]["box_uv"].as_bool() == Some(true) || e["box_uv"].as_bool() == Some(true);
                let bx = boxed.then(|| { let uo = arr3(&e["uv_offset"], 0.); box_uv(uo[0], uo[1], b[0] - a[0], b[1] - a[1], b[2] - a[2]) });
                let mut faces = Map::new();
                for d in dirs2 {
                    let fc = &e["faces"][d];
                    if !fc.is_object() || fc["enabled"].as_bool() == Some(false) { continue; }
                    let Some(ti) = tex_index(&fc["texture"]).filter(|t| *t < texes.len()) else { continue };
                    let given = fc["uv"].as_array().map(|u| [0, 1, 2, 3].map(|i| u.get(i).map_or(0., f)));
                    let uv = match (given, &bx) { (Some(u), Some(_)) if u.iter().all(|x| *x == 0.) => bx.as_ref().map(|b| b[d]), (Some(u), _) => Some(u), (None, Some(b)) => Some(b[d]), _ => None };
                    let Some(uv) = uv else { continue };
                    let mut o = json!({ "uv": uv, "tex": ti });
                    if f(&fc["rotation"]) != 0. { o["rotation"] = fc["rotation"].clone(); }
                    faces.insert(d.into(), o);
                }
                if faces.is_empty() { continue; }
                let mut out = json!({ "name": name, "from": from, "to": to, "faces": faces });
                let r = arr3(&e["rotation"], 0.);
                if nz(r) { out["rotation"] = json!({ "x": r[0], "y": r[1], "z": r[2], "origin": arr3(&e["origin"], 8.) }); }
                if f(&e["light_emission"]) > 0. { out["light_emission"] = e["light_emission"].clone(); }
                if let Some(u) = e["uuid"].as_str() { by_uuid.insert(u.into(), els.len()); }
                els.push(out);
            }
        } else {
            let keys: Vec<String> = src["textures"].as_object().map(|o| o.keys().filter(|k| *k != "particle").cloned().collect()).unwrap_or_default();
            for e in src["elements"].as_array().into_iter().flatten() {
                if e["name"].as_str().unwrap_or("").starts_with("_ref") { continue; }
                let mut faces = Map::new();
                for d in DIRS {
                    let fc = &e["faces"][d];
                    let Some(t) = fc["texture"].as_str() else { continue };
                    let ti = keys.iter().position(|k| *k == t.trim_start_matches('#')).unwrap_or(0);
                    let uv = if fc["uv"].is_array() { fc["uv"].clone() } else { json!([0, 0, 16, 16]) };
                    let mut o = json!({ "uv": uv, "tex": ti });
                    if f(&fc["rotation"]) != 0. { o["rotation"] = fc["rotation"].clone(); }
                    faces.insert(d.into(), o);
                }
                if faces.is_empty() { continue; }
                let mut out = json!({ "name": e["name"], "from": arr3(&e["from"], 0.), "to": arr3(&e["to"], 0.), "faces": faces });
                if f(&e["rotation"]["angle"]) != 0. {
                    let mut r = e["rotation"].clone();
                    r["origin"] = json!(arr3(&e["rotation"]["origin"], 8.));
                    out["rotation"] = r;
                }
                if f(&e["light_emission"]) > 0. { out["light_emission"] = e["light_emission"].clone(); }
                els.push(out);
            }
        }
        let mut bone_by_uuid: HashMap<String, String> = HashMap::new();
        fn group(g: &Value, by_uuid: &HashMap<String, usize>, bones: &mut HashMap<String, String>, depth: u32) -> Option<Value> {
            if let Some(u) = g.as_str() { return by_uuid.get(u).map(|i| json!(i)); }
            if let Some(n) = g.as_u64() { return Some(json!(n)); }
            let name = g["name"].as_str().unwrap_or("");
            if !g.is_object() || name.starts_with("_ref") || depth > 64 { return None; }
            let mut node = json!({ "name": name, "origin": arr3(&g["origin"], 8.), "color": 0 });
            let r = arr3(&g["rotation"], 0.);
            if nz(r) { node["rotation"] = json!(r); }
            if let Some(u) = g["uuid"].as_str() { bones.insert(u.into(), name.into()); }
            node["children"] = Value::Array(g["children"].as_array().into_iter().flatten().filter_map(|c| group(c, by_uuid, bones, depth + 1)).collect());
            Some(node)
        }
        let roots = if bb { &src["outliner"] } else { &src["groups"] };
        let groups: Vec<Value> = roots.as_array().into_iter().flatten().filter_map(|g| group(g, &by_uuid, &mut bone_by_uuid, 0)).filter(|g| g.is_object()).collect();
        let mut anim = vec![];
        if let Some(list) = src["animations"].as_array().filter(|a| bb && !a.is_empty()) {
            let a = list.iter().find(|x| x["loop"].as_str() == Some("loop")).unwrap_or(&list[0]);
            let mut smooth = false;
            for (uuid, an) in a["animators"].as_object().into_iter().flatten() {
                let Some(bone) = bone_by_uuid.get(uuid).cloned().or_else(|| an["name"].as_str().map(str::to_string)) else { continue };
                if an["type"].as_str() == Some("effect") { continue; }
                let kf = an["keyframes"].as_array().cloned().unwrap_or_default();
                smooth |= kf.iter().any(|k| k["interpolation"].as_str().is_some_and(|i| i != "linear"));
                let ch = |c: &str| -> Vec<Value> {
                    let mut v: Vec<[f64; 4]> = kf.iter().filter(|k| k["channel"].as_str() == Some(c)).map(|k| { let p = &k["data_points"][0]; [r4(f(&k["time"])), f(&p["x"]), f(&p["y"]), f(&p["z"])] }).collect();
                    v.sort_by(|x, y| x[0].partial_cmp(&y[0]).unwrap_or(std::cmp::Ordering::Equal));
                    v.into_iter().take(256).map(|k| json!(k)).collect()
                };
                let (rot, pos, scl) = (ch("rotation"), ch("position"), ch("scale"));
                if rot.is_empty() && pos.is_empty() && scl.is_empty() { continue; }
                let mut o = json!({ "group": bone, "length": f(&a["length"]).max(0.05) });
                if !rot.is_empty() { o["rotation"] = json!(rot); }
                if !pos.is_empty() { o["position"] = json!(pos); }
                if !scl.is_empty() { o["scale"] = json!(scl); }
                anim.push(o);
            }
            if smooth { self.warn.push("smooth / step keyframes are played linear".into()); }
        }
        Ok(Src { texes, els, groups, anim, anchor: None })
    }

    /// Bedrock / GeckoLib (+ gek descriptor)
    fn bedrock(&mut self, file: &str, gek: Option<&Value>, def: &'static str) -> Result<Src> {
        let geo = geometry_of(&self.json[file]).ok_or_else(|| anyhow!("{}: no geometry in it", base(file)))?;
        let anchor = gek.and_then(|g| gek_anchor(&g["attached_bone"])).unwrap_or(def);
        let mut warn = std::mem::take(&mut self.warn);
        let (els, groups, bones, _) = bedrock_model(&geo, anchor, &mut warn);
        self.warn = warn;
        let tf = self.texture_for(file, gek.and_then(|g| g["texture"].as_str())).ok_or_else(|| anyhow!("no texture (PNG, WebP or GIF) among the files"))?;
        let texes = vec![Tex { img: self.image(&tf)?, uv_w: geo.tex_w, uv_h: geo.tex_h }];
        let anim_files: Vec<String> = match gek.and_then(|g| g["animation"].as_str()) {
            Some(a) => self.find(a).into_iter().collect(),
            None => self.files.names.iter().filter(|n| self.json.get(*n).is_some_and(|j| j["animations"].is_object() && geometry_of(j).is_none())).cloned().collect(),
        };
        let mut anims = Map::new();
        for fl in anim_files { if let Some(o) = self.json.get(&fl).and_then(|j| j["animations"].as_object()) { anims.extend(o.clone()); } }
        let mut anim = vec![];
        if let Some(pick) = pick_animation(&anims, gek.and_then(gek_animation).as_deref()) {
            let mut warn = std::mem::take(&mut self.warn);
            anim = bake_animation(&anims[&pick], &bones, &mut warn)?.1;
            self.warn = warn;
        }
        Ok(Src { texes, els, groups, anim, anchor: Some(anchor) })
    }

    /// Wavefront .obj
    fn obj(&mut self, file: &str, what: What, def: &'static str) -> Result<Src> {
        // Lunar's conventions: masks over the mouth, bandannas (headband) on the forehead with their ties down the back
        let (cy, flip) = match what { What::Item(Kind::Mask) => (1.5, false), What::Item(Kind::Headband) => (6., true), _ => (if def == "head" { 4. } else { -6. }, false) };
        let text = String::from_utf8_lossy(&self.files.data[file]).to_string();
        let mut warn = std::mem::take(&mut self.warn);
        let e = obj_model(&text, &mut warn, cy, flip)?;
        self.warn = warn;
        let tf = self.texture_for(file, None).ok_or_else(|| anyhow!("no texture (PNG, WebP or GIF) among the files"))?;
        let mut d = self.image(&tf)?;
        // uv 0..1 covers one frame: square frames when the texture is a vertical strip
        if d.frames <= 1 && d.img.height() > d.img.width() && d.img.height() % d.img.width() == 0 { d.frames = d.img.height() / d.img.width(); }
        Ok(Src { texes: vec![Tex { img: d, uv_w: 1., uv_h: 1. }], els: vec![e], groups: vec![], anim: vec![], anchor: None })
    }
}

/// Guess what the files are from their names (and a gek's type / attached bone): the upload dialog's starting pick.
/// None = no clue (the wardrobe page the upload started from decides).
pub fn guess(files: &Files) -> Option<What> {
    let names: Vec<String> = files.names.iter().map(|n| base(n)).collect();
    let model = names.iter().any(|n| n.ends_with(".json") || n.ends_with(".bbmodel") || n.ends_with(".obj"));
    if !model { return Some(What::Cape); }
    let all = format!("{} {}", files.label.to_lowercase(), names.join(" "));
    let has = |ws: &[&str]| ws.iter().any(|w| all.contains(w));
    // wings first: Lunar files some wings as shoulder "pets"
    if has(&["wing"]) { return Some(What::Item(Kind::Wings)); }
    // a Lunar descriptor says what it is
    for (n, b) in &files.data {
        if !base(n).ends_with(".gek.json") { continue; }
        let Ok(g) = serde_json::from_slice::<Value>(strip_bom(b)) else { continue };
        let t = g["type"].as_str().unwrap_or("").to_lowercase();
        let k = match t.as_str() {
            "hat" | "hats" | "head" | "helmet" => Some(Kind::Hat), "bandanna" | "bandana" | "headband" => Some(Kind::Headband), "mask" | "masks" => Some(Kind::Mask),
            "back" | "backpack" | "backbling" | "cloak" => Some(Kind::Back), "wings" | "wing" => Some(Kind::Wings), "aura" | "auras" => Some(Kind::Aura), "pet" | "pets" => Some(Kind::Pet), _ => None,
        };
        if let Some(k) = k { return Some(What::Item(k)); }
    }
    if has(&["aura", "particle", "halo_aura"]) { return Some(What::Item(Kind::Aura)); }
    if has(&["mask", "goggle", "glasses", "visor"]) { return Some(What::Item(Kind::Mask)); }
    if has(&["bandana", "bandanna", "headband", "_band", "band_"]) { return Some(What::Item(Kind::Headband)); }
    if has(&["pet", "companion", "buddy", "dragon"]) { return Some(What::Item(Kind::Pet)); }
    if has(&["back", "bag", "sword", "quiver", "tail", "jetpack", "cloak", "cape", "katana", "scythe"]) { return Some(What::Item(Kind::Back)); }
    if has(&["hat", "crown", "cap", "helmet", "horn", "ears", "halo", "head"]) { return Some(What::Item(Kind::Hat)); }
    for (n, b) in &files.data {
        if base(n).ends_with(".gek.json") {
            if let Ok(g) = serde_json::from_slice::<Value>(strip_bom(b)) {
                return Some(match gek_anchor(&g["attached_bone"]) { Some("head") | None => What::Item(Kind::Hat), _ => What::Item(Kind::Back) });
            }
        }
    }
    None
}

fn strip_bom(b: &[u8]) -> &[u8] { b.strip_prefix(&[0xef, 0xbb, 0xbf]).unwrap_or(b) }

/// A name for the upload from the files (the model's, else the image's), tidied: "black_dragon.geo.json" -> "Black Dragon".
fn nice_name(files: &Files) -> String {
    let pick = files.names.iter().find(|n| { let b = base(n); b.ends_with(".gek.json") || b.ends_with(".geo.json") || b.ends_with(".bbmodel") || b.ends_with(".obj") })
        .or_else(|| files.names.iter().find(|n| base(n).ends_with(".json")))
        .or_else(|| files.names.first());
    let s = if files.label.is_empty() { pick.map(|n| base(n)).unwrap_or_default() } else { files.label.to_lowercase() };
    let s = s.split('.').next().unwrap_or("").replace(['_', '-'], " ");
    let s: String = s.split_whitespace().map(|w| { let mut c = w.chars(); c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default() }).collect::<Vec<_>>().join(" ");
    s.chars().take(32).collect()
}

/// Convert source files into an upload of type `what`.
pub fn import(files: &Files, what: What) -> Result<Imported> {
    let mut cx = Ctx { files, json: HashMap::new(), images: vec![], warn: vec![] };
    for n in &files.names {
        let b = base(n);
        if b.ends_with(".json") || b.ends_with(".bbmodel") {
            match serde_json::from_slice::<Value>(strip_bom(&files.data[n])) { Ok(v) => { cx.json.insert(n.clone(), v); } Err(e) => cx.warn.push(format!("{b}: not valid JSON ({e})")) }
        }
        if is_image(n) { cx.images.push(n.clone()); }
    }
    let name = nice_name(files);
    let kind = match what { What::Cape => return cape(cx, name), What::Item(k) => k };
    let def: &'static str = if matches!(kind, Kind::Hat | Kind::Headband | Kind::Mask) { "head" } else { "body" };
    let gek_file = files.names.iter().find(|n| base(n).ends_with(".gek.json")).cloned();
    let gek = gek_file.as_ref().and_then(|g| cx.json.get(g)).cloned();
    let geo = match gek.as_ref().and_then(|g| g["model"].as_str()) {
        Some(m) => { let f = cx.find(m); if f.as_ref().is_none_or(|f| !cx.json.contains_key(f)) { bail!("the .gek.json names {m}, which isn't among the files"); } f }
        None => files.names.iter().find(|n| cx.json.get(*n).is_some_and(|j| geometry_of(j).is_some())).cloned(),
    };
    let bbm = files.names.iter().find(|n| base(n).ends_with(".bbmodel") && cx.json.contains_key(*n)).cloned();
    let objf = files.names.iter().find(|n| base(n).ends_with(".obj")).cloned();
    let java = files.names.iter().find(|n| base(n).ends_with(".json") && cx.json.get(*n).is_some_and(|j| j["elements"].is_array())).cloned();
    let src = if let Some(g) = geo { cx.bedrock(&g, gek.as_ref(), def)? }
        else if let Some(b) = bbm { cx.blockbench(&b)? }
        else if let Some(o) = objf { cx.obj(&o, what, def)? }
        else if let Some(j) = java { cx.blockbench(&j)? }
        else if kind == Kind::Aura {
            let em = files.names.iter().find(|n| base(n).ends_with(".json") && cx.json.get(*n).is_some_and(|j| j.is_object())).cloned();
            let (Some(em), 1) = (em, cx.images.len()) else { bail!("an aura is a model, or a particle emitter .json + its sprite sheet image") };
            let d = cx.image(&cx.images[0].clone())?;
            crate::cosmetics::Aura::parse(&cx.json[&em])?;
            if d.img.width() > 4096 || d.img.height() > 8192 { bail!("sprite sheet: up to 4096 x 8192"); }
            let model = serde_json::to_vec(&cx.json[&em])?;
            return finish(cx.warn, what, name, model, &d.img, None, format!("particle aura ({}x{} sprite sheet)", d.img.width(), d.img.height()));
        } else { bail!("no model found: add a .geo.json (+ .animation.json / .gek.json), .bbmodel, .obj or Java .json and its texture") };
    if cx.images.iter().any(|n| { let b = base(n); b.contains("_glowmask.") || b.contains("_emissive.") || b.contains("_glow.") }) {
        cx.warn.push("glow mask textures are not supported (light_emission on Blockbench cubes is)".into());
    }

    // pack the textures into one atlas (frames of the first when there is only one)
    if src.texes.is_empty() { bail!("the model has no texture"); }
    let fh_of = |t: &Tex| if t.img.frames > 1 { t.img.img.height() / t.img.frames } else { let w = t.img.img.width() as f64; t.img.img.height().min(((w * t.uv_h / t.uv_w).round() as u32).max(1)) };
    let (atlas, aw, frame_h, place): (RgbaImage, u32, u32, Vec<[u32; 4]>) = if src.texes.len() == 1 {
        let t = &src.texes[0];
        let fh = fh_of(t);
        (t.img.img.clone(), t.img.img.width(), fh, vec![[0, 0, t.img.img.width(), fh]])
    } else {
        let aw = src.texes.iter().map(|t| t.img.img.width()).max().unwrap_or(1);
        let mut place = vec![];
        let mut y = 0;
        for t in &src.texes {
            let fh = fh_of(t);
            if t.img.img.height() > fh { cx.warn.push("several textures: only the first frame of animated ones is used".into()); }
            place.push([0, y, t.img.img.width(), fh]);
            y += fh;
        }
        let mut atlas = RgbaImage::new(aw, y.max(1));
        for (t, p) in src.texes.iter().zip(&place) { image::imageops::overlay(&mut atlas, &image::imageops::crop_imm(&t.img.img, 0, 0, p[2], p[3]).to_image(), 0, p[1] as i64); }
        (atlas, aw, y.max(1), place)
    };
    let map = |ti: usize, u: f64, v: f64, unit: bool| -> [f64; 2] {
        let (t, p) = (src.texes.get(ti).unwrap_or(&src.texes[0]), place.get(ti).unwrap_or(&place[0]));
        let (sx, sy) = (p[2] as f64 / if unit { 1. } else { t.uv_w }, p[3] as f64 / if unit { 1. } else { t.uv_h });
        [r4((p[0] as f64 + u * sx) / aw as f64 * 16.), r4((p[1] as f64 + v * sy) / frame_h as f64 * 16.)]
    };
    let els: Vec<Value> = src.els.iter().map(|e| {
        if e["type"].as_str() == Some("mesh") {
            let unit = e["normalized"].as_bool() == Some(true);
            let ti = e["tex"].as_u64().unwrap_or(0) as usize;
            let verts: Vec<Value> = e["vertices"].as_array().into_iter().flatten().map(|v| { let uv = map(ti, f(&v[3]), f(&v[4]), unit); json!([v[0], v[1], v[2], uv[0], uv[1]]) }).collect();
            let mut m = json!({ "type": "mesh", "vertices": verts, "faces": e["faces"] });
            if !e["light_emission"].is_null() { m["light_emission"] = e["light_emission"].clone(); }
            return m;
        }
        let mut faces = Map::new();
        for (d, fc) in e["faces"].as_object().into_iter().flatten() {
            let ti = fc["tex"].as_u64().unwrap_or(0) as usize;
            let u = &fc["uv"];
            let (a, b) = (map(ti, f(&u[0]), f(&u[1]), false), map(ti, f(&u[2]), f(&u[3]), false));
            let mut o = json!({ "uv": [a[0], a[1], b[0], b[1]], "texture": "#0" });
            if !fc["rotation"].is_null() { o["rotation"] = fc["rotation"].clone(); }
            faces.insert(d.clone(), o);
        }
        let mut out = e.clone();
        out["faces"] = Value::Object(faces);
        if let Some(o) = out.as_object_mut() { o.remove("tex"); o.remove("normalized"); }
        out
    }).collect();
    if els.is_empty() { bail!("nothing to import (only reference cubes?)"); }
    let mut model = json!({ "credit": "Nether Client", "texture_size": [aw, frame_h], "textures": { "0": "texture", "particle": "texture" }, "elements": els });
    if !src.groups.is_empty() { model["groups"] = json!(src.groups); }
    let mut nether = json!({ "anchor": src.anchor.unwrap_or(def) });
    if !src.anim.is_empty() { nether["anim"] = json!(src.anim); }
    model["nether"] = nether;
    let frames = atlas.height() / frame_h.max(1);
    let ms = (src.texes.len() == 1 && frames > 1).then(|| src.texes[0].img.ms).flatten();
    let quads: usize = model["elements"].as_array().unwrap().iter().map(|e| if e["type"].as_str() == Some("mesh") { e["faces"].as_array().map_or(0, Vec::len) } else { e["faces"].as_object().map_or(0, Map::len) }).sum();
    let n = model["elements"].as_array().unwrap().len();
    let summary = format!("{n} element{} ({quads} faces), {aw}x{} texture{}", if n == 1 { "" } else { "s" }, atlas.height(), if frames > 1 { format!(", {frames} frames") } else { String::new() });
    finish(cx.warn, what, name, serde_json::to_vec(&model)?, &atlas, ms, summary)
}

fn finish(mut warn: Vec<String>, what: What, name: String, model: Vec<u8>, img: &RgbaImage, ms: Option<u32>, summary: String) -> Result<Imported> {
    if model.len() > MAX_MODEL { bail!("the model is {} KB (256 KB at most): simplify it or use a shorter animation", model.len() / 1024); }
    if img.width() > 4096 || img.height() > 8192 { bail!("the texture is {}x{} (4096 x 8192 at most)", img.width(), img.height()); }
    let png = png_bytes(img)?;
    if png.len() > MAX_PNG { bail!("the texture is {:.1} MB as a PNG (1 MB at most): use fewer frames or a smaller texture", png.len() as f64 / 1048576.); }
    // the launcher and the game must be able to read it
    let kind = match what { What::Item(k) => k, What::Cape => unreachable!() };
    crate::cosmetics::Loaded::from_bytes("upload", kind, &model, &png, ms.unwrap_or(100)).map_err(|e| anyhow!("the converted model doesn't load: {e}"))?;
    warn.dedup();
    Ok(Imported { what, model: Some(model), png, frame_ms: ms, name, warnings: warn, summary })
}

/// a cape: one image, frames of 64:32 (cape + elytra) or 22:17 (cape only), animated or a vertical strip
fn cape(cx: Ctx, name: String) -> Result<Imported> {
    if cx.files.names.iter().any(|n| { let b = base(n); b.ends_with(".json") || b.ends_with(".bbmodel") || b.ends_with(".obj") }) {
        bail!("these files hold a model, not a cape: pick what it is (hat, mask, wings...)");
    }
    if cx.images.len() != 1 { bail!("a cape is one image (PNG, WebP or GIF)"); }
    let d = cx.image(&cx.images[0])?;
    let (mut img, mut ms) = (d.img, d.ms);
    let w = img.width();
    let fh = if d.frames > 1 { img.height() / d.frames } else { img.height() };
    let Some((bw, bh)) = [(64u32, 32u32), (22, 17)].into_iter().find(|(bw, bh)| w % bw == 0 && w > 0 && fh % ((w / bw) * bh) == 0)
        else { bail!("{w}x{fh} isn't a cape: frames of 64:32 (cape + elytra) or 22:17 (cape only), stacked vertically when animated") };
    let (s, frame_h) = (w / bw, (w / bw) * bh);
    let mut n = img.height() / frame_h;
    let max_n = (2048 / (s * s)).clamp(1, 128);
    let mut warn = cx.warn;
    if n > max_n {
        let k = n.div_ceil(max_n);
        let keep = n.div_ceil(k);
        warn.push(format!("{n} frames at {s}x scale is over the cape limit: every {k}th frame is kept ({keep})"));
        let mut out = RgbaImage::new(w, keep * frame_h);
        for fr in 0..keep { image::imageops::overlay(&mut out, &image::imageops::crop_imm(&img, 0, fr * k * frame_h, w, frame_h).to_image(), 0, (fr * frame_h) as i64); }
        img = out;
        n = keep;
        ms = Some(ms.unwrap_or(100) * k);
    }
    if crate::backend::CapeLayout::of(img.width(), img.height()).is_none() { bail!("{}x{}: not a valid cape layout", img.width(), img.height()); }
    let png = png_bytes(&img)?;
    if png.len() > MAX_PNG { bail!("the cape is {:.1} MB as a PNG (1 MB at most): use fewer frames or a smaller scale", png.len() as f64 / 1048576.); }
    let summary = format!("{}x{} cape{}", img.width(), img.height(), if n > 1 { format!(", {n} frames") } else { String::new() });
    Ok(Imported { what: What::Cape, model: None, png, frame_ms: if n > 1 { ms.or(Some(100)) } else { None }, name, warnings: warn, summary })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png(w: u32, h: u32) -> Vec<u8> { png_bytes(&RgbaImage::from_pixel(w, h, image::Rgba([200, 30, 30, 255]))).unwrap() }

    #[test]
    fn molang_subset() {
        let mut w = vec![];
        let m = |s: &str, w: &mut Vec<String>| Molang::parse(s, w).unwrap();
        assert_eq!(m("1 + 2 * 3", &mut w).eval(0.), 7.);
        assert_eq!(m("-2 * 3", &mut w).eval(0.), -6.);
        assert!((m("math.sin(q.anim_time * 90)", &mut w).eval(1.) - 1.).abs() < 1e-9);
        assert!(m("math.sin(q.anim_time * 90)", &mut w).timed);
        assert_eq!(m("v.x = 2; v.y = v.x * 3; return v.y + 1;", &mut w).eval(0.), 7.);
        assert_eq!(m("q.is_on_ground ? 5 : 9", &mut w).eval(0.), 5.);
        assert_eq!(m("math.clamp(12, 0, 10)", &mut w).eval(0.), 10.);
        assert_eq!(m("query.unknown_thing", &mut w).eval(0.), 0.);
        assert!(!w.is_empty());
        assert!(Molang::parse("1 +", &mut w).is_err());
    }

    #[test]
    fn easing_ends() {
        for n in ["easeInSine", "easeOutQuad", "easeInOutCubic", "easeInBack", "easeOutElastic", "easeInOutBounce", "easeOutExpo", "easeInCirc"] {
            let e = Ease::of(&json!(n), &Value::Null).unwrap();
            assert!(e.apply(0.).abs() < 1e-6 && (e.apply(1.) - 1.).abs() < 1e-6, "{n}");
        }
        assert!(Ease::of(&json!("linear"), &Value::Null).is_none());
    }

    #[test]
    fn anchors() {
        assert_eq!(anchor_name("anchor_right_arm"), Some("right_arm"));
        assert_eq!(anchor_name("armorHead"), Some("head"));
        assert_eq!(anchor_name("armorChest"), Some("body"));
        assert_eq!(anchor_name("wing_left"), None);
        assert_eq!(gek_anchor(&json!("bipedLeftArm")), Some("left_arm"));
        assert_eq!(gek_anchor(&json!("HEAD")), Some("head"));
    }

    #[test]
    fn bedrock_with_animation() {
        let geo = json!({ "format_version": "1.12.0", "minecraft:geometry": [{ "description": { "identifier": "geometry.t", "texture_width": 32, "texture_height": 32 },
            "bones": [{ "name": "root", "pivot": [0, 24, 0] }, { "name": "spin", "parent": "root", "pivot": [0, 32, 0], "cubes": [{ "origin": [-2, 32, -2], "size": [4, 4, 4], "uv": [0, 0] }] }] }] });
        let anim = json!({ "animations": { "animation.t.idle": { "loop": true, "animation_length": 2, "bones": { "spin": { "rotation": { "0": [0, 0, 0], "2": [0, 360, 0] } } } } } });
        let files = Files::from_bytes(vec![("thing.geo.json".into(), geo.to_string().into_bytes()), ("thing.animation.json".into(), anim.to_string().into_bytes()), ("thing.png".into(), png(32, 32))]).unwrap();
        let out = import(&files, What::Item(Kind::Hat)).unwrap();
        let m: Value = serde_json::from_slice(out.model.as_ref().unwrap()).unwrap();
        assert_eq!(m["nether"]["anchor"], "head");
        assert_eq!(m["nether"]["anim"][0]["group"], "spin");
        assert_eq!(m["elements"].as_array().unwrap().len(), 1);
        // Bedrock (−2..2, 32..36) around the neck pivot (0,24,0) -> ours (6..10, 8..12)
        assert_eq!(m["elements"][0]["from"], json!([6.0, 8.0, 6.0]));
        assert_eq!(out.name, "Thing");
    }

    #[test]
    fn obj_and_cape_and_guess() {
        let obj = "v -0.25 0 -0.25\nv 0.25 0 -0.25\nv 0.25 0.5 -0.25\nv -0.25 0.5 -0.25\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nf 1/1 2/2 3/3 4/4\n";
        let files = Files::from_bytes(vec![("cool_mask.obj".into(), obj.into()), ("cool_mask.png".into(), png(16, 16))]).unwrap();
        assert_eq!(guess(&files), Some(What::Item(Kind::Mask)));
        assert!(import(&files, What::Item(Kind::Mask)).is_ok());
        let c = Files::from_bytes(vec![("my cape.png".into(), png(64, 32 * 3))]).unwrap();
        assert_eq!(guess(&c), Some(What::Cape));
        let out = import(&c, What::Cape).unwrap();
        assert!(out.model.is_none() && out.frame_ms == Some(100));
        assert!(import(&Files::from_bytes(vec![("x.png".into(), png(50, 30))]).unwrap(), What::Cape).is_err());
    }

    #[test]
    fn catalog_sources_reimport() {
        // the signed catalog's own models are valid Java Block/Item models: re-importing one must load
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../backend/content/cosmetics");
        let (Ok(js), Ok(p)) = (std::fs::read(dir.join("top_hat.json")), std::fs::read(dir.join("top_hat.png"))) else { return };
        let files = Files::from_bytes(vec![("top_hat.json".into(), js), ("top_hat.png".into(), p)]).unwrap();
        let out = import(&files, What::Item(Kind::Hat)).unwrap();
        assert!(out.model.is_some() && !out.png.is_empty());
    }
}

/// `cargo test -p nether-core --lib cosimport::raw -- --ignored --nocapture`: import every source in cosmetics_raw/
/// (folder = type) and print what came out (compare with the catalog the Node importer made from the same files).
#[cfg(test)]
#[test]
#[ignore]
fn raw() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../cosmetics_raw");
    let Ok(dirs) = std::fs::read_dir(&root) else { return };
    for d in dirs.flatten() {
        let what = match d.file_name().to_string_lossy().as_ref() {
            "auras" => What::Item(Kind::Aura), "backbling" => What::Item(Kind::Back), "bandannas" => What::Item(Kind::Headband), "capes" => What::Cape,
            "hats" => What::Item(Kind::Hat), "masks" => What::Item(Kind::Mask), "pets" => What::Item(Kind::Pet), "wings" => What::Item(Kind::Wings), _ => continue,
        };
        for f in std::fs::read_dir(d.path()).unwrap().flatten() {
            let files = match Files::from_paths(&[f.path()]) { Ok(x) => x, Err(e) => { println!("{:?}: read: {e}", f.file_name()); continue } };
            let g = guess(&files);
            match import(&files, what) {
                Ok(o) => {
                    let m: Value = o.model.as_ref().map(|m| serde_json::from_slice(m).unwrap()).unwrap_or(Value::Null);
                    println!("{:<40} {:<9} guess {:<9} {} | {} | anchor {} anim {} ms {:?} png {} KB{}", f.file_name().to_string_lossy(), what.as_str(), g.map_or("?", What::as_str), o.name, o.summary,
                        m["nether"]["anchor"], m["nether"]["anim"].as_array().map_or(0, Vec::len), o.frame_ms, o.png.len() / 1024,
                        if o.warnings.is_empty() { String::new() } else { format!(" | warn: {}", o.warnings.join("; ")) });
                }
                Err(e) => println!("{:<40} {:<9} FAILED: {e}", f.file_name().to_string_lossy(), what.as_str()),
            }
        }
    }
}
