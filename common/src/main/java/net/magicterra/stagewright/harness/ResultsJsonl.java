package net.magicterra.stagewright.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneOutcome;

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

    public ResultsJsonl(Path file) {
        this.file = file;
    }

    public void writeSuiteHeader(String loader, List<Scene> scenes) {
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
        sb.append("]}\n");
        write(sb.toString(), true);
    }

    public void writeScene(String name, SceneOutcome outcome, int ticks, long wallMs, String reason) {
        write("{\"type\":\"scene\",\"name\":\"" + escape(name)
                + "\",\"outcome\":\"" + escape(String.valueOf(outcome))
                + "\",\"ticks\":" + ticks + ",\"wallMs\":" + wallMs
                + ",\"reason\":\"" + escape(reason == null ? "" : reason) + "\"}\n", false);
    }

    public void writeDone(int scenes) {
        write("{\"type\":\"done\",\"scenes\":" + scenes + "}\n", false);
    }

    private void write(String line, boolean truncate) {
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

    private static String escape(String s) {
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
