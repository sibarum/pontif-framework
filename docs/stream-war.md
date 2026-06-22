 and # Stream war: the pure membrane over stateful sources

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
  Pinned by `TraitExtendsTest`. **Transitive dispatch satisfaction also LANDED** — a `B`
  value is-a `A` in `TraitRegistry` (query-time base-chain walk), so a bare `A`-typed
  receiver resolves a `B` value (`useBase(v:Base)` called with a `Derived` value
  dispatches `Base.a` → `T.a`). **Remaining:** parametric-base type-arg substitution
  (`IndexedStream[E] : Stream[E]`), deferred until `IndexedStream` lands.
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

### Ascription is a transform spectrum (RULED 2026-06-21, James)

The deepest reframe of the war, and it makes the fragment *not a new construct*:
a fragment is **type ascription** where the type-language is allowed to be a
**transform**, not only a predicate. `:` keeps its single meaning — *"this value
goes through this type"* — across a spectrum:

```pontif
x:[TypeA]                              # confirm membership — the IDENTITY transform
x:[TypeA -> TypeB]                     # coercion — the type refinement transforms x
x:[a:TypeA -> TypeB(a.value)]          # explicit conversion (named binder, result expr)
x:[a:TypeA -> b:[TypeB(a.value)] -> TypeC(b)]   # a chain of transforms
```

Membership is the **degenerate (identity) case** of transformation — *"it was a
type system all along."* Just as destructuring compresses boilerplate extracting
values from arguments, **type fragments compress boilerplate in contextual data
conversion** (coercion). Consequences (each a corollary, not a new rule):

- **`:` is not overloaded.** One operator over a richer type algebra; the earlier
  "`:` means transform-by now" worry dissolves — confirm/refine/coerce/map are one
  spectrum.
