package net.magicterra.stagewright.driver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
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
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * The warnings and errors this JVM logs, kept for {@code mc.test.logs}: an appender on log4j's root logger, which both
 * loaders log through, and on each logger that keeps its events from the root, holding the most recent {@value #KEPT}.
 * Installed once, as the mod initializes, so a test reads what the game logged from its start, and put back on a
 * configuration log4j loads later.
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
        capture(context);
    }

    /** Keep what {@code context} logs at WARN or worse, under this configuration and any it loads later. */
    static void capture(LoggerContext context) {
        Appender appender = new Appender();
        appender.start();
        attach(context.getConfiguration(), appender);
        context.updateLoggers();
        // A reconfiguration, a mod loading its own log4j2.xml, builds a configuration without it. Its logger configs
        // are live: an appender added to one takes its next event, so the loggers need no update, and updating them
        // here would fire this event again.
        context.addPropertyChangeListener(change -> {
            if (LoggerContext.PROPERTY_CONFIG.equals(change.getPropertyName())
                    && change.getNewValue() instanceof Configuration fresh) {
                // Stopped with the configuration before, which may stop its loggers' appenders too; it holds
                // nothing to close, so it starts again.
                if (!appender.isStarted()) {
                    appender.start();
                }
                attach(fresh, appender);
            }
        });
    }

    /**
     * Put {@code appender} on {@code config}'s root logger and on every logger that does not pass its events up, as a
     * pack's log4j2.xml sets with {@code additivity="false"}: the events of each logger then reach it exactly once. Not
     * on the configuration's own list of appenders, whose every one it stops when it is replaced.
     */
    static void attach(Configuration config, org.apache.logging.log4j.core.Appender appender) {
        for (LoggerConfig logger :
                targets(config.getRootLogger(), config.getLoggers().values())) {
            if (!logger.getAppenders().containsKey(appender.getName())) {
                logger.addAppender(appender, Level.WARN, null);
            }
        }
    }

    /** The root, and each logger that does not pass its events up to the root's appenders. */
    static List<LoggerConfig> targets(LoggerConfig root, Collection<LoggerConfig> loggers) {
        List<LoggerConfig> out = new ArrayList<>();
        out.add(root);
        for (LoggerConfig logger : loggers) {
            if (logger != root && !logger.isAdditive()) {
                out.add(logger);
            }
        }
        return out;
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
