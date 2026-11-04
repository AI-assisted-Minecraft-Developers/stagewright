package net.magicterra.stagewright.driver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import net.magicterra.stagewright.test.methods.LogsContracts;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

/**
 * The warnings and errors this JVM logs, kept for {@code mc.test.logs}: an appender on log4j's root logger, which both
 * loaders log through, holding the most recent {@value #KEPT}. Installed once, as the mod initializes, so a test reads
 * what the game logged from its start.
 */
public final class LogCapture {

    /** Lines kept; the oldest go first. */
    static final int KEPT = 4096;

    /** The most lines one answer lists. */
    static final int LISTED = 1024;

    private static final int TEXT = 4096;

    private static final int NAME = 256;

    private static final Deque<LogsContracts.Line> LINES = new ArrayDeque<>();

    /** The position of the last line logged; a mark is this value. Guarded by {@link #LINES}. */
    private static long last;

    private static volatile boolean installed;

    private LogCapture() {}

    /** Start keeping lines, once per JVM; a second call, as from the other side of an integrated game, does nothing. */
    // The context is the JVM's own logging, open for as long as the game runs; closing it would end every log.
    @SuppressWarnings("PMD.CloseResource")
    public static synchronized void install() {
        if (installed || !(LogManager.getContext(false) instanceof LoggerContext context)) {
            return;
        }
        installed = true;
        Configuration config = context.getConfiguration();
        Appender appender = new Appender();
        appender.start();
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, Level.WARN, null);
        context.updateLoggers();
    }

    /** Where the log stands: lines logged from now on come after it. */
    public static long mark() {
        synchronized (LINES) {
            return last;
        }
    }

    /** The lines after {@code after} at {@code atLeast} or worse, by a logger whose name starts with {@code logger}. */
    public static LogsContracts.Lines since(long after, LogsContracts.Level atLeast, Optional<String> logger) {
        List<LogsContracts.Line> matched = new ArrayList<>();
        int omitted = 0;
        long dropped;
        synchronized (LINES) {
            LogsContracts.Line oldest = LINES.peekFirst();
            long firstKept = oldest == null ? last + 1 : oldest.at();
            dropped = Math.max(0, firstKept - 1 - after);
            for (LogsContracts.Line line : LINES) {
                if (line.at() <= after
                        || line.level().compareTo(atLeast) < 0
                        || logger.isPresent() && !line.logger().startsWith(logger.get())) {
                    continue;
                }
                if (matched.size() < LISTED) {
                    matched.add(line);
                } else {
                    omitted++;
                }
            }
        }
        return new LogsContracts.Lines(matched, omitted, dropped);
    }

    /** Keep {@code event} as a line. */
    static void keep(LogEvent event) {
        LogsContracts.Level level =
                event.getLevel().isMoreSpecificThan(Level.ERROR) ? LogsContracts.Level.ERROR : LogsContracts.Level.WARN;
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        Optional<String> thrown = Optional.ofNullable(event.getThrown()).map(LogCapture::describe);
        synchronized (LINES) {
            last++;
            LINES.addLast(new LogsContracts.Line(
                    last,
                    clip(String.valueOf(event.getLoggerName()), NAME),
                    level,
                    clip(String.valueOf(event.getThreadName()), NAME),
                    clip(message, TEXT),
                    thrown));
            if (LINES.size() > KEPT) {
                LINES.removeFirst();
            }
        }
    }

    /** {@code t} and its causes, each as its class and message. */
    private static String describe(Throwable t) {
        StringBuilder text = new StringBuilder();
        for (Throwable c = t; c != null && text.length() < TEXT; c = c.getCause() == c ? null : c.getCause()) {
            if (!text.isEmpty()) {
                text.append(" ← caused by ");
            }
            text.append(c);
        }
        return clip(text.toString(), TEXT);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** The appender: every event at WARN or worse, whichever logger. */
    private static final class Appender extends AbstractAppender {

        Appender() {
            super("StageWrightLogCapture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            keep(event);
        }
    }
}
