package net.magicterra.stagewright.gradle;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import net.magicterra.stagewright.engine.BuildId;
import net.magicterra.stagewright.engine.RunDirectory;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * The build id, taken by the build's first run that gets one and checked again by each later one. A
 * build service, since the configuration cache would give each task its own copy of a shared object.
 */
public abstract class StageWrightBuildIdService implements BuildService<StageWrightBuildIdService.Params> {

    public interface Params extends BuildServiceParameters {
        Property<File> getWorkTree();
    }

    /** Null until a run gets an id: a run git failed for says nothing about the tree later runs see. */
    private String first;
    /** When the tree was last seen to be {@link #first}: the latest start of a read that found it,
     *  before any read found it otherwise. */
    private long firstSeenAt;
    /** A read found the tree changed or could not read it. A later read finding it as it was cannot
     *  vouch for what was compiled in between, from the changed tree, so it no longer moves
     *  {@link #firstSeenAt}. */
    private boolean seenOtherwise;
    private boolean toldOfNoId;

    /**
     * {@code -Dstagewright.build=<id>} for {@code run}, or nothing when git gives no id. The build's id
     * is the one its first run that got one took. Every later run takes the tree again. Where the tree
     * is still that, or where it changed or git could not read it but nothing the run {@code loads}
     * was written since the tree last read as that, the run loads code built from it and takes the
     * build's id: an edit no build reads, or git timing out once, leaves it alone. A run that may have
     * loaded a change records a build no other run matches.
     */
    public synchronized List<String> arguments(String run, Iterable<File> loads) {
        File workTree = getParameters().getWorkTree().get();
        long readAt = System.currentTimeMillis();
        String now = BuildId.ofGitWorkTree(workTree.toPath());
        if (first == null) first = now;
        if (first == null) {
            if (toldOfNoId) return List.of();
            toldOfNoId = true;
            Logging.getLogger(StageWrightBuildIdService.class).warn("[stagewright] no build id for {}:"
                    + " git tracks nothing there, it has no commit yet, or git failed or took over"
                    + " 30s. The results record no build, and stagewrightCoverage reports MIXED"
                    + " BUILDS against any run that has one", workTree);
            return List.of();
        }
        if (first.equals(now)) {
            if (!seenOtherwise) firstSeenAt = readAt;
            return argument(first);
        }
        seenOtherwise = true;
        // Unread, it is named rather than left out: a run with no id would match every run git gave none.
        String seen = now == null ? "unknown" : now;
        Path written = BuildId.writtenSince(loads, firstSeenAt);
        if (written == null) {
            Logging.getLogger(StageWrightBuildIdService.class).lifecycle("[stagewright] {} changed, or git"
                    + " could not read it, since this build's first run ({} -> {}), but nothing {} loads was"
                    + " written since, so it keeps the build's id", workTree, first, seen, run);
            return argument(first);
        }
        // A nonce per run, not per build: two runs after one edit can still have compiled different
        // code, as subprojects compile at different moments.
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        Logging.getLogger(StageWrightBuildIdService.class).warn("[stagewright] {} changed, or git could not"
                + " read it, since this build's first run ({} -> {}), and {} loads {}, written since, so it"
                + " records a build no other run matches and stagewrightCoverage reports MIXED BUILDS until"
                + " the gate is re-run on a tree that does not change", workTree, first, seen, run, written);
        return argument(BuildId.CHANGED_DURING_BUILD + nonce + ": " + first + " -> " + seen);
    }

    private static List<String> argument(String build) {
        return List.of("-D" + RunDirectory.BUILD_PROPERTY + "=" + build);
    }
}
