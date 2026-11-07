package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import net.magicterra.stagewright.testengine.StageWrightTestEngine;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * What each test recorded ({@code t.record}, a Java test's {@code TestReporter}), as {@code <properties>} of its
 * {@code <testcase>} in the JUnit XML the run wrote. JUnit's own XML puts report entries only in a test's
 * {@code <system-out>}, as text; a property is what a tool reading the XML can pick out. A testcase is told apart by
 * the {@code unique-id:} line JUnit writes into its {@code <system-out>}, since a test that runs in two scenes has the
 * same class and name in both.
 *
 * <p>What a scene itself reported, such as a process its games left running, has no element of its own in that XML: it
 * goes into the {@code <properties>} of each {@code <testsuite>} that holds one of the scene's tests.
 */
final class RecordProperties implements TestExecutionListener {

    private static final Pattern UNIQUE_ID = Pattern.compile("(?m)^unique-id: (.+)$");

    private record Property(String name, String value) {}

    private final Map<String, List<Property>> byTest = new ConcurrentHashMap<>();

    /** By a scene's, or another container's, unique id. */
    private final Map<String, List<Property>> byContainer = new ConcurrentHashMap<>();

    @Override
    public void reportingEntryPublished(TestIdentifier id, ReportEntry entry) {
        List<Property> properties =
                (id.isTest() ? byTest : byContainer).computeIfAbsent(id.getUniqueId(), k -> new ArrayList<>());
        synchronized (properties) {
            entry.getKeyValuePairs()
                    .forEach((key, value) -> properties.add(
                            StageWrightTestEngine.BLANK_RECORD.equals(key)
                                    ? new Property(value, "")
                                    : new Property(key, value)));
        }
    }

    /**
     * Split the run's XML in {@code reports} by scene, then add the records to it. In that order: the split copies a
     * suite's properties into every scene's file, which would hand each scene what the others reported.
     */
    void finish(Path reports) throws IOException {
        SceneResults.split(reports);
        writeInto(reports);
    }

    /**
     * Add each test's records to its testcase, and each scene's to the suites holding its tests, in every XML file
     * directly in {@code reports}.
     */
    void writeInto(Path reports) throws IOException {
        if ((byTest.isEmpty() && byContainer.isEmpty()) || !Files.isDirectory(reports)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(reports)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
        }
        for (Path file : files) {
            addTo(file);
        }
    }

    private void addTo(Path file) throws IOException {
        try {
            Document doc =
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
            NodeList cases = doc.getElementsByTagName("testcase");
            boolean changed = false;
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < cases.getLength(); i++) {
                Element testcase = (Element) cases.item(i);
                String id = uniqueId(testcase);
                ids.add(id);
                List<Property> properties = byTest.get(id);
                if (properties == null || properties.isEmpty()) {
                    continue;
                }
                Element list = doc.createElement("properties");
                append(doc, list, properties);
                testcase.insertBefore(list, testcase.getFirstChild());
                changed = true;
            }
            changed |= addSceneRecords(doc, ids);
            if (!changed) {
                return;
            }
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.transform(new DOMSource(doc), new StreamResult(file.toFile()));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot add the recorded values to " + file, e);
        }
    }

    /**
     * Add to the file's suite what each container holding one of its tests, {@code ids}, reported, outermost first;
     * whether anything was added.
     */
    private boolean addSceneRecords(Document doc, List<String> ids) {
        List<Property> found = byContainer.entrySet().stream()
                .filter(e -> ids.stream().anyMatch(id -> id.startsWith(e.getKey() + "/")))
                .sorted(Map.Entry.comparingByKey())
                .flatMap(e -> e.getValue().stream())
                .toList();
        Element suite = doc.getDocumentElement();
        if (found.isEmpty() || !"testsuite".equals(suite.getTagName())) {
            return false;
        }
        Element list = null;
        for (Node child = suite.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e && "properties".equals(e.getTagName())) {
                list = e;
                break;
            }
        }
        if (list == null) {
            list = doc.createElement("properties");
            suite.insertBefore(list, suite.getFirstChild());
        }
        append(doc, list, found);
        return true;
    }

    private static void append(Document doc, Element list, List<Property> properties) {
        for (Property p : properties) {
            Element property = doc.createElement("property");
            property.setAttribute("name", xmlText(p.name()));
            property.setAttribute("value", xmlText(p.value()));
            list.appendChild(property);
        }
    }

    /**
     * {@code text} without the characters XML 1.0 has no place for, each a {@code ?}: a process's command line may
     * carry a control character, and one written into an attribute leaves a file no parser reads.
     */
    static String xmlText(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            boolean allowed = c == 0x9
                    || c == 0xA
                    || c == 0xD
                    || (c >= 0x20 && c <= 0xD7FF)
                    || (c >= 0xE000 && c <= 0xFFFD)
                    || (c >= 0x10000 && c <= 0x10FFFF);
            out.appendCodePoint(allowed ? c : '?');
        });
        return out.toString();
    }

    /** The unique id JUnit wrote into the testcase's {@code <system-out>}, or the empty string. */
    static String uniqueId(Element testcase) {
        for (Node child = testcase.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e && "system-out".equals(e.getTagName())) {
                Matcher m = UNIQUE_ID.matcher(e.getTextContent());
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        }
        return "";
    }
}
