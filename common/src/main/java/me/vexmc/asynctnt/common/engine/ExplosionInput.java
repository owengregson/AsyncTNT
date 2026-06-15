package me.vexmc.asynctnt.common.engine;

import java.util.List;

import me.vexmc.asynctnt.common.math.Vec3d;
import me.vexmc.asynctnt.common.snapshot.BlastResistanceView;
import me.vexmc.asynctnt.common.snapshot.EntitySnapshot;
import me.vexmc.asynctnt.common.version.PhysicsProfile;

/**
 * Immutable, owning-thread-captured input for one explosion solve.
 *
 * @param center          explosion centre ({@code entity.getY(0.0625)} for TNT)
 * @param power           explosion radius/power (4.0 for TNT)
 * @param fire            whether the explosion sets fire (false for TNT)
 * @param resistance      block snapshot the ray-march reads
 * @param rayIntensities  the {@code RAY_COUNT} pre-drawn {@code nextFloat()} values, in
 *                        {@link ExplosionRays} order — replayed off-thread for an identical
 *                        destroyed-block set without touching the world RNG
 * @param entities        nearby entities (with exposure precomputed) for damage/knockback
 * @param profile         per-version behaviour switches
 */
public record ExplosionInput(Vec3d center,
                             float power,
                             boolean fire,
                             BlastResistanceView resistance,
                             float[] rayIntensities,
                             List<EntitySnapshot> entities,
                             PhysicsProfile profile) {
}
