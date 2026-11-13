//! `nether-media.exe`: now-playing bridge for the in-game client (agent `Media.java`).
//!
//! Reads the Windows system media controls (GlobalSystemMediaTransportControlsSessionManager, the same source as the
//! volume flyout: Spotify, browsers, Media Player, ...) and writes one JSON object per line to stdout when something
//! changes. Commands come in on stdin, one per line. No network, no files except album art, no registry, no children.
//!
//! stdout: `{"t":"hello","v":1}` first, then `{"t":"state",...}` (see [`state_json`]) or `{"t":"none"}`.
//! stdin:  `play` `pause` `toggle` `next` `prev` `seek <seconds>` `prefer <token,token|auto>` `quit`.
//! Args:   `--art-dir <dir>` (album art files `art-<hash>.<ext>`, default `%LOCALAPPDATA%\NetherClient\media`),
//!         `--parent <pid>` (exit when that process ends).
//! Exits when stdin closes (the game died), on `quit`, or when the parent process is gone.
//! Session choice + optimistic control idea adapted from Velo Client's SMTC helper (MIT, velo345/velo-client).
#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(windows)]
mod win;

#[cfg(windows)]
fn main() { win::run(); }

#[cfg(not(windows))]
fn main() {
    // the agent only starts this on Windows; a later MPRIS/macOS backend speaks the same protocol
    println!("{{\"t\":\"hello\",\"v\":1}}");
    println!("{{\"t\":\"unsupported\"}}");
}
