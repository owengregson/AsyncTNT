I have all the material I need, corroborated across authoritative sources (Minecraft Wiki, Technical Minecraft Wiki, real patch source from TacoSpigot/PaperSpigot/SulphurSpigot/FluxSpigot, Paper docs, Folia DeepWiki, GitHub issues). Synthesizing the final brief.

---

# RESEARCH BRIEF: Factions TNT Cannons & Async/Off-Thread TNT Engine

Scope: how cannons physically work, what must be bit-exact, prior-art for optimizing/asyncing TNT, and concrete pitfalls for a custom off-thread engine. All numbers below are corroborated by at least two of: Minecraft Wiki, Technical Minecraft Wiki, or real fork patch source.

---

## PART A — Cannon mechanics & bit-exactness requirements

### A.1 The standard cannon
A cannon converts explosion knockback into directed momentum on a payload TNT, igniting the payload late enough that it has cleared the cannon before its own fuse fires.

- **Propellant / charge TNT** ("boosters"): a cluster of TNT (typically 4–16+, or stacked at one block) that detonates and shoves the projectile. More propellant + smaller distance from blast-center to payload = more velocity. Charges are kept from blowing each other apart by **water** and a blast-resistant frame.
- **Projectile / payload TNT**: the single TNT that gets launched. It must be ignited slightly *after* the propellant so it travels before exploding.
- **Water**: three jobs — (1) **absorbs explosion block-destruction** so the cannon doesn't destroy itself (explosions in water/lava destroy no blocks); (2) **aligns/holds** the charge and payload at fixed positions/velocities so each shot starts from an identical state; (3) the 1.8+ behavior where **flowing water pushes primed TNT** is itself a cannon mechanic on some designs and a bug to be reverted on others (see A.4).
- **Sand/gravel & slabs**: alignment guides. The payload is placed on a slab/fence so it sits at a precise sub-block Y; falling-block payloads have the same flight behavior as TNT. The block of TNT, when primed, spawns a `EntityTNTPrimed` offset to block-center `[x+0.5, y(+0.5), z+0.5]`.
- **Obsidian / blast-resistant frame**: contains the blast and defines which rays escape (shaping direction).
- **Redstone / dispenser firing**: repeaters/torches/wire deliver precisely-timed ignition pulses; dispensers can fire payloads. Redstone diode timing IS the firing schedule.
- **TNT-pushing-TNT**: explosion knockback is applied to *all* nearby entities including other primed TNT, so charges accelerate the payload. Knockback **accumulates within a single tick across multiple explosions** — multi-charge cannons depend on this summation.

### A.2 Exact physics numbers (these define "bit-exact")
- **TNT spawn velocity (vanilla):** `motY = +0.20`, and `motX/motZ = ±0.02` in a **random** direction (`f = random()*2π`). Cannon forks **zero this** (`motX = motZ = 0`) — see A.4. This single RNG call is a primary determinism hazard.
- **Fuse:** redstone/fire ignition = **80 ticks** (4s, fixed). Explosion-ignited TNT = **random 10–30 ticks**. PaperSpigot/SulphurSpigot recompute as `random.nextInt(fuse/4) + fuse/8`. Chain-detonation timing is therefore RNG-dependent and a major desync source.
- **Explosion knockback:** each entity is accelerated 0–1 block/tick, added to current velocity. Magnitude = `(1 − distance/(2·power)) · exposure · knockbackMultiplier · (1 − knockbackResistance)`. Direction = vector from explosion center to entity **eye** position — **except TNT, which uses feet/center**. Forks force head-height to `length/2` so the vector is deterministic and symmetric.
- **Exposure/block-density:** rays cast on a 16×16×16 grid (1352 rays); exposure = unobstructed/total. This is a pure geometric function of positions/AABB (no RNG) — but it is the thing Paper's `optimize-explosions` caches.
- **Block destruction rays:** intensity per ray = `(0.7 + random[0,0.6]) · power` (i.e. 0.7–1.3× power); step 0.3 blocks, lose `0.225 + (resistance+0.3)·0.3` per step. The 0.7–1.3 factor is **RNG-seeded**, so block-break patterns are not deterministic unless you control the RNG.

