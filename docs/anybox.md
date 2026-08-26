# Anybox — the Pontif GUI on vexelray-gui

**Ruled 2026-08-26 (James).** The GUI surface Pontif exposes is **one element**. Everything a
program builds is a `Box`; everything a Box can look like is a **style atom** appended to it.

This replaces the dasum-era `pontif.gui` (`Label`, `Button`, `TextField`, `Column` — a closed set of
hand-written widget structs), which capped at whatever we thought to write and never showed Pontif
the layout engine at all. The renderer underneath is [vexelray-gui](https://github.com/sibarum/vexelray-gui):
a retained node tree, flex layout in relative units, theme roles, and one SDF uber-shader.

## The design

```
struct Box(kind:Kind, style:_, children:_)
```

A row is a Box with a direction. A text is a Box carrying a string. A button is a Box that reports
being clicked. `style` is an ordinary aggregate of small values — `Gap(Rem(0.5))`, `Fills(Role.Panel)`
— and every fluent method appends exactly one:

```
method Box.styled(s:Style):Box -> Box(this.kind, this.style + {s}, this.children)
method Box.gap(g:Length):Box   -> this.styled(Gap(g))
```

so the whole fluent surface is one primitive move plus a list of names. The bridge folds the
aggregate onto a vexelray `Node` left to right, which makes the precedence rule fall out for free:
**last wins**, the way a stylesheet reads.

**Why this shape.** Adding a vexelray property to Pontif is one struct, one method, and one case in
the fold — *never a wider `Box`*. Pontif has no default constructor arguments, so a Box with a field
per property would be unwritable by hand and would need re-authoring on every addition. Atoms move
the openness out of the struct's arity and into an aggregate, where it costs nothing.

**Why not builders in Java.** A Box is an ordinary Pontif record: it `let`-binds, nests in `{…}`, and
passes as an argument. The framework is therefore written *in Pontif* (`pontif.gui.ptf` — a trait,
three enums, sixteen atoms, seventeen methods), and the Java is a walker, a window, and a sink.

### Three vocabularies, as enums

`Role`, `Kind` and `Placement` are `enum`s whose cases carry an explicit `key` naming their vexelray
counterpart (`Role.Panel` → `"PANEL"`). The bridge reads that field rather than an ordinal (which
would couple the two declaration orders) or a mangled `E$Case` type name (which would couple to a
spelling). A colour is always a **role**, never a literal — switch the palette and every widget
follows.

## The reactive loop

Unchanged from the dasum era, because it was the part that was right:

```
click ──▶ Clicked{id}  ──▶  conduit folds it into a Pontif value
                                   │
                                   └──▶ emit SetText(id, …) ──▶ one mutation, one node
```

The tree is walked **once**. Every update afterwards is isolated: a command names an id, and the
sink posts a single mutation for the single node registered under it. Nothing is rebuilt — which is
what keeps a field's caret, selection, undo log and scroll offset alive across an update.

vexelray handles are write-only and thread-safe by construction, so a sink runs wherever the conduit
runs and needs no marshalling back to the GUI thread. That is the framework's normal path, not an
exception to it.

**Fields are uncontrolled.** A `field(id, initial)` owns its buffer after construction; `initial` is
a seed, not a binding. Every edit fires `TextChanged{id, text}`; fold that and drive *other* widgets.
Writing the same field back from the conduit fights the caret.

## What is Pontif's and what is vexelray's

vexelray-gui's own first rule is that it re-implements nothing a platform library ships. The same
rule applies one level up:

| | owner |
| --- | --- |
| Element vocabulary, styling, composition, the app model | **Pontif** (`pontif.gui.ptf`) |
| Caret, selection, undo, clipboard, hit-testing, focus, flex layout, theming, the shader | **vexelray-gui** |
| Input acquisition | tactroller, as bus traffic |

So `TextField` is not rebuilt on top of `Box` and events — it is vexelray's widget, wrapped. A Box
kind exists precisely for the leaves that carry machinery of their own.

## Three compiler defects this surfaced

Building the surface ran into three real holes. The first two are the same thing — **the parser only
knows types declared in the current file** — and the third is a lie in the refinement kernel. All
three are now fixed; they are recorded because each was invisible until a fairly ordinary piece of
Pontif ran into it, and because two of them had a guard nearby that already knew about the trap:

1. **Cross-module enum members** (`Role.Panel` from an importing program) read as `Unbound variable
   'Role'` — which made `enum` a same-file-only feature the moment a program had more than one file.
   The parser rewrites `Enum.Case` in a value position to the case's zero-field construction, but
   only for a *locally declared* enum. **Fixed** here, in `StructLiteralRewriter` — the post-link
   pass that already exists for exactly this parser-blindness with imported struct literals
   (`CrossModuleEnumMemberTest`). One caveat is documented on the helper: a local binding named
   after an imported enum can no longer shadow it, because by that point the scope is gone. Every
   same-file instance is still settled by the parser, where the scope exists.

   **Still open, one level down:** the same spelling in a *pattern* position — `match f {
   [Facing.Up] -> … }` over an imported enum — cannot be repaired after linking, because the sort
   parser rewrites the dotted name inline and so the dot is a **syntax** error; nothing survives for
   a later pass. Until that is deferred the way `DestructureResolver` defers imported struct
   patterns, a match over an imported enum goes through a function in the enum's own module. The gap
   is pinned by a test that asserts the current error, so closing it fails loudly.

2. **Forward references to structs.** A struct's constructor resolved only for code appearing
   *after* its declaration — so a struct could never construct itself inside its own member block,
   and the most ordinary method on an immutable struct (one returning a modified copy) was
   unwritable there. The surface shipped with standalone `method Box.gap(…)` declarations as a
   workaround. **Fixed shortly after** (a declaration pre-pass; `ef6b31c`), and the surface now uses
   the member block it was designed for.

3. **`_` was compared as a nominal type** whose name happened to be `"_"`, so `imply(T, _)` reported
   Failed — which by that kernel's own contract means *provably disjoint* — and the call gate turned
   that lie into a compile error. A parameter written `x:_` therefore rejected any argument whose
   sort reached the kernel as a bare name. What hid it is that most arguments infer to something
   structural, and structural-vs-name is merely undecided, so the gate abstained; it took a struct
   whose fields were *all* `String` (which infers to a bare name where one `Int` field does not) to
   make it fire. **Fixed** in `Refinements.imply`, in the kernel rather than in the gate, so the
   gate, dispatch specificity and assignability all get the same answer: as the looser sort `_` is
   **top** (everything implies it, totally); as the tighter sort it is **residual** (an unknown sort
   proves nothing, but is disjoint from nothing either). `CallGateTest` guards both directions.

   `window` still declares `root:Box` rather than `root:_` — not a workaround any more, just the
   more honest signature.

## The old `pontif.gui` is gone

Cut in the same slice, and it had to be: both extensions declared the module name `pontif.gui`, and
`Extensions.install` is last-wins, so they could not have shared a classpath.

The cut was smaller than it looked, because the two halves of `pontif-builtin-gui` were already
independent — `pontif.plot.ptf` does not `requires pontif.gui`, and nothing in the plot path called
`GuiTree.toComponent`. So `GuiExtension`, the old `pontif.gui.ptf`, and the declarative half of
`GuiTree` are deleted, while `PlotExtension` and the window loop it shares are untouched. That
module now exists for one reason: `pontif.shape` does not link without *some* `pontif.plot`
(docs/plotting.md, §The renderer seam).

Nine examples went with it. Two were already ported (`reactive-counter` → `counter.ptf`,
`reactive-textfield` → `echo.ptf`); the rest wanted `ExprPlot` or `Status`, which are plot and
status-bar features rather than GUI ones and return when those slices do.

One capability was deliberately **not** carried over: `Clickable`, where a program subtyped `Button`
and assigned a trait whose `onClick` emitted. Anybox's answer is the id it already has — give the
button an id and match on it in the conduit, which is less machinery for the same result, and does
not require a user struct to determine `Box`'s three fields.

## Status

Landed: the surface, the walker, the window, the `SetText` sink, `Clicked`/`TextChanged`, the
counter and echo examples, and the removal of the surface this replaces.

Not yet: plotting, the status ribbon, and the remaining vexelray widgets (Slider, Tabs, TreeView,
modals, menus). Each of those is an atom or a kind, not a redesign — which was the point of the
shape.
