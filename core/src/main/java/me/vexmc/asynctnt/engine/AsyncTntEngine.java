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
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import me.vexmc.asynctnt.api.event.AsyncTntTakeoverEvent;
import me.vexmc.asynctnt.common.engine.EntityPush;
import me.vexmc.asynctnt.common.engine.ExplosionInput;
import me.vexmc.asynctnt.common.engine.ExplosionResult;
import me.vexmc.asynctnt.common.engine.MotionIntegrator;
import me.vexmc.asynctnt.common.engine.MotionResult;
import me.vexmc.asynctnt.common.engine.ExplosionSolver;
import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.BlockPos;
import me.vexmc.asynctnt.common.math.Mth;
import me.vexmc.asynctnt.common.math.Vec3d;
import me.vexmc.asynctnt.common.rng.RayFloats;
import me.vexmc.asynctnt.common.rng.SeededRng;
import me.vexmc.asynctnt.common.scheduling.Scheduling;
import me.vexmc.asynctnt.common.snapshot.BlastResistanceView;
import me.vexmc.asynctnt.common.snapshot.BlockCollisionView;
import me.vexmc.asynctnt.common.snapshot.BodyState;
import me.vexmc.asynctnt.common.snapshot.EntitySnapshot;
import me.vexmc.asynctnt.common.snapshot.FluidView;
import me.vexmc.asynctnt.common.version.ForkFlags;
import me.vexmc.asynctnt.common.version.PhysicsProfile;
import me.vexmc.asynctnt.config.AsyncTntConfig;
import me.vexmc.asynctnt.nms.NmsAccess;

/**
 * The off-thread TNT/falling-block engine. On takeover it neutralizes the
 * vanilla tick and drives each body via the {@link Scheduling} seam (region-
 * correct on Folia, main-thread on Paper). Per tick it integrates movement
 * inline on the owning thread (cheap, deterministic) using live collision/fluid
 * views; on detonation it snapshots the blast cube + entities, then runs the
 * heavy 1352-ray solve on the worker pool and applies the result per owning
 * region. Any error on a body returns it to vanilla ticking — never dropped.
 *
 * <p>The explosion's per-ray RNG is seeded deterministically from the centre
 * and world time via {@link SeededRng}, so it never touches {@code level.random}
 * (the cardinal safety rule) while staying reproducible and vanilla-equivalent;
 * the cannon-relevant knockback is RNG-free and therefore exact.
 */
public final class AsyncTntEngine implements EngineHandle {

    private static final float TNT_POWER = 4.0f;
    private static final int BLAST_RADIUS = 8; // power-4 rays reach < ~6 blocks; 8 is safe.

    private final JavaPlugin plugin;
    private final Scheduling scheduling;
    private final NmsAccess nms;
    private final Supplier<AsyncTntConfig> config;
    private final PhysicsProfile profile;
    private final Map<UUID, EngineBody> bodies = new ConcurrentHashMap<>();

    private volatile ExecutorService workers;
    private volatile boolean active;

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

    // ── EngineHandle ─────────────────────────────────────────────────────────
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

