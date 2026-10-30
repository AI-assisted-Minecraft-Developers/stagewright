package net.magicterra.stagewright.gradle;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.SourceSet;

/**
 * The {@code stagewright { ... }} block.
 *
 * <pre>{@code
 * stagewright {
 *     testmodSourceSet = true
 *     topologies {
 *         dedicatedServer {
 *             runTask = 'runStagewrightDedicatedServer'
 *         }
 *         integratedServer {
 *             runTask = 'runStagewrightIntegratedServer'
 *         }
 *         dedicatedServerWithClient {
 *             runTask          = 'runStagewrightDedicatedServerWithClient'
 *             companionRunTask = 'runStagewrightJoiningClient'
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>A topology runs nothing by itself: a test suite in {@code tests} maps the topology names its scenes declare to
 * these, and its {@code stagewrightTest<Suite>} task launches them.
 *
 * <p><b>On the names.</b> All three run scenes on a SERVER; what differs is which server, and whether a real client is
 * attached to it — {@code integratedServer} is the one inside a game client, {@code dedicatedServerWithClient} is a
 * headless server with a client joined over multiplayer. The topology formerly called "the client topology" never ran a
 * scene on a client.
 */
public abstract class StageWrightExtension {

    private final NamedDomainObjectContainer<StageWrightTopology> topologies;
    private final NamedDomainObjectContainer<StageWrightTestSuite> tests;
    private SourceSet testmodSourceSetRef;

    /** Called by Gradle with the containers it creates for this extension. */
    @javax.inject.Inject
    public StageWrightExtension(
            NamedDomainObjectContainer<StageWrightTopology> topologies,
            NamedDomainObjectContainer<StageWrightTestSuite> tests) {
        this.topologies = topologies;
        this.tests = tests;
    }

    /** Test suites: each gets a {@code stagewrightTest<Name>} task on the outer test engine. */
    public NamedDomainObjectContainer<StageWrightTestSuite> getTests() {
        return tests;
    }

    /** Configures the test suites in place, as {@code tests { ... }} does in a build script. */
    public void tests(Action<? super NamedDomainObjectContainer<StageWrightTestSuite>> action) {
        action.execute(tests);
    }

    /** The topologies the build declares, by name. */
    public NamedDomainObjectContainer<StageWrightTopology> getTopologies() {
        return topologies;
    }

    /** Configures the topologies in place, as {@code topologies { ... }} does in a build script. */
    public void topologies(Action<? super NamedDomainObjectContainer<StageWrightTopology>> action) {
        action.execute(topologies);
    }

    /**
     * Register a {@code testmod} source set that sees main's output and main's own classpaths. Convention:
     * {@code false} — off means no source set is created and the plugin is invisible.
     *
     * <p>Classpath wiring only. Attaching that source set to a dev RUN belongs to the host loader plugin, whose DSL
     * differs per loader ({@code neoForge.mods { … sourceSet … }} under ModDevGradle, run configs under loom), so the
     * consumer writes that one line rather than the plugin guessing which loader it is inside.
     */
    public abstract Property<Boolean> getTestmodSourceSet();

    /**
     * The registered {@code testmod} source set, or null when {@link #getTestmodSourceSet()} is false or the
     * {@code java} plugin was never applied.
     */
    public SourceSet getTestmodSourceSetRef() {
        return testmodSourceSetRef;
    }

    void setTestmodSourceSetRef(SourceSet sourceSet) {
        this.testmodSourceSetRef = sourceSet;
    }
}
