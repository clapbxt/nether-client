#![deny(clippy::correctness, clippy::suspicious)]
//! Shared vocabulary of the launcher crates, depended on by all of them and depending on none:
//! where data lives ([`paths`]), small JSON files written safely ([`store`]), deleting to the Recycle Bin ([`trash`]),
//! the two types every long job passes around ([`Progress`], [`Session`]), and [`platform`], the one place
//! for code that differs by operating system.
pub mod paths;
pub mod platform;
pub mod store;
pub mod trash;

use std::sync::Arc;

/// Progress callback: (message, fraction 0..=1).
pub type Progress = Arc<dyn Fn(&str, f32) + Send + Sync>;

/// The account a game is launched with.
#[derive(Clone, Debug)]
pub struct Session {
    pub name: String,
    pub uuid: String,
    pub access_token: String,
    /// "msa" for Microsoft accounts, "legacy" for offline.
    pub user_type: String,
}

impl Session {
    /// Offline session (single-player and offline-mode servers only).
    pub fn offline(name: &str) -> Self {
        let mut d = md5::compute(format!("OfflinePlayer:{name}")).0;
        d[6] = (d[6] & 0x0f) | 0x30;
        d[8] = (d[8] & 0x3f) | 0x80;
        Session { name: name.into(), uuid: hex::encode(d), access_token: "0".into(), user_type: "legacy".into() }
    }
}
