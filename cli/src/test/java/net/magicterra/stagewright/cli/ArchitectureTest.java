package net.magicterra.stagewright.cli;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which StageWright module may depend on what, checked on the compiled classes: the CLI's own, and the two loader jars
 * it carries, which hold the api, common and loader modules' classes as shipped.
 */
class ArchitectureTest {

    /** Packages that are built on WorldDriver by design: common's driver and JUnit runtime, and api's test API. */
    private static final String[] DRIVES_WORLDDRIVER = {
        "net.magicterra.stagewright.driver..",
        "net.magicterra.stagewright.jupiter..",
        "net.magicterra.stagewright.test.."
    };

    /** Classes WorldDriver nests in its own jar; a second copy in StageWright's would load twice. */
    private static final String[] NOT_CARRIED = {"net/magicterra/worlddriver/", "dev/latvian/mods/rhino/", "io/netty/"};

    /** What a game's side of StageWright must contain for the test engine to drive it. */
    private static final String[] CONTRACT = {
        "net/magicterra/stagewright/jupiter/JupiterRun.class",
        "net/magicterra/stagewright/test/Game.class",
        "net/magicterra/stagewright/capability/CapabilityProvider.class"
    };

    @TempDir
    Path dir;

    @Test
    void theCliUsesNoGameNorWorldDriverClass() {
        JavaClasses cli = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("net.magicterra.stagewright.cli");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "net.minecraft..", "net.fabricmc..", "net.neoforged..", "net.magicterra.worlddriver..")
                .because("the CLI runs on a machine with no Minecraft class path")
                .check(cli);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fabric", "neoforge"})
    void aLoaderJarUsesWorldDriverOnlyWhereItDrivesIt(String loader) throws IOException {
        try (JarFile jar = loaderJar(loader)) {
            JavaClasses classes = new ClassFileImporter().importJar(jar);
            noClasses()
                    .that()
                    .resideInAPackage("net.magicterra.stagewright..")
                    .and()
                    .resideOutsideOfPackages(DRIVES_WORLDDRIVER)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("net.magicterra.worlddriver..")
                    .because("WorldDriver belongs in common's driver package")
                    .check(classes);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"fabric", "neoforge"})
    void aLoaderJarCarriesTheContractAndNoDriverOrItsLibraries(String loader) throws IOException {
        try (JarFile jar = loaderJar(loader)) {
            List<String> carried = new ArrayList<>();
            for (JarEntry entry : jar.stream().toList()) {
                for (String prefix : NOT_CARRIED) {
                    if (entry.getName().startsWith(prefix)) {
                        carried.add(entry.getName());
                    }
                }
            }
            assertEquals(List.of(), carried, loader + " jar carries the driver or a runtime library of its own");
            for (String name : CONTRACT) {
                assertNotNull(jar.getEntry(name), loader + " jar is missing framework contract " + name);
            }
        }
    }

    /** The loader jar this CLI carries, as the loader's build assembles it. */
    private JarFile loaderJar(String loader) throws IOException {
        String resource = "/mods/mc_stagewright-" + loader + ".jar";
        Path copy = dir.resolve(loader + ".jar");
        try (InputStream in = ArchitectureTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "no " + resource + " on the class path");
            Files.copy(in, copy);
        }
        return new JarFile(copy.toFile());
    }
}
