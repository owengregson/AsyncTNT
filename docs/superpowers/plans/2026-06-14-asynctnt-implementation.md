# AsyncTNT Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Folia-native (Paper + Folia) plugin for MC 1.17.1→26.1.x that ticks primed TNT and falling blocks on a worker pool (snapshot→compute→apply) while replicating vanilla physics bit-for-bit per version.

**Architecture:** One universal jar compiled against the 1.17.1 floor API; a pure-math physics core in `:common` (unit-tested, the parity oracle); a reflective NMS bridge + off-thread engine in `:core`; Folia scheduling in `:compat-folia` (loaded reflectively); an in-server `AsyncTNTTester` proving engine-on==engine-off per version. Mirrors the Mental/StrikeSync project at `/Users/owengregson/Documents/StrikeSync` — **that repo is the canonical pattern for all boilerplate** (gradle layout, `Scheduling` seam, `reflection-remapper` usage, tester harness, integration matrix). Read it; copy/adapt, don't reinvent.

**Tech Stack:** Java 17 bytecode (Java 25 toolchain) · Gradle 9.5.x · Kotlin DSL · `com.gradleup.shadow` · `xyz.jpenilla.run-paper` · `xyz.jpenilla:reflection-remapper` · paper-api 1.17.1 floor · JUnit 5.

**Reference docs (read before coding):**
- Spec: `docs/superpowers/specs/2026-06-14-asynctnt-design.md`
- Research ground-truth (exact constants + RNG order + Folia rules): `docs/research/2026-06-14-tnt-explosion-folia-research.md`
- Mental skills: `/Users/owengregson/Documents/StrikeSync/.claude/skills/{paper-cross-version,nms-archaeology,live-server-testing,matrix-gate,mental-conventions,legacy-motion-physics}/SKILL.md`

**Conventions (from `mental-conventions`):** imports never inline-qualified; records for data with `DEFAULTS` + `withX`; pure math in its own class with hand-computed unit pins; atomic immutable config snapshot; comments explain *why*/provenance; conventional commits with prose bodies; commit as you go. End commit messages with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## File structure (locked-in decomposition)

```
settings.gradle.kts · build.gradle.kts · gradle.properties · gradle/libs.versions.toml · gradle/wrapper/* · gradlew[.bat]

api/        me/vexmc/asynctnt/api/
  AsyncTntService.java         status / ownedCount / isEngineActive / forceVanilla(Entity) / describe()
  event/AsyncTntTakeoverEvent.java   observe-only (engine took over a body)

common/     me/vexmc/asynctnt/common/      (NO Bukkit-gameplay deps; floor compileOnly only)
  scheduling/Scheduling.java, TaskHandle.java          (copy Mental's seam verbatim)
  platform/Capabilities.java, ServerEnvironment.java
  math/Vec3d.java                                       immutable (x,y,z) double vector + ops
  math/Aabb.java                                        immutable bounding box
  snapshot/BodyState.java                               record(kind, x,y,z, dx,dy,dz, fuse, onGround, blockStateId, …)
  snapshot/BlockCollisionView.java (iface)              shapes intersecting an Aabb + blockSpeedFactor + stuck-speed
  snapshot/VoxelShapeData.java                          list of AABBs per block (full fidelity: slabs/fences/stairs/panes)
  snapshot/FluidView.java (iface)                       fluid type + flow vector + height at a cell
  snapshot/BlastResistanceView.java (iface)             resistance at (x,y,z)
  snapshot/EntitySnapshot.java                          record(id, isPlayer, isLiving, x,y,z, eyeY, aabb, kbResist, ignores)
  engine/MotionIntegrator.java                          PURE: BodyState + views + PhysicsProfile + flags -> MotionResult
  engine/MotionResult.java                              record(newState, fuseHitZero)
  engine/ExplosionInput.java                            record(center, power, ResistanceView, float[] rayFloats, List<EntitySnapshot>, PhysicsProfile)
  engine/ExplosionSolver.java                           PURE: ExplosionInput -> ExplosionResult
  engine/ExplosionResult.java                           record(List<long packedPos> broken, List<EntityPush> pushes)
  engine/FallingBlockResolver.java                      PURE: landing decision (place vs drop) given target cell views
  rng/RayFloats.java                                    holds pre-drawn float[1352]; index in CACHED_RAYS order
  rng/SeededRng.java                                    deterministic xoroshiro-ish for shuffle/decay (off-aim only)
  version/PhysicsProfile.java                           record(hasPortalTick, hasBlockEffectsTick, explosionShape, kbResistMode, …)

core/       me/vexmc/asynctnt/
  AsyncTntPlugin.java
  platform/FoliaSupport.java, BukkitScheduling.java, SchedulingFactory.java, Attributes.java
  nms/NmsBridge.java, Reflect.java
  nms/LevelAccess.java, TntAccess.java, FallingBlockAccess.java, ExplosionRng.java
  engine/AsyncTntEngine.java, Snapshotter.java, Applier.java, WorkerPool.java
  engine/body/EngineBody.java
  engine/takeover/Takeover.java, SpawnInterceptor.java
  config/ConfigStore.java, AsyncTntConfig.java, ForkToggles.java
  command/AsyncTntCommands.java
  resources/plugin.yml, config.yml

compat/folia/ me/vexmc/asynctnt/compat/folia/
  FoliaScheduling.java                                  (copy Mental's verbatim)
  FoliaRegionDispatch.java                              split block/entity apply per owning region

tester/     me/vexmc/asynctnt/tester/
  AsyncTntTesterPlugin.java
  harness/{TestCase,TestContext,TestHarness,TestResultWriter}.java   (copy Mental's verbatim)
  fixture/{Arena,Cannon}.java
  suite/{BootSuite,MotionParitySuite,ExplosionParitySuite,FallingBlockSuite,CannonFixtureSuite}.java
  resources/plugin.yml
```

