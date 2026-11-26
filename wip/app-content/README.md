# app-content: unmerged launcher work, saved from Nate's PC

This folder holds launcher work that existed only in a local worktree on Nate's PC (the `app-content` branch). It is
saved here so it isn't lost, and it is **not applied**. The branch sits on old local history that GitHub never had
(its shared base `a3b010b` isn't on origin). Most hunks no longer apply to main after the backend cleanup and the
crate split, so the work needs porting by hand. The PR that adds this folder is a draft and is not meant to be
merged as is.

| File | What |
|---|---|
| `LOG.txt` | The branch's three commits since `a3b010b`. The Host page commit has a detailed message. |
| `app-content.diff` | `git diff --binary a3b010b` in the worktree: those commits plus the uncommitted edits to tracked files |
| `new/crates/app/src/discover.rs`, `new/crates/app/ui/importdlg.slint` | Files that were never committed |

## What the work is

- **Host page** (committed on that branch, not on main):
  - Choose an instance, then set up, start and stop its server.
  - EULA dialog: only the user's click accepts it.
  - Live status, console and player list.
  - Server settings, whitelist and ops.
  - Copy a world in, and make backups.
  - LAN and UPnP addresses, plus an invite code.
  - Code: `crates/app/src/host.rs` and `ui/host.slint`.
- **Wardrobe skin library** (committed on that branch). main already has a later version of the wardrobe, so check
  what is still missing before porting.
- **Discover rework** (uncommitted on that branch):
  - Live Modrinth search replaces the hardcoded modpack list.
  - Filters and sort.
  - Icons load in the background.
  - One-click install, with progress shown on each card.
  - Files: `discover.rs`, `discover.slint`, `moddetail.slint`, `mods.rs` and core `modrinth.rs`.
- **Import dialog** (uncommitted on that branch):
  - Pick or drop a file or folder.
  - Preview its format, name, version, loader and missing files, then import.
  - Files: `importdlg.slint`, and `preview()` in core `import.rs`, with a test.

`git apply --reject app-content.diff` on main rejects about 50 hunks: app.slint, structs.slint, skin.rs,
wardrobe.slint, instances.slint, moddetail.slint, components.slint, modrinth.rs and others. The new files (host.rs,
host.slint, discover.rs, importdlg.slint) can be taken whole.
