package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.magicterra.stagewright.engine.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --with-client}: the joined client runs its own probe and writes its own results, and this
 * CLI judges them exactly as the plugin judges a companion file — the worse verdict wins.
 */
class WithClientVerdictTest {

    private static final String CLIENT_FILE = "stagewright-client-results.jsonl";

    private static void writeClient(Path dir, String outcome) throws IOException {
        Files.writeString(dir.resolve(CLIENT_FILE), String.join("\n",
                "{\"type\":\"suite\",\"loader\":\"neoforge\",\"registered\":"
                        + "[{\"name\":\"client.damageSourceAcrossTheWire\",\"required\":true,\"canary\":\"NONE\"}]}",
                "{\"type\":\"scene\",\"name\":\"client.damageSourceAcrossTheWire\",\"outcome\":\""
                        + outcome + "\",\"ticks\":80,\"wallMs\":4000,\"reason\":\"\"}",
                "{\"type\":\"done\",\"scenes\":1}"), StandardCharsets.UTF_8);
    }

    @Test
    void provisioningClearsTheFileTheClientActuallyWrites(@TempDir Path clientDir) {
        assertEquals(List.of(clientDir.resolve(CLIENT_FILE)), Main.clientStaleResults(clientDir));
    }

    private static Verdict.Result server(int code) {
        return new Verdict.Result(code, List.of());
    }

    @Test
    void aFailingClientProbeTurnsAGreenServerRed(@TempDir Path clientDir) throws IOException {
        // The failure this topology exists to catch: the server is fine, the wire is not.
        writeClient(clientDir, "TIMEOUT");
        List<String> out = new ArrayList<>();
        assertEquals(1, Main.judgeWithClient(server(0), clientDir, out::add));
        assertTrue(out.stream().anyMatch(l -> l.contains("CLIENT VERDICT: RED")), out.toString());
        assertTrue(out.stream().anyMatch(l -> l.contains("VERDICT (server and client): RED")),
                out.toString());
    }

    @Test
    void aClientThatWroteNothingIsEnv(@TempDir Path clientDir) throws IOException {
        List<String> out = new ArrayList<>();
        assertEquals(3, Main.judgeWithClient(server(0), clientDir, out::add));
        assertTrue(out.stream().anyMatch(l -> l.contains("wrote no results")), out.toString());
    }

    @Test
    void aPassingClientLeavesTheServersVerdictAlone(@TempDir Path clientDir) throws IOException {
        writeClient(clientDir, "PASS");
        assertEquals(0, Main.judgeWithClient(server(0), clientDir, l -> { }));
        assertEquals(2, Main.judgeWithClient(server(2), clientDir, l -> { }));
    }

    @Test
    void aFilteredServerKeepsItsSuffixOnTheRunsVerdict(@TempDir Path clientDir) throws IOException {
        writeClient(clientDir, "FAIL");
        List<String> out = new ArrayList<>();
        Main.judgeWithClient(new Verdict.Result(0, List.of(), true), clientDir, out::add);
        assertTrue(out.stream().anyMatch(l -> l.contains("RED (FILTERED")), out.toString());
    }
}
