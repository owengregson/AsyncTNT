package me.vexmc.asynctnt.engine.takeover;

import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;

import me.vexmc.asynctnt.engine.AsyncTntEngine;

/**
 * Hands every freshly spawned primed TNT / falling block to the engine, on the
 * spawn's owning thread, before its first vanilla tick. Observe-only (MONITOR,
 * never cancels) — the engine neutralizes and drives the entity itself.
 */
public final class SpawnInterceptor implements Listener {

    private final AsyncTntEngine engine;

    public SpawnInterceptor(AsyncTntEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof TNTPrimed || entity instanceof FallingBlock) {
            engine.takeOver(entity);
        }
    }
}
