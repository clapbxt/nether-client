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
| Nether news, packs | the Nether service (signed files) | Opening Home |
| Capes and cosmetics | the Nether player service (Cloudflare) | Opening Wardrobe, wearing a cape or cosmetic, and checking in at game start so others see your icon |
| Updates | api.github.com, github.com | Checking for and downloading signed updates |
| OptiFine (Nether + OptiFine profiles, 1.8.9 / 1.12.2) | bmclapi2.bangbang93.com (the OptiFine mirror HMCL and XMCL use) | Only the first launch of an OptiFine profile |
| Hosting | api.playit.gg, api.ipify.org, api.mcstatus.io, fill.papermc.io | Only while you use the Host tab |
| Friends, world invites | the Nether friends service (Cloudflare) | While you are signed in: your friends list, requests, blocks and status, about once a minute |
| Parties and party voice | your party members, directly (peer-to-peer); n0's iroh.link relays when a direct link can't be made | Only while you are in a party with others |
| Invite to world (friends joining your world) | your friends, directly (peer-to-peer); n0's iroh.link relays when a direct link can't be made | While a game runs, the launcher keeps a link to an iroh.link relay ready so sharing your world is instant; friends connect only after you invite them |
| Sharing logs | api.mclo.gs | Only when you confirm Share or press Upload; anyone with the link can read it (your user name, home folder, IP addresses and tokens are removed first) |

## In game

- **Capes and cosmetics.** To find the Nether players in your tab list, the client sends the player service only the first
  4 characters of a hash of each player's UUID. The service answers with every Nether player under those prefixes
  and the client picks the matches itself, so the service never learns whom you play with. It asks at most once per
  player every 10 minutes. You can turn this off with **Settings → Privacy → Capes and cosmetics in game**.
- **Lyrics** (off by default) send the song title, artist, album and length to lrclib.net. If LRCLIB has no lyrics,
  the title and artist are also sent to api.lyrics.ovh for plain-text lyrics. The title and artist are sent to
  music.163.com (NetEase Cloud Music) for word-by-word timing. Lyrics are cached on your PC.
- **Server status.** The title screen shows the status of the servers saved in your Multiplayer list, so the game
  pings them (like the Multiplayer screen does) about once a minute while it is open. Turn **Live server status** off in the Main
  Menu settings to stop it.
- **The media player** reads your PC's media session through a small local helper. Nothing leaves your PC.

## Friends and parties

- **Friends.** The friends service keeps your friends list, friend requests, blocks and a short-lived status
  (online / away, and what you are playing; the server address only when **Share server** is on). Friends see your
  status only while you are online. Choose **Invisible** to look offline.
- **Parties.** So that friends can invite you or ask to join, your launcher adds its party address (a random key made
  fresh each time the launcher starts) and the size of your party to the status your friends see. Everything else in a
  party (who is in it, invites, join requests, chat and voice) goes **directly between party members' launchers**,
  encrypted. It is never sent to or stored by a Nether server.
- **Party chat** is shown only on the title screen and lives in memory: it is never saved, logged or shown again after
  the party ends.
- **Party voice** is off until you turn on push-to-talk or open mic. The microphone isn't opened before that. Voice goes
  only to your party and is **never recorded**.
- **Your IP address.** A direct peer-to-peer link lets the party leader see your public IP address (and you see
  theirs). Turn on **Hide my IP (relay)** in the party panel to send everything through a relay instead: party members
  then never see your IP; the relay (run by n0, the makers of iroh) sees connection details such as IP addresses and
  timing, never what is said.
- **Blocking** someone stops their party chat and voice reaching you (and yours reaching them) at once; they can't
  invite you, and a party you are in won't let them join.

## What we store

The player service stores, per player who checked in: your UUID, the cape and cosmetics (hat, headband, mask, back, wings, aura,
pet) you chose, and when you were last seen. Players unseen for 180 days are deleted automatically.

Turning **Capes and cosmetics in game** off deletes your entry right away (if you are signed out or offline, at your next
sign-in).

The friends service stores your friends list (at most 67 friends), pending requests (deleted after 14 days), blocks,
and your last status. 180 days after you were last seen, all of it is deleted, including you on others' lists. World invites expire after two hours. Nothing about parties is stored.

Everything else (instances, worlds, settings, screenshots, logs) stays in `%APPDATA%\NetherClient` on your PC.

## Contact

To ask what we store about you, or to have it deleted, contact us on the [Nether Client Discord](https://discord.gg/R5vxAxVjwu).
The [Terms of Service](TERMS.md) also apply.
