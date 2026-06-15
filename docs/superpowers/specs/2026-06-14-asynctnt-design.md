# AsyncTNT — Design Spec

_Status: approved (brainstorm 2026-06-14). Seeds the implementation plan._
_Ground truth: [`docs/research/2026-06-14-tnt-explosion-folia-research.md`](../../research/2026-06-14-tnt-explosion-folia-research.md)._

## 1. Summary

AsyncTNT is a Folia-native (Paper + Folia) plugin for Minecraft **1.17.1 → 26.1.x** that takes
ownership of primed-TNT and falling-block (sand/gravel) ticking away from the server and runs the
**heavy computation on a worker pool**, via a strict **snapshot → compute → apply** discipline, to
relieve the lag that mass TNT (factions cannons) puts on the main/region thread. It replicates
vanilla physics **bit-for-bit per server version** (cannon-exact + deterministic) and does **not**
change mechanics: real entities stay in the world, and the default behavior is byte-identical
vanilla.

This is unprecedented: every existing "async TNT" plugin is client-side packet suppression, not
real off-thread simulation. Correctness is therefore **proven against a vanilla oracle**, not
assumed — the verification gate (§9) is the load-bearing part of the project.

## 2. Goals / non-goals

**Goals**
- 1:1 vanilla physics for primed TNT + falling blocks, per version, cannon-exact and deterministic.
- Offload the explosion solve (1352-ray block selection + damage/knockback math) and batched body
  integration off the owning thread.
- Folia-correct (region-threaded ≥1.19.4) and Paper-correct (main-thread <1.19.4), same code path.
- A test suite that proves parity per version (the Mental/MentalTester model).

**Non-goals**
- Changing any mechanic by default. Fork-style cannon stabilizations ship **OFF** (§8).
- Custom cannon features, GUIs, economy, etc.
- Optimizing non-TNT/non-falling-block entities.

## 3. Decisions (the four cornerstones, locked in brainstorm)

1. **Engine model:** custom off-thread engine owns TNT + falling-block lifecycle. Snapshot →
   compute → apply. Real "shadow" entities remain; engine holds the authoritative physics state.
2. **Parity bar:** cannon-exact + deterministic. Bit-identical trajectories, fuse timing,
   water-push, explosion block-sets and knockback for self-contained cannon scenarios. Interactions
   with non-engine entities may differ by ≤1 tick (cross-region marshalling / snapshot boundary) —
   the documented, accepted caveat.
3. **Scope:** primed TNT **and** falling blocks (sand/gravel) and cannon-relevant fluid/redstone.
   Critical asymmetry: **TNT is pushed by flowing water; falling blocks are not** (they fall through
   it) — this is exactly why cannons fire sand/gravel through base-wall water layers. Both are
   engine-owned for lock-step timing, with their different fluid rules.
4. **Fork fixes:** strict-vanilla default; opt-in stabilization toggles shipped OFF.

## 4. Module / repo architecture (mirrors Mental/StrikeSync)

One universal jar compiled against the **1.17.1 floor API**; Gradle 9.5.x; Java 25 toolchain →
`options.release = 17`; shadow (`com.gradleup.shadow`) + run-paper; `reflection-remapper` shaded and
relocated under `me.vexmc.asynctnt.lib.*`; `folia-supported: true`; `api-version: '1.17'`. Group /
package `me.vexmc.asynctnt`. Integration matrix `1.17.1, 1.18.2, 1.19.4, 1.20.6, 1.21.4, 1.21.11,
26.1.2` (Folia run added for ≥1.19.4).

| Module | Path | Purpose | Compiles against |
| --- | --- | --- | --- |
| `:api` | `api/` | Tiny public surface: `AsyncTntService` (status / owned-count / force-vanilla), observe-only events. Other plugins/anticheat see normal TNT, so this stays minimal. | floor (compileOnly) |
| `:common` | `common/` | **No Bukkit-gameplay deps.** The pure-math physics core (crown jewel, unit-tested with hand-computed pins): `MotionIntegrator`, `ExplosionSolver`, `FallingBlockResolver`; immutable snapshot/result records; the `Scheduling`/`TaskHandle` seam; `Capabilities`, `ServerEnvironment`; deterministic-RNG utils. | floor (compileOnly); JUnit (test) |
| `:core` | `core/` | The plugin: reflective NMS bridge (remapper-routed), the engine orchestrator (snapshot/apply + worker dispatch), takeover hooks, per-version resolvers, `BukkitScheduling`, config, commands. Applies shadow + run-paper; bundles compat output. | floor (compileOnly) |
| `:compat-folia` | `compat/folia/` | `FoliaScheduling` + per-region apply/split helpers; loaded reflectively behind capability detection. | Folia floor (1.20.4) |
| `:tester` | `tester/` | `AsyncTNTTester` plugin: in-server suites + the vanilla-parity oracle harness. Built as `AsyncTNTTester-<ver>.jar`. | floor (compileOnly of api/common/core) |

