package net.magicterra.stagewright.engine;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * The engine and the supervision agent run outside the game, on a Gradle buildscript classpath or in a game's system
 * class loader before any mod loader starts, so neither may link against a game, loader or WorldDriver class.
 */
class ArchitectureTest {

    @Test
    void theEngineUsesNoGameClass() {
        JavaClasses engine = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("net.magicterra.stagewright.engine");
        noGameClasses().check(engine);
    }

    @Test
    void theAgentUsesNoGameClass() throws IOException {
        try (JarFile agent = new JarFile(System.getProperty("stagewright.agentJar"))) {
            noGameClasses().check(new ClassFileImporter().importJar(agent));
        }
    }

    private static ArchRule noGameClasses() {
        return noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "net.minecraft..", "net.fabricmc..", "net.neoforged..", "net.magicterra.worlddriver..")
                .because("this code runs where no game or WorldDriver class can load");
    }
}
