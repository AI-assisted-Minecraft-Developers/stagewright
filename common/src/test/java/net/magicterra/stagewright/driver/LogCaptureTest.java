package net.magicterra.stagewright.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.magicterra.stagewright.test.methods.LogsContracts;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.Test;

/** Through log4j itself, as a game's loggers reach it. */
class LogCaptureTest {

    private static final Logger MOD = LogManager.getLogger("com.example.mymod.Ledger");

    private static final Logger OTHER = LogManager.getLogger("net.other.Thing");

    @Test
    void theWarningsAndErrorsAfterAMarkAreListedByLevelAndLogger() {
        LogCapture.install();
        MOD.warn("before the mark");
        long mark = LogCapture.mark();
        MOD.info("not a warning");
        MOD.warn("a balance of {} is negative", -3);
        MOD.error("could not save", new IllegalStateException("disk full", new java.io.IOException("quota")));
        OTHER.warn("someone else's");

        List<LogsContracts.Line> warnings = LogCapture.since(mark, LogsContracts.Level.WARN, Optional.of("com.example"))
                .lines();
        assertEquals(2, warnings.size(), warnings.toString());
        assertEquals("a balance of -3 is negative", warnings.get(0).message(), "formatted");
        assertEquals(LogsContracts.Level.ERROR, warnings.get(1).level());
        assertEquals(
                Optional.of("java.lang.IllegalStateException: disk full ← caused by java.io.IOException: quota"),
                warnings.get(1).thrown());

        LogsContracts.Lines errors = LogCapture.since(mark, LogsContracts.Level.ERROR, Optional.empty());
        assertEquals(
                List.of("could not save"),
                errors.lines().stream().map(LogsContracts.Line::message).toList());
        assertEquals(
                3,
                LogCapture.since(mark, LogsContracts.Level.WARN, Optional.empty())
                        .lines()
                        .size(),
                "all loggers");
        assertEquals(0, errors.dropped());
    }

    @Test
    void aLoggerKeepingItsEventsFromTheRootAndALaterConfigurationAreStillCaptured() {
        LoggerContext context = new LoggerContext("log-capture-test");
        context.start(new DefaultConfiguration());
        try {
            context.getConfiguration().addLogger("quiet.mod", new LoggerConfig("quiet.mod", Level.ALL, false));
            context.updateLoggers();
            LogCapture.capture(context);
            long mark = LogCapture.mark();
            context.getLogger("quiet.mod.Ledger").error("kept from the root");
            // The default configuration's root takes ERROR and worse only.
            context.getLogger("loud.mod").error("passed up to the root");
            assertEquals(
                    List.of("kept from the root"),
                    messages(mark, "quiet.mod"),
                    "once, though its logger does not pass it up");
            assertEquals(List.of("passed up to the root"), messages(mark, "loud.mod"));

            // As a mod loading its own log4j2.xml does.
            context.setConfiguration(new DefaultConfiguration());
            long after = LogCapture.mark();
            context.getLogger("loud.mod").error("after the reconfiguration");
            assertEquals(List.of("after the reconfiguration"), messages(after, "loud.mod"));
            // Started, not reached only because log4j forgives a stopped appender that ignores exceptions.
            assertTrue(context.getConfiguration().getRootLogger().getAppenders().values().stream()
                    .filter(a -> a.getName().equals("StageWrightLogCapture"))
                    .allMatch(org.apache.logging.log4j.core.Appender::isStarted));
            assertEquals(
                    1,
                    context.getConfiguration().getRootLogger().getAppenders().values().stream()
                            .filter(a -> a.getName().equals("StageWrightLogCapture"))
                            .count());
        } finally {
            context.stop();
        }
    }

    private static List<String> messages(long mark, String logger) {
        return LogCapture.since(mark, LogsContracts.Level.WARN, Optional.of(logger)).lines().stream()
                .map(LogsContracts.Line::message)
                .toList();
    }

    @Test
    void linesNoLongerKeptAreCounted() {
        LogCapture.install();
        long mark = LogCapture.mark();
        for (int i = 0; i < LogCapture.KEPT + 10; i++) {
            OTHER.warn("flood {}", i);
        }
        LogsContracts.Lines lines = LogCapture.since(mark, LogsContracts.Level.WARN, Optional.empty());
        assertEquals(10, lines.dropped(), "the oldest went first");
        assertEquals(LogCapture.LISTED, lines.lines().size());
        assertTrue(lines.omitted() > 0, "the rest left out for the list's length");
    }
}
