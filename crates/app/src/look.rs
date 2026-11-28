//! Themes (design v9, see the vault's Design.md): a theme is `{ name, dark, base, accent?, corners? }` and every
//! surface, line and text colour is generated from its base tint, so built-in and custom themes share one code path.
//! The launcher is the theme authority: it pushes the palette into the Slint `Theme` global and writes every theme it
//! knows, resolved, into the global client.json so the in-game client draws the same colours and offers the same list.
//! Custom themes are plain JSON files in `<data>/themes/*.json` (open formats).
use std::path::Path;

use serde::Deserialize;
use slint::{Color, ComponentHandle, ModelRc, VecModel};

use crate::{AppWindow, Backend, Theme, ThemeSpec};

/// Accent presets: base, bright, deep, text on dark, pressed, PLAY plate (same order as the Settings swatches).
pub const ACCENTS: [[u32; 6]; 5] = [
    [0xF76A05, 0xFF9A2E, 0x8F3A02, 0xFFAA66, 0xD65A04, 0x4A2002], // fire (the logo's orange; default)
    [0xE5372D, 0xFF6157, 0x9E1F18, 0xFF8A82, 0xC42A21, 0x4A1210], // racing red
    [0x22A845, 0x3DDC6A, 0x146B2B, 0x7BE89A, 0x1B8A39, 0x0B3816], // acid
    [0x1F6FEB, 0x4C8DFF, 0x123F86, 0x8AB4FF, 0x1A5DC7, 0x0A2148], // cobalt
    [0x8B3DFF, 0xA66BFF, 0x4F1F99, 0xC4A1FF, 0x7330D9, 0x26104A], // violet
];
pub const ACCENT_NAMES: [&str; 5] = ["Fire", "Racing red", "Acid", "Cobalt", "Violet"];

/// One theme. `accent` / `corners` set by a custom theme file override the user's own picks while it is active.
#[derive(Clone, Debug, Deserialize)]
pub struct Def {
    pub name: String,
    #[serde(default = "yes")]
    pub dark: bool,
    #[serde(deserialize_with = "hex")]
    pub base: u32,
    #[serde(default, deserialize_with = "hex_opt")]
    pub accent: Option<u32>,
    #[serde(default)]
    pub corners: Option<i32>,
}
fn yes() -> bool { true }
fn hex<'de, D: serde::Deserializer<'de>>(d: D) -> Result<u32, D::Error> {
    let s = String::deserialize(d)?;
    parse_hex(&s).ok_or_else(|| serde::de::Error::custom(format!("not a colour: {s}")))
}
fn hex_opt<'de, D: serde::Deserializer<'de>>(d: D) -> Result<Option<u32>, D::Error> {
    Ok(Option::<String>::deserialize(d)?.and_then(|s| parse_hex(&s)))
}

/// "#RRGGBB" (or "RRGGBB") to 0xRRGGBB.
pub fn parse_hex(s: &str) -> Option<u32> {
    let h = s.trim().trim_start_matches('#');
    (h.len() == 6).then(|| u32::from_str_radix(h, 16).ok()).flatten()
}

fn def(name: &str, dark: bool, base: u32) -> Def { Def { name: name.into(), dark, base, accent: None, corners: None } }

/// Built-in themes, in picker order. The first two are the v8 Nether Dark / Light (kept exact).
pub fn builtins() -> Vec<Def> {
    vec![
        def("Nether Dark", true, 0x0F0F11),
        def("Nether Light", false, 0xEEEEF1),
        def("Midnight", true, 0x0B0E14),
        def("OLED", true, 0x000000),
        def("Crimson", true, 0x140C0C),
        def("Warped", true, 0x0A1312),
        def("Paper", false, 0xF3EFE8),
    ]
}

