# Architecture & craft — how Pontif is built, and why

*Part of the [Pontif guide](../../README.md). This page is for the reader who has
decided Pontif is worth understanding from the inside: the compiler pipeline, why it
runs on GraalVM, why there's an IR seam before any backend, the module map of the
source tree, and a few of the small deliberate details that add up to the whole. For
the one-page overview, see the root [README](../../README.md).*

## Contents

- [The compiler pipeline](#the-compiler-pipeline)
- [Why GraalVM](#why-graalvm)
- [The IR is the stable seam](#the-ir-is-the-stable-seam)
- [Source code, module by module](#source-code-module-by-module)
- [The craft — details chosen on purpose](#the-craft--details-chosen-on-purpose)

## The compiler pipeline

Pontif is a pipeline from source text to a running program, with the proof gates
sitting between compilation and execution:

```
parse → link modules → resolve aliases → promote literals → construction gate
      → type check → overload-overlap check → compile & simplify
      → return-refinement gate → conservation gate → lower / interpret
```

- **The parser's only job is shape.** Parsing produces a typed IR and nothing
  downstream ever sees source text again — every gate, every backend, and both
  execution engines work on the IR alone. Surface syntax can move without any of
  them noticing.
- **Gates, not warnings.** The construction gate judges every constructor argument
  three ways (provable fit → no runtime check; provable miss → compile error;
  genuine overlap → a check at the construction site). The return-refinement and
  conservation gates reject any program whose claims aren't discharged.
- **A namespace can span many files.** Every `.ptf` file sharing a `module a.b`
  header merges into one module (Go-package style, folder-agnostic), so
  same-namespace files are mutually visible with no `requires`. The merge policy is
  isolated in one pure seam (`NamespaceAssembler.merge`), so the strategy can change
  without touching the loaders.

## Why GraalVM

Pontif runs on GraalVM's Truffle framework, and the choice is load-bearing rather
than incidental:

- **JIT for free, and a good one.** A Truffle interpreter is partially evaluated by
  the Graal compiler into optimized machine code — you write an AST interpreter and
  get a competitive JIT without hand-writing a compiler backend. For a language whose
  value is in its *type system* rather than its raw throughput, that is exactly the
  right place to spend someone else's engineering.
- **Native images.** The same GraalVM toolchain compiles the CLI and the editor to
  standalone native binaries (no JVM startup, no warm-up) — so `pontif` and
  `pontif-editor` ship as real executables. The runtime and the tooling get this from
  one platform decision.
- **A polyglot floor.** Truffle's language-interop substrate is a foundation Pontif
  can build across later without re-platforming.

A direct IR interpreter runs alongside the Truffle path, and the two are
**cross-checked** — a second, simpler execution engine that keeps the fast one
honest.

## The IR is the stable seam

Lowering is a *separate* phase from everything above it, and **nothing in the IR is
Truffle-specific.** Source compiles to a typed intermediate representation; the proof
gates run on that IR; and only then does a backend consume it. That decoupling isn't
theoretical — it has already paid off more than once:

- the IR lowers to **GraalVM Truffle nodes** for JIT execution (the default path);
- it lowers to **GLSL** — an SDF shape becomes a raymarch shader's `map` function;
- it lowers to **SPIR-V / Vulkan** — an `on Gpu` iteration becomes a SuperVast
  compute kernel.

Each backend was a *contained addition* in its own opt-in module, not a rewrite of
the language. What began as "we should keep lowering separate" is now three shipped
backends sharing one front end and one proof layer. The IR is where that leverage
lives.

## Source code, module by module

| Module | What it provides |
| --- | --- |
| `pontif-core` | Symbolic algebra (`SymExpr`, `Simplifier`, alpha-equivalence, substitution), the type system (`Sort`, with refined/structural/function/union/intersection variants), refinements with BigDecimal-generalized implication, multi-dispatch (`DispatchTable`, `FunctionDecl`, `FunctionCheck`, `TraitRegistry`), `Decimals` (display + derived-tolerance `~=`), Truffle language registration. |
| `pontif-ast` | Ready-made Truffle nodes — literals (Int, Decimal, Bool), arithmetic (`+ - * / % ^`), comparison (incl. `~=`), let-bindings, records, field access, match, function entry/call. |
| `pontif-ir` | Typed intermediate representation (`IrExpr`, `IrStmt`, `IrSort`, `IrModule`). **`NarrowingInference` is the single inference engine** — every stage (parse, type-check, return gate, dispatch) decides a value's narrowing through it, over a stage-appropriate `InferenceContext`; `inferFloor` adds the coarse-base fallback for the totality/field-existence consumers, and `closeOver` projects a value-pin to a variable-free bound at scope boundaries. `IrSourceReflector` re-emits the IR as source-shaped text with declared types replaced by inferred narrowings (the playground's Narrowings view), walked from a variable entrypoint with shallow call-site specialization. `AliasResolver` substitutes type aliases; `SortChecker` validates types, calls, trait impls, Decimal narrow shapes, and **match totality** (the conservation rule); `DecimalPromotion` promotes Int literals at Decimal boundaries; `IrCompiler` lowers to compiled functions; `TruffleLowering` emits executable Truffle nodes; `IrInterpreter` evaluates the IR directly. |
| `pontif-predicates` | Predicate-arithmetic kernel — satisfiability, complement, and bound analysis over `Int` and `Bool` domains. `PredicateArithmetic` decides single-domain coverage (used by match totality, the `_`-arm desugar, and overload-overlap); `BoundAnalysis` is the hybrid linear-bound + sign engine that powers integer discharge. |
| `pontif-defaults` | Canonical rule-set factories for the simplifier — `DefaultRules.production()` and `DefaultRules.full()`. Owns `BoundAnalysisRules`, the in-simplifier wrapper over `BoundAnalysis.discharge`, gated to abstain on non-integer values. |
| `pontif-parser` | Source text → IR: the lexer and parser for the surface syntax, including the destructure desugars, literal field patterns, rename binders, and destructuring `let`. |
| `pontif-receipts` | Receipt-graph subsystem — `Drafter` (deterministic source-to-obligation graph through recursive bodies, match arms, and cross-function calls), `BuiltinIssuer` + `Notary` (default issuer + refutation-only verifier), and the **domain-routed discharge**: `IntegerDischarge` (integer-strict, via `BoundAnalysis`) vs `DecimalDischarge` (dense-valid only) selected by the obligation's type. In-source `proof` / `assign proof` declarations supply the hard cases. |
| `pontif-conservation` | The conservation ledger, derived from the sealed IR per `docs/conservation-algebra.md` — three node kinds (Computation, Branch, Construction) with metadata on flow edges; `ConservationDrafter`, `ConservationRoles` (per-branch-path role multisets), `ConservationQueries` (`DataConservative`, `Reversible`, duplication — all fail-closed on residual flow), `ConservationProofs` (the `std.conservation` vocabulary), and the text reading. |
| `pontif-runtime` | The runtime entry point (`PontifCompiler`, `PontifRunner`) — parser, module linker, simplifier, IR compiler, the return-verification **and conservation** gates, and interpreter / Truffle in a single flow. Owns the `Extensions` mechanism and the default builtins installed through it — `IoExtension` (`pontif.events`: `emit` sinks `StdOut`/`StdErr`, `stdin`), `MathExtension` (`pontif.math`), `MathExtExtension` (`pontif.math.ext`), and `AlgebraExtension` (`pontif.algebra`). `ReceiptGraphReport` / `ConservationReport` produce reviewable text renderings of a program's two ledgers, and `ReflectionReport` renders the inferred-narrowings ("Narrowings") view from any entrypoint. |
| `pontif-builtin-gui` | The GUI + plotting extensions — `GuiExtension` (`pontif.gui`) and `PlotExtension` (`pontif.plot`), bridged onto the author's dasum flexbox/OpenGL toolkit via `DasumBridge`. |
| `pontif-builtin-shape` | The 3D-shape extension — `ShapeExtension` (`pontif.shape`): SDF primitives + transforms + boolean CSG + attribute fields, viewed by `render` (GPU raymarch) or `previewGradientField`. |
| `pontif-playground` | **Pontif Editor** — editor + status ribbon for running snippets interactively, built on the dasum UI toolkit; its **Run GUI** launches a program through `pontif-builtin-gui`'s `GuiLauncher`. |
| `pontif-cli` | The **`pontif`** command-line tool — `run`, `pack`, `console`, `new`, `editor` — over the `pontif-runtime` compile/run surface. picocli-based; runs on the JVM and as a GraalVM native image. |
| `pontif-demo` | Worked examples and integration tests for every layer — refinements, dispatch, traits, union/intersection, match. |

## The craft — details chosen on purpose

Some of Pontif's choices look arbitrary or even backwards.

- **The editor's syntax highlighter colors the wrong things — on purpose.** Every
  *keyword* is grey; the color goes to the **names you chose**, each tinted by a hash
  of its own text. The usual scheme paints the language's vocabulary in bright colors
  and leaves your identifiers monochrome — which is exactly backwards, because the
  keywords are the part you already know. Hashing names to stable colors means a given
  identifier is the same hue everywhere it appears, so you track `balance` or `Account`
  across a function by color, and the fixed grammar recedes into the background where it
  belongs. The highlighter is optimizing for *reading your program*, not for
  advertising the language.

- **Two execution engines, kept in agreement.** The IR interpreter isn't a legacy path
  waiting to be deleted; it exists so the Truffle backend has something to be
  cross-checked against. A second, dumb-simple evaluator is one of the cheapest forms of
  assurance you can build.

- **The documentation is pinned by the build.** Every ` ```pontif ` snippet in the README
  and these guide pages is a test case in `ReadmeSnippetTest` — the docs compile and
  produce the stated answer, or the build goes red. Prose can still drift; the code in it
  cannot.

None of these is load-bearing on its own. Together they're the texture of a project
where the details were chosen with scrutiny — which is the thing you notice only once
you're already inside.

---

**Full design notes:** [backward-language-design](../backward-language-design.md) ·
[extensions](../extensions.md) · [inference-unification](../inference-unification.md) ·
[editor-navigation](../editor-navigation.md) · [glossary](../glossary.md)
