package net.magicterra.stagewright.gradle;

import java.util.HashSet;
import java.util.Set;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

/**
 * Puts the JUnit Platform StageWright's in-game runner needs on the dev runs a test suite starts. The loader jars nest
 * it, but loom drops nested jars when it remaps a mod dependency, so a dev run would start without it.
 *
 * <p>Only under loom, into {@code forgeRuntimeLibrary} on a NeoForge or Forge project and {@code localRuntime} on a
 * Fabric one. Under ModDevGradle nothing is added. Added as the configuration resolves, so that the projects are the
 * ones the suites name by then, whatever order the build configured them in.
 */
// JUnit is the framework's own name; the run-of-capitals rule would force a spelling nobody searches for.
@SuppressWarnings("checkstyle:abbreviationaswordinname")
final class DevJUnitRuntime {

    private static final String[] MODULES = {
        "org.junit.platform:junit-platform-launcher",
        "org.junit.platform:junit-platform-reporting",
        "org.junit.jupiter:junit-jupiter-engine",
    };

    private DevJUnitRuntime() {}

    // The identity test is on purpose: it asks whether this configuration is the one target() found for the project.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    static void install(
            Project plugin,
            NamedDomainObjectContainer<StageWrightTestSuite> tests,
            NamedDomainObjectContainer<StageWrightTopology> topologies) {
        plugin.getRootProject()
                .allprojects(p -> p.getConfigurations().configureEach(c -> {
                    if (!c.getName().equals("forgeRuntimeLibrary")
                            && !c.getName().equals("localRuntime")) {
                        return;
                    }
                    c.withDependencies(dependencies -> {
                        if (target(p) != c) {
                            return;
                        }
                        if (!runProjects(plugin, tests, topologies).contains(p.getPath())) {
                            return;
                        }
                        dependencies.add(p.getDependencies().platform("org.junit:junit-bom:" + PluginVersion.junit()));
                        for (String module : MODULES) {
                            dependencies.add(p.getDependencies().create(module));
                        }
                    });
                }));
    }

    /** Where a loom project keeps runtime-only libraries; null for a project not under loom. */
    private static Configuration target(Project p) {
        if (p.getExtensions().findByName("loom") == null) {
            return null;
        }
        Configuration forge = p.getConfigurations().findByName("forgeRuntimeLibrary");
        return forge != null ? forge : p.getConfigurations().findByName("localRuntime");
    }

    /** The projects whose run tasks start the games of some suite's topologies. */
    private static Set<String> runProjects(
            Project plugin,
            NamedDomainObjectContainer<StageWrightTestSuite> tests,
            NamedDomainObjectContainer<StageWrightTopology> topologies) {
        Set<String> projects = new HashSet<>();
        for (StageWrightTestSuite suite : tests) {
            if (GeneratedRuns.generates(suite)) {
                projects.add(GeneratedRuns.loader(plugin, suite).getPath());
            }
            for (String declared : suite.getTopologies().get().values()) {
                StageWrightTopology topology = topologies.findByName(declared);
                String runTask = topology == null ? null : topology.getRunTask().getOrNull();
                if (runTask == null) {
                    continue;
                }
                int lastColon = runTask.lastIndexOf(':');
                projects.add(lastColon < 0 ? plugin.getPath() : lastColon == 0 ? ":" : runTask.substring(0, lastColon));
            }
        }
        return projects;
    }
}
