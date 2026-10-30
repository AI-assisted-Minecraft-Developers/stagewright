package net.magicterra.stagewright.gradle;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectCollection;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;

/**
 * What a suite with a loader project needs on its classpaths, as defaults of two configurations of the plugin's own:
 * StageWright's loader mod and WorldDriver's in {@value #RUNTIME}, on the generated runs only and remapped by loom like
 * any {@code mod*} dependency, and what the tests compile against in {@value #COMPILE}. A build that declares anything
 * in either replaces that configuration's defaults; repositories are the build's own (gradle-plugin.md).
 *
 * <p>Loom remaps mod dependencies when its project is evaluated, before this plugin's own hooks there, so
 * {@value #RUNTIME} is added to every loom project as loom is applied, empty unless that project is a suite's loader,
 * and its defaults are filled in as loom resolves it, by which time the suites are configured. Its source set is the
 * run source set, so {@code runClient}, which starts from {@code main}, goes without both mods. Under ModDevGradle the
 * mods are plain jars in {@value #MOD_DEV_RUNTIME}, under the run source set's runtime classpath.
 */
final class SuiteDependencies {

    static final String RUNTIME = "modStagewrightTestRuntime";

    /** {@value #RUNTIME}'s counterpart under ModDevGradle, which needs no remapping: NeoForge runs in Mojang names. */
    static final String MOD_DEV_RUNTIME = "stagewrightTestRuntime";

    static final String COMPILE = "stagewrightTestCompile";

    private static final String[] LOOM_PLUGINS = {"dev.architectury.loom", "fabric-loom", "net.fabricmc.fabric-loom"};

