# Supervising a game process: an agent inside, a channel out

## The problem

Something outside the game has to answer four questions about every run: has the suite finished,
is the game still making progress, has it crashed, and why has it not exited. Today two programs
answer them — the command-line runner and the Gradle plugin — and they answer them differently,
and mostly by inference.

The command-line runner reads files from outside the process. `awaitDoneFooter` in
`cli/.../Main.java` rereads the whole results file once a second looking for the done footer.
`Stall` decides the game is stuck when its log, results and heartbeat files have not changed size
or modification time for five minutes. `NoWindow` searches the client's output for the phrases a
failed window creation prints. When the footer appears, `drain` and `kill` end the process tree.

The Gradle plugin infers nothing and waits instead. Its run task is a `JavaExec`, so it returns
when the game process exits. A pack whose mods leave a non-daemon thread behind finishes its suite,
writes its footer, and then holds the build for the whole `timeoutMinutes`.

The game, meanwhile, already knows most of the answers precisely:

- `StallWatchdog` (in `common/.../harness/`) judges a stall by ticks delivered over a window, from
  a thread that is not the server thread, and writes the verdict the wedged thread cannot.
- `StageWrightHarness.armExitWatchdog` notices, thirty seconds after halting a dedicated server,
  that the JVM is still up — so it knows a non-daemon thread is holding it. It logs one sentence
  that names no thread and no mod, and calls `Runtime.halt(0)`.

So the precise knowledge is inside the process and the decisions are made outside it, from files.
This document moves the knowledge to where the decisions are made.

## The shape

Three pieces, each with one job.

| Piece | Lives in | Job |
|---|---|---|
| **The agent** | the game's JVM, loaded with `-javaagent` | report what the JVM is doing from its first instant to its last, and end the JVM when the run is over |
| **The bridge** | the StageWright mod | hand the agent what only the game knows: harness ready, ticks, done |
| **The supervisor** | the command-line runner, or a build service in the Gradle plugin; one implementation, in the engine | listen for the agent, decide when a run is over or stuck, and act |

The results file is untouched as the record and as the only thing a verdict is read from. The
channel carries events and diagnosis. A run whose channel says "done" but whose results file has
no footer is still RED; the channel only says when to look.

## The agent

A separate artifact, `stagewright-agent` (`agent/`), built by the engine's build so that the engine's
tests can start a real JVM with it, and for the engine's reason: it must never see a Minecraft or a
Gradle classpath. It uses only the JDK. It does not transform any class, so it cannot conflict with mixins or with another
agent.

It is loaded only on the command line (`-javaagent:<jar>`), never by dynamic attach: JDK 21 warns
on dynamic agent loading and a later release will refuse it by default (JEP 451). Placement on the
command line follows the rule `-D` already follows in `Main.buildCommand` — before the loader's
argument file, where the JVM rather than the launcher reads it.

`premain` starts one daemon thread and returns. That thread:

1. **Connects to the supervisor** at the address and token passed as system properties, and says
   hello with its pid, its role (server or client) and its own version. A token that does not match
   is refused — the point is less security than not letting a previous run's game report into this
   run's supervisor.
2. **Sends a heartbeat every second**, carrying the JVM's loaded-class count and the phase it
   believes the game is in. Loaded classes climbing is what mod loading looks like from inside;
   it is a better progress signal before the harness exists than any log's size.
3. **Classifies a stuck start**. Before the bridge reports the harness ready, it samples the
   stacks of `main` and `Render thread` with `ThreadMXBean`. The stuck starts measured so far each
   have a signature — LWJGL's GLFW spinning inside `glfwCreateWindow` under Xwayland, NeoForge's
   failed-window dialog waiting in the AWT event queue — and those signatures become data in the
   agent rather than phrases searched for in a log. An unrecognised stuck start is reported with
   its top frames, which is still more than a silent log says.

   *As built, the first half of that is not yet done.* Once nothing the supervisor can see —
   phase, ticks, loaded classes — has moved for `idle` seconds (20 by default), the agent attaches
   the stacks of `main`, `Render thread` and `Server thread` to every heartbeat, and the runner
   prints them with the verdict on a stall. That is the evidence; it is not a classification,
   because the two signatures above were reported by hand and have not been measured on this box.
   The runner still reads the client's log for the failed-window lines (`NoWindow`), and it
   still reads `/proc` to say whether a stalled process was spinning or asleep.
