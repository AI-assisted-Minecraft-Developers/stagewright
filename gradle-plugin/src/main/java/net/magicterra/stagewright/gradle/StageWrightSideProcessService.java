package net.magicterra.stagewright.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.logging.Logger;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.api.tasks.JavaExec;

/**
 * Owns the processes a topology stands up beside the game under test, and guarantees they come down.
 *
 * <p>A {@link BuildService} rather than task state, for one reason: Gradle calls {@link #close()} at
 * the end of the build on every path — success, assertion failure, task timeout, Ctrl-C. Teardown in
 * a {@code doLast} is skipped exactly when the run failed, which is when a leaked game client is
 * most likely and most expensive: it holds its run directory open, so the NEXT run's provisioning
 * cannot delete the world, and the failure that follows looks like a scene bug.
 *
 * <p>This session has had to hand-kill orphaned game JVMs more than once. That is the failure this
 * class exists to make impossible.
 */
public abstract class StageWrightSideProcessService
        implements BuildService<StageWrightSideProcessService.Params>, AutoCloseable {

    public interface Params extends BuildServiceParameters {
        Property<Boolean> getQuiet();
    }

    private final List<Process> companions = new ArrayList<>();

    /** Start a companion run and take ownership of it. */
    public synchronized void startCompanion(JavaExec spec, File logFile, Logger logger) {
        companions.add(SideProcesses.startCompanion(spec, logFile, logger));
    }

    @Override
    public synchronized void close() {
        Logger logger = org.gradle.api.logging.Logging.getLogger(StageWrightSideProcessService.class);
        for (Process companion : companions) {
            SideProcesses.stopCompanion(companion, logger);
        }
        companions.clear();
    }
}
