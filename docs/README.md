# Nether Client documentation

Everything about Nether Client in one place. Pick the part that fits you.

## Player guide

For anyone who wants to play. No coding needed.

| Page | What's in it |
|---|---|
| [Install and first launch](guide/install.md) | System requirements, installing, signing in, playing for the first time, updating, uninstalling |
| [The launcher](guide/launcher.md) | A tour of every page: Home, Play, Install Packs, Wardrobe, Friends, Settings, shortcuts |
| [Profiles](guide/profiles.md) | Ways to play (Nether, Fabric, Forge, OptiFine, Vanilla), mods, packs, worlds, backups, importing |
| [In game](guide/in-game.md) | The Right Shift menu, turning modules on, the HUD editor, keyboard controls |
| [Module list](guide/modules.md) | Every module, what it does, and whether servers may care |
| [Friends, parties and world invites](guide/friends.md) | Adding friends, the party lobby, voice, sharing your singleplayer world |
| [Skins, capes and cosmetics](guide/wardrobe.md) | The Wardrobe, uploading skins, capes, community uploads |
| [Troubleshooting and FAQ](guide/troubleshooting.md) | Crashes, logs, getting help, common questions |

Policies for players live at the top of the repository: [Fair play](../SAFETY.md), [Privacy](../PRIVACY.md),
[Terms](../TERMS.md).

## Building and contributing

Nether Client is source-available: you can read it, build it yourself, change it for your own use and send changes
back. These pages explain how.

| Page | What's in it |
|---|---|
| [Building from source](develop/building.md) | Tools to install, build order, running your own build |
| [How it fits together](develop/architecture.md) | The launcher, the core library, the in-game client and how they talk |
| [Repository layout](develop/layout.md) | Where everything lives |
| [Tweaking and dev tools](develop/dev-tools.md) | Config files, developer switches, scripted launches, screenshots, tests |
| [Adding an in-game module](develop/modules.md) | Step by step, plus the rules a module must follow |
| [Releases and signing](develop/releasing.md) | How releases and updates are signed and checked |
| [Contributing](../CONTRIBUTING.md) | Reporting bugs and sending pull requests |
| [Changelog](../CHANGELOG.md) | What changed in each release, and what is waiting for the next one |

## Keeping these docs current

- One topic per page. When a feature changes, change the one page that covers it.
- Player pages describe what you see and do, never class names or file paths in the code.
- Developer pages may name paths. When code moves, search `docs/` for the old path.
- Links between pages are relative, so the `docs/` folder can move as a whole.
