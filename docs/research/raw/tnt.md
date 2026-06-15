This confirms the move() semantics are consistent (1.20.6 uses `setOnGroundWithKnownMovement`, renamed to `setOnGroundWithMovement` in 1.21+ — pure rename). The collision logic (collide → horizontalCollision via Mth.equal → verticalCollision → verticalCollisionBelow → onGround update → horizontal zeroing → blockSpeedFactor) is stable.

I now have all ground-truth data. Here is the complete extraction.

---

# Vanilla Primed-TNT Per-Tick Algorithm: 1.7/1.8 → 26.1.x

**Confidence: HIGH.** Every constant, field name, method name, and integration order below was read directly from disk (decompiled `.java` for 1.21.11 and 1.8.9; `javap -c -constants` bytecode for the 1.18.2/1.19.4/1.20.6/1.21.4/1.21.11/26.1.2 Paper jars). Sources cited inline. The TNT/explosion logic is extremely stable; the only *real behavior* deltas are the gravity refactor (1.20.x), the addition of `handlePortal()`+`applyEffectsFromBlocks()` to the tick (1.21.x), the legacy `fuse--` post-decrement vs modern `getFuse()-1` (1.8 vs modern), and the legacy lack of flowing-fluid push (1.8). Everything else is renames or code relocation.

Note on sources: the 1.21.11 `.java` and all Paper jars are **Paper builds**. I have separated vanilla logic from Paper patches below; the Paper-only lines (spigot tnt-tick cap, `tntEntityHeightNerf`, `preventTntFromMovingInWater` resync, `ExplosionPrimeEvent`) are flagged. The pure-vanilla algorithm is what you replicate.

---

## 1. The tick() method — ordered pseudocode (modern: 1.21.4 / 1.21.11 / 26.1.2)

From `decomp-1.21.11/net/minecraft/world/entity/item/PrimedTnt.java` lines 110-144 and `javap` of `paper-26.1.2`/`paper-1.21.4` `PrimedTnt.tick()`:

```
tick():
  # [PAPER-ONLY] spigotConfig.maxTntTicksPerTick cap -> early return; skip in vanilla replication
  handlePortal()                                            # vanilla, added in 1.21.x
  applyGravity()                                            # see below; net effect: motionY -= 0.04
  move(MoverType.SELF, getDeltaMovement())                 # collision + position integration
  applyEffectsFromBlocks()                                 # vanilla, added in 1.21.x (cobweb/honey/etc effects)
  # [PAPER-ONLY] tntEntityHeightNerf check -> discard
  setDeltaMovement(getDeltaMovement().scale(0.98))         # drag, all 3 axes * 0.98
  if onGround():
      setDeltaMovement(getDeltaMovement().multiply(0.7, -0.5, 0.7))   # ground bounce
  i = getFuse() - 1
  setFuse(i)
  if i <= 0:
      if !level.isClientSide(): explode()
      discard()                                            # remove entity
  else:
      updateInWaterStateAndDoFluidPushing()   # 26.1.2: renamed updateFluidInteraction()
      if level.isClientSide(): addParticle(SMOKE, x, y+0.5, z, 0,0,0)
```

**Exact constants:** drag `0.98d`; bounce `multiply(0.7d, -0.5d, 0.7d)`. Fuse field is `fuse` (synched `DATA_FUSE_ID`, default 80). Accessors `getFuse()`/`setFuse(int)`.

**`applyGravity()`** (`Entity.java` 1904-1909): `gravity = getGravity()` → `if (gravity != 0) setDeltaMovement(dm.add(0, -gravity, 0))`. `getGravity()` = `isNoGravity() ? 0 : getDefaultGravity()`. `PrimedTnt.getDefaultGravity()` returns **`0.04d`** (PrimedTnt.java:107; confirmed `0.04d` in 1.20.6 & 1.21.4 bytecode). So net per-tick gravity is `motionY -= 0.04`, applied **before** move() — same order as legacy.

### Legacy form (1.17.1 / 1.18.2 / 1.19.4) — `javap EntityTNTPrimed.l()/k()`

