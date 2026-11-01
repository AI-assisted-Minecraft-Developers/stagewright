package net.magicterra.stagewright.testengine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.ClasspathResourceSelector;
import org.junit.platform.engine.discovery.ClasspathRootSelector;
import org.junit.platform.engine.discovery.MethodSelector;
import org.junit.platform.engine.discovery.UniqueIdSelector;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

/**
 * The outer engine, id {@value #ID}. Finds every class that declares a {@code @Scene} without loading it, and every
 * script test without the game ({@link ScriptScan}), groups the tests by scene, and runs each scene in a game of its
 * own, reporting the game's results as this engine's. See {@code docs/design/test-engine.md}.
 */
public final class StageWrightTestEngine implements TestEngine {

    public static final String ID = "stagewright";

    /** The suite's test roots, path-separated: where the script tests under {@value ScriptScan#DIRECTORY} are. */
    public static final String TEST_ROOTS = "stagewright.testRoots";

    /**
     * The key of the report entry that stands for a value a test recorded blank, which a report entry cannot hold: its
     * value is the name the test recorded under, and the recorded value is the empty string.
     */
    public static final String BLANK_RECORD = "stagewright.blank";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Optional<String> getGroupId() {
        return Optional.of("net.magicterra");
    }

    @Override
    public Optional<String> getArtifactId() {
        return Optional.of("stagewright-testengine");
    }

