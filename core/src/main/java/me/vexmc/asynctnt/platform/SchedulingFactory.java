package me.vexmc.asynctnt.platform;

import me.vexmc.asynctnt.common.platform.Capabilities;
import me.vexmc.asynctnt.common.scheduling.Scheduling;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Chooses the scheduling backend for this server.
 *
 * <p>The Folia implementation is referenced by name only: its class is
 * compiled against a newer API and must never be loaded on servers that
 * lack the region scheduler types.</p>
 */
public final class SchedulingFactory {

    private static final String FOLIA_IMPL = "me.vexmc.asynctnt.compat.folia.FoliaScheduling";

    private SchedulingFactory() {}

    public static @NotNull Scheduling create(@NotNull Plugin plugin, @NotNull Capabilities capabilities) {
        if (!capabilities.folia()) {
            return new BukkitScheduling(plugin);
        }
        try {
            return (Scheduling) Class.forName(FOLIA_IMPL)
                    .getDeclaredConstructor(Plugin.class)
                    .newInstance(plugin);
        } catch (ReflectiveOperationException failure) {
            // On Folia the Bukkit scheduler would hard-fail anyway; surface the real problem.
            throw new IllegalStateException(
                    "Folia detected but the Folia scheduling backend failed to load", failure);
        }
    }
}
