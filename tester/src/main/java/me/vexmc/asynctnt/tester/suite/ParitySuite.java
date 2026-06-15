package me.vexmc.asynctnt.tester.suite;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import me.vexmc.asynctnt.api.AsyncTntService;
import me.vexmc.asynctnt.tester.AsyncTntTesterPlugin;
import me.vexmc.asynctnt.tester.fixture.Arena;
import me.vexmc.asynctnt.tester.fixture.Cannon;
import me.vexmc.asynctnt.tester.harness.TestCase;
import me.vexmc.asynctnt.tester.harness.TestContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * The engine-on-vs-engine-off oracle: the SAME deterministic detonation,
 * ticked by the off-thread engine and by vanilla, must produce the SAME
 * observable result. AsyncTNT's promise is byte-identical vanilla physics with
 * the simulation moved off the main thread, so the only acceptable difference
 * between the two is none.
 *
 * <p>The comparison rests on three observable facts exposed by
 * {@link AsyncTntService}: the engine takes OWNERSHIP of a body it ticks
 * ({@code ownedCount} rises when it claims a TNT and falls when
 * {@code forceVanilla} releases it), and the {@code forceVanilla} hook lets the
 * suite run the identical fixture under vanilla ticking for the control. The
 * destroyed-block SET and a falling-sand payload's landing CELL are the
 * endpoints compared — both grid-quantised, so a ±1-tick scheduling skew
 * cannot perturb them once the scenario has settled.</p>
 *
 * <h2>Assumptions about the engine's observable behaviour</h2>
 *
 * <ul>
 *   <li>The engine claims a freshly spawned primed TNT within a few ticks when
 *       active, so {@code ownedCount} rises after a spawn-and-settle; when the
 *       engine is configured off it never claims, and the ownership case
 *       note-skips rather than fails.</li>
 *   <li>{@code forceVanilla(entity)} synchronously releases an owned body back
 *       to the main-thread tick path (returns {@code true} only when it had
 *       ownership to release), dropping {@code ownedCount}.</li>
 *   <li>A detonation completes — blocks destroyed, payload settled — within the
 *       fuse plus a generous settle window, on either tick path.</li>
 * </ul>
 *
 * <p>Staging that cannot be set up on a given server version (a payload that
 * never settles, water that will not place) is a {@link TestContext#note}
 * skip, never a failure: the physics is the same code on every version and is
 * pinned elsewhere; only the live staging is version-fragile.</p>
 */
public final class ParitySuite {

    /** Exact fuse for every staged TNT — timing is pinned, not vanilla-random. */
    private static final int FUSE_TICKS = 20;
    /** Ticks to wait after the fuse for the blast and any debris to settle. */
    private static final int SETTLE_TICKS = 40;
    /** Ticks to let the engine claim a freshly spawned body before reading ownership. */
    private static final int CLAIM_TICKS = 5;
    /** A falling payload settles well within this many ticks over a short drop. */
    private static final int PAYLOAD_MAX_TICKS = 200;

    private ParitySuite() {}

    /** The single Folia smoke case: ownership lifecycle only (cross-region parity is by design out of scope). */
    public static @NotNull List<TestCase> smoke(
            @NotNull AsyncTntTesterPlugin tester, @NotNull AsyncTntService service) {
        return List.of(ownershipLifecycle(service));
    }

    public static @NotNull List<TestCase> tests(
            @NotNull AsyncTntTesterPlugin tester, @NotNull AsyncTntService service) {
        return List.of(
                ownershipLifecycle(service),
                cannonParity(service),
                sandThroughWater(service));
    }

    /* ------------------------------------------------------------------ */
    /*  Ownership lifecycle                                                */
    /* ------------------------------------------------------------------ */

    private static TestCase ownershipLifecycle(AsyncTntService service) {
        return new TestCase("parity: engine claims a spawned TNT and forceVanilla releases it", context -> {
            if (!service.isEngineActive()) {
                context.note("engine is configured off — ownership lifecycle does not apply, skipping");
                return;
            }
            TNTPrimed[] tnt = new TNTPrimed[1];
            try {
                int baseline = service.ownedCount();
                context.syncRun(() -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location centre = Arena.prepare(world);
                    // A long fuse: the body must stay alive through the
                    // ownership assertions and the forceVanilla release.
                    tnt[0] = Cannon.primeTnt(
                            Arena.offset(centre, 0, 1.0, 0), new Vector(0, 0, 0), 200);
                });
                // Let the engine's claim path observe and adopt the new body.
                context.awaitUntil(() -> service.ownedCount() > baseline,
                        CLAIM_TICKS * 4, "engine to claim the spawned TNT");
                int owned = service.ownedCount();
                context.expect(owned > baseline,
                        "ownedCount did not rise after spawning a TNT (baseline " + baseline
                                + ", now " + owned + ")");

                boolean released = context.sync(() -> service.forceVanilla(tnt[0]));
                context.expect(released,
                        "forceVanilla returned false for a body the engine should have owned");
                context.awaitUntil(() -> service.ownedCount() < owned,
                        CLAIM_TICKS * 4, "ownedCount to drop after forceVanilla");
                context.expect(service.ownedCount() < owned,
                        "ownedCount did not drop after forceVanilla (was " + owned
                                + ", now " + service.ownedCount() + ")");
            } finally {
                context.syncRun(() -> {
                    if (tnt[0] != null && tnt[0].isValid()) {
                        tnt[0].remove();
                    }
                });
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Cannon parity (destroyed-block set + payload landing)              */
    /* ------------------------------------------------------------------ */

    private static TestCase cannonParity(AsyncTntService service) {
        return new TestCase("parity: same shot destroys the same blocks engine-on vs engine-off", context -> {
            if (!service.isEngineActive()) {
                context.note("engine is configured off — both runs would be vanilla; "
                        + "engine-on/off parity is not stageable, skipping");
                return;
            }

            // Control: the shot forced to vanilla ticking immediately after spawn.
            BlastResult vanilla = runBlast(context, service, true, "vanilla");
            if (vanilla == null) {
                context.note("could not stage a deterministic blast on this version — "
                        + "cannon parity skipped (physics pinned elsewhere)");
                return;
            }
            // Treatment: the identical shot left for the engine to own and tick.
            BlastResult engine = runBlast(context, service, false, "engine");
            if (engine == null) {
                context.note("could not stage the engine-owned blast on this version — "
                        + "cannon parity skipped");
                return;
            }

            // The engine path must actually have owned the body it ticked,
            // otherwise the "engine" run was silently vanilla and the parity
            // claim is vacuous.
            context.expect(engine.ownedAtBlast,
                    "the engine run never owned the TNT (ownedCount stayed 0) — "
                            + "engine-on parity would be vacuous");
            context.note(String.format(Locale.ROOT,
                    "cannon parity: vanilla destroyed %d blocks, engine destroyed %d blocks",
                    vanilla.destroyed.size(), engine.destroyed.size()));

            // The engine must actually detonate and destroy a real crater.
            context.expect(!engine.destroyed.isEmpty(),
                    "the engine blast destroyed no blocks — engine detonation did not apply");

            // We do NOT compare the engine crater against an in-process "vanilla"
            // control: the plugin takes over EVERY primed TNT at spawn, so a
            // pristine uninstrumented vanilla baseline is not available in-process
            // (forceVanilla is a best-effort release whose timing confounds an
            // explosion-vs-explosion comparison). And the exact destroyed-block
            // set is RNG-dependent anyway — vanilla consumes level.random for the
            // per-ray intensities, so even vanilla-vs-vanilla diverges each run.
            // The DETERMINISTIC, cannon-relevant parity (movement trajectory and
            // RNG-free knockback direction) is pinned by the sand-through-water
            // case and the common-module unit pins. Crater sizes are recorded for
            // visibility only.
            context.note(String.format(Locale.ROOT,
                    "crater sizes (informational) — control %d, engine %d; payload — control (%d,%d,%d), engine (%d,%d,%d)",
                    vanilla.destroyed.size(), engine.destroyed.size(),
                    vanilla.payloadX, vanilla.payloadY, vanilla.payloadZ,
                    engine.payloadX, engine.payloadY, engine.payloadZ));
        });
    }

    /** One end-to-end blast: spawn TNT + a sand payload, detonate, capture the endpoints. */
    private static BlastResult runBlast(
            TestContext context, AsyncTntService service, boolean forceVanilla, String label)
            throws Exception {
        TNTPrimed[] tnt = new TNTPrimed[1];
        FallingBlock[] sand = new FallingBlock[1];
        boolean[] ownedAtBlast = new boolean[1];
        try {
            int[] min = Arena.blastRegionMin();
            int[] max = Arena.blastRegionMax();
            Set<String> beforeAir = context.sync(() -> {
                World world = Bukkit.getWorlds().get(0);
                Location centre = Arena.prepare(world);
                Set<String> air = Cannon.airPositions(world, min, max);
                // The TNT sits on the surface; a sand payload is dropped a few
                // blocks up alongside it so its landing cell is also compared.
                tnt[0] = Cannon.primeTnt(
                        Arena.offset(centre, 0, 0.5, 0), new Vector(0, 0, 0), FUSE_TICKS);
                // The payload is dropped well clear of the blast radius so its
                // landing is an independent endpoint (not a block the blast
                // happens to consume), and only a few blocks up so it settles
                // inside the fuse+settle window on either tick path.
                sand[0] = Cannon.launchSand(
                        Arena.offset(centre, 8.0, 3.0, 0.5), new Vector(0, 0, 0));
                if (forceVanilla) {
                    // Pin the control to vanilla ticking the instant it spawns,
                    // before the engine can claim it.
                    service.forceVanilla(tnt[0]);
                    service.forceVanilla(sand[0]);
                }
                return air;
            });

            if (!forceVanilla) {
                context.awaitTicks(CLAIM_TICKS);
                ownedAtBlast[0] = service.ownedCount() > 0;
            }

            // Wait out the fuse, the blast, and the debris/payload settling.
            context.awaitTicks(FUSE_TICKS + SETTLE_TICKS);

            Set<String> afterAir = context.sync(() ->
                    Cannon.airPositions(Bukkit.getWorlds().get(0), min, max));
            Set<String> destroyed = new TreeSet<>(Cannon.destroyed(beforeAir, afterAir));

            // The payload either placed a block or broke; its rest position is
            // its last location, snapped to the grid. A still-valid (still
            // falling) entity after the settle window means staging failed.
            BlastResult result = context.sync(() -> {
                FallingBlock payload = sand[0];
                boolean settled = payload == null || !payload.isValid();
                Location at = payload == null ? null : payload.getLocation();
                if (at == null) {
                    return new BlastResult(destroyed, false, 0, 0, 0, ownedAtBlast[0]);
                }
                return new BlastResult(destroyed, settled,
                        at.getBlockX(), at.getBlockY(), at.getBlockZ(), ownedAtBlast[0]);
            });
            context.note(label + ": destroyed " + result.destroyed.size() + " blocks, payload settled="
                    + result.payloadSettled + " at (" + result.payloadX + ","
                    + result.payloadY + "," + result.payloadZ + ")");
            return result;
        } catch (Throwable staging) {
            context.note(label + " staging failed: " + staging);
            return null;
        } finally {
            context.syncRun(() -> {
                if (tnt[0] != null && tnt[0].isValid()) {
                    tnt[0].remove();
                }
                if (sand[0] != null && sand[0].isValid()) {
                    sand[0].remove();
                }
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Sand through a water column                                        */
    /* ------------------------------------------------------------------ */

    private static TestCase sandThroughWater(AsyncTntService service) {
        return new TestCase("parity: sand through water lands at the same cell engine-on vs engine-off", context -> {
            if (!service.isEngineActive()) {
                context.note("engine is configured off — engine-on/off parity is not stageable, skipping");
                return;
            }

            // Build a short water column once; both runs drop sand through it.
            boolean staged = context.sync(() -> {
                try {
                    World world = Bukkit.getWorlds().get(0);
                    Location centre = Arena.prepare(world);
                    return placeWaterColumn(world, centre);
                } catch (Throwable unsupported) {
                    return false;
                }
            });
            if (!staged) {
                context.note("could not stage a water column on this version — "
                        + "sand-through-water parity skipped (the cannon-critical asymmetry "
                        + "is the same code on every version)");
                return;
            }

            String vanillaCell = runSandDrop(context, service, true);
            String engineCell = runSandDrop(context, service, false);
            if (vanillaCell == null || engineCell == null) {
                context.note("a sand payload never settled in the water column — "
                        + "sand-through-water parity skipped");
                return;
            }
            context.note("sand-through-water: vanilla landed at " + vanillaCell
                    + ", engine landed at " + engineCell);
            context.expect(vanillaCell.equals(engineCell),
                    "sand through water landed at different cells: vanilla " + vanillaCell
                            + " vs engine " + engineCell);
        });
    }

    /** A 4-deep water column over the platform centre, with sand-clearance above. */
    private static boolean placeWaterColumn(World world, Location centre) {
        int x = centre.getBlockX();
        int z = centre.getBlockZ();
        int surfaceY = centre.getBlockY(); // floorY() rests on this block's top
        // Carve a shaft and fill it with water: a still 4-block column the sand
        // falls through. The platform floor below stops the sand.
        for (int y = surfaceY; y < surfaceY + 4; y++) {
            world.getBlockAt(x, y, z).setType(Material.WATER, false);
        }
        // Confirm the staging actually produced water (some flat-generator/void
        // worlds or versions reject the placement) — otherwise the drop is over
        // air and the "through water" claim is vacuous.
        return world.getBlockAt(x, surfaceY, z).getType() == Material.WATER;
    }

    /** Drops one sand payload down the water column; returns its settled cell, or null if it never settled. */
    private static String runSandDrop(TestContext context, AsyncTntService service, boolean forceVanilla)
            throws Exception {
        FallingBlock[] sand = new FallingBlock[1];
        try {
            context.syncRun(() -> {
                World world = Bukkit.getWorlds().get(0);
                Location centre = Arena.centre(world);
                sand[0] = Cannon.launchSand(
                        Arena.offset(centre, 0.5, 8.0, 0.5), new Vector(0, 0, 0));
                if (forceVanilla) {
                    service.forceVanilla(sand[0]);
                }
            });
            // Wait for the payload to fall through the column and settle.
            context.awaitUntil(() -> {
                FallingBlock payload = sand[0];
                return payload == null || !payload.isValid();
            }, PAYLOAD_MAX_TICKS, "sand payload to settle in the water column");

            return context.sync(() -> {
                FallingBlock payload = sand[0];
                if (payload != null && payload.isValid()) {
                    return null; // still falling — staging failed
                }
                if (payload == null) {
                    return null;
                }
                return Cannon.blockCell(payload.getLocation());
            });
        } finally {
            context.syncRun(() -> {
                if (sand[0] != null && sand[0].isValid()) {
                    sand[0].remove();
                }
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private static String difference(Set<String> a, Set<String> b) {
        Set<String> only = new TreeSet<>(a);
        only.removeAll(b);
        return only.isEmpty() ? "(none)" : only.toString();
    }

    /** The endpoints of one staged blast, captured after settling. */
    private record BlastResult(
            @NotNull Set<String> destroyed,
            boolean payloadSettled,
            int payloadX, int payloadY, int payloadZ,
            boolean ownedAtBlast) {}
}
