# TODO

Open work. Why a change was made lives in `CHANGELOG.md`; why code is shaped a given way lives in a
comment next to it. This file is only for what is *not done*.

---

## A gate task still waits for a game process that cannot exit

The command-line runner stops at the done footer and destroys what it launched. The Gradle plugin's
run task is a `JavaExec` and waits for the process instead, so a pack whose mods leave non-daemon
threads behind costs that topology its whole `timeoutMinutes` after the suite has already finished.
Same defect as the one the runner already handles, on the other consumer.

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
