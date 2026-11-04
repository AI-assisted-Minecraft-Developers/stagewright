package net.magicterra.stagewright.test;

import java.util.List;
import java.util.Optional;
import net.magicterra.stagewright.test.methods.LogsContracts;
import net.magicterra.stagewright.test.methods.TestQueriesBlockingApi;
import net.magicterra.worlddriver.api.registry.ApiRegistry;

/**
 * The warnings and errors a JVM of the game logged, through {@code mc.test.logs}: {@code Logs.of(game.registry())} for
 * the server's, {@code Logs.of(client.registry())} for the client's, which on an integrated server is the same JVM.
 *
 * <pre>{@code
 * Logs logs = Logs.of(client.registry());
 * long mark = logs.mark();
 * // ... what must not warn
 * assertEquals(List.of(), logs.since(mark, LogsContracts.Level.WARN, "com.example.mymod"));
 * }</pre>
 *
 * <p>Every logger that reaches log4j's root is read: Minecraft's, the loader's, and a mod's through SLF4J or log4j.
 */
public final class Logs {

    private final TestQueriesBlockingApi api;

    private Logs(ApiRegistry registry) {
        this.api = TestQueriesBlockingApi.of(registry);
    }

    /** The log of the JVM that answers {@code registry}. */
    public static Logs of(ApiRegistry registry) {
        return new Logs(registry);
    }

    /** Where the log stands now; the lines logged after it are the ones {@link #since} lists. */
    public long mark() {
        return ((LogsContracts.Marked) api.logs(new LogsContracts.Mark())).at();
    }

    /**
     * The lines logged after {@code mark} at {@code atLeast} or worse, oldest first, by loggers whose name starts with
     * {@code logger}; an empty {@code logger} for every one.
     *
     * @throws IllegalStateException when lines that may match are no longer kept, or more matched than one answer
     *     holds, so an empty list always means none came
     */
    public List<LogsContracts.Line> since(long mark, LogsContracts.Level atLeast, String logger) {
        LogsContracts.Lines lines = (LogsContracts.Lines) api.logs(
                new LogsContracts.Since(mark, atLeast, logger.isEmpty() ? Optional.empty() : Optional.of(logger)));
        if (lines.dropped() > 0 || lines.omitted() > 0) {
            throw new IllegalStateException("the log after mark " + mark + " is not complete: " + lines.dropped()
                    + " lines no longer kept, " + lines.omitted() + " left out; take a mark closer to what is tested");
        }
        return lines.lines();
    }
}
