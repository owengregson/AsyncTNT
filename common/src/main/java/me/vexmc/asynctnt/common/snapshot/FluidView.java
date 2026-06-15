package me.vexmc.asynctnt.common.snapshot;

import java.util.List;

import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.Vec3d;

/**
 * Owning-thread snapshot of the water cells overlapping a body's (deflated)
 * bounding box, with each cell's own surface height and flow vector — exactly
 * the inputs {@code Entity.updateFluidHeightAndDoFluidPushing} consumes. This is
 * what propels water-stream TNT cannons; falling blocks ignore it.
 */
public interface FluidView {

    /** Water cells overlapping the given (already deflated by 0.001) box. */
    List<FluidCell> waterCells(Aabb deflatedBounds);

    FluidView EMPTY = deflatedBounds -> List.of();

    /**
     * One water cell. {@code height} is {@code FluidState.getHeight} (0..1, e.g.
     * ~0.888 for a full source). {@code flow} is {@code FluidState.getFlow}.
     */
    record FluidCell(int y, float height, Vec3d flow) {
    }
}