/// Built-in themes plus valid files from `<root>/themes` (a file with a built-in's name is skipped).
pub fn all(root: &Path) -> Vec<Def> {
    let mut l = builtins();
    let Ok(rd) = std::fs::read_dir(root.join("themes")) else { return l };
    let mut files: Vec<_> = rd.flatten().map(|e| e.path()).filter(|p| p.extension().is_some_and(|x| x.eq_ignore_ascii_case("json"))).collect();
    files.sort();
    for p in files {
        match std::fs::read_to_string(&p).map_err(|e| e.to_string()).and_then(|s| serde_json::from_str::<Def>(&s).map_err(|e| e.to_string())) {
            Ok(d) if !d.name.trim().is_empty() && !l.iter().any(|b| b.name.eq_ignore_ascii_case(&d.name)) => l.push(d),
            Ok(_) => {}
            Err(e) => crate::applog::line(&format!("theme {}: {e}", p.display())),
        }
    }
    l
}

// ---------------------------------------------------------------- colour maths (0xAARRGGBB / 0xRRGGBB)

fn ch(c: u32, s: u32) -> f32 { ((c >> s) & 255) as f32 }
fn rgb(r: f32, g: f32, b: f32) -> u32 { ((r.round().clamp(0.0, 255.0) as u32) << 16) | ((g.round().clamp(0.0, 255.0) as u32) << 8) | b.round().clamp(0.0, 255.0) as u32 }
/// Toward white by t (0..1).
fn lighten(c: u32, t: f32) -> u32 { rgb(ch(c, 16) + (255.0 - ch(c, 16)) * t, ch(c, 8) + (255.0 - ch(c, 8)) * t, ch(c, 0) + (255.0 - ch(c, 0)) * t) }
/// Toward black by t (0..1).
fn darken(c: u32, t: f32) -> u32 { rgb(ch(c, 16) * (1.0 - t), ch(c, 8) * (1.0 - t), ch(c, 0) * (1.0 - t)) }
fn a(alpha: u32, c: u32) -> u32 { (alpha << 24) | (c & 0xFFFFFF) }

/// Resolved surfaces: bg, s1, s2, s3, hairline, hairline2, text, dim, mut, ink, ice, glass, glass hairline, hover,
/// backdrop top tint, panel sheen, card shadow (ARGB), and whether it is a light theme.
pub struct Pal { pub c: [u32; 17], pub light: bool }

pub fn palette(d: &Def) -> Pal {
    // v8 values, exactly
    if d.name == "Nether Dark" {
        return Pal { c: [0xFF0F0F11, 0xF218181B, 0xFF202024, 0xFF2A2A30, 0x10FFFFFF, 0x21FFFFFF, 0xFFF4F4F5, 0xFFA1A1AA, 0xFF71717A, 0xFF0F0F11, 0xFFF4F4F5, 0xC718181B, 0x17FFFFFF, 0x0BFFFFFF, 0xFF17171B, 0x08FFFFFF, 0x66000000], light: false };
    }
    if d.name == "Nether Light" {
        return Pal { c: [0xFFEEEEF1, 0xF7FFFFFF, 0xFFF5F5F7, 0xFFE6E6EB, 0x12000000, 0x24000000, 0xFF18181B, 0xFF52525B, 0xFF8A8A93, 0xFFFFFFFF, 0xFF18181B, 0xD9FFFFFF, 0x14000000, 0x0A000000, 0xFFF9F9FB, 0x80FFFFFF, 0x1F000000], light: true };
    }
    let b = d.base & 0xFFFFFF;
    if d.dark {
        // raised steps lighten the base by 3.7 / 7 / 11 %; text keeps a trace of the base's tint
        let (s1, s2, s3) = (lighten(b, 0.037), lighten(b, 0.07), lighten(b, 0.11));
        let tx = lighten(b, 0.955);
        Pal { c: [a(255, b), a(0xF2, s1), a(255, s2), a(255, s3), 0x10FFFFFF, 0x21FFFFFF, a(255, tx), a(255, lighten(b, 0.6)), a(255, lighten(b, 0.42)), a(255, b), a(255, tx), a(0xC7, s1), 0x17FFFFFF, 0x0BFFFFFF, a(255, lighten(b, 0.03)), 0x08FFFFFF, 0x66000000], light: false }
    } else {
        let tx = darken(b, 0.9);
        Pal { c: [a(255, b), a(0xF7, lighten(b, 1.0)), a(255, lighten(b, 0.5)), a(255, darken(b, 0.035)), 0x12000000, 0x24000000, a(255, tx), a(255, darken(b, 0.655)), a(255, darken(b, 0.42)), 0xFFFFFFFF, a(255, tx), a(0xD9, lighten(b, 1.0)), 0x14000000, 0x0A000000, a(255, lighten(b, 0.6)), 0x80FFFFFF, 0x1F000000], light: true }
    }
}

