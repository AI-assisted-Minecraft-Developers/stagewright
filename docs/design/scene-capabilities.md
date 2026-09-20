# Scene capabilities: from placing blocks to testing processes

## The gap

A scene could build blocks and assert about blocks. That is enough to test movement, mining and
placement, and it is not enough to test anything a modpack is actually about.

Two targets made the shortfall concrete, and between them they named six subjects the scene
context could not see at all: items, advancements, recipes, dimensions, loot tables and
container menus.

**A large content mod.** Its whole progression ladder is gated on *advancements* rather than on
the act of defeating a boss — structures check the player's advancements before letting them in,
a periodic check pushes players out of regions they have not unlocked, and the portal to the
dimension is locked the same way. That single fact changes what a progression test is: not
"defeat eight bosses", which is unrepeatable, but "grant the advancement and assert that the next
structure and the portal are genuinely unlocked" — which is fast, repeatable, and tests the mod's
own gating logic rather than combat. Its accessory items need equipment in named slots that
vanilla has no concept of. Its signature block is a container, and almost all of that block's
logic lives in a server-side menu. Its content is in its own dimension.

**A several-hundred-mod pack.** Its headline item sits at the end of a recipe tree hundreds of
hours long, so "play to it" is not a test. What *is* testable is a property of the graph: start
at the item, walk the recipes down, and assert the walk closes on materials obtainable in vanilla
with no broken edges and no recipes pointing at items that do not exist. That is the question a
pack author actually has — *I changed a recipe; is the item still craftable?* — and it answers in
seconds. The pack also needed quest-graph queries, structure generation, loot tables and a
dimension of its own.

That one conclusion determined the shape of the recipe facet: it has to do **closure traversal**,
not single-recipe lookup.

## Four constraints determined the shape

### JavaScript safety is a hard constraint, not a style

The rule predates this work and is stated on the scene context itself: **a scene file may hold a
Minecraft object and pass it around, but must never call a method on one.** JavaScript resolves
method names at run time, and a production Fabric jar is intermediary-mapped — the method spelled
`getX` in a development environment is `method_10263` there. The same line passes on one loader
and throws on the other.

The consequence every new method must obey: **parameters and return values are StageWright's own
types, string identifiers, or primitives.** Never an item stack the author is then expected to
ask for a count. This is also why these capabilities could not simply be driver verbs: an
in-process scene cannot reach the driver's transport, and the leverage described below would be
lost.

### Facets, not a pile of methods

The scene context was already at the edge of what one class should hold, against a hard
line-count budget. Following the precedent already set by the arena and performance accessors,
each new capability is its own class, and the context gains one method returning it.

The benefit is not only line count. The facet name is documentation: typing `s.recipes().` lists
that family and nothing else.

### They belong in the published contract module, because they are vanilla API

Items, advancements, recipes, loot, structures, menus and equipment use nothing but the vanilla
server API. Not a line of the driver is involved, so they live in `:stagewright-api`, whose rule
is to depend on nothing — meaning nothing beyond Minecraft, which was always there.

That placement is the largest single piece of leverage in this design. JavaScript scenes reach
the scene context by direct reflection, so:

> A Java facet written once is **immediately available to a pack author's `.js` scenes** — with
> no new driver verb, no prelude change, and no cost to any language-model client's prompt
> budget.

The pack target, which has no Gradle project at all and drives the game through the command-line
runner, therefore needed no separate work to gain every facet.

### Third-party mods are reached by probe, never by dependency

The accessory-slot mod and the quest mod cannot become dependencies. `:stagewright-api` is what
every consuming mod compiles against, and hanging an accessory-mod dependency on it would require
every mod under test to install that mod.

So those two integrations resolve their classes **by name and call them reflectively**. Present,
and the facet works; absent, and the scene records a skip with a reason — never a
`NoClassDefFoundError`, and never a silent pass.

Two details of that probe are load-bearing. The probe must come **first**, and it must go **by
name**: building a list of method references resolves every class in the list before the loop
body runs, so a try/catch around the calls never sees the resulting error. And absent is a
*skip*, which follows the precedent already set by asking for a player on a topology that has
none.

