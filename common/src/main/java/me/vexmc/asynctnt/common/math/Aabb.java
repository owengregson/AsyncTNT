package me.vexmc.asynctnt.common.math;

/**
 * Immutable axis-aligned bounding box mirroring {@code net.minecraft.world.phys.AABB}.
 * Carries the exact {@code expandTowards} and per-axis collision-clip semantics
 * the movement integrator needs to reproduce {@code Entity.collide} 1:1 for
 * box-decomposable collision shapes (all vanilla shapes are unions of AABBs).
 */
public record Aabb(double minX, double minY, double minZ,
                   double maxX, double maxY, double maxZ) {

    /** Full unit cube whose feet (min-corner of x/z is centred) sit at the block. */
    public static Aabb ofBlock(int x, int y, int z) {
        return new Aabb(x, y, z, x + 1.0, y + 1.0, z + 1.0);
    }

    /** Entity box centred horizontally on (x,z) with feet at y. */
    public static Aabb ofEntity(double x, double y, double z, double width, double height) {
        double half = width / 2.0;
        return new Aabb(x - half, y, z - half, x + half, y + height, z + half);
    }

    public Aabb move(double dx, double dy, double dz) {
        return new Aabb(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** Vanilla {@code AABB.deflate}: shrink by d on every face. */
    public Aabb deflate(double d) {
        return new Aabb(minX + d, minY + d, minZ + d, maxX - d, maxY - d, maxZ - d);
    }

    /** Vanilla {@code AABB.expandTowards}: grow the box along the movement vector. */
    public Aabb expandTowards(double dx, double dy, double dz) {
        double x0 = minX, y0 = minY, z0 = minZ, x1 = maxX, y1 = maxY, z1 = maxZ;
        if (dx < 0.0) {
            x0 += dx;
        } else if (dx > 0.0) {
            x1 += dx;
        }
        if (dy < 0.0) {
            y0 += dy;
        } else if (dy > 0.0) {
            y1 += dy;
        }
        if (dz < 0.0) {
            z0 += dz;
        } else if (dz > 0.0) {
            z1 += dz;
        }
        return new Aabb(x0, y0, z0, x1, y1, z1);
    }

    public boolean intersects(Aabb o) {
        return minX < o.maxX && maxX > o.minX
                && minY < o.maxY && maxY > o.minY
                && minZ < o.maxZ && maxZ > o.minZ;
    }
}
