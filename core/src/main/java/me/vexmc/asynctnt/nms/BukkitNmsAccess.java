package me.vexmc.asynctnt.nms;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.BlockPos;
import me.vexmc.asynctnt.common.math.Mth;
import me.vexmc.asynctnt.common.math.Vec3d;
import me.vexmc.asynctnt.common.rng.SeededRng;
import me.vexmc.asynctnt.common.snapshot.BlastResistanceView;
import me.vexmc.asynctnt.common.snapshot.BlockCollisionView;
import me.vexmc.asynctnt.common.snapshot.BodyState;
import me.vexmc.asynctnt.common.snapshot.EntitySnapshot;
import me.vexmc.asynctnt.common.snapshot.FluidView;

/**
 * Bukkit-API-based {@link NmsAccess}. Body read/write, collision boxes, entity
 * gather with an exact {@code getSeenPercent} rayTrace port, the vanilla
 * explosion events (so protection plugins work like vanilla), block destruction
 * + drops, knockback, and effects are all done through the public API. The two
 * values the API does not expose — explosion resistance and fluid flow — use a
 * vanilla-constant table and a flow approximation here; {@link NmsReflection}
 * upgrades them to the exact NMS values when available. Effect/event lookups are
 * defensive so a per-version rename never crashes.
 */
public final class BukkitNmsAccess implements NmsAccess {

    /**
     * Vanilla fuse held high while the engine owns the TNT, so the server can
     * never win the fuse-countdown race and detonate it itself (which it could
     * with a small held value — version-dependent tick ordering let vanilla
     * reach 0 first on 1.19.4 / 1.20.6). The engine detonates from its own fuse.
     */
    private static final int HELD_FUSE = 72_000;

    private final NmsReflection reflection;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final boolean folia;
    /** 0 = untested, 1 = exact NMS reflection trusted, -1 = use the constant table. */
    private volatile int resistanceTrust;
    /** One-time diagnostic: which movement path applyState actually takes (setPos vs teleport). */
    private volatile boolean loggedApplyPath;

    public BukkitNmsAccess(org.bukkit.plugin.java.JavaPlugin plugin) {
        this(plugin, false);
    }

    public BukkitNmsAccess(org.bukkit.plugin.java.JavaPlugin plugin, boolean folia) {
        this.plugin = plugin;
        this.folia = folia;
        this.reflection = new NmsReflection(plugin);
    }

