# Capabilities

The facets on `SceneContext` cover the vanilla server API. Almost everything a modpack is *about*
lives outside it — accessory slots, a quest graph, a chemical tank, a stress network, a storage grid
— and without a seam, every one of those would cost a StageWright release and a version bump for
everybody.

The seam is the capability system. This guide covers it from both ends: the scene author asking for a
capability, and the mod or pack author teaching the framework a new one.

## Starting from what is installed

Before anything conditional, `s.mods()` answers what is actually in this run.

```java
s.mods().loaded("mekanism")      // is it here
s.mods().any("ae2", "appeng")    // a fork, a rename, a compat shim — one call holds them all
s.mods().all("create", "curios")
s.mods().version("create")       // "" when absent
s.mods().ids()                   // every loaded id, sorted
s.mods().count()                 // the one number that says which pack these results are about
s.mods().require("ae2")          // the version, or a recorded skip naming the mod
```

This is the ground floor because it is the one thing a registry census cannot answer: a mod that adds
no content has no namespace to look for.

The list is pushed in by each loader's entry point at startup rather than read out of the loader,
because the scene API compiles against neither loader. **A list that never arrived is a failure, not
an empty list.** An empty one would make every mod look uninstalled, so a pack's scenes would all
skip citing a mod that is plainly there — the framework's own wiring failing while wearing an absent
mod's clothes.

## Asking for a capability

```java
s.capability("mymod:rituals")                      // the facet, or a recorded skip
s.capability("mymod:rituals", Rituals.class)       // the same, type-checked
s.hasCapability("curios")                          // branch instead of skipping
s.capabilities()                                   // what this runtime offers, sorted
s.capabilityProviders()                            // what LOADED, available or not, sorted
```

```javascript
s.capability('mymod:rituals').consecrate(0, 1, 0);  // the same object, through Rhino
```

**Absent is a skip, not a failure** — the same rule as a scene needing a player on a server that has
none. A mod that is not installed is not the pack's defect, and it must not read as a pass either.
The skip message names what was missing *and* what the runtime did offer instead, so the results
explain themselves a week later.

The typed form earns its keep across a version bump. An adapter whose facet type changed would
otherwise surface as a cast failure inside the scene, which names the scene rather than the adapter.

### `capabilities()` and `capabilityProviders()` are different questions

They are easy to conflate, and the difference is the one worth asserting on. A runtime where
discovery found nothing and a runtime where it found two providers whose mods are absent answer
identically to every `hasCapability` call. So a suite that only asks that stays green through a
dropped service file, a shadow merge that ate a `META-INF/services` entry, or a jar that shipped
without it.

Registered-but-unavailable is a working framework reporting an absent mod. Nothing registered is a
broken framework reporting the same thing.

## Four ways a capability gets there

In ascending order of what it costs you.

### 1. It is already there

A pack configures nothing and still gets the big ecosystems detected, plus — on NeoForge — the three
platform capabilities every tech mod is built on.

```javascript
s.capability('itemhandler').insert(0, 0, 0, 0, 'minecraft:diamond', 3);
s.capability('energy').fill(0, 0, 0);
s.capability('fluids').fill(0, 0, 0, 'minecraft:water', 1000);
s.capability('mekanism').probe().callStatic('…');
```

`itemhandler`, `energy` and `fluids` are written against the **capability**, not against a mod. A
hundred tech mods share no API; what they share is the interface a hopper or a pipe talks to them
through. So one facet covers all of them, including mods written after the facet was. It also cannot
over-reach: a block that implements nothing reports that it implements nothing rather than guessing.

The interfaces live in the scene API and the implementations in the NeoForge module, which is both a
technical necessity — the API compiles against neither loader — and a standing invitation: implement
the same interfaces over Fabric's storage API and every existing scene works unchanged. Until then, a
scene asking for one of the three on Fabric records a skip that says so.

Vanilla containers implement `itemhandler` too, which is what lets the mechanism be proved with no
mod installed at all — a chest answers with twenty-seven slots. Only the *coverage* needs a modpack
to prove.

