package net.magicterra.stagewright.driver.verbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.magicterra.stagewright.test.methods.AdvancementsContracts;
import net.magicterra.stagewright.test.methods.LootContracts;
import net.magicterra.stagewright.test.methods.ModsContracts;
import net.magicterra.stagewright.test.methods.RecipesContracts;
import net.magicterra.stagewright.test.methods.StructuresContracts;
import org.junit.jupiter.api.Test;

/** The ops added for scripts that ask what the game has, as a request spells them and a reply reads. */
class TestQueryContractsTest {

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    @Test
    void theListOpsTakeAnOptionalNamespace() {
        assertEquals(Optional.of("minecraft"), assertInstanceOf(LootContracts.ListTables.class,
                LootContracts.LOOT.input().decode(json("{\"op\":\"list\",\"namespace\":\"minecraft\"}"))).namespace());
        assertEquals(Optional.empty(), assertInstanceOf(AdvancementsContracts.ListAdvancements.class,
                AdvancementsContracts.ADVANCEMENTS.input().decode(json("{\"op\":\"list\"}"))).namespace());
        assertInstanceOf(StructuresContracts.ListStructures.class,
                StructuresContracts.STRUCTURES.input().decode(json("{\"op\":\"list\"}")));
    }

    @Test
    void modsListsAndAsksForOne() {
        assertInstanceOf(ModsContracts.ListMods.class, ModsContracts.MODS.input().decode(json("{\"op\":\"list\"}")));
        assertEquals("worlddriver", assertInstanceOf(ModsContracts.Has.class,
                ModsContracts.MODS.input().decode(json("{\"op\":\"has\",\"id\":\"worlddriver\"}"))).id());
        JsonObject reply = ModsContracts.MODS.output().encode(new ModsContracts.ModList(1,
                List.of(new ModsContracts.Mod("worlddriver", "0.1.0")))).getAsJsonObject();
        assertEquals("list", reply.get("op").getAsString());
        assertEquals(1, reply.get("count").getAsInt());
        assertEquals("worlddriver", reply.getAsJsonArray("mods").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void theRecipeAuditCarriesItsTotalsAndCountCountsByType() {
        assertInstanceOf(RecipesContracts.Count.class, RecipesContracts.RECIPES.input().decode(json("{\"op\":\"count\"}")));
        JsonObject audit = RecipesContracts.RECIPES.output().encode(new RecipesContracts.AuditReport(10, 6, 5, 4, 1,
                List.of(new RecipesContracts.KindCount("minecraft:crafting_shaped", 1)), List.of("x"))).getAsJsonObject();
        assertEquals(10, audit.get("total").getAsInt());
        assertEquals(5, audit.get("succeeded").getAsInt());
        assertEquals("minecraft:crafting_shaped",
                audit.getAsJsonArray("failuresBySerializer").get(0).getAsJsonObject().get("kind").getAsString());
        JsonObject counted = RecipesContracts.RECIPES.output().encode(new RecipesContracts.Counted(3,
                List.of(new RecipesContracts.KindCount("minecraft:crafting", 3)))).getAsJsonObject();
        assertEquals("count", counted.get("op").getAsString());
        assertEquals(3, counted.get("total").getAsInt());
    }
}
