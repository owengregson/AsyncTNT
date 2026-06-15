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

Verified:
- 22 hand-computed unit pins for the physics core (movement, explosion, falling-block, RNG, per-version profile).
- Live on **Paper 1.17.1** (legacy/spigot-mapped): **6/6** integration tests pass — plugin loads,
  engine takes ownership, detonates a real crater, and sand-through-water lands bit-identically
  engine-on vs engine-off.
- Live on **Paper 26.1.2** (year-scheme/Mojang-mapped, `modernSchedulers`/`registryAttributes`):
  loads with the engine on, **5/6** pass.

Known refinement points (tracked in the spec §10; the harness drives them per version):
- 26.1.2 falling-block-in-water settling timeout.
- Explosion **block-resistance** uses a vanilla-constant table and **fluid flow** a gradient
  approximation; the exact NMS values are the matrix-verified follow-up.
- Explosion **drops** and `EntityExplodeEvent` (protection-plugin compatibility) are not yet emitted.
- The offloaded explosion-apply has a ≤1-tick latency for entities it knocks (the accepted parity caveat).

Config (`config.yml`): per-world enable, worker-thread count, a kill-switch, and opt-in
cannon-stabilization toggles (all off by default — vanilla is the default). Commands: `/asynctnt
status|reload|world <name> <on|off>|killswitch`.