Two details of that implementation are worth knowing because they look like defects when you hit
them:

**A write tries `null` and then all six faces, stopping at the first that accepts.** Block
capabilities are queried with a direction, and `null` means "unspecified". Most mods answer `null`
with an internal view carrying the machine's real numbers, so *reading* is always correct, while
*insertion* is governed by the machine's side configuration and only permitted on faces configured as
inputs. Without the sweep, a scene asserting "this machine can be charged" silently becomes a scene
about that machine's side configuration, and nothing in the symptom points at the face. Handlers are
de-duplicated by reference, so a block exposing one handler on six faces is asked once.

**The shadow jar must merge service files, not replace them.** Adapters are registered from two
modules, and the default for a duplicate path is last-writer-wins. Without an explicit merge the jar
ships one of the two files and every capability in the other reports itself absent — which is
indistinguishable from an uninstalled mod.

The shipped descriptors are a deliberately short list in `data/stagewright/capabilities.json` inside
the framework jar. Each is gated on a **class**, not on a mod id: the class existing is exactly the
condition under which its probe works, so the mod id's spelling never enters the question. An entry
earns its place by naming a stable API root a scene would otherwise hardcode. It is not an ecosystem
census; `s.mods()` already answers "is X here".

### 2. Declare one in a JSON file

For a mod nobody wrote an adapter for, when you have a `config/` folder and no build tool. Drop a
file beside your scenes:

```json
// config/stagewright/capabilities/rituals.json
{
  "name": "mymod:rituals",
  "mods": ["mymod"],
  "probe": "com.mymod.RitualApi",
  "absent": "MyMod is not in this pack"
}
```

```javascript
s.capability('mymod:rituals').probe().callStatic('lookup');
```

Conditions may be any combination of `mods` (all must be loaded), `anyMods` (at least one),
`classes` (all must be in this JVM), `items` and `blocks` (all must be registered). A single entry
may be written bare rather than as a one-element array, and one file may hold a single object or an
array of them.

**At least one condition is required.** A descriptor stating none would report itself available in
every runtime, including ones without the thing it names, where scenes gated on it would run and fail
for a reason that has nothing to do with the pack.

The registry conditions are the ones to reach for when a mod is present but its content is not: a mod
loaded with its feature switched off in config registers no items, and no amount of mod-list checking
notices.

Anything malformed fails loudly at load, naming the file. A descriptor that silently degraded to
"unavailable" would be indistinguishable from an absent mod, and a typo would then read as an
ordinary skip forever.

**Declaring a name StageWright ships replaces ours.** That is how a pack whose fork of a mod moved a
class corrects the framework without waiting for a release.

### 3. Ship a typed adapter

A mod that wants its own machinery testable ships one in its own jar:

```java
public final class RitualsCapability implements CapabilityProvider {
    @Override public String name()                        { return "mymod:rituals"; }
    @Override public boolean availableIn(SceneContext ctx) { return true; }
    @Override public Object facet(SceneContext ctx)        { return new Rituals(ctx); }
    @Override public String absentReason()                 { return "MyMod is not installed here"; }
}
```

Registered exactly like scenes are, through
`META-INF/services/net.magicterra.stagewright.scene.CapabilityProvider`. Any scene can then reach it,
including a JavaScript file in a pack's config folder — no prelude change, no new verb, nothing to
import.

**Write it typed, against your mod's real classes.** An adapter for Curios imports Curios' classes
and therefore cannot even load in a runtime without Curios — which is correct, since it could not
have worked there either, and discovery is built for it. Each provider is instantiated inside its own
guard, so one that cannot load is recorded as absent *with the reason*, and its neighbours are
unaffected. An adapter that never loaded cannot say which name it would have answered to, so the
reason is attached to every skip for a capability nothing offers: a scene asking for yours reads the
linkage error rather than a bare "nothing in this run offers it".

