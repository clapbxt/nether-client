# Rules
1. **Appearance is the only representation we get.** Every screen must look finished. Polish before breadth.
2. **Fast.** Cold start < 1s, UI never blocks (async everything), High FPS while idle.
3. **Native Rust.** No Electron/webview. Small binary, low RAM, high speed.
4. **Customizable by default, simple by default.** Good out of the box; every piece themable/movable/toggleable.
5. **Open formats.** Instances, themes, profiles = plain files (TOML/JSON). Exportable, importable (Prism/MultiMC/Modrinth/CurseForge).
6. **Trust.** No telemetry without opt-in, no ads, no paywalled basics. Auth tokens in OS keyring.
7. **Compatibility over novelty.** Never break a user's instance or worlds.
8. **Smallest complete change.** No unrequested features; ship vertical slices.
9. **Notes stay tiny.** Vault holds decisions only.
10. **Quality isn't compromised.** Previous rules are used in combination, not seperately, making sure quality is maintained, such as code quality, UI quality, compatability, etc.
11. **Never touch the rendering engine.** Client hooks stay below/outside renderer code (Sodium, Iris, DH, Embeddium must work untouched).
