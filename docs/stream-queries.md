# Stream queries: the type-sort spread is a query; terminal ops pick cardinality

Status: **DESIGN — PROPOSED (2026-07-28); Slice A LANDED (2026-07-28).** Converged in a
design conversation with James; the two load-bearing rulings (the bracket-content
disambiguation and terminal-operation cardinality) are **RULED**, the surface names are
**PROPOSED**. Landed & green: Slice A (`.first()`), `.all()`, `[T|Absent]` result-sort
narrowing (`StreamQueryTest`), and Slice B (`assign … index` parse + register,
`AssignIndexTest`) — see §3, §5.
Builds on `keyed.md` (the KEYED disposition — filtering, keys, index/scan duality),
on `stream-war.md` (the `&s:[…]` transform-spectrum and the *no external `next()`*
ruling), and on `indexed-streams.md` (the `[Present(T)|OutOfRange]` honest-absence
ladder). Markers: **RULED** = James ruled it · **DERIVED** = follows from ruled
material + standing principles · **PROPOSED** = Claude's suggestion, awaiting a ruling.

---

## 0. The smell it fixes

`keyed.md` gives the *set-valued* face of retrieval — `&users[User:@.type.name="admin"]`
yields `Stream[User]` — and lists `get(k)`→0-or-1 as "one operation at a different
licensed cardinality," but never spells the **scalar** surface. The obvious spelling,
`&stream:[…].next()`, collides with a hard ruling: `stream-war.md` §7 retired
`Stream.next()` (internal iteration only, *no external `next()`*). So the scalar face
had a hole, and the naive fill was already forbidden.

The fix is not a new operator bolted onto `Stream`. It is recognizing that
`&s:[type-sort]` was never producing a `Stream` in the first place — it produces a
**query**, and a query is a different kind of value with its own terminal operations.

---

## 1. The bracket dispatches on the *kind* of its content (RULED)

`&s:[…]` already carries two meanings along `stream-war.md`'s transform spectrum. This
ruling makes the split explicit and names the second branch:

| Bracket content | Kind | `&s:[…]` produces | Semantics |
|---|---|---|---|
| a **transform arrow** `(el:T) -> expr` | conversion function | a `Stream` (map/filter/fold/…) | run the function on **every** element (the iteration multitool, `stream-war.md`) |
| a **bare type-sort** `MyType:[@.p == v]` | membership predicate | a **`Query`** | *describe* a retrieval — nothing materialized yet |

This is not a new construct grafted onto the spread — it is the **degenerate
(membership) case** of the transform spectrum finally given a use distinct from map.
`stream-war.md` already ruled *"`x:[TypeA]` = confirm membership — the IDENTITY
transform"*; when that identity-membership sort is spread over a stream, the result is
not a per-element map (there is no function to run), it is a **selection**. Selection,
described but not yet executed, is a query. (RULED — James, 2026-07-28.)

The disambiguator is purely the content's grammar: an arrow (`->`) in the fragment head
⇒ conversion ⇒ `Stream`; a bare `Type:[pred]` (no arrow) ⇒ membership ⇒ `Query`. No new
keyword. Consistent with `keyed.md`'s *"the sort language IS the query language."*

---

## 2. A `Query` is first-class, unmaterialized, and terminal-driven (RULED)

`&s:[MyType:[…]]` yields a `Query[MyType]` — a **described, not-yet-run** retrieval. You
then choose *what to do with it*, and the **terminal operation picks the cardinality**
(and triggers execution). This is the SQL shape exactly: a `SELECT` is a query; `fetch
one` and `fetch all` are different terminals over the same query. (RULED.)

The first-class, lazy `Query` is what gives the optimizer its seam: because the query is
a *describable value* rather than an eagerly-built stream, the runtime is free to serve
its terminal from a standing index or from a table scan — the KEYED "index/scan is
invisible" guarantee (`keyed.md`), now with an explicit place to make the choice.

### 2.1 `.first()` — the 0-or-1 terminal (surface PROPOSED)

The scalar terminal returns **the first matching element itself, or absence** — an
`[T | Absent]` union, with the element **unwrapped** (no `Present(T)` wrapper, RULED —
James, 2026-07-28):

```pontif
match &users:[User:@.id == 5].first() {
  [User]   -> use(...)        # a hit IS a User — a type-guard arm, scrutinee in scope
  [Absent] -> fallback()      # the miss arm — provably exhaustive, no `[_]` needed
}
```

(`.first()` infers as the union `[User | Absent]`, so the two arms are provably total —
result-sort narrowing landed 2026-07-29; see §5.)

