# Notation — braces, brackets, parentheses

*Part of the [Pontif guide](../../README.md). This page explains why Pontif's
brackets aren't ad-hoc punctuation but a small, complete grid. For the one-page
overview, see the root [README](../../README.md).*

The brackets are not ad-hoc punctuation. The subject `@` combines with three
brackets across two arenas (a value vs. a type), and every cell is a distinct,
namable operation:

| | on a **value** | on a **type** |
| --- | --- | --- |
| **name** (access / construct) | `@.{}` — destructure fields by name | `@{}` — a type's members (`Type{ ping:[Method():Int] }`) |
| **refine** (restrict) | `@:[]` — narrow a value (`let x:[Int:@>0]`) | `@[]` — a reusable type (`Type[[Int:@>0]]`) |
| **call** (compute) | `@:(…)` — a decision tree (`match`) | `@()` — apply / dispatch (`f(x)`, `v.m()`) |

You have already seen most of them in the [type system](type-system.md):
`[Int:@>=0]` is `@:[]`; `Type{ weight:… }` is
`@{}`; `area(s)` and `Vec(3,4).norm()` are `@()`; `match n { … }` is `@:(…)`;
`p.{x, y}` and `requires lib.{inc}` are the same `.{}` named decomposition.

**The arrow `->` is orthogonal to the grid and means one thing everywhere:
"produced by / bind-and-produce."** It appears *inside* cells — a `@{}` member's
producer (`weight -> 1`), a `@:(…)` match arm (`[@>0] -> …`), a function body, a
synthesis pipeline's `let`-stages — so its presence never tells you which cell
you're in; the bracket does. Match and trait-impl arms are **ordered**
(first-match wins; `_` is the complement). Its mirror `<-` reads "asserted
placement" — the direction the conservation ledger uses to record where a value
*lands* (you'll see it in the
[conservation receipts](proofs-and-ledgers.md#conservation-receipts--the-second-ledger)); as a general writing operator it is
reserved for the forthcoming property-definition language.

(Anonymous functions are obsolete here rather than absent — the constructs that
would need them already cover the ground. The first-class *callable* you reach for
is the metareference `$f[T]` (see the [proofs
guide](proofs-and-ledgers.md#proofs-and-synthesis)), and the first-class *body* you
pass around is the synthesis fragment (see the [streams guide](streams.md)).)

---

**Full design notes:** [brace-aggregates](../brace-aggregates.md) ·
[arrows](../arrows.md) · [univocal-arrows](../univocal-arrows.md) ·
[metatypes](../metatypes.md)
