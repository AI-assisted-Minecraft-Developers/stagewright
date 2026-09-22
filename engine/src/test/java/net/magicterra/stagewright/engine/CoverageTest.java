package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The cross-run invariant: every registered scene executed somewhere.
 *
 * <p>Each case here is a shape that {@link Verdict} calls GREEN and should — the point of these
 * tests is that greenness per run is not the same claim as coverage, and only this reconciliation
 * can tell the difference.
 */
class CoverageTest {

    // ---- fixtures ------------------------------------------------------------------------------

    private static Map<String, Object> suite(Map<String, Object>... registered) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("type", "suite");
        rec.put("registered", List.of(registered));
        return rec;
    }

    private static Map<String, Object> reg(String name) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("name", name);
        return rec;
    }

    private static Map<String, Object> reg(String name, String canary) {
        Map<String, Object> rec = reg(name);
        rec.put("canary", canary);
        return rec;
    }

    private static Map<String, Object> ran(String name) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("type", "scene");
        rec.put("name", name);
        rec.put("outcome", "PASS");
        return rec;
    }

    private static Map<String, Object> skipped(String name, String why) {
        Map<String, Object> rec = ran(name);
        rec.put("skipped", true);
        rec.put("reason", "skipped: " + why);
        return rec;
    }

    private static Coverage.Run run(String label, Object... parts) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object part : parts) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rec = (Map<String, Object>) part;
            records.add(rec);
        }
        return new Coverage.Run(label, records);
    }

    private static boolean reports(Coverage.Result result, String fragment) {
        return result.report().stream().anyMatch(line -> line.contains(fragment));
    }

    // ---- the invariant -------------------------------------------------------------------------

    @Test
    void aSceneThatRunsOnOneTopologyIsCovered() {
        // The Twilight Forest shape: thirteen scenes skip on the dedicated server for want of a
        // player and execute in the integrated client. Both runs are GREEN and so is the suite.
        Coverage.Result result = Coverage.judge(List.of(
                run("dedicated", suite(reg("tf.foodFeeds")), skipped("tf.foodFeeds", "no player")),
                run("integrated", suite(reg("tf.foodFeeds")), ran("tf.foodFeeds"))));
        assertEquals(0, result.code());
        assertTrue(reports(result, "COVERAGE: 1 of 1"));
    }

    @Test
    void aSceneThatSkipsEverywhereIsUncovered() {
        // The All the Mods 10 shape, and the reason this class exists: one topology, a scene that
        // needs a player, a run that is honestly green over a subject it has never executed.
        Coverage.Result result = Coverage.judge(List.of(
                run("dedicated", suite(reg("atm.aQuestCanBeClaimed")),
                        skipped("atm.aQuestCanBeClaimed", "no connected player"))));
        assertEquals(1, result.code());
        assertTrue(reports(result, "UNCOVERED: 'atm.aQuestCanBeClaimed'"));
        assertTrue(reports(result, "no connected player"));
    }

    @Test
    void theReportNamesWhatEachRunSaidInstead() {
        // Without the per-run lines, "never executed" sends the reader back to two results files to
        // find out why — and the two runs can have skipped it for entirely different reasons.
        Coverage.Result result = Coverage.judge(List.of(
                run("dedicated", suite(reg("x")), skipped("x", "no connected player")),
                run("integrated", suite(reg("x")), skipped("x", "Curios is not in this runtime"))));
        assertEquals(1, result.code());
        assertTrue(reports(result, "dedicated: skipped: no connected player"));
        assertTrue(reports(result, "integrated: skipped: Curios is not in this runtime"));
    }

    @Test
    void aMustSkipSceneIsNotAHole() {
        // Its subject IS the skip, declared at the scene by the author who knows why. Verdict
        // separately requires it to actually skip, which is a stronger claim than this check makes.
        Coverage.Result result = Coverage.judge(List.of(
                run("dedicated", suite(reg("cap.absent", "MUST_SKIP")),
                        skipped("cap.absent", "nothing offers it"))));
        assertEquals(0, result.code());
        assertTrue(reports(result, "1 declared must-skip"));
    }

    @Test
    void canariesAreNotCounted() {
        // The swallow canary is registered and deliberately never recorded — being uncovered is its
        // passing condition, and Verdict already judges it.
        Coverage.Result result = Coverage.judge(List.of(
                run("dedicated", suite(reg("cs", "MUST_SWALLOW"), reg("a")), ran("a"))));
        assertEquals(0, result.code());
    }

    @Test
    void aSceneWithNoRecordAtAllIsAlsoUncovered() {
        // Verdict calls this SWALLOWED per run. Repeating it here is deliberate: a scene that
        // vanished from every run is a coverage hole by any reading, and staying silent about it
        // would make the two checks disagree about the same file.
        Coverage.Result result = Coverage.judge(List.of(
                run("dedicated", suite(reg("a"), reg("b")), ran("a"))));
        assertEquals(1, result.code());
        assertTrue(reports(result, "UNCOVERED: 'b'"));
        assertTrue(reports(result, "no record"));
    }

    @Test
    void aRunThatNeverArmedContributesNothingAndSaysSo() {
        Coverage.Result result = Coverage.judge(List.of(
                run("dead", ran("a")),
                run("live", suite(reg("a")), ran("a"))));
        assertEquals(0, result.code());
        assertTrue(reports(result, "no suite header"));
    }

    @Test
    void aFilteredRunIsNotACoverageClaim() {
        // Its registered list is whatever the pattern kept, so "N of N executed" over it would be a
        // statement about a subset dressed as one about the suite.
        Map<String, Object> narrowed = suite(reg("wd.a"));
        narrowed.put("filter", "wd.a");
        Coverage.Result result = Coverage.judge(List.of(
                run("dedicated", narrowed, ran("wd.a")),
                run("integrated", suite(reg("wd.a"), reg("wd.b")), ran("wd.a"), ran("wd.b"))));
        assertEquals(3, result.code());
        assertTrue(reports(result, "'dedicated' was FILTERED to 'wd.a'"));
        assertFalse(reports(result, "COVERAGE: "));
    }

    @Test
    void anEnvFailIsNotAnExecution() {
        // ENV_FAIL is written from PREP, before a body exists: nothing about the scene was tested.
        Map<String, Object> envFail = ran("a");
        envFail.put("outcome", "ENV_FAIL");
        envFail.put("reason", "the arena never became usable");
        Coverage.Result result = Coverage.judge(List.of(
                run("dedicated", suite(reg("a")), envFail)));
        assertEquals(1, result.code());
        assertTrue(reports(result, "UNCOVERED: 'a'"));
        assertTrue(reports(result, "ENV_FAIL"));
        assertTrue(reports(result, "the arena never became usable"));
    }

    @Test
    void aFailedSceneStillExecuted() {
        Map<String, Object> failed = ran("a");
        failed.put("outcome", "FAIL");
        assertEquals(0, Coverage.judge(List.of(run("dedicated", suite(reg("a")), failed))).code());
    }

    @Test
    void coverageOverNoRunsIsNotAPass() {
        assertEquals(1, Coverage.judge(List.of()).code());
    }
}
