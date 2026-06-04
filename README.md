# Pontif Framework

An experimental typed language built on top of GraalVM's Truffle. The
type system is the star: refinements, multi-dispatch, traits, union /
intersection sorts, and narrowing-driven polymorphism all share a
single symbolic-predicate kernel — and declared types are *claims the
compiler proves or rejects*, not annotations it trusts.

## What it is

Pontif is a language for **proof-assistant-grade type correctness in
real programming**. A sort like `[Int:@>0]` is a refined type — a base
plus a symbolic predicate. Values belong to the sort exactly when the
predicate holds, and the same `SymExpr` / `Simplifier` that checks
sorts also drives multi-dispatch, trait satisfaction, match-arm
selection, and inference.

The framework ships:

- Both an **S-expression reference parser** and a richer **alt-syntax
  parser** (`pontif-parser`).
- A typed IR (`pontif-ir`) — sorts (named, refined, structural,
  function, trait, union, intersection), function/method declarations,
  trait impls, dispatch tables.
- A **module system** — file-as-module with FQN-keyed dispatch, an
  orphan rule for coherence under multi-dispatch (`CoherenceCheck`),
  and compiler-registered builtin modules (`requires std.proof`).
- A **return-verification gate** — a declared return refinement is
  proven (by the receipt-graph engine, or by an in-source `proof`) or
  the program is rejected.
- A **conservation gate** — per-function dataflow ledgers with named
  algorithmic properties (`proof translate = DataConservative()`); a drop,
  duplication, or untraceable flow the proof doesn't account for
  rejects the program (`requires std.conservation`).
- A Truffle lowering and an `IrInterpreter` (`pontif-runtime`).
- A playground for editing and running snippets (`pontif-playground`).

## A taste of the language

```pontif
struct Point(x:Int, y:Int)

let Sized:Type{
  magnitude:[Function():Int]
}

assign trait Point:Sized {
  magnitude():Int -> self.x * self.x + self.y * self.y
}

function describe(d:Sized):Int -> d.magnitude()

describe(Point(3, 4))   # → 25
```

**Narrowing handles polymorphism** — a trait-typed param (`d:Sized`)
accepts any value whose concrete type satisfies the trait, checked
through the same `:` operator that does refinement narrowing everywhere
else. There is no separate trait-dispatch machinery.

## Narrowing

Sorts narrow by predicate, dispatch selects on the narrowing, and
declared returns are proof obligations:

```pontif
function factorial(n:[Int:0])  :Int -> 1
function factorial(n:[Int:@>0]):Int -> n * factorial(n-1)

function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1

function sign(n:Int):Int -> match n {
  [@<0 ] -> -1
  [@==0] ->  0
  [@>0 ] ->  1
}

factorial(5) + inc(4) + sign(-7)   # → 124
```

What's at work:

- **Overloads dispatch on narrowings** (`[Int:0]` vs `[Int:@>0]`), with
  provable overlap rejected at registration — dispatch is unordered and
  unambiguous, unlike `match`, which is ordered with overlap allowed.
