package me.vexmc.asynctnt.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import me.vexmc.asynctnt.api.event.AsyncTntTakeoverEvent;
import me.vexmc.asynctnt.common.engine.EntityPush;
import me.vexmc.asynctnt.common.engine.ExplosionInput;
import me.vexmc.asynctnt.common.engine.ExplosionSolver;
import me.vexmc.asynctnt.common.engine.MotionIntegrator;
import me.vexmc.asynctnt.common.engine.MotionResult;
import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.BlockPos;
import me.vexmc.asynctnt.common.math.Vec3d;
import me.vexmc.asynctnt.common.rng.RayFloats;
import me.vexmc.asynctnt.common.rng.SeededRng;
import me.vexmc.asynctnt.common.scheduling.Scheduling;
import me.vexmc.asynctnt.common.snapshot.BlastResistanceView;
import me.vexmc.asynctnt.common.snapshot.BlockCollisionView;
import me.vexmc.asynctnt.common.snapshot.BodyState;
import me.vexmc.asynctnt.common.snapshot.FluidView;
import me.vexmc.asynctnt.common.version.PhysicsProfile;
import me.vexmc.asynctnt.config.AsyncTntConfig;
import me.vexmc.asynctnt.nms.NmsAccess;

/**
 * The off-thread TNT/falling-block engine. On takeover it neutralizes the
 * vanilla tick and drives each body via the {@link Scheduling} seam (region-
 * correct on Folia, main-thread on Paper). Movement is integrated inline on the
 * owning thread; explosion knockback (cannon-critical, RNG-free) is also applied
 * inline the same tick the TNT detonates, while the heavy 1352-ray block march
 * runs on a worker pool and is applied per owning region a tick later (block-
 * break latency is imperceptible). Vanilla's {@code ExplosionPrimeEvent} and
 * {@code EntityExplodeEvent} are fired so protection plugins behave exactly as
 * with vanilla. Any error on a body returns it to vanilla ticking.
 */
public final class AsyncTntEngine implements EngineHandle {

    private static final int MAX_FALLING_BLOCK_AGE = 600; // vanilla autoExpire
    private static final int OUT_OF_WORLD_GRACE = 100;     // vanilla out-of-world discard delay

    private final JavaPlugin plugin;
    private final Scheduling scheduling;
    private final NmsAccess nms;
    private final Supplier<AsyncTntConfig> config;
    private final PhysicsProfile profile;
    private final Map<UUID, EngineBody> bodies = new ConcurrentHashMap<>();
    /** Monotonic spawn-order index — the off-thread analogue of vanilla's entity tick order. */
    private final AtomicLong spawnOrder = new AtomicLong();

    private volatile ExecutorService workers;
    private volatile boolean active;
    private volatile boolean paused;

