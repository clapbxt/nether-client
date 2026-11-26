#![deny(clippy::correctness, clippy::suspicious)]
//! The social pieces that don't need Nether's backend: parties ([`party`], [`party_net`]) linked peer-to-peer over
//! QUIC with iroh ([`p2p`]), cosmetic models and their previews ([`cosmetics`], [`preview`]), and skins ([`skins`]).
//! The backend clients (friends, world invites, capes, uploads) are still in nether-core and will join this crate
//! once they are untangled from it.
pub mod cosmetics;
pub mod p2p;
pub mod party;
pub mod party_net;
pub mod preview;
pub mod skins;

// the modules were written as part of nether-core and still say crate::paths, crate::net and so on
#[allow(unused_imports)]
use nether_common::paths;
#[allow(unused_imports)]
use nether_net::net;
