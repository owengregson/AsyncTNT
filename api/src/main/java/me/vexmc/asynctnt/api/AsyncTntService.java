package me.vexmc.asynctnt.api;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * Public service facade for AsyncTNT, registered with the Bukkit services
 * manager. Other plugins (and the integration tester) query engine status and
 * can force individual bodies back onto the vanilla tick path.
 *
 * <p>This is a scaffold surface; the full contract is fleshed out in the
 * api task of the implementation plan.
 */
public interface AsyncTntService {

    /** Whether the off-thread engine is active on this server. */
    boolean isEngineActive();

    /** Number of TNT/falling-block bodies currently owned by the engine. */
    int ownedCount();

    /** Describes the active scheduling backend ("folia" or "bukkit"). */
    @NotNull String describe();

    /**
     * Returns the given entity to vanilla server ticking, releasing engine
     * ownership. Used by the parity oracle to compare engine-on vs engine-off.
     *
     * @return true if the entity was owned and has been released
     */
    boolean forceVanilla(@NotNull Entity entity);
}
