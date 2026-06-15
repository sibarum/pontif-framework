# Iteration

Status: DRAFT (2026-06-14). A semantic proposal for replacing forced
structural-recursion-over-cons-cells with a single iteration construct.
**Surface syntax is deliberately unfilled** — every concrete spelling below is a
disposable strawman in ‹angle brackets›; the real syntax is James's to invent
(§8). Markers: **RULED** = settled (here or in conversation); **PROPOSED** = this
doc's recommendation, awaiting a ruling; **OPEN** = undecided; **SYNTAX-SLOT** =
a hole left for the surface design.

This supersedes the recursive-`Element/Leaf` model as the spelling of *linear*
iteration (`streams.md`). Genuinely recursive/branching data — proof trees,
`Leaf|Split` — keeps structural recursion (§7.1); this is only about flat
traversal.

# 0. Why move off forced recursion (RULED 2026-06-14)

The cons-cell `Element(head, rest)` welds a datum to its continuation. The
consequences (James):

- **Semantic category error** — to hold an element you must materialise its
  successor, whose head must materialise *its* successor. The structure cannot
  represent "this element, traversal pending," which is exactly what an array is
  (elements that do *not* know their neighbours) and what a live/`reality` source
  is. So the model is structurally *incompatible* with contiguous memory and
  streaming sources — the very things `streams.md` said it wanted.
- **No proof benefit** — recursion was chosen as "the proven-halting discharger,"
  but halting was the *goal*; structural recursion was only the means. Bounded
  iteration over a finite source halts *by construction* (§5).
- **Boilerplate / cognitive load** — every consumer re-writes the same
  `match q { [Element] -> … q.rest …; [Leaf] -> … }` skeleton.

The replacement is an iteration construct whose per-element body reuses the
matcher (the `{ }` block earns its keep) and whose conveniences are, on
inspection, the conservation ledger wearing a syntax (§1, §4).

## 0.1 The seed (James, off-the-cuff)

The motivating sketch this doc formalises:

> Uses familiar matcher/lambda syntax · inherits scope from caller · iterates
> each element and provides the current index · **variable output(s)** — enables
> simultaneous filter / map / append / fold / group-by / aggregate with one
> syntax, without breaking conservation rules, keeping the code simple.
>
> Variable outputs is the key unification move. Map is the default. Filter
> becomes a two-stream output (accepted / rejected). Append does something
> practically impossible for recursion, controlled so it doesn't compromise
> control-flow analysis. Fold/aggregate becomes a non-stream output revised each
> iteration. Group-by becomes an output map of key→stream pairs.

# 1. The one idea: iteration *is* the conservation ledger

Push on "variable outputs + map-is-default" and it collapses to a single
invariant. For each input element, the body declares its **disposition** across
named outputs, and disposition has exactly three shapes:

1. **Placement** (consuming) — the element goes into **exactly one** stream-kind
   output. This is the conserved quantity: one element in ⟹ one placement out.
   *No loss* (every element is placed) and *no duplication* (placed once).
2. **Observation** (non-consuming) — the element is *read* into a revised
   accumulator (fold). Reads don't move the element, so any number of
   observations coexist with the placement, conservation-neutral. (When there is
   **no** stream output, the accumulator *is* the element's accounting home — it
   is absorbed into the fold; see §2.5 and the open below.)
3. **Emission** (creation) — an *extra* element, not the input one, contributed
   to an output. Legal only with **tracked provenance** (it must be a function of
   values in scope — the ledger already records this for any derived value), so
   it is a no-duplicate-respecting *creation*, not a copy.

> **The invariant:** every input element is **accounted for exactly once** — one
> placement into a stream, *or* absorption into an accumulator when no stream is
> its home — plus any non-consuming observations and provenance-tracked emissions.

