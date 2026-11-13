# Nether Client: privacy

**No telemetry, no analytics, no ads.** Nether Client doesn't track what you do. This page lists every service the
launcher and the in-game client talk to, and when. The same list is in the launcher under **Settings → Privacy**.

## Your Microsoft account

You sign in with Microsoft's own device-code page. Your sign-in token goes **only** to Microsoft and Mojang (the same
services the official launcher uses), and it is kept in Windows Credential Manager on your PC.

**Our cape service never sees your Minecraft token.** To prove who you are, the launcher uses the same handshake a
Minecraft server uses: it tells Mojang it joined a one-time id, and our service asks Mojang to confirm. We get back
your player UUID and nothing else.

## Who the launcher talks to

| What | Services | When |
|---|---|---|
| Sign-in | login.microsoftonline.com, xboxlive.com, api.minecraftservices.com | Signing in, and refreshing it |
| Game files | piston-meta.mojang.com, launchermeta.mojang.com, libraries.minecraft.net, resources.download.minecraft.net | Installing and repairing versions |
| Mod loaders | meta.fabricmc.net, meta.quiltmc.org, maven.minecraftforge.net, maven.neoforged.net | Installing a loader |
| Mods and modpacks | api.modrinth.com, cdn.modrinth.com | Searches and downloads you start |
| Skins, capes, news | sessionserver.mojang.com, api.mojang.com, textures.minecraft.net, launchercontent.mojang.com | Showing your skin and Mojang's news |
| Nether news, packs, cape list | the Nether service (signed files) | Opening Home and Wardrobe |
| Nether capes | the Nether cape service (Cloudflare) | Wearing a cape, and checking in at game start so others see your icon |
| Updates | api.github.com, github.com | Checking for and downloading signed updates |
| Hosting | api.playit.gg, api.ipify.org, api.mcstatus.io, fill.papermc.io | Only while you use the Host tab |
| Sharing logs | api.mclo.gs | Only when you press Share or Upload (your user name, home folder and tokens are removed first) |

## In game

- **Nether capes.** The client asks the cape service which players in your tab list are Nether players, by
  UUID only. It asks at most once per player every 10 minutes. You can turn this off with
  **Settings → Privacy → Nether capes in game**.
- **Lyrics** (off by default) send the song title, artist, album and length to lrclib.net. Lyrics are cached on your PC.
- **The media player** reads your PC's media session through a small local helper. Nothing leaves your PC.

## What we store

The cape service stores, per player who checked in: your UUID, the Nether cape you chose, and when you were last
seen. Players unseen for 180 days are deleted automatically. To be removed sooner, open
[an issue](https://github.com/clapbxt/nether-client/issues) and we'll delete your entry.

Friends (when it launches) is opt-in. It stores your friend code and your accepted friends by UUID, never names.
Turning it off deletes all of it.

Everything else (instances, worlds, settings, screenshots, logs) stays in `%APPDATA%\NetherClient` on your PC.
