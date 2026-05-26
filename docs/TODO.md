# Pontif TODO

Running list of tech debt and follow-up items flagged while building. Each
entry: one-line description + enough context to pick it up later. Resolved
items get removed (history is in git); this file is forward-looking.

---

## ⭐ Next priority — Dispatch inference at compile time

Union/intersection sorts and traits landed during the receipt-graph
pause, expanding the surface area dispatch inference must cover.
Phased prerequisites for the full dispatch-inference work:

### Phase A — Match-arm result narrowing ✅ landed

`pontif-ir/NarrowingInference` exposes `infer(expr, env, functionReturns)`
as a pure, query-on-demand function returning the narrowest sort
statically derivable for an expression — or `null` when nothing tighter
than the declared sort is available. Current slice handles literals,
var lookup, let-bindings, calls (declared-return fallback), and
match-arm result narrowing via same-base union of arm result sorts.
Out-of-scope expressions (BinOp, Record, FieldAccess, Apply, Lambda,
SelfRef) return `null` and consumers fall back to declared sorts.

### Phase B — `@.field` in struct refinements end-to-end ✅ landed

Struct refinements like `[Point:@.x > 0]` now compile and reduce:
- `AliasResolver` preserves struct `TypeAlias` statements (matching
  what trait aliases already did) so `SortChecker` can see them.
- `SortChecker` recognizes struct base names in `IrSort.Refined`, and
  validates `@.field` references in the predicate against the base
  struct's declared members. Unknown field → `CompileException` with
  the field's origin.
- `PontifCompiler.defaultRules()` now includes `StructuralRules` —
  the pre-existing `FIELD_ACCESS_ON_RECORD` rule was wired into the
  demo tests but missing from the production simplifier. With it in
  place, `Refinements.satisfies` substitutes `@` with the record,
  reduces field projections, and folds the resulting comparison.
- Cross-field refinements (`[Point:@.x + @.y > 0]`) work end-to-end.
- Field-access nesting deeper than one level on `@` is not yet
  structurally validated — extend `validateSelfFieldAccesses` when
  nested struct fields enter the picture.

### Phase C — Struct match-arm narrowing (unifier) ✅ landed

Wires A and B together. New `InferenceContext` record consolidates
`(typeEnv, functionReturns, structDefs)` so future extensions
(dispatch table, trait registry) don't widen `infer`'s signature
again. New inference cases:
- **Record literal** (`Point(3, 4)`): for each member with an
  inferrable narrowing, substitute `@` in the member's predicate with
  `@.fieldName` and AND the resulting predicates → `[Point:@.x==3 &
  @.y==4]`. Anonymous records (no `typeName`) return null.
- **Field access** (`p.x` where `p:[Point:…]`): extract conjuncts from
  the base's narrowing that reference *only* `@.fieldName`,
  substitute `@.fieldName → @`, return as a refinement over the
  field's declared base sort (looked up via `ctx.structDefs`).
  Cross-field conjuncts and bare-`@` conjuncts are skipped
  conservatively.
- **Struct match arm** flow-through: scrutinee `Var` narrows to the
  arm's `Refined` pattern; `p.x` inside the arm projects out the
  per-field narrowing automatically. Same-base union of arm results
  yields the match's overall return narrowing.

Match-arm hypothesis still *replaces* the var's prior narrowing
rather than intersecting — Phase D refinement.

### Phase D — Dispatch inference proper ✅ landed

Three sub-phases delivered:

- **D.1 — Overload-overlap check at registration.**
  `pontif-ir/OverloadOverlap` runs after `SortChecker` in
  `IrCompiler.compile`. Pairwise per function name: for each param
  position, classifies as Disjoint / Overlapping / Unknown via base
  comparison + `PredicateArithmetic.satisfiable(pred_A ∧ pred_B,
  baseSort)`. Provable irreducible overlap → `CompileException`.
  **Subsumption escape hatch:** overlap is accepted when one overload
  strictly implies the other (the catch-all + specialization pattern),
  since most-specific dispatch resolves at runtime. Unknown cases
  pass silently and defer to runtime.

- **D.2 — Compile-time call-site dispatch.**
  `pontif-ir/StaticDispatch.resolve(overloads, argNarrowings)` returns
  `Resolved(decl)` or `Unresolved(reason)`. Per-overload match status
  is the AND of per-param `Refinements.imply(narrowing, paramSort)`
  results; definite-matches list goes through most-specific filtering.
  Null arg narrowings degrade to Residual.

