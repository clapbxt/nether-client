//! Renders every cosmetic in backend/content/cosmetics onto a skin, as a contact sheet:
//!   cargo run -p nether-core --release --example cosmetics -- <skin.png> <out.png> [seconds]
use nether_core::cosmetics::{Kind, Loaded};
use nether_core::preview::{render, Look, View};

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let skin = image::open(&a[1]).expect("skin").to_rgba8();
    let t: f64 = a.get(3).and_then(|s| s.parse().ok()).unwrap_or(1.3);
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../backend/content/cosmetics");
    let idx: Vec<serde_json::Value> = serde_json::from_slice(&std::fs::read(dir.join("index.json")).unwrap()).unwrap();
    let items: Vec<Loaded> = idx.iter().map(|c| {
        let id = c["id"].as_str().unwrap();
        let kind = Kind::parse(c["type"].as_str().unwrap()).unwrap();
        Loaded::from_bytes(id, kind, &std::fs::read(dir.join(format!("{id}.json"))).unwrap(), &std::fs::read(dir.join(format!("{id}.png"))).unwrap(), c["frametime"].as_u64().unwrap_or(100) as u32).unwrap()
    }).collect();
    let (cw, ch) = (300u32, 390u32);
    let cols = 5u32;
    let rows = (items.len() as u32 * 2 + cols - 1) / cols;
    let mut sheet = image::RgbaImage::from_pixel(cw * cols, ch * rows, image::Rgba([18, 18, 22, 255]));
    let mut n = 0u32;
    for it in &items {
        for (k, yaw) in [0.45f32, -2.4].iter().enumerate() {
            let mut slots: [Option<&Loaded>; 4] = [None; 4];
            slots[it.kind.slot()] = Some(it);
            let look = Look { skin: &skin, slim: false, cape: None, items: slots };
            let view = match (it.kind, k) { (Kind::Hat | Kind::Headband, 0) => View::head(*yaw), (Kind::Shield, 0) => View::arm(0.35), _ => View::full(*yaw) };
            let img = render(&look, view, cw, ch, t, (t * 1000.) as u64);
            image::imageops::overlay(&mut sheet, &img, ((n % cols) * cw) as i64, ((n / cols) * ch) as i64);
            n += 1;
        }
    }
    sheet.save(&a[2]).unwrap();
}
