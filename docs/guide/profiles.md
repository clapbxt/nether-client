# Profiles

An **instance** is one Minecraft setup: a version, a loader, the Nether client on or off, and its own mods,
resource packs, worlds and settings. Instances never share mods or worlds, so you can keep a PvP setup, a modpack and
a survival world side by side without them touching each other.

## Make an instance

On **Play**, press **New instance** (or **Ctrl+N**). If you don't have any instances yet, Play opens straight into
these steps. First choose **Quick**, **Manual**, **Modpack** or **Horizon**.

**Quick** asks for one thing, the Minecraft version. You get Fabric, the Nether client turned on, and Nether's
performance mods (Sodium, Lithium, FerriteCore, ModernFix and a mod menu). Press **Create** or **Create & play**.

**Manual** takes five short steps:

1. **Version**: every Minecraft release, newest first. A dot marks the versions the Nether client runs on.
2. **Loader**: Vanilla, Fabric (recommended), Forge or NeoForge. A loader with no build for your version is greyed
   out, and each one says whether the Nether client runs with it.
3. **Nether client**: on or off. When it can't run on your version and loader, the launcher says why (for example
   "On 1.12.2, the Nether client runs with Vanilla only, not Forge") and offers a loader where it does.
4. **Mods**: the **Performance mods** switch, on by default, with a box for each of its mods. Turning the switch on
   ticks them all; unticking any one turns the switch off, and only the ticked ones are installed. Below it, popular
   Modrinth mods for your version and loader to pick from or search. Vanilla skips this step.
5. **Resource packs**: pick any popular packs for that version (optional). The name is filled in for you.

Mods and packs download right after you press **Create**. **Create & play** starts the game once they're done.

**Modpack** takes two steps: search Modrinth for a pack (the most downloaded show first), then turn the Nether
client on or off. When the pack's version or loader can't run the Nether client, **Nether on** is greyed out and the
reason is shown. The pack's newest version installs in the background and appears in the list when it's ready.

**Horizon** has nothing to choose: it adds Horizon to your Play list (see [Horizon](#horizon-experimental)).

### Where the Nether client runs

| Minecraft | Loaders |
|---|---|
| 1.14 and newer (26.x too) | Vanilla, Fabric, Forge |
| 1.12.2 | Vanilla |
| 1.8.9 | Vanilla, Fabric (Legacy Fabric) |

NeoForge, other versions and snapshots play as plain Minecraft. The Nether client works the same on every loader
because it is not a mod: the launcher adds it to the game when it starts. That is also why it doesn't conflict with
Sodium, Iris or other performance mods. You can turn it on or off later from the instance's page.

**Import** brings in a modpack file (Modrinth `.mrpack`, CurseForge zip) or profiles from another launcher on your
PC: Prism, MultiMC, the CurseForge app, the Modrinth App, ATLauncher, the official launcher, and Lunar, NoRisk,
OneClient or River client profiles. It can also copy their keybinds and game settings. The original is only read,
never changed. **Install Packs** installs a Modrinth modpack as a new instance; modpacks list on **Play** with everything else.

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

Add it from **New instance > Horizon** and the Play list shows **Horizon**, a separate open-source project that
rewrites Minecraft Java Edition 26.3 in Rust. It is experimental and runs without the Nether client. You need to own
Minecraft and be signed in to play it. Right-click its row to open its folder or remove it from the list (its files
stay).
