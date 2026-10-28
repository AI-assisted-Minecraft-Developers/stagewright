# Topologies

A topology is the shape of the process tree a scene's tests run in: which game processes start, how they are
connected, and therefore what the tests inside them are in a position to observe. A scene file names one with
`topology:`.

Three shapes matter, and they are not three ways of doing the same thing. Each one can establish facts the other two
cannot.

## The three shapes

In all three, tests run in the **server's** JVM, on a test thread beside the server thread. What differs is which
server, and whether a real game client is connected to it.

| Topology | What starts | A player is present | What only this shape can establish |
|---|---|---|---|
| `dedicatedServer` | one headless dedicated server | no | That a client-only call is genuinely refused, and that an empty player list reads as empty. Neither is observable anywhere a client exists. |
| `integratedServer` | one game client hosting its own server | yes | Anything that needs client-side state in the same JVM, and anything measured in frames — the client frame rate is a number here and nowhere else. |
| `dedicatedServerWithClient` | a dedicated server, plus a real client joined to it over a network socket | yes | Anything whose subject is the connection between the two halves. |

On the two shapes with a client, the tests wait until the player has joined before they start.

The names are the ones the Gradle plugin generates runs for and the command-line runner writes launch specs for. A
build may map them to topologies of its own, or add others; see [the Gradle plugin](gradle-plugin.md#test-suites-tests--).
Nothing reads the name to decide whether a client is present: `@Requires(CLIENT)`, `@Requires(DEDICATED_SERVER)` and
`@Requires(INTEGRATED_SERVER)` are checked in the game, from how it was started.

## Why the two-process shape is not the one-process shape with extra steps

A client that hosts its own integrated server is two halves of Minecraft in one JVM, joined by an in-memory connection.
That connection never serialises a packet. So an assertion about what a mod sends over the network, checked on an
integrated server, has proved that the code which builds the packet is reachable and nothing about the packet.

In the two-process shape the packet is encoded, pushed through a socket, and decoded before anything reads it. That is
the difference between "works in singleplayer" and "works on a server", and it is the whole reason the third shape
exists.

The dedicated server in that shape also holds a genuine `ServerPlayer` that arrived over the network, rather than one
constructed locally, so every server-side check runs against the player list a production server actually has.

### The two ends load the same mods

A loader refuses a connection whose two ends disagree about the mod list, and a disagreement about content would read
as a defect of the mod under test. So the client is started with the server's mods: under the Gradle plugin both runs
come from the same loader project and source set, and the command-line runner gives the client the server's `mods/`
unless `--client-mods` names others.

## Which facts need which shape

Some questions can only be asked where there is a real client, because the state they are about does not exist in a
server JVM: client screens, keybindings, input handling, the client's own view of an entity, and frame rate.

A test reaches the client through its `Client` parameter (`t.client()` in a script), calling WorldDriver's client
methods. On an integrated server those run in the same JVM; on `dedicatedServerWithClient` they travel to the client
over its WorldDriver connection, and the test reads the same either way. The `Player` parameter is the client's player
on both. A test taking either carries `@Requires(CLIENT)`, so it is reported as disabled on a dedicated server rather
than failed.

Frame rate is measured only where the client renders in the same JVM as the tests, which is `integratedServer`. A
`Perf.Window` has `fps` there and `NaN` everywhere else, including the server of `dedicatedServerWithClient`; guard on
`hasFps()` rather than asserting on the value.

## Running one

A scene file declares its topology, and a suite's `stagewrightTest<Name>` task, or `stagewright test` with the
command-line runner, starts that topology's games for it. The runner starts the two topologies with a client only when
given `--client`; see [Getting started](getting-started.md#scenes-with-a-client).
