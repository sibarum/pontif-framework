# KEYED: indexing as a disposition; Unique / Ordinal / Cardinal

Status: **DESIGN RULED 2026-07-26.** Converged in a design conversation with
James. Builds on the ruling stream model (`stream-war.md` → the `Iterate`
disposition table), on sort-transforms (`sort-transforms.md` → keys declared in
sort position via `->`), and on native record equality (`RecordValue.equals`,
`glossary.md`).
Markers: **RULED** (James ruled it in conversation), **DERIVED** (follows from
ruled material plus the standing principles), **PROPOSED** (Claude's suggestion,
awaiting a ruling). All surface names here — "disposition", "access path",
"junction", the eventual spelling of the key-fragment — are provisional until
ratified into `glossary.md`.

**Implementation status.**
- **KEYED output kind — STUBBED.** `IrExpr.OutputKind.KEYED` exists in the
  disposition table; `IrInterpreter.evalIterate` throws for it. This doc rules
  what filling it in *means*. Nothing below is landed yet.
- **Substrate already present:** `Iterate` (source/coSources/outputs/arms), the
  fan-write sentinel (`"*"`, `Write.FAN`) used by fork, `IndexedStream : Stream`
  (`count`/`at`), sort-transforms on arg/return positions, and the native
  generated `RecordValue.equals`/`hashCode` (typeName + members).

# Thesis

There is no `HashMap`, no `Array`, no `Set`, no `Dictionary` type. There is one
`Iterate` node with three **dispositions** — the three ways an element leaves the
loop:

| Disposition | Meaning | Surface it presents as |
|---|---|---|
| `STREAM` | ordered emission | the sequence / tuple |
| `ACCUMULATOR` | absorbed into a fold | the scalar |
| `KEYED` | **placed at a key** | the addressed set (dict / array / set / group / graph) |

`STREAM` is ordered *consumption*; `KEYED` is out-of-order *addressing* — the two
are duals. Just as `fold` is the universal shape of consumption, **index
application is the universal shape of retrieval**: `get(k)`, `a[i]`, `range(lo,hi)`,
`group(k)`, `contains(x)` are *one operation* — re-observing a `KEYED` output at a
key — at different licensed cardinalities. (RULED.)

An array is a database with an auto-increment primary key. A dictionary is an
extra unique index on the same table. (RULED — the framing that opened the
conversation.) So:

- **array** = `KEYED` by a dense, contiguous integer key → `IndexedStream.at`.
- **dict** = `KEYED` by a `Unique` key → 0-or-1 lookup.
- **set** = `KEYED` by identity (the record's own `Unique`).
- **multimap / group / graph** = `KEYED` by a `Cardinal` key.

Array/Dict/Set are not types; each is *a relation plus a declared access path*.

# Keys are declared in sort position

The per-element key is a **sort-transform** (`sort-transforms.md`), not a returned
pair: the arrow computes the key on the way past, the refinement carries the claim.

```
&people:[ (p:[Person -> @.id -> Int:@unique]) -> p ]
```

The arg shell `Person -> @.id -> Int` computes the key (the coercion use of
sort-transforms); `@unique` on the derived key type is the **ledger claim**; the
element flows through unchanged (`-> p`). Composite keys ride the sort-transform
**fan-in** (`sort-transforms.md` §dataflow — arg sorts mutually visible via `@`),
so `by(a,b)` is a key sort reading a sibling. (DERIVED.)

The claim is a **proof obligation the SortChecker discharges** (the same layer that
checks the return-shell wrap), not a runtime construction-gate assertion —
discharging it is the *license* to specialize the physical structure. (RULED.)

# The three key-traits — identity, order, cardinality

Three questions you can ask about a key, three traits, three licensed structures.
Independent, composable axes on a single key.

| Trait | Question | Contract | Licenses | Unlocks |
|---|---|---|---|---|
| `Unique` | "are these the same?" | `equals` + `hash` | hashed slot map | `get`→0/1, dedup, membership, the `@unique` collapse |
| `Ordinal` | "which comes first?" | `compareTo` | sorted vector / B-tree | `range`, min/max, successor, ordered scan |
| `Cardinal` | "how many?" | `categorize(a):Stream[K]` | bucket / junction / adjacency | `group`→many, join, neighbors |

`Unique` and `Ordinal` are **unary** algebras on the key *value* (how it hashes,
how it orders). `Cardinal` is the **arity** axis — how many keys one instance
emits — and rides on top of the category type's `Unique`/`Ordinal` (you still need
the tag hashable to bucket it). They compose: `Unique + Ordinal` = a sorted unique
index; `Cardinal + Ordinal` = a sorted multimap. (RULED — the three names and their
meanings; the orthogonality is DERIVED.)

