# Security policy

## Supported versions

Only the latest release receives fixes. Installed launchers update themselves.

## Reporting a vulnerability

Please do not open a public issue for security problems. Report them privately through
[GitHub's private vulnerability reporting](https://github.com/clapbxt/nether-client/security/advisories/new), or
message a maintainer on the [Discord](https://discord.gg/R5vxAxVjwu).

Include what is affected, how to reproduce it and the impact you expect. You will get a reply within a few days.
Please give us reasonable time to release a fix before disclosing the issue.

In scope: the launcher, the installer and update process, the in-game client, and Nether's online services. Out of
scope: Minecraft itself, third-party mods and services, and issues that need an already-compromised PC.

## How releases are protected

Updates are verified with SHA-256 and an Ed25519 signature before they install, and remote content is signed with a
separate key. Details are in the [README](README.md#release-and-update-signing).
