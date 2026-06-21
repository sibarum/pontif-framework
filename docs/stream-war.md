# Stream war: the pure membrane over stateful sources

**Status: WAR — DECLARED (design session 2026-06-21).** The enemy is the
`Element|Leaf` cons-cell `Stream`; the replacement is `Stream` as a **trait** plus
a single iteration primitive — the **synthesis fragment**. Markers: **RULED** =
settled this session · **DERIVED** = follows from ruled material + standing laws ·
**PROPOSED** = recommendation awaiting a ruling · **OPEN** = undecided.

Supersedes the `Element|Leaf` parts of `streams.md`; builds on `iteration.md` (the
disposition model) and `indexed-streams.md` (the random-access sub-trait). Anchored
by the canonical example in §3.

---

## 0. The enemy (already ruled, never finished)

`iteration.md` §0 (**RULED 2026-06-14**) already condemned the cons-cell model:
`Element(head, rest)` welds a datum to its continuation — a **semantic category
error** that "cannot represent 'this element, traversal pending,'" so it is
**structurally incompatible with contiguous memory and streaming sources** (the very
things `streams.md` wanted), and it carries **no proof benefit** (halting was the
goal; structural recursion was only the means — bounded iteration over a finite
source halts by construction).

The replacement (`Iterate`) landed, but the `std.stream` combinators
(`map`/`concat`/`exchange`/…) were **never migrated off `Element|Leaf`** — they still
ride cons-cells today. And the deeper reason the removal stalled: a real `Stream` had
to be a **generic, optimizable interface**, which the type system couldn't express
(no real `Stream[T]`, untyped `_` everywhere). **That gap drove the dependent-sorts /
type-spec convergence rework** (`dependent-sorts.md`, `project_type_spec_layering`),
now complete. The runway exists; this war lands the plane.

---

## 1. The vision: `Stream` is the membrane (RULED)

A `Stream` is the **pure, provable, conservation-checked interface** that wraps a
messy stateful source — a file, a socket, a GPU buffer, an event/message queue, a
mutable array. **Above** the membrane: a reliable pure functional logic tree.
**Below**: quarantined effects (the actions architecture — effects observed at the
edge, pure inside). That is the design goal in one line: *take a messy stateful
system and wrap it to conform with a reliable, provable, pure functional logic tree.*

This is **not** Rust/Java iterators. The distinctives they lack:
conservation-checked traversal (no-loss / no-duplication), provable termination by
construction, dependently-typed access (`IndexedStream`), fusibility to a GPU kernel,
and a single disposition model unifying every operation.

**No directly-accessible concrete type (RULED).** `Stream` is *existential* — you
only ever hold a value through the trait; the backing (immutable array, handle) is
hidden. Exposing the mutable array would break purity. There is no `Stream` struct,
now or maybe ever.

---

## 2. `Stream` is a trait, with sub-traits (RULED)

- `trait Stream` lives in **`pontif.core`** (RULED namespace — the language's own
  core; `requires pontif.core.{Stream, Nothing}`). Pattern matches the existing
  `std.*` builtin-module mechanism (linker-injected on `require`).
- `IndexedStream : Stream` (name **RULED** — preferred over bare `Indexed`) — the
  random-access refinement, adding `count` and `at` (`indexed-streams.md`).
- **Implementing a sub-trait obligates the whole chain (DERIVED):** `apply trait
  IndexedStream` requires the implementor to satisfy `Stream`'s contract +
  `IndexedStream`'s additions in one block, and the value is-a `Stream` automatically
  (subset/narrowing is-a, `univocal-language-design`). Applying base + sub separately
  is a possible convenience, not the default.
- **trait-*extends*-trait — contract-merge LANDED (slice 1a, 2026-06-21).** `trait B : A`
  now parses (`IrSort.Trait.baseTrait`), and an `assign trait T : B` impl must satisfy
  the **flattened** contract — B's members ∪ A's transitively (`SortChecker.flattenTrait`;
  base-first merge so a derived trait may refine; unknown/cyclic base = hard error).
  Pinned by `TraitExtendsTest`. **Remaining:** transitive *dispatch* satisfaction (a `B`
  value is-a `A` in the trait registry, for bare-trait-receiver dispatch) and
  parametric-base type-arg substitution (`IndexedStream[E] : Stream[E]`).
