package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Which scene each result came from, on the console and in the JUnit XML.
 *
 * <p>JUnit's XML puts every test of the engine in one {@code <testsuite name="StageWright">}. The engine already names
 * each class and script file {@code <scene>/<class>}, so a testcase's {@code classname} says its scene, as in the files
 * Gradle writes; {@link #split} gives the CLI Gradle's layout too, one {@code TEST-<scene>.xml} per scene whose suite
 * is named for the scene, and leaves the {@code classname} as the engine reported it. A testcase is placed by the scene
 * segment of the unique id in its {@code <system-out>}, which every testcase has, whatever its class source.
 *
 * <p>As a listener it prints a line as each scene ends, with its counts, so a run that passes says what it ran.
 */
final class SceneResults implements TestExecutionListener {

    private static final String SCENE = "scene";

    static final class Counts {
        int passed;
        int failed;
        int skipped;
        long startedAt = System.nanoTime();
    }

    private final Consumer<String> log;
    private final Map<String, Counts> byScene = new ConcurrentHashMap<>();
    private volatile TestPlan plan;

    SceneResults(Consumer<String> log) {
        this.log = log;
    }

    /** The scene {@code uniqueId} lies in, or null for one in none (the engine, a script outside every scene). */
    static String sceneOf(String uniqueId) {
        try {
            for (UniqueId.Segment s : UniqueId.parse(uniqueId).getSegments()) {
                if (s.getType().equals(SCENE)) {
                    return s.getValue();
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static boolean isScene(TestIdentifier id) {
        return UniqueId.parse(id.getUniqueId()).getLastSegment().getType().equals(SCENE);
    }

    private Counts counts(String scene) {
        return byScene.computeIfAbsent(scene, k -> new Counts());
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        plan = testPlan;
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        if (!id.isTest() && isScene(id)) {
            counts(sceneOf(id.getUniqueId())).startedAt = System.nanoTime();
        }
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        String scene = sceneOf(id.getUniqueId());
        if (scene == null) {
            return;
        }
        if (id.isTest()) {
            Counts c = counts(scene);
            synchronized (c) {
                c.skipped++;
            }
        } else if (isScene(id)) {
            long tests = plan == null
                    ? 0
                    : plan.getDescendants(id).stream()
                            .filter(TestIdentifier::isTest)
                            .count();
            log.accept("scene " + scene + " skipped, " + tests + (tests == 1 ? " test: " : " tests: ") + reason);
        }
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        String scene = sceneOf(id.getUniqueId());
        if (scene == null) {
            return;
        }
        Counts c = counts(scene);
        if (id.isTest()) {
            synchronized (c) {
                // An aborted test is one whose assumption failed: JUnit's XML reports it skipped.
                switch (result.getStatus()) {
                    case SUCCESSFUL -> c.passed++;
                    case FAILED -> c.failed++;
                    case ABORTED -> c.skipped++;
                    default -> throw new IllegalStateException("unexpected status " + result.getStatus());
                }
            }
        } else if (isScene(id)) {
            log.accept(summary(scene, c, result));
        }
    }

    static String summary(String scene, Counts c, TestExecutionResult result) {
        boolean passed = result.getStatus() == TestExecutionResult.Status.SUCCESSFUL && c.failed == 0;
        int tests = c.passed + c.failed + c.skipped;
        StringBuilder line = new StringBuilder(128)
                .append("scene ")
                .append(scene)
                .append(passed ? " passed: " : " failed: ")
                .append(tests)
                .append(tests == 1 ? " test, " : " tests, ")
                .append(c.passed)
                .append(" passed");
        if (c.failed > 0) {
            line.append(", ").append(c.failed).append(" failed");
        }
        if (c.skipped > 0) {
            line.append(", ").append(c.skipped).append(" skipped");
        }
        line.append(", in ")
                .append(BigDecimal.valueOf((System.nanoTime() - c.startedAt) / 1e9)
                        .setScale(1, RoundingMode.HALF_UP)
                        .toPlainString())
                .append(" s");
        Optional<Throwable> why = result.getThrowable();
        if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL && why.isPresent()) {
            String message = String.valueOf(why.get().getMessage());
            line.append(" — ").append(message.lines().findFirst().orElse(""));
        }
        return line.toString();
    }

    static Counts countsOf(int passed, int failed, int skipped) {
        Counts c = new Counts();
        c.passed = passed;
        c.failed = failed;
        c.skipped = skipped;
        return c;
    }

    /** Rewrite each XML file directly in {@code reports} whose testcases lie in scenes into one file per scene. */
    static void split(Path reports) throws IOException {
        if (!Files.isDirectory(reports)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(reports)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
        }
        for (Path file : files) {
            splitFile(file);
        }
    }

    private static void splitFile(Path file) throws IOException {
        try {
            Document doc =
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
            Element suite = doc.getDocumentElement();
            if (!suite.getTagName().equals("testsuite")) {
                return;
            }
            Map<String, List<Element>> scenes = new LinkedHashMap<>();
            List<Element> unplaced = new ArrayList<>();
            for (Node child = suite.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element e) || !e.getTagName().equals("testcase")) {
                    continue;
                }
                String scene = sceneOf(RecordProperties.uniqueId(e));
                if (scene == null) {
                    unplaced.add(e);
                } else {
                    scenes.computeIfAbsent(scene, k -> new ArrayList<>()).add(e);
                }
            }
            if (scenes.isEmpty()) {
                return;
            }

            Element suiteProperties = null;
            for (Node child = suite.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element e && e.getTagName().equals("properties")) {
                    suiteProperties = e;
                }
            }
            for (Map.Entry<String, List<Element>> scene : scenes.entrySet()) {
                Document out = DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .newDocument();
                Element root = (Element) out.importNode(suite, false);
                out.appendChild(root);
                root.setAttribute("name", scene.getKey());
                if (suiteProperties != null) {
                    root.appendChild(out.importNode(suiteProperties, true));
                }
                for (Element testcase : scene.getValue()) {
                    root.appendChild(out.importNode(testcase, true));
                }
                count(root, scene.getValue());
                Path target = file.resolveSibling(fileName(scene.getKey(), file));
                write(out, target);
            }
            if (unplaced.isEmpty()) {
                Files.delete(file);
                return;
            }
            for (List<Element> moved : scenes.values()) {
                moved.forEach(suite::removeChild);
            }
            count(suite, unplaced);
            write(doc, file);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot split " + file + " by scene", e);
        }
    }

    /** {@code TEST-<scene>.xml}, kept off the name of the file split, which keeps the tests in no scene. */
    static String fileName(String scene, Path split) {
        String safe = scene.replaceAll("[^A-Za-z0-9._-]", "_");
        String name = "TEST-" + safe + ".xml";
        return name.equals(split.getFileName().toString()) ? "TEST-" + safe + "-scene.xml" : name;
    }

    private static void count(Element suite, List<Element> testcases) {
        int skipped = 0;
        int failures = 0;
        int errors = 0;
        double time = 0;
        for (Element testcase : testcases) {
            if (testcase.getElementsByTagName("skipped").getLength() > 0) {
                skipped++;
            } else if (testcase.getElementsByTagName("failure").getLength() > 0) {
                failures++;
            } else if (testcase.getElementsByTagName("error").getLength() > 0) {
                errors++;
            }
            try {
                time += Double.parseDouble(testcase.getAttribute("time"));
            } catch (NumberFormatException e) {
                // No time: it adds nothing.
            }
        }
        suite.setAttribute("tests", String.valueOf(testcases.size()));
        suite.setAttribute("skipped", String.valueOf(skipped));
        suite.setAttribute("failures", String.valueOf(failures));
        suite.setAttribute("errors", String.valueOf(errors));
        suite.setAttribute("time", seconds(time));
    }

    private static String seconds(double s) {
        return BigDecimal.valueOf(s)
                .setScale(3, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static void write(Document doc, Path file) throws TransformerException {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.transform(new DOMSource(doc), new StreamResult(file.toFile()));
    }
}
