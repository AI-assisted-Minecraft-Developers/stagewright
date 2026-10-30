package net.magicterra.stagewright.gradle;

import org.gradle.api.Named;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

/**
 * One scene topology: a run configuration the host build already declares, which a test suite maps a scene's topology
 * name to with {@link StageWrightTestSuite#getTopologies()}.
 *
 * <p><b>The plugin does not build the launch.</b> It points at a run task the consumer's own loader plugin produced,
 * because that plugin is the only thing that knows how to start the game it supports — the dev bootstrap for NeoForge
 * under ModDevGradle, for Fabric under loom, and for Forge under ForgeGradle share nothing but the fact that each ends
 * in a JVM. Reimplementing any of them would make StageWright's support matrix a list of loader-plugin versions instead
 * of a list of Minecraft versions, and would go stale on someone else's release schedule.
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

    /** Called by Gradle with the name the topology is declared under. */
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

    /**
     * The host build's run task the primary game is launched from, e.g. {@code runStagewrightServer}. A task in another
     * project needs its full path: {@code ':neoforge:runDogfoodServer'}.
     */
    public abstract Property<String> getRunTask();

    /**
     * A second run task whose game runs alongside {@link #getRunTask()}'s, e.g. the game client that joins the
     * dedicated server the scenes run on. Its launch is read off its own {@code JavaExec} spec, so the loader plugin
     * still owns what a dev run looks like.
     */
    public abstract Property<String> getCompanionRunTask();

    /** The run's game directory. Convention: {@code run-stagewright-<name>}. */
    public abstract DirectoryProperty getGameDirectory();

    /** Delete the world before each scene. Convention: true. */
    public abstract Property<Boolean> getCleanWorld();

    /** Wall-clock ceiling for one scene's games, in minutes. Convention: 20. */
    public abstract Property<Integer> getTimeoutMinutes();

    /**
     * How long a game may go without moving before the scene is ended, in minutes. Convention: 5.
     *
     * <p>The game's supervision agent reports its phase, the server ticks it has delivered and the classes it has
     * loaded; a game that changes none of them for this long is stuck.
     */
    public abstract Property<Integer> getStallMinutes();

    /**
     * What a game the supervision agent had to force down after its run does to its scene: {@code "fail"} fails it,
     * {@code "report"} leaves the verdict to the tests. Reported the same either way. Convention: {@code "fail"}.
     */
    public abstract Property<String> getLeaks();

    /**
     * The run task starts a game client rather than a dedicated server. Convention: {@code false}.
     *
     * <p>A client needs a display; the plugin does not start one: a desktop, a CI image with Xvfb, or {@code xvfb-run
     * ./gradlew …} provides it.
     */
    public abstract Property<Boolean> getClient();
}