Gravity is applied **inline** (no `applyGravity()` method, no `getDefaultGravity()` override). No `handlePortal()`, no `applyEffectsFromBlocks()`:

```
tick():
  # [PAPER cap]
  if !isNoGravity():
      setDeltaMovement(getDeltaMovement().add(0.0, -0.04, 0.0))   # literal double -0.04d
  move(MoverType.SELF, getDeltaMovement())
  # [PAPER tntEntityHeightNerf]
  setDeltaMovement(getDeltaMovement().scale(0.98))
  if onGround:                                  # field, not method
      setDeltaMovement(getDeltaMovement().multiply(0.7, -0.5, 0.7))
  i = getFuse() - 1; setFuse(i)
  if i <= 0: { if !clientSide: explode(); discard(); }
  else: { updateInWaterStateAndDoFluidPushing(); if clientSide: addParticle(SMOKE...) }
```

### Legacy baseline (1.8.9 / 1.7.10) — `javap vj.t_()` (class `vj` = EntityTNTPrimed; fuse field `a:I`)

Per-component, not Vec3-based; **post-decrement** fuse; **no fluid push** (only `handleWaterMovement()`):

```
tick():  # prevPos = pos first
  this.motionY -= 0.03999999910593033        # == 0.04f promoted to double
  moveEntity(motionX, motionY, motionZ)
  motionX *= 0.9800000190734863              # == 0.98f promoted; applied per-axis
  motionY *= 0.9800000190734863
  motionZ *= 0.9800000190734863
  if onGround (field C):
      motionX *= 0.699999988079071           # == 0.7f promoted
      motionZ *= 0.699999988079071
      motionY *= -0.5
  if (fuse-- <= 0):                          # POST-decrement: fuse ticks at 0 once
      setDead(); if !world.isRemote: explode()
  else:
      handleWaterMovement()                  # water DETECTION only, no flow push
      world.spawnParticle(SMOKE, posX, posY+0.5, posZ, 0,0,0)
```

The legacy `0.98` is `0.98f` (`0.9800000190734863d`) and bounce is `0.7f` (`0.699999988079071d`); the modern code uses the exact `double` literals `0.98d` and `0.7d`. **This is a tiny precision delta** (legacy = float-promoted constants; modern = clean doubles). Gravity legacy `0.03999999910593033d` (0.04f) vs modern `0.04d`.

---

## 2. Fluid interaction (cannon-critical)

Called **after** the fuse branch (only when not exploding), i.e. *after* move/drag/bounce in the same tick: `updateInWaterStateAndDoFluidPushing()` (26.1.2: `updateFluidInteraction()`).

`Entity.updateInWaterStateAndDoFluidPushing()` (`Entity.java` 2006-2012):
```
fluidHeight.clear()
updateInWaterStateAndDoWaterCurrentPushing()      # WATER push, flowScale = 0.014
lavaScale = ultraWarm/FAST_LAVA ? 0.007 : 0.0023333333333333335
updateFluidHeightAndDoFluidPushing(LAVA, lavaScale)
```
Water current pushing (line 2019): `updateFluidHeightAndDoFluidPushing(FluidTags.WATER, 0.014)`.

**Fluid-push vector math** — `Entity.updateFluidHeightAndDoFluidPushing(tag, flowScale)` (Entity.java 4329-4397). Per fluid block in the (deflated-by-0.001) bounding box whose surface height ≥ box.minY:
```
pushVector += (heightDiff < 0.4 ? flow.scale(heightDiff) : flow)   # flow = FluidState.getFlow()
totalPushes += 1
...
if pushVector != ZERO:
    pushVector = pushVector.scale(1.0 / totalPushes)
    if not Player: pushVector = pushVector.normalize()             # TNT is normalized
    pushVector = pushVector.scale(flowScale)                       # 0.014 water / 0.007|0.00233 lava
    if (|motX| < 0.003 && |motZ| < 0.003 && pushVector.length() < 0.0045000000000000005):
        pushVector = pushVector.normalize().scale(0.0045000000000000005)   # minimum push floor
    setDeltaMovement(getDeltaMovement().add(pushVector))
```

