# Topologies

A topology is the shape of the process tree a run happens in: which game processes start, how they
are connected, and therefore what the scenes inside them are in a position to observe.

Three shapes matter, and they are not three ways of doing the same thing. Each one can establish
facts the other two cannot.

## The three shapes

All three run scenes on a **server**. A scene body executes on the server thread, in the server's
JVM, always. What differs is which server, and whether a real game client is attached to it.

| Topology | What starts | A player is present | What only this shape can establish |
|---|---|---|---|
| `dedicatedServer` | one headless dedicated server | no | That a client-only verb is genuinely refused, and that an empty player list reads as empty. Neither is observable anywhere a client exists. |
| `integratedServer` | one game client hosting its own integrated server | yes | Anything that needs client-side state in the same JVM, and anything measured in frames — client frame rate is a number here and is not defined elsewhere. |
| `dedicatedServerWithClient` | a dedicated server, plus a real client joined to it over a network socket | yes | Anything whose subject is the wire between the two halves. |

The names are what a consuming project calls its topology declarations; StageWright itself has no
enumeration of topologies and no opinion about the names. Those three are the conventional ones, and
they are conventional because they say what starts. See [the Gradle plugin](gradle-plugin.md) for
how a project declares one.

The topology label a run carries is a free string, which is why nothing that needs to know whether a
client is present reads it. The JUnit attach module, for instance, detects the face of the JVM it
attached to by asking whether a client-only call answers, not by reading the label.

## Why the two-process shape is not the one-process shape with extra steps

A client that hosts its own integrated server is two halves of Minecraft in one JVM, joined by an
in-memory connection. That connection never serialises a packet. So an assertion about what a mod
sends over the wire, checked on an integrated server, has proved that the code which builds the
packet is reachable and nothing about the packet.

In the two-process shape the packet is encoded, pushed through a socket, and decoded before anything
reads it. That is the difference between "works in singleplayer" and "works on a server", and it is
the whole reason the third shape exists.

The dedicated server in that shape also holds a genuine `ServerPlayer` that arrived over the network,
rather than a stand-in constructed locally, so every server-side check runs against the player list a
production server actually has.

### The two ends are the same build

Both halves run the same jar, built from the same checkout, on the same machine, with one loader per
run. The world derives from a single per-loader template copied into the server's run directory
before the run starts.

There is therefore no version skew between the ends and no template divergence: the client joins a
server whose world, mod set and protocol are byte-for-byte what that checkout produces standalone.
This matters because a loader refuses a connection whose two ends disagree about the mod list, and
because a disagreement about *content* would be reported as a disagreement about the mod under test.

For the same reason, a companion client gets the same installed mods as the server it joins, in its
own run directory. Before that was true, a companion launched with an empty `mods/` directory — which
is not an error anywhere. The client boots, joins, arms nothing, and the run hangs until the server's
budget ends it.

## Which facts need which shape

Some questions can only be asked on a topology that has a real client, because the state they are
about does not exist in a server JVM. Client screens, keybindings, input handling, the client's own
view of an entity, and frame rate are all in that category.

On the integrated-server shape, a scene can still reach them, because both ends share a JVM: a scene
body on the server thread can route a call into the client and read the answer. On the two-process
shape it cannot. That is the dividing line.

Frame rate specifically is measurable only where the JVM has a client rendering. The performance
facet returns a number there and `NaN` everywhere else; guard on `hasFps()` rather than asserting on
the value, or a server run fails on a measurement that was never available to it.

## Running one

A scene declares the topology it runs in, and a test suite's `stagewrightTest<Name>` task starts that
topology's games for it. See [the Gradle plugin](gradle-plugin.md#test-suites-tests--) for how a
project declares a suite and maps or generates its topologies.
