# Soundness holes — a bug-squashing marathon

**Found 2026-08-24 by probing, not by a failing test.** Every item here is a program
the compiler *accepts* and should not. The suite was green throughout: 5211 tests
pass with all of this live, which is the point — these are gaps in what is checked,
so no existing test asks the question.

Three families, one of them a single line. They are ordered below by
fix-cost-to-value, not by severity.

| # | Family | Root cause | Items | Cost |
|---|--------|-----------|-------|------|
| 1 | Tuple slots are never judged | `ConstructionGate.gated()` has no `_tuple` case | 5 | one line + tests |
| 2 | Aggregate `==` crashes one engine | `Cmp.combine` falls through to `(Long)` | 6 | one branch + tests |
| 3 | The base type is never checked | `gated()` answers false for a bare primitive | 11 | needs care |

Families 1 and 3 are the *same switch*, two lines apart. Family 1 is the cheap half
and can land first; family 3 is the one with blast radius.

## The shape of the thing

Pontif proves refinements everywhere and checks base types almost nowhere.

```pontif
struct P(n:[Int:@>0])
P(0-5)              # correctly REJECTED — the refinement is proved

struct P(x:Int)
P("s")              # ACCEPTED — the base type is not
```

That asymmetry holds at *every* judgment site: constructor arguments, constructor
extension fields, function returns, method returns. The refinement half works; the
base-type half is skipped whenever the declared sort is a bare primitive. Two places
get it right and are worth studying as the model — a `let` claim
(`let n:Int = "s"` is rejected at parse time by `Assignability`) and a trait
attribute.

The consequence is not merely a missed error. Because the value is built anyway, the
two engines then disagree about what the program *means*:

```pontif
struct P(x:Int)
P("s").x + 1
# INTERPRETER → "s1"    (string concatenation)
# TRUFFLE     → runtime error: Operator applied to a String operand
```

A compiler-accepted program whose meaning depends on the engine is the clearest
possible statement that the value should never have been constructible.

## How to reproduce anything here

`AnonymousRecordSortTest` (pontif-runtime) has the harness this sweep was built on —
its `run` helper executes every `PontifRunner.Engine` and asserts they agree. Reusing
that helper for existing aggregate tests is the cheapest way to find whatever else is
hiding; it is how family 2 surfaced.

---

## Family 1 — tuple slots are never judged

`gated()` decides whether a declared sort is a claim worth checking. It has cases for
`Refined`, `Named`, `Trait`, `Union`, `Intersection`, and (since `10f175b`) an
anonymous `_record` shape — but none for `_tuple`. So a tuple sort in a declared
position constrains **nothing**: not arity, not component types, not even component
refinements, which every other site proves.

```pontif
let t:[{Int, Int}]        = {1, "s"}     # ACCEPTED — component base type
let t:[{[Int:@>0], Int}]  = {0-5, 2}     # ACCEPTED — component REFINEMENT violated
let t:[{Int, Int}]        = {1, 2, 3}    # ACCEPTED — too many slots
let t:[{Int, Int, Int}]   = {1, 2}       # ACCEPTED — too few slots
struct S(t:[{[Int:@>0], Int}])
S({0-5, 2})                              # ACCEPTED — same hole through a struct field
```

The refinement case is the sharpest: a tuple slot is the one place in the language
where `[Int:@>0]` is decoration.

**Root cause** — `ConstructionGate.gated()`
([ConstructionGate.java:727](../pontif-ir/src/main/java/sibarum/pontif/ir/ConstructionGate.java)),
the `default -> false` arm swallowing `IrSort.Structural("_tuple", …)`.

**Fix sketch.** Extend the `Structural` case that `10f175b` added for `_record` to
cover `_tuple` as well. The judgment machinery is already there and already correct:
`Assignability.structurallySubsumes` compares two `Structural` sorts member-wise
(key sets equal, then `isA` per member), which is exactly the tuple rule including
arity. `ConstructionGate.argSort()` already rebuilds an anonymous record literal's
shape; it needs the same for a tuple literal.

