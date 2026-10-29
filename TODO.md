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

Moving six consumers to this path (2026-10-29) left these open, by weight: a script cannot catch a failed call,
`waitUntil` or `expect`, and has no after-test hook, so nothing cleans up after a failure; `t.record` values reach no
report, and a blank value fails the test; `mc.action.runCommand` runs as the first player, which is a bot, so a pack
with LuckPerms refuses every command and `@s` is another test's bot; a bot's `name` is not its player name
(`agent-body-N`); a topology that cannot launch fails its tests instead of skipping them; the CLI does not follow a
symlinked tests directory, and does not notice a stale harness jar in `mods/`; scripts have
no `Java` global and no way to run several commands in one tick; plots far from spawn lose non-persistent mobs.
