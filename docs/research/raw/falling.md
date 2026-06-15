I have everything needed. Here is the complete documentation.

---

# Vanilla `FallingBlockEntity` per-tick algorithm — MC 1.18.2 → 26.1.x

Target: `net.minecraft.world.entity.item.FallingBlockEntity` (Spigot-obf `EntityFallingBlock` pre-1.20.5). All facts cited to the file/jar I read. Confidence is **high** unless noted.

## Sources read
- Decompiled Mojang-named source: `/Users/owengregson/Documents/StrikeSync/legacy-lab/decomp-1.21.11/net/minecraft/world/entity/item/FallingBlockEntity.java` (Paper 1.21.11; this is the primary, fully-readable reference — quoted below).
- `…/world/entity/Entity.java`, `…/world/level/block/{FallingBlock,AnvilBlock,ConcretePowderBlock,Fallable}.java`, `…/world/level/ServerExplosion.java` (same decomp tree).
- `javap -p -c` on real server jars: 26.1.2, 1.21.4, 1.20.6 (Mojang-mapped, real names) and 1.19.4, 1.18.2 (Spigot-obf `EntityFallingBlock.class`) at `…/StrikeSync/run/<ver>/versions/<ver>/paper-<ver>.jar`. 26.1.2 is unobfuscated and verified to match 1.21.11 byte-for-byte in structure/constants.
- No 1.17.x jar exists on disk; 1.17 is inferred from the 1.18.2 model (identical), confidence high but not jar-verified.

## Constants (verified identical in EVERY version 1.18.2 → 26.1.2)
| Constant | Value | Where |
|---|---|---|
| Gravity (per-tick downward accel) | **0.04** | `getDefaultGravity()` returns `0.04` (1.20.5+); inline `add(0,-0.04,0)` in tick (≤1.19.4). javap shows `ldc2_w double 0.04`/`double -0.04d` in all jars. |
| Drag / air resistance multiplier | **0.98** | applied at the very END of tick: `setDeltaMovement(getDeltaMovement().scale(0.98))` (1.21.11 line 256; javap `ldc2_w double 0.98d` in all). |
| Ground/land contact damping | **(0.7, -0.5, 0.7)** | `setDeltaMovement(getDeltaMovement().multiply(0.7,-0.5,0.7))` on contact (line 199; javap 0.7d/-0.5d/0.7d in all). |
| Despawn out-of-range time | **>100 ticks** if below `minY` or above `maxY` | line 191; javap `bipush 100`. |
| Hard despawn time | **>600 ticks** (30 s) | line 191; javap `sipush 600`. |
| Default `fallDamageMax` | **40** | line 83. |
| Default `fallDamagePerDistance` | **0.0** (no fall damage unless block sets it) | line 84. |
| `setBlock` flags | **3** (UPDATE_NEIGHBORS \| UPDATE_CLIENTS) | line 214. |

**No ground bounce.** There is no restitution/bounce term anywhere — on contact it damps to (0.7,-0.5,0.7) for that single frame and then immediately tries to place/drop. Confirmed across all jars.

## tick() order (exact, 1.21.11 / 26.1.2 — lines 158-257)
1. If `blockState.isAir()` → `discard(DESPAWN)`, return. (RNG: none)
2. `block = blockState.getBlock()`
3. `++this.time`
4. **`applyGravity()`** → `deltaMovement.y -= 0.04` (skipped if `isNoGravity()`).
5. **`move(MoverType.SELF, getDeltaMovement())`** — collision/sweep; sets `onGround`, `verticalCollision`, records movement. (Does NOT itself do fluid current pushing — see fluids below.)
6. **`applyEffectsFromBlocks()`** — step-on / inside-block effects (cobweb stuck-speed, etc.). **Added in 1.21.2; NOT present ≤1.20.6.**
7. Paper `fallingBlockHeightNerf` check (Paper-only) → maybe drop item + `discard(OUT_OF_WORLD)`.
8. **`handlePortal()`** (nether/end portal). Added in the 1.20.x line; present 1.20.6+.
9. If `level instanceof ServerLevel` and `isAlive()`:
   - Compute `blockPos = blockPosition()`.
   - Concrete-powder-in-water fast path: if block is `ConcretePowderBlock` and `deltaMovement.lengthSqr() > 1.0`, ray-clip (`ClipContext … Fluid.SOURCE_ONLY`) from old→new pos; if it hits water, snap `blockPos` to that hit and set `flag1=true` (so it solidifies at the water surface instead of tunneling through).
   - **If `!onGround() && !flag1`** (still airborne): only the despawn check (step 10).
   - **Else (landed / hit water)**: `setDeltaMovement(multiply(0.7,-0.5,0.7))`, then landing logic (step 11), unless the block at pos is `MOVING_PISTON` (then do nothing this tick).
10. Despawn: if `time>100 && autoExpire && (y≤minY || y>maxY)` **OR** `time>600 && autoExpire` → optionally `spawnAtLocation(block)` (if `dropItem` and gamerule `doEntityDrops`), `discard(DROP)`.
11. **Landing logic** (lines 200-251), detailed below.
12. **`setDeltaMovement(getDeltaMovement().scale(0.98))`** — drag, applied unconditionally at end every tick.

