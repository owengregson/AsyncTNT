package me.vexmc.asynctnt;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import me.vexmc.asynctnt.api.AsyncTntService;
import me.vexmc.asynctnt.engine.EngineHandle;

/**
 * Thin adapter implementing the public {@link AsyncTntService}, registered with
 * the Bukkit services manager. Every query and command delegates to the
 * {@link EngineHandle}, so the API surface stays decoupled from the engine's
 * internals — other plugins and the integration tester talk only to this
 * facade.
 */
public final class AsyncTntManager implements AsyncTntService {

    private final EngineHandle engine;

    public AsyncTntManager(@NotNull EngineHandle engine) {
        this.engine = engine;
    }

    @Override
    public boolean isEngineActive() {
        return engine.isActive();
    }

    @Override
    public int ownedCount() {
        return engine.ownedCount();
    }

    @Override
    public @NotNull String describe() {
        return engine.schedulingBackend();
    }

    @Override
    public boolean forceVanilla(@NotNull Entity entity) {
        return engine.forceVanilla(entity);
    }

    @Override
    public void setEnginePaused(boolean paused) {
        engine.setPaused(paused);
    }

    @Override
    public boolean isEnginePaused() {
        return engine.isPaused();
    }
}
