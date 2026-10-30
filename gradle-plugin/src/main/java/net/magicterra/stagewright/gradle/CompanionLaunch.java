package net.magicterra.stagewright.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.gradle.api.GradleException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * Everything a game needs to start — a topology's primary or its companion — taken from its run task when the build is
 * configured and holding no reference to that task. {@link StageWrightLaunchSpecsTask} writes it into a launch spec.
 * (Named for the companion, which was its first use.)
 *
 * <p>A {@code Task} cannot be written into the configuration cache. What the launch needs is a set of values, and most
 * of them are lazy: properties the loader binds, a classpath it assembles, a launcher it resolves. Those are kept as
 * the property objects and read when the spec is written, which is when the loader has finished — a value read earlier
 * produces a launch that differs from the one Gradle would have performed. The rest is plain data, read once.
 *
 * <p>Where the loader keeps what it stages late is {@link Toolchain}'s to know; this class asks it.
 */
final class CompanionLaunch {

    private final Provider<JavaLauncher> launcher;
    private final String executable;
    private final Provider<String> mainClass;
    private final List<String> eagerJvmArgs;
    private final Provider<List<String>> jvmArguments;
    private final List<CommandLineArgumentProvider> jvmArgumentProviders;
    private final List<String> args;
    private final List<CommandLineArgumentProvider> argumentProviders;
    private final FileCollection classpath;
    private final Map<String, String> environment;
    private final List<MapProperty<?, ?>> lateEnvironment;
    private final List<Toolchain.WorkingDirectory> workingDirectories;
    private final File fallbackDirectory;

    private CompanionLaunch(JavaExec spec, List<Toolchain> toolchains) {
        this.launcher = spec.getJavaLauncher();
        this.executable = spec.getExecutable();
        this.mainClass = spec.getMainClass();
        // Copies: a list the task keeps changing, or one the configuration cache cannot read back.
        this.jvmArgumentProviders = new ArrayList<>(spec.getJvmArgumentProviders());
        this.eagerJvmArgs = eagerJvmArgs(spec, jvmArgumentProviders);
        this.jvmArguments = spec.getJvmArguments();
        this.args = spec.getArgs() == null ? new ArrayList<>() : new ArrayList<>(spec.getArgs());
        // Loom gives its program arguments this way, asked at exec: --port and --nogui live here, not in getArgs().
        this.argumentProviders = new ArrayList<>(spec.getArgumentProviders());
        FileCollection cp = spec.getClasspath();
        for (Toolchain toolchain : toolchains) {
            FileCollection late = toolchain.lateClasspath(spec);
            if (late != null) {
                cp = cp.plus(late);
            }
        }
        this.classpath = cp;
        this.environment = new LinkedHashMap<>();
        spec.getEnvironment().forEach((k, v) -> environment.put(k, String.valueOf(v)));
        this.lateEnvironment = new ArrayList<>();
        this.workingDirectories = new ArrayList<>();
        for (Toolchain toolchain : toolchains) {
            lateEnvironment.addAll(toolchain.lateEnvironment(spec));
            Toolchain.WorkingDirectory dir = toolchain.workingDirectory(spec);
            if (dir != null) {
                workingDirectories.add(dir);
            }
        }
        this.fallbackDirectory = spec.getWorkingDir();
    }

    /**
     * {@code getAllJvmArgs()} without what the argument providers add.
     *
     * <p>Gradle expands the providers into that list, and a provider may ask for something that only exists once the
     * build has run its prerequisites. The providers are kept as providers and asked when the launch spec is written,
     * so the list is read with them set aside.
     */
    private static List<String> eagerJvmArgs(JavaExec spec, List<CommandLineArgumentProvider> providers) {
        spec.getJvmArgumentProviders().clear();
        try {
            return new ArrayList<>(spec.getAllJvmArgs());
        } finally {
            spec.getJvmArgumentProviders().addAll(providers);
        }
    }