- **D.3 — Wire static dispatch into `NarrowingInference`.**
  `InferenceContext` now carries an `overloads` map. Call narrowing
  flows through `StaticDispatch` when overloads are populated;
  declared-return fallback on Unresolved. `InferenceContext.fromModule`
  builds the full context (overloads + returns + structDefs) from an
  `IrModule` for end-to-end consumers.

`DispatchResult.Ambiguous` is now unreachable in practice for
overloads the kernel can decide — D.1 rejects irreducible overlap and
D.2 picks most-specific. Ambiguous remains as a runtime safety net for
the Unknown-kernel cases (struct refinements, function-typed params,
etc.).

---

## ⭐ Next priority — receipt-graph subsystem (phased)

Dispatch inference (Phase D) lands inferred return narrowings on call
sites, so recursive back-references in the graph carry meaningful
inductive hypotheses — the original reason for the pause. Plan is
vertical-slice-first: validate the whole drafter→issuer→notary loop on
the simplest *interesting* obligation before growing the drafter.

### R1 — End-to-end loop on `square` ✅ landed

Full drafter → issuer → notary loop on `square(x:Int):[Int:@>=0] ->
x*x`, no drafter changes (existing non-recursive slice).

- **`ReceiptGraphPrinter`** — indented-text tree renderer with
  precedence-aware infix `SymExpr` + `Sort` renderers. The review
  mechanism for later drafter phases. Renders the factorial shape
  identically to the design doc.
- **`BuiltinIssuer`** — eager-close: walks the graph, substitutes the
  result var's body definition into the obligation, gathers path
  facts (guard + sub-call IHs + non-defining receipts), discharges via
  `SignAnalysis` then `Refinements`. Emits `ClosingReceipt` referencing
  the discharging branch. ISSUER_ID `<pontif-default>`.
- **`Notary`** — three verifications: `graphExists` (trivial),
  `skeletonMatches` (re-draft + record structural equality — the
  deterministic drafter makes `.equals()` the skeleton check),
  `hypothesisSupported` (negate conclusion, substitute definition,
  try to discharge the negation → refuted=reject, else accept).
- **`PathFacts`** — shared helper gathering a branch's facts + result
  var definition; used by both issuer (discharge obligation) and
  notary (refute negation). Extension point for R3/R4's
  back-reference IH traversal.

### R2 — Drafter: match arms ✅ landed

`Drafter.draftFunction` dispatches on body type: `match` → one
`Branch` per arm via `draftMatchBranches`, non-match → the existing
single unconditional branch. Each arm's guard is its
`IrSort.Refined` pattern predicate with `@` bound to the renamed
scrutinee (`[@<0]` over `match n` → `n_0 < 0`); the body equation is
`r_0 = armResult`. Scrutinee can be any expression (binds `@` to e.g.
`n_0 + 1`), not just a Var. Non-Refined (structural) patterns produce
a guardless branch for now — struct-match drafting is a later slice.
Skeleton-match round-trips for match bodies.

### R3 — Drafter: recursion + cross-function CallRefs ✅ landed

Calls in a body equation are hoisted (`hoistCalls`, post-order) into
`CallRef`s with fresh result vars (`r_1`, `r_2`, … from a per-function
counter), the call replaced by a var ref so the equation reads
`r_0 = n_0 * r_1`. CallRef result-var sort = callee's return narrowing
via `NarrowingInference.infer` over `InferenceContext.fromModule`
(declared return for the recursive/single-overload case;
`StaticDispatch`-resolved for overloaded cross-function calls). The
recursive `CallRef` *is* the back-reference (no-duplicate-edges — it
names the enclosing function, not a re-expansion) and carries the IH
`r_1 >= 1` automatically. `factorial` renders byte-for-byte like the
design doc. Verified: recursive, cross-function, and nested-call
(`inc(inc(n))` → r_1, r_2 post-order) cases; skeleton-match
round-trips.

### R4 — Notary + issuer on the richer graphs ✅ landed

The R1 issuer/notary already traversed branches and (via `PathFacts`)
pulled in back-reference IHs, so the only missing piece was a leaf
arithmetic step. **Empirical finding:** factorial discharged *nothing*
out of the box — not because of the induction (that worked: the
back-reference brought `r_1 >= 1` into scope and `SignAnalysis` gave
`POSITIVE × POSITIVE = POSITIVE` for the product) but because
`Sign.satisfies` is calibrated for the *rational* domain (`SymExpr.Frac`,
used by the algebra layer), where `> 0` does NOT imply `>= 1`. Over
Pontif's integer-only refinement domain it does.

