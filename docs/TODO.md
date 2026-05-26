# Pontif TODO

Running list of tech debt and follow-up items flagged while building. Each
entry: one-line description + enough context to pick it up later. Resolved
items get removed (history is in git); this file is forward-looking.

---

## ⭐ Next priority — Dispatch as the star: union types + dispatch inference

A type system is only as strong as its dispatch. Without compile-time
dispatch inference, narrow types can't shine — and narrow types are
Pontif's identity. The receipt-graph subsystem (previously top priority;
now paused below) is a *consumer* of dispatch inference, not a
substitute for it. Strengthen dispatch first; everything downstream
becomes easier.

1. **Union and intersection sorts in the IR.** `[Int|Float]` and
   `[Even & Positive]` parse operator-wise today (`|` / `&` recognized
   by the lexer / Pratt parser) but have no IR representation — no
   `IrSort.Union`, no `IrSort.Intersection`. Spec is now in
   `docs/alternative-syntax.ptf` principle 4. Concretely:
   - **Parsing rule:** top-level `|` / `&` inside `[...]` is
     sort-level; after a `:` we're in predicate mode and `|` / `&` are
     predicate operators until the matching `]`. Each level is LL(1)
     on the next token; no backtracking.
   - **Same-base normalization at parse time.** `[[Int:0]|[Int:1]]`
     collapses to the IR equivalent of `[Int:0|1]` (one canonical
     refined-sort shape per equivalence class). Same for intersection.
     Cross-base / cross-kind unions stay as `IrSort.Union`;
     intersections of refinements over the same base merge into a
     single `IrSort.Refined` with `&`-joined predicate.
   - **Origin tracking under normalization.** The outer Refined gets a
     span from outermost `[` to outermost `]` via the existing
     `spanTo` helper; sub-predicates keep their parse-time origins so
     error messages still point at the offending disjunct after merge.
   - **New IR shapes:** `IrSort.Union(List<IrSort> branches, Origin)`
     for cross-base unions, `IrSort.Intersection(List<IrSort>
     branches, Origin)` for cross-base intersections (rare; the
     refinement-of-one-base case normalizes away). Both sealed
     alternatives under `IrSort`.

2. **Dispatch inference at compile time.** `DispatchTable.resolve`
   today operates on argument `SymExpr`s lifted from runtime values
   (via `toSymExpr` in `IrInterpreter.evalCall`). Inference lifts it
   to argument *narrowings* — symbolic claims about the set of values
   the arg could take, sourced from the call site's static refinement
   info. Two pieces:
   - **Overload-registration overlap check.** Pairwise per function
     name at module-compile time: verify no two overloads' param
     patterns can match the same value (`pred_A ∧ pred_B`
     unsatisfiable). Fail loudly on overlap. With this in place,
     runtime dispatch ambiguity becomes impossible — `DispatchResult.Ambiguous`
     becomes unreachable in practice (consider removing or making it
     a framework-bug-only signal).
   - **Call-site dispatch resolution.** At each call site the compiler
     asks: given the argument's narrowing, which overload(s) could
     match, and what's the resulting return narrowing (the union of
     the matched overloads' returns)? Needs union types (#1), and
     likely an expanded `DispatchResult` — the current `NoMatch` /
     `Ambiguous` / `Resolved` becomes a richer "set of resolved
     overloads with combined return narrowing."
   - **Shared predicate-arithmetic kernel.** Overload-overlap,
     match-arm `_` desugar, and match totality (see Match / patterns)
     all reduce to the same operations: predicate intersection,
     complement, satisfiability over a sort's domain. Design the
     kernel once; the three uses share it.

3. **Wire compile-time dispatch into call-site narrowing.** Once
   inference works, every `IrExpr.Call` site has a statically-knowable
   return narrowing. Propagate it into the surrounding expression's
   type. This is where the overload return-narrowing gap dissolves —
   the compiler simply knows what each call resolves to. See
   `pontif-receipts` for the consumer.

Vocabulary, design, and worked example for the receipt-graph subsystem
all stand and stay current — see `docs/glossary.md`,
`docs/receipt-graph.md`, and the `Drafter` slice in `pontif-receipts`
(kept as regression test for the data structure). They resume as
priorities once #1 and #2 land.

