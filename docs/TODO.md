# Pontif TODO

Running list of tech debt and follow-up items flagged while building. Each
entry: one-line description + enough context to pick it up later. Resolved
items get removed (history is in git); this file is forward-looking.

---

## ⭐ Next priority — Dispatch inference at compile time

Union/intersection sorts in the IR are now in place (parser, IR, Sort
layer, Refinements satisfaction, dispatch matching all wired). The
remaining piece of *"dispatch as the star"* is compile-time inference:

`DispatchTable.resolve` today operates on argument `SymExpr`s lifted
from runtime values (via `toSymExpr` in `IrInterpreter.evalCall`).
Inference lifts it to argument *narrowings* — symbolic claims about
the set of values the arg could take, sourced from the call site's
static refinement info. Two pieces:

- **Overload-registration overlap check.** Pairwise per function name
  at module-compile time: verify no two overloads' param patterns can
  match the same value (`pred_A ∧ pred_B` unsatisfiable). Fail loudly
  on overlap. With this in place, runtime dispatch ambiguity becomes
  impossible — `DispatchResult.Ambiguous` becomes unreachable in
  practice (consider removing or making it a framework-bug-only
  signal).
- **Call-site dispatch resolution.** At each call site the compiler
  asks: given the argument's narrowing, which overload(s) could match,
  and what's the resulting return narrowing (the union of the matched
  overloads' returns)? `DispatchResult` likely expands to a richer
  "set of resolved overloads with combined return narrowing."
- **Shared predicate-arithmetic kernel.** Overload-overlap, match-arm
  `_` desugar, and match totality all reduce to the same operations:
  predicate intersection, complement, satisfiability over a sort's
  domain. The kernel lives in `pontif-predicates` and is partially
  built (used by `_`-arm desugar today). Extend rather than reinvent.

Then: **wire compile-time dispatch into call-site narrowing.** Once
inference works, every `IrExpr.Call` site has a statically-knowable
return narrowing. Propagate it into the surrounding expression's type.
The overload return-narrowing gap dissolves — the compiler simply
knows what each call resolves to.

---

## Paused — receipt-graph subsystem (pending dispatch inference)

These were a previous next priority; they remain valid but are gated
on dispatch inference. They become straightforward once inference
provides the dispatch resolution input.

1. **Drafter** — extend beyond the `double` slice (currently in
   `pontif-receipts`) to handle match arms, recursive calls, and
   cross-function CallRefs.
2. **Notary** — three verifications: graph exists, skeleton matches a
   fresh draft, hypothesis is supported (not refuted) by the graph.
   Refutation-only; reads `(issuer, conclusion, reference)` from
   closing receipts. Backed by `SignAnalysis` + `Refinements`.
3. **Built-in default issuer + trust integration.** `SignAnalysis` +
   equality covers the trivial fragment; trusted by the notary by
   default, user-disablable.
4. **Issuer plugin interface (Maven-style).** Doubly-blocked — gated
   on dispatch inference *and* on Pontif's not-yet-designed
   package-management / build tool. Receipt-graph data shape is
   public; the plugin protocol on top of it is what's deferred.

---

## Traits — follow-on work

- **Default method impls in trait bodies.** Trait body provides a
  default; impl blocks override or inherit. Useful but adds
  self-reference resolution.
- **Multi-trait constraints** in param positions (`a:Duck & Audible`).
  Already partially achievable via intersection sorts; needs a small
  parser extension to compose trait names with `&`.
- **Trait inheritance** (Trait B extends Trait A — B implies A). Pure
  sugar over multi-trait constraints; defer.
- **Primitives as trait implementors** (`Int` implements `Addable`).
  Gated on the unified-operator-dispatch direction that would turn
  built-in operators into real dispatch entries. Until then, traits
  work for user types only.

## Type system

- **Tighten the `Function` sort placeholder.** `IrSort.named("Function")`
  is currently accepted as a primitive in `SortChecker` to keep legacy
  lambda-test patterns compiling. The right shape for function-typed
  bindings is `IrSort.Function([params...], returnSort)`. Migrate the
  tests that use the named placeholder to the variant, then remove
  `Function` from `PRIMITIVE_SORT_NAMES`.
- **Record-literal vs. declared-sort mismatch (S-expr only).** The alt
  parser's struct-literal forms close the gap at construction time by
  going through `declaredStructs`; the S-expr `(record ...)` form
  still relies on `SortChecker`, which doesn't verify field-set match
  against a declared struct.
- **Narrowing for non-`Var` match scrutinees.** `SortChecker` narrows
  a scrutinee's sort inside a structural-pattern branch only when the
  scrutinee is an `IrExpr.Var`. The parser always emits a synthetic
  outer let (so the scrutinee IS a Var after desugar) — but if someone
  hand-builds an `IrExpr.Match` directly with a non-Var scrutinee,
  narrowing is skipped.
