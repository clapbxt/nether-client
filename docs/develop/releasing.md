# Releases and signing

Releases are built and signed by the maintainer and published to this repository's
[Releases](https://github.com/clapbxt/nether-client/releases).

## Update signing

- Each installer has a detached **Ed25519** signature (`.sig`). The launcher accepts an update only if GitHub's
  SHA-256 matches and the signature verifies against `crates/net/res/release-ed25519.pub`.
- The update is applied by the installer (`--update`) when the launcher closes.
- Remote content (news, packs, capes, cosmetics) is signed separately and checked against
  `crates/core/res/content-ed25519.pub`.
- Private keys are never in this repository. `.gitignore` blocks `*.key` and `.nether-signing/`.
- The installer isn't Authenticode-signed yet, so Windows SmartScreen may warn on first run.

## Channels

Players get full releases by default. **Settings → Storage & About → Beta updates** opts into betas; a beta stays
installed until the next full release.

## Before tagging a release

1. Build in order: agent, launcher, installer (see [Building](building.md)).
2. `cargo test -p nether-core` and `cargo clippy --workspace --release` pass.
3. New network destinations are in [PRIVACY.md](../../PRIVACY.md); new modules are in the
   [module list](../guide/modules.md) and both SAFETY files.