- **OPEN:** distinguishing an accumulator that is the element's *home* (`sum`
  absorbs the value) from one that merely *observes* it (`count` records that it
  occurred, the value isn't in it) may not be statically decidable in general.
  Flagged; the accounting rule above holds either way (the element is accounted
  once), but which accumulator "owns" it is a refinement.

The matcher's **totality** guarantees every element-shape reaches the placement
logic; "exactly one placement" guarantees nothing is lost or duplicated. That is
`splitOn` / the no-erase coin (`user_cott_conservation`) lifted from data to
**control flow**. Everything else in this doc is a consequence.

## 1.1 Map is the default — so you cannot drop by accident

The default disposition of an unrouted element is **placement, unchanged, into
the primary output** (identity map). Therefore:

- Writing nothing = pass-through = map. (RULED: "map is the default.")
- Removing an element is not silence; it is *routing it somewhere else* — a
  residue output you named. Ignoring that output is the caller's choice, but
  **inside the construct nothing vanishes**. This makes `streams.md`'s
  "silent discard is structurally inexpressible" a property of the construct, not
  a convention (§4, [[project_pontif_no_lie]]).

# 2. The model: streams in two modes

§1 is the *body's* per-element view (place / observe / emit). This section is the
*I/O* view of the same machine (James's framing): everything is a **stream**, held
in one of two modes, and a disposition is just which stream(s) an element flows
to.

## 2.1 The primitive — a stream is read-mode or write-mode (RULED 2026-06-14, James)

There is one primitive: a **stream**, held in exactly one of two modes, never
both at once:

- **read-mode** (an *input* / source) — readonly, iterable; what the construct
  traverses.
- **write-mode** (an *output* / sink) — write-only; you *push* into it and never
  read it back.

Write-only is not a convenience; it is the **mechanism of purity**. The rule the
whole construct rests on:

> **Reads are inputs; writes are outputs — never the same handle.**

If an output could be read mid-iteration, the result would depend on read/write
interleaving — observable mutable state. Forbidding reads on a write-stream makes
the construct a pure function of (inputs, body): nothing about the partial
accumulation is observable, so order cannot perturb it, and it **cannot fail at
runtime** (there is no read that could witness an unfinished value). This is also
exactly the shape a future **event / message queue** wants — a sink you push into
— so the same write-stream generalises to messaging later (§7).

## 2.2 The seal — how a write-stream becomes a read-stream (answers the UNKNOWN)

The open question: does an output stream *transform* into an input stream, or do
both live on one `Stream[T]` with runtime guardrails? **Neither.** They are **one
collection at two lifecycle phases, separated by a seal:**

1. **Write phase** — while the producing iteration runs, you hold a **write-only
   handle**, scoped to that iteration.
2. **Seal** — when the iteration completes, the handle is *spent*; what escapes is
   an **immutable, readonly value**.
3. **Read phase** — that sealed value is an ordinary read-stream (input) for any
   later iteration.

So the guardrail is **static phase/scope separation**, not a runtime check on a
dual-mode object: you never simultaneously hold a write-handle and a read-handle
to the same stream, because the write-handle's lifetime *is* the producing
iteration and only the sealed value leaves. This is the linear / no-erase
discipline ([[user_cott_conservation]]) — the write-handle is consumed to yield
the readonly value — and it is why **pipelines compose with no aliasing window**:
stage N's sealed output is stage N+1's input.

## 2.3 Input streams — the source (read-mode)

There is essentially **one** kind of input stream: a readonly, iterable source. It
satisfies a small contract whose single obligation is *"present your elements, in
order, to a total per-element handler"* — the source drives the traversal
(**internal iteration**), the construct supplies the handler. Internal iteration
is what keeps it pure: no external stateful `next()` to thread; the traversal is a
read.

- a **native array** is a source — iterating **contiguous memory** (the payoff the
  cons-list fenced out);
- a **range** / generated sequence is a source with no backing storage;
- a **sealed write-stream** (§2.2) is a source;
- the old **`Element/Leaf` chain** is *one source among many*, not the mandated
  shape. {Idea (OPEN): a universal trait for user-defined Element/Leaf objects, to
  maintain linkages?}
- **multiple inputs** = streaming **tuples** (one source of tuples, not N
  sources).

- **PROPOSED:** a trait ‹`Source[type E]`› with that one obligation; ship the
  native array as its first tenant.
- **OPEN (decision #3):** define the trait now, or array + range first and
  generalise once there are two tenants? (Lean: now.)
- **OUT OF SCOPE:** infinite / live (`reality`) sources — a construct over an
  unbounded source never seals, so it is not a pure expression; that is the action
  layer's job ([[project_actions_architecture]]). (Symmetrically, a live sink is a
  write-stream that is never sealed.)

## 2.4 Output streams — the sinks (write-mode), and their kinds

A construct names a set of write-streams; on seal, its result is the tuple/record
of their finished values. The "kinds" are just **which write-streams, sealed at
what granularity**:

| Kind | Push semantics | Seal granularity | Serves |
|------|----------------|------------------|--------|
| **stream** | append, in order | once, at iteration end | map; the surviving sequence |
| **stream ×N** | append to one of several | once each | filter (`kept`/`rejected`), routing |
| **keyed-stream** | append to the key's stream | once (a map key→sequence) | group-by (append-by-key, minimal boilerplate) |
| **accumulator** | overwrite the single cell | **per frame** (§2.5) | fold / aggregate (sum, count, any, …) |
| **rewrite** | write a node mirroring the input's shape | structural (§2.6) | tree-traversal-as-iteration |

An accumulator needs an **initial value**; the rest start empty. These should ship
as builtins.

- **OPEN (decision #1):** outputs **declared** (names + kinds + inits) or
  **inferred** from the body's writes? The result *type* is the output tuple and
  conservation-checking needs the full set, so I lean **explicit-but-terse** — the
  outputs are the construct's interface.

## 2.5 Fold — the read/write pair, and per-frame seal (RULED 2026-06-14, James)

Fold appears to break "reads are inputs, writes are outputs": `accₙ = f(accₙ₋₁,
e)` must *read* the prior accumulator. It doesn't break the rule — it is the case
where the accumulator is **both** an input and an output, a (read, write) **pair**,
and the body's step is two ordered moves: **read the prior, then write the next.**
Within a frame nothing is mutated; the read side and write side are distinct
handles, pure dataflow from one to the other.

The only thing separating the accumulator from a map/filter output is **seal
granularity**: a map output seals **once** (read back only after the iteration);
the accumulator's pair seals **per frame** — frame *n*'s read *is* frame *n−1*'s
write. That per-frame carry is precisely the **loop-carried dependency**, which is
the chain the fold-invariant proof (§5) already walks — so the proof obligation
falls out of the dataflow shape rather than being bolted on. Single definition per
value, one dependency edge: static analysis stays traceable and predictable
(James).

## 2.6 Tree traversal — the synchronized pair (the non-flat outlier)

Most outputs are flat. **`rewrite`** is the exception: a **synchronized
input/output pair for leaf-first traversal of a tree**, where the write-stream
mirrors the read-stream's *shape* rather than a linear order — read the tree
leaf-first, write the rewritten tree in the same structure. This is the one place
the linear placement model (§1) does not directly apply, and it is the honest
bridge to "recursion stays for genuinely recursive data" (§7.1): a tree rewrite is
structural, so its output is shaped, not flat.

- **OPEN:** does `rewrite` belong to *this* construct (iteration with a shaped
  output) or alongside structural recursion (§7.1)? It straddles them — OPEN until
  the flat cases settle.

## 2.7 The body — a total matcher; reads in, writes out (PROPOSED)

The body is the existing matcher over the current element (the source's element
sort is the scrutinee, so the totality checker applies unchanged); the `{ }` block
earns its keep by auto-binding the element. Everything the body **reads** is an
input; everything it **writes** is an output:

- **reads (inputs):** the **current element**; the **current index** (read-only
  position projection); the **caller's lexical scope** (closure over *immutable*
  values, §6); each accumulator's **prior revision** (the read side of its pair,
  §2.5).
- **writes (outputs)** — see §2.8. **Exactly one placement per element** (or
  accumulator absorption) is the conservation law (§4).

## 2.8 The arm result — write commands (RULED 2026-06-15, James)

Each output stream is a **name in scope**. An arm's result is not a value; it is a
set of **writes**, each one *"send `value` to the output `os`"* — and the OS's
**kind** supplies the effect, so there is nothing to tag:

- to a **stream** → append; to a **keyed-stream** → route by key; to an
  **accumulator** → revise (the prior is read from the same name, §2.5).

So `Place` / `Observe` / `Emit` collapse into **one write command**. An arm
resolves to **one write, a tuple of writes (you may write to two), or none**.
"Place the element" vs "emit an extra" is not a command distinction — both are
writes; provenance is the ledger's job (§4), not the command's.

**No-op (the empty write set) is valid in the representation, fenced by
conservation (RULED).** It is always *expressible*; whether a given no-op is
*legal* is the §4 check, not a syntax rule:

- with a default/primary output (§1.1), an empty arm means *the default placement
  fires* — not a drop;
- without one (a pure fold; a filter that must route everything), an empty arm
  that leaves the element **unaccounted** is a **compile error** — never a silent
  loss.

The AST is lenient (no-op is a node); the checker is strict (it rejects only the
no-ops that would erase). That is the no-lie law in the right place.

# 3. The unification (PROPOSED)

Every combinator becomes a configuration of outputs + dispositions — the five
ruled combinators of `streams.md` collapse into one construct:

| Operation | Output configuration | Per-element disposition |
|-----------|----------------------|--------------------------|
| **map** | one stream | default placement, transformed |
| **filter** | two streams `kept`, `rejected` | place into one or the other (partition — both halves kept) |
| **append / insert** | one stream | default placement **+** an emission (provenance-tracked) |
| **fold / aggregate** | one accumulator | observation only (no placement → element passes through, *or* a sink output if you want it consumed) |
| **group-by** | one keyed-stream | placement routed by computed key |
| **map + count** | one stream + one accumulator | placement + observation together |
| **tree rewrite** | one `rewrite` output (§2.6) | shaped write mirroring the input tree |

"Append does something impossible for recursion": the cons-structure has no slot
for "and also emit this," but an emission is a first-class per-step contribution
— and control-flow analysis is untouched because it is still a bounded fold over
declared outputs.

- **Fold without a stream (resolved by §2.5):** when the only outputs are
  accumulators, the element is *accounted for by being read into the
  accumulator's read/write pair* — the accumulator is where it went, so no
  vestigial stream is forced. ("sum these" is the natural shape.) The accumulator
  pair's per-frame seal is what makes this a placement-equivalent that conserves.

# 4. Conservation & the ledger (PROPOSED)

The construct is checked against the conservation discipline
([[project_conservation_receipts]], [[project_pontif_no_lie]]):

**The law is "no *silent* erase," not "no erase" (RULED 2026-06-15, James).** A
system in which nothing can be destroyed is a memory leak with extra steps —
destruction is as necessary as creation. The discipline is not that information
*cannot* be lost, but that loss is **explicit and detectable**: a function may use
only the `accept` stream and drop the `reject`, and the system can point at exactly
where and what was discarded. Conservation makes information loss *traceable and
auditable*; it forbids *lying about* loss, not loss itself. (When iterators chain,
the reject stream is carried alongside the accept chain, so the discard stays
locatable until something explicitly drops it.)

- **No loss:** matcher totality + "every element is accounted for" (placed, or —
  per §3's open — consumed into an accumulator). A bare drop is **not
  expressible** (§1.1); removal is routing to a named residue.
- **No duplication:** exactly one placement per element; an element cannot be
  placed into two streams. (Want it in two? That is an **emission** into the
  second — a creation, provenance-tracked, not a free copy.)
- **Provenance:** placements carry the element's identity through to its output;
  emissions record the computation that produced them; observations record the
  element flowing into the accumulator. This is exactly the receipt-graph's
  consult/combine/emit taxonomy at iteration granularity — the construct should
  *generate* ledger entries, not be opaque to the drafter.

- **OPEN (decision #2):** ratify "no bare drop — removal is routing to a named
  output." (I strongly lean yes; it is the no-lie law made structural.)

# 5. Termination & proof (PROPOSED)

A construct over a finite source halts **by construction** — the bound is the
source, so there is no "does the recursion bottom out?" obligation. The gate sees
a **fold invariant** over the output tuple (property preserved across one
element), which is more uniform to discharge than per-consumer structural
recursion. So the proof story is *simpler*, not weaker — the inductive analysis
James predicted ("same analysis, possibly easier") is the fold invariant, and
termination is free.

- **Work item:** the return gate / Drafter ([[project_conservation_receipts]])
  must learn this construct as a first-class bounded fold (today it reasons about
  structural recursion). New, but cleaner; a named follow-up, not a blocker for
  the surface design.

# 6. Purity & desugaring (PROPOSED)

No new runtime mechanism and no mutation:

- The construct **desugars to a fold** whose state is the tuple of outputs;
  stream outputs append, accumulators revise, all functionally threaded.
- "**Inherits caller scope**" = ordinary lexical closure over *immutable* caller
  values; nothing is written.
- "**Current index**" = a counter threaded as hidden fold state; a read-only
  projection.

So the construct is sugar over the same pure fold the source contract already
implies — the conveniences are spellings, not new semantics.

# 7. What this retires, keeps, and reopens

## 7.1 Keeps: recursion for genuinely recursive data (RULED)

This retires recursion only as the **forced spelling of linear iteration**.
Trees, `Leaf|Split` proof structures, and any data where a node *genuinely* owns
its children stay structural recursion — there the "successor" is real structure,
not a traversal artefact.

## 7.2 Reopens: `streams.md` (PROPOSED)

- The "RULED" five-combinator basis collapses into §3 — a simplification (fewer
  primitives), to be reconciled in `streams.md`.
- The `(1,2,3) : [Stream[Int]]` **literal** (slice 3c) now desugars to a
  **source** (an array literal), iterated by this construct — *not* to a
  cons-chain. Good that 3c was paused before pouring concrete on the old model.
- `Element` need no longer be the mandated linear shape; it survives (if at all)
  as one `Source` tenant.

## 7.3 Relationship to the type-parameter arc

The `Source[type E]` trait and any element-typed outputs ride the parametric
machinery just landed (`type-parameters.md` slices 1–3b): the element sort `E` is
a trait type parameter; a typed literal's elements are gate-checked against `E`.
So this builds *on* that arc rather than reopening it.

# 8. The surface (PROPOSED 2026-06-15, James)

Syntax is James's; this records the proposal converged on this session. The spine:
**semantics are determined entirely by the destructuring** — one keyword, `iter`,
and the member set you pull from it *is* the capability set.

## 8.1 `iter` and the member vocabulary

`iter(src)` wraps a raw source into an iterator over its elements; you destructure
the members you need, then a block:

```
let dest = iter(src).{value, index, …} { match value … }
```

The members are a fixed vocabulary, split into READS (inputs) and WRITES (outputs):

| Member | Role | Read/Write |
|--------|------|------------|
| `value` | the current element | read |
| `index` | the current position | read (not writable — §0.1 / James's ruling) |
| `accept`, `reject` | filter routing (two streams) | write |
| `current`, `next` | the fold pair — prior revision / next revision | read `current`, write `next` |
| `put` | group-by (`put(key, value)`) | write (keyed) |

There is **no mode prefix** (`iter.filter` / `iter.fold` are retired): which
members you destructure *is* the mode, and they compose —
`{current, next, reject, value}` is filter-and-fold in one pass.

## 8.2 The body: arms return dispositions (RULED 2026-06-15)

The block matches `value`. An arm **returns a value, and that value is a
*disposition*** — no side-effects, no void writes: `accept` / `reject` / `next` /
`put` are pure functions that *construct* a disposition, the arm returns it, and
the construct folds the returned dispositions into the outputs ("no function
without a return type"). A **bare value** is itself a disposition — *emit to the
default stream* (map-is-default). Multiple writes in a frame = a **tuple of
dispositions** (§2.8's "single or tuple"):

```
# filter (+map): accept(v)/reject(v) carry a value, so this filters AND maps.
iter(src).{accept, reject, value} { match value
  [0] -> reject(value)
  [_] -> accept(value) }

# bare bool is the skip disposition — ONLY when accept/reject are in scope;
# elsewhere a bool arm is ordinary data (→ Stream[Bool]).
iter(src).{accept, reject, value} { match value
  [0] -> false
  [_] -> true }

# fold: current = prior revision, next(…) = next revision (the §2.5 pair).
iter(src).{current, next, value} { match value
  [@>0] -> next(current + value)
  [_]   -> next(current) }

# map + count = fold + map: a TUPLE of dispositions per arm.
iter(src).{value, current, next} { match value
  [_] -> (next(current + 1), value * 2) }   # fold the count, emit value*2 to default
```

The default stream is populated by exactly the arms that return a bare value — no
vestigial empty stream when arms only fold/route.

## 8.3 The completed iterator — result and chaining (RULED 2026-06-15)

A block's result is a **completed iterator** — a value of the same kind it
consumed, carrying the sealed outputs as named attributes, so it is both
*queryable* and *chainable*:

```
let it     = iter(src).{value} { match value [_] -> value * 2 }
let stream = it.stream()                  # query: extract the default stream
let next   = it.{accept, reject} { … }    # chain: iterate it again (it IS a source)
```

This is §2.2's **seal made a first-class value**: the sealed outputs *are* a new
iterator, so input-type = output-type and pipelines compose with no glue (stage N
feeds stage N+1). Read-side attributes: `.stream()` (default), `.accepted` /
`.rejected` (filter), and the fold's aggregate — **named at the destructure**
(requires-style) so it is not sum-specific:

```
iter(src).{total = (current, next), value} { match value [_] -> next(current + value) }
# → it.total
```

## 8.4 Block scoping — alignment, not a feature

`x.{a, b} { … }` reads as "destructure `a, b`; the `{ … }` is a block where they
are in scope and don't leak" — a Ruby-block-ish reading. We do **not** build a
general block feature; `iter` (and a completed iterator) is a recognized special
form, and this is the *alignment story* (lens, not cage), as with `requires` /
`exports`. The `iter` keyword + the destructure-then-braces shape is what lets the
parser emit the `Iterate` node and enforce the guardrails (totality; write-only
outputs; only valid capability members). `(iter(src) -> it) { … it.value …
it.accept() … }` is an equivalent surface (bind instead of destructure) lowering
to the same node.

## 8.5 Desugar to the node, and what's still open

Each member maps onto the slice-1 node (§10): `value`/`index` → per-frame read
bindings; `accept`/`reject` → two STREAM outputs; `current`/`next` → one
ACCUMULATOR (read prior / write next); `put` → one KEYED output; a bare value →
the primary/default STREAM. An arm's returned disposition (or tuple) desugars to
its `Write` list; a bare value → `Write(default, value)`. **No IR change — pure
surface sugar.**

Resolved this session: read/write-stream primitive + seal (§2.1–2.2); fold = a
per-frame read/write pair (§2.5); outputs declared by destructuring; no mode
prefix; arms return dispositions (§8.2); result = a completed iterator (§8.3);
"no *silent* erase" (§4).

Still open:
- **read-side attribute names** — aggregate default `.total` vs user-named (§8.3);
  `.accepted`/`.rejected` vs `.accept`/`.reject`.
- **`rewrite` / tree traversal** (§2.6) — placement TBD.
- **`Source` trait now vs array-first** (§2.3).
- **the conservation checks themselves** (§4) — still the SortChecker REVISIT (§10).

## 8.6 `Stream[T]` and the tuple autobox (RULED 2026-06-15, James)

A `Stream[T]` is a distinct type — the homogeneous, variable-length sequence —
*not* a tuple. A tuple **autoboxes** into `Stream[T]` by a **one-way cast in the
cast law's lose-freely / fabricate-never family** ([[project_subtypes]]):

- the tuple `(1,2,3,4)` knows it is *exactly four, at positions `_0.._3`*;
  `Stream[T]` knows only *"homogeneous `T`, some count."* So the cast **forgets the
  arity / positional identity** (a clean forget — free), and is **irreversible**
  precisely because the reverse would have to *fabricate* "it is a 4-tuple"
  (forbidden). It is therefore *not* coercion (reversible) and *not* a wrapper
  `Stream(tuple)` (no tuple identity survives — gone, not boxed).
- it is **element-gated**: the box is licensed iff every member is convertible to
  `T` (the tuple's independently-typed slots all land in the single `T`).

When the multi-dispatch **promotion** machinery is complete, this *is* that cast
and should run on it. Until then it is **figurative** — a bespoke conversion at the
boundary, conceptually identical, swapped for the promotion path later.

**Slice (figurative) — LANDED.** `Stream` is a recognized parametric type
(SortChecker `BUILTIN_PARAMETRIC_TYPES`); a `let x:Stream[T] = (…)` claim where the
value is a tuple autoboxes (AltParser `requireStreamElements`: base-level element
gate, plus the lossless Int→Decimal embedding), one-way only (no `Stream[T]` →
tuple). The runtime value stays the native positional record; the claim is the
parse-time gate (no separate runtime `Stream` check). `iter` accepts a `Stream[T]`
binding as a source. REVISIT: ride the real promotion logic; full element coercion
(not just base); the expression-level `let … in …` path (only the top-level/
statement `let` autoboxes today).

# 9. Costs (honest)

- The Drafter/return gate must learn the bounded-fold construct (§5) — real work,
  but it replaces per-combinator recursion analysis.
- The `Source` contract + native-array tenant is new surface (and the array's
  traversal must be a *pure read* to keep the construct pure).
- `streams.md` must be reconciled (§7.2): the combinator basis and the literal.
- Provenance for emissions leans on the ledger already tracking derived values
  (§4) — confirm that path covers per-iteration creations.

None of these block the surface-syntax design; they are the implementation arc
that follows a ratified model.

# 10. Implementation status & revisit checklist (slice 1)

**Slice 1 (no syntax): a hand-constructable `IrExpr.Iterate` that runs on the
IrInterpreter path.** Decision (James, 2026-06-15): minimal-runnable, *minimize
rework*; fill coverage everywhere once the surface syntax is finalized. This
checklist is the contract — every site touched is listed, marked **[real]**
(handled for slice 1) or **[REVISIT]** (stubbed / deferred, must be completed).

Node shape (slice 1): `IrExpr.Iterate(source, element, outputs, arms, origin)`,
where `outputs` are `OutputSpec(name, kind, init)` (`kind ∈ {STREAM, ACCUMULATOR}`
for slice 1; KEYED + rewrite deferred), and each `arm` is `Arm(pattern, writes)`
with `writes = [Write(output, value)]` (empty = no-op). One new sealed `IrExpr`
variant; the inner records are NOT `IrExpr` variants (keeps the switch ripple to a
single case per site).

- **IrExpr.java** [real] — the variant + inner records.
- **IrInterpreter** [real] — fold eval. **Streams are NATIVE** (a positional
  record / tuple), NOT `Element/Leaf` (James 2026-06-15: trees use recursion,
  streams are native): the source is iterated as a positional record's members in
  order, and a STREAM output seals to a tuple `(_0, _1, …)`. ACCUMULATOR seals to
  its final revision. KEYED/rewrite throw. The result is the queryable completed
  result — a single output returned directly, else a record keyed by output name
  (its fields are the native streams). The **stream literal** is the tuple form
  `(1,2,3)` (already-existing syntax). REVISIT: the completed-iterator wrapper
  (`.stream()` / chaining) and a dedicated `Stream` type over the Source contract.
- **AliasResolver / NameResolver / MethodResolver / AggregatePromotion /
  DecimalPromotion / ConstructionGate / StructLiteralRewriter / NarrowingInference
  / IrFreeVars** — structural recursion into source/inits/writes/patterns.
  **[REVISIT]**: confirm each is *semantically* right once syntax lands (e.g.
  AggregatePromotion stamping inside arm writes; NarrowingInference inferring the
  result tuple type rather than returning unknown).
- **SortChecker** **[REVISIT]** — slice 1 only validates sub-sorts/children; the
  real conservation checks (no-bare-drop §4, exactly-one-placement / accounting,
  output-kind/write agreement, home-vs-observe §1-open) are NOT yet enforced.
- **IrCompiler** [real-ish] — register sorts in children so the IrInterpreter path
  runs. **[REVISIT]**: real lowering/compilation of the construct.
- **IrPrinter** [real] — placeholder rendering (don't block error paths).
- **TruffleLowering** **[REVISIT]** — throws "Iterate: not yet"; the Truffle path
  is unsupported until coverage.
- **Drafter / ConservationDrafter / ConservationProofs** **[REVISIT]** — throw
  "Iterate: not yet"; the return/conservation gates don't reason about the
  construct yet (§5 work item).
- **Parser (AltParser)** — **map+filter slice LANDED** (`iter(src).{value, accept,
  reject} { match value … }` → the node; `accept(e)`/`reject(e)`/bool/bare-value
  dispositions lower to writes; `IterationParseTest`). **[REVISIT]**: `index`,
  fold (`current`/`next`), group-by (`put`); the completed-iterator result
  (`.stream()`/`.total`/chaining — currently returns the raw stream(s)); the
  conservation guardrails; and refinement-shorthand bases (`[0]`/`[@>1]`) rely on
  a slice-1 head-field element-sort heuristic until real Source-contract
  element-type inference lands.

Deferred semantics (independent of the ripple): KEYED + `rewrite` output kinds
(§2.4, §2.6); the `Source` trait abstraction (slice 1 iterates a concrete source);
decisions #1/#2 from §8.
