# AsyncTNT — Per-version TNT/Explosion physics + Folia threading research

_Dated 2026-06-14. Produced by a 6-agent research workflow (Folia docs + javap/decompiled-jar
extraction from StrikeSync/legacy-lab + SimpleBoxer/StrikeSync run/<ver> server jars + prior-art)._
_Raw per-agent briefs are in `raw/`. This consolidated note is the design ground-truth._

# AsyncTNT — Authoritative Design-Grounding Document

Folia-native plugin (MC 1.17.1 → 26.1.x). Ticks primed TNT on its own worker pool via **snapshot → compute → apply**, replicating vanilla physics 1:1. This document seeds the design spec. All constants are exact; contradictions are resolved inline with the trusted brief named.

---

## 1. Folia rules AsyncTNT MUST obey

**Detection (exactly one allowed method)**
- Detect Folia ONLY via `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`. Do NOT detect by scheduler classes — `io.papermc.paper.threadedregions.scheduler.*`, `EntityScheduler`, `TickRegions` all ship in regular Paper now; only `RegionizedServer` is Folia-exclusive.

**Region ownership (the core invariant)**
- A region = a set of owned chunk positions + one private data object. Exclusive ownership: no chunk is owned by two non-dead regions. Chunks are grouped into power-of-two NxN "region section coordinates"; nearby loaded chunks merge into one independent region with its own tick loop at 20 TPS. There is no main thread; each region is its own "main thread."
- A thread may read/write entity/block/chunk state ONLY for chunks/entities owned by the region it is currently ticking, and ONLY while ticking it. "Regions tick in parallel, not concurrently. They do not share data… sharing of data WILL cause data corruption." Region data "may only be accessed while ticking the region and by the thread ticking the region."
- The **global region** owns: game rules, global game time, daylight time, console command handling, world border, weather, "and others." Fixed 20 TPS, never splits/merges. AsyncTNT almost certainly needs none of this; touch it only via `GlobalRegionScheduler` / guarded by `isGlobalTickThread()`.
- **Owned set is frozen within a single tick body.** A ticking region "cannot expand the chunk positions it owns as it ticks"; merges/splits happen only at tick boundaries. So ownership is stable within one tick, never across ticks → **re-resolve ownership every tick; never cache "which thread owns this."**

**What may run off-thread (hard rule)**
- Off-thread = pure compute on primitives/POJO snapshots ONLY. NEVER touch Block, BlockState, Chunk, World, Entity, or Location-as-mutator from the worker pool. There are NO thread-safe block read APIs (`getBlockAt`/`getType`/`getBlockState`/`setType` are all unsafe off-thread). `getChunkAtAsync` exists for loading, but its callback still runs on the owning region thread, not your worker.

**Snapshot → compute → apply discipline (mandatory)**
1. **Snapshot** on the owning region thread: copy plain data (block types, voxel-shape descriptors, positions, entity positions/AABBs/velocities) into immutable POJOs.
2. **Compute** off-thread: hand snapshot to your `ExecutorService` (recommended — see below) or `AsyncScheduler`; run pure physics on primitives/POJOs only.
3. **Apply** back on the owning region thread, split per owning region.

**Schedulers (exact signatures, from javap of paper-1.21.11)**
- `GlobalRegionScheduler`: `execute(Plugin,Runnable)`, `run(Plugin,Consumer<ScheduledTask>)`, `runDelayed(…,long delayTicks)`, `runAtFixedRate(…,long initialDelayTicks,long periodTicks)`, `cancelTasks(Plugin)`. Global state only.
- `RegionScheduler`: `execute/run/runDelayed/runAtFixedRate(Plugin, World, int chunkX, int chunkZ, …)` plus `Location` overloads. For BLOCK/world edits at a position. **Docs warning: do NOT use for entity ops** (entities move regions).
- `EntityScheduler` (via `entity.getScheduler()`): `boolean execute(Plugin, Runnable run, Runnable retired, long delayTicks)`, `run(Plugin, Consumer run, Runnable retired)`, `runDelayed(…, long delay)`, `runAtFixedRate(…, long initialDelay, long period)`. Task FOLLOWS the entity across region boundaries. If the entity dies/unloads before it runs, `run` is skipped and `retired` fires instead. `run/runDelayed/runAtFixedRate` return `null` if already retired at scheduling time → **always null-check and supply a `retired` callback**.
- `AsyncScheduler`: `runNow(Plugin,Consumer)`, `runDelayed(…,long delay,TimeUnit)`, `runAtFixedRate(…,long initialDelay,long period,TimeUnit)`, `cancelTasks(Plugin)`. Runs off ALL region threads → same no-world-access rule applies.
- Callback type everywhere: `java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>`; `ScheduledTask` exposes `cancel()`/`isCancelled()`.

**Cross-region movement & explosions**
- An entity is owned by the region owning its current chunk; ownership transfers as it crosses chunk boundaries (at tick end).
- Sync `Entity#teleport` is BROKEN on Folia — "will NEVER UNDER ANY CIRCUMSTANCE come back." Use `teleportAsync(Location, TeleportCause, TeleportFlag...) → CompletableFuture<Boolean>` and continue in the future's callback.
- Do NOT drive TNT movement from the worker thread. Schedule per-entity work via `entity.getScheduler().run/runAtFixedRate(...)` so each tick executes on whatever region currently owns the TNT — Folia re-homes the task.
- **Explosions can straddle multiple regions** (blast AABB spans several chunks). You MUST split the result set by owning region:
  - Block destruction: bucket each broken `BlockPos` by chunk → for each owning region, `RegionScheduler.execute(plugin, world, chunkX, chunkZ, applyBlocks)`. Guard every apply with `Bukkit.getServer().isOwnedByCurrentRegion(world, chunkX, chunkZ)` (use the square-chunk-radius overload to validate the blast cube).
  - Entity knockback: bucket by entity → apply each push inside that entity's `EntityScheduler.run(...)`.

