package me.vexmc.asynctnt.common.engine;

import java.util.List;

import me.vexmc.asynctnt.common.math.BlockPos;

/**
 * Off-thread explosion solve output, applied on the owning region thread(s):
 * the destroyed-block set (in vanilla discovery order) and per-entity pushes.
 */
public record ExplosionResult(List<BlockPos> broken, List<EntityPush> pushes) {
}