---

## Paused — receipt-graph subsystem (pending dispatch inference)

These were the previous next priority; they remain valid but are gated
on union types + dispatch inference. They become straightforward once
#2 above provides the dispatch resolution input.

1. **Drafter** — extend beyond the `double` slice (currently in
   `pontif-receipts`) to handle match arms, recursive calls, and
   cross-function CallRefs. The dispatch question that previously
   blocked this work dissolves once dispatch inference is real.
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

## Type system

- **Tighten the `Function` sort placeholder.** `IrSort.named("Function")` is currently accepted as a primitive in `SortChecker` to keep legacy lambda-test patterns compiling. The right shape for function-typed bindings is `IrSort.Function([params...], returnSort)`. Migrate the tests that use the named placeholder to the variant, then remove `Function` from `PRIMITIVE_SORT_NAMES`.
- **Record-literal vs. declared-sort mismatch (S-expr only).** `(let p (struct P (x Int) (y Int)) (record (x 1)) ...)` declares two fields but the record only has one. `SortChecker` doesn't notice. Symmetric "record shape matches declared sort" check would catch it. The alt parser's struct-literal forms (`Point(1, 2)` / `Point{x=1, y=2}`) close this gap at construction time by going through `declaredStructs`; the S-expr `(record ...)` form still relies on `SortChecker`.
- **Narrowing for non-`Var` match scrutinees.** `SortChecker` narrows a scrutinee's sort inside a structural-pattern branch only when the scrutinee is an `IrExpr.Var`. The parser always emits a synthetic outer let (so the scrutinee IS a Var after desugar) — but if someone hand-builds an `IrExpr.Match` directly with a non-Var scrutinee, narrowing is skipped.
- **Sort checking inside refinement predicates.** `SortChecker` doesn't recurse into `IrSort.Refined.predicate()` because predicates compile through `compileSymExpr`/`SymExpr`. Field accesses against `self` inside a refinement currently aren't validated. Needs symbolic-layer extension.
- **`Function` sort isn't validated at runtime.** A function declared with return sort `(function (Int) Int)` doesn't actually check that the lambda body produces an `Int → Int`. `Refinements.satisfiesFunction` exists; not wired in.
- **Destructuring through a type alias.** Match patterns that name an alias (e.g., `(match p (Point body))` where `Point` aliases a struct sort) get correctly resolved by `AliasResolver` — but the parser's destructuring desugar runs *before* alias resolution, so it doesn't see the structural shape and skips field-binding. Users must use the literal struct sort in the pattern to get destructuring, or use explicit `(field p x)` in the body. Fix: move destructuring out of the parser into a post-`AliasResolver` IR pass.
- **`toSymExpr` for `Closure`/`LambdaValue`.** Passing a lambda/closure as an argument to a function call goes through dispatch, which calls `toSymExpr(arg)` to build symbolic args for refinement check. `toSymExpr` only knows Long / Integer / Boolean / RecordValue today; a closure throws. Either lift closures to `SymExpr.Lam` for dispatch, or short-circuit `toSymExpr` for non-refined param positions.

## Match / patterns

- **Compile-time totality proof + `_` default desugar.** The alt parser accepts any non-empty set of match arms; if no arm matches at runtime, `IrInterpreter.evalMatch` throws `RuntimeCheckException`. Per doc principle 8, match must be total — the compiler proves the union of arm predicates equals the scrutinee's sort. The `_` default arm (ergonomic catch-all) desugars at parse time to the explicit complement of other arms' union over the scrutinee's sort; the IR only sees explicit predicates. Needs predicate-complement / sort-subtraction at parse time (same machinery the dispatch-inference work will need; do the design once).
- **Explicit-binding / rename syntax.** E.g., `(struct Point ((x Int) as a) (y Int))` to rebind `x` as `a`. Not pressing while implicit binding covers the common case.
- **Nested destructuring.** Currently only top-level fields auto-bind; inner records still require `(field inner n)` chains. Recursive destructuring would let you write `(struct Outer (inner (struct Inner (n Int))))` and have `n` directly bound.
- **Underscore `_` in let-bindings and function params.** `(let _ Int sideEffectExpr body)` to discard a value; `((_ Int))` to declare a deliberately-unused param. Pure-mode use is just clarity (no warning to silence yet); becomes important once impure expressions / actions exist and a discarded result needs to read as intentional. (Note: the originally-flagged "underscore for ignored struct-pattern fields" turned out unnecessary — partial patterns work today; see `PartialPatternTest`.)
- **Pattern struct-name is currently cosmetic.** `(struct AnyName (x Int))` matches any value with a compatible `x` field, regardless of the value's declared sort name — matching is purely structural. Decide whether this is intended (Pontif as structurally-typed) or whether patterns should reject mismatched names (Pontif as nominally-typed). The current behavior is pinned by `PartialPatternTest.patternStructName_isCosmetic_matchesPurelyByShape`.