## Physical form is a licensed collapse

Discharging a claim licenses collapsing the generic `KEYED[K, Stream[E]]` bucket:

| Proved of the key | Physical form | Result type |
|---|---|---|
| nothing (raw `Cardinal`) | bucket hashmap | `KEYED[K, Stream[E]]` |
| `Unique` (injective) | slot map — bucket collapses | `KEYED[K, E]` |
| `Ordinal` | sorted vector / B-tree | + `range` |
| dense contiguous int | array backing (the existing linearity license) | `IndexedStream` — `at` |

If injectivity can't be proved, retrieval stays `Stream[E]` and the compiler makes
the caller handle the multi-hit case (the two-faces discipline). Enforcement and
access are the *same* hashed probe — "is this key present" and "fetch by this key"
are one operation. (DERIVED.)

## Runtime: KEYED, plus fan for Cardinal

No new runtime machinery. `Unique` is `KEYED` with a single write + injectivity
collapse. `Cardinal` is `KEYED` with a **fan** write — the key sort returns
`Stream[K]` and the existing fan sentinel (`"*"`, `Write.FAN`) routes the one
element into every emitted bucket. Same disposition, different write arity — the
conservation ledger (one-in, many-placements) already models it. (DERIVED.)

# Equality: every struct gets a generated Unique

Native `==` on structs is structural + nominal (`RecordValue.equals`: same
`typeName` and recursively-equal members; `Point{x:1} != {x:1}`). Today it is
hardwired — `EQ`/`NE`/`APPROX` never route to a user overload.

Ruling: **every struct gets a comptime-generated `Unique` (equals/hash), like a
reflection equals-builder but generated at compile time. To override, assign the
trait.** (RULED.)

- `equals` and `hash` are **one non-splittable trait** — you cannot supply one
  without the other (the completeness gate rejects a half-pair), so a KEYED
  structure's collision rule and its hash are always consistent. (RULED — bundle;
  the gate is DERIVED.)
- `EQ` is de-hardwired *only* to "does this struct type carry the trait?" → route
  to it; else the generated default. `Int`/`Decimal` never get the trait and stay
  hardwired by design. (RULED.)
- Heterogeneous `==` (different types, or named-vs-anonymous) finds no same-type
  overload and **falls back to the generated default** (false on `typeName`
  mismatch) — never a "no matching `==`" error. (RULED.)
- `~=` (APPROX) stays kernel-only — it is numeric rounding, not identity. (PROPOSED.)

# Uniqueness constraint = the declaration of an equality

A uniqueness constraint is not a property added to a table; it **is** an equality.
Defining `equals`/`hash` on a struct is declaring "these fields make two of these
the same row" — exactly what `UNIQUE(cols)` means. So `Unique` and the `@unique`
key-claim are one concept from two ends: `Unique` *defines* which collisions count;
`@unique` *asserts* none occur. (RULED.)

Two loci:
- **Intrinsic** — the struct's own `Unique`: its natural / primary key, shared by
  every relation that stores it. Every struct ships one for free, which is what
  makes it a `Set` element.
- **Extrinsic** — a `KEYED` access path keyed by a *projection*; uniqueness is
  governed by the **projection type's** `Unique` (a unique index on `email` = key
  by the `Email` projection, collision rule = `Email`'s `Unique`; a composite unique
  index = key by a tuple projection, collision rule = the tuple's generated
  structural `Unique`). (DERIVED.)

Overriding `equals` = redefining the natural key = redefining the uniqueness
constraint. The three phrasings are synonyms.

# Cardinal is a generalized categorizer; many-to-many is always a join

`categorize(a):Stream[K]` emits 0, 1, or many keys per instance:
- one key → single membership (a foreign key)
- many keys → tags/labels
- keys that are other instances → adjacency / a graph

