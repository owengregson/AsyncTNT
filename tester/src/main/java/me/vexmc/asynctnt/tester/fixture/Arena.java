package me.vexmc.asynctnt.tester.fixture;

import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.jetbrains.annotations.NotNull;

/**
 * A deterministic flat stone platform, far from world spawn, where the parity
 * oracle detonates TNT and lands falling-block payloads in isolation.
 *
 * <p>The whole point of the parity suite is that the SAME deterministic
 * fixture produces the SAME observable result with the engine on or off, so
 * the arena must remove every source of run-to-run variance the run worlds
 * introduce:</p>
 *
 * <ul>
 *   <li>The run worlds are normal night-cycling worlds and a flat stone
 *       platform is dark spawnable ground: hostile mobs spawn on it, wander
 *       across the blast region, and either eat the explosion or get launched
 *       — a creeper or zombie standing where a block should be air reads
 *       exactly like an engine-vs-vanilla destruction mismatch. Spawning is
 *       forced off and anything hostile already nearby is purged each
 *       {@link #prepare}.</li>
 *   <li>The platform sits at {@code x,z ~ 2000}, far from spawn terrain,
 *       overhangs and the spawn-protection radius, so the bounded blast region
 *       is always solid stone over a void of air — a clean canvas whose only
 *       changes are the ones the TNT makes.</li>
 * </ul>
 *
 * <p>Every method here touches the world and must run on the main thread
 * (callers wrap it in {@link me.vexmc.asynctnt.tester.harness.TestContext#syncRun}).
 * Construction is idempotent: re-running {@link #prepare} on the same world
 * re-flattens the platform and re-clears the air pocket, so each scenario
 * starts from the identical canvas regardless of what the previous one
 * destroyed.</p>
 */
public final class Arena {

    private static final int BASE_X = 2000;
    private static final int BASE_Y = 120;
    private static final int BASE_Z = 2000;

    /** Side length of the solid stone floor. Comfortably wider than any blast. */
    private static final int FLOOR_SIZE = 24;
    /** Air headroom cleared above the floor for the blast and any payload flight. */
    private static final int HEADROOM = 24;
    /** Solid stone depth below the surface, so a downward blast still hits stone. */
    private static final int FLOOR_DEPTH = 4;
    /** Radius (blocks, both axes) around the platform that is purged of monsters. */
    private static final int PURGE_RADIUS = 64;

    private Arena() {}

    /**
     * Builds (idempotently) the platform and returns the anchor — the centre
     * of the stone surface, the y a settled entity rests on. Main thread only.
     */
    public static @NotNull Location prepare(@NotNull World world) {
        forceNoMobSpawning(world);
        purgeNearbyMonsters(world);
        loadChunks(world);
        buildFloorAndClearAir(world);
        return centre(world);
    }

    /** The top surface of the floor — the y at which the platform's stone ends. */
    public static double floorY() {
        return BASE_Y + 1.0;
    }

    /** The centre of the platform surface, the canonical spawn/anchor point. */
    public static @NotNull Location centre(@NotNull World world) {
        return new Location(world, BASE_X + 0.5, BASE_Y + 1.0, BASE_Z + 0.5);
    }

    /** A point offset from the platform centre, on the floor surface. */
    public static @NotNull Location offset(@NotNull Location centre, double dx, double dy, double dz) {
        return centre.clone().add(dx, dy, dz);
    }

    /**
     * The bounded cuboid the cannon fixture inspects after a detonation —
     * exactly the solid stone slab plus its air headroom. Any block the blast
     * turns to air lies inside it, and (critically) so does every block it
     * leaves intact, so a region scan captures the complete destroyed-block
     * SET rather than a sampled guess. Coordinates are block coordinates,
     * {@code min} inclusive, {@code max} inclusive.
     */
    public static @NotNull int[] blastRegionMin() {
        return new int[] {BASE_X - FLOOR_SIZE / 2, BASE_Y - FLOOR_DEPTH + 1, BASE_Z - FLOOR_SIZE / 2};
    }

    public static @NotNull int[] blastRegionMax() {
        return new int[] {BASE_X + FLOOR_SIZE / 2, BASE_Y + HEADROOM, BASE_Z + FLOOR_SIZE / 2};
    }

    private static void forceNoMobSpawning(World world) {
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
    }

    private static void purgeNearbyMonsters(World world) {
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Monster
                    && Math.abs(entity.getLocation().getX() - BASE_X) < PURGE_RADIUS
                    && Math.abs(entity.getLocation().getZ() - BASE_Z) < PURGE_RADIUS) {
                entity.remove();
            }
        }
    }

    private static void loadChunks(World world) {
        int half = FLOOR_SIZE / 2;
        for (int cx = (BASE_X - half) >> 4; cx <= (BASE_X + half) >> 4; cx++) {
            for (int cz = (BASE_Z - half) >> 4; cz <= (BASE_Z + half) >> 4; cz++) {
                world.getChunkAt(cx, cz).load();
            }
        }
    }

    private static void buildFloorAndClearAir(World world) {
        int half = FLOOR_SIZE / 2;
        for (int x = BASE_X - half; x <= BASE_X + half; x++) {
            for (int z = BASE_Z - half; z <= BASE_Z + half; z++) {
                // Solid stone slab: the surface plus depth below it, so even a
                // straight-down blast resolves against stone, not pre-existing
                // air or terrain.
                for (int y = BASE_Y - FLOOR_DEPTH + 1; y <= BASE_Y; y++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE, false);
                }
                // Air headroom: the canvas the blast acts on. Cleared every
                // prepare so a previous scenario's destruction does not leak in.
                for (int y = BASE_Y + 1; y <= BASE_Y + HEADROOM; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }
}
