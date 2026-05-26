# Pontif Framework

An experimental typed language built on top of GraalVM's Truffle. The
type system is the star: refinements, multi-dispatch, traits, union /
intersection sorts, and narrowing-driven polymorphism all share a
single symbolic-predicate kernel.

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
- A Truffle lowering and an `IrInterpreter` (`pontif-runtime`).
- A playground for editing and running snippets (`pontif-playground`).

## A taste of the language

```pontif
struct Point(x:Int, y:Int)

method Point.+(p:Point):Point ->
  Point(self.x + p.x, self.y + p.y)

let Addable:Type{
  +:[Function(self):self]
}
assign trait Point:Addable {
  +(p:Point):Point -> Point(self.x + p.x, self.y + p.y)
}

function double(p:Point):Point -> p + p

double(Point(3, 4))   # → Point(6, 8)
```

What's at work above, in roughly the order it appears:

- **Structs** with field sorts.
- **Methods** namespaced under a type, with operator-character names
  (overloaded `+`, `==`, etc.).
- **Traits** declared as a kind-of-sort (`Type{...}`) and assigned to
  types via `assign trait T:Tr {...}`. **Narrowing handles
  polymorphism** — a trait-typed param accepts any value whose
  concrete type satisfies the trait, checked through the same `:`
  operator that does refinement narrowing everywhere else.
- **Refinement sorts** (`[Int:@>0]`) for narrow types.
- **Union and intersection sorts** at the bracket level (`[Int|Bool]`,
  `[[Int:@>0] & [Int:@<10]]`) with same-base normalization at parse
  time.

See `docs/alternative-syntax.ptf` for the canonical reference (and
`docs/glossary.md` for terms).

## Modules

| Module | What it provides |
| --- | --- |
| `pontif-core` | Symbolic algebra (`SymExpr`, `Simplifier`, alpha-equivalence, substitution), the sort system (`Sort`, with refined/structural/function/union/intersection variants), refinements, multi-dispatch (`DispatchTable`, `FunctionDecl`, `FunctionCheck`, `TraitRegistry`), Truffle language registration. |
| `pontif-ast` | Ready-made Truffle nodes — literals, arithmetic, comparison, let-bindings, lambdas/closures, records, field access, match, function entry/call. |
| `pontif-ir` | Typed intermediate representation (`IrExpr`, `IrStmt`, `IrSort`, `IrModule`). `AliasResolver` substitutes type aliases; `SortChecker` validates sorts, calls, and trait impls; `IrCompiler` lowers to compiled functions; `TruffleLowering` emits executable Truffle nodes; `IrInterpreter` evaluates the IR directly. |
| `pontif-predicates` | Predicate-arithmetic kernel — complement, intersection, satisfiability over a sort's domain. Used by the alt parser's `_`-arm desugar and (eventually) overload-overlap checks. |
| `pontif-parser` | Two parsers sharing the same IR: a stable S-expression parser (`Parser`) for tests / reference, and the canonical alt-syntax parser (`AltParser`) for user-written Pontif code. |
| `pontif-receipts` | Receipt-graph subsystem — `Drafter` (deterministic graph builder), receipt data shapes for the eventual notary / issuer story. Currently a regression-test slice; paused pending dispatch inference. |
| `pontif-runtime` | The runtime entry point (`PontifCompiler`, `PontifRunner`) — wires the parser, simplifier, IR compiler, and interpreter / Truffle into a single `eval(src) → Object` flow. |
| `pontif-playground` | Editor + status ribbon for running snippets interactively, built on the dasum UI toolkit. |
| `pontif-demo` | Worked examples and integration tests for every layer — refinements, dispatch, traits, union/intersection, lambdas, match. |

## Status

Active experimental development. Public APIs are not yet stable —
expect breaking changes while the version reads `1.0-SNAPSHOT`.

Capabilities that work end-to-end in alt syntax:

- Refinement sorts (`[Int:@>0]`, `[Int:0|1|2]`)
- Struct declarations, struct literals (positional `Point(1, 2)` and
  by-name `Point{x=1, y=2}`)
- Top-level `let` (inferred or annotated sort) and in-expression
  `let X = value BODY` (with optional `{ ... }` block wrapper)
- Functions and overloads, methods, instance method calls, operator
  overloading (`+ - * < <= > >= == !=`), static / 0-arg-function bare
  access
- Pattern matching with `_` default arm desugaring
- Traits via `let Trait:Type{...}` / `assign trait T:Trait { ... }`
  with trait-typed parameters and trait-method dispatch
- Union and intersection sorts at the bracket level (`[Int|Bool]`,
  `[[Int:@>0] & [Int:@<10]]`) with same-base normalization

See `docs/TODO.md` for the active work list. The current priority is
**dispatch inference at compile time** — overload-overlap checks and
call-site narrowing propagation, which unblocks the paused
receipt-graph subsystem.

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
