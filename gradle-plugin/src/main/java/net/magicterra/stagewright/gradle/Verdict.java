package net.magicterra.stagewright.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The sole verdict authority: turns a results JSONL into an exit code and a report.
 *
 * <p>Semantics are the orchestration contract, carried over unchanged from the Python implementation
 * this replaces. The exit codes are load-bearing and distinct on purpose:
 *
 * <ul>
 *   <li><b>0 GREEN</b> — footer present, every registered non-canary scene PASSed (or failed while
 *       marked optional), every canary landed on the outcome it was declared to require.</li>
 *   <li><b>1 RED</b> — a required scene failed, was never recorded, or reconciliation against the
 *       expected-scenes manifest failed.</li>
 *   <li><b>2 DEAD</b> — a canary landed on the WRONG outcome. The framework can no longer be trusted
 *       to catch failures, so the whole run's results are void rather than merely bad. This is not a
 *       louder RED: a RED says the code is broken, a DEAD says the measurement is.</li>
 *   <li><b>3 ENV</b> — no suite header, i.e. the game never armed. Never reported as RED, because
 *       "the server did not start" must not read as "your code is broken".</li>
 * </ul>
 */
final class Verdict {

    static final String[] LABELS = {"GREEN", "RED", "DEAD", "ENV"};

    /** Canary kind to the outcome it must produce. */
    private static final Map<String, String> CANARY_EXPECT =
            Map.of("MUST_FAIL", "FAIL", "MUST_TIMEOUT", "TIMEOUT");

    record Result(int code, List<String> report) {
        String label() {
            return code >= 0 && code < LABELS.length ? LABELS[code] : "FAILED(" + code + ")";
        }
    }

    private Verdict() {}

