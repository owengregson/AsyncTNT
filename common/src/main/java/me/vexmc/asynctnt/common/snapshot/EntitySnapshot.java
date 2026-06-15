package me.vexmc.asynctnt.common.snapshot;

import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.Vec3d;

/**
 * Owning-thread snapshot of an entity within an explosion's range, carrying the
 * fields the solver needs to compute exposure, damage, and knockback off-thread.
 *
 * @param id              opaque stable id used to re-resolve the live entity at apply time
 * @param isPrimedTnt     TNT victims take knockback from the explosion centre toward their feet
 * @param isLiving        living entities consult the explosion-knockback-resistance attribute
 * @param feet            {@code entity.position()} (used for PrimedTnt knockback origin)
 * @param eye             {@code entity.getEyePosition()} (knockback origin for non-TNT)
 * @param box             entity bounding box (drives the {@code getSeenPercent} sample grid)
 * @param knockbackResist explosion-knockback-resistance attribute value (0 for non-living)
 * @param seenPercent     {@code getSeenPercent}/{@code getBlockDensity} exposure, captured on the
 *                        owning thread for this explosion (entity-exposure raycasts read the world,
 *                        so they are snapshotted rather than run off-thread)
 * @param ignored         {@code entity.ignoreExplosion(this)} — skipped entirely when true
 */
public record EntitySnapshot(long id,
                             boolean isPrimedTnt,
                             boolean isLiving,
                             Vec3d feet,
                             Vec3d eye,
                             Aabb box,
                             double knockbackResist,
                             double seenPercent,
                             boolean ignored) {

    /** Knockback origin: feet for primed TNT, eye position otherwise (vanilla). */
    public Vec3d knockbackOrigin() {
        return isPrimedTnt ? feet : eye;
    }
}