---

## Phase 0 — Scaffold (Task 0)

### Task 0: Gradle multi-module skeleton

**Files:** all root gradle files + empty module `build.gradle.kts` + `plugin.yml`s + one placeholder class per module.

- [ ] **Step 1:** Copy the gradle wrapper from Mental so versions match exactly:
  `cp -r /Users/owengregson/Documents/StrikeSync/gradle/wrapper gradle/ && cp /Users/owengregson/Documents/StrikeSync/gradlew /Users/owengregson/Documents/StrikeSync/gradlew.bat .` then `chmod +x gradlew`.
- [ ] **Step 2:** Write `settings.gradle.kts` (foojay-resolver, paper repo, `rootProject.name="AsyncTNT"`, `include(":api",":common",":core",":compat-folia",":tester")` with `project(":compat-folia").projectDir = file("compat/folia")`).
- [ ] **Step 3:** Write `gradle/libs.versions.toml` adapting Mental's: `paper-floor="1.17.1-R0.1-SNAPSHOT"`, `paper-folia="1.20.4-R0.1-SNAPSHOT"`, `annotations`, `junit`, `reflection-remapper="0.1.3"`, `shadow="9.4.2"`, `run-paper="3.0.2"`.
- [ ] **Step 4:** Write `gradle.properties` with `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.jvmargs=-Xmx2g`, `integrationTestVersions=1.17.1,1.18.2,1.19.4,1.20.6,1.21.4,1.21.11,26.1.2`.
- [ ] **Step 5:** Write root `build.gradle.kts`: `allprojects{group="me.vexmc";version="0.1.0";repositories{...}}`, `subprojects{apply java-library; toolchain 25; release 17; -parameters; UTF-8; useJUnitPlatform}`.
- [ ] **Step 6:** Write each module `build.gradle.kts` (deps per the table in spec §4). `core` applies shadow+run-paper, relocates reflection-remapper under `me.vexmc.asynctnt.lib.*`, bundles `compat-folia` output, `archiveBaseName="AsyncTNT"`, `processResources expand version`. `tester` applies shadow, `archiveBaseName="AsyncTNTTester"`.
- [ ] **Step 7:** Write `core/.../resources/plugin.yml` (`name: AsyncTNT`, `main: me.vexmc.asynctnt.AsyncTntPlugin`, `api-version: '1.17'`, `folia-supported: true`, `version: '${version}'`, command `asynctnt`/`atnt`) and `tester/.../resources/plugin.yml` (`name: AsyncTNTTester`, `depend: [AsyncTNT]`, `folia-supported: true`).
- [ ] **Step 8:** Add one trivial placeholder class per module so each compiles.
- [ ] **Step 9: Verify configure + compile:** Run `./gradlew projects` (expect 5 subprojects) then `./gradlew compileJava` (expect BUILD SUCCESSFUL, downloads paper-api). If offline, note it and proceed — fix in Phase 9.
- [ ] **Step 10: Commit** `chore: scaffold AsyncTNT multi-module gradle build`.

