package net.magicterra.stagewright.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.contract.SceneOutcome;

/**
 * Orchestration-contract-v0 results stream, one JSON object per line, written into
 * the server's working directory (the loom runDir).
 *
 * TIMING CONTRACT (P0 probe incident, worlddriver commit 926396d): file IO here
 * happens ONLY at scene boundaries — suite start, after a scene completes, suite
 * end. Never write during a scene's RUN ticks: synchronous server-thread IO
 * measurably broke a byte-deterministic arena once already. Boundary writes still
 * shift wall-clock for the NEXT scene; before hosting determinism-sensitive
 * dogfood arenas (P1c) this must be revisited (the precedent fix was an async
 * off-thread writer that drains a queue instead of doing IO inline).
 *
 * Names/reasons are escaped (quote+backslash+control chars) — scene names are
 * ordinarily Java identifiers, but reasons are free text: the harness's
 * catch(Throwable) path feeds raw exception messages into `reason`, and those can
 * contain newlines/tabs that would otherwise split one JSON record across
 * physical lines and crash the orchestrator's line-oriented parser.
 */
public final class ResultsJsonl {
    private final Path file;

    /** Scene records written so far. Guarded by this object's monitor, like every write. */
    private int sceneRecords;

    public ResultsJsonl(Path file) {
        this.file = file;
    }

    /**
     * How many scene records are on disk.
     *
     * <p>For the stall watchdog, which has to write a done footer from a thread that is not the one
     * that wrote the records. Counting here rather than at the call site means the footer cannot
     * disagree with the file it terminates — and a footer whose count disagrees is reported as
     * TRUNCATED, which would bury the stall the watchdog exists to name.
     */
    public synchronized int sceneRecordCount() {
        return sceneRecords;
    }

    public void writeSuiteHeader(String loader, List<Scene> scenes) {
        writeSuiteHeader(loader, scenes, null, null);
    }

    /**
     * @param filter the scene-name pattern this run was narrowed by, or null when it ran everything.
     *               Recorded so a filtered run can never be read as a full one: {@code Verdict}
     *               keys its FILTERED handling off this field rather than guessing from a count.
     * @param worldPin one line naming the world state this run held still ({@code WorldPin}), or null
     *               for a stream that does not pin (the client probe file). Recorded because a suite
     *               that freezes the clock and three gamerules and then reports a bare GREEN is
     *               claiming more than it proved.
     */
    public void writeSuiteHeader(String loader, List<Scene> scenes, String filter,
                                 String worldPin) {
        StringBuilder sb = new StringBuilder();
        // Every string field goes through escape(), including the ones that happen to hold a
        // constrained vocabulary today (loader is "fabric"|"neoforge", canary is an enum-ish
        // tag). Leaving them raw made the contract depend on a caller's value never containing
        // a quote — and a corrupt HEADER line is the worst one to produce: verdict.parse()
        // drops lines it cannot decode, so the run reports exit 3 ENV "server never armed"
        // instead of naming the real problem.
        sb.append("{\"type\":\"suite\",\"loader\":\"").append(escape(loader)).append("\",\"registered\":[");
        for (int i = 0; i < scenes.size(); i++) {
            Scene s = scenes.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(escape(s.name()))
              .append("\",\"required\":").append(s.required())
              .append(",\"canary\":\"").append(escape(String.valueOf(s.canary()))).append("\"}");
        }
        sb.append(']');
        if (filter != null && !filter.isBlank()) {
            sb.append(",\"filter\":\"").append(escape(filter)).append('"');
        }
        // What world these results were produced in. A suite that pins the clock and three gamerules
        // and then reports a bare GREEN is claiming more than it proved; this is where the claim gets
        // qualified, next to the results rather than in a log someone would have to still have.
        if (worldPin != null && !worldPin.isBlank()) {
            sb.append(",\"worldPin\":\"").append(escape(worldPin)).append('"');
        }
        sb.append("}\n");
        write(sb.toString(), true);
    }

