#![deny(clippy::correctness, clippy::suspicious)]
//! Nether Client core: installing and launching the game, instances, content, social and the backend clients.
//!
//! Being split into focused crates (docs/develop/architecture.md). Modules that have moved are re-exported here
//! under their old names, so `nether_core::paths`, `nether_core::download` and so on keep working for the launcher:
//! - `nether-common`: paths, store, trash, Progress, Session
//! - `nether-net`: download, net, update
//! - `nether-auth`: auth
//! - `nether-game`: mojang, versions, java, fabric, forge, optifine, gamesettings, rpacks
//! - `nether-social`: p2p, party, party_net, cosmetics, preview, skins
pub mod agent;
pub mod art;
pub mod backend;
pub mod combine;
pub mod cosimport;
pub mod horizon;
pub mod friends;
pub mod instances;
pub mod modrinth;
pub mod modpack;
pub mod mclogs;
pub mod netherpack;
pub mod news;
pub mod import;
pub mod launchers;
pub mod worlds;
pub mod crash;
pub mod launch;
pub mod perf;
pub mod preflight;
pub mod restore;
pub mod share;
pub mod snapshot;
pub mod ugc;

pub use nether_auth as auth;
pub use nether_common::{paths, store, trash, Progress, Session};
pub use nether_net::{download, net, update};
pub use nether_game::{fabric, forge, gamesettings, java, mojang, optifine, rpacks, versions};
pub use nether_social::{cosmetics, p2p, party, party_net, preview, skins};
