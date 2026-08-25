# Soundness holes — the marathon, run

**Found 2026-08-24 by probing, not by a failing test. Closed 2026-08-25.** Every item
here was a program the compiler *accepted* and should not. The suite was green throughout
the finding: 5211 tests passed with all of this live, which was the point — these were
gaps in what is checked, so no existing test asked the question.

Three families were catalogued. Fixing them surfaced three more that nothing had ever
asked about either, each found the same way: a hole closes, a legitimate program starts
failing, and the reason it fails turns out to be a second bug. All six are closed; the
suite is green at 2670 across the reactor, with 54 new tests that ask the questions.

| # | Family | Root cause | Items | State |
|---|--------|-----------|-------|-------|
| 1 | Tuple slots are never judged | the positional face reached the gate as a bare `_tuple` head | 5 | **closed** |
| 2 | Aggregate `==` crashes one engine | `Cmp.combine` fell through to `(Long)` | 6 | **closed** |
| 3 | The base type is never checked | `gated()` answered false for a bare primitive | 11 | **closed** |
| 4 | String `+` inferred as `Int` | `inferBinOp` guarded Decimal and user types, not String | — | **closed** |
| 5 | String `+` crashes one engine | Truffle's `Add` had no String branch at all | — | **closed** |
| 6 | The effective-sort lens merged files | keyed by `(line, column)` with no source | — | **closed** |

## The shape of the thing

Pontif proved refinements everywhere and checked base types almost nowhere.

```pontif
struct P(n:[Int:@>0])
P(0-5)              # correctly REJECTED — the refinement is proved

struct P(x:Int)
P("s")              # was ACCEPTED — the base type was not
```

That asymmetry held at *every* judgment site: constructor arguments, constructor
extension fields, function returns, method returns. The refinement half worked; the
base-type half was skipped whenever the declared sort was a bare primitive. Two places
got it right and were the model the rest now follow — a `let` claim and a trait
attribute.

The consequence was not merely a missed error. Because the value was built anyway, the
two engines then disagreed about what the program *meant*:

```pontif
struct P(x:Int)
P("s").x + 1
# INTERPRETER → "s1"    (string concatenation)
# TRUFFLE     → runtime error: Operator applied to a String operand
```

A compiler-accepted program whose meaning depends on the engine is the clearest possible
statement that the value should never have been constructible. That test — *do the two
engines agree?* — found four of the six families here, and every new test below runs on
both engines and asserts they do.

---

## Family 1 — tuple slots are never judged

`gated()` decides whether a declared sort is a claim worth checking. A tuple sort in a
declared position constrained **nothing**: not arity, not component types, not even
component refinements, which every other site proves.

```pontif
let t:[{Int, Int}]        = {1, "s"}     # was ACCEPTED — component base type
let t:[{[Int:@>0], Int}]  = {0-5, 2}     # was ACCEPTED — component REFINEMENT violated
let t:[{Int, Int}]        = {1, 2, 3}    # was ACCEPTED — too many slots
let t:[{Int, Int, Int}]   = {1, 2}       # was ACCEPTED — too few slots
struct S(t:[{[Int:@>0], Int}])
S({0-5, 2})                              # was ACCEPTED — same hole through a struct field
```

The refinement case was the sharpest: a tuple slot was the one place in the language
where `[Int:@>0]` was decoration.

**Root cause — not a missing rule but a shape mismatch.** The obvious diagnosis was the
missing `_tuple` case in `ConstructionGate.gated()`, and adding it changed nothing.
`NarrowingInference` gives a NAMED record its field-conjunct refinement (`[P:@.x==1]`) —
the form where the *name* carries the shape and the conjuncts only add pins — and a tuple
literal is a named record, stamped `_tuple`. But `_tuple` names no shape, so after
`stripRefinement` the argument was a bare `_tuple` head, which is reflexively is-a every
tuple sort whatsoever. The by-name face escaped this only because `infer` abstains on a
null typeName and the structural floor was reached instead.

**What landed.** `ConstructionGate.anonymousShapeOf` reads an anonymous literal's own
member-wise shape, bypassing `infer` for exactly this reason, and `effectiveArg` prefers
it when the declared sort is an anonymous shape. `Assignability.structurallySubsumes` then
compares the two shapes member-wise — key sets first, which for keys `_0 .. _n` IS the
arity rule, so there is one arity check rather than a second one written for assignment
position. Both faces now take the same road.

`NumericCoercion` had the identical `_record`/`_tuple` asymmetry one pass earlier, and
judging the slot is what exposed it: `let t:[{Decimal, Int}] = {3, 2}` had been accepted
*and* left an Int in the Decimal slot, printing `3` rather than `3.0`. The slot is a
declared value boundary in either spelling; it now coerces in both.

**Tests.** `AnonymousTupleSortTest` (17), shaped as a sibling of `AnonymousRecordSortTest`
— the five rejections, the passing cases, and `3.0` asserted rather than mere acceptance.

