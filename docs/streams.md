
Streams: the sequence substrate
===

Status: DRAFT FOR RED-PEN (2026-06-06). Companion to `actions.md` — the
queue defined there is this document's first customer, but the substrate
stands on its own (collections, String-later, the fold theory). Markers:
**RULED** = settled in design conversation; **DERIVED** = follows from ruled
material plus standing laws; **PROPOSED** = Claude's suggestion awaiting a
ruling; **OPEN** = explicitly undecided. All names provisional until the
glossary ratifies them.

# The governing decision (RULED)

**There is no semantic array type.** The sequence abstraction is the
**Stream trait**; programs interact with sequences exclusively through its
combinators. Random-access indexing does not exist.

This is not asceticism — each absence discharges obligations by
non-existence, the strongest discharge there is:

| Absent operation | Mode that never gets minted |
| --- | --- |
| indexing (`xs(i)`) | out-of-bounds |
| `head` / `tail` as separate ops | head-of-empty (emptiness is a match arm instead) |
| `foldl` / `foldr` as distinct ops | nothing — but a false semantic distinction dies (see The one fold) |
| lambdas as combinator arguments | the conservation algebra's Lambda/Apply residual (see Deprecation) |

Arrays still exist — demoted from semantic type to **runtime
representation** (see Implementors and Licenses).

# The two implementors (RULED)

The Stream trait is narrowing-checked (interfaces are sorts — the standing
no-typeclasses rule). Two implementors, marking **jurisdiction**, not data
structure:

**1. Queue — the inductive view (pure-side jurisdiction).**

```pontif
struct Leaf()                                  # empty stream; carries nothing
struct Element(head:T, rest:[Element(T)|Leaf]) # schematic; element sort per declaration
```

The `Leaf/Split` shape family with a payload — recursion through a
constructor, contractive, already admitted, already exercised by
`std.proof`. Pure functions consume a Queue by destructuring match over the
recursive union: the canonical ADT match, coverage determined, no default
arm needed. **Structural recursion over `Element` is the classic
termination argument** — this is the proven-*halting* discharger the skip
license's asymmetric member (divergence) was missing. Pure-side sequences
are inductively certifiable by construction.

**2. Array — storage (action-side jurisdiction).**

Native-backed contiguous storage. **Iterable only via an action**: memory
order is runtime dynamics, and runtime dynamics belong to the observer
world. The pure side may hold an array value opaquely (one OTHER-capacity
conservation atom — honest) but never walks it; an array enters the pure
world **as events** — observation is the coercion.

At runtime the split can dissolve **by license**: a Queue whose consumption
the ledger proves linear may be array-backed in the lowering. The trait
marks which world has jurisdiction over the same memory.

# Literals (RULED)

Tuple form with a **required, explicit element sort** — no inference for
now, and heterogeneous content writes its union out ("expanded"):

```pontif
let xs:[Stream(Int)]        = (1, 2, 3)
let ys:[Stream([Int|Bool])] = (1, true, 2)
```

Each element is judged against the element sort by the **construction
gate**, three-way as everywhere: provable fit passes clean, provable miss is
a compile error, overlap carries a runtime check. Per-element implicit
coercion (Int→Decimal embedding) rides the same machinery. **Traits are
admissible element sorts** — per-element satisfaction, dispatch inside
combinators.

A literal desugars to `Element`/`Leaf` construction — which is what makes
the action layer prototypable purely: an array literal of events is a
synthetic reality, a recorded queue is an array you replay, and the live
queue is a stream whose source happens to be reality.

# The combinator basis (RULED)

Five combinators — two partitions, two maps of attention, and an exchange:

| Combinator | Splits / transforms | Conservation character |
| --- | --- | --- |
| `partition` | by **content** (predicate) | per-element Branch — both halves returned, nothing erased |
| `next` | by **position** (head ∕ tail) | bijective destructure (`Element ↔ (head, rest)`) — RECOVERABLE |
| `map` | every element | per-element Computation |
| `exchange` | matching elements, **in place** | per-element borrow-and-return — the conservation coin at stream granularity |
| `fold` | the monoidal collapse | combine; association uncontracted under proof (below) |

Notes, each load-bearing:

- **`partition` IS filter, with honest accounting.** Classic `filter` does
  not exist, not even as sugar: discarding the non-matching half is an
  erasure, and erasures are declared (the `DataConservativeExcept`
  precedent, element-wise). Wanting discard means writing the partition and
  the drop; the ledger shows both halves.