---

## Phase 1 — Common contracts (Task 1)

These are the fixed interfaces/records every later task builds against. No behavior yet (except trivial record bodies). Define them all in one task so the API surface is locked before parallel impl.

### Task 1: Snapshot + math + scheduling contracts

**Files:** every file under `common/.../{scheduling,platform,math,snapshot,engine,rng,version}` listed in the structure map (records + interfaces + empty method bodies / `throw new UnsupportedOperationException` for to-be-implemented pure methods).

- [ ] **Step 1:** Copy `Scheduling.java` + `TaskHandle.java` from `/Users/owengregson/Documents/StrikeSync/common/.../scheduling/` verbatim (repackage to `me.vexmc.asynctnt.common.scheduling`). Methods: `runGlobal`, `runAt(Location)`, `ownsRegion(Location)`, `runOn(Entity,run,retired)`, `runLaterOn`, `runAsync`, `repeatGlobal`, `repeatOn`, `repeatAsync`, `describe`.
- [ ] **Step 2:** Copy `Capabilities.java` + `ServerEnvironment.java` from Mental, adapt: keep `folia` (RegionizedServer marker), `modernSchedulers`, and add `explosionShape`/`kbResistMode` enums derived from class/attribute presence.
- [ ] **Step 3:** Write `Vec3d` (record x,y,z; `add/sub/scale/normalize/length/lengthSqr/multiply`), `Aabb` (record; `expandTowards`, `intersects`, `inflate`).
- [ ] **Step 4:** Write the snapshot records/interfaces (`BodyState`, `VoxelShapeData`, `BlockCollisionView`, `FluidView`, `BlastResistanceView`, `EntitySnapshot`) with javadoc stating exactly what each field/method represents (cite the research note).
- [ ] **Step 5:** Write `PhysicsProfile`, `MotionResult`, `ExplosionInput`, `ExplosionResult`, `RayFloats`, `SeededRng` shells.
- [ ] **Step 6:** Write `MotionIntegrator`, `ExplosionSolver`, `FallingBlockResolver` class shells with the public method signatures (bodies `throw new UnsupportedOperationException`).
- [ ] **Step 7: Verify** `./gradlew :common:compileJava` SUCCESS. **Commit** `feat(common): contracts for snapshot, math, and scheduling seam`.

---

## Phase 2 — Pure-math core, TDD (Tasks 2a–2c, PARALLELIZABLE after Task 1)

Each sub-task is independent (different files), all depend only on Task 1 contracts → **dispatch in parallel**. Each follows strict TDD with hand-computed pins from the research note (`docs/research/2026-06-14-...md` §2, §3, §4). Tests live in `common/src/test/java/...`.

### Task 2a: MotionIntegrator (TNT + falling-block movement)

**Files:** Create `engine/MotionIntegrator.java`; Test `common/src/test/.../engine/MotionIntegratorTest.java`.

Algorithm to implement (research §2.1, §2.4, §2.5) — order: gravity `0.04` (before move) → collide(Y→X→Z, no step-up) → set onGround=(clampedY!=dy && dy<0) → zero horizontally-collided dm components (leave dy) → drag `×0.98` all axes → if onGround bounce `×(0.7,-0.5,0.7)` → fuse `getFuse()-1` → if `>0` and TNT: water push (`0.014`, min-floor `0.0045…`, threshold `0.003`); falling blocks skip water push.

