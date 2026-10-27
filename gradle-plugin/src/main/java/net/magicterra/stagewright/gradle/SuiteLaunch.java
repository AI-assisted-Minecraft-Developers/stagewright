package net.magicterra.stagewright.gradle;

import java.io.File;

/**
 * One topology a test suite's scenes may name, and the run tasks that start its games: a topology the build
 * declares, or one {@link GeneratedRuns} declared for the suite.
 *
 * @param topology         the name, as a scene declaration spells it
 * @param runTask          the primary game's run task, by full path
 * @param companionRunTask the client that joins it, or null
 * @param server           the primary is a dedicated server
 */
record SuiteLaunch(String topology, String runTask, String companionRunTask, File gameDirectory, boolean cleanWorld,
                   int timeoutMinutes, int stallMinutes, boolean server) {}
