package net.magicterra.stagewright.gradle;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The plugin runs in Gradle, where no game class can load. It names loader plugins, their Maven groups and Loom's
 * classes in strings, which is allowed; linking against any of them is not.
 */
class ArchitectureTest {

    @Test
    void thePluginUsesNoGameNorWorldDriverClass() {
        JavaClasses plugin = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("net.magicterra.stagewright.gradle");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "net.minecraft..", "net.fabricmc..", "net.neoforged..", "net.magicterra.worlddriver..")
                .because("the plugin runs in Gradle, where no game class can load")
                .check(plugin);
    }
}