4. **Watches the crash-report directory** with a `WatchService`, and reports a new report the
   moment it is written. A crashed game frequently does not exit; this is how the supervisor stops
   waiting on it without a second polling loop.
5. **Ends the JVM after the run.** When the bridge reports done, the agent waits a grace period for
   the game to stop on its own. If the JVM is still up at the end of it, the agent lists every
   live non-daemon thread with its stack, attributes each to the jars of the classes on that stack
   (`Instrumentation.getAllLoadedClasses` gives the classes, their `CodeSource` gives the jar), sends
   that list, and calls `Runtime.halt(0)`. This replaces `armExitWatchdog`, and unlike it covers the
   client topology too, because the agent knows nothing about which side it is on.

In a hold run there is no "done", so the agent never ends the JVM. A held game instead reports
`results` when its results file is complete, which ends the supervisor's wait and leaves ending the
game to the caller. The command-line runner's `--attached` run waits on it; the Gradle plugin's hold
task is not supervised, since it is meant to outlast the build.

## The bridge

The mod and the agent are loaded by different class loaders — the agent by the system loader, the
mod inside modlauncher's or Knot's — so the mod must not link against an agent class. The agent's
jar is on the system class path, so the mod loads one class from the system loader by name and
calls its static methods by reflection, with JDK types only (the methods are in
[the protocol](../reference/supervision-protocol.md)). If that class is not there the bridge does
nothing, which is the correct behaviour for a game started by hand, by `runClient` without the
plugin, or for a JUnit attach.

An MBean on the platform `MBeanServer` was the first idea and was dropped: asking for the platform
server from `premain` initialises `java.util.logging` early, which breaks a pack that sets its own
`java.util.logging.manager`, and it would make the mod depend on `java.management` being readable
from its module layer. Reflection on a class the JVM already put on the class path depends on
neither.

What the bridge sends: harness ready; tick counts from the same counter `StallWatchdog` reads;
scene started; done. `StallWatchdog` stays where it is and stays the authority on a stall measured
in ticks — it needs the game's notion of a tick, which the agent does not have.

## The supervisor

One implementation in the engine, used by both the runner and the plugin, so that "when is a run
over" cannot be answered two ways again.

It binds a TCP socket on `127.0.0.1`, on a port it chooses, before the game starts, and passes the
address and a one-time token to the game. It then reacts to events instead of polling:

| Event | What it means | What the supervisor does |
|---|---|---|
| `done` | the results file has its footer | reads the results file, once |
| `leaked` | the JVM outlived the grace period after done | records the threads and jars; a warning above the verdict by default, RED when the user asks for it |
| `crash` | a crash report was written | stops waiting, reports the file |
| `stuck` | a start classified as stuck | stops waiting, reports the classification and stacks |
| connection closed without `exiting` | the process died | `Process.onExit` or the closed socket, whichever comes first; no `isAlive` loop |
| heartbeat missing for the limit | the whole JVM has stopped, agent included | ends the process tree by the pid the agent reported |

The last row is the one case the agent cannot cover from inside. A JVM wedged at a safepoint — the
measured example is `halt` itself stuck inside `VM_Exit` on a 262-mod pack — stops every Java
thread, the agent's with it. That is why the supervisor keeps a kill. But it is now triggered by a
fact that has one meaning, the heartbeat from inside the JVM stopping, not by a log file going
quiet, and the limit can be short because nothing that is still running goes a minute without a
heartbeat.

The ending of the process tree uses the pid from the hello, not a `Process` handle. That is what lets
the Gradle plugin, which does not own the process its `JavaExec` started, end it all the same.

The leak check is a judgment printed above the `VERDICT:` line, beside the coverage and canary
judgments, because it is made after the results file is complete and is not a scene's outcome. It
is a warning by default: a pack author usually cannot change the mod that leaked, and a default of
RED would fail their every run for a defect they do not own. A mod author testing their own mod
should turn it on.

There is no fallback to the file-reading supervision. A run started by a supervisor always carries
the agent, because the supervisor added it; an agent that never connects means the JVM never got as
far as `premain`, and the run is an ENV failure that says so. Keeping the file readers as a second
path would mean two mechanisms to maintain and to test, and the second would be exactly the
inference this replaces.

## The two front ends

**The command-line runner** adds the agent to every launch — server, client, and both halves of a
server-with-client run — and hands its waiting to the supervisor. `Stall`, `NoWindow`, the polling
in `awaitDoneFooter`, and `drain` are deleted. Its own installer and client launcher (`ClientLaunch`,
`install/`) stay as they are: they gain one JVM argument and nothing else.

