package me.vexmc.asynctnt.tester.harness;

import org.jetbrains.annotations.NotNull;

/** One named integration test. */
public record TestCase(@NotNull String name, @NotNull Body body) {

    @FunctionalInterface
    public interface Body {
        void run(@NotNull TestContext context) throws Exception;
    }
}
