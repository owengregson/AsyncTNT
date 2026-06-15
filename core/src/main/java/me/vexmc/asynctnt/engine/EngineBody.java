package me.vexmc.asynctnt.engine;

import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;

import me.vexmc.asynctnt.common.scheduling.TaskHandle;
import me.vexmc.asynctnt.common.snapshot.BodyState;

/**
 * One engine-owned body: the shadow {@link Entity} plus the authoritative
 * physics {@link BodyState} the engine drives it from. For falling blocks the
 * Bukkit {@link BlockData} is kept here (the pure core treats the block-state
 * id as opaque); the engine uses it to place/drop on landing.
 */
final class EngineBody {

    final Entity entity;
    final @Nullable BlockData fallingBlockData;
    BodyState state;
    @Nullable TaskHandle driver;
    volatile boolean released;

    EngineBody(Entity entity, BodyState state, @Nullable BlockData fallingBlockData) {
        this.entity = entity;
        this.state = state;
        this.fallingBlockData = fallingBlockData;
    }
}
