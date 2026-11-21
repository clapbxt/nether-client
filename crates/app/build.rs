fn main() {
    // the screens compile in crates/ui; only the icon is embedded here
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let mut res = winresource::WindowsResource::new();
        res.set_icon("ui/assets/nether-icon.ico").set("ProductName", "Nether Client").set("FileDescription", "Nether Client");
        res.compile().expect("embed icon");
    }
}
