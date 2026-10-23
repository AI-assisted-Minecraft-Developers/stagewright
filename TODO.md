# TODO

Open work. Why a change was made lives in `CHANGELOG.md`; why code is shaped a given way lives in a
comment next to it. This file is only for what is *not done*.

---

## The AWT failed-window dialog is not recognised from its stack

`NoWindow` still matches the failed-window lines in the client's log, and `Stall.describe` still
reads `/proc` for a stuck start no signature fits. Measure the dialog's stack (it needs a failed
`glfwInit`), add it to `Narration.stuckStart`, and delete both. The glfw hang is recognised from
frames recorded earlier, and was not reproduced on 2026-10-24: it did not occur on an unpatched GLFW
with the screen unlocked.

## `CrashWatch` is still in the runner beside the agent's crash event

The agent reports a new crash report and the runner stops waiting on it, but `CrashWatch` also
backs the verdict's no-results explanation and the in-process path, and finds a report written by a
JVM that died before it could say so. Drop it once those read the report from the supervisor instead.

## The harness still forces a JVM down when no agent is attached

`armExitWatchdog` stays for a game started without the agent: by hand, through a `--launch` that drops
JVM arguments, or through a run task the plugin cannot put an argument on. It goes if and when none
of those is supported.

## An attached scene cannot read a field of a held object

A script that writes `pos.x` on an object the game handed back gets a function, because the runner
cannot tell a field from a method name. Methods work. Reading public fields and bean properties would
mean asking the game what the name is before answering.

## The companion adapters have only met stand-ins for the loaders

`Toolchain` finds ModDevGradle's and Loom's late-bound members by name, and the TestKit tests use run
tasks that declare the same members. Run `stagewrightDedicatedServerWithClientFabric` and
`…Neoforge` with `--configuration-cache` once, store and reuse, and compare the companion's logged
command with the one before this change.