- [ ] **Step 1: Failing test — free-fall pin.** TNT at rest, no blocks, one tick: dy = `(0 - 0.04) * 0.98 = -0.0392`; dx=dz=0; y -= 0.0392... assert exact doubles.
```java
@Test void freeFallOneTick() {
    BodyState s = BodyState.tnt(0,100,0, 0,0,0, 80);
    MotionResult r = MotionIntegrator.tick(s, BlockCollisionView.EMPTY, FluidView.EMPTY, PhysicsProfile.MODERN, ForkFlags.VANILLA);
    assertEquals(-0.0392d, r.state().dy(), 0.0);          // (-0.04)*0.98
    assertEquals(100 - 0.04d, r.state().y(), 1e-12);      // moved by pre-drag dy = -0.04
    assertEquals(79, r.state().fuse());
}
```
  (Note the subtlety: move() uses the *pre-drag* deltaMovement; drag applies after move. Verify against research §2.1 ordering.)
- [ ] **Step 2:** Run `./gradlew :common:test --tests '*MotionIntegratorTest*'` → FAIL (UnsupportedOperationException).
- [ ] **Step 3:** Implement gravity+move(empty)+drag, no collision/fluid yet. Re-run → PASS.
- [ ] **Step 4: Ground-bounce pin.** TNT moving down onto a full block at y=64: assert onGround true, dy after bounce = `(prevDy*0.98)*-0.5`, horizontal `×0.7`. Add collision against `VoxelShapeData` full-cube. Implement collide Y→X→Z. PASS.
- [ ] **Step 5: Water-push pin (TNT).** TNT in a flowing-water column: assert the `0.014`-scaled normalized push added after move; falling-block variant asserts NO push. Implement fluid push. PASS.
- [ ] **Step 6: Determinism pin.** Same input twice → identical bits. PASS.
- [ ] **Step 7: Commit** `feat(common): MotionIntegrator with TNT/falling-block tick pins`.

### Task 2b: ExplosionSolver (1352-ray block selection + damage/knockback)

**Files:** Create `engine/ExplosionSolver.java`; Test `.../engine/ExplosionSolverTest.java`. Algorithm: research §3.2–§3.3.

- [ ] **Step 1: Ray-count pin.** Assert the CACHED_RAYS surface set has exactly **1352** rays and direction `(float)i/15f*2f-1f` normalized × `0.3`.
- [ ] **Step 2:** FAIL → implement ray generation. PASS.
- [ ] **Step 3: Empty-air break pin.** Power 4.0, all-air resistance view, fixed `RayFloats` (all = 0.7 → start power `4.0*(0.7+0.7*0.6)`? no: `4.0*(0.7 + 0.0*0.6)=2.8` if float=0). Compute by hand how far a ray marches in air (`power -= 0.3*0.3` per step? air sentinel `-0.3f` → `(−0.3+0.3)*0.3=0` then `−0.22500001`). Assert the set of broken positions for a known resistance field. Implement march. PASS.
- [ ] **Step 4: Knockback pin.** One `EntitySnapshot` at known offset: assert push vector = `(1-dist/8)*seen*1.0*(1-kbResist)` along `(target-center).normalize()`, TNT uses feet (center.y = entity Y). PASS.
- [ ] **Step 5: Damage pin.** `((d1²+d1)/2)*7.0*8.0+1`. PASS.
- [ ] **Step 6: RNG-replay determinism.** Same `RayFloats` → identical broken set. PASS.
- [ ] **Step 7: Commit** `feat(common): ExplosionSolver with 1352-ray + knockback/damage pins`.

### Task 2c: FallingBlockResolver + RNG utils + PhysicsProfile

**Files:** `engine/FallingBlockResolver.java`, `rng/RayFloats.java`, `rng/SeededRng.java`, `version/PhysicsProfile.java`; Tests `.../engine/FallingBlockResolverTest.java`, `.../rng/RngTest.java`.

- [ ] **Step 1:** Landing pins (research §4): replaceable+canSurvive → place blockStateId; else drop; concrete-powder-in-water → solidify; no water push. Tests first, then implement.
- [ ] **Step 2:** `RayFloats` index ordering pin (nested x,y,z surface order); `SeededRng` determinism pin.
- [ ] **Step 3:** `PhysicsProfile.forVersion(...)` flags pin (1.17 vs 1.21 vs 26.1.2 deltas from research §2.8/§3.7).
- [ ] **Step 4: Commit** `feat(common): falling-block resolver, RNG utils, per-version physics profile`.