---

## Family 2 — aggregate `==` crashes the Truffle engine

Every aggregate comparison worked on the interpreter and died on Truffle:

```pontif
struct P(x:Int)
match (P(1) == P(1)) { [Bool:true] -> 1  [_] -> 0 }
# INTERPRETER → 1
# TRUFFLE     → internal ClassCastException: RecordValue cannot be cast to java.lang.Long
```

Confirmed identical for a named struct, a tuple, a dictionary, a declared record shape, a
nested struct, and — most damagingly — an **enum case**. Enums had landed two days before
the sweep, and `Color.Red == Color.Red` is the most natural thing anyone writes with one.

**Root cause** — `Cmp.combine` ran a typed ladder (`CharValue`, `StringValue`,
`BigDecimal`) and then fell through to `long l = (Long) leftValue`.

The intended behavior was already ruled in two places that agree, so this was never a
design question: `IrInterpreter.dispatchOperatorSymbol` ("`==`/`!=` stay built-in
**structural equality**" — arithmetic and ordering route to user overloads, equality does
not) and `docs/keyed.md` ("Native `==` on structs is structural + nominal
(`RecordValue.equals`: same typeName + same members)").

**What landed.** `Cmp.combine` handles `EQ`/`NE`/`APPROX` with `Objects.equals` before the
numeric fall-through — the same line the interpreter has always run. Ordering stays
Int-only and an aggregate `<` stays a compile error ("Operator '<' is not defined for
(P, P)"), which is the boundary the new tests pin.

**Tests.** `AggregateEqualityTest` (16), both engines, one per aggregate kind equal and
unequal, plus the ordering pin and the scalar-ladder negative controls.

---

## Family 3 — the base type is never checked

The deep one. `gated()` asked whether a declared sort was worth judging and answered
`structs.containsKey(n.name())` — and `Int` is not a declared struct, so a field declared
`Int` was never gated and `classify`, which returns `DISJOINT` immediately, was never
called. All eleven of these were accepted:

```pontif
struct P(x:Int)      P("s").x            # positional constructor
struct P(x:Int)      P{x = "s"}.x        # by-name literal
struct P(x:Int)      let p:P = {x = "s"} # dictionary promoted to a struct
struct P(x:Int)      P(true).x           # Bool for Int
struct P(x:String)   P(3).x              # Int for String
struct P(d:Decimal)  P("s").d            # String for Decimal
struct P(c:Char)     P(3).c              # Int for Char
struct Q(a:Int) struct P(x:Int)  P(Q(1)).x        # a whole struct for Int
struct I(n:Int) struct O(i:I)    O(I(true)).i.n   # nested

function f():Int -> "s"                          # function return
function f(n:Int):Int -> match n { [@>0] -> "s"  [_] -> 0 }   # return via a match arm
struct P(x:Int)  method P.get():Int -> "s"       # method return

struct R(w:Int) ->
    let this.a:String = this.w                   # constructor EXTENSION field
```

**What landed — and the one design call.** The first attempt was the obvious one: gate
primitives like anything else. That produced 28 failures, and reading them split cleanly
in two. Seven were legitimate programs where inference simply abstains (`Vec(a / b)`, a
method result resolved later in the pass order) — the gate was demanding a *proof* of
something with nothing to prove.

So the rule is **the provable miss only**. A bare base carries no predicate; DISJOINT is
still decidable, and that half bites while the other stays silent. This is the §1d rule
read the other way: §1d forbids deferring an unprovable *refinement* to a runtime stamp,
and a bare base has no refinement to defer.

The remaining failures were findings, not nuisances — families 4 and 6 below, plus one
test with `struct Status(text:Str)`, a type that does not exist.

**Where the care was needed, both checked.** `Int → Decimal` is a ruled feature and
survives because `NumericCoercion` inserts the `Cast` before the gate judges
(`AggregatePromotion` → `NumericCoercion` → `ConstructionGate`); `BaseTypeGateTest` now
pins that ordering, so a later reorder fails there rather than silently. Native
constructors already gated bare primitives and were the working precedent the fix
generalizes.

**The return half.** `PontifCompiler.firstUnprovableReturn` proves the return *refinement*
via the receipt graph and never asked the base question. A `gateReturn` in
`ConstructionGate` now judges every TAIL position — through match arms, lets and emits, so
a match arm returning a String from an `:Int` function is caught one level in.

Its scope is deliberately narrow: **both sides a bare primitive**. Unlike a constructor
argument, a return position is reached through desugars that run *after* this pass — a
decomposition `let d.{a, b}` becomes one declaration per name whose body is still the
whole source, a param-conversion clause is applied by a prologue, a type variable's
binding is a call-site fact — so the tail expression there is often not yet the value the
function returns, and judging it reports a lie the program does not tell. The closed
scalar tower is the fragment with none of that structure. That closes the hole and does
not pretend to more; see **What is still open** below.

**Also fixed here.** `ConstructionGate.baseName` returned the literal `"_"` for a refined
unknown base, so `[_:@<=0]` (what a self-recursive method's tail infers to) was judged as
a named type nothing else is. `classify`'s own contract says `_` must read as unknown
("never delegate a guess to the engine"); the bare arm did it and the refined arm did not.

**Tests.** `BaseTypeGateTest` (21) — one per row above, plus the negative controls: the
`Int → Decimal` pin, matching base types, a widen into a field, an abstaining argument
sort compiling fine, and string concatenation at a `String` boundary.

---

## Family 4 — String `+` inferred as `Int`

Found by family 3: thirteen conductor and conduit tests started failing with
*"Constructor argument 'text' of 'StdOut' can never satisfy its declared sort String — the
argument's sort is Int"*, over `emit StdOut("" + this.count)`.

`"" + this.count` is String concatenation, so it is a String. `NarrowingInference.inferBinOp`
had guards for a Decimal operand and for a user-type operand, and no sibling for String —
so it minted `[Int:@== "" + count]`, a pin whose *base* was a lie. Nothing checked it,
because nothing checked base types.

**What landed.** A String operand wins, checked before Decimal — the same precedence
`IrInterpreter.evalBinOp` runs — and a Char operand abstains rather than claiming a base.
The three near-copies of "is this operand a Decimal?" became one `isPrimitiveOperand(e,
ctx, base)`, which also learned to read a field access's declared sort.

---

## Family 5 — String `+` crashes the Truffle engine

Found by family 4's tests: writing the *correct* program exposed that `"n=" + 3` produced
`"n=3"` on the interpreter and failed closed on Truffle with "strings order and compare;
they don't compute". Family 2's shape exactly, on the single most common String operation
in the language, and pinned in place by a test asserting the Truffle error was correct.

**What landed.** `Add` opts into `acceptsString()` and concatenates, rendering the other
operand canonically. The rendering itself moved to `sibarum.pontif.core.types.CanonicalText`,
below both engines, because the interpreter renders in `pontif-ir` and Truffle's `Add`
renders in `pontif-ast` and neither can see the other — one renderer is what keeps them
from disagreeing about the *output* next. `StringAltTest.truffleBackend_agreesOnComparisonAndConcatenation`
now asserts agreement where it used to assert the divergence.

---

## Family 6 — the effective-sort lens merged files

The loudest failure of the family-3 attempt was `'pontif.math/asin' returns a value that
can never satisfy its declared return sort Decimal — the value's sort is String`, over

```pontif
function asin(x:Decimal):Decimal -> 0.0
```

There is no String in that function. `Origin.Span` is `(start, end)` positions with **no
source file**, and `EffectiveSortLens` built one `Map<Span, IrSort>` per compiled module —
so in a linked multi-file module, `pontif.math`'s `14:37` and a user file's `14:37` were
one entry, and a position could be judged against a sort read out of a different file
entirely. Silent, and invisible to any single-file test.

**What landed.** The lens is keyed by the whole `Origin` — source plus span.

**A related collision, same key.** The walk records a parent before its children, so two
nodes sharing one span meant the child overwrote the parent. Two parser desugars stamped
synthesized nodes with a borrowed source span — the S6 promotion's field reads
(`mergePartialWithPin`) and the clause-chain `Apply` (`applyReturnClause`) — which made a
field's effective sort read as the whole target struct's, and a conversion's *result* sort
read as its *input* sort. `collectEffectiveSorts` already documents that it omits
synthesized nodes; both now carry `Origin.NONE` so it can.

---

## What is still open

- **The return base check is primitives-only.** Extending it to struct, alias, shape and
  type-variable returns needs the return-position desugars (decomposition lets,
  param-conversion clauses) to run *before* `ConstructionGate`, or the gate to run after
  them. That is a pass-ordering change, not a rule change, and it is the natural next step.
- **An unregistered type name in a field is not an error.** `struct Status(text:Str)`
  compiles, with `Str` naming nothing. The gate now catches the *consequence* at a call
  site; the declaration itself should not have been accepted.
- **`(String:value)` has no Truffle lowering.** It throws an explicit "not yet
  implemented" — a stated gap rather than a divergence, so it is not in the table above,
  but family 5 makes it the obvious sibling to finish.
- The three "real soundness MISFIREs" in [language-inventory.md](language-inventory.md)
  (`destructure__18`, `methods__17`, `methods__22`) are unrelated to these and still open.

## How this was found, and how to find the rest

`AnonymousRecordSortTest` had the harness the whole sweep was built on: a `run` helper
that executes every `PontifRunner.Engine` and asserts they agree. Four of the six families
here are cases where the engines disagreed, and every new suite uses that helper. Reusing
it on existing aggregate tests is still the cheapest way to find whatever else is hiding.

The [feature-matrix](feature-matrix.md) is worth a pass now. Its no-lie rule says a `^^^`
cell must name a passing witness — but a witness only proves what it asks, and none of
these cells were ever asked about base types.
