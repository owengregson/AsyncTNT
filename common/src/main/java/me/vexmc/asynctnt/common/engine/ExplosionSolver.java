package me.vexmc.asynctnt.common.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.vexmc.asynctnt.common.math.BlockPos;
import me.vexmc.asynctnt.common.math.Mth;
import me.vexmc.asynctnt.common.math.Vec3d;
import me.vexmc.asynctnt.common.snapshot.BlastResistanceView;
import me.vexmc.asynctnt.common.snapshot.EntitySnapshot;

/**
 * The pure explosion solver. Reproduces {@code ServerExplosion} bit-for-bit
 * against an immutable snapshot: the 1352-ray block-destruction march
 * ({@link #solveBlocks}) and the per-entity damage/knockback
 * ({@link #solveKnockback} / {@link #knockbackFor}). Zero world access; zero
 * live RNG (intensities are pre-drawn on the owning thread).
 *
 * <p>The two halves are independent: knockback (cannon-relevant, RNG-free, cheap)
 * needs no rays, so the engine runs it inline on the owning thread for same-tick
 * parity, while the heavy {@link #solveBlocks} march is offloaded to a worker
 * (block-break latency is imperceptible and within the parity bar).
 *
 * <p>Constants verified against decompiled 1.21.11 {@code ServerExplosion}:
 * per-ray {@code power = radius*(0.7f + r*0.6f)}; per-block decay
 * {@code (resistanceOrSentinel + 0.3f) * 0.3f}; air sentinel {@code -0.3f};
 * per-step {@code power -= 0.22500001f}; collect while {@code power > 0}.
 * Knockback {@code (1 - dist/2r) * seen * (1 - kbResist)} from centre toward the
 * TNT feet / entity eye; damage {@code (d1^2+d1)/2 * 7 * 2r + 1}.
 */
public final class ExplosionSolver {

    private static final float STEP_DECAY = 0.22500001f;
    private static final float AIR_SENTINEL = -0.3f;

    private ExplosionSolver() {
    }

    /** Both halves — used by unit tests; the engine calls the halves separately. */
    public static ExplosionResult solve(ExplosionInput in) {
        return new ExplosionResult(solveBlocks(in), solveKnockback(in));
    }

    /** The 1352-ray block-destruction march (offloaded to a worker thread). */
    public static List<BlockPos> solveBlocks(ExplosionInput in) {
        BlastResistanceView view = in.resistance();
        float[] intensities = in.rayIntensities();
        Vec3d center = in.center();
        List<BlockPos> broken = new ArrayList<>();
        Set<BlockPos> decided = new HashSet<>();
        double[] steps = ExplosionRays.STEPS;

        int rayIndex = 0;
        for (int r = 0; r < steps.length; r += 3) {
            double incX = steps[r];
            double incY = steps[r + 1];
            double incZ = steps[r + 2];
            float power = in.power() * (0.7f + intensities[rayIndex] * 0.6f);
            rayIndex++;

            double currX = center.x();
            double currY = center.y();
            double currZ = center.z();
            do {
                int bx = Mth.floor(currX);
                int by = Mth.floor(currY);
                int bz = Mth.floor(currZ);
                if (view.outOfWorld(bx, by, bz)) {
                    break; // ray terminates; continue with the next ray
                }
                float res = view.resistanceOrNaN(bx, by, bz);
                float term = ((Float.isNaN(res) ? AIR_SENTINEL : res) + 0.3f) * 0.3f;
                power -= term;
                if (power > 0.0f) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    if (decided.add(pos)) {
                        if (view.destroyable(bx, by, bz) && (in.fire() || view.nonAir(bx, by, bz))) {
                            broken.add(pos);
                        }
                    }
                }
                currX += incX;
                currY += incY;
                currZ += incZ;
            } while ((power -= STEP_DECAY) > 0.0f);
        }
        return broken;
    }

    /** Per-entity damage + knockback (run inline on the owning thread for same-tick parity). */
    public static List<EntityPush> solveKnockback(ExplosionInput in) {
        List<EntityPush> pushes = new ArrayList<>();
        for (EntitySnapshot e : in.entities()) {
            EntityPush push = knockbackFor(in.center(), in.power(), e);
            if (push != null) {
                pushes.add(push);
            }
        }
        return pushes;
    }

    /**
     * The damage + knockback for a single entity, or {@code null} if it is out of
     * range or ignores the explosion. Pure and RNG-free — this is what the engine
     * applies inline on the owning thread the same tick the TNT detonates.
     */
    public static EntityPush knockbackFor(Vec3d center, double power, EntitySnapshot e) {
        if (e.ignored() || power < 1.0E-5) {
            return null;
        }
        double diameter = power * 2.0;
        double distSqr = e.feet().subtract(center).lengthSqr();
        double d2 = Math.sqrt(distSqr) / diameter;
        if (d2 > 1.0) {
            return null;
        }
        double seen = e.seenPercent();
        double d1 = (1.0 - d2) * seen;
        float damage = (float) ((d1 * d1 + d1) / 2.0 * 7.0 * diameter + 1.0);

        double kbResist = e.isLiving() ? e.knockbackResist() : 0.0;
        double scalar = (1.0 - d2) * seen * (1.0 - kbResist);
        Vec3d dir = e.knockbackOrigin().subtract(center).normalize();
        return new EntityPush(e.id(), dir.scale(scalar), damage);
    }
}
