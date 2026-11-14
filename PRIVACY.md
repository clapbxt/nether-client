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
| Sharing logs | api.mclo.gs | Only when you confirm Share or press Upload; anyone with the link can read it (your user name, home folder, IP addresses and tokens are removed first) |

## In game

- **Nether capes.** To find the Nether players in your tab list, the client sends the cape service only the first
  4 characters of a hash of each player's UUID. The service answers with every Nether player under those prefixes
  and the client picks the matches itself, so the service never learns whom you play with. It asks at most once per
  player every 10 minutes. You can turn this off with **Settings → Privacy → Nether capes in game**.
- **Lyrics** (off by default) send the song title, artist, album and length to lrclib.net, and the title and artist
  to music.163.com (NetEase Cloud Music) for word-by-word timing. Lyrics are cached on your PC.
- **Server status.** The title screen shows the status of the servers saved in your Multiplayer list, so the game
  pings them (like the Multiplayer screen does) about once a minute while it is open. Turn **Live server status** off in the Main
  Menu settings to stop it.
- **The media player** reads your PC's media session through a small local helper. Nothing leaves your PC.

## What we store

The cape service stores, per player who checked in: your UUID, the Nether cape and cosmetics (hat, headband, mask, back, wings, aura,
pet) you chose, and when you were last seen. Players unseen for 180 days are deleted automatically.

Turning **Nether capes in game** off deletes your entry right away (if you are signed out or offline, at your next
sign-in).

Everything else (instances, worlds, settings, screenshots, logs) stays in `%APPDATA%\NetherClient` on your PC.
