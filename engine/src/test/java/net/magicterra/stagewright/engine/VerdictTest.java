package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
}