`core` bundles `compat-folia`'s compiled `sourceSets.main.output` into the shaded jar (never compiles
against it) so its newer-API bytecode rides along but is only linked when the capability is present.

## 5. The engine core

**Bodies.** `TntBody` / `FallingBlockBody` hold the authoritative physics state (position,
`deltaMovement`, fuse/time, falling-block `BlockState`, source/owner, handle to the real entity). The
engine owns the truth; the real entity is a **shadow** synced each tick — so pistons, other
explosions, anticheat, rendering, and other plugins all still see a genuine `PrimedTnt` /
`FallingBlock`.

**Takeover (highest-risk NMS piece).** On spawn of a primed TNT or falling block we (a) register it
as an engine body, (b) **suppress its vanilla per-tick physics**, (c) drive it from the engine.
Recommended mechanism: intercept at spawn (cancellable `EntitySpawnEvent` / NMS add-entity path) and
swap in a thin custom NMS subclass whose `tick()` is a no-op, constructed via the remapper bridge.
**Prototype on 26.1.2 first** (unobfuscated, easiest via `nms-archaeology`), then generalize, with a
documented fallback (neutralize the vanilla instance's motion/fuse each tick) if subclass injection
is unsafe on a version. **Safety invariant: any engine error on a body returns it to vanilla ticking
— never dropped, never duped.**

**Per-tick cycle** — runs on the owning region thread (Folia) / main thread (Paper), via the
`Scheduling` seam:
1. **Snapshot (owning thread):** per owned body, copy its state + the neighborhood block
   **VoxelShapes** and **fluid states** it could touch this tick; for any TNT detonating this tick,
   snapshot the **blast-resistance cube** and **pre-draw the 1352 ray floats** from `level.random`.
   Immutable primitives/POJOs only.
2. **Compute (worker pool):** batch-integrate every body through `MotionIntegrator` (one
   cache-friendly numeric pass) + run `ExplosionSolver` for detonations. **Zero Bukkit access.**
   Emits a result delta: new states, block-break sets, knockback vectors, chain primes, drops,
   effects.
3. **Apply (owning thread, split per owning region on Folia):** write position/velocity/fuse to
   shadow entities; destroy blocks (bucketed per owning region → `RegionScheduler`, guarded by
   `isOwnedByCurrentRegion`); apply knockback (per-entity `EntityScheduler`; players via the vanilla
   `hitPlayers` packet path); spawn chain TNT / drops; emit sound + particles; draw the
   shuffle/decay/sound RNG here in vanilla order (§7).

The pure-math core **is** the project's definition of vanilla, so the test oracle (§9) is this same
code, validated against the decompiled jars and live vanilla fixtures.

## 6. Version abstraction

Capability detection over version-string branching, decided once at enable:
- **Folia:** `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` (only Folia-exclusive marker).
- **Explosion shape:** monolith `Explosion` (≤1.20.6) vs `ServerExplosion` + `ExplosionDamageCalculator` (1.21+) → per-version strategy object.
- **Knockback reduction:** `EXPLOSION_KNOCKBACK_RESISTANCE` attribute (≥1.20.5) vs `ProtectionEnchantment` dampener (≤1.19.4) → version-branched term.

NMS bridge is reflective via `reflection-remapper` (Mojang names first; reobf on ≤1.20.4, no-op
≥1.20.5; memoized; plain-name fallback). Read the real server with `nms-archaeology` (`javap`).
Deltas actually absorbed: `handlePortal()` / `applyEffectsFromBlocks()` added at 1.21 (the latter
carries **stuck-speed / `blockSpeedFactor`**, which affects movement → captured in the snapshot), the
explosion class refactor, and the knockback-resistance branch. All numeric constants are
byte-identical across the range.

## 7. Determinism & RNG

- **Movement & fuse: zero RNG** — deterministic given state + block snapshot.
- **Spawn scatter** (one `nextDouble()*2π` → `±0.02` kick): replayed exactly from `level.random` at
  spawn, or zeroed by the opt-in toggle.
- **Explosion rays:** the **1352 intensity floats pre-drawn from `level.random` at snapshot** and
  replayed off-thread → identical block-break set, **engine-agnostic** (sidesteps the 1.18.2
  `java.util.Random` difference: we consume the real stream, whatever class it is).
- **Shuffle / drop-decay / (≤1.20.6) sound-pitch draws:** consumed at **apply, on the owning thread,
  in exact vanilla order** → drops and `level.random` advancement bit-identical per explosion. **No
  worker thread ever touches `level.random`.**
- **Float-bit fidelity:** exact op/cast order (`(float)x/15f*2f-1f`, decay `0.22500001f`, gravity
  `0.04d`); knockback **accumulated in spawn order** within a tick.

## 8. Config & fork toggles

Atomic immutable `Snapshot` config (Mental-style; warn-and-fallback parsing; YAML-as-docs). Global +
**per-world enable**; a **kill-switch** that instantly un-suppresses bodies back to vanilla ticking;
worker-pool sizing. Fork-fix toggles ship **OFF**, each documented with provenance
(TacoSpigot/PandaSpigot lineage): `zero-spawn-kick`, `fixed-fuse-80`, `deterministic-redstone-order`,
`east-west-collision-fix`.

## 9. Verification gate (the linchpin)

- **Tier 1 — Unit pins (`common`):** hand-computed expectations for the integrator/solver/lander
  against the `legacy-lab` decompiled constants; `parse(empty) == VANILLA` equality pins.
- **Tier 2 — Engine-on vs engine-off parity (centerpiece):** the matrix runs a **deterministic
  cannon fixture** (TNT spawned at exact pos/velocity/fuse, seeded world) **with takeover disabled
  (pure vanilla)** and records trajectory + final block-break set + knockback; then **with takeover
  enabled** and asserts **bit-identical**. Proves 1:1 against the actual server, per version.
  Cross-checked against the `common` oracle (engine wiring == math; math == vanilla).
- **Tier 3 — Matrix gate:** run-paper integration matrix `1.17.1…26.1.2` (+ Folia run ≥1.19.4),
  each writing PASS/FAIL; concurrent `integration-matrix.sh` local gate; boot suite asserts the
  Folia capability + scheduling backend.
- **Tier 4 — Load/integrity (later):** N×1000-TNT stress suites asserting no body lost/duped and TPS
  holds.

## 10. Risks & open questions (explicit implementation tasks)

1. **Takeover viability per version** — prototype on 26.1.2 first, then generalize; documented fallback.
2. **Folia tick-phase coherency** — empirically verify the engine snapshot reflects
   post-fluid/post-redstone block state matching vanilla's entity-tick phase (unverified).
3. **Coverage gaps** — obtain/verify a **1.17.1** jar and the **1.21.5–.8** points (only .4/.11 read directly).
4. **Moonrise "#309" fuse drift** — determine whether our own fuse decrement *sidesteps* the live
   Paper/Folia early-detonation regression (potential selling point) or must match it.
5. **VoxelShape snapshot fidelity** — capture full per-block shapes (slabs/fences/stairs/panes);
   cannon alignment uses sub-block Y.
6. **Stuck-speed / `blockSpeedFactor`** replication for complete movement parity.

## 11. Canonical constants (quick reference; full detail in the research note)

- TNT tick: gravity `0.04d` (before move) → `move(SELF)` (collide Y→X→Z, no step-up) → drag
  `×0.98d` (all axes) → if `onGround` bounce `×(0.7,-0.5,0.7)` → `fuse = getFuse()-1`; `≤0` →
  `explode()` + `discard()`; else `updateInWaterStateAndDoFluidPushing()`. Default fuse `80`.
- Spawn: `d = random.nextDouble()*6.2831854820251465`; `delta = (-sin(d)*0.02, 0.2f, -cos(d)*0.02)`.
- Water push (TNT only, after move): per fluid voxel `push += heightDiff<0.4 ? flow*heightDiff :
  flow`; `push = normalize × 0.014` (lava `0.007` / `0.0023333333333333335`); min-push floor
  `0.0045000000000000005` when `|motX|,|motZ| < 0.003`.
- Explosion (power `4.0f`): **1352 surface rays** (`x|y|z ∈ {0,15}` of `0..15`); dir
  `(float)i/15f*2f-1f` normalized × `0.3`; per-ray `power = 4.0*(0.7f + nextFloat()*0.6f)`; step
  decay `power -= (resistance + 0.3f)*0.3f` then `power -= 0.22500001f`; collect while `power>0`.
  Damage `((d1²+d1)/2)*7.0*8.0+1` with `d1=(1-dist/8)*seen`. Knockback `(1-dist/8)*seen*1.0*(1-kbRes)`
  along (target − center), **TNT victims use feet/`position()`**, players via packet. Drop yield
  `1.0/4.0 = 0.25`. Falling blocks: same `0.04`/`0.98`, **no water push**, land→place-or-drop,
  knockback from **eye**.
