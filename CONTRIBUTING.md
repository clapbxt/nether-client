# Contributing

Thanks for helping improve Nether Client. Bug reports, fixes and well-scoped improvements are welcome.

## Before you start

- Read the [License](LICENSE). You may fork this repository only to prepare contributions to it. Forks may not be
  published as separate builds or projects.
- By opening a pull request you agree to section 3 of the License: you keep the copyright in your contribution and
  grant the maintainer a license to use and relicense it.
- For anything larger than a bug fix, open an issue or ask on [Discord](https://discord.gg/R5vxAxVjwu) first, so work
  is not duplicated or declined late.

## Reporting bugs

Open an [issue](https://github.com/clapbxt/nether-client/issues) with:

- what happened and what you expected;
- steps to reproduce;
- **Settings → Support → Copy system info** from the launcher (user folder and tokens are removed);
- the log or crash report, if any (the crash page can share it through mclo.gs).

Report security problems privately, as described in [SECURITY.md](SECURITY.md).

## Pull requests

1. Build and run the project as described in [Building from source](docs/develop/building.md).
2. Keep each pull request to one change. No unrelated refactors or formatting churn.
3. Follow the [project rules](README.md#project-rules). Changes that patch renderer classes, automate input, send
   packets, or read data the server did not send will not be accepted.
4. Run `cargo test -p nether-common -p nether-net -p nether-auth -p nether-game -p nether-social -p nether-core` and
   `cargo clippy --workspace --release`. Client changes go to [nether-agent](https://github.com/clapbxt/nether-agent) with its own checks.
   CI runs the same checks on your pull request.
5. For UI changes, attach before and after screenshots (the `--snapshot` switch in
   [dev tools](docs/develop/dev-tools.md#developer-switches) makes them).
6. If you add a network destination, list it in [PRIVACY.md](PRIVACY.md). If you add a module, follow
   [Adding an in-game module](docs/develop/modules.md), including its documentation rows.
7. If you change something a player sees, update the matching page in [`docs/guide`](docs/guide).
8. Add a line under **Unreleased** in [CHANGELOG.md](CHANGELOG.md), and write a commit message that says what
   changed and why. One commit per feature or fix; small related fixes can share one.

## Code style

- **Rust:** `rustfmt` defaults; `anyhow` for errors in core; no blocking work on the UI thread.
- **Slint:** use the tokens in `ui/theme.slint` (colours, radii, fonts) and the widgets in `ui/components.slint`;
  never hard-code the accent colour.
- **Java (agent):** Java 8 language level; game access only through `game.Game` / `game.Data` with null-safe
  fallbacks; drawing only through `ui.Gfx`; a feature goes in its own `features.*` package (see the package-info
  files).
- Comments explain why, not what.
