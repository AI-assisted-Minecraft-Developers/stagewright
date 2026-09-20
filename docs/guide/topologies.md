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

### What runs where, when two processes are involved

Scenes run on the server, write the server's results file, and their outcome is the run's outcome.

Anything whose subject is the **boundary** cannot be a scene, because a scene body runs in the
server's JVM and the client is a different process. It runs in the client's JVM instead, as a
**client probe**, and writes its own one-scene results file, `stagewright-client-results.jsonl`,
into the client's run directory.

The consuming project points its topology's verdict at that file, and **the worse of the two verdicts
wins**. A green server with a red client is a red run — which is the point, since the server cannot
see what the client sees. A declared-but-missing client results file is an environment failure, never
a pass.

StageWright ships one client probe, `client.damageSourceAcrossTheWire`. It listens through the
driver's own event fan-out — the same one any attached agent subscribes to — for a damage event
mirrored client-side off the incoming packet, and asserts the attribution survived the wire. It
causes its own damage rather than waiting on a server-side scene: two harnesses in two processes
agreeing on when something should have happened is a synchronisation problem with no handshake
available to solve it.

Most clients running StageWright have no driver in them, and never should. There the probe records a
skip naming that, so the results count it as untested rather than as tested and fine. Getting that
guard right is subtler than it looks: naming a class is what makes the JVM load it, so
`if (SomeDriverClass.api() == null)` throws `NoClassDefFoundError` instead of answering. Every
reference to the driver therefore lives in one class that tests presence by name and is never loaded
at all by a runtime that answers no.

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

## A skip is not coverage

A scene that needs a player records a **skip** on a topology that has none, and a skip resolves as a
pass. That is correct: a topology without a player is not a defect in the scene.

It is also invisible. A suite whose player scenes skip on **every** topology it runs reports success
over subjects it has never once executed. That is not hypothetical — a large modpack's suite shipped
in exactly that state, with two of the six subjects it existed to test registered, reconciled,
counted, and skipped in every run that had ever happened.

Three things close it.

**A skip is reported as a skip.** The verdict prints `skip:` for a skipped scene rather than `pass:`,
so the two are not the same line in the output.

**Each run closes with a coverage census.** A `COVERAGE:` line names how many scenes executed on that
topology and which ones tested nothing there.

**A separate task reconciles the topologies against each other.** `stagewrightCoverage` reads every
topology's results and enforces one rule: *every scene any run registers must have executed in at
least one of them.* It is deliberately not a list of what must run where — that list would be
maintained by whoever just forgot to update it. Add a scene and the check covers it the moment it
exists. See [Gates](gates.md#reconciling-the-topologies-against-each-other).

The single sanctioned exception is declared at the scene with `@SceneDef(mustSkip = true)`, and it is
an assertion rather than an excuse: the scene's subject *is* the skip, the run requires it, and a
scene that executes instead is judged as a broken framework. Reach for it only when the scene can
never be satisfied where it lives. It is not a way to excuse a scene that skips because the topology
is thin; that scene needs a topology that can run it.

**So: what does a scene prove by skipping? Nothing.** The skip is honest, it is recorded, and it is
worth exactly as much coverage as not having written the scene.

## Which facts need which shape

Some questions can only be asked on a topology that has a real client, because the state they are
about does not exist in a server JVM. Client screens, keybindings, input handling, the client's own
view of an entity, and frame rate are all in that category.

On the integrated-server shape, a scene can still reach them, because both ends share a JVM: a scene
body on the server thread can route a call into the client and read the answer. On the two-process
shape it cannot. That is the dividing line, and it is why the client-side half of any suite either
lives in a client probe or attaches from outside the game entirely — see
[JUnit attach](junit-attach.md).

Frame rate specifically is measurable only where the JVM has a client rendering. The performance
facet returns a number there and `NaN` everywhere else; guard on `hasFps()` rather than asserting on
the value, or a server run fails on a measurement that was never available to it.

## Running one

Nothing runs from StageWright's own repository. A gate belongs to the consuming project: it is a task
in that project's build, declared by that project's configuration, driving that project's run task
and judging that project's results against that project's manifest. See [Gates](gates.md) for the
commands and for reading what comes back.
