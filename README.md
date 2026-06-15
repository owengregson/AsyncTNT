# AsyncTNT

**Asynchronous TNT for Paper & Folia — kill the lag from TNT cannons without changing how TNT works.**

AsyncTNT moves the heavy work of ticking primed TNT and falling blocks (sand/gravel) and computing
explosions **off the main server thread**, so big factions-cannon volleys stop tanking your TPS. The
physics stay **identical to vanilla** on every supported version — same trajectories, same
explosions, same cannon behavior.

## What it does

- Runs TNT + sand/gravel movement and the explosion math on background threads, removing the lag
  spikes that mass TNT causes.
- **Changes nothing by default.** Out of the box it's byte-for-byte vanilla — your cannons fire
  exactly as they do now, just without the lag.
- One jar for **Minecraft 1.17.1 → 26.1.x**, on both **Paper and Folia**.

## Install

1. Download `AsyncTNT.jar` from the [latest release](https://github.com/owengregson/AsyncTNT/releases/latest).
2. Put it in your server's `plugins/` folder.
3. Restart the server. That's it — no setup required.

## Works with your other plugins

- **Drop-in.** No client mods, no datapacks, no world changes.
- **Protection / anti-grief / logging** plugins (WorldGuard, factions plugins, CoreProtect, GriefPrevention, …)
  keep working — AsyncTNT fires the same explosion events Minecraft does, so those plugins still
  block, protect regions, and log explosions normally.
- **Anti-cheats** see ordinary TNT entities and ordinary velocities.

## Commands

| Command | What it does |
| --- | --- |
| `/asynctnt status` | Show whether the engine is running and how many TNT it's handling. |
| `/asynctnt reload` | Reload the config. |
| `/asynctnt world <name> on\|off` | Turn the engine on/off for one world. |
| `/asynctnt killswitch` | Instantly hand all TNT back to normal vanilla ticking. |

Alias: `/atnt`. Permission: `asynctnt.command.use` (ops by default).

## Configuration (`config.yml`)

| Setting | Default | Meaning |
| --- | --- | --- |
| `engine.enabled` | `true` | Master on/off switch. |
| `engine.disabled-worlds` | (none) | Worlds left on vanilla TNT. |
| `engine.worker-threads` | auto | Number of background threads. |
| `fork-fixes.*` | all `false` | Optional cannon-stabilization tweaks (TacoSpigot/PandaSpigot style). Leave off for pure vanilla. |

## Supported platforms

- **Paper** 1.17.1, 1.18.2, 1.19.4, 1.20.6, 1.21.x, 26.1.x (and the versions in between).
- **Folia** 1.19.4+ (region-aware — cannons that fly across regions are handled).
- Requires Java 17+ (Java 21+ on Minecraft 1.20.5 and newer, as the game itself does).

## Notes

- While the engine is handling a TNT, its blinking "fuse flash" animation may not speed up near the
  end — purely visual; the timing and explosion are exactly vanilla.
- Found a bug or a cannon that behaves differently? Please open an [issue](https://github.com/owengregson/AsyncTNT/issues).

---

<sub>Building from source: `./gradlew build`. Design notes, the per-version physics research, and the
real-server test matrix live under `docs/`.</sub>
