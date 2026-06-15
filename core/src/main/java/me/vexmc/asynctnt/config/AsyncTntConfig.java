package me.vexmc.asynctnt.config;

import java.util.Set;

import me.vexmc.asynctnt.common.version.ForkFlags;

/**
 * Immutable configuration snapshot, swapped atomically by {@code ConfigStore}.
 * No code path reads a torn mix mid-tick.
 *
 * @param enabled        master switch for the off-thread engine
 * @param disabledWorlds worlds where the engine stays off (TNT ticks vanilla)
 * @param workerThreads  size of the compute pool (>=1)
 * @param forkFlags      opt-in cannon-stabilization toggles (all false by default)
 */
public record AsyncTntConfig(boolean enabled,
                             Set<String> disabledWorlds,
                             int workerThreads,
                             ForkFlags forkFlags) {

    public static final AsyncTntConfig DEFAULTS = new AsyncTntConfig(
            true,
            Set.of(),
            Math.max(1, Runtime.getRuntime().availableProcessors() - 2),
            ForkFlags.VANILLA);

    /** Whether the engine should own bodies in the given world. */
    public boolean enabledIn(String worldName) {
        return enabled && !disabledWorlds.contains(worldName);
    }
}
