#![deny(clippy::correctness, clippy::suspicious)]
//! Nether Client core: Mojang install/launch engine and Microsoft account auth.
pub mod agent;
pub mod art;
pub mod auth;
pub mod backend;
pub mod gamesettings;
pub mod download;
pub mod fabric;
pub mod forge;
pub mod friends;
pub mod instances;
pub mod modrinth;
pub mod net;
pub mod modpack;
pub mod mclogs;
pub mod netherpack;
pub mod news;
pub mod import;
pub mod worlds;
pub mod crash;
pub mod java;
pub mod launch;
pub mod mojang;
pub mod paths;
pub mod preflight;
pub mod restore;
pub mod server;
pub mod skins;
pub mod snapshot;
pub mod store;
pub mod trash;
pub mod tunnel;
pub mod update;

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
