package net.magicterra.stagewright.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.SourceSet;

/**
 * Configuration surface for the stagewright gradle plugin — the {@code stagewright { ... }}
 * extension block. All values feed the frozen orchestration-argument assembly table in
 * {@link StageWrightPlugin}; the plugin itself never re-judges results.
 *
 * <p>Conventions (defaults) are set in {@link StageWrightPlugin#apply}:
 * <ul>
 *   <li>{@link #getLoader() loader} — {@code "fabric"}</li>
 *   <li>{@link #getPythonExecutable() pythonExecutable} — {@code "python3"}</li>
 *   <li>{@link #getScriptsDir() scriptsDir} — {@code "scripts/stagewright"} (relative to the
 *       applied project directory, which is the orchestrator working directory)</li>
 *   <li>{@link #getExpectFile() expectFile} — unset (nullable; orchestrator default used)</li>
 *   <li>{@link #getExtraArgs() extraArgs} — empty list</li>
 *   <li>{@link #getTestmodSourceSet() testmodSourceSet} — {@code false} (opt-in, zero
 *       impact when off)</li>
 * </ul>
 */
public abstract class StageWrightExtension {

    /** Loader dimension threaded into every orchestrator invocation ({@code --loader L}). */
    public abstract Property<String> getLoader();

    /** Python interpreter used to run the orchestrator scripts. */
    public abstract Property<String> getPythonExecutable();

    /**
     * Directory (relative to the applied project = orchestrator working dir) holding the
     * frozen orchestration scripts (t0.py / t1.py / t2.py) and the dogfood expect-file.
     */
    public abstract Property<String> getScriptsDir();

    /**
     * Optional expect-file threaded as {@code --expect-file} to the CLIENT topologies
     * (stagewrightClient / stagewrightE2E) when present. Nullable: when unset the orchestrator's
     * own default applies. (stagewrightServer always uses the loader-derived dogfood expect
     * manifest per the frozen table and ignores this value.)
     */
    public abstract RegularFileProperty getExpectFile();

    /** Extra arguments appended verbatim to the tail of every assembled command line. */
    public abstract ListProperty<String> getExtraArgs();

    /**
     * {@code stagewrightServer} only — the gradle run task t0.py launches. Unset (the convention)
     * keeps the frozen dogfood value {@code :<loader>:runDogfoodServer}.
     *
     * <p>An EXTERNAL consumer is not architectury: a single-loader ModDevGradle mod has one
     * gradle project and names its run task {@code :runStageWrightServer}. Without this override
     * the plugin could only ever drive this repo's own dogfood layout.
     */
    public abstract Property<String> getServerRunTask();

    /**
     * {@code stagewrightServer} only — the results JSONL path, relative to the applied project
     * (= the orchestrator working directory) or absolute. Unset keeps the frozen dogfood value
     * {@code <loader>/run-dogfood/testkit-results.jsonl}. It must name the file the game
     * actually writes, i.e. {@code <run directory>/testkit-results.jsonl}.
     */
    public abstract Property<String> getServerResults();

    /**
     * {@code stagewrightServer} only — the expected-scenes manifest. Unset keeps the frozen dogfood
     * value {@code <scriptsDir>/expected-scenes-<loader>.txt}, which lives beside the shared
     * scripts; a consumer's manifest lives in the consumer's own repo instead, because it names
     * the consumer's scenes.
     *
     * <p>Deliberately distinct from {@link #getExpectFile()}, which the CLIENT topologies use —
     * the two were never the same file, and collapsing them would silently hand T0 a T1 manifest.
     */
    public abstract RegularFileProperty getServerExpectFile();

    /**
     * Opt-in: when {@code true}, the plugin registers a {@code testmod} source set on the
     * applied project — see {@link StageWrightPlugin#apply} for the registration reaction and
     * the v1 boundary (classpath wiring only; no loom run-config edits, no dependency
     * additions beyond main's own output+classpaths, no jar packaging changes). Requires
     * the {@code java} plugin on the applied project; registration is a no-op (does not
     * fail the build) when {@code java} is never applied. Convention: {@code false} — zero
     * impact when off, no {@code testmod} source set is created.
     */
    public abstract Property<Boolean> getTestmodSourceSet();

    /**
     * Read-only: the {@code testmod} {@link SourceSet} the plugin registered, or
     * {@code null} when {@link #getTestmodSourceSet()} resolved {@code false} (the
     * default) or the applied project never gained the {@code java} plugin. The plugin is
     * the sole writer (see {@link #setTestmodSourceSetRef}) — consumers read this to wire
     * their OWN loom run config; v1 never touches loom itself (see
     * {@code stagewright/README.md}'s testmod source-set convention section).
     */
    public SourceSet getTestmodSourceSetRef() {
        return testmodSourceSetRef;
    }

    private SourceSet testmodSourceSetRef;

    /** Package-private: written once by {@link StageWrightPlugin} when it registers the set. */
    void setTestmodSourceSetRef(SourceSet sourceSet) {
        this.testmodSourceSetRef = sourceSet;
    }
}