- **The spine — re-observability is per-source (RULED 2026-06-21, James).** A
  tuple/array literal **can be re-observed** (map/fold it twice, same result;
  conservation's "read = Observation, doesn't consume" holds). Other sources
  (file/socket/event) **cannot** — and James flagged the tension: a consumable stream
  *violates purity* (observing twice yields different values), "IDK how we'll do that."
- **PROPOSED resolution — conservation already covers it (DERIVED from the no-duplicate
  law).** A single-pass stream is not impure, it is a **conserved/linear resource**:
  observing it is a **Placement** (consumes, `iteration.md` §1), and re-observing it
  would be **duplication — which conservation already forbids**. So purity is preserved
  *by Pontif's own no-erase/no-duplicate law* (`project_conservation_receipts`), not by
  importing linear types: the ledger that already refuses to duplicate a value refuses
  to re-observe a consumed stream. Two kinds, one law:
  - **Observation-stream** (literal / array / `IndexedStream`): immutable, re-observable,
    many reads coexist — random access needs this, so `IndexedStream` requires it.
  - **Conserved-stream** (file / socket / event): single-observation, consumed on read.
  Then **base `Stream` = single-observation** (the general, conserved case);
  **re-observability is the additional capability** the finite/pure side
  (`IndexedStream`, literals) carries. This unblocks slice 1's contract. *Still James's
  to ratify — the open part is whether conservation-as-the-mechanism is the chosen
  framing, not whether it's expressible.*

---

## 3. Synthesis fragments: the one iteration primitive (RULED)

There is **no `map`, `filter`, or `fold` primitive**. There is one construct: a
**synthesis fragment** — a named, reusable, refinement-guarded per-element function
(`let f:[ (channels) -> (channels) ]`) — applied to a stream by **spread** (`&s`).
(`map`/`filter`/`fold` will return *later* as library sugar over this — **out of
scope for now**.)

### The positional-channel model (RULED)

Each tuple position is a **channel**; input position *i* ↔ output position *i*:
- a **`&stream`** argument makes a position a **stream channel** — per element, the
  return value at that position is emitted (`null` = omit), sealing to `Stream[T]`;
- a **value** argument makes a position an **accumulator** — seeded by the value, the
  return value at that position threads to the next iteration's input there, sealing
  to the final scalar `T`.

`Nothing`/`null` is the **universal omission value** (RULED — `requires
pontif.core.{Nothing}`, `let null:Nothing = Nothing()`), not a Stream-special
disposition.

**The output channel shape is declared explicitly** on the application —
`expr:[Shape]` — and is **required** when the fragment + args don't fix it
(fan-out/fan-in). The per-position type *is* the channel-kind signal: `Stream[T]` ⟹
stream channel, bare `T` ⟹ accumulator. This is the **construction-gate claim**
applied to a call result: the declared output is judged against what the fragment
produces (no-lie — a channel the fragment can't fill is a compile error). Positional
projection is the existing tuple-member access `._N` (the earlier `.{index}` proposal
is **retired**).

`&` on **multiple** stream args **zips** (element-wise) — `add(&a, &b):[Stream[Int]]`
is vector-add, which **unblocks the supirvast GPU kernel** (`project_supirvast`).

**`&` is universal — it applies to *any* function (RULED), not just fragments.** A
**single-return** function spread over one stream **is map**, with no ceremony:
`double(&s)` → `Stream[Int]`, result type inferred from the single return. This
collapses map and filter into one thing: **filter is just a map whose function can
return `null`** (the omission drops) — there is no separate `filter` operation. So the
dividing line is the **return arity**: a *single* return ⟹ map (easy, inferred); a
*tuple* return ⟹ the multi-channel model (explicit `:[Shape]`). The synthesis-fragment
machinery (accumulators, fan-out, ascription) only engages for the tuple case; plain
elementwise transforms are just ordinary function calls with `&`. (Value args
alongside `&` that have **no** matching bare-`T` output channel are plain constants,
not accumulators — `f(&s, 5)` binds `5` constant; `fold(&s, 0):[(…, Int)]` makes `0`
an accumulator via its `Int` output position. **OPEN detail:** whether `f(&s)` over a
`T|Nothing`-returning function infers `Stream[T]` (strip → lossy) or
`Stream[T|Nothing]` (keep) — the explicit `:[Stream[T]]` appears to be what chooses to
strip.)

### Canonical example (the final prototype, 2026-06-21)

```pontif
module examples.stream

requires pontif.core.{Stream, Nothing}

let s:Stream[Int] = (1,2,3,4)

# "Nothing" is used for omissions during iteration.
let null:Nothing = Nothing()

let filter:[
  (el:Int) ->
  match el
    [@>2] -> el
    [_]   -> null
]
let filteredLossy = filter(&s):[Stream[Int]]          # one stream; null drops (LOSSY)

# Args passed in are the same order as the returned tuple.
let fold:[
  (el:Int, total:Int) ->
  (null, el + total)                                  # &s -> null (empty stream); 0 -> el+total (accumulator)
]
let result:Int = fold(&s, 0):[(Stream[Int], Int)]._1  # project the accumulator

let fork:[
  (el:Int) ->
  match el
    [@>2] -> (el, null)
    [_]   -> (null, el)
]
let filteredConservative = fork(&s):[(Stream[Int], Stream[Int])]   # CONSERVATIVE: each element routed to exactly one
```

`map` = a one-stream-channel fragment; `filter` = one stream channel with `null`
(lossy); `fold` = a stream channel (often empty) + an accumulator; `scan` = a stream
channel emitting the running accumulator **and** the accumulator (`(el,total) ->
(el+total, el+total)`); `fork` = fan-out to two stream channels (conservative); `zip`
= multi-`&` fan-in. All one construct.

**Replaces lambdas.** A named synthesis fragment + `&` application is the
anonymous-function story now (`Lambda`/`Apply` were already headed for deprecation,
`project_stream_substrate`).

---

## 4. How you get a `Stream` (RULED shape)

1. **Stream literal** — a tuple coerced to `Stream[T]`, the compiler verifying every
   element conforms to `T` (the acceptor's element type). A one-way autobox **partly
   exists** today (`AltParser`, element-checked) but currently *forgets* the tuple to
   a tuple-at-runtime; this war makes it produce a **real (immutable-array-backed)
   `Stream`**. Literals are concatenable.
2. **A builtin API** — file read, webservice-response parse, GPU data formatting,
   thread/event message queues. Each is an **implementation of the `Stream` trait**,
   added per task. Most of these APIs are blocked today purely for lack of streaming;
   each wraps a stateful system into the pure membrane (the message-queue framework
   adds its own riders: thread-safety, locking, pure callbacks).

---

## 5. Conservation (DERIVED + OPEN)

- **Lossy vs conservative is visible in the return shape.** A single stream channel
  with `null` arms **drops** elements (lossy — `filteredLossy`); a tuple of stream
  channels routes each element to exactly one (conservative — `fork`: no loss, no
  duplication). **OPEN:** does the conservation ledger *flag* / require explicit
  acknowledgement of a lossy fragment (no-*silent*-erase, `iteration.md` §4), or is
  declaring the single-channel return type acknowledgement enough?
- **Read = Observation** for re-observable streams (doesn't consume); single-pass
  streams consume — the §2 spine question.

---

## 6. Builds on / retires

- **Builds on** the dependent-sorts **discharge foundation** (`cab59d2`):
  `IndexedStream`'s `at(i:[Int:@>=0 & @<this.count]):T` discharges through
  `StaticDispatch.provablyDisjoint` + `inferArg` — a literal `count` proves in-bounds
  statically, an unknown one stays residual (→ `[!!]` hazard). And on the type-spec
  convergence: a synthesis fragment **is** a named spec fragment (its `[@>2]` guards
  are reusable refinement fragments — `project_type_spec_layering`).
- **Retires:** `Element|Leaf` (`std.stream`), the cons-cell combinators, and
  anonymous `Lambda`/`Apply` (fragments replace them).

---

## 7. Slice plan (vertical, each end-to-end green)

1. **`trait Stream` + trait-extends-trait machinery**; `Stream` in `pontif.core`;
   tuple literal → a real immutable-array-backed `Stream[T]` (not the forgetful
   autobox). The spine ruling (§2) needed here.
2. **The synthesis-fragment primitive** — `&` spread, `Nothing`/`null`, explicit
   return-type ascription — single stream channel (map-shape) first.
3. **Multi-channel** — accumulators (fold/scan), fan-out (fork), fan-in/zip.
4. **`IndexedStream : Stream`** — `count` + `at`, rungs 1–2 (`indexed-streams.md`);
   the discharge foundation already exists.
5. **Demolition** — remove `Element|Leaf`; migrate or retire the combinator tests.
6. **Builtin API impls** — file / GPU first (unblock supirvast vector-add).
- *Deferred:* `map`/`filter`/`fold` library sugar; the `Fin`-style index endgame.

---

## 8. Open decisions

1. **Single-pass vs re-observable spine** (§2) — the base `Stream` contract.
2. **`Nothing`'s identity** — a fresh `pontif.core` type, or unified with the
   `std.common` `Leaf` / a shared absence-value family (and the `Present|OutOfRange`
   option shape, `indexed-streams.md` §8)?
3. **Conservation ledger treatment of lossy** (§5).
4. **`pontif.core` contents + `std.stream` retirement** — repurpose `std.stream` or
   stand up `pontif.core` and retire it.
5. **The combinator sugar surface** (`map`/`filter`/`fold`) — deferred, but its shape
   should fit the query-DSL grain (`project_query_dsl`).

---

## 9. WAR markers (cut sites)

- `BuiltinModules.STD_STREAM` / `STD_STREAM_SOURCE` — the `Element|Leaf` source to
  delete / repurpose; `stdCommon`'s `Leaf` re-export.
- `AltParser` tuple→`Stream` autobox (`streamAutobox`, `requireStreamElements`) — make
  it produce a real Stream; add `&` spread parsing and `expr:[Shape]` ascription.
- The trait machinery (`assign trait` / `AliasResolver` parametric-trait resolution) —
  add trait-extends-trait.
- `StaticDispatch.provablyDisjoint` / `NarrowingInference.inferArg` (`cab59d2`) —
  `IndexedStream.at` bounds discharge (foundation done).
- `StreamCombinatorTest` / `std.stream` combinators — migrate or retire.
- `IrInterpreter` `Iterate` / `sealStream` — the execution semantics the fragment
  lowers to.