"Up to many-to-many" is structural: the emission is multi-valued *and* its inverse
is multi-valued. One `Cardinal` index *is* the bidirectional relationship —
`categorize(x)` forward, the KEYED bucket at `c` inverse. **The junction table
disappears**: you declare the categorizer, not the junction. (RULED.)

## One definition site, always a join underneath

A many-to-many is declared exactly *one* of three ways, chosen for how the domain
reads — but physically it is **always a junction**, and the surface form is only
where you write the declaration:

- **a's on B** — "a `Tag` has `Photo`s"
- **b's on A** — "a `Photo` has `Tag`s"
- **join table** — "`Tagging` relates the two"

(RULED.) Consequences:

- **Exactly one — single source of truth.** Declaring on both sides (two mutable
  mirrors) is the classic drift bug; forbidden. Because the junction is symmetric,
  **both directions are queryable regardless of which side you wrote it on** — the
  owning side has zero semantic weight (unlike an ORM). Which directions are
  *indexed* is chosen by access (fusion/pushdown), not by the owning side. (RULED /
  DERIVED.)
- **The join-table form is strictly more expressive.** The embedded forms express
  only an *attribute-free* edge. When the relationship carries data (an
  `Enrollment` has a date), you need the named junction — and because it **is a
  relation**, it has its own `Unique`/`Ordinal`/`Cardinal` keys, is indexable, and
  joins further. (RULED.)
- **One lowering.** All three lower to an internal `Junction(A, B, attrs?)`. The
  embedded forms produce an **anonymous, attribute-free** junction; the join-table
  form a **named** junction that can carry columns. Embedded forms are pure sugar
  for "the join table with no extra columns"; you upgrade to a named junction only
  to hang data on the edge. (RULED.)

# Restrict: the predicate is a refinement sort (and the proof, and the probe)

The KEY story above is the *index* half of SELECT. The *restrict* half needs no
new surface either — a `where` is a refinement sort applied by spread. (RULED — the
construction is James's.)

```
struct UserType(name:String)
struct User(id:Int, name:String, type:UserType)
let users:Stream[User] = fetchUsers()
let admins:Stream[User:@.type.name="admin"] = &users[User:@.type.name="admin"]
```

The single refinement `[User:@.type.name="admin"]` plays **three roles at once**:
- **restriction** — as the spread's admittance sort it is the filter (the stream-war
  drop-out-of-domain disposition);
- **proof** — as the LHS element type it refines every survivor, so downstream code
  needs no re-check (the effective-sort machinery);
- **index-probe** — it decomposes into *projection + operator + constant*.

One expression, three roles: restriction, proof, probe. There is no `where` /
`filter` / `get` keyword — all are `&stream[sort]`. (DERIVED.)

## The predicate's shape selects the trait

`@.type.name = "admin"` → projection `@.type.name`, operator `=`, value `"admin"`.
The operator picks which trait serves the probe:

| Predicate shape | Served by | Result |
|---|---|---|
| `@.id = 5` (unique projection) | `Unique` — slot | 0-or-1 |
| `@.type.name = "admin"` (non-unique) | `Cardinal` — bucket | the category's members |
| `@.age > 30` / `between` | `Ordinal` — sorted | range |

No index → scan; index present → pushdown. Same declarative surface, compiler
chooses. (DERIVED.)

## A filtered stream *is* a KEYED bucket

`&users[User:@.type.name="admin"]` is exactly the `"admin"` bucket of the `Cardinal`
index `categorize(u)=u.type.name`. So filtering and indexing are the **same
operation at different materialization timing**:
- `where` = one bucket, on demand;
- an **index** = the whole family of buckets `{ [.type.name = k] : k }`, precomputed;
- `groupBy` (Cardinal categorize) = materializing every filter-bucket at once.

Whether `admins` is a live scan or a hit on a standing index on `.type.name` is the
storage/pushdown decision — it changes neither the meaning nor the type. (DERIVED.)

## What you may filter by: any type-valid subset sort

The gate on `&stream[sort]` is **not** an indexability grammar — it is subsort
type-validity. **Any sort `S` such that `Stream[S]` is a possible subset of
`Stream[E]` (i.e. `S <: E` — a well-formed narrowing of the element sort) is a legal
filter**, yielding `Stream[S]`. (RULED.) This reuses the effective-sort /
assignability check (`type-system-convergence`); there is no separate query grammar —
the sort language *is* the query language, in full. `@.name.length % 2 == 0` is a
perfectly legal filter; so is any other type-valid refinement.