- **No separate `:[Shape]` output ascription.** The output channel shape *is the
  codomain of the arrow*. `fold`'s `(Stream[Int], Int)` is where the transform
  lands, not a trailing claim. Channel kinds come from the codomain — **inferred
  when the inputs pin them** (a value-arg in → accumulator out), **written in the
  arrow's result type when they don't** (fan-out: one stream in, two streams out,
  so `(Stream, Stream)` can't be read off the inputs).
- **`:` applies, `&` distributes.** `:` runs the transform on its subject; `&`
  lifts it over the elements of a stream. So `s:[el:Int -> …]` would run a
  *per-element* transform on the *whole* stream (a domain error); **`&s:[…]` maps
  it**. `map` is therefore a **corollary of spread + transform**, never primitive —
  and this keeps the `[…]` in **sort position** (right of `:`), honoring the
  bracket/paren law (`[]` for types) that `fragment(&s)` / `[…](&s)` broke.
- **Refinement and transform compose in one bracket.** `x:[Int:@>0 -> Decimal]` =
  *"x is a positive Int, then coerce to Decimal"* — guard the domain (`:`), then
  advance it (`->`). A new legal form the frame produces, not a re-spelling.
- **Bare `[A -> B]` (no body) resolves to a *registered* coercion or is a compile
  error** (no-lie — never invent a conversion). That is exactly the closed-primitive
  implicit set (`Int→Decimal`) from the explicit-coercion ruling, viewed through
  this lens; otherwise write the body `[a:A -> B(…)]`.

Canonical application form (supersedes `fragment(&s)`):

```pontif
let filteredLossy = &s:[
  (el:Int) ->
  match el
    [@>2] -> el
    [_]   -> null
]
```

Placement: neighbors are coercive subtyping, arrows, "compiling to categories";
what is **distinctly Pontif** is the whole spectrum (confirm / refine / coerce /
map) on one `:` with the value on the left and the conservation law underneath —
so the stream combinators aren't a library grafted on, they are *what the type
system already does, distributed*.

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

1. **`trait Stream` + trait-extends-trait machinery.** trait-extends **LANDED** (slice
   1a — contract merge + transitive dispatch). `trait Stream[type E]{}` (marker, no
   contract member yet — ruling (i): internal iteration, no external `next()`) now
   lives in the `pontif.core` builtin module, **importable** (`requires
   pontif.core.{Stream}`), and a tuple literal autoboxes into `Stream[E]` with element
   checking (slice 1b, `StreamTraitTest`). **Remaining:** the real immutable-array
   backing (1c, deferred — the figurative tuple-as-Stream works for now) and migrating
   the figurative `BUILTIN_PARAMETRIC_TYPES` "Stream" onto the trait.
2. **The synthesis-fragment primitive** — `&` spread, `Nothing`/`null`, explicit
   return-type ascription — single stream channel (map-shape) first.
   - **Slice 2a LANDED** (the map shape, ordinary-fn first — James's cut): a `&s`
     spread argument on *any* single-return function call lowers to `IrExpr.Iterate`
     (one default `STREAM` output, a wildcard arm applying the fn per element), so
     `double(&s)` → `(2,4,6,8)` and a `Stream[Int]`-typed binding autoboxes the result
     (`StreamMapTest`). `&` is detected at the head of a call argument (unambiguous —
     binary `&` only occurs inside a refinement bracket); multiple spreads (zip) are
     rejected with a slice-2c pointer. Wired through `Call`/`Apply`/`MethodCall` via
     `AltParser.lowerSpreadCall`; the spread rides a transient `&spread` call sentinel
     (no new IR variant). The `Iterate` engine already did `STREAM`/`ACCUMULATOR`, so
     this was pure parser/IR.
   - **Slice 2b LANDED** (the lossy `filter` semantics, ordinary-fn first again —
     same philosophy as 2a, before the fragment-literal sugar): `Nothing` is a
     zero-field struct in `pontif.core` (`requires pontif.core.{Stream, Nothing}`,
     `let null:Nothing = Nothing()`), and a stream-channel write of a `Nothing` value
     **drops** that element (`IrInterpreter.evalIterate` skips the append;
     `isNothing` matches the bare or `pontif.core/`-qualified nominal). So an ordinary
     `keep(x:Int):[Int|Nothing]` spread over `(1,2,3,4)` yields `(3,4)`
     (`StreamMapTest.spreadOverNothingReturningFn…`). Also fixed a no-lie bug: the
     parser's let-claim mismatch gate hard-rejected when the value's inferred base was
     the unknown floor `_` — that's rejecting on absence of proof; it now **abstains**
     on `_` and defers to the IR `ConstructionGate` (which sees imported structs) and
     the runtime binding-claim check. Required so `let null:Nothing = Nothing()`
     (imported constructor → inferred `_`) is accepted.
   - **Slice 2c LANDED** (the fragment literal — James ruled **first-class value**,
     the lambda replacement, over desugar-to-function). `let f:[ (el:Int) -> body ]`
     parses to an `IrExpr.Lambda` (a `Closure` at runtime) bound as a 0-arg let sorted
     `[Method(el:Int):Ret]` (`AltParser.parseFragmentLiteral`, detected by a NAMED
     param head `( IDENT :` — no tuple/Method sort starts that way). A fragment is a
     callable value: `double(3)` applies it directly, `double(&s)` spreads it (map),
     and the canonical `filter(&s)` over `(1,2,3,4)` → `(3,4)` (`StreamFragmentTest`).
     The enabler: `IrInterpreter.dispatchValues` already reached through a 0-arg let to
     re-dispatch a bound **metareference** (the `()`-law); extended so a 0-arg let
     holding a **Closure** is invoked with the call's args — so the spread-lowered
     `Call("filter",[elem])` applies the fragment with no parse-time tracking.
   - **Remaining: 2d — re-cut around the transform-spectrum ruling (§3).** The
     application form is now **`&s:[transform]`** (ascription, `[…]` in sort
     position), NOT `fragment(&s)`. Sub-cuts, ordinary-shapes-first as before:
     - **2d-1 LANDED (the ascription face).** `&s:[ (el:Int) -> … ]` parses
       (`AltParser.parseSpreadAscription`, hooked at the head of
       `parsePrimaryWithPostfix`) and lowers through the *same* `lowerSpreadCall` as
       the call form — `&a:[frag]` ≡ `frag(&a)`. Both map and filter work in the
       ruled syntax (`StreamFragmentTest.spreadAscription_*`). **Both forms stay**
       (RULED — James): the call form `x(&a)`/`x(&b)` is the **named-reuse** face
       (bind once, apply to many streams), the `&s:[…]` form is the **inline /
       anonymous** face — function vs lambda, two spellings of one operation,
       neither legacy. Required a grammar fix: a **line-leading `&` is the spread
       prefix, not infix conjunction** (the binary loop now breaks on a not-same-line
       `&`, mirroring the same-line postfix rule) — else a prior `let`'s value
       absorbs the next line's `&s` as `(…) & s`. (Caveat: a genuinely multi-line
       `a &\n b` conjunction must now parenthesize or stay on one line.)
     - **2d-2 LANDED (accumulators — fold/scan).** A value-arg alongside the spread
       is an accumulator seed (the total-input-marker rule James approved); output
       arity = input arity, kinds inferred from the markers (spread → STREAM, value →
       ACCUMULATOR), so **no codomain annotation needed**. The fragment returns a
       tuple, **fan-distributed** to the channels: `IrExpr.Write.FAN` (`"*"`) is a
       reserved write evaluated once per element and routed positionally
       (`IrInterpreter.routeWrite`); `AltParser.lowerSpreadCall` builds the
       multi-output `Iterate`; `SortChecker` accepts the fan write (it accounts for
       all channels by construction). `fold(&s, 0)` → `(emptyStream, total)`,
       `scan(&s, 0)` → `(runningTotals, total)` (`StreamFragmentTest`). Multi-output
       results with positional `_0.._n` names seal to a `_tuple`.
       - **`._N` read-projection RULED IN (2026-06-21, James).** Destructure-only
         would have made tuples the *only* aggregate without a read form; there's no
         reason not to have both. Removed the parser's destructure-only rejection, so
         `value._N` is an ordinary field access (the read-access sibling of
         `let [(a, b)] = …`). The canonical `fold(&s, 0)._1` now works as written.
       - **Remaining for 2d-3:** fork (fan-out — extra outputs need the codomain) and
         zip (multi-`&` — needs a multi-source `Iterate`).
     - **2d-3 (the spectrum proper, beyond streams):** non-spread ascription as
       coercion — `x:[A -> B]` (registered) and `x:[a:A -> B(…)]` (explicit), plus
       refinement+transform composition `[Int:@>0 -> Decimal]`. Unifies with the
       explicit-coercion ruling (`docs/dispatch-unification.md`, `(Type:value)`).
     The single-channel map+filter **semantics** are fully working today (slices
     2a–2c) under the `fragment(&s)` surface; 2d-1 moves them onto `&s:[…]`.
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
