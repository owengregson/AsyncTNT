package me.vexmc.asynctnt.tester.suite;

import java.util.List;
import me.vexmc.asynctnt.api.AsyncTntService;
import me.vexmc.asynctnt.common.platform.Capabilities;
import me.vexmc.asynctnt.tester.AsyncTntTesterPlugin;
import me.vexmc.asynctnt.tester.harness.TestCase;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * The plugin, its public service, and the scheduling backend came up
 * correctly on this exact server version — the cheap invariants that must
 * hold before any parity scenario is worth running.
 */
public final class BootSuite {

    private static final String ASYNCTNT_PLUGIN = "AsyncTNT";

    private BootSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull AsyncTntTesterPlugin tester, @NotNull AsyncTntService service) {
        return List.of(
                new TestCase("boot: AsyncTNT plugin present and enabled", context -> {
                    Plugin asyncTnt = Bukkit.getPluginManager().getPlugin(ASYNCTNT_PLUGIN);
                    context.expect(asyncTnt != null, "AsyncTNT plugin is not installed");
                    context.expect(asyncTnt.isEnabled(),
                            "AsyncTNT plugin is registered but not enabled");
                }),
                new TestCase("boot: service registered and engine-active agrees with config", context -> {
                    AsyncTntService registered = Bukkit.getServicesManager()
                            .load(AsyncTntService.class);
                    context.expect(registered != null,
                            "AsyncTntService is not registered with the Bukkit services manager");
                    context.expect(registered == service,
                            "the registered service differs from the one handed to the suite");

                    Plugin asyncTnt = Bukkit.getPluginManager().getPlugin(ASYNCTNT_PLUGIN);
                    context.expect(asyncTnt != null, "AsyncTNT plugin is not installed");
                    // engine.enabled is the documented master switch (default
                    // true); isEngineActive() must reflect it. A configured-off
                    // engine that still reports active — or vice versa — means
                    // the kill switch and the public status disagree.
                    boolean configuredOn = asyncTnt.getConfig().getBoolean("engine.enabled", true);
                    context.note("engine.enabled=" + configuredOn
                            + ", service.isEngineActive()=" + service.isEngineActive());
                    context.expect(service.isEngineActive() == configuredOn,
                            "isEngineActive() (" + service.isEngineActive()
                                    + ") disagrees with config engine.enabled (" + configuredOn + ")");
                }),
                new TestCase("boot: scheduling backend matches the detected platform", context -> {
                    Capabilities capabilities = Capabilities.detect();
                    String describe = service.describe();
                    context.note("capabilities.folia()=" + capabilities.folia()
                            + ", service.describe()=\"" + describe + "\"");
                    // The single backend invariant: a Folia server must select
                    // the folia backend and nothing else may, in lockstep with
                    // the same capability detection the engine itself keys off.
                    context.expect(capabilities.folia() == "folia".equals(describe),
                            "scheduling backend (\"" + describe + "\") disagrees with"
                                    + " Capabilities.detect().folia()=" + capabilities.folia());
                    if (capabilities.folia()) {
                        context.expect(capabilities.modernSchedulers(),
                                "folia without modern schedulers is impossible");
                    } else {
                        context.expect("bukkit".equals(describe),
                                "a non-folia server must describe the bukkit backend, not \""
                                        + describe + "\"");
                    }
                }));
    }
}