- It is **0-or-1 by *taking* one, not by proving one** (RULED — James): `.first()` does
  **not** care whether more than one element matches. It is "sample a match," not "assert
  uniqueness." (Contrast the earlier proposal to *derive* the option type from a proof of
  `Unique` — set aside; cardinality comes from the terminal you call, not from the
  predicate's shape.)
- **No `Present(T)` wrapper — the element rides unwrapped (RULED — James).** The result is
  `[T | Absent]`: a hit is the found element *as itself*, a miss is `Absent`. This is
  cleaner than the wrapped `[Present(T)|Absent]` and — decisively — makes consuming the
  result an **ordinary type-guard match** (`[T] -> … [_] -> …`), which needs no
  imported-struct destructure and therefore **no linker pass**. (Wrapping would force a
  `[Present(v)]` destructure of an imported `pontif.core` struct, resolvable only in the
  full `ModuleLinker` pipeline — an ugly requirement the unwrapped form eliminates.) `Absent`
  is its own nominal — deliberately NOT unified with `OutOfRange`, `Leaf`, or `Break` (§4.1).
- **The one honest caveat (edge case, ACCEPTED):** if the element type `T` *is* `Absent`
  (or a union containing it), a found-`Absent` element is indistinguishable from a miss —
  the collapse the `Present` wrapper would have prevented. This requires a literal
  `Stream[Absent]`, which is pathological; the clean common case wins. (Revisit only if a
  real need for querying `Absent`-typed streams appears.)
- Divergence from `indexed-streams.md`, noted: `at(i)` there is still specced as
  `[Present(T)|OutOfRange]`. If that surface is built, the same unwrap-to-`[T|OutOfRange]`
  question applies; not resolved here.
- **Name (RULED): `.first()`.** Rejected: **`.next()`** — reads as the retired
  `Stream.next()` (`stream-war.md`), the exact collision this doc exists to avoid; it is a
  *stepping* verb, and a query has no cursor to step. Runner-up: **`.peek()`** — aligns
  with conservation (a read is a non-consuming Observation), but "peek" connotes
  "look before continuing to iterate," underselling that this *is* the whole result.
  `.first()` states plainly "a leading match, at most one." A `Query` is not a `Stream`,
  so putting a method on it does **not** reopen the no-external-`next` ruling.

### 2.2 Other terminals (DERIVED, sketch)

The same query, other terminals — each a different licensed cardinality over one
description (the `keyed.md` "one operation, many cardinalities" thesis):

| Terminal | Cardinality | Result | Status |
|---|---|---|---|
| `.first()` | 0-or-1 | `[T \| Absent]` (element unwrapped) | LANDED (Slice A) |
| `.all()` | 0-or-many | `Stream[T]` — the `keyed.md` set-face, materialized | LANDED (2026-07-28) |
| `:Stream[T]` ascription | 0-or-many | `Stream[T]` — the bare-spread materialize | later (needs the reified `Query`) |
| `.count()` | scalar | `Int` | later |
| `.only()` | exactly-1, else error | `T` — the uniqueness-*asserting* sibling of `.first()` | later |

`.only()` is where a proven/enforced `Unique` would pay off (it may skip the "is there a
second?" check); deferred with constraint enforcement (§4).

---

## 3. `assign … index` — the explicit, correctness-neutral hint (RULED; Slice B1+B2 LANDED)

`keyed.md`'s open edge left the *explicit* index spelling unspecified (it defaults to
inferring indexes from the retrievals the program performs). This is the ruled spelling for
that override — a **standing** index declaration:

```pontif
assign unique index byId:[ (n:User) -> n.id ]           # a named key-mapping User → Int
assign unique index byBoth:[ (n:User) -> {n.id, n.name} ]   # compound key (tuple)
```

