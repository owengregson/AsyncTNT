package me.vexmc.asynctnt.common.snapshot;

import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.Vec3d;

/**
 * Immutable authoritative physics state of one engine-owned body (a primed TNT
 * or a falling block). The engine holds this as the source of truth and syncs
 * the shadow entity to it; the pure integrator consumes and returns it.
 *
 * <p>{@code fuseOrTime} is the TNT fuse countdown or the falling-block age.
 * {@code blockStateId} is an opaque per-version block-state id (falling blocks
 * only). Both TNT and falling blocks are 0.98-cube entities in vanilla.
 */
public record BodyState(Kind kind,
                        double x, double y, double z,
                        double dx, double dy, double dz,
                        int fuseOrTime,
                        boolean onGround,
                        int blockStateId) {

    public enum Kind {
        TNT(0.98, 0.98, true),
        FALLING_BLOCK(0.98, 0.98, false);

        public final double width;
        public final double height;
        /** TNT is pushed by flowing water; falling blocks are not (they fall through it). */
        public final boolean pushedByFluid;

        Kind(double width, double height, boolean pushedByFluid) {
            this.width = width;
            this.height = height;
            this.pushedByFluid = pushedByFluid;
        }
    }

    public static BodyState tnt(double x, double y, double z,
                                double dx, double dy, double dz, int fuse) {
        return new BodyState(Kind.TNT, x, y, z, dx, dy, dz, fuse, false, 0);
    }

    public static BodyState fallingBlock(double x, double y, double z,
                                         double dx, double dy, double dz,
                                         int time, int blockStateId) {
        return new BodyState(Kind.FALLING_BLOCK, x, y, z, dx, dy, dz, time, false, blockStateId);
    }

    public Vec3d position() {
        return new Vec3d(x, y, z);
    }

    public Vec3d motion() {
        return new Vec3d(dx, dy, dz);
    }

    public Aabb boundingBox() {
        return Aabb.ofEntity(x, y, z, kind.width, kind.height);
    }

    public BodyState withPosition(Vec3d p) {
        return new BodyState(kind, p.x(), p.y(), p.z(), dx, dy, dz, fuseOrTime, onGround, blockStateId);
    }

    public BodyState withMotion(Vec3d m) {
        return new BodyState(kind, x, y, z, m.x(), m.y(), m.z(), fuseOrTime, onGround, blockStateId);
    }

    public BodyState withOnGround(boolean g) {
        return new BodyState(kind, x, y, z, dx, dy, dz, fuseOrTime, g, blockStateId);
    }

    public BodyState withFuseOrTime(int f) {
        return new BodyState(kind, x, y, z, dx, dy, dz, f, onGround, blockStateId);
    }
}
