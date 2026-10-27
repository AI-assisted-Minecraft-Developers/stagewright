package net.magicterra.stagewright.testengine;

import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.ClasspathResourceSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

/**
 * The outer tree: engine, then one node per scene, per test class or script file in it, per test method or script
 * test, plus the invocations a game registers while running. A class or script test named by several scenes sits
 * under each, and runs in each.
 */
final class Nodes {

    private Nodes() {}

    /** One game instance. */
    static final class Scene extends AbstractTestDescriptor {
        final String name;

        Scene(UniqueId parent, String name) {
            super(parent.append("scene", name), name);
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

        TestClass(UniqueId parent, ClassScan.Scanned scanned) {
            super(parent.append("class", scanned.className()),
                    scanned.displayName().orElse(simpleName(scanned.className())), ClassSource.from(scanned.className()));
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
            super(parent.append("method", method.key()), method.displayName().orElse(method.key()),
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

    /** A script test file within one scene, holding the tests it registered for that scene. */
    static final class ScriptFile extends AbstractTestDescriptor {
        final String resource;

        ScriptFile(UniqueId parent, String resource) {
            super(parent.append("script", resource), resource, ClasspathResourceSource.from(resource));
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
            super(parent.append("test", name), name, ClasspathResourceSource.from(resource));
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

    /** A script file that could not be read here, so it has no scene to run in; it fails, saying why. */
    static final class Unreadable extends AbstractTestDescriptor {
        final String error;

        Unreadable(UniqueId engine, String resource, String error) {
            super(engine.append("script", resource), resource, ClasspathResourceSource.from(resource));
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
