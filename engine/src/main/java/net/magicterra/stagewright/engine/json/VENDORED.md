# minimal-json, vendored

`com.eclipsesource.minimal-json:minimal-json:0.9.5`, MIT, © 2013–2016 EclipseSource.
Sources copied verbatim from the published `-sources` jar; the only edit is the package
declaration, moved to `net.magicterra.stagewright.engine.json`. Every file keeps its
original MIT header, which is the whole of the license obligation.

StageWright as a whole is LGPL-3.0-only; this directory is the one exception and stays
MIT. MIT is compatible with the LGPL in this direction, so distributing the combined work
under the LGPL is fine — but the headers below are the upstream author's terms, not ours.
They are not covered by the repository's `COPYING.LESSER` and must not be replaced with an
LGPL notice or stripped.

## Why a copy instead of a dependency

This parser decides whether a build passes, and it runs on a consumer's **buildscript**
classpath, which is the worst place to need a coordinate to resolve. The gradle plugin
declares `stagewright-engine`, and a transitive `com.eclipsesource:minimal-json` would
have to resolve from whatever `pluginManagement { repositories }` a consumer happens to
have — worlddriver's, for one, lists no Maven Central at all and would be relying on
gradlePluginPortal proxying it. "Probably resolves" is not a property the verdict
component may have.

Copying removes the question. There is no coordinate, no version to conflict with
anything else on that classpath, and no repository that has to be reachable.

## Why not hand-written

It was, and that was the wrong call. A parser is exactly the kind of code where the
edge cases — escapes, surrogate pairs, number grammar, nesting depth — are unglamorous,
easy to get subtly wrong, and directly able to make the gate report the wrong answer.
Borrowing a tested one costs a directory of files nobody has to read.

## Rules

**Do not edit these files.** Fixes go upstream or into an adapter beside them. The only
sanctioned local change is the package rename, so that re-vendoring a newer release stays
a mechanical `sed`.

`Json` in the parent package is that adapter: it converts minimal-json's model into the
plain `Map`/`List`/`String`/`Double`/`Boolean` shapes the rest of the engine reads.
