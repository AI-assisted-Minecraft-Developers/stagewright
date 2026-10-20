package net.magicterra.stagewright.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.magicterra.stagewright.contract.remote.RemoteSession;
import net.magicterra.stagewright.contract.remote.Wire;

/**
 * Runs a directory of {@code .js} scenes out of process and writes the results file the verdict reads.
 *
 * <p>Each scene gets an arena of the game's own (see {@link AttachedContext}): opened before its body,
 * closed after its cleanups, so the world it stands in is allocated, swept and audited by the same
 * code that does it for a scene run in process. The whole phase runs under the world pin the suite
 * itself takes, handed back before the suite starts.
 *
 * <p>The results format is the orchestration contract, unchanged — the same header / scene / done
 * shape the in-process suite and the companion client write, so the existing worst-wins verdict
 * judges a third file without learning anything new. Four details of that contract are not obvious
 * for a home with no game, and each is a way to be silently wrong:
 *
 * <ul>
 *   <li><b>{@code worldPin} states what the game pinned</b>, as the in-process header does, and is
 *       absent only when the game never accepted the phase — the key's "present only when it has a
 *       value" semantics.</li>
 *   <li><b>{@code loader} is mandatory</b> and this JVM has no loader — it is copied from the
 *       endpoint descriptor, which carries it.</li>
 *   <li><b>{@code ticks} is a frozen field</b> and there are no ticks here. It is written as
 *       {@code 0}, with the real {@code wallMs} in {@code data}. Dividing wall-clock by 50 to
 *       manufacture a tick count would produce a number that is wrong by a factor that varies with
 *       how far behind the server is — wrong in a way no reader could detect.</li>
 *   <li><b>An empty registration list is RED.</b> The companion path judges with no expected-scene
 *       reconciliation, so a file with a header, zero scenes and a done footer would otherwise be a
 *       perfectly valid GREEN describing a run that tested nothing. That is the same
 *       silent-skip-looks-like-a-pass failure the expected-scenes manifest exists to close, arriving
 *       through a different file.</li>
 * </ul>
 */
public final class AttachedRun {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** The window around an arena a scene may build in, in chunks; a script cannot ask for another, as in process. */
    private static final int CHUNK_RADIUS = 1;

    /** How long to wait for an arena before giving up on the game giving up. The game ends a stalled
     *  arena itself, by its own progress test; this only keeps a wedged game from holding the runner. */
    private static final long OPEN_TIMEOUT_MS = 15 * 60_000L;

    private final List<SceneSpec> scenes;
    private final DriverBinding driver;
    private final String loader;
    private final Consumer<String> log;
    private final java.util.function.LongSupplier clockMs;

    public AttachedRun(List<SceneSpec> scenes, DriverBinding driver, String loader,
                       Consumer<String> log, java.util.function.LongSupplier clockMs) {
        this.scenes = scenes;
        this.driver = driver;
        this.loader = loader;
        this.log = log;
        this.clockMs = clockMs;
    }