- **`inc`'s return is a claim**: `x >= 1 ⟹ x+1 > 1` is discharged by
  the integer bound engine at compile time. A false claim (or a true
  one the engine can't prove and no `proof` is supplied for) rejects
  the program — declared types never lie.
- **Match totality is the conservation rule**: every match must be
  provably total. Non-exhaustive is a compile error *with the
  uncovered witness* ("no arm covers `@ == 0`"); undecidable coverage
  requires a default arm — never a runtime gamble. `_` desugars to the
  precise complement where computable, the universal `[_]` otherwise.

Narrowing extends to the `Decimal` domain (sign, range, and
equality-up-to-scale narrows; integer-only reasoning like `>0 ⟹ >=1`
is provably quarantined from the dense domain):

```pontif
struct Account(balance:[Decimal:@>=0], rate:Decimal)

function grow(a:Account):Decimal -> a.balance * (1.0 + a.rate)

let acct = Account(100.0, 0.05)
grow(acct) ~= 105.0   # → true
```

- **Int literals promote** at Decimal-declared boundaries (lossless
  direction only); `Decimal op Int` promotes in arithmetic.
- **`~=` is approximate equality done right**: equal within one ulp at
  the working precision (DECIMAL128) — the tolerance is *derived from
  the division policy*, never configured. `~=` coincides with `==`
  wherever no rounding exists, and is rejected in sort position: the
  proof layer never forgives.

## Destructuring

Match patterns **are sorts** — there is no separate pattern DSL, so
destructuring, narrowing, and literal-pinning compose in one form:

```pontif
struct Ternion(z:Decimal, n:Decimal, w:Decimal)

method Ternion.inv():Ternion ->
  match self {
    [Ternion(z, 0, w)] -> Ternion(w, 0, z+1)     # n pinned to 0, unbound
    [Ternion(z, n, w)] -> Ternion(w, 1.0/n, z)   # bare destructure = total
  }

let [Ternion(first, second, third)] = Ternion(2, 0, 5).inv()
first + third   # → 8.0
```

The pattern vocabulary, all composable:

| Form | Means |
| --- | --- |
| `[@<0]`, `[Int:@==0]` | refinement on the scrutinee (`@` is the value) |
| `[Ternion:@.n==0]` | struct refinement — field predicates, cross-field too |
| `[Ternion]` | bare type — type matching, esp. over unions |
| `[Ternion(a, b, c)]` | destructure — binds fields (positional renames allowed) |
| `[Ternion(z, n:[@==0], w)]` | bind **and** narrow a field |
| `[Ternion(z, 0, w)]` | positional literal — constrain the field, bind nothing |
| `[Ternion(z, _, w)]` | `_` discards a slot — occupies it, binds nothing |
| `(1, true)` / `[(Int, Bool)]` | tuple — anonymous positional aggregate (value / sort) |
| `[(a, b)]` / `[(a, _, c)]` | tuple destructure — positional binders; `_` discards a slot |
| `{a = 1, b = 2}` | dictionary — anonymous by-name aggregate |
| `let d.{a, b -> bee}` | by-name decomposition — same `.{}` as `requires`/`exports`; `->` renames |

- A literal field **binds nothing** — no accidental shadowing of an
  outer name you never wrote.
- A **positional** `(...)` pattern wears the constructor's clothes, so it
  must account for **every** slot — a subset like `[Ternion(a)]` is
  *lying by omission* and is rejected. Discard the slots you don't want
  with `_` (`[Ternion(a, _, _)]`), or focus by name with a refinement
  (`[Ternion:@.n==0]`), which makes no false completeness claim. The same
  arity-total rule governs tuples.
- **Destructuring `let`** works at both expression level and top level,
  and the pattern must be *provably irrefutable*: a bare destructure is
  total by construction; a narrowed pattern is accepted only when the
  value provably satisfies it (`let [Pair(a, 0)] = Pair(3, 0)`
  compiles; `= Pair(3, 7)` is rejected at compile time).

Sum types fall out of unions + bare/destructure arms — the canonical
ADT match needs no default arm (coverage is *determined*, per the
conservation rule):

```pontif
struct Circle(r:Decimal)
struct Rect(w:Decimal, h:Decimal)

function area(s:[Circle|Rect]):Decimal -> match s {
  [Circle(r)]  -> 3.14 * r * r
  [Rect(w, h)] -> w * h
}

area(Rect(3.0, 4.0))   # → 12.00
```

## Conservation receipts

The receipt graph proves what values **are**; the conservation ledger
proves where they **went**. Every function gets a compile-time dataflow
ledger — which input attributes were *consulted* by branches, *combined*
into derived values, *emitted* into outputs, or silently dropped — and
`proof` statements assert algorithmic properties over it. A failing
assertion is a compile error:

```pontif
requires std.conservation.{DataConservative}

struct Source(name:Int, age:Int, email:Int)
struct Target(fullName:Int, years:Int, contact:Int)

function translate(s:Source):Target ->
  {fullName = s.name, years = s.age + 1, contact = s.email}

proof translate = DataConservative()       # every Source attribute provably reaches Target

translate(Source(1, 2, 3)).years   # → 3
```

Delete `contact` from `Target` and the same program **rejects** — and the
error *is* the receipt (abridged):

```
Conservation proof for 'translate' failed: 's_0.email' is UNTOUCHED …
  c_1:   s_0.age + 1   [arithmetic, recoverable]
  ret_2: construct { r_0.fullName <- s_0.name, r_0.years <- c_1 }
  classification:
    s_0.name         flows-verbatim
    s_0.age          flows-derived
    s_0.email        UNTOUCHED (no flow into the return)
```

Dropping data on purpose is fine — *declared*: `proof translate =
DataConservativeExcept(s.email)` makes the lossy version compile, and then fails
the moment someone fixes the translation (the declaration is stale) —
proofs track the code in both directions.

Conservation composes with the rest of the grid. A tuple swap is a
fan-in-free, fan-out-free verbatim placement, which is structurally
invertible — so reversibility is a *witnessed corollary*, not a feature:

```pontif
requires std.conservation.{Reversible}

function swap(p:[(Int, Bool)]):[(Bool, Int)] ->
  match p { [(a, b)] -> (b, a) }

proof swap = Reversible()          # bijective rewiring — invertibility witnessed

let [(x, y)] = swap((1, true)) y   # → 1
```

The ledger obeys the same honesty law as everything else: flow it can't
trace is **OPAQUE**, and no conservation assertion ever passes over it —
honest ignorance fails closed. Properties ship as values
(`std.conservation`) on the same `proof` statement as algebraic
`Leaf`/`Split` proofs — one statement, two ledgers, the proposition's
vocabulary picks which. See `docs/conservation-receipts.md`.

Every snippet above is pinned by `ReadmeSnippetTest` — the README
compiles. See `docs/alternative-syntax.ptf` for the canonical reference,
`docs/glossary.md` for terms, and `docs/backward-language-design.md` for
the method that produced all of this (the theory is layer zero; the
whole language is one big syntactic sugar for it).

## Modules

| Module | What it provides |
| --- | --- |
| `pontif-core` | Symbolic algebra (`SymExpr`, `Simplifier`, alpha-equivalence, substitution), the sort system (`Sort`, with refined/structural/function/union/intersection variants), refinements with BigDecimal-generalized implication, multi-dispatch (`DispatchTable`, `FunctionDecl`, `FunctionCheck`, `TraitRegistry`), `Decimals` (display + derived-tolerance `~=`), Truffle language registration. |
| `pontif-ast` | Ready-made Truffle nodes — literals (Int, Decimal, Bool), arithmetic (`+ - * / %`), comparison (incl. `~=`), let-bindings, lambdas/closures, records, field access, match, function entry/call. |
| `pontif-ir` | Typed intermediate representation (`IrExpr`, `IrStmt`, `IrSort`, `IrModule`). `AliasResolver` substitutes type aliases; `SortChecker` validates sorts, calls, trait impls, Decimal narrow shapes, and **match totality** (the conservation rule); `DecimalPromotion` promotes Int literals at Decimal boundaries; `IrCompiler` lowers to compiled functions; `TruffleLowering` emits executable Truffle nodes; `IrInterpreter` evaluates the IR directly. |
| `pontif-predicates` | Predicate-arithmetic kernel — satisfiability, complement, and bound analysis over `Int` and `Bool` domains. `PredicateArithmetic` decides single-domain coverage (used by match totality, the `_`-arm desugar, and overload-overlap); `BoundAnalysis` is the hybrid linear-bound + sign engine that powers integer discharge (integer-strictness lives here and only here). |
| `pontif-defaults` | Canonical rule-set factories for the simplifier — `DefaultRules.production()` and `DefaultRules.full()`. Owns `BoundAnalysisRules`, the in-simplifier wrapper over `BoundAnalysis.discharge`, gated to abstain on non-integer values. |
| `pontif-parser` | Two parsers sharing the same IR: a stable S-expression parser (`Parser`) for tests / reference, and the canonical alt-syntax parser (`AltParser`) for user-written Pontif code — including the destructure desugars, literal field patterns, rename binders, and destructuring `let`. |
| `pontif-receipts` | Receipt-graph subsystem — `Drafter` (deterministic source-to-obligation graph through recursive bodies, match arms, and cross-function calls), `BuiltinIssuer` + `Notary` (default issuer + refutation-only verifier), and the **domain-routed discharge**: `IntegerDischarge` (integer-strict, via `BoundAnalysis`) vs `DecimalDischarge` (dense-valid only) selected by the obligation's sort — the routing *is* the discreteness boundary. In-source `proof` declarations supply the hard cases. |
| `pontif-conservation` | The conservation ledger, derived from the sealed IR per `docs/conservation-algebra.md` — three node kinds (Computation with op-class + recoverability verdicts, Branch, Construction) with metadata on flow edges; `ConservationDrafter` (exhaustive over `IrExpr`, no default case — the standing completeness proof), `ConservationRoles` (per-branch-path role multisets; fates demoted to views), `ConservationQueries` (the sort-aware `DataConservative` under the capacity law, the `Reversible` verbatim-bijection witness, duplication — all fail-closed on residual flow), `ConservationProofs` (the `std.conservation` vocabulary), and the text reading. |
| `pontif-runtime` | The runtime entry point (`PontifCompiler`, `PontifRunner`) — parser, module linker, simplifier, IR compiler, the return-verification **and conservation** gates, and interpreter / Truffle in a single flow. `ReceiptGraphReport` / `ConservationReport` produce reviewable text renderings of a program's two ledgers. |
| `pontif-playground` | Editor + status ribbon for running snippets interactively, built on the dasum UI toolkit. |
| `pontif-demo` | Worked examples and integration tests for every layer — refinements, dispatch, traits, union/intersection, lambdas, match. |

## Status

Active experimental development. Public APIs are not yet stable —
expect breaking changes while the version reads `1.0-SNAPSHOT`.

Capabilities that work end-to-end in alt syntax:

- Refinement sorts (`[Int:@>0]`, `[Int:0|1|2]`), union and intersection
  sorts (`[Int|Bool]`, `[[Int:@>0] & [Int:@<10]]`)
- **Three numeric primitives**: `Int`, `Bool`, `Decimal`
  (BigDecimal-backed, exact `+ - *`; `/` rounds by explicit DECIMAL128
  policy; `Int / Int` truncates, paired with `%` as an
  information-conserving pair: `a == (a/b)*b + a%b`)
- **Decimal narrows** — sign, range, equality (the three; anything
  richer is rejected as "not a Decimal narrow") — proven by a
  dense-valid discharger that never touches integer-strict reasoning
- Functions and overloads, methods, instance method calls, operator
  overloading (`+ - * / % < <= > >= == !=`), static / 0-arg bare access
- **Pattern matching where patterns are sorts** — refinements, struct
  refinements, bare types, destructure with renames, per-field
  narrowing, positional literal fields, `_` slot discards (positional
  patterns are arity-total — subset patterns are rejected as lying by
  omission)
- **The aggregate grid** — one substrate, two knobs: tuples `(1, true)`
  (anonymous positional, destructure-only components), dictionaries
  `{a = 1}` (anonymous by-name), and `.{}` named decomposition unifying
  `requires` / `exports` / `let d.{a, b -> bee}` with inline `->` rename
- **The claim rule** — a declared type name bites: anonymous literals
  promote at assertion positions (`let p:Point = {x=1, y=2}` is checked
  construction), re-badging is rejected (`Vec` never passes as a
  same-shaped `Point`), questions never coerce (`match`, `==`), and
  native equality follows matching
- **Conservation receipts** — a per-function compile-time dataflow
  graph derived from the sealed IR (`docs/conservation-algebra.md`:
  Computation / Branch / Construction nodes, metadata on edges,
  per-branch-path role multisets; residual flow — lambdas, applications,
  unresolved calls — fails closed) with named properties
  (`DataConservative` under the sort-aware capacity law, `Reversible`,
  `NoDuplication`, `DataConservativeExcept`) asserted via `proof f = …`
  and gated at compile time; reviewable `*.conservation.txt` reports
- **Destructuring `let`** (expression and top level), irrefutability
  proven
- **Compile-time match totality, enforced as the conservation rule** —
  provable coverage passes, provable gaps reject with a witness,
  undecidable coverage requires a default arm
- **Return verification** — declared return refinements proven by the
  receipt-graph engine or by in-source `proof` declarations
  (struct-tree `Leaf`/`Split` proofs via `requires std.proof`), else
  rejected; per-function proof re-validation on every compile
- **Module system** — file-as-module, FQN-keyed dispatch, orphan-rule
  coherence, builtin modules, cross-module struct literals
- **Receipt-graph reviewable artifact** — `ReceiptGraphReport` emits a
  plain-text rendering of the obligation graph and per-branch discharge
  outcomes (the "show me the receipts" view); Ackermann's `[Int:@>1]`
  postcondition closes end-to-end

See `docs/TODO.md` for the active work list and parked design sketches
(approximate comparison sorts, `@` as the current concrete type,
dispatch unification — parked at Phase 0 pending real friction).

## Build and test

```
mvn clean install              # build all modules
mvn test                       # run every test in the reactor
mvn -pl pontif-demo test       # run the demo & integration tests
```

JDK 25 (sealed interfaces, records, switch pattern matching).
Maven 3.9+. GraalVM Truffle 24.1.1 pulled transitively.

## License

Pontif is dual-licensed:

- **Source code** is licensed under the
  [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
  See `LICENSE`.
- **Documentation** (everything under `docs/`, plus `README.md` and
  other text content in the repository) is licensed under the
  [Creative Commons Attribution 4.0 International License
  (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/). See
  `LICENSE-docs`.

See `NOTICE` for the full dual-license statement and attribution.
