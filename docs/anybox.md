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

## Two compiler defects this surfaced

Building the surface ran into two real holes, both instances of the same thing — **the parser only
knows types declared in the current file**:

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

2. **Forward references to structs.** A struct's constructor resolves only for code appearing
   *after* its declaration — so a struct can never construct itself inside its own member block,
   and the most ordinary method on an immutable struct (one returning a modified copy) cannot be
   written there. This is a documented single-pass restriction (`PontifParser.types`), left alone
   here: the surface uses standalone `method Box.gap(…)` declarations, which work. It wants a
   declaration pre-pass, and probably belongs with the open item in
   [parser-linker-refactor.md](parser-linker-refactor.md).

A third was worked around rather than fixed: a struct value passed to a `_` (wildcard) parameter is
rejected by the call gate as "provably violates the parameter refinement", with no obvious rule to
the shapes that trip it (`T(a:Int)` passes, `T(a:String)` does not). `window` therefore declares
`root:Box` rather than `root:_`, which is the more honest signature anyway. The dasum-era surface
never hit this because it always wrapped children in `{…}`, and aggregates pass.

## Status

Landed: the surface, the walker, the window, the `SetText` sink, `Clicked`/`TextChanged`, and the
counter example.

Not yet: plotting (`pontif.plot` still lives on dasum in `pontif-builtin-gui`, which is what keeps
that module alive — `pontif.shape` needs two symbols from it), the status ribbon, and the remaining
vexelray widgets (Slider, Tabs, TreeView, modals, menus). Those are the next slices, and each is an
atom or a kind rather than a redesign.