That is also why the two adapters StageWright itself ships are reflective and yours should not be.
They ride in the harness jar, which is present in every run whether or not the mods they adapt are,
so their classes have to load in a runtime that has neither.

Three contract points that nothing can check for you:

**`name()`.** Bare names are reserved for adapters StageWright ships; anything else is
`<modid>:<what>`. Two providers claiming one name is rejected loudly, naming both classes, for the
same reason duplicate scene names are: one silently wins, and a scene then tests something it did not
mean to.

**`availableIn()` is called more than once and cached for the run**, so it must be cheap and free of
side effects. A provider whose class loaded at all can usually return `true` — the class loading *is*
the probe. Return false for the finer conditions: the mod is present but its data has not loaded, the
feature is off in config, the server has no world yet.

**The facet's own surface must be safe to call from JavaScript.** A scene may hold a Minecraft object
but must never call a method on one, because those names differ between a development runtime and a
production Fabric jar. So a facet's parameters and return values must be StageWright types, string
ids, or primitives — never an `ItemStack` the scene is then expected to call a method on. Getting
this wrong produces a facet that works on NeoForge and throws on Fabric, which is the most expensive
shape of bug this framework has.

`facet(ctx)` is resolved once per scene and reused, so an adapter that registers a cleanup registers
exactly one. Returning a fresh instance per call is the bug that produces.

### 4. Reflect into the mod directly

A pack author with `.js` files and no build still needs a way in. `s.probe(className)` is guarded
reflection into a **mod's** API:

```javascript
var api = s.probe('com.some.mod.Api');            // absent class => recorded skip
s.expect(api.callStatic('lookup', s.player()).asInt()).isAtLeast(1);
```

`s.hasClass(className)` is the branching form, for a scene that would rather not skip.

**`net.minecraft.*` is refused, and this is correctness rather than caution.** A production Fabric jar
carries intermediary names, so a by-name call there resolves a completely different identifier — the
same line would pass on NeoForge and throw on Fabric. A mod's own class names are never remapped,
which is exactly what makes the technique sound for them. Passing a Minecraft object as an *argument*
is fine; that is holding, not calling.

Everything non-primitive comes back wrapped in another `Probe`, so a scene never ends up holding a
bare Minecraft object it might then call a method on. Leaving the wrapper is explicit: `asString`,
`asInt`, `asDouble`, `asBoolean`, `asList`, `isNull`, `unwrapped`. `on`, `call`, `callStatic`,
`field` and `staticField` chain.

A miss names the alternatives on the class, because reflection that only says "no such method" sends
you to a decompiler.

The same reflection appearing three times in a pack is a `CapabilityProvider` waiting to be written.
This is a hatch, not an API.

## The adapters StageWright ships

Two, both registered through the same seam a third party uses, so they stop being special cases:
`curios` (accessory slots, reached through `s.equip()`) and `ftbquests` (the quest book, reached
through `s.quests()`).

Availability means more than "the mod is here". An FTB Quests server with no loaded quest file
answers every graph question with nothing, which is not a fact about the pack's quests — so
availability requires a loaded book, and the absent message says which of the two is missing.

What registering them buys, besides consistency, is that `s.capabilities()` reports them alongside
anything a mod contributed, so a scene that skipped can record what the runtime actually had — and
whoever writes the third integration has two worked examples that are not toys.

## The pack's own content: data, and driving it

Capabilities reach a mod's *code*. Most of what a modpack actually is, though, is **data**: recipes,
advancements, quests, structures and loot tables, in quantities no one reads by hand. Six facets
cover it, and every one is on `SceneContext`.

