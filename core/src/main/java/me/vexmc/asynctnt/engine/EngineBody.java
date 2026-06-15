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
    // Volatile: usually written by this body's own driver, but an explosion in
    // another body's detonation also adds knockback to it (possibly from a
    // different region thread on Folia) — the immutable BodyState reference is
    // swapped atomically, so a concurrent read sees a consistent old-or-new value.
    volatile BodyState state;
    @Nullable TaskHandle driver;
    volatile boolean released;

    // Deterministic ordering for the per-region single pass (see AsyncTntEngine.coordinatedTick):
    // spawnSeq is a monotonic spawn-order index — the off-thread analogue of vanilla's
    // entity-list tick order — and lastPassTick guards exactly-once integration per server
    // tick so the several per-entity drivers that fire each tick collapse into one ordered scan.
    long spawnSeq;
    volatile long lastPassTick = Long.MIN_VALUE;

    EngineBody(Entity entity, BodyState state, @Nullable BlockData fallingBlockData) {
        this.entity = entity;
        this.state = state;
        this.fallingBlockData = fallingBlockData;
    }
}
