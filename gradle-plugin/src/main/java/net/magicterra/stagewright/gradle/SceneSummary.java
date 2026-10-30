package net.magicterra.stagewright.gradle;

import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestListener;
import org.gradle.api.tasks.testing.TestResult;

/**
 * Prints {@code [stagewright] <scene>: N passed, M skipped} as each scene finishes, and how many failed when any did,
 * so a green run says what it ran: Gradle prints nothing for passing tests.
 *
 * <p>A scene is the outermost class the test engine reports: the engine gives each scene a class source named for it.
 * The classes below it, {@code <scene>/<class>} and {@code <scene>/<file>}, have a class above them; the executor's
 * suite above it has no class.
 */
final class SceneSummary implements TestListener {

    @Override
    public void beforeSuite(TestDescriptor suite) {
        // Nothing to do before a suite: the summary is written once the suite's results are known.
    }

    @Override
    public void afterSuite(TestDescriptor suite, TestResult result) {
        if (!isScene(suite)) {
            return;
        }
        StringBuilder line = new StringBuilder(64)
                .append("[stagewright] ")
                .append(suite.getClassName())
                .append(": ")
                .append(result.getSuccessfulTestCount())
                .append(" passed, ")
                .append(result.getSkippedTestCount())
                .append(" skipped");
        if (result.getFailedTestCount() > 0) {
            line.append(", ").append(result.getFailedTestCount()).append(" failed");
        }
        Logging.getLogger(SceneSummary.class).lifecycle(line.toString());
    }

    @Override
    public void beforeTest(TestDescriptor test) {
        // Nothing to do before a test: only scenes are summarized, and only when they finish.
    }

    @Override
    public void afterTest(TestDescriptor test, TestResult result) {
        // Nothing to do after a test: its outcome is counted in the scene's suite summary.
    }

    static boolean isScene(TestDescriptor suite) {
        if (suite.getClassName() == null) {
            return false;
        }
        for (TestDescriptor p = suite.getParent(); p != null; p = p.getParent()) {
            if (p.getClassName() != null) {
                return false;
            }
        }
        return true;
    }
}
