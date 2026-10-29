package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** The scene each result came from, in the XML the CLI writes and on its console. */
class SceneResultsTest {

    private static final UniqueId ENGINE = UniqueId.forEngine("stagewright");

    private static UniqueId test(String scene, String name) {
        return ENGINE.append("scene", scene).append("script", "stagewright/tests/crops.js").append("test", name);
    }

    private static String testcase(UniqueId id, String name, String time, String outcome) {
        // As the engine names it: <scene>/<file>, or the file alone for a script in no scene.
        String script = id.getSegments().get(id.getSegments().size() - 2).getValue();
        String scene = SceneResults.sceneOf(id.toString());
        String classname = scene == null ? script : scene + "/" + script;
        return "<testcase name=\"" + name + "\" classname=\"" + classname + "\" time=\"" + time + "\">\n"
                + outcome + "<system-out><![CDATA[\nunique-id: " + id + "\ndisplay-name: " + name + "\n]]></system-out>\n"
                + "</testcase>\n";
    }

    private static Element suiteOf(Path file) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    }

    @Test
    void theSceneIsReadFromTheUniqueId() {
        assertEquals("mms-client", SceneResults.sceneOf(test("mms-client", "a").toString()));
        assertNull(SceneResults.sceneOf(ENGINE.append("script", "stagewright/tests/x.js").toString()));
        assertNull(SceneResults.sceneOf(""));
    }

    @Test
    void eachSceneGetsASuiteOfItsOwnAndTheTestsInNoneStayBehind(@TempDir Path reports) throws Exception {
        Path xml = reports.resolve("TEST-stagewright.xml");
        Files.writeString(xml, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite name=\"StageWright\" tests=\"5\" skipped=\"2\" failures=\"1\" errors=\"0\" time=\"9\""
                + " hostname=\"h\">\n<properties><property name=\"os.name\" value=\"Linux\"/></properties>\n"
                + testcase(test("mms", "cropDrying"), "cropDrying", "1.5", "")
                + testcase(test("mms", "cropGrowing"), "cropGrowing", "2.25", "<failure message=\"no\">x</failure>")
                + testcase(test("mms-client", "cropDrying"), "cropDrying", "0", "<skipped><![CDATA[no --client]]></skipped>")
                + testcase(test("mms-client", "cropGrowing"), "cropGrowing", "0", "<skipped/>")
                + testcase(ENGINE.append("script", "stagewright/tests/lost.js").append("test", "lost"), "lost", "0.1",
                        "<failure message=\"ran in no scene\"/>")
                + "<system-out><![CDATA[\nunique-id: [engine:stagewright]\n]]></system-out>\n"
                + "</testsuite>\n");

        SceneResults.split(reports);

        Element mms = suiteOf(reports.resolve("TEST-mms.xml"));
        assertEquals("mms", mms.getAttribute("name"));
        assertEquals("2", mms.getAttribute("tests"));
        assertEquals("1", mms.getAttribute("failures"));
        assertEquals("0", mms.getAttribute("skipped"));
        assertEquals("3.75", mms.getAttribute("time"));
        assertEquals("h", mms.getAttribute("hostname"));
        assertEquals(1, mms.getElementsByTagName("property").getLength(), "the run's properties come along");
        NodeList cases = mms.getElementsByTagName("testcase");
        assertEquals(2, cases.getLength());
        assertEquals("mms/stagewright/tests/crops.js", ((Element) cases.item(0)).getAttribute("classname"),
                "the classname is left as the engine reported it");
        assertEquals("cropDrying", ((Element) cases.item(0)).getAttribute("name"));

        Element client = suiteOf(reports.resolve("TEST-mms-client.xml"));
        assertEquals("mms-client", client.getAttribute("name"));
        assertEquals("2", client.getAttribute("tests"));
        assertEquals("2", client.getAttribute("skipped"));
        assertTrue(Files.readString(reports.resolve("TEST-mms-client.xml")).contains("no --client"));

        Element rest = suiteOf(xml);
        assertEquals("StageWright", rest.getAttribute("name"));
        assertEquals("1", rest.getAttribute("tests"));
        assertEquals("1", rest.getAttribute("failures"));
        assertEquals("stagewright/tests/lost.js",
                ((Element) rest.getElementsByTagName("testcase").item(0)).getAttribute("classname"));
    }

    @Test
    void aFileWhoseTestsAllLieInScenesIsReplacedByTheirs(@TempDir Path reports) throws Exception {
        Path xml = reports.resolve("TEST-stagewright.xml");
        Files.writeString(xml, "<testsuite name=\"StageWright\" tests=\"1\">\n"
                + testcase(test("stagewright", "a"), "a", "1", "") + "</testsuite>\n");
        SceneResults.split(reports);
        assertFalse(Files.exists(xml));
        assertEquals("stagewright", suiteOf(reports.resolve("TEST-stagewright-scene.xml")).getAttribute("name"));
    }

    @Test
    void aFileWithNoSceneIsLeftAlone(@TempDir Path reports) throws IOException {
        Path xml = reports.resolve("TEST-other.xml");
        String text = "<testsuite name=\"x\" tests=\"1\"><testcase name=\"a\" classname=\"b\"/></testsuite>";
        Files.writeString(xml, text);
        SceneResults.split(reports);
        assertEquals(text, Files.readString(xml));
    }

    private static TestDescriptor node(UniqueId id, boolean test) {
        return new AbstractTestDescriptor(id, id.getLastSegment().getValue()) {
            @Override
            public Type getType() {
                return test ? Type.TEST : Type.CONTAINER;
            }
        };
    }

    @SuppressWarnings("deprecation")
    private static TestPlan plan(TestDescriptor engine) {
        return TestPlan.from(List.of(engine), new ConfigurationParameters() {
            @Override
            public Optional<String> get(String key) {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> getBoolean(String key) {
                return Optional.empty();
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public Set<String> keySet() {
                return Set.of();
            }
        });
    }

    @Test
    void eachSceneSaysOnTheConsoleHowItWent() {
        EngineDescriptor engine = new EngineDescriptor(ENGINE, "StageWright");
        TestDescriptor mms = node(ENGINE.append("scene", "mms"), false);
        TestDescriptor client = node(ENGINE.append("scene", "mms-client"), false);
        engine.addChild(mms);
        engine.addChild(client);
        List<TestDescriptor> tests = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            TestDescriptor t = node(test("mms", name), true);
            mms.addChild(t);
            tests.add(t);
        }
        client.addChild(node(test("mms-client", "a"), true));
        client.addChild(node(test("mms-client", "b"), true));

        List<String> lines = new ArrayList<>();
        SceneResults results = new SceneResults(lines::add);
        results.testPlanExecutionStarted(plan(engine));
        results.executionStarted(TestIdentifier.from(mms));
        results.executionFinished(TestIdentifier.from(tests.get(0)), TestExecutionResult.successful());
        results.executionFinished(TestIdentifier.from(tests.get(1)), TestExecutionResult.successful());
        results.executionSkipped(TestIdentifier.from(tests.get(2)), "needs a client");
        results.executionFinished(TestIdentifier.from(mms), TestExecutionResult.successful());
        results.executionSkipped(TestIdentifier.from(client), "scene 'mms-client' was not run: no --client");

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("scene mms passed: 3 tests, 2 passed, 1 skipped, in "), lines.get(0));
        assertEquals("scene mms-client skipped, 2 tests: scene 'mms-client' was not run: no --client", lines.get(1));
    }

    @Test
    void aFailedSceneSaysWhatFailedFirst() {
        String line = SceneResults.summary("mms", SceneResults.countsOf(1, 2, 0),
                TestExecutionResult.failed(new IllegalStateException("the game crashed\nat line 2")));
        assertTrue(line.startsWith("scene mms failed: 3 tests, 1 passed, 2 failed, in "), line);
        assertTrue(line.endsWith(" s — the game crashed"), line);
        assertTrue(SceneResults.summary("mms", SceneResults.countsOf(1, 1, 0), TestExecutionResult.successful())
                .startsWith("scene mms failed: 2 tests, 1 passed, 1 failed"), "a failed test fails its scene");
    }
}
