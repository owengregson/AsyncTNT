package me.vexmc.asynctnt.common.snapshot;

/**
 * Owning-thread snapshot of the block data the explosion ray-march reads inside
 * the blast cube: per-block explosion resistance, whether the block is
 * destroyable/air, and an opaque block-state id for the apply phase. All reads
 * happen off-thread against this immutable view, never the live world.
 */
public interface BlastResistanceView {

    /**
     * {@code max(block, fluid)} explosion resistance at the position, or
     * {@link Float#NaN} when the position is air with no fluid — vanilla treats
     * that as an empty {@code Optional}, which the solver maps to the
     * {@code -0.3f} sentinel (contributing 0 to the ray decay).
     */
    float resistanceOrNaN(int x, int y, int z);

    /** Whether the position is outside the loaded world — the ray terminates. */
    boolean outOfWorld(int x, int y, int z);

    /** Whether the block may be destroyed by an explosion (false for air/indestructible). */
    boolean destroyable(int x, int y, int z);

    /** {@code !blockState.isAir()} — only non-air positions are collected for destruction. */
    boolean nonAir(int x, int y, int z);

    /** Opaque per-version block-state id at the position (for the apply phase). */
    int blockStateId(int x, int y, int z);
}
