# StageWright documentation

StageWright is an in-game integration-test framework for Minecraft mods. A scene builds a
situation in a live world, drives it, and asserts an outcome; the framework launches the game in
a chosen process topology, runs the scenes and reports a machine-readable result.

If you have not used it before, start with the [repository README](../README.md) and then
[Getting started](guide/getting-started.md).

The documentation is in four parts, and the difference between them is what kind of question
each answers.

## Guides — how to do something

Written for someone with a task in front of them.

| Document | Read it when |
|---|---|
| [Getting started](guide/getting-started.md) | You want a first passing run, whether you ship a modpack and have no build tool or you build a mod with Gradle. |
| [Writing a scene](guide/writing-a-scene.md) | You are writing scenes: the lifecycle, declaring what a scene needs, asserting, registration, naming, and what is frozen about the world. |
| [Capabilities](guide/capabilities.md) | A scene needs to reach machinery the base game has no concept of, or you want to teach the framework about your own. |
| [Topologies](guide/topologies.md) | You need to know which process shape can establish the fact you are trying to establish. |
| [Running the checks](guide/gates.md) | You are running a suite and reading what came back, including the failures that are not a failing scene. |
| [The Gradle plugin](guide/gradle-plugin.md) | You are wiring StageWright into a project: tasks, properties, the source-set convention. |
| [Attaching a JUnit suite](guide/junit-attach.md) | You need to assert something a scene cannot assert about itself, from outside the game process. |

## Reference — what the contracts are

Written to be correct rather than readable in order. These are the agreements two separately
built programs rely on, so they are stated as literal names, keys and codes.

| Document | Covers |
|---|---|
| [Orchestration contract](reference/orchestration-contract.md) | The interface between whatever launches the game and the harness inside it: startup, the results file, exit codes, in-game timing guarantees, scene discovery, manifest reconciliation, coordinate pinning. |
| [Instrument contract](reference/instrument-contract.md) | The instrumentation surface reachable from outside the game, and the permanent assertions and canaries by which the framework establishes that it is itself sound. |
| [Attached protocol](reference/attached-protocol.md) | The seven hidden methods by which a scene file running outside the game gets an arena in it and calls into it: the run, the answers, and how values cross. |
| [Supervision protocol](reference/supervision-protocol.md) | What the agent in a game's JVM sends its supervisor, the conclusions drawn from it, and how the mod talks to the agent. |
| [Scene file](reference/scene-file.md) | A scene's declaration: its topology, and the world it runs on — what applies at creation, what is set once loaded, and the defaults. |
| [Launch spec](reference/launch-spec.md) | The file that tells the test engine how to start one topology's games, and what the engine adds to the command. |
| [Publishing and artifacts](reference/publishing.md) | What is published, which coordinate to depend on for which job, and why the wrong one fails to link rather than failing to resolve. |

## Design — why it is shaped this way

Written for someone who wants to change the framework, or who disagrees with a decision and
wants to know what it was weighed against.

| Document | Decision |
|---|---|
| [Attached scenes](design/attached-scenes.md) | Running scenes outside the game process: where that capability lives, what it can honestly offer, and what it must refuse rather than approximate. |
| [Scene capabilities](design/scene-capabilities.md) | Moving from scenes that place blocks to scenes that test processes: the constraints that determined the shape, and the silent-failure modes it has to defend against. |
| [Capability extensions](design/capability-extensions.md) | The two routes by which a third-party mod or a modpack teaches the framework something new, and the rule that makes the reflective route sound. |
| [The test engine](design/test-engine.md) | Tests run inside the game and are reported by an outer JUnit engine: finding them without loading them, matching the two trees, the one channel back, and why it reads a launch spec instead of a Gradle task. |
| [Supervision](design/supervision.md) | How a launcher learns that a run finished, stalled, crashed or cannot exit: an agent inside the game's JVM reporting over a local channel, instead of reading files from outside. |

## Archive — historical records

[`archive/`](archive/README.md) holds documents kept for provenance. Each describes the project
as it was when written and none of it documents current behaviour. It currently holds one report
from the first external project to adopt the framework.

## Maintaining this index

Every document under `docs/` appears in exactly one table above, and a new document is added to
the index in the same change that creates it — an unindexed document is one nobody finds and
nobody updates.

Two rules have been expensive to learn here and are worth restating. First, a claim is verified
against the code, not against the previous version of the sentence; most errors found in this
documentation were inherited by copying, and several had survived more than one revision that
way. Second, do not write a count that will be wrong next month — point at the file that holds
the answer.
