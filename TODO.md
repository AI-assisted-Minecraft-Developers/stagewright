# TODO

Open work. Why a change was made lives in `CHANGELOG.md`; why code is shaped a given way lives in a
comment next to it. This file is only for what is *not done*.

---

## A kept bot does not survive a restart

`@Bot.Keep` hands a bot to the next test within one game; a test after a restart joins its own, because a
bot's profile is `agent-body-N` from a per-process counter and WorldDriver's join wipes what the save had.
Keeping one across needs a stable profile per name on WorldDriver's side and a join that keeps the saved
player data. `Npc`s are still removed when their test ends.

## `--tests` does not narrow script tests

A Gradle filter naming a Java test class still starts every scene that has script tests and runs them, so a run
meant for one class pays for every script scene. Script tests need a source Gradle's filter can match or exclude.

## The game still forces a JVM down when no agent is attached

`JupiterRun.halt` arms an exit watchdog for a dedicated server started without the agent, by hand or
through a run task the plugin cannot put an argument on. It goes if and when neither is supported.

## Leaked threads and stall stacks are collected but not reported

The test engine gathers both, yet a game the agent had to force down after its run still passes, and a stall
failure names only the phase and the tick.

## The joined client of `dedicatedServerWithClient` runs without the agent

Nothing notices it crashing or hanging.

## `mc_stagewright-api`'s POM declares no dependencies

Its public types reference WorldDriver's api, so a project without the plugin has to add `worlddriver-api:dev`
itself.

## The launch-spec adapters have only met stand-ins for the loaders

`Toolchain` finds ModDevGradle's and Loom's late-bound members by name, and the TestKit tests use run
tasks that declare the same members. Run `stagewrightTestFabric` and `…Neoforge` with
`--configuration-cache` once, store and reuse, and compare the written launch specs with the ones
from a run without it.
