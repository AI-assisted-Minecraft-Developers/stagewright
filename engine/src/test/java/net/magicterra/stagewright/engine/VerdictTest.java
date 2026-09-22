package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The verdict rules, exercised against hand-built results.
 *
 * <p>This is the one piece of the plugin that decides whether a run passed, and every consumer —
 * five repositories at the time of writing — trusts its exit code without reading the log. Its
 * failure mode is not a crash: it is a GREEN that should have been RED, which nobody investigates.
 *
 * <p>Unit-level rather than through TestKit on purpose. Judging is a pure function of a parsed
 * results file, so a booted Gradle would add minutes per case and test nothing extra — and the cases
 * that matter here are the malformed ones a real run produces only when something is already broken.
 */
class VerdictTest {

    // ---- fixtures ------------------------------------------------------------------------------

    private static Map<String, Object> suite(Map<String, Object>... registered) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("type", "suite");
        rec.put("loader", "neoforge");
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

    private static Map<String, Object> optional(String name) {
        Map<String, Object> rec = reg(name);
        rec.put("required", Boolean.FALSE);
        return rec;
    }

    private static Map<String, Object> scene(String name, String outcome) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("type", "scene");
        rec.put("name", name);
        rec.put("outcome", outcome);
        rec.put("ticks", 1);
        rec.put("wallMs", 10);
        return rec;
    }

    /** A scene that resolved without testing its subject. PASS, like the writers produce. */
    private static Map<String, Object> skipped(String name, String why) {
        Map<String, Object> rec = scene(name, "PASS");
        rec.put("skipped", true);
        rec.put("reason", "skipped: " + why);
        return rec;
    }

    /** The same, written by a harness predating the {@code skipped} field — the prose prefix only. */
    private static Map<String, Object> legacySkip(String name, String why) {
        Map<String, Object> rec = scene(name, "PASS");
        rec.put("reason", "skipped: " + why);
        return rec;
    }

    private static Map<String, Object> done(int scenes) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("type", "done");
        rec.put("scenes", scenes);
        return rec;
    }

    private static List<Map<String, Object>> records(Object... parts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object part : parts) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rec = (Map<String, Object>) part;
            out.add(rec);
        }
        return out;
    }

    private static boolean reports(Verdict.Result result, String fragment) {
        return result.report().stream().anyMatch(line -> line.contains(fragment));
    }

    // ---- the four exit codes -------------------------------------------------------------------

    @Test
    void aCleanRunIsGreen() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("a"), reg("b")), scene("a", "PASS"), scene("b", "PASS"), done(2)),
                null);
        assertEquals(0, result.code());
        assertEquals("GREEN", result.label());
    }

    @Test
    void aFailedSceneIsRed() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("a")), scene("a", "FAIL"), done(1)), null);
        assertEquals(1, result.code());
        assertTrue(reports(result, "FAIL: 'a'"));
    }

    @Test
    void noSuiteHeaderIsEnvNotRed() {
        // "The game never started" must never read as "your code is broken" — a build that reports
        // RED for a missing JVM sends everyone to read a diff that is fine.
        Verdict.Result result = Verdict.judge(records(scene("a", "PASS"), done(1)), null);
        assertEquals(3, result.code());
        assertEquals("ENV", result.label());
    }

    @Test
    void aMissingFooterIsRedBecauseTheRunIsIncomplete() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("a")), scene("a", "PASS")), null);
        assertEquals(1, result.code());
        assertTrue(reports(result, "no done footer"));
    }

    // ---- canaries: the framework testing itself ------------------------------------------------

    @Test
    void aCanaryLandingOnItsDeclaredOutcomeIsNotAFailure() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("cf", "MUST_FAIL"), reg("ct", "MUST_TIMEOUT")),
                        scene("cf", "FAIL"), scene("ct", "TIMEOUT"), done(2)),
                null);
        assertEquals(0, result.code());
    }

    @Test
    void aCanaryThatPassesIsDeadNotRed() {
        // The distinction is the whole point of the code: RED says the product is broken, DEAD says
        // the measurement is — and a run whose measurement is broken has no usable result at all,
        // including the parts that looked fine.
        Verdict.Result result = Verdict.judge(
                records(suite(reg("cf", "MUST_FAIL")), scene("cf", "PASS"), done(1)), null);
        assertEquals(2, result.code());
        assertEquals("DEAD", result.label());
    }

    @Test
    void aMissingCanaryIsDeadBecauseTheCatchGateNeverRan() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("ct", "MUST_TIMEOUT")), done(0)), null);
        assertEquals(2, result.code());
    }

    @Test
    void anExecutedSwallowCanaryIsDead() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("cs", "MUST_SWALLOW")), scene("cs", "PASS"), done(1)), null);
        assertEquals(2, result.code());
        assertTrue(reports(result, "skip gate is broken"));
    }

    @Test
    void anOmittedSwallowCanaryIsGreen() {
        Verdict.Result result = Verdict.judge(records(suite(reg("cs", "MUST_SWALLOW")), done(0)), null);
        assertEquals(0, result.code());
    }

    // ---- skips: green, but never evidence ------------------------------------------------------

    @Test
    void aSkipIsGreenButIsNotReportedAsAPass() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("a"), reg("b")), scene("a", "PASS"),
                        skipped("b", "no connected player"), done(2)), null);
        assertEquals(0, result.code());
        assertTrue(reports(result, "skip: 'b'"));
        assertFalse(reports(result, "pass: 'b'"));
        assertTrue(reports(result, "COVERAGE: 1 scene(s) executed, 1 skipped"));
    }

    @Test
    void aSkipFromBeforeTheFieldExistedIsStillASkip() {
        // The results files on disk when the field was added carry only the reason prefix. Reading
        // just the field would report every skip in them as a scene that ran, and the direction that
        // error moves in is toward a green that claims coverage it never had.
        Verdict.Result result = Verdict.judge(
                records(suite(reg("b")), legacySkip("b", "no connected player"), done(1)), null);
        assertEquals(0, result.code());
        assertTrue(reports(result, "skip: 'b'"));
    }

    @Test
    void aRunWithNoSkipsSaysNothingAboutCoverage() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("a")), scene("a", "PASS"), done(1)), null);
        assertEquals(0, result.code());
        assertFalse(reports(result, "COVERAGE"));
    }

    @Test
    void aMustSkipSceneThatSkipsIsGreen() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("cap.absent", "MUST_SKIP")),
                        skipped("cap.absent", "nothing offers it"), done(1)), null);
        assertEquals(0, result.code());
        assertTrue(reports(result, "correctly skipped"));
    }

    @Test
    void aMustSkipSceneThatExecutesIsDeadNotRed() {
        // Not RED: what broke is the absence detection every other suite's skips are trusted through,
        // so this run's greens stop being evidence — the MUST_SWALLOW argument exactly.
        Verdict.Result result = Verdict.judge(
                records(suite(reg("cap.absent", "MUST_SKIP")), scene("cap.absent", "PASS"), done(1)),
                null);
        assertEquals(2, result.code());
        assertEquals("DEAD", result.label());
    }

    @Test
    void aMustSkipSceneWithNoRecordIsRed() {
        // A skip is a RECORD. No record means the scene never reached the harness at all, which is
        // the ordinary SWALLOWED hole and not evidence that the skip gate broke.
        Verdict.Result result = Verdict.judge(
                records(suite(reg("cap.absent", "MUST_SKIP")), done(0)), null);
        assertEquals(1, result.code());
        assertTrue(reports(result, "SWALLOWED: must-skip"));
    }

    // ---- the holes that produce a false GREEN --------------------------------------------------

    @Test
    void aRegisteredSceneThatNeverRanIsRed() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("a"), reg("b")), scene("a", "PASS"), done(1)), null);
        assertEquals(1, result.code());
        assertTrue(reports(result, "SWALLOWED: 'b'"));
    }

    @Test
    void duplicateRecordsAreRedBecauseLastWinsCanHideAFail() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("a")), scene("a", "FAIL"), scene("a", "PASS"), done(2)), null);
        assertEquals(1, result.code());
        assertTrue(reports(result, "DUPLICATE"));
    }

    @Test
    void aRecordForAnUnregisteredNameIsRed() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("a")), scene("a", "PASS"), scene("ghost", "PASS"), done(2)), null);
        assertEquals(1, result.code());
        assertTrue(reports(result, "DRIFTED"));
    }

    @Test
    void aFooterCountThatDisagreesWithTheRecordsIsRed() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("a")), scene("a", "PASS"), done(7)), null);
        assertEquals(1, result.code());
        assertTrue(reports(result, "TRUNCATED"));
    }

    // ---- optional scenes -----------------------------------------------------------------------

    @Test
    void anOptionalSceneMayFailWithoutFailingTheRun() {
        Verdict.Result result = Verdict.judge(
                records(suite(optional("flaky")), scene("flaky", "FAIL"), done(1)), null);
        assertEquals(0, result.code());
        assertTrue(reports(result, "fail(optional)"));
    }

    // ---- reconciliation against the manifest ---------------------------------------------------

    @Test
    void aManifestNameThatIsNotRegisteredIsRed() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("wd.a")), scene("wd.a", "PASS"), done(1)),
                List.of("wd.a", "wd.b"));
        assertEquals(1, result.code());
        assertTrue(reports(result, "MISSING-EXPECTED: wd.b"));
    }

    @Test
    void aSceneAddedToTheProviderButNotTheManifestIsRed() {
        // The half that actually closes the hole. Without it a new scene ships unreconciled, which
        // is exactly the silent composition the manifest exists to catch.
        Verdict.Result result = Verdict.judge(
                records(suite(reg("wd.a"), reg("wd.new")),
                        scene("wd.a", "PASS"), scene("wd.new", "PASS"), done(2)),
                List.of("wd.a"));
        assertEquals(1, result.code());
    }

    @Test
    void theFrameworksOwnBuiltinsStayOutOfAConsumersReconciliation() {
        // Scoped to namespaces the manifest itself uses: a consumer listing only `wd.*` must not go
        // RED because StageWright shipped a new built-in scene.
        Verdict.Result result = Verdict.judge(
                records(suite(reg("wd.a"), reg("floorAssert")),
                        scene("wd.a", "PASS"), scene("floorAssert", "PASS"), done(2)),
                List.of("wd.a"));
        assertEquals(0, result.code());
    }

    @Test
    void noManifestMeansNoReconciliation() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("a")), scene("a", "PASS"), done(1)), null);
        assertEquals(0, result.code());
    }

    // ---- filtered runs -------------------------------------------------------------------------

    private static Map<String, Object> filtered(String pattern, Map<String, Object>... registered) {
        Map<String, Object> rec = suite(registered);
        rec.put("filter", pattern);
        return rec;
    }

    @Test
    void aFilteredGreenIsLabelledAsNotAGateResult() {
        // The label is what a human reads first; a bare GREEN here would read as the suite passing.
        Verdict.Result result = Verdict.judge(
                records(filtered("wd.a*", reg("wd.a")), scene("wd.a", "PASS"), done(1)), null);
        assertEquals(0, result.code());
        assertTrue(result.filtered());
        assertEquals("GREEN (FILTERED — not a gate result)", result.label());
        assertTrue(reports(result, "FILTERED to 'wd.a*'"));
    }

    @Test
    void aFilteredRedKeepsItsSuffix() {
        Verdict.Result result = Verdict.judge(
                records(filtered("wd.a", reg("wd.a")), scene("wd.a", "FAIL"), done(1)), null);
        assertEquals(1, result.code());
        assertEquals("RED (FILTERED — not a gate result)", result.label());
    }

    @Test
    void aFilteredRunSkipsManifestReconciliation() {
        // Every scene the pattern left out is legitimately absent; reporting the whole manifest as
        // missing would bury the one outcome the run was asked about.
        Verdict.Result result = Verdict.judge(
                records(filtered("wd.a", reg("wd.a")), scene("wd.a", "PASS"), done(1)),
                List.of("wd.a", "wd.b", "wd.c"));
        assertEquals(0, result.code());
        assertFalse(reports(result, "MISSING-EXPECTED"));
    }

    @Test
    void aFilterThatMatchedNothingIsRed() {
        Verdict.Result result = Verdict.judge(records(filtered("wd.typo*"), done(0)), null);
        assertEquals(1, result.code());
        assertTrue(result.filtered());
        assertTrue(reports(result, "matched NO scenes"));
    }

    @Test
    void aFilterThatMatchedOnlyTheCanariesItKeepsIsStillRed() {
        // The filter never drops the framework canaries, so a typo leaves exactly those behind —
        // and they pass, which is the empty-suite green in a disguise.
        Verdict.Result result = Verdict.judge(
                records(filtered("wd.typo*", reg("cf", "MUST_FAIL"), reg("cs", "MUST_SWALLOW")),
                        scene("cf", "FAIL"), done(1)), null);
        assertEquals(1, result.code());
        assertTrue(reports(result, "matched NO scenes"));
    }

    @Test
    void aFilteredRunStillJudgesTheCanariesItKept() {
        Verdict.Result result = Verdict.judge(
                records(filtered("wd.a", reg("wd.a"), reg("cf", "MUST_FAIL")),
                        scene("wd.a", "PASS"), scene("cf", "PASS"), done(2)), null);
        assertEquals(2, result.code());
    }

    @Test
    void aMustSkipSceneIsAMatchNotAKeptCanary() {
        Verdict.Result result = Verdict.judge(
                records(filtered("cap.*", reg("cap.absent", "MUST_SKIP"), reg("cf", "MUST_FAIL")),
                        skipped("cap.absent", "nothing offers it"), scene("cf", "FAIL"), done(2)),
                null);
        assertEquals(0, result.code());
    }

    @Test
    void anUnfilteredRunIsNotLabelledFiltered() {
        Verdict.Result result = Verdict.judge(
                records(suite(reg("a")), scene("a", "PASS"), done(1)), null);
        assertFalse(result.filtered());
        assertFalse(reports(result, "FILTERED"));
    }

    // ---- reading a results file ----------------------------------------------------------------

    @Test
    void parseDropsWhatDoesNotDecodeAndSaysWhich(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("results.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"suite\",\"loader\":\"fabric\",\"registered\":[{\"name\":\"a\"}]}",
                "",
                "{\"type\":\"scene\",\"name\":\"a\",\"outc",
                "[1,2,3]",
                "{\"type\":\"done\",\"scenes\":1}"), StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> parsed = Verdict.parse(file, warnings);

        assertEquals(2, parsed.size());
        assertEquals("suite", parsed.get(0).get("type"));
        assertEquals("done", parsed.get(1).get("type"));
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("undecodable line 3"));
        assertTrue(warnings.get(1).contains("non-object line 4"));
    }

    @Test
    void aDroppedSceneLineSurfacesAsSwallowedRatherThanGreen(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("results.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"suite\",\"loader\":\"fabric\",\"registered\":[{\"name\":\"a\"}]}",
                "{\"type\":\"scene\",\"name\":\"a\",\"outcome\":\"FA",
                "{\"type\":\"done\",\"scenes\":1}"), StandardCharsets.UTF_8);
        Verdict.Result result = Verdict.judge(Verdict.parse(file, new ArrayList<>()), null);
        assertEquals(1, result.code());
        assertTrue(reports(result, "SWALLOWED: 'a'"));
    }
}
