package net.magicterra.stagewright.testengine;

import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

/**
 * The outer tree: engine, then one node per scene, per test class or script file in it, per test method or script test,
 * plus the invocations a game registers while running. A class or script test named by several scenes sits under each,
 * and runs in each.
 *
 * <p>A class's and a script file's source is a {@link ClassSource} named {@code <scene>/<class>} or
 * {@code <scene>/<file>}, and the scene's one named for the scene. A build tool takes the nearest class source above a
 * test as the test's class: Gradle files its JUnit XML by it, one file per class, naming the {@code testsuite} and each
 * {@code testcase}'s {@code classname} for it. Named for the class alone, a class that runs in three scenes gave three
 * files {@code TEST-<class>-1.xml} to {@code -3.xml} and no word of which scene each was. Each test's
 * {@link MethodSource} still names the real class or file, which is what Gradle's {@code --tests} filter matches it on.
 * An IDE cannot open a class from a Gradle run's node any more.
 */
final class Nodes {

    private Nodes() {}

    /** One game instance. Its source is named for it, so a failure of the scene itself is filed under its name. */
    static final class Scene extends AbstractTestDescriptor {
        final String name;

        Scene(UniqueId parent, String name) {
            super(parent.append("scene", name), name, ClassSource.from(name));
            this.name = name;
        }

        @Override
        public Type getType() {
            return Type.CONTAINER;
        }
    }

    /** A test class within one scene. */
    static final class TestClass extends AbstractTestDescriptor {
        final String className;

        TestClass(Scene scene, ClassScan.Scanned scanned) {
            super(
                    scene.getUniqueId().append("class", scanned.className()),
                    scanned.displayName().orElse(simpleName(scanned.className())),
                    ClassSource.from(scene.name + "/" + scanned.className()));
            this.className = scanned.className();
        }

        @Override
        public Type getType() {
            return Type.CONTAINER;
        }
    }

    /** A test method within one class within one scene. */
    static final class TestMethod extends AbstractTestDescriptor {
        final String className;
        final ClassScan.Method method;

        TestMethod(UniqueId parent, String className, ClassScan.Method method) {
            super(
                    parent.append("method", method.key()),
                    method.displayName().orElse(method.key()),
                    MethodSource.from(className, method.name(), method.parameters()));
            this.className = className;
            this.method = method;
        }

        /** The key the game's nodes are matched on. */
        String key() {
            return className + "#" + method.key();
        }

        @Override
        public Type getType() {
            return method.container() ? Type.CONTAINER : Type.TEST;
        }

        @Override
        public boolean mayRegisterTests() {
            return method.container();
        }
    }

    /**
     * A script test file within one scene, holding the tests it registered for that scene.
     *
     * <p>Its source is a {@link ClassSource} named for the scene and the file's path (see {@link Nodes}), and each
     * test's a {@link MethodSource} of the path and the test's name, rather than the {@code ClasspathResourceSource}
     * they are: Gradle's {@code --tests} filter matches class and method sources only and keeps every node with any
     * other, so a resource source made every script test run whatever the filter said. An IDE can no longer open the
     * file from the node.
     */
    static final class ScriptFile extends AbstractTestDescriptor {
        final String resource;

        ScriptFile(Scene scene, String resource) {
            super(
                    scene.getUniqueId().append("script", resource),
                    resource,
                    ClassSource.from(scene.name + "/" + resource));
            this.resource = resource;
        }

        @Override
        public Type getType() {
            return Type.CONTAINER;
        }
    }

    /** One test a script registered, within one scene. */
    static final class ScriptTest extends AbstractTestDescriptor {
        final String resource;
        final String name;

        ScriptTest(UniqueId parent, String resource, String name) {
            super(parent.append("test", name), name, MethodSource.from(resource, name));
            this.resource = resource;
            this.name = name;
        }

        /** The key the game's nodes are matched on. */
        String key() {
            return resource + "#" + name;
        }

        @Override
        public Type getType() {
            return Type.TEST;
        }
    }

    /**
     * A script file that could not be read here, so it has no scene to run in; it fails, saying why. Its source is the
     * file's path as a class, as for {@link ScriptFile}, so a {@code --tests} filter that leaves the file out drops it.
     */
    static final class Unreadable extends AbstractTestDescriptor {
        final String error;

        Unreadable(UniqueId engine, String resource, String error) {
            super(engine.append("script", resource), resource, ClassSource.from(resource));
            this.error = error;
        }

        @Override
        public Type getType() {
            return Type.TEST;
        }
    }

    /** An invocation the game registered while running, such as one row of a parameterized test. */
    static final class Dynamic extends AbstractTestDescriptor {
        private final Type type;

        Dynamic(UniqueId id, String name, TestSource source, boolean test) {
            super(id, name, source);
            this.type = test ? Type.TEST : Type.CONTAINER;
        }

        @Override
        public Type getType() {
            return type;
        }

        @Override
        public boolean mayRegisterTests() {
            return type == Type.CONTAINER;
        }
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
