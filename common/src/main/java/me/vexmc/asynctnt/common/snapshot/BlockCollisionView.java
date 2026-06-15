package me.vexmc.asynctnt.common.snapshot;

import java.util.List;

import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.Vec3d;

/**
 * Immutable, owning-thread-captured view of the block collision environment a
 * body could touch this tick. Off-thread code reads only this snapshot — never
 * a live world. Returned AABBs are world-space and may be a block's full
 * {@code VoxelShape} decomposed into boxes (slabs/stairs/fences/panes) so the
 * integrator reproduces {@code Entity.collide} bit-exactly.
 */
public interface BlockCollisionView {

    /** World-space collision boxes intersecting the expanded movement bounds. */
    List<Aabb> collisionBoxes(Aabb movementBounds);

    /** {@code Entity.getBlockSpeedFactor()} — 1.0 normally; soul-sand/honey reduce it. */
    double blockSpeedFactor(double x, double y, double z);

    /**
     * {@code applyEffectsFromBlocks} stuck-speed multiplier (cobweb, sweet-berry,
     * powder snow). Identity (1,1,1) when none apply — the common cannon case.
     */
    default Vec3d stuckSpeedMultiplier(Aabb entityBounds) {
        return new Vec3d(1.0, 1.0, 1.0);
    }

    BlockCollisionView EMPTY = new BlockCollisionView() {
        @Override
        public List<Aabb> collisionBoxes(Aabb movementBounds) {
            return List.of();
        }

        @Override
        public double blockSpeedFactor(double x, double y, double z) {
            return 1.0;
        }
    };
}
