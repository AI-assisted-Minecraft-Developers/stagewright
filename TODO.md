# TODO

Open work. Why a change was made lives in `CHANGELOG.md`; why code is shaped a given way lives in a
comment next to it. This file is only for what is *not done*.

---

## A body and a save cannot outlive one test

A test's bodies and plot go back when it ends, so a long survival run that plays one body through
many steps on one world (worlddriver had one, deleted on 2026-10-28 for this reason) cannot be a
chain of `@DependsOn` tests. It needs a body that later tests can take over, the world kept between
them, and somewhere to keep what each step observed beyond `record`'s report entries.

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

## Leftovers of the old results path

`results`, `RESULTS` and `resultsComplete()` in the agent and supervisor, and the `-Dstagewright.scenesDir` read,
are reached by nothing now.

## `mc_stagewright-api`'s POM declares no dependencies

Its public types reference WorldDriver's api, so a project without the plugin has to add `worlddriver-api:dev`
itself. The CLI runner also never calls `DriverRequirement`, so a missing WorldDriver shows up as a loader refusal.

## The launch-spec adapters have only met stand-ins for the loaders

`Toolchain` finds ModDevGradle's and Loom's late-bound members by name, and the TestKit tests use run
tasks that declare the same members. Run `stagewrightTestFabric` and `…Neoforge` with
`--configuration-cache` once, store and reuse, and compare the written launch specs with the ones
from a run without it.