    /**
     * Run every scene in order and write {@code resultsFile}. Once the connection to the driver is
     * lost, the scenes after that one are recorded as not run rather than run against it.
     *
     * @return true when no required scene failed and the connection to the driver held — the caller
     *         turns that into an exit code
     */
    public boolean run(Path resultsFile) {
        long startedAt = clockMs.getAsLong();
        List<Map<String, Object>> records = new ArrayList<>();
        boolean allGood = true;

        // Zero scenes is RED, and it has to be checked HERE rather than left to the verdict. The
        // companion path judges records without reconciling against an expected-scene list, so a
        // header + no scenes + a done footer is a structurally perfect GREEN describing a run that
        // executed nothing — someone points --attached at the wrong folder and the gate congratulates
        // them. Writing the file first and failing after keeps the evidence: the empty file is on
        // disk, so the diagnosis is "it found no scenes" rather than "it produced nothing".
        if (scenes.isEmpty()) {
            write(resultsFile, records, startedAt, null, null);
            log.accept("RED: no attached scenes were registered — a run that tested nothing is not a"
                    + " pass. Check that --attached points at a directory containing .js files.");
            return false;
        }

        // Watched at the binding rather than read from the scene's outcome: a body may catch the
        // failure and carry on, and the connection is gone all the same.
        StageWrightTransportException[] lost = new StageWrightTransportException[1];
        DriverBinding watched = (method, params) -> {
            try {
                return driver.route(method, params);
            } catch (SceneFailure e) {
                if (lost[0] == null && e.getCause() instanceof StageWrightTransportException t) lost[0] = t;
                throw e;
            }
        };
        String lostDuring = null;

        // The world is pinned for the whole out-of-process phase, once: the arenas below are the game's
        // own and each scene's clock is applied to its arena, but the gamerules that freeze the sky
        // and the weather are the suite's and are taken and handed back around all of it.
        String worldPin = null;
        String beginFailure = null;
        try {
            Map<?, ?> begun = (Map<?, ?>) watched.route(Wire.BEGIN, Map.of("protocol", Wire.PROTOCOL));
            if (begun.get("description") != null) worldPin = String.valueOf(begun.get("description"));
        } catch (RuntimeException e) {
            beginFailure = Reasons.message(e);
            log.accept("RED: the game did not accept the out-of-process phase (" + beginFailure + "). It needs a"
                    + " StageWright that speaks protocol " + Wire.PROTOCOL + " — the one this runner embeds does.");
        }

        for (SceneSpec spec : scenes) {
            if (beginFailure != null) {
                records.add(record(spec, "FAIL", 0, "not run: the game did not accept the out-of-process phase ("
                        + beginFailure + ")", false, false, Map.of()));
                allGood = false;
                continue;
            }
            if (lostDuring != null) {
                // Each would fail on its first call without testing anything; recorded as not run,
                // so coverage counts it as a hole instead of as executed.
                records.add(record(spec, "FAIL", 0, "not run: the connection to the driver was lost"
                        + " during '" + lostDuring + "' (" + lost[0].error() + ")", false, false, Map.of()));
                continue;
            }
            long started = clockMs.getAsLong();
            AttachedContext ctx = new AttachedContext(spec.name(), watched, spec.wallMsHint(), clockMs);
            RemoteSession remote = null;
            String outcome = "PASS";
            String reason = "";
            String skipReason = null;
            boolean skipped = false;
            boolean bodyRan = false;
            try {
                // The arena first: a scene that names a dimension this runtime lacks is skipped here, as
                // in process, and one whose arena never arrives fails here without its body having run.
                remote = RemoteSession.open(watched, spec.name(),
                        new RemoteSession.Options(spec.terrain(), spec.clock(), spec.dimension(), CHUNK_RADIUS),
                        clockMs, OPEN_TIMEOUT_MS);
                ctx.attach(remote);
                bodyRan = true;
                Scripts.run(spec, ctx);
            } catch (SceneSkipped e) {
                // An absent thing is a recorded PASS carrying the reason — the framework's central
                // rule, and the one Rhino's WrappedException hid for so long. Scripts.unwrapOurs is
                // shared precisely so this branch cannot be reached in one home and missed in the other.
                skipReason = e.getMessage();
            } catch (SceneFailure e) {
                outcome = "FAIL";
                reason = e.getMessage();
            } catch (Throwable e) {
                // Throwable, as in-process: an Error escaping here would end the run before the
                // results file is written, and the CLI would report a crash instead of RED.
                outcome = "FAIL";
                reason = Reasons.unexpected(e);
            }

            // This side's cleanups first, then the game's: a script's cleanup may still want the arena
            // it built, and the game's own (a restored inventory, a block entity taken down) run when
            // the arena is let go.
            List<String> teardown = new ArrayList<>(
                    ctx.runCleanups(line -> log.accept("'" + spec.name() + "': " + line)));
            RemoteSession.Closed closed = closeSession(remote, spec, log);
            teardown.addAll(closed.cleanupFailures());
            Map<String, Object> recorded = new LinkedHashMap<>(closed.records());
            recorded.putAll(ctx.records());
            if (!closed.leaks().isEmpty()) recorded.put("leaks", closed.leaks());

            // Checks failed on either side count, wherever they were made: a check on a game-side
            // object (checkBlock) records its violation there.
            List<String> soft = new ArrayList<>(ctx.softViolations());
            soft.addAll(closed.softViolations());
            if (skipReason != null) {
                String failed = SceneSkipped.failureAfterChecks(soft, skipReason);
                if (failed != null) {
                    outcome = "FAIL";
                    reason = failed;
                } else {
                    reason = "skipped: " + skipReason;
                    skipped = true;
                }
            } else if ("PASS".equals(outcome) && !soft.isEmpty()) {
                outcome = "FAIL";
                reason = String.join("; ", soft);
            }
            if (!teardown.isEmpty() && "PASS".equals(outcome)) {
                outcome = "FAIL";
                reason = "cleanup failed: " + String.join("; ", teardown);
            }
            if (lost[0] != null) {
                // A pass here was measured over a dead connection, its body having caught the failure
                // or taken it for an answer; a failure for another reason still has to name it.
                String loss = "the connection to the driver was lost during this scene (" + lost[0].error() + ")";
                if ("PASS".equals(outcome)) reason = loss;
                else if (!reason.contains(lost[0].error())) reason = reason + "; " + loss;
                outcome = "FAIL";
                skipped = false;
            }

            long wallMs = clockMs.getAsLong() - started;
            records.add(record(spec, outcome, wallMs, reason, skipped, bodyRan, recorded));

            if ("FAIL".equals(outcome) && !spec.optional()) allGood = false;
            log.accept(outcome.toLowerCase(java.util.Locale.ROOT) + ": '" + spec.name() + "' ("
                    + wallMs + " ms)" + (reason.isEmpty() ? "" : " — " + reason));

            if (lost[0] != null) {
                // RED even if what failed or went unrun was optional: the run did not do what it was given.
                lostDuring = spec.name();
                allGood = false;
                int left = scenes.size() - records.size();
                String rest = left == 0 ? "no scene was left to run."
                        : "the " + left + " scene(s) after it are recorded as not run.";
                log.accept("RED: the connection to the driver was lost during '" + lostDuring + "' ("
                        + lost[0].error() + "); " + rest);
            }
        }

        // Handed back before mc.test.run, which pins the world itself and would restore this phase's
        // frozen values as the world's own. Not when there is no connection left to hand it back over.
        if (beginFailure == null && lostDuring == null) {
            try {
                watched.route(Wire.END, Map.of());
            } catch (RuntimeException e) {
                log.accept("the game did not take the out-of-process phase back (" + Reasons.message(e) + ")");
                allGood = false;
            }
        }

        // In the file as well as in the return value: judged from its records alone, a run whose scenes
        // are all optional would otherwise read GREEN however early the connection went.
        write(resultsFile, records, startedAt, lostDuring == null ? null
                : "the connection to the driver was lost during '" + lostDuring + "' (" + lost[0].error() + ")",
                worldPin);
        return allGood;
    }