    /**
     * Take the launch of a run task, or refuse one whose loader this plugin has no adapter for.
     *
     * <p>Refused rather than launched on a guess: a run task with properties of its own that nothing here reads would
     * start a game that is missing whatever they hold, and nothing would say so.
     */
    static CompanionLaunch of(JavaExec spec) {
        List<Toolchain> toolchains = Toolchain.of(spec);
        if (toolchains.isEmpty()) {
            String unread = Toolchain.unreadMembers(spec);
            if (unread != null) {
                throw new GradleException("the companion run '" + spec.getName() + "' is a "
                        + spec.getClass().getSuperclass().getSimpleName() + ", whose own properties ("
                        + unread + ") this plugin has no adapter for, so it cannot tell what the run binds"
                        + " late. Supported: "
                        + String.join(
                                ", ",
                                Toolchain.KNOWN.stream().map(Toolchain::name).toList()) + ", or a plain JavaExec.");
            }
        }
        return new CompanionLaunch(spec, toolchains);
    }

    File workingDirectory() {
        for (Toolchain.WorkingDirectory dir : workingDirectories) {
            File resolved = dir.resolve();
            if (resolved != null) {
                return resolved;
            }
        }
        return fallbackDirectory;
    }

    /** The environment the game gets: the task's own, plus what its loader binds late. */
    Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<>(environment);
        for (MapProperty<?, ?> p : lateEnvironment) {
            p.get().forEach((k, v) -> env.put(String.valueOf(k), String.valueOf(v)));
        }
        return env;
    }

    /**
     * Every JVM argument the run would start with, from all three places Gradle keeps them.
     *
     * <p>No single source is sufficient, and each loader plugin uses a different one.
     *
     * <ul>
     *   <li>{@code getAllJvmArgs()} — the eager list. ModDevGradle's argfile reference is here.
     *   <li>{@code getJvmArguments()} — the lazy {@code ListProperty} that superseded it, and NOT included in the eager
     *       list. architectury-loom puts everything here, so reading only the eager list produced a four-argument
     *       command line carrying nothing but the default encoding and locale — the client died in
     *       {@code TransformerRuntime.applyProperties} on a null path, which is the transformer asking for settings
     *       nobody had given it.
     *   <li>{@code jvmArgumentProviders} — argument providers, whose output a plugin may or may not also place in one
     *       of the above. ModDevGradle places its {@code -Dfml.modFolders} in both, and expanding it a second time put
     *       two copies on the line.
     * </ul>
     *
     * <p>Union, then, de-duplicated a whole source at a time — never token by token. A JVM argument list is not a set
     * of independent strings: {@code --add-opens} and {@code --add-exports} appear many times over, each followed by
     * its own value, and dropping the second occurrence of the flag as a "duplicate" leaves its value stranded on the
     * command line. The JVM then reads that value as the main class and reports {@code Could not find or load main
     * class java.base.java.lang.invoke=cpw.mods.securejarhandler}, which names the symptom and hides the cause
     * completely.
     */
    List<String> jvmArgs() {
        List<String> all = new ArrayList<>(eagerJvmArgs);
        appendUnlessPresent(all, jvmArguments.getOrElse(List.of()));
        for (CommandLineArgumentProvider provider : jvmArgumentProviders) {
            List<String> provided = new ArrayList<>();
            provider.asArguments().forEach(provided::add);
            appendUnlessPresent(all, provided);
        }
        return all;
    }

    /**
     * Everything that goes between the java binary and the main class: {@link #jvmArgs()} with argfile references
     * unescaped, plus an {@code @file} carrying the classpath when those arguments do not already carry one. The file
     * is written to {@code classpathArgFile}.
     */
    List<String> launchJvmArgs(File classpathArgFile) {
        List<String> jvm = new ArrayList<>();
        jvmArgs().forEach(a -> jvm.add(unescapeArgFileReference(a)));
        if (!alreadyHasClasspath(jvm)) {
            String reference = writeClasspathArgFile(classpathArgFile);
            if (reference != null) {
                jvm.add(reference);
            }
        }
        return jvm;
    }

    /** The main class, resolved now: the loader has finished binding it by the time a game starts. */
    String mainClassName() {
        return mainClass.get();
    }

    /** The program arguments, the task's own and then its providers', argfile references unescaped. */
    List<String> programArgs() {
        List<String> out = new ArrayList<>();
        args.forEach(a -> out.add(unescapeArgFileReference(a)));
        for (CommandLineArgumentProvider provider : argumentProviders) {
            provider.asArguments().forEach(a -> out.add(unescapeArgFileReference(a)));
        }
        return out;
    }

    /** The java binary the run task would use. */
    String javaExecutable() {
        return javaBinary();
    }

    /** Append a source's arguments unless the target already carries all of them. */
    private static void appendUnlessPresent(List<String> target, List<String> extra) {
        if (extra.isEmpty() || target.containsAll(extra)) {
            return;
        }
        target.addAll(extra);
    }

    /**
     * Undo the backslash escaping a loader plugin applies to an {@code @argfile} reference.
     *
     * <p>ModDevGradle hands its run task {@code @C:\\Users\\...\\RunVmArgs.txt} — doubled, because Gradle writes long
     * command lines into a java argument file of its own and a backslash is an escape character in there. Handed
     * straight to {@code ProcessBuilder}, which does no such rewriting, that names a file which does not exist. The JVM
     * then starts with none of the vm args — no module path, no launch target — and dies on
     * {@code ClassNotFoundException: net.neoforged.devlaunch.Main}, an error that says nothing whatsoever about the
     * actual cause.
     *
     * <p>Only after the first character, so a UNC reference ({@code @\\server\share\...}) keeps its leading pair.
     */
    static String unescapeArgFileReference(String arg) {
        if (arg == null || arg.length() < 2 || arg.charAt(0) != '@') {
            return arg;
        }
        return "@" + arg.substring(1).replace("\\\\", "\\");
    }

    /**
     * Whether the JVM arguments already put a classpath on the command line, directly or through an argument file.
     *
     * <p>Asked because appending a second one is not harmless. A later {@code -classpath} wins, so the run would
     * silently execute against this plugin's idea of the classpath instead of the loader's — and loom's differs
     * deliberately, having filtered out libraries its run must not see.
     */
    private static boolean alreadyHasClasspath(List<String> args) {
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            if ("-cp".equals(arg) || "-classpath".equals(arg) || "--class-path".equals(arg)) {
                return true;
            }
            if (arg.startsWith("@")) {
                try {
                    String body = Files.readString(Path.of(arg.substring(1)));
                    if (body.startsWith("-classpath")
                            || body.startsWith("-cp")
                            || body.contains("\n-classpath")
                            || body.contains("\n-cp")) {
                        return true;
                    }
                } catch (IOException | RuntimeException e) {
                    // Unreadable argfile: not this method's problem. The JVM will report it far more
                    // precisely than a guess here could.
                }
            }
        }
        return false;
    }

    /**
     * Put the run's classpath in a java argument file and return the {@code @file} argument, or null when the run
     * carries no classpath at all.
     *
     * <p>A file rather than command-line arguments because a modded dev classpath runs to hundreds of entries, well
     * past the ~32k Windows allows in a command line.
     *
     * <p>Separators are normalised to {@code /}: a java argument file treats backslash as an escape character, so a
     * Windows path written verbatim silently loses its separators. The JVM accepts forward slashes in classpath entries
     * on every platform, which makes normalising simpler and safer than escaping.
     */
    private String writeClasspathArgFile(File argFile) {
        String path = classpath.getAsPath();
        if (path == null || path.isBlank()) {
            return null;
        }

        String body = "-cp \"" + path.replace('\\', '/') + "\"\n";
        try {
            argFile.getParentFile().mkdirs();
            Files.writeString(argFile.toPath(), body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the companion classpath argfile " + argFile, e);
        }
        return "@" + argFile.getAbsolutePath();
    }

    /**
     * The java binary a run task would use.
     *
     * <p>Three sources, in the order they win. {@code javaLauncher} is what a modern loader plugin sets — ModDevGradle
     * configures its runs through the toolchain service and leaves {@code executable} null, which is how the first
     * attempt at this got a {@code NullPointerException} out of {@code ProcessBuilder} with nothing in it to say why.
     * {@code executable} covers a build that pinned one by hand. The JVM running Gradle is the last resort, and it is a
     * real answer rather than a guess: the daemon is already on a JDK this build chose.
     */
    private String javaBinary() {
        if (launcher.isPresent()) {
            return launcher.get().getExecutablePath().getAsFile().getAbsolutePath();
        }
        if (executable != null && !executable.isBlank()) {
            return executable;
        }
        return new File(
                        new File(System.getProperty("java.home"), "bin"),
                        System.getProperty("os.name", "")
                                        .toLowerCase(Locale.ROOT)
                                        .startsWith("windows")
                                ? "java.exe"
                                : "java")
                .getAbsolutePath();
    }
}
