# Fix Flashback

A small NeoForge compatibility patch that lets the Fabric mod **Flashback** run on a NeoForge modpack through **Sinytra Connector** without crashing.

Flashback records and replays gameplay, but when it is loaded on NeoForge via Connector it crashes in several places that only exist on the Forgified Fabric API / ModernFix / NeoForge stack. This mod fixes those crashes so recordings can be opened and played back normally.

> **[Create: Flashback](https://github.com/tatuto-riku/create_flashback)** and **[Create: Aeronautics Flashback](https://github.com/tatuto-riku/create_aeronautics_flashback)** make the Flashback mod compatible with **Create** and **Create: Aeronautics**.

## Installation

- Install NeoForge `1.21.1` and Sinytra Connector.
- Install Flashback (download the **Fabric** `1.21.1` version; Connector runs it for you).
- Put `fix_flashback-<version>.jar` into your `mods` folder.
- That's it — just record and replay with Flashback as usual.

## Requirements

- Minecraft `1.21.1`
- NeoForge `21.1.x`
- Flashback `0.30+` (loaded through Sinytra Connector)
- Sinytra Connector + Forgified Fabric API (needed to run Fabric mods like Flashback on NeoForge)

## Notes

- **This is an add-on patch, not a replacement for Flashback.** Flashback itself must be installed.
- It is safe to install even when Flashback is absent — every fix is applied through `@Pseudo` mixins and does nothing unless the relevant mod is present.
- No known conflicting mods. It only hooks Flashback / ModernFix / NeoForge internals to restore expected behaviour.

## Known incompatible mods

The following mods interfere with Flashback's replay features. If you install them, the corresponding feature stops working. Remove or disable them to use that feature:

| Mod | Feature broken |
| --- | --- |
| **BadOptimizations** | Night vision is no longer applied during replays |
| **Create: Steam 'n' Rails** | Spectator camera stops working |
| **Smooth Movement** | Time override stops working |

These only affect Flashback's replay features; the rest of the mods work normally.

## Other fixes

- **Entity stuck-sliding during replays** — Recorded entities slide linearly between positions instead of snapping to the replayed location. Fix Flashback clamps the client-side interpolation step count back to the vanilla default after each teleport packet and disables entity smoothing while a replay is playing, so entities stay put at their recorded positions.

## How it works

Flashback's fake replay player relies on an internal Fabric marker interface and on Fabric's networking / stronghold-cache behaviour, none of which Forgified Fabric API reproduces exactly. Fix Flashback patches the gaps:

- **World load crash** — Flashback's fake player implements `net.fabricmc.fabric.impl.networking.UntrackedNetworkHandler`, an internal Fabric interface Forgified Fabric API never shipped. The marker is removed from Flashback's class at load time (it is empty, so nothing is lost) and the "skip untracked handler" behaviour it controlled is restored, so Fabric networking no longer hands the fake player to real connection listeners.
- **Crash opening a recording** — ModernFix's `cache_strongholds` optimization downcasts a `null` chunk generator for Flashback's replay server and throws a `NullPointerException`. The stronghold cache setup is skipped for the replay server, which is safe (it is only a performance optimization).
- **"Disconnected" when entering a replay** — NeoForge throws while firing `PlayerLoggedInEvent` for a `BannerPattern` that is not in the replay registry. The exception is swallowed so the viewer player is placed correctly.
- **Connection lost while playing back** — Recorded `custom_payload` packets can fail to read/encode on this modpack. Corrupted replay actions are skipped and encoder exceptions no longer close the connection, so playback continues.

## License

[MIT](LICENSE) © tatuto-riku

> Since this mod is currently under development, it may not work correctly.
