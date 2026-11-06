package net.magicterra.stagewright.driver.verbs;

/**
 * The client's connection to the game's server, as the client installs it: what {@code mc.test.client.leave} and
 * {@code mc.test.client.join} do there. Here, rather than beside the client code, because those methods are published
 * on every side and this class must load on a dedicated server; the client side installs it from its own branch.
 */
public interface ClientSession {

    /**
     * Leave the server as the pause menu's Disconnect does; returns the player's profile name once the client has no
     * world.
     */
    String leave();

    /** Join the server the client left again; returns the player's profile name once the client is in the world. */
    String join();

    /**
     * The run is over: a client a test left disconnected closes, as a client in the world does when the server stops.
     * Nothing for one in the world. Does not wait; the server asks it as it stops.
     *
     * @return whether a test had left it disconnected, so it is closing
     */
    boolean end();

    /** The one installed on this client, or null on a server and on a client that opened a world. */
    static ClientSession installed() {
        return Holder.session;
    }

    /** Called by the client side once, when it joins a server. */
    static void install(ClientSession session) {
        Holder.session = session;
    }

    /** The field an interface cannot have. */
    final class Holder {
        private static volatile ClientSession session;

        private Holder() {}
    }
}
