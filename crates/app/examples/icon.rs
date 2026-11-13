//! Regenerate the app icon (ico + png) from the approved logo: `cargo run -p nether-app --example icon`
use image::codecs::ico::{IcoEncoder, IcoFrame};

fn main() {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
    let svg = std::fs::read(root.join("../../design/icon/nether-logo.svg")).expect("logo svg");
    let tree = resvg::usvg::Tree::from_data(&svg, &Default::default()).expect("parse svg");
    let png = |n: u32| {
        let mut pm = resvg::tiny_skia::Pixmap::new(n, n).unwrap();
        let s = n as f32 / tree.size().width();
        resvg::render(&tree, resvg::tiny_skia::Transform::from_scale(s, s), &mut pm.as_mut());
        pm.encode_png().unwrap()
    };
    let assets = root.join("ui/assets");
    std::fs::write(assets.join("nether-icon.png"), png(512)).unwrap();
    let frames: Vec<IcoFrame> = [256u32, 128, 64, 48, 40, 32, 24, 20, 16].iter()
        .map(|&n| IcoFrame::with_encoded(png(n), n, n, image::ExtendedColorType::Rgba8).unwrap()).collect();
    IcoEncoder::new(std::fs::File::create(assets.join("nether-icon.ico")).unwrap()).encode_images(&frames).unwrap();
    println!("wrote {}", assets.display());
}