**Phase 2 gate:** `./gradlew :common:test` → all PASS.

---

## Phase 3 — Scheduling + detection (Task 3, PARALLELIZABLE with Phase 2)

### Task 3: BukkitScheduling, FoliaScheduling, factory

**Files:** `core/.../platform/{FoliaSupport,BukkitScheduling,SchedulingFactory,Attributes}.java`, `compat/folia/.../FoliaScheduling.java`.

- [ ] **Step 1:** Copy `FoliaSupport`, `BukkitScheduling`, `SchedulingFactory` from Mental's `core/.../platform/`, repackage, adapt to AsyncTNT's `Scheduling` interface. `FoliaSupport.detect()` = `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`.
- [ ] **Step 2:** Copy `FoliaScheduling` from Mental's `compat/folia/` verbatim (it's fully reflective against floor — exactly what we need), repackage.
- [ ] **Step 3:** Write `Attributes` resolver for `EXPLOSION_KNOCKBACK_RESISTANCE` (modern name → legacy fallback → absent), mirroring Mental's `Attributes`.
- [ ] **Step 4: Verify** `./gradlew :core:compileJava :compat-folia:compileJava`. **Commit** `feat: Folia/Bukkit scheduling seam + capability detection`.

---

## Phase 4 — NMS bridge + accessors (Task 4)

### Task 4: Reflective NMS bridge (use nms-archaeology)

**Files:** `core/.../nms/{NmsBridge,Reflect,LevelAccess,TntAccess,FallingBlockAccess,ExplosionRng}.java`.

- [ ] **Step 1:** Copy Mental's `nms/Reflect.java` + the reflection-remapper bootstrap from `NmsBridge`/`FakePlayer` (shared remapper: `forReobfMappingsInPaperJar()` else `noop()`), repackage.
- [ ] **Step 2:** `TntAccess`: read/write `PrimedTnt` position, deltaMovement, fuse, onGround; resolve `PrimedTnt` class + `explode()`; Mojang-name-first via remapper, plain fallback. Verify member names per version with `javap` on `run/<ver>/versions/<ver>/paper-<ver>.jar` (see nms-archaeology). Same for `FallingBlockAccess` (blockState, time).
- [ ] **Step 3:** `LevelAccess`: read block VoxelShapes into `VoxelShapeData`, fluid state/flow into `FluidView`, block explosion-resistance into `BlastResistanceView`; apply setBlock with chosen flags; spawn entity; drop item; playSound/particle.
- [ ] **Step 4:** `ExplosionRng`: on the owning thread, draw N floats from `level.random` (the pre-draw) and the shuffle/decay/sound draws in vanilla order (research §3.5). Resolve `level.random` field reflectively.
- [ ] **Step 5: Verify** `./gradlew :core:compileJava`. **Commit** `feat(nms): reflective bridge for TNT/falling-block/level access + RNG draws`.

> No unit tests here (reflection needs a live server) — covered by the tester (Phase 8). Pin any pure parsing in unit tests if extracted.

---

## Phase 5 — Engine orchestrator + takeover (Task 5)

### Task 5: AsyncTntEngine, Snapshotter, Applier, WorkerPool, Takeover

**Files:** `core/.../engine/{AsyncTntEngine,Snapshotter,Applier,WorkerPool}.java`, `engine/body/EngineBody.java`, `engine/takeover/{Takeover,SpawnInterceptor}.java`, `compat/folia/.../FoliaRegionDispatch.java`.

