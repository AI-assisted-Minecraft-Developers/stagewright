package net.magicterra.stagewright.gradle;

import java.util.Locale;

import org.codehaus.groovy.runtime.InvokerHelper;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectCollection;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;

/**
 * What a suite with a loader project needs on its classpaths, so a build declares none of it: StageWright's loader
 * mod and WorldDriver's on the generated runs only, remapped by loom like any {@code mod*} dependency, and what the
 * tests compile against on the run source set.
 *
 * <p>The runs get the mods through a remap configuration of their own, {@value #RUNTIME}, added to every loom project
 * as loom is applied and empty unless that project is a suite's loader. Loom remaps mod dependencies when its project
 * is evaluated, before this plugin's own hooks there, so the configuration exists from the start and its dependencies
 * are added as loom resolves it, by which time the suites are configured. Its source set is the run source set, so
 * {@code runClient}, which starts from {@code main}, goes without both mods.
 */
final class SuiteDependencies {

    static final String RUNTIME = "modStagewrightTestRuntime";
    private static final String[] LOOM_PLUGINS = {"dev.architectury.loom", "fabric-loom", "net.fabricmc.fabric-loom"};

    private SuiteDependencies() {}

    static void install(Project plugin, NamedDomainObjectContainer<StageWrightTestSuite> tests) {
        repositories(plugin);
        plugin.getRootProject().allprojects(p -> {
            for (String id : LOOM_PLUGINS) {
                p.getPluginManager().withPlugin(id, applied -> {
                    repositories(p);
                    runtimeMods(p, tests);
                });
            }
            p.getConfigurations().configureEach(c -> c.withDependencies(deps -> compileDependencies(p, c, deps, tests)));
        });
    }

    /**
     * Where what this plugin adds comes from, appended after the project's own repositories and asked for nothing but
     * these groups: StageWright's and WorldDriver's, Architectury's, and for a local build of StageWright, mavenLocal.
     * Left out when the settings keep repositories to themselves, which then have to list these.
     */
    private static void repositories(Project project) {
        if (!projectRepositoriesAllowed(project)) return;
        RepositoryHandler repos = project.getRepositories();
        if (repos.findByName("stagewrightNexus") != null) return;
        if (PluginVersion.get().contains("build.local")) {
            repos.mavenLocal(r -> {
                r.setName("stagewrightLocal");
                r.content(c -> c.includeGroup("net.magicterra"));
            });
        }
        repos.maven(r -> {
            r.setName("stagewrightNexus");
            r.setUrl("https://nexus.gardel.top/repository/maven-releases");
            r.content(c -> c.includeGroup("net.magicterra"));
        });
        repos.maven(r -> {
            r.setName("stagewrightArchitectury");
            r.setUrl("https://maven.architectury.dev/");
            r.content(c -> c.includeGroup("dev.architectury"));
        });
    }

    /** False when {@code dependencyResolutionManagement} prefers or demands the settings' repositories. */
    private static boolean projectRepositoriesAllowed(Project project) {
        try {
            Object settings = InvokerHelper.invokeMethod(project.getGradle(), "getSettings", null);
            Object management = InvokerHelper.invokeMethod(settings, "getDependencyResolutionManagement", null);
            Object mode = ((Provider<?>) InvokerHelper.invokeMethod(management, "getRepositoriesMode", null)).getOrNull();
            return mode == null || String.valueOf(mode).equals("PREFER_PROJECT");
        } catch (RuntimeException e) {
            // A Gradle without the mode lets projects declare repositories.
            return true;
        }
    }

    private static void runtimeMods(Project project, NamedDomainObjectContainer<StageWrightTestSuite> tests) {
        Object loom = project.getExtensions().getByName("loom");
        NamedDomainObjectCollection<?> remaps =
                (NamedDomainObjectCollection<?>) InvokerHelper.invokeMethod(loom, "getRemapConfigurations", null);
        if (remaps.findByName(RUNTIME) != null) return;
        Provider<SourceSet> source = project.provider(() -> {
            StageWrightTestSuite suite = GeneratedRuns.suiteOf(project, tests);
            return suite != null ? GeneratedRuns.sourceSet(project, suite)
                    : project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                            .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        });
        InvokerHelper.invokeMethod(loom, "addRemapConfiguration", new Object[] {RUNTIME, (Action<Object>) settings -> {
            property(settings, "getSourceSet").set(source);
            property(settings, "getTargetConfigurationName").set(source.map(SourceSet::getRuntimeOnlyConfigurationName));
            property(settings, "getOnCompileClasspath").set(false);
            property(settings, "getOnRuntimeClasspath").set(true);
            property(settings, "getPublishingMode").set(publishingMode(settings, "NONE"));
        }});
        // Not transitive, as loom made it: StageWright and WorldDriver carry what they need, and what Architectury
        // asks for — Fabric Loader, Fabric API — is the project's own; a second Fabric Loader stops the game at once.
        project.getConfigurations().named(RUNTIME).configure(c -> c.setTransitive(false));
        project.getConfigurations().named(RUNTIME).configure(c -> c.withDependencies(deps -> {
            StageWrightTestSuite suite = GeneratedRuns.suiteOf(project, tests);
            if (suite == null) return;
            String loader = loaderName(loom, project, suite);
            add(project, deps, "net.magicterra:mc_stagewright-" + loader + ":" + PluginVersion.get(), null);
            if (suite.getIncludeWorldDriver().getOrElse(true)) {
                add(project, deps, "net.magicterra:worlddriver-" + loader + ":" + PluginVersion.worldDriver(), null);
                // WorldDriver needs it beside it; a project that has its own keeps that one, a second would clash.
                if (!declaresArchitectury(project)) {
                    deps.add(project.getDependencies().create(
                            "dev.architectury:architectury-" + loader + ":" + PluginVersion.architectury()));
                }
            }
        }));
    }

