package net.magicterra.stagewright.gradle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The defaults of the plugin's configurations, in a build where more than one project applies the plugin. The
 * ModDevGradle here is a stand-in in {@code buildSrc} with the members the plugin calls.
 */
class SuiteDependenciesTest {

    @TempDir
    Path dir;

    private static final String FAKE_MOD_DEV = """
            import javax.inject.Inject
            import org.gradle.api.Named
            import org.gradle.api.NamedDomainObjectContainer
            import org.gradle.api.Plugin
            import org.gradle.api.Project
            import org.gradle.api.file.DirectoryProperty
            import org.gradle.api.provider.ListProperty
            import org.gradle.api.provider.Property
            import org.gradle.api.provider.SetProperty
            import org.gradle.api.tasks.SourceSet

            abstract class FakeMod implements Named {
                private final String name
                @Inject FakeMod(String name) { this.name = name }
                String getName() { name }
                abstract ListProperty<SourceSet> getModSourceSets()
            }

            abstract class FakeRun implements Named {
                private final String name
                @Inject FakeRun(String name) { this.name = name }
                String getName() { name }
                abstract DirectoryProperty getGameDirectory()
                abstract Property<SourceSet> getSourceSet()
                abstract SetProperty<Object> getLoadedMods()
                void server() {}
                void client() {}
                void disableIdeRun() {}
                void programArgument(String a) {}
                void jvmArgument(String a) {}
                void systemProperty(String k, String v) {}
            }

            class FakeNeoForge {
                NamedDomainObjectContainer<FakeRun> runs
                NamedDomainObjectContainer<FakeMod> mods
            }

            class FakeModDev implements Plugin<Project> {
                void apply(Project p) {
                    p.pluginManager.apply('java')
                    p.extensions.add('neoForge', new FakeNeoForge(
                            runs: p.objects.domainObjectContainer(FakeRun),
                            mods: p.objects.domainObjectContainer(FakeMod)))
                }
            }
            """;

    private static String loader(String mod) {
        return """
                plugins {
                    id 'net.neoforged.moddev'
                    id 'net.magicterra.stagewright'
                }
                neoForge.mods.create('%s') { modSourceSets.add(sourceSets.main) }
                stagewright { tests { neoforge { } } }
                """.formatted(mod);
    }

    private BuildResult dependencies(String project) {
        return GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments(project + ":dependencies", "--configuration", "testmodCompileClasspath")
                .build();
    }

    @Test
    void eachProjectApplyingThePluginGetsItsOwnSuitesDefaults() throws Exception {
        Files.createDirectories(dir.resolve("buildSrc/src/main/groovy"));
        Files.writeString(dir.resolve("buildSrc/build.gradle"), """
                plugins { id 'groovy-gradle-plugin' }
                gradlePlugin { plugins { create('moddev') {
                    id = 'net.neoforged.moddev'
                    implementationClass = 'FakeModDev'
                } } }
                """);
        Files.writeString(dir.resolve("buildSrc/src/main/groovy/FakeModDev.groovy"), FAKE_MOD_DEV);
        // Evaluated in name order: the first project's plugin sees the second apply ModDevGradle before its own does.
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'pack'\ninclude 'core', 'essentials'\n");
        for (String project : new String[] {"core", "essentials"}) {
            Files.createDirectories(dir.resolve(project));
            Files.writeString(dir.resolve(project + "/build.gradle"), loader(project));
        }

        for (String project : new String[] {":core", ":essentials"}) {
            String out = dependencies(project).getOutput();
            for (String module : new String[] {"mc_stagewright-api", "worlddriver-api", "junit-jupiter-api"}) {
                assertTrue(out.contains(module), project + " compiles against " + module + ":\n" + out);
            }
        }
    }
}
