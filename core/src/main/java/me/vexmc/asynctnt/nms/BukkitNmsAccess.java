package me.vexmc.asynctnt.nms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import me.vexmc.asynctnt.common.engine.EntityPush;
import me.vexmc.asynctnt.common.engine.ExplosionResult;
import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.BlockPos;
import me.vexmc.asynctnt.common.math.Mth;
import me.vexmc.asynctnt.common.math.Vec3d;
import me.vexmc.asynctnt.common.snapshot.BlastResistanceView;
import me.vexmc.asynctnt.common.snapshot.BlockCollisionView;
import me.vexmc.asynctnt.common.snapshot.BodyState;
import me.vexmc.asynctnt.common.snapshot.EntitySnapshot;
import me.vexmc.asynctnt.common.snapshot.FluidView;

/**
 * Bukkit-API-based {@link NmsAccess}. Everything achievable through the public
 * API is done here directly (body read/write, collision boxes, entity gather +
 * exact {@code getSeenPercent} via {@code rayTraceBlocks}, block destruction,
 * knockback, effects); the few values the API does not expose — explosion
 * resistance and fluid flow — use a vanilla-constant table and a flow
 * approximation, which are the documented matrix-verified refinement points.
 * Effect enums are looked up defensively so a per-version rename never crashes.
 *
 * <p>All methods run on the owning region thread. The takeover neutralizes the
 * vanilla tick via {@code setGravity(false)} + zeroed vanilla velocity + a held
 * fuse, so {@code PrimedTnt.tick} becomes a no-op regardless of tick ordering.
 */
public final class BukkitNmsAccess implements NmsAccess {

