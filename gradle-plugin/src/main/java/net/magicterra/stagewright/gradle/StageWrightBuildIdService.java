package net.magicterra.stagewright.gradle;

import java.io.File;
import java.util.List;

import net.magicterra.stagewright.engine.BuildId;
import net.magicterra.stagewright.engine.RunDirectory;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * The build id, taken once per build and shared by every run task that asks. A build service rather
 * than a shared object, because the configuration cache stores each task apart and a shared object
 * comes back as one copy per task, each asking git on its own.
 */
public abstract class StageWrightBuildIdService implements BuildService<StageWrightBuildIdService.Params> {

    public interface Params extends BuildServiceParameters {
        Property<File> getWorkTree();
    }

    private List<String> arguments;

    /** {@code -Dstagewright.build=<id>}, or nothing when git gives no id. */
    public synchronized List<String> arguments() {
        if (arguments == null) {
            File workTree = getParameters().getWorkTree().get();
            String build = BuildId.ofGitWorkTree(workTree.toPath());
            if (build == null) {
                Logging.getLogger(StageWrightBuildIdService.class).warn("[stagewright] no build id for {}:"
                        + " git tracks nothing there, it has no commit yet, or git failed or took over"
                        + " 30s. The results record no build, and stagewrightCoverage reports MIXED"
                        + " BUILDS against any run that has one", workTree);
            }
            arguments = build == null ? List.of() : List.of("-D" + RunDirectory.BUILD_PROPERTY + "=" + build);
        }
        return arguments;
    }
}
