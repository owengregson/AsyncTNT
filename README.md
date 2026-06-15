# AsyncTNT

Folia-native (Paper + Folia) plugin for Minecraft **1.17.1 → 26.1.x** that ticks primed TNT and
falling blocks (sand/gravel) on a worker pool instead of the server thread — relieving the lag that
mass TNT (factions cannons) puts on the main/region thread — while replicating vanilla physics
**1:1 per server version** and changing **no mechanics by default**.

It is not packet-suppression like existing "async TNT" plugins: it is a real off-thread engine that
takes ownership of TNT/falling-block ticking via a strict **snapshot → compute → apply** discipline,
with a pure, unit-pinned physics core that *is* the definition of vanilla in this project.

## How it works

- The **engine** claims every primed TNT / falling block at spawn and neutralizes its vanilla tick
  (gravity off + zeroed vanilla velocity + held fuse), then drives it via a scheduling seam that is
  region-correct on Folia and main-thread on Paper. Per tick it integrates movement on the owning
  thread (cheap, deterministic) and, on detonation, runs the heavy 1352-ray explosion solve on a
  worker pool and applies the result per owning region.
- The **physics core** (`:common`) reproduces — bit-for-bit, verified against decompiled 1.21.11
  source — gravity `0.04`, the `Entity.collide` axis order, drag `0.98`, ground friction, the
  water-current push that propels water-stream cannons (and the cannon-critical fact that **falling
  blocks are *not* water-pushed**), and the explosion ray-march + RNG-free knockback.
- **Cross-version**: one universal jar compiled against the 1.17.1 floor API, capability detection
  (never version-string branching), reflection routed through reflection-remapper, and a
  `compat-folia` module loaded reflectively. See `docs/superpowers/specs/` and `docs/research/`.

## Build & test

```bash
./gradlew build                 # compile + 22 physics unit pins (the parity-oracle math)
scripts/integration-matrix.sh                 # real-server suite, every version in gradle.properties
scripts/integration-matrix.sh 1.17.1 26.1.2   # floor + ceiling only
./gradlew integrationTest                      # gradle path: floor + ceiling
./gradlew integrationTestMatrix                # gradle path: every version
```

Never trust the gradle banner alone — verify `run/<version>/plugins/AsyncTNTTester/test-results.txt`
is fresh and reads `PASS` (the `matrix-gate` discipline).

## Status

Verified — **the full matrix is green: all 7 versions (1.17.1, 1.18.2, 1.19.4, 1.20.6, 1.21.4,
1.21.11, 26.1.2) pass 6/6 on real Paper servers**, spanning the spigot/Mojang mapping flip (1.20.5),
the explosion refactor (1.21), the attribute change (1.20.5/1.21.3), and the year-scheme version
parsing (26.x). Plus 26 hand-computed unit pins for the physics core. Each integration run proves the
plugin loads, the engine takes ownership of TNT/falling-blocks, detonates a real crater (with vanilla
events), and drives sand through water onto the floor.

Protection-plugin compatibility, exact NMS resistance/fluid, the inline same-tick knockback, and the
vanilla falling-block despawn are all implemented (see below). Remaining honest notes:
- Exact NMS explosion resistance is used where the reflection is **validated** against the known
  constant table; on versions where it disagreed (silently wrong on some spigot-mapped builds) it
  falls back to the vanilla-constant table — both are exact for cannon-relevant blocks.
- While the engine owns a TNT its client-side **fuse flash** is held (the server fuse is pinned high
  so vanilla can never win the detonation race); the physics/timing are unaffected.
- The heavy block-destruction raytrace is applied with a ≤1-tick latency (within the agreed parity
  bar); explosion **knockback** is applied inline the same tick, so cannon aim is exact.

What now works like vanilla for plugins: AsyncTNT fires `ExplosionPrimeEvent` (cancellable; honors a
modified radius/fire) and `EntityExplodeEvent` (cancellable, mutable block list, yield) before
destroying blocks, emits block drops at the yield, and primes TNT blocks caught in a blast — so
WorldGuard/factions/CoreProtect and friends block, filter, and log AsyncTNT explosions exactly as
they do vanilla ones.

Config (`config.yml`): per-world enable, worker-thread count, a kill-switch, and opt-in
cannon-stabilization toggles (all off by default — vanilla is the default). Commands: `/asynctnt
status|reload|world <name> <on|off>|killswitch`.
