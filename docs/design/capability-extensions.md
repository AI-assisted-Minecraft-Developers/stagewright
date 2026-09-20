# Capability extensions: teaching the framework about things the base game has no concept of

For how to *use* these extension points, see the [capabilities guide](../guide/capabilities.md).
This document is about why they are shaped the way they are.

## The gap

The scene facets cover the vanilla server API. Everything a modpack is actually about lives
outside it — accessory slots, quest graphs, chemical tanks, stress networks, storage grids — and
that is where nearly all the value of testing a modpack sits.

Before there was a seam, two such integrations existed as reflection hard-coded inside
`:stagewright-api`. Three consequences followed, and each is worse than the last:

- Making a third mod testable meant changing StageWright, releasing it, and everyone upgrading.
  **The framework was the bottleneck.**
- A pack author holding a mod nobody here has heard of had no route at all — not even an ugly one.
- Those hard-coded reflection strings had no compile-time constraint anywhere, so a mod renaming
  a package produced a failure on **somebody else's** check.

## Two audiences, two doors

| Who | Has | Needs |
|---|---|---|
| A mod author | Java, a build, their own classes on the compile classpath | A service interface: write an adapter, and anybody's scenes can use it. |
| A pack author | a folder of scene files, configs and jars, and no way to compile anything | Either an adapter that already exists, or a way to describe one as data. |

Both doors have to open. Opening only the first shuts out pack authors, who are half the point.
Opening only the second would force mod authors to write reflection against **their own** classes,
which is absurd.

## The first door: the provider interface

The shape follows the scene provider interface, which had already proved itself: an interface in
the published contract module, discovered by the service loader. A provider names the capability,
answers whether it works in this runtime, and returns the object scenes call.

The same object reaches a Java scene and a JavaScript one, because JavaScript reaches it by
direct reflection over the returned instance. A mod author writes one adapter and both kinds of
scene have it.

Two decisions inside that interface carry most of the design weight.

### Discovery must survive a provider that cannot load, or the door does not open at all

An adapter written for an accessory mod **imports that mod's classes**. In a runtime without it,
instantiating that adapter throws a linkage error — and a plain iteration over the service loader
would take every *other* provider down with it, including ones that would have worked.

So discovery streams the providers and wraps each instantiation individually: one that cannot
load is recorded as absent *with the reason*, and its neighbours are unaffected. The same trap
has a subtler half already recorded elsewhere in this codebase: building a list of method
references resolves every class in the list before the loop body runs, so a try/catch around the
calls never sees the error. The probe must come first and must go by name.

That single piece of defensiveness is what makes the door usable, because it lets an adapter be
written **the natural, typed way**. An adapter failing to load in a runtime without its mod is
correct behaviour, not an error to be handled. Nobody has to write reflection to survive.

### Absence is a skip, not a failure

A missing mod is not the pack's defect — but it must not pass silently either. A scene asking for
an absent capability records a skip naming what was absent and what *is* present, which is the
same semantics as asking for a player on a topology that has none.

A provider may supply its own absence message, and one that names the mod and where to get it
turns "this scene skipped" into "install X" for whoever reads the results a week later.

### Naming, and the one place duplicates are allowed

Bare names are reserved for the adapters StageWright ships; a third party uses
`<modid>:<what>`. **A duplicate name is rejected loudly, naming both classes** — for exactly the
reason duplicate scene names are: the later registration silently wins, and a scene then tests
something it did not mean to.

There is one deliberate exception, in the second door, and it is the mirror image: a pack's own
descriptor with the same name as a shipped one **replaces** it. A collision between two mod
authors is an accident; a pack correcting the framework is the only available fix when a fork has
moved a class, and that author should not have to wait for a release.

### The rule nothing can enforce

A facet's own surface must obey the JavaScript safety rule — parameters and return values are
StageWright types, string identifiers or primitives, never a Minecraft object the scene is then
expected to call a method on. This is on the provider author and no compiler checks it. Getting
it wrong produces a facet that works on one loader and throws on the other, which is the most
expensive shape of bug this framework has.

## The second door: reflection, and the hard rule that makes it sound

A pack author reaching a mod with no adapter has only reflection. Give it to them — bounded, and
speaking plainly when it fails.

`Probe` binds to a class by name, calls methods and reads fields on it, and **wraps every
non-primitive return value in another `Probe`** rather than handing back a bare object. That buys
two things: chaining, and the guarantee that a scene never receives a raw Minecraft object it
might be tempted to call a method on. Leaving the probe is explicit — ask for a string, an int, a
double, a boolean, a list, or whether it is null.

Its errors list candidates. A message saying only "no such method" sends the author to a
decompiler; one that names the methods the class actually has gets them back to work immediately.
The same applies to a wrong argument count.

### `Probe` refuses `net.minecraft.*`

This is the rule that makes the second door sound, and it is about correctness rather than
caution.

The safety rule says a scene may hold a Minecraft object but must never call a method on one by
name, because a production Fabric jar is intermediary-mapped. **A mod's own classes do not
participate in that remapping** — a mod package is spelled the same in every runtime. So calling
a method by name is *safe against a mod's API* and *necessarily wrong against Minecraft*.

