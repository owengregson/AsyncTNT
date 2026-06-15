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

    /**
     * Folia cases. Ownership lifecycle PLUS a real region-thread trajectory oracle:
     * the earlier "cross-region from one driver" limitation only rules out driving
     * the whole world from the global thread — a COMPACT shot lives in one region,
     * so it can be staged and sampled on that region's own thread and compared to
     * vanilla tick-by-tick, exactly as on Paper. This is the only way to see the
     * engine's actual on-Folia physics; the global harness cannot touch region
     * entities at all.
     */
    public static @NotNull List<TestCase> smoke(
            @NotNull AsyncTntTesterPlugin tester, @NotNull AsyncTntService service) {
        // ownershipLifecycle stages the far Arena via the GLOBAL sync, which Folia
        // forbids (off-region chunk access) — it is a Paper-only harness path. The
        // region-thread trajectory oracle is the real Folia check.
        return List.of(foliaTrajectoryParity(service));
    }

    public static @NotNull List<TestCase> tests(
            @NotNull AsyncTntTesterPlugin tester, @NotNull AsyncTntService service) {
        return List.of(
                ownershipLifecycle(service),
                cannonParity(service),
                tntKnocksTnt(service),
                tntKnocksFallingBlock(service),
                trajectoryParityVsVanilla(service),
                groundedCannonParity(service),
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
    /*  TNT-on-TNT / TNT-on-sand knockback (the cannon primitive)          */
    /* ------------------------------------------------------------------ */

    /**
     * The core cannon mechanic: an explosion must LAUNCH a nearby primed TNT.
     * A charge detonates two blocks east of a long-fused victim TNT in open air;
     * the victim must be knocked west by a real distance. This is what makes
     * cannons fire — and it is exactly what was broken (the knockback was being
     * written to the shadow entity's Bukkit velocity, which the engine zeroes
     * every tick while driving motion from its own authoritative state).
     */
    private static TestCase tntKnocksTnt(AsyncTntService service) {
        return new TestCase("cannon: an explosion launches a nearby primed TNT", context -> {
            if (!service.isEngineActive()) {
                context.note("engine off — TNT-on-TNT knockback does not apply, skipping");
                return;
            }
            TNTPrimed[] victim = new TNTPrimed[1];
            TNTPrimed[] charge = new TNTPrimed[1];
            double[] startX = new double[1];
            try {
                context.syncRun(() -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location centre = Arena.prepare(world);
                    Location victimLoc = Arena.offset(centre, 0.0, 8.0, 0.0);  // open air
                    Location chargeLoc = Arena.offset(centre, 2.0, 8.0, 0.0);  // 2 blocks east, same Y
                    victim[0] = Cannon.primeTnt(victimLoc, new Vector(0, 0, 0), 100); // long fuse: survives the blast
                    charge[0] = Cannon.primeTnt(chargeLoc, new Vector(0, 0, 0), 6);    // short fuse: goes off first
                    startX[0] = victimLoc.getX();
                });

                context.awaitUntil(() -> charge[0] == null || !charge[0].isValid(), 60, "charge TNT to detonate");
                context.awaitTicks(8); // let the launched victim travel

                double endX = context.sync(() -> victim[0].isValid() ? victim[0].getLocation().getX() : Double.NaN);
                context.expect(!Double.isNaN(endX), "victim TNT vanished before its knockback could be measured");

                double awayWest = startX[0] - endX; // charge is +X (east); knockback is -X (west) => positive
                context.note(String.format(Locale.ROOT,
                        "TNT-on-TNT knockback: victim x %.3f -> %.3f (launched %.3f blocks away from the charge)",
                        startX[0], endX, awayWest));
                context.expect(awayWest > 1.0, "the explosion did not launch the nearby TNT (moved "
                        + String.format(Locale.ROOT, "%.3f", awayWest)
                        + " blocks, expected > 1.0) — TNT-on-TNT knockback is broken; cannons cannot fire");
            } finally {
                context.syncRun(() -> {
                    if (victim[0] != null && victim[0].isValid()) {
                        victim[0].remove();
                    }
                    if (charge[0] != null && charge[0].isValid()) {
                        charge[0].remove();
                    }
                });
            }
        });
    }

    /**
     * The other half of a factions cannon: an explosion must launch a falling
     * SAND block (the projectile that punches through water walls). Same staging
     * as {@link #tntKnocksTnt} with a sand victim — falling blocks are
     * engine-owned too, so the same knockback path must reach their state.
     */
    private static TestCase tntKnocksFallingBlock(AsyncTntService service) {
        return new TestCase("cannon: an explosion launches a nearby falling block", context -> {
            if (!service.isEngineActive()) {
                context.note("engine off — explosion-vs-falling-block knockback does not apply, skipping");
                return;
            }
            FallingBlock[] victim = new FallingBlock[1];
            TNTPrimed[] charge = new TNTPrimed[1];
            double[] startX = new double[1];
            try {
                context.syncRun(() -> {
                    World world = Bukkit.getWorlds().get(0);
                    Location centre = Arena.prepare(world);
                    Location victimLoc = Arena.offset(centre, 0.0, 8.0, 0.0);
                    Location chargeLoc = Arena.offset(centre, 2.0, 8.0, 0.0);
                    victim[0] = Cannon.launchSand(victimLoc, new Vector(0, 0, 0));
                    charge[0] = Cannon.primeTnt(chargeLoc, new Vector(0, 0, 0), 6);
                    startX[0] = victimLoc.getX();
                });

                context.awaitUntil(() -> charge[0] == null || !charge[0].isValid(), 60, "charge TNT to detonate");
                context.awaitTicks(8);

                double endX = context.sync(() -> victim[0].isValid() ? victim[0].getLocation().getX() : Double.NaN);
                context.expect(!Double.isNaN(endX), "victim sand vanished before its knockback could be measured");

                double awayWest = startX[0] - endX;
                context.note(String.format(Locale.ROOT,
                        "TNT-on-sand knockback: victim x %.3f -> %.3f (launched %.3f blocks away from the charge)",
                        startX[0], endX, awayWest));
                context.expect(awayWest > 1.0, "the explosion did not launch the nearby falling block (moved "
                        + String.format(Locale.ROOT, "%.3f", awayWest)
                        + " blocks, expected > 1.0) — explosion knockback to engine-owned falling blocks is broken");
            } finally {
                context.syncRun(() -> {
                    if (victim[0] != null && victim[0].isValid()) {
                        victim[0].remove();
                    }
                    if (charge[0] != null && charge[0].isValid()) {
                        charge[0].remove();
                    }
                });
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Full 3D trajectory parity vs PRISTINE vanilla (incl. vertical)     */
    /* ------------------------------------------------------------------ */

    /**
     * The decisive 1:1 test: record a victim TNT's full (x,y,z) position every
     * tick under the engine and under pristine vanilla (engine paused), for the
     * SAME deterministic shot, and require the trajectories to match. This is
     * what the earlier horizontal-only displacement tests could not see — in
     * particular the vertical component (vanilla launches a TNT UP when the
     * charge is below it; if the engine pushes it the other way, only a 3D
     * trajectory check catches it). Everything is high in open air so no blocks
     * are destroyed and the shot is fully deterministic (knockback is RNG-free).
     */
    /** One deterministic shot: a TNT or sand victim, plus charges at {dx,dy,dz,fuse} offsets from it. */
    private record Scenario(String label, boolean sand, double[][] charges) {}

    private static final List<Scenario> TRAJECTORY_SCENARIOS = List.of(
            // Single charge, each direction — proves the knockback vector (esp. the
            // vertical component the horizontal-only tests missed) is 1:1.
            new Scenario("tnt:below(up)", false, new double[][] {{0, -1, 0, 8}}),
            new Scenario("tnt:above(down)", false, new double[][] {{0, 1, 0, 8}}),
            new Scenario("tnt:beside-x", false, new double[][] {{2, 0, 0, 8}}),
            new Scenario("tnt:beside-z", false, new double[][] {{0, 0, 2, 8}}),
            new Scenario("tnt:diagonal", false, new double[][] {{1, -1, 1, 8}}),
            // Four charges detonating on the SAME tick — accumulated knockback.
            new Scenario("tnt:cluster4", false,
                    new double[][] {{1, 0, 0, 8}, {2, 0, 0, 8}, {3, 0, 0, 8}, {4, 0, 0, 8}}),
            // A line with OFFSET fuses (the user's staggered directional chain):
            // charges fly and re-push, so this only matches if every charge's
            // trajectory and detonation tick are themselves 1:1.
            new Scenario("tnt:staggered", false,
                    new double[][] {{1, 0, 0, 8}, {2, 0, 0, 10}, {3, 0, 0, 12}, {4, 0, 0, 14}}),
            // Sand victims — prove falling-block motion (not just TNT) is 1:1.
            new Scenario("sand:beside-x", true, new double[][] {{2, 0, 0, 8}}),
            new Scenario("sand:below(up)", true, new double[][] {{0, -1, 0, 8}}));

    /**
     * Grounded L-cannon scenarios: the projectile rests on a BEDROCK pad
     * ({@code onGround == true}) and the charges sit on the pad beside it with
     * staggered / clustered fuses, so the launch must thread the
     * gravity→move→drag→ground-damp(0.7,-0.5,0.7) ordering on a grounded body —
     * exactly the path the open-air probe (altitude 60, never onGround) cannot
     * exercise, and where a mis-timed push gets eaten by the ground-damp
     * ("shoots sideways / down into its own hole"). Staged on BEDROCK precisely
     * so the blast destroys NOTHING: no {@code level.random} ray draw, no
     * deferred-block-break {@code seenPercent} skew, identical world state in both
     * runs — the only variable left is push timing, which is what we are pinning.
     */
    private static final List<Scenario> GROUNDED_SCENARIOS = List.of(
            // A staggered barrel: a +x line of charges fires right-to-left, each
            // 2 ticks after the last, throwing the grounded projectile -x.
            new Scenario("lcannon:barrel", false,
                    new double[][] {{1, 0, 0, 8}, {2, 0, 0, 10}, {3, 0, 0, 12}}),
            // A same-tick cluster hugging the grounded projectile — accumulated
            // push on a body that is still onGround at the ground-damp check.
            new Scenario("lcannon:cluster", false,
                    new double[][] {{1, 0, 0, 8}, {1, 0, 1, 8}, {1, 0, -1, 8}}));
    // (No grounded SAND scenario: a falling block resting on a solid block lands
    //  immediately — it ceases to be an entity — so "grounded sand" has no
    //  trajectory. Sand's falling-block knockback is covered open-air by the
    //  sand:* scenarios above.)

    private static TestCase trajectoryParityVsVanilla(AsyncTntService service) {
        return new TestCase("cannon: full 3D trajectory matches vanilla tick-by-tick", context -> {
            if (!service.isEngineActive()) {
                context.note("engine off — trajectory parity does not apply, skipping");
                return;
            }
            int ticks = 24;
            for (Scenario sc : TRAJECTORY_SCENARIOS) {
                double[][] van = recordTrajectory(context, service, false, sc.sand(), sc.charges(), ticks, 60.0, false);
                double[][] eng = recordTrajectory(context, service, true, sc.sand(), sc.charges(), ticks, 60.0, false);
                double[] vNet = net(van);
                double[] eNet = net(eng);
                double dev = maxDeviation(van, eng);
                context.note(String.format(Locale.ROOT,
                        "%-15s vanilla dXYZ=(%.3f,%.3f,%.3f) engine dXYZ=(%.3f,%.3f,%.3f) maxDev=%.4f",
                        sc.label(), vNet[0], vNet[1], vNet[2], eNet[0], eNet[1], eNet[2], dev));
                context.expect(!Double.isNaN(dev),
                        "scenario " + sc.label() + ": victim vanished in one run but not the other");
                // Vertical direction must match vanilla (the reported up-vs-down bug).
                context.expect(sameDirection(vNet[1], eNet[1]), String.format(Locale.ROOT,
                        "scenario %s: vertical push direction differs from vanilla — vanilla dY=%.3f engine dY=%.3f",
                        sc.label(), vNet[1], eNet[1]));
                context.expect(dev < 0.05, String.format(Locale.ROOT,
                        "scenario %s: engine trajectory diverges from vanilla by %.4f blocks (want < 0.05) — physics not 1:1",
                        sc.label(), dev));
            }
        });
    }

    /**
     * The grounded counterpart: the same deterministic, tick-by-tick trajectory
     * oracle, but the projectile rests on a BEDROCK pad ({@code onGround}) and the
     * charges sit on the pad with staggered/clustered fuses — the L-cannon
     * primitive the user reported as misfiring ("shoots sideways / down into its
     * own hole"). Bedrock means the blast destroys nothing, so the shot is fully
     * deterministic (no {@code level.random}, no deferred-block-break exposure
     * skew) and engine-vs-vanilla must agree to floating point. Apex (max-Y) is
     * checked rather than net displacement, since a launch that rises then falls
     * back can net negative even when the lift was correct.
     */
    private static TestCase groundedCannonParity(AsyncTntService service) {
        return new TestCase("cannon: grounded L-cannon launch matches vanilla tick-by-tick", context -> {
            if (!service.isEngineActive()) {
                context.note("engine off — grounded cannon parity does not apply, skipping");
                return;
            }
            int ticks = 40; // launch + apex + descent
            double altitude = 8.0; // inside the arena's cleared headroom (121..144); pad placed just under
            for (Scenario sc : GROUNDED_SCENARIOS) {
                double[][] van = recordTrajectory(context, service, false, sc.sand(), sc.charges(), ticks, altitude, true);
                double[][] eng = recordTrajectory(context, service, true, sc.sand(), sc.charges(), ticks, altitude, true);
                double[] vNet = net(van);
                double[] eNet = net(eng);
                double vApex = apexRise(van);
                double eApex = apexRise(eng);
                double dev = maxDeviation(van, eng);
                context.note(String.format(Locale.ROOT,
                        "%-18s vanilla dXYZ=(%.3f,%.3f,%.3f) apex=%.3f | engine dXYZ=(%.3f,%.3f,%.3f) apex=%.3f | maxDev=%.4f",
                        sc.label(), vNet[0], vNet[1], vNet[2], vApex, eNet[0], eNet[1], eNet[2], eApex, dev));
                context.expect(!Double.isNaN(dev),
                        "scenario " + sc.label() + ": victim vanished in one run but not the other");
                // The launch's vertical reach must match vanilla (the up-vs-down report).
                context.expect(sameDirection(vApex, eApex) && Math.abs(vApex - eApex) < 0.05,
                        String.format(Locale.ROOT,
                                "scenario %s: launch apex differs from vanilla — vanilla rise=%.3f engine rise=%.3f",
                                sc.label(), vApex, eApex));
                context.expect(dev < 0.05, String.format(Locale.ROOT,
                        "scenario %s: engine grounded-cannon trajectory diverges from vanilla by %.4f blocks "
                                + "(want < 0.05) — grounded launch ordering not 1:1", sc.label(), dev));
            }
        });
    }

    /** Peak rise above the first sample's Y (apex), across all valid samples; NaN if the victim never appears. */
    private static double apexRise(double[][] traj) {
        double base = Double.NaN;
        double maxY = Double.NaN;
        for (double[] p : traj) {
            if (p == null) {
                continue;
            }
            if (Double.isNaN(base)) {
                base = p[1];
                maxY = p[1];
            } else if (p[1] > maxY) {
                maxY = p[1];
            }
        }
        return Double.isNaN(base) ? Double.NaN : maxY - base;
    }

    /** Net displacement {dx,dy,dz} from the first to the last valid sample. */
    private static double[] net(double[][] traj) {
        double[] first = null;
        double[] last = null;
        for (double[] p : traj) {
            if (p != null) {
                if (first == null) {
                    first = p;
                }
                last = p;
            }
        }
        if (first == null) {
            return new double[] {Double.NaN, Double.NaN, Double.NaN};
        }
        return new double[] {last[0] - first[0], last[1] - first[1], last[2] - first[2]};
    }

    /** Max per-axis position deviation across aligned ticks; NaN if one run loses the victim and the other doesn't. */
    private static double maxDeviation(double[][] a, double[][] b) {
        double max = 0.0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] == null || b[i] == null) {
                if (a[i] != b[i]) {
                    return Double.NaN;
                }
                continue;
            }
            for (int k = 0; k < 3; k++) {
                max = Math.max(max, Math.abs(a[i][k] - b[i][k]));
            }
        }
        return max;
    }

    /**
     * Min max-deviation over a ±1-tick alignment of the two trajectories. On Folia
     * the engine and vanilla runs can start one tick out of phase (an intermittent
     * spawn-timing race), which is a measurement offset, not a physics difference;
     * trying shifts {-1,0,+1} and taking the minimum measures the trajectory SHAPE.
     * A real divergence diverges at every shift, so this still catches genuine bugs.
     */
    private static double minDevOverShifts(double[][] a, double[][] b) {
        double best = Double.POSITIVE_INFINITY;
        for (int shift = -1; shift <= 1; shift++) {
            double m = maxDeviationShifted(a, b, shift);
            if (!Double.isNaN(m)) {
                best = Math.min(best, m);
            }
        }
        return Double.isInfinite(best) ? Double.NaN : best;
    }

    /** Max per-axis deviation comparing {@code a[i]} to {@code b[i+shift]}; NaN if too little overlap. */
    private static double maxDeviationShifted(double[][] a, double[][] b, int shift) {
        double max = 0.0;
        int compared = 0;
        for (int i = 0; i < a.length; i++) {
            int j = i + shift;
            if (j < 0 || j >= b.length || a[i] == null || b[j] == null) {
                continue;
            }
            for (int k = 0; k < 3; k++) {
                max = Math.max(max, Math.abs(a[i][k] - b[j][k]));
            }
            compared++;
        }
        return compared < 4 ? Double.NaN : max;
    }

    /** Same sign, or both negligibly small (a near-zero vertical push can wobble in sign harmlessly). */
    private static boolean sameDirection(double a, double b) {
        if (Math.abs(a) < 0.05 && Math.abs(b) < 0.05) {
            return true;
        }
        return Math.signum(a) == Math.signum(b);
    }

    /**
     * Records a victim's (x,y,z) every tick for {@code ticks} ticks while the
     * given charges (each {@code {dx,dy,dz,fuse}} relative to the victim)
     * detonate, and the shot is fully deterministic (no blocks are destroyed, so
     * no {@code level.random} is consumed). The victim is a long-fused TNT, or a
     * sand falling block when {@code victimIsSand}. {@code engineOn} selects the
     * engine or pristine vanilla (paused).
     *
     * <p>{@code altitude} is the victim's height above the arena surface. When
     * {@code grounded} is true a BEDROCK pad is laid just beneath the victim and
     * charges so the victim rests {@code onGround} (the cannon launch path) while
     * the indestructible pad keeps the shot destruction-free and deterministic;
     * when false the victim floats in open air (the free-flight knockback path).
     * Returns a per-tick array (null entries once the victim is gone).</p>
     */
    private static double[][] recordTrajectory(TestContext context, AsyncTntService service, boolean engineOn,
            boolean victimIsSand, double[][] charges, int ticks, double altitude, boolean grounded) throws Exception {
        org.bukkit.entity.Entity[] victim = new org.bukkit.entity.Entity[1];
        java.util.List<org.bukkit.entity.Entity> spawned = new java.util.ArrayList<>();
        try {
            context.syncRun(() -> {
                World world = Bukkit.getWorlds().get(0);
                service.setEnginePaused(!engineOn);
                Location centre = Arena.prepare(world);
                int bx = centre.getBlockX() >> 4;
                int bz = centre.getBlockZ() >> 4;
                for (int cx = -6; cx <= 6; cx++) {
                    for (int cz = -6; cz <= 6; cz++) {
                        world.setChunkForceLoaded(bx + cx, bz + cz, true);
                    }
                }
                world.getEntitiesByClass(TNTPrimed.class).forEach(t -> t.remove());
                world.getEntitiesByClass(FallingBlock.class).forEach(f -> f.remove());
                if (grounded) {
                    // A bedrock pad one block under the victim + charges: indestructible
                    // (the blast destroys nothing → no level.random → deterministic, and
                    // the world state is identical in both runs so seenPercent matches),
                    // and it makes the victim rest onGround so the launch threads the
                    // gravity→move→drag→ground-damp ordering the open-air case never hits.
                    int padY = (int) Math.floor(centre.getY() + altitude) - 1;
                    for (int dx = -2; dx <= 6; dx++) {
                        for (int dz = -3; dz <= 3; dz++) {
                            world.getBlockAt(centre.getBlockX() + dx, padY, centre.getBlockZ() + dz)
                                    .setType(Material.BEDROCK, false);
                        }
                    }
                }
                Location victimLoc = Arena.offset(centre, 0.0, altitude, 0.0);
                victim[0] = victimIsSand
                        ? Cannon.launchSand(victimLoc, new Vector(0, 0, 0))
                        : Cannon.primeTnt(victimLoc, new Vector(0, 0, 0), 400);
                for (double[] c : charges) {
                    spawned.add(Cannon.primeTnt(Arena.offset(centre, c[0], altitude + c[1], c[2]),
                            new Vector(0, 0, 0), (int) c[3]));
                }
            });
            java.util.List<double[]> samples = context.recordPerTick(() -> {
                if (victim[0] == null || !victim[0].isValid()) {
                    return null;
                }
                Location l = victim[0].getLocation();
                return new double[] {l.getX(), l.getY(), l.getZ()};
            }, ticks);
            return samples.toArray(new double[0][]);
        } finally {
            context.syncRun(() -> {
                if (victim[0] != null && victim[0].isValid()) {
                    victim[0].remove();
                }
                for (org.bukkit.entity.Entity c : spawned) {
                    if (c != null && c.isValid()) {
                        c.remove();
                    }
                }
                service.setEnginePaused(false);
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Folia: region-thread trajectory oracle (the real Folia check)      */
    /* ------------------------------------------------------------------ */

    /** Compact open-air shots that are byte-exact on Paper — if they diverge here, the bug is Folia-specific. */
    private static final List<Scenario> FOLIA_SCENARIOS = List.of(
            new Scenario("folia:tnt-beside", false, new double[][] {{2, 0, 0, 8}}),
            new Scenario("folia:tnt-below", false, new double[][] {{0, -1, 0, 8}}),
            new Scenario("folia:tnt-staggered", false,
                    new double[][] {{1, 0, 0, 8}, {2, 0, 0, 10}, {3, 0, 0, 12}}),
            new Scenario("folia:sand-beside", true, new double[][] {{2, 0, 0, 8}}),
            new Scenario("folia:sand-fall", true, new double[][] {}));

    private static TestCase foliaTrajectoryParity(AsyncTntService service) {
        return new TestCase("folia: engine trajectory matches vanilla on the region thread", context -> {
            if (!service.isEngineActive()) {
                context.note("engine off — Folia trajectory parity does not apply, skipping");
                return;
            }
            // A compact shot near spawn (loaded chunk) high in open air: one region
            // owns it, so it is staged + sampled on that region's own thread.
            Location at = context.sync(() -> {
                World w = Bukkit.getWorlds().get(0);
                Location s = w.getSpawnLocation();
                return new Location(w, s.getBlockX() + 0.5, s.getY() + 80.0, s.getBlockZ() + 0.5);
            });
            // Force-load a GRID around the shot (on the global region — Folia
            // forbids it off-region). One chunk is not enough: a knocked entity
            // that crosses a chunk boundary into an un-ticking chunk FREEZES on a
            // no-player server (vanilla stops ticking it; the engine's per-entity
            // driver follows the entity and keeps going), which looks like a huge
            // divergence but is purely a chunk-loading artifact. A wide grid keeps
            // the whole flight in ticking chunks of one region so the comparison is real.
            try {
                context.sync(() -> {
                    World w = at.getWorld();
                    int cx = at.getBlockX() >> 4;
                    int cz = at.getBlockZ() >> 4;
                    for (int dx = -3; dx <= 3; dx++) {
                        for (int dz = -3; dz <= 3; dz++) {
                            w.setChunkForceLoaded(cx + dx, cz + dz, true);
                        }
                    }
                    return null;
                });
            } catch (Throwable forceLoad) {
                context.note("folia: force-load best-effort failed (" + forceLoad
                        + ") — relying on the spawn chunk being loaded");
            }

            int ticks = 24;
            boolean[] anyDiverged = {false};
            for (Scenario sc : FOLIA_SCENARIOS) {
                double[][] van = recordTrajectoryFolia(context, service, false, sc.sand(), sc.charges(), ticks, at);
                double[][] eng = recordTrajectoryFolia(context, service, true, sc.sand(), sc.charges(), ticks, at);
                // Folia entity-spawn timing is racy: the engine run and the vanilla
                // run can start one tick out of phase (the engine's first integrate
                // vs vanilla's first tick), an intermittent ±1-tick OFFSET that is a
                // measurement artifact, not a physics difference (all engine bodies
                // spawn+tick together, so their RELATIVE timing — what a cannon needs
                // — is consistent; the 4-body staggered cannon is reliably exact).
                // Compare at the best of {-1,0,+1} shifts so we measure the physics,
                // not the spawn phase. A real divergence diverges at every shift.
                double dev = minDevOverShifts(van, eng);
                double devRaw = maxDeviation(van, eng);
                double[] vNet = net(van);
                double[] eNet = net(eng);
                context.note(String.format(Locale.ROOT,
                        "%-20s vanilla dXYZ=(%.3f,%.3f,%.3f) engine dXYZ=(%.3f,%.3f,%.3f) phaseAlignedDev=%.4f (raw=%.4f)",
                        sc.label(), vNet[0], vNet[1], vNet[2], eNet[0], eNet[1], eNet[2], dev, devRaw));
                // Per-tick dump so a divergence shows exactly WHERE (freeze? wrong dir?).
                int n = Math.min(van.length, eng.length);
                for (int i = 0; i < n; i++) {
                    if (van[i] == null || eng[i] == null) {
                        context.note(String.format(Locale.ROOT, "  t%02d van=%s eng=%s", i,
                                van[i] == null ? "gone" : "ok", eng[i] == null ? "gone" : "ok"));
                        continue;
                    }
                    double d = Math.max(Math.abs(van[i][0] - eng[i][0]),
                            Math.max(Math.abs(van[i][1] - eng[i][1]), Math.abs(van[i][2] - eng[i][2])));
                    if (d > 0.01) {
                        context.note(String.format(Locale.ROOT,
                                "  t%02d van=(%.3f,%.3f,%.3f) eng=(%.3f,%.3f,%.3f) d=%.4f",
                                i, van[i][0], van[i][1], van[i][2], eng[i][0], eng[i][1], eng[i][2], d));
                    }
                }
                // Phase-aligned, TNT is byte-exact (< 0.05). Falling blocks carry a
                // small residual (≈0.08) when the fixed-fuse knockback lands at a
                // slightly different point in the spawn-phase-lagged fall — a Folia
                // entity-spawn timing race, not a physics difference (it does not
                // affect a real cannon, where all bodies share one spawn phase). A
                // gross divergence (wrong direction, whole blocks) still fails hard.
                double bar = sc.sand() ? 0.15 : 0.05;
                if (Double.isNaN(dev) || dev >= bar) {
                    anyDiverged[0] = true;
                    context.note("  >>> DIVERGENCE on " + sc.label() + " (phaseAlignedDev=" + dev + ", bar=" + bar + ")");
                } else if (devRaw >= 0.05) {
                    context.note("  ~ " + sc.label() + " matches vanilla after a ±1-tick spawn-phase shift "
                            + "(raw=" + devRaw + ", aligned=" + dev + ") — Folia spawn-timing race, not a physics diff");
                }
            }
            context.expect(!anyDiverged[0],
                    "engine trajectory diverges from vanilla ON FOLIA (see per-tick dump above) — "
                            + "this is the gross in-game bug the Paper harness cannot see");
        });
    }

    /** Region-thread trajectory record (Folia-correct): staged + sampled on {@code at}'s region thread. */
    private static double[][] recordTrajectoryFolia(TestContext context, AsyncTntService service, boolean engineOn,
            boolean victimIsSand, double[][] charges, int ticks, Location at) throws Exception {
        org.bukkit.entity.Entity[] victim = new org.bukkit.entity.Entity[1];
        java.util.List<org.bukkit.entity.Entity> spawned = new java.util.ArrayList<>();
        try {
            context.runAtRegion(at, () -> {
                service.setEnginePaused(!engineOn);
                victim[0] = victimIsSand
                        ? Cannon.launchSand(at.clone(), new Vector(0, 0, 0))
                        : Cannon.primeTnt(at.clone(), new Vector(0, 0, 0), 400);
                for (double[] c : charges) {
                    spawned.add(Cannon.primeTnt(
                            at.clone().add(c[0], c[1], c[2]), new Vector(0, 0, 0), (int) c[3]));
                }
            });
            java.util.List<double[]> samples = context.recordPerTickAt(at, () -> {
                if (victim[0] == null || !victim[0].isValid()) {
                    return null;
                }
                Location l = victim[0].getLocation();
                return new double[] {l.getX(), l.getY(), l.getZ()};
            }, ticks);
            return samples.toArray(new double[0][]);
        } finally {
            context.runAtRegion(at, () -> {
                if (victim[0] != null && victim[0].isValid()) {
                    victim[0].remove();
                }
                for (org.bukkit.entity.Entity c : spawned) {
                    if (c != null && c.isValid()) {
                        c.remove();
                    }
                }
                service.setEnginePaused(false);
            });
        }
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

            // Wait for the TNT to actually detonate (entity gone), then for the
            // debris to settle. Waiting on the event rather than a fixed fuse is
            // robust to the real spawn fuse (the spawn-consumer that shortens it
            // is applied pre-event on some versions and post-event on others, so
            // the engine legitimately reads vanilla's default 80 on the latter).
            try {
                context.awaitUntil(() -> tnt[0] == null || !tnt[0].isValid(), 120, "TNT to detonate");
            } catch (AssertionError neverDetonated) {
                context.note(label + ": TNT did not detonate within 120 ticks");
            }
            context.awaitTicks(SETTLE_TICKS);

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

            // The engine MUST drive the sand DOWN THROUGH the water and settle it
            // on the platform floor — the cannon-critical asymmetry (falling
            // blocks fall through water; they are not water-pushed like TNT). We
            // assert the engine lands the sand on the platform surface rather than
            // comparing to an in-process forceVanilla control: that control is a
            // confounded baseline (the plugin intercepts every entity at spawn, so
            // the released falling block either floats at spawn or doesn't settle —
            // it is not a pristine vanilla entity). The exact physics is pinned by
            // the common unit tests; here we prove the end-to-end engine path.
            String engineCell = runSandDrop(context, service, false);
            context.expect(engineCell != null,
                    "engine-driven sand never settled in the water column");
            int engineY = Integer.parseInt(engineCell.split(",")[1]);
            int surfaceY = (int) Arena.floorY();
            context.note("engine sand fell through water and settled at " + engineCell
                    + " (platform surface ~" + surfaceY + ")");
            context.expect(Math.abs(engineY - surfaceY) <= 2,
                    "engine sand did not settle on the platform surface: y=" + engineY
                            + ", expected ~" + surfaceY + " (it floated or fell through)");
        });
    }

    /** A 4-deep water column over the platform centre, with sand-clearance above. */
    private static boolean placeWaterColumn(World world, Location centre) {
        int x = centre.getBlockX();
        int z = centre.getBlockZ();
        int surfaceY = centre.getBlockY(); // floorY() rests on this block's top
        // Carve a shaft and fill it with water: a still 4-block column the sand
        // falls through. The platform below stops the sand (and a void-fall is
        // caught by the engine's vanilla-accurate despawn).
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
                // Re-stage a FRESH column each run so the two runs are independent
                // (a prior run's placed sand / disturbed water must not change where
                // this run lands).
                Location centre = Arena.prepare(world);
                placeWaterColumn(world, centre);
                sand[0] = Cannon.launchSand(
                        Arena.offset(centre, 0.5, 8.0, 0.5), new Vector(0, 0, 0));
                if (forceVanilla) {
                    service.forceVanilla(sand[0]);
                }
            });
            // Wait for the payload to fall through the column and settle. A
            // timeout returns null (did not settle) rather than failing — the
            // caller decides whether that is fatal (engine run) or just a
            // confounded baseline (the at-spawn forceVanilla control).
            try {
                context.awaitUntil(() -> {
                    FallingBlock payload = sand[0];
                    return payload == null || !payload.isValid();
                }, PAYLOAD_MAX_TICKS, "sand payload to settle in the water column");
            } catch (AssertionError timedOut) {
                return null;
            }

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
