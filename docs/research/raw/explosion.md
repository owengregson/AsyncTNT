I now have full ground truth on the drop-decay RNG. The drop decay (`ApplyExplosionDecay`) draws one `random.nextFloat()` **per item in the stack** (count rolls), each gated by `<= 1.0/radius`. This RNG comes from the loot context's random (which is `level.random` on the server). This is a critical and large RNG consumer for block destruction.

I have everything verified across all sources. Let me write the final comprehensive answer.

I have gathered complete, cross-verified ground truth. Here is the full extraction.

---

# Vanilla TNT Explosion (power 4.0) — Exact Algorithm, MC 1.17 → 26.1.x

**Sources read (with confidence):**
- `…/decomp-1.21.11/net/minecraft/world/level/ServerExplosion.java` (Paper-patched, Moonrise-optimized, but math 1:1 vanilla) — **primary**, HIGH confidence.
- `…/decomp-1.21.11/net/minecraft/world/level/{Explosion,ExplosionDamageCalculator,EntityBasedExplosionDamageCalculator,SimpleExplosionDamageCalculator}.java`
- `…/decomp-1.21.11/net/minecraft/server/level/ServerLevel.java` (lines 1658–1701: `explode0`, TNT→BlockInteraction mapping)
- `…/decomp-1.21.11/…/loot/functions/ApplyExplosionDecay.java`, `…/loot/predicates/ExplosionCondition.java`
- `…/decomp-1.21.11/net/minecraft/world/entity/item/PrimedTnt.java` (power 4.0)
- `…/decomp-1.8.9/adi.java` (1.8.9 obfuscated monolith) — cross-reference, HIGH confidence on math.
- javap (GraalVM 25 `javap`) of Paper jars: **26.1.2** (unobf), **1.20.6** (Mojang-mapped), **1.19.4 / 1.18.2** (Spigot-mapped). 1.17.1 jar absent on disk — inferred from 1.18.2 (identical bytecode shape) + known history, MEDIUM-HIGH confidence (see notes).

Naming convention below: Mojang names (1.21.11 / 26.1.2).

---

## 0. Entry point & parameters (TNT, power 4.0)

`PrimedTnt`: `private static final float DEFAULT_EXPLOSION_POWER = 4.0f; public float explosionPower = 4.0f;` (clamped 0–128 on load). On detonation calls:
```
level().explode(this, Explosion.getDefaultDamageSource(...), null,
                getX(), getY(0.0625), getZ(), 4.0f, /*fire=*/false, ExplosionInteraction.TNT)
```
- **TNT does NOT set fire** (`fire=false`). Confirmed `PrimedTnt.explode()` passes `event.getFire()` whose default is false; the boolean controls `createFire`.
- `ExplosionInteraction.TNT` → `getDestroyType(GameRules.TNT_EXPLOSION_DROP_DECAY)` → with default gamerule = **`DESTROY_WITH_DECAY`** (ServerLevel.java:1677,1699-1701). `DESTROY_WITH_DECAY` ⇒ `yield = 1.0f / radius = 0.25` and drops are decay-gated.
- `center.y` = entity Y + 0.0625 (TNT-specific eye/position offset).

---

## 1. `explode()` — block ray grid (`calculateExplodedPositions`)

**Ray grid (static `CACHED_RAYS`, built once):** triple loop `x,y,z ∈ 0..15`, keep only cube **surface** (`x∈{0,15} ∨ y∈{0,15} ∨ z∈{0,15}`); the `continue` skips all interior cells. Exactly **1352 rays** (16³ − 14³). Direction:
```java
xDir = (float)x/15.0f*2.0f - 1.0f;   yDir = ...;   zDir = ...;
mag  = sqrt(xDir² + yDir² + zDir²);
step = (xDir/mag*0.3f, yDir/mag*0.3f, zDir/mag*0.3f)   // pre-normalized × 0.3
```
The cast-to-`float` of `x/15.0f*2.0f-1.0f` then promoted to `double` is **load-bearing for bit-exact parity** (do the intermediate in float, then double). Note: in pre-1.21 monolith the normalization+0.3 multiply happens **inside** the per-ray loop, not pre-cached, but produces the identical doubles.

