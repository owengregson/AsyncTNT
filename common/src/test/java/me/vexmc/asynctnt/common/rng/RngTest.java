package me.vexmc.asynctnt.common.rng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import me.vexmc.asynctnt.common.engine.ExplosionRays;

class RngTest {

    @Test
    void seededRngIsDeterministic() {
        SeededRng a = new SeededRng(42L);
        SeededRng b = new SeededRng(42L);
        for (int i = 0; i < 64; i++) {
            assertEquals(a.nextFloat(), b.nextFloat(), 0.0);
        }
    }

    @Test
    void seededRngFloatsAreInUnitRange() {
        SeededRng r = new SeededRng(123456789L);
        for (int i = 0; i < 10_000; i++) {
            float f = r.nextFloat();
            assertTrue(f >= 0.0f && f < 1.0f, "float out of [0,1): " + f);
        }
    }

    @Test
    void rayFloatsDrawsExactlyTheRayCount() {
        float[] drawn = RayFloats.draw(() -> 0.5f);
        assertEquals(ExplosionRays.RAY_COUNT, drawn.length);
        assertEquals(0.5f, drawn[0], 0.0f);
    }

    @Test
    void rayFloatsRequireRejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> RayFloats.require(new float[10]));
    }
}