`Probe` turns that into a machine rule: a class name under `net.minecraft` is refused, with an
error explaining why and pointing at the facet that covers the same ground.

Passing a Minecraft object **as an argument** is allowed, because that is holding, not calling.
Which is exactly what lets the player or the level be handed to a mod's API while the whole chain
stays safe.

And `Probe` is an escape hatch, not an API. The same reflection appearing three times in one
pack is a signal that it should become an adapter — said plainly in the documentation, because
otherwise this door becomes the reason nobody writes one.

### Between the two doors: descriptors as data

The provider interface asks for a jar, and a pack author has a folder. Telling them to stand up a
build in order to name a class is the same mistake as telling them to stand one up in order to
write a scene. So a capability can be a JSON file, holding the conditions under which it is
available and the class its probe binds to.

What is bought is **a layer of indirection**: the class name lives in one file rather than in
every scene that reaches for it. A mod renaming a package then breaks one line instead of a
suite.

Three of its rules are load-bearing.

**Conditions can be registry contents, not only the mod list.** A descriptor may require mods,
any of a set of mods, classes, items or blocks. The registry conditions are the only way to catch
"the mod is installed but its content is switched off in config" — a mod loaded with a feature
disabled registers no items, and no amount of mod-list checking notices.

**A descriptor with no conditions is refused at load.** It would report itself available in every
runtime, so scenes gated on it would run against a pack that does not have the thing and then fail
for a reason unrelated to the pack. Refusing the file is better than that.

**Descriptor files and scene files share one folder, sorted by extension.** A pack author has a
folder; a capability descriptor is authored in the same sitting as the scene that needs it, usually
to make that scene work at all. Two flags and two directories would impose a build tool's filing
system on somebody who does not have a build tool.

## The bundled adapters

**Two shipped integrations** — accessory slots and the quest graph — are registered through the
same seam a third party uses. Their code did not move, and scenes still reach them through their
original accessors. What registering them buys is that they stop being special cases: they are
reported alongside anything a mod contributed, so a skip can name what the runtime actually had;
"present means it works, absent means skip" becomes one rule with several implementations rather
than several implementations of an unwritten rule; and whoever writes the third integration has
two worked examples that are not toys.

Both are reflective, which the interface explicitly tells third parties **not** to copy. The
reason is specific to shipping: they live in the harness jar, which is present in every run
whether or not those mods are, so their classes must load in a runtime that has neither. An
adapter shipped inside the mod it adapts has no such problem and should be written against real
types.

**A short list of shipped descriptors** covers a handful of large mods, each gated on a *class*
rather than on a mod identifier — because the class existing is exactly the condition under which
its probe works, which takes identifier spelling out of the equation. Every one of them was read
out of the mod's own jar rather than recalled. A pack with one of those mods gets a handle on it
having configured nothing; a pack without gets a skip that says so.

The list is deliberately short. It is not an ecosystem census, and the mod-list facet already
answers "is X here". An entry earns its place only by naming a stable API root that a scene would
otherwise hard-code. It is a resource file rather than a directory, because enumerating a
directory inside a jar works in an exploded development build and fails in a real one — precisely
the shape of bug that is invisible on the developer's machine and broken for every user.

**Three block-capability facets** — item handler, energy and fluids — are the other half of
"bundled", and they follow a different principle worth stating on its own.

### Write to the platform capability, not to the mod

Writing an adapter per mod works for *one* mod and is absurd for a *class* of mods. A dozen tech
mods have a dozen incompatible energy APIs, but **energy storage is one platform capability** —
it is how hoppers and pipes already talk to all of them. Written against the capability, one
implementation covers every such mod including the ones that appear after the code is written,
and it cannot over-claim: a block with no handler reports that it has none rather than guessing.

The interfaces live in the published contract module and the implementations in the loader module,
because a capability belongs to the loader and the contract module depends on neither. That
placement is also a standing invitation: implement the same interfaces over the other loader's
storage API and **existing scenes need no change**. Until then, scenes on that loader record a
skip explaining why.

Three further rules:

- Coordinates are **arena-relative**, like every other block verb. Absolute coordinates would be
  the one place in the API where a scene has to know where it was put, which is the entire thing
  the grid exists to hide.
- Arguments and results are **primitives and string identifiers only** — the JavaScript safety
  rule, in a facet that crosses a loader boundary and has no room to bend it.
- A block with no handler is a **failure naming the block**, not a null and not a zero. Asserting
  about the inventory of something with no inventory has already found a problem and deserves a
  sentence. A scene that wants to branch asks whether it is present.

One implementation detail is a design decision rather than plumbing. **Writes try every face.** A
block capability is queried *with a direction*, and null means "no particular side". Most mods
answer null with an internal view carrying the machine's real numbers — so **reads are correct**
— while *insertion* is governed by the machine's side configuration and granted only on faces
configured as inputs. A block can therefore report a large capacity, report that it accepts
energy, and accept none of it. So a write tries the no-side view and then each of the six faces,
stopping at the first that accepts, with handlers deduplicated by identity so a block exposing one
handler on every face is not asked seven times.

