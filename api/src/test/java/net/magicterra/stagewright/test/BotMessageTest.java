package net.magicterra.stagewright.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class BotMessageTest {

    private static Optional<String> key(Component text) {
        return new Bot.Message(text, Bot.Message.Kind.SYSTEM).key();
    }

    @Test
    void theKeyIsTheLinesOwnWhenItIsTranslatable() {
        assertEquals(Optional.of("a.b"), key(Component.translatable("a.b", Component.translatable("arg.key"))));
    }

    @Test
    void aFailureWrappedInAnEmptyRedComponentStillHasItsKey() {
        // What CommandSourceStack.sendFailure sends.
        Component failure = Component.empty()
                .append(Component.translatable("mymod.error.taken"))
                .withStyle(ChatFormatting.RED);
        assertEquals(Optional.of("mymod.error.taken"), key(failure));
    }

    @Test
    void theFirstTranslatablePartInOrderWins() {
        Component line = Component.literal("x")
                .append(Component.literal("y").append(Component.translatable("deep.first")))
                .append(Component.translatable("second"));
        assertEquals(Optional.of("deep.first"), key(line));
        assertEquals(Optional.empty(), key(Component.literal("plain")));
    }
}