**The Gradle plugin** adds the agent and the supervisor address to the run task through
`jvmArgumentProviders`, evaluated at execution and stored in the configuration cache as a value.
The supervisor runs in a build service for the length of the run task. The run task remains a
`JavaExec`; it now returns when the agent ends the JVM, so the wait for a process that cannot exit
disappears. `timeoutMinutes` stays, as the ceiling for a run whose agent never connected.

Both resolve the agent jar the same way they resolve the engine: the runner carries it as a
resource and writes it into the run directory; the plugin resolves `stagewright-agent` at the
version it was built with.

## The second process: toolchain adapters

A server-with-client topology under Gradle starts a second game process, the companion, from
another run task. Today `SideProcesses` reads that task's `JavaExec` spec by reflection while the
run task executes — including late-staged parts kept in each toolchain's internal members — and
because it holds a `Task`, `StageWrightPlugin` declares the run task incompatible with the
configuration cache.

The replacement is one adapter per toolchain, behind one interface: given a run task at
configuration time, return its launch as lazily evaluated values — the Java launcher, main class,
classpath, JVM and program arguments, environment including the late-staged parts, and working
directory — and no reference to the task. Gradle can store those in the configuration cache. The
first two adapters are Loom (fabric-loom and architectury-loom) and ModDevGradle, the two
`SideProcesses` handles today.

This does not remove the dependence on toolchain internals; Loom stages its environment in members
it does not publish. It confines it: each adapter is small, owns its toolchain's quirks, and is
tested by Gradle TestKit against the toolchain versions it claims. A run task whose toolchain has no
adapter is refused for companion topologies with a message naming the task's class. Every other
topology needs only a `JavaExec`, and works with any toolchain that provides one.

Reading a file the toolchain generates instead was considered and does not work: Loom's
`launch.cfg` carries system properties and program arguments but no classpath and no main class.

## What stays, what goes

| Stays | Why |
|---|---|
| The results file, its footer, and the verdict read from it | the record, and the only verdict authority |
| `stagewright-progress.json` | answers "where did it die" after a death nothing reported |
| `StallWatchdog` | the only judge of a stall measured in ticks |
| The supervisor's kill and the outer timeout | the one case the agent cannot cover from inside |
| `ClientLaunch` and the installer | outside the scope of supervision; they gain the agent argument |

| Goes | Replaced by |
|---|---|
| `Stall`, `NoWindow`, the polling in `awaitDoneFooter`, `drain` | the channel's events |
| the polling in `awaitDoneFooter` | the `results` event, for `--attached` |
| reflection in `SideProcesses` | the toolchain adapters |
| `notCompatibleWithConfigurationCache` on the run task | values captured at configuration time |

`armExitWatchdog` was meant to go here and stays. It is the only thing that ends a dedicated server
started without an agent: by hand, through a `--launch` that drops JVM arguments, or through a run
task the plugin cannot put an argument on. With an agent attached it does nothing.

## To verify before building on it

Each of these is believed and not yet measured:

- The system class loader can load the agent's `Control` class by name from a mod under NeoForge's
  module layers and under Fabric's Knot.
- An agent jar on the system class path is accepted by NeoForge's launcher without being mistaken
  for a mod or tripping its package checks.
- Attributing a thread to a jar through loaded classes and their `CodeSource` names the mod on the
  pack that motivated `armExitWatchdog`.
- The stack signatures for the two measured stuck starts are distinguishable from a slow but healthy
  start.
- Which of the values a Loom and a ModDevGradle run task needs are final at configuration time, and
  which must stay lazy.

## Order of work

Each step leaves both loaders' checks green.

1. The agent, the supervisor in the engine, and a reference document for the channel's events.
   Switch the runner's server topology to it; delete `Stall` and `NoWindow` from that path.
2. The runner's client and server-with-client topologies.
3. The plugin's run task through the build service, which closes the wait for a process that
   cannot exit.
4. The toolchain adapters for the companion, which closes the configuration-cache incompatibility.
5. `--attached` through the channel, with the `results` event for a held game. `armExitWatchdog`
   stays as the fallback for a game without an agent.

Two test fixtures make the claims checkable rather than asserted: a mod that leaves a non-daemon
thread running, which must be named with its jar; and a game that wedges before the harness loads,
which must be classified rather than timed out.
