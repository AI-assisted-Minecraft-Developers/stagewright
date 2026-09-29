package net.magicterra.stagewright.contract;

/** Scene teardown as both homes report it. */
public final class Cleanups {

    private Cleanups() {}

    /**
     * A cleanup's failure as its reason reads, the same in both homes: the author's own words rather
     * than Rhino's wrapper, and a skip named as one, since skipping cannot undo what the scene did.
     *
     * <p>Followed through {@code getCause}, which Rhino's wrapper sets, so the in-process API needs no
     * Rhino on its classpath to call this.
     */
    public static String reasonOf(Throwable failure) {
        Throwable cause = failure;
        for (int hops = 0; hops < 8; hops++) {
            if (cause instanceof SceneFailure) return cause.getMessage();
            if (cause instanceof SceneSkipped) return "skipped mid-teardown: " + cause.getMessage();
            if (cause.getCause() == null) break;
            cause = cause.getCause();
        }
        return "unexpected " + cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