    /**
     * Exact NMS resistance where the reflection is proven reliable, else the
     * vanilla-constant table. The reflection is validated once against a
     * table-known block: it silently returns wrong values on some spigot-mapped
     * versions (1.19.4 / 1.20.6 observed), so we trust it only where it agrees
     * with the known constant, falling back to the table everywhere else.
     */
    private float resistanceFor(Block block, Material m) {
        if (resistanceTrust == -1) {
            return blastResistance(m);
        }
        float exact = reflection.explosionResistance(block);
        if (Float.isNaN(exact)) {
            return blastResistance(m); // reflection unavailable for this block
        }
        if (resistanceTrust == 0) {
            Float known = RESISTANCE.get(m);
            if (known != null) {
                if (Math.abs(exact - known) <= Math.max(2.0f, known)) {
                    resistanceTrust = 1;
                } else {
                    resistanceTrust = -1;
                    plugin.getLogger().info("Exact NMS explosion resistance disagrees with the known "
                            + m + " constant (" + exact + " vs " + known + "); using the constant table.");
                    return blastResistance(m);
                }
            }
        }
        return exact;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean isPrimedTnt(@NotNull Entity entity) {
        return entity instanceof TNTPrimed;
    }

    @Override
    public boolean isFallingBlock(@NotNull Entity entity) {
        return entity instanceof FallingBlock;
    }

    @Override
    public void neutralize(@NotNull Entity entity) {
        entity.setGravity(false);
        entity.setVelocity(new Vector(0, 0, 0));
        if (entity instanceof TNTPrimed tnt) {
            tnt.setFuseTicks(72_000);
        }
    }

    @Override
    public void restore(@NotNull Entity entity, @NotNull BodyState state) {
        entity.setGravity(true);
        entity.setVelocity(new Vector(state.dx(), state.dy(), state.dz()));
        if (entity instanceof TNTPrimed tnt) {
            tnt.setFuseTicks(Math.max(1, state.fuseOrTime()));
        }
    }

    @Override
    public @NotNull BodyState readBody(@NotNull Entity entity) {
        Location l = entity.getLocation();
        Vector v = entity.getVelocity();
        if (entity instanceof TNTPrimed tnt) {
            return BodyState.tnt(l.getX(), l.getY(), l.getZ(), v.getX(), v.getY(), v.getZ(), tnt.getFuseTicks());
        }
        return BodyState.fallingBlock(l.getX(), l.getY(), l.getZ(),
                v.getX(), v.getY(), v.getZ(), entity.getTicksLived(), 0);
    }

    @Override
    public @NotNull BlockCollisionView captureCollision(@NotNull World world, @NotNull Aabb bounds) {
        return new BlockCollisionView() {
            @Override
            public List<Aabb> collisionBoxes(Aabb q) {
                List<Aabb> out = new ArrayList<>();
                int minX = Mth.floor(q.minX()), maxX = Mth.floor(q.maxX());
                int minY = Mth.floor(q.minY()), maxY = Mth.floor(q.maxY());
                int minZ = Mth.floor(q.minZ()), maxZ = Mth.floor(q.maxZ());
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            Block b = world.getBlockAt(x, y, z);
                            Material m = b.getType();
                            // Liquids and air never collide (TNT/sand fall through). A block is a
                            // collider if it is solid OR not passable — relying on Material.isSolid()
                            // as well as isPassable() because isPassable() proved unreliable for
                            // some solids on 1.19.4 / 1.20.6 (bodies tunnelled through the floor).
                            if (m.isAir() || b.isLiquid()) {
                                continue;
                            }
                            if (b.isPassable() && !m.isSolid()) {
                                continue;
                            }
                            BoundingBox bb = b.getBoundingBox();
                            if (bb == null || bb.getVolume() < 1.0E-9) {
                                out.add(new Aabb(x, y, z, x + 1.0, y + 1.0, z + 1.0)); // degenerate -> full cube
                            } else {
                                out.add(new Aabb(bb.getMinX(), bb.getMinY(), bb.getMinZ(),
                                        bb.getMaxX(), bb.getMaxY(), bb.getMaxZ()));
                            }
                        }
                    }
                }
                return out;
            }

