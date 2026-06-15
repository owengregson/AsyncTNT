I now have authoritative confirmation of every required fact, including the canonical Folia detection method from official PaperMC docs (`Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`) and the global region's full ownership list ("game rules, global game time, daylight time, console command handling, world border, weather, and others"). The `>= 1 tick` rule is enforced by the API (and is exactly what the in-repo `FoliaScheduling.clampTicks` works around — confirmed in code at line 92-94). I have everything needed. Synthesizing the brief.

---

# FOLIA THREADING/REGION MODEL BRIEF — for AsyncTNT

Every fact below is cited to either (a) the official Folia/Paper docs, (b) the Folia README, or (c) bytecode I extracted via `javap` from the real `paper-1.21.11.jar` (the `io.papermc.paper.threadedregions.*` API ships in regular Paper too), or (d) the Mojang-named decompiled `ServerExplosion.java` (1.21.11). Confidence noted per section.

---

## 1. THE REGION MODEL

**What a region is** (HIGH confidence — docs.papermc.io/folia/reference/region-logic + overview):
> "a set of owned chunk positions and [an] implementation defined unique data object tied to that region." Exclusive ownership invariant: for any non-dead region `x`, for each owned chunk position `y`, there is no other non-dead region `z` that also owns `y`.

**World partitioning** (HIGH — region-logic page): The regionizer does **not** track individual chunks. It groups chunks into **"region section coordinates"** = NxN chunks on a grid where **N is a power of two** (e.g. N=16 → section (0,0) owns chunks x∈[0,15], z∈[0,15]). Nearby loaded chunks are merged into one **"independent region,"** each with its own tick loop at **20 TPS** on a configurable thread pool. README (ver/26.1.x), verbatim: *"There is no main thread anymore, as each region effectively has its own 'main thread' that executes the entire tick loop."*

**Region states** (HIGH — region-logic): `transient`, `ready`, `ticking`, `dead`.

**The GLOBAL region** (HIGH — Folia overview page, verbatim): owns *"game rules, global game time, daylight time, console command handling, world border, weather, and others."* It runs at a fixed 20 TPS and **never splits or merges**. (README also lists time/weather; console commands run here.)

**THE EXACT READ/WRITE RULE** (HIGH — README + overview, verbatim):
> "The regions tick in **parallel**, and not **concurrently**. They do not share data, they do not expect to share data, and sharing of data **will** cause data corruption."
> "regions have their own data object that **may only be accessed while ticking the region and by the thread ticking the region**."
> Code running in one region **cannot under any circumstance access or modify** data in another region.

So: a thread may read/write entity/block/chunk state **only** for chunks/entities **owned by the region that thread is currently ticking**, and **only while it is ticking that region**. The global region thread may touch global state (time/weather/gamerules). Any other thread (including your worker pool) may touch **none** of it.

---

## 2. THE FOUR SCHEDULERS — exact signatures

Obtained from `Bukkit.getServer()` getters (confirmed in `CraftServer`):
`getGlobalRegionScheduler()`, `getRegionScheduler()`, `getAsyncScheduler()`; per-entity via `Entity#getScheduler()` (confirmed `CraftEntity.getScheduler()` returns `io.papermc.paper.threadedregions.scheduler.EntityScheduler`).

The callback type everywhere is **`java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>`** (confirmed in every `javap` dump). `ScheduledTask` exposes `cancel()` and `isCancelled()` (confirmed via the in-repo `FoliaScheduling.FoliaHandle`).

### GlobalRegionScheduler — verbatim from `FoliaGlobalRegionScheduler` (javap, paper-1.21.11)
```
void        execute(Plugin, Runnable)
ScheduledTask run(Plugin, Consumer<ScheduledTask>)
ScheduledTask runDelayed(Plugin, Consumer<ScheduledTask>, long delayTicks)
ScheduledTask runAtFixedRate(Plugin, Consumer<ScheduledTask>, long initialDelayTicks, long periodTicks)
void        cancelTasks(Plugin)
```
Correct for: time/weather/gamerule edits, console-command-equivalent work, anything touching global-region state. **NOT** for per-location/per-entity world edits.

