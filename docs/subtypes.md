
Subtypes: inheritance as named narrowing
===

> **SUPERSEDED (2026-06-08) by `docs/univocal-language-design.md` + the shipped
> implementation (`docs/univocal-implementation-plan.md`).** This early draft's
> exclusive `[]`-vs-`()` framing and tagged-reversible-borrow cast were both
> overturned: the shipped construct is `struct Name:[Base:rel](fields)` (is-a AND
> has-a on one declaration), and an extension upcast is a *lossy clean forget*
> (no surviving tag), not a reversible borrow. Kept as the historical design
> record; read the univocal docs for canonical truth.

Status: DRAFT FOR RED-PEN (2026-06-07). The architecture emerged in design
conversation (the `Zero` sequence, growing from `loginvolution.ptf`); nothing
here is implemented. The slice-1 substrate it stands on — the construction
gate's three-way claim rule, union types, match narrowing, methods, the
nominal type-name toggle — already ships. Markers: **RULED** = settled in
conversation; **DERIVED** = follows from ruled material plus the standing
laws; **PROPOSED** = Claude's suggestion awaiting a ruling; **OPEN** =
explicitly undecided. All surface names are provisional until ratified into
the glossary.

# The one construct (RULED)

A single declaration form. What sits in the brackets decides everything:

```pontif
struct Float[Decimal]            # a sub-brand of Decimal
struct Number[Decimal|Int]       # a named supertype of the union
struct Zero[Decimal:@==0]        # a constrained subtype (refinement)
```

`struct T[X]` declares a **nominal type `T` whose value-set is exactly `X`**.
Where `T` lands in the subtype lattice is not a separate concept to declare —
it falls out of where `X`'s value-set sits relative to everything else:

- `Float[Decimal]` — `Float`'s set is `Decimal`'s set, so `Float` sits at or
  below `Decimal`; a `Float` is usable as a `Decimal`.
- `Number[Decimal|Int]` — `Number`'s set is `Decimal ∪ Int`, so `Number` sits
  *above* both; a `Decimal` is usable as a `Number`.
- `Zero[Decimal:@==0]` — `Zero`'s set is the `Decimal`s satisfying `@==0`, a
  strict subset; `Zero` sits below `Decimal`.

This is the unification: **inheritance, union types, newtypes, and refinement
subtyping stop being four features and become one**, distinguished only by the
bracket contents. There is no `extends` keyword, no `class`, no typeclass — a
subtype is a named sort whose membership the existing narrowing kernel already
decides.

## `[]` is is-a; `()` is has-a (RULED)

The bracket/paren law (the standing syntax gate) draws the line that matters:

```pontif
struct Zero(coef:Decimal)   # ()  value fields → HAS-A.  A Zero CONTAINS a Decimal.
struct Zero[Decimal]        # []  type relation → IS-A.   A Zero IS a Decimal.
```

A `Decimal` value is never a `Zero(coef:Decimal)` (a scalar is not a record),
but a `Decimal` *can be* a `Zero[Decimal:@==0]` (it is a Decimal that may
satisfy the cut). This is exactly why `match someDecimal [Zero]` is coherent
for the bracket form and a type error for the paren form — composition does
not give you the narrowing; inheritance does.

# The cast law (RULED)

One rule, the same direction law as everywhere else in Pontif:

> **Widen for free; narrow by match.**

| Cast | Set direction | Cost |
| --- | --- | --- |
| `Float → Decimal` | toward an equal/wider set | free (the upcast forgets nothing it needs) |
| `Decimal → Number` | toward a wider set (into the union) | free |
| `Number → Decimal` | toward a narrower set | a **match** — which branch? |
| `Decimal → Zero` | toward a narrower set | a **match** — does `@==0` hold? |

The downcast is not new machinery: `Number → Decimal` **is** the union
narrowing match already shipped, and `Decimal → Zero` **is** the refinement
narrowing the construction gate already runs. `Number[Decimal|Int]` is the
existing `[Decimal|Int]` union handed a name — so methods can attach and
dispatch can see it.

# Representation: tagged, because the brand is information (RULED)

A subtype value **carries its most-specific brand as a tag**. It is *not*
bit-erased into its base. Two independent arguments force this, both standing
laws rather than performance taste:

1. **No-erase / reversibility.** If `Float → Decimal` discarded the
   `Float`-ness, then `Decimal → match [Float]` could not recover it and the
   round-trip would lose data. The tag makes a cast a **reversible borrow**:
   upcast lends the value as its base with the tag riding along untouched, and
   a downcast match recovers the brand. Cast-up-then-match-down round-trips
   losslessly — the conservation coin at the type level, the same shape as
   `exchange`. Erasure would be the lie.

