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
- **writes (outputs)** — the §1 dispositions: **placement** (write the element,
  transformed or not, to one stream; default = the primary output, §1.1),
  **observation** (read prior + write next on an accumulator pair), **emission**
  (an extra, provenance-tracked write). **Exactly one placement per element** is
  the conservation law (§4).

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

# 8. Open decisions & syntax slots

**Semantic decisions (need a ruling):**

1. Outputs **declared vs inferred** (§2.4). Lean: explicit-but-terse.
2. **No bare drop** — removal is routing to a named output (§4). Lean: yes.
3. **`Source` trait now vs array-first** (§2.3). Lean: trait now.
4. **`rewrite` placement** — in this construct vs alongside structural recursion
   (§2.6). OPEN; settle after the flat cases.

Resolved this session: the **read/write-stream primitive + seal** (§2.1–2.2,
answers the UNKNOWN); **fold = a per-frame-sealed read/write pair** (§2.5, which
also resolves fold-without-a-stream, §3).

**SYNTAX-SLOT (James's to design) — at minimum:**

- the construct keyword/header and how the **source** and **current element /
  index** are bound;
- how **outputs** are named and their **kinds + inits** given (or the inference
  rule, per decision #1);
- the **placement** disposition (`element → output`), **routing** (which output),
  **observation** (accumulator revision, reading the prior), and **emission**
  (extra element) spellings;
- how the construct's **result** (the output tuple/record) is received.

## 8.1 Disposable strawman (NOT a proposal — semantics only)

Purely to make §1–§4 legible; every token here is a placeholder.

```
‹each› xs ‹as e, i› {
  [Int:@>0] -> e            # default placement of (possibly transformed) e
  _         -> e ‹→ rejected›   # routing: place into the `rejected` stream
}
# filter: result is ‹(kept, rejected)› — two streams, nothing erased

‹each› xs ‹as e› ‹fold total = 0› {
  _ -> ‹total + e›          # observation: revise the accumulator; element consumed into it
}
# fold: result is the final `total`, no stream output

‹each› xs ‹as e› {
  _ -> e ‹+ emit (e * 2)›   # placement of e AND an emission (provenance: e*2)
}
# append/insert: one stream, two elements out per element in (the extra is a creation)
```

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
