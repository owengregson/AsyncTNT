package me.vexmc.asynctnt.common.platform;

import org.bukkit.attribute.Attribute;
import org.jetbrains.annotations.NotNull;

/**
 * Feature detection, computed once at boot.
 *
 * <p>Every optional code path in AsyncTNT keys off a capability rather than a
 * version comparison: a class either exists on this server or it does not.
 * Version numbers are reserved for the boot report and protocol decisions.</p>
 */
public record Capabilities(
        boolean folia,
        boolean modernSchedulers,
        boolean registryAttributes) {

    public static @NotNull Capabilities detect() {
        boolean folia = classPresent("io.papermc.paper.threadedregions.RegionizedServer");
        boolean modernSchedulers =
                folia || classPresent("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
        boolean registryAttributes = !Attribute.class.isEnum();
        return new Capabilities(folia, modernSchedulers, registryAttributes);
    }

    public @NotNull String describe() {
        return "folia=" + folia
                + " modernSchedulers=" + modernSchedulers
                + " registryAttributes=" + registryAttributes;
    }

    private static boolean classPresent(@NotNull String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}
