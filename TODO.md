# TODO

Open work. Why a change was made lives in `CHANGELOG.md`; why code is shaped a given way lives in a
comment next to it. This file is only for what is *not done*.

---

## A kept bot does not survive a restart

`@Bot.Keep` hands a bot to the next test within one game; a test after a restart joins its own, because a
bot's profile is `agent-body-N` from a per-process counter and WorldDriver's join wipes what the save had.
Keeping one across needs a stable profile per name on WorldDriver's side and a join that keeps the saved
player data. `Npc`s are still removed when their test ends.

## The game still forces a JVM down when no agent is attached

`JupiterRun.halt` arms an exit watchdog for a dedicated server started without the agent, by hand or
through a run task the plugin cannot put an argument on. It goes if and when neither is supported.

## The plugin does not run on Gradle 9

`StageWrightLaunchSpecsTask` resolves the run's classpath while executing (`CompanionLaunch.writeClasspathArgFile`),
and the run belongs to another project, which Gradle 9 refuses: "Resolution of the configuration
':fabric:testmodRuntimeClasspath' was attempted without an exclusive lock". The classpath has to become a task input
resolved at configuration time. Fabric Loom 1.19 needs Gradle 9.8, so worlddriver stays on Loom 1.13 until then.

## What the third-party consumers found

Moving six consumers to this path (2026-10-29) left these open, by weight: scripts have no `Java` global; scene-level
report entries (`world`, `mods`) reach no XML; a Java test's generated callers (`game.api(...)`) still give up when the
server thread does not take their task within WorldDriver's 8 seconds; the CLI's `--client-dir` has not been through
a client run.
