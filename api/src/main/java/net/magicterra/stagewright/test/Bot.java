package net.magicterra.stagewright.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A server-side player WorldDriver joins for one test, injected as a test method parameter: it is on the player list as
 * any player is, so mobs, advancements and chunk loading see it. It joins at the world spawn in survival with an empty
 * inventory and full health and hunger, and leaves when the test ends unless the test {@linkplain Keep keeps} it for a
 * later one.
 */
public interface Bot extends Body, Driven {

    @Override
    default String ref() {
        return Body.super.ref();
    }

    @Override
    Bot park();

    @Override
    Bot unpark();

    /**
     * Respawn the dead bot, as a player does from the death screen: at its respawn point, by vanilla's rules. A bot is
     * hurt and dies as any player does, and stays dead until this is called; the handle drives the player that replaces
     * it from then on.
     *
     * @throws IllegalStateException when the bot is alive
     */
    Bot respawn();

    /**
     * Disconnect the bot as a client that loses its connection does, and return once it has left: its connection
     * closes, and on a later server task the server takes it off the player list through the same path, so every
     * loader's logout event fires, and saves its data. Until {@link #reconnect} nothing may ask the handle about the
     * player, which is not in the game.
     *
     * @throws IllegalStateException when the bot is not in the game
     */
    Bot disconnect();

    /**
     * Join the bot again under its name after {@link #disconnect}, as a client reconnecting does: the same player, by
     * UUID, from its save, in the dimension and at the place it left, with what it had, and the login event fired.
     * StageWright resets nothing of it, as it does a bot that joins for a test. It comes back parked if it left parked.
     *
     * @throws IllegalStateException when the bot is in the game, or another player took its name
     */
    Bot reconnect();

    /**
     * What the server said to the bot since it last joined, oldest first: each chat line, system message and action bar
     * text a client would have shown, whether from {@code sendSystemMessage}, {@code displayClientMessage}, a command's
     * feedback or another player's chat. The last 256 are kept; a respawn keeps them, a {@link #reconnect} starts
     * afresh. For example {@code assertEquals(Optional.of("mymod.error.taken"), bot.messages().getLast().key())}.
     */
    List<Message> messages();

    /** Forget the messages so far, so {@link #messages} holds only what the server says after. */
    Bot clearMessages();

    /**
     * The custom payloads of {@code type}, or a subtype, mods sent the bot since it last connected, its configuration
     * included, in the order sent, a bundle's read into. Each is the object the mod sent, as nothing encodes it for a
     * bot: a mod that changes it after sending changes what is read. The last 256 on each channel, the payload's
     * {@code type()}, are kept, so a mod that sends on one every tick pushes out only its own; a respawn keeps them, a
     * {@link #reconnect} starts afresh. For example, that a mod pushed a player a new snapshot when the player's rank
     * changed: {@code assertEquals(2, bot.payloads(SnapshotUpdate.class).getLast().revision())}.
     *
     * <p>A bot negotiates no channel, so a mod that asks first whether the player has its channel, NeoForge's
     * {@code hasChannel} or Fabric's {@code ServerPlayNetworking.canSend}, sends a bot nothing; one that just sends
     * reaches it on either loader.
     *
     * <p>A default that refuses, so a class of a consumer's own that implements {@code Bot} still compiles; the bot a
     * test is given has it.
     */
    default <T extends CustomPacketPayload> List<T> payloads(Class<T> type) {
        throw new UnsupportedOperationException(getClass().getName() + " does not keep the payloads sent to it");
    }

    /** Forget the payloads so far, so {@link #payloads} holds only what mods send after. */
    default Bot clearPayloads() {
        throw new UnsupportedOperationException(getClass().getName() + " does not keep the payloads sent to it");
    }

    /** One line the server sent the bot, as its client would show it. */
    record Message(Component text, Kind kind) {

        /** Where a client shows the line. */
        public enum Kind {
            /** Another player's chat, decorated as its chat type says: {@code <alice> hi}. */
            CHAT,
            /** A line in the chat that no player said: command feedback, a mod's message. */
            SYSTEM,
            /** Above the hotbar: a system message sent as an overlay, or {@code /title … actionbar}. */
            ACTION_BAR
        }

        /**
         * The translation key of the line: of its first translatable part, the line itself first and then each part
         * appended to it in order, as a mod's own messages usually are. First found that way rather than only the
         * line's own, because a command's {@code sendFailure} wraps its message in an empty red component, so the key a
         * mod refused with is one level down.
         */
        public Optional<String> key() {
            return firstKey(text);
        }

        private static Optional<String> firstKey(Component part) {
            if (part.getContents() instanceof TranslatableContents translatable) {
                return Optional.of(translatable.getKey());
            }
            for (Component appended : part.getSiblings()) {
                Optional<String> key = firstKey(appended);
                if (key.isPresent()) {
                    return key;
                }
            }
            return Optional.empty();
        }

        /** The text as a client would show it, without its styling, in the server's language. */
        public String plain() {
            return text.getString();
        }
    }

    /** The name a parameter's bot joins under; without it, one StageWright makes up. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Name {

        /** A player name: letters, digits and underscores, at most 16. */
        String value();
    }

    /**
     * The parameter's bot joins {@linkplain #park parked}, before the server has ticked it once, so it stays where the
     * test puts it until the test ticks it.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Parked {}

    /**
     * The parameter's bot stays in the world when the test ends, parked, with what it has and where it stands, for a
     * test that {@code @DependsOn} this one to take as {@link Kept} under the same {@link Name}. A test that fails or
     * is aborted removes it instead, and a kept bot no test takes leaves when the run's tests are over. It must stand
     * outside the test's plots, which are put back when the test ends. A test that takes it after a restart
     * ({@code @DependsOn(afterRestart = true)}) finds it where the game before left it: the bot left that game as a
     * disconnecting player does, which saved it, and joins the next one from that save, in the same dimension, with the
     * same inventory and state.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Keep {}

    /**
     * The parameter's bot is the one a test this one {@code @DependsOn} kept under the same {@link Name}: the same
     * player, where it stood and with what it had, back on the tick list unless the parameter is also {@link Parked};
     * across a restart, the same player joined again from its save. The test fails when no passed test kept a bot of
     * that name, or another test took it first.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Kept {}
}