Indexability is **orthogonal and invisible**. The optimizer recognizes whatever
shapes it can currently serve from a standing index (equality/range on a projection
today, more over time) and pushes those down; *everything else is a scan*. Both
paths return identical values and identical types, so index-acceleration never
constrains the surface — **scan is the total fallback** that makes `&stream[sort]`
unconditional. "Which sorts an index can serve" is thus an optimizer capability that
grows freely, not a language boundary and not a design open-edge.

# Prerequisite (Slice 0): nested-path refinements

**Multi-hop `@`-path refinements are NOT implemented — single-hop `@.field <op>
value` only.** (Verified 2026-07-26; already tracked at `TODO.md:519`.) `@.a.b.c`
parses to an uncapped `FieldAccess` chain (`AltParser.java:3947`), but
`SortChecker.validateSelfFieldAccesses` (`SortChecker.java:1336`) validates a field
only when its base is `SelfRef` *directly*, so hops past the first are silently
unchecked no-ops; `Refinements` projects Self→member one hop only; and
`pinFieldName` recognizes a pin only on a `SelfRef` base (`@.a.b == v` is not seen
as a pin). So every projection-path example in this doc (`@.type.name`,
`@.nested…`) is aspirational until this lands.

This lives **one layer below KEYED** — it is effective-sort / `SortChecker` work,
not `Iterate` work — but the whole projection-path surface (keys *and* filters)
consumes it. Consequences for sequencing:
- **Flat single-hop keys/filters (`@.id`, `@.name`) are NOT blocked** — the KEYED
  table-scan slice can proceed today with one-hop access paths.
- Anything nested waits on Slice 0.

**Slice 0 = nested-path refinement**: teach `validateSelfFieldAccesses` to resolve
the base's sort and project field types hop-by-hop; walk the chain in `Refinements`;
generalize `pinFieldName` from a name to a path. Then the KEYED table-scan slice
sits on top.

Also confirm the literal spelling: existing fixtures use `==`; the `=` in this doc's
examples needs `=` to lex to `EQ` in predicate position, else the surface is `==`.

# Open edges

- **`~=` governance** (above) — PROPOSED, unruled.
- **Index-direction storage** — forward / inverse / both is a per-*relation* choice
  (not a type property). Ruled that it's inferred from which retrievals the program
  performs; the explicit override spelling is a **candidate**: `assign unique index
  name:[ binder:T -> keyExpr ]` (a standing, correctness-neutral eager-build hint) —
  see `stream-queries.md` §3.
- **Scalar (0-or-1) retrieval** — the set-valued filter above has a scalar sibling: a
  bare-type-sort spread is a first-class **`Query`**, and a terminal op (`.first()`)
  fetches 0-or-1 as `[Present(T)|Absent]`. Ruled 2026-07-28; spec in
  `stream-queries.md`.
- **`emit` and maintenance.** Insert-time upkeep of secondary indexes is the
  deferred effects-by-shell `emit` (`sort-transforms.md`) landing in the KEYED
  shell — an effect injected by shape. MVP retrieval doesn't need it; it's the
  clean slice boundary.
- **Slice plan.** First slice: **get the whole surface working on a table scan —
  no indexes, no pushdown, no physical specialization.** (RULED.) That means:
  `&stream[sort]` filters by scanning and drops non-satisfying elements, yielding
  `Stream[S]` with the refinement carried on the element type; `KEYED` in
  `evalIterate` materializes buckets by a single linear pass (`categorize` →
  append), and retrieval reads/scans a bucket. `Unique`/`Ordinal`/`Cardinal` are
  *declared and type-checked* but do **not** yet drive structure — no bucket→slot
  collapse, no hashed slot map, no range tree. Correctness first; the optimizer that
  turns recognized scans into index hits is a later, purely-performance layer (which
  is why it can never affect results — see "any type-valid subset sort"). Deferred to
  later slices: index selection / pushdown from the trait claims, `Ordinal` range
  structures, `Cardinal` fan-materialized standing indexes, and `emit` maintenance.