> Net per-tick vertical model when airborne: `vy = (vy − 0.04) * 0.98` after move; horizontal `vx,vz` only decay by `*0.98` (no other horizontal drag). Terminal velocity ≈ `0.04*0.98/(1−0.98) = 1.96` blocks/tick downward.

## Landing logic (place block vs drop item) — lines 200-251
Given `blockState` (entity's carried state), target `blockPos`, and `blockStateAtPos = level.getBlockState(blockPos)`:
- If `cancelDrop` is true → `discard(DESPAWN)` + `callOnBrokenAfterFall` (no placement, no drop).
- Else compute:
  - `canBeReplaced = blockStateAtPos.canBeReplaced(new DirectionalPlaceContext(level, pos, DOWN, EMPTY, UP))` — is the target replaceable (air, water, grass, etc.).
  - `flag2 = FallingBlock.isFree(level.getBlockState(pos.below())) && (!flag || !flag1)` — i.e. the block **below** is free (air/fire/liquid/replaceable) so it can't rest here (excluding the concrete-in-water case).
  - `canSurvive = blockState.canSurvive(level, pos) && !flag2`.
  - **If `canBeReplaced && canSurvive`** → PLACE:
    - If carried block has WATERLOGGED property and target fluid is WATER, set `WATERLOGGED=true`.
    - `level.setBlock(pos, blockState, 3)`. On success: resend block update to trackers, `discard(DESPAWN)`, call `Fallable.onLand(...)` (concrete→solidify to concrete; anvil→play levelEvent 1031), and restore block-entity NBT (`blockData`) if any.
    - If `setBlock` fails → `discard(DROP)` + `callOnBrokenAfterFall` + `spawnAtLocation(block)` (gated by `dropItem` && `doEntityDrops`).
  - **Else (can't place)** → `discard(DROP)`; if `dropItem` && `doEntityDrops`: `callOnBrokenAfterFall` + `spawnAtLocation(block)` (drops as item).

`FallingBlock.isFree(state)` = `state.isAir() || state.is(FIRE) || state.liquid() || state.canBeReplaced()` (`FallingBlock.java` line 63). `getDelayAfterPlace()` = 2 ticks (the scheduled-tick delay before a placed sand/gravel block turns back into a falling entity).

### Fall-on special behavior
- **Concrete powder**: `ConcretePowderBlock.onLand` → if it touches/lands-in water (`shouldSolidify`), solidifies to concrete (Paper fires `BlockFormEvent`). The tick() fast-path also lets fast-moving concrete powder solidify at the water surface mid-fall.
- **Anvil**: `AnvilBlock.falling()` sets `setHurtsEntities(2.0f, 40)`. `onLand` plays levelEvent 1031 (anvil land sound). `onBrokenAfterFall` plays 1029. Anvil damages entities + may degrade on landing (see below).

## Max fall time / despawn
Two thresholds (lines 191-196): `>100` ticks AND outside world height (`y≤minY || y>maxY`); OR `>600` ticks (30 s) unconditionally — but **only while still airborne** (`!onGround && !flag1`) and only if `autoExpire` (Paper flag, default true). On despawn it drops as item if `dropItem` && gamerule `doEntityDrops`. A landed block never reaches these (it places/drops first).

## Anvil damage to entities — `causeFallDamage()` (lines 266-297)
Only runs if `hurtEntities` (anvils, or NBT-set). Note: this is invoked from `move()`'s fall-damage path, not from tick directly.
- `ceil = Mth.ceil(fallDistance - 1.0)`; if `<0` return.
- Damage `f = min(floor(ceil * fallDamagePerDistance), fallDamageMax)` (anvil: 2.0/block, cap 40).
- Hits all `LIVING_ENTITY_STILL_ALIVE && !creative/!spectator` in its bounding box: `entity.hurt(anvilDamageSource, f)`.
- **Anvil degradation (RNG):** if `blockState.is(ANVIL tag) && f>0 && random.nextFloat() < 0.05f + ceil*0.05f` → `AnvilBlock.damage(state)` (anvil→chipped→damaged→null). If null → sets `cancelDrop=true` (anvil destroyed).

## Fluid interaction — **sand is NOT pushed by water current** (confidence high)
- FallingBlockEntity does **not** override `isPushedByFluid()` (base returns `true`, `Entity.java` 3934) — BUT the water/lava *current push* (`updateInWaterStateAndDoFluidPushing()`, `Entity.java` 998) is invoked only from **`baseTick()`**, and **FallingBlockEntity.tick() fully overrides tick() and never calls `super.tick()`/`baseTick()`** (verified: tick starts at air-check, no `baseTick` invoke in 1.21.11 source or in javap of 26.1.2/1.21.4/1.20.6/1.19.4/1.18.2). So a falling sand/gravel entity gets **no flow-velocity push from water** while falling. Water only matters for: (a) waterlogging on placement, (b) concrete-powder solidification, (c) collision (water is `isFree`, so the block falls *through* it and won't rest on it). Net: **water does not deflect/aim falling blocks** — important for cannon alignment.
- It is NOT buoyant either (no fluid-height buoyancy term in the custom tick).

## Explosions — pushed like any entity (confidence high)
- FallingBlockEntity does **not** override `ignoreExplosion()` (base returns `false`) nor any knockback method (verified via javap on 26.1.2: only overrides are `getDefaultGravity` and `causeFallDamage`).
- `ServerExplosion.hurtEntities()` (lines 376-413) treats it as a generic entity: knockback magnitude `(1 - dist/2r) * blockDensity * knockbackMultiplier`, direction = `(eyePos - center).normalize()`, applied via `entity.push(vec)` which does `setDeltaMovement(getDeltaMovement().add(delta))` (Entity.java 2287). No explosion *damage* (it is non-living; `hurtServer` is a no-op that only marks hurt). So TNT impulse adds straight into deltaMovement, then next tick gravity/drag act on it normally. This is the core of cannon physics: explosion `push` directly mutates the same `deltaMovement` the tick integrates.

## RNG usage
- tick() / movement / placement / despawn: **NO RNG.** Fully deterministic given position, velocity, world state. (Critical for a deterministic cannon-aiming engine.)
- The ONLY RNG in this class is anvil degradation in `causeFallDamage()` (`random.nextFloat()`), which is irrelevant to sand/gravel.
- `FallingBlock.animateTick` uses `random.nextInt(16)` but that is client particle-only, not server tick.

## Per-version delta table
| Version | Gravity mechanism | `applyEffectsFromBlocks()` in tick | `handlePortal()` in tick | `spawnAtLocation` sig | 0.04 / 0.98 / (0.7,-0.5,0.7) / 100 / 600 | Notes |
|---|---|---|---|---|---|---|
| 1.17.x | inline `add(0,-0.04,0)` guarded by `isNoGravity()` | no | no (added later in 1.20.x) | `(ItemLike)` | identical (inferred from 1.18.2) | not jar-verified; behaviorally == 1.18.2 |
| 1.18.2 | inline `-0.04d` (javap off.56) | no | no | `(ItemLike)` | identical (javap-confirmed) | `EntityFallingBlock` Spigot-obf |
| 1.19.4 | inline `-0.04d` (javap off.56) | no | no | `(ItemLike)` | identical (javap-confirmed) | same as 1.18.2 |
| 1.20.6 | `getDefaultGravity()`→0.04 + `applyGravity()` | **no** | yes | `(ItemLike)` | identical (javap-confirmed) | gravity refactor landed 1.20.5; `applyEffectsFromBlocks` not yet called |
| 1.21.4 | `getDefaultGravity()` + `applyGravity()` | **yes** | yes | `(ServerLevel, ItemLike)` | identical (javap-confirmed) | `applyEffectsFromBlocks` added (1.21.2); drop API now takes ServerLevel; concrete-powder water-clip fast-path present |
| 1.21.11 | same as 1.21.4 | yes | yes | `(ServerLevel, ItemLike)` | identical (source-confirmed) | reference source above |
| 26.1.2 | same | yes | yes | `(ServerLevel, ItemLike)` | identical (javap-confirmed) | **unobfuscated**; byte-identical structure to 1.21.11 |

### What actually changed (the only real deltas)
1. **Gravity application form** (≤1.19.4 inline literal `-0.04` vs 1.20.5+ `getDefaultGravity()`/`applyGravity()`). **Value never changed (0.04).**
2. **`applyEffectsFromBlocks()`** added to the tick loop in **1.21.2** (step 6) — lets cobweb/honey/etc. affect falling blocks. Irrelevant in open air cannon trajectories but can alter behavior if the block passes through stuck-speed blocks.
3. **`handlePortal()`** in tick (portal teleport of falling blocks) — 1.20.x line.
4. **`spawnAtLocation` signature** gained a leading `ServerLevel` param (1.21.x) — pure API, same drop behavior.
5. **Concrete-powder water-clip fast-path** present in modern versions (mid-fall solidification at water surface) — sand/gravel unaffected.

**Everything load-bearing for a cannon engine — 0.04 gravity, 0.98 drag (applied last), terminal velocity 1.96, no bounce, no water current push (tick bypasses baseTick), explosion `push` adds straight into deltaMovement, fully deterministic (no RNG) — is invariant across all versions 1.17 → 26.1.x.**

Primary reference file to read for the exact code: `/Users/owengregson/Documents/StrikeSync/legacy-lab/decomp-1.21.11/net/minecraft/world/entity/item/FallingBlockEntity.java` (tick = lines 158-258; gravity 152-155; anvil/fall-damage 266-297). Base integration helpers: `…/net/minecraft/world/entity/Entity.java` (`applyGravity` 1904-1909, `baseTick` fluid push 998, `push` 2287, `isPushedByFluid` 3934, `ignoreExplosion` 4129). Explosion knockback: `…/net/minecraft/world/level/ServerExplosion.java` 366-413.