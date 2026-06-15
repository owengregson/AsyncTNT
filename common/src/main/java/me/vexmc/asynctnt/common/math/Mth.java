package me.vexmc.asynctnt.common.math;

/**
 * Subset of {@code net.minecraft.util.Mth} reproduced here so the physics core
 * has zero NMS dependency. Only the operations the integrator/solver need, with
 * vanilla's exact semantics (the float-widened equality epsilon in particular).
 */
public final class Mth {

    /** Vanilla {@code Mth.EPSILON} (the float 1.0E-5 widened to double). */
    public static final double EQUAL_EPSILON = 9.999999747378752E-6;

    private Mth() {
    }

    public static int floor(double value) {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }

    public static int ceil(double value) {
        int i = (int) value;
        return value > (double) i ? i + 1 : i;
    }

    /** Vanilla {@code Mth.equal}: |b - a| &lt; EPSILON. */
    public static boolean equal(double a, double b) {
        return Math.abs(b - a) < EQUAL_EPSILON;
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    public static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
