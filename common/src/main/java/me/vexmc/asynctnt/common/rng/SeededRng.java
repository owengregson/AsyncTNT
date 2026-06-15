package me.vexmc.asynctnt.common.rng;

/**
 * Deterministic SplitMix64-based generator for the off-aim explosion randomness
 * (block-drop decay rolls, drop-stack shuffle) in the optional performance mode
 * that does not bounce those draws back to the owning thread. Seeded
 * per-explosion (e.g. from block position + tick) so results are reproducible
 * and never advance the world's shared RNG. The strict-vanilla default instead
 * draws these on the owning thread at apply time; this is the opt-in path.
 *
 * <p>Drop decay and shuffle do NOT affect cannon aim (only which items drop and
 * in what order), so divergence from {@code level.random} here is safe.
 */
public final class SeededRng {

    private long state;

    public SeededRng(long seed) {
        this.state = seed;
    }

    private long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform float in [0, 1), matching {@code Random.nextFloat}'s 24-bit resolution. */
    public float nextFloat() {
        return (nextLong() >>> 40) * 0x1.0p-24f;
    }

    /** Uniform int in [0, bound). */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return (int) Math.floorMod(nextLong(), (long) bound);
    }
}
