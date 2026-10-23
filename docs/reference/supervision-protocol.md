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
| `heartbeat` | `atMs`, `phase`, `ticks`, `classes` when the JVM can say | every second |
| `phase` | `phase` | when the game reports a phase different from the last |
| `done` | none | when the game reports the run over |
| `leaked` | `graceMs`, `threads` | once, when the JVM is still up `grace` seconds after `done` |
| `exiting` | none | from a shutdown hook, when the JVM ends in an orderly way |

Any event counts as a sign of life. A thread in `leaked.threads` has:

| Field | Meaning |
|---|---|
| `name` | the thread's name |
| `stack` | its top twelve frames, as `StackTraceElement.toString` gives them |
| `jars` | where the classes on that stack were loaded from, in stack order, without the JDK's own |

`leaked` lists the non-daemon threads still alive, `DestroyJavaVM` excluded, and may be empty when
the JVM is held up by something that is not a thread. The agent ends the JVM with `Runtime.halt(0)`
right after sending it: the results file, not the exit code, carries the verdict.

## What the supervisor concludes

| Ended | When |
|---|---|
| `DONE` | the agent sent `done`. Checked before whether the process is alive: a game that says so and exits in the same second is done, not dead |
| `PROCESS_DIED` | the process is gone and never said `done`, once its connection has closed so that its last lines are read |
| `HEARTBEAT_LOST` | connected, and no line for longer than the limit: the whole JVM has stopped |
| `NEVER_CONNECTED` | alive and silent for longer than the connect limit: it did not reach the agent |
| `STALLED` | heartbeating, and none of `phase`, `ticks` and `classes` has changed for longer than the stall limit. Loading a pack moves `classes`, running a suite moves `ticks`, so a game that moves neither is stuck |
| `CRASHED` | the caller's probe said so; the command-line runner asks whether a crash report has appeared |
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
