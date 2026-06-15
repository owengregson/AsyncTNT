package me.vexmc.asynctnt.common.engine;

import me.vexmc.asynctnt.common.math.Vec3d;

/**
 * One entity's explosion effect, produced off-thread and applied on the owning
 * region thread: {@code knockback} is added to the entity's delta movement
 * (or sent via the vanilla {@code hitPlayers} packet path for players), and
 * {@code damage} is dealt.
 */
public record EntityPush(long entityId, Vec3d knockback, float damage) {
}