- [ ] **Step 1: SpawnInterceptor** — listen to `EntitySpawnEvent` for `PrimedTNT`/`FallingBlock`; register an `EngineBody`; suppress vanilla tick via the takeover mechanism (prototype: swap NMS subclass on 26.1.2; fallback: per-tick motion/fuse neutralize). Document the chosen path inline.
- [ ] **Step 2: Snapshotter** (owning thread) — build `BodyState` + `BlockCollisionView`/`FluidView` from `LevelAccess`; for detonating TNT, snapshot `BlastResistanceView` + pre-draw `RayFloats`.
- [ ] **Step 3: WorkerPool** — plugin-owned `ExecutorService` (sized via config); runs `MotionIntegrator`/`ExplosionSolver`; zero Bukkit access; shutdown in onDisable with a fence flag.
- [ ] **Step 4: Applier** (owning thread, per region) — write shadow entity state; destroy blocks bucketed per owning region via `RegionScheduler` guarded by `isOwnedByCurrentRegion`; knockback per-entity via `EntityScheduler` / players via packet; chain primes + drops; draw shuffle/decay/sound RNG here in vanilla order; emit effects. `FoliaRegionDispatch` does the per-region split (no-op on Paper).
- [ ] **Step 5: AsyncTntEngine** — per-entity `repeatOn(entity,1,1,…)` driving; coalesce same-region-same-tick bodies into one compute batch; lifecycle (enable/disable, kill-switch un-suppress). Safety: any body exception → return to vanilla ticking.
- [ ] **Step 6: Verify** `./gradlew :core:compileJava` + `:core:shadowJar`. **Commit** `feat(engine): snapshot->compute->apply engine + spawn takeover`.

---

## Phase 6 — Config, commands, plugin (Task 6, PARALLELIZABLE with Phase 4/5 wiring)

### Task 6: ConfigStore, ForkToggles, commands, AsyncTntPlugin

**Files:** `core/.../config/{ConfigStore,AsyncTntConfig,ForkToggles}.java`, `command/AsyncTntCommands.java`, `AsyncTntPlugin.java`, `resources/config.yml`.