## Boolean / predicates

- **Short-circuit evaluation for `&&` and `||`.** Currently strict — both operands evaluate. `(|| expensive false)` evaluates `expensive` even though the right operand short-circuits it. Either add dedicated `ShortCircuitOr`/`And` nodes or special-case in interpreter/lowering. Critical once impure expressions (actions) exist.
- **No `Not` operator.** `[!= 0]` works via `NE` but real Boolean negation `(not (isPrime self))` isn't expressible. Needs `SymExpr.Not` + a unary-op shape in IR (currently only `BinOp` exists).
- **No `/` (division) operator.** `AltLexer` recognizes `/` as an `OP` token, but `IrExpr.Op` has no `DIV` and the interpreter has no case. Easy to add when needed.
- **`SignAnalysis` doesn't reason about `&&` / `||`.** It uses `instanceof` chains, not sealed switches, so adding the variants didn't break it — but it also can't infer bounds from `(x > 0) && (x < 10)`. Worth extending when refinement arithmetic gets harder.

## Exception handling

- **`IllegalArgumentException`/`IllegalStateException` audit.** Several throw sites in `IrCompiler` and the AST validators conflate "framework bug" (should stay unchecked) and "user error" (should be `CompileException`). Audit pass, reclassify case-by-case.
- **`SelfRef` at runtime → `CompileException`.** The interpreter throws `IllegalStateException("Self has no runtime value")` if `SelfRef` reaches it. Could become a `CompileException` with origin if you decide that's a user-level error worth surfacing properly.

## Architecture

- **`Closure` (`pontif-ir`) vs. `LambdaValue` (`pontif-ast`) parallel types.** Same conceptual role (closure value), different inner shapes because the interpreter and the Truffle-lowered AST need different representations (`Environment` vs `CallTarget + captures[]`). Not a bug, but two places to keep in sync if closure semantics ever change.
- **`CompiledFunction.verification` and `CompiledModule.diagnostics` are write-only stubs.** `IrCompiler.compileFunctionDecl` populates both with hardcoded `ProofResult.passed()` and nothing in the codebase ever reads them (`grep verification\(\)` / `\.diagnostics` → zero hits). They're scaffolding for the return-type-proof priority item, but right now they encode a false "this function was verified" signal. Either inline the no-op default or, when the proof engine lands, wire them up — at minimum keep them honest with the proof state.
- **`extractDottedName` builds a `Call` from any Var-rooted FieldAccess chain, without checking it's a declared function.** `parsePrimaryWithPostfix` (AltParser.java:740–769) treats `random.x.y(1, 2)` as `Call("random.x.y", [1, 2])` even when `random` is just a local variable. The dispatch table then fails with a confusing "no such function" instead of the accurate "x is a value, not a module / qualified name". Fix: when the leftmost ident is in `currentScope` (i.e., a real local), prefer the `Apply` path on the field-access chain over `Call`. Or: keep both shapes and let SortChecker disambiguate. Becomes more visible once the module system lands and dotted names actually mean something.
- **`inferBaseSortName` only recognizes scrutinees that are `IrExpr.Var`.** A struct-literal scrutinee (`match Point(1, 2) { ... }`) or a Call returning a struct (`match makePoint(1, 2) { ... }`) returns `null`, so contextual `[pred]` arms aren't usable — the user is forced to write the explicit base. Trivial extension once struct-literal expressions are recognized: their declared struct name IS the contextual base.
- **Match-destructure desugar uses `IrSort.named("_")` as a placeholder declared sort** for the synthetic outer let (`__scrutinee$N`) when the scrutinee isn't already a Var. That sort flows through `SortChecker` (where the env entry is then overridden inside each structural branch via narrowing) and `IrCompiler.registerSort` (which compiles a `Sort.of("_")` and stores it in `compiledSorts`). It happens to work because the synthetic var is only referenced inside structural branches that re-narrow it, but the `_` placeholder leaks into the compiled module's sort table. Either thread the real scrutinee sort through the desugar or carve out a proper "unknown" sort form.
- **Dead code / stale annotations in the parser frontend:**
  - `AltLexer.peekAhead` (line 193) is private and never called — delete it.
  - `AltParser.syntheticCounter` (line 87–88) is annotated `@SuppressWarnings("unused")` with a comment "currently unused; reserved for slice 5+", but is used at line 834 in `desugarStructuralDestructure`. Remove the annotation, rewrite the comment.

