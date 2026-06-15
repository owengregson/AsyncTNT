package me.vexmc.asynctnt.tester.fixture;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * A deterministic detonation fixture: it spawns one primed TNT (or one
 * falling-block payload) at an exact location with an exact velocity and fuse,
 * and reads back what the simulation produced — the set of blocks turned to
 * air by the blast, or where a payload came to rest.
 *
 * <p>"Deterministic" is the whole contract. Vanilla applies a random
 * ±0.02 horizontal "spawn kick" to primed TNT, and the engine reproduces it
 * byte-for-byte; either way it is a per-spawn random vector that would make
 * two runs of the "same" shot diverge. The parity oracle compares one run
 * against another, so the kick must be eliminated identically on both sides:
 * {@link #primeTnt} overwrites the entity's velocity with the caller's exact
 * vector immediately after spawn ({@code setVelocity} replaces, not adds), and
 * sets an explicit fuse so timing is fixed too. With those pinned, the only
 * remaining input to the simulation is the physics — which is exactly what the
 * oracle is comparing.</p>
 *
 * <p>All methods touch the world and must run on the main thread (callers wrap
 * them in {@link me.vexmc.asynctnt.tester.harness.TestContext#syncRun}).</p>
 */
public final class Cannon {

    private Cannon() {}

    /**
     * Spawns a primed TNT at {@code at} with the exact {@code velocity} and
     * {@code fuseTicks}, having overwritten vanilla's random spawn kick so the
     * shot is reproducible. The returned entity is live and will detonate after
     * its fuse; the caller decides who owns it (engine or vanilla) before then.
     */
    public static @NotNull TNTPrimed primeTnt(
            @NotNull Location at, @NotNull Vector velocity, int fuseTicks) {
        World world = at.getWorld();
        TNTPrimed tnt = world.spawn(at, TNTPrimed.class);
        // Replace the spawn kick (and any momentum the spawn applied) with the
        // caller's exact vector — setVelocity overwrites, so the random ±0.02
        // scatter is gone identically regardless of the engine's fork-fix flags.
        tnt.setVelocity(velocity.clone());
        tnt.setFuseTicks(fuseTicks);
        return tnt;
    }

    /**
     * Spawns a sand falling-block payload at {@code at} with the exact
     * {@code velocity}. Falling sand is the cannon-critical projectile: its
     * landing spot, and especially whether it survives a water column instead
     * of breaking into an item, is the asymmetry an off-thread engine must get
     * right. {@code FallingBlock} carries no fuse; it lands or breaks on its own.
     */
    public static @NotNull FallingBlock launchSand(@NotNull Location at, @NotNull Vector velocity) {
        World world = at.getWorld();
        @SuppressWarnings("deprecation") // spawnFallingBlock(Location, Material, byte) — 1.17 floor
        FallingBlock sand = world.spawnFallingBlock(at, Material.SAND, (byte) 0);
        sand.setVelocity(velocity.clone());
        // Drops break into items on a partial-block landing; for a deterministic
        // landing-position oracle we want the block placed, not an item drop.
        sand.setDropItem(false);
        return sand;
    }

    /**
     * Records the set of block positions inside the bounded region (inclusive
     * corners, block coordinates) that are currently AIR. Taken once before the
     * blast and once after, the set DIFFERENCE is precisely the blocks the
     * explosion destroyed — the destroyed-block set the parity oracle compares.
     *
     * <p>Positions are encoded as {@code "x,y,z"} strings so two captures can be
     * compared with plain set operations and reported legibly on a mismatch.</p>
     */
    public static @NotNull Set<String> airPositions(
            @NotNull World world, @NotNull int[] min, @NotNull int[] max) {
        Set<String> air = new HashSet<>();
        for (int x = min[0]; x <= max[0]; x++) {
            for (int y = min[1]; y <= max[1]; y++) {
                for (int z = min[2]; z <= max[2]; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.AIR) {
                        air.add(x + "," + y + "," + z);
                    }
                }
            }
        }
        return air;
    }

    /**
     * The blocks destroyed by a blast: the positions that were solid in
     * {@code before} (the pre-blast air set) and are air in {@code after}.
     */
    public static @NotNull Set<String> destroyed(
            @NotNull Set<String> beforeAir, @NotNull Set<String> afterAir) {
        Set<String> newlyAir = new HashSet<>(afterAir);
        newlyAir.removeAll(beforeAir);
        return newlyAir;
    }

    /**
     * The settled position of a falling-block payload: its block-grid landing
     * cell once it has come to rest (the FallingBlock entity is gone — it
     * either placed a block or broke — so the position is read off its last
     * location, snapped to the block grid for a tick-insensitive comparison).
     * Encoded {@code "x,y,z"} to match {@link #airPositions}.
     */
    public static @NotNull String blockCell(@NotNull Location location) {
        return location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }
}