Fix: `IntegerDischarge` — an issuer-layer wrapper that adds the
**integer-strictness bridge** (`POSITIVE ⟹ >= 1`, `NEGATIVE ⟹ <= -1`)
on top of `SignAnalysis`/`Refinements`, leaving the shared `Sign`
lattice domain-neutral (it still serves the rational algebra layer
correctly). Used by both `BuiltinIssuer` (discharge) and `Notary`
(refute negation). With it, factorial closes on **both** branches —
base `1 >= 1` and recursive `n_0 * r_1 >= 1` — and the notary accepts
both while still refuting a bogus `r_0 <= 0`.

**Soundness gate:** the bridge is sound only while the refinement
domain is integer-only; documented in `IntegerDischarge` as the
integer counterpart of why float refinements were deferred.

### R5 — Build-artifact emission

Wire the drafter + formatter into the compile path so compiling a
program emits its receipt-graph(s) to text files (e.g.
`target/receipt-graphs/`) for review. Drafter stays standalone (per
doc "invokable standalone"); don't bloat `CompiledModule`.

### Deferred — issuer plugin interface (Maven-style)

Still gated on Pontif's not-yet-designed package-management / build
tool. Receipt-graph data shape is public; the plugin protocol on top
of it is what's deferred.

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
- **Sort checking inside refinement predicates (deeper than `@.field`).**
  `SortChecker.validateSelfFieldAccesses` now validates one-level
  `@.field` references against the base struct in Phase B. Predicates
  that go deeper (`@.field.subfield`, `@.method(...)`) or that involve
  function-call shapes still aren't recursively type-checked. Extend
  when nested struct refinements show up.
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

- **Default-rule drift across tests.** ~28 test files build their own
  rule lists (`defaultRules()` / `combinedRules()` / `allRules()`)
  instead of pulling from a single canonical source. Three patterns
  observed:
  1. Stripped-down `defaultRules()` in ~9 pontif-ir tests (`IrTest`,
     `TruffleExecutionTest`, `IrMatchTest`, `IrRecordTest`, `OriginTest`,
     `IrLambdaTest`, `TruffleCoverageTest`, `TruffleLambdaTest`) —
     local copy of `cmpLitLit`, not even `RefinementRules.all()`.
  2. Smaller-than-production `combinedRules()` in ~5 pontif-demo tests
     (`RefinementTest`, `M6MatchTest`, `CaseTest`, `FunctionDeclTest`,
     `TotalExpressionTest`) — Refinement + Arithmetic only; missing
     Boolean + Structural relative to current production defaults.
  3. Larger-than-production `allRules()` in ~10 pontif-demo tests
     (`DispatchTest`, `CompiledCallTest`, `CrossFieldInvariantTest`,
     `SignAnalysisTest`, etc.) — adds `HypothesisRules` and
     `LambdaRules`, which aren't in production defaults.

  This is how the missing-`StructuralRules` bug in
  `PontifCompiler.defaultRules()` went undetected: demo tests had it
  via `allRules()`, IR tests didn't need it. Real fix is two-pronged:
  (a) introduce a canonical `DefaultRules.production()` /
  `DefaultRules.full()` in `pontif-core` so test helpers can delegate
  rather than re-derive; (b) decide whether `HypothesisRules` and
  `LambdaRules` should be in production defaults (group-3 tests treat
  them as essential — that's an architectural signal worth
  acting on). Migration carries real risk: a test passing only
  because a rule *doesn't* fire would silently change behavior when
  upgraded to the canonical set. Migrate one file at a time, verify
  after each.

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

- **Inductive postconditions beyond sign reasoning.** The trivial
  issuer handles more than first assumed: `x*x >= 0`, and — since R4's
  integer-strictness bridge — `factorial(n) >= 1` (the induction is
  carried by the graph's back-reference; the bridge supplies the
  `POSITIVE ⟹ >= 1` leaf step). What's still out of reach is anything
  needing genuine linear/non-linear arithmetic the sign lattice can't
  express — e.g. `sum(n) == n*(n+1)/2`, or bounds that depend on the
  *magnitude* of a recursive result rather than just its sign. Z3-style
  arithmetic, an inductive prover, or a hand-written issuer module fit
  there.
- **Proof Authority (PA) trust model — roadmap goal, low priority.**
  Borrow from how Certificate Authorities work: designate certain
  issuers / oracle modules as trusted *Proof Authorities*, and
  receipts they produce are accepted by attribution rather than
  independent validation.
