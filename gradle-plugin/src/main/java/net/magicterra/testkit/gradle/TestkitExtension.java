package net.magicterra.testkit.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Configuration surface for the mc-testkit gradle plugin — the {@code testkit { ... }}
 * extension block. All values feed the frozen orchestration-argument assembly table in
 * {@link TestkitPlugin}; the plugin itself never re-judges results.
 *
 * <p>Conventions (defaults) are set in {@link TestkitPlugin#apply}:
 * <ul>
 *   <li>{@link #getLoader() loader} — {@code "fabric"}</li>
 *   <li>{@link #getPythonExecutable() pythonExecutable} — {@code "python3"}</li>
 *   <li>{@link #getScriptsDir() scriptsDir} — {@code "scripts/testkit"} (relative to the
 *       applied project directory, which is the orchestrator working directory)</li>
 *   <li>{@link #getExpectFile() expectFile} — unset (nullable; orchestrator default used)</li>
 *   <li>{@link #getExtraArgs() extraArgs} — empty list</li>
 * </ul>
 */
public abstract class TestkitExtension {

    /** Loader dimension threaded into every orchestrator invocation ({@code --loader L}). */
    public abstract Property<String> getLoader();

    /** Python interpreter used to run the orchestrator scripts. */
    public abstract Property<String> getPythonExecutable();

    /**
     * Directory (relative to the applied project = orchestrator working dir) holding the
     * frozen orchestration scripts (t0.py / t1.py / t2.py) and the dogfood expect-file.
     */
    public abstract Property<String> getScriptsDir();

    /**
     * Optional expect-file threaded as {@code --expect-file} to the CLIENT topologies
     * (testkitClient / testkitE2E) when present. Nullable: when unset the orchestrator's
     * own default applies. (testkitServer always uses the loader-derived dogfood expect
     * manifest per the frozen table and ignores this value.)
     */
    public abstract RegularFileProperty getExpectFile();

    /** Extra arguments appended verbatim to the tail of every assembled command line. */
    public abstract ListProperty<String> getExtraArgs();
}
