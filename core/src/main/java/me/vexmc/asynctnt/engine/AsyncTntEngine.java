package me.vexmc.asynctnt.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
        bodies.put(entity.getUniqueId(), body);

        // Tick-clock alignment: vanilla ticks a freshly-ignited TNT/falling block
        // on the SAME server tick it appears, but our per-entity driver's first
        // run is the NEXT tick. Advance one tick of physics now (fuse countdown +
        // motion) so detonation and movement land on the same tick vanilla's
        // would — without this every body lags vanilla by exactly one tick, which
        // is invisible for a lone TNT but makes timing-tuned cannons drift badly.
        // No teleport or detonation happens here (we ignore the result): we must
        // not move the entity or explode re-entrantly during its own spawn event;
        // the driver applies state and acts on any terminal flag next tick.
        try {
            integrate(body, entity.getWorld());
        } catch (Throwable alignFailure) {
            plugin.getLogger().warning("AsyncTNT spawn-tick alignment failed; body lags one tick: " + alignFailure);
        }

        body.driver = scheduling.repeatOn(entity, 1L, 1L, () -> tick(body), () -> retire(body));
        plugin.getServer().getPluginManager().callEvent(new AsyncTntTakeoverEvent(entity));
    }

    private void tick(EngineBody body) {
        if (body.released || !active) {
            return;
        }
        Entity entity = body.entity;
        if (!entity.isValid()) {
            retire(body);
            return;
        }
        World world = entity.getWorld();
        if (paused || !config.get().enabledIn(world.getName())) {
            forceVanilla(entity); // runtime pause / kill-switch / per-world disable
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
     * whether the caller should. Shared by the per-tick driver and the
     * spawn-tick alignment in {@link #takeOver}.
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