Note the arity check exists and is good — but only in *pattern* position
(`checkTupleArity`, with a helpful message naming the slot count). Assignment
position never calls it. Prefer routing both through the one rule rather than
writing a second arity check.

**Tests.** Mirror `AnonymousRecordSortTest`'s structure for the positional face: the
five rejections above, plus the passing cases (exact arity, satisfied refinements,
tower coercion into a slot) to prove the fix does not overshoot.

---

## Family 2 — aggregate `==` crashes the Truffle engine

Every aggregate comparison works on the interpreter and dies on Truffle:

```pontif
struct P(x:Int)
match (P(1) == P(1)) { [Bool:true] -> 1  [_] -> 0 }
# INTERPRETER → 1
# TRUFFLE     → internal ClassCastException:
#               RecordValue cannot be cast to java.lang.Long
```

Confirmed identical for a named struct, a tuple, a dictionary, a declared record
shape, a nested struct, and — most damagingly — an **enum case**:

```pontif
enum Color { Red
  Green }
Color.Red == Color.Red      # same crash
```

Enums landed two days before this sweep (`ffd0499`), and comparing two cases is the
most natural thing anyone will write with them. That makes this the highest
*practical* severity item here even though family 3 is the deeper flaw.

**Root cause** — `Cmp.combine`
([Cmp.java:94](../pontif-ast/src/main/java/sibarum/pontif/ast/binary/Cmp.java)).
It runs a typed ladder — `CharValue`, then `StringValue`, then `BigDecimal` — and
then falls through to `long l = (Long) leftValue`. A `RecordValue` reaches that cast.

**The intended behavior is already ruled**, so this is not a design question.
`IrInterpreter.dispatchOperatorSymbol` states it directly: "`==`/`!=` stay built-in
**structural equality**" — arithmetic and ordering route to user overloads, equality
does not. The interpreter implements it as `Objects.equals(l, r)`
([IrInterpreter.java:1649](../pontif-ir/src/main/java/sibarum/pontif/ir/IrInterpreter.java)),
which is structural because `RecordValue` is a record.

**Fix sketch.** In `Cmp.combine`, handle `EQ` / `NE` / `APPROX` with
`Objects.equals` before the numeric fall-through. Ordering (`< <= > >=`) on an
aggregate must stay an error — and already is, correctly, at compile time:
`P(1) < P(2)` is rejected with "Operator '<' is not defined for (P, P)". So the new
branch should cover the three equality ops only and let ordering keep failing.

**Tests.** Both-engine cases for each aggregate kind (struct, tuple, dictionary,
record shape, nested, enum case), equal and unequal, plus a pin that ordering stays
rejected. `docs/keyed.md` also promises generated `equals`/`hash` per struct — worth
checking this fix agrees with the direction recorded there before writing it.

---

## Family 3 — the base type is never checked

The deep one. `gated()` asks whether a declared sort is worth judging and answers:

```java
case IrSort.Named n -> structs.containsKey(n.name());   // ConstructionGate.java:730
```

