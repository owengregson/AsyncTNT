package me.vexmc.asynctnt.tester;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * AsyncTNTTester entry point. Scaffold lifecycle only — the test harness,
 * boot suite, and engine-on-vs-engine-off parity oracle are wired in the
 * tester task of the implementation plan.
 */
public final class AsyncTntTesterPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("AsyncTNTTester " + getDescription().getVersion() + " enabled (scaffold).");
    }
}
