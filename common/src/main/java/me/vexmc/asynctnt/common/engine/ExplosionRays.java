package me.vexmc.asynctnt.common.engine;

/**
 * The vanilla {@code ServerExplosion.CACHED_RAYS} table: the pre-normalised,
 * 0.3-scaled step vectors for the 1352 rays cast from an explosion centre (the
 * surface of a 16x16x16 grid). Built once, identically to vanilla's static
 * initializer including the {@code (float)} direction cast — this is
 * load-bearing for a bit-exact destroyed-block set.
 *
 * <p>Layout: flat {@code double[]} of {@code [incX, incY, incZ]} triples in
 * vanilla's nested {@code x(0..15) -> y(0..15) -> z(0..15)} surface order, which
 * is also the order the per-ray {@code nextFloat()} intensities are consumed.
 */
public final class ExplosionRays {

    /** Number of rays (16^3 - 14^3). */
    public static final int RAY_COUNT = 1352;

    /** Flattened step vectors; length == RAY_COUNT * 3. */
    public static final double[] STEPS;

    static {
        double[] rays = new double[RAY_COUNT * 3];
        int i = 0;
        for (int x = 0; x <= 15; ++x) {
            for (int y = 0; y <= 15; ++y) {
                for (int z = 0; z <= 15; ++z) {
                    if (x != 0 && x != 15 && y != 0 && y != 15 && z != 0 && z != 15) {
                        continue;
                    }
                    double xDir = (float) x / 15.0f * 2.0f - 1.0f;
                    double yDir = (float) y / 15.0f * 2.0f - 1.0f;
                    double zDir = (float) z / 15.0f * 2.0f - 1.0f;
                    double mag = Math.sqrt(xDir * xDir + yDir * yDir + zDir * zDir);
                    rays[i++] = xDir / mag * (double) 0.3f;
                    rays[i++] = yDir / mag * (double) 0.3f;
                    rays[i++] = zDir / mag * (double) 0.3f;
                }
            }
        }
        STEPS = rays;
    }

    private ExplosionRays() {
    }
}
