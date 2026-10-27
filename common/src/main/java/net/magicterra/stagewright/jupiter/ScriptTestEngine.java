package net.magicterra.stagewright.jupiter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * The JUnit engine for tests written in JavaScript, inside the game. A selected {@code .js} resource is evaluated once
 * at discovery through WorldDriver's {@link Scripting}, which collects what it registers with {@code test(name,
 * options, body)} and {@code step(name, body)}; each test's body runs later with the context {@link ScriptContext}
 * builds. A file that fails to evaluate is reported as a failed container rather than dropped, so a typo cannot make
 * its tests disappear.
 *
 * <p>Tests run one after another, files in the order they were selected. The test engine outside the game discovers
 * the same files without the game (docs/design/test-engine.md), so what a file registers must not depend on the game.
 */
public final class ScriptTestEngine implements TestEngine {

    static final String ID = "stagewright-script";

    /** The scene the test engine outside started this game for; a file's tests for other scenes are not registered. */
    static final String SCENE = "stagewright.junit.scene";

    /** The options {@code test()} understands. The scene is read by the test engine outside the game, not here. */
    private static final Set<String> OPTIONS = Set.of("scene", "requires");

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
        return engine;
    }

    @Override
    public void execute(ExecutionRequest request) {
        EngineExecutionListener listener = request.getEngineExecutionListener();
        TestDescriptor engine = request.getRootTestDescriptor();
        listener.executionStarted(engine);
        for (TestDescriptor child : engine.getChildren()) ((File) child).execute(listener);
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
            for (Object key : options.keySet()) {
                if (!OPTIONS.contains(String.valueOf(key))) {
                    throw new IllegalArgumentException("test '" + name + "': unknown option '" + key + "'; known: "
                            + OPTIONS);
                }
            }
            if (!inThisScene(options.get("scene"))) return null;
            UniqueId id = getUniqueId().append("test", name);
            if (getChildren().stream().anyMatch(c -> c.getUniqueId().equals(id))) {
                throw new IllegalArgumentException("test '" + name + "' is registered twice in " + resource);
            }
            addChild(new Test(id, this, name, requires(name, options.get("requires")), body));
            return null;
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

        void execute(EngineExecutionListener listener) {
            listener.executionStarted(this);
            if (loadError != null) {
                listener.executionFinished(this, TestExecutionResult.failed(loadError));
                return;
            }
            for (TestDescriptor child : getChildren()) ((Test) child).execute(listener);
            listener.executionFinished(this, TestExecutionResult.successful());
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

    /** One test a script registered. */
    static final class Test extends AbstractTestDescriptor {
        private final File file;
        private final Requirement requirement;
        private final Scripting.Function body;

        private Test(UniqueId id, File file, String name, Requirement requirement, Scripting.Function body) {
            super(id, name, ClasspathResourceSource.from(file.resource));
            this.file = file;
            this.requirement = requirement;
            this.body = body;
        }

        @Override
        public Type getType() {
            return Type.TEST;
        }

        void execute(EngineExecutionListener listener) {
            if (requirement.any()) {
                List<String> missing = RequiresCondition.missing(requirement.capabilities(), requirement.mods());
                if (!missing.isEmpty()) {
                    // The prefix is how the engine outside tells a requirement no scene met from any other skip.
                    listener.executionSkipped(this, RequiresCondition.REASON_PREFIX + "this game has no "
                            + String.join(", ", missing));
                    return;
                }
            }
            listener.executionStarted(this);
            TestExecutionResult result;
            try {
                body.call(new ScriptContext(GameExtension.game(), file.steps, this, listener).asScript());
                result = TestExecutionResult.successful();
            } catch (Throwable t) {
                // Matched by name: in a Fabric development game the class may come from another loader.
                result = t.getClass().getName().equals("org.opentest4j.TestAbortedException")
                        ? TestExecutionResult.aborted(t) : TestExecutionResult.failed(t);
            }
            listener.executionFinished(this, result);
        }
    }
}
