package net.magicterra.stagewright.junit.instrument;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.magicterra.stagewright.junit.StageWright;
import net.magicterra.stagewright.junit.Face;
import net.magicterra.stagewright.junit.RequiresFace;
import net.magicterra.stagewright.junit.StageWrightExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static net.magicterra.stagewright.junit.instrument.Contract.at;
import static net.magicterra.stagewright.junit.instrument.Contract.cmd;
import static net.magicterra.stagewright.junit.instrument.Contract.num;
import static net.magicterra.stagewright.junit.instrument.Contract.pos;
import static net.magicterra.stagewright.junit.instrument.Contract.query;
import static net.magicterra.stagewright.junit.instrument.Contract.queryRows;
import static net.magicterra.stagewright.junit.instrument.Contract.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * World writes and the reads that must agree with them.
 *
 * <p><b>Every check here is dual-source on purpose</b>: vanilla writes it, the driver reads it back
 * (or the reverse). A driver that wrote and read through one broken code path would pass a
 * self-consistent check and fail these — which is the only kind of world assertion worth making
 * from outside the game.
 *
 * <p>Coordinates are pinned high (y=200) and far apart per test, so nothing here depends on terrain
 * and no two tests collide regardless of the order JUnit picks.
 */
@ExtendWith(StageWrightExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
@RequiresFace(Face.SERVER)
class WorldContractTest {

    @Test
    void vanillaSetblockIsVisibleToTheDriverRead(StageWright tk) {
        cmd(tk, "setblock 0 200 0 minecraft:gold_block");
        JsonArray found = queryRows(tk, query("blocks", pos(0, 200, 0), 1, "minecraft:gold_block"));
        assertEquals(1, found.size(), "driver read disagrees with the vanilla write: " + found);
        assertEquals(pos(0, 200, 0), at(found, 0).getAsJsonObject("pos"),
                "readback landed at the wrong position: " + found);
    }

    @Test
    void fillPlacesAndCountsEveryBlock(StageWright tk) {
        JsonObject params = new JsonObject();
        params.add("from", pos(4, 200, 4));
        params.add("to", pos(6, 202, 6));
        params.addProperty("type", "minecraft:polished_andesite");
        // A refused fill is an error envelope and throws from call(); a reply is a fill that ran.
        JsonObject r = tk.call("mc.action.fill", params);
        assertEquals(27L, num(r, "placed"), "3x3x3 fill reported the wrong count: " + r);

        JsonArray found = queryRows(tk, query("blocks", pos(5, 201, 5), 2, "minecraft:polished_andesite"));
        assertEquals(27, found.size(), "query readback disagrees with the reported count");
    }

    @Test
    void restorePutsBackWhatTheSnapshotHeld(StageWright tk) {
        JsonObject snapParams = new JsonObject();
        snapParams.add("from", pos(10, 200, 10));
        snapParams.add("to", pos(12, 202, 12));
        JsonObject snap = tk.call("mc.world.snapshot", snapParams);
        assertFalse(str(snap, "id").isEmpty(), "snapshot answered no id: " + snap);
        assertEquals(27L, num(snap, "blocks"), "a 3x3x3 snapshot must hold 27 cells: " + snap);

        cmd(tk, "setblock 11 201 11 minecraft:emerald_block");

        JsonObject restoreParams = new JsonObject();
        restoreParams.addProperty("id", str(snap, "id"));
        restoreParams.addProperty("discard", true);
        JsonObject restored = tk.call("mc.world.restore", restoreParams);
        assertEquals(str(snap, "id"), str(restored, "id"), "restore answered for another snapshot: " + restored);

        JsonArray left = queryRows(tk, query("blocks", pos(11, 201, 11), 2, "minecraft:emerald_block"));
        assertEquals(0, left.size(), "restore left the marker block behind: " + left);
    }
}
