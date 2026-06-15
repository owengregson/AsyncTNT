package me.vexmc.asynctnt.common.math;

/**
 * Immutable 3D double vector mirroring {@code net.minecraft.world.phys.Vec3}'s
 * semantics. Lives in {@code common} with no Bukkit/NMS dependency so the pure
 * physics core is unit-testable off-thread.
 *
 * <p>Operation and rounding order match vanilla exactly where it matters for
 * 1:1 parity (see {@link #normalize()} — vanilla returns ZERO below a 1.0E-4
 * length, which is load-bearing for coincident explosion knockback).
 */
public record Vec3d(double x, double y, double z) {

    public static final Vec3d ZERO = new Vec3d(0.0, 0.0, 0.0);

    public Vec3d add(Vec3d o) {
        return new Vec3d(x + o.x, y + o.y, z + o.z);
    }

    public Vec3d add(double dx, double dy, double dz) {
        return new Vec3d(x + dx, y + dy, z + dz);
    }

    public Vec3d subtract(Vec3d o) {
        return new Vec3d(x - o.x, y - o.y, z - o.z);
    }

    public Vec3d scale(double factor) {
        return new Vec3d(x * factor, y * factor, z * factor);
    }

    public Vec3d multiply(double fx, double fy, double fz) {
        return new Vec3d(x * fx, y * fy, z * fz);
    }

    public double lengthSqr() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSqr());
    }

    /** Vanilla {@code Vec3.normalize}: returns ZERO below a 1.0E-4 length. */
    public Vec3d normalize() {
        double len = Math.sqrt(x * x + y * y + z * z);
        return len < 1.0E-4 ? ZERO : new Vec3d(x / len, y / len, z / len);
    }
}
