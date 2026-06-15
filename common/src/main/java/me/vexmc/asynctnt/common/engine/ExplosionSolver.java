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
 * The pure, off-thread explosion solver. Reproduces {@code ServerExplosion}
 * bit-for-bit against an immutable snapshot: the 1352-ray block-destruction
 * march (consuming the pre-drawn per-ray intensities so the destroyed set
 * matches vanilla exactly) and the per-entity damage/knockback. Zero world
 * access; zero live RNG (intensities are pre-drawn on the owning thread).
 *
 * <p>Constants verified against decompiled 1.21.11 {@code ServerExplosion}:
 * per-ray {@code power = radius*(0.7f + r*0.6f)}; per-block decay
 * {@code (resistanceOrSentinel + 0.3f) * 0.3f}; air sentinel {@code -0.3f};
 * per-step {@code power -= 0.22500001f}; collect while {@code power > 0}.
 */
public final class ExplosionSolver {

    private static final float STEP_DECAY = 0.22500001f;
    private static final float AIR_SENTINEL = -0.3f;

    private ExplosionSolver() {
    }

    public static ExplosionResult solve(ExplosionInput in) {
        return new ExplosionResult(calculateExplodedPositions(in), hurtEntities(in));
    }

    private static List<BlockPos> calculateExplodedPositions(ExplosionInput in) {
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

    private static List<EntityPush> hurtEntities(ExplosionInput in) {
        List<EntityPush> pushes = new ArrayList<>();
        if (in.power() < 1.0E-5f) {
            return pushes;
        }
        Vec3d center = in.center();
        double diameter = in.power() * 2.0;
        for (EntitySnapshot e : in.entities()) {
            if (e.ignored()) {
                continue;
            }
            double distSqr = e.feet().subtract(center).lengthSqr();
            double d2 = Math.sqrt(distSqr) / diameter;
            if (d2 > 1.0) {
                continue;
            }
            double seen = e.seenPercent();

            // Damage: (d1^2 + d1)/2 * 7 * diameter + 1, with d1 = (1 - d2) * seen.
            double d1 = (1.0 - d2) * seen;
            float damage = (float) ((d1 * d1 + d1) / 2.0 * 7.0 * diameter + 1.0);

            // Knockback: (1 - d2) * seen * knockbackMultiplier(=1) * (1 - kbResist).
            double kbResist = e.isLiving() ? e.knockbackResist() : 0.0;
            double scalar = (1.0 - d2) * seen * (1.0 - kbResist);
            Vec3d dir = e.knockbackOrigin().subtract(center).normalize();
            Vec3d knockback = dir.scale(scalar);

            pushes.add(new EntityPush(e.id(), knockback, damage));
        }
        return pushes;
    }
}
