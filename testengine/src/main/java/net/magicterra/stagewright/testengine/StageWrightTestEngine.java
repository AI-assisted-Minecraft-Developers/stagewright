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
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.ClasspathRootSelector;
import org.junit.platform.engine.discovery.MethodSelector;
import org.junit.platform.engine.discovery.UniqueIdSelector;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

/**
 * The outer engine, id {@value #ID}. Finds every class that declares a {@code @Scene} without loading it, groups the
 * tests by scene, and runs each scene in a game of its own, reporting the game's results as this engine's. See
 * {@code docs/design/test-engine.md}.
 */
public final class StageWrightTestEngine implements TestEngine {

    public static final String ID = "stagewright";

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

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
        EngineDescriptor engine = new EngineDescriptor(uniqueId, "StageWright");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        // What was asked for: whole classes, or particular methods of a class, optionally in one scene only.
        Map<String, Predicate<ClassScan.Method>> wanted = new LinkedHashMap<>();
        Map<String, Set<String>> scenesOf = new LinkedHashMap<>();
        for (ClassSelector s : request.getSelectorsByType(ClassSelector.class)) wanted.put(s.getClassName(), m -> true);
        for (ClasspathRootSelector s : request.getSelectorsByType(ClasspathRootSelector.class)) {
            for (String name : classesUnder(Path.of(s.getClasspathRoot()))) wanted.put(name, m -> true);
        }
        for (MethodSelector s : request.getSelectorsByType(MethodSelector.class)) {
            String name = s.getMethodName();
            String parameters = s.getParameterTypeNames();
            Predicate<ClassScan.Method> one = m -> m.name().equals(name)
                    && (parameters.isEmpty() || m.parameters().equals(parameters));
            wanted.merge(s.getClassName(), one, Predicate::or);
        }
        for (UniqueIdSelector s : request.getSelectorsByType(UniqueIdSelector.class)) {
            selectById(s.getUniqueId(), uniqueId, wanted, scenesOf);
        }

        Map<String, Nodes.Scene> scenes = new LinkedHashMap<>();
        for (Map.Entry<String, Predicate<ClassScan.Method>> entry : wanted.entrySet()) {
            Optional<ClassScan.Scanned> scanned;
            try {
                scanned = ClassScan.scan(entry.getKey(), loader);
            } catch (IOException e) {
                throw new UncheckedIOException("could not read " + entry.getKey(), e);
            }
            if (scanned.isEmpty()) continue;
            for (String sceneName : scanned.get().scenes()) {
                Set<String> only = scenesOf.get(entry.getKey());
                if (only != null && !only.contains(sceneName)) continue;
                Nodes.Scene scene = scenes.computeIfAbsent(sceneName, n -> new Nodes.Scene(uniqueId, n));
                Nodes.TestClass testClass = new Nodes.TestClass(scene.getUniqueId(), scanned.get());
                for (ClassScan.Method m : scanned.get().methods()) {
                    if (entry.getValue().test(m)) {
                        testClass.addChild(new Nodes.TestMethod(testClass.getUniqueId(), scanned.get().className(), m));
                    }
                }
                if (!testClass.getChildren().isEmpty()) scene.addChild(testClass);
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
        for (TestDescriptor scene : new ArrayList<>(engine.getChildren())) {
            new SceneRun((Nodes.Scene) scene, request.getEngineExecutionListener()).run(loader);
        }
        request.getEngineExecutionListener().executionFinished(engine,
                org.junit.platform.engine.TestExecutionResult.successful());
    }

    /** A re-run of one node: its class (and method) are selected, in the scene its id names. */
    private static void selectById(UniqueId id, UniqueId engineId, Map<String, Predicate<ClassScan.Method>> wanted,
                                   Map<String, Set<String>> scenesOf) {
        if (!id.hasPrefix(engineId)) return;
        String scene = null;
        String className = null;
        String methodKey = null;
        for (UniqueId.Segment segment : id.getSegments()) {
            switch (segment.getType()) {
                case "scene" -> scene = segment.getValue();
                case "class" -> className = segment.getValue();
                case "method" -> methodKey = segment.getValue();
                default -> { }
            }
        }
        if (className == null) return;
        if (scene != null) scenesOf.computeIfAbsent(className, c -> new LinkedHashSet<>()).add(scene);
        String key = methodKey;
        wanted.merge(className, key == null ? m -> true : m -> m.key().equals(key), Predicate::or);
    }

    private static List<String> classesUnder(Path root) {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".class") && !p.getFileName().toString().contains("$"))
                    .map(p -> root.relativize(p).toString().replace(root.getFileSystem().getSeparator(), ".")
                            .replaceAll("\\.class$", ""))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
