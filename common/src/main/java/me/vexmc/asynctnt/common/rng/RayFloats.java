package me.vexmc.asynctnt.common.rng;

import me.vexmc.asynctnt.common.engine.ExplosionRays;

/**
 * Helper for drawing the explosion's per-ray intensity floats on the owning
 * region thread, in the exact {@link ExplosionRays} order, so the off-thread
 * solver can replay them for a bit-identical destroyed-block set without ever
 * touching the world's shared RNG from a worker thread.
 */
public final class RayFloats {

    /** A source of {@code Random.nextFloat()} values from the owning thread. */
    @FunctionalInterface
    public interface FloatSource {
        float nextFloat();
    }

    private RayFloats() {
    }

    /** Draw exactly {@link ExplosionRays#RAY_COUNT} floats in ray order. */
    public static float[] draw(FloatSource source) {
        float[] out = new float[ExplosionRays.RAY_COUNT];
        for (int i = 0; i < out.length; i++) {
            out[i] = source.nextFloat();
        }
        return out;
    }

    /** Validate a pre-drawn array has the exact ray count the solver expects. */
    public static float[] require(float[] intensities) {
        if (intensities.length != ExplosionRays.RAY_COUNT) {
            throw new IllegalArgumentException(
                    "expected " + ExplosionRays.RAY_COUNT + " ray intensities, got " + intensities.length);
        }
        return intensities;
    }
}
