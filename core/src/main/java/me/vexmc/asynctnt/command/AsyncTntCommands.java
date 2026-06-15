package me.vexmc.asynctnt.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import me.vexmc.asynctnt.config.AsyncTntConfig;
import me.vexmc.asynctnt.config.ConfigStore;
import me.vexmc.asynctnt.engine.EngineHandle;

/**
 * The whole {@code /asynctnt} ({@code /atnt}) command tree as a plain
 * {@link TabExecutor}. Every subcommand is op-only, gated once on
 * {@code asynctnt.command.use} (declared in plugin.yml), and replies with
 * §-coloured chat. Nothing here throws at the sender — a bad argument produces
 * a usage line, never a stack trace.
 *
 * <p>State changes go through the {@link ConfigStore}: it owns the only path
 * that mutates {@code config.yml} and re-publishes the snapshot, and the engine
 * reacts to the new snapshot on reload. The {@link EngineHandle} is read-only
 * here — used to report live status (active, backend, owned count).</p>
 *
 * <p>This class is intentionally <em>not</em> self-registering; the plugin
 * entry point wires it onto the {@code asynctnt} command.</p>
 */
public final class AsyncTntCommands implements TabExecutor {

    private static final String PERMISSION_USE = "asynctnt.command.use";

    private static final String PREFIX = "§8[§cAsync§fTNT§8]§r ";
    private static final String OK = "§a";
    private static final String INFO = "§7";
    private static final String VALUE = "§f";
    private static final String BAD = "§c";

    private final EngineHandle engine;
    private final ConfigStore config;
    private final JavaPlugin plugin;

    public AsyncTntCommands(
            @NotNull EngineHandle engine,
            @NotNull ConfigStore config,
            @NotNull JavaPlugin plugin) {
        this.engine = engine;
        this.config = config;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION_USE)) {
            reply(sender, BAD + "You do not have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            case "world" -> world(sender, args);
            case "killswitch" -> killSwitch(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void status(@NotNull CommandSender sender) {
        AsyncTntConfig snapshot = config.get();
        boolean active = engine.isActive();
        reply(sender, "§8— §cAsync§fTNT §8—");
        reply(sender, INFO + "engine: "
                + (active ? OK + "active" : BAD + "inactive")
                + INFO + "  (config enabled: "
                + (snapshot.enabled() ? OK + "true" : BAD + "false") + INFO + ")");
        reply(sender, INFO + "scheduling backend: " + VALUE + engine.schedulingBackend());
        reply(sender, INFO + "owned bodies: " + VALUE + engine.ownedCount());
        reply(sender, INFO + "worker threads: " + VALUE + snapshot.workerThreads());
        reply(sender, INFO + "enabled worlds: " + VALUE + describeEnabledWorlds(snapshot));
        if (!snapshot.disabledWorlds().isEmpty()) {
            reply(sender, INFO + "disabled worlds: " + VALUE
                    + String.join(", ", snapshot.disabledWorlds()));
        }
    }

    private void reload(@NotNull CommandSender sender) {
        try {
            config.reload();
            reply(sender, OK + "Configuration reloaded.");
        } catch (RuntimeException failure) {
            // Parsing itself never throws (warn-and-fallback); this guards the
            // file IO path so the sender always gets a clean message.
            reply(sender, BAD + "Reload failed: "
                    + (failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage()));
        }
    }

    private void world(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            reply(sender, INFO + "Usage: " + VALUE + "/asynctnt world <name> <on|off>");
            return;
        }
        String name = args[1];
        Boolean on = parseOnOff(args[2]);
        if (on == null) {
            reply(sender, BAD + "Expected 'on' or 'off', got '" + args[2] + "'.");
            return;
        }
        AsyncTntConfig snapshot = config.setWorldEnabled(name, on);
        plugin.getLogger().info(sender.getName() + " set engine "
                + (on ? "ON" : "OFF") + " for world '" + name + "'.");
        if (on) {
            reply(sender, OK + "Engine enabled in world '" + name + "'.");
        } else {
            reply(sender, OK + "Engine disabled in world '" + name
                    + "' — its TNT now ticks vanilla.");
        }
        if (Bukkit.getWorld(name) == null) {
            reply(sender, INFO + "(note: no loaded world is named '" + name
                    + "' — the setting is stored and applies if it loads.)");
        }
        reply(sender, INFO + "enabled worlds: " + VALUE + describeEnabledWorlds(snapshot));
    }

    private void killSwitch(@NotNull CommandSender sender) {
        // The kill switch is just engine.enabled=false persisted and reloaded:
        // the engine observes the disabled snapshot and releases every body it
        // owns back to vanilla ticking. Reversible with `world`/`reload` after
        // re-enabling in config, kept deliberately to one reachable mechanism.
        config.setEngineEnabled(false);
        plugin.getLogger().warning("Kill switch engaged by " + sender.getName()
                + " — engine disabled, all bodies released to vanilla ticking.");
        reply(sender, OK + "Kill switch engaged — engine disabled; "
                + "all owned bodies return to vanilla ticking.");
        reply(sender, INFO + "Set " + VALUE + "engine.enabled: true"
                + INFO + " in config.yml and " + VALUE + "/asynctnt reload"
                + INFO + " to turn it back on.");
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION_USE)) {
            return List.of();
        }
        if (args.length == 1) {
            return prefixed(args[0], List.of("status", "reload", "world", "killswitch"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("world")) {
            List<String> worlds = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                worlds.add(world.getName());
            }
            return prefixed(args[1], worlds);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("world")) {
            return prefixed(args[2], List.of("on", "off"));
        }
        return List.of();
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private @NotNull String describeEnabledWorlds(@NotNull AsyncTntConfig snapshot) {
        if (!snapshot.enabled()) {
            return "none (engine disabled)";
        }
        List<String> names = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (snapshot.enabledIn(world.getName())) {
                names.add(world.getName());
            }
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private void usage(@NotNull CommandSender sender) {
        reply(sender, "§8— §cAsync§fTNT §8—");
        reply(sender, INFO + "/asynctnt status " + INFO + "— engine state, backend, owned count");
        reply(sender, INFO + "/asynctnt reload " + INFO + "— re-read config.yml atomically");
        reply(sender, INFO + "/asynctnt world <name> <on|off> " + INFO + "— per-world engine toggle");
        reply(sender, INFO + "/asynctnt killswitch " + INFO + "— disable the engine entirely");
    }

    private static @Nullable Boolean parseOnOff(@NotNull String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable", "enabled", "yes" -> Boolean.TRUE;
            case "off", "false", "disable", "disabled", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static @NotNull List<String> prefixed(@NotNull String partial, @NotNull List<String> options) {
        String lower = partial.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }

    private void reply(@NotNull CommandSender sender, @NotNull String message) {
        sender.sendMessage(PREFIX + message);
    }
}
