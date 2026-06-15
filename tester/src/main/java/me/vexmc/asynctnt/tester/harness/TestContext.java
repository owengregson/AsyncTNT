package me.vexmc.asynctnt.tester.harness;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import me.vexmc.asynctnt.common.scheduling.Scheduling;
import me.vexmc.asynctnt.common.scheduling.TaskHandle;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * Bridges the off-thread test driver into the live server: synchronous
 * hops onto the global tick, tick-count waits, and assertions.
 */
public final class TestContext {

    // Generous: a concurrent matrix can momentarily starve a healthy server
    // (the host, not the suite, is the bottleneck); genuinely dead servers
    // are caught by the launcher's hard per-server watchdog.
    private static final long SYNC_TIMEOUT_SECONDS = 90;
    private static final long TICK_WAIT_TIMEOUT_SECONDS = 120;

    private final Scheduling scheduling;
    private final Logger logger;

    TestContext(@NotNull Scheduling scheduling, @NotNull Logger logger) {
        this.scheduling = scheduling;
        this.logger = logger;
    }

    public <T> T sync(@NotNull Callable<T> work) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        scheduling.runGlobal(() -> {
            try {
                future.complete(work.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        try {
            return future.get(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("Synchronous work did not complete within "
                    + SYNC_TIMEOUT_SECONDS + "s — is the server tick stalled?");
        }
    }

    public void syncRun(@NotNull ThrowingRunnable work) throws Exception {
        sync(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Samples {@code sampler} exactly once per server tick for {@code ticks}
     * ticks and returns the readings in order. Unlike a {@code sync}+{@code
     * awaitTicks} loop — which round-trips to the off-thread driver each
     * iteration and so cannot pin a reading to a specific physics tick — this
     * runs entirely in one repeating main-thread task, giving one deterministic
     * reading per tick at a fixed phase (after the engine's per-entity driver,
     * which was registered earlier, has applied that tick's state). That
     * determinism is what makes a tick-by-tick trajectory comparison meaningful.
     */
    public <T> java.util.List<T> recordPerTick(@NotNull Callable<T> sampler, int ticks) throws Exception {
        java.util.List<T> samples = new java.util.ArrayList<>();
        CompletableFuture<java.util.List<T>> done = new CompletableFuture<>();
        TaskHandle[] handle = new TaskHandle[1];
        handle[0] = scheduling.repeatGlobal(1L, 1L, () -> {
            if (done.isDone()) {
                return;
            }
            try {
                samples.add(sampler.call());
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
                return;
            }
            if (samples.size() >= ticks) {
                done.complete(samples);
            }
        });
        try {
            return done.get(TICK_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("recordPerTick did not gather " + ticks + " samples — server tick stalled?");
        } finally {
            if (handle[0] != null) {
                handle[0].cancel();
            }
        }
    }

    /**
     * Runs {@code work} once on the region thread that owns {@code at} and blocks
     * until it finishes. This is {@link #sync} for Folia: on Folia world/entity
     * work must run on the owning region thread, which the global {@link #sync}
     * (global region) may not touch. On Paper both collapse to the main thread.
     */
    public void runAtRegion(@NotNull Location at, @NotNull ThrowingRunnable work) throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduling.runAt(at, () -> {
            try {
                work.run();
                future.complete(null);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        try {
            future.get(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("Region work at " + at + " did not complete within "
                    + SYNC_TIMEOUT_SECONDS + "s — region tick stalled?");
        }
    }

    /**
     * Like {@link #recordPerTick} but sampled on the region thread that owns
     * {@code at} (Folia-correct: it can read entities there). Samples once per
     * tick for {@code ticks} ticks at a fixed phase after that region's entity
     * schedulers — including the engine's per-entity driver — have run.
     */
    public <T> java.util.List<T> recordPerTickAt(@NotNull Location at, @NotNull Callable<T> sampler, int ticks)
            throws Exception {
        java.util.List<T> samples = new java.util.ArrayList<>();
        CompletableFuture<java.util.List<T>> done = new CompletableFuture<>();
        TaskHandle[] handle = new TaskHandle[1];
        handle[0] = scheduling.repeatAt(at, 1L, 1L, () -> {
            if (done.isDone()) {
                return;
            }
            try {
                samples.add(sampler.call());
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
                return;
            }
            if (samples.size() >= ticks) {
                done.complete(samples);
            }
        });
        try {
            return done.get(TICK_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("recordPerTickAt did not gather " + ticks + " samples — region tick stalled?");
        } finally {
            if (handle[0] != null) {
                handle[0].cancel();
            }
        }
    }

    public void awaitTicks(int ticks) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(ticks);
        TaskHandle[] handle = new TaskHandle[1];
        handle[0] = scheduling.repeatGlobal(1L, 1L, () -> {
            latch.countDown();
            if (latch.getCount() == 0 && handle[0] != null) {
                handle[0].cancel();
            }
        });
        if (!latch.await(TICK_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            handle[0].cancel();
            throw new AssertionError("Waited " + TICK_WAIT_TIMEOUT_SECONDS + "s for "
                    + ticks + " ticks — server tick stalled?");
        }
    }

    /**
     * Ticks until the condition holds — condition-based waiting where a
     * fixed sleep would race the matrix: under nine concurrent servers,
     * event dispatch can lag a fixed 3-tick window and a last-write-wins
     * captor then reads stale spawn-time state instead of the knock.
     */
    public void awaitUntil(@NotNull BooleanSupplier condition, int maxTicks, @NotNull String what)
            throws InterruptedException {
        for (int tick = 0; tick < maxTicks && !condition.getAsBoolean(); tick++) {
            awaitTicks(1);
        }
        expect(condition.getAsBoolean(),
                "timed out after " + maxTicks + " ticks waiting for " + what);
    }

    public void expect(boolean condition, @NotNull String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public void expectNear(double expected, double actual, double epsilon, @NotNull String what) {
        if (Double.isNaN(actual) || Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(what + ": expected " + expected + " ± " + epsilon
                    + " but was " + actual);
        }
    }

    public void note(@NotNull String message) {
        logger.info("[test] " + message);
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
