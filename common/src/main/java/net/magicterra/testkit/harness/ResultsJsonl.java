package net.magicterra.testkit.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import net.magicterra.testkit.scene.Scene;
import net.magicterra.testkit.scene.SceneOutcome;

/**
 * Orchestration-contract-v0 results stream, one JSON object per line, written into
 * the server's working directory (the loom runDir).
 *
 * TIMING CONTRACT (P0 probe incident, agent-driver commit 926396d): file IO here
 * happens ONLY at scene boundaries — suite start, after a scene completes, suite
 * end. Never write during a scene's RUN ticks: synchronous server-thread IO
 * measurably broke a byte-deterministic arena once already. Boundary writes still
 * shift wall-clock for the NEXT scene; before hosting determinism-sensitive
 * dogfood arenas (P1c) this must be revisited (async writer precedent:
 * agent-driver GameTestManifest).
 *
 * Names/reasons are escaped minimally (quote+backslash) — scene names are Java
 * identifiers, reasons are free text we generate ourselves.
 */
public final class ResultsJsonl {
    private final Path file;

    public ResultsJsonl(Path file) {
        this.file = file;
    }

    public void writeSuiteHeader(String loader, List<Scene> scenes) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"suite\",\"loader\":\"").append(loader).append("\",\"registered\":[");
        for (int i = 0; i < scenes.size(); i++) {
            Scene s = scenes.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(s.name())
              .append("\",\"required\":").append(s.required())
              .append(",\"canary\":\"").append(s.canary()).append("\"}");
        }
        sb.append("]}\n");
        write(sb.toString(), true);
    }

    public void writeScene(String name, SceneOutcome outcome, int ticks, long wallMs, String reason) {
        write("{\"type\":\"scene\",\"name\":\"" + name + "\",\"outcome\":\"" + outcome
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
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