- **Sort checking inside refinement predicates.** `SortChecker` doesn't
  recurse into `IrSort.Refined.predicate()` because predicates compile
  through `compileSymExpr`/`SymExpr`. Field accesses against `self`
  inside a refinement currently aren't validated. Needs symbolic-layer
  extension.
- **`Function` sort isn't validated at runtime.** A function declared
  with return sort `[Function(Int):Int]` doesn't check that the lambda
  body produces an `Int → Int`. `Refinements.satisfiesFunction` exists;
  not wired in.
- **Destructuring through a type alias.** Match patterns that name an
  alias get correctly resolved by `AliasResolver` — but the parser's
  destructuring desugar runs *before* alias resolution, so it doesn't
  see the structural shape and skips field-binding. Fix: move
  destructuring out of the parser into a post-`AliasResolver` IR pass.
- **`toSymExpr` for `Closure`/`LambdaValue`.** Passing a lambda/closure
  as an argument to a function call goes through dispatch, which calls
  `toSymExpr(arg)` to build symbolic args for refinement check.
  `toSymExpr` only knows Long / Integer / Boolean / RecordValue today;
  a closure throws. Either lift closures to `SymExpr.Lam` for
  dispatch, or short-circuit `toSymExpr` for non-refined param
  positions.

## Match / patterns

- **Compile-time totality proof + `_` default desugar.** The alt parser
  accepts any non-empty set of match arms; if no arm matches at runtime,
  `IrInterpreter.evalMatch` throws `RuntimeCheckException`. Per doc
  principle 8, match must be total — the compiler proves the union of
  arm predicates equals the scrutinee's sort. Same predicate-arithmetic
  kernel as dispatch inference; do the design once.
- **Explicit-binding / rename syntax.** E.g., `(struct Point ((x Int) as a)
  (y Int))` to rebind `x` as `a`. Not pressing while implicit binding
  covers the common case.
- **Nested destructuring.** Currently only top-level fields auto-bind;
  inner records still require `(field inner n)` chains.
- **Underscore `_` in let-bindings and function params.** `(let _ Int
  sideEffectExpr body)` to discard a value; `((_ Int))` to declare a
  deliberately-unused param. Becomes important once impure expressions
  / actions exist and a discarded result needs to read as intentional.
- **Pattern struct-name is currently cosmetic.** `(struct AnyName (x
  Int))` matches any value with a compatible `x` field, regardless of
  the value's declared sort name — matching is purely structural.
  Decide whether this is intended (Pontif as structurally-typed) or
  whether patterns should reject mismatched names (Pontif as
  nominally-typed). Now that `IrExpr.Record` carries a `typeName`,
  nominal matching is feasible — but the current behavior is pinned by
  `PartialPatternTest`.

## Boolean / predicates

- **Short-circuit evaluation for `&&` and `||`.** Currently strict —
  both operands evaluate. Critical once impure expressions (actions)
  exist.
- **No `Not` operator.** `[!= 0]` works via `NE` but real Boolean
  negation `(not (isPrime self))` isn't expressible. Needs
  `SymExpr.Not` + a unary-op shape in IR (currently only `BinOp`
  exists).
- **No `/` (division) operator.** `AltLexer` recognizes `/` as an `OP`
  token, but `IrExpr.Op` has no `DIV` and the interpreter has no case.
  Easy to add when needed.
- **`SignAnalysis` doesn't reason about `&&` / `||`.** It uses
  `instanceof` chains, not sealed switches, so adding the variants
  didn't break it — but it also can't infer bounds from `(x > 0) &&
  (x < 10)`.

## Exception handling

- **`IllegalArgumentException`/`IllegalStateException` audit.** Several
  throw sites in `IrCompiler` and the AST validators conflate
  "framework bug" (should stay unchecked) and "user error" (should be
  `CompileException`). Audit pass, reclassify case-by-case.
- **`SelfRef` at runtime → `CompileException`.** The interpreter
  throws `IllegalStateException("Self has no runtime value")` if
  `SelfRef` reaches it. Could become a `CompileException` with origin
  if you decide that's a user-level error worth surfacing properly.

## Architecture

- **`Closure` (`pontif-ir`) vs. `LambdaValue` (`pontif-ast`) parallel
  types.** Same conceptual role, different inner shapes
  (`Environment` vs `CallTarget + captures[]`). Not a bug, but two
  places to keep in sync if closure semantics ever change.
- **`CompiledFunction.verification` and `CompiledModule.diagnostics`
  are write-only stubs.** Hardcoded `ProofResult.passed()`, never
  read. Either inline the no-op default or, when the proof engine
  lands, wire them up.
- **`extractDottedName` builds a `Call` from any Var-rooted FieldAccess
  chain, without checking it's a declared function.** Treats
  `random.x.y(1, 2)` as `Call("random.x.y", [1, 2])` even when
  `random` is a local variable. Becomes more visible once the module
  system lands.
