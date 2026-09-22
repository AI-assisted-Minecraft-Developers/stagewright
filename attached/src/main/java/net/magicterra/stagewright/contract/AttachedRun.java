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

/**
 * Runs a directory of {@code .js} scenes out of process and writes the results file the verdict reads.
 *
 * <p>The results format is the orchestration contract, unchanged — the same header / scene / done
 * shape the in-process suite and the companion client write, so the existing worst-wins verdict
 * judges a third file without learning anything new. Four details of that contract are not obvious
 * for a home with no game, and each is a way to be silently wrong:
 *
 * <ul>
 *   <li><b>No {@code worldPin} key.</b> Attached does not pin the world. The key's "present only
 *       when it has a value" semantics exist for exactly this.</li>
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
     * Run every scene in order and write {@code resultsFile}.
     *
     * @return true when nothing failed — the caller turns that into an exit code
     */
    public boolean run(Path resultsFile) {
        List<Map<String, Object>> records = new ArrayList<>();
        boolean allGood = true;

        // Zero scenes is RED, and it has to be checked HERE rather than left to the verdict. The
        // companion path judges records without reconciling against an expected-scene list, so a
        // header + no scenes + a done footer is a structurally perfect GREEN describing a run that
        // executed nothing — someone points --attached at the wrong folder and the gate congratulates
        // them. Writing the file first and failing after keeps the evidence: the empty file is on
        // disk, so the diagnosis is "it found no scenes" rather than "it produced nothing".
        if (scenes.isEmpty()) {
            write(resultsFile, records);
            log.accept("RED: no attached scenes were registered — a run that tested nothing is not a"
                    + " pass. Check that --attached points at a directory containing .js files.");
            return false;
        }

        for (SceneSpec spec : scenes) {
            long started = clockMs.getAsLong();
            AttachedContext ctx = new AttachedContext(spec.name(), driver, spec.wallMsHint(), clockMs);
            String outcome = "PASS";
            String reason = "";
            boolean skipped = false;
            try {
                refuseUnhonourableOptions(spec);
                Scripts.run(spec, ctx);
                List<String> soft = ctx.softViolations();
                if (!soft.isEmpty()) {
                    outcome = "FAIL";
                    reason = String.join("; ", soft);
                }
            } catch (SceneSkipped e) {
                // An absent thing is a recorded PASS carrying the reason — the framework's central
                // rule, and the one Rhino's WrappedException hid for so long. Scripts.unwrapOurs is
                // shared precisely so this branch cannot be reached in one home and missed in the other.
                String failed = SceneSkipped.failureAfterChecks(ctx.softViolations(), e.getMessage());
                if (failed != null) {
                    outcome = "FAIL";
                    reason = failed;
                } else {
                    reason = "skipped: " + e.getMessage();
                    skipped = true;
                }
            } catch (SceneFailure e) {
                outcome = "FAIL";
                reason = e.getMessage();
            } catch (RuntimeException e) {
                outcome = "FAIL";
                reason = "unexpected " + e.getClass().getSimpleName() + ": " + Scripts.message(e);
            }

            List<String> teardown = ctx.runCleanups();
            if (!teardown.isEmpty() && "PASS".equals(outcome)) {
                outcome = "FAIL";
                reason = "cleanup failed: " + String.join("; ", teardown);
            }

            long wallMs = clockMs.getAsLong() - started;
            Map<String, Object> data = new LinkedHashMap<>(ctx.records());
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
            record.put("data", data);
            records.add(record);

            if ("FAIL".equals(outcome) && !spec.optional()) allGood = false;
            log.accept(outcome.toLowerCase(java.util.Locale.ROOT) + ": '" + spec.name() + "' ("
                    + wallMs + " ms)" + (reason.isEmpty() ? "" : " — " + reason));
        }

        write(resultsFile, records);
        return allGood;
    }

    /**
     * Refuse, before running it, a scene whose options this home cannot honour.
     *
     * <p>{@code terrain} and {@code dimension} shape the world a scene stands in. Out here nothing
     * builds an arena, so honouring them is impossible — and running the body anyway would assert
     * against whatever world happens to be loaded while the file plainly said otherwise. That is a
     * pass or a failure with no relationship to what the author wrote. {@code clock} is the same
     * argument: a scene that pinned noon and ran at whatever time it is has not been tested.
     *
     * <p>Refused as a FAILURE rather than a skip, deliberately. A skip would be the right answer if
     * the option were about something ABSENT from this runtime; it is not. It is about this home
     * being the wrong place to run this scene, which is an authoring mistake and should be fixed
     * rather than tolerated on every future run.
     */
    private static void refuseUnhonourableOptions(SceneSpec spec) {
        List<String> unhonourable = new ArrayList<>();
        if (spec.terrain() != Terrain.RUN_WORLD) unhonourable.add("terrain=" + spec.terrain());
        if (spec.clock() != Clock.MIDNIGHT) unhonourable.add("clock=" + spec.clock());
        if (spec.dimension() != null) unhonourable.add("dimension=" + spec.dimension());
        if (unhonourable.isEmpty()) return;
        throw new SceneFailure("scene '" + spec.name() + "' declares " + String.join(", ", unhonourable)
                + ", and out of process there is no arena to build and no world to pin, so those"
                + " cannot be honoured. Running it anyway would assert against whatever world happens"
                + " to be loaded. Move this scene to config/stagewright/scenes, where the harness"
                + " gives it the world it asked for.");
    }

    private void write(Path resultsFile, List<Map<String, Object>> records) {
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
                // No worldPin key. Attached pins nothing, and the contract's "present only when it
                // has a value" rule is what lets that be stated by omission rather than by a lie.
                w.write(GSON.toJson(header));
                w.write('\n');
                for (Map<String, Object> record : records) {
                    w.write(GSON.toJson(record));
                    w.write('\n');
                }
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("type", "done");
                done.put("scenes", records.size());
                w.write(GSON.toJson(done));
                w.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write attached results to " + resultsFile, e);
        }
    }
}