`structs` holds *declared structs*. `Int` is not one, so a field declared `Int` is
never gated, and `classify` — which would return `DISJOINT` immediately — is never
called. The comment above `gated()` says this is deliberate ("bare-primitive
legality is decided trait-free at the parser via `Assignability`"), and for a `let`
claim that is true. For construction and returns it is not: nothing downstream makes
the check.

Everything below is ACCEPTED today:

```pontif
struct P(x:Int)      P("s").x            # positional constructor
struct P(x:Int)      P{x = "s"}.x        # by-name literal
struct P(x:Int)      let p:P = {x = "s"} # dictionary promoted to a struct
struct P(x:Int)      P(true).x           # Bool for Int
struct P(x:String)   P(3).x              # Int for String
struct P(d:Decimal)  P("s").d            # String for Decimal
struct P(c:Char)     P(3).c              # Int for Char
struct Q(a:Int) struct P(x:Int)  P(Q(1)).x   # a whole struct for Int
struct I(n:Int) struct O(i:I)    O(I(true)).i.n   # nested

function f():Int -> "s"                          # function return
function f(n:Int):Int -> match n { [@>0] -> "s"  [_] -> 0 }   # return via a match arm
struct P(x:Int)  method P.get():Int -> "s"       # method return

struct R(w:Int) ->
    let this.a:String = this.w                   # constructor EXTENSION field
```

That last one matters for the docs as well as the code: the type-system guide's
constructor-bodies section says an extension field is "judged against its type
exactly like a constructor argument." That is *literally* true — including the hole —
but a reader will take it to mean the mismatch above is caught. Fix the sentence when
the code lands, or before.

**What limits the blast radius** — and it is worth knowing before deciding urgency.
A wrongly-typed value cannot enter a function that declares the type: dispatch
refuses it (`f(P("s"))` fails to find an overload). So the bad value is *created*
freely but travels poorly. The damage is field reads, operators, and the engine
divergence above.

**Fix sketch.** Gate a bare primitive: extend the `Named` case to
`structs.containsKey(n.name()) || isPrimitive(n.name())` over the closed tower
(`Int`, `Decimal`, `Bool`, `String`, `Char`). `classify` already decides these
correctly — the sorts are disjoint, so it returns `DISJOINT` and the existing
diagnostic ("can never satisfy its declared sort … which is disjoint") is the right
message with no new error text.

**Where the care is needed.** This runs on every construction in the codebase, and
two interactions must be checked before trusting a green suite:

1. **`Int → Decimal` coercion.** `struct P(d:Decimal)` accepting `P(3)` is a ruled
   language feature, not a hole, and it survives only if `NumericCoercion` has
   inserted the `Cast` before the gate judges. **Checked — it has.** The pass order in
   `IrCompiler` is `AggregatePromotion` (:80) → `NumericCoercion` (:93) →
   `ConstructionGate` (:106), so by the time the gate sees the argument it is already
   a `Cast` to `Decimal` and classifies as FITS. This was the main risk in family 3
   and it is not one. Still pin a `P(3)`-into-`d:Decimal` test before starting, so a
   later reordering cannot silently undo it.
2. **Native constructors** already gate bare primitives (`nativeTarget` bypasses the
   leniency at `ConstructionGate.java:440`). That path is the working precedent for
   what the fix does everywhere else — read it first.

Then the three call sites at
[ConstructionGate.java:215, 440, 505](../pontif-ir/src/main/java/sibarum/pontif/ir/ConstructionGate.java)
(let claim, constructor argument, extension field) all tighten at once, and the
return-type gate in
[PontifCompiler.java:619](../pontif-runtime/src/main/java/sibarum/pontif/runtime/PontifCompiler.java)
needs the same treatment separately — it proves the return refinement but not the
return base.

**Tests.** One case per row of the table above, both engines. Plus the negative
controls that must keep passing: `P(3)` into `d:Decimal`, every legitimate
construction in the existing suite, and the `let`/trait-attribute sites that already
worked.

---

## Suggested order

1. **Family 2** first. Independent of the others, one branch, and it unbreaks enum
   equality — which someone will hit this week.
2. **Family 1** next. One line in the same switch family 3 will touch, so doing it
   first means the harder change lands against a switch that already has the shape.
3. **Family 3** last, in two steps: verify the `NumericCoercion` ordering, then gate
   the primitives and read every new failure carefully. Expect real failures here —
   a suite that has never checked base types has almost certainly accumulated tests
   that rely on not checking them, and each one is a finding rather than a nuisance.

## Relationship to the existing ledger

None of these appear in [language-inventory.md](language-inventory.md)'s BUG or
MISFIRE tables. That file is generated from the probe harness, which asks a different
question — it compares outcomes against a manifest of expectations, so a hole nobody
thought to expect stays invisible. Its three "real soundness MISFIREs"
(`destructure__18`, `methods__17`, `methods__22`) are still open and unrelated to
these; they belong in the same marathon.

The [feature-matrix](feature-matrix.md) is affected too. Its no-lie rule says a
`^^^` cell must name a passing witness — but a witness only proves what it asks, and
none of these cells were ever asked about base types. Worth a pass over the
`struct` / `function` / `method` rows against the `Nominal` column once family 3
lands.
