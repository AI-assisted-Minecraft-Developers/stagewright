package net.magicterra.stagewright.test.methods;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * {@code mc.test.logs}: the warnings and errors this game's JVM has logged, for a test to assert none came, or the one
 * it expects did. Asked of a game it reads the server's JVM; asked of a client, the client's.
 */
public final class LogsContracts {

    public static final UnionType<LogsOp> LOGS_PARAMS = ApiTypes.union(LogsOp.class);

    public static final UnionType<LogsReply> LOGS_REPLY = ApiTypes.union(LogsReply.class);

    public static final MethodSpec<LogsOp, LogsReply> LOGS = MethodSpec.of(
                    "mc.test.logs",
                    LOGS_PARAMS,
                    LOGS_REPLY,
                    "mark answers where this JVM's log stands now; since lists the WARN and ERROR lines logged after a "
                            + "mark, oldest first, at a level and by a logger the request names. Kept from the "
                            + "JVM's start, the most recent 4096.")
            .hidden();

    private LogsContracts() {}

    /** How serious a line is, as log4j ranks it. */
    @Named("TestLogLevel")
    public enum Level implements WireName {
        WARN,
        ERROR;

        @Override
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The requests {@code mc.test.logs} takes, told apart by {@code op}. */
    @Tag("op")
    @Named("TestLogsParams")
    public sealed interface LogsOp permits Mark, Since {}

    /** Asks where the log stands, for a later {@link Since}. */
    @Variant("mark")
    public record Mark() implements LogsOp {}

    /** Asks for the lines logged after {@code after}. */
    @Variant("since")
    public record Since(
            @Doc("A mark's position; the lines logged after it are listed. 0 for every line kept.") @Range(min = 0)
            long after,

            @Doc("The least serious level listed: warn lists warnings and errors, error errors only.")
            Level atLeast,

            @Doc("Only lines whose logger's name starts with this, such as a mod's package or its logger's name; "
                    + "absent for every logger.")
            Optional<@Length(max = 256) String> logger)
            implements LogsOp {}

    /** One line of the log. */
    @Named("TestLogLine")
    public record Line(
            @Doc("Its position, after every mark taken before it was logged.") @Range(min = 1)
            long at,

            @Doc("The logger's name.") @Length(max = 256) String logger,

            Level level,

            @Doc("The thread it was logged on.") @Length(max = 256)
            String thread,

            @Doc("The message, formatted.") @Length(max = 4096)
            String message,

            @Doc("The exception logged with it, as its class and message, and its causes; absent when none was.")
            Optional<@Length(max = 4096) String> thrown) {

        @Override
        public String toString() {
            return level + " [" + thread + "] " + logger + ": " + message
                    + thrown.map(t -> " — " + t).orElse("");
        }
    }

    /** The answers {@code mc.test.logs} gives, one per {@link LogsOp} variant. */
    @Tag("op")
    @Named("TestLogsReply")
    public sealed interface LogsReply permits Marked, Lines {}

    /** Where the log stands. */
    @Variant("mark")
    public record Marked(
            @Doc("Pass it as since's after.") @Range(min = 0)
            long at) implements LogsReply {}

    /** The lines asked for, oldest first. */
    @Variant("since")
    public record Lines(
            @Length(max = 1024) List<Line> lines,

            @Doc("Lines that matched and were left out for the list's length, the newest of them.") @Range(min = 0)
            int omitted,

            @Doc("Warnings and errors logged after the mark that are no longer kept, of any logger: when not 0, a "
                    + "line asked for may be among them.")
            @Range(min = 0)
            long dropped)
            implements LogsReply {}
}
