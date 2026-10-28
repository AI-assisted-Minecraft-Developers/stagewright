# Supervising a game process: an agent inside, a channel out

## The problem

Something outside the game has to answer four questions about every game a test run starts: has its run finished, is
it still making progress, has it crashed, and why has it not exited. Read from outside — a results file reread once a
second, a log that stopped growing, a process that will not end — each answer is an inference, and each was wrong in
practice: a quiet but busy game was killed as stuck, a crashed game that kept its JVM alive was waited on until the
timeout, and a pack whose mods leave a non-daemon thread behind held the build for the whole timeout after its tests
had finished.

The game itself knows most of the answers precisely. So the knowledge is reported from inside the JVM, over one
channel, to the one program that decides.

## The shape

Three pieces, each with one job.

| Piece | Lives in | Job |
|---|---|---|
| **The agent** | the game's JVM, loaded with `-javaagent` (`agent/`, artifact `stagewright-agent`) | report what the JVM is doing from its first instant to its last, and end the JVM when the run is over |
| **The bridge** | the StageWright mod (`AgentBridge`) | hand the agent what only the game knows: that it is up, its ticks, each test event, that its run is over |
| **The supervisor** | `Supervisor` in the engine, used by the test engine for every game it starts | listen for the agent, decide when a game is done or stuck, and end it |

The supervisor decides only whether the game is done and what to say if it stopped before it was. Whether a test
passed is in the test events the same channel carries ([the test engine](test-engine.md#one-channel-back)).

Neither the Gradle plugin nor the command-line runner supervises anything itself. Both write
[launch specs](../reference/launch-spec.md) and hand them to the test engine, which starts every game with the agent
and waits on it through the supervisor, so there is one answer to "when is a game over".

## The agent

It uses only the JDK and transforms no class, so it cannot conflict with mixins or with another agent. It is loaded
only on the command line, never by dynamic attach, which JDK 21 warns about and a later release will refuse. The
test engine places the argument after the run's own system properties and before the first `-cp` or argument file,
where the JVM rather than a launcher reads it.

`premain` starts a daemon thread and returns. That thread:

1. **Connects to the supervisor** at the address and one-time token passed in its argument, and says hello with its
   pid. A connection with the wrong token is refused, so a game left over from an earlier run cannot report into this
   one.
2. **Sends a heartbeat every second**, with the phase the game last reported, its tick count and the JVM's loaded
   class count. Loaded classes climbing is what mod loading looks like from inside, a better sign of progress before
   the world exists than any log's size. Once none of the three has moved for `idle` seconds (20), each heartbeat also
   carries the stacks of `main`, `Render thread` and `Server thread`.
3. **Watches the crash-report folder** and reports a new report within a second. A crashed game often does not exit;
   this is how the supervisor stops waiting on it.
4. **Ends the JVM after the run.** When the game reports done, the agent waits a grace period (30 seconds). If the JVM
   is still up, it lists every live non-daemon thread with its stack, the jars on that stack, and the code that started
   it (from JFR's `jdk.ThreadStart`, recorded from `premain`, since a parked pool worker's own stack is all JDK), sends
   that, and calls `Runtime.halt(0)`.

## The bridge

The mod and the agent are loaded by different class loaders, so the mod must not link against an agent class. The
agent's jar is on the system class path, so the mod loads `net.magicterra.stagewright.agent.Control` from the system
loader by name and calls its static methods by reflection, with JDK types only. Without the agent the bridge does
nothing, which is right for a game started by hand.

An MBean on the platform `MBeanServer` was considered and dropped: asking for the platform server from `premain`
initialises `java.util.logging` early, which breaks a pack that sets its own `java.util.logging.manager`, and it would
make the mod depend on `java.management` being readable from its module layer.

What the bridge sends: the phase `armed` once the server has started; the server's tick count every 20 ticks; each
event of the in-game test run; and `done` just before the run halts the server. A client joining a server reports its
own phases (`client-…`) and ticks the same way.

## The supervisor

It binds a TCP socket on `127.0.0.1` before the game starts and passes the address and token to the game. It then
reacts to events instead of polling files, and ends its wait in one of these ways
([the full table](../reference/supervision-protocol.md#what-the-supervisor-concludes)):

| Ended | Meaning |
|---|---|
| `DONE` | the game said its run is over |
| `CRASHED` | a crash report was written |
| `STALLED` | heartbeating, and phase, ticks and loaded classes have all stood still for `stallMinutes` |
| `HEARTBEAT_LOST` | connected, then silent for a minute: the whole JVM has stopped, agent included |
| `NEVER_CONNECTED` | alive and silent for two minutes: it never got as far as the agent |
| `PROCESS_DIED` | gone without saying it was done |
| `TIMED_OUT` | still going at `timeoutMinutes` |

On anything but `DONE` the test engine ends the game's process and its children, and reports every test
the game had not finished as failed with that reason, the game's phase and tick, and the path of its log.

`HEARTBEAT_LOST` is the one case the agent cannot cover from inside. A JVM wedged at a safepoint stops every Java
thread, the agent's with it; that is why the supervisor keeps a kill. But it is triggered by a fact with one meaning,
the heartbeat stopping, not by a log going quiet, and the limit can be short because nothing still running goes a
minute without a heartbeat.

There is no fallback to reading files. A game the test engine starts always carries the agent; an agent that never
connects means the JVM never reached `premain`, and that is what the failure says.

## Reading the launch from the loader's run task

The Gradle plugin does not start games; it writes down how its loader plugin would have started them. A run task is
allowed to finish assembling itself inside its own action, and both Loom and ModDevGradle bind the classpath, the
environment and the working directory there, in members they do not publish. `Toolchain` holds one small adapter per
loader that finds those members by name at configuration time and hands back the lazily evaluated values, never the
task, so the launch specs are compatible with the configuration cache. A run task with members of that kind the
adapters do not know is refused, naming its class, rather than launched without them.

Reading a file the loader generates instead was considered and does not work: Loom's `launch.cfg` carries system
properties and program arguments but no classpath and no main class.

## A game without the agent

`JupiterRun.halt` still forces a dedicated server's JVM down thirty seconds after its run halted the server, when no
agent is attached: the one case where nothing else would end a JVM that a mod's thread holds up, such as a server
started by hand. With an agent attached it does nothing.

## Not done

- The test engine collects the `leaked` threads and the idle stacks, and reports neither: a JVM the agent had to end
  after its run passes silently, and a stalled game's failure gives its phase and tick but not where its threads were.
- The companion client of a `dedicatedServerWithClient` scene is started without the agent. It is ended when its
  server's game ends, but nothing watches it for crashing or standing still.
- `results` and the `RESULTS` ending, for a game held open after its run, are still in the agent and the supervisor;
  nothing sends them any more.