/// The six accent shades for a preset index, or generated from any colour.
pub fn accent(preset: i32, custom: Option<u32>) -> [u32; 6] {
    if let Some(c) = custom { return [c, lighten(c, 0.25), darken(c, 0.42), lighten(c, 0.4), darken(c, 0.13), darken(c, 0.7)]; }
    ACCENTS[preset.clamp(0, ACCENTS.len() as i32 - 1) as usize]
}

/// Radius for controls / panels / floating cards: Sharp, Default, Round.
pub fn radii(corners: i32) -> (f32, f32, f32) {
    match corners { 0 => (2.0, 3.0, 4.0), 2 => (8.0, 14.0, 20.0), _ => (5.0, 8.0, 12.0) }
}

fn argb(c: u32) -> Color { Color::from_argb_u8((c >> 24) as u8, (c >> 16) as u8, (c >> 8) as u8, c as u8) }
fn opaque(c: u32) -> Color { Color::from_rgb_u8((c >> 16) as u8, (c >> 8) as u8, c as u8) }

/// What the user picked (prefs): theme by name, accent preset or custom colour, corners, text size.
pub struct Choice<'a> { pub theme: &'a str, pub accent: i32, pub accent_hex: &'a str, pub corners: i32, pub text: i32 }

/// The theme a choice resolves to (unknown names fall back to Nether Dark) and its effective accent / corners.
pub fn resolve<'a>(list: &'a [Def], c: &Choice) -> (&'a Def, [u32; 6], i32) {
    let d = list.iter().find(|d| d.name.eq_ignore_ascii_case(c.theme)).unwrap_or(&list[0]);
    let acc = accent(c.accent, d.accent.or_else(|| parse_hex(c.accent_hex)));
    (d, acc, d.corners.unwrap_or(c.corners).clamp(0, 2))
}

const TEXT_SCALES: [f32; 3] = [1.0, 1.1, 1.2];

/// Push the whole look into the Slint globals: surfaces, accent, radii, text size, and the picker list.
pub fn apply(ui: &AppWindow, list: &[Def], c: &Choice) {
    let (d, acc, corners) = resolve(list, c);
    let p = palette(d);
    let t = ui.global::<Theme>();
    let k = p.c;
    t.set_bg(argb(k[0])); t.set_s1(argb(k[1])); t.set_s2(argb(k[2])); t.set_s3(argb(k[3])); t.set_ln(argb(k[4])); t.set_ln2(argb(k[5]));
    t.set_tx(argb(k[6])); t.set_dim(argb(k[7])); t.set_mut(argb(k[8])); t.set_ink(argb(k[9])); t.set_ice(argb(k[10]));
    t.set_glass(argb(k[11])); t.set_glass_ln(argb(k[12])); t.set_hov(argb(k[13])); t.set_bg_top(argb(k[14])); t.set_sheen(argb(k[15])); t.set_shade(argb(k[16]));
    t.set_aura(if p.light { 0.05 } else if d.base & 0xFFFFFF == 0 { 0.0 } else { 0.07 });
    let (r1, r2, r3) = radii(corners);
    t.set_r1(r1); t.set_r2(r2); t.set_r3(r3);
    t.set_square(false); t.set_light(p.light);
    t.set_disp("Barlow".into());
    t.set_red(opaque(acc[0])); t.set_red2(opaque(acc[1])); t.set_red3(opaque(acc[2])); t.set_redtx_d(opaque(acc[3])); t.set_press(opaque(acc[4])); t.set_plate(opaque(acc[5]));
    t.set_ts(TEXT_SCALES[c.text.clamp(0, TEXT_SCALES.len() as i32 - 1) as usize]);
    let b = ui.global::<Backend>();
    let (_, r, _) = radii(corners);
    b.set_themes(ModelRc::new(VecModel::from(list.iter().map(|d| {
        let p = palette(d);
        let (name, sub) = d.name.split_once(' ').filter(|(n, _)| *n == "Nether").map(|(n, s)| (n.to_string(), s.to_string())).unwrap_or((d.name.clone(), String::new()));
        ThemeSpec { name: name.into(), sub: sub.into(), bg: argb(p.c[0]), panel: argb(p.c[2] | 0xFF000000), line: argb(p.c[5]), text: argb(p.c[6]), r }
    }).collect::<Vec<_>>())));
    b.set_lp_theme(list.iter().position(|x| std::ptr::eq(x, d)).unwrap_or(0) as i32);
    b.set_accent_custom(opaque(acc[0]));
}