    public AsyncTntEngine(JavaPlugin plugin, Scheduling scheduling, NmsAccess nms,
                          Supplier<AsyncTntConfig> config, PhysicsProfile profile) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.nms = nms;
        this.config = config;
        this.profile = profile;
    }

    public void start() {
        if (!nms.available()) {
            plugin.getLogger().warning("NMS access unavailable — AsyncTNT engine stays off; TNT ticks vanilla.");
            return;
        }
        int threads = Math.max(1, config.get().workerThreads());
        AtomicInteger n = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "asynctnt-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.active = true;
    }

    public void stop() {
        this.active = false;
        for (EngineBody body : new ArrayList<>(bodies.values())) {
            forceVanilla(body.entity);
        }
        bodies.clear();
        ExecutorService w = this.workers;
        if (w != null) {
            w.shutdownNow();
        }
    }

    @Override
    public boolean isActive() {
        return active && nms.available();
    }

    @Override
    public int ownedCount() {
        return bodies.size();
    }

    @Override
    public boolean forceVanilla(@NotNull Entity entity) {
        EngineBody body = bodies.remove(entity.getUniqueId());
        if (body == null) {
            return false;
        }
        releaseToVanilla(body);
        return true;
    }

    @Override
    public @NotNull String schedulingBackend() {
        return scheduling.describe();
    }

    @Override
    public void setPaused(boolean p) {
        this.paused = p;
        if (p) {
            for (EngineBody body : new ArrayList<>(bodies.values())) {
                forceVanilla(body.entity);
            }
        }
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    /** Called on the spawn's owning thread by the spawn interceptor. */
    public void takeOver(Entity entity) {
        if (!isActive() || paused || !config.get().enabledIn(entity.getWorld().getName())) {
            return;
        }
        boolean tnt = nms.isPrimedTnt(entity);
        if (!tnt && !nms.isFallingBlock(entity)) {
            return;
        }
        if (bodies.containsKey(entity.getUniqueId())) {
            return;
        }
        BodyState state = nms.readBody(entity); // read the real fuse/velocity BEFORE neutralizing
        var forks = config.get().forkFlags();
        if (tnt && forks.zeroSpawnKick()) {
            state = state.withMotion(new Vec3d(0.0, state.dy(), 0.0));
        }
        if (tnt && forks.fixedFuse80()) {
            state = state.withFuseOrTime(80);
        }
        nms.neutralize(entity);
        BlockData blockData = (!tnt && entity instanceof FallingBlock fb) ? fb.getBlockData() : null;
        EngineBody body = new EngineBody(entity, state, blockData);
        body.spawnSeq = spawnOrder.getAndIncrement();
        bodies.put(entity.getUniqueId(), body);

        body.driver = scheduling.repeatOn(entity, 1L, 1L, () -> coordinatedTick(body), () -> retire(body));
        plugin.getServer().getPluginManager().callEvent(new AsyncTntTakeoverEvent(entity));
    }

    /**
     * The per-entity driver callback. Rather than tick only {@code trigger}, the
     * first body whose driver fires in a given server tick runs ONE ordered pass
     * over every engine body its region owns — in {@code spawnSeq} order — and the
     * other bodies' callbacks that tick become no-ops (their slice was already
     * integrated). This reproduces vanilla's single sequential entity pass: when a
     * body detonates mid-pass its knockback lands in the victim's {@link BodyState}
     * immediately, so a victim later in the order consumes it THIS tick (and one
     * earlier consumes it next tick) — exactly vanilla's same-tick rule, which the
     * old independent-per-body model could not guarantee (Folia fires the per-entity
     * schedulers in an unstable order, so a projectile could move before receiving
     * its launch push and have it eaten by the on-ground damping).
     *
     * <p>Region-safe by construction: a coordinator only ever integrates bodies the
     * current region thread owns ({@link NmsAccess#regionOwnsChunkAt}); a body in
     * another region is left to that region's own pass. On Paper everything is one
     * region, so it is a single global ordered pass. Any failure degrades to ticking
     * just the trigger (legacy behaviour) or returns the body to vanilla — it can
     * never crash the region.
     */
    private void coordinatedTick(EngineBody trigger) {
        if (trigger.released || !active) {
            return;
        }
        Entity te = trigger.entity;
        if (!te.isValid()) {
            retire(trigger);
            return;
        }
        World world = te.getWorld();
        if (paused || !config.get().enabledIn(world.getName())) {
            forceVanilla(te); // runtime pause / kill-switch / per-world disable
            return;
        }

        long tick;
        try {
            tick = world.getFullTime(); // shared per-tick clock; same value for every callback this tick
        } catch (Throwable noClock) {
            stepOne(trigger, world); // no region-safe clock — fall back to a single-body tick
            return;
        }
        if (trigger.lastPassTick == tick) {
            return; // an earlier coordinator already ran this region's pass this tick
        }

        List<EngineBody> pass;
        try {
            pass = new ArrayList<>();
            for (EngineBody b : bodies.values()) {
                if (b.released || b.lastPassTick == tick) {
                    continue;
                }
                if (!nms.regionOwnsChunkAt(world, b.state.x(), b.state.z())) {
                    continue; // owned by another region — its own pass will tick it
                }
                pass.add(b);
            }
            pass.sort(Comparator.comparingLong(b -> b.spawnSeq));
        } catch (Throwable buildFailed) {
            stepOne(trigger, world); // degrade to a single-body tick
            return;
        }

        for (EngineBody b : pass) {
            if (b.released || b.lastPassTick == tick) {
                continue; // detonation earlier in the pass may have removed it
            }
            b.lastPassTick = tick;
            stepOne(b, world);
        }
    }

    /** Advance one body one tick: integrate, apply, then detonate / land / despawn. Errors return it to vanilla. */
    private void stepOne(EngineBody body, World world) {
        Entity entity = body.entity;
        if (body.released) {
            return;
        }
        if (!entity.isValid()) {
            retire(body);
            return;
        }
        try {
            MotionResult result = integrate(body, world);
            nms.applyState(entity, body.state);

            if (result.detonate()) {
                detonate(body);
            } else if (result.landed()) {
                land(body);
            } else if (body.state.kind() == BodyState.Kind.FALLING_BLOCK && shouldDespawn(body.state, world)) {
                // Vanilla discards falling blocks past their age / out of the world.
                removeBodyAndEntity(body);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AsyncTNT body errored; returning it to vanilla: " + t);
            forceVanilla(entity);
        }
    }

    /**
     * Advance one tick of physics — fuse/age countdown, gravity, collision,
     * fluids — updating {@link EngineBody#state}. Pure state evolution: it does
     * not move the entity or fire detonation/landing; the returned result says
     * whether the caller should. Invoked once per body per tick from the ordered
     * pass in {@link #stepOne}.
     */
    private MotionResult integrate(EngineBody body, World world) {
        BodyState s = body.state;
        Aabb box = s.boundingBox();
        Aabb bounds = box.expandTowards(s.dx(), s.dy() - 0.04, s.dz());
        BlockCollisionView blocks = nms.captureCollision(world, bounds);
        FluidView fluids = s.kind() == BodyState.Kind.TNT ? nms.captureFluid(world, box) : FluidView.EMPTY;
        MotionResult result = MotionIntegrator.tick(s, blocks, fluids, profile, config.get().forkFlags());
        body.state = result.state();
        return result;
    }

    private static boolean shouldDespawn(BodyState s, World world) {
        int age = s.fuseOrTime();
        if (age > MAX_FALLING_BLOCK_AGE) {
            return true;
        }
        return age > OUT_OF_WORLD_GRACE && (s.y() < world.getMinHeight() || s.y() >= world.getMaxHeight());
    }

    // ── detonation: ExplosionPrimeEvent -> inline knockback -> offload blocks -> EntityExplodeEvent ──
    private void detonate(EngineBody body) {
        Entity tnt = body.entity;
        World world = tnt.getWorld();

        NmsAccess.PrimeResult prime = nms.fireExplosionPrime(tnt);
        if (prime.cancelled()) {
            removeBodyAndEntity(body); // vanilla discards the TNT even when the prime is cancelled
            return;
        }
        float power = prime.radius();
        boolean fire = prime.fire();
        Vec3d center = nms.explosionCenter(tnt);

        // Inline, same-tick knockback (cannon aim) — RNG-free, applied to live entities.
        for (NmsAccess.ExplosionTarget target : nms.captureExplosionTargets(world, center, power * 2.0, tnt.getEntityId())) {
            EntityPush push = ExplosionSolver.knockbackFor(center, power, target.snapshot());
            if (push == null) {
                continue;
            }
            EngineBody victimBody = bodies.get(target.entity().getUniqueId());
            if (victimBody != null) {
                // The victim is engine-owned (another TNT/falling block — e.g. a
                // cannon's projectile). Its shadow entity's Bukkit velocity is
                // zeroed by its own applyState every tick and the engine drives
                // motion from this authoritative state, so the knockback MUST be
                // added here, not via setVelocity. This is what makes cannons fire.
                BodyState vs = victimBody.state;
                victimBody.state = vs.withMotion(vs.motion().add(push.knockback()));
            } else {
                // Not engine-owned (player, mob, item, …): vanilla ticks it, so the
                // Bukkit velocity (and damage) is the right place.
                nms.applyPush(target.entity(), push.knockback(), push.damage());
            }
        }

        // Snapshot the blast cube + pre-draw the per-ray RNG (never touches level.random).
        long seed = seedFor(center, world);
        float[] rays = RayFloats.draw(new SeededRng(seed)::nextFloat);
        int radius = blastRadius(power);
        BlastResistanceView blast = nms.captureBlast(world, center, radius);
        ExplosionInput input = new ExplosionInput(center, power, fire, blast, rays, List.of(), profile);

        // Release the body (keep the entity reference for EntityExplodeEvent + removal in the apply).
        body.released = true;
        if (body.driver != null) {
            body.driver.cancel();
        }
        bodies.remove(tnt.getUniqueId());

        Location at = new Location(world, center.x(), center.y(), center.z());
        ExecutorService w = this.workers;
        if (active && w != null && !w.isShutdown()) {
            w.submit(() -> {
                List<BlockPos> broken;
                try {
                    broken = ExplosionSolver.solveBlocks(input);
                } catch (Throwable t) {
                    plugin.getLogger().warning("AsyncTNT explosion solve failed: " + t);
                    scheduling.runAt(at, () -> {
                        nms.emitExplosionEffects(world, center);
                        nms.removeEntity(tnt);
                    });
                    return;
                }
                scheduling.runAt(at, () -> applyExplosion(world, tnt, center, broken, power, seed));
            });
        } else {
            applyExplosion(world, tnt, center, ExplosionSolver.solveBlocks(input), power, seed);
        }
    }

    private void applyExplosion(World world, Entity tnt, Vec3d center, List<BlockPos> broken, float power, long seed) {
        float yield = 1.0f / power;
        NmsAccess.ExplodeResult exploded = nms.fireEntityExplode(tnt, center, broken, yield);
        if (!exploded.cancelled()) {
            // Bucket the (event-filtered) blocks by chunk so each batch applies on its owning region.
            Map<Long, List<BlockPos>> byChunk = new HashMap<>();
            for (BlockPos pos : exploded.blocks()) {
                long key = (((long) (pos.x() >> 4)) << 32) | ((pos.z() >> 4) & 0xFFFFFFFFL);
                byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(pos);
            }
            int bucketIndex = 0;
            for (List<BlockPos> bucket : byChunk.values()) {
                BlockPos any = bucket.get(0);
                Location chunkLoc = new Location(world, (any.x() & ~15) + 8, center.y(), (any.z() & ~15) + 8);
                long bucketSeed = seed * 31 + (bucketIndex++);
                float finalYield = exploded.yield();
                scheduling.runAt(chunkLoc, () -> nms.destroyBlocks(world, bucket, finalYield, bucketSeed));
            }
        }
        nms.emitExplosionEffects(world, center);
        nms.removeEntity(tnt);
    }

    private void land(EngineBody body) {
        Entity entity = body.entity;
        World world = entity.getWorld();
        BodyState state = body.state;
        BlockData data = body.fallingBlockData;
        body.released = true;
        if (body.driver != null) {
            body.driver.cancel();
        }
        bodies.remove(entity.getUniqueId());
        nms.removeEntity(entity);
        nms.landFallingBlock(world, state, data);
    }

    private void retire(EngineBody body) {
        body.released = true;
        bodies.remove(body.entity.getUniqueId());
    }

    private void removeBodyAndEntity(EngineBody body) {
        body.released = true;
        bodies.remove(body.entity.getUniqueId());
        if (body.driver != null) {
            body.driver.cancel();
        }
        nms.removeEntity(body.entity);
    }

    private void releaseToVanilla(EngineBody body) {
        body.released = true;
        if (body.driver != null) {
            body.driver.cancel();
        }
        Entity entity = body.entity;
        if (entity.isValid()) {
            // forceVanilla runs on the entity's owning thread; restore immediately
            // so the entity resumes vanilla ticking with no neutralized window.
            nms.restore(entity, body.state);
        }
    }

    private static int blastRadius(float power) {
        // Ray reach ~= power * (0.7..1.3) / 0.22500001 * 0.3 ~= power * 1.8; pad by 1 and cap.
        return Math.min(48, (int) Math.ceil(power * 1.8) + 1);
    }

    private static long seedFor(Vec3d center, World world) {
        long h = Double.doubleToLongBits(center.x()) * 0x9E3779B97F4A7C15L;
        h ^= Double.doubleToLongBits(center.y()) * 0xC2B2AE3D27D4EB4FL;
        h ^= Double.doubleToLongBits(center.z()) * 0x165667B19E3779F9L;
        h ^= world.getFullTime();
        return h;
    }
}