2. **The tag is a discharged-narrowing receipt.** When the subtype is
   narrow-defined (`Zero[Decimal:@==0]`, a predicate outside the compile-time
   kernel), acquiring the brand is a runtime resolution — precisely the
   construction gate's UNKNOWN verdict. The tag records that the narrowing was
   discharged, so a later `match [Zero]` reads the receipt instead of
   re-resolving the predicate (re-proof) — or, where the predicate is not
   re-checkable, instead of asserting membership it cannot back. Same family
   as the gate's runtime checks and the receipt graph: a value that has passed
   a check carries the proof that it did.

**What "zero-cost" means, precisely:** arithmetic and behavior are unchanged —
a `Float` computes exactly as its underlying `Decimal`, dispatches into every
`Decimal` operation, runs the same. The cost is one brand tag, and that tag is
doing conservation work (reversible casts, discharged-obligation receipts),
not boxing overhead. The substrate's behavior for Decimals is untouched; the
value simply knows what it is.

# Methods attach to the brand (DERIVED)

The nominal tag is what lets `method Float.exp(...)` and `method Number.foo(...)`
dispatch — the localized/rigid mechanism of the dispatch-unification design,
keyed on the brand. The receiver is `self` (the injected method param), typed
at the brand. Because a subtype upcasts freely, a `Float` method body may use
`self` anywhere its base is expected, and a method declared on the base is
available on the brand (inherited by the is-a relation) — the precise
inheritance-of-methods rule is OPEN below.

# The namespace (RULED)

**Uppercase is a sort; lowercase is a `let`.** `Float`, `Number`, `Zero` name
types; `zero`, `five`, `x` name values. Consequence for the just-landed
spec-only synthesis (a value-pinning sort defining a value-less `let`): it must
fire on lowercase bindings only — a capitalized binding is a sort declaration,
not a value-let, and `let Zero:[…]` should be rejected or read as the sort
form, never synthesized into a value. (Known edge; folded in when subtypes
land.)

# What stays untouched

- **The Decimal substrate**: arithmetic, comparison, the discharge engine —
  a branded value runs the base's code.
- **Union types / match**: the downcast IS the union narrowing; no new node
  kinds.
- **Construction gate**: acquiring a brand is passing the gate — three-way as
  always (provable fit → free tag, provable miss → compile error, overlap →
  runtime resolution stamped at the cast/match site).
- **Conservation**: the tag is a receipt; casts are reversible borrows. The
  ledger gains brands as attested identity, not as erasure.

# Open questions (for red-pen)

1. **Brand acquisition: structural or nominal entry?** Does a raw `Decimal`
   satisfying `Zero`'s predicate *become* a `Zero` by matching `[Zero]`
   (structural — match resolves the predicate and tags it), or must a `Zero`
   be explicitly constructed/asserted (nominal — match only recognizes
   already-branded values)? The first makes refinement subtypes feel like
   sets; the second makes them feel like classes. The cast table above leans
   structural (`Decimal → Zero` by match), but it wants an explicit ruling.
2. **Multiple brands over one base.** With `Float[Decimal]` *and*
   `Cost[Decimal]` both predicate-free, is a `Decimal` simultaneously a `Float`
   and a `Cost` (additive, interface-like), or is a brand exclusive (a value
   has exactly one most-specific tag)? Bears on whether the tag is a set or a
   single identity, and on dispatch coherence.
3. **Upcast and the tag.** Confirm the upcast keeps the most-specific tag
   (a view-widening, not a mutation) — required for the reversibility argument.
   Does an explicit re-brand to a sibling exist, and is it a match?
4. **Method inheritance.** A method on the base — automatically available on
   the brand (is-a)? A method on the brand — shadowed by / overriding a
   base method of the same name? Ties into dispatch-unification's resolution
   order.
5. **Where the predicate lives.** `T[Base:pred]` (predicate inside the
   inheritance bracket — "inherit and narrow") vs `T[Base]:[pred]`. Lean: the
   first, one move.
6. **Names.** `struct T[X]` itself, "brand," "tag," the cast vocabulary — all
   provisional; glossary entries follow ratification.

# Slices (PROPOSED)

0. **This document ratified** (red-pen pass).
1. **Bare single-base brand**: `struct Float[Decimal]`, parsed and registered
   as a nominal subtype; tagged representation; free upcast `Float → Decimal`;
   construction `Float(x)`. No predicate, no downcast-match yet. Reviewable:
   a Float value that runs as a Decimal and prints its brand.
2. **Union supertype**: `struct Number[Decimal|Int]`; `Decimal/Int → Number`
   upcast; `match n [Decimal] / [Int]` downcast (the existing union narrowing,
   now over a named supertype).
3. **Refinement subtype**: `struct Zero[Decimal:@==0]`; brand acquisition
   through the construction gate (three-way); `match [Zero]` resolving the
   predicate and tagging — the runtime-resolution case that forced tagging.
4. **Methods on brands**: `method Float.exp(...)`, inheritance-of-methods per
   the question-4 ruling.