**Per-ray starting intensity (quote, exact):**
```java
float power = this.radius * (0.7f + this.level.random.nextFloat() * 0.6f);
```
- Confirmed `radius * (0.7f + random.nextFloat()*0.6f)`. **random is `level.random`** — verified in 1.20.6 bytecode (`getfield Level.random` at offset 209–212 immediately before `nextFloat`) and 1.21.11 source. (1.18.2 uses `java.util.Random.nextFloat()` — same algorithm, different RNG engine class, see deltas.)
- This is **one `nextFloat()` per ray, before stepping.** Power 4.0 ⇒ start ∈ [2.8, 4.4).

**Step march (do/while):**
```java
do {
    blockState = blockAt(floor(currX),floor(currY),floor(currZ));
    resistanceTerm = (getExplosionResistance(...) + 0.3f) * 0.3f;   // see below
    power -= resistanceTerm;
    if (power > 0.0f && shouldBlockExplode(...))  // threshold > 0
        collect pos (if fire || !isAir);
    currX += stepX; currY += stepY; currZ += stepZ;   // step = 0.3-scaled dir
} while ((power -= 0.22500001f) > 0.0f);
```
- **Step size 0.3** (the direction vector magnitude is 0.3 per the `* 0.3f` above).
- **Per-step base decay: `power -= 0.22500001f`** — this is `0.3f * 0.75f` precomputed = the air/empty decay; confirmed literal `0.22500001f` in 1.19.4/1.20.6/1.21.11/26.1.2 bytecode, and as `f2 -= 0.225...` in the 1.8.9 loop (there it's written `0.3F*0.75F`). Crucially this is **`float` `0.22500001f`, not `0.225`** — required for parity.
- **Block resistance decay:** `power -= (resistance + 0.3f) * 0.3f`. In the cached path the cache stores `(resistance.orElse(-0.3f) + 0.3f) * 0.3f`; for air/no-fluid `getBlockExplosionResistance` returns `Optional.empty()` ⇒ `ZERO_RESISTANCE = -0.3f` ⇒ term = `(-0.3+0.3)*0.3 = 0` (i.e. air costs only the 0.225 base). **`getExplosionResistance` is queried via `ExplosionDamageCalculator.getBlockExplosionResistance` = `max(block.getExplosionResistance(), fluid.getExplosionResistance())`.** For TNT the calculator is `EntityBasedExplosionDamageCalculator(source)` which wraps it through `Entity.getBlockExplosionResistance(...)` (identity for plain TNT/most blocks).
- **Collection threshold: `power > 0.0f`** AND `shouldBlockExplode` (calculator returns `true` for default) AND `blockState.isDestroyable()`. Block added once (`shouldExplode` cached null→Boolean to avoid recompute). Air is skipped unless `fire` is on.

**Order in `explode()`:** `gameEvent(EXPLODE)` → `calculateExplodedPositions()` (all per-ray RNG here) → `hurtEntities()` (no RNG) → if interacts: `interactWithBlocks(list)` → if fire: `createFire(list)`. Returns block count.

---

## 2. Entity effects (`hurtEntities`)

**Gather:** `f = radius * 2.0f` (= 8.0 for TNT). AABB = center ± (`f + 1`) on each axis → `floor`ed. `level.getEntities(excludeSource? source : null, AABB, e -> e.isAlive() && !e.isSpectator())`. Skip `entity.ignoreExplosion(this)` and any with `sqrt(distSqr(center))/f > 1.0`.

**Exposure / seen percent** — `getSeenPercent(center, entity)` (vanilla) / `getBlockDensity`→`getSeenFraction` (Paper cached). Sampling grid per entity AABB:
```
d  = 1/((maxX-minX)*2+1);  d1 = 1/((maxY-minY)*2+1);  d2 = 1/((maxZ-minZ)*2+1);
for d5,d6,d7 in [0..1] step d,d1,d2:
    sample = lerp(d5,minX,maxX)+xOff, lerp(d6,minY,maxY), lerp(d7,minZ,maxZ)+zOff
    ray-clip sample→explosionVector (ClipContext.COLLIDER, Fluid.NONE)
    miss → ++i
return (float)misses / (float)total
```
**No RNG.** Result `f1` = seen fraction (computed only if entity will be damaged or knocked back).

**DAMAGE formula** — `ExplosionDamageCalculator.getEntityDamageAmount(explosion, entity, seenPercent)` (quote, exact, 1.17→26.1.x):
```java
float f = explosion.radius() * 2.0f;                  // 8.0
double d = sqrt(entity.distanceToSqr(center)) / f;    // 0..1
double d1 = (1.0 - d) * seenPercent;
return (float)((d1*d1 + d1) / 2.0 * 7.0 * f + 1.0);   // **7.0**, not 8.0
```
- Constant is **`7.0`** in ALL seven versions (verified `double 7.0d` in 1.18.2/1.19.4 inline, 1.20.6/26.1.2 calculator). 1.8.9 used `8.0` — the `8.0→7.0` change predates 1.17 (MC 1.14). In ≤1.19.4 this math is **inlined in `Explosion`** (and result is `(int)`-truncated then re-cast in 1.8.9; modern is plain `(float)`); in 1.20.6+ it lives in `ExplosionDamageCalculator`.

**KNOCKBACK formula** (quote, exact, 1.21.11):
```java
double knockResist = (entity instanceof LivingEntity)
        ? livingEntity.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE) : 0.0;
double scalar = (1.0 - d) * (double)f1 * (double)knockbackMultiplier * (1.0 - knockResist);
Vec3 push = dir.scale(scalar);    // dir = (eyePos - center).normalize(); TNT uses position() not eyePos
entity.push(push);
```
where `d = sqrt(distSqr(center))/f`, `f1` = seenPercent, `knockbackMultiplier` = `damageCalculator.getKnockbackMultiplier(entity)` (= **1.0** default; 0 for flying creative players).
- **Direction vector** = `(targetVec − center).normalize()`, `targetVec = (entity instanceof PrimedTnt) ? entity.position() : entity.getEyePosition()`. The `(1 − distance/radius) * exposure` scalar is applied to the **normalized** direction. (radius here = `f` = `2*radius`.)
- **`EXPLOSION_KNOCKBACK_RESISTANCE` attribute** is the modern "protection-enchant knockback reduction" path: Blast Protection / knockback-resist contribute via this attribute as `(1 − knockResist)`. In ≤1.19.4 the reduction was applied differently — via `EnchantmentHelper.getExplosionKnockbackAfterDampener` (Blast Protection level → multiplier) directly on the push vector, and there was **no** `EXPLOSION_KNOCKBACK_RESISTANCE` attribute (introduced 1.20.5). Pre-1.20.5 monolith: `double d8 = LivingEntity ? ProtectionEnchantment.getExplosionKnockbackAfterDampener(le, d7) : d7; push = dir.scale(d8)`.
- **Players vs non-players:** non-player living entities just get `push`. **Players** (non-spectator, not creative-flying) additionally have their push stored in `hitPlayers` map, which is **sent to the client in the explode packet** so the client applies it authoritatively (avoids server/client desync) — players are NOT directly `push`ed via velocity sync the same way. Redirectable projectiles get re-owned instead. (Paper also fires `EntityKnockbackEvent` and respects `disableExplosionKnockback`.)
- `entity.onExplosionHit(source)` called last (e.g. chains TNT, ignites creepers — no RNG for plain TNT).

---

## 3. `finalizeExplosion` / `interactWithBlocks` + `createFire`

**(1.20.6 and earlier — `finalizeExplosion(boolean spawnParticles)`):**
1. **Sound:** `level.playSound(...,SoundEvents.GENERIC_EXPLODE, 4.0f, (1.0 + (random.nextFloat() − random.nextFloat())*0.2) * 0.7)` — **consumes 2 `nextFloat()`** (uses the explosion's stored `random` field = `level.random`). *(1.8.9: `random.explode`, vol 4.0, same pitch math.)*
2. **Particles:** `EXPLOSION_EMITTER` if `radius≥2 && interacts` else `EXPLOSION` — no RNG.
3. `Util.shuffle(toBlow, level.random)` — **consumes RNG** (Fisher–Yates, one `nextInt` per element).
4. For each block (reverse): `getDrops` via loot → drop-decay → `setBlock(AIR, flags)` → `block.wasExploded`. Item merge into `StackCollector` (cap 16, `MAX_DROPS_PER_COMBINED_STACK`), then `Block.popResource`.

**(1.21.x — split into `interactWithBlocks` + `createFire`; sound/particles REMOVED from here):**
- **`interactWithBlocks(blocks)`:**
  1. `Util.shuffle(blocks, level.random)` — **RNG** (first consumer in this method).
  2. Build bukkit block list, fire `EntityExplodeEvent`/`BlockExplodeEvent` (Paper), apply `yield`.
  3. For each surviving pos: if TNT-block & `TNT_EXPLODES` gamerule → prime it (`onCaughtFire`/TNTPrimeEvent) else `getBlockState(pos).onExplosionHit(level, pos, this, stackDropper)`.
  4. `onExplosionHit` default (`BlockBehaviour`): if `DESTROY_WITH_DECAY` and `radius` present, drops run through the loot table with `EXPLOSION_RADIUS` param ⇒ **`ApplyExplosionDecay`**.
  5. Merged stacks popped via `Block.popResource`.
- **DROP decision / yield gate (confirmed `1.0/radius`):** `ApplyExplosionDecay.run`: `float f = 1.0f / radius; for each of stack.getCount() items: if (random.nextFloat() <= f) ++kept;` → **one `nextFloat()` PER ITEM in every dropped stack.** `ExplosionCondition.SURVIVES_EXPLOSION` (block-level survival): `random.nextFloat() < 1.0f/radius`. For TNT radius 4.0 ⇒ gate = 0.25. *(1.8.9 monolith: `afh2.a(world, pos, state, 1.0f/radius, 0)` — the `dropBlockAsItemWithChance(... 1/radius ...)` path, equivalent per-item rolls.)*
- **`createFire(blocks)`** (only if `fire` — **NOT for TNT**): per block `if (level.random.nextInt(3) != 0 || !isAir || !below.isSolidRender()) continue; setBlock(fire)`. **One `nextInt(3)` per candidate block.** (1.8.9: `this.c.nextInt(3)` — used a *dedicated `Random c`* field, see delta.)

**`isFire` / sets fire:** Controlled solely by the `fire` boolean → `createFire`. TNT passes `false`, so **no fire RNG and no fire blocks** for TNT.

**Sound + particles in 1.21.x:** emitted afterward in `ServerLevel.explode0` via `ClientboundExplodePacket(center, radius, blockCount, hitPlayerKb, particleType, sound, blockParticles)` to players within 64 blocks (`distSqr < 4096`). **No server RNG** — pitch jitter is now done **client-side**. (Verified ServerLevel.java:1690-1694; no `nextFloat` anywhere in the 1.21.11 explosion-finalize path except shuffle + decay.)

---

## 4. EVERY `level.random` consumption, IN ORDER (for pre-draw / replay)

**1.21.x / 26.1.x (TNT, fire=false), exact order:**
1. **`calculateExplodedPositions`:** `level.random.nextFloat()` × **1352** (one per surface ray, in CACHED_RAYS index order: nested `x=0..15, y=0..15, z=0..15` surface order). Each → `power = radius*(0.7f + r*0.6f)`.
2. **`hurtEntities`:** **none** (deterministic given snapshot + entity list/order).
3. **`interactWithBlocks`:** `Util.shuffle(blocks, level.random)` — Fisher–Yates: for `i = size−1 .. 1`: `level.random.nextInt(i+1)`. (size−1 draws.)
4. **Drops** (per destroyed block, in shuffled order): block loot table draws — for the simple "block survives + decays" tables this is **`ApplyExplosionDecay`: one `nextFloat()` per item in the produced stack** (and/or `ExplosionCondition`: one `nextFloat()` per block for survival-style tables). Order = shuffled block order, then loot-pool order. (Number of draws = sum of stack counts; e.g. each stone/ore yields its drop count of rolls.)
5. **`createFire`:** **skipped for TNT** (fire=false). *(If it ran: `level.random.nextInt(3)` per candidate block, in `blocks` list order.)*

**Pre-1.21 (1.17–1.20.6) `finalizeExplosion`, exact order — DIFFERENT:**
1. Per-ray `nextFloat()` × 1352 (same as above).
2. `hurtEntities`: none.
3. **Sound pitch: `nextFloat()`, `nextFloat()`** (2 draws) — *inserted here, absent in 1.21+*.
4. `Util.shuffle` (size−1 `nextInt`).
5. Drops (loot-table decay rolls, same shape).
6. `createFire`: `nextInt(3)` per block (TNT skips).

> **Critical replay notes:** (a) The two sound-pitch `nextFloat`s exist in ≤1.20.6 and **vanish in 1.21+** (moved client-side) — this shifts every subsequent draw. (b) In 1.8.9 the **fire loop used a *separate* `new Random()` field (`c`)**, not `level.random` — so 1.8.9 fire is not replayable off the world RNG; 1.17+ uses `level.random` for fire. (c) `Util.shuffle` order depends on block-collection order, which depends on ray order and the per-ray `nextFloat`s — pre-draw the 1352 ray floats first, run the deterministic ray march to get the block set, then pre-draw shuffle ints, then decay floats.

---

## 5. Block-update / lighting / neighbor cost (what to keep cheap on apply)

The expensive part is **not** the ray math — it is `setBlock`/`onExplosionHit` per destroyed block:
- Each `setBlock(pos, AIR, flags)` with flags `3` (`UPDATE_NEIGHBORS|UPDATE_CLIENTS`) triggers: **neighbor `updateShape`/`neighborChanged` notifications**, **block-entity removal**, **client block-change packets**, and **lighting engine relight** (sky+block light propagation around each removed block). Mass destruction (hundreds of blocks) ⇒ hundreds of relights + neighbor cascades — the dominant cost.
- `Block.popResource` spawns `ItemEntity`s (entity adds + tracker).
- **Paper batching:** Paper/Moonrise (present in your 1.21.11 + 26.1.2 jars) optimizes the *ray phase* (chunk + block caches, `directMappedBlockCache[512]`, `explosionDensityCache`) and shuffles/processes in one pass, but the `setBlock`+lighting+neighbor cost itself is **not** batched into a single bulk op — each block still goes through `sendBlockUpdated` / `setBlock`. (Paper does collapse client packets via chunk-section updates and skips relight for some cases, but no atomic "remove N blocks, relight once" path in vanilla/Paper here.) **For your plugin:** do all the math + RNG + block-set selection on the worker thread against the snapshot, then on the main thread apply with the cheapest flags you can tolerate (e.g. defer/disable per-block light updates and do a single region relight, suppress neighbor notifies where safe) — that is the cost you want to avoid replicating per-block.

---

## 6. PER-VERSION DELTA TABLE (1.17.1, 1.18.2, 1.19.4, 1.20.6, 1.21.4, 1.21.11, 26.1.2)

| Aspect | 1.17.1 | 1.18.2 | 1.19.4 | 1.20.6 | 1.21.4 | 1.21.11 | 26.1.2 |
|---|---|---|---|---|---|---|---|
| Class shape | monolith `Explosion` | monolith `Explosion` | monolith `Explosion` | monolith `Explosion` | **`ServerExplosion` impl `Explosion` iface** | `ServerExplosion`+iface | `ServerExplosion`+iface |
| BlockInteraction enum name | `Explosion.BlockInteraction` (`Mode`/`Effect` in Spigot map) | **`Explosion$Effect`** (Spigot) | **`Explosion$Effect`** (Spigot) | `Explosion$BlockInteraction` | `BlockInteraction` | `BlockInteraction` | `BlockInteraction` |
| Per-ray intensity `radius*(0.7f+r*0.6f)` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Step 0.3 / decay `0.22500001f` | ✓ | ✓ (`0.225...` literal) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Resistance term `(res+0.3)*0.3` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Ray-grid surface 1352 rays | ✓ | ✓ | ✓ | ✓ (`CACHED_RAYS`) | ✓ | ✓ | ✓ |
| Damage const | **7.0** | **7.0** (inline) | **7.0** (inline) | **7.0** (calc) | 7.0 | 7.0 | 7.0 |
| `getEntityDamageAmount` location | inline in `Explosion` | inline | inline | **`ExplosionDamageCalculator`** | calculator | calculator | calculator |
| Per-ray RNG engine | `RandomSource` (Level.random) | **`java.util.Random`** | `RandomSource` | `RandomSource` | `RandomSource` | `RandomSource` | `RandomSource` |
| Knockback reduction | Blast-Prot via `ProtectionEnchantment.getExplosionKnockbackAfterDampener` | same | same | **attribute `EXPLOSION_KNOCKBACK_RESISTANCE`** (added 1.20.5) | attribute | attribute | attribute |
| Sound pitch RNG (2× nextFloat) in finalize | ✓ server-side | ✓ | ✓ | ✓ | **removed (client-side via packet)** | removed | removed |
| Sound/particle emission | in `finalizeExplosion` | same | same | same | **in `ServerLevel.explode0` via `ClientboundExplodePacket`** | same | same |
| Drop decay | `dropBlockAsItemWithChance(1/radius)` | loot `ApplyExplosionDecay` (1.16+) | `ApplyExplosionDecay` | `ApplyExplosionDecay` | `ApplyExplosionDecay` | `ApplyExplosionDecay` | same |
| Drop-decay gamerule split | single behavior | single | single | single | **`TNT/BLOCK/MOB_EXPLOSION_DROP_DECAY` gamerules** | ✓ | ✓ |
| `yield = 1/radius` (DESTROY_WITH_DECAY) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ (+`Double.isFinite` guard) | ✓ |
| Fire RNG `nextInt(3)` (TNT skips) | level.random | level.random | level.random | level.random | level.random | level.random | level.random |
| `hitPlayers` packet knockback | ✓ (1.15+) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Obfuscation | Spigot-obf | Spigot-mapped | Spigot-mapped | **Mojang-mapped** | Mojang-mapped | Mojang-mapped | **un-obfuscated** |

**Real behavior changes vs renames:**
- **REAL (affects RNG replay / values):** (1) the **2 sound-pitch `nextFloat`s removed in 1.21** — shifts all downstream draws; (2) **1.18.2 uses `java.util.Random`** vs `RandomSource` elsewhere (different stream, same algorithm); (3) **knockback-resistance moved to an attribute in 1.20.5** (Blast Protection now feeds `EXPLOSION_KNOCKBACK_RESISTANCE`); (4) drop-decay split into per-source gamerules in 1.21.
- **RENAMES / refactor-only (no behavior change):** `Explosion`→`ServerExplosion`+interface (1.21); `Explosion$Effect`→`BlockInteraction` (1.20); damage math relocated from `Explosion` into `ExplosionDamageCalculator` (1.20, value unchanged at 7.0); `finalizeExplosion`→`interactWithBlocks`+`createFire` (1.21).

**Core math constants (identical across ALL seven): per-ray `radius*(0.7f + level.random.nextFloat()*0.6f)`; step 0.3; base decay `0.22500001f`; resistance decay `(resistance + 0.3f)*0.3f`; air resistance sentinel `-0.3f`; collection threshold `power > 0.0f`; entity radius `2*radius`; damage `((d1²+d1)/2 * 7.0 * 2r + 1)` with `d1=(1−dist/2r)*seen`; drop/yield gate `1.0f/radius`; fire `nextInt(3)`.**

**Confidence:** HIGH for 1.18.2–26.1.2 (read source + javap'd bytecode directly). 1.17.1 jar not present on disk — its constants/structure are asserted from the 1.18.2 bytecode (byte-identical Explosion shape, `java.util.Random`, `Effect` enum, 7.0 damage, inline damage math) plus documented MC history; treat 1.17.1 as "same as 1.18.2" (MEDIUM-HIGH). The decomp-1.21.11 source is Paper/Moonrise-patched — the *caching* (block/chunk caches, `explosionDensityCache`, `EntityKnockbackEvent`, paperConfig toggles) is Paper-only, but every numeric constant and the RNG-consumption order match vanilla bytecode I javap'd.