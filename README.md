# Pontif Framework

![Pontif Playground editing the Traction example](traction-ptf.png)

An experimental typed language built on GraalVM's Truffle, where **declared
types are claims the compiler proves or rejects** — never annotations it
trusts. A single symbolic-predicate engine drives refinement, multi-dispatch,
traits, pattern matching, type extension, and synthesis; they are not five
subsystems bolted together but one idea seen from different angles. The name is
the thesis: the compiler *pontificates*, and is held to its word — it is never
allowed to lie.

## Contents

- [A quick example](#a-quick-example)
- [Function dispatching with refined types](#function-dispatching-with-refined-types)
- [Structs and methods](#structs-and-methods)
- [Traits — alternative interfaces](#traits--alternative-interfaces)
- [Type extension — a richer type](#type-extension--a-richer-type)
- [Multiple polymorphism models](#multiple-polymorphism-models)
- [Generics (Type Parameters)](#generics-type-parameters)
- [Braces, Brackets, Parenthesis](#braces-brackets-parenthesis)
- [Operator overloading](#operator-overloading)
- [Proofs and synthesis](#proofs-and-synthesis)
- [Conservation receipts — the second ledger](#conservation-receipts--the-second-ledger)
- [The compiler](#the-compiler)
- [Source code explained](#source-code-explained)
- [Status](#status)
- [Build and test](#build-and-test)
- [License](#license)

## A quick example

```pontif
module ledger

struct Account(balance:[Int:@>=0])
struct Txns(amount:Int, rest:[Txns|Done])
struct Done()

method Account.deposit(n:Int):Account -> match n {
  [@>0]  -> Account(this.balance + n)
  [@<=0] -> this
}

function totalIn(ts:[Txns|Done]):Int -> match ts {
  [Txns] -> ts.amount + totalIn(ts.rest)
  [Done] -> 0
}

Account(0).deposit(totalIn(Txns(100, Txns(50, Done())))).balance   # → 150
```

Most of the language is already on this page:

- **`module ledger`** names the file's module. **`Txns` / `Done`** are an
  inductive list — a value is either a `Txns` (an amount plus the rest of the
  list) or the empty `Done`; recursion over that union is how `totalIn` folds it.
- **`struct Account(balance:[Int:@>=0])`** declares a record whose `balance`
  field is a *refined* type: an `Int` that is provably `>= 0`. The `[...]` wrap a
  type; the predicate inside is a real proof obligation, not a comment. A
  construction that can't satisfy it is a compile error.
- **`@`** is the subject of the enclosing refinement — the value being described
  or matched. In `match n { [@>0] -> … }`, `@` *is* `n`; in `[Int:@>=0]`, `@` is
  the field's value. Each refinement binds its own `@`.
- **`method Account.deposit`** is namespaced to `Account` (more below). **`this`**
  is its receiver — distinct from `@`.
- **`match`** arms *are types*: `[@>0]` and `[@<=0]` partition every `Int`, and
  the compiler checks the partition is total. `[Txns]` / `[Done]` discriminate
  the union — the canonical sum-type fold, no tag field, no default arm.

Everything below is an elaboration of these moves.

## Function dispatching with refined types

Sorts narrow by predicate, dispatch selects on the narrowing, and declared
returns are proof obligations:

```pontif
function factorial(n:[Int:0])  :[Int:@>=1] -> 1
function factorial(n:[Int:@>0]):[Int:@>=1] -> n * factorial(n-1)

function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1

function sign(n:Int):Int -> match n {
  [@<0 ] -> -1
  [@==0] ->  0
  [@>0 ] ->  1
}

factorial(5) + inc(4) + sign(-7)   # → 124
```

- **Overloads dispatch on the narrowing.** `factorial`'s two clauses are selected
  by which sort the argument satisfies (`[Int:0]` vs `[Int:@>0]`), and *provable
  overlap is rejected at registration* — multi-dispatch is unordered and
  unambiguous. (Contrast `match`, which is ordered top-to-bottom with overlap
  allowed; the two are deliberately different tools.)
- **A declared return is a claim.** `inc`'s `[Int:@>1]` says "the result exceeds
  1." The compiler discharges `x >= 1 ⟹ x + 1 > 1` at compile time: the
  **receipt-graph engine** drafts an obligation graph from the body (`Drafter`),
  an issuer closes it with sign / linear-bound / integer reasoning
  (`BuiltinIssuer`), and a notary accepts only what it cannot refute (`Notary`).
  A false claim — or a true one the engine can't prove and you supply no `proof`
  for — rejects the program. Both `factorial` clauses carry the same claim,
  `[Int:@>=1]`, and it closes *inductively*: the recursive call's own
  `[Int:@>=1]` is taken as the induction hypothesis, so `n > 0` (from the
  parameter sort) and `factorial(n-1) >= 1` together discharge
  `n * factorial(n-1) >= 1`.
- **Match totality is enforced as a conservation rule.** A non-exhaustive match
  is a compile error *with the uncovered witness*; undecidable coverage demands a
  default arm. `_` desugars to the precise complement where computable.

Narrowing extends to the `Decimal` domain (sign, range, and equality-up-to-scale
narrows; integer-only reasoning like `>0 ⟹ >=1` is provably quarantined from the
dense domain):

```pontif
struct Account(balance:[Decimal:@>=0], rate:Decimal)

function grow(a:Account):Decimal -> a.balance * (1.0 + a.rate)

let acct = Account(100.0, 0.05)
grow(acct) ~= 105.0   # → true
```

`~=` is approximate equality done right — equal within one ulp at the working
precision (DECIMAL128), a tolerance *derived from the division policy*, never
configured — and it is rejected in sort position: the proof layer never forgives.

## Structs and methods

Methods are **namespaced to their receiver, not globally dispatched**:

```pontif
struct Vec(x:Int, y:Int)

method Vec.norm():Int -> this.x * this.x + this.y * this.y

Vec(3, 4).norm()   # → 25
```

`norm` is keyed `Vec.norm` and resolved on the receiver's type. A free function
`norm(v)` and a method `v.norm()` are *different names that may coexist* — there
is no `p.m()` ≡ `m(p)` equivalence. This is Pontif's second dispatch mechanism
(localized and rigid, owned by the type/module), kept deliberately separate from
the first (free functions and operators, which are global, open, and
module-coherent).

Sum types fall out of unions plus bare/destructure match arms — coverage is
*determined*, so the canonical ADT match needs no default:

```pontif
struct Circle(r:Decimal)
struct Rect(w:Decimal, h:Decimal)

function area(s:[Circle|Rect]):Decimal -> match s {
  [Circle(r)]  -> 3.14 * r * r
  [Rect(w, h)] -> w * h
}

area(Rect(3.0, 4.0))   # → 12.00
```

Match patterns *are sorts*, so destructuring (`[Rect(w, h)]`), narrowing
(`[@>0]`), and literal-pinning (`[Rect(3.0, h)]`) all compose in one form — there
is no separate pattern DSL. A positional pattern wears the constructor's clothes
and must account for *every* slot (discard with `_`); a subset is lying by
omission and is rejected.

The same per-slot composition applies to **tuples** — a slot is pinned to a
value, bound to a name, or discarded:

```pontif
function score(p:[(Int, Int)]):Int -> match p {
  [(0, 0)] -> 0          # both slots pinned
  [(0, y)] -> y          # pin the first, bind the second
  [(x, y)] -> x * y      # bind both — the catch-all
}

score((0, 5)) + score((2, 3))   # → 11
```

The pattern stays bracketed (`[(…)]`) even though the value is written `(…)`:
`[` is never postfix in Pontif (arrays index by application), so `[…]` is
unambiguously a pattern — which an unbracketed `(…)` could not be.

## Traits — alternative interfaces

A trait is a type whose members are declared by *name* with `Type{ ... }`, and a
struct is fitted to it with `assign trait`. Those members are **methods** — named
contracts a type promises to satisfy — and typed **data attributes**.

The method form is the heart of it: a trait names method signatures, each concrete
type supplies its own implementation, and a function written against the trait
dispatches to whichever implementation the runtime value carries:

```pontif
let Greeter:Type{ greet:[Method():Int] }

struct Formal(rank:Int)
struct Casual(mood:Int)

assign trait Formal:Greeter {
  greet():Int -> this.rank + 100
}
assign trait Casual:Greeter {
  greet():Int -> this.mood
}

function announce(g:Greeter):Int -> g.greet()

announce(Formal(5)) + announce(Casual(2))   # → 107
```

`greet:[Method():Int]` is the contract — a method from the receiver alone to `Int`,
with the `this` parameter implicit. Each `assign trait` block supplies that one
type's `greet`, and `announce(g:Greeter)` accepts *any* satisfier: the call
`g.greet()` resolves to `Formal.greet` or `Casual.greet` by the concrete type the
value carries. There is no inheritance and no vtable — trait dispatch is the same
module-coherent multi-dispatch the rest of the language uses, keyed on the receiver.

Members can also be typed **data attributes** — a pure projection of the struct:

```pontif
let Heavyish:Type{ weight:[Int:@>0] }

struct Ipsum(name:Int)

assign trait Ipsum:Heavyish {
  weight:Int -> 1
}

let i = Ipsum(5)
i.weight   # → 1
```

`Ipsum` has no `weight` field, so the impl *produces* one with a `->` arrow
(`weight:Int -> 1`) — the metaprogramming that writes the member. An attribute
must be supplied **exactly once**: by a matching field *xor* a producer. The
producer is itself checked against the contract `[Int:@>0]` — a producer of `0`
is rejected, fail-closed.

Because every trait attribute is a pure projection of the underlying struct — no
independent information is added — a struct coerces to a trait it satisfies, and
back, **freely in both directions**:

```pontif
let Heavyish:Type{ weight:[Int:@>0] }

struct Ipsum(name:Int)

assign trait Ipsum:Heavyish {
  weight:Int -> 1
}

let i = Ipsum(5)
let h:Heavyish = i      # upcast — free: weight is computed, nothing is lost
let back:Ipsum = h      # downcast — free: the concrete identity was never erased
back.name               # → 5
```

This is the conservation principle lifted to polymorphism: a trait gives a type an
alternative interface *with a guaranteed return path* to the original. (A downcast
to the *wrong* concrete type — one that merely also satisfies the trait — is
rejected: the value cannot masquerade as something it isn't.)

Two receivers to keep distinct: **`this`** is a method's injected instance
(`this.weight`); **`@`** is the value under refinement in a `[...]` predicate.
They are orthogonal. (Looking ahead: a future `action` construct will be where
*mutation* lives — observed, ledgered, and proof-licensed; it is drafted in
`docs/actions.md`, not yet implemented.)

## Type extension — a richer type

The third model. Inheritance, newtypes, union supertypes, and refinement
subtyping aren't four features — they're **one construct**,
`struct Name:[Base:rel](fields)`: the brackets say what the type *is* (is-a), the
parens what it *has* (has-a):

```pontif
struct Point(x:Int, y:Int)
struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)

let p = Point3D(2, 3, 5)
let flat:Point = p                   # demote — run the morphism, forget z
let back:[Point3D:@.z==0] = flat;    # promote — merge the value with the pin

flat.x + flat.y + back.z             # → 5
```

The cast law is the no-lie law made geometric: **lose freely, fabricate never.**

- **Demotion** (`flat:Point = p`) runs `Point3D`'s declared morphism — a clean
  forget. `flat` is `Point(2, 3)`; `z` is *gone*, not hidden behind a tag.
- **Promotion** can't conjure `z`, so it's never implicit. The trailing `;` is the
  *synthesis directive*: the spec writes the body (more in
  [Proofs and synthesis](#proofs-and-synthesis)). Extension promotes you to a
  strictly richer type while every existing behavior of the base still applies.

### Explicit casts — `(Type:value)`

The same law governs coercion *across* domains. `let n:String = 12` is a type
mismatch, not a silent stringification — the fix is the explicit cast `(Type:value)`,
the value-space sibling of a refinement (`[Base:pred]` is a type, `(Type:value)` is
a value; same colon, opposite side of the type/value line):

```pontif
let n:String = (String:12)        # render is named — no silent stringification
"n=" + n                          # → "n=12"
```

This is Pontif's answer to Julia-style promotion. Implicit coercion is kept *only*
for the closed primitive tower (`Int → Decimal`, a lossless embedding you can't
extend or shadow); everything open is explicit. Because the target is named, nothing
is searched (so nothing is incomplete) and nothing is ambiguous — and a coercion that
can't be performed fails closed rather than fabricating. A cast is a dispatch feature:
it resolves `(source sort → target sort)` on the one shared engine. Today the built-in
renders to `String` ship; user-defined `Type → Type` coercions register and resolve
the same way.

## Multiple polymorphism models

Polymorphism in Pontif is three sharply-separated tools, and between them they
cover the major use cases:

- **Traits** give a type *alternative interfaces*, with a guaranteed
  information-conserving return path back to the concrete type.
- **Multi-dispatch** provides *interoperability* between types of independent
  authorship — open, symmetric, and kept coherent by the orphan rule.
- **Type extension** provides *promotion* to a strictly richer type while
  retaining all existing behavior.

None subsumes another; the type system's job is to keep them honest, not to merge
them.

## Generics (Type Parameters)

A `[type T]` slot after a name makes a function, struct, or trait *parametric*.
Pontif never erases types — every value carries its concrete type — so a generic
needs no runtime witness, no dictionary, no monomorphized copy: **the value is its
own evidence.** The parameter is derived from the value at construction and
inferred at the call.

```pontif
struct Box[type T](value:T)              # T is carried by the field
function open(b:Box[Int]):Int -> b.value
function id[type E](x:E):E -> x          # E inferred at each call

id(open(Box(7))) + id(3)                 # → 10
```

A trait can be parametric too, and a struct forwards its *own* parameter into the
trait it satisfies — so the element type rides each value, per value:

```pontif
let Container[type E]:Type{ get:[Method():E] }

struct Box[type T](value:T)

assign trait Box[type T]:Container[T] {
  get():T -> this.value
}

Box(42).get()                            # T = Int from the field → 42
```

The same parametric sort is honest in an **is-a** base, where the type argument is
*invariant*: a struct that claims it is-a `Literal[Int]` must really hold an `Int`
— not a refinement of one, and not a `Bool`.

```pontif
struct Literal[type T](value:T)
struct IntLit:[Literal[Int]:@.value==value](value:Int)

IntLit(9).value                          # → 9
```

## Braces, Brackets, Parenthesis

The brackets are not ad-hoc punctuation. The subject `@` combines with three
brackets across two arenas (a value vs. a type), and every cell is a distinct,
namable operation:

| | on a **value** | on a **type** |
| --- | --- | --- |
| **name** (access / construct) | `@.{}` — destructure fields by name | `@{}` — a type's members (`Type{ ping:[Method():Int] }`) |
| **refine** (restrict) | `@:[]` — narrow a value (`let x:[Int:@>0]`) | `@[]` — a reusable sort (`Type[[Int:@>0]]`) |
| **call** (compute) | `@:(…)` — a decision tree (`match`) | `@()` — apply / dispatch (`f(x)`, `v.m()`) |

You have already seen most of them: `[Int:@>=0]` is `@:[]`; `Type{ weight:… }` is
`@{}`; `area(s)` and `Vec(3,4).norm()` are `@()`; `match n { … }` is `@:(…)`;
`p.{x, y}` and `requires lib.{inc}` are the same `.{}` named decomposition.

**The arrow `->` is orthogonal to the grid and means one thing everywhere:
"produced by / bind-and-produce."** It appears *inside* cells — a `@{}` member's
producer (`weight -> 1`), a `@:(…)` match arm (`[@>0] -> …`), a function body, a
synthesis pipeline's `let`-stages — so its presence never tells you which cell
you're in; the bracket does. Match and trait-impl arms are **ordered**
(first-match wins; `_` is the complement). Its mirror `<-` reads "asserted
placement" — the direction the conservation ledger uses to record where a value
*lands* (you'll see it in the receipts below); as a general writing operator it is
reserved for the forthcoming property-definition language.

(Anonymous functions exist only as a vestigial form and are being retired; the
first-class *callable* you reach for instead is the metareference, `$f[T]` — see
[Proofs and synthesis](#proofs-and-synthesis).)

## Operator overloading

An operator is just a free function whose name is the operator, which makes custom
algebras read like the built-in ones:

```pontif
struct Money(cents:Int)

function +(a:Money, b:Money):Money -> Money(a.cents + b.cents)

(Money(150) + Money(75)).cents   # → 225
```

The safeguard against rogue definitions is the **coherence (orphan) rule**: an
operator (or any trait impl) may be defined only in the module that owns one of
its operand types — *one operand must be local*. A third module can't quietly
redefine `+` on types it doesn't own, so global multi-dispatch stays sane.

## Proofs and synthesis

Most return refinements are discharged automatically by the receipt-graph engine
(`inc`, above). When the math is genuinely beyond it, the program is *rejected* —
it does not silently pass:

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

A return refinement can **reference destructured arguments**, and the `;`
synthesis directive lets the *specification write the body*:

```pontif
struct Vec(x:Int, y:Int)

function normSq(v:[Vec.{x, y}]):[
  let s:Int = x ^ 2 + y ^ 2 ->
  Int:@==s
];

normSq(Vec(3, 4))   # → 25
```

`v:[Vec.{x, y}]` destructures the parameter into `x` and `y`; the return is an
in-type pipeline (`let`-stages then a final pin); the trailing `;` synthesizes the
body from the spec. The same directive drives **value synthesis** and **function
synthesis**:

```pontif
struct Point(x:Int, y:Int)
struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)

function promote(point:[Point.{x, y}], z:Int):Point3D{x, y, z};

promote(Point(2, 3), 7).x + promote(Point(2, 3), 7).y + promote(Point(2, 3), 7).z   # → 12
```

`Point3D{x, y, z}` is a construction-pin: no `->` body, just `;`, and the
constructor is written from the return spec.

Two more pieces of compile-time machinery round out the metaprogramming:

```pontif
function inc(x:Int):Int -> x + 1
function twice(d:[Dispatch(Int):Int], x:Int):Int -> d(d(x))

twice($inc[Int], 5)   # → 7
```

**Metareferences** — `$inc[Int]` reifies the *dispatch* named `inc` keyed at
`Int` (the `$` quotes the name; the `[...]` give the key sorts). It is not a
function pointer: applying it re-runs dispatch with narrowings intact, and it can
be passed as a first-class `[Dispatch(Int):Int]` value.

```pontif
let Positive:Type[[Int:@>0]]

function step(n:Positive):Positive -> n + 1

step(5)   # → 6
```

**Reusable sort aliases** — `Type[...]` names a refinement (or a union of them)
once and reuses it wherever a type annotation goes. It is the bracketed sibling of
the `Type{...}` trait form.

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

function swap(p:[(Int, Bool)]):[(Bool, Int)] ->
  match p { [(a, b)] -> (b, a) }

proof swap = Reversible()          # bijective rewiring — invertibility witnessed

let [(x, y)] = swap((1, true)) y   # → 1
```

## The compiler

Pontif is a pipeline from source text to a running program, with the proof gates
sitting between compilation and execution:

```
parse → link modules → resolve aliases → promote literals → construction gate
      → sort check → overload-overlap check → compile & simplify
      → return-refinement gate → conservation gate → lower / interpret
```

- **Two parsers, one IR.** The **reference parser** reads a stable S-expression
  syntax and is the ground truth the test suite is written against; the **Pontif
  parser** reads the surface syntax you've seen on this page. Both emit the *same*
  typed IR, so everything downstream is blind to which front end ran.
- **Gates, not warnings.** The construction gate judges every constructor argument
  three ways (provable fit → no runtime check; provable miss → compile error;
  genuine overlap → a check at the construction site). The return-refinement and
  conservation gates reject any program whose claims aren't discharged.
- **JIT via Truffle.** The IR lowers to GraalVM Truffle nodes for
  just-in-time compilation; a direct IR interpreter is the alternative execution
  path (and the two are cross-checked).
- **The IR is the stable seam.** Lowering is a *separate* phase from everything
  above it, and nothing in the IR is Truffle-specific. Today exactly one backend
  consumes the IR; a second target — a SPIR-V or C transpilation path, say — would
  be a contained addition rather than a rewrite. That's a direction, not a
  shipped feature.

Every ` ```pontif ` snippet above is pinned by `ReadmeSnippetTest` — the README
compiles, or the build fails. See `docs/alternative-syntax.ptf` for the canonical
reference, `docs/glossary.md` for terms, and `docs/backward-language-design.md`
for the method that produced all of this (the theory is layer zero; the whole
language is one big syntactic sugar for it).

## Source code explained

| Module | What it provides |
| --- | --- |
| `pontif-core` | Symbolic algebra (`SymExpr`, `Simplifier`, alpha-equivalence, substitution), the sort system (`Sort`, with refined/structural/function/union/intersection variants), refinements with BigDecimal-generalized implication, multi-dispatch (`DispatchTable`, `FunctionDecl`, `FunctionCheck`, `TraitRegistry`), `Decimals` (display + derived-tolerance `~=`), Truffle language registration. |
| `pontif-ast` | Ready-made Truffle nodes — literals (Int, Decimal, Bool), arithmetic (`+ - * / % ^`), comparison (incl. `~=`), let-bindings, records, field access, match, function entry/call. |
| `pontif-ir` | Typed intermediate representation (`IrExpr`, `IrStmt`, `IrSort`, `IrModule`). `AliasResolver` substitutes type aliases; `SortChecker` validates sorts, calls, trait impls, Decimal narrow shapes, and **match totality** (the conservation rule); `DecimalPromotion` promotes Int literals at Decimal boundaries; `IrCompiler` lowers to compiled functions; `TruffleLowering` emits executable Truffle nodes; `IrInterpreter` evaluates the IR directly. |
| `pontif-predicates` | Predicate-arithmetic kernel — satisfiability, complement, and bound analysis over `Int` and `Bool` domains. `PredicateArithmetic` decides single-domain coverage (used by match totality, the `_`-arm desugar, and overload-overlap); `BoundAnalysis` is the hybrid linear-bound + sign engine that powers integer discharge. |
| `pontif-defaults` | Canonical rule-set factories for the simplifier — `DefaultRules.production()` and `DefaultRules.full()`. Owns `BoundAnalysisRules`, the in-simplifier wrapper over `BoundAnalysis.discharge`, gated to abstain on non-integer values. |
| `pontif-parser` | Two parsers sharing the same IR: the **reference parser** (`Parser`, S-expression, stable, for tests / reference) and the **Pontif parser** (`AltParser`, the user-facing surface) — including the destructure desugars, literal field patterns, rename binders, and destructuring `let`. |
| `pontif-receipts` | Receipt-graph subsystem — `Drafter` (deterministic source-to-obligation graph through recursive bodies, match arms, and cross-function calls), `BuiltinIssuer` + `Notary` (default issuer + refutation-only verifier), and the **domain-routed discharge**: `IntegerDischarge` (integer-strict, via `BoundAnalysis`) vs `DecimalDischarge` (dense-valid only) selected by the obligation's sort. In-source `proof` / `assign proof` declarations supply the hard cases. |
| `pontif-conservation` | The conservation ledger, derived from the sealed IR per `docs/conservation-algebra.md` — three node kinds (Computation, Branch, Construction) with metadata on flow edges; `ConservationDrafter`, `ConservationRoles` (per-branch-path role multisets), `ConservationQueries` (`DataConservative`, `Reversible`, duplication — all fail-closed on residual flow), `ConservationProofs` (the `std.conservation` vocabulary), and the text reading. |
| `pontif-runtime` | The runtime entry point (`PontifCompiler`, `PontifRunner`) — parser, module linker, simplifier, IR compiler, the return-verification **and conservation** gates, and interpreter / Truffle in a single flow. `ReceiptGraphReport` / `ConservationReport` produce reviewable text renderings of a program's two ledgers. |
| `pontif-playground` | Editor + status ribbon for running snippets interactively, built on the dasum UI toolkit. |
| `pontif-demo` | Worked examples and integration tests for every layer — refinements, dispatch, traits, union/intersection, match. |

## Status

Active experimental development. Public APIs are not yet stable — expect breaking
changes while the version reads `1.0-SNAPSHOT`.

Capabilities that work end-to-end in the Pontif surface syntax:

- Refinement sorts (`[Int:@>0]`, `[Int:0|1|2]`), union and intersection sorts;
  **reusable sort aliases** (`let Positive:Type[[Int:@>0]]`)
- **Three numeric primitives** — `Int`, `Bool`, `Decimal` (BigDecimal-backed;
  `Int / Int` truncates, paired with `%` as an information-conserving pair) — plus
  `Char`
- **Decimal narrows** (sign, range, equality), proven by a dense-valid discharger
  that never touches integer-strict reasoning
- Functions and overloads, methods, operator overloading guarded by the orphan
  rule, the `^` power operator
- **Pattern matching where patterns are sorts** — refinements, struct refinements,
  bare types, destructure with renames, per-field narrowing, positional literal
  fields, `_` slot discards (positional patterns are arity-total)
- **The aggregate grid** — tuples, dictionaries, and `.{}` named decomposition
  unifying `requires` / `exports` / by-name `let`
- **Three polymorphism models** — traits with **DATA attributes** and free
  bidirectional struct↔trait coercion; module-coherent multi-dispatch;
  `struct Name:[Base:rel](fields)` type extension (demote freely, promote by
  synthesis — *lose freely, fabricate never*)
- **Synthesis from the spec** — the trailing `;` directive (value pins,
  construction pins, in-type `let`-pipelines)
- **Metareferences** — `$f[Sorts]` first-class dispatch references
- **Compile-time match totality**, **return verification** (automatic
  receipt-graph discharge or in-source `proof` / `assign proof`), and
  **conservation receipts** (`DataConservative`, `Reversible`, `NoDuplication`,
  `DataConservativeExcept`) — all gated at compile time, with reviewable text
  reports
- **Module system** — file-as-module, FQN-keyed dispatch, orphan-rule coherence,
  builtin modules (`std.proof`, `std.stream`, `std.conservation`), cross-module
  struct literals

See `docs/TODO.md` for the active work list and parked design sketches.

## Build and test

```
mvn clean install              # build all modules
mvn test                       # run every test in the reactor
mvn -pl pontif-demo test       # run the demo & integration tests
```

JDK 25 (sealed interfaces, records, switch pattern matching). Maven 3.9+. GraalVM
Truffle 24.1.1 pulled transitively.

## License

Pontif is dual-licensed:

- **Source code** is licensed under the
  [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
  See `LICENSE`.
- **Documentation** (everything under `docs/`, plus `README.md` and other text
  content in the repository) is licensed under the
  [Creative Commons Attribution 4.0 International License
  (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/). See `LICENSE-docs`.

See `NOTICE` for the full dual-license statement and attribution.