## The facets

| Facet | Is |
|---|---|
| `s.items()` | The player's inventory and their eating: give, hold, count, clear, eat, and the hunger and status-effect readbacks that follow. |
| `s.advancements()` | Grant, revoke, query, and ask which criteria remain. |
| `s.recipes()` | Which recipes produce an item, what a recipe needs, and the closure walk — plus an audit of what the pack as a whole can and cannot craft. |
| `s.loot()` | Whether a table exists, and rolling it a stated number of times. |
| `s.structures()` | Locating a structure, and asking whether a point falls inside one. |
| `s.menu()` | Server-side container menus: open one from a block, read and write slots, click, close. |
| `s.equip()` | Vanilla armour slots, extended with accessory slots when that mod is present. |
| `s.quests()` | The quest graph: chapters, completion, dependencies. |

Every facet takes and returns strings and primitives. Every one that changes player state
registers its own cleanup, because inventories, advancements, equipment and open menus all
survive the scene that made them — and a scene that granted a progression advancement quietly
disables every later scene asserting that the same gate holds shut, whose failure then points at
the mod's gating logic rather than at the scene before it.

Two facets are slow enough to matter: locating a structure may scan thousands of blocks and load
a great many chunks, and a closure walk may traverse a very large graph. Both take explicit
bounds and both record their elapsed time, because a scene body runs on the tick and a locate
that takes seconds is spending the same budget every other scene in the run is drawing on.

Three of the surface choices are worth stating because they were decided against the obvious
alternative:

**A closure is a product, not a pile of methods.** The walk returns an object that answers how
deep it got, which items it touched, which leaves it stopped on, which references it could not
resolve, and — most importantly — which recipes it could not see through. A walk that exceeds its
bounds **throws, naming where**, rather than returning a partial result: half a closure looks
exactly like a closed graph.

**Rolling loot takes an explicit count.** A single roll returning nothing is entirely legal
behaviour for a loot table, so a test asserting "roll once and get something" fails at random.
Offering only "roll N times" forces the author to decide whether they are asserting a
distribution or an existence.

**Menus are the testable half of the user interface, and the boundary is stated rather than
implied.** A server-side container menu — slots, clicks, recipe matching, result output — is where
almost all of a container block's logic lives, and it is testable from a scene. Pixels, layout and
rendering are not in this facet; those belong to the client probe or to an attached test. A menu
scene needs a player and therefore skips on a bare dedicated server.

**Unknown identifiers throw.** Every one of these facets takes identifier strings, and a silent
downgrade would let a scene pass under a false premise. This is the easiest mistake in the whole
design to make and the hardest to find afterwards — see the first trap below, where the first
implementation made it in the same file that warns against it.

## The dimension, which is a harness change rather than a facet

Seven of the eight are facets. Running a scene in a mod's own dimension is not, and the design
originally expected it to be the largest piece of work: the scene's level was fixed at
construction, and the harness allocated origins, force-loaded chunks and swept and audited them
against that one level.

Reading the implementation changed the answer. The terrain option was **already** a per-scene
choice of dimension — that is the trick by which one server offers both a flat plain and
generated ground without either being the run world's type. The harness already resolved a level
per scene. So the change was to relax an existing mechanism rather than to build a new one:

- allow an arbitrary dimension identifier, not only the ones StageWright ships;
- **make terrain and dimension mutually exclusive, rejected at scene construction.** A terrain
  *is* a dimension StageWright ships, so asking for both asks for the arena to be in two places,
  and a run-time answer would have to pick one and silently ignore half of what the author wrote;
- clamp to the dimension's build height, because the grid's altitude is an overworld number and
  another dimension's ceiling may be lower;
- and split what "absent" means, which is the part that carries the design weight.

**A missing dimension is two different events.** A StageWright terrain that is not there means
the framework's own datapack failed to load — that is a broken framework, reported as an
environment failure. A mod's dimension that is not there means this runtime does not have that
mod — that is a fact about the pack, reported as a recorded skip naming it. Collapsing the two
would make one of them read as the other, and only one of them is anybody's bug.

