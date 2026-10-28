# TODO

Open work. Why a change was made lives in `CHANGELOG.md`; why code is shaped a given way lives in a
comment next to it. This file is only for what is *not done*.

---

## A body and a save cannot outlive one test

A test's bodies and plot go back when it ends, so a campaign that plays one body through many steps
on one world (worlddriver's survival ladder, deleted on 2026-10-28 for this reason) cannot be a
chain of `@DependsOn` tests. It needs a body that later tests can take over, the world kept between
them, and a place for evidence rows beyond `record`'s report entries.

## The game still forces a JVM down when no agent is attached

`JupiterRun.halt` arms an exit watchdog for a dedicated server started without the agent, by hand or
through a run task the plugin cannot put an argument on. It goes if and when neither is supported.

## The launch-spec adapters have only met stand-ins for the loaders

`Toolchain` finds ModDevGradle's and Loom's late-bound members by name, and the TestKit tests use run
tasks that declare the same members. Run `stagewrightTestFabric` and `…Neoforge` with
`--configuration-cache` once, store and reuse, and compare the written launch specs with the ones
from a run without it.
