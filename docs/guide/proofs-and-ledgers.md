# Proofs, synthesis, and the legible ledgers

*Part of the [Pontif guide](../../README.md). This page covers what happens when a
declared claim is beyond the automatic prover — how you supply the missing
reasoning, how a function reflects into its own AST, the conservation ledger, and
the single inference engine underneath all of it. For the one-page overview, see the
root [README](../../README.md).*

A note on intent first, because it shapes everything here. **Pontif is not a proof
language.** It has no SMT solver and will never grow one by default. That is a
deliberate choice, not a missing feature: a proof is only as good as it is
*auditable*, and a hand-rolled, deterministic prover you can read end-to-end is
worth more to a working developer than an opaque oracle you must trust. The prover
is intentionally incomplete; the escape hatch (`assign proof`, or restructuring your
code) is the intended, acceptable cost. What you get in return is **predictability**
— you can tell, by reading, what the compiler will and won't prove — and
**legibility**: every ledger below is plain text you can read.

## Contents

- [Proofs and synthesis](#proofs-and-synthesis)
- [Reflecting a function into its AST](#reflecting-a-function-into-its-ast)
- [Conservation receipts — the second ledger](#conservation-receipts--the-second-ledger)
- [One inference engine, every stage](#one-inference-engine-every-stage)

## Proofs and synthesis

Most return refinements are discharged automatically by the receipt-graph engine.
When the math is genuinely beyond it, the program is *rejected* — it does not
silently pass:

```pontif
function f(x:Int):[Int:@>=-16] -> (x-3)*(x+5)
f(0)   # rejected at compile time — the engine can't prove the result is ≥ -16
```

You then supply the missing reasoning with **`assign proof`**, a case-function
that partitions the input domain and proves each region — the proof lives beside
the code, not inside it:

```pontif
function isSparse(x:Int):[Int] -> (x-3)*(x+5)

assign proof isSparse(x:Int):[
  (match x
    [@>=3]  -> this(x)
    [@<=-6] -> this(x)
    [_]     -> this(x)
  ) ->
  [Int:@ >= -16]
]

isSparse(10)   # → 105
```

The base function's return is unrefined `[Int]`; the proof *grants and proves*
`[Int:@>=-16]` by cutting the domain into regions the engine can each close (the
finite middle residual is peeled to singletons automatically).

A return refinement can **reference destructured arguments**, and a declaration
terminated with no body lets the *specification write the body*:

```pontif
struct Vec(x:Int, y:Int)

function normSq(v:[Vec.{x, y}]):[
  let s:Int = x ^ 2 + y ^ 2 ->
  Int:@==s
];

normSq(Vec(3, 4))   # → 25
```

`v:[Vec.{x, y}]` destructures the parameter into `x` and `y`; the return is an
in-type pipeline (`let`-stages then a final pin); because that pin fixes a single
value and no `->` body follows, the body is synthesized from the spec. The trailing
`;` terminates the declaration — it is not what asks for the synthesis; the pin is
(see [the type system guide](type-system.md#type-extension--a-richer-type)). The
same rule drives **value synthesis** and **function synthesis**:

```pontif
struct Point(x:Int, y:Int)
struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)

function promote(point:[Point.{x, y}], z:Int):Point3D{x, y, z};

promote(Point(2, 3), 7).x + promote(Point(2, 3), 7).y + promote(Point(2, 3), 7).z   # → 12
```

`Point3D{x, y, z}` is a construction-pin: no `->` body, so the constructor is
written from the return spec.

Two more pieces of compile-time machinery round out the metaprogramming:

```pontif
function inc(x:Int):Int -> x + 1
function twice(d:[Dispatch(Int):Int], x:Int):Int -> d(d(x))

twice($inc[Int], 5)   # → 7
```

**Metareferences** — `$inc[Int]` reifies the *dispatch* named `inc` keyed at
`Int` (the `$` quotes the name; the `[...]` give the key types). It is not a
function pointer: applying it re-runs dispatch with narrowings intact, and it can
be passed as a first-class `[Dispatch(Int):Int]` value.

```pontif
let Positive:Type[[Int:@>0]]

function step(n:Positive):Positive -> n + 1

step(5)   # → 6
```

**Reusable type aliases** — `Type[...]` names a refinement (or a union of them)
once and reuses it wherever a type annotation goes. It is the bracketed sibling of
the `Type{...}` trait form.

## Reflecting a function into its AST

A metareference isn't only a callable — when its referent is *proven algebraic* it
becomes a window onto the function's own structure. Mark a function
`assign proof f:Algebraic` and you promise its body is pure algebra: arithmetic in
one variable over `pontif.algebra`'s node set (`+ - * / ^`, constants, the
parameter). The compiler holds you to it — a body that isn't (a branch, an effect,
an unprovable shape) fails the proof.

That claim buys a capability. `$poly[Decimal]` — the metareference — now *is-a*
`Algebraic`, a trait it satisfies as an ordinary first-class **object**: it carries
an attribute `.ast` that reflects the body into a first-class `AlgExpr`, and a
method `.eval(x)` that evaluates the function at a point.

```pontif
requires pontif.algebra.{Algebraic}

function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
assign proof poly:Algebraic

$poly[Decimal].eval(3.0) == poly(3.0)   # → true
```

Calling `$poly[Decimal].eval(3.0)` treats the reference as a differentiable object;
under the hood it walks `poly`'s AST over `x = 3.0` in exact `BigDecimal`
arithmetic, and it agrees with calling `poly` directly. `.ast` and `.eval` are
members the metareference gets purely by *being* an `AlgebraicDispatch` — each was
added with only a trait declaration, no change to the type system, because a
metareference is a genuine object and not a special-cased function pointer.

`AlgExpr` is no black box — it is an ordinary trait union (`Const`, `Param`, `Add`,
`Sub`, `Mul`, `Div`, `Pow`), so you `match` on it and write your own evaluator,
simplifier, or symbolic *differentiator*:

```pontif
requires pontif.algebra.{AlgExpr, Add}

function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
assign proof poly:Algebraic

let e:AlgExpr = $poly[Decimal].ast
match e {
  [Add(_, _)] -> 1                 # poly's body ((x*x + 2.0*x) + 1.0) is rooted at `+`
  [_]         -> 0
}                                  # → 1
```

The AST is genuinely **multi-variable** — reflection mints a distinct `Param` per
argument, by name — so evaluation takes a point that binds each name. `.eval(x)` is
the one-variable convenience; `evalAt` is the general form:

```pontif
requires pontif.algebra.{evalAt}

function f(x:Decimal, y:Decimal):Decimal -> x*y + x
assign proof f:Algebraic

evalAt($f[Decimal, Decimal].ast, {x = 3.0, y = 4.0}) == f(3.0, 4.0)   # → true
```

Two honesty rules make this more than reflection. The guarantee is a **type**, not a
marker: `.ast` on a non-algebraic reference (`$inc[Int].ast`) is a *compile* error,
and it travels through parameters — a function taking `f:Algebraic` may write
`f.ast`, and only a proven-algebraic metareference type-checks as its argument. And
nested calls to other algebraic functions are **inlined** into one tree (finite —
recursion is banned by the same gate), so `.ast` always yields a self-contained AST.
The reflection primitive itself (`astOf`) is non-exported: the `Algebraic` members
(`$f[Decimal].ast` / `.eval`) are the only door in.

## Conservation receipts — the second ledger

The receipt graph proves what values *are*; the conservation ledger proves where
they *went*. Every function gets a compile-time dataflow ledger — which inputs
were consulted, combined, emitted, or silently dropped — and `proof` statements
assert algorithmic properties over it. A failing assertion is a compile error:

```pontif
requires std.conservation.{DataConservative}

struct Source(name:Int, age:Int, email:Int)
struct Target(fullName:Int, years:Int, contact:Int)

function translate(s:Source):Target ->
  {fullName = s.name, years = s.age + 1, contact = s.email}

proof translate = DataConservative()       # every Source attribute provably reaches Target

translate(Source(1, 2, 3)).years   # → 3
```

Drop `contact` from `Target` and the same program **rejects** — and the error
*is* the receipt (abridged), where `<-` records each placement:

```
Conservation proof for 'translate' failed: 's_0.email' is UNTOUCHED …
  ret_2: construct { r_0.fullName <- s_0.name, r_0.years <- c_1 }
    s_0.email   UNTOUCHED (no flow into the return)
```

Dropping data on purpose is fine — *declared*:
`proof translate = DataConservativeExcept(s.email)` makes the lossy version
compile, then fails the moment someone fixes the translation (the declaration is
now stale). Properties are values on the same `proof` statement, and the ledger
obeys the same honesty law as everything else: flow it can't trace is **OPAQUE**,
and no assertion ever passes over it. Reversibility, for instance, is a *witnessed
corollary* of a fan-in-free, fan-out-free rewiring:

```pontif
requires std.conservation.{Reversible}

function swap(p:[{Int, Bool}]):[{Bool, Int}] ->
  match p { [{a, b}] -> {b, a} }

proof swap = Reversible()          # bijective rewiring — invertibility witnessed

let [{x, y}] = swap({1, true}) y   # → 1
```

## One inference engine, every stage

Everything above — refinement, dispatch, match, the return gate — rests on one
question: *what is this value, exactly?* A type system is only as honest as the
thing that answers it, and Pontif has exactly **one** answerer: `NarrowingInference`.
It runs while parsing, while type-checking, at the return-refinement gate, and
during dispatch. The stages differ only in **what is known** — a parser hasn't
linked imports yet; the gate has the whole module — never in **how the reasoning
works**. There is no second typer to drift out of agreement with the first, which
is the failure mode where touching one corner of a type system quietly breaks
another.

What it computes is a **narrowing**: the tightest true statement about a value's
set, written as a predicate over `@`. A literal is `[Int:@==5]`; a comparison is
`[Bool:@==(x>0)]`; an arithmetic result is the *exact* value-pin `[Int:@==x+1]`.
There is no separate "bound type" and "singleton type" and "pin type" — they are
one shape, a refinement, and the engine always produces the most precise one it
can express.

A *bound* like `[Int:@>=2]` is not a rival representation — **it is a value-pin
with its out-of-scope variables eliminated.** `x+1` is `[Int:@==x+1]` everywhere
`x` is in scope; the moment it crosses a boundary where `x` no longer exists — a
return value seen by its caller, a stream element being quantified — the engine
*projects* `x` out by interval reasoning, and what survives is the bound. So the
**same expression has different, equally-correct narrowings depending on the scope
that consumes it** — and projecting only at the boundary, rather than eagerly
everywhere, is what keeps the engine both precise and small.

You can watch it work. The playground's **Narrowings** view re-emits your program
in source shape with declared types replaced by what the engine inferred — walked
from any entrypoint, each function shown specialized to how it is actually called:

```
# entrypoint: main
inc(5)

function inc(x:[Int:(@ == 5)]):[Int:(@ == 6)]    # return was: Int
  (x + 1)
```

`inc` was *declared* to return `Int`; entered via `inc(5)`, the engine pins the
argument to `5` and infers the return as exactly `6` — it evaluated the call at the
type level. (The walk borrows the receipt-graph's no-duplicate-edges discipline:
each reachable function is shown once, and a recursive call is a back-edge, never an
infinite unfolding.) It is the same idea as the receipt and conservation ledgers — a
plain-text window you can *read* — turned on the type system itself.

### Principles that aren't specific to Pontif

Four moves do most of the work, and none of them needs refinement types to be
worth borrowing:

- **One reasoner, many stages.** Don't reimplement "what is the type of this
  expression" in the parser, the checker, and the optimizer. Write it once,
  parameterize it by what is known. Stages that share a reasoner cannot disagree.
- **A coarse type is a precise one, projected.** Keep the most precise fact while
  its inputs are in scope; *widen at the boundary*, deliberately, instead of
  discarding precision eagerly. This is the instinct behind flow-typing
  (TypeScript, Kotlin, Flow), made into an explicit operation with a name.
- **Abstain, never bluff.** When it can't prove the precise fact, the engine drops
  to the honest coarse one — it never asserts a plausible-but-false widening. That
  one rule is why a Pontif error points at a real gap instead of a phantom, and why
  the language stays workable even where the prover is incomplete.
- **Make the inferred view legible.** A "show me what you concluded" mode — the
  whole inferred program, not just hover-hints — turns a type system from a black
  box into something you read. It is cheap to build on top of a single engine and
  pays back disproportionately in how the language *feels*.

---

**Full design notes:** [receipt-graph](../receipt-graph.md) ·
[conservation-receipts](../conservation-receipts.md) ·
[conservation-algebra](../conservation-algebra.md) ·
[inference-unification](../inference-unification.md) ·
[numeric-discharge](../numeric-discharge.md) ·
[differential-endofunctors](../differential-endofunctors.md)