/// The in-game side of the look, written to the global client.json: the active theme's name, the accent pair, corners
/// and every theme resolved ("#bg #s1 #s2 #s3 #ln #ln2 #tx #dim #mut #ov dark|light").
pub fn game_look(list: &[Def], c: &Choice) -> serde_json::Value {
    let (d, acc, corners) = resolve(list, c);
    let mut themes = serde_json::Map::new();
    for t in list {
        let p = palette(t).c;
        let ov = if palette(t).light { 0xFF000000 } else { 0xFFFFFFFF };
        let cols = [p[0], p[1] | 0xFF000000, p[2], p[3], p[4], p[5], p[6], p[7], p[8], ov];
        let s: Vec<String> = cols.iter().map(|c| format!("#{c:08X}")).collect();
        themes.insert(t.name.clone(), format!("{} {}", s.join(" "), if palette(t).light { "light" } else { "dark" }).into());
    }
    serde_json::json!({
        "theme": d.name,
        "accent": format!("#{:06X}", acc[0]),
        "accent2": format!("#{:06X}", acc[1]),
        "corners": corners,
        "themes": themes,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_dark_matches_v8_steps() {
        // the generator lands within 10 levels per channel of the hand-tuned v8 Dark from the same base
        let p = palette(&def("x", true, 0x0F0F11)).c;
        let close = |a: u32, b: u32| (0..3).all(|s| (ch(a, s * 8) - ch(b, s * 8)).abs() <= 10.0);
        assert!(close(p[2], 0x202024) && close(p[3], 0x2A2A30) && close(p[6], 0xF4F4F5) && close(p[7], 0xA1A1AA));
    }

    #[test]
    fn custom_accent_and_files() {
        assert_eq!(parse_hex("#3fa7ff"), Some(0x3FA7FF));
        assert_eq!(parse_hex("nope"), None);
        let d: Def = serde_json::from_str(r##"{"name":"Mine","base":"#101418","accent":"#3FA7FF","corners":2}"##).unwrap();
        assert!(d.dark && d.base == 0x101418 && d.accent == Some(0x3FA7FF) && d.corners == Some(2));
        let list = vec![builtins().remove(0), d];
        let (r, acc, corners) = resolve(&list, &Choice { theme: "mine", accent: 0, accent_hex: "", corners: 1, text: 0 });
        assert!(r.name == "Mine" && acc[0] == 0x3FA7FF && corners == 2);
        let (r, _, _) = resolve(&list, &Choice { theme: "gone", accent: 0, accent_hex: "", corners: 1, text: 0 });
        assert_eq!(r.name, "Nether Dark");
    }

    #[test]
    fn game_look_lists_every_theme() {
        let list = builtins();
        let v = game_look(&list, &Choice { theme: "Paper", accent: 3, accent_hex: "", corners: 0, text: 0 });
        assert_eq!(v["theme"], "Paper");
        assert_eq!(v["accent"], "#1F6FEB");
        assert_eq!(v["themes"].as_object().unwrap().len(), list.len());
        assert!(v["themes"]["Paper"].as_str().unwrap().ends_with("light"));
        assert_eq!(v["themes"]["Nether Dark"].as_str().unwrap().split(' ').count(), 11);
    }
}