    private static final String MOD_DEV_PLUGIN = "net.neoforged.moddev";

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
            p.getPluginManager().withPlugin(MOD_DEV_PLUGIN, applied -> {
                modDevRuntime(p, tests);
                compileConfiguration(p, tests);
            });
        });
    }

    /**
     * Puts the plugin's configurations under the run source set: {@value #COMPILE} compile-only, since at run time both
     * apis are inside the mods, and under ModDevGradle {@value #MOD_DEV_RUNTIME} runtime-only.
     */
    static void attach(Project loader, StageWrightTestSuite suite, SourceSet source) {
        Configuration runtime = loader.getConfigurations().findByName(MOD_DEV_RUNTIME);
        if (runtime != null) {
            loader.getConfigurations()
                    .getByName(source.getRuntimeOnlyConfigurationName())
                    .extendsFrom(runtime);
        }
        if (!suite.getTestDependencies().getOrElse(true)) {
            return;
        }
        Configuration compile = loader.getConfigurations().findByName(COMPILE);
        if (compile == null) {
            return;
        }
        loader.getConfigurations()
                .getByName(source.getCompileOnlyConfigurationName())
                .extendsFrom(compile);
    }

    private static void runtimeMods(Project project, NamedDomainObjectContainer<StageWrightTestSuite> tests) {
        Object loom = project.getExtensions().getByName("loom");
        NamedDomainObjectCollection<?> remaps =
                (NamedDomainObjectCollection<?>) InvokerHelper.invokeMethod(loom, "getRemapConfigurations", null);
        if (remaps.findByName(RUNTIME) != null) {
            return;
        }
        Provider<SourceSet> source = project.provider(() -> {
            StageWrightTestSuite suite = GeneratedRuns.suiteOf(project, tests);
            return suite != null
                    ? GeneratedRuns.sourceSet(project, suite)
                    : project.getExtensions()
                            .getByType(JavaPluginExtension.class)
                            .getSourceSets()
                            .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        });
        InvokerHelper.invokeMethod(loom, "addRemapConfiguration", new Object[] {
            RUNTIME,
            (Action<Object>) settings -> {
                property(settings, "getSourceSet").set(source);
                property(settings, "getTargetConfigurationName")
                        .set(source.map(SourceSet::getRuntimeOnlyConfigurationName));
                property(settings, "getOnCompileClasspath").set(false);
                property(settings, "getOnRuntimeClasspath").set(true);
                property(settings, "getPublishingMode").set(publishingMode(settings, "NONE"));
            }
        });
        project.getConfigurations().named(RUNTIME).configure(c -> {
            c.setDescription("The mods StageWright's generated runs get: StageWright's and WorldDriver's by default.");
            // Transitive, unlike loom's own, so a mod a build declares here brings the mods its POM names.
            c.setTransitive(true);
            // On the configuration too, for what a build declares there: loom resolves it alone, so nothing inherits
            // it.
            for (String group : PROJECT_GROUPS) {
                c.exclude(Map.of("group", group));
            }
            runtimeDefaults(project, c, tests, suite -> loaderName(loom, project, suite));
        });
        constrainWorldDriver(project, RUNTIME, "worlddriver-fabric", "worlddriver-neoforge");
    }

    /**
     * Under ModDevGradle, a plain configuration the run source set's runtime classpath extends: the mods are on the
     * same classpath as the project's own, so Gradle settles a library both declare without help.
     */
    private static void modDevRuntime(Project project, NamedDomainObjectContainer<StageWrightTestSuite> tests) {
        if (project.getConfigurations().findByName(MOD_DEV_RUNTIME) != null) {
            return;
        }
        project.getConfigurations().create(MOD_DEV_RUNTIME, c -> {
            c.setDescription("The mods StageWright's generated runs get: StageWright's and WorldDriver's by default.");
            c.setCanBeConsumed(false);
            c.setCanBeResolved(false);
            runtimeDefaults(project, c, tests, suite -> "neoforge");
        });
        constrainWorldDriver(project, MOD_DEV_RUNTIME, "worlddriver-neoforge");
    }

    /**
     * StageWright's loader mod and WorldDriver's, and what WorldDriver brings except the loader's own groups. Excluded
     * on each dependency, not on the configuration: a configuration's exclusions pass to every configuration extending
     * it, and under ModDevGradle that is the run's classpath, which then loses NeoForge's DevLaunch.
     */
    private static void runtimeDefaults(
            Project project,
            Configuration c,
            NamedDomainObjectContainer<StageWrightTestSuite> tests,
            Function<StageWrightTestSuite, String> loaderOf) {
        c.defaultDependencies(deps -> {
            StageWrightTestSuite suite = GeneratedRuns.suiteOf(project, tests);
            if (suite == null) {
                return;
            }
            String loader = loaderOf.apply(suite);
            add(project, deps, "net.magicterra:mc_stagewright-" + loader + ":" + PluginVersion.get(), null);
            if (suite.getIncludeWorldDriver().getOrElse(true)) {
                ExternalModuleDependency worldDriver = add(
                        project,
                        deps,
                        "net.magicterra:worlddriver-" + loader + ":"
                                + suite.getWorldDriverVersion().get(),
                        null);
                for (String group : PROJECT_GROUPS) {
                    worldDriver.exclude(Map.of("group", group));
                }
            }
        });
    }

    private static void compileConfiguration(Project project, NamedDomainObjectContainer<StageWrightTestSuite> tests) {
        if (project.getConfigurations().findByName(COMPILE) != null) {
            return;
        }
        project.getConfigurations().create(COMPILE, c -> {
            c.setDescription("What StageWright tests compile against: StageWright's api, WorldDriver's and JUnit"
                    + " Jupiter's by default, the first two in Mojang names.");
            c.setCanBeConsumed(false);
            c.setCanBeResolved(false);
            c.defaultDependencies(deps -> {
                StageWrightTestSuite suite = GeneratedRuns.suiteOf(project, tests);
                if (suite == null) {
                    return;
                }
                // Its POM names WorldDriver's api, at the build StageWright was built against; which one the tests get,
                // if any, is includeWorldDriver's and worldDriverVersion's to say, so the default brings it only below.
                add(project, deps, "net.magicterra:mc_stagewright-api:" + PluginVersion.get(), "dev")
                        .setTransitive(false);
                if (suite.getIncludeWorldDriver().getOrElse(true)) {
                    add(
                            project,
                            deps,
                            "net.magicterra:worlddriver-api:"
                                    + suite.getWorldDriverVersion().get(),
                            "dev");
                }
                deps.add(project.getDependencies().platform("org.junit:junit-bom:" + PluginVersion.junit()));
                deps.add(project.getDependencies().create("org.junit.jupiter:junit-jupiter-api"));
            });
        });
        constrainWorldDriver(project, COMPILE, "worlddriver-api");
    }

    /**
     * Refuses a WorldDriver outside the line StageWright links against, which would otherwise fail only once a game
     * starts. The range's bounds are versions Gradle orders as the loaders do (the stagewright.version plugin in
     * build-logic).
     */
    private static void constrainWorldDriver(Project project, String configuration, String... modules) {
        for (String module : modules) {
            project.getDependencies().getConstraints().add(configuration, "net.magicterra:" + module, c -> {
                c.version(v -> v.strictly(PluginVersion.worldDriverRange()));
                c.because("StageWright " + PluginVersion.get() + " links against this WorldDriver line;"
                        + " set worldDriverVersion to a build in it");
            });
        }
    }

    private static ExternalModuleDependency add(
            Project project, DependencySet deps, String coordinates, String classifier) {
        ExternalModuleDependency dependency = (ExternalModuleDependency)
                project.getDependencies().create(classifier == null ? coordinates : coordinates + ":" + classifier);
        // A build published to mavenLocal keeps its version while its contents move, and loom keys its remapped
        // copy on the coordinates: without this a republished StageWright never reaches the run.
        dependency.setChanging(true);
        deps.add(dependency);
        return dependency;
    }

    /** {@code fabric} or {@code neoforge}: the loader jar to take. */
    private static String loaderName(Object loom, Project project, StageWrightTestSuite suite) {
        if (is(loom, "isNeoForge")) {
            return "neoforge";
        }
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
            Class type = settings.getClass()
                    .getClassLoader()
                    .loadClass("net.fabricmc.loom.api.RemapConfigurationSettings$PublishingMode");
            return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
        } catch (ClassNotFoundException e) {
            throw new GradleException("this loom has no RemapConfigurationSettings.PublishingMode", e);
        }
    }
}
