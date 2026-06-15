package me.vexmc.asynctnt.common.engine;

import me.vexmc.asynctnt.common.snapshot.BodyState;

/**
 * Result of one {@link MotionIntegrator} tick: the new body state plus the two
 * lifecycle signals the apply phase acts on.
 *
 * @param state    the integrated body state (new position, motion, fuse/time, onGround)
 * @param detonate the TNT fuse reached 0 this tick — the body explodes and is removed
 * @param landed   the falling block is on the ground this tick — resolve place-or-drop
 */
public record MotionResult(BodyState state, boolean detonate, boolean landed) {
}
