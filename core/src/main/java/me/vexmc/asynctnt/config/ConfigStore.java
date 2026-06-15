package me.vexmc.asynctnt.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import me.vexmc.asynctnt.common.version.ForkFlags;

/**
 * Owns the live {@link AsyncTntConfig} snapshot and the only path that swaps it.
 *
 * <p>A reload re-reads {@code config.yml} from disk, parses every key into one
 * fresh immutable {@link AsyncTntConfig}, and publishes it with a single
 * reference store: any code path — the engine on its worker threads, the
 * command handlers, the API service — observes either the old configuration or
 * the new one in full, never a torn mix mid-tick. The bundled {@code config.yml}
 * is exhaustively commented and is the canonical documentation.</p>
 *
 * <p>Parsing never throws. A malformed value warns once through the plugin
 * logger ({@code "config — <key>: <reason>"}) and falls back to the documented
 * default, so a single bad key can never poison the swap or leave the engine
 * without a configuration.</p>
 */
public final class ConfigStore {

    private final JavaPlugin plugin;
    private final AtomicReference<AsyncTntConfig> snapshot =
            new AtomicReference<>(AsyncTntConfig.DEFAULTS);

    /**
     * Extracts the bundled {@code config.yml} when absent, then reads it into the
     * first snapshot, so the store is usable the instant it is constructed.
     */
    public ConfigStore(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** The current configuration. Always the full, internally-consistent snapshot. */
    public @NotNull AsyncTntConfig get() {
        return snapshot.get();
    }

    /**
     * Re-reads {@code config.yml} from disk and atomically publishes a fresh
     * snapshot. The engine reacts to the new {@code engine.enabled} /
     * disabled-worlds on its next observation of {@link #get()}.
     */
    public @NotNull AsyncTntConfig reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        AsyncTntConfig parsed = parse(plugin.getConfig());
        snapshot.set(parsed);
        return parsed;
    }

    /**
     * Adds or removes {@code world} from {@code engine.disabled-worlds}, persists
     * {@code config.yml}, and reloads. Returns the freshly published snapshot so
     * the caller can report the new state without a second read.
     *
     * @param world the world name as Bukkit reports it
     * @param on    {@code true} to let the engine own bodies in the world,
     *              {@code false} to pin that world to vanilla ticking
     */
    public @NotNull AsyncTntConfig setWorldEnabled(@NotNull String world, boolean on) {
        // The on-disk list is the source of truth: read it, mutate it, write it
        // back, then reload so the snapshot matches the file exactly.
        Set<String> disabled = new LinkedHashSet<>(plugin.getConfig().getStringList("engine.disabled-worlds"));
        if (on) {
            disabled.remove(world);
        } else {
            disabled.add(world);
        }
        plugin.getConfig().set("engine.disabled-worlds", List.copyOf(disabled));
        plugin.saveConfig();
        return reload();
    }

    /**
     * Flips the engine master switch. Persisting {@code engine.enabled=false}
     * and reloading is the kill switch — every body the engine owns returns to
     * vanilla ticking once it observes the disabled snapshot.
     */
    public @NotNull AsyncTntConfig setEngineEnabled(boolean enabled) {
        plugin.getConfig().set("engine.enabled", enabled);
        plugin.saveConfig();
        return reload();
    }

    /* ------------------------------------------------------------------ */
    /*  Parsing — warn per bad key, never throw, never fail the swap.      */
    /* ------------------------------------------------------------------ */

    private @NotNull AsyncTntConfig parse(@NotNull FileConfiguration config) {
        boolean enabled = flag(config, "engine.enabled", AsyncTntConfig.DEFAULTS.enabled());
        Set<String> disabledWorlds = worlds(config, "engine.disabled-worlds");
        int workerThreads = intAtLeast(config, "engine.worker-threads",
                AsyncTntConfig.DEFAULTS.workerThreads(), 1);
        ForkFlags forkFlags = new ForkFlags(
                flag(config, "fork-fixes.zero-spawn-kick", false),
                flag(config, "fork-fixes.fixed-fuse-80", false),
                flag(config, "fork-fixes.deterministic-redstone", false),
                flag(config, "fork-fixes.east-west-collision-fix", false));
        return new AsyncTntConfig(enabled, disabledWorlds, workerThreads, forkFlags);
    }

    private boolean flag(@NotNull FileConfiguration config, @NotNull String key, boolean fallback) {
        if (!config.isSet(key)) {
            return fallback;
        }
        if (!config.isBoolean(key)) {
            warn(key, "expected true/false, found '" + config.get(key) + "'");
            return fallback;
        }
        return config.getBoolean(key);
    }

    private int intAtLeast(@NotNull FileConfiguration config, @NotNull String key, int fallback, int minimum) {
        if (!config.isSet(key)) {
            return fallback;
        }
        if (!config.isInt(key)) {
            warn(key, "expected a whole number, found '" + config.get(key) + "'");
            return fallback;
        }
        int value = config.getInt(key);
        if (value < minimum) {
            warn(key, "must be at least " + minimum + ", found " + value);
            return fallback;
        }
        return value;
    }

    private @NotNull Set<String> worlds(@NotNull FileConfiguration config, @NotNull String key) {
        if (!config.isSet(key)) {
            return Set.of();
        }
        if (!config.isList(key)) {
            warn(key, "expected a list of world names, found '" + config.get(key) + "'");
            return Set.of();
        }
        // Copied into an immutable set so the published snapshot cannot be
        // mutated through the file; membership is all enabledIn() needs.
        return Set.copyOf(config.getStringList(key));
    }

    private void warn(@NotNull String key, @NotNull String reason) {
        plugin.getLogger().warning("config — " + key + ": " + reason + " (using default)");
    }
}
