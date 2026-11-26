# Install and first launch

## What you need

- **Windows 10 or 11, 64-bit.** Other systems aren't supported yet.
- **Minecraft: Java Edition** on a Microsoft account. Nether Client is a launcher, not a copy of the game.
- No admin rights. Nether Client installs for your Windows user only.

Java is downloaded for you, in the right version for each Minecraft version.

## Install

1. Download `NetherClientSetup.exe` from the [latest release](https://github.com/clapbxt/nether-client/releases/latest)
   or the [website](https://clapbxt.github.io/nether-client/).
2. Run it. Windows SmartScreen may say it "protected your PC", because the installer isn't code-signed by Microsoft
   yet. Click **More info**, then **Run anyway**. Every release is signed with our own key, and the launcher checks that
   signature before it installs any update (see [Releases and signing](../develop/releasing.md)).
3. Choose whether you want a desktop shortcut and whether Nether Client starts with Windows, then install.

## Sign in

The first time it opens, the launcher asks you to sign in with Microsoft:

1. Press **Sign in with Microsoft**. The launcher shows a short code.
2. Press **Open page** (or go to the page yourself), enter the code and sign in as you would on the official launcher.
3. The launcher picks up the sign-in by itself.

Your sign-in is stored in Windows Credential Manager on your PC and goes only to Microsoft and Mojang. Our services
never see it. Details: [Privacy](../../PRIVACY.md).

You can add more accounts and switch between them in **Settings → Accounts**.

## Play for the first time

The first time you open Home it says **Pick a version and play** and offers two ready-made choices. Press one and it
starts that Minecraft version with the Nether client. The first launch downloads the game, so it takes a minute; after
that it starts straight away.

When you want a particular version, mods or a modpack, make a **profile** on the **Play** page. See
[Profiles](profiles.md).

Once you're in the game, press **Right Shift** to open the Nether menu. See [In game](in-game.md).

## Updates

The launcher checks for updates by itself, and you can press **Check now** in **Settings → Storage & About → Updates**.
When an update has downloaded, press **Restart now** or just close the launcher: it is applied as the launcher closes.
Turn on **Beta updates** there to get new versions early, before they're fully tested.

Sometimes an update is required, for example when our services change. The launcher then tells you before you play.

## Uninstall

Open **Windows Settings → Apps → Installed apps**, find **Nether Client** and choose **Uninstall**.

Your instances, worlds, screenshots and settings are kept unless you tick **Also delete my data**. They stay in
`%APPDATA%\NetherClient` until you delete them.

## Where things are on your PC

| Folder | What's there |
|---|---|
| `%LOCALAPPDATA%\Programs\Nether Client` | The launcher itself |
| `%APPDATA%\NetherClient` | Your profiles, worlds, screenshots, downloaded game files, Java, settings and logs |
| Windows Credential Manager | Your Microsoft sign-in |

**Settings → Storage & About** has buttons that open each of these folders.
