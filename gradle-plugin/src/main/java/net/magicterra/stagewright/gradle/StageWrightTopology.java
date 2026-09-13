package net.magicterra.stagewright.gradle;

import org.gradle.api.Named;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * One scene topology: a run configuration the host build already declares, plus where its results
 * land and how to judge them.
 *
 * <p><b>The plugin does not build the launch.</b> It points at a run task the consumer's own loader
 * plugin produced, because that plugin is the only thing that knows how to start the game it
 * supports — the dev bootstrap for NeoForge under ModDevGradle, for Fabric under loom, and for Forge
 * under ForgeGradle share nothing but the fact that each ends in a JVM. Reimplementing any of them
 * would make StageWright's support matrix a list of loader-plugin versions instead of a list of
 * Minecraft versions, and would go stale on someone else's release schedule.
 *
 * <pre>{@code
 * stagewright {
 *     topologies {
 *         server { runTask = 'runStagewrightServer'; gameDirectory = file('run-stagewright') }
 *     }
 * }
 * }</pre>
 */
public abstract class StageWrightTopology implements Named {

    private final String name;

    // @Inject even though the container passes the name explicitly: Gradle instantiates managed
    // types through its own injector, and from 9.x it refuses any constructor that is not annotated
    // rather than falling back to a plain reflective call.
    @javax.inject.Inject
    public StageWrightTopology(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    /** The host build's run task to execute, e.g. {@code runStagewrightServer}. A task in another
     *  project needs its full path: {@code ':neoforge:runDogfoodServer'}. */
    public abstract Property<String> getRunTask();

    /**
     * A second run task to stand up alongside {@link #getRunTask()} and tear down after it, e.g. the
     * game client that joins the dedicated server the scenes run on.
     *
     * <p>Launched as a detached process rather than as a Gradle task, because Gradle runs the tasks
     * in one project's graph sequentially and this pair has to overlap. The command line is read off
     * the companion's own {@code JavaExec} spec, so the loader plugin still owns what a dev run
     * looks like.
     *
     * <p>No start-order handshake, deliberately. The client director re-dials until the server
     * answers, which is cheaper and more robust than either side waiting for a readiness signal the
     * other has no reason to emit.
     */
    public abstract Property<String> getCompanionRunTask();

    /** The run's game directory — the process working directory, which is where the harness writes. */
    public abstract DirectoryProperty getGameDirectory();

    /** Results file name relative to {@link #getGameDirectory()}. Convention:
     *  {@code stagewright-results.jsonl}. A name other than the convention is passed into the game
     *  as {@code -Dstagewright.results}, so the harness writes the file this topology is judged on;
     *  a StageWright older than that property ignores it and writes the convention. */
    public abstract Property<String> getResultsFile();

    /** Optional expected-scenes manifest. Present: reconciliation runs in both directions and a
     *  scene registered but not declared REDs. Absent: outcomes are judged, coverage is not. */
    public abstract RegularFileProperty getExpectFile();

    /**
     * Optional results file written by the companion CLIENT, judged alongside the server's.
     *
     * <p>Set it on a topology whose client half asserts something in its own JVM — anything about
     * the BOUNDARY between the two processes, which no scene can reach because every scene body
     * runs on the server thread. Absent (the convention) the client is a passive player and only
     * the server's results decide the run.
     *
     * <p>Once set, an ABSENT file is an ENV verdict rather than a pass. A client that was launched
     * to assert something and said nothing is indistinguishable from one that never started, and
     * the run must not be green either way.
     */
    public abstract RegularFileProperty getCompanionResultsFile();

    /**
     * Delete the world and any stale results before the run. Convention: true.
     *
     * <p>Reusing a world across runs is the single most expensive false signal this framework can
     * produce. Blocks a previous run placed are still standing, so a scene asserting "crossing this
     * gap must consume a block" reports that nothing was consumed; shafts a previous run dug change
     * the terrain under the next one. Which scenes fail varies per run, so it reads exactly like
     * flakiness — and the hours go into the scenes rather than into the world they inherited.
     */
    public abstract Property<Boolean> getCleanWorld();

    /**
     * Wall-clock ceiling for the run task, in minutes. Convention: 20.
     *
     * <p>The suite normally ends itself — the harness halts the server once its registry drains — so
     * this only fires when the game never got far enough to run scenes at all. That case is common
     * enough to design for: a client whose mod loading fails sits on an error screen forever, holding
     * its game directory open, and without a ceiling the gate waits with it. Gradle destroys the
     * process on expiry, so the verdict task then reports a results file that is missing or
     * truncated, which is the honest description of what happened.
     */
    public abstract Property<Integer> getTimeoutMinutes();

    /**
     * Run the game against an X virtual framebuffer this task starts and stops. Convention:
     * {@code false}.
     *
     * <p>For the topologies that need a real client on a headless Linux CI box. A free display is
     * probed rather than pinned — hardcoding {@code :99} collides with whatever a developer left
     * running — and {@code DISPLAY} is injected into the run's environment for the duration.
     *
     * <p>Ignored where it cannot apply: not Linux, no {@code Xvfb} on PATH, or {@code DISPLAY}
     * already set (a real desktop, which is a better display than one we would start).
     */
    public abstract Property<Boolean> getVirtualDisplay();

    /**
     * Jars to install into the run directory's {@code mods/} folder before the game starts.
     * Optional.
     *
     * <p>Point this at the StageWright loader jar — normally a configuration, so the version comes
     * from the dependency block like everything else:
     *
     * <pre>{@code
     * dependencies { stagewrightRuntime "net.magicterra:mc_stagewright-neoforge:0.1.0+1.21.1" }
     * stagewright { topologies { dedicatedServer { installMods.from configurations.stagewrightRuntime } } }
     * }</pre>
     *
     * <p><b>Not optional in practice for ModDevGradle consumers.</b> Under architectury-loom,
     * {@code modLocalRuntime} already puts a mod jar in front of FML and this can stay empty. MDG has
     * no equivalent — its dev run assumes the only mod is yours — and adding the harness to the
     * runtime classpath instead does NOT work: FML claims it as a plain game library, the mod never
     * appears in the mod list, and the run boots, ticks, writes no results and reports "the game
     * never armed" over a log with no error in it.
     *
     * <p>{@link net.magicterra.stagewright.engine.ModInstall} does the work, so these are the same
     * rules the CLI applies to a modpack: a previous install's jars are swept first, because the
     * versioned filenames mean an upgrade otherwise lands beside its predecessor and the loader arms
     * the stale one.
     */
    public abstract org.gradle.api.file.ConfigurableFileCollection getInstallMods();

    /**
     * A checked-in directory of {@code .js} scene files, installed into the run directory's
     * {@code config/stagewright/scenes} before the game starts. Optional.
     *
     * <p>The surface a modpack uses: scenes as files beside the pack's configs, no source set and no
     * build. Pointing a topology at a directory of them is what lets those files live in a repo and
     * still arrive where a pack would put them. They register into the same registry as compiled
     * scenes and are reconciled against the same {@link #getExpectFile() manifest}.
     *
     * <p>Requires Rhino on the run's classpath, which arrives with worlddriver. Without it the
     * harness fails the run rather than skipping the files.
     */
    public abstract DirectoryProperty getSceneScripts();
}
