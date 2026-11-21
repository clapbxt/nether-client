// The launcher's screens live in crates/app/ui (next to the app that uses them). Slint turns them into ~30 MB of
// Rust; keeping that in its own crate means a change to the app's Rust code, or a release's version bump, does not
// compile it again.
fn main() {
    slint_build::compile("../app/ui/app.slint").unwrap();
}