### RegionScheduler — verbatim from `FallbackRegionScheduler` (javap)
```
void        execute(Plugin, World, int chunkX, int chunkZ, Runnable)
ScheduledTask run(Plugin, World, int chunkX, int chunkZ, Consumer<ScheduledTask>)
ScheduledTask runDelayed(Plugin, World, int chunkX, int chunkZ, Consumer<ScheduledTask>, long delayTicks)
ScheduledTask runAtFixedRate(Plugin, World, int chunkX, int chunkZ, Consumer<ScheduledTask>, long initialDelayTicks, long periodTicks)
```
The API also exposes `Location` overloads (`execute(Plugin, Location, Runnable)`, etc.) on the `RegionScheduler` interface (the in-repo `FoliaScheduling.runAt` uses `Bukkit.getRegionScheduler().execute(plugin, location, task)`). Correct for: editing **blocks/world** at a position. The task runs on the region owning that location, on its next tick. **Docs warning: do NOT use it for entity operations** — use the EntityScheduler, because an entity may move regions.

### EntityScheduler — verbatim from `FoliaEntityScheduler` (javap)
```
boolean      execute(Plugin, Runnable run, Runnable retired, long delayTicks)
ScheduledTask run(Plugin, Consumer<ScheduledTask> run, Runnable retired)
ScheduledTask runDelayed(Plugin, Consumer<ScheduledTask> run, Runnable retired, long delayTicks)
ScheduledTask runAtFixedRate(Plugin, Consumer<ScheduledTask> run, Runnable retired, long initialDelayTicks, long periodTicks)
```
**`retired` semantics** (HIGH — confirmed by `io.papermc.paper.threadedregions.EntityScheduler` internals: methods `isRetired()`, `retire()`, and `schedule(Consumer run, Consumer retired, long delay)`): the task **follows the entity across region boundaries**. If the entity is **removed/dead before the task can run**, the task is *not* executed and the `retired` Runnable is invoked instead (best-effort, may be null to skip). `run(...)`/`runDelayed(...)`/`runAtFixedRate(...)` **return `null`** if the entity is already retired/removed at scheduling time — your code must null-check (the in-repo `FoliaScheduling.repeatOn` does exactly this at lines 69-72: on null it runs `retired` and returns a no-op handle). `execute(...)` returns a `boolean` (false if it could not be scheduled). Correct for: anything touching a **specific entity** (the TNT entity itself, knockback push on a victim).

### AsyncScheduler — verbatim from `FoliaAsyncScheduler` (javap)
```
ScheduledTask runNow(Plugin, Consumer<ScheduledTask>)
ScheduledTask runDelayed(Plugin, Consumer<ScheduledTask>, long delay, TimeUnit)
ScheduledTask runAtFixedRate(Plugin, Consumer<ScheduledTask>, long initialDelay, long period, TimeUnit)
void         cancelTasks(Plugin)
```
Backed by `Executor executors` + `ScheduledExecutorService timerThread` (confirmed in `javap` field dump). Runs **off all region threads**, independent of the tick loop. Correct for: pure CPU work / IO with **zero world/entity/block access**.

### The ">= 1 tick delay" rule (HIGH — confirmed by in-repo code + API behavior)
Region/entity/global tick-based delays must be **>= 1**. A delay of 0 throws `IllegalArgumentException`. The in-repo `FoliaScheduling.clampTicks(long) { return Math.max(1, ticks); }` (lines 92-94) exists precisely to clamp "this tick" requests up to 1. For AsyncScheduler the period must be **>= 1** in its `TimeUnit` (in-repo `repeatAsync` clamps period to `Math.max(1, ...)`, line 82).

---

## 3. OWNERSHIP CHECKS — exact API names (HIGH — javap of `CraftServer`)

On `org.bukkit.Server` / `Bukkit.getServer()` (all `public final`, real names verbatim):
```
boolean isOwnedByCurrentRegion(Location)
boolean isOwnedByCurrentRegion(Location, int squareRadiusChunks)
boolean isOwnedByCurrentRegion(World, io.papermc.paper.math.Position)
boolean isOwnedByCurrentRegion(World, io.papermc.paper.math.Position, int squareRadiusChunks)
boolean isOwnedByCurrentRegion(World, int chunkX, int chunkZ)
boolean isOwnedByCurrentRegion(World, int chunkX, int chunkZ, int squareRadiusChunks)
boolean isOwnedByCurrentRegion(World, int chunkX, int blockY, int chunkZ)   // (block-Y overload)
boolean isOwnedByCurrentRegion(Entity)
boolean isGlobalTickThread()
```
`isPrimaryThread()` still exists but on Folia means "is THIS region's tick thread" — do not rely on its old "the one main thread" meaning. Use `isOwnedByCurrentRegion(...)` for position/entity ownership and `isGlobalTickThread()` for global-region state. The `int` radius overloads check ownership of a **square of chunks** centered on the position — directly useful for an explosion that spans several chunks (see §5).