    /**
     * Read a results file, dropping lines that do not decode.
     *
     * <p>Dropping can only push a verdict toward RED, never toward a false GREEN: a lost footer
     * fails the footer check, a lost scene record surfaces as SWALLOWED, and a lost header is an ENV.
     */
    static List<Map<String, Object>> parse(Path file, List<String> warnings) throws IOException {
        List<Map<String, Object>> records = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            try {
                Object parsed = Json.parse(line);
                if (parsed instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rec = (Map<String, Object>) map;
                    records.add(rec);
                } else {
                    warnings.add("dropped non-object line " + (i + 1));
                }
            } catch (RuntimeException e) {
                warnings.add("dropped undecodable line " + (i + 1) + ": " + e.getMessage());
            }
        }
        return records;
    }

    /** @param expected scene names that MUST be registered, or null to skip reconciliation. */
    static Result judge(List<Map<String, Object>> records, List<String> expected) {
        Map<String, Object> suite = null;
        Map<String, Object> done = null;
        Map<String, Map<String, Object>> scenes = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (Map<String, Object> rec : records) {
            String type = str(rec.get("type"));
            if ("suite".equals(type)) {
                suite = rec;
            } else if ("scene".equals(type)) {
                String name = str(rec.get("name"));
                counts.merge(name, 1, Integer::sum);
                scenes.put(name, rec);       // last wins, which is exactly why duplicates are flagged
            } else if ("done".equals(type)) {
                done = rec;
            }
        }

        List<String> report = new ArrayList<>();
        if (suite == null) return new Result(3, List.of("no suite header — the game never armed"));
        if (done == null) return new Result(1, List.of("no done footer — the harness died mid-run"));

        int code = 0;

        for (String name : new TreeSet<>(counts.keySet())) {
            if (counts.get(name) > 1) {
                code = Math.max(code, 1);
                report.add("DUPLICATE: '" + name + "' has " + counts.get(name) + " scene records"
                        + " — last-wins can mask an earlier FAIL as GREEN");
            }
        }

        List<Map<String, Object>> registered = registered(suite);
        Set<String> registeredNames = new LinkedHashSet<>();
        for (Map<String, Object> r : registered) registeredNames.add(str(r.get("name")));

        if (expected != null && !expected.isEmpty()) {
            for (String want : expected) {
                if (!registeredNames.contains(want)) {
                    code = Math.max(code, 1);
                    report.add("MISSING-EXPECTED: " + want + " not in registered");
                }
            }
            // The reverse direction, and it is the half that actually closes the hole: a scene added
            // to the provider but never added to the manifest would otherwise be accepted silently,
            // which is precisely the omission the manifest exists to catch. Scoped to namespaces the
            // manifest itself uses, so StageWright's own built-ins stay out of a consumer's verdict.
            Set<String> namespaces = new LinkedHashSet<>();
            for (String want : expected) {
                int dot = want.indexOf('.');
                if (dot > 0) namespaces.add(want.substring(0, dot + 1));
            }
            if (!namespaces.isEmpty()) {
                Set<String> wanted = new LinkedHashSet<>(expected);
                for (Map<String, Object> r : registered) {
                    String name = str(r.get("name"));
                    String canary = str(r.get("canary"));
                    if (wanted.contains(name)) continue;
                    if (CANARY_EXPECT.containsKey(canary) || "MUST_SWALLOW".equals(canary)) continue;
                    for (String ns : namespaces) {
                        if (name.startsWith(ns)) {
                            code = Math.max(code, 1);
                            report.add("UNDECLARED: " + name + " is registered but not in the expected"
                                    + " manifest — add it in the same commit that registers it");
                            break;
                        }
                    }
                }
            }
        }

        for (Map<String, Object> reg : registered) {
            String name = str(reg.get("name"));
            String canary = str(reg.get("canary"));
            Map<String, Object> rec = scenes.get(name);
            boolean required = !Boolean.FALSE.equals(reg.get("required"));

            if ("MUST_SWALLOW".equals(canary)) {
                if (rec != null) {
                    return new Result(2, List.of("DEAD: swallow-canary '" + name
                            + "' was executed — the skip gate is broken"));
                }
                report.add("canary '" + name + "': correctly omitted (swallow gate alive)");
            } else if (CANARY_EXPECT.containsKey(canary)) {
                if (rec == null) {
                    return new Result(2, List.of("DEAD: canary '" + name
                            + "' has no record — the catch gate is broken"));
                }
                String outcome = str(rec.get("outcome"));
                if (!CANARY_EXPECT.get(canary).equals(outcome)) {
                    return new Result(2, List.of("DEAD: canary '" + name + "' -> " + outcome
                            + ", expected " + CANARY_EXPECT.get(canary)));
                }
                report.add("canary '" + name + "': caught as " + outcome + " (expected)");
            } else if (rec == null) {
                code = Math.max(code, 1);
                report.add("SWALLOWED: '" + name + "' is registered but was never recorded");
            } else if (!"PASS".equals(str(rec.get("outcome")))) {
                if (required) code = Math.max(code, 1);
                report.add((required ? "FAIL" : "fail(optional)") + ": '" + name + "' -> "
                        + str(rec.get("outcome")) + " — " + str(rec.getOrDefault("reason", "")));
            } else {
                report.add("pass: '" + name + "' (" + num(rec.get("ticks")) + " ticks, "
                        + num(rec.get("wallMs")) + " ms)" + data(rec));
            }
        }

        Set<String> drifted = new TreeSet<>(scenes.keySet());
        drifted.removeAll(registeredNames);
        if (!drifted.isEmpty()) {
            code = Math.max(code, 1);
            report.add("DRIFTED: records for unregistered names " + drifted);
        }

        Object declared = done.get("scenes");
        if (declared instanceof Number n) {
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            if (n.intValue() != total) {
                code = Math.max(code, 1);
                report.add("TRUNCATED: done.scenes=" + n.intValue() + " but " + total + " scene records");
            }
        }
        return new Result(code, report);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> registered(Map<String, Object> suite) {
        Object raw = suite.get("registered");
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    /** Recorded measurements, rendered inline so a PASS carries its evidence into the console too —
     *  not only into the results file. */
    private static String data(Map<String, Object> rec) {
        Object raw = rec.get("data");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" [");
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append('=').append(num(e.getValue()));
        }
        return sb.append(']').toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** Render a parsed JSON number without the ".0" the parser's Double always carries. */
    private static String num(Object o) {
        if (o instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
            return String.valueOf(d.longValue());
        }
        return str(o);
    }
}
