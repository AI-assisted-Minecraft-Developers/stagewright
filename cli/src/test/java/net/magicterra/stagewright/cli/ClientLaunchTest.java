package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where the user's and our own JVM properties land on a client's command line. */
class ClientLaunchTest {

    @Test
    void aPropertyTheVersionAlsoSetsIsOursBecauseItComesLast(@TempDir Path install) throws IOException {
        Path json = Files.createDirectories(install.resolve("versions/1.21.1")).resolve("1.21.1.json");
        Files.writeString(json, """
                {"id": "1.21.1", "mainClass": "net.minecraft.client.main.Main", "assetIndex": {"id": "17"},
                 "libraries": [],
                 "arguments": {"jvm": ["-Djava.library.path=${natives_directory}", "-cp", "${classpath}"],
                               "game": ["--username", "${auth_player_name}"]}}
                """);

        List<String> command = ClientLaunch.command(install, install.resolve("game"), "1.21.1", "java",
                List.of("-Djava.library.path=/mine"), List.of(), "StageWright");
        int versions = command.indexOf("-cp");
        int ours = command.indexOf("-Djava.library.path=/mine");
        int main = command.indexOf("net.minecraft.client.main.Main");
        assertTrue(versions < ours && ours < main, command.toString());
    }

    @Test
    void theJoiningClientGetsTheUsersPropertiesToo() {
        assertEquals(List.of("-Dorg.lwjgl.glfw.libname=/x.so", "-Dstagewright.client.connect=127.0.0.1:25565",
                        "-Dfabric.addMods=a.jar"),
                Main.companionProps(List.of("-Dorg.lwjgl.glfw.libname=/x.so"), "127.0.0.1:25565",
                        List.of("-Dfabric.addMods=a.jar")));
    }
}
