# Indexed: random access as a named Stream capability

Status: **PROPOSED (2026-06-19).** Amends the `streams.md` ruling "there is no
semantic array type … random-access indexing does not exist." Decisions ruled by
James this session are marked **RULED**; the rest is **PROPOSED**/**OPEN**. Markers:
**RULED** = settled · **DERIVED** = follows from ruled material + standing laws ·
**PROPOSED** = this doc's recommendation · **OPEN** = undecided · **SYNTAX-SLOT** =
a hole left for the surface design.

Companion to `streams.md` (the sequence substrate) and `iteration.md` (the
`Iterate` construct). Forcing function: the GPU lowering (`pontif-supirvast`) needs
element-wise kernels `out[i] = f(in[i])`, which the no-indexing rule made
inexpressible.

---

## 0. The smell: a hole was mistaken for an absence

`streams.md` rules "random-access indexing does not exist" and justifies it by
**discharge-by-non-existence**: if `xs(i)` cannot be written, the **out-of-bounds**
failure mode is never minted (streams.md absence table). That reasoning is sound for
the *base* sequence abstraction — but it over-reached into "no program may ever
random-access," which is false to need. Sometimes random access is legitimate.

The existing docs already hedged toward the fix without naming it:

- `glossary.md`: "there is **no indexing** (random access is an **Array/action
  concern**)"
- `strings.md`: "`s(i)` does not exist — random access is an **Array/action
  concern**, not a String one"

So the design never said *forbidden* — it said *not a `Stream` thing, an `Array`
thing*. The mistake was leaving `Array` a second-class "iterable only via
observation" citizen (streams.md) and never giving random access a name. Contiguous
storage's entire reason to exist is O(1) random access; the old model forbade the
one thing an array is *for*.

**The correction (James, 2026-06-19):** base `Stream` still can't index — but that
doesn't make indexing impossible, it makes it a **named sub-capability**. This is
the Pontif idiom — **capability = type boundary** (interfaces are sorts checked by
narrowing; `narrowing handles polymorphism`). Only storage-backed sequences can
honestly promise O(1) random access; a `Queue` cannot random-access without walking,
which defeats the point. **So the trait line is the performance line.**

---

## 1. The type: `Indexed`, a sub-trait of `Stream` (RULED)

`Indexed` (name **RULED**) is-a `Stream` under the **subset/narrowing** is-a regime
(`univocal-language-design.md`) — the tag survives, so an `Indexed` value is still a
full `Stream`: mappable, foldable, partitionable. It adds two contract members — a
**data attribute** `count` and a **method** `at`:

```pontif
trait Indexed : Stream {
  count : [Int: @ >= 0]                              # DATA attribute — the bound's upper limit (§1.1)
  at(i:[Int: @ >= 0]) : [Present(T) | OutOfRange]    # total random access (§2)
}
```

- **`count`** (name **RULED** — *length* is metric, *size* implies a measure/extent;
  *count* is the cardinality of a finite collection, exactly what this is) is the
  precondition for bounded indexing. A plain `Queue` cannot promise O(1) `count`,
  which is *why* this is a sub-trait, not a `Stream` universal.

### 1.1 `count` is a field, not a method (RULED) — and it matters for §2

`count` is declared as a **trait DATA attribute** (`trait_attributes`, 2026-06-11:
`Type{…}` typed attribute members, satisfied by a field *or* a producer), **not a
method**. The storage-backed implementor satisfies it with its **stored length
field** — legitimate precisely because the value is **immutable**, so the length is
fixed data, not a computed-on-demand quantity that could drift. This is the move that
makes §2 rung 2 cheap: a refinement referencing `this.count` is a **field reference**
(within the existing `@.field` machinery), not a method call in a refinement (the
harder, not-yet-supported case). Spelling is `this.count`, **no parens**.
- **First implementor: `Array`** (native storage). Fixed literals `(1, 2, 3)` are
  statically-sized and qualify too (§9 slice 3).
- **Spelling: `xs(i)`** — indexing is **application**, **already RULED** by the
  bracket/paren law (`[]` types, `()` values; arrays index by application, never
  `xs[i]`). `xs(i)` is method-shaped dispatch on the receiver `xs`, so "method
  form" is not a separate choice — it is what `xs(i)` already *is*. Receiver = `xs`.

---

## 2. Honest access: total primitive, refined sugar, dependent endgame

Bringing back `xs(i)` re-introduces out-of-bounds, so it must pass the no-lie gate.
There are three rungs of increasing strength; **rung 1 is RULED and needs no new
machinery**, so it ships first.

### Rung 1 — total `Present | OutOfRange` (RULED, the primitive)

`at(i)` returns a union `[Present(T) | OutOfRange]`, and out-of-bounds is a **match
arm**, not a thrown error. It *cannot lie* — there is no hidden failure mode.

This is not new theory; it is two existing precedents fused:

- "head-of-empty → **emptiness is a match arm instead**" (streams.md absence table).
- `next` returning `[Element(T) | Leaf]` handled by match (streams.md). `at` is the
  random-access analog of `next`'s sequential destructure.

```pontif
match xs(i) {
  [Present(v)] -> use(v)
  [OutOfRange] -> fallback()
}
```

(`Present` / `OutOfRange` member names — **OPEN**. `OutOfRange` could share the
`Leaf`/terminal family in `std.common`; the present-wrapper could be a bare
`Present(T)` or reuse an option-shaped union if one is later ruled.)

### Rung 2 — refined direct access via **receiver-relative refinement** (PROPOSED)

When the index is *provably* in bounds, the `Present|OutOfRange` ceremony is dead
weight — the `OutOfRange` arm can never fire. Rung 2 offers a check-free
`xs(i) : T` returning the bare element, gated by a refinement on the index:

```pontif
method Indexed.at(i:[Int: @ >= 0 & @ < this.count]) : T
```

- `@` = the refinement-self, i.e. the **index `i`** (self-reference law: `@` is
  refinement-self **only**).
- `this` = the **receiver** (the `Indexed` value). `this.count` is a **field
  reference** (§1.1), not a method call.

**Why `this.count`, not `@.length`.** James's first instinct was `@.length`. Under
the self-reference law that collides: `@` is already bound to `i`, so `@.length`
reads as `i.length` (an `Int` has no length). The receiver is `this`, never `@` (the
law specifically rejected `@`-as-receiver). And `count` is a field, so no parens.

**Why this is the *contained* form of dependent typing.** A general dependent
refinement lets any parameter's sort reference any other parameter — full dependent
function types. Method form narrows that to **one privileged, always-in-scope
binder: the receiver**. So rung 2 needs only **"a refinement may name `this`"**
(receiver-relative refinement), not general cross-argument dependency. James's
field-not-method move (§1.1) shrinks it further: because `count` is a stored field
(immutability makes that honest), `this.count` is the **same field-access machinery
as `@.field` pointed at `this`** — so rung 2 does **not** need `@.method(...)`
predicates (the harder, not-yet-sort-checked case — TODO "Refinement-predicate
sort-checking deeper than `@.field`"). The two pieces it needs are both small:
(1) a refinement may name the receiver binder `this`; (2) reference its field
`this.count`.

What it does **not** make free: the **discharge**. The bound `i < this.count`
references a value that is generally dynamic (a specific array's length), so proving
it at a call site is the integer engine's job — provable for literals
(`(1,2,3).count == 3`) and `Iterate`-bounded indices, deferred otherwise. It routes
through `BoundAnalysis`/`IntegerDischarge` and **degrades safe**: not statically
provable → the `[!!T]` runtime hazard (`runtime-hazard`); provable miss (a negative
literal) → compile error. The construction gate's three-way, applied to an index —
and this discharge is the same difficulty whether the bound came from a field or a
method, so nothing here is new beyond the existing bounds reasoning.

**Soundness rider — immutability.** `this.count()` must be **stable** between the
check and the access, or the bound is a TOCTOU lie. Pontif values are immutable (the
conservation/no-erase world), so this holds for free. It is *why* receiver-relative
bounds are sound here and would not be in a mutable-array language — state it
explicitly at the gate.

### Rung 3 — a `Fin`-style index sort (the endgame, OPEN)

The strongest encoding makes out-of-bounds *unrepresentable*: an index sort
`Fin(this.count())` (the type of naturals `< count`) carried by the
free-type-parameter machinery (`type-parameters`: *the field IS the witness*). Then
`at` returns plain `T` with **no `OutOfRange` arm at all** — it provably never
fires. Deferred; recorded so rungs 1–2 leave the breadcrumb.

---

## 3. Lineage (array indexing is *the* dependent-types example)

Three escalating strengths in the literature; Pontif's three rungs track them:

| Pontif rung | Literature | Shape |
|---|---|---|
| 1 — total union | Rust `slice::get(i) -> Option`, Scala `Seq.lift` | runtime-checked totality, no dependency |
| 2 — receiver-relative refinement | **F\*** `i:nat{i < length l}`, **Liquid Haskell** `{i:Nat \| i < len xs}`, **Dafny** `requires 0 <= i < a.Length` | predicate references a prior/receiver binder |
| 3 — `Fin`-indexed family | **Agda/Idris/Coq/Lean** `lookup : Vec n a → Fin n → a` | index type excludes out-of-bounds by construction |

The seminal motivating work is exactly this problem: **Xi & Pfenning,
"Eliminating Array Bound Checking Through Dependent Types," PLDI 1998** (Dependent
ML). This war and that paper share an enemy.

Note rung 2 (refinement-style) is the closest to Pontif's grain — it *is* `[Base:
predicate]` with the predicate allowed to name `this`. Rung 3 (Fin-style) is the
heavier, type-level-natural encoding, and is where `Present|OutOfRange` collapses to
just `Present`.

---

## 4. Conservation & jurisdiction

**Read = Observation (DERIVED).** A random-access read is an **Observation**, not a
Placement (`iteration.md` §1): it reads without consuming, any number of reads
coexist, nothing moves. So indexing drops into the conservation ledger cleanly and
never touches the no-erase / no-duplicate coin. Repeated `xs(i)` is repeated
observation of an immutable value — not duplication.

**Array purity — OPEN.** `streams.md` made `Array` action-side ("iterable only via
observation; memory order is runtime dynamics"). But *random access at a
caller-chosen index* is a **pure projection**, not memory-order iteration — it is
deterministic and side-effect-free. So `Indexed` access is pure even though
bulk-walking an array in storage order may stay action-side. The open question:
does adding `Indexed` make `Array` (partly) pure-side, or does `Array` stay dual
(pure indexed access + action-side bulk iteration)? Lean: **random access is pure;
the action-side framing was always specifically about *un-indexed* memory-order
walking.** This is the "war" escalation James set aside (additive route chosen, §8);
recorded, not resolved.

---

## 5. The GPU payoff (the forcing function)

`pontif-supirvast` Slice 2 was blocked because `(i, a, b) -> a(i) + b(i)` was
inexpressible (`project_supirvast_integration`). `Indexed` makes it expressible, and
it composes with `Iterate`, which **already** moved off cons-cells precisely because
they "cannot represent 'this element, traversal pending,' which is exactly what an
array is" (`iteration.md`) and already "provides the current index." So:

- `Iterate` supplies the index `i`;
- `Indexed` supplies `other(i)` (random access into the *other* input arrays).

Vector-add then lowers **without tuple/struct columns** — likely retiring supirvast
path-3 (the "add tuple columns now" option). The expressible kernel is no longer
just single-input map; element-wise multi-input falls out of index + `Iterate`.

---

## 6. Scope: additive amendment (RULED)

Code-wise this is **mostly additive** — a new trait, an `Array` implementor, the
bounds discharge wiring. The weighty part is **doctrinal**: it amends the "there is
no indexing" ruling in three ratified docs. James ruled the **additive** route (not
the Array-jurisdiction war, §4).

Docs to amend (pending ratification of this proposal — *not yet edited*):

- `streams.md` — "no semantic array type / random-access indexing does not exist"
  → "no indexing on **base `Stream`**; random access is the `Indexed` sub-trait
  capability."
- `glossary.md` — the `Queue / Array` and "no indexing" entries; add an `Indexed`
  entry.
- `strings.md` — `s(i)` stays absent for `String` (a String is not `Indexed`;
  random access remains an Array concern), but reference `Indexed` as the home of
  the capability it points at.

Adjacent TODO threads this unifies with: **"Named-parameter method sorts"**
(dependent *return* refinement referencing the function's own parameter — the
sibling of receiver-relative refinement) and **"Refinement-predicate sort-checking
deeper than `@.field`"** (the `@.method(...)` gap rung 2 needs closed).

---

## 7. Slice plan (vertical, each end-to-end green)

0. **This doc ratified** + the three-doc amendment (§6).
1. **`Indexed` trait + total `at`** — declare `Indexed : Stream` with `count` and
   `at(i):[Present(T)|OutOfRange]`; `Array` as implementor; union return matched by
   arm. No proof machinery; cannot lie. (Depends on the `Array` implementor —
   `streams.md` slice 4 — and the trait/narrowing rails, which exist.)
2. **Refined `xs(i):T` via receiver-relative refinement** — allow a param
   refinement to name `this`; sort-check `this.count()`-shaped predicates; the
   value-dependent bounds discharge through the integer engines; three-way gate
   (clean / `[!!T]` / compile error).
3. **Literals as `Indexed`** — `(1,2,3)` carries the `Indexed` sort with static
   `count`, so small fixed accesses prove clean at compile time (the first rung-2
   customer that discharges statically).
4. **`Iterate` + index → GPU** — feed `Indexed` access into supirvast's element-wise
   kernel; vector-add lowers (§5).
5. **(Endgame, deferred)** the `Fin`-style index sort (rung 3, §2).

Probe meter: the existing `dispatch__*` / `traits__*` cross-module probes plus new
`indexed__*` probes (in-bounds clean, out-of-bounds arm, provable-miss reject,
unprovable → hazard).

---

## 8. Open decisions

1. **`Present` / `OutOfRange`** member names; whether `OutOfRange` joins the
   `std.common` terminal family with `Leaf`, and whether `Present(T)` reuses a
   future option-shaped union (§2 rung 1). **`OutOfRange` NOT unified with `Leaf` /
   the absence family (RESOLVED 2026-07-28, James)** — a leaf, a stream-end, an invalid
   index, and a query-miss are semantically distinct and keep distinct nominals; see
   `stream-queries.md` §4.1. (Member-name spelling still open; a shared *trait* is fine.)
2. **Array purity** (§4) — does `Indexed` make `Array` pure-side for random access,
   and is the action-side framing now *only* about un-indexed bulk iteration?
   (Set aside by the additive ruling; revisit if it bites.)
3. **Rung-2 discharge depth** — how much of `i < this.count` the integer engines
   prove statically vs defer to the `[!!T]` hazard; the receiver-relative-refinement
   capability (refinement-may-name-`this` + `this.count` field reference) is the
   prerequisite either way.

*Resolved this session: `Indexed` (name), total `Present|OutOfRange` as the rung-1
primitive, additive scope, `count` (name + field-not-method), `this.count` spelling.*
