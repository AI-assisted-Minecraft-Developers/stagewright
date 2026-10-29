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
 */
final class RecordProperties implements TestExecutionListener {

    private static final Pattern UNIQUE_ID = Pattern.compile("(?m)^unique-id: (.+)$");

    private record Property(String name, String value) {}

    private final Map<String, List<Property>> byTest = new ConcurrentHashMap<>();

    @Override
    public void reportingEntryPublished(TestIdentifier id, ReportEntry entry) {
        if (!id.isTest()) return;
        List<Property> properties = byTest.computeIfAbsent(id.getUniqueId(), k -> new ArrayList<>());
        synchronized (properties) {
            entry.getKeyValuePairs().forEach((key, value) -> properties.add(
                    key.equals(StageWrightTestEngine.BLANK_RECORD) ? new Property(value, "") : new Property(key, value)));
        }
    }

    /** Add each test's records to its testcase in every XML file directly in {@code reports}. */
    void writeInto(Path reports) throws IOException {
        if (byTest.isEmpty() || !Files.isDirectory(reports)) return;
        List<Path> files;
        try (Stream<Path> list = Files.list(reports)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".xml")).sorted().toList();
        }
        for (Path file : files) addTo(file);
    }

    private void addTo(Path file) throws IOException {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
            NodeList cases = doc.getElementsByTagName("testcase");
            boolean changed = false;
            for (int i = 0; i < cases.getLength(); i++) {
                Element testcase = (Element) cases.item(i);
                List<Property> properties = byTest.get(uniqueId(testcase));
                if (properties == null || properties.isEmpty()) continue;
                Element list = doc.createElement("properties");
                for (Property p : properties) {
                    Element property = doc.createElement("property");
                    property.setAttribute("name", p.name());
                    property.setAttribute("value", p.value());
                    list.appendChild(property);
                }
                testcase.insertBefore(list, testcase.getFirstChild());
                changed = true;
            }
            if (!changed) return;
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.transform(new DOMSource(doc), new StreamResult(file.toFile()));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot add the recorded values to " + file, e);
        }
    }

    /** The unique id JUnit wrote into the testcase's {@code <system-out>}, or the empty string. */
    static String uniqueId(Element testcase) {
        for (Node child = testcase.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e && e.getTagName().equals("system-out")) {
                Matcher m = UNIQUE_ID.matcher(e.getTextContent());
                if (m.find()) return m.group(1).trim();
            }
        }
        return "";
    }
}
