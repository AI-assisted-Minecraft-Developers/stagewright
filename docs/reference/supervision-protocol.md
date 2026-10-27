# Supervision protocol

What the agent in a game's JVM says to the process that started it, and what the mod says to the
agent. Written to be correct rather than readable in order. The reasons are in
[Supervision](../design/supervision.md).

## Attaching the agent

The supervisor adds one JVM argument, placed where a `-D` goes — before the loader's argument file,
so the JVM reads it rather than the launcher:

```
-javaagent:<agent jar>=<host>:<port>:<token>[,grace=<seconds>]
```

| Part | Meaning |
|---|---|
| `host`, `port` | where the supervisor listens: the loopback interface, on a port it chose before starting the game |
| `token` | a one-time value the supervisor generated; the agent repeats it in its first line |
| `grace` | seconds the agent lets the JVM end by itself after `done`, default 30 |
| `idle` | seconds without any change in `phase`, `ticks` or `classes` after which heartbeats carry `stacks`, default 20 |

An argument the agent cannot parse is reported on standard error and leaves the JVM as it would
have been without the agent. The agent never stops a game from starting.

## The channel

One TCP connection from the agent to the supervisor, carrying one JSON object per line in UTF-8.
Nothing flows the other way. The agent retries the connection for ten seconds; when it cannot connect it
says so on standard error and the game runs unsupervised.

The first line must be a `hello` carrying the supervisor's token. A connection whose first line is
anything else, or carries another token, is closed, and so is a second connection once one has
been accepted. A line that is not JSON, and an event this version does not know, are ignored: the
agent may be newer than the supervisor.

## Events

Every line has `event`. The agent's events:

| `event` | Fields | Sent |
|---|---|---|
| `hello` | `token`, `pid`, `agent` (the agent's protocol version), `java` | first, once |
| `heartbeat` | `atMs`, `phase`, `ticks`, `classes` when the JVM can say, and `stacks` once `idle` seconds have passed with none of the three changing | every second |
| `phase` | `phase` | when the game reports a phase different from the last |
| `done` | none | when the game reports the run over |
| `results` | none | when a held game's results are complete; it stays up and the agent ends nothing |
| `crash` | `report` (the path) | once for each file that appears in `<working directory>/crash-reports` after the agent started; the listing is taken in `premain`, so a previous run's reports are not this run's. Looked for once a second |
| `leaked` | `graceMs`, `threads` | once, when the JVM is still up `grace` seconds after `done` |
| `exiting` | none | from a shutdown hook, when the JVM ends in an orderly way |
| `test` | `data`, one object of the in-game test run (see [Test events](#test-events)) | each time the game relays one |

Any event counts as a sign of life. A thread in `leaked.threads` has:

| Field | Meaning |
|---|---|
| `name` | the thread's name |
| `stack` | its top twelve frames, as `StackTraceElement.toString` gives them |
| `jars` | where the classes on that stack were loaded from, in stack order, without the JDK's own |
| `origin` | `class.method` for the frames that started the thread, innermost first, without the JDK's and the agent's own; empty when the JVM did not see it start |
| `originJars` | where the classes in `origin` were loaded from |

A parked pool worker's own stack is all JDK, so `origin` is what names its owner. It comes from the
JVM's `jdk.ThreadStart` events, which the agent records through a JFR stream opened in `premain`,
before the game's `main`; only the JVM's own threads start earlier. A JVM without `jdk.jfr` reports `stack`
and `jars` only. The stream's reader is the one thread of the agent's that is not a daemon, so the
agent closes the stream as soon as nothing but it and `DestroyJavaVM` is keeping the JVM up, and does
not list it among the leaks.

An element of `stacks` has `name` and `stack` (the top eight frames), for the threads called `main`,
`Render thread` and `Server thread`.

The phases the game reports are `armed` (the mod is loaded and the world is up), `waiting-for-player`
(a dedicated server holding the suite until a client joins, which is why a client that never arrives
shows as a server that stopped moving) and `harness-ready` (the suite is armed and running).

`leaked` lists the non-daemon threads still alive, `DestroyJavaVM` excluded, and may be empty when
the JVM is held up by something that is not a thread. The agent ends the JVM with `Runtime.halt(0)`
right after sending it: the results file, not the exit code, carries the verdict.

## What the supervisor concludes

| Ended | When |
|---|---|
| `DONE` | the agent sent `done`. Checked before whether the process is alive: a game that says so and exits in the same second is done, not dead |
| `RESULTS` | the agent sent `results`: a game held open on purpose has its results complete. Ranks with `DONE` above a crash probe; the caller ends the game, since nothing else will |
| `PROCESS_DIED` | the process is gone and never said `done`, once its connection has closed so that its last lines are read |
| `HEARTBEAT_LOST` | connected, and no line for longer than the limit: the whole JVM has stopped |
| `NEVER_CONNECTED` | alive and silent for longer than the connect limit: it did not reach the agent |
| `STALLED` | heartbeating, and none of `phase`, `ticks` and `classes` has changed for longer than the stall limit. Loading a pack moves `classes`, running a suite moves `ticks`, so a game that moves neither is stuck |
| `CRASHED` | the agent sent `crash`, or the caller's probe said so; the command-line runner also asks whether a crash report has appeared, for a game whose JVM died before it could say. Ranks below `DONE` and `RESULTS` and above whether the process is alive |
| `TIMED_OUT` | alive and talking, and not done within the ceiling |

`DONE` says when to read the results file, and nothing about what is in it.

## The game to the agent

The agent's jar is on the system class path, so the mod reaches it without linking against it:
`ClassLoader.getSystemClassLoader().loadClass("net.magicterra.stagewright.agent.Control")`, then a
static method by reflection. Every signature uses JDK types only. Each method does nothing when the
JVM was not started with the agent, and none throws.

| Method | Meaning |
|---|---|
| `phase(String)` | where the game is: `harness-ready`, `scenes-running` |
| `ticks(long)` | server ticks delivered so far, sent with the next heartbeat |
| `done()` | the run is over and its results file is complete |
| `resultsComplete()` | the results file is complete and the JVM stays up, because it is held for something to attach to. Starts no grace period. Absent from an older agent, and the mod then loses only this report |
| `test(String)` | one event of the in-game test run, a JSON object, sent on as a `test` line's `data`. The agent does not read it. Absent from an older agent, and the game's tests then reach no one outside |

## Test events

The in-game JUnit run reports itself through `test`, in the order it happens; the supervisor hands each
`data` to its listener on the thread reading the connection, so that order is kept. Every object has `type`:

| `type` | Fields | Sent |
|---|---|---|
| `planned` | `nodes`: every node of the plan, parents before children | once, before anything runs |
| `dynamic` | `node`: one node registered while running, such as an invocation of a parameterized test | when it is registered |
| `started` | `id` | when a node starts |
| `skipped` | `id`, `reason` | instead of `started` and `finished`, and for none of the node's children; a `reason` beginning `@Requires: ` is an unmet requirement |
| `finished` | `id`, `status` (`SUCCESSFUL`, `FAILED`, `ABORTED`), `failure` when there is one | when a node ends |
| `ended` | none | once, after the last node; `done` follows when the game was told to exit |
| `error` | `failure` | instead of `planned`, when the run could not start, such as a `@DependsOn` cycle |

A node has `id` (JUnit's unique id in the game), `parent` (absent on an engine), `name` (the display name),
`test` (whether it is a test rather than a container), and from its source `class`, and for a method `method`
and `parameters` (the parameter types, comma-separated, as JUnit's `MethodSource` spells them). A script file
and the tests it registered have `script` instead (the class-path resource, such as `stagewright/tests/x.js`); a
test is the one with `test` true, and its `name` is the name it was registered under.

A `failure` has `class` (the throwable's class name), `message` when it has one, `trace` (at most the top 64
frames, as `StackTraceElement.toString` gives them), and `cause`, a failure of the same shape, when there is one.