    /**
     * Let a scene's arena go, whatever state the scene ended in.
     *
     * <p>A failure to close is logged and answered with nothing left behind: the scene's own outcome
     * is already decided, and a dead connection fails here the same way it failed there, where the
     * run already knows about it.
     */
    private static RemoteSession.Closed closeSession(RemoteSession remote, SceneSpec spec, Consumer<String> log) {
        if (remote == null) return RemoteSession.Closed.none();
        try {
            return remote.close();
        } catch (RuntimeException e) {
            log.accept("'" + spec.name() + "': the game did not close the scene's arena ("
                    + Reasons.message(e) + ")");
            return RemoteSession.Closed.none();
        }
    }

    private static Map<String, Object> record(SceneSpec spec, String outcome, long wallMs, String reason,
                                              boolean skipped, boolean bodyRan, Map<String, Object> recorded) {
        Map<String, Object> data = new LinkedHashMap<>(recorded);
        data.put("wallMs", wallMs);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "scene");
        record.put("name", spec.name());
        record.put("outcome", outcome);
        record.put("ticks", 0);          // frozen field; there are no ticks out here — see above
        record.put("wallMs", wallMs);
        record.put("reason", reason);
        // Omitted when false, matching ResultsJsonl: the two homes write one schema, and a key
        // that appeared on every line here and only on skips there would be a difference the
        // consistency gate has to explain away rather than one it can assert on.
        if (skipped) record.put("skipped", true);
        // A refusal is a FAIL that tested nothing; unmarked, coverage would count it.
        if (!bodyRan) record.put("bodyRan", false);
        record.put("data", data);
        return record;
    }

    private void write(Path resultsFile, List<Map<String, Object>> records, long startedAt, String cutShort,
                       String worldPin) {
        try {
            Path parent = resultsFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Writer w = Files.newBufferedWriter(resultsFile, StandardCharsets.UTF_8)) {
                Map<String, Object> header = new LinkedHashMap<>();
                header.put("type", "suite");
                header.put("loader", loader);
                List<Map<String, Object>> registered = new ArrayList<>();
                for (SceneSpec s : scenes) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("name", s.name());
                    r.put("required", !s.optional());
                    r.put("canary", Canary.NONE.name());
                    registered.add(r);
                }
                header.put("registered", registered);
                header.put("startedAt", startedAt);
                // Present only when the game pinned something, which is the contract's rule for the key:
                // a run the game never accepted has nothing to state.
                if (worldPin != null && !worldPin.isBlank()) header.put("worldPin", worldPin);
                w.write(GSON.toJson(header));
                w.write('\n');
                for (Map<String, Object> record : records) {
                    w.write(GSON.toJson(record));
                    w.write('\n');
                }
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("type", "done");
                done.put("scenes", records.size());
                if (cutShort != null) done.put("cutShort", cutShort);
                w.write(GSON.toJson(done));
                w.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write attached results to " + resultsFile, e);
        }
    }
}