- [ ] **Step 1:** `AsyncTntConfig` immutable snapshot record (global enable, per-world set, worker threads, `ForkToggles` all-false default) + `ConfigStore` atomic `AtomicReference<Snapshot>` swap with warn-and-fallback parsing (mirror Mental's `ConfigStore`). Unit-test `parse(empty) == VANILLA_DEFAULTS` in `common` or `core` test.
- [ ] **Step 2:** `config.yml` heavily commented (YAML-as-docs), fork toggles documented with provenance.
- [ ] **Step 3:** `AsyncTntCommands` (`/asynctnt status|reload|world <on|off>|killswitch`), op-only, plain replies.
- [ ] **Step 4:** `AsyncTntPlugin.onEnable`: detect capabilities + environment, pick scheduling, build config, start engine, register SpawnInterceptor + commands + service; log one structured line `"AsyncTNT <ver> — server <env>, scheduling=<backend>, <caps>"`. `onDisable`: stop engine, un-suppress bodies, shutdown pool.
- [ ] **Step 5: Verify** `./gradlew :core:shadowJar`. **Commit** `feat(core): config snapshot + fork toggles + commands + plugin lifecycle`.

---

## Phase 7 — API (Task 7, PARALLELIZABLE)

### Task 7: AsyncTntService + event

**Files:** `api/.../AsyncTntService.java`, `api/.../event/AsyncTntTakeoverEvent.java`; wire registration in `AsyncTntPlugin`.

- [ ] **Step 1:** Interface methods (`isEngineActive`, `ownedCount`, `forceVanilla(Entity)`, `describe`). Observe-only event.
- [ ] **Step 2:** Implement in core (`AsyncTntManager`), register as a Bukkit service.
- [ ] **Step 3: Verify** compile. **Commit** `feat(api): AsyncTntService facade + takeover event`.

---

## Phase 8 — Tester harness + parity oracle (Task 8)

### Task 8: AsyncTNTTester

**Files:** `tester/.../AsyncTntTesterPlugin.java`, `harness/{TestCase,TestContext,TestHarness,TestResultWriter}.java`, `fixture/{Arena,Cannon}.java`, `suite/*.java`.

- [ ] **Step 1:** Copy Mental's `tester/.../{TestCase,TestContext,TestHarness,TestResultWriter}` verbatim (the watchdog+driver model, `awaitTicks`/`awaitUntil`/`sync`, PASS/FAIL writer). Repackage. Heed `live-server-testing` traps (tick-anchored waits, `-Ddisable.watchdog`, finally-cleanup).
- [ ] **Step 2:** `Arena` (flat platform far from spawn, `doMobSpawning false`, purge Monsters) + `Cannon` (deterministic fixture: place a known cannon schematic, fire by priming TNT at exact positions/fuses, capture final broken-block set + payload landing pos).
- [ ] **Step 3:** `BootSuite` — assert capability/scheduling backend invariants (Folia ⇒ describe=="folia").
- [ ] **Step 4: ParitySuite (centerpiece)** — for a deterministic fixture: run with `forceVanilla` (takeover off) → record trajectory + broken set + knockback; reset world; run with engine on → assert **bit-identical**. Implement via the `AsyncTntService.forceVanilla` toggle + a fixed-seed arena.
- [ ] **Step 5:** `MotionParitySuite`, `ExplosionParitySuite`, `FallingBlockSuite`, `CannonFixtureSuite` (sand/gravel-through-water).
- [ ] **Step 6:** Tester `onEnable` boot→settle(40t)→assemble suite (BootSuite always; Folia ⇒ Boot+Parity smoke; else full)→`TestHarness.run`→write PASS/FAIL→shutdown.
- [ ] **Step 7: Verify** `./gradlew :tester:shadowJar`. **Commit** `feat(tester): AsyncTNTTester harness + engine-on-vs-off parity oracle`.

---

## Phase 9 — Integration matrix + verify (Task 9)

### Task 9: run-paper matrix + green build

**Files:** `core/build.gradle.kts` (matrix block), `scripts/integration-matrix.sh`.

- [ ] **Step 1:** Port Mental's `registerIntegrationServer` block + `requiredJavaVersion` boundary (17 below 1.20.5 else 25) + aggregate tasks (`integrationTest` floor+ceiling, `integrationTestMatrix`) + disable default `runServer`. Inject `AsyncTNT.jar` + `AsyncTNTTester.jar`.
- [ ] **Step 2:** Port `scripts/integration-matrix.sh` (concurrent local gate, per-port, caffeinate, watchdog, verdict verification per `matrix-gate`).
- [ ] **Step 3: Verify unit gate:** `./gradlew build` → BUILD SUCCESSFUL, all `:common` tests PASS.
- [ ] **Step 4: Verify integration (best-effort, sandbox/network permitting):** `./gradlew integrationTest` (floor+ceiling) — read `run/<v>/plugins/AsyncTNTTester/test-results.txt` for PASS per `matrix-gate`. If sandbox blocks server downloads, document and leave for the user's machine.
- [ ] **Step 5: Commit** `build: run-paper integration matrix + local gate script`.

---

## Parallelization map

- Phase 0 → 1 are serial (foundation).
- After Task 1: **Tasks 2a, 2b, 2c, 3 run in parallel** (disjoint files, depend only on contracts).
- After 2/3: **Tasks 4 and 6 (config/commands) can run in parallel**; Task 5 depends on 4; Task 7 can run any time after 1.
- Task 8 depends on 5+6+7; Task 9 last.
- Use `subagent-driven-development`: one subagent per task against fixed contracts; I integrate + compile + fix between waves. Code-mutating parallel tasks touch disjoint files (no worktrees needed since files don't overlap).

## Self-review

- **Spec coverage:** §4 modules→Phase 0/1; §5 engine→Phase 5; §6 version abstraction→Phase 3/4 + `PhysicsProfile`; §7 RNG→Tasks 2b/4 (`ExplosionRng`, `RayFloats`); §8 config/forks→Phase 6; §9 verification→Phase 8/9; §10 risks→called out in Tasks 4/5/8. All covered.
- **Type consistency:** `MotionResult`, `ExplosionInput/Result`, `BodyState`, `PhysicsProfile`, `RayFloats`, `Scheduling` names are used identically across tasks. `forceVanilla(Entity)` appears in api (Task 7) and the parity suite (Task 8) — consistent.
- **Placeholders:** physics pins carry real hand-computed expectations; boilerplate tasks reference exact Mental files to copy. No "TBD".
- **Gap fix:** added `ExplosionRng` (owning-thread RNG draws) to Task 4 explicitly so the §7 RNG order is owned by one class.
```