    private static boolean declaresArchitectury(Project project) {
        for (Configuration c : project.getConfigurations()) {
            if (c.getName().equals(RUNTIME)) continue;
            for (Dependency d : c.getDependencies()) {
                if ("dev.architectury".equals(d.getGroup()) && d.getName().startsWith("architectury")) return true;
            }
        }
        return false;
    }

    /**
     * What the tests compile against, compile-only on the run source set of a suite's loader project: at run time
     * both apis are inside the mods, and a second copy beside them would be loaded twice.
     */
    private static void compileDependencies(Project project, Configuration configuration, DependencySet deps,
                                            NamedDomainObjectContainer<StageWrightTestSuite> tests) {
        if (!configuration.getName().endsWith("ompileOnly")) return;
        StageWrightTestSuite suite = GeneratedRuns.suiteOf(project, tests);
        if (suite == null || !suite.getTestDependencies().getOrElse(true)) return;
        String sourceSet = suite.getRunSourceSet().getOrElse(GeneratedRuns.TESTMOD);
        String name = sourceSet.equals(SourceSet.MAIN_SOURCE_SET_NAME) ? "compileOnly" : sourceSet + "CompileOnly";
        if (!configuration.getName().equals(name)) return;
        add(project, deps, "net.magicterra:mc_stagewright-api:" + PluginVersion.get(), "dev");
        if (suite.getIncludeWorldDriver().getOrElse(true)) {
            add(project, deps, "net.magicterra:worlddriver-api:" + PluginVersion.worldDriver(), "dev");
        }
        deps.add(project.getDependencies().platform("org.junit:junit-bom:" + PluginVersion.junit()));
        deps.add(project.getDependencies().create("org.junit.jupiter:junit-jupiter-api"));
    }

    private static void add(Project project, DependencySet deps, String coordinates, String classifier) {
        ExternalModuleDependency dependency = (ExternalModuleDependency) project.getDependencies()
                .create(classifier == null ? coordinates : coordinates + ":" + classifier);
        // A build published to mavenLocal keeps its version while its contents move, and loom keys its remapped
        // copy on the coordinates: without this a republished StageWright never reaches the run.
        dependency.setChanging(true);
        deps.add(dependency);
    }

    /** {@code fabric} or {@code neoforge}: the loader jar to take. */
    private static String loaderName(Object loom, Project project, StageWrightTestSuite suite) {
        if (is(loom, "isNeoForge")) return "neoforge";
        if (is(loom, "isForge")) {
            throw new GradleException("stagewright test suite '" + suite.getName() + "': " + project.getPath()
                    + " is a Forge project, and StageWright has no Forge mod");
        }
        return "fabric";
    }

    /** A loom predicate that only Architectury Loom has; false under Fabric Loom. */
    private static boolean is(Object loom, String method) {
        try {
            loom.getClass().getMethod(method);
        } catch (NoSuchMethodException e) {
            return false;
        }
        return Boolean.TRUE.equals(InvokerHelper.invokeMethod(loom, method, null));
    }

    @SuppressWarnings("unchecked")
    private static Property<Object> property(Object settings, String getter) {
        return (Property<Object>) InvokerHelper.invokeMethod(settings, getter, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object publishingMode(Object settings, String name) {
        try {
            Class type = settings.getClass().getClassLoader()
                    .loadClass("net.fabricmc.loom.api.RemapConfigurationSettings$PublishingMode");
            return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
        } catch (ClassNotFoundException e) {
            throw new GradleException("this loom has no RemapConfigurationSettings.PublishingMode", e);
        }
    }
}
