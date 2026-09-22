package net.magicterra.stagewright.contract;

import java.util.List;

/**
 * Thrown by a scene body to stop early on a topology it does not apply to. The harness resolves the
 * scene PASS and records the reason verbatim.
 *
 * <p>This is not a swallow, and the distinction matters enough to spell out. A swallowed scene is
 * one the runner forgot: it never appears in the results, so nothing downstream can tell it apart
 * from a scene that was never written. A skipped scene is in the results, was entered, and carries
 * the sentence explaining why its body did not run — so a suite that skipped everything reads as a
 * suite that skipped everything, rather than as a green run.
 *
 * <p>The case it exists for: a scene needs a real connected player, and a bare dedicated-server run
 * has none. Making that a failure would mean the server topology could never be green; making it
 * invisible would mean the client topology's extra coverage could silently stop happening. Neither
 * is what you want from the gate that is supposed to tell you the three topologies still agree.
 */
public final class SceneSkipped extends RuntimeException {

    public SceneSkipped(String reason) {
        super(reason, null, false, false);
    }

    /**
     * The failure a skip turns into when soft checks had already failed before it, or null when none
     * had. A skip says the subject is absent; a failed check says something that WAS measured came
     * out wrong, and the absence of one thing cannot excuse a wrong answer about another.
     *
     * <p>Shared so that both homes word it identically and neither can drop the violations.
     */
    public static String failureAfterChecks(List<String> softViolations, String skipReason) {
        if (softViolations.isEmpty()) return null;
        return softViolations.size() + " check(s) failed: " + String.join("; ", softViolations)
                + " — then skipped: " + skipReason;
    }
}
