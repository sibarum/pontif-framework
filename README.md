# Pontif Framework

High-level AST builders on top of GraalVM's Truffle framework. Bring your own parser, assemble an AST from ready-made nodes, and let the framework handle resolution, dispatch, sort checking, and execution.

## What it is

Truffle is powerful, but every literal, every binary operator, and every binding form is your problem. Pontif ships a curated toolkit of those nodes — literals, arithmetic, comparison, let/var bindings, function entry/call/registry, lambdas, closures — alongside a typed intermediate representation and a Truffle lowering. Building a small language reads like wiring components together rather than writing a Truffle compiler from scratch.

**BYO-parser.** Pontif intentionally ships no grammar and no parser. You construct ASTs programmatically (or generate them from your own front-end), then call `Pontif.eval(tree)` to run them.

## The unified pattern system

A single pattern-and-predicate language runs through every layer of the framework, so the same machinery answers questions that would normally live in separate subsystems:

- **Type interfaces.** A sort like `Nat[x > 0]` is a refined sort — a name plus a symbolic predicate. Values belong to the sort exactly when the predicate holds.
- **Function and method signatures.** Parameter sorts can be refined. `square(n: Nat[n > 0])` is a real signature, not a doc comment.
- **Multi-dispatch.** When several declarations share a name, the dispatch table picks the one whose argument refinements close under the call site. The same `SymExpr` / `Simplifier` that checks sorts also selects the overload.
- **Pattern matching.** Cases over sealed AST hierarchies and refined sorts share that predicate language; branch selection and sort checking are facets of the same question.

Because one simplifier underpins all of these, "is this value a `Nat`?", "does this call resolve?", and "which branch runs?" are the same question asked in different positions.

**Proofs are an opt-in feature, not the framing.** If you want the simplifier to *prove* a predicate at compile time — to reject impossible calls or unreachable branches — wire in the proof machinery on the declarations that need it. If you don't, the same predicate still serves as a runtime check. Nothing about the rest of the framework changes either way.

## Modules

| Module | What it provides |
| --- | --- |
| `pontif-core` | Truffle language registration (`PontifLanguage`, `Pontif.eval`), the `PontifNode` base class, source-position `Origin`, symbolic algebra (`SymExpr`, `Simplifier`, alpha-equivalence, substitution), the sort/type system (`Sort`, `RuleEngine`), refinements, and multi-dispatch (`DispatchTable`, `FunctionDecl`, `FunctionCheck`). |
| `pontif-ast` | Ready-made Truffle nodes — literals (`IntLiteral`, `Bool`), binary ops (`Add`, `Sub`, `Mul`, `Cmp`), bindings (`Let`, `Var`), and function machinery (`CallNode`, `FunctionEntryNode`, `FunctionRegistry`). |
| `pontif-ir` | A typed intermediate representation (`IrExpr`, `IrStmt`, `IrSort`, `IrModule`) with sorts, refinements, lambdas, and closures. `IrCompiler` resolves declarations and discharges sort rules; `TruffleLowering` emits executable Truffle nodes; `IrInterpreter` evaluates the IR directly. |
| `pontif-demo` | Worked examples and tests — including a positive-natural (`PosNat`) sort defined entirely by a refinement, plus end-to-end tests for dispatch, refinements, lambdas, sort rules, and Truffle execution. |

## Quick example

Build a small AST and run it:

```java
import sibarum.pontif.ast.binary.Add;
import sibarum.pontif.ast.literal.IntLiteral;
import sibarum.pontif.core.Pontif;

long result = Pontif.evalLong(
    Add.of(IntLiteral.of(2), IntLiteral.of(3))
);
// → 5
```

The same nodes compose under a parser, a code generator, or another DSL. See `pontif-demo/src/test/java` for richer examples — let-bindings, multi-dispatch, refined sorts, lambdas, and full IR-to-Truffle lowering.

## Requirements

- **JDK 25** (source and target level — sealed interfaces, records, switch pattern matching)
- **Maven 3.9+**
- **GraalVM Truffle 24.1.1** (pulled transitively)

## Build and test

```
mvn clean install              # build all modules
mvn -pl pontif-demo test       # run the demo and integration tests
```

## Coordinates

```xml
<dependency>
  <groupId>sibarum.pontif</groupId>
  <artifactId>pontif-core</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

Replace the artifact id with `pontif-ast` or `pontif-ir` as needed; the framework is a Maven multi-module project and each module is published independently.

## Status

Active development. The four modules build, the demo tests pass, and the framework is being shaped by working through small example languages. The public API is not yet stable — expect breaking changes while the version reads `1.0-SNAPSHOT`.

Planned and in-flight directions:

- **Richer feature builders** — modules and namespaces, imports/exports, exceptions, loops and streams, optionals and promises.
- **Utility kits** — arrays, lists, sets, maps, tensors, primitive data types.
- **Type-system kits** — inheritance, prototypes, mixins, generics, strict vs gradual typing.
- **Optional proof layer** — let a host language declare invariants the simplifier discharges at compile time.

See `proof-language-concept.md` and `simple-proof-example.txt` for an early sketch of how the proof layer could be exposed to a host language.

## License

Licensed under the Apache License, Version 2.0. A `LICENSE` file will be added with the next release; in the meantime the terms at <https://www.apache.org/licenses/LICENSE-2.0> apply.