- **`exchange` (name RULED 2026-06-06) is the third no-erase answer:
  neither erase nor split — focus.** It never breaks the stream: matching
  elements are *lent out*, modified, and *placed back*; the result is the
  **full stream with the modifications woven in at their original
  positions**, non-matching elements riding through untouched. Each match
  is borrow → transform → return — assignment-becomes-swap at stream
  granularity, nothing erased (the remainder is total), nothing duplicated
  (the element moves out and back). Consequences: silent discard is
  *structurally inexpressible* (the non-matching elements are in your hands
  whether you wanted them or not — "modify to nothing" isn't a
  modification); modifications preserve the element sort, or the stream's
  sort honestly widens ("expanded"). And the resonance is not decorative:
  **`exchange` is the `when`-arm's semantics inside the pure world** —
  select the matching, react, everything else flows past untouched for
  other observers. One selective-attention construct, two jurisdictions,
  like `next` and destructure.
  - v1 ships the **one-shot form** — `exchange(pred[T], f[T], xs)` →
    the full stream, `f` applied where `pred` matched. Branch +
    Computation per element; zero new theory.
  - The **cursor form** (the selection as a value you work through, the
    remainder reclaiming it) is a NAMED FOLLOW-UP: selection and
    remainder coexisting would place matching elements twice, so the
    selection must be a **linear borrow the ledger proves consumed and
    returned** — Pontif's first borrow construct, checked rather than
    trusted, by the NoDuplication-shaped machinery that already prints.
- **`next` returns the `[Element(T)|Leaf]`-shaped union**, handled by
  match — never separate partial `head`/`tail`. On the pure Queue, `next`
  *is* destructuring match; as a trait method it is what action-side
  iteration steps with. Same eliminator, two jurisdictions.
- **Combinator arguments are metareferences** (`map(inc[Int], xs)`), not
  lambdas — pipelines stay fully ledgerable with machinery that exists
  (captured overload sets resolve the callee). Lambdas would land the
  entire collection layer on the algebra's fail-closed residual.
- The basis is the conservation algebra's three node kinds, lifted
  pointwise over a sequence — plus its coin: partition/next are Branch,
  map is Computation, fold is the combine, exchange is the swap, and
  stream sources are Construction. The API is not a library; it is the
  algebra with a cardinality.

# Extensions: concat, append, mix

**`concat` (RULED needed, 2026-06-06)** — the stream **monoid**:
associative with identity `Leaf()`, both provable by structural induction
over `Element`, so the facts ship as builtins — and the parallel-reduction
license applies wherever concat is folded. Two structures complete at once:
`flatten = fold(concat, Leaf(), …)` comes free, and **the monad closes** —
`singleton(x) = Element(x, Leaf())` is pure, map + fold(concat) is bind;
the stream is the free monoid over its elements, and the literal-as-monad
reading gets its join. Conservation: total placement — every element of
both streams placed once, order kept, nothing erased or duplicated.
(Cons-Queue concat is O(first argument) — representation gossip,
uncontracted, the array-backing license's territory.)

**`append` (PROPOSED sugar)** — `append(xs, x) = concat(xs, singleton(x))`.
Legitimate sugar: it hides no erasure (contrast filter, whose sugar was
rejected for hiding one). Owes a ratified name for `singleton` —
candidates: `singleton` / `single` / `one` / `of`.

**`mix` (NOTED — awaiting its first use-case; not to be implemented
before one arrives)** — the n-ary zip:
`mix((Stream(A), Stream(B), …), f[A, B, …]) → Stream(C)`. The typing is
possible with **no parametric machinery**: the arities live in the tuple
(fixed, heterogeneous — what tuples are for) and in the metareference's
key sorts, so the check is per-call-site coherence (open question 4,
n-ary) — and `map` is unary mix, so the basis stays minimal. Recorded for
when the consumer arrives, the one genuine ruling: **length mismatch**.
Truncate-to-shortest silently erases the longer tails — the leniency that
lies, fenced. Honest options: (1) **total accounting** — return the zipped
stream AND the unconsumed remainder (the partition/exchange precedent);
(2) **equal-length as an unproven theory** — a runtime mode whose
counterexample payload is literally the leftover tail. Lean: (1) as the
primitive, (2) as declared-expectation sugar over it.

# The one fold (RULED in shape; proof vocabulary PROPOSED)

There is exactly one `fold`. The lfold/rfold split conflates two
differences, and the machinery dissolves them separately:

**The semantic difference is a proof obligation.** `fold(op, z, xs)`
requires `op` **proven associative**. Then all association orders are
provably equal and "which fold" is meaningless. The obligation is ∀-shaped,
so its unproven case **cannot become a runtime mode** (a universal is not
checkable per-call): like match totality, fold is proven-or-refused at
compile time — "we'll find out at runtime" is unavailable in principle.
Builtin ops ship their facts (`+`, `*`, `min`, `max`, `&`, `|`); user ops
claim theirs:

```pontif
proof combine = Associative()          # proposed vocabulary — std.algebra
proof combine = Identity(zero)
```

Monoid-as-**proof**, not Monoid-as-typeclass: Haskell trusts the instance's
laws; Pontif checks the claim.

**The performance difference is a license.** Once associativity is proven,
association order is uncontracted implementation gossip (the same status as
cross-chain queue order):

| Proof | Licensed reduction |
| --- | --- |
| `Associative(op)` | any sequential association — left (constant stack), right (incremental/streaming) |
| `Associative(op)` + `Identity(op, z)` | balanced-tree / chunked **parallel** reduction (chunk seeding inserts `z` more than once — sound only against a proven identity) |

**Order-dependent collapses lose nothing**: they were never fold's job.
They are explicit structural recursion over `Element/Leaf` — the order in
the code, visible, ledgered. One fold plus `next`-recursion is complete;
`foldl`/`foldr` were implementation gossip wearing a semantic costume.

# Lambda/Apply deprecation (RULED in direction)

With combinators taking metareferences and actions being declared forms,
nothing remains that needs anonymous functions. Deprecating Lambda/Apply
does not solve the conservation algebra's only residual case — it
**deletes** it: every flow becomes traceable, "untraceable flow" exits the
failure taxonomy, and the ledger becomes total over the language. All
computation is named, module-scoped, dispatchable — namespace hygiene
extended to computation itself.

Survivor: `Method` sorts inside trait *contracts* are types, not values —
they stay.

# Interplay (DERIVED)

- **Actions** (`actions.md`): the queue is a Stream; `when`-arms are a
  match-shaped stage; the fold over reality and the fold over a literal are
  the same trait method — prototyping and replay are representation swaps.
- **Recursion fixpoint / No-Halt**: queue recursion is the fixpoint
  machinery's home turf; halting proofs for structural descent feed the
  skip license.
- **Construction gate**: per-element judgment is the existing three-way
  generalized from record members to elements.
- **String (PROPOSED, awaiting explicit yes)**: a native Char-collection
  value (anatomy TBD) whose integration API is a stream view — no `charAt`,
  no indexing. "No array type" holds at the semantic level; storage is
  representation.

# Open questions (for red-pen)

1. **Names**: `Stream`, `Queue`, `Element`, `partition`, `next`,
   `map`, `fold`, `concat`, `append`, `singleton`, `Associative`,
   `Identity` (still provisional; **`exchange` is RULED** — 2026-06-06).
   **`Leaf` is RULED shared** —
   one freestanding nominal referenced by both `[Leaf|Split]` and
   `[Element(T)|Leaf]` (the un-Haskell union design's poster child: types
   are freestanding, unions borrow them). Mechanically this is
   **re-exports' first real consumer** (the parsed-but-parked non-`@`
   exports qualifier): one declaring owner, the other module re-exports
   the same FQN. Owner RULED: **`std.common`** — the builtin home for
   structs with cross-domain reuse value; `Leaf()` is its founding
   resident, and `std.proof`/`std.stream` re-export it. Known consequence:
   the two unions overlap at `Leaf` — true therefore honest; cross-union
   overloads at `Leaf()` are exactly what the overload-overlap checker
   rejects.
