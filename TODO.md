# TODO

Open work. Why a change was made lives in `CHANGELOG.md`; why code is shaped a given way lives in a
comment next to it. This file is only for what is *not done*.

---

## The companion mechanism is configuration-cache incompatible

`stagewright { topologies { … companionRunTask = 'x' } }` starts a second game process built from
that task's `JavaExec` spec, read inside the run task's action. A `Task` cannot be serialised into
the configuration cache, so the run task declares the incompatibility
(`notCompatibleWithConfigurationCache`) rather than failing the build with a stack trace a consumer
cannot act on.

The cost is one cold configuration per gate run of a `dedicatedServerWithClient` topology — seconds,
against runs measured in tens of minutes. It is on this list because a *consumer* who has tuned their
own build for the cache sees StageWright turn it off, and that should be their choice to make.

The fix is to capture what the companion launch actually needs — executable, JVM args, program args,
classpath, environment, working directory — as plain values at configuration time, and hold no task
reference at all. `SideProcesses.lateBoundEnvironment` already does exactly this for the two build
systems' staged environment maps, so the shape is known; the rest of the spec is the same kind of
read. What makes it more than a mechanical change is that both loom and ModDevGradle finalise parts
of a run spec late, and a value read too early is a launch that differs from the one Gradle would
have performed — which is the failure this mechanism exists to avoid.

## Mekanism blocks are unreachable through the menu seam

Not a defect and not scheduled — recorded so it is not rediscovered. `TileEntityMekanism` is not a
`MenuProvider`, so `s.menu().openAt(...)` cannot reach any Mekanism machine no matter how the facet
asks. `hasMenuAt` reports it honestly and the capability seam reads the same blocks fine. Covering
them would mean a second opening path per mod family, which is the thing this framework does not do.
