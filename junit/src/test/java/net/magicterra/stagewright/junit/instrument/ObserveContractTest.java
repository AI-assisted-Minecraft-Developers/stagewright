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
import static net.magicterra.stagewright.junit.instrument.Contract.flag;
import static net.magicterra.stagewright.junit.instrument.Contract.num;
import static net.magicterra.stagewright.junit.instrument.Contract.pos;
import static net.magicterra.stagewright.junit.instrument.Contract.query;
import static net.magicterra.stagewright.junit.instrument.Contract.rows;
import static net.magicterra.stagewright.junit.instrument.Contract.str;
import static net.magicterra.stagewright.junit.instrument.Contract.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code mc.observe.*} and {@code mc.query} report about a world someone else wrote. */
@ExtendWith(StageWrightExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
@RequiresFace(Face.SERVER)
class ObserveContractTest {

    /**
     * A container read must carry an item's full wear, and the three fields must agree with each
     * other and with vanilla's numbers — a diamond pickaxe damaged to 123 has 1561 max and 1438
     * left. Pinned rather than merely "present" because the failure this exists for was a read that
     * returned the fields with plausible-but-wrong values.
     */
    @Test
    void containerReadCarriesItemDurability(StageWright tk) {
        cmd(tk, "setblock 20 200 20 minecraft:chest");
        cmd(tk, "item replace block 20 200 20 container.0 with"
                + " minecraft:diamond_pickaxe[minecraft:damage=123] 1");

        JsonObject r = tk.call("mc.observe.container", with("pos", pos(20, 200, 20)));
        assertTrue(flag(r, "present"), "chest not present: " + r);

        JsonArray slots = r.getAsJsonArray("slots");
        JsonObject slot0 = at(slots, 0);
        assertEquals("minecraft:diamond_pickaxe", str(slot0, "id"), "slot 0 drifted: " + slot0);
        assertEquals(123L, num(slot0, "damage"), "damage drifted: " + slot0);
        assertEquals(1561L, num(slot0, "maxDamage"), "maxDamage drifted: " + slot0);
        assertEquals(1438L, num(slot0, "durability"), "durability drifted: " + slot0);
    }

    /**
     * An empty {@code PlayerList} answers {@code {present:false}} — it does not throw.
     *
     * <p>This is the whole reason a dedicated server is the right host for the instrument face: the
     * documented no-player semantics can only be observed where there is genuinely no player, and
     * every topology with a client has one.
     */
    @Test
    void absentPlayerIsReportedNotThrown(StageWright tk) {
        JsonObject r = tk.observePlayer();
        assertFalse(flag(r, "present"),
                "expected present:false on a server with no player, got: " + r);
    }

    @Test
    void entityQueryReturnsTheLivingRow(StageWright tk) {
        // Midnight so a mob under open sky at y=200 does not ignite mid-check.
        cmd(tk, "time set midnight");
        cmd(tk, "summon minecraft:zombie 30.5 200.0 30.5 {NoAI:1b,PersistenceRequired:1b}");
        try {
            JsonArray found = rows(tk, "mc.query",
                    query("entities", pos(30, 200, 30), 4, "zombie"));
            assertEquals(1, found.size(), "expected exactly one zombie, got: " + found);
            assertTrue(num(at(found, 0), "health") > 0,
                    "a living row must carry health: " + at(found, 0));
        } finally {
            cmd(tk, "kill @e[type=zombie]");
        }
    }
}
