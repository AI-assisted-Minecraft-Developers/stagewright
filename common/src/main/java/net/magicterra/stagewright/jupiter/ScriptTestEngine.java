package net.magicterra.stagewright.jupiter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.magicterra.stagewright.test.Requires;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.registry.Scripting;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClasspathResourceSelector;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClasspathResourceSource;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

/**
 * The JUnit engine for tests written in JavaScript or YAML, inside the game. A selected {@code .js} resource is
 * evaluated once at discovery through WorldDriver's {@link Scripting}, which collects what it registers with
 * {@code test(name, options, body)} and {@code step(name, body)}; a {@code .yaml} one is read by {@link YamlTests}.
 * Each test runs later with the context {@link ScriptContext} builds. A file that fails to load is reported as a
 * failed container rather than dropped, so a typo cannot make its tests disappear.
 *
 * <p>Tests run one after another, JavaScript files before YAML ones (a YAML step may be a script's), each kind in the
 * order selected. The test engine outside the game discovers
 * the same files without the game (docs/design/test-engine.md), so what a file registers must not depend on the game.
 */
public final class ScriptTestEngine implements TestEngine {

    static final String ID = "stagewright-script";

    /** The scene the test engine outside started this game for; a file's tests for other scenes are not registered. */
    static final String SCENE = "stagewright.junit.scene";