2. **`std.algebra`**: the home and form of algebraic property proofs —
   builtin module like `std.conservation`, discharged by the receipt
   machinery / in-source proofs?
3. **Pipeline syntax**: nested application vs method-chaining
   (`xs.map(f[Int]).fold(g[Int,Int], z)`) — both lawful under the bracket
   rules; chaining reads better, rides method dispatch.
4. **Element-sort flow**: how the declared element sort threads into
   combinator dispatch (the metareference's key sorts must agree with it —
   a coherence check at the pipeline boundary?).
5. **String confirmation** (above).

# Slices (PROPOSED)

0. **This document ratified.**
1. **Queue, purely** — LANDED 2026-06-06: `std.stream` declares `Element`
   and re-exports `std.common`'s `Leaf`; construction, bare-arm union
   matching, and structural recursion ride existing rails (the only
   production change was the module definition; the construction gate
   covered queue typing for free). `head` is loose until `[Stream(T)]`
   lands; literal sugar deferred with it. Known gap surfaced: destructure
   patterns over *imported* structs don't parse (the parser's per-file
   declaredStructs — slice-5 restriction); consumers use bare arms + field
   access meanwhile.
2. **The Stream trait + `map`/`partition`/`next`/`exchange`** (one-shot
   form) **+ `concat`/`append`** with metareference arguments, Queue as
   first implementor; the `[Stream(T)]` sort form and literal desugar.
3. **The one fold**: `Associative`/`Identity` proof vocabulary
   (`std.algebra`), builtin facts (including concat's own — unlocking
   flatten and its parallel license), fold refusing unproven ops.
4. **Array implementor**: native storage, action-side iteration (depends on
   `actions.md` slice 1 — the queue runtime).
5. **Licenses**, each its own slice with its hypothesis statement:
   array-backing under linearity, fusion under single-consumption, parallel
   fold under `Associative + Identity`.
6. **Cursor-form `exchange`**: the linear borrow (selection proven consumed
   and returned), once the ledger obligation is ruled.
