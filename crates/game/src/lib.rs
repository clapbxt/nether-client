#![deny(clippy::correctness, clippy::suspicious)]
//! Installing the game, everything up to (not including) starting it: Mojang's version manifests, libraries and
//! assets ([`mojang`]), the versions offered per loader ([`versions`]), Java runtimes ([`java`]), the Fabric family
//! ([`fabric`]), Forge and NeoForge ([`forge`]), OptiFine ([`optifine`]), the shared `options.txt`
//! ([`gamesettings`]) and resource packs carried into the game ([`rpacks`]). Starting the game (`launch`) is still in
//! nether-core, because it also needs the backend for capes.
pub mod fabric;
pub mod forge;
pub mod gamesettings;
pub mod java;
pub mod mojang;
pub mod optifine;
pub mod rpacks;
pub mod versions;

// the modules were written as part of nether-core and still say crate::paths, crate::download and so on
#[allow(unused_imports)]
use nether_common::{paths, store, Progress, Session};
#[allow(unused_imports)]
use nether_net::download;
