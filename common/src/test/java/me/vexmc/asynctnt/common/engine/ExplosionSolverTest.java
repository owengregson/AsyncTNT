package me.vexmc.asynctnt.common.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.BlockPos;
import me.vexmc.asynctnt.common.math.Vec3d;
import me.vexmc.asynctnt.common.snapshot.BlastResistanceView;
import me.vexmc.asynctnt.common.snapshot.EntitySnapshot;
import me.vexmc.asynctnt.common.version.PhysicsProfile;

/**
 * Hand-computed pins for the explosion solver. Block-march gating and the
 * knockback/damage formulas are checked against decompiled 1.21.11
 * {@code ServerExplosion}; the destroyed set is exercised via controlled views
 * rather than exact ray counts (those are validated end-to-end by the live
 * parity oracle).
 */
class ExplosionSolverTest {

    private static final Aabb BOX = new Aabb(0, 0, 0, 1, 1, 1);

    private static float[] zeroRays() {
        return new float[ExplosionRays.RAY_COUNT];
    }

    @Test
    void rayTableHasExactlyTheSurfaceRays() {
        assertEquals(1352, ExplosionRays.RAY_COUNT);
        assertEquals(1352 * 3, ExplosionRays.STEPS.length);
    }

    @Test
    void allAirBreaksNothing() {
        BlastResistanceView air = new UniformView(Float.NaN, false, false, false);
        ExplosionInput in = new ExplosionInput(new Vec3d(0.5, 64.5, 0.5), 4.0f, false,
                air, zeroRays(), List.of(), PhysicsProfile.MODERN);
        ExplosionResult r = ExplosionSolver.solve(in);
        assertTrue(r.broken().isEmpty());
    }

    @Test
    void indestructibleFieldBreaksNothing() {
        // Non-air but not destroyable (bedrock-like): never collected.
        BlastResistanceView bedrock = new UniformView(0.0f, false, /*destroyable*/ false, /*nonAir*/ true);
        ExplosionInput in = new ExplosionInput(new Vec3d(0.5, 64.5, 0.5), 4.0f, false,
                bedrock, zeroRays(), List.of(), PhysicsProfile.MODERN);
        ExplosionResult r = ExplosionSolver.solve(in);
        assertTrue(r.broken().isEmpty());
    }

    @Test
    void destructibleFieldBreaksCentreAndIsDeterministic() {
        BlastResistanceView weak = new UniformView(0.0f, false, true, true);
        ExplosionInput in = new ExplosionInput(new Vec3d(0.5, 64.5, 0.5), 4.0f, false,
                weak, zeroRays(), List.of(), PhysicsProfile.MODERN);

        ExplosionResult r1 = ExplosionSolver.solve(in);
        ExplosionResult r2 = ExplosionSolver.solve(in);

        assertFalse(r1.broken().isEmpty());
        assertTrue(r1.broken().contains(new BlockPos(0, 64, 0))); // floor(centre)
        assertEquals(r1.broken(), r2.broken()); // deterministic given the same ray floats
    }

    @Test
    void tntVictimKnockbackAndDamagePins() {
        // TNT victim 2 blocks east of centre, full exposure, power 4 (diameter 8).
        EntitySnapshot victim = new EntitySnapshot(1L, /*tnt*/ true, /*living*/ false,
                new Vec3d(2, 0, 0), new Vec3d(2, 1.6, 0), BOX, 0.0, 1.0, false);
        ExplosionInput in = new ExplosionInput(new Vec3d(0, 0, 0), 4.0f, false,
                new UniformView(Float.NaN, false, false, false), zeroRays(),
                List.of(victim), PhysicsProfile.MODERN);

        EntityPush p = ExplosionSolver.solve(in).pushes().get(0);

        // d2 = sqrt(4)/8 = 0.25; scalar = (1-0.25)*1*(1-0) = 0.75 along +X.
        assertEquals(0.75, p.knockback().x(), 1.0E-12);
        assertEquals(0.0, p.knockback().y(), 1.0E-12);
        assertEquals(0.0, p.knockback().z(), 1.0E-12);
        // damage = (0.75^2 + 0.75)/2 * 7 * 8 + 1 = 37.75.
        assertEquals(37.75f, p.damage(), 1.0E-4f);
    }

    @Test
    void knockbackResistanceHalvesPush() {
        EntitySnapshot victim = new EntitySnapshot(2L, false, /*living*/ true,
                new Vec3d(2, 0, 0), new Vec3d(2, 0, 0), BOX, /*kbResist*/ 0.5, 1.0, false);
        ExplosionInput in = new ExplosionInput(new Vec3d(0, 0, 0), 4.0f, false,
                new UniformView(Float.NaN, false, false, false), zeroRays(),
                List.of(victim), PhysicsProfile.MODERN);

        EntityPush p = ExplosionSolver.solve(in).pushes().get(0);
        assertEquals(0.75 * (1.0 - 0.5), p.knockback().x(), 1.0E-12);
    }

    @Test
    void nonTntUsesEyeOriginForKnockbackDirection() {
        // Same feet, eye lifted: a non-TNT victim's knockback points from the eye.
        EntitySnapshot mob = new EntitySnapshot(3L, /*tnt*/ false, true,
                new Vec3d(2, 0, 0), new Vec3d(2, 5, 0), BOX, 0.0, 1.0, false);
        ExplosionInput in = new ExplosionInput(new Vec3d(0, 0, 0), 4.0f, false,
                new UniformView(Float.NaN, false, false, false), zeroRays(),
                List.of(mob), PhysicsProfile.MODERN);

        EntityPush p = ExplosionSolver.solve(in).pushes().get(0);
        assertTrue(p.knockback().y() > 0.0, "eye origin gives upward knockback component");
    }

    /** A homogeneous block field for controlled ray-march pins. */
    private record UniformView(float resistance, boolean out, boolean destroyable, boolean nonAir)
            implements BlastResistanceView {
        @Override
        public float resistanceOrNaN(int x, int y, int z) {
            return resistance;
        }

        @Override
        public boolean outOfWorld(int x, int y, int z) {
            return out;
        }

        @Override
        public boolean destroyable(int x, int y, int z) {
            return destroyable;
        }

        @Override
        public boolean nonAir(int x, int y, int z) {
            return nonAir;
        }

        @Override
        public int blockStateId(int x, int y, int z) {
            return 0;
        }
    }
}
