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

### R5 — Build-artifact emission ✅ landed

`pontif-runtime/ReceiptGraphReport` turns alt-syntax source into a
reviewable text report: parse → `AliasResolver` → `Drafter` →
`ReceiptGraphPrinter`, with `BuiltinIssuer` + `Notary` layered on for a
"## Obligations" section. The section shows <em>every</em> per-branch
obligation with its outcome — `discharged [notary: accepted]`,
`NOT DISCHARGED`, or `(no return refinement — nothing to prove)` —
not just successes, so a tightened return refinement that the issuer
can't prove is visible instead of silent. Backed by
`BuiltinIssuer.attemptAll` (close() is now its discharged subset). `fromAltSource` returns
`Generated(text)` / `Failed(error)` (never throws); `writeReport`
emits to `target/receipt-graphs/<name>.receipts.txt` (failures written
as the body so the artifact always exists). ASCII-clean output
(`|-` not `⊢`) for portability. Drafter stays standalone; nothing
added to `CompiledModule`. `pontif-runtime` gains a `pontif-receipts`
dep (no cycle). Verified end-to-end on square / sign / factorial.

### Numeric discharge — linear integer bounds ✅ landed (slice 1)

`pontif-predicates/BoundAnalysis` — a hybrid linear-bound + sign engine
(sibling to `SignAnalysis`). Normalizes an integer `SymExpr` to a linear
form (`c₀ + Σ cᵢ·atom`), bounds each atom to an integer interval (from
single-atom hypotheses, integer-strict, intersected with the atom's
`SignAnalysis` sign for opaque products/squares), and interval-evaluates
the goal `(subject − bound) OP 0`. Sound by whole-interval over-approx;
never a false discharge. Public API `discharge(hyps, goal) → boolean` and
`bound(expr, hyps) → Interval`. New public `pontif-predicates/Interval`
(single range, saturating `scale`/`add`). Wired into
`IntegerDischarge.discharge` (first, ahead of the sign / `Refinements` /
integer-strictness backstops — all sound, OR-ing can't regress). Headline:
`inc(x:[Int:@>=1]):[Int:@>1] -> x+1` now discharges (the `>0`-vs-`>1`
cliff is gone); factorial / square / sign suites unchanged. Flagship:
**Ackermann with a `[Int:@>1]` postcondition closes on all three
overloads** — branch 0 (`y_0+1 > 1` from `y_0 > 0`) is the BoundAnalysis
win; the recursive branches close because each `CallRef`'s `[Int:@>1]`
result sort is the inductive hypothesis the back-reference carries in.
Pinned by `ReceiptGraphReportTest.ackermann_dischargesGreaterThanOneOnAllThreeOverloads`.
See `docs/numeric-discharge.md`.

Slice 2 (arithmetic narrowing in `NarrowingInference`) ✅ landed:
`infer` now narrows `IrExpr.BinOp` arithmetic (`+ - *`) via
`BoundAnalysis.bound` under the env's refinements, lifting the resulting
`Interval` to an `[Int:…]` refinement (`x+1` with `x:[Int:@>=1]` →
`[Int:@>=2]`; `2x`, `x-1`, `x*x`→`@>=0`, finite ranges). Comparison /
boolean ops stay null (they yield `Bool`). `BoundAnalysis` also gained
**`And`-hypothesis flattening**, so a range refinement
(`[Int:@>=1 & @<=4]`) now constrains its atom — benefits the issuer too
(range-typed params become usable hypotheses). Unit-tested in
`NarrowingInferenceTest` / `BoundAnalysisTest`.

Slice 3 (drafter body-inference fallback) ✅ landed:
`Drafter.resolveCallReturnSort` now falls back to a new
`NarrowingInference.inferCallReturnFromBody` when the call's
declared/dispatched return is unrefined. The helper runs
`StaticDispatch.resolve` against arg narrowings, then
`inferFunctionReturn` on the resolved overload — turning a callee like
`function add5(x:[Int:@>=0]):Int -> x + 5` into a CallRef result sort
`r_N: [Int:@>=5]` in the caller's graph. Carries the inferred narrowing
into `PathFacts` as an inductive hypothesis the issuer can use.
**Headline:** `chain(x:[Int:@>=0]):[Int:@>=10] -> add5(x) + 5`
discharges its `r_0 >= 10` obligation, which it couldn't before this
slice (no bound on `r_1`). Termination safe by construction:
`NarrowingInference.inferCall` never recurses into bodies, so
self/mutual recursion in the callee terminates at the declared-return
fallback. Pinned by `ReceiptGraphReportTest.bareIntCallee_…` and
`chainArithmetic_…`. Option (b) — alt-parser inferred-let sort —
remains open under "Per-call dispatch return narrowing for inferred let
sorts" below.

Follow-ups (deferred, in priority order):
- **Strengthen `Refinements.imply` (dispatch / overload-overlap) via
  bounds.** Currently ad-hoc single-atom threshold compare
  (`checkImpliesOnLongs`); a bound check generalizes it to linear shapes
  (`2x+3 ≥ 5`). Lives in `pontif-core`, *below* predicates — so this needs
  either the engine reachable from core or a thin core-level port. Design
  call when an obligation needs it.
- **Unify `BoundAnalysis`'s `Interval` with `PredicateArithmetic`'s private
  `Interval`/`IntervalSet`.** Two same-named types in one package: one
  models a single range with arithmetic (`scale`/`add`), the other models
  integer *sets* (`union`/`complement`) for satisfiability. Merge into one
  type carrying both, carefully — `PredicateArithmetic` is tested and the
  set-vs-range arithmetic semantics differ.
- **Trim `IntegerDischarge` backstops ✅ landed.** Empirically confirmed:
  `BoundAnalysis.discharge` subsumes all four prior layers across the
  full test suite (~920 tests, all green with each backstop removed in
  turn). `IntegerDischarge` is now a thin one-line wrapper that still
  earns its keep as a soundness gate (integer-domain only) marking the
  call site for future Float-refinement work, but the OR-chain itself is
  gone. Dead methods (`isReflexiveEquality`, `integerStrictness`,
  `asLong`) and imports (`Refinements`, `Sign`, `SignAnalysis`) removed.

### Receipt-graph: back-reference overload disambiguation (deferred)

The overload <em>collision</em> is fixed: `ReceiptGraph.roots` is now a
`List<Node>` (one node per declaration, source order), `GraphReference`
is `(nodeIndex, branchIndex)`, and the printer/issuer/notary all work
per-node. Ackermann's three overloads each render with their distinct
param sorts. (This also fixed the `Map.copyOf` order-scramble bug.)

What remains: a `CallRef` still targets by bare `targetFunctionName`, so
a recursive/cross call to an overloaded name doesn't pin *which* overload
it dispatches to — fine for display (the recursive structure is visible),
but for the issuer to carry overload-specific inductive hypotheses across
a back-reference it should resolve the target via `StaticDispatch` and
record the specific node index. Real slice, tied to dispatch resolution;
deferred until an obligation actually needs it (Ackermann's returns are
bare `Int`, so nothing to discharge there anyway).

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

- **Compile-time totality proof ✅ landed (decidable fragment); `_` desugar
  already done.** `SortChecker.checkMatchTotality` proves principle 8 at
  compile time for the decidable fragment — all arms `IrSort.Refined` over a
  known scrutinee sort the `PredicateArithmetic` kernel decides (**`Int` and
  `Bool`**): it unions the arm predicates, complements over the scrutinee
  domain, and rejects with the uncovered region as the witness (e.g.
  `no arm covers @ == 0`, `no arm covers @ == false`). **Sound by
  construction** — errors only when the kernel *proves* uncovered values
  exist; otherwise it **defers** (non-`Refined`/struct arms, un-inferrable
  scrutinee sort, neither-`Int`-nor-`Bool` domain, literal scrutinees, kernel
  `Unknown`), leaving `IrInterpreter.evalMatch`'s runtime no-match check as
  the safety net. The `_` arm was already desugared to the explicit
  complement by the parser (`computeDefaultArmPattern`) — and now works over
  `Bool` scrutinees too, since `PredicateArithmetic.complement` handles the
  Bool domain. Covered by `MatchTotalityTest`.
  - **Bool match evaluation ✅ landed.** `RefinementRules.CMP_BOOL_BOOL`
    folds `Bool(a) == Bool(b)` (and `!=`) to a Bool literal alongside the Int
    `CMP_LIT_LIT`, so after substituting `Self` with the scrutinee value the
    arm is decided at runtime — Bool matches compile-check *and* run.
  - **Struct totality — Tier A ✅ landed.** A bare `IrSort.Structural` arm
    (no refined or nested-structural fields) whose field set is a subset of
    the scrutinee's fields covers every value of that struct shape — per
    Pontif's subset-match semantics — so the match is trivially total. The
    common case (`match p { [Point(x, y)] -> … }`) is now compile-time
    verified. Helpers `scrutineeFieldSet`/`isBareStructuralCovering`.
  - **Struct totality — Tier B (single-varying-field) ✅ landed.**
    `tryTierBSingleField` recognizes matches where every arm is structural
    and refines the *same one* field (others bare), then reduces to that
    field's domain-coverage problem and reuses the existing kernel: union of
    arms' field refinements vs. the field's declared sort. Rejects with a
    field-anchored witness (e.g. *"no arm covers field 'x' where @ == 0"*).
    Catches the classic `[x>0] | [x<0]` missing `x==0` bug.
  - **Still deferred** (extend the kernel): **multi-varying-field struct
    totality** (genuine cross-product over field domains — e.g.
    `[Point(x>0,y>0)] | [Point(x<=0,y<=0)]` is non-exhaustive but the gap
    is two-dimensional); struct *unions* in the scrutinee; and **literal
    scrutinees** (`inferSort` returns null for `Lit`, so their singleton
    domain isn't checked — `match -3 {…}` style).
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
- **Sign + linear-bound discharge in the production `Simplifier` ✅ landed.**
  Both `HypothesisRules` (sign-analysis-backed) and
  `BoundAnalysisRules` (linear-bound + sign engine) are now part of
  `DefaultRules.production()`. The compile-time function-verification
  path (`FunctionCheck.verifyDefinition`, "proven return sort") gets the
  same reasoning the receipt-graph path has via `IntegerDischarge`.
  - `square(x:[Int:@>=0]):[Int:@>=0] -> x*x` Passes at compile time via
    the sign rule. (Pinned by
    `FunctionDeclTest.bodyUsingParameters_dischargesAtCompileTime`.)
  - `inc(x:[Int:@>=1]):[Int:@>1] -> x+1` Passes at compile time via
    `BoundAnalysisRules` — the `>0`-vs-`>1` cliff is gone here too, not
    just in the receipt-graph path. (Pinned by
    `FunctionDeclTest.linearBoundReturnSort_dischargesAtCompileTime`.)

  Layering resolved by adding a new `pontif-defaults` module between
  `pontif-predicates` and `pontif-ir`. It owns `DefaultRules` (moved
  from `pontif-core`) and the new `BoundAnalysisRules` wrapper. Single
  canonical source for "what production runs," reachable by every
  downstream module. `BoundAnalysisRules.BOUND_DISCHARGE` is guarded
  against {@code SymExpr.Frac} appearing in the goal or hypotheses —
  the integer-strictness step inside `BoundAnalysis` is sound only on
  the integer domain, and the algebra layer's rational tests stay
  unaffected.

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
  write-only stubs ✅ removed.** Both fields were always set to
  `ProofResult.passed()` and never read; the receipt-graph subsystem is
  the actual proof engine now and uses its own artifact
  (`ClosingReceipt`) plus reporting (`ReceiptGraphReport`). Fields and
  the corresponding `ProofResult` plumbing in `IrCompiler` removed.
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
- **Dead code / stale annotations ✅ landed.** Removed unused
  `AltLexer.peekAhead`; dropped the stale `@SuppressWarnings("unused")`
  on `AltParser.syntheticCounter` (it's used by the
  structural-destructure desugar) and corrected the doc comment.

- **Default-rule drift across tests ✅ landed.** Introduced
  `pontif-core/symbolic/DefaultRules` with two canonical factories —
  `production()` (Refinement + Arithmetic + Boolean + Structural; matches
  `PontifCompiler.defaultRules()`, which now delegates) and `full()`
  (production + Hypothesis + Lambda). Migrated 20 test files across
  `pontif-ir`, `pontif-runtime`, and `pontif-demo` to delegate to these
  factories rather than re-deriving locally. Full suite (~980 tests) green
  after each migration — the widening hazard the original entry warned
  about did not surface; the tests were robust to the added reductions
  in the canonical set. Part (b) — whether `HypothesisRules` and
  `LambdaRules` should join production defaults — is open as the next
  slice ("Sign/linear discharge inactive in the production `Simplifier`"
  under Boolean/predicates).

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
- **Under-specified return-type proof → hard error (resolved).** A
  body-less `function f():[Int:@>=0]` or `method Point.add(p:Point):Point`
  used to emit a silent `NoOp` — it looked defined, *skipped sort-checking
  of its signature* (so even an undeclared return type sailed through),
  and failed later as a misleading "Unknown function". It's now a
  `ParseException` at the declaration (`AltParser.specOnlyWithoutSynthesis`,
  covered by `AltParserIntegrationTest`). The *value-pinning* case
  (`[Int:@==EXPR]`, e.g. `:[Int:y+1]`) still synthesizes the body `EXPR`
  at parse time and drafts + discharges its reflexive obligation
  (`SpecOnlySynthesisTest`). **Still open:** *real* synthesis from a
  non-pinning spec (a range / struct return) — genuine program search,
  not desugar; deferred, with the hard error as the interim.
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

## Deep work — oracle territory

Anything past Pontif's built-in trivial issuer is oracle work; the
receipt-graph format is the contract. None of this is Pontif's burden
to ship — these are obligations whose closing receipts the notary
can't refute today, where a richer issuer or external solver would
earn its keep.

- **Inductive postconditions beyond sign reasoning.** The trivial
  issuer handles more than first assumed: `x*x >= 0`, `factorial(n) >= 1`
  (induction carried by the back-reference), and — since the
  `BoundAnalysis` slice — **linear integer arithmetic**: any `[Int op n]`
  threshold, linear combinations (`2x+3 >= 5`), and products/squares via
  opaque-atom sign bounds. The oracle boundary moved: **linear integer
  arithmetic is built-in; oracles start at general nonlinear / quantified /
  multi-atom-linear.** Still out of reach — `sum(n) == n*(n+1)/2`
  (nonlinear closed form), product *magnitude* (`x*y >= 6` from
  `x>=2,y>=3` gets only the sign), and multi-atom hypothesis constraints
  (`x+y>0` bounds neither alone — needs Fourier–Motzkin / Presburger).
  Z3-style arithmetic, an inductive prover, or a hand-written issuer
  module fit there.
- **Proof Authority (PA) trust model — roadmap goal, low priority.**
  Borrow from how Certificate Authorities work: designate certain
  issuers / oracle modules as trusted *Proof Authorities*, and
  receipts they produce are accepted by attribution rather than
  independent validation.
