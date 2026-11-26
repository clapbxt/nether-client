#![deny(clippy::correctness, clippy::suspicious)]
//! Nether Client core: installing and launching the game, instances, content, social and the backend clients.
//!
//! Being split into focused crates (docs/develop/architecture.md). Modules that have moved are re-exported here
//! under their old names, so `nether_core::paths`, `nether_core::download` and so on keep working for the launcher:
//! - `nether-common`: paths, store, trash, Progress, Session
//! - `nether-net`: download, net, update
//! - `nether-auth`: auth
pub mod agent;
pub mod art;
pub mod backend;
pub mod combine;
pub mod cosimport;
pub mod cosmetics;
pub mod gamesettings;
pub mod horizon;
pub mod fabric;
pub mod forge;
pub mod friends;
pub mod instances;
pub mod modrinth;
pub mod modpack;
pub mod mclogs;
pub mod netherpack;
pub mod optifine;
pub mod news;
pub mod import;
pub mod launchers;
pub mod worlds;
pub mod crash;
pub mod java;
pub mod launch;
pub mod mojang;
pub mod perf;
pub mod preflight;
pub mod preview;
pub mod restore;
pub mod rpacks;
pub mod p2p;
pub mod party;
pub mod party_net;
pub mod share;
pub mod skins;
pub mod snapshot;
pub mod ugc;
pub mod versions;

pub use nether_auth as auth;
pub use nether_common::{paths, store, trash, Progress, Session};
pub use nether_net::{download, net, update};
