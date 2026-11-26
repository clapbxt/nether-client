# Troubleshooting and FAQ

## Minecraft crashed

When the game crashes the launcher opens a crash page by itself:

- **What we found** explains the likely cause in plain words, with a suggested **Fix**.
- An automatic analysis from mclo.gs is added when the log is shared.
- **Full log** shows the whole log. **Support zip** saves everything someone helping you would need.

You can reopen it later with **View crash** on Home.

Before a launch the launcher also checks for common problems (missing or clashing mods, the wrong Java). If it finds
any it lists them with a fix; you can still **Launch anyway**.

## A mod update broke something

Open the profile, find the update in its history and press **Undo**. For bigger problems, restore a **restore point**
from the profile's Overview. See [Profiles](profiles.md#keeping-your-stuff-safe).

## Getting help

1. Ask on [Discord](https://discord.gg/R5vxAxVjwu).
2. Or open an [issue](https://github.com/clapbxt/nether-client/issues). Include:
   - what happened, and what you expected;
   - **Settings → Storage & About → Support → Copy system info** (your user folder and tokens are removed);
   - the log, shared from the profile's **Logs → Share link** or the crash page.

Security problems: please report them privately as described in [SECURITY.md](../../SECURITY.md).

## FAQ

**Is Nether Client a mod?**
No. It's a launcher plus an in-game client the launcher adds when the game starts. That's why it works the same on
Vanilla, Fabric, Forge and OptiFine, and why it doesn't clash with Sodium or Iris.

**Can I get banned for using it?**
The Nether client never plays for you, sends packets, or shows anything the server didn't send. A few modules (Zoom,
Fullbright, Freelook, Reach Display) are against some servers' rules, so check before turning them on. See
[Fair play](../../SAFETY.md).

**Which versions does it support?**
The Nether client runs on 1.8.9, 1.12.2, and 1.14 and newer. Any other version can be played through a Vanilla
profile, without the client.

**Does it collect data about me?**
No telemetry, no analytics, no ads. Every online service it uses is listed in [Privacy](../../PRIVACY.md) and in
**Settings → Privacy**.

**Windows says the installer is unsafe.**
The installer isn't code-signed by Microsoft yet. See [Install](install.md#install).

**Mac or Linux?**
Not yet. Nether Client runs on Windows 10 and 11 (64-bit).

**Where are my worlds?**
In `%APPDATA%\NetherClient`, one folder per profile. A profile's **Worlds** tab lists them and opens the folder.

**Can I change the menu key?**
Yes, in the client's settings inside the Nether menu.
