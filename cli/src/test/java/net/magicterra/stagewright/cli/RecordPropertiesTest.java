package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.magicterra.stagewright.testengine.StageWrightTestEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.launcher.TestIdentifier;

/** What tests recorded, as properties of their testcases in the JUnit XML the CLI writes. */
class RecordPropertiesTest {

    private static TestIdentifier test(String scene) {
        UniqueId id = UniqueId.forEngine("stagewright")
                .append("scene", scene)
                .append("script", "stagewright/tests/crops.js")
                .append("test", "cropDrying");
        return TestIdentifier.from(new AbstractTestDescriptor(id, "cropDrying") {
            @Override
            public Type getType() {
                return Type.TEST;
            }
        });
    }

    private static String testcase(TestIdentifier id) {
        return "<testcase name=\"cropDrying\" classname=\"stagewright/tests/crops.js\" time=\"1\">\n"
                + "<system-out><![CDATA[\nunique-id: " + id.getUniqueId()
                + "\ndisplay-name: cropDrying\n]]></system-out>\n"
                + "</testcase>\n";
    }

    @Test
    void eachTestcaseGetsItsOwnTestsRecordsABlankOneAsTheEmptyString(@TempDir Path reports) throws IOException {
        TestIdentifier inMms = test("mms");
        TestIdentifier inClient = test("mms-client");
        Path xml = reports.resolve("TEST-stagewright.xml");
        Files.writeString(
                xml,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"StageWright\" tests=\"2\">\n"
                        + testcase(inMms) + testcase(inClient) + "</testsuite>\n");

        RecordProperties records = new RecordProperties();
        records.reportingEntryPublished(inMms, ReportEntry.from("dried", "3 of 4"));
        records.reportingEntryPublished(inMms, ReportEntry.from(StageWrightTestEngine.BLANK_RECORD, "none found"));
        records.writeInto(reports);

        String text = Files.readString(xml);
        int mms = text.indexOf("[scene:mms]");
        int client = text.indexOf("[scene:mms-client]");
        String first = text.substring(0, client);
        assertTrue(first.contains("<property name=\"dried\" value=\"3 of 4\"/>"), text);
        assertTrue(first.contains("<property name=\"none found\" value=\"\"/>"), text);
        assertTrue(first.indexOf("<properties>") < mms, "the properties come first in the testcase: " + text);
        assertFalse(
                text.substring(client).contains("<property "), "the other scene's testcase recorded nothing: " + text);
        assertEquals(1, text.split("<properties>", -1).length - 1, text);
    }
}
