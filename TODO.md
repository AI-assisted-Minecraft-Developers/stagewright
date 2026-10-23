# TODO

Open work. Why a change was made lives in `CHANGELOG.md`; why code is shaped a given way lives in a
comment next to it. This file is only for what is *not done*.

---

## A stuck start is reported with stacks but not classified

`NoWindow` still matches the failed-window lines in the client's log, and a stalled game's CPU state
still comes from `/proc`. Measure the glfw spin and the AWT dialog on a display, put their stack
signatures in the agent, and delete both. Crash reports are still found by `CrashWatch` in the runner,
not by the agent.

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