    /**
     * The whole file for a run whose registry could not be built: a header that registers nothing and
     * carries {@code registryError}, then a footer. Complete, so a supervisor waiting on the footer
     * stops waiting, and judged RED with the error, where a crash with no header would read ENV.
     */
    public synchronized void writeRegistryFailure(String loader, String filter, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"suite\",\"loader\":\"").append(escape(loader)).append("\",\"registered\":[]");
        if (filter != null && !filter.isBlank()) {
            sb.append(",\"filter\":\"").append(escape(filter)).append('"');
        }
        sb.append(",\"registryError\":\"").append(escape(error)).append("\"}\n");
        write(sb.toString(), true);
        writeDone(0);
    }

    public void writeScene(String name, SceneOutcome outcome, int ticks, long wallMs, String reason) {
        writeScene(name, outcome, ticks, wallMs, reason, java.util.Map.of());
    }

    /**
     * @param data values the scene attached with {@code SceneContext.record}. Emitted as a
     *             {@code "data"} object, and OMITTED entirely when empty so that every scene
     *             predating the record API produces a byte-identical line.
     *
     *             <p>Values are rendered as JSON numbers when they are numeric and as strings
     *             otherwise — a measurement that arrives as {@code "19.6"} cannot be compared or
     *             plotted downstream without every consumer re-parsing it, which is the whole reason
     *             a scene bothers to record it.
     */
    public void writeScene(String name, SceneOutcome outcome, int ticks, long wallMs,
                           String reason, java.util.Map<String, Object> data) {
        writeScene(name, outcome, ticks, wallMs, reason, data, false);
    }

    /**
     * @param skipped the scene resolved without executing its subject. Emitted as a {@code "skipped"}
     *                boolean, and OMITTED when false so a run of scenes that all executed produces a
     *                byte-identical line to before this existed.
     *
     *                <p>A skip resolves the scene as PASS, so the outcome alone cannot tell the two
     *                apart, and the only signal used to be the {@code "skipped: "} prefix on the
     *                reason string — a contract two independent writers had to remember and any
     *                reader had to reconstruct by parsing prose. It is a field now because the
     *                verdict has to be able to say what a run actually covered, and a suite whose
     *                skips are invisible reports a GREEN that reads as coverage it does not have.
     */
    public synchronized void writeScene(String name, SceneOutcome outcome, int ticks, long wallMs,
                           String reason, java.util.Map<String, Object> data, boolean skipped) {
        sceneRecords++;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"scene\",\"name\":\"").append(escape(name))
          .append("\",\"outcome\":\"").append(escape(String.valueOf(outcome)))
          .append("\",\"ticks\":").append(ticks).append(",\"wallMs\":").append(wallMs)
          .append(",\"reason\":\"").append(escape(reason == null ? "" : reason)).append('"');
        if (skipped) sb.append(",\"skipped\":true");
        if (data != null && !data.isEmpty()) {
            sb.append(",\"data\":{");
            boolean first = true;
            for (java.util.Map.Entry<String, Object> e : data.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(e.getKey())).append("\":").append(jsonValue(e.getValue()));
            }
            sb.append('}');
        }
        sb.append("}\n");
        write(sb.toString(), false);
    }

    private static String jsonValue(Object v) {
        if (v instanceof Boolean b) return b.toString();
        if (v instanceof Number n) {
            double d = n.doubleValue();
            // NaN and infinities are not JSON. They reach here from a perf ratio whose baseline was
            // zero, and emitting them raw produces a line the orchestrator's parser drops — which
            // silently turns a scene record into a SWALLOWED report naming the wrong problem.
            if (Double.isNaN(d) || Double.isInfinite(d)) return '"' + String.valueOf(d) + '"';
            return n.toString();
        }
        return '"' + escape(String.valueOf(v)) + '"';
    }

    /** The heartbeat's path, derived from this file's rather than named independently: if the
     *  results ever move — a companion run's own directory already moves them — the thing that says
     *  where the run got to has to move with them, or the verdict reads one run's position while
     *  judging another's records. */
    Path progressFile() {
        return file.toAbsolutePath().resolveSibling(Heartbeat.FILE);
    }

    public synchronized void writeDone(int scenes) {
        write("{\"type\":\"done\",\"scenes\":" + scenes + "}\n", false);
    }

    /** Synchronized because the stall watchdog writes from its own thread: the server thread it
     *  would otherwise interleave with is wedged by definition, but "by definition" is not a
     *  guarantee, and a half-written line reads to the orchestrator as a dropped record. */
    private synchronized void write(String line, boolean truncate) {
        try {
            if (truncate) {
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } else {
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            // A broken results stream must never read as green — fail the run loudly;
            // the orchestrator's missing-footer rule turns this into RED regardless.
            throw new UncheckedIOException("cannot write testkit results", e);
        }
    }

    /** Package-private so {@link EndpointDescriptor} hand-rolls its JSON against the same escaping. */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
