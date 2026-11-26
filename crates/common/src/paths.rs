use std::path::PathBuf;

/// All launcher data lives under %APPDATA%\NetherClient (or the platform equivalent).
#[derive(Clone, Debug)]
pub struct Paths {
    pub root: PathBuf,
}

impl Paths {
    pub fn new() -> Self {
        let root = std::env::var_os("NETHER_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| dirs::data_dir().unwrap_or_else(|| PathBuf::from(".")).join("NetherClient"));
        Paths { root }
    }
    pub fn libraries(&self) -> PathBuf { self.root.join("libraries") }
    pub fn assets(&self) -> PathBuf { self.root.join("assets") }
    pub fn versions(&self) -> PathBuf { self.root.join("versions") }
    pub fn runtimes(&self) -> PathBuf { self.root.join("runtimes") }
    pub fn instances(&self) -> PathBuf { self.root.join("instances") }
    pub fn config(&self) -> PathBuf { self.root.join("config.json") }
    /// downloaded, verified launcher updates (setup exes)
    pub fn updates(&self) -> PathBuf { self.root.join("updates") }
    /// An instance's folder. ASCII names map as before ("My Pack" -> `my-pack`); names with other characters
    /// get a short stable hash so "Лес" and "Мир" don't share one folder. Never the instances root itself.
    pub fn instance(&self, name: &str) -> PathBuf {
        let mut slug = ascii_slug(name);
        if !name.is_ascii() { slug = format!("{slug}-{:08x}", fnv(name)); }
        if slug.trim_matches('-').is_empty() && name.is_ascii() { slug = format!("instance-{:08x}", fnv(name)); }
        self.instances().join(slug)
    }
    /// The folder an instance used before non-ASCII names got their own (`instances::open` migrates).
    pub fn legacy_instance(&self, name: &str) -> PathBuf { self.instances().join(ascii_slug(name)) }
}

fn ascii_slug(name: &str) -> String {
    name.chars().map(|c| if c.is_ascii_alphanumeric() { c.to_ascii_lowercase() } else { '-' }).collect()
}

/// FNV-1a: tiny, and stable across Rust versions (unlike `DefaultHasher`).
fn fnv(s: &str) -> u32 {
    s.bytes().fold(0x811c_9dc5u32, |h, b| (h ^ b as u32).wrapping_mul(0x0100_0193))
}

impl Default for Paths {
    fn default() -> Self { Self::new() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn instance_folders() {
        let p = Paths { root: PathBuf::from("r") };
        assert_eq!(p.instance("My Pack"), PathBuf::from("r/instances/my-pack"));
        assert_ne!(p.instance("Лес"), p.instance("Мир"));
        assert_eq!(p.instance("Лес"), p.instance("Лес"));
        assert_ne!(p.instance(""), p.instances());
        assert_ne!(p.instance("!!"), p.instance("??"));
    }
}
