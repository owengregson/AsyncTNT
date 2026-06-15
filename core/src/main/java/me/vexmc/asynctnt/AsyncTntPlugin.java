package me.vexmc.asynctnt;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * AsyncTNT entry point. Scaffold lifecycle only — capability detection,
 * scheduling selection, the engine, takeover, config, and commands are wired in
 * the core task of the implementation plan.
 */
public final class AsyncTntPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("AsyncTNT " + getDescription().getVersion() + " enabled (scaffold).");
    }

    @Override
    public void onDisable() {
        getLogger().info("AsyncTNT disabled.");
    }
}