    // Discovery reads as one pass over the selectors and the classes they name; splitting it per selector kind
    // would spread the single ordering of scenes across helpers.
    @Override
    @SuppressWarnings("checkstyle:javancss")
    public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
        // What was asked for: whole classes, or particular methods of a class, optionally in one scene only.
        Map<String, Predicate<ClassScan.Method>> wanted = new LinkedHashMap<>();
        for (ClassSelector s : request.getSelectorsByType(ClassSelector.class)) {
            wanted.put(s.getClassName(), m -> true);
        }
        for (ClasspathRootSelector s : request.getSelectorsByType(ClasspathRootSelector.class)) {
            for (String name : classesUnder(Path.of(s.getClasspathRoot()))) {
                wanted.put(name, m -> true);
            }
        }
        for (MethodSelector s : request.getSelectorsByType(MethodSelector.class)) {
            String name = s.getMethodName();
            String parameters = s.getParameterTypeNames();
            Predicate<ClassScan.Method> one = m -> m.name().equals(name)
                    && (parameters.isEmpty() || m.parameters().equals(parameters));
            wanted.merge(s.getClassName(), one, Predicate::or);
        }
        // Scripts: every file under the suite's test roots when the request is for whole classes, as a Gradle Test
        // task's is, and whatever files and tests are named directly.
        Map<String, Predicate<String>> wantedScripts = new LinkedHashMap<>();
        if (!request.getSelectorsByType(ClassSelector.class).isEmpty()) {
            for (String root : System.getProperty(TEST_ROOTS, "").split(java.io.File.pathSeparator)) {
                if (root.isBlank()) {
                    continue;
                }
                for (String script : ScriptScan.scriptsUnder(Path.of(root))) {
                    wantedScripts.put(script, t -> true);
                }
            }
        }
        for (ClasspathRootSelector s : request.getSelectorsByType(ClasspathRootSelector.class)) {
            for (String script : ScriptScan.scriptsUnder(Path.of(s.getClasspathRoot()))) {
                wantedScripts.put(script, t -> true);
            }
        }
        for (ClasspathResourceSelector s : request.getSelectorsByType(ClasspathResourceSelector.class)) {
            if (ScriptScan.isScript(s.getClasspathResourceName())) {
                wantedScripts.put(s.getClasspathResourceName(), t -> true);
            }
        }
        Map<String, Set<String>> scenesOf = new LinkedHashMap<>();
        for (UniqueIdSelector s : request.getSelectorsByType(UniqueIdSelector.class)) {
            selectById(s.getUniqueId(), uniqueId, wanted, scenesOf, wantedScripts);
        }

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Map<String, Nodes.Scene> scenes = new LinkedHashMap<>();
        for (Map.Entry<String, Predicate<ClassScan.Method>> entry : wanted.entrySet()) {
            Optional<ClassScan.Scanned> scanned;
            try {
                scanned = ClassScan.scan(entry.getKey(), loader);
            } catch (IOException e) {
                throw new UncheckedIOException("could not read " + entry.getKey(), e);
            }
            if (scanned.isEmpty()) {
                continue;
            }
            for (String sceneName : scanned.get().scenes()) {
                Set<String> only = scenesOf.get(entry.getKey());
                if (only != null && !only.contains(sceneName)) {
                    continue;
                }
                Nodes.Scene scene = scenes.computeIfAbsent(sceneName, n -> new Nodes.Scene(uniqueId, n));
                Nodes.TestClass testClass = new Nodes.TestClass(scene, scanned.get());
                for (ClassScan.Method m : scanned.get().methods()) {
                    if (entry.getValue().test(m)) {
                        testClass.addChild(new Nodes.TestMethod(
                                testClass.getUniqueId(), scanned.get().className(), m));
                    }
                }
                if (!testClass.getChildren().isEmpty()) {
                    scene.addChild(testClass);
                }
            }
        }
        EngineDescriptor engine = new EngineDescriptor(uniqueId, "StageWright");
        for (Map.Entry<String, Predicate<String>> entry : wantedScripts.entrySet()) {
            ScriptScan.Scanned scanned = ScriptScan.scan(entry.getKey(), loader);
            if (scanned.error() != null) {
                engine.addChild(new Nodes.Unreadable(uniqueId, entry.getKey(), scanned.error()));
                continue;
            }
            for (ScriptScan.Test test : scanned.tests()) {
                if (!entry.getValue().test(test.name())) {
                    continue;
                }
                for (String sceneName : test.scenes()) {
                    Set<String> only = scenesOf.get(entry.getKey());
                    if (only != null && !only.contains(sceneName)) {
                        continue;
                    }
                    Nodes.Scene scene = scenes.computeIfAbsent(sceneName, n -> new Nodes.Scene(uniqueId, n));
                    Nodes.ScriptFile file = scene.getChildren().stream()
                            .filter(c -> c instanceof Nodes.ScriptFile f && f.resource.equals(entry.getKey()))
                            .map(Nodes.ScriptFile.class::cast)
                            .findFirst()
                            .orElse(null);
                    if (file == null) {
                        file = new Nodes.ScriptFile(scene, entry.getKey());
                        scene.addChild(file);
                    }
                    file.addChild(new Nodes.ScriptTest(file.getUniqueId(), entry.getKey(), test.name()));
                }
            }
        }
        scenes.values().stream().filter(s -> !s.getChildren().isEmpty()).forEach(engine::addChild);
        return engine;
    }

    @Override
    public void execute(ExecutionRequest request) {
        TestDescriptor engine = request.getRootTestDescriptor();
        request.getEngineExecutionListener().executionStarted(engine);
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Coverage coverage = new Coverage();
        List<SceneScheduler.Job> scenes = new ArrayList<>();
        for (TestDescriptor child : new ArrayList<>(engine.getChildren())) {
            if (child instanceof Nodes.Scene scene) {
                scenes.add(new SceneScheduler.Job(
                        SceneRun.needs(scene.name, loader),
                        () -> new SceneRun(scene, request.getEngineExecutionListener(), coverage).run(loader)));
            } else if (child instanceof Nodes.Unreadable unreadable) {
                request.getEngineExecutionListener().executionStarted(unreadable);
                request.getEngineExecutionListener()
                        .executionFinished(
                                unreadable, TestExecutionResult.failed(new AssertionError(unreadable.error)));
            }
        }
        try {
            SceneScheduler.from(request.getConfigurationParameters()).run(scenes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            request.getEngineExecutionListener().executionFinished(engine, TestExecutionResult.aborted(e));
            return;
        } catch (RuntimeException e) {
            request.getEngineExecutionListener().executionFinished(engine, TestExecutionResult.failed(e));
            return;
        }
        List<String> neverRan = coverage.neverRan();
        request.getEngineExecutionListener()
                .executionFinished(
                        engine,
                        neverRan.isEmpty()
                                ? TestExecutionResult.successful()
                                : TestExecutionResult.failed(
                                        new AssertionError(neverRan.size() + " test(s) ran in no scene, every"
                                                + " scene they name lacking what their @Requires asks for:\n  "
                                                + String.join("\n  ", neverRan))));
    }

    /** A re-run of one node: its class (and method) or script (and test) are selected, in the scene its id names. */
    private static void selectById(
            UniqueId id,
            UniqueId engineId,
            Map<String, Predicate<ClassScan.Method>> wanted,
            Map<String, Set<String>> scenesOf,
            Map<String, Predicate<String>> wantedScripts) {
        if (!id.hasPrefix(engineId)) {
            return;
        }
        String scene = null;
        String className = null;
        String methodKey = null;
        String script = null;
        String testName = null;
        for (UniqueId.Segment segment : id.getSegments()) {
            switch (segment.getType()) {
                case "scene" -> scene = segment.getValue();
                case "class" -> className = segment.getValue();
                case "method" -> methodKey = segment.getValue();
                case "script" -> script = segment.getValue();
                case "test" -> testName = segment.getValue();
                default -> {}
            }
        }
        if (script != null) {
            if (scene != null) {
                scenesOf.computeIfAbsent(script, c -> new LinkedHashSet<>()).add(scene);
            }
            String name = testName;
            wantedScripts.merge(script, name == null ? t -> true : t -> t.equals(name), Predicate::or);
            return;
        }
        if (className == null) {
            return;
        }
        if (scene != null) {
            scenesOf.computeIfAbsent(className, c -> new LinkedHashSet<>()).add(scene);
        }
        String key = methodKey;
        wanted.merge(className, key == null ? m -> true : m -> m.key().equals(key), Predicate::or);
    }

    private static List<String> classesUnder(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".class")
                            && !p.getFileName().toString().contains("$"))
                    .map(p -> root.relativize(p)
                            .toString()
                            .replace(root.getFileSystem().getSeparator(), ".")
                            .replaceAll("\\.class$", ""))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
