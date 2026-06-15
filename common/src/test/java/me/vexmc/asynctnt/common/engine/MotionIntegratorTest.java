package me.vexmc.asynctnt.common.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.Vec3d;
import me.vexmc.asynctnt.common.snapshot.BlockCollisionView;
import me.vexmc.asynctnt.common.snapshot.BodyState;
import me.vexmc.asynctnt.common.snapshot.FluidView;
import me.vexmc.asynctnt.common.version.ForkFlags;
import me.vexmc.asynctnt.common.version.PhysicsProfile;

/**
 * Hand-computed pins for the movement integrator. Expected values are derived
 * from the vanilla constants (gravity 0.04, drag 0.98, ground 0.7/-0.5/0.7,
 * water flow 0.014) and integration order verified against decompiled 1.21.11
 * {@code PrimedTnt.tick} / {@code Entity.move}, NOT from running the code.
 */
class MotionIntegratorTest {

    private static final PhysicsProfile P = PhysicsProfile.MODERN;
    private static final ForkFlags F = ForkFlags.VANILLA;

    private static MotionResult tick(BodyState s, BlockCollisionView blocks, FluidView fluids) {
        return MotionIntegrator.tick(s, blocks, fluids, P, F);
    }

    /** A floor plane occupying y in [63,64] over a wide x/z area. */
    private static BlockCollisionView floorPlane() {
        return new BlockCollisionView() {
            @Override
            public List<Aabb> collisionBoxes(Aabb movementBounds) {
                return List.of(new Aabb(-10, 63, -10, 10, 64, 10));
            }

            @Override
            public double blockSpeedFactor(double x, double y, double z) {
                return 1.0;
            }
        };
    }

    @Test
    void freeFallOneTick() {
        BodyState s = BodyState.tnt(0, 100, 0, 0, 0, 0, 80);
        MotionResult r = tick(s, BlockCollisionView.EMPTY, FluidView.EMPTY);

        // gravity then drag: dy = (0 - 0.04) * 0.98; position moves by the pre-drag dy.
        assertEquals((0.0 - 0.04) * 0.98, r.state().dy(), 0.0);
        assertEquals(100.0 + (0.0 - 0.04), r.state().y(), 0.0);
        assertEquals(0.0, r.state().dx(), 0.0);
        assertEquals(0.0, r.state().dz(), 0.0);
        assertEquals(79, r.state().fuseOrTime());
        assertFalse(r.detonate());
        assertFalse(r.state().onGround());
    }

    @Test
    void fuseReachesZeroDetonates() {
        BodyState s = BodyState.tnt(0, 100, 0, 0, 0, 0, 1);
        MotionResult r = tick(s, BlockCollisionView.EMPTY, FluidView.EMPTY);
        assertEquals(0, r.state().fuseOrTime());
        assertTrue(r.detonate());
    }

    @Test
    void tntOnGroundDragsThenFrictions() {
        // Sliding on the floor at dx=1: TNT drags (×0.98) then ground-frictions (×0.7).
        BodyState s = BodyState.tnt(0.5, 64.0, 0.5, 1.0, 0.0, 0.0, 80);
        MotionResult r = tick(s, floorPlane(), FluidView.EMPTY);

        assertTrue(r.state().onGround());
        assertEquals(1.0 * 0.98 * 0.7, r.state().dx(), 1.0E-12);
        assertEquals(0.0, r.state().dy(), 0.0);
        assertEquals(64.0, r.state().y(), 1.0E-9);
    }

    @Test
    void fallingBlockFrictionsThenDragsSameHorizontalResult() {
        // Multiplication commutes, so horizontal result matches TNT; pins the friction value.
        BodyState s = BodyState.fallingBlock(0.5, 64.0, 0.5, 1.0, 0.0, 0.0, 0, 1);
        MotionResult r = tick(s, floorPlane(), FluidView.EMPTY);

        assertTrue(r.state().onGround());
        assertTrue(r.landed());
        assertEquals(1.0 * 0.7 * 0.98, r.state().dx(), 1.0E-12);
        assertEquals(1, r.state().fuseOrTime()); // time incremented
    }

    @Test
    void tntPushedByWaterCurrent() {
        // A water cell with an eastward flow pushes the TNT by the 0.014 flow scale.
        FluidView water = deflated -> List.of(new FluidView.FluidCell(64, 0.888f, new Vec3d(1, 0, 0)));
        BodyState s = BodyState.tnt(0.5, 64.5, 0.5, 0, 0, 0, 80);
        MotionResult r = tick(s, BlockCollisionView.EMPTY, water);

        assertEquals(0.014, r.state().dx(), 1.0E-9);
        assertEquals((0.0 - 0.04) * 0.98, r.state().dy(), 1.0E-12);
    }

    @Test
    void fallingBlockIgnoresWaterCurrent() {
        // The cannon-critical asymmetry: sand/gravel fall through water undeflected.
        FluidView water = deflated -> List.of(new FluidView.FluidCell(64, 0.888f, new Vec3d(1, 0, 0)));
        BodyState s = BodyState.fallingBlock(0.5, 64.5, 0.5, 0, 0, 0, 0, 1);
        MotionResult r = tick(s, BlockCollisionView.EMPTY, water);

        assertEquals(0.0, r.state().dx(), 0.0);
        assertEquals(0.0, r.state().dz(), 0.0);
    }

    @Test
    void deterministicGivenSameInput() {
        BodyState s = BodyState.tnt(0.25, 70.5, -3.5, 0.13, -0.2, 0.07, 50);
        MotionResult a = tick(s, floorPlane(), FluidView.EMPTY);
        MotionResult b = tick(s, floorPlane(), FluidView.EMPTY);
        assertEquals(a.state().x(), b.state().x(), 0.0);
        assertEquals(a.state().y(), b.state().y(), 0.0);
        assertEquals(a.state().z(), b.state().z(), 0.0);
        assertEquals(a.state().dx(), b.state().dx(), 0.0);
        assertEquals(a.state().dy(), b.state().dy(), 0.0);
        assertEquals(a.state().dz(), b.state().dz(), 0.0);
    }
}
