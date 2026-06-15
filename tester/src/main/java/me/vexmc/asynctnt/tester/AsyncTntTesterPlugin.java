package me.vexmc.asynctnt.tester;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.asynctnt.api.AsyncTntService;
import me.vexmc.asynctnt.common.platform.Capabilities;
import me.vexmc.asynctnt.common.scheduling.Scheduling;
import me.vexmc.asynctnt.common.scheduling.TaskHandle;
import me.vexmc.asynctnt.platform.SchedulingFactory;
import me.vexmc.asynctnt.tester.harness.TestCase;
import me.vexmc.asynctnt.tester.harness.TestHarness;
import me.vexmc.asynctnt.tester.harness.TestResultWriter;
import me.vexmc.asynctnt.tester.suite.BootSuite;
import me.vexmc.asynctnt.tester.suite.ParitySuite;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Boots inside a real server next to AsyncTNT, waits for the world to settle,
 * runs the suite, writes PASS/FAIL for the Gradle build, and shuts the server
 * down. On Folia only the boot suite and a single parity smoke run — the
 * full cannon-parity scenarios drive cross-region world state from one driver
 * context, which Folia does not permit.
 */
public final class AsyncTntTesterPlugin extends JavaPlugin {

    private static final String ASYNCTNT_PLUGIN = "AsyncTNT";
    private static final long SETTLE_TICKS = 40L;
    // A large period for the self-cancelling starter: it only ever runs once.
    private static final long STARTER_PERIOD_TICKS = 72_000L;

    @Override
    public void onEnable() {
        try {
            Plugin asyncTnt = getServer().getPluginManager().getPlugin(ASYNCTNT_PLUGIN);
            if (asyncTnt == null) {
                fail("AsyncTNT is not installed — cannot test");
                return;
            }

            AsyncTntService service = getServer().getServicesManager().load(AsyncTntService.class);
            if (service == null) {
                fail("AsyncTntService is not registered with the Bukkit services manager");
                return;
            }

            Scheduling scheduling = SchedulingFactory.create(this, Capabilities.detect());

            // Settle the world before staging anything: chunk generation and
            // the host's boot churn would otherwise race the first scenario.
            // A self-cancelling repeat fires the assembly once on the global
            // tick, then cancels — the harness owns all timing from there.
            TaskHandle[] starter = new TaskHandle[1];
            starter[0] = scheduling.repeatGlobal(SETTLE_TICKS, STARTER_PERIOD_TICKS, () -> {
                starter[0].cancel();
                runSuite(service, scheduling);
            });
        } catch (Throwable enableFailure) {
            getLogger().severe("Tester failed to enable: " + enableFailure);
            fail("tester enable-time exception: " + enableFailure);
        }
    }

    private void runSuite(AsyncTntService service, Scheduling scheduling) {
        try {
            List<TestCase> suite = new ArrayList<>(BootSuite.tests(this, service));
            if (Capabilities.detect().folia()) {
                getLogger().info("Folia detected — running the boot suite and a single parity smoke.");
                suite.addAll(ParitySuite.smoke(this, service));
            } else {
                suite.addAll(ParitySuite.tests(this, service));
            }
            new TestHarness(this, scheduling).run(suite);
        } catch (Throwable assemblyFailure) {
            getLogger().severe("Suite assembly failed: " + assemblyFailure);
            fail("suite assembly exception: " + assemblyFailure);
        }
    }

    /** Writes FAIL for the Gradle build and shuts the server down. */
    private void fail(String reason) {
        getLogger().severe(reason);
        TestResultWriter.write(this, false, List.of(reason));
        getServer().shutdown();
    }
}