    /** Held vanilla fuse keeps it well above 0 so the server never self-detonates. */
    private static final int HELD_FUSE_FLOOR = 4;

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
                            if (!b.isPassable() && !b.isEmpty()) {
                                BoundingBox bb = b.getBoundingBox();
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
                        float height = waterHeight(b);
                        cells.add(new FluidView.FluidCell(y, height, approximateFlow(world, x, y, z)));
                    }
                }
            }
            return cells;
        };
    }

    private static float waterHeight(Block b) {
        // Source (level 0) ~ 8/9; flowing levels 1..7 lower. Approximates FluidState.getOwnHeight.
        if (b.getBlockData() instanceof Levelled lev) {
            int level = lev.getLevel();
            if (level == 0) {
                return 0.8888889f;
            }
            int falling = level >= 8 ? 8 : (8 - level);
            return falling / 9.0f;
        }
        return 0.8888889f;
    }

    /** Approximate FluidState.getFlow: points toward lower-water / open neighbours. */
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
                continue; // solid neighbour: no flow that way
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
        int min = -radius, max = radius;
        int side = max - min + 1;
        int minWorldY = world.getMinHeight();
        int maxWorldY = world.getMaxHeight();
        float[] resistance = new float[side * side * side];
        boolean[] air = new boolean[side * side * side];
        boolean[] destroyable = new boolean[side * side * side];
        boolean[] outside = new boolean[side * side * side];
        for (int dx = min; dx <= max; dx++) {
            for (int dy = min; dy <= max; dy++) {
                for (int dz = min; dz <= max; dz++) {
                    int i = index(dx - min, dy - min, dz - min, side);
                    int wy = cy + dy;
                    if (wy < minWorldY || wy >= maxWorldY) {
                        outside[i] = true;
                        resistance[i] = Float.NaN;
                        continue;
                    }
                    Material m = world.getBlockAt(cx + dx, wy, cz + dz).getType();
                    boolean isAir = m.isAir();
                    air[i] = isAir;
                    resistance[i] = isAir ? Float.NaN : blastResistance(m);
                    destroyable[i] = !isAir && isDestroyable(m);
                }
            }
        }
        return new CubeBlastView(cx, cy, cz, radius, side, resistance, air, destroyable, outside);
    }

    private static int index(int x, int y, int z, int side) {
        return (x * side + y) * side + z;
    }

    /** Immutable blast-cube snapshot — safe to read off-thread. */
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
            return i < 0 || outside[i]; // leaving the captured cube terminates the ray
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
    public @NotNull List<EntitySnapshot> captureEntities(@NotNull World world, @NotNull Vec3d center,
                                                         double diameter, long ignoredEntityId) {
        Location c = new Location(world, center.x(), center.y(), center.z());
        double reach = diameter + 1.0;
        Collection<Entity> near = world.getNearbyEntities(c, reach, reach, reach);
        List<EntitySnapshot> out = new ArrayList<>();
        for (Entity e : near) {
            if (e.getEntityId() == ignoredEntityId || !e.isValid()) {
                continue;
            }
            boolean tnt = e instanceof TNTPrimed;
            boolean living = e instanceof LivingEntity;
            Location feetLoc = e.getLocation();
            Vec3d feet = new Vec3d(feetLoc.getX(), feetLoc.getY(), feetLoc.getZ());
            Vec3d eye = feet;
            if (living) {
                Location eyeLoc = ((LivingEntity) e).getEyeLocation();
                eye = new Vec3d(eyeLoc.getX(), eyeLoc.getY(), eyeLoc.getZ());
            }
            BoundingBox bb = e.getBoundingBox();
            Aabb box = new Aabb(bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ());
            double kbResist = 0.0; // refined per-version via the knockback-resistance attribute in the matrix
            double seen = seenPercent(world, center, bb);
            out.add(new EntitySnapshot(e.getEntityId(), tnt, living, feet, eye, box, kbResist, seen, false));
        }
        return out;
    }

    /** Exact vanilla getSeenPercent grid, using rayTraceBlocks for the COLLIDER clip. */
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
                    Vector start = new Vector(sx, sy, sz);
                    Vector dir = centerVec.clone().subtract(start);
                    double dist = dir.length();
                    if (dist > 1.0E-7) {
                        RayTraceResult hit = world.rayTraceBlocks(
                                new Location(world, sx, sy, sz), dir, dist,
                                FluidCollisionMode.NEVER, true);
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
        return new Vec3d(l.getX(), l.getY() + 0.0625, l.getZ());
    }

    @Override
    public void applyState(@NotNull Entity entity, @NotNull BodyState state) {
        Location current = entity.getLocation();
        Location target = new Location(entity.getWorld(), state.x(), state.y(), state.z(),
                current.getYaw(), current.getPitch());
        try {
            entity.teleport(target);
        } catch (UnsupportedOperationException folia) {
            entity.teleportAsync(target);
        }
        entity.setVelocity(new Vector(0, 0, 0)); // keep the vanilla tick a no-op
        if (entity instanceof TNTPrimed tnt) {
            tnt.setFuseTicks(Math.max(HELD_FUSE_FLOOR, state.fuseOrTime()));
        }
    }

    @Override
    public void destroyBlocks(@NotNull World world, @NotNull ExplosionResult result) {
        for (BlockPos pos : result.broken()) {
            Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (!b.getType().isAir()) {
                b.setType(Material.AIR, true); // applyPhysics=true so water/redstone react like vanilla
            }
        }
    }

    @Override
    public void applyPush(@NotNull World world, @NotNull EntityPush push) {
        Entity victim = resolveEntity(world, push.entityId());
        if (victim == null) {
            return;
        }
        Vec3d kb = push.knockback();
        victim.setVelocity(victim.getVelocity().add(new Vector(kb.x(), kb.y(), kb.z())));
    }

    @Override
    public void emitExplosionEffects(@NotNull World world, @NotNull Vec3d center) {
        Location loc = new Location(world, center.x(), center.y(), center.z());
        try {
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 4.0f, 1.0f);
        } catch (Throwable ignored) {
            // cosmetic; sound enum may be renamed on a version
        }
        try {
            world.spawnParticle(Particle.EXPLOSION_LARGE, loc, 1);
        } catch (Throwable ignored) {
            // cosmetic; particle enum may be renamed on a version
        }
    }

    @Override
    public void removeEntity(@NotNull Entity entity) {
        entity.remove();
    }

    @Override
    public Entity resolveEntity(@NotNull World world, long entityId) {
        for (Entity e : world.getEntities()) {
            if (e.getEntityId() == entityId) {
                return e;
            }
        }
        return null;
    }

    // ── blast-resistance table (vanilla constants; refined via NMS in the matrix) ──
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
        return v != null ? v : 1.0f; // conservative default; exact NMS value verified in the matrix
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
