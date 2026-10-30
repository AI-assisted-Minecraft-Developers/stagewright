package net.magicterra.stagewright.gradle;

import java.io.File;

/**
 * One topology a test suite's scenes may name, and the run tasks that start its games: a topology the build declares,
 * or one {@link GeneratedRuns} declared for the suite.
 *
 * @param topology the name, as a scene declaration spells it
 * @param runTask the primary game's run task, by full path
 * @param companionRunTask the client that joins it, or null
 * @param server the primary is a dedicated server
 * @param leaks {@code fail} or {@code report}: what a game forced down after its run does to its scene
 */
record SuiteLaunch(
        String topology,
        String runTask,
        String companionRunTask,
        File gameDirectory,
        boolean cleanWorld,
        int timeoutMinutes,
        int stallMinutes,
        boolean server,
        String leaks) {

    static final String LEAKS_FAIL = "fail";
    static final String LEAKS_REPORT = "report";

    /** {@code value}, refused unless it is one the engine reads, naming {@code owner}, the topology or suite set on. */
    static String leaks(String value, String owner) {
        if (value == null) {
            return LEAKS_FAIL;
        }
        if (!value.equals(LEAKS_FAIL) && !value.equals(LEAKS_REPORT)) {
            throw new org.gradle.api.GradleException(owner + ": leaks is '" + value + "'; it is 'fail' or 'report'");
        }
        return value;
    }
}
