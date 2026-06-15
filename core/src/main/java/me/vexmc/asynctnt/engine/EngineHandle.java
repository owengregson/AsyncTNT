package me.vexmc.asynctnt.engine;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * Narrow view of the engine exposed to the API service and commands, so those
 * pieces can be written without depending on the engine's internals.
 */
public interface EngineHandle {

    /** Whether the off-thread engine is running. */
    boolean isActive();

    /** Number of TNT/falling-block bodies the engine currently owns. */
    int ownedCount();

    /**
     * Release a body back to vanilla server ticking (used by the parity oracle
     * and the kill-switch). Returns true if the entity was owned.
     */
    boolean forceVanilla(@NotNull Entity entity);

    /** The active scheduling backend: "folia" or "bukkit". */
    @NotNull String schedulingBackend();
}
