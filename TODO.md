# TODO

Open work. Why a change was made lives in `CHANGELOG.md`; why code is shaped a given way lives in a
comment next to it. This file is only for what is *not done*.

---

## `--attached` and holds are not supervised through the agent

`--attached` of the command-line runner and the Gradle plugin's `Hold` task still read files from
outside. A hold never says the run is over, so it needs a "results complete" event that does not end
the JVM. The Gradle plugin's companion client is not supervised either. Design:
`docs/design/supervision.md`.

## A stuck start is reported with stacks but not classified

`NoWindow` still matches the failed-window lines in the client's log, and a stalled game's CPU state
still comes from `/proc`. Measure the glfw spin and the AWT dialog on a display, put their stack
signatures in the agent, and delete both. Crash reports are still found by `CrashWatch` in the runner,
not by the agent.

## The harness still forces a JVM down when no agent is attached

`armExitWatchdog` stays for a game started without the agent, and goes when every launcher attaches one.

## An attached scene cannot read a field of a held object

A script that writes `pos.x` on an object the game handed back gets a function, because the runner
cannot tell a field from a method name. Methods work. Reading public fields and bean properties would
mean asking the game what the name is before answering.

## The companion mechanism is not compatible with the configuration cache

`stagewright { topologies { … companionRunTask = 'x' } }` starts a second game process built from
that task's `JavaExec` spec, read inside the run task's action. A `Task` cannot be serialised into
the configuration cache, so `StageWrightPlugin` declares the incompatibility rather than failing the
build with a stack trace a consumer cannot act on. The cost is one cold configuration per run of a
dedicated-server-with-client topology, against runs measured in tens of minutes — it is on this list
because a consumer who has tuned their own build for the cache sees StageWright switch it off, and
that should be their choice.

The fix is to capture what the companion launch needs — executable, JVM arguments, program
arguments, classpath, environment, working directory — as plain values at configuration time and
hold no task reference at all. `SideProcesses.lateBoundEnvironment` already does this for the two
build systems' staged environment maps, so the shape is known. What makes it more than mechanical is
that both Loom and ModDevGradle finalise parts of a run spec late, and a value read too early
produces a launch that differs from the one Gradle would have performed — which is the failure this
mechanism exists to avoid.
