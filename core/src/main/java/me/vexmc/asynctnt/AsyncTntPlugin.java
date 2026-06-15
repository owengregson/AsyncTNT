package me.vexmc.asynctnt;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import me.vexmc.asynctnt.api.AsyncTntService;
import me.vexmc.asynctnt.command.AsyncTntCommands;
import me.vexmc.asynctnt.common.platform.Capabilities;
import me.vexmc.asynctnt.common.platform.ServerEnvironment;
import me.vexmc.asynctnt.common.scheduling.Scheduling;
import me.vexmc.asynctnt.common.version.PhysicsProfile;
import me.vexmc.asynctnt.config.ConfigStore;
import me.vexmc.asynctnt.engine.AsyncTntEngine;
import me.vexmc.asynctnt.engine.takeover.SpawnInterceptor;
import me.vexmc.asynctnt.nms.BukkitNmsAccess;
import me.vexmc.asynctnt.nms.NmsAccess;
import me.vexmc.asynctnt.platform.SchedulingFactory;

/**
 * AsyncTNT entry point. Detects the server's capabilities/version once, selects
 * the scheduling backend (Folia region schedulers or the Bukkit scheduler),
 * builds the engine, and registers the spawn interceptor, command, and service.
 */
public final class AsyncTntPlugin extends JavaPlugin {

    private AsyncTntEngine engine;

    @Override
    public void onEnable() {
        Capabilities capabilities = Capabilities.detect();
        ServerEnvironment environment = ServerEnvironment.parse(getServer().getBukkitVersion());
        Scheduling scheduling = SchedulingFactory.create(this, capabilities);
        PhysicsProfile profile =
                PhysicsProfile.forVersion(environment.major(), environment.minor(), environment.patch());

        ConfigStore config = new ConfigStore(this);
        NmsAccess nms = new BukkitNmsAccess(this, capabilities.folia());

        this.engine = new AsyncTntEngine(this, scheduling, nms, config::get, profile);
        this.engine.start();

        getServer().getServicesManager().register(
                AsyncTntService.class, new AsyncTntManager(this.engine), this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new SpawnInterceptor(this.engine), this);

        PluginCommand command = getCommand("asynctnt");
        if (command != null) {
            AsyncTntCommands commands = new AsyncTntCommands(this.engine, config, this);
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }

        getLogger().info("AsyncTNT " + getDescription().getVersion() + " enabled — server "
                + environment.describe() + ", scheduling=" + scheduling.describe()
                + ", " + capabilities.describe() + ", engine=" + (this.engine.isActive() ? "on" : "off"));
    }

    @Override
    public void onDisable() {
        if (this.engine != null) {
            this.engine.stop();
            this.engine = null;
        }
        getLogger().info("AsyncTNT disabled.");
    }
}