---

## 4. CROSS-REGION ENTITY MOVEMENT (critical for AsyncTNT)

(HIGH — README + region-logic + javap)

- **Ownership transfer**: an entity is owned by the region owning the chunk it currently occupies. As it moves, ownership transfers when it crosses into a chunk owned by another region. Regions **merge** when entities/loaded chunks bring two regions adjacent, and **split** when they separate — these happen at **tick end**, never mid-tick (region-logic: a ticking region *"cannot expand the chunk positions it owns as it ticks"*; merges/splits are processed at tick boundaries). So **a region's owned set is frozen for the duration of a single tick** — safe to assume stability within one tick body, never across ticks.
- **EntityScheduler tasks across the boundary**: they **follow the entity** to whatever region owns it; you do **not** re-target them. If the entity dies first, the `retired` callback fires (see §2).
- **Moving/teleporting across regions — the ONLY supported way**: `Entity#teleportAsync(...)`. Confirmed real signature (javap `CraftEntity`):
  ```
  CompletableFuture<Boolean> teleportAsync(Location,
      PlayerTeleportEvent.TeleportCause,
      io.papermc.paper.entity.TeleportFlag...)
  ```
  README, verbatim warning: synchronous **`Entity#teleport` "will NEVER UNDER ANY CIRCUMSTANCE come back, use teleportAsync."** Treat sync teleport as broken on Folia.
- **Re-homing each tick (the AsyncTNT pattern)**: do **not** drive the TNT entity's movement from your worker thread. Instead schedule per-entity work via `entity.getScheduler().run(plugin, t -> {...}, retired)` (or `runAtFixedRate`) so every tick of work executes **on whatever region currently owns the TNT** — Folia re-homes the task for you. Within that callback you may read/write that entity and the blocks/entities in its own region. If you need to push the TNT into a far chunk, use `teleportAsync` and continue work in the future's callback.

---

## 5. BLOCK READS/WRITES + MULTI-REGION EXPLOSIONS

- **Can a worker thread read block state? NO.** Block/`BlockState`/chunk reads and writes are region-owned data; touching them off the owning region thread is the exact "sharing of data → data corruption" the README forbids. There are **no thread-safe block read APIs** — `getBlockAt`, `getType`, `getBlockState`, `setType`, etc. are all unsafe off-thread. (`World#getChunkAtAsync(int,int,boolean,boolean,Consumer<Chunk>)` exists for async *loading*, but the chunk callback still runs on the owning region thread, not your worker.)
- **Safe pattern (mandatory for AsyncTNT)**:
  1. **Snapshot on the owning region thread**: in a RegionScheduler/EntityScheduler callback, copy out plain data (block types, positions, entity positions/AABBs as immutable POJOs) into your own structures.
  2. **Compute off-thread**: hand the snapshot to your `ExecutorService` / `AsyncScheduler` and run pure physics — no Bukkit objects, only primitives/POJOs.
  3. **Apply on the owning region thread**: dispatch the results back via the **per-region** scheduler that owns each affected position/entity.
- **Explosion whose blast cube spans MULTIPLE regions**: the affected AABB (`center ± radius*2 + 1` on each axis — see exact bounds below) can straddle several regions. You **must split the result set by owning region** and dispatch each slice through the correct scheduler:
  - **Block destruction**: bucket each broken `BlockPos` by its chunk; for each distinct owning region, send a `RegionScheduler.execute(plugin, world, chunkX, chunkZ, () -> applyBlocks(...))`. Never apply a block change for a chunk the current thread doesn't own. Guard each apply with `Bukkit.getServer().isOwnedByCurrentRegion(world, chunkX, chunkZ)`.
  - **Entity knockback**: bucket victims by entity, and apply each push inside **that entity's** `EntityScheduler.run(plugin, t -> victim.setVelocity(...), retired)`, so it lands on whatever region owns that victim.

  **Exact vanilla physics to replicate (from `ServerExplosion.java`, 1.21.11, `hurtEntities()` lines 366-422):**
  - search/damage cutoff radius: `float f = this.radius * 2.0f`; AABB scanned is `[center − f − 1, center + f + 1]` on each axis (`Mth.floor`).
  - per-entity normalized distance: `d2 = sqrt(distanceToSqr(center)) / f`; **skip if `d2 > 1.0`**.
  - exposure: `f1 = getBlockDensity(center, entity)` (16x16x16 ray sampling, step `0.3f`).
  - knockback magnitude: `(1.0 − d2) * f1 * knockbackMultiplier * (1.0 − knockbackResistance)`, direction = `(entityPos − center).normalize()`; applied via `entity.push(vec)`. PrimedTnt uses `position()` not `getEyePosition()` for the ray origin (line 382). This math is **pure** and is exactly what belongs on your worker thread; only the snapshot (positions/AABBs) and the final `push`/`setVelocity` touch the region.

