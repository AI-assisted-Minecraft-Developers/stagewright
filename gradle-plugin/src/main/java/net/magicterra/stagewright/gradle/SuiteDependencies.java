package net.magicterra.stagewright.gradle;

import java.util.Locale;
import java.util.Map;

import org.codehaus.groovy.runtime.InvokerHelper;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectCollection;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyResolveDetails;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;

/**
 * What a suite with a loader project needs on its classpaths, as defaults of two configurations of the plugin's own:
 * StageWright's loader mod and WorldDriver's in {@value #RUNTIME}, on the generated runs only and remapped by loom
 * like any {@code mod*} dependency, and what the tests compile against in {@value #COMPILE}. A build that declares
 * anything in either replaces that configuration's defaults; repositories are the build's own (gradle-plugin.md).
 *
 * <p>Loom remaps mod dependencies when its project is evaluated, before this plugin's own hooks there, so
 * {@value #RUNTIME} is added to every loom project as loom is applied, empty unless that project is a suite's loader,
 * and its defaults are filled in as loom resolves it, by which time the suites are configured. Its source set is the
 * run source set, so {@code runClient}, which starts from {@code main}, goes without both mods.
 */
final class SuiteDependencies {

    static final String RUNTIME = "modStagewrightTestRuntime";
    static final String COMPILE = "stagewrightTestCompile";
    private static final String[] LOOM_PLUGINS = {"dev.architectury.loom", "fabric-loom", "net.fabricmc.fabric-loom"};
    // The loader and its api, which the project brings itself: a second Fabric Loader stops the game at once.
    private static final String[] PROJECT_GROUPS = {"net.fabricmc", "net.fabricmc.fabric-api", "net.neoforged"};

    private SuiteDependencies() {}

    static void install(Project plugin, NamedDomainObjectContainer<StageWrightTestSuite> tests) {
        plugin.getRootProject().allprojects(p -> {
            for (String id : LOOM_PLUGINS) {
                p.getPluginManager().withPlugin(id, applied -> {
                    runtimeMods(p, tests);
                    compileConfiguration(p, tests);
                });
            }
        });
    }

    /** Puts {@value #COMPILE} under the run source set, compile-only: at run time both apis are inside the mods. */
    static void compileAgainst(Project loader, StageWrightTestSuite suite, SourceSet source) {
        if (!suite.getTestDependencies().getOrElse(true)) return;
        Configuration compile = loader.getConfigurations().findByName(COMPILE);
        if (compile == null) return;
        loader.getConfigurations().getByName(source.getCompileOnlyConfigurationName()).extendsFrom(compile);
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
        project.getConfigurations().named(RUNTIME).configure(c -> {
            c.setDescription("The mods StageWright's generated runs get: StageWright's and WorldDriver's by default.");
            // Transitive, unlike loom's own, so WorldDriver brings the Architectury it is built against.
            c.setTransitive(true);
            for (String group : PROJECT_GROUPS) c.exclude(Map.of("group", group));
            c.defaultDependencies(deps -> {
                StageWrightTestSuite suite = GeneratedRuns.suiteOf(project, tests);
                if (suite == null) return;
                String loader = loaderName(loom, project, suite);
                add(project, deps, "net.magicterra:mc_stagewright-" + loader + ":" + PluginVersion.get(), null);
                if (suite.getIncludeWorldDriver().getOrElse(true)) {
                    add(project, deps, "net.magicterra:worlddriver-" + loader + ":"
                            + suite.getWorldDriverVersion().get(), null);
                }
            });
            c.getResolutionStrategy().eachDependency(details -> {
                checkWorldDriver(details);
                alignToProject(project, details);
            });
        });
    }

    private static void compileConfiguration(Project project, NamedDomainObjectContainer<StageWrightTestSuite> tests) {
        if (project.getConfigurations().findByName(COMPILE) != null) return;
        project.getConfigurations().create(COMPILE, c -> {
            c.setDescription("What StageWright tests compile against: StageWright's api, WorldDriver's and JUnit"
                    + " Jupiter's by default, the first two in Mojang names.");
            c.setCanBeConsumed(false);
            c.setCanBeResolved(false);
            c.defaultDependencies(deps -> {
                StageWrightTestSuite suite = GeneratedRuns.suiteOf(project, tests);
                if (suite == null) return;
                add(project, deps, "net.magicterra:mc_stagewright-api:" + PluginVersion.get(), "dev");
                if (suite.getIncludeWorldDriver().getOrElse(true)) {
                    add(project, deps, "net.magicterra:worlddriver-api:" + suite.getWorldDriverVersion().get(), "dev");
                }
                deps.add(project.getDependencies().platform("org.junit:junit-bom:" + PluginVersion.junit()));
                deps.add(project.getDependencies().create("org.junit.jupiter:junit-jupiter-api"));
            });
        });
        // Not resolvable itself, so the check runs where it is resolved: the classpaths that extend it.
        project.getConfigurations().configureEach(c -> {
            if (c.getName().endsWith("ompileClasspath")) {
                c.getResolutionStrategy().eachDependency(SuiteDependencies::checkWorldDriver);
            }
        });
    }

    /**
     * Refuses a WorldDriver outside the line StageWright links against, which would otherwise fail only once a game
     * starts. Compared as SemVer, as the loaders compare it: Gradle orders {@code 0.1.0-build.N} below
     * {@code 0.1.0-0}, so the range cannot be handed to Gradle as it is.
     */
    private static void checkWorldDriver(DependencyResolveDetails details) {
        var requested = details.getRequested();
        if (!"net.magicterra".equals(requested.getGroup()) || !requested.getName().startsWith("worlddriver-")) return;
        String version = requested.getVersion();
        if (version == null || version.isEmpty()) return;
        String range = PluginVersion.worldDriverRange();
        if (!SemVer.inRange(version, range)) {
            throw new GradleException("WorldDriver " + version + " is outside " + range + ", the line StageWright "
                    + PluginVersion.get() + " links against; set worldDriverVersion to a build in it");
        }
    }

    /**
     * WorldDriver's Architectury, moved to the version the project declares elsewhere if it does: the project's own
     * is on the runs' classpath too, and two versions of one mod stop the game.
     */
    private static void alignToProject(Project project, DependencyResolveDetails details) {
        var requested = details.getRequested();
        if (!"dev.architectury".equals(requested.getGroup())) return;
        for (Configuration c : project.getConfigurations()) {
            if (c.getName().equals(RUNTIME)) continue;
            for (Dependency d : c.getDependencies()) {
                if (requested.getGroup().equals(d.getGroup()) && requested.getName().equals(d.getName())
                        && d.getVersion() != null) {
                    details.useVersion(d.getVersion());
                    details.because("the project declares this version");
                    return;
                }
            }
        }
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
