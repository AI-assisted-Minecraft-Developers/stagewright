package net.magicterra.stagewright.junit.ui;

import com.google.gson.JsonObject;
import net.magicterra.stagewright.junit.StageWright;
import net.magicterra.stagewright.junit.StageWrightExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;

import static net.magicterra.stagewright.junit.ui.UiSupport.anyEditBoxValueContains;
import static net.magicterra.stagewright.junit.ui.UiSupport.hasScreen;
import static net.magicterra.stagewright.junit.ui.UiSupport.screenType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live UI scene {@code ui.chatScreenType} — T opens a ChatScreen; {@code typeText}
 * lands in the focused edit box; {@code mc.test.reset} closes the screen AND clears the
 * packet-captured chat readback.
 *
 * <p>To make the "reset clears chat" assertion non-vacuous, the scene first dirties the
 * chat log with one {@code mc.client.chat.send} (a client-instrument verb, same probe
 * P2b's reset.behavior uses) and confirms the readback went non-empty before reset.
 *
 * <p>Instrument face only: {@code input.key}/{@code input.typeText}/{@code screen.*}/
 * {@code chat.send}/{@code chat.history}/{@code mc.test.reset} — no behavior verbs.
 */
@ExtendWith(StageWrightExtension.class)
@EnabledIfEnvironmentVariable(named = "TESTKIT_ENDPOINT", matches = ".+")
class ChatScreenTest {

    private static final Duration UI = Duration.ofSeconds(5);

    @Test
    void chatScreenType(StageWright tk) {
        tk.reset();
        tk.awaitCondition(() -> !hasScreen(tk.screenInfo()), UI);

        // Dirty the chat readback so reset's clear is observable (non-vacuous).
        String probe = "testkit-chat-" + (System.currentTimeMillis() % 100000);
        JsonObject sendParams = new JsonObject();
        sendParams.addProperty("text", probe);
        tk.call("mc.client.chat.send", sendParams);
        tk.awaitCondition(() -> chatCount(tk) > 0, UI);
        assertTrue(chatCount(tk) > 0, "chat readback should be non-empty after chat.send");

        // T opens the chat screen (keybind consumed next tick -> poll).
        tk.key("T");
        tk.awaitCondition(() -> {
            var info = tk.screenInfo();
            return hasScreen(info) && screenType(info).contains("Chat");
        }, UI);
        assertTrue(screenType(tk.screenInfo()).contains("Chat"),
                "T should open a ChatScreen, got: " + screenType(tk.screenInfo()));

        // typeText into the focused chat edit box; the tree must reflect the typed value.
        String typed = "hello-from-testkit";
        tk.typeText(typed);
        tk.awaitCondition(() -> anyEditBoxValueContains(tk.screenTree(), typed), UI);
        assertTrue(anyEditBoxValueContains(tk.screenTree(), typed),
                "typeText should land in the focused chat edit box");

        // reset closes the screen (does NOT send the composed text) and clears chat.
        tk.reset();
        tk.awaitCondition(() -> !hasScreen(tk.screenInfo()), UI);
        assertFalse(hasScreen(tk.screenInfo()), "reset must close the chat screen");
        tk.awaitCondition(() -> chatCount(tk) == 0, UI);
        assertEquals(0, chatCount(tk), "reset must clear the chat readback");
    }

    /** {@code mc.client.chat.history} → captured line count. */
    private static int chatCount(StageWright tk) {
        JsonObject params = new JsonObject();
        params.addProperty("limit", 50);
        JsonObject r = tk.call("mc.client.chat.history", params);
        return r.has("count") && r.get("count").isJsonPrimitive() ? r.get("count").getAsInt() : -1;
    }
}
