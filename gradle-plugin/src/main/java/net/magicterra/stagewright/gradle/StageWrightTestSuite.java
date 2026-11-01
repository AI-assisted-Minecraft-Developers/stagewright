package net.magicterra.stagewright.gradle;

import org.gradle.api.Named;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * One entry of {@code stagewright { tests { ... } }}: a set of compiled test classes and the games they run in.
 *
 * <pre>{@code
 * tests {
 *     fabric {
 *         classes.from(project(':common').sourceSets.testmod.output)
 *         classpath.from(project(':common').sourceSets.testmod.compileClasspath)
 *         loaderProject = ':fabric'
 *         jvmArgs.add('-Xmx2g')
 *     }
 * }
 * }</pre>
 *
 * <p>With {@link #getLoaderProject()} the plugin declares the suite's runs in that project's loom or ModDevGradle, one
 * per topology a scene may name, and the loader plugin still builds each launch. With {@link #getTopologies()} instead
 * the suite borrows topologies the build declares. Each suite gets a {@code stagewrightTest<Name>} task running the
 * outer test engine; see docs/design/test-engine.md.
 */
public abstract class StageWrightTestSuite implements Named {

    private final String name;

    /** Called by Gradle with the name the suite is declared under. */
    @javax.inject.Inject
    public StageWrightTestSuite(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    /** Compiled test classes and the resources (scene yaml) the engine scans. */
    public abstract ConfigurableFileCollection getClasses();

    /**
     * What the test classes link against: Minecraft, the mods, StageWright's api — the test source set's compile
     * classpath. Gradle loads every class under {@link #getClasses()} by name in the test JVM, without running it, and
     * a class whose supertypes are missing there fails to load and fails the task. Nothing here is started.
     */
    public abstract ConfigurableFileCollection getClasspath();

    /**
     * The topology name a scene declares, to the topology declared in {@code topologies} that runs it. Left empty when
     * {@link #getLoaderProject()} is set: the suite's runs are generated then.
     */
    public abstract MapProperty<String, String> getTopologies();

    /**
     * The loom or ModDevGradle project whose runs this suite's games are, such as {@code ':fabric'}. The plugin
     * declares them there: {@code stagewright<Name>DedicatedServer}, {@code stagewright<Name>DedicatedServerWithClient}
     * and the {@code stagewright<Name>JoiningClient} that joins it, each in its own directory under
     * {@code run-stagewright/<name>/}, with no IDE run configuration. Convention: the project the plugin is applied to,
     * when it applies either.
     */
    public abstract Property<String> getLoaderProject();

    /**
     * The source set of {@link #getLoaderProject()} the generated runs start from, which is what puts the tests in the
     * game while {@code runClient} goes without them. Convention: {@code testmod}, created seeing {@code main} when the
     * project has none.
     */
    public abstract Property<String> getRunSourceSet();

    /**
     * JVM arguments every generated run gets, such as {@code -Xmx2g}. What one machine needs on every game goes in the
     * Gradle property {@code stagewright.jvmArgs} instead, which mapped topologies get as well.
     */
    public abstract ListProperty<String> getJvmArgs();

    /** System properties every generated run gets. */
    public abstract MapProperty<String, String> getSystemProperties();

    /**
     * The port the generated dedicated servers listen on and the joining client dials. Convention: 0, a free port the
     * test engine picks as each scene starts, so suites of two builds can run at once on one machine.
     */
    public abstract Property<Integer> getServerPort();

    /**
     * How many scenes run at once, each with its games in a directory of its own. Convention: 1, one after another.
     * Every game takes its own memory and processor; the Gradle property {@code stagewright.parallel.scenes} overrides
     * this on one machine. A fixed {@link #getServerPort()} allows no more than 1.
     */
    public abstract Property<Integer> getMaxParallelScenes();

    /**
     * How many of those scenes may start a game client: an integrated server, or a client joining a dedicated one.
     * Convention: 1, since clients share one display, and Minecraft slows the window without the focus. The Gradle
     * property {@code stagewright.parallel.clients} overrides it.
     */
    public abstract Property<Integer> getMaxParallelClients();

    /** The longest one scene's games may run on a generated topology, in minutes. Convention: 20. */
    public abstract Property<Integer> getTimeoutMinutes();

    /**
     * {@link StageWrightTopology#getLeaks()} for the generated topologies: {@code "fail"} or {@code "report"}.
     * Convention: {@code "fail"}.
     */
    public abstract Property<String> getLeaks();

    /**
     * Whether the plugin brings WorldDriver as well as StageWright into the generated runs, and its api onto the tests'
     * compile classpath. Convention: true; WorldDriver's own build, which is WorldDriver, turns it off.
     */
    public abstract Property<Boolean> getIncludeWorldDriver();

    /**
     * The WorldDriver build the generated runs get and the tests compile against. It has to be in the line this
     * StageWright links against, which the plugin checks. Convention: the build StageWright was built against.
     */
    public abstract Property<String> getWorldDriverVersion();

    /**
     * Whether the run source set compiles against the plugin's {@code stagewrightTestCompile} configuration:
     * StageWright's api and WorldDriver's, both in Mojang names, and JUnit Jupiter's unless the build declares its own
     * there. Convention: true.
     */
    public abstract Property<Boolean> getTestDependencies();
}