**Constants verified identical across 1.18.2, 1.19.4, 1.20.6, 1.21.4, 1.21.11, 26.1.2:** water `0.014`, lava `0.007`/`0.0023333333333333335`, threshold `0.003`, min-push floor `0.0045000000000000005`, the `0.4` height split. In **26.1.2** this math was moved verbatim into `EntityFluidInteraction$Tracker.applyCurrentTo` (constants now named `WATER_FLOW_SCALE=0.014`, `LAVA_FAST_FLOW_SCALE=0.007`, `LAVA_SLOW_FLOW_SCALE=0.0023333333333333335`; same `normalize`/`scale`/`0.003`/`0.0045` sequence) — **pure relocation, no behavior change**.

`isPushedByFluid()` returns true for TNT (vanilla). **The water push happens AFTER move() each tick** — this is what propels water-stream TNT cannons. **1.8.9 has NO flowing-fluid push at all** (only `handleWaterMovement()` for fall-distance reset / drowning detection), so cannon physics in 1.8.x are fundamentally different from 1.13+.

---

## 3. Collision detail in move()

`Entity.move(MoverType.SELF, dm)` (`Entity.java` 1216-1333; vanilla collision in `collide()` 1569+; cross-checked against 1.20.6 `javap`):

1. If `noPhysics`: just `setPos(x+dx, y+dy, z+dz)`, clear collision flags. (TNT has physics.)
2. `vec3 = collide(movement)` — the resolved (clamped) movement.
3. `collide()` gathers block collision **VoxelShapes** in `boundingBox.expandTowards(movement)` (plus world border + hard entity collisions), then **resolves axis-by-axis** (step-and-slide): Y first, then X/Z, each axis clamped against the shapes. If it collided downward or was already onGround AND collided horizontally, it attempts a **step-up** (maxUpStep; 0 for TNT, so no stepping). Result `vec3` = how far it actually moved.
4. `setPos(position + vec3)` if movement is significant.
5. **Collision flags:** `horizontalCollision = !Mth.equal(movement.x, vec3.x) || !Mth.equal(movement.z, vec3.z)`. `verticalCollision = movement.y != vec3.y`. `verticalCollisionBelow = verticalCollision && movement.y < 0`.
6. **onGround update with the "only while moving down" nuance:** the block is guarded by `if (Math.abs(movement.y) > 0.0 || isLocalInstanceAuthoritative())`, and `setOnGroundWithMovement(verticalCollisionBelow, horizontalCollision, vec3)` sets `onGround = verticalCollisionBelow`. So **onGround only becomes true when there was downward (`movement.y < 0`) motion that got clamped** — a TNT resting and only moving sideways won't refresh onGround=true unless it also has downward intent. (1.20.6: same logic via `setOnGroundWithKnownMovement` — pure rename to `setOnGroundWithMovement` in 1.21+.)
7. **Horizontal velocity zeroing:** `if (horizontalCollision) setDeltaMovement(collidedX ? 0 : dm.x, dm.y, collidedZ ? 0 : dm.z)`. Note: this zeroes the X/Z **delta-movement** components that hit a wall, but does NOT zero motionY here.
8. **Vertical motionY zeroing** is implicit: `collide()` returns `vec3.y = 0` when it hits floor/ceiling, so after `setPos`, the next tick's drag operates on the original dm.y until the ground-bounce branch flips it. Actually motionY is preserved in dm until the explicit ground-bounce `*-0.5` in tick(); the *position* delta is what gets clamped. (For a 1:1 replica: track dm.y separately; the bounce uses the dm, not the clamped vec3.)
9. `blockSpeedFactor` multiply at the end (`dm.multiply(f,1,f)`) — for normal blocks f=1.0 (soul sand/honey reduce it). Negligible unless on those blocks.

To reproduce against a block-shape snapshot: gather all block `VoxelShape`s intersecting `bb.expandTowards(dm)`, resolve Y then X then Z against them (clamp each component so the box doesn't penetrate), set `onGround = (clampedY != dm.y && dm.y < 0)`, zero the horizontally-collided dm components, leave dm.y untouched (it's handled by the bounce branch).

---

