# Profiles

A **profile** (also called an instance) is one Minecraft setup: a version, a way to play, and its own mods, resource
packs, worlds and settings. Profiles never share mods or worlds, so you can keep a PvP setup, a modpack and a
survival world side by side without them touching each other.

> The way you pick a version and turn the Nether client on or off is being redesigned. This page describes the
> launcher as it is today and will change with it.

## Ways to play

When you make a profile you first choose **how to play**:

| Choice | What you get | Versions |
|---|---|---|
| **Nether** | Minecraft with the Nether client: HUD, cosmetics, friends and parties. Nothing else to install. | 1.8.9, 1.12.2, 1.14 and newer |
| **Nether + Fabric** (recommended) | The Nether client on Fabric. Adds Sodium, Lithium, FerriteCore and Mod Menu, and runs your own Fabric mods. | 1.14 and newer |
| **Nether + Forge** | The Nether client on Forge, for big content mods and Forge modpacks. | 1.14 and newer |
| **Nether + OptiFine** | OptiFine's zoom, shaders and connected textures, downloaded and set up for you. | 1.8.9 and 1.12.2 |
| **Vanilla profile** | Plain Minecraft, or Fabric, Forge, NeoForge or Quilt, **without** the Nether client. | Any version |

Pick a Vanilla profile when you need NeoForge, Quilt or a version the Nether client doesn't support.

The Nether client works the same on every loader because it is not a mod: the launcher adds it to the game when it
starts. That is also why it doesn't conflict with Sodium, Iris or other performance mods.

## Make a profile

On **Play**, press **New profile** (or **Ctrl+N**) and follow the steps:

1. **How to play** (or **Loader** for a Vanilla profile).
2. **Version**: the Minecraft versions that way of playing supports, newest first.
3. **Nether pack**: start empty, or with one of our small ready-made sets of mods and client settings.
4. **Resource packs**: pick any popular packs for that version to download with it (optional).
5. **Name, memory, icon and wallpaper**, with a summary. Press **Create**.

Nothing downloads until you press **Play** the first time.

**Import** brings in a modpack file (Modrinth `.mrpack`, CurseForge zip) or profiles from another launcher on your
PC: Prism, MultiMC, the CurseForge app, the Modrinth App, ATLauncher, the official launcher, and Lunar, NoRisk,
OneClient or River client profiles. It can also copy their keybinds and game settings. The original is only read,
never changed. **Install Packs** installs a Modrinth modpack as a new profile.

## Inside a profile

Open a profile from **Play** to see its tabs.

| Tab | What you can do |
|---|---|
| **Overview** | Play or stop, rename, move it to a group, change memory, Java, icon and wallpaper, and manage **restore points**. |
| **Mods** | Browse and install mods from Modrinth, update them, turn them on or off, or remove several at once. |
| **Packs** | Resource packs and shaders. **Combine packs** builds one new resource pack from the parts of several. |
| **Worlds** | Your singleplayer worlds and servers. Play a world or join a server straight from the launcher, or **Import world**. |
| **Screenshots** | Every screenshot from this profile. |
| **Logs** | The game log. **Issues only** filters it to warnings and errors; **Share link** uploads it to mclo.gs for support (personal details are removed first). |

## Keeping your stuff safe

- **Update history.** Before mods or packs are updated, the old files are set aside. Open the history and press
  **Undo** to put an update back exactly as it was. The last 5 are kept.
- **Restore points.** A restore point is a copy of the whole profile: mods, configs, worlds, packs and settings.
  By default one is made automatically each week, before a launch, keeping the newest. You can restore in place or as a
  new profile; restoring in place first saves the current state, so you can always go back.
- **Deleting** a profile, world, mod or screenshot sends it to the Windows Recycle Bin, so a mis-click can be undone
  from there.

## Shared game settings

Normally every profile has its own Minecraft settings. Turn on **Settings → Shared game settings** to set your
keybinds, FOV, audio, chat and mod video settings once and have them follow you into every profile. Keybinds are
translated for old versions (1.8.9, 1.12.2) automatically.

## Horizon (experimental)

The Play page also lists **Horizon**, a separate open-source project that rewrites Minecraft Java Edition 26.3 in
Rust. It is experimental and runs without the Nether client. You need to own Minecraft and be signed in to play it.