- **`inferBaseSortName` only recognizes scrutinees that are `IrExpr.Var`.**
  A struct-literal scrutinee or a Call returning a struct returns
  `null`, so contextual `[pred]` arms aren't usable.
- **Match-destructure desugar uses `IrSort.named("_")` as a placeholder
  declared sort.** Leaks into the compiled module's sort table. Either
  thread the real scrutinee sort through the desugar or carve out a
  proper "unknown" sort form.
- **Dead code / stale annotations:**
  - `AltLexer.peekAhead` (line 193) is private and never called — delete it.
  - `AltParser.syntheticCounter` is annotated `@SuppressWarnings("unused")`
    with a stale comment, but is used in `desugarStructuralDestructure`.

## Playground / dasum integration

- **`StandardInput.install(window, cursors)` helper upstream.** The
  playground's `wireInput` is ~110 lines of boilerplate vendored from
  the demo. A reusable helper in `dasum-core` would shrink that to one
  call.
- **Origin → editor caret jump.** When a status-ribbon error has an
  `<editor>:L:C` origin, clicking it should move the caret. Needs
  `line:col → character offset` conversion.
- **Interactive verification.** The playground launches and renders
  cleanly under timeout but I can't drive button clicks from a shell.
- **Playground uses the S-expr parser only.** `App.onRunClicked` calls
  `PontifCompiler.compile`, which uses the S-expr `Parser`. To run
  alt-syntax programs, the compiler or runner needs an `Engine`-style
  enum, or a separate "alt" toolbar button, or autodetection.

## Alt syntax — surface forms that parse but produce `IrStmt.NoOp`

- **Spec-only top-level `let qualified.name:Sort`** with maximally-
  specific sort *and no `= value`*. The "synthesize body from sort"
  form: `let Point.origin:Point[x:0, y:0]` should derive `Point(0, 0)`
  from the sort. Still NoOp pending the proof engine.
- **Under-specified return-type proof.** Spec-only declarations like
  `function f():[Int>=0]` (no body, return doesn't pin a single value)
  still emit `NoOp`. Pick: synthesis from body (needs proof engine) or
  hard error.
- **`requires`, `exports`.** No semantics until the module system
  lands.

## Alt syntax — surface forms not yet parsed (would error today)

- **Named-parameter function sorts: `[Function(x:Int):[Int:x+n]]`.**
  Lets dependent return refinements reference the function's own
  parameter. AltParser throws a clear "not yet supported" error.
  Needs `IrSort.Function` to carry param names.
- **Inline lambda creation.** `[Function(...):Ret]` is parseable as a
  sort but you can't create a value of that sort from alt syntax.
  Probably want something like `(x:Int) -> x+1`. Design call.

## New language features

- **Per-call dispatch return narrowing for inferred let sorts.** When
  inferring `let q = factorial(3)`, the parser only knows
  `factorial`'s declared return sort, not the specific narrowing from
  the matched overload. Gated on the dispatch-inference priority work.
- **Module system: `requires`, `exports`, namespacing.** Currently
  `module` is a label, `requires`/`exports` are no-ops. Needs a
  loader, symbol resolver, and compile-time linking.
- **Action classes / mutable semantics.** Pure functions stay pure;
  actions are the controlled escape hatch. Likely as a side-by-side IR
  family (`IrAction`, `IrActionStmt`) rather than a tag on `IrExpr`.

## Repo hygiene

- **Root-level `proof-language-concept.md` and `simple-proof-example.txt`
  describe an older project named "SPN" (Symbols + Numbers).** The
  concept doc opens with `# Symbols + Numbers (SPN)` followed by a
  self-contradictory line "All references to SPN in this document
  should be ignored." Either reconcile (rewrite under the Pontif
  name, or move to `docs/archive/` with a header noting they predate
  the current design), or drop entirely.
- **No `LICENSE` file at the repo root.** README references
  Apache 2.0; just add the file.

## Deep work — oracle territory

Anything past Pontif's built-in trivial issuer is oracle work; the
receipt-graph format is the contract. None of this is Pontif's burden
to ship — these are obligations whose closing receipts the notary
can't refute today, where a richer issuer or external solver would
earn its keep.

- **Inductive postconditions / magnitude-of-product.** The trivial
  issuer handles sign-analysis (`x*x >= 0` for any `x:Int`), but not
  things like `factorial(n) >= 1`. Z3-style linear arithmetic, an
  inductive prover, or a hand-written issuer module all fit.
- **Proof Authority (PA) trust model — roadmap goal, low priority.**
  Borrow from how Certificate Authorities work: designate certain
  issuers / oracle modules as trusted *Proof Authorities*, and
  receipts they produce are accepted by attribution rather than
  independent validation.