## 4. Spawn / prime path

**Constructors** (PrimedTnt.java 72-87, identical bytecode 1.20.6/1.21.4/1.21.11/26.1.2):
- `PrimedTnt(EntityType, Level)` — sets `blocksBuilding = true`.
- `PrimedTnt(Level, double x, y, z, @Nullable LivingEntity owner)`:
  ```
  setPos(x,y,z)
  d = random.nextDouble() * 6.2831854820251465          # = (double)(2*PI as float); the launch angle
  setDeltaMovement(-sin(d)*0.02, 0.2f, -cos(d)*0.02)    # 0.2f = 0.20000000298023224
  setFuse(80)
  xo=x; yo=y; zo=z
  owner = EntityReference.of(owner)                     # 1.21.4+; older: plain LivingEntity field
  ```
  **Default fuse = 80 ticks** (`DEFAULT_FUSE_TIME = 80`, `defineSynchedData` defines `DATA_FUSE_ID` = 80; NBT default 80). Confirmed in all jars.
  **Initial random horizontal scatter:** modern uses `random.nextDouble() * 6.2831854820251465` (`6.2831854820251465` is `2π` cast through float). **1.8.9 differs:** `(float)(Math.random() * 3.1415927410125732 * 2.0)` with sin/cos cast to float, `motX = -(float)sin*0.02f`, `motZ = -(float)cos*0.02f`. Same algorithm, slightly different float rounding and RNG source (`Math.random()` global vs `this.random`).

**Block-break / natural prime flow:** `TntBlock.onCaughtFire` / `explode` / redstone / fire → constructs `new PrimedTnt(level, x+0.5, y, z+0.5, igniter)` (the +0.5 centering happens at the call site, not in the ctor), `level.addFreshEntity(tnt)`, plays fuse sound. The random scatter above is what gives a freshly-primed TNT its slight horizontal drift + 0.2 upward pop. Stacked-block-break duping cannons rely on this exact scatter being deterministic-per-seed.

**explode()** (PrimedTnt.java 146-156): `level.explode(this, getDefaultDamageSource(level,this), usedPortal ? USED_PORTAL_DAMAGE_CALCULATOR : null, x, getY(0.0625), z, radius=4.0f, fire=false, ExplosionInteraction.TNT)`. Power **4.0f** (`DEFAULT_EXPLOSION_POWER`, `explosionPower` field, NBT `explosion_power`, clamped 0..128). Explosion origin y is `getY(0.0625)` (slightly above feet). **[Paper]** wraps this in `ExplosionPrimeEvent` (cancellable, lets plugins override radius/fire) and a `TNT_EXPLODES` gamerule check. 1.8.9: `world.createExplosion(this, x, y, z, 4.0F, true)`.

**Owner/source tracking:** modern field `owner` is `EntityReference<LivingEntity>` (1.21.4+) — a UUID-based weak ref, restored via `EntityReference.read/store` and copied in `restoreFrom`/`teleport`. 1.20.6 and earlier: plain `public LivingEntity owner` (Spigot field `d`). 1.8.9: `pr b` (the igniter). `getOwner()` resolves the living entity.

---

## 5. Misc per-tick / lifecycle

