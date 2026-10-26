package net.magicterra.stagewright.gradle;

import org.gradle.api.Named;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.MapProperty;

/**
 * One entry of {@code stagewright { tests { ... } }}: a set of compiled test classes and the
 * topologies they may ask for.
 *
 * <pre>{@code
 * tests {
 *     fabric {
 *         classes.from(project(':common').sourceSets.testmod.output)
 *         classpath.from(project(':common').sourceSets.testmod.compileClasspath)
 *         topologies.put('dedicatedServerWithClient', 'dedicatedServerWithClientFabric')
 *     }
 * }
 * }</pre>
 *
 * Each gets a {@code stagewrightTest<Name>} task running the outer test engine; see
 * docs/design/test-engine.md.
 */
public abstract class StageWrightTestSuite implements Named {

    private final String name;

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
     * classpath. Gradle loads every class under {@link #getClasses()} by name in the test JVM, without running it,
     * and a class whose supertypes are missing there fails to load and fails the task. Nothing here is started.
     */
    public abstract ConfigurableFileCollection getClasspath();

    /** The topology name a scene declares, to the topology declared in {@code topologies} that runs it. */
    public abstract MapProperty<String, String> getTopologies();
}