## Five traps a scene author needs to know about

Every one of these was hit in practice, none was caught in review, and all five have the same
shape: **the code ran, the assertion passed, and nothing was tested.** They share a single root
cause, stated at the end.

**A defaulted registry never returns null.** The item and block registries answer with air for
an identifier that was never registered. So a mistyped identifier does not fail — it becomes an
empty stack, and what surfaces downstream is "the inventory is full", "the slot is empty", "the
recipe has no ingredients": every symptom except the misspelling. Ask a registry whether it
*contains* the key; do not check whether `get` returned null.

**Rolling a loot table with the wrong parameter set returns an empty list.** Roll an entity's
table with chest parameters and nothing is thrown — the table's conditions simply cannot be
evaluated, so it drops nothing. The scene concludes "this creature drops nothing", which is a
legitimate result and indistinguishable from the real one. The facet compares the parameter sets
itself and refuses, because the game will not.

**A missing loot table reads as an empty one.** The lookup answers with a shared empty stand-in
rather than null, so a renamed table reads as a table that drops nothing — a legal state, and
therefore inseparable from a bug. Existence has to be asked as a separate question.

**A scene's command source has no entity.** Selectors resolving to the executing entity match
nobody, so a command using one throws. The fix is not to route around the command — it is that
**a test's precondition should be set by a facet, not by a command**. A facet that can set hunger
directly is both more reliable and more honest than a command that happens to have the side
effect.

**Asking a recipe for its ingredients can answer "none" when it means "I do not answer that".**
The vanilla ingredient accessor is a default method returning an empty list, and custom recipe
types — which is to say nearly every machine recipe in a modded pack — do not override it. A
closure walk over such a graph therefore terminates early with no leaves and nothing unresolved,
looking perfectly healthy while never having left the first cycle it found. There is no other
interface to ask, so this one **cannot be fixed, only reported**: recipes the walk could not see
through are collected and named, and only an empty list of them means the traversal was complete.
A second list records where the walk had to infer rather than read. **Read those two before
believing anything else the closure says.**

The common root is worth stating on its own, because it generalises past these five:
**Minecraft's lookup APIs overwhelmingly return a default rather than null or an exception.** A
defaulted registry gives you air; a missing loot table gives you the empty table; a mismatched
parameter set gives you an empty list; an unimplemented accessor gives you an empty list. So any
wrapper turning an identifier into an object is unsafe by default, and the burden is on the
wrapper to make absence loud. Four of the five above are closed by one identifier-resolution
class. The fifth cannot be, because what is missing is not validation but an interface — and when
that happens the only honest move is to make the caller see "I did not see all of this", rather
than letting it look like "I saw all of it, and this is all there was".

## Getting the harness into someone else's runtime

The facets were finished and the self-test scenes were green before a third-party mod was
connected, and then a twenty-minute run produced no results at all, with the framework's name
absent from the log entirely.

The cause was not in any facet. It was delivery: **under ModDevGradle there is no such thing as
"also run this other mod".** Under the loom-based build one line puts a mod jar in front of the
loader, which is why the gap did not exist while there was a single consumer. Adding the harness
to the runtime classpath instead does not work — the jar does arrive, and the loader's own
discovery prints it, but prints it as already located and claims it as an ordinary game library.
It never appears in the mod list. The game boots, ticks, writes nothing, and the run is reported
as a game that never armed, over a log with no error in it.

The `mods/` folder is the route. Its locator reads in every run, development or production, and
the published NeoForge jar needs no remapping to go there. It also has a better property: what
runs is the artifact a pack author would install, not its development variant.

The fix belongs in the framework rather than in each consumer's build script, so the install
rules live in the shared engine — the same rules the command-line runner applies to a modpack,
held once for the same reason the verdict rules are — and a topology declares which jars to
install. The least obvious rule in them is **sweep before installing**: jar names carry versions,
so an upgrade lands *beside* its predecessor rather than on top of it, and the loader then sees
two copies of the framework and either refuses to start or arms the old one, reporting the old
code's behaviour as the new code's.