### A.3 Intra-tick ordering that MUST be preserved
Within one tick the canonical order is: **block/fluid ticks (water flow) → scheduled block events → redstone updates → entity tick (each entity: move, then fuse-decrement, then explode if fuse≤0) → block-entities.** Critical invariants:
1. **Entity iteration order = spawn order.** Entities tick in the order they were added to the global list. Two charges spawned in a different order produce different accumulated knockback ordering → different aim.
2. **Per-entity sequence:** velocity/move resolution happens, THEN the fuse check/explosion. A payload must move on the tick of detonation before/relative to the charge per vanilla order.
3. **Per-axis movement order:** vanilla resolves **Y, then X, then Z**. This asymmetry is why "east/west cannons" behave differently from "north/south" — fixed by processing **Z before X when |dx| > |dz|** (TacoSpigot `fix-east-west-cannoning`).
4. **Water push timing:** water must flow and push TNT on the same tick-phase as vanilla; the velocity-preserving `W()` water-check (Jedediah Smith's patch) must run so water alignment doesn't clobber accumulated velocity.
5. **Lazy-chunk acceleration:** "lazy" chunks tick blocks but NOT entities. Some cannons park the payload in a lazy chunk so it accumulates knockback from propellant (which ticks elsewhere) without moving until nudged. Entity-tick-range being narrower than view-distance reproduces/breaks this (Paper #3051).

### A.4 What forks change to make aim deterministic (the "fix cannons" semantics)
From real PaperSpigot/TacoSpigot/SulphurSpigot patch source:
- `EntityTNTPrimed`: **`motX = motZ = 0`** at spawn (remove the random 0.02 kick); `fuseTicks = 80` fixed; `getHeadHeight() = length/2` (deterministic knockback origin); `aL()`→false; velocity-preserving water check in `W()`.
- `BlockTNT`/dispenser: spawn primed TNT at integer block-Y (not `y+0.5`) — the "legacy explosion height" fix.
- Redstone (`BlockDiodeAbstract`, `BlockRedstoneTorch`, `BlockRedstoneWire`): **apply physics in a fixed explicit direction order** (W,E,N,S,D,U) instead of `EnumDirection.values()` iteration order — makes redstone update propagation deterministic/symmetric ("always fix cannons").
- **Removing `optimize-explosions`** (SulphurSpigot `0027`): the density cache was deliberately stripped to restore vanilla parity (see B.2 for the trap).

### A.5 What timing/ordering drift breaks aim
- A stray RNG call (spawn kick, fuse jitter) → payload starts with a different velocity → misses by blocks at range.
- Wrong entity iteration order → knockback summed in wrong order → wrong final vector.
- Off-by-one fuse decrement (explode one tick early/late) → payload detonates inside the cannon or overshoots (this is the live Paper/Moonrise "TNT explodes a few tenths early" bug, issue #309).
- Per-axis move-order asymmetry → east/west vs north/south cannons land differently.
- Water flow on a different tick-phase → payload not aligned/pushed when the charge fires → scatter.

---

## PART B — Prior art: optimizing/asyncing TNT (what they did, what broke)

### B.1 Paper's built-in optimizations & config (vanilla-parity-preserving, main-thread)
Paper world-config (`config/paper-world-defaults.yml`), defaults in parens:
- `optimize-explosions` (false): per-tick cache of entity explosion-exposure (`world.a(vec3d, aabb)`), keyed by world+explosion pos+entity AABB, cleared every tick. Determinism note: it changes results when two explosions hit the same AABB at the same center in one tick — that's why cannon forks disable/remove it.
- `prevent-tnt-from-moving-in-water` (false): restores pre-1.8 cannon mechanics (water doesn't push TNT).
- `disable-explosion-knockback` (false): nukes knockback entirely (would kill cannons).
- `tnt-entity-height-nerf` / `falling-block-height-nerf` (disabled): despawn primed TNT/falling blocks above a Y to cap anti-lag.
- `max-entity-collisions` (8): caps collision processing.
- Entity **tracking-range** / entity-tick-range: governs whether TNT in view distance actually ticks (root of "lazy chunk" cannon bugs, #3051).
- There is **no** vanilla "max-tnt-per-tick", "merge-radius for TNT", or "fixed-tnt-delay" in Paper core; `merge-radius` exists only for dropped items. (Caller's term "fixedDelays/merge-radius/max-tnt-per-tick" maps to *plugin* features, not Paper core.)

### B.2 Cannon-focused forks (TacoSpigot lineage)
- **SulphurSpigot** (1.8.8 TacoSpigot fork, AmbrosL): the cleanest reference. Patches `0024 optimize-tnt-and-falling-block-movement`, `0025 fix-east-west-cannoning`, `0026 disable-falling-block-stacking-at-256`, `0027 remove-optimize-explosions`, `0028 always-fix-cannons`. Lesson: it *removed* an optimization (explosion cache) to keep aim correct, and made all the cannon-fix behaviors unconditional.
  - Source: https://github.com/AmbrosL/SulphurSpigot/tree/master/PaperSpigot-Server-Patches
- **TacoSpigot `optimize-liquid-explosions`** (default true): skips computing which blocks to destroy when the explosion is in water/lava (safe because water absorbs destruction anyway) — a *correctness-preserving* optimization. The TNT/falling-block movement patch also does **axis-by-axis scan only when movement volume > 10** and returns empty entity-collision lists for TNT/falling blocks (skips entity collision) — note its own comment "may not fully emulate vanilla."
- **FluxSpigot** (TacoSpigot fork marketed as "Async TNT"): despite the name, its `0030-flux-optimize-tnt` patch is **not real off-thread physics** — it suppresses TNT spawn packets (TNT becomes invisible client-side), comments out explosion/smoke particles, and suppresses the explosion broadcast packet loop. So "async/optimized TNT" in the BuiltByBit market often means *don't send the entity/particles to clients*, not *move simulation off the main thread*. Reported problems: cannons "not working properly," client/server desync, added knobs like `fix-left-shooting-cannoning` / `left-shooting-accuracy` chasing residual axis asymmetry.
  - Source: https://github.com/VictorML11/FluxSpigot/blob/master/PaperSpigot-Server-Patches/0030-flux-optimize-tnt.patch
- **CannonFix / Cannon Patcher** (Spigot plugin, needs Paper): config to stop TNT moving in water — i.e. revert 1.8+ physics to 1.7 at the plugin layer.

### B.3 Folia (region threading) — the genuinely hard case
- World split into regions (`ThreadedRegionizer`, default 16×16 chunks = `2^gridExponent`), each ticking on its own thread at independent 20 TPS. **Only the owning thread may touch a region's data** (`TickThread.isTickThreadFor()`), enforced with exceptions.
- **Each region has independent tick state / RNG / scheduling** → two regions' RNG sequences diverge; cross-region timing is not phase-aligned.
- **Cross-region explosions are the core conflict:** you can't mutate chunks in a parallel region from one thread, so cross-boundary blast/knockback must be serialized (perf hit) or forbidden/capped (gameplay change). Entity boundary-crossing is a remove-from-source / schedule-on-target / add-to-target handoff — not parallel.
- Live bug: TNT detonates ~a few tenths early vs vanilla on recent Paper/Folia, traced to **Moonrise** (Folia/Paper #309) — confirms fuse-timing drift is a real, current regression risk when the tick/scheduler engine changes.
  - Sources: https://deepwiki.com/PaperMC/Folia/2-region-threading-system , https://github.com/PaperMC/Folia/issues/309

### B.4 Player-facing complaints across the prior art
Determinism/desync (cannon misses, "boost" deviating from vanilla), invisible-TNT desync (FluxSpigot approach), east/west vs N/S asymmetry, TNT exploding early/late, lag spikes from un-merged TNT swarms, and anti-lag despawns (`height-nerf`) silently deleting payloads mid-flight.

---

## PART C — Pitfalls for OUR custom off-thread engine

1. **RNG reproduction.** Vanilla TNT uses `Random` for: spawn kick (±0.02), explosion-ignited fuse (10–30), and block-ray intensity (0.7–1.3). An off-thread engine sharing/forking the world `Random` will (a) be non-deterministic across threads and (b) **corrupt the main-thread RNG sequence** other systems depend on. Trap: the "Randar" exploit shows reused `java.util.Random` state is recoverable/observable. Mitigation: give the engine its own seeded, per-cannon-shot RNG (or zero the kicks and fix the fuse like the forks do) and never advance the shared world RNG from a worker thread.

2. **Intra-tick ordering vs other entities.** If TNT physics runs off-thread, the **spawn-order iteration**, the move→fuse→explode per-entity sequence, and knockback **accumulation order within a tick** can reorder relative to vanilla. Even one reorder changes the summed knockback vector → aim drift. Must reproduce a stable, vanilla-equivalent ordering for all entities the blast touches, not just engine-owned TNT.

3. **Per-axis movement asymmetry.** Replicate Y→X→Z (with the |dx|>|dz| Z-before-X correction) bit-for-bit, or east/west and north/south cannons will land in different spots. This is collision-resolution order, easy to get subtly wrong in a rewrite.

4. **Block-snapshot staleness.** Off-thread movement/collision reads block state. If the engine snapshots blocks at tick start but redstone/water/piston changes them mid-tick (or the main thread edits concurrently), the TNT collides against a stale world → wrong path or phases through walls. Need a consistent, tick-coherent block view and a defined ordering between water-flow/redstone updates and engine movement.

5. **Folia cross-region.** A blast or a fast payload spanning two regions violates the single-owner rule. Knockback applied to entities in another region, or block destruction across the boundary, must be marshalled onto the target region's thread — which serializes and **delays by a tick**, drifting fuse/impact timing. A high-velocity payload crosses region boundaries every few ticks. Plan ownership handoff and cross-region task scheduling explicitly; never touch foreign-region entities/chunks directly.

6. **Chain-detonation ordering.** When an explosion primes neighboring TNT, vanilla assigns each a random 10–30 fuse and they tick in spawn order. Off-thread batching can reorder the chain or collapse the staggered fuses → the whole stack goes off on one tick (visible "instant" detonation, wrong knockback, the #309 "early explosion" class of bug). Preserve per-TNT fuse values and chain ordering.

7. **Knockback to non-engine entities.** Explosions push players, items, mobs, boats, etc. If the engine only updates its own TNT and applies player/entity knockback on a worker thread, you get anticheat conflicts (see #8), thread-safety violations (mutating a player's velocity off the main thread), and desync. Entity-affecting effects must be applied on the owning thread.

8. **Anticheat reactions.** Server-applied explosion velocity that the anticheat didn't predict (especially if applied a tick off, or applied off-thread so the velocity packet ordering shifts) triggers false flags for fly/speed/velocity. Cannon servers already fight this; an async engine that changes *when* knockback packets are sent makes it worse. Emit `PlayerVelocityEvent`/velocity packets on the main thread in vanilla order.

9. **Duplication / item exploits.** TNT/sand machines and stacking are classic dupe vectors; Paper patches several by default. An async engine that defers `addEntity`/`removeEntity` or processes detonation out of band can create windows where a TNT exists in two states (counted twice, dropped twice) → dupes. Make spawn/remove and drop-resolution atomic with respect to the main-thread entity list.

10. **Anti-lag despawns deleting payloads.** `tnt-entity-height-nerf` / `falling-block-height-nerf` and entity-tick-range limits can silently delete in-flight payloads or freeze them (the lazy-chunk #3051 behavior). The engine must own/keep-alive its in-flight payloads regardless of view/tick-range, or shots randomly vanish.

11. **Optimization caches that change results.** Paper's `optimize-explosions` density cache is keyed on float positions; reusing it across an off-thread engine (or caching exposure between explosions) diverges from vanilla — which is exactly why SulphurSpigot *removed* it. Don't cache exposure/knockback across explosions unless you can prove identical inputs.

12. **Float reproducibility across threads/JIT.** Use strict, identical float ops; `strictfp`-equivalent care. Different code paths (your engine vs vanilla fallback) computing the same blast with different operation order/order-of-summation can produce different last-bit results → cumulative aim drift over a long flight.

### C.1 Recommended invariants for the engine (design checklist)
- Deterministic, engine-owned RNG; never advance shared world RNG off-thread. Prefer zeroed spawn kick + fixed 80 fuse (fork-proven).
- Reproduce: entity spawn-order iteration; Y→X→Z move with the east/west correction; move→fuse→explode per-entity; tick-coherent block snapshot ordered after water/redstone.
- Knockback origin = TNT feet/center (`length/2`), accumulate per-tick in vanilla order.
- Apply all effects on **non-engine** entities (players/mobs/items) and all entity add/remove on the **owning main/region thread**, atomically.
- On Folia: explicit region-ownership handoff for crossing payloads; marshal cross-region blast effects; accept/measure the 1-tick serialization cost.
- Keep in-flight payloads exempt from height-nerf/tick-range despawn.
- Emit velocity packets in vanilla order/timing for anticheat compatibility.

---

## Sources
- TNT cannon mechanics & per-tick processing: https://technical-minecraft.fandom.com/wiki/TNT_Cannon_Mechanics
- Cannon anatomy / water / charges / auto cannons: https://minecraft.wiki/w/Tutorial:TNT_cannons and https://minecraft.wiki/w/Tutorial:TNT_cannons/moving_TNT
- Explosion algorithm, knockback formula, rays, RNG (0.7–1.3), exposure, TNT feet-vs-eyes, knockback accumulation: https://minecraft.wiki/w/Explosion
- Fuse timing (80 vs 10–30), spawn offset & velocity: https://minecraft.wiki/w/TNT and https://minecraft.wiki/w/Explosion
- Entity tick = spawn order; intra-tick ordering: https://minecraft.wiki/w/Tick and https://techmcdocs.github.io/pages/GameTick/
- "Fix cannons" exact semantics (motX/Z=0, fuse=80, head=length/2, water W(), block-Y): SulphurSpigot patches 0024/0025/0027/0028 — https://github.com/AmbrosL/SulphurSpigot/tree/master/PaperSpigot-Server-Patches
- TacoSpigot TNT/falling-block movement + liquid-explosion optimization: https://github.com/VictorML11/FluxSpigot/blob/master/PaperSpigot-Server-Patches/0024-Optimize-tnt-entity-and-falling-block-movement.patch
- "Async TNT" marketing = packet suppression, not off-thread sim; desync complaints: https://github.com/VictorML11/FluxSpigot/blob/master/PaperSpigot-Server-Patches/0030-flux-optimize-tnt.patch and https://builtbybit.com/threads/fluxspigot-jar-free-async-tnt-best-cannon-jar-big-update-coming-soon-perfect-for-factions.326279/
- Paper explosion cache (optimize-explosions) internals & per-tick clear: https://github.com/PaperMC/Paper-1.7/blob/master/Spigot-Server-Patches/0063-Optimize-explosions.patch
- Paper world-config TNT/explosion knobs: https://docs.papermc.io/paper/reference/world-configuration/
- Paper cannon regressions (extra AoE, directional bias, sand/stacking): https://github.com/PaperMC/Paper/issues/329
- Lazy-chunk / entity-tick-range cannon bug: https://github.com/PaperMC/Paper/issues/3051
- TNT exploding early (Moonrise) — live fuse-timing regression: https://github.com/PaperMC/Folia/issues/309
- Folia region threading (ownership rule, per-region RNG, cross-region explosions): https://deepwiki.com/PaperMC/Folia/2-region-threading-system
- TNT dupe exploits / Paper default patches / anti-lag: https://xgamingserver.com/docs/minecraft/configure-tnt-exploits and https://www.spigotmc.org/resources/anti-food-dupe-and-tnt-duplication-exploit-fix.71005/
- RNG state observability (Randar) — caution on shared java.util.Random: https://github.com/spawnmason/randar-explanation