| Facet | Reads | Drives |
|---|---|---|
| `s.recipes()` | `producing`, `resultOf`, `ingredientsOf`, `ingredientSlotsOf`, `uncraftable`, `mentionedBy`, `closureOf` | `crafts(id)`, `craftAudit()` |
| `s.menu()` | `hasMenuAt`, `isOpen`, `title`, `slotCount`, `item`, `count`, `contents` | `openAt`, `openInventory`, `put`, `clear`, `click`, `shiftClick`, `close` |
| `s.advancements()` | `registered`, `has`, `parentOf`, `remaining`, `completed`, `all`, `allIn` | `grant`, `revoke`, `awaitEarned` |
| `s.quests()` | `loaded`, `chapters`, `questCount`, `allQuests`, `questsInChapter`, `dependenciesOf`, `isComplete`, `canStart` | `complete` |
| `s.structures()` | `registered`, `all`, `allIn`, `at`, `generatedAt`, `locate`, `distanceTo` | — worldgen places them |
| `s.loot()` | `exists`, `all` | `roll`, `rollCounts`, `rollTotals`, `distinct` |

**The split down the middle of that table is the point.** Reading the data proves a road is
*connected*; running it proves the road can be *walked*, and the two fail apart. A recipe whose
matcher rejects its own declared ingredients is registered, has resolvable ingredients, and every
static walk goes straight through it — every read-side check passes and the item is craftable by
nobody. So `crafts(recipeId)` fills a recipe's grid from its own declaration and runs it, and
`craftAudit()` does that to the whole pack.

### Reading a craft audit

The audit returns how many recipes were **attempted**, how many were **skipped**, and the attempted
ones that failed. Read the skipped count before believing the failure list: two kinds of recipe cannot
be attempted at all.

A recipe that is **not a crafting grid** — every smelting recipe and everything a tech mod registers
— takes an input this facet cannot construct without knowing that mod. A recipe that **declares
nothing** and computes itself at runtime, like map cloning or armour dyeing, has no declared result,
so "does it make what it says" is a question with no meaning for it.

There is a third category that *is* attempted and fails on purpose: a recipe that declares everything
and never matches by design, which some mods ship so a recipe viewer can display something whose real
crafting happens elsewhere.

So the assertable subset is keyed on the **serializer**, not the recipe type. `failuresInVanillaTypes()`
is what to assert on: a vanilla-serialized recipe is run by vanilla's own matcher and has no room for
"I meant it not to work". Record `failures()` and `failuresByType()` beside it, because a real pack
has some legitimately, and the breakdown is what makes a vacuous filter visible — keying the split on
recipe type instead is vacuous, since every crafting-grid recipe in the game shares one type, and
that version reported zero, which is exactly what a clean pack reports.

### Opening a menu

`openAt` throws when a block has no menu, which makes it useless for asking *whether* a block is
reachable — a pack author's first question. `hasMenuAt` exists for that, and the answer is neither
obvious nor uniform.

A block's own menu provider is authoritative: it is the route a right-click takes, and it is where the
conditional cases live. It very often answers nothing, and that is not the same as "this block has no
menu" — the default is to answer nothing, and vanilla blocks get an implementation only by extending a
base class that hands the question to the block entity. A mod with its own block hierarchy and its own
GUI-opening packet never overrides it, while its block entity *is* a menu provider. So the facet asks
the block, then falls through to the block entity, which is the object the mod's own open call passes
anyway. Nothing that answered is overridden, so no conditional provider is bypassed.

That is as far as a generic opener can go. A mod whose machines are not menu providers anywhere — and
at least one large tech mod is exactly that — cannot be opened this way at all, while its capabilities
stay perfectly readable. The two seams are independent.

Opening builds the menu **server-side** — the mod's own factory, its real slots, its real click
handler — and deliberately sends no open packet to the client. That packet asks the client to
reconstruct the menu from a data buffer whose shape only the opener knows, and a generic caller cannot
know it. Sending it empty is not a harmless no-op: one mod's generator read a position out of it, got
null, and took the client's whole packet listener down mid-run, ending a suite nine scenes in.

What is lost is the client screen, which this facet does not assert about. What is kept is everything
it does.

## Where to go next

[Writing a scene](writing-a-scene.md) covers the scene API these facets hang off.
[Getting started](getting-started.md) covers installing scene and descriptor files into a pack.