    // ── takeover ─────────────────────────────────────────────────────────────
    /** Called on the spawn's owning thread by the spawn interceptor. */
    public void takeOver(Entity entity) {
        if (!isActive()) {
            return;
        }
        if (!config.get().enabledIn(entity.getWorld().getName())) {
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
        ForkFlags forks = config.get().forkFlags();
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
        body.driver = scheduling.repeatOn(entity, 1L, 1L, () -> tick(body), () -> retire(body));
        plugin.getServer().getPluginManager().callEvent(new AsyncTntTakeoverEvent(entity));
    }

    // ── per-tick (owning thread) ─────────────────────────────────────────────
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
        if (!config.get().enabledIn(world.getName())) {
            forceVanilla(entity); // kill-switch / per-world disable: hand back to vanilla
            return;
        }
        try {
            BodyState s = body.state;
            Aabb box = s.boundingBox();
            Aabb bounds = box.expandTowards(s.dx(), s.dy() - 0.04, s.dz());
            BlockCollisionView blocks = nms.captureCollision(world, bounds);
            FluidView fluids = s.kind() == BodyState.Kind.TNT ? nms.captureFluid(world, box) : FluidView.EMPTY;

            MotionResult result = MotionIntegrator.tick(s, blocks, fluids, profile, config.get().forkFlags());
            body.state = result.state();
            nms.applyState(entity, body.state);

            if (result.detonate()) {
                detonate(body);
            } else if (result.landed()) {
                land(body);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AsyncTNT body errored; returning it to vanilla: " + t);
            forceVanilla(entity);
        }
    }

    // ── detonation (snapshot here, solve off-thread, apply per region) ────────
    private void detonate(EngineBody body) {
        Entity entity = body.entity;
        World world = entity.getWorld();
        Vec3d center = nms.explosionCenter(entity);

        SeededRng rng = new SeededRng(seedFor(center, world));
        float[] rays = RayFloats.draw(rng::nextFloat);
        BlastResistanceView blast = nms.captureBlast(world, center, BLAST_RADIUS);
        List<EntitySnapshot> entities = nms.captureEntities(world, center, TNT_POWER * 2.0, entity.getEntityId());
        ExplosionInput input = new ExplosionInput(center, TNT_POWER, false, blast, rays, entities, profile);

        removeBodyAndEntity(body); // the TNT is gone the moment it detonates

        Location at = new Location(world, center.x(), center.y(), center.z());
        ExecutorService w = this.workers;
        if (active && w != null && !w.isShutdown()) {
            w.submit(() -> {
                ExplosionResult solved;
                try {
                    solved = ExplosionSolver.solve(input);
                } catch (Throwable t) {
                    plugin.getLogger().warning("AsyncTNT explosion solve failed: " + t);
                    return;
                }
                scheduling.runAt(at, () -> applyExplosion(world, center, solved));
            });
        } else {
            applyExplosion(world, center, ExplosionSolver.solve(input));
        }
    }

    private void applyExplosion(World world, Vec3d center, ExplosionResult result) {
        // Block destruction: bucket by chunk so each batch applies on its owning region (Folia).
        Map<Long, List<BlockPos>> byChunk = new HashMap<>();
        for (BlockPos pos : result.broken()) {
            long key = (((long) (pos.x() >> 4)) << 32) | ((pos.z() >> 4) & 0xFFFFFFFFL);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(pos);
        }
        for (List<BlockPos> bucket : byChunk.values()) {
            BlockPos any = bucket.get(0);
            Location chunkLoc = new Location(world, (any.x() & ~15) + 8, center.y(), (any.z() & ~15) + 8);
            ExplosionResult sub = new ExplosionResult(bucket, List.of());
            scheduling.runAt(chunkLoc, () -> nms.destroyBlocks(world, sub));
        }
        // Knockback: each push on the victim's own region thread.
        for (EntityPush push : result.pushes()) {
            Entity victim = nms.resolveEntity(world, push.entityId());
            if (victim != null) {
                scheduling.runOn(victim, () -> nms.applyPush(world, push), () -> { });
            }
        }
        nms.emitExplosionEffects(world, center);
    }

    // ── falling-block landing (owning thread) ────────────────────────────────
    private void land(EngineBody body) {
        Entity entity = body.entity;
        World world = entity.getWorld();
        BodyState s = body.state;
        int bx = Mth.floor(s.x());
        int by = Mth.floor(s.y());
        int bz = Mth.floor(s.z());
        BlockData data = body.fallingBlockData;
        removeBodyAndEntity(body);
        if (data == null) {
            return;
        }
        Block block = world.getBlockAt(bx, by, bz);
        if (block.getType().isAir() || block.isLiquid()) {
            block.setBlockData(data, true);
        } else {
            world.dropItem(new Location(world, bx + 0.5, by + 0.5, bz + 0.5),
                    new ItemStack(data.getMaterial()));
        }
    }

    // ── lifecycle helpers ────────────────────────────────────────────────────
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
            // forceVanilla is invoked on the entity's owning thread (command,
            // kill-switch reaction, or the test sync), so restore immediately —
            // deferring a tick left a window where the entity stayed neutralized
            // (gravity off, fuse held) and never resumed vanilla ticking.
            nms.restore(entity, body.state);
        }
    }

    private static long seedFor(Vec3d center, World world) {
        long h = Double.doubleToLongBits(center.x()) * 0x9E3779B97F4A7C15L;
        h ^= Double.doubleToLongBits(center.y()) * 0xC2B2AE3D27D4EB4FL;
        h ^= Double.doubleToLongBits(center.z()) * 0x165667B19E3779F9L;
        h ^= world.getFullTime();
        return h;
    }
}
