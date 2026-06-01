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

## Return verification + refinement-syntax direction (2026-05-31 session)

Decisions and findings from a playground pre-flight pass (`pontif-runtime/
PlaygroundProbeTest`, 35 probes — realistic alt programs run end-to-end).

**🔴 Soundness gap — return refinements are never verified in the run path.**
`FunctionCheck.verifyDefinition` (the "proven return sort" check) is
implemented + demo-tested but **never called by `IrCompiler.compile`** (the
path both `compile`/`compileAlt`/playground share). The compiler checks only
*argument* refinements (`StaticDispatch.imply`, runtime match via
`Refinements.satisfies`) — never *returns*. So `function bad(x:Int):[Int:@>0]
-> x` compiles and `bad(-1)` returns `-1`, silently violating its declared
sort. **Decision (James): reject unprovable returns.** A declared return is a
*claim* — prove it or it's rejected; the programmer's recourse to not prove
is to not *declare* the narrowing. Inference stays best-effort (narrow only
what's provable, else base) — it never lies, just stays conservative.
Silent-widen is rejected (it's the COTT "lie by omission").
**Coupling (load-bearing):** with an incomplete engine and no proof-supply
path wired in, naive rejection rejects *true-but-unprovable* returns —
including `isSparse` `[Int:@>=-16]`. So reject-unprovable-returns is the
policy that makes the receipt-graph refinement (Slice 1b) *necessary*, not
decorative. **Sequence:** (A) wire `verifyDefinition` in as a non-fatal
*measurement* first (blast radius across the corpus — how many declared
returns are provable today); (B) flip to hard rejection for provable cases;
(C) wire Slice-1b `Refinement` into compile as the proof-supply recourse for
the hard cases. Do NOT flip rejection before C or the language bricks on its
own incompleteness.

*Step A (measurement) ✅ landed (`ReturnVerificationMeasurementTest`).* Across
a representative corpus: **PROVABLE** today — `factorial` (inductive back-ref
IH), `inc` (linear threshold), `square` (sign square-rule), `prod` (product
magnitude — the Slice-0 win at the verification level), `addNonNeg` (linear);
**UNPROVABLE** — `isSparse` (true-but-hard → needs the Slice-1 proof-supply
recourse) and `bad` (genuinely false → correctly rejected). Blast radius is
the expected shape: simple linear/sign/inductive returns sail through; the
rejected set is exactly {needs-a-proof, real-error}. **Key refinement this
surfaced:** the gate should consult the **receipt-graph engine**
(`BuiltinIssuer`/`Notary` — per-branch, recursion-capable), NOT
`FunctionCheck.verifyDefinition` (whole-body; can't carry the inductive
hypotheses recursion needs — `factorial` would fail it). So step B = a
post-IrCompile gate in **`PontifCompiler.compileModule` (pontif-runtime)** —
**not `IrCompiler`**: `pontif-ir` sits *below* `pontif-receipts`
(`receipts → ir`, verified), so `IrCompiler` calling `BuiltinIssuer` would be
a dependency cycle. The gate drafts the graph, runs `BuiltinIssuer`/`Notary`,
and fails the compile (`CompileResult.Failed`) for any refined return whose
obligation is NOT discharged. Step C = let a supplied `Refinement` (Slice 1b)
discharge the hard ones first, so the gate consults proofs before rejecting.

*Full shape-coverage measurement ✅ (`blastRadius_coversEveryCorpusShape`).*
Classified one representative per distinct refined-return shape in the corpus
(35 refined returns total). **Impact is LIGHT, not heavy:** thresholds
(`@>1/@>=1/@>=0`), `@>0`-with-supporting-hyp, sign (`x*x`), inductive,
dependent value-pins (`a+b`, `n*2`, `y+1` — reflexive), singletons (`0`,
`42`), `[Bool:false]`, and product-magnitude (`@>=6`, the Slice-0 win) are all
PROVABLE. Only three kinds reject: (1) genuinely-false (`@>0` from
unconstrained input — gate working as intended; the pre-existing corpus has
**zero** of these, so no latent bugs hid behind unverified returns); (2) the
true-but-hard polynomial (`@>=-16` — proof recourse; only this session's tests
use it); and (3) — the one **new actionable finding** — **union returns
(`[Int:0|1]`) are UNPROVABLE because `BoundAnalysis.discharge`/
`IntegerDischarge` handle only `Cmp` goals, not `Or`.** A union obligation
like `0==0 | 0==1` is trivially true but the engine won't evaluate a
disjunction. **Small fix (do before flipping the gate):** discharge an `Or`
goal if any disjunct discharges (and `And` if all do). That moves union
returns to PROVABLE, shrinking the rejected set to exactly
{genuinely-false, true-but-hard-with-proof}.

*Or-goal fix ✅ landed.* `BoundAnalysis.discharge` now decomposes
disjunctive/conjunctive goals (Or → any disjunct discharges; And → all do).
Union value returns (`[Int:0|1]`, `[Int:@<0|@>10]`) now discharge; `bit`
flipped to PROVABLE in the shape-coverage measurement. So the gate's rejected
set is now exactly {genuinely-false, true-but-hard-with-proof} — the clean
partition. (Type-union `[Int|Rational|Decimal]` is the sort system's, not a
discharge goal; deep-nested mixes degrade gracefully — an unreadable disjunct
just doesn't discharge.) **Path to the gate (step B) is now clear:**
provable → pass, false → reject, hard → consult supplied proof.

*Step B (the gate) ✅ landed.* `PontifCompiler.compileModule` now rejects a
declared return refinement the proof system can't discharge — consulting the
receipt-graph engine over a cleanly-drafted graph, rejecting only on a
positive NOT-DISCHARGED verdict, and **abstaining** (not rejecting) when
drafting throws (so the drafter's scope gaps never punish valid code).
`ReturnGateTest` proves enforcement (false rejected; provable incl. inductive
and bare-Int accepted); full suite green (the corpus is provable-or-abstained).
**Remaining for completeness:**
1. **Proof-authoring surface ✅ landed (struct-tree, in-source).** The gate now
   consults hand-authored proofs, so it's "reject hard returns *lacking a valid
   proof*," not "reject hard returns." A new top-level alt form
   `proof <fn> = <Leaf/Split tree>` (`IrStmt.Proof`, carrying the *unevaluated*
   `IrExpr` so split predicates stay symbolic) is parsed by `AltParser`;
   `pontif-receipts/RefinementProof.fromIr` translates the struct literal to a
   `Refinement` (renaming source params `x` → graph `x_0`, requiring each
   `Split` predicate be a `Cmp`); `PontifCompiler.firstUnprovableReturn` binds
   each proof to its function's node (`GraphReference(nodeIndex, 0)`) and calls
   `BuiltinIssuer.attemptAll(graph, proofs)`. **Headline:** `function f(x:Int):
   [Int:@>=0] -> x*(x-1)` with `proof f = Split(x>=1, Leaf(), Leaf())` compiles
   (was rejected); the flagship `isSparse` `(x-3)*(x+5) >= -16` closes via an
   in-source piecewise proof. The user declares `struct Leaf()` /
   `struct Split(p:Bool, whenTrue:[Leaf|Split], whenFalse:[Leaf|Split])` (the
   recursive types that just landed). Covered by `ProofAuthoringTest` (e2e) +
   `RefinementProofTest` (translator).
   - **Staleness = per-function re-validation, NOT a snapshot compare** (James's
     call): every compile re-checks each proof against its function's freshly
     drafted obligation, so an unrelated edit never disturbs a valid proof,
     while a change that breaks one yields a scoped hard error. Reserved hard
     errors: supplied-proof-no-longer-discharges (stale/insufficient),
     proof-for-unknown-function, orphaned-proof (return refinement dropped),
     duplicate proof. A redundant proof (engine already discharges) is silently
     fine (could warn later). A skeletonMatches structural compare was
     *rejected* — it would over-trigger on still-valid cosmetic edits.
   - **Decision (James's, resolved):** recursive types were built first; the
     struct-tree path came nearly free after (no DSL parser needed). The DSL
     alternative is moot.
   - **Deferred:** multi-branch (per-`match`-arm) proofs — needs branch-
     addressing syntax (v1 asserts single-branch); proofs on overloaded
     functions (v1 asserts sole-node-of-name); recursion-in-proof to generate
     singleton ladders (translator reads *unevaluated* IR, so `isSparse`'s
     middle region is a flat literal); a shipped `Leaf`/`Split` prelude;
     separate distributable proof files; explicit `proves Name : pred` claims +
     sharper stale messages; And/Or (De Morgan) split predicates; position-
     robust (rename-proof) variable binding; Notary re-validation of
     `REFINEMENT_ISSUER_ID` receipts.
2. **Direct `IrCompiler` path is ungated** by design — it sits below
   pontif-receipts (cycle), so the gate is at the `PontifCompiler` layer only.
   The IR API stays unprotected (test harnesses use it); fine, since the
   user-facing surface is gated.
3. Optional: a `pontif.gateReturns` toggle if the gate ever needs to be opt-out.

**✅ Confirmed working (locked in by tests):** dependent return refinements
referencing parameters — `function add(a:Int,b:Int):[Int:a+b] -> a+b` runs,
and the spec-only form synthesizes the body from the `a+b` pin. This is
"Scenario 1" (crucial for synthesis / elaborate proofs) and it already works;
no build needed.

**Refinement-syntax direction (the "#2" thread).** Unifying principle:
*a refinement can refer to whatever's in scope at its position; the sugar is
just dropping what's inferable.* The grammar is unambiguous — every stage's
kind is fixed by its leading lexeme (`@…` = focus/drill; `(` after an ident =
deconstruction; comparison = predicate with implicit/explicit subject; bare
ident = sort-or-name). `:` is one uniform "refined-by / has-sort" connective
(not overloaded); `@.field` is the operator that shifts the subject inward.
Wanted forms:
- `[Int>=0]` ≡ `[Int:@>=0]` (base + implicit `@`); needed where there's no
  subject (return/param positions).
- `[@>0]` contextual base — should work on *any* statically-typed scrutinee,
  not just a bare Var (**fixes F1**: `match n+1 { [@<=0] -> … }` currently
  parse-errors "no contextual base"; explicit `[Int:@<=0]` is the workaround).
- Constructor deconstruction `[Point(x>0, y==0)]` — binds-and-constrains per
  field (breadth). Endorsed.
- Path refinement `[Vector:@.x:Rational:@.denominator>0]` — drill one field
  deep, no sibling `_`s (depth). Composes with deconstruction:
  `[Vector:@.x:Rational(numerator>0, denominator>0)]`.
- The one enabling slice under all of these: **type/name/field-environment-
  aware match-and-refinement elaboration** (today the parser is nearly
  type-blind — the F1 root). Open decision: are types and values separate
  namespaces (Capital vs lowercase, as all examples assume)? If yes, a leading
  identifier is classified *lexically* before any scope lookup.

**"Scenario 2" restriction (no tuples yet).** A bare free-name match predicate
`match x { [y>0] -> … }` has no referent without tuples/deconstruction to
introduce `y` — it's a **hard error** until tuples land (confirmed rejected by
test). Names in match arms come only from constructor deconstruction;
return-refinement names come only from the parameter list.

**Confirmed known gaps (now test-pinned):** `!` boolean negation parses but
can't lower (no `Not` op, F2); inline alt lambda `(x:Int)->…` not parseable
(F3). **Fixed this session:** `let` redefinition now reports "'n' is already
defined" instead of the generic overload-overlap message (`OverloadOverlap`
special-cases zero-arg overloads).

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

### ⭐ Recursive types (foundational) ✅ landed

Pontif now has recursive types. `struct Node(v:Int, next:Node)` and
`struct Split(p:Int, t:[Leaf|Split], f:[Leaf|Split])` compile, and recursive
*values* construct + traverse end-to-end (pinned by `RecursiveSortProbeTest`).
The blocker is gone: the struct-tree proof-authoring path (`Refinement =
[Leaf|Split]` holding `Refinement`s) is now expressible.

**What landed (textbook equi-recursive — deliberately not novel):**
- **Nominal, by-reference.** A struct reference stays `IrSort.Named` and is
  resolved by name against a registry on demand, never inlined.
  `AliasResolver` excludes `IrSort.Structural` aliases from its inlining table
  (only pure abbreviations like `type Coord = Int` still inline) and resolves
  abbreviation references *inside* kept struct members while leaving struct
  refs nominal. Canonical struct collection is `pontif-ir/TypeRegistry.collect`
  (keyed by both the alias name and the struct's own name, since
  `(deftype Point (struct P …))` lets them differ). `SortChecker` accepts a
  `Named` whose name is a declared struct, and projects fields via a shared
  `resolveNominal` (single name lookup, never unrolling).
- **Contractiveness for free.** Because structs are absent from the alias
  table, `resolveSort` never follows a reference into a struct body — so
  recursion *through a constructor* is admitted while a constructor-free
  abbreviation cycle (`type A = [A|Int]`, `type A = B; type B = A`) is still
  caught by the existing path-based cycle check. No separate checker needed.
  (Inhabitability polish — reachable base case — deferred.)
- **Coinductive `imply`.** `pontif-core/symbolic/Coinduction` (immutable
  `Assumed` ordered-pair set + `Seen` name set). `Refinements.imply` resolves
  by-reference struct sorts via a registry carried on the `Simplifier`
  (`withRegistry`) and guards `implyStructural` with the assumption set:
  revisiting a `(tighter, looser)` name pair assumes it holds (GFP) and
  returns `Passed`, so `imply(Node, Node)` terminates. `StaticDispatch` threads
  the registry (`InferenceContext.sortRegistry()`).
- **Soundness restored, not just termination.** Nominalizing structs made a
  struct param a bare `Sort.of("Point")` — which `satisfies`/`imply` treated
  as *unconstrained* (a real regression: `id(42)` for `id(p:Point):Point` was
  accepted). The registry resolution in `satisfies` (runtime, via
  `IrInterpreter` attaching `CompiledModule.structRegistry`) and `imply`
  (dispatch) restores structural checking. Pinned by
  `RecursiveSortProbeTest.nominalStructParam_stillCheckedStructurally` and
  `StructuralSortTest` (recursive self-imply terminates; disjoint nominal
  structs don't imply).

**Reasoners that did NOT need a guard (empirically):**
- **Runtime `satisfies(value, sort)`** — value-directed; descent follows only
  fields present in the finite record, so it terminates on recursive types
  even while resolving nominal members.
- **`OverloadOverlap`** — compares by base name (nominally correct: distinct
  struct names are disjoint, same names overlap); its `imply` uses are
  same-base / refined-vs-bare, which the existing logic handles.
- **`NarrowingInference`** — recursion is expression-directed (finite);
  `inferFieldAccess` does a single-level `structDefs` lookup per node and never
  chases a field's struct definition. The `Seen` guard would be dead code
  today; revisit only if narrowing ever walks nested struct sorts.

**No frontend work** — the syntax already parsed; the change was entirely
representation + reasoners. Full suite green throughout (~1060 tests).

- **Tighten the `Function` sort placeholder ✅ landed.** `"Function"`
  removed from `SortChecker.PRIMITIVE_SORT_NAMES`. All test sites
  migrated to `IrSort.Function(paramSorts, returnSort, origin)` — the
  Java tests gained typed helpers (`FN = Int→Int`, `HOF = (Int→Int)→Int`,
  `CURRIED = Int→(Int→Int)`); the S-expr tests migrated to the
  `(function (Int) Int)` surface form (which was already supported by
  `Parser.parseFunctionSort`). A test that explicitly demonstrated the
  new form is now redundant and removed.
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
  types — deliberate, not actionable.** Reviewed 2026-05-30; these
  aren't duplication, they're two correct realizations of "closure"
  for two execution models. `Closure` uses `Environment` (name → value)
  for the IR interpreter's lazy AST traversal; `LambdaValue` uses
  Truffle's `CallTarget + Object[]` for positional pre-compiled
  invocation. Unifying would force either Truffle into the IR
  interpreter (wrong layering) or name-keyed lookup into the Truffle
  path (defeats its purpose). The "kept in sync" cost is real but
  minor — only capture-by-value/reference-style changes would touch
  both. Entry preserved as a tombstone so the question isn't
  re-opened without new information.
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
- **Match-destructure desugar `IrSort.named("_")` leak ✅ landed for
  alt syntax.** `AltParser.desugarStructuralDestructure` now threads
  `inferMaximalSort(scrutinee)` through the outer let, so record-literal
  scrutinees give `Structural`, call scrutinees give the callee's
  return, etc. The `"_"` sentinel still appears only as a genuine
  don't-know fallback. S-expr `Parser.java` left as-is — it's the
  stable test parser with no sort-env tracking; revisit if a test
  surfaces a real leak.
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
- **Union return over mutual recursion isn't gate-provable (engine gap).**
  `isEven`/`isOdd` with `[Int:0|1]` returns can't be discharged: it needs
  reasoning from a disjunctive *hypothesis* (`r_0 == r_1 ∧ r_1 ∈ {0,1} ⟹
  r_0 ∈ {0,1}` — the call's union-narrowed result), which the Or-*goal*
  discharge doesn't cover, and which can't be proof-authored (overloaded).
  Pinned by `PlaygroundIntegrationTest.unionMutualRecursion_…`. (This used to
  ship in `App.DEFAULT_CODE` and errored the playground on first Run; the
  default was replaced 2026-06-01 — see below.)
- **Playground default ✅ replaced (2026-06-01).** `App.DEFAULT_CODE` is now a
  hand-written-proof tour: `inc` (auto linear-bound), the recursive `Leaf`/
  `Split` proof structs, and `quirk = x*(x-1):[Int:@>=0]` rescued by
  `proof quirk = Split(x>=1, Leaf(), Leaf())` → evaluates to 25. Compiles past
  the gate and runs; the Receipts view shows `quirk` discharged via proof.
  Pinned by `PlaygroundIntegrationTest.playgroundDefaultTour_…`.
- **Playground uses the alt parser** (was "S-expr only" — stale): `App.onRunClicked`
  calls `PontifCompiler.compileAlt`. The S-expr `Parser` remains the stable test
  surface; the `proof`/recursive-struct forms are alt-only.

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

## Receipt-graph refinement — custom issuers as type-checked Pontif functions

*Recorded architectural direction, not an immediate slice.*

The receipt-graph subsystem currently sits parallel to the dependent-type
machinery rather than being driven by it (see "How much of the dependent
type machinery is leveraging the receipt graph?" — answer: almost none).
The architecturally clean move that resolves this without weakening the
trust base, and simultaneously unlocks user-defined proof systems
(traction, Tri-logic, anything algebra-specific), looks roughly like:

**Receipt-graph nodes become a runtime-accessible value with structure
Pontif can pattern-match on.** A custom issuer is a Pontif function of
shape:

```
function refine(b: Branch): [List<Branch>:
    covers(@, b) & disjoint(@) & all-recursively-dischargeable(@)]
```

The construction precondition `covers & disjoint & all-recursively-
dischargeable` is the proof of validity. The kernel decides it via the
machinery that already gates ordinary Pontif functions:

- **Coverage**: match totality (the `PredicateArithmetic` kernel decides
  union-covers-domain for the integer/bool fragment).
- **Disjointness**: overload-overlap rejection (the same kernel decides
  pairwise unsatisfiability).
- **Per-leaf discharge**: refinement satisfaction (existing
  `Refinements.satisfies` + `BoundAnalysis`).

**Trust does not move.** The Drafter stays Java-trusted (deterministic,
small, no reasoning). The Notary stays Java-trusted (refutation-only). A
user's custom issuer is *no more trusted than any other user function* —
its outputs are values whose validity is gated by refinement at
construction time. If Pontif type-checks the issuer, it's correct; if it
doesn't, no value is constructed and no proof is asserted. Both outcomes
are safe.

**The clean payoff: nonlinear discharge via piecewise-linear case
analysis, kernel-verified.**

Worked example. Prove `(x-3)*(x+5) >= -16` for any integer x. The
linear-bound engine can't handle this directly — it's a nonlinear product
of two atoms, and `BoundAnalysis` treats the whole product as a single
opaque atom (bounded only by sign reasoning, which gives nothing
useful here).

A custom issuer refines the single branch `(unconditional, prove
(x-3)*(x+5) >= -16)` into three sub-branches:

```
branch A [x >= 3]:          goal: (x-3)*(x+5) >= -16
branch B [-5 < x < 3]:      goal: (x-3)*(x+5) >= -16
branch C [x <= -5]:         goal: (x-3)*(x+5) >= -16
```

The kernel verifies (no double-counting; the three guards partition
ℤ exhaustively). Each leaf is now a piecewise problem:

- Branch A: under `x >= 3`, both `(x-3) >= 0` and `(x+5) >= 8`, so the
  product `>= 0 >= -16` by `BoundAnalysis`'s opaque-product rule.
- Branch B: the minimum of `(x-3)*(x+5)` on `[-4, 2]` is at `x = -1`,
  giving `(-4)*4 = -16`. A second refinement splits this case on
  `x <= -1` vs `x >= -1`, each closing via linear bounds on `(x-3)` and
  `(x+5)` separately.
- Branch C: under `x <= -5`, both `(x-3) <= -8` and `(x+5) <= 0`, so the
  product `>= 0 >= -16` again.

**The whole proof is recorded in the refined receipt graph.** No step
happens off the books; every case is named and verified. The custom
issuer can be inspected, replayed, and audited. The trust comes from
the kernel checking each refinement step at construction time, not from
trusting the issuer's logic. **Traceability and trust come from the
same property.**

**The recursive case (infinite streams).** A refining issuer can recurse
indefinitely on case-splits — terminating by Pontif's structural-
recursion / match-totality guarantees, exactly the same property that
makes ordinary recursive functions trusted to terminate. This means
decision-procedure-shaped proofs (Cooper-style quantifier elimination,
simplex-style case enumeration) become user-writable as recursive Pontif
functions, with the kernel verifying soundness at each split.

**What this changes elsewhere:**
- The "issuer plugin interface (Maven-style)" deferred item largely
  dissolves — custom issuers are just user functions; no plugin protocol
  needed beyond the language itself.
- The "Deep work — oracle territory" section shrinks. Piecewise-linear
  nonlinear reasoning is no longer oracle work; only the parts that
  *can't* be expressed as piecewise-linear case analysis over integer
  ranges remain (genuinely transcendental shapes, undecidable fragments,
  quantified statements that don't admit case enumeration).
- The compile-time function-check path could consult custom issuers'
  outputs — closing the "receipt-graph and compile-time check are
  parallel, not integrated" gap noted elsewhere.

**Design call when this is taken on:**

What exactly goes in `ClosingReceipt`'s refinement clause? Three
candidates:

- Strict: `[ClosingReceipt: discharge(graph.facts(@.ref), @.conclusion)]`
  — Pontif must re-verify the obligation from the named path facts.
  Requires `discharge` callable from Pontif (in some form).
- Structural: `[ClosingReceipt: substituted(@.ref).implies(@.conclusion)]`
  — the substituted goal at the referenced branch implies the
  conclusion. Less about specific decision procedures, more about
  logical entailment.
- Loose: well-formedness only; the payload carries the proof witness
  for third-party verification. Snake-oil territory unless signed.

The middle ground is probably the design sweet spot, but it's a real
choice.

### Implementation working-session refinements (2026-05-31)

Worked through *how* to build it; surfaced corrections and a sharper
design. **These supersede the worked-example analysis above and the
matching claims in `docs/receipt-graph-refinement.md`** — update both
when this is taken on.

**Correction — the worked example does NOT close today, and not for the
reason given.** Branches A/B/C above all reach `NOT DISCHARGED` on the
current engine. `BoundAnalysis` treats `(x-3)*(x+5)` as one opaque atom
(`LinearForm.normalize`: a `Mul` with neither side constant → opaque),
so it never bounds the two factors. The only fallback is `SignAnalysis`,
which *loses the shift*: `signFromHypotheses` fires only when the
hypothesis is about the literally-identical expression, so `sign(x-3)`
under `x>=3` is `sign(x) ⊕ sign(-3) = POSITIVE ⊕ NEGATIVE = TOP`. Product
→ TOP → no bound. So even branch A (the "easy" one) fails. The doc's "no
new trusted code" claim is wrong: a base-engine addition is a hard
prerequisite.

**Slice 0 (prerequisite, independently useful) — interval × interval
multiplication. ✅ landed (2026-05-31).**
- `Interval.multiply(other)`: four-corner method (`lo·lo, lo·hi, hi·lo,
  hi·hi`, take min/max). Needs a general saturating mul where *either*
  operand may be `±∞` (the current `satMul` assumes a finite
  coefficient). The `0·∞` corner is **forced to 0** — see design note.
- `BoundAnalysis.atomBound`: when the atom is a genuine opaque `Mul(l,r)`,
  recursively `bound(l)`/`bound(r)`, multiply, and **intersect** into the
  existing hypotheses+sign result (they compose — `x*x` over `[2,5]` gets
  `[4,25] ∩ [0,∞)`). Recursion terminates on subexpression structure.
- Closes branch A (`[0,∞)·[8,∞)=[0,∞)`), branch C
  (`[-∞,-9]·[-∞,-1]=[9,∞)`), AND the long-standing `x*y>=6` from
  `x>=2,y>=3` gap (`[2,∞)·[3,∞)=[6,∞)`) — *with no case-split at all*.
  Moves product-magnitude out of oracle territory.
- Regression watch: factorial `n_0*r_1>=1` (mult → `[1,∞)`, same as the
  sign path); `x*x` unbounded (sign still carries it).
- No design ambiguity; executable as-is.

*Landed:* `Interval.multiply` (four-corner, with explicit `satMulFull`
where the `0·∞=0` soundness decision is commented at its site) +
`BoundAnalysis.atomBound` recursing into opaque-`Mul` factors and
intersecting the interval-product into the hyp+sign result. Closes
`x*y>=6` and isSparse branches A/C with no split; the un-split middle
region B correctly *refuses* (pinned as a soundness test — the case that
motivates the next slice). New `IntervalTest` (12) + 6 `BoundAnalysisTest`
cases; full suite green, no regressions.

**Design note — `0·∞=0` is forced by soundness, not convention; `∞`
never becomes a value.** Forcing counterexample: `[0,0]·[-∞,∞]` has true
product `{0}`; any `0·∞=k≠0` yields `[k,k]`, excluding the real value 0 →
unsound (would discharge `result≠0` for an identically-zero expression).
Set-level trichotomy: `0·∞` (really `0·S`, S finite-unbounded) → solution
set `{0}`, forced; `1/0` (`q·0=1`) → empty set, *no* sound value
(Lean/Isabelle's `1/0=0` is a totality *convention* that knowingly breaks
`(1/x)·x=1` at 0 — chosen, not forced); `0/0` (`q·0=0`) → everything,
genuine `⊤`. Principle: `∞` is a lattice sentinel ("unbounded above"),
never a `SymExpr` value — so there is no extended-integer algebra
(`ℤ∪{±∞}` is no ring) to commit to, and `∞` always erases back to a
one-sided / absent refinement. Consistent with `Frac` already rejecting
`denom==0` and with totality gating any future division.

**Design refinement — conservative morphisms make coverage/disjointness
invariants, not checks.** Supersedes the "Coverage = match totality /
Disjointness = overload-overlap" framing above. Instead of "user produces
`List<Branch>`, kernel then *checks* coverage+disjointness via
`PredicateArithmetic`," the only splitting primitive is a binary cut:

```
splitOn(branch, p) → { branch ∧ p , branch ∧ ¬p }
```

- Coverage is excluded middle (`(G∧p)∨(G∧¬p)=G`); disjointness is
  non-contradiction (`(G∧p)∧(G∧¬p)=⊥`). Both hold *by construction* —
  invalid partitions are unrepresentable. **This removes
  `PredicateArithmetic` from the split-validation path entirely** (one of
  the two reach-limiting kernels drops out).
- `¬p` stays symbolic (never simplified, for conservation); only *leaf
  discharge* interprets guards, and the fallible engine can only fail-safe
  (honest non-discharge, never false discharge). Tree-soundness and
  leaf-soundness are cleanly separated.
- No expressiveness lost: any finite predicate-partition is a tree of
  binary cuts (the doc's 3-way A/B/C = two `splitOn`s). The cut tree *is*
  the traceable proof.
- Limit: `splitOn` conserves the *domain partition* only. Goal-rewrite
  (`obligation ⟺ obligation'`) and induction (back-reference IH, already
  shipping) are separate morphism classes with their own conservation
  laws. Kit is `{split, discharge, induct}`; build only `splitOn` now,
  generalize the morphism interface only once a second instance exists.

**Leaf endgame + termination.** Branch B (bounded interval with interior
minimum) needs recursion: `splitOn` down to singletons `x=-5..2`, where
interval-mult is *exact* and each leaf is a constant comparison.
Termination measure for the split-recursion = the interval's width
(strictly decreasing) — answers open-Q#5 for this morphism class.

**Feasibility framing — per-application validation, no universal
verification.** For the "human writes the split" milestone the split is
*concrete data*, so nothing is universally quantified — you validate
ground output, exactly as the Notary validates an emitted receipt (it
never trusts issuer *logic*). The "is `refine` valid for all inputs"
question only arises later, when a split becomes a reusable Pontif
function. So the hard architectural fork is off the table for
feasibility; it's answerable entirely in Java with no Pontif-side
`SymExpr` / `refine`.

**Re-slicing (feasibility-first):**
- Slice 0 ✅ landed — interval multiplication (above). Unblocks every leaf;
  independently useful.
- Slice 1a ✅ landed (2026-05-31) — conservative combinator + validator.
  `Refinement` (sealed `Leaf`/`Split`): the only constructor is
  `splitOn(p, whenTrue, whenFalse)` storing *only* `p` — the `¬p` guard is
  *derived* (`complement`, exact op-flip over the total order), so a
  non-partition is unrepresentable; coverage (excluded middle) and
  disjointness (non-contradiction) are structural, never checked.
  `RefinementValidator` walks the tree reusing `PathFacts` +
  `IntegerDischarge`, accumulating split guards, verifying only per-leaf
  discharge, and returns a tree-shaped `Outcome` (fully traceable: which
  guard each leaf sat under, whether it closed). Cmp-only predicates for
  now (And/Or De Morgan deferred — compose binary cuts instead).
  **Headline test:** `isSparse` `(x-3)*(x+5) >= -16` closes end-to-end via
  splits A `[x>=3]` / C `[x<=-6]` (both via Slice-0 interval mult) + region
  B `[-5..2]` recursed to singletons (exact). Negative controls: un-split
  doesn't close; an *insufficient* split (valid partition, open leaf)
  reports unverified with the trace pinpointing the open leaf. Purely
  additive (no existing file touched); full suite green.
- Slice 1b — proof-supply into the issuer. **Issuer integration ✅ landed
  (2026-05-31):** `BuiltinIssuer.attemptAll/close(graph, Map<GraphReference,
  Refinement>)` — a branch the engine can't discharge is rescued by a
  supplied proof the kernel validates (`RefinementValidator`); discharged
  obligations carry `Attempt.provenByRefinement` and receipts attribute to
  `REFINEMENT_ISSUER_ID`. Sound by the validator: `isSparse` rescued, `bad`
  (false) refused by any proof. This is the **recourse** that gates step B.
  **Reviewable-artifact half ✅ landed (2026-06-01):** now that proofs parse
  (`proof f = …`), `ReceiptGraphReport` binds in-source proofs via the shared
  `pontif-receipts/ProofBinding` (same binding the gate uses, so Run and the
  Receipts view agree) and renders a proof-rescued branch as
  `-> discharged [via proof; notary: accepted]` instead of `NOT DISCHARGED`
  (pinned by `ReceiptGraphReportTest.handWrittenProof_showsDischargedViaProof`).
  Rendering the split *tree* itself is still TODO (the report shows the branch
  outcome, not the proof's internal case-splits). **Still to do — Notary
  proof-verification:** for a `REFINEMENT_ISSUER_ID` receipt the notary should
  re-run the validator, not just attempt refutation (current soundness rests on
  the validator gating emission at `close`-time / the gate at compile-time —
  sound for those flows, but the notary should independently re-check).
- Slice 2 — split supplied as data (not hardcoded) + recursion to
  singletons for region B.
- Slice 3 — `refine` as a Pontif function: Pontif-side `SymExpr`/`Branch`,
  calling user code during checking, validator on its output. The
  language lift; open-Qs #1/#2/#4 live here.
- Slices 0–2 are Java-only and deliver the reviewable text artifact;
  Slice 3 concentrates the language work.

**Proof-file model (separate file, like a unit test — with one
reframe).** Bespoke proofs live in a separate file, opt-in where the
auto-prover falls short, one subject per file, independently checkable,
reviewable, distributable as a library. *But* a proof is a **required
lemma keyed to an obligation, not an optional test**: if the obligation
doesn't auto-discharge, the proof file is load-bearing for the type's
validity — its absence or staleness must hard-fail compilation. Binding
is semantic (`proves isSparse : @ >= -16`, matched to the graph);
staleness is caught by a `skeletonMatches`-style re-draft-and-compare
(the Notary already does this for receipt graphs). The conservative
combinators are what make the hand-authored file safe — it can express an
*insufficient* proof (leaves don't close) but never a *wrong* one.

---

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
  (nonlinear closed form) and multi-atom hypothesis constraints
  (`x+y>0` bounds neither alone — needs Fourier–Motzkin / Presburger).
  (Product *magnitude*, `x*y >= 6` from `x>=2,y>=3`, **left** oracle
  territory when the receipt-graph-refinement Slice 0 interval-multiply
  landed 2026-05-31 — `[2,∞)·[3,∞)=[6,∞)`, no case-split. See that section.)
  Z3-style arithmetic, an inductive prover, or a hand-written issuer
  module fit there.
- **Proof Authority (PA) trust model — roadmap goal, low priority.**
  Borrow from how Certificate Authorities work: designate certain
  issuers / oracle modules as trusted *Proof Authorities*, and
  receipts they produce are accepted by attribution rather than
  independent validation.
