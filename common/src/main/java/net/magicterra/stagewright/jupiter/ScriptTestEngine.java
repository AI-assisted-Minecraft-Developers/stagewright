package net.magicterra.stagewright.jupiter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.magicterra.stagewright.test.Requires;
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
 * <p>JavaScript files load before YAML ones, since a YAML step may be a script's. The tests run beside the game's Java
 * tests, concurrently as {@link ScriptScheduler} arranges. The test engine outside the game discovers
 * the same files without the game (docs/design/test-engine.md), so what a file registers must not depend on the game.
 */
public final class ScriptTestEngine implements TestEngine {

    static final String ID = "stagewright-script";

    /** The scene the test engine outside started this game for; a file's tests for other scenes are not registered. */
    static final String SCENE = "stagewright.junit.scene";

    /** The options {@code test()} understands. The scene is read by the test engine outside the game, not here. */
    private static final Set<String> OPTIONS = Set.of("scene", "requires", "dependsOn", "knownIssue", "locks", "isolated",
            "afterRestart");

    private volatile TestDescriptor discovered;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
        EngineDescriptor engine = new EngineDescriptor(uniqueId, "StageWright scripts");
        Steps steps = new Steps(new LinkedHashMap<>(), JavaSteps.none());
        RuntimeException javaStepsError = null;
        try {
            steps = new Steps(steps.scripts(), JavaSteps.load(Thread.currentThread().getContextClassLoader()));
        } catch (RuntimeException e) {
            // Every file fails with it: which of them would have taken a step that is missing cannot be told.
            javaStepsError = e;
        }
        for (ClasspathResourceSelector selector : request.getSelectorsByType(ClasspathResourceSelector.class)) {
            String resource = selector.getClasspathResourceName();
            if (resource.endsWith(".js")) engine.addChild(File.load(engine, resource, steps, javaStepsError));
        }
        // YAML after JavaScript: a YAML step may be one a script registers.
        for (ClasspathResourceSelector selector : request.getSelectorsByType(ClasspathResourceSelector.class)) {
            String resource = selector.getClasspathResourceName();
            if (isYaml(resource)) engine.addChild(File.load(engine, resource, steps, javaStepsError));
        }
        keepThisRound(engine);
        discovered = engine;
        return engine;
    }

    /**
     * Leave only the tests of the round this game runs ({@link Rounds}), once every file has registered its own: a
     * test's round may follow from one in another file, or from a Java test.
     */
    private static void keepThisRound(TestDescriptor engine) {
        Map<String, Rounds.Upstream> upstream = new LinkedHashMap<>();
        for (TestDescriptor child : engine.getChildren()) {
            for (TestDescriptor t : child.getChildren()) {
                Test test = (Test) t;
                upstream.put(test.key(), new Rounds.Upstream(test.dependsOn(), test.afterRestart));
            }
        }
        Rounds.scripts(upstream);
        for (TestDescriptor child : engine.getChildren()) {
            for (TestDescriptor t : new ArrayList<>(child.getChildren())) {
                if (Rounds.of(((Test) t).key()) != Rounds.current()) child.removeChild(t);
            }
        }
    }

    /**
     * The tests the last discovery found that will run, each with the keys of the tests it depends on: those of a file
     * that failed to load do not.
     */
    Map<String, List<String>> discoveredTests() {
        Map<String, List<String>> tests = new LinkedHashMap<>();
        if (discovered == null) return tests;
        for (TestDescriptor child : discovered.getChildren()) {
            File file = (File) child;
            if (file.loadError != null) continue;
            for (TestDescriptor t : file.getChildren()) tests.put(((Test) t).key(), ((Test) t).dependsOn());
        }
        return tests;
    }

    @Override
    public void execute(ExecutionRequest request) {
        EngineExecutionListener listener = request.getEngineExecutionListener();
        TestDescriptor engine = request.getRootTestDescriptor();
        listener.executionStarted(engine);
        List<File> loaded = new ArrayList<>();
        List<Test> tests = new ArrayList<>();
        for (TestDescriptor child : engine.getChildren()) {
            File file = (File) child;
            listener.executionStarted(file);
            if (file.loadError != null) {
                listener.executionFinished(file, TestExecutionResult.failed(file.loadError));
                continue;
            }
            loaded.add(file);
            for (TestDescriptor test : file.getChildren()) tests.add((Test) test);
        }
        ScriptScheduler.run(tests, request.getConfigurationParameters(), (test, scheduler) -> test.execute(listener, scheduler));
        for (File file : loaded) listener.executionFinished(file, TestExecutionResult.successful());
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

    /** The named steps of one run: those scripts register as they load, and the Java {@code @Step} methods. */
    record Steps(Map<String, Scripting.Function> scripts, JavaSteps java) {}

    static boolean isYaml(String resource) {
        return resource.endsWith(".yaml") || resource.endsWith(".yml");
    }

    /** One script file: what it registered, or why it registered nothing. */
    static final class File extends AbstractTestDescriptor {
        final String resource;
        final Steps steps;
        private RuntimeException loadError;

        private File(UniqueId id, String resource, Steps steps) {
            super(id, resource, ClasspathResourceSource.from(resource));
            this.resource = resource;
            this.steps = steps;
        }

        static File load(TestDescriptor engine, String resource, Steps steps, RuntimeException javaStepsError) {
            File file = new File(engine.getUniqueId().append("script", resource), resource, steps);
            if (javaStepsError != null) {
                file.loadError = javaStepsError;
                return file;
            }
            try {
                if (isYaml(resource)) {
                    for (YamlTests.Test test : YamlTests.read(resource, source(resource))) {
                        file.add(test.name(), test.options(), test.body());
                    }
                    return file;
                }
                Scripting scripting = GameImpl.scripting(GameExtension.game());
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
            Object isolated = options.get("isolated");
            if (isolated != null && !(isolated instanceof Boolean)) {
                throw new IllegalArgumentException("test '" + name + "': isolated is true or false");
            }
            Object afterRestart = options.get("afterRestart");
            if (afterRestart != null && !(afterRestart instanceof Boolean)) {
                throw new IllegalArgumentException("test '" + name + "': afterRestart is true or false");
            }
            addChild(new Test(id, this, name, requires(name, options.get("requires")),
                    dependencies(resource, name, options.get("dependsOn")), locks(name, options.get("locks")),
                    Boolean.TRUE.equals(isolated), Boolean.TRUE.equals(afterRestart), (String) known, body));
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
            if (steps.java().has(name)) {
                throw new IllegalArgumentException("step '" + name + "' is a Java @Step; give yours another name");
            }
            synchronized (steps.scripts()) {
                if (steps.scripts().putIfAbsent(name, body) != null) {
                    throw new IllegalArgumentException("step '" + name + "' is already registered");
                }
            }
            return null;
        }

        @Override
        public Type getType() {
            return Type.CONTAINER;
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

    /**
     * {@code dependsOn}: test names, a bare one in the same file, {@code file.js#name} in another, and
     * {@code com.example.SomeTest#method} a Java test, waited for as a script test is.
     */
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

    /**
     * {@code locks}: what the test shares with others, by a {@link net.magicterra.stagewright.test.Locks} value's name
     * after {@code stagewright.} ({@code worldRules}, {@code worldClock}, {@code weather}, {@code tickRate},
     * {@code player:<name>}), or any name the tests agree on. Tests naming one lock never run at once.
     */
    private static Set<String> locks(String test, Object value) {
        List<Object> items = value == null ? List.of() : value instanceof List<?> l ? new ArrayList<>(l) : List.of(value);
        Set<String> locks = new HashSet<>();
        for (Object item : items) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException("test '" + test + "': locks are names, but has " + Expectations.show(item));
            }
            locks.add(TestScope.LOCK_PREFIX + s);
        }
        return locks;
    }

    /** One test a script registered. */
    static final class Test extends AbstractTestDescriptor {
        private final File file;
        private final String name;
        private final Requirement requirement;
        private final List<String> dependsOn;
        private final Set<String> locks;
        private final boolean isolated;
        private final boolean afterRestart;
        private final String knownIssue;
        private final Body body;

        private Test(UniqueId id, File file, String name, Requirement requirement, List<String> dependsOn,
                     Set<String> locks, boolean isolated, boolean afterRestart, String knownIssue, Body body) {
            super(id, name, ClasspathResourceSource.from(file.resource));
            this.file = file;
            this.name = name;
            this.requirement = requirement;
            this.dependsOn = dependsOn;
            this.locks = locks;
            this.isolated = isolated;
            this.afterRestart = afterRestart;
            this.knownIssue = knownIssue;
            this.body = body;
        }

        /** How {@code dependsOn} names this test from another file. */
        String key() {
            return file.resource + "#" + name;
        }

        List<String> dependsOn() {
            return dependsOn;
        }

        @Override
        public Type getType() {
            return Type.TEST;
        }

        /** Run, or skip, this test and report it; answers how it ended. */
        TestExecutionResult.Status execute(EngineExecutionListener listener, ScriptScheduler scheduler) {
            if (requirement.any()) {
                List<String> missing = RequiresCondition.missing(requirement.capabilities(), requirement.mods());
                if (!missing.isEmpty()) {
                    // The prefix is how the engine outside tells a requirement no scene met from any other skip.
                    listener.executionSkipped(this, RequiresCondition.REASON_PREFIX + "this game has no "
                            + String.join(", ", missing));
                    return TestExecutionResult.Status.ABORTED;
                }
            }
            listener.executionStarted(this);
            TestExecutionResult result = upstream(scheduler);
            if (result == null) {
                try (AutoCloseable held = scheduler.hold(locks, isolated)) {
                    result = run();
                } catch (Exception e) {
                    result = TestExecutionResult.failed(e);
                }
            }
            listener.executionFinished(this, result);
            return result.getStatus();
        }

        /** Failed in a cycle, aborted when a test this one depends on did not pass or is not in the run; else null. */
        private TestExecutionResult upstream(ScriptScheduler scheduler) {
            for (String dependency : dependsOn) {
                String why = null;
                if (!Dependencies.isScript(dependency)) {
                    int hash = dependency.indexOf('#');
                    why = Dependencies.blockerFor(dependency.substring(0, hash), dependency.substring(hash + 1));
                } else if (!scheduler.has(dependency)) {
                    why = Dependencies.earlierRound(dependency);
                    if (why != null && why.startsWith("is not")) {
                        why += ": not in this scene, or its file did not load";
                    }
                }
                if (why != null) {
                    return TestExecutionResult.aborted(new IllegalStateException("depends on " + dependency + ", which "
                            + why));
                }
            }
            return scheduler.blocker(this);
        }

        private TestExecutionResult run() {
            ScriptContext context = new ScriptContext(GameExtension.game(), file.steps.scripts(), file.steps.java(), this);
            TestScope.bind(TestScope.ofScript(getDisplayName(), locks, isolated));
            Throwable failure = null;
            try {
                body.run(context);
            } catch (Throwable t) {
                failure = t;
            }
            failure = context.end(failure);
            TestScope.unbind();
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