    /** The options {@code test()} understands. The scene is read by the test engine outside the game, not here. */
    private static final Set<String> OPTIONS = Set.of("scene", "requires", "dependsOn", "knownIssue");

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
        EngineDescriptor engine = new EngineDescriptor(uniqueId, "StageWright scripts");
        Map<String, Scripting.Function> steps = new LinkedHashMap<>();
        for (ClasspathResourceSelector selector : request.getSelectorsByType(ClasspathResourceSelector.class)) {
            String resource = selector.getClasspathResourceName();
            if (resource.endsWith(".js")) engine.addChild(File.load(engine, resource, steps));
        }
        // YAML after JavaScript: a YAML step may be one a script registers.
        for (ClasspathResourceSelector selector : request.getSelectorsByType(ClasspathResourceSelector.class)) {
            String resource = selector.getClasspathResourceName();
            if (isYaml(resource)) engine.addChild(File.load(engine, resource, steps));
        }
        return engine;
    }

    @Override
    public void execute(ExecutionRequest request) {
        EngineExecutionListener listener = request.getEngineExecutionListener();
        TestDescriptor engine = request.getRootTestDescriptor();
        listener.executionStarted(engine);
        // What each test ended as, by file#name, for the dependsOn of the tests after it.
        Map<String, TestExecutionResult.Status> results = new HashMap<>();
        for (TestDescriptor child : engine.getChildren()) ((File) child).execute(listener, results);
        listener.executionFinished(engine, TestExecutionResult.successful());
    }

    @Override
    public Optional<String> getGroupId() {
        return Optional.of("net.magicterra");
    }

    @Override
    public Optional<String> getArtifactId() {
        return Optional.of("stagewright-common");
    }

    /** What a test does when it runs: a script's function, or a YAML test's steps. */
    @FunctionalInterface
    interface Body {
        void run(ScriptContext context);
    }

    static boolean isYaml(String resource) {
        return resource.endsWith(".yaml") || resource.endsWith(".yml");
    }

    /** One script file: what it registered, or why it registered nothing. */
    static final class File extends AbstractTestDescriptor {
        final String resource;
        final Map<String, Scripting.Function> steps;
        private RuntimeException loadError;

        private File(UniqueId id, String resource, Map<String, Scripting.Function> steps) {
            super(id, resource, ClasspathResourceSource.from(resource));
            this.resource = resource;
            this.steps = steps;
        }

        static File load(TestDescriptor engine, String resource, Map<String, Scripting.Function> steps) {
            File file = new File(engine.getUniqueId().append("script", resource), resource, steps);
            try {
                if (isYaml(resource)) {
                    for (YamlTests.Test test : YamlTests.read(resource, source(resource))) {
                        file.add(test.name(), test.options(), test.body());
                    }
                    return file;
                }
                Scripting scripting = WorldDriver.scripting();
                if (scripting == null) throw new IllegalStateException("WorldDriver is not up, so no script can run");
                Map<String, Object> globals = Map.of(
                        "test", (Scripting.Function) file::register,
                        "step", (Scripting.Function) file::registerStep);
                scripting.evaluate(resource, source(resource), globals);
            } catch (RuntimeException e) {
                file.loadError = e;
            }
            return file;
        }

        private static String source(String resource) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) throw new IllegalArgumentException("no " + resource + " on the game's class path");
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("could not read " + resource, e);
            }
        }

        /** {@code test(name, body)} or {@code test(name, options, body)}. */
        private Object register(Object... args) {
            if (args.length < 2 || !(args[0] instanceof String name) || !(args[args.length - 1] instanceof Scripting.Function body)) {
                throw new IllegalArgumentException("test(name, options, body) takes a name, optional options, and a function");
            }
            Map<?, ?> options = args.length > 2 && args[1] instanceof Map<?, ?> m ? m : Map.of();
            add(name, options, context -> body.call(context.asScript()));
            return null;
        }

        /** A test, from a script's {@code test()} or a YAML file, unless it is for another scene. */
        private void add(String name, Map<?, ?> options, Body body) {
            for (Object key : options.keySet()) {
                if (!OPTIONS.contains(String.valueOf(key))) {
                    throw new IllegalArgumentException("test '" + name + "': unknown option '" + key + "'; known: "
                            + OPTIONS);
                }
            }
            if (!inThisScene(options.get("scene"))) return;
            UniqueId id = getUniqueId().append("test", name);
            if (getChildren().stream().anyMatch(c -> c.getUniqueId().equals(id))) {
                throw new IllegalArgumentException("test '" + name + "' is registered twice in " + resource);
            }
            Object known = options.get("knownIssue");
            if (known != null && !(known instanceof String)) {
                throw new IllegalArgumentException("test '" + name + "': knownIssue is the reason, a string");
            }
            addChild(new Test(id, this, name, requires(name, options.get("requires")),
                    dependencies(resource, name, options.get("dependsOn")), (String) known, body));
        }

        /** Whether a test naming {@code scenes} runs in this game: every test does when no scene was chosen. */
        private static boolean inThisScene(Object scenes) {
            String scene = System.getProperty(SCENE);
            if (scene == null) return true;
            return scenes instanceof List<?> list ? list.contains(scene) : scene.equals(scenes);
        }

        /** {@code step(name, body)}: a named step any test can take with {@code t.step(name, args)}. */
        private Object registerStep(Object... args) {
            if (args.length != 2 || !(args[0] instanceof String name) || !(args[1] instanceof Scripting.Function body)) {
                throw new IllegalArgumentException("step(name, body) takes a name and a function");
            }
            if (BuiltinSteps.NAMES.contains(name)) {
                throw new IllegalArgumentException("step '" + name + "' is built in; give yours another name");
            }
            synchronized (steps) {
                if (steps.putIfAbsent(name, body) != null) {
                    throw new IllegalArgumentException("step '" + name + "' is already registered");
                }
            }
            return null;
        }

        @Override
        public Type getType() {
            return Type.CONTAINER;
        }

        void execute(EngineExecutionListener listener, Map<String, TestExecutionResult.Status> results) {
            listener.executionStarted(this);
            List<Test> order;
            try {
                if (loadError != null) throw loadError;
                order = inDependencyOrder();
            } catch (RuntimeException e) {
                listener.executionFinished(this, TestExecutionResult.failed(e));
                return;
            }
            for (Test test : order) test.execute(listener, results);
            listener.executionFinished(this, TestExecutionResult.successful());
        }

        /** The tests in registration order, except that one runs after every test of this file it depends on. */
        private List<Test> inDependencyOrder() {
            Map<String, Test> byKey = new LinkedHashMap<>();
            for (TestDescriptor child : getChildren()) byKey.put(((Test) child).key(), (Test) child);
            List<Test> order = new ArrayList<>();
            Set<Test> visiting = new HashSet<>();
            for (Test test : byKey.values()) visit(test, byKey, visiting, order);
            return order;
        }

        private void visit(Test test, Map<String, Test> byKey, Set<Test> visiting, List<Test> order) {
            if (order.contains(test)) return;
            if (!visiting.add(test)) {
                throw new IllegalStateException("dependsOn cycle through '" + test.getDisplayName() + "' in " + resource
                        + ", so none of them can go first");
            }
            for (String dependency : test.dependsOn) {
                Test upstream = byKey.get(dependency);
                if (upstream != null) visit(upstream, byKey, visiting, order);
            }
            visiting.remove(test);
            order.add(test);
        }
    }

    /** What {@code requires} asks for: a capability's name, or {@code mod:<id>}, alone or in a list. */
    private static Requirement requires(String test, Object value) {
        List<Object> items = value == null ? List.of() : value instanceof List<?> l ? new ArrayList<>(l) : List.of(value);
        List<Requires.Capability> capabilities = new ArrayList<>();
        List<String> mods = new ArrayList<>();
        for (Object item : items) {
            String s = String.valueOf(item);
            if (s.startsWith("mod:")) {
                mods.add(s.substring(4));
                continue;
            }
            capabilities.add(switch (s) {
                case "client" -> Requires.Capability.CLIENT;
                case "dedicatedServer" -> Requires.Capability.DEDICATED_SERVER;
                case "integratedServer" -> Requires.Capability.INTEGRATED_SERVER;
                default -> throw new IllegalArgumentException("test '" + test + "': requires '" + s + "' is none of"
                        + " client, dedicatedServer, integratedServer, mod:<id>");
            });
        }
        return new Requirement(capabilities.toArray(Requires.Capability[]::new), mods.toArray(String[]::new));
    }

    private record Requirement(Requires.Capability[] capabilities, String[] mods) {
        boolean any() {
            return capabilities.length > 0 || mods.length > 0;
        }
    }

    /** {@code dependsOn}: test names, a bare one in the same file, {@code file.js#name} in another. */
    private static List<String> dependencies(String resource, String test, Object value) {
        List<Object> items = value == null ? List.of() : value instanceof List<?> l ? new ArrayList<>(l) : List.of(value);
        List<String> keys = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException("test '" + test + "': dependsOn names tests, but has "
                        + Expectations.show(item));
            }
            keys.add(s.contains("#") ? s : resource + "#" + s);
        }
        return keys;
    }

    /** One test a script registered. */
    static final class Test extends AbstractTestDescriptor {
        private final File file;
        private final String name;
        private final Requirement requirement;
        private final List<String> dependsOn;
        private final String knownIssue;
        private final Body body;

        private Test(UniqueId id, File file, String name, Requirement requirement, List<String> dependsOn,
                     String knownIssue, Body body) {
            super(id, name, ClasspathResourceSource.from(file.resource));
            this.file = file;
            this.name = name;
            this.requirement = requirement;
            this.dependsOn = dependsOn;
            this.knownIssue = knownIssue;
            this.body = body;
        }

        /** How {@code dependsOn} names this test from another file. */
        String key() {
            return file.resource + "#" + name;
        }

        @Override
        public Type getType() {
            return Type.TEST;
        }

        void execute(EngineExecutionListener listener, Map<String, TestExecutionResult.Status> results) {
            if (requirement.any()) {
                List<String> missing = RequiresCondition.missing(requirement.capabilities(), requirement.mods());
                if (!missing.isEmpty()) {
                    // The prefix is how the engine outside tells a requirement no scene met from any other skip.
                    listener.executionSkipped(this, RequiresCondition.REASON_PREFIX + "this game has no "
                            + String.join(", ", missing));
                    results.put(key(), TestExecutionResult.Status.ABORTED);
                    return;
                }
            }
            listener.executionStarted(this);
            TestExecutionResult result = upstream(results);
            if (result == null) result = run(listener);
            results.put(key(), result.getStatus());
            listener.executionFinished(this, result);
        }

        /** Aborted when a test this one depends on did not pass, or has not run; null when every one passed. */
        private TestExecutionResult upstream(Map<String, TestExecutionResult.Status> results) {
            for (String dependency : dependsOn) {
                TestExecutionResult.Status status = results.get(dependency);
                if (status == TestExecutionResult.Status.SUCCESSFUL) continue;
                String why = status == null
                        ? "has not run before it: it is not in this scene, or its file is selected after this one"
                        : "did not pass (" + status + ")";
                return TestExecutionResult.aborted(new IllegalStateException("depends on " + dependency
                        + ", which " + why));
            }
            return null;
        }

        private TestExecutionResult run(EngineExecutionListener listener) {
            ScriptContext context = new ScriptContext(GameExtension.game(), file.steps, this, listener);
            TestScope.bind(TestScope.ofScript(getDisplayName()));
            Throwable failure = null;
            try {
                body.run(context);
            } catch (Throwable t) {
                failure = t;
            }
            failure = context.end(failure);
            if (knownIssue != null) {
                // As @KnownIssue: the failure is expected, and a pass means the option should come off.
                return failure == null
                        ? TestExecutionResult.failed(new AssertionError("passed although marked knownIssue '"
                                + knownIssue + "': if the issue is fixed, remove the option"))
                        : TestExecutionResult.aborted(new IllegalStateException("known issue: " + knownIssue, failure));
            }
            if (failure == null) return TestExecutionResult.successful();
            // Matched by name: in a Fabric development game the class may come from another loader.
            return failure.getClass().getName().equals("org.opentest4j.TestAbortedException")
                    ? TestExecutionResult.aborted(failure) : TestExecutionResult.failed(failure);
        }
    }
}
