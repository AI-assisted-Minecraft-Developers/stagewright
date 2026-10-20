package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Whether the same scene file says the same thing in both homes.
 *
 * <p>With {@code --parity}, the scene files given to {@code --attached} are expected to be the ones
 * the game runs in process too. A scene that passes in one home and fails in the other is not a
 * defect in either result: it is the shared language meaning two things, which is the one failure the
 * single scene vocabulary exists to rule out, and it surfaces only here.
 *
 * <p>Only the outcome is compared, not the reason, the measurements or the timing. Wording differs
 * between homes by design (a tick count is not a wall clock), and a check that flagged it would be
 * red on every scene and read by nobody.
 */
final class Parity {
    private Parity() {}

    /** How a scene ended, reduced to what both homes can be asked to agree on. */
    enum Result {
        PASSED, SKIPPED, DID_NOT_PASS;

        static Result of(String outcome, boolean skipped) {
            if (skipped) return SKIPPED;
            return "PASS".equals(outcome) ? PASSED : DID_NOT_PASS;
        }
    }

    /** What a comparison found. */
    record Report(List<String> differences, int compared, int onlyInProcess, int onlyAttached) {
        boolean agrees() {
            return differences.isEmpty();
        }
    }

    /** Scene name to result, from a results file; an unreadable or missing file has none. */
    static Map<String, Result> read(Path file) {
        Map<String, Result> out = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return out;
        }
        for (String line : lines) {
            if (line.isBlank()) continue;
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(line);
            } catch (RuntimeException e) {
                continue;
            }
            if (!parsed.isJsonObject()) continue;
            JsonObject row = parsed.getAsJsonObject();
            if (!row.has("type") || !"scene".equals(row.get("type").getAsString()) || !row.has("name")) continue;
            boolean skipped = row.has("skipped") && row.get("skipped").getAsBoolean();
            String outcome = row.has("outcome") ? row.get("outcome").getAsString() : "";
            out.put(row.get("name").getAsString(), Result.of(outcome, skipped));
        }
        return out;
    }

    static Report compare(Map<String, Result> inProcess, Map<String, Result> attached) {
        List<String> differences = new ArrayList<>();
        int compared = 0;
        int onlyAttached = 0;
        for (Map.Entry<String, Result> e : attached.entrySet()) {
            Result there = inProcess.get(e.getKey());
            if (there == null) {
                onlyAttached++;
                continue;
            }
            compared++;
            if (there != e.getValue()) {
                differences.add("'" + e.getKey() + "': " + describe(there) + " in process, "
                        + describe(e.getValue()) + " attached");
            }
        }
        int onlyInProcess = 0;
        for (String name : inProcess.keySet()) {
            if (!attached.containsKey(name)) onlyInProcess++;
        }
        return new Report(differences, compared, onlyInProcess, onlyAttached);
    }

    private static String describe(Result r) {
        return switch (r) {
            case PASSED -> "passed";
            case SKIPPED -> "skipped";
            case DID_NOT_PASS -> "did not pass";
        };
    }

    /** The lines to print, and nothing else: the verdict is {@link Report#agrees}. */
    static List<String> lines(Report report) {
        List<String> out = new ArrayList<>();
        for (String d : report.differences()) out.add("[stagewright] PARITY: " + d);
        out.add("[stagewright] PARITY " + (report.agrees() ? "agrees" : "DIFFERS") + ": " + report.compared()
                + " scene(s) ran in both homes" + (report.differences().isEmpty() ? "" : ", "
                + report.differences().size() + " differently")
                + "; " + report.onlyAttached() + " ran only attached, " + report.onlyInProcess()
                + " only in process (not compared)");
        return out;
    }
}