**Ownership-check API (javap of CraftServer, all public final)**
`isOwnedByCurrentRegion(Location[, int squareRadiusChunks])`, `(World, Position[, radius])`, `(World, int chunkX, int chunkZ[, radius])`, `(World, int chunkX, int blockY, int chunkZ)`, `(Entity)`, `isGlobalTickThread()`. Note `isPrimaryThread()` now means "is THIS region's tick thread" — do not rely on its old meaning.

**Tick-delay constraints**
- Region/entity/global tick delays must be **>= 1**; a delay of 0 throws `IllegalArgumentException`. Clamp like the in-repo `clampTicks(long t){return Math.max(1,t);}`. AsyncScheduler periods must be >= 1 in their `TimeUnit`.

**Spawns / drops / sounds / particles**
- All mutate world/entity state → run on the owning region thread of the target location. Chain-TNT spawn, item drops, `playSound`, `spawnParticle` → `RegionScheduler` at the location (or the source entity's `EntityScheduler` when chaining). There is NO "async particle" fast path. Player-targeted sound/particle → that player's `EntityScheduler`.

**Worker pool choice (recommendation)**
- Use a **plugin-owned `ExecutorService`** (sized, named, batched for the explosion solve) for compute, re-entering the world only via RegionScheduler/EntityScheduler. Shut it down in `onDisable`; ensure no queued task re-enters the world after unload. `AsyncScheduler` is an acceptable simpler alternative (auto `cancelTasks` on disable) but cedes pool control. Either way: off-thread code touches ZERO Bukkit world objects.

**Lifecycle**: if using your own pool, shut it down in `onDisable` and fence stale tasks from touching the world post-unload.

---

## 2. Vanilla TNT tick — canonical algorithm + per-version deltas

**Confidence: HIGH** (Brief 2 read decompiled 1.21.11 + javap of 1.18.2/1.19.4/1.20.6/1.21.4/1.21.11/26.1.2; 1.8.9 obf baseline). Paper-only lines are flagged and are NOT replicated.

### 2.1 Canonical tick() — modern (1.21.4 / 1.21.11 / 26.1.2)

```
tick():
  # [PAPER-ONLY] spigotConfig.maxTntTicksPerTick cap -> early return (skip)
  handlePortal()                                       # vanilla, added 1.21.x
  applyGravity()                                        # net: motionY -= 0.04
  move(MoverType.SELF, getDeltaMovement())              # collision + position integration
  applyEffectsFromBlocks()                              # vanilla, added 1.21.x (stuck-speed etc.)
  # [PAPER-ONLY] tntEntityHeightNerf -> discard (skip)
  setDeltaMovement(getDeltaMovement().scale(0.98))      # drag, all 3 axes * 0.98d
  if onGround():
      setDeltaMovement(getDeltaMovement().multiply(0.7, -0.5, 0.7))   # ground bounce
  i = getFuse() - 1
  setFuse(i)
  if i <= 0:
      if !level.isClientSide(): explode()
      discard()
  else:
      updateInWaterStateAndDoFluidPushing()             # 26.1.2: updateFluidInteraction()
      if level.isClientSide(): addParticle(SMOKE, x, y+0.5, z, 0,0,0)
```

Exact constants: gravity `0.04d` (`getDefaultGravity()`); drag `0.98d`; bounce `multiply(0.7d, -0.5d, 0.7d)`; default fuse `80` (`DATA_FUSE_ID`/`DEFAULT_FUSE_TIME`). `getGravity()=isNoGravity()?0:getDefaultGravity()`; gravity applied BEFORE move.

### 2.2 Legacy form (1.17.1 / 1.18.2 / 1.19.4)
Same algorithm, but gravity is applied inline (`setDeltaMovement(add(0,-0.04,0))`), NO `handlePortal()`, NO `applyEffectsFromBlocks()`. `onGround` is a field, not a method. Everything else identical.

### 2.3 Legacy baseline (1.8.9 / 1.7.10) — for reference only
Per-axis (not Vec3); `motionY -= 0.03999999910593033` (0.04f); drag per-axis `*0.9800000190734863` (0.98f); bounce `*0.699999988079071`/`*-0.5`. **Post-decrement** `fuse--` ⇒ TNT ticks once at fuse 0 (one extra tick). **NO flowing-fluid push** (only `handleWaterMovement()` detection). Cannon physics fundamentally differ pre-1.13.

### 2.4 move() / collision detail (cannon-critical, stable 1.17→26.1.x)
1. `noPhysics` false for TNT.
2. `vec3 = collide(movement)` — gather block VoxelShapes in `boundingBox.expandTowards(movement)` (+world border + hard entity collisions), resolve **axis-by-axis: Y first, then X, then Z**, each clamped against shapes. Step-up uses `maxUpStep` = 0 for TNT → no stepping.
3. `setPos(position + vec3)`.
4. `horizontalCollision = !Mth.equal(movement.x,vec3.x) || !Mth.equal(movement.z,vec3.z)`; `verticalCollision = movement.y != vec3.y`; `verticalCollisionBelow = verticalCollision && movement.y < 0`.
5. onGround update guarded by `if (Math.abs(movement.y)>0 || isLocalInstanceAuthoritative())`, then `setOnGroundWithMovement(verticalCollisionBelow, horizontalCollision, vec3)` sets `onGround = verticalCollisionBelow`. **onGround becomes true only when there was downward intent (movement.y < 0) that got clamped.**
6. Horizontal zeroing: `if horizontalCollision → setDeltaMovement(collidedX?0:dm.x, dm.y, collidedZ?0:dm.z)`. dm.y NOT zeroed here.
7. dm.y is preserved in deltaMovement until the explicit ground-bounce `*-0.5` in tick(); only the position delta (`vec3.y`) is clamped by collide(). **For 1:1: track dm.y separately; the bounce uses dm, not the clamped vec3.**
8. `blockSpeedFactor` final `dm.multiply(f,1,f)` — f=1.0 normal blocks; soul sand/honey reduce it.

**Replication recipe:** gather block VoxelShapes intersecting `bb.expandTowards(dm)`; resolve Y→X→Z; set `onGround = (clampedY != dm.y && dm.y < 0)`; zero horizontally-collided dm components; leave dm.y untouched (bounce handles it).

### 2.5 Fluid push (cannon-critical, runs AFTER move() in the non-exploding branch)
`updateInWaterStateAndDoFluidPushing()`: clears fluidHeight, water push `updateFluidHeightAndDoFluidPushing(WATER, 0.014)`, lava `updateFluidHeightAndDoFluidPushing(LAVA, ultraWarm?0.007:0.0023333333333333335)`.

Per fluid block in the box deflated by `0.001` whose surface height ≥ box.minY:
```
push += (heightDiff < 0.4 ? flow.scale(heightDiff) : flow)   # flow = FluidState.getFlow()
totalPushes++
if push != ZERO:
    push = push.scale(1.0/totalPushes)
    if not Player: push = push.normalize()                    # TNT normalizes
    push = push.scale(flowScale)                              # 0.014 / 0.007 / 0.00233...
    if (|motX|<0.003 && |motZ|<0.003 && push.length()<0.0045000000000000005):
        push = push.normalize().scale(0.0045000000000000005)  # min push floor
    setDeltaMovement(getDeltaMovement().add(push))
```
Constants identical 1.18.2→26.1.2: water `0.014`, lava `0.007`/`0.0023333333333333335`, threshold `0.003`, floor `0.0045000000000000005`, split `0.4`. In 26.1.2 moved verbatim into `EntityFluidInteraction$Tracker.applyCurrentTo` (named constants, no behavior change). TNT `isPushedByFluid()=true`. **This after-move water push is what propels water-stream cannons.**

### 2.6 Spawn / prime
`PrimedTnt(Level, x, y, z, owner)`: `setPos`; `d = random.nextDouble() * 6.2831854820251465` (2π via float); `setDeltaMovement(-sin(d)*0.02, 0.2f, -cos(d)*0.02)` (0.2f = 0.20000000298023224); `setFuse(80)`. Block-break centers at `x+0.5, y, z+0.5` at the call site. `explode()` → power `4.0f`, fire=false, origin `getY(0.0625)`, `ExplosionInteraction.TNT`. **[Paper]** wraps in cancellable `ExplosionPrimeEvent` + `TNT_EXPLODES` gamerule.

### 2.7 RNG in tick = ZERO
The TNT `tick()` consumes NO randomness in any version. The ONLY nondeterminism is the single `random.nextDouble()` at construction (the launch-angle scatter). The per-tick physics integration is fully deterministic given position + deltaMovement + block-shape snapshot.

### 2.8 Per-version delta table (TNT tick)

| Aspect | 1.8.9 (baseline) | 1.17.1 / 1.18.2 / 1.19.4 | 1.20.6 | 1.21.4 | 1.21.11 | 26.1.2 |
|---|---|---|---|---|---|---|
| Gravity application | inline `motY -= 0.03999999910593033` | inline `add(0,-0.04,0)` | `applyGravity()`/`getDefaultGravity()=0.04d` (refactor) | same | same | same |
| Gravity value | 0.04f-as-double | `-0.04d` | `0.04d` | `0.04d` | `0.04d` | `0.04d` |
| `handlePortal()` in tick | no | **no** | **no** | **YES (added)** | yes | yes |
| `applyEffectsFromBlocks()` | no | **no** | **no** | **YES (added)** | yes | yes |
| Drag 0.98 | per-axis 0.98f | `scale(0.98d)` | `scale(0.98d)` | `scale(0.98d)` | `scale(0.98d)` | `scale(0.98d)` |
| Ground bounce | per-axis 0.7f/-0.5 | `multiply(0.7,-0.5,0.7)` | same | same | same | same |
| onGround | field `C` | field `onGround` | `setOnGroundWithKnownMovement` | `setOnGroundWithMovement` (rename) | same | same |
| Fuse decrement | `fuse--` **post-decrement** | `getFuse()-1` | same | same | same | same |
| Fluid push | **none** (water detect only) | `updateInWaterStateAndDoFluidPushing()` | same | same | same | `updateFluidInteraction()` (rename+moved) |
| Water/lava scale | n/a | 0.014 / 0.007 / 0.00233… | same | same | same | same (named consts) |
| Min-push floor | n/a | 0.0045… (thr 0.003) | same | same | same | same |
| Prime angle RNG | `(float)(Math.random()*πf*2)` | `random.nextDouble()*6.2831854820251465` | same | same | same | same (`getRandom()`) |
| Initial velocity | `(-sinθ*0.02f,0.2f,-cosθ*0.02f)` | `(-sin·0.02,0.2f,-cos·0.02)` | same | same | same | same |
| Default fuse / power | 80 / 4.0f | 80 / 4.0f | 80 / 4.0f | 80 / 4.0f (`explosionPower`, clamp 0-128) | same | same |
| usedPortal-aware explosion | no | no | no | **YES** | yes | yes |

**Real behavior changes (not renames):** (a) gravity inline→`applyGravity()` at 1.20.x (no numeric change); (b) `handlePortal()`+`applyEffectsFromBlocks()` added to tick at 1.21.x (can alter trajectory through portals/stuck-speed blocks); (c) 1.8.x has no flowing-fluid push; (d) 1.8.x `fuse--` gives one extra tick at fuse 0. **All numeric constants (0.04, 0.98, 0.7/-0.5/0.7, 80, 0.02, 0.2, 4.0, 0.014, 0.007, 0.00233, 0.0045) are byte-identical across 1.17→26.1.2.**

---

## 3. Vanilla explosion — canonical algorithm + RNG-consumption order + per-version deltas

**Confidence: HIGH** for 1.18.2–26.1.2 (Brief 3 read source + javap'd bytecode). 1.17.1 jar absent → asserted identical to 1.18.2 (MEDIUM-HIGH). Naming = Mojang (1.21.11/26.1.2).

### 3.1 Entry (TNT, power 4.0)
`level.explode(tnt, damageSource, null, x, getY(0.0625), z, 4.0f, fire=false, ExplosionInteraction.TNT)`. TNT does NOT set fire. `ExplosionInteraction.TNT` + default `TNT_EXPLOSION_DROP_DECAY` gamerule → `DESTROY_WITH_DECAY` ⇒ `yield = 1.0f/radius = 0.25` and drops decay-gated. Center.y = entity Y + 0.0625.

### 3.2 Block ray grid (`calculateExplodedPositions`)
- **CACHED_RAYS:** triple loop `x,y,z ∈ 0..15`, keep cube SURFACE only (`x∈{0,15} ∨ y∈{0,15} ∨ z∈{0,15}`) ⇒ exactly **1352 rays** (16³−14³). Direction per ray:
  ```
  xDir = (float)x/15.0f*2.0f - 1.0f; yDir,zDir similar
  mag  = sqrt(xDir²+yDir²+zDir²)
  step = (xDir/mag*0.3f, yDir/mag*0.3f, zDir/mag*0.3f)   # pre-normalized × 0.3
  ```
  The cast-to-float of `x/15.0f*2.0f-1.0f` (then promoted to double) is **load-bearing for bit-exact parity**.
- **Per-ray starting intensity (one nextFloat per ray, BEFORE marching):**
  ```
  float power = this.radius * (0.7f + this.level.random.nextFloat() * 0.6f);   # power 4.0 ⇒ [2.8, 4.4)
  ```
- **Step march (do/while):**
  ```
  do {
      state = blockAt(floor(currX), floor(currY), floor(currZ))
      power -= (getBlockExplosionResistance(...) + 0.3f) * 0.3f   # air: empty→sentinel -0.3f → term 0
      if (power > 0.0f && shouldBlockExplode(...))                # threshold > 0.0f
          collect pos (if fire || !isAir)
      curr += step                                                # 0.3-scaled dir
  } while ((power -= 0.22500001f) > 0.0f)                          # FLOAT literal, not 0.225
  ```
  Resistance = `max(block.getExplosionResistance(), fluid.getExplosionResistance())` via `EntityBasedExplosionDamageCalculator`. Air sentinel `ZERO_RESISTANCE = -0.3f`.

Order inside `explode()`: `gameEvent(EXPLODE)` → `calculateExplodedPositions()` (ALL per-ray RNG here) → `hurtEntities()` (no RNG) → `interactWithBlocks(list)` → (fire only) `createFire(list)`.

### 3.3 Entity effects (`hurtEntities`) — NO RNG
- Gather: `f = radius * 2.0f` (= 8.0). AABB = center ± (f+1) per axis, floored. Filter alive && !spectator; skip `ignoreExplosion(this)`; skip if `sqrt(distSqr(center))/f > 1.0`.
- Exposure `f1 = getSeenPercent(center, entity)`: per-entity AABB grid, steps `d=1/((maxX-minX)*2+1)` (and Y, Z), lerp sample points + offsets, ray-clip each toward center (`ClipContext.COLLIDER, Fluid.NONE`), `misses/total`. No RNG.
- **DAMAGE** (constant `7.0`, all versions 1.17→26.1.x):
  ```
  d  = sqrt(distanceToSqr(center)) / f            # 0..1
  d1 = (1.0 - d) * seenPercent
  dmg = (float)((d1*d1 + d1)/2.0 * 7.0 * f + 1.0)
  ```
- **KNOCKBACK:**
  ```
  knockResist = (LivingEntity) ? getAttributeValue(EXPLOSION_KNOCKBACK_RESISTANCE) : 0.0
  scalar = (1.0 - d) * f1 * knockbackMultiplier * (1.0 - knockResist)   # knockbackMultiplier default 1.0
  dir    = (targetVec - center).normalize()       # targetVec = PrimedTnt ? position() : getEyePosition()
  entity.push(dir.scale(scalar))                   # push adds into deltaMovement
  ```
  **TNT victims use position() not eye.** Players (non-spectator/non-creative-flying) have their push stored in `hitPlayers` and sent in the explode packet (client applies authoritatively); non-player living entities are `push`ed directly. `onExplosionHit(source)` called last (chains TNT etc. — no RNG for plain TNT).

### 3.4 Block apply (`interactWithBlocks` + `createFire`)
- **1.21.x+ `interactWithBlocks`:** `Util.shuffle(blocks, level.random)` (Fisher–Yates) → fire events/yield → per pos: if TNT-block & `TNT_EXPLODES` → prime it, else `onExplosionHit(...)` → `ApplyExplosionDecay` drops → merged stacks via `Block.popResource`. **Sound + particles emitted later in `ServerLevel.explode0` via `ClientboundExplodePacket` (pitch jitter now client-side, NO server RNG).**
- **≤1.20.6 `finalizeExplosion`:** sound `playSound(GENERIC_EXPLODE, 4.0f, (1.0 + (nextFloat()-nextFloat())*0.2)*0.7)` (**2 server nextFloat**) → particles → `Util.shuffle` → drops → fire.
- **Drop decay:** `ApplyExplosionDecay.run`: `float f = 1.0f/radius; for each of stack.getCount(): if (random.nextFloat() <= f) ++kept;` ⇒ **one nextFloat PER ITEM in every dropped stack** (radius 4.0 ⇒ gate 0.25). Block-survival tables use `ExplosionCondition`: `random.nextFloat() < 1.0f/radius` once per block.
- **createFire (skipped for TNT, fire=false):** `if (level.random.nextInt(3) != 0 || !isAir || !below.isSolidRender()) continue; setBlock(fire)` — one `nextInt(3)` per candidate.

### 3.5 EXACT RNG-consumption order — what to PRE-DRAW on the owning thread

Because the worker pool must NOT touch `level.random` (it is region-owned and shared with everything else, and advancing it off-thread corrupts other systems — Brief 5 pitfall #1), **pre-draw all required randomness on the owning region thread during snapshot**, into a fixed-order list, then replay deterministically off-thread.

**1.21.x / 26.1.x order (TNT, fire=false):**
1. `calculateExplodedPositions`: **1352 × `nextFloat()`** in CACHED_RAYS index order (nested `x=0..15, y=0..15, z=0..15` surface order). Each → `power = radius*(0.7f + r*0.6f)`.
2. `hurtEntities`: **none**.
3. `interactWithBlocks`: `Util.shuffle` Fisher–Yates → for `i = size-1 .. 1`: **`nextInt(i+1)`** (size−1 draws). **Block set & order depend on the 1352 ray floats**, so: pre-draw 1352 ray floats first → run deterministic march to get block set → then pre-draw shuffle ints → then decay floats.
4. Drops (per destroyed block, shuffled order, then loot-pool order): **one `nextFloat()` per item** in each produced stack (`ApplyExplosionDecay`) and/or one `nextFloat()` per block (`ExplosionCondition`). Count = sum of stack counts.
5. `createFire`: **skipped for TNT**. (If it ran: `nextInt(3)` per candidate in list order.)

**≤1.20.6 order — DIFFERENT:** same step 1 (1352 nextFloat), then **2 extra sound-pitch `nextFloat()` between hurtEntities and shuffle** (these vanish in 1.21+, shifting all downstream draws), then shuffle ints, then decay floats, then fire `nextInt(3)`.

**Pre-draw replay strategy:** Pre-drawing the 1352 ray floats up front is trivial. Shuffle and decay draws depend on the computed block set/drop counts, so they cannot all be pre-drawn blindly. Two viable designs (flagged as an open question, §7): (a) **draw 1352 ray floats on the owning thread, snapshot, compute block set off-thread, then bounce a second owning-thread call to draw shuffle+decay and apply**, or (b) **seed a private deterministic RNG per shot** (fork-style) and accept divergence from `level.random` for shuffle/decay only — acceptable since drop-decay and shuffle do not affect cannon aim (they affect only which items drop and in what order), whereas the 1352 ray floats DO affect the destroyed-block set. Trust Brief 5's strong guidance: never advance shared `level.random` off-thread.

### 3.6 Block-update / lighting cost (apply phase)
The dominant cost is NOT ray math — it is per-block `setBlock(pos, AIR, flags=3)` → neighbor `updateShape`/`neighborChanged`, block-entity removal, client block-change packets, and lighting relight. Paper/Moonrise optimizes the ray phase (block/chunk caches, `directMappedBlockCache[512]`, `explosionDensityCache`) but does NOT batch `setBlock`+lighting+neighbor into one bulk op. **Design implication:** do all math/RNG/block-selection off-thread on the snapshot; on apply, use the cheapest flags tolerable (defer/disable per-block light, single region relight, suppress neighbor notifies where safe).

### 3.7 Per-version delta table (explosion)

| Aspect | 1.17.1 | 1.18.2 | 1.19.4 | 1.20.6 | 1.21.4 | 1.21.11 | 26.1.2 |
|---|---|---|---|---|---|---|---|
| Class shape | monolith `Explosion` | monolith | monolith | monolith | `ServerExplosion`+iface | same | same |
| Per-ray `radius*(0.7f+r*0.6f)` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Step 0.3 / decay `0.22500001f` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Resistance `(res+0.3)*0.3`, air `-0.3f` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 1352 surface rays | ✓ | ✓ | ✓ | ✓ (CACHED_RAYS) | ✓ | ✓ | ✓ |
| Damage const 7.0 | ✓ inline | ✓ inline | ✓ inline | ✓ calculator | ✓ | ✓ | ✓ |
| Per-ray RNG engine | RandomSource | **java.util.Random** | RandomSource | RandomSource | RandomSource | RandomSource | RandomSource |
| Knockback reduction | `ProtectionEnchantment.getExplosionKnockbackAfterDampener` | same | same | **attribute EXPLOSION_KNOCKBACK_RESISTANCE** (1.20.5) | attribute | attribute | attribute |
| Sound pitch 2× nextFloat in finalize | ✓ server | ✓ | ✓ | ✓ | **removed (client-side)** | removed | removed |
| Sound/particle emission | finalizeExplosion | same | same | same | **ServerLevel.explode0 / ClientboundExplodePacket** | same | same |
| Drop decay | `dropBlockAsItemWithChance(1/radius)` | `ApplyExplosionDecay` | `ApplyExplosionDecay` | same | same | same | same |
| Drop-decay gamerule split | single | single | single | single | **TNT/BLOCK/MOB_EXPLOSION_DROP_DECAY** | ✓ | ✓ |
| `yield = 1/radius` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ (+isFinite guard) | ✓ |
| `hitPlayers` packet knockback | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

**Real behavior changes vs renames:** REAL → (1) 2 sound-pitch nextFloat removed in 1.21 (shifts downstream draws); (2) 1.18.2 uses `java.util.Random` for per-ray (different stream, same algorithm) — **note this contradicts the "RandomSource" entry elsewhere; trust the explicit 1.18.2 javap finding: 1.18.2 per-ray RNG = `java.util.Random`**; (3) knockback-resistance → attribute in 1.20.5; (4) drop-decay split into per-source gamerules in 1.21. RENAMES → `Explosion`→`ServerExplosion` (1.21), `Effect`→`BlockInteraction` (1.20), damage math relocated to calculator (1.20, value unchanged), `finalizeExplosion`→`interactWithBlocks`+`createFire` (1.21).

**Core constants identical across all seven:** per-ray `radius*(0.7f+nextFloat*0.6f)`; step 0.3; decay `0.22500001f`; resistance `(res+0.3f)*0.3f`; air sentinel `-0.3f`; threshold `power>0.0f`; entity radius `2*radius`; damage `((d1²+d1)/2 * 7.0 * 2r + 1)`; gate `1.0f/radius`; fire `nextInt(3)`.

---

## 4. Falling-block summary (brief)

**Confidence: HIGH** (Brief 4; 1.18.2→26.1.2 read; 1.17 inferred). `FallingBlockEntity` matters because falling-block payloads share TNT flight behavior in cannons.

- **Constants (identical 1.18.2→26.1.2):** gravity 0.04 (applied first), drag 0.98 (applied LAST every tick), ground/land damp `(0.7,-0.5,0.7)` (single frame, **no bounce/restitution**), despawn `>100` ticks if outside world height OR `>600` ticks unconditionally (only while airborne, if `autoExpire`), default `fallDamageMax=40`, `fallDamagePerDistance=0.0`, setBlock flags `3`. Terminal velocity ≈ `0.04*0.98/(1−0.98) = 1.96` blocks/tick.
- **tick() order:** air-check→discard; `++time`; `applyGravity()`; `move(SELF)`; `applyEffectsFromBlocks()` (added 1.21.2); `[Paper] fallingBlockHeightNerf`; `handlePortal()` (1.20.x); on ServerLevel: concrete-powder-in-water fast clip; if airborne → despawn check only; else `setDeltaMovement(multiply(0.7,-0.5,0.7))` + landing logic; despawn; **drag `*0.98` last**.
- **Landing:** place if target `canBeReplaced` && `canSurvive` (`isFree(below)` blocks resting), else drop as item (gated by `dropItem` && `doEntityDrops`). Concrete-powder solidifies in water; anvil sets `hurtsEntities(2.0f, 40)`.
- **Fluids — sand/gravel is NOT pushed by water current.** `FallingBlockEntity.tick()` fully overrides tick() and never calls `super.tick()`/`baseTick()`, so `updateInWaterStateAndDoFluidPushing()` is never invoked. Water only matters for waterlogging-on-place, concrete solidification, and collision (water is `isFree` → block falls through it). **Water does not deflect/aim falling blocks** — important: this DIFFERS from primed TNT, which IS pushed by water.
- **Explosions:** treated as a generic entity (no `ignoreExplosion`/knockback override). Knockback `push(vec)` adds straight into deltaMovement, direction from `(eyePos − center).normalize()` (falling block uses eye, NOT feet — unlike TNT). No explosion damage (non-living).
- **RNG:** tick/movement/placement/despawn = **NONE** (fully deterministic). Only RNG is anvil degradation in `causeFallDamage()` (`nextFloat() < 0.05f + ceil*0.05f`), irrelevant to sand/gravel.
- **Deltas:** gravity form (inline ≤1.19.4 vs `getDefaultGravity()` 1.20.5+, value unchanged); `applyEffectsFromBlocks()` added 1.21.2; `handlePortal()` 1.20.x; `spawnAtLocation` gained leading `ServerLevel` param (1.21.x); concrete-powder water-clip fast-path (modern). All load-bearing physics invariant across 1.17→26.1.x.

---

## 5. Cannon fidelity requirements

**Confidence: HIGH** (Brief 5, corroborated by Briefs 2–4 source-level constants).

### Must be BIT-EXACT
1. **Spawn velocity scatter.** Vanilla: `motY=+0.20`, `motX/motZ = ±0.02` in a random direction from one `random.nextDouble()*2π`. This single RNG call is the primary determinism hazard. Cannon forks ZERO it (`motX=motZ=0`). Either replicate the exact draw on the owning thread, or expose a config to zero it (fork-proven behavior).
2. **Per-axis collision resolution order Y→X→Z** (§2.4). Forks add a Z-before-X correction when `|dx| > |dz|` (TacoSpigot `fix-east-west-cannoning`) — without exact-matching this, east/west vs north/south cannons land differently.
3. **Explosion knockback math** (§3.3): `(1 − dist/(2·power)) · exposure · knockbackMultiplier · (1 − knockResist)`, direction from center to **TNT feet/center** (not eye), `push` adds into deltaMovement. **Knockback accumulates within a single tick across multiple explosions** — multi-charge cannons depend on this summation order.
4. **The 1352-ray block-break floats** (§3.2): they determine the destroyed-block set, so off-thread replay must use the exact pre-drawn values in CACHED_RAYS order with the float-cast direction math.
5. **Fuse values.** Redstone/fire prime = fixed **80**. Explosion-primed TNT = random **10–30** (`nextInt(fuse/4) + fuse/8`-style per forks). Chain-detonation timing is RNG-dependent and a major desync source; preserve per-TNT fuse values and chain ordering.
6. **Water push timing** (§2.5): the after-move `0.014` water-current push must occur on the same tick-phase as vanilla, or water-stream cannon payloads scatter. (Falling-block payloads do NOT get this push — §4.)
7. **Float reproducibility:** identical operation order/summation order; strictfp-equivalent care. Engine-vs-vanilla op-order divergence → last-bit drift → cumulative aim error over a long flight.

### What timing drift breaks aim
- **Off-by-one fuse decrement** → detonates inside the cannon or overshoots (this is the live Moonrise "TNT explodes a few tenths early" regression, Folia/Paper #309 — a real current risk when the tick/scheduler engine changes; AsyncTNT must match the `getFuse()-1` post-move decrement exactly).
- **Wrong entity iteration / knockback-accumulation order** → wrong summed vector. Vanilla ticks entities in **spawn order**; the per-entity sequence is move→fuse→explode.
- **Cross-region 1-tick serialization** (Folia): a high-velocity payload crosses region boundaries every few ticks; marshalling cross-region blast/knockback onto the target region's thread delays by a tick and drifts fuse/impact timing.
- **Block-snapshot staleness:** if redstone/water/piston mutate the world mid-tick after the engine snapshotted, TNT collides against a stale world. Need a tick-coherent block view ordered AFTER water-flow/redstone updates.
- **Anti-lag despawns** (`tnt-entity-height-nerf`) silently deleting in-flight payloads.

---

## 6. Prior-art lessons + concrete pitfall list (each with mitigation)

**Prior-art lessons (Brief 5):**
- "Async TNT" in the market (FluxSpigot) is **packet suppression, not off-thread sim** (hides TNT client-side, comments out particles/broadcast) → desync, "cannons not working." Lesson: real off-thread simulation is unprecedented in this space; correctness must be proven against vanilla, not assumed.
- **SulphurSpigot (TacoSpigot lineage) REMOVED `optimize-explosions`** (patch 0027) to restore aim parity, and made cannon fixes unconditional (0024 movement, 0025 east-west, 0028 always-fix). Lesson: caches that change results break cannons.
- Cannon fixes are deterministic-aim hacks: `motX=motZ=0`, fuse=80, knockback origin=`length/2`, fixed redstone direction order (W,E,N,S,D,U), velocity-preserving water check. AsyncTNT must reproduce vanilla exactly AND optionally offer these fork modes.

**Pitfall list (each with mitigation):**

1. **RNG corruption/divergence.** Sharing/forking `level.random` off-thread is non-deterministic AND corrupts the shared sequence other systems depend on (Randar shows reused `java.util.Random` is observable). → **Mitigation:** never advance shared world RNG off-thread; pre-draw the 1352 ray floats on the owning thread; for shuffle/decay use either a bounce-back owning-thread draw or a private seeded per-shot RNG. Optionally zero spawn kick + fix fuse (fork-proven).
2. **Intra-tick ordering vs other entities.** Off-thread can reorder spawn-order iteration, the move→fuse→explode sequence, and knockback accumulation → aim drift. → **Mitigation:** reproduce a stable vanilla-equivalent ordering for ALL entities the blast touches; accumulate knockback per-tick in spawn order.
3. **Per-axis movement asymmetry.** → **Mitigation:** replicate Y→X→Z with the `|dx|>|dz|` correction bit-for-bit.
4. **Block-snapshot staleness.** → **Mitigation:** tick-coherent block view captured AFTER water-flow/redstone; define ordering between fluid/redstone updates and engine movement; never read blocks concurrently with main-thread edits.
5. **Folia cross-region.** Blast/fast payload spanning two regions violates single-owner. → **Mitigation:** split results per owning region (§1), `teleportAsync` for crossings, marshal cross-region effects via the target region's scheduler, accept/measure the 1-tick serialization cost.
6. **Chain-detonation ordering.** Off-thread batching can collapse staggered 10–30 fuses → whole stack detonates one tick (the #309 class). → **Mitigation:** preserve per-TNT fuse values and chain spawn order; pre-draw each chained fuse on the owning thread.
7. **Knockback to non-engine entities.** Mutating a player/mob velocity off-thread → thread-safety violation + anticheat conflict. → **Mitigation:** apply ALL effects on non-engine entities and all add/remove on the owning region thread; for players, use the vanilla `hitPlayers` packet path.
8. **Anticheat reactions.** Velocity applied a tick off / off-thread shifts packet ordering → false fly/speed flags. → **Mitigation:** emit `PlayerVelocityEvent`/velocity packets on the owning thread in vanilla order/timing.
9. **Duplication.** Deferred `addEntity`/`removeEntity` or out-of-band detonation → TNT in two states → dupes. → **Mitigation:** make spawn/remove and drop-resolution atomic w.r.t. the owning-region entity list.
10. **Anti-lag despawns deleting payloads.** `tnt-entity-height-nerf` / entity-tick-range can delete or freeze in-flight payloads (lazy-chunk #3051). → **Mitigation:** the engine owns/keeps-alive in-flight payloads regardless of view/tick-range.
11. **Optimization caches that change results.** Paper `optimize-explosions` density cache keyed on float positions diverges from vanilla. → **Mitigation:** do not cache exposure/knockback across explosions unless inputs are provably identical; prefer per-explosion fresh computation (SulphurSpigot lesson).
12. **Float reproducibility across threads/JIT.** → **Mitigation:** strict identical float ops and summation order; match the float-cast intermediates exactly (e.g. ray direction `(float)x/15.0f*2.0f-1.0f`, gravity `0.04d`, decay `0.22500001f`).

---

## 7. Open questions / contradictions / low-confidence facts to verify

**Resolved contradictions (decision + reason):**
- **Per-ray RNG engine in 1.18.2.** Brief 3's delta table lists per-ray RNG as `RandomSource` generally but explicitly flags **1.18.2 = `java.util.Random`** (from direct javap). **Trust the explicit 1.18.2 javap finding** — it is a direct bytecode read, more authoritative than the summary row. Implication: the per-ray float STREAM differs on 1.18.2 (same nextFloat algorithm, different engine class). Verify on a real 1.18.2 jar before relying on cross-version RNG replay there.
- **Knockback origin for TNT vs eye.** Brief 1 (§5) and Brief 3 (§3.3) and Brief 5 (A.2) all agree: PrimedTnt victims use `position()`, falling blocks and other entities use `getEyePosition()`. Brief 4 confirms falling block uses eye. **No real conflict — consistent.**
- **Gravity 0.04 application form.** Briefs 2 and 4 agree (inline ≤1.19.4 → `getDefaultGravity()`/`applyGravity()` 1.20.5+, value unchanged). **No conflict.**
- **Where sound/particle RNG lives.** Briefs agree: 2 server `nextFloat` pitch draws in `finalizeExplosion` ≤1.20.6, removed (client-side) in 1.21+. **No conflict.** This is the single biggest RNG-replay landmine across the version range.

**Open questions / gaps to verify before/during implementation:**
1. **1.17.1 is NOT jar-verified** in any brief (no 1.17.1 jar on disk; inferred from 1.18.2). Both TNT-tick and explosion constants for 1.17.1 are MEDIUM-HIGH confidence. **Action:** obtain a 1.17.1 jar and javap-verify per-ray RNG engine, damage const 7.0, fluid-push constants, and `Effect` enum before claiming 1.17.1 parity. Also verify 1.21.5/.6/.7/.8 (only .4 and .11 were directly examined) — assumed identical to 1.21.4/.11 but unconfirmed.
2. **Shuffle + decay RNG cannot be fully pre-drawn blindly** (counts depend on the computed block set/drop counts). Decide between: (a) two-phase owning-thread draw (draw 1352 rays → snapshot → compute block set off-thread → bounce back to owning thread to draw shuffle+decay and apply), or (b) private seeded per-shot RNG for shuffle/decay only (accept divergence from `level.random` for which items drop/order, NOT for the block-break set). Recommendation leans (b) since shuffle/decay don't affect cannon aim — but this changes drop-order/decay outcomes vs vanilla and must be a documented, possibly configurable, parity tradeoff.
3. **Tick-coherency vs Folia region phases.** Briefs establish per-region RNG and independent 20 TPS, but do NOT pin down the exact ordering of water-flow/redstone block ticks vs the EntityScheduler callback within a single region tick on Folia. **Action:** verify empirically that the EntityScheduler TNT callback runs in a phase where the block snapshot reflects post-fluid/post-redstone state matching vanilla's entity-tick phase. This is critical for pitfall #4 and currently UNVERIFIED.
4. **Moonrise "TNT explodes a few tenths early" (#309).** This is a live regression in current Paper/Folia/Moonrise. **Action:** determine whether AsyncTNT (bypassing the vanilla tick for TNT) sidesteps #309 or inherits/reintroduces it. If AsyncTNT drives its own fuse decrement, it must match vanilla `getFuse()-1` semantics exactly and NOT inherit Moonrise's drift — potentially a selling point, but must be tested against a known-good vanilla baseline.
5. **`EXPLOSION_KNOCKBACK_RESISTANCE` attribute availability.** Added 1.20.5. For 1.17.1–1.19.4, knockback reduction comes via `ProtectionEnchantment.getExplosionKnockbackAfterDampener` (different code path). **Action:** the off-thread knockback solver must branch by version for this term; verify exact pre-1.20.5 dampener math before claiming knockback parity on old versions.
6. **Folia version coverage of the 1.17.1→26.1.x range.** Brief 1 confirms Folia exists only for 1.19.4, 1.20.1/.2/.4/.6, 1.21.x, 26.1.x — **Folia does NOT exist for <1.19.4.** So for 1.17.1/1.18.2 targets, the plugin runs on Paper (single main thread), not Folia. **Action:** clarify the design intent — "Folia-native 1.17.1→26.1.x" must mean "Folia where available (≥1.19.4), Paper main-thread fallback below that." The snapshot→compute→apply discipline still applies on Paper (single owning thread), but cross-region splitting is a no-op there.
7. **Whether to expose fork-style cannon-fix modes** (zero spawn kick, fixed fuse, head=length/2, fixed redstone direction order) as config, or replicate strict vanilla only. This is a product decision, not a fact gap, but it determines RNG-handling design (strict vanilla needs the spawn `nextDouble` replicated; fork mode zeroes it).
8. **Block VoxelShape snapshot fidelity.** No brief enumerated exactly which block-shape data must be captured for non-full-cube collision (slabs, fences, stairs, panes) to reproduce `collide()` bit-exactly. **Action:** confirm the snapshot captures full per-block `VoxelShape` (not just "solid/air"), since cannon alignment uses slabs/fences at precise sub-block Y (Brief 5 A.1).

**Overall confidence:** HIGH for the 1.18.2→26.1.2 numeric constants and algorithms (multiple direct source + bytecode reads across all five briefs, mutually consistent). MEDIUM-HIGH for 1.17.1 (inferred) and for the exact Folia tick-phase ordering of the EntityScheduler callback relative to block/fluid ticks (unverified empirically). The Folia threading model, scheduler signatures, and ownership rules are HIGH confidence (official docs + javap).