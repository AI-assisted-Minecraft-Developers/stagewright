package net.magicterra.stagewright.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The question no single run can answer: did every scene in this suite execute SOMEWHERE?
 *
 * <p>{@link Verdict} judges one results file, and it is right to leave skips green — a scene that
 * needs a player is not broken because the dedicated topology has none. But the same reasoning that
 * makes a skip acceptable in one run makes it invisible across all of them, and a suite whose player
 * scenes skip on every topology it runs reports GREEN over subjects it has never once exercised.
 * That is not hypothetical: the All the Mods 10 suite named 进度解锁 and 任务领取 among its six
 * declared subjects, registered a scene for each, ran exactly one topology, and both scenes skipped
 * for want of a player in every run that has ever existed. Every one of those runs was honestly
 * GREEN. Nothing anywhere said the two subjects were untested.
 *
 * <p>So this is deliberately NOT a hand-maintained list of what must run where. Such a list would
 * have to be updated by the same person who just added the scene that needs it, in the same commit
 * they forgot to. The invariant here needs no maintenance at all: <b>every scene registered by any
 * run must have executed in at least one run.</b> Add a scene and it is covered by the check the
 * moment it exists; add a topology and the check gets easier to satisfy; delete the only topology
 * that could run something and the check goes RED naming it.
 *
 * <p>The single exception is declared in the suite itself, not here: a {@code MUST_SKIP} scene's
 * subject IS the skip, so "it never executed" is its passing condition rather than a hole. Those are
 * marked at the scene ({@code @SceneDef(mustSkip = true)}) by the author who knows why, and
 * {@link Verdict} separately requires them to actually skip — which is a stronger statement than
 * this check ever made about them.
 */
public final class Coverage {

    /** One results file, already parsed, under the name a human should see it by. */
    public record Run(String label, List<Map<String, Object>> records) {}

    public record Result(int code, List<String> report) {}

    private Coverage() {}

    /**
     * @param runs every results file the suite produces. Passing a subset is not an error and not
     *             detectable here — the check can only see the runs it is given, so a project that
     *             wires half its topologies gets a stricter answer than it should, never a looser
     *             one.
     */
    public static Result judge(List<Run> runs) {
        List<String> report = new ArrayList<>();
        if (runs.isEmpty()) {
            return new Result(1, List.of("no results files to reconcile — coverage over zero runs is"
                    + " not a pass"));
        }

        Set<String> everExecuted = new LinkedHashSet<>();
        Set<String> mustSkip = new LinkedHashSet<>();
        Set<String> canaries = new LinkedHashSet<>();
        Map<String, Map<String, String>> absentIn = new LinkedHashMap<>();   // scene -> run -> why
        Set<String> everRegistered = new TreeSet<>();
        List<String> filtered = new ArrayList<>();

        for (Run run : runs) {
            Map<String, Object> suite = null;
            Map<String, Map<String, Object>> scenes = new LinkedHashMap<>();
            for (Map<String, Object> rec : run.records()) {
                String type = Verdict.str(rec.get("type"));
                if ("suite".equals(type)) suite = rec;
                else if ("scene".equals(type)) scenes.put(Verdict.str(rec.get("name")), rec);
            }
            if (suite == null) {
                // ENV in Verdict's terms. Reported rather than fatal: the other runs' coverage is
                // still worth computing, and a run that never armed contributes no scenes to the
                // union, so it can only make this check stricter.
                report.add("NOTE: '" + run.label() + "' has no suite header — it armed nothing and"
                        + " contributes no coverage");
                continue;
            }
            String filter = Verdict.str(suite.get("filter"));
            if (!filter.isBlank()) {
                filtered.add("FILTERED: '" + run.label() + "' was FILTERED to '" + filter + "'");
                continue;
            }
            for (Map<String, Object> reg : Verdict.registered(suite)) {
                String name = Verdict.str(reg.get("name"));
                String canary = Verdict.str(reg.get("canary"));
                everRegistered.add(name);
                if ("MUST_SKIP".equals(canary)) {
                    mustSkip.add(name);
                    continue;
                }
                if (!canary.isBlank() && !"NONE".equals(canary)) {
                    canaries.add(name);
                    continue;
                }
                Map<String, Object> rec = scenes.get(name);
                // ENV_FAIL is written out of PREP, before any body exists, so it is as much "did
                // not run" as a skip — unlike FAIL or TIMEOUT, which executed and found something.
                boolean envFail = rec != null && "ENV_FAIL".equals(Verdict.str(rec.get("outcome")));
                if (rec != null && !Verdict.skipped(rec) && !envFail) {
                    everExecuted.add(name);
                } else {
                    absentIn.computeIfAbsent(name, k -> new LinkedHashMap<>())
                            .put(run.label(), rec == null
                                    ? "no record — the scene was registered and never ran"
                                    : envFail
                                    ? "ENV_FAIL before the body ran — " + Verdict.str(rec.get("reason"))
                                    : Verdict.str(rec.getOrDefault("reason", "skipped")));
                }
            }
        }

        // Not reconciled around: the filtered run's registered list is whatever its pattern kept, so
        // every figure below would describe a subset while reading as the suite.
        if (!filtered.isEmpty()) {
            List<String> out = new ArrayList<>(report);
            out.addAll(filtered);
            out.add("A filtered run is not a coverage claim. Re-run "
                    + (filtered.size() == 1 ? "that topology" : "those topologies")
                    + " without -Pstagewright.scenes, then reconcile again.");
            return new Result(3, out);
        }

        int code = 0;
        List<String> uncovered = new ArrayList<>();
        for (String name : new TreeSet<>(absentIn.keySet())) {
            if (everExecuted.contains(name)) continue;
            uncovered.add(name);
        }

        for (String name : uncovered) {
            code = Math.max(code, 1);
            report.add("UNCOVERED: '" + name + "' never executed in any of the " + runs.size()
                    + " run(s) — it is registered, it is green, and it has tested nothing");
            for (Map.Entry<String, String> e : absentIn.get(name).entrySet()) {
                report.add("    " + e.getKey() + ": " + e.getValue());
            }
        }
        if (!uncovered.isEmpty()) {
            report.add("A scene that skips everywhere needs a topology that can run it, not a"
                    + " manifest entry excusing it. If the skip IS the subject, declare it at the"
                    + " scene with @SceneDef(mustSkip = true) and the verdict will require it.");
        }

        report.add("COVERAGE: " + everExecuted.size() + " of " + everRegistered.size()
                + " registered scene(s) executed across " + runs.size() + " run(s)"
                + (mustSkip.isEmpty() ? "" : ", " + mustSkip.size() + " declared must-skip")
                + (canaries.isEmpty() ? "" : ", " + canaries.size() + " canary")
                + (uncovered.isEmpty() ? "" : ", " + uncovered.size() + " UNCOVERED"));
        for (Run run : runs) {
            report.add("    ran: " + run.label());
        }
        return new Result(code, report);
    }
}