## Playground / dasum integration

- **`StandardInput.install(window, cursors)` helper upstream.** The playground's `wireInput` is ~110 lines of generic GLFW-callbacks-to-controllers boilerplate vendored from the demo. A reusable helper in `dasum-core` would let the playground (and any future dasum app) shrink that to one call.
- **Origin → editor caret jump.** When a status-ribbon error has an `<editor>:L:C` origin, clicking it should move the editor caret to that position. Needs `line:col → character offset` conversion + `TextStates.of(codeText).setCaretIndex(...)`.
- **Interactive verification.** The playground launches and renders cleanly under timeout but I can't drive button clicks from a shell. File dialog (Open/Save/Save As) and status-ribbon click-to-history dialog need eyes-on validation.
- **Playground uses the S-expr parser only.** `App.onRunClicked` calls `PontifCompiler.compile`, which uses the S-expr `Parser`. To run alt-syntax programs, the compiler or runner needs an `Engine`-style enum for "which frontend." Or a separate "alt" toolbar button. Or autodetection (try alt first, fall back to S-expr).

## Alt syntax — surface forms that parse but produce `IrStmt.NoOp`

Each needs IR semantics added to replace the placeholder. Ordered roughly by leverage / ease.

- **Spec-only top-level `let qualified.name:Sort`** with maximally-specific sort *and no `= value`*. The "synthesize body from sort" form: `let Point.origin:Point[x:0, y:0]` should derive `Point(0, 0)` from the sort. Still NoOp pending the proof engine — same path as `function f():[Int:@>0]` (no body). The `= value` form is now handled (lowers to a 0-arg `IrStmt.FunctionDecl` with inferred or declared sort; bare references rewrite to a 0-arg `Call` at parse time).
- **Under-specified return-type proof.** Spec-only declarations like `function f():[Int>=0]` (no body, return doesn't pin a single value) still emit `NoOp`. The right answer isn't synthesis — it's the proof engine. Either the user must provide a body and we discharge that it satisfies the spec, or we error with "no derivable body, please provide one". Currently we silently NoOp, which means the dispatch table doesn't know `f` exists. Either path is fine; pick when the proof engine is closer.
- **`requires`, `exports`.** No semantics until the module system lands. See "New language features" below.

## Alt syntax — surface forms not yet parsed (would error today)

- **Named-parameter function sorts: `[Function(x:Int):[Int:x+n]]`**. Lets dependent return refinements reference the function's own parameter. AltParser throws a clear "not yet supported" error when it sees `(name:Sort)` inside `Function(...)`. Needs `IrSort.Function` to carry param names — both IR and parser need extension. Anonymous-param form (`[Function(Int):Int]`) works today.
- **Inline lambda creation.** `[Function(...):Ret]` is parseable as a sort but you can't create a value of that sort from alt syntax. Probably want something like `(x:Int) -> x+1`. Design call.
- **Refinement of refined sorts via `&` / `|`.** *Promoted to top
  priority — see "Dispatch as the star" section.* `[Int|Float]`,
  `[Even & Positive]`, `[[Int:0]|[Float:@>1]]`, and friends parse
  operator-wise but have no IR representation. Parsing rule and
  same-base normalization settled (see `docs/alternative-syntax.ptf`
  principle 4); IR shapes (`IrSort.Union`, `IrSort.Intersection`) are
  next on the work plan.

## New language features

- **Nominal struct names in `IrExpr.Record`.** Today `IrExpr.Record` carries no type name — only its field map. `AltParser.inferMaximalSort` recovers the name via exact field-set lookup in `declaredStructs`, which is lossy when two structs share an identical field set (falls back to an anonymous structural sort). Cleanest fix: add a nullable `typeName` to `IrExpr.Record`. This couples with the bigger open question "is Pontif nominally or structurally typed for structs" (TODO Match section: pattern struct-name is currently cosmetic). Decide that first, then the field follows.
- **Per-call dispatch return narrowing for inferred let sorts.** When inferring `let q = factorial(3)`, the parser only knows `factorial`'s declared return sort (loose), not the specific narrowing from the matched overload (`[Int:@>=1]` for the recursive arm, `[Int:@==1]` for the base case). Gated on the in-progress "Dispatch as the star" priority work.
- **Module system: `requires`, `exports`, namespacing.** Currently `module` is a label, `requires`/`exports` are no-ops. Needs a loader, symbol resolver, and compile-time linking. Decide whether modules are file-scoped, whether names are auto-imported from same-module decls, how cycles are handled.
- **Action classes / mutable semantics.** Pure functions stay pure; actions are the controlled escape hatch. Likely as a side-by-side IR family (`IrAction`, `IrActionStmt`) rather than a tag on `IrExpr`, mirroring how `IrSort` and `IrExpr` are sealed siblings. Pre/post-condition refinements via `@` and `old.*` naturally extend the existing refinement machinery.

## Repo hygiene

- **README is out of date.** Lists 4 modules in the modules table (`pontif-core`, `pontif-ast`, `pontif-ir`, `pontif-demo`); project currently has 7 (`pontif-parser`, `pontif-runtime`, `pontif-playground` are missing). Prose also calls the framework "BYO-parser" and says "Pontif intentionally ships no grammar and no parser" — that was true before `pontif-parser` shipped both an S-expr and an alt-syntax parser. The "Planned and in-flight directions" bullets partly overlap with completed work. License section still reads "A LICENSE file will be added with the next release" and no `LICENSE` file is present at the repo root.
- **Root-level `proof-language-concept.md` and `simple-proof-example.txt` describe an older project named "SPN" (Symbols + Numbers).** The concept doc opens with `# Symbols + Numbers (SPN)` followed by a self-contradictory line "All references to SPN in this document should be ignored, this file does not describe SPN." README links both as authoritative sketches of the proof layer. Either reconcile (rewrite under the Pontif name, or move to `docs/archive/` with a header noting they predate the current design), or drop the README link.

## Deep work — oracle territory

Anything past Pontif's built-in trivial issuer is oracle work; the
receipt-graph format is the contract. None of this is Pontif's burden to
ship — these are obligations whose closing receipts the notary can't
refute today, where a richer issuer or external solver would earn its
keep.

- **Inductive postconditions / magnitude-of-product.** The trivial
  issuer handles sign-analysis (`x*x >= 0` for any `x:Int`), but not
  things like `factorial(n) >= 1`, where the issuer would need to
  combine `n >= 1` and the inductive-hypothesis `factorial(n-1) >= 1`
  into `n * factorial(n-1) >= 1`. Z3-style linear arithmetic, an
  inductive prover, or a hand-written issuer module all fit.
- **Proof Authority (PA) trust model — roadmap goal, low priority.**
  Borrow from how Certificate Authorities work: designate certain
  issuers / oracle modules as trusted *Proof Authorities*, and receipts
  they produce are accepted by attribution rather than independent
  validation. *Snake oil* becomes a *status* — receipts from any
  unrecognized issuer — instead of a class of receipt. Pontif could
  ship a default trusted set (the built-in trivial issuer, Z3 once
  landed) and let users register project-specific PAs. Not pressing;
  pinned as a goal because the trust-by-attribution framing is the
  natural extension once oracle modules exist.
