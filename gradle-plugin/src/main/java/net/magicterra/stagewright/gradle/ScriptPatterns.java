package net.magicterra.stagewright.gradle;

import java.util.LinkedHashSet;
import java.util.Set;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestFilter;

/**
 * Lets a {@code --tests} pattern that names a script test by its path reach it.
 *
 * <p>The engine reports a script test as the method {@code <name>} of a class named for the file's path under the
 * test root ({@code stagewright/tests/validation/42_combat.js}), so Gradle's filter matches it like any other test.
 * But Gradle first decides which test classes to hand the engine at all, by the patterns' leading segments, and no
 * class shares a path's: a pattern such as {@code stagewright/tests/validation/42_combat.js} left nothing to start the
 * run with, and the engine never got to look for script tests. A pattern starting with {@code *} lets every class
 * through, so such a pattern gets one. A class name has no {@code /}, so for classes the pattern matches as before.
 */
final class ScriptPatterns implements Action<Task> {

    private ScriptPatterns() {}

    static final ScriptPatterns INSTANCE = new ScriptPatterns();

    /** {@code patterns} with each one that names a script path starting with {@code *}. */
    static Set<String> reachingScripts(Set<String> patterns) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : patterns) out.add(p.contains("/") && !p.startsWith("*") ? "*" + p : p);
        return out;
    }

    /** Before the test task runs: its build script's patterns and its command line's. */
    @Override
    public void execute(Task task) {
        TestFilter filter = ((Test) task).getFilter();
        filter.setIncludePatterns(reachingScripts(filter.getIncludePatterns()).toArray(String[]::new));
        if (filter instanceof DefaultTestFilter internal) {
            internal.setCommandLineIncludePatterns(reachingScripts(internal.getCommandLineIncludePatterns()));
        }
    }
}
