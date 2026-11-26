#![deny(clippy::correctness, clippy::suspicious)]
//! Network layer of the launcher: [`download`] (the shared HTTP client, fetches checked by SHA-1/SHA-256),
//! [`net`] (UPnP, LAN and public IP for hosting) and [`update`] (launcher auto-update, verified against the Ed25519
//! release keys in `res/`). Depends only on `nether-common`.
pub mod download;
pub mod net;
pub mod update;

// the modules were written as part of nether-core and still say crate::paths / crate::store
use nether_common::{paths, store};