That is the default a test API should have. A scene asserting that a machine can be charged should
not first become a scene about that machine's side configuration — especially since nothing in the
symptom points at sides, and the author's available conclusions are "the machine is broken" or
"the arena is not running".

## The foundation both doors stand on

Every conditional in a pack's scene starts with "is X in this pack", and before there was a
mod-list facet a scene could not ask it. It reports which mods are loaded and at what version, and
answers the three shapes an author needs: is this one here, is at least one of these here — for
forks, renames and compatibility layers — and require this one or skip.

The list is **pushed in** by each loader's entry point at start-up rather than read reflectively.
The loader's own classes do not participate in remapping, so reflection would work; but reflection
here means two possibly-wrong strings reaching for data the entry point already holds, typed.

The rule that matters most is what happens when nobody pushed one. **The facet raises, and never
returns an empty list.** An empty list would make every query answer false, so a pack's
conditional scenes would all skip saying a mod is not installed — while it is installed. That is
the framework's own wiring broken, wearing an absent mod's clothes.

## The species this whole surface is built to avoid

That last paragraph is not an isolated caution. It is the recurring failure of this entire design
area, and it has been hit repeatedly: **a framework wiring failure that presents exactly as an
uninstalled mod.** Every piece of defensiveness above exists because of it.

Three instances, each with its fix:

**Asking only "which capabilities are available" cannot detect a broken registry.** A runtime
where discovery found nothing and a runtime where it found two providers whose mods are both
absent give the *identical* answer to every availability question. So a dropped services file, a
resource a shadow merge swallowed, or a jar published without its metadata all present as "this
pack does not have that mod", and a self-test suite asserting only availability stays green
through all of them.

The first version of that self-test was written exactly that way and was **completely immune to
the failure it was meant to catch**. The fix is to separate two questions that are easy to
conflate: which capabilities are *available*, and which providers *loaded* at all. A self-test then
asserts that the shipped providers loaded **and** that none is available — a pair only "the
framework works and the mods are absent" can satisfy.

The skip message splits the same way, because these are three different situations for whoever
reads it: the provider said no and the mod is simply not installed; there is no provider by that
name but there are others, so the name is wrong or that adapter was never published; or **nothing
loaded at all**, which is StageWright's own bug wearing the costume.

**Service files in two modules must merge.** The provider registrations live in the published
contract module and in the loader module, and the shadow packaging step's default is
last-writer-wins per path. Without an explicit merge the jar ships one of the two, and every
capability in the other reports itself absent — identical to the mods not being installed. The
merge is load-bearing rather than hygiene, and the reason is recorded in the service files
themselves, where the next person to touch one will see it.

**A mod list that never arrived.** The third instance, and the one described above.

## Connected is not the same question as walkable

Everything above is about wiring: how a scene reaches a thing the base game has no concept of.
Pointing that wiring at a real several-hundred-mod pack surfaced a second distinction, unrelated
to capabilities and just as load-bearing for what these scenes are worth.

**Is the path connected?** — the recipe resolves, the quest's dependencies still exist, the
advancement's parent has not moved. **Can the path be walked?** — actually unlock the
advancement, actually claim the quest, actually make the world generate the structure.

They are different questions and both are worth scenes. The connectivity half deserves the
larger share, because it is the half that breaks *silently* when several hundred mods update
together, and it answers in seconds where walking a path may not be possible at all. But it is
not a substitute: a graph can be perfectly closed and still lead somewhere unreachable.

Two facts about the walkable half are worth knowing before writing one.

**Granting progression needs a real player, and there is no way around it on a bare server.**
Advancements are awarded to a player, and quest completion hangs off team data created when a
player joins. From inside a server there is no substitute: the loader's own fake-player type
returns false from the award call unconditionally — deliberately, since a fake player is not
supposed to earn anything. So those scenes are written, skip on a topology with no player with a
reason that says so, and pass unchanged on a topology that has one. The mechanism itself is
verified elsewhere, on a topology with a player, which is what keeps the skip from being an
untested claim. The cross-run coverage check in the
[orchestration contract](../reference/orchestration-contract.md) exists precisely so that a
scene skipping on *every* topology cannot stay quietly green.

**"Searched and found nothing" and "this generator cannot place that at all" are the same return
value.** The structure search answers null immediately when the generator has no placement for
the structure, and answers null after a long scan when it genuinely found none — and the only
outward difference is how long it took. A scene pinned to one structure is therefore asserting
about *the pack's worldgen configuration* while reporting it as worldgen being broken. Ask about
several and assert that at least one is placeable, which is a line no playable pack fails.

## Traps

- **Service-loader context.** The registry has to be resolved on the server thread, once, at the
  same moment the rest of the framework's discovery happens.
- **Availability is asked more than once and is cached for the run.** It must be cheap and free
  of side effects.
- **A facet is built once per scene and reused.** Returning a fresh instance per call is the bug
  that makes an adapter registering a cleanup register several.