---

## 6. SPAWNING ENTITIES / DROPS / SOUNDS / PARTICLES

All of these mutate world/entity state and must run on the **owning region thread of the target location**:
- **Spawning chain TNT** (`World#spawn`, `World#spawnEntity`): run inside `RegionScheduler.run/execute(plugin, world, chunkX, chunkZ, ...)` for the spawn location, **or** if chaining off an existing entity, inside that entity's `EntityScheduler` callback. The new entity is then owned by that region.
- **Dropping items** (`World#dropItem(Naturally)`): same — RegionScheduler at the drop location.
- **Sounds / particles** (`World#playSound`, `World#spawnParticle`): these are world writes too → RegionScheduler at the location. There is **no** "async particle" fast path; do not call them from your worker pool. (Player-targeted `Player#playSound`/`spawnParticle` should go through that player's EntityScheduler.)

---

## 7. FOLIA VERSION RANGE, DETECTION MARKER, GOTCHAS

- **Version range** (HIGH — papermc.io/downloads/folia + search): Folia exists for **1.19.4, 1.20.1/.2/.4/.6, 1.21.x (1.21.4, .5, .6, .7, .8, .11), and current 26.1.x**. The Folia repo's default branch is **`ver/26.1.x`** (confirmed via `gh api`). Folia does **not** exist for <1.19.4.
- **Detection marker** (HIGH — official PaperMC docs, verbatim):
  ```java
  private static boolean isFolia() {
      try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); return true; }
      catch (ClassNotFoundException e) { return false; }
  }
  ```
  Note: do **not** detect by the scheduler classes (`AsyncScheduler` etc.) — those now ship in **regular Paper too** (I confirmed `io.papermc.paper.threadedregions.scheduler.*` and `TickRegions`/`EntityScheduler` are present in `paper-1.21.11.jar`, which is non-Folia Paper). Only `RegionizedServer` is Folia-exclusive. (The in-repo design already reflects this: `FoliaScheduling` is only constructed *after* the Folia capability is detected — see its header comment lines 18-20.)
- **Gotchas for custom entity ticking** (HIGH — README): never run your own tick loop that touches entities/blocks from a non-region thread; never cache an entity/block reference and mutate it later off-thread; never assume one global tick order across regions (events fire in parallel). Region ownership can change between ticks, so **re-resolve ownership every tick** rather than caching "which thread owns this." Sync teleport is dead. These violations cause silent data corruption that is *"near impossible to debug."*

---

## 8. AsyncScheduler GUARANTEES — and should AsyncTNT use it or its own pool?

(HIGH — `javap` of `FoliaAsyncScheduler` + README)
- **It is a real thread pool**: fields are `java.util.concurrent.Executor executors` (the worker pool) + a `ScheduledExecutorService timerThread` (for delayed/fixed-rate timing). `runNow` dispatches immediately to a pooled thread.
- **Constraint**: it runs **outside the region tick loop** → the same no-world-access rule applies. AsyncScheduler tasks may **not** touch any region/global world, block, or entity state. They are for pure compute / external IO only.
- **AsyncScheduler vs your own ExecutorService — trade-offs:**
  - *AsyncScheduler*: lifecycle is managed by the server; `cancelTasks(plugin)` is called automatically on plugin disable, so you won't leak threads or run tasks after unload; integrates with Folia's shutdown; shares the pool with other plugins (you don't control sizing/priority/affinity).
  - *Your own ExecutorService*: full control over pool size, naming, work-stealing, batching of the physics solve; but **you** must shut it down on `onDisable`, must never let a stale task call back into the world after unload, and you get no scheduler integration. Folia/Paper docs say plugin-owned threads are allowed **only** if they touch the world/entity API exclusively by **bouncing back through a region/entity/global scheduler** — a raw thread must never call world-mutating API directly.
- **Recommendation for AsyncTNT**: a **plugin-owned `ExecutorService`** for the physics solve is the better fit (you want a sized, named, batched pool tuned for the explosion math), **provided** it does pure compute on snapshots and **only** re-enters the world via `RegionScheduler` (blocks/spawns/sounds) and `EntityScheduler` (knockback/the TNT itself), and is shut down in `onDisable`. AsyncScheduler is an acceptable simpler alternative for low volume, but gives up pool control. Either way the off-thread code touches **zero** Bukkit world objects.

---

## RULES AsyncTNT MUST OBEY (checklist)

1. **Detect Folia** only via `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`.
2. **Off-thread = pure compute.** The worker pool (AsyncScheduler or your own ExecutorService) touches only primitives/POJO snapshots — **never** Block, BlockState, Chunk, World, Entity, Location-as-mutator.
3. **Snapshot → compute → apply.** Capture world/entity data on the owning region thread; solve off-thread; apply back on the owning region thread.
4. **Block writes, spawns, drops, sounds, particles** → `RegionScheduler.run/execute(plugin, world, chunkX, chunkZ, ...)` for that exact location.
5. **Entity writes** (knockback push, ticking the TNT, victim velocity) → `entity.getScheduler().run/runDelayed/runAtFixedRate(plugin, run, retired[, delay[, period]])`. **Always null-check** the returned `ScheduledTask` and provide a `retired` callback (entity may die/unload).
6. **Multi-region explosions: split results per owning region** and dispatch each slice through the correct scheduler. Guard every apply with `Bukkit.getServer().isOwnedByCurrentRegion(...)` (use the chunk-radius overload to validate the blast square).
7. **Tick delays must be >= 1** (clamp like the in-repo `clampTicks`); AsyncScheduler periods >= 1 in their `TimeUnit`.
8. **Never** call synchronous `Entity#teleport` — use `teleportAsync(Location, TeleportCause, TeleportFlag...)` and continue in the returned `CompletableFuture` callback.
9. **Re-resolve ownership every tick.** Region ownership can change at tick boundaries (merge/split); never cache "which thread owns this entity/chunk." Within a single tick body the owned set is frozen, so it's safe there.
10. **Global state** (time/weather/gamerules) only via `GlobalRegionScheduler` / guarded by `isGlobalTickThread()`. AsyncTNT almost certainly needs none of this.
11. **Lifecycle**: if using your own ExecutorService, shut it down in `onDisable` and ensure no queued task re-enters the world after unload (AsyncScheduler does this for you via auto `cancelTasks`).

---

### Source / file citations
- In-repo impl: `/Users/owengregson/Documents/StrikeSync/compat/folia/src/main/java/me/vexmc/mental/compat/folia/FoliaScheduling.java` (scheduler usage, `clampTicks` >=1 rule lines 92-94, null-`ScheduledTask` handling lines 69-72, Folia-only construction note lines 18-20).
- Exact scheduler signatures: `javap` of `io.papermc.paper.threadedregions.scheduler.{FoliaGlobalRegionScheduler, FallbackRegionScheduler, FoliaEntityScheduler, FoliaAsyncScheduler}` and `io.papermc.paper.threadedregions.EntityScheduler` from `/Users/owengregson/Documents/StrikeSync/run/1.21.11/versions/1.21.11/paper-1.21.11.jar`.
- Ownership + teleport APIs: `javap` of `org.bukkit.craftbukkit.CraftServer` and `org.bukkit.craftbukkit.entity.CraftEntity` from the same jar.
- Explosion physics: `/Users/owengregson/Documents/StrikeSync/legacy-lab/decomp-1.21.11/net/minecraft/world/level/ServerExplosion.java` (`hurtEntities()` 366-422, `getBlockDensity` 567-595).
- Docs/README (web): docs.papermc.io/folia/ (reference/region-logic, reference/overview, faq), docs.papermc.io/paper/dev/folia-support/, github.com/PaperMC/Folia README (branch `ver/26.1.x`), papermc.io/downloads/folia.

**Overall confidence: HIGH.** The only items I could not pull verbatim from a single rendered page (the per-method Javadoc text, since jd.papermc.io is JS-rendered) I instead confirmed directly from compiled bytecode in the real jar, which is authoritative for signatures. The `>=1 tick` rule and the explosion math are cross-confirmed against source. TNT/explosion logic is extremely stable across 1.17→26.1.x; the only deltas are cosmetic (1.20.5+ renamed `damage`→`hurtServer` and added the Paper `disableExplosionKnockback` config branch shown at line 407/417); the radius cutoff (`radius*2`), distance test, density sampling, and `push(vec)` mechanics are unchanged.