- **An index is a NAMED key-mapping `T → K`, decoupled from `T`'s intrinsic identity
  (RULED — James, 2026-07-29).** `K` is a value carrying `{hashCode, equals}` — either a
  type with that trait, or a **tuple of primitive value types** that get it for free (the
  "reflection equals-builder, generated at compile time"; keyed.md's structural `Unique`).
  The decoupling is the point: several **views** over one `T` may key it *differently*, so
  uniqueness is not read off `T`'s trait membership — it's declared per index. Hence the
  registry holds **many indexes per type**, each with its own name.
- Reuses the `assign` impl verb (as in `assign trait T : B`) — consistent. `unique` /
  `ordinal` / `cardinal` are contextual (not reserved), disambiguated by `<kind> index`.
- The body `[ (n:T) -> keyExpr ]` is the **key-transform fragment**; a compound key is a
  tuple return.
- **`unique`/`ordinal`/`cardinal` is a KIND hint only (RULED — James, 2026-07-29):** it
  selects the physical structure the optimizer *may* build (slot / sorted / bucket, keyed.md's
  three key-traits). It is **not** enforced against data here — uniqueness-constraint
  enforcement is out of scope (§4).
- **Correctness-neutral (the KEYED law restated).** `assign … index` is an *eager-build hint
  / optimizer directive only*. It never changes the meaning or type of any retrieval; with or
  without it, `&s:[sort].first()` returns the same value. Removing every `assign … index`
  changes performance, never results.

**Implementation (Slice B1+B2, LANDED 2026-07-30).** Mirrors `action`/`conduit`: the parser
(`AltParser.parseAssignIndex`) lowers the declaration to an ordinary `IrStmt.FunctionDecl`
under a reserved, non-lexable `#index#SEQ#KIND#NAME` key with params `(n:T)` and body the
key-projection — so **the projection is type-checked for free** (a bad field is a compile
error, `AssignIndexTest`). `IrCompiler` recognizes the `#index#` prefix and registers a
`CompiledModule.CompiledIndex{name, kind, elementType, keyFunction}` in a new
`indexesByType : Map<String, List<CompiledIndex>>` (one list per element type — the
decoupled-views model). **No new `IrStmt`, no new pass.** Drives no structure yet; Slice C's
pushdown will read the registry. `CompiledModule` gained a back-compat constructor so existing
call sites are untouched.

---

## 4. Out of scope (named, not resolved)

- **Uniqueness *constraint* enforcement.** `assign unique index` might eventually also
  *guarantee* uniqueness on the data (reject an insert that collides), not merely index
  it. That is `keyed.md`'s "uniqueness constraint = the declaration of an equality"
  meeting the deferred `emit` / index-maintenance open edge. **Out of scope now** (James)
  — the hint is a retrieval accelerator first; the constraint is a later slice.
- **`&s[sort]` vs `&s:[sort]` colon** — `keyed.md` writes the filter without a colon;
  `stream-war.md`/README write the spread with one. Reconcile when this lands.

---

## 4.1 Absence values are NOT unified — the separation is the statement (RULED-leaning)

The recurring "should the absence values share one nominal?" open question (posed in
`indexed-streams.md` §8 and `stream-war.md` §8.2) is answered: **keep them distinct.**
Unifying costs nothing mechanically and would be the tidy move — but *not* unifying makes
a statement worth making. **The leaf of a tree, the end of a stream, an invalid index,
and the absence of an expected match are four semantically different things. They
originate from different places / call-stacks for different reasons, so they get
different names.** (James, 2026-07-28.)

| Nominal | Answers | Origin |
|---|---|---|
| `Leaf` (`std.common`) | "is there more *structure*?" | the shape of an inductive tree/queue |
| `Break` (`pontif.core`) | "does the producer *keep emitting*?" | stream control flow (a producer's decision) |
| `OutOfRange` (`indexed-streams`) | "was that index *in the domain*?" | a domain violation at an indexed access |
| `Absent` (this doc) | "did my *lookup* find one?" | a query that matched nothing (a legitimate empty result) |

A caller matching `.first()` asks "did my lookup find a record?" — which has nothing to
do with "did I reach the end of the stream." Sharing one arm would let a structural
terminal and a lookup-miss be conflated exactly where they must not be. The distinct name
is the type system recording *why* the absence happened — the same no-lie discipline that
keeps `Nothing` (drop) and `Break` (halt) separate in `stream-war.md`. (The four may still
share a *trait* — e.g. an `Absence` marker for generic handling — without collapsing to
one nominal; that is additive and does not weaken the statement.)

## 5. Implementation plan (rides KEYED's slices)

Prerequisites: `keyed.md` **Slice 0** (nested-path refinements in `SortChecker`) and
**Slice 1** (KEYED-on-table-scan) — both currently unlanded. On top:

- **Slice A — `.first()` over a bare-type-sort query (scan-correct) — LANDED (2026-07-28).**
  `&s:[T:pred]` with a bare type-sort (no transform arrow) is recognized as a query in
  `AltParser.parseSpreadAscription` (the `!looksLikeClause()` branch); `.first()` is parsed
  by `parseQueryTerminal` and lowered by `lowerQueryFirst` to a **stop-at-first-hit scan**:
  an `Iterate` with a single `ACCUMULATOR` seeded to `Absent()`, a guard arm on the
  membership sort that writes the **bare matching element** then a `STOP` write (writes
  process in order, so the accumulator is set *then* the scan halts), and a catch-all arm
  that threads the accumulator unchanged (conservation). A single ACCUMULATOR output seals to
  its value directly, so the `Iterate` evaluates to the found element or `Absent` — the
  `[T | Absent]` union, **element unwrapped, no `Present`**. **No new engine machinery** —
  pure parser/IR reuse of the existing `ACCUMULATOR` + `STOP` primitives; the stubbed `KEYED`
  disposition is not touched. Only `Absent()` is added to `pontif.core` (a zero-field nominal,
  the miss value; distinct nominal, §4.1) and built as an `IrExpr.Record` (struct
  construction), not `Call` (a struct name is not a declared function); the caller
  `require`s it. Pinned by `StreamQueryTest` (19), incl. struct-payload queries
  (`&s:[User:@.id == 2]`), **nested-path predicates** (`&s:[User:@.name.first == "b"]` —
  KEYED Slice 0's hop-by-hop validation plus `Refinements` runtime projection match multi-hop
  paths, so queries are not limited to single-hop), predicate conjunction, the
  arrow-vs-type-sort disambiguation, and **matching the result**
  (`match r { [T] -> … [Absent] -> … }`) — the union is consumable end-to-end. Both
  earlier findings are now **resolved**:
  - **Result-sort narrowing — LANDED (2026-07-29).** `.first()` infers as the union
    `[T | Absent]`, so a two-arm `[T]`/`[Absent]` match is **provably exhaustive with no
    `[_]` catch-all** (`StreamQueryTest.twoArmMatch_isExhaustiveWithoutCatchAll`). The fix is
    a single-`ACCUMULATOR` case in `NarrowingInference.inferIterate` (`inferAccumulatorResult`):
    a lone accumulator output's result sort is the base-widened union of its seed (`Absent`)
    and every non-self-threading write to it (the matched element → `T`), giving `[T | Absent]`
    — instead of the fall-through that sealed a single non-STREAM output to a bare `Stream`.
    Scoped to single-output accumulators, so multi-output Iterates (fold/scan/fork/generator)
    are untouched. A coercion `Cast` was the wrong tool (it demands a registered `cast`
    function); sort *inference* is the right layer.
  - *(Resolved by the unwrap:)* the earlier "destructuring the result needs the full linker
    pipeline" finding is **gone** — matching `[T]`/`[Absent]` uses no imported-struct
    destructure, so `DestructureResolver` is off the consumption path. (Naming `[Absent]` as an
    arm still resolves the imported nominal as a *sort* via the normal module registry — which
    every real multi-file compile has; only the bare single-file test harness lacks it.)
  Un-terminated / unknown-terminal / zip queries are parse errors. **Honest scope:** the standalone
  `Query` value is NOT yet reified — a query must be terminated in place (`.first()`); no
  index/pushdown (Slice C); the found element is the raw `T`, not narrowed by the predicate
  (the §8.6-style imprecision inherited from the guard-filter).
- **Slice B — `assign … index` parse + register — LANDED (2026-07-30).**
  `assign <unique|ordinal|cardinal> index NAME:[ (n:T) -> keyExpr ]` parses (B1) to a marker
  `#index#SEQ#KIND#NAME` `FunctionDecl` — so the key-projection is **type-checked for free**
  (bad field → compile error) — and `IrCompiler` registers it (B2) in
  `CompiledModule.indexesByType` (`Map<String, List<CompiledIndex>>`, one list per element
  type; `CompiledIndex{name, kind, elementType, keyFunction}`). Mirrors `action`/`conduit`:
  no new `IrStmt`, no new pass. **Drives no structure**, enforces no constraint — kind is a
  hint. Pinned by `AssignIndexTest` (6). See §3 for the model (named, decoupled key-mapping).
- **Slice C — pushdown (pure performance).**
  The optimizer recognizes a `.first()`/`.all()` whose query pins a projection matching a
  registered index (read from `indexesByType`) and serves it from the slot/sorted structure
  instead of scanning. Results and types identical **by construction** — the
  correctness-neutrality of §3 made real; can land arbitrarily later without touching meaning.
  (This is the first consumer of the Slice B registry.)
- **Slice D — the other terminals + the reified `Query`.** `.all()` (0-or-many →
  `Stream[T]`, the materialized "Restrict" face) — **LANDED (2026-07-28)** via
  `lowerQueryAll` (a `STREAM`-output `Iterate` emitting matching elements, dropping the
  rest; structurally the guard-filter but emitting the bare element — a query selects, it
  does not map; `StreamQueryTest`). Still open: `.count()`, `.only()`, the bare-spread
  `:Stream[T]` ascription face, reifying `Query` as a first-class bindable value (so
  `let q = &s:[…]` then `q.first()`), and constraint enforcement (§4).

Slices A/B are surface + correctness; C is the "SQL table whose indexes fall back to
table scans" payoff and is invisible to results.
