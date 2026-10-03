package net.magicterra.stagewright.contract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** A scene's teardown, one implementation for both homes so their rules cannot drift apart. */
public final class Cleanups {

    private final SceneReport owner;
    private final Deque<Runnable> queue = new ArrayDeque<>();
    /** How many {@link #run} calls are draining: a count, so a cleanup that drains again cannot reopen
     *  registration for the cleanups after it when its inner drain returns. */
    private int draining;
    private int unrun;

    /** @param owner the scene whose record carries how many cleanups did not run */
    public Cleanups(SceneReport owner) {
        this.owner = owner;
    }

    /**
     * Queue teardown, to run last-registered first.
     *
     * <p>One registered by a cleanup does not run: what the cleanups leave is how the scene ends. A
     * restoring helper called from a cleanup would otherwise undo that cleanup, or loop forever.
     */
    public void add(Runnable action) {
        if (draining > 0) {
            unrun++;
            return;
        }
        queue.addFirst(action);
    }

    /** True while {@link #run} is draining, so a caller can tell a cleanup's work from the body's. */
    public boolean draining() {
        return draining > 0;
    }

    /** Run everything queued; each failure is warned about and returned, never thrown, so one failing
     *  cleanup cannot stop the rest. How many a cleanup registered is recorded on the owner. */
    public List<String> run(Consumer<String> warn) {
        List<String> failed = new ArrayList<>();
        draining++;
        try {
            for (Runnable r; (r = queue.pollFirst()) != null; ) {
                try {
                    r.run();
                } catch (Throwable t) {
                    String reason = reasonOf(t);
                    warn.accept("cleanup failed: " + reason);
                    failed.add(reason);
                }
            }
        } finally {
            draining--;
        }
        if (draining == 0 && unrun > 0) {
            warn.accept(unrun + " cleanup(s) registered by a cleanup did not run");
            // In the results too: a dropped cleanup may have been undoing something, and the log
            // is not what a reader of a green run looks at.
            owner.record("cleanupsNotRun", unrun);
            unrun = 0;
        }
        return failed;
    }

    /**
     * A cleanup's failure as its reason reads, the same in both homes and as a body's would: the
     * author's own words rather than Rhino's wrapper, and a skip named as one, since skipping cannot
     * undo what the scene did.
     *
     * <p>Our own failure is looked for through {@code getCause}, which Rhino's wrapper sets, so the
     * in-process API needs no Rhino on its classpath to call this. Anything else reads from the
     * throwable that reached here, not its innermost cause: see {@link Reasons#unexpected}.
     */
    public static String reasonOf(Throwable failure) {
        Throwable cause = failure;
        for (int hops = 0; cause != null && hops < 8; hops++) {
            if (cause instanceof SceneFailure) return cause.getMessage();
            if (cause instanceof SceneSkipped) return "skipped mid-teardown: " + cause.getMessage();
            cause = cause.getCause();
        }
        return Reasons.unexpected(failure);
    }
}