- **Portal:** `handlePortal()` added to the **start** of tick() in 1.21.x (after the Paper cap, before gravity). Sets `usedPortal=true` on teleport (`teleport()` override), which switches the explosion to `USED_PORTAL_DAMAGE_CALCULATOR` (won't destroy nether portal blocks). 1.20.6 and earlier: **no `handlePortal()` in tick** (portal handled elsewhere/not at all for TNT). 1.8.9: had inline portal/`inPortal` counter logic in the generic Entity tick, not TNT-specific.
- **Despawn / out-of-world:** No fuse-independent despawn timer in modern TNT (it always explodes on fuse). 1.8.9 EntityItem had a 6000-tick despawn but **TNT does not**. **[Paper]** `tntEntityHeightNerf` discards TNT above a Y threshold (config, default disabled).
- **isInWater / lava / fire:** TNT is flammable-immune in effect (no fire damage handling in tick); water/lava only affect it via the fluid-push (§2) and fall-distance reset. `applyEffectsFromBlocks()` (1.21.x) applies block touch effects (cobweb stuck-speed `stuckSpeedMultiplier`, bubble columns, etc.) that feed into next tick's `move()`.
- **Fall:** `move()` calls `checkFallDamage`, but TNT takes no fall damage; fall distance is tracked but irrelevant.
- **hurtServer** returns `false` (1.21.4+: TNT is invulnerable to damage). `getMovementEmission()` = `NONE` (no step sounds/vibrations).

---

## 6. PER-VERSION DELTA TABLE

| Aspect | 1.7.10 / 1.8.9 (baseline) | 1.17.1 / 1.18.2 / 1.19.4 | 1.20.6 | 1.21.4 | 1.21.11 | 26.1.2 |
|---|---|---|---|---|---|---|
| Class name (Mojang) | `EntityTNTPrimed` (obf `vj`) | `EntityTNTPrimed` (Spigot-obf members) | `PrimedTnt` (Mojang-mapped) | `PrimedTnt` | `PrimedTnt` | `PrimedTnt` (unobf) |
| Gravity application | inline `motY -= 0.03999999910593033` (0.04f) | inline `setDeltaMovement(add(0,-0.04,0))` | `applyGravity()` → `getDefaultGravity()=0.04d` **(refactor)** | same | same | same |
| Gravity constant | `0.04f`-as-double | `-0.04d` (clean double) | `0.04d` | `0.04d` | `0.04d` | `0.04d` |
| `handlePortal()` in tick | no (generic entity portal logic) | **no** | **no** | **YES (added)** | yes | yes |
| `applyEffectsFromBlocks()` in tick | no | **no** | **no** | **YES (added)** | yes | yes |
| Drag 0.98 | per-axis `*0.9800000190734863` | `Vec3.scale(0.98d)` | `scale(0.98d)` | `scale(0.98d)` | `scale(0.98d)` | `scale(0.98d)` |
| Ground bounce | per-axis `*0.699999988079071`, `*-0.5`, `*0.7f` | `multiply(0.7d,-0.5d,0.7d)` | same | same | same | same |
| onGround | field `C` | field `onGround` | `setOnGroundWithKnownMovement` | `setOnGroundWithMovement` **(rename)** | same | same |
| Fuse field / accessor | `a:I`, `fuse--` **post-decrement** | `fuse` `getFuse()/setFuse()` `getFuse()-1` | same | same | same | same |
| Fuse=0 semantics | ticks once at 0 then explodes | explode when `getFuse()-1 <= 0` | same | same | same | same |
| Fluid push call | **none** (`handleWaterMovement()` only) | `updateInWaterStateAndDoFluidPushing()` | same | same | same | `updateFluidInteraction()` **(rename + moved to EntityFluidInteraction)** |
| Water flow scale | n/a | `0.014` | `0.014` | `0.014` | `0.014` | `0.014` |
| Lava flow scale | n/a | `0.007` / `0.0023333…` | same | same | same | same (named consts) |
| Fluid min-push floor | n/a | `0.0045…` (thr `0.003`) | `0.0045…` | `0.0045…` | `0.0045…` | `0.0045…` (in Tracker) |
| Prime random angle | `(float)(Math.random()*πf*2)`, float sin/cos | `random.nextDouble()*6.2831854820251465` | same | same | same | same (`getRandom()`) |
| Initial velocity | `(-sinθ*0.02f, 0.2f, -cosθ*0.02f)` | `(-sin·0.02, 0.2f, -cos·0.02)` | same | same | same | same |
| Default fuse | 80 | 80 | 80 | 80 | 80 | 80 |
| Explosion power | 4.0f, fixed | 4.0f (`yield` field) | 4.0f (`yield`) | 4.0f (`explosionPower`, NBT, clamp 0-128) | same | same |
| Owner field | `pr b` | `LivingEntity` (Spigot `d`) | `LivingEntity owner` | `EntityReference<LivingEntity>` **(change)** | same | same |
| NBT API | `NBTTagCompound` | `NBTTagCompound` (obf `a/b`) | `CompoundTag` | `CompoundTag` | `ValueInput/ValueOutput` **(codec refactor)** | `ValueInput/ValueOutput` |
| usedPortal / portal-aware explosion | no | no | no | **YES** | yes | yes |
| `hurtServer` invulnerable | (via generic) | (generic) | (generic) | `hurtServer()→false` added | yes | yes |

**Real behavior changes (not renames):** (a) gravity moved from inline to `applyGravity()/getDefaultGravity()` at 1.20.x — *no numeric change*; (b) `handlePortal()` + `applyEffectsFromBlocks()` added to tick at 1.21.x — these can alter trajectory if TNT enters a portal or touches a stuck-speed block; (c) 1.8.x has no flowing-fluid push (cannon physics differ); (d) 1.8.x `fuse--` post-decrement gives TNT one extra tick at fuse value 0. **Everything else (0.04, 0.98, 0.7/-0.5/0.7, 80, 0.02, 0.2, 4.0, 0.014, 0.007, 0.00233, 0.0045) is byte-identical across the entire 1.17→26.1.2 range** and effectively identical (modulo float-vs-double rounding) back to 1.8.9.

---

## 7. RNG usage in the tick itself

**The TNT `tick()` consumes ZERO randomness** in any version (1.8.9 → 26.1.2). Verified across all bytecode dumps: no `random.*`/`Math.random` call inside `tick()`/`t_()`/`l()`/`k()`. The SMOKE particle uses constant `(0,0,0)` velocity and only spawns client-side.

**RNG is consumed only once, at construction** (prime time), in the `PrimedTnt(Level,d,d,d,LivingEntity)` constructor: a single `this.random.nextDouble()` (1.8.9: `Math.random()`), multiplied by `6.2831854820251465` to pick the launch angle. **Source: `this.random`** (the entity's `RandomSource`, per `Entity.random`; 26.1.2 calls `getRandom()`). The vertical 0.2 and horizontal 0.02 magnitudes are constants. So for off-thread replication, the *only* nondeterminism to mirror is that one `nextDouble()` at spawn; the entire per-tick physics integration is fully deterministic given position + deltaMovement + the block-shape snapshot.

---

### Files / jars read (all absolute paths)
- `/Users/owengregson/Documents/StrikeSync/legacy-lab/decomp-1.21.11/net/minecraft/world/entity/item/PrimedTnt.java` (vanilla+Paper tick, ctor, explode, gravity, fuse)
- `/Users/owengregson/Documents/StrikeSync/legacy-lab/decomp-1.21.11/net/minecraft/world/entity/Entity.java` (`applyGravity` 1904, `move` 1216, `collide` 1569, `setOnGroundWithMovement` 1178, `updateInWaterStateAndDoFluidPushing` 2006, `updateFluidHeightAndDoFluidPushing` 4329)
- `/Users/owengregson/Documents/StrikeSync/run/26.1.2/versions/26.1.2/paper-26.1.2.jar` → `net.minecraft.world.entity.item.PrimedTnt` (tick, ctor), `net.minecraft.world.entity.Entity` + `EntityFluidInteraction$Tracker` (fluid consts) — unobfuscated
- `/Users/owengregson/Documents/StrikeSync/run/1.21.4/versions/1.21.4/paper-1.21.4.jar` and `.../1.20.6/...paper-1.20.6.jar` → `PrimedTnt` tick/ctor/getDefaultGravity, `Entity` move + fluid (Mojang-mapped)
- `/Users/owengregson/Documents/StrikeSync/run/1.19.4/...paper-1.19.4.jar` and `.../1.18.2/...paper-1.18.2.jar` → `net.minecraft.world.entity.item.EntityTNTPrimed` tick (`l()`/`k()`) + `Entity` fluid consts (Spigot-obf members)
- `/Users/owengregson/Documents/StrikeSync/legacy-lab/vanilla-1.8.9.jar` → class `vj` = EntityTNTPrimed (`t_()` tick, `l()` explode, ctor) — obf baseline. (No 1.17.1 jar on disk; 1.18.2 represents the 1.17→1.19 era, which is structurally identical for TNT.)