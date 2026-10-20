package net.magicterra.stagewright.junit.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.magicterra.stagewright.contract.AttachedRun;
import net.magicterra.stagewright.contract.DriverJson;
import net.magicterra.stagewright.contract.RpcDriverBinding;
import net.magicterra.stagewright.contract.SceneSpec;
import net.magicterra.stagewright.contract.Scripts;
import net.magicterra.stagewright.contract.remote.AttachedBridge;
import net.magicterra.stagewright.junit.Face;
import net.magicterra.stagewright.junit.RequiresFace;
import net.magicterra.stagewright.junit.StageWright;
import net.magicterra.stagewright.junit.StageWrightExtension;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scene files run out of process, against the arena the game allocates for each of them.
 *
 * <p>The claim under test is that an attached scene stands where an in-process one does: a plot of
 * its own in loaded chunks, the same {@code SceneContext} methods under the same names, the world
 * pin, and the sweep and audit when it ends. Each check is made from the scene file's side, so what
 * it proves is what a pack author's file would see.
 *
 * <p>Runs before the suite trigger's class and not after it: a run is refused once the in-game
 * suite has started, because the suite pins the world itself.
 */
@ExtendWith(StageWrightExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
@RequiresFace(Face.SERVER)
@Order(Integer.MAX_VALUE - 1)
class AttachedArenaContractTest {

    private static List<Map<String, Object>> run(StageWright tk, Path dir, String js, List<String> log)
            throws IOException {
        Path scenes = Files.createDirectories(dir.resolve("scenes"));
        Files.writeString(scenes.resolve("scenes.js"), js, StandardCharsets.UTF_8);
        List<SceneSpec> specs = Scripts.load(scenes, AttachedBridge.setup(log::add), log::add);
        Path results = dir.resolve("attached-results.jsonl");
        new AttachedRun(specs, new RpcDriverBinding(tk.rpc(), 60_000), "server", log::add, System::currentTimeMillis)
                .run(results);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(results)) {
            JsonElement parsed = JsonParser.parseString(line);
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) DriverJson.fromJson(parsed);
            if ("scene".equals(row.get("type"))) rows.add(row);
        }
        return rows;
    }

    private static String describe(Map<String, Object> row, List<String> log) {
        return row + "\n" + String.join("\n", log);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> row) {
        return (Map<String, Object>) row.get("data");
    }

    @Test
    void aSceneBuildsInAnArenaOfItsOwnAndReadsItBack(StageWright tk, @TempDir Path dir) throws IOException {
        List<String> log = new ArrayList<>();
        List<Map<String, Object>> rows = run(tk, dir, """
                scene('attach.builds', 20, function (s) {
                    s.record('originX', s.originX());
                    s.expect(s.originX()).as('the arena is a grid slot, not the world origin').isAtLeast(100000);
                    s.setBlock(0, 0, 0, block('minecraft:gold_block'));
                    s.expect(s.blockAt(0, 0, 0)).as('what was placed').isEqualTo(block('minecraft:gold_block'));
                    s.assertBlock(0, 0, 0, block('minecraft:gold_block'));
                    s.expectBlock(0, 0, 1).as('untouched cell').isEqualTo(block('minecraft:air'));
                    s.cleanup(function () { s.setBlock(0, 0, 0, block('minecraft:air')); });
                });
                """, log);

        assertEquals("PASS", rows.get(0).get("outcome"), describe(rows.get(0), log));
        assertTrue(data(rows.get(0)).containsKey("prep.ticks"), "the game's arena timings travel with the scene: " + rows.get(0));
    }

    @Test
    void everySceneGetsAPlotNoOtherSceneUsed(StageWright tk, @TempDir Path dir) throws IOException {
        List<String> log = new ArrayList<>();
        List<Map<String, Object>> rows = run(tk, dir, """
                scene('attach.first', 20, function (s) { s.record('x', s.originX()); s.setBlock(0, 0, 0, block('minecraft:stone')); });
                scene('attach.second', 20, function (s) {
                    s.record('x', s.originX());
                    s.expect(s.blockAt(0, 0, 0)).as('a fresh plot holds nothing the last scene built').isEqualTo(block('minecraft:air'));
                });
                """, log);

        assertEquals("PASS", rows.get(0).get("outcome"), describe(rows.get(0), log));
        assertEquals("PASS", rows.get(1).get("outcome"), describe(rows.get(1), log));
        assertFalse(data(rows.get(0)).get("x").equals(data(rows.get(1)).get("x")), "two scenes shared a plot: " + rows);
    }

    @Test
    void whatASceneLeavesInTheWorldIsReported(StageWright tk, @TempDir Path dir) throws IOException {
        List<String> log = new ArrayList<>();
        // A gamerule, not an entity or the weather: the sweep removes an entity a scene forgot and the
        // clock reset clears the weather, both before the audit looks, in process and here alike. What
        // the audit reports is what survives them.
        List<Map<String, Object>> rows = run(tk, dir, """
                scene('attach.leaks', 20, function (s) { s.command('gamerule keepInventory true'); });
                scene('attach.putsItBack', 20, function (s) { s.command('gamerule keepInventory false'); });
                scene('attach.noLeak', 20, function (s) { });
                """, log);

        assertEquals("PASS", rows.get(0).get("outcome"), describe(rows.get(0), log));
        assertTrue(String.valueOf(data(rows.get(0)).get("leaks")).contains("gamerule"),
                "a gamerule left changed is reported as in process: " + rows.get(0));
        assertEquals("PASS", rows.get(2).get("outcome"), describe(rows.get(2), log));
        assertFalse(data(rows.get(2)).containsKey("leaks"), "a scene that left nothing reports nothing: " + rows.get(2));
    }

    @Test
    void aCheckThatFailsOnTheGamesSideFailsTheScene(StageWright tk, @TempDir Path dir) throws IOException {
        List<String> log = new ArrayList<>();
        List<Map<String, Object>> rows = run(tk, dir, """
                scene('attach.softBlock', 20, function (s) {
                    s.checkBlock(0, 0, 0).isEqualTo(block('minecraft:gold_block'));
                });
                """, log);

        assertEquals("FAIL", rows.get(0).get("outcome"), describe(rows.get(0), log));
    }

    @Test
    void aNameTheContextDoesNotHaveFailsWithTheGamesAnswer(StageWright tk, @TempDir Path dir) throws IOException {
        List<String> log = new ArrayList<>();
        List<Map<String, Object>> rows = run(tk, dir, """
                scene('attach.noSuchVerb', 20, function (s) { s.noSuchVerb(1); });
                """, log);

        assertEquals("FAIL", rows.get(0).get("outcome"), describe(rows.get(0), log));
        assertTrue(String.valueOf(rows.get(0).get("reason")).contains("noSuchVerb"), describe(rows.get(0), log));
    }

    @Test
    void anObjectTheGameHandsBackIsHeldAndAskedThings(StageWright tk, @TempDir Path dir) throws IOException {
        List<String> log = new ArrayList<>();
        List<Map<String, Object>> rows = run(tk, dir, """
                scene('attach.objects', 20, function (s) {
                    var said = s.command('time query daytime');
                    s.expect(said.output().length).as('a held CommandResult answers output()').isGreaterThan(0);
                    s.expect(s.mods().loaded('mc_testkit')).as('the loader asked through a held facet').isTrue();
                });
                """, log);

        assertEquals("PASS", rows.get(0).get("outcome"), describe(rows.get(0), log));
    }

    @Test
    void theHarnessVerbsRunOnTheServerThreadLikeInProcess(StageWright tk, @TempDir Path dir) throws IOException {
        List<String> log = new ArrayList<>();
        List<Map<String, Object>> rows = run(tk, dir, """
                scene('attach.harness', 20, function (s) {
                    var counted = s.driver('mc.test.bots', { op: 'count' });
                    s.expect(counted).as('mc.test.bots answered through the server thread').isNotNull();
                });
                scene('attach.ownsTheRun', 20, function (s) { s.driver('mc.test.attached.end'); });
                """, log);

        assertEquals("PASS", rows.get(0).get("outcome"), describe(rows.get(0), log));
        assertEquals("FAIL", rows.get(1).get("outcome"), describe(rows.get(1), log));
    }

    @Test
    void tickWaitsAreRefusedByNameNotApproximated(StageWright tk, @TempDir Path dir) throws IOException {
        List<String> log = new ArrayList<>();
        List<Map<String, Object>> rows = run(tk, dir, """
                scene('attach.noTicks', 20, function (s) { s.ticks(); });
                """, log);

        assertEquals("FAIL", rows.get(0).get("outcome"), describe(rows.get(0), log));
        assertTrue(String.valueOf(rows.get(0).get("reason")).contains("waitUntil"), describe(rows.get(0), log));
    }
}