            @Override
            public double blockSpeedFactor(double x, double y, double z) {
                Material m = world.getBlockAt(Mth.floor(x), Mth.floor(y), Mth.floor(z)).getType();
                return (m == Material.SOUL_SAND || m == Material.HONEY_BLOCK) ? 0.4 : 1.0;
            }
        };
    }

    @Override
    public @NotNull FluidView captureFluid(@NotNull World world, @NotNull Aabb bounds) {
        return deflated -> {
            List<FluidView.FluidCell> cells = new ArrayList<>();
            int minX = Mth.floor(deflated.minX()), maxX = Mth.floor(deflated.maxX());
            int minY = Mth.floor(deflated.minY()), maxY = Mth.floor(deflated.maxY());
            int minZ = Mth.floor(deflated.minZ()), maxZ = Mth.floor(deflated.maxZ());
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block b = world.getBlockAt(x, y, z);
                        if (b.getType() != Material.WATER) {
                            continue;
                        }
                        Vec3d flow = reflection.fluidFlow(b);
                        float height = reflection.fluidHeight(b);
                        if (flow == null) {
                            flow = approximateFlow(world, x, y, z);
                        }
                        if (Float.isNaN(height)) {
                            height = waterHeight(b);
                        }
                        cells.add(new FluidView.FluidCell(y, height, flow));
                    }
                }
            }
            return cells;
        };
    }

    private static float waterHeight(Block b) {
        if (b.getBlockData() instanceof org.bukkit.block.data.Levelled lev) {
            int level = lev.getLevel();
            if (level == 0) {
                return 0.8888889f;
            }
            int falling = level >= 8 ? 8 : (8 - level);
            return falling / 9.0f;
        }
        return 0.8888889f;
    }

    private static Vec3d approximateFlow(World world, int x, int y, int z) {
        double fx = 0, fz = 0;
        float here = waterHeight(world.getBlockAt(x, y, z));
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            Block n = world.getBlockAt(x + d[0], y, z + d[1]);
            float there;
            if (n.getType() == Material.WATER) {
                there = waterHeight(n);
            } else if (n.isPassable() || n.isEmpty()) {
                there = 0.0f;
            } else {
                continue;
            }
            double diff = here - there;
            if (diff > 0) {
                fx += d[0] * diff;
                fz += d[1] * diff;
            }
        }
        return new Vec3d(fx, 0, fz);
    }

    @Override
    public @NotNull BlastResistanceView captureBlast(@NotNull World world, @NotNull Vec3d center, int radius) {
        int cx = Mth.floor(center.x()), cy = Mth.floor(center.y()), cz = Mth.floor(center.z());
        int side = 2 * radius + 1;
        int minWorldY = world.getMinHeight();
        int maxWorldY = world.getMaxHeight();
        float[] resistance = new float[side * side * side];
        boolean[] air = new boolean[side * side * side];
        boolean[] destroyable = new boolean[side * side * side];
        boolean[] outside = new boolean[side * side * side];
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int i = ((dx + radius) * side + (dy + radius)) * side + (dz + radius);
                    int wy = cy + dy;
                    if (wy < minWorldY || wy >= maxWorldY) {
                        outside[i] = true;
                        resistance[i] = Float.NaN;
                        continue;
                    }
                    Block block = world.getBlockAt(cx + dx, wy, cz + dz);
                    Material m = block.getType();
                    boolean isAir = m.isAir();
                    air[i] = isAir;
                    resistance[i] = isAir ? Float.NaN : resistanceFor(block, m);
                    destroyable[i] = !isAir && isDestroyable(m);
                }
            }
        }
        return new CubeBlastView(cx, cy, cz, radius, side, resistance, air, destroyable, outside);
    }

    private record CubeBlastView(int cx, int cy, int cz, int radius, int side,
                                 float[] resistance, boolean[] air, boolean[] destroyable, boolean[] outside)
            implements BlastResistanceView {
        private int idx(int x, int y, int z) {
            int lx = x - cx + radius, ly = y - cy + radius, lz = z - cz + radius;
            if (lx < 0 || lx >= side || ly < 0 || ly >= side || lz < 0 || lz >= side) {
                return -1;
            }
            return (lx * side + ly) * side + lz;
        }

        @Override
        public float resistanceOrNaN(int x, int y, int z) {
            int i = idx(x, y, z);
            return i < 0 ? Float.NaN : resistance[i];
        }

        @Override
        public boolean outOfWorld(int x, int y, int z) {
            int i = idx(x, y, z);
            return i < 0 || outside[i];
        }

        @Override
        public boolean destroyable(int x, int y, int z) {
            int i = idx(x, y, z);
            return i >= 0 && destroyable[i];
        }

        @Override
        public boolean nonAir(int x, int y, int z) {
            int i = idx(x, y, z);
            return i >= 0 && !air[i];
        }

        @Override
        public int blockStateId(int x, int y, int z) {
            return 0;
        }
    }

    @Override
    public @NotNull List<ExplosionTarget> captureExplosionTargets(@NotNull World world, @NotNull Vec3d center,
                                                                  double diameter, long ignoredEntityId) {
        Location c = new Location(world, center.x(), center.y(), center.z());
        double reach = diameter + 1.0;
        Collection<Entity> near = world.getNearbyEntities(c, reach, reach, reach);
        List<ExplosionTarget> out = new ArrayList<>();
        for (Entity e : near) {
            if (e.getEntityId() == ignoredEntityId || !e.isValid()) {
                continue;
            }
            boolean tnt = e instanceof TNTPrimed;
            boolean living = e instanceof LivingEntity;
            Location feetLoc = e.getLocation();
            Vec3d feet = new Vec3d(feetLoc.getX(), feetLoc.getY(), feetLoc.getZ());
            BoundingBox bb = e.getBoundingBox();
            // Knockback direction origin (vanilla ServerExplosion): primed TNT uses
            // getY() (feet); EVERY other entity uses getEyeY(). For living entities
            // that's getEyeLocation(); for non-living non-TNT bodies — a falling
            // block (sand/gravel), an item, … — it is feet + eyeHeight, and the
            // scalable dimensions those use put the eye at 0.85 * box height
            // (e.g. a 0.98-tall falling block => +0.833). Using feet here instead
            // pushed falling blocks DOWN where vanilla pushes them UP.
            Vec3d eye;
            if (living) {
                Location eyeLoc = ((LivingEntity) e).getEyeLocation();
                eye = new Vec3d(eyeLoc.getX(), eyeLoc.getY(), eyeLoc.getZ());
            } else if (tnt) {
                eye = feet; // unused for TNT (knockbackOrigin returns feet), kept consistent
            } else {
                double eyeHeight = (bb.getMaxY() - bb.getMinY()) * 0.85;
                eye = new Vec3d(feetLoc.getX(), feetLoc.getY() + eyeHeight, feetLoc.getZ());
            }
            Aabb box = new Aabb(bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ());
            double kbResist = living ? reflection.explosionKnockbackResistance((LivingEntity) e) : 0.0;
            double seen = seenPercent(world, center, bb);
            EntitySnapshot snap = new EntitySnapshot(e.getEntityId(), tnt, living, feet, eye, box, kbResist, seen, false);
            out.add(new ExplosionTarget(e, snap));
        }
        return out;
    }

    private static double seenPercent(World world, Vec3d center, BoundingBox bb) {
        double dx = 1.0 / ((bb.getMaxX() - bb.getMinX()) * 2.0 + 1.0);
        double dy = 1.0 / ((bb.getMaxY() - bb.getMinY()) * 2.0 + 1.0);
        double dz = 1.0 / ((bb.getMaxZ() - bb.getMinZ()) * 2.0 + 1.0);
        double ox = (1.0 - Math.floor(1.0 / dx) * dx) / 2.0;
        double oz = (1.0 - Math.floor(1.0 / dz) * dz) / 2.0;
        if (dx < 0 || dy < 0 || dz < 0) {
            return 0.0;
        }
        int hits = 0, total = 0;
        Vector centerVec = new Vector(center.x(), center.y(), center.z());
        for (double a = 0.0; a <= 1.0; a += dx) {
            for (double b = 0.0; b <= 1.0; b += dy) {
                for (double cc = 0.0; cc <= 1.0; cc += dz) {
                    double sx = Mth.lerp(a, bb.getMinX(), bb.getMaxX()) + ox;
                    double sy = Mth.lerp(b, bb.getMinY(), bb.getMaxY());
                    double sz = Mth.lerp(cc, bb.getMinZ(), bb.getMaxZ()) + oz;
                    Vector dir = centerVec.clone().subtract(new Vector(sx, sy, sz));
                    double dist = dir.length();
                    if (dist > 1.0E-7) {
                        RayTraceResult hit = world.rayTraceBlocks(
                                new Location(world, sx, sy, sz), dir, dist, FluidCollisionMode.NEVER, true);
                        if (hit == null) {
                            hits++;
                        }
                    } else {
                        hits++;
                    }
                    total++;
                }
            }
        }
        return total == 0 ? 0.0 : (double) hits / (double) total;
    }

    @Override
    public @NotNull Vec3d explosionCenter(@NotNull Entity tnt) {
        Location l = tnt.getLocation();
        // Vanilla PrimedTnt.explode detonates at getY(0.0625) = position.y +
        // bbHeight * 0.0625. The primed-TNT box is 0.98 tall, so the centre is
        // +0.06125, NOT a flat +0.0625. The blast ray-march and the entity
        // seen-percent grid key off this centre, so the sub-cm difference is a
        // real (if tiny) non-parity that the deterministic oracle now pins.
        return new Vec3d(l.getX(), l.getY() + 0.98 * 0.0625, l.getZ());
    }

    @Override
    public boolean regionOwnsChunkAt(@NotNull World world, double x, double z) {
        if (!folia) {
            return true; // Paper: a single tick thread owns everything.
        }
        int chunkX = ((int) Math.floor(x)) >> 4;
        int chunkZ = ((int) Math.floor(z)) >> 4;
        return reflection.ownedByCurrentRegion(world, chunkX, chunkZ);
    }

    @Override
    public void applyState(@NotNull Entity entity, @NotNull BodyState state) {
        // Prefer a direct NMS reposition: it emits relative-move packets the client
        // interpolates (smooth flight/fall), whereas a Bukkit teleport emits an
        // absolute teleport packet the client snaps to — which is why an
        // engine-driven falling block looked like it jumped to its landing spot.
        // On Folia this is safe only when the destination chunk is owned by the
        // region currently ticking (the usual case — a falling block moves within
        // one region); a move that would cross a region boundary falls through to
        // the region-aware async teleport, which hands the entity off correctly.
        boolean smooth;
        if (folia) {
            int chunkX = ((int) Math.floor(state.x())) >> 4;
            int chunkZ = ((int) Math.floor(state.z())) >> 4;
            smooth = reflection.ownedByCurrentRegion(entity.getWorld(), chunkX, chunkZ);
        } else {
            smooth = true;
        }
        boolean moved = smooth && reflection.setPos(entity, state.x(), state.y(), state.z());
        if (!moved) {
            Location current = entity.getLocation();
            Location target = new Location(entity.getWorld(), state.x(), state.y(), state.z(),
                    current.getYaw(), current.getPitch());
            try {
                entity.teleport(target);
            } catch (UnsupportedOperationException foliaTeleport) {
                entity.teleportAsync(target);
            }
        }
        if (!loggedApplyPath) {
            loggedApplyPath = true;
            plugin.getLogger().info("Engine movement: " + (moved
                    ? "NMS setPos (smooth client interpolation)"
                    : "teleport fallback (client may snap) — folia=" + folia + " regionOwned=" + smooth));
        }
        entity.setVelocity(new Vector(0, 0, 0));
        if (entity instanceof TNTPrimed tnt) {
            tnt.setFuseTicks(HELD_FUSE); // engine owns the real countdown; vanilla must never reach 0
        }
        // Smooth client rendering of an engine-driven body (render-only; physics
        // untouched; both no-ops if unresolved):
        //  1. Force a per-tick POSITION broadcast. The tracker otherwise re-syncs
        //     position only every updateInterval ticks (20 for a falling block),
        //     so the client jumps; needsSync makes it send every tick and the
        //     renderer interpolates between consecutive positions (how every entity
        //     renders smoothly). This is the primary fix and rides the tracker's
        //     own Folia-safe path.
        //  2. Hand the client the engine's true velocity so it dead-reckons between
        //     frames (the shadow entity's real velocity is held at zero so vanilla
        //     never re-moves it, which would otherwise freeze the client render).
        reflection.markNeedsSync(entity);
        reflection.broadcastVelocity(entity, state.dx(), state.dy(), state.dz());
    }

    @Override
    public void broadcastRenderVelocity(@NotNull Entity entity, @NotNull Vec3d velocity) {
        reflection.broadcastVelocity(entity, velocity.x(), velocity.y(), velocity.z());
    }

    @Override
    public void applyPush(@NotNull Entity victim, @NotNull Vec3d knockback, float damage) {
        if (!victim.isValid()) {
            return;
        }
        if (victim instanceof LivingEntity le && damage > 0.0f) {
            try {
                le.damage(damage);
            } catch (Throwable ignored) {
                // some entities reject generic damage; knockback still applies
            }
        }
        victim.setVelocity(victim.getVelocity().add(new Vector(knockback.x(), knockback.y(), knockback.z())));
    }

    @Override
    public @NotNull PrimeResult fireExplosionPrime(@NotNull Entity tnt) {
        ExplosionPrimeEvent event = new ExplosionPrimeEvent(tnt, 4.0f, false);
        Bukkit.getPluginManager().callEvent(event);
        return new PrimeResult(event.isCancelled(), event.getRadius(), event.getFire());
    }

    @Override
    public @NotNull ExplodeResult fireEntityExplode(@NotNull Entity tnt, @NotNull Vec3d center,
                                                    @NotNull List<BlockPos> broken, float yield) {
        List<Block> blocks = new ArrayList<>(broken.size());
        for (BlockPos p : broken) {
            Block b = tnt.getWorld().getBlockAt(p.x(), p.y(), p.z());
            if (!b.getType().isAir()) {
                blocks.add(b);
            }
        }
        Location loc = new Location(tnt.getWorld(), center.x(), center.y(), center.z());
        EntityExplodeEvent event = newEntityExplodeEvent(tnt, loc, blocks, yield);
        if (event == null) {
            // Could not construct the event on this version — destroy as solved
            // rather than silently dropping protection; logged once in NmsReflection.
            return new ExplodeResult(false, broken, yield);
        }
        Bukkit.getPluginManager().callEvent(event);
        List<BlockPos> survivors = new ArrayList<>();
        for (Block b : event.blockList()) {
            survivors.add(new BlockPos(b.getX(), b.getY(), b.getZ()));
        }
        return new ExplodeResult(event.isCancelled(), survivors, event.getYield());
    }

    // EntityExplodeEvent gained a 5-arg (…, ExplosionResult) constructor at 1.21;
    // resolve whichever exists once and reuse it.
    private volatile Constructor<EntityExplodeEvent> explodeCtor;
    private volatile Object explosionResultDecay;
    private volatile boolean explodeCtorTried;

    @Nullable
    private EntityExplodeEvent newEntityExplodeEvent(Entity tnt, Location loc, List<Block> blocks, float yield) {
        if (!explodeCtorTried) {
            resolveExplodeCtor();
        }
        Constructor<EntityExplodeEvent> ctor = explodeCtor;
        if (ctor == null) {
            return null;
        }
        try {
            if (ctor.getParameterCount() == 5) {
                return ctor.newInstance(tnt, loc, blocks, yield, explosionResultDecay);
            }
            return ctor.newInstance(tnt, loc, blocks, yield);
        } catch (Throwable t) {
            return null;
        }
    }

    private synchronized void resolveExplodeCtor() {
        if (explodeCtorTried) {
            return;
        }
        explodeCtorTried = true;
        try {
            Class<?> resultClass = Class.forName("org.bukkit.ExplosionResult");
            for (Object constant : resultClass.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals("DESTROY_WITH_DECAY")) {
                    explosionResultDecay = constant;
                    break;
                }
            }
            explodeCtor = (Constructor<EntityExplodeEvent>) EntityExplodeEvent.class.getConstructor(
                    Entity.class, Location.class, List.class, float.class, resultClass);
            return;
        } catch (Throwable modernAbsent) {
            // fall through to the legacy 4-arg constructor
        }
        try {
            explodeCtor = EntityExplodeEvent.class.getConstructor(
                    Entity.class, Location.class, List.class, float.class);
        } catch (Throwable legacyAbsent) {
            explodeCtor = null;
        }
    }

    @Override
    public void destroyBlocks(@NotNull World world, @NotNull List<BlockPos> blocks, float yield, long dropSeed) {
        SeededRng rng = new SeededRng(dropSeed);
        boolean dropItems = world.getGameRuleValue(org.bukkit.GameRule.DO_TILE_DROPS) != Boolean.FALSE;
        for (BlockPos pos : blocks) {
            Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (b.getType().isAir()) {
                continue;
            }
            Collection<ItemStack> drops = (dropItems && yield > 0.0f && rng.nextFloat() < yield)
                    ? b.getDrops() : List.of();
            b.setType(Material.AIR, true);
            for (ItemStack drop : drops) {
                world.dropItemNaturally(new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5), drop);
            }
        }
    }

    @Override
    public void emitExplosionEffects(@NotNull World world, @NotNull Vec3d center) {
        Location loc = new Location(world, center.x(), center.y(), center.z());
        try {
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 4.0f, 1.0f);
        } catch (Throwable ignored) {
        }
        try {
            world.spawnParticle(Particle.EXPLOSION_LARGE, loc, 1);
        } catch (Throwable renamed) {
            try {
                world.spawnParticle(Particle.valueOf("EXPLOSION_EMITTER"), loc, 1);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void landFallingBlock(@NotNull World world, @NotNull BodyState state, @Nullable BlockData data) {
        if (data == null) {
            return;
        }
        int bx = Mth.floor(state.x()), by = Mth.floor(state.y()), bz = Mth.floor(state.z());
        Block block = world.getBlockAt(bx, by, bz);
        Material at = block.getType();
        if (at.isAir() || block.isLiquid() || !block.getType().isSolid()) {
            block.setBlockData(data, true);
        } else if (world.getGameRuleValue(org.bukkit.GameRule.DO_ENTITY_DROPS) != Boolean.FALSE) {
            world.dropItemNaturally(new Location(world, bx + 0.5, by + 0.5, bz + 0.5),
                    new ItemStack(data.getMaterial()));
        }
    }

    @Override
    public void removeEntity(@NotNull Entity entity) {
        entity.remove();
    }

    // ── blast-resistance fallback table (used only when NMS reflection is unavailable) ──
    private static final Map<Material, Float> RESISTANCE = new EnumMap<>(Material.class);

    static {
        put(1200f, Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.ANCIENT_DEBRIS,
                Material.NETHERITE_BLOCK, Material.RESPAWN_ANCHOR, Material.ENCHANTING_TABLE);
        put(600f, Material.ENDER_CHEST);
        put(6f, Material.STONE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.STONE_BRICKS,
                Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.GRANITE, Material.DIORITE,
                Material.ANDESITE, Material.BRICKS, Material.NETHER_BRICKS, Material.END_STONE,
                Material.END_STONE_BRICKS, Material.SMOOTH_STONE, Material.BLACKSTONE);
        put(100f, Material.WATER, Material.LAVA);
        put(0.5f, Material.DIRT, Material.GRASS_BLOCK, Material.SAND, Material.RED_SAND, Material.GRAVEL,
                Material.CLAY, Material.SANDSTONE, Material.NETHERRACK, Material.SOUL_SAND);
        put(0.3f, Material.GLASS, Material.GLOWSTONE, Material.SEA_LANTERN);
        put(3f, Material.OAK_PLANKS, Material.OAK_LOG, Material.OAK_WOOD);
        put(0.8f, Material.WHITE_WOOL);
    }

    private static void put(float value, Material... materials) {
        for (Material m : materials) {
            RESISTANCE.put(m, value);
        }
    }

    private static float blastResistance(Material m) {
        Float v = RESISTANCE.get(m);
        return v != null ? v : 1.0f;
    }

    private static boolean isDestroyable(Material m) {
        if (m == Material.BEDROCK || m == Material.BARRIER || m == Material.END_PORTAL_FRAME) {
            return false;
        }
        String name = m.name();
        return !name.contains("COMMAND_BLOCK") && !name.contains("REINFORCED")
                && !name.contains("PORTAL") && !name.equals("STRUCTURE_BLOCK") && !name.equals("JIGSAW");
    }
}
