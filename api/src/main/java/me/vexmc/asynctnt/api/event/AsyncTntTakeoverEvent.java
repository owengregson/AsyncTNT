package me.vexmc.asynctnt.api.event;

import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when the off-thread engine takes ownership of an entity (a primed TNT
 * or falling block) and begins ticking it on its worker pool instead of the
 * main thread.
 *
 * <p>This event is purely observational: it is <em>not</em> cancellable and
 * carries no mutable state. By the time it fires the takeover has already
 * happened — handlers may inspect or count the entity, but cannot veto the
 * decision. Whether the engine claims a body is governed by configuration
 * ({@code engine.enabled} and {@code engine.disabled-worlds}); use those, or
 * {@code AsyncTntService.forceVanilla(Entity)}, to influence ownership.</p>
 */
public final class AsyncTntTakeoverEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Entity entity;

    /**
     * @param entity the entity the engine has just taken over
     */
    public AsyncTntTakeoverEvent(@NotNull Entity entity) {
        this.entity = entity;
    }

    /** The entity the engine has taken over (a primed TNT or falling block). */
    public @NotNull Entity getEntity() {
        return entity;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
