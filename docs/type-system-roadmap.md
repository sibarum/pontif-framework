# Type-system roadmap — the plan of record

*Status: PLAN-OF-RECORD (drafted 2026-07-18). This is the single ordered plan that ties
the type-system's separate campaigns to one target state, records the dependencies
**between** them, and gives the nominal-subtype / `Assignability` migration the home it
currently lacks. It supersedes nothing — it **indexes** the existing partial docs
(`type-records.md`, `inference-unification.md`, `dispatch-unification.md`) and adds the
connective tissue they don't have. Named `type-system-roadmap` (not "convergence")
deliberately: the TODO cluster "Type-system convergence — 4 facets" is a **separate,
parallel** goal (the C5 binding substrate), not this roadmap.*

> **Why this doc exists.** A 2026-07-18 audit found the plan fragmented: the *model* is in
> `type-records.md`, one campaign is "done" in `inference-unification.md`, one is "in
> progress" in `dispatch-unification.md`, the `Assignability` engine's increments live only
> in a Javadoc + parser comments, and the whole nominal-subtype migration is **absent from
> `docs/TODO.md`**. Worse, the one hard *cross-campaign* dependency — the `CoercionResolver`
> deletion is gated on dispatch going post-link — is written down nowhere. This doc fixes
> that. The governing intent (James, 2026-07-18): **push the type system to the target state
> before making further changes to it** — so a change like `AlgebraicDispatch` (the thread
> that surfaced this) waits behind the convergence rather than adding a fresh divergence.

---

## 1. The target state

The end-state has three load-bearing commitments. Two are about *representation* (what "the
type here" means); one is about *engines* (how many deciders answer each question).

### 1a. Three records per position (representation) — from `type-records.md`

Every typed position carries up to three separate records, consumed by three mechanisms:

| Record | Question | Consumer |
|---|---|---|
| **Declared Sort** | what was *asserted* here (the annotation) | nominal / method / trait dispatch |
| **Inferred Sort** | what we can *prove* about the value statically | refinement machinery (gates, proof, narrowing) |
| **Value Type** | what the value *actually is* at runtime | runtime dispatch |

The bug that exposed the collapse (methods-on-aliases) and the runtime "single record via
re-stamp" decision are in `type-records.md`; not repeated here.

### 1b. One decider per question (engines) — the "no second typer to drift" law

There must be exactly one engine answering each type-level question, referenced at every
stage, so stages cannot disagree (`README.md` "One inference engine, every stage"):

- **"What is the narrowing/type of an expression?"** → **`NarrowingInference`** (one engine).
- **"Is a value of sort A usable where B is required? / what does this binding require?"** →
  **`Assignability`** (`isA` / `assign` / `construct` / `cast`), the nominal-subtype engine.
- **"Does this value inhabit this refinement sort? / does A's predicate imply B's?"** →
  **`Refinements.satisfies` / `imply`** (the refinement-proof kernel, in `pontif-core`).

`Assignability` is the *nominal/structural* decider; it **delegates** the refinement-precise
leaf question to `Refinements.imply` (it does not absorb it — `pontif-core` cannot depend on
`pontif-ir`). The target is: nominal/assign decisions have one home (`Assignability`),
refinement-proof has one home (`Refinements`), and the two compose in one direction.

### 1c. The facade — `sibarum.pontif.types.TypeSystem`

A strangler-fig facade (`TypeSystem` / `DelegatingTypeSystem`) is the seam callers use so
they stop reading one raw `IrSort` and guessing. It forwards to the engines above today;
the target is that it *owns* the dispatch/coercion surface (`declaredSortOf`, `infer`,
`coercionFor`, `dispatch`) and the scattered copies are deleted behind it.

### 1d. The no-unproven-runtime-check law (a project-wide invariant)

*Ratified James, 2026-07-18. The most important commitment here; it governs every gate.*

**The compiler must prove the runtime will always succeed. A runtime decision is legal only
when the compiler has verified the runtime will always have what it needs to make it.
Otherwise: prove it, mark the exception sort `[!!]`, or reject at compile time. A silently
stamped runtime check is a lie.**

The sharp edge — this is *not* "no runtime decisions ever":

- **Legal — a proven-total runtime *determination*.** Union dispatch where every member has a
  handler; `match` totality. The runtime picks the arm; it *cannot* fail, because coverage was
  proven at compile time.
- **The lie — an unproven runtime *check that can throw*.** A refinement/claim check stamped
  because the compiler *couldn't* prove the fit, deferring the obligation to a runtime that may
  raise. The construction gate's `UNKNOWN → stamp` arm ([ConstructionGate.java:237]) is the
  canonical case.

Runtime checks were an early crutch (before the systems existed to decide things at compile
time) that became a catch-all. The direction of travel is already set: the **call gate** and
the **2026-07-12 dependent-let-claim gate** both adopted *"no runtime refinement checks by
default"* — [ConstructionGate.java:218] literally says *"never stamp a runtime check"* in the
let-claim path, while the general field path a few lines down still stamps. **Bringing every
remaining stamp into line is the law; every surviving stamp is a flagged violation.**

`[!!]` (the exception/hazard sort — the *only* sanctioned way to defer a genuinely-undecidable
check to runtime) is **not built**, and the intent is to avoid needing it. So in practice the
default outcome for "can't prove it" is a **compile error**, not a deferral.

**Enforcement — audit RUN (2026-07-20; Phase 0 of the C3 finish-line plan).** Findings:
`ConstructionGate` is the **sole** producer of `IrExpr.Record.runtimeChecks` (every other site
only consumes/propagates: `IrCompiler:344`, `IrInterpreter:231`, `TruffleLowering:185`,
`MethodOperatorResolver:260`). Exactly **two live stamp sites** violate §1d — the field path
[ConstructionGate.java:421] (`gateRecord`, `UNKNOWN → checks.put`) and the non-dependent
let-claim path [ConstructionGate.java:241] (`gateClaim`, `UNKNOWN → stamp claim`) — plus **one
sanctioned exception**, the parametric-`Stream` element check [ConstructionGate.java:208] (§8.6;
the genuine `[!!]` candidate). The dependent-claim sub-path [ConstructionGate.java:225-233]
already proves-or-errors and never stamps — that is the model the two violators must adopt. The
`classify` verdicts feeding the stamp (→ must become prove-via-`Refinements`-else-compile-error):
refined same-base not-provably-total [:653], predicate outside the arithmetic fragment [:640],
`Int`→*refined*-`Decimal` [:625], arg-sort-unknown [:604]. Out of construction scope (noted, not
yet classified): arithmetic `Div`/`Mod`/`Pow`, `Closure.java:16`, `match` totality — genuine
runtime hazards or proven-total determinations, not unproven-fit stamps.

---

## 2. The campaigns (status board)

Five efforts converge on §1. Two are representation-model, three are engine-unification.
Status markers: ☐ not started · ◐ in progress · ☑ landed.

| # | Campaign | Owns | Status | Doc |
|---|---|---|---|---|
| C1 | **Inference unification** | narrowing = one engine (`NarrowingInference`) | ☑ landed (on master) | `inference-unification.md` |
| C2 | **Dispatch unification** | method/operator/trait resolution → post-link | ◐ **core landed** (phases 1–4 + Cluster 4 — resolution IS post-link); remaining = the cross-module **visibility** model | `dispatch-unification.md`, `cross-module-dispatch.md` |
| C3 | **Nominal-subtype / `Assignability`** | is-a / assign / construct / cast = one engine | ◐ **engine, gates & let-coercion landed** (Phase 0/1, §1d, effective-sort, `CoercionResolver` deleted — §4.5 item 1); remaining = generics, static-cast | **this doc** (§4) |
| C4 | **Three-records model** | Declared / Inferred / Value split | ☑ **effectively landed** (audited 2026-07-21) — landed incidentally through C2/C3; residual = doc reconciliation only (§ notes) | `type-records.md` |
| C5 | **Scoped type-level binding substrate** | dependent sorts, structural traits, Type fragments, sub-traits, generics | ◐ **per-facet** (audited 2026-07-21): generics + sub-traits ☑, dependent-sorts + Type-fragments ◐, structural traits ☐ | `TODO.md` "4 facets", `dependent-sorts.md`, `feature-matrix.md` |

**Disambiguation.** The TODO cluster titled *"Type-system convergence — one scoped
type-level binding substrate (4 facets)"* is **C5** — a parallel axis about *type-level
bindings that reference each other* (generics ≈ dependent types). It is **not** this
roadmap and **not** C3. This doc is the umbrella that indexes both axes; C5 keeps its own
war docs.

### Campaign notes

- **C1 (inference) — done, on master.** `NarrowingInference` is the sole expression-typer:
  `SortChecker.inferSort` → `inferFloor` ([SortChecker.java:2226]),
  `AltParser.inferMaximalSort` → `inferFloor` ([AltParser.java:2039]); all four stages route
  through it; TODO marks Cluster 5 done. **Precondition SATISFIED (verified 2026-07-18):**
  `war/scope-aware-narrowing` is fully subsumed by master (master 0 behind / 207 ahead), so
  C3 already builds on the unified state — no merge needed.
- **C2 (dispatch) — core landed; visibility model remains.** Cluster 4 + Phases 1–4 all
  shipped (per `dispatch-unification.md`, which now marks the phase list closed): operators route
  post-link by both operands, **methods resolve on the receiver sort post-link** (Phase 2 — the
  parse-time `methodNameForReceiver` is gone), trait dispatch is receiver-sort resolution, and the
  parser is de-blinded. The **one remaining C2 piece** is the cross-module **visibility** model
  (import-by-association under the orphan rule) — its own war, `cross-module-dispatch.md`. **NB
  (2026-07-21):** Phase 2 landing means the roadmap's old "C3 blocked until C2 Phase 2" edge (§3)
  is **discharged** — see the reframed §3.
- **C3 (nominal-subtype) — engine, gates & let-coercion landed.** `Assignability`
  ([pontif-ir/types/Assignability.java]) is a pure engine over `IrSort` + a `TypeCatalog`. Landed:
  Slice 0/1, the §4.2 engine gaps, Phase 0/1 (ConstructionGate nominal leg → engine), the §1d
  stamp-kill, the effective-sort consumption across the construction/claim/**call** gates
  (§4.6+ archive), and **let-coercion (§4.5 item 1, `22578bf`): `CoercionResolver` deleted, the parser
  decides trait-free coercion via `Assignability`.** Remaining: generics/type-args, static-cast wiring
  (§4.5). This doc is its plan-of-record (§4).
- **C4 (three-records) — effectively landed (audited 2026-07-21); code is ahead of `type-records.md`.**
  The split is realized as three distinct artifacts: **Declared** = `LetIn.claim()` / param
  `sort()` (+ `MethodOperatorResolver.declaredReturns`); **Inferred** = `NarrowingInference`,
  materialized as the `EffectiveSortLens` on `CompiledModule`; **Value** = runtime
  `SymExpr.Record.typeName()` read by `DispatchTable`. All three migration items shipped —
  *incidentally, threaded through C2/C3 commits*, which is why the board was never checked off:
  (i) nominal dispatch consults the Declared record via `MethodOperatorResolver.nominalReceiverSort`;
  (ii) the `parseLet None→inferredSort` collapse no longer *discards* the Declared sort (both records
  are carried — the binding sort is the Inferred, the annotation rides `LetIn.claim`); (iii) the
  re-stamp discipline is retired (demotion is a view, §6.5 — `projectDemotion` is gone). Item 2
  (declared-first method routing) **landed 2026-07-21** — `MethodOperatorResolver.nominalReceiverSort`
  is now declared-first (§6.6). The audit's diagnosis was directionally right but mislocated the leak:
  a *top-level* demoted `let b:Point = point3dValue` never leaked — its binding sort is narrowed to the
  declared `Point` at parse time (`AltParser.nominalBinding`), so inference already yields the `Point`
  head. The real leak was in **local** bindings: `MethodOperatorResolver` re-infers the value sort and
  binds the local var to the concrete Inferred sort (`Point3D`), so a local demoted `let` routed methods
  on `Point3D`. The fix reads a `Var` receiver's own Declared claim from a lexically-scoped `localClaims`
  map (sourced from `LetIn.claim`, cleared for shadowing lambda params, isolated per function), else the
  Inferred head; the top-level `Call`/`declaredReturns`/`_tuple` path is unchanged. The name-collision
  care point dissolves: a shadowing local is a `Var` carrying its own lexical entry, never a name-hit in
  `declaredReturns`. The named `declaredSortOf` facade was never built (goal met via `LetIn.claim`).
- **C5 (binding substrate) — per-facet (audited 2026-07-21).** Tracked in `feature-matrix.md`;
  independent of C3's finish-line except where generics touch `Assignability` type-args (§4 gap).
  - **Generics / type-args — ☑ landed** in the shipping engines (`NarrowingInference.unifyTypeArgs`,
    `ConstructionGate.deriveAndCheckTypeParams`, dispatch; ~13 `TypeParameter*Test`). **Ahead of C3**:
    `Assignability` has *zero* type-arg support — building it in is **C3's item 2** (§4.5), not
    duplicate work.
  - **Sub-traits — ☑ landed** (`trait B : A`, transitive dispatch; `TraitExtendsTest`). *The
    `TODO.md` cluster understates this as "deprioritized / needs Stream first" — stale; it landed
    independently of the Stream substrate.*
  - **Dependent sorts — ◐ partial**: the call-gate (`StaticDispatch.substituteSiblings`) and
    let-claim gate (`ConstructionGate.gateClaim`/`dischargesUnderScope`) are landed + tested; the
    struct-**field**, named-method-contract, and value-indexed cases are unbuilt. (`feature-matrix.md`
    N1's "zero tests" note is now stale for the call case.)
  - **Named Type fragments — ◐ partial**: complete-sort aliases work (`ReusableSortTest`); baseless
    predicate fragments (`let gtz:Type=[@>0]` applied as `[Int:gtz]`) are unbuilt (N3).
  - **Structural (anonymous) traits — ☐ parse-only**: `Type{…}` parses but method dispatch through
    such a param is unwired (N2). *(Distinct from structural **sorts** — record width-subtyping —
    which are landed; don't credit one for the other.)*
  - `[!!Sort]` escape hatch is advertised in error messages but **not built** (parser rejects unary
    `!`) — self-flagged in `TODO.md`, still open.

---

## 3. The dependency graph (the part written down nowhere until now)

```
C1 inference ✔ ──► (on master) ──► everything builds on the unified state
                                            │
C2 dispatch ── Phase 2 (post-link resolution) ✔ LANDED
     │            (methods/traits resolve post-link — the enabling infra exists)
     │  remaining: cross-module VISIBILITY model (own war; not a C3 blocker)
     ▼
C3 Assignability
     ├─ gate-side ConstructionGate delegation        ✔ (Phase 0/1, §1d, effective-sort)
     ├─ engine gaps (refinement, Int→Decimal, intersection, function-sorts)  ✔
     ├─ generics / type-args                          ☐ (NOT blocked)
     ├─ static-cast wiring                            ☐ (NOT blocked)
     └─ CoercionResolver deletion  ── needs: RELOCATE the coercion decision off the
            parser to post-link (a C3-internal move — the post-link infra now exists)
```

**The former "one hard cross-campaign gate" is discharged.** The reasoning was: `CoercionResolver`
is invoked at the **parser** ([AltParser.java:1888] / `:4605`, still true), and the parser cannot
see trait satisfaction — a **post-link** fact ([AssignabilityContext.fromModule] needs a finished
`IrModule`). The roadmap treated this as *"blocked until C2 Phase 2 moves resolution post-link."*
**C2 Phase 2 has landed** — but Phase 2 moved *dispatch resolution* post-link, it did **not** move
the *coercion decision* off the parser. So the real remaining step is a **C3-internal relocation**:
move the parse-time `coercionFor` decision to a post-link gate (exactly as `ConstructionGate`
already runs post-link with `AssignabilityContext.fromModule`), then delete `CoercionResolver`. This
no longer waits on C2; it waits on doing the relocation (§4.5). Only the `TraitCast` *permissiveness*
nuance (§4.3) genuinely touches C2's visibility model.

**C1 is on master** (the `war/scope-aware-narrowing` branch is fully subsumed — master 0
behind / 207 ahead, verified 2026-07-18), so C3 already builds on the single-engine narrowing
state. No merge step.

---

## 4. C3 — the `Assignability` finish-line (the new plan)

### 4.0 Definition of done

`Assignability` is "across the finish line" when: (a) every is-a / assign / construct /
static-cast **decision** in the tree is made by it (or by `Refinements.imply` for the
refinement-proof leg it delegates); (b) `CoercionResolver` and the fit legs of
`ConstructionGate.classify` are **deleted** or reduced to thin adapters; (c) a
**differential harness** proves the engine agrees with the copies over the corpus *before*
any copy is deleted; and (d) per §1d, **no construction path stamps an unproven can-throw
runtime check** — an unprovable fit is a compile error (or explicit `[!!]`), never a
`runtimeChecks` stamp. Until (c) exists, no deletion is safe.

### 4.1 Do this first — the differential harness (the license to delete) — ✅ LANDED (2026-07-18)

`AssignabilityDifferentialTest` (pontif-ir) drives `Assignability.assign` and the legacy
`CoercionResolver.resolve` over a shared-catalog corpus and compares the one shared question —
*is this an implicitly-legal binding?* A divergence outside the documented `KNOWN_DIVERGENCES`
(or a listed one that stops diverging) fails the test, so drift on either engine is caught.
This is the deletion baseline for the whole C3 migration.

**Findings (10-pair corpus): now 8 agree, 2 diverge (both "engine stricter").**
- ~~engine WEAKER — `Int → Decimal`~~ **CLOSED** via the new `Assignment.COERCE` outcome (§4.2):
  a lossless primitive conversion, distinct from view-`WIDEN` (per §6.5, it changes the concrete
  `Long→BigDecimal`). Now agrees with the legacy.
- **engine STRICTER — accepted, the target is better:** `Point → Showable` (non-impl) and
  `Point → AnyNumber` (non-member). The engine rejects eagerly (correct); the legacy **defers**
  — `TraitCast` (satisfaction checked later by `SortChecker`) / abstains to `None` on the
  unresolved alias. The deferred stage rejects these anyway, so migrating tightens the check
  with no valid-program regression. These stay in `KNOWN_DIVERGENCES`.

*The harness is corpus-extensible: add refinement / generics / intersection / function-sort
pairs as those gaps are worked (each new pair either agrees or joins `KNOWN_DIVERGENCES`).*

### 4.2 Engine capability gaps (build inside `Assignability`)

| Gap | Status | Size | Notes |
|---|---|---|---|
| Refinement-precise leaf subsumption | ✅ **DONE** (2026-07-18) | M | `structurallySubsumes` delegates a refined-`sup` leaf to `Refinements.imply` (compile both sorts, abstain on non-linear predicates). Also fixed a latent unsoundness: `sameType` was predicate-blind (equated `[Int:@>=0]` with `[Int:@>0]`) — now compares predicates. Pinned by `AssignabilityTest.refinementPreciseSubsumption` |
| `Int→Decimal` / primitive coercion | ✅ **DONE** (2026-07-18) | S | new `Assignment.COERCE` outcome — a lossless primitive conversion (concrete changes), distinct from view-`WIDEN`; structs never get it. Pinned by `AssignabilityTest.numericTowerCoerces_butStructsDoNot` |
| `construct` fit as a **two-way** prove/reject decision | ✅ **DONE** (2026-07-21) | M | §1d stamp-kill landed (`5eb9aaa`): the construction/claim gate discharges via `Assignability`+`Refinements` or compile-errors — the `UNKNOWN → runtimeChecks` stamp is gone (sole deferral: parametric `Stream` `[!!]`). Gates now read the materialized effective-sort lens |
| Generics / type-args (`Box[Int]`) | absent (parser guard skips type-args) | L | |
| Intersection sorts | ✅ **DONE** (2026-07-18) | S | `isA` gained the dual-of-union arms (is-a ∩ = every branch; ∩ is-a X = some branch); pinned by `AssignabilityTest.intersectionSubtyping` |
| Method / Dispatch function-sorts | ✅ **DONE** (2026-07-18) | M | `isA` arms: **Method** delegates to `Refinements.imply` (contra-params / covariant-return); **Dispatch** decided directly (same keys, covariant return — imply has no dispatch arm); the two never cross-assign. Pinned by `AssignabilityTest.method/dispatchSortSubtyping`. **NB (2026-07-19):** these two arms are exactly what the Dispatch/Method elimination (`docs/dispatch-method-elimination.md`, §5) makes **capability-driven** — Stage E1 replaces the `instanceof Method`/`instanceof Dispatch` selection here with a call-kind-capability lookup on the unified `CallSig` node (behavior-preserving) |
| Static-cast legality wiring | decision present, unwired | M | currently decided *nowhere*; `IrExpr.Cast` legality is implicit-at-runtime |

### 4.3 Migration targets (wire onto the engine, then delete)

| Decision | Site | Action | Size |
|---|---|---|---|
| struct↔struct `let` assign | [AltParser.java:2098] | landed (Slice 1) | — |
| all other `let` coercions | `AltParser.nominalBinding` (was `coercionFor` → `CoercionResolver`) | ✅ **DONE (2026-07-21, `22578bf`).** The trait-free nominal cases (`IntToDecimal`, `RecordPromotion`, `Demote`, `Mismatch`/`None`, primitives) are decided at the parser **via `Assignability`** (no trait closure needed); `Autobox` stays a parser-local sentinel (re-homes to the generics slice later); only **`TraitCast`** legality is deferred post-link (permissive, as before — eager satisfaction is the C2-adjacent follow-up). `CoercionResolver` deleted. | — |
| construction fit | `ConstructionGate.gateRecord/gateClaim` (`classify`) | ✅ **DONE (2026-07-21, `5eb9aaa`)** — fit is a single-engine query (`Assignability`+`Refinements`) reading the effective-sort lens; the `UNKNOWN → runtimeChecks` stamp is dropped (§1d); demotion projection removed (§6.5). Dependent-claims/type-params orchestration stays. | — |
| parametric base invariance | `SortChecker.sortsExactlyEqual` ([:1051]) | optional — it's exact-equality, arguably not assignment | S |
| cast legality | `IrInterpreter.evalCast` ([:1189]) + `IrExpr.Cast` producers | new wiring for `Assignability.cast` | M (mostly new) |

Note `IrStmt.Coercion` / `CoercionCheck` (user-defined `cast Target:(x:Source)->…`) is a
**different axis** (runtime execution of author coercions), *not* a copy to absorb.

### 4.4 Context-construction burden (the real cost driver)

`AssignabilityContext.of(catalog)` is trivial everywhere but knows **no traits**;
`.fromModule(IrModule)` gives the full trait closure but needs a **finished module**.
Therefore:

- **Gates / checkers** (post-link, module in hand): `.fromModule` is cheap — *easiest place to
  migrate first* (`ConstructionGate` already builds `TypeCatalog.fromModule`).
- **Parser**: has the `TypeCatalog` cheaply but **no trait impls at parse time** → the wall in
  §3. The fix is to stop deciding coercion at the parser: relocate it to a post-link gate (where
  `.fromModule` is cheap), the same move `ConstructionGate` already made. No longer a C2 dependency.
- **Runtime cast** (`IrInterpreter`, a `CompiledModule`): needs a catalog adapter. M.

### 4.5 C3 — remaining work (re-scoped 2026-07-21)

Everything through the effective-sort landing is **done** (differential harness §4.1; the §4.2
engine gaps; Phase 0/1 nominal-leg delegation; the §1d stamp-kill; effective-sort consumption
across the construction, claim, and call gates — see the archived session-logs, Appendix A). The
finish line is now **two C3-internal items, none blocked on C2** (C2 Phase 2 has landed):

1. ✅ **DONE (2026-07-21, `22578bf`) — `CoercionResolver` deleted; `Assignability` decides let coercion.**
   The `let`-binding coercion was a second engine parallel to `Assignability`, made at parse time via
   `coercionFor` → `CoercionResolver`. *As-built (adjusted from the original plan):* a trait-free
   legality question needs no trait closure, so the parser decides it **via `Assignability`** and
   rejects a provable mismatch there (generalizing the slice-1 `structAssignBinding` → `nominalBinding`
   to all trait-free nominal pairs incl. `Int→Decimal`); only trait-dependent legality stays deferred
   post-link (permissive, as before — eager trait-satisfaction is a follow-up). The aggregate sentinels
   (`_record`→`AggregatePromotion`, `_tuple`→`Stream` autobox) stay as parser-local lowering checks.
   Deleted: `CoercionResolver`, `Coercion`, `CoercionContext`, `TypeSystem.coercionFor`, and the
   `AssignabilityDifferentialTest` license-to-delete harness. *(The plan's "widen `gated()` + defer to
   the post-link gate" mechanism was tried and reverted — it over-reached into `gateRecord`, regressing
   valid unknown-sort construction args and colliding with transform-chain lets. Deciding the trait-free
   case at the parser is behavior-preserving and lower-risk.)*
2. **Generics / type-args** in `Assignability` (`Box[Int]`; the parser guard currently skips
   type-args). Also retires `isStreamName`/`Autobox`. **Size: L.** Touches C5 where generics meet
   type-args.
3. **Static-cast legality wiring** through `Assignability.cast` (`IrExpr.Cast` legality is
   implicit-at-runtime today; §4.2, §4.3 `cast legality` row). **Size: M** (mostly new wiring).

**Follow-up (deferred from item 1):** *eager trait-satisfaction for `let`s* — a `let x:SomeTrait =
nonSatisfier` is still permissive (deferred), as it was before; gating bare-trait claims so it errors is
the C2-visibility-adjacent residue (needs the trait closure to be complete cross-module).

**Loose ends (own tracks, not on the C3 finish line):**
- **Dispatch-specificity bug** — no specificity rules anywhere; a *total* overload set (`bark(Dog)`
  + `bark(Animal)`) throws runtime "Ambiguous dispatch" on a concrete `Dog` instead of preferring
  the specific overload, while compile-time `OverloadOverlap` allows the overlap. Lives in
  `DispatchTable`.
- **`match`-arm effective-sort refinement (low pri)** — `[d:Dog] -> bark(d)` works; the
  scrutinee-reuse form `[Dog] -> bark(a)` (narrow the bound scrutinee) is the nice-to-have.

**Definition of done** is unchanged (§4.0): all four decisions made by the engine; `CoercionResolver`
+ the `ConstructionGate` fit legs deleted/thinned (item 1 is the last of these); harness green.

## 5. Motivating first customer — `AlgebraicDispatch` → the Dispatch/Method elimination

The differential-programming work (`assign proof f:Algebraic` + runtime `pontif.algebra`
reflection, landed 2026-07-18) wants a compile-time-safe `$f[Decimal].ast` — a `Dispatch` value
that *statically* carries "algebraic," so `.ast` is a type error on a non-algebraic function.

**This has its own plan of record now: [`docs/dispatch-method-elimination.md`](dispatch-method-elimination.md).**
Building `.ast` surfaced that `Method`/`Dispatch` are hardcoded (parser keywords + two bespoke
`IrSort` kinds + name/`instanceof` logic at ~40 sites) *in a way that makes the type system hard
to extend*. Ratified with James (2026-07-19): the right move is to **remove that hardcoding**, not
to bolt `.ast` onto it. So the plan pivoted from the earlier "`AlgebraicDispatch <: Dispatch`
intersection view" (a stepping stone — see the note below) to:

- **One generic `IrSort.CallSig(typeName, paramSorts, paramNames, returnSort)`** replacing
  `IrSort.Method` + `IrSort.Dispatch`; `typeName` is data.
- **Two builtin call-kind capability traits** (function-style / dispatch-style) that *drive*
  subtyping + value-satisfaction, selected by which the head type is-a — never by name. `Dispatch`
  and `Method` become ordinary types carrying a capability; the `Type(Args):Return` syntax is
  attribute-driven and parser discrimination is purely syntactic (trailing `:Return`).
- **Acid test:** adding a future callable type touches *no* type-system code. `.ast` is then
  Stage E2 — purely additive on the general machinery, which *proves* the acid test.

Sequencing: **Stage E1** (the elimination, behavior-preserving) then **Stage E2** (`.ast`). Both
are C2-independent (the `.ast` gate is post-link). Slices A + B (below, `1890fda`) are the
substrate; Slice B's `Algebraic` marker + `[Dispatch & Algebraic]` intersection are stepping
stones E2 reworks onto the real traits. The shipped runtime `astOf`/`eval` stays until E2 makes
`astOf` non-exported behind `.ast`.

> **Superseded (kept for provenance):** the earlier §5 framing represented `AlgebraicDispatch` as
> a nominal `AlgebraicDispatch <: Dispatch` trait-view *layered on the existing structural
> `IrSort.Dispatch`*. That relocates rather than removes the hardcoding (it keeps the two special
> sort kinds), which fails the acid test — hence the CallSig/capability design above.

---

## 6. Open decisions (for James)

1. ~~**Filename** — separate doc vs folding into `type-records.md`.~~ **RESOLVED (2026-07-18,
   James):** kept separate (roadmap vs model), named `type-system-roadmap.md` — "convergence"
   belongs to the C5 goal, which is related but separate.
2. ~~**C3 vs C2 sequencing** — parallel or strictly after C2 Phase 2?~~ **RESOLVED (2026-07-18):**
   mostly parallel. After the C1 merge, the C2-independent C3 slices (engine-internal gaps,
   the `ConstructionGate`-side harness + base-leg delegation, static-cast wiring — all
   gate/runtime-stage, module in hand) proceed in parallel with C2; **only** the
   `CoercionResolver` parser-side harness + deletion serializes behind C2 Phase 2 (the one leg
   with a real post-link-trait-context dependency). Follow the dependency graph, don't
   over-serialize. **Update (2026-07-21):** C2 Phase 2 has since **landed**, so even that leg no
   longer waits on C2 — the remaining step is a C3-internal relocation of the coercion decision
   to a post-link gate (§3, §4.5). The sequencing question is fully discharged.
3. ~~**`construct` three-way tier** — grow the UNKNOWN/runtime-check tier or keep it in the
   gate?~~ **RESOLVED (2026-07-18, James):** dissolved by §1d. **Eliminate the stamp** — the fit
   is a single-engine subsumption query (`Assignability`+`Refinements`); on unprovable the gate
   **errors** (as its own let-claim path already does), it does not stamp; `[!!]` is the only
   deferral. `ConstructionGate` stays as the orchestrator (demotion, dependent-claims,
   type-params), consulting the one decider for fit.
4. ~~**Static-cast home** — compile-time gate vs implicit-at-runtime?~~ **RESOLVED (2026-07-18):
   compile-time**, forced by §1d. `Assignability.cast` proves the coercion path exists (+
   `Refinements` when the target narrows); provable → the runtime only *executes* a coercion
   known to succeed; unprovable → compile error (or `[!!]`), never a runtime throw.
   Identity-preserving downcasts stay legal with no check (a proven determination, not a
   can-throw check). This clarified the **cast / coercion / view distinction** — three-way, not
   two-way:
   - **view only** (variable sort changes, concrete preserved): free `WIDEN` / trait upcast —
     **no cast, no coercion**.
   - **concrete change, lose-freely / lossless**: **implicit coercion** at the binding, **no
     cast** — `Int→Decimal`, autobox. *(Demotion is NOT here — see §6.5: it's a view.)*
   - **concrete change, not-obviously-safe**: **explicit `(Target:value)` cast** — render
     (`(String:12)`), sibling re-tag (`Vec3`↔`Color`), narrow.

   `(Target:value)` is the *explicit* concrete-type changer — **not** the only one (the implicit
   lossless coercions above also change the concrete value without a cast).
5. ~~**Demotion — view-widen or value-changing re-stamp?**~~ **RESOLVED (2026-07-18, James):
   view-based — leave the concrete type as-is.** The concrete identity is **immutable**;
   rebinding to a coarser type is a **view** (the mainstream model — `Animal a = dog` never
   mutates the `Dog`). Demotion (`Point3D → Point`) is a `WIDEN` that **retains** the value's
   concrete `Point3D` and restricts access to the declared `Point` interface — it does **not**
   forget `z` and does **not** re-stamp. **Concrete type changes only by construction or an
   explicit `(Target:value)` cast.** Consequences ratified with it:
   - The **re-stamp discipline is retired** (`type-records.md` §132+), and with it the
     same-structure stale-stamp gap — a demoted value's concrete type is always honest, so
     runtime trait dispatch ([DispatchTable.java:227], reads the concrete name) is correct by
     construction (verified 2026-07-18). This is *why* re-stamp existed; it's now unnecessary.
   - The **cast law shifts** from "lose-freely = *clean forget*" to "**restrict the view,
     retain the data**." Observable: a pin-less downcast `let back:Point3D = flat` becomes a
     free **identity-recovery** (recovers `z`), not a fabrication. Requires deliberate prose
     updates in `README.md` / `docs/univocal-language-design.md`.
   - The **implicit sibling / same-structure coercion is forbidden** (`Vec3→Color` needs an
     explicit cast) — which `Assignability` already enforces (`NEEDS_CAST`); a legacy
     `CoercionResolver` hole to close.
   - `Assignment.WIDEN` is uniformly view-only (never changes the concrete type), which
     **simplifies** the C3 engine.
   *Work status (2026-07-18): (1) **DONE** — demotion made a view; `ConstructionGate`
   retains the concrete value (no projection/re-stamp), views it at the declared base sort;
   the projection machinery (`projectDemotion` + helpers) was removed. Full ir+runtime+demo
   suite green (~1290 tests), README pins intact. (2) **already satisfied** for the nominal
   case — a same-structure sibling struct assign is already rejected: `Assignability.assign`
   returns `NEEDS_CAST` and `structAssignBinding` throws ([AltParser.java:2099]); the legacy
   `CoercionResolver` returns `Mismatch` too. The only residual implicit same-structure
   coercion is between **transparent aliases**, which is correct under view-based (no concrete
   change — same structural type) and is tied to `project_type_aliases`, not this ruling.
   (3) revise the cast-law prose (`README` / `univocal`) — pending. (4) suite = safety net,
   green. NB: the observable payoffs (free downcast recovery, concrete-based trait dispatch)
   are follow-on — (1) delivers immutable concrete identity, the foundation they build on.*
6. **Demoted-binding method routing was a view leak — FIXED 2026-07-21.** Both
   `type-records.md:62-65,104-107` (*"`b` has only `Point`'s methods even though the value is a
   `Point3D`"*, and "read Declared when present, else the Inferred head") **and** §6.5 (a view
   *restricts* static access to the declared interface) mandate **declared-first** method routing.
   `MethodOperatorResolver.nominalReceiverSort` is now declared-first, and the leak's true location was
   pinned down in the process: a **top-level** demoted `let b:Point = point3dValue` never leaked — its
   binding sort is narrowed to the declared `Point` at parse time (`AltParser.nominalBinding` returns the
   declared sort for a WIDEN/demotion verdict), so inference already gives the `Point` head; a top-level
   let / 0-arg fn / computed receiver lowers to a 0-arg `Call`. The leak was in **local** bindings (which
   lower to a `Var`): the pass re-infers the value sort and binds the local var to the concrete Inferred
   sort (`Point3D`), so a local demoted `let` routed methods on `Point3D` — exposing `Point3D`-only
   methods (and *hiding* `Point`'s own). **The fix:** for a `Var` receiver, read the binding's own
   Declared claim from a lexically-scoped `localClaims` map — sourced from `LetIn.claim`, cleared for a
   shadowing lambda param, isolated per function — falling back to the Inferred head when there is no
   claim (a param already narrows to its declared sort); the top-level `Call`/`declaredReturns`/`_tuple`
   path is unchanged. The care point dissolves: `declaredReturns` is name-keyed, but a shadowing local is
   a `Var` carrying its OWN lexical claim entry and is never routed through `declaredReturns`, so no
   name-collision is possible. Regression tests: `MethodResolutionTest`
   (`localDemotedBinding_doesNotLeakConcreteOnlyMethod` + three guards). Full reactor suite green.
   Independent of C3 Item 1.

---

## 7. Relationships

- `docs/type-records.md` — the Declared/Inferred/Value **model** (C4); the representation half
  of §1.
- `docs/inference-unification.md` — C1, the narrowing engine (done).
- `docs/dispatch-unification.md` + `docs/cross-module-dispatch.md` — C2; phases 1–4 landed
  (Phase 2's post-link move discharged the old C3 gate — §3), remaining = the visibility model.
- `docs/dependent-sorts.md`, `docs/feature-matrix.md` — C5, the binding substrate (parallel
  axis).
- `docs/univocal-language-design.md` — the cast law (lose-freely / fabricate-never) governing
  the nominal axis.
- Engine source: `pontif-ir/types/{Assignability, AssignabilityContext, TypeCatalog, TypeInfo,
  CoercionResolver}.java`; `pontif-core/symbolic/Refinements.java`;
  `pontif-ir/{NarrowingInference, ConstructionGate, SortChecker}.java`.
- Memory: `project_typesystem_api` (the facade/strangler intent, referenced from
  `TypeSystem.java`), `project_type_spec_layering` (C5), `feedback_declare_war_divide_conquer`
  (the campaign method).

---

## Appendix A — C3 landed history (archived session handoffs)

*Superseded by §4.5 (the current remaining-work list); kept for provenance — these were the
live progress boards and session handoffs recorded as C3 advanced (2026-07-20 → 2026-07-21).*

### 4.6 Progress & resume point (updated 2026-07-20)

**Phase 0 DONE (2026-07-20) — the finish-line plan's measure-twice step.** Full `mvn test` green
baseline confirmed (maven exit 0). Both audits ran: the §1d stamp sweep (findings folded into §1d
above — two violators + one `[!!]` candidate, all in `ConstructionGate`) and the parser-side
trait-dependency audit (findings in §4.3 — the C2-blocked residue is a single leg, `TraitCast`).
Sizing updated by the measurements: the §1d construct-two-way cut is **M** (two sites, one file,
with the dependent-claim path as the working template), and `CoercionResolver` can be gutted
**pre-C2**. The agreed finish-line plan drives Phases 1–5 (C3-independent) to an engine-complete
state and queues Phase 6 (the `CoercionResolver` deletion) behind C2 Phase 2.

**Phase 1 DONE (2026-07-20; full `mvn test` green) — `ConstructionGate`'s nominal base leg now
delegates to `Assignability`.** `ConstructionGate.classify` kept its three-way shape
(FITS/DISJOINT/UNKNOWN) but its hand-rolled base-name compare (the retired `argConcrete &&
fieldConcrete → DISJOINT` heuristic, plus the dead `PRIMITIVES` set) is replaced by a single
`Assignability.assign(stripRefinement(arg), stripRefinement(field))` query; the genuinely
three-way **refinement leg** (PredicateArithmetic) stays in the gate because the engine is two-way
and abstains. Context is `AssignabilityContext.fromModule` (module in hand — cheap, trait closure
included), threaded through `rewriteExpr`/`gateRecord`/`gateClaim`/`classify`. **The nominal
question ignores refinements on BOTH sides** — the one bug found in-flight: stripping only the
field made `assign(Decimal[==100], Decimal)` return `ILLEGAL` (engine `sameType` is
refinement-aware + primitive-tag short-circuit) → a spurious DISJOINT that skipped the
Decimal-outside-kernel refinement leg (16 failures); stripping both fixed it. **Reachability note:
the engine only decides GATED fields** (`gated()`: refined, known-struct `Named`, unions/intersections
thereof, parametric `Stream`) — **trait fields are NOT gated at construction**, so the "non-satisfying
trait → compile-error" §1d delta does *not* fire here (it belongs to the call/let-gate slices). The
reachable, pinned improvement is the **struct widen** (`Point3D → Point` field): the old base-name
compare wrongly ruled it DISJOINT; the engine rules it a widen → FITS, no stamp
(`ConstructionGateTest.structWidenAtConstruction_fits_withNoRuntimeCheck`). **Next: Phase 2** — the
§1d construct-two-way cut (kill the `UNKNOWN → stamp` at [ConstructionGate.java:421] / `:241`), now
that the fit query already flows through the engine.



**Landed this session (all on master, each green):** the differential harness + four
`Assignability` engine-capability gaps — **intersection**, **`Int→Decimal` (`COERCE`)**,
**refinement-precise leaf subsumption**, **Method/Dispatch function-sorts** — plus a latent
**`sameType` unsoundness fix** (it was predicate-blind, equating `[Int:@>=0]` with `[Int:@>0]`).

**Engine state:** `Assignability.isA`/`assign` now handles exact, is-a widen (view), demotion
(view, §6.5), `COERCE`, siblings→`NEEDS_CAST`, unions, intersections, traits, refinement-precise
leaves, and function-sorts. The differential harness (`AssignabilityDifferentialTest`) is at
**8 agree / 2 diverge**, and both divergences are the engine being *correctly stricter* than the
legacy (eager trait-satisfaction / alias-membership rejection the legacy defers). **No case where
the engine is weaker** — a strong license-to-delete position.

**Remaining §4.2 engine gaps:** generics/type-args (L), three-way `construct`→two-way per §1d
(L), static-cast wiring (M).

**Update (2026-07-19):** the `AlgebraicDispatch` thread was pursued and pivoted into a larger,
ratified refactor — **[`docs/dispatch-method-elimination.md`](dispatch-method-elimination.md)**
(the Dispatch/Method → capability-driven `CallSig` elimination; §5). Along the way, two substrate
slices landed on master (`1890fda`):
- **Slice A** — general some-branch **intersection member resolution** (`NarrowingInference`
  field inference, `MethodOperatorResolver`, `SortChecker` gate). Reusable; kept.
- **Slice B** — the `[Dispatch & Algebraic]` metareference stamp (`Algebraic` marker,
  `InferenceContext.algebraicFunctions`, `dispatchRefSort`). A **stepping stone** the elimination's
  Stage E2 reworks onto real traits.
A third increment (**C1a**, nominal identity on the runtime `DispatchValue`) was explored and
**reverted** — superseded by the E1 design. The next session starts **Stage E1** of the
elimination doc (a fresh context was intended for it).

**Where to resume — pick one:**
- **Dispatch/Method elimination, Stage E1** (`docs/dispatch-method-elimination.md`) — the primary
  thread; collapse `IrSort.Method`/`IrSort.Dispatch` into capability-driven `CallSig`,
  behavior-preserving, then Stage E2 delivers `.ast`.
- **First migration (§4.5 step 4)** — delegate `ConstructionGate`'s base-name fit leg to
  `Assignability`, guarded by the harness. C2-independent; the first real legacy consolidation.
- **Remaining engine gaps** — generics / construct-two-way / static-cast.

**Key files:** `pontif-ir/.../types/Assignability.java` (the engine),
`.../types/{AssignabilityContext,TypeCatalog,TypeInfo,CoercionResolver}.java`,
`pontif-ir/src/test/.../types/{AssignabilityTest,AssignabilityDifferentialTest}.java`.

---

### 4.7 Effective-sort work — session handoff (2026-07-21)

This session pivoted C3 from the §4.5 sequencing into James's **effective-sort** requirement (the
Inferred record of `type-records.md`, i.e. the *accumulated* sort at each position). Full narrative
below; **read this before resuming**.

**LANDED on master (each committed, full `mvn test` green):**
- `2d213d3` — ConstructionGate nominal fit leg → `Assignability` (Phase 1).
- `b77ebf1` — `NarrowingInference.effectiveSort(expr, ctx)` (use-site projection) + `hypothesesFromEnv`
  widened to **field invariants**, sourced from the field's *effective* sort (reuses `inferFieldAccess`).
- `0de9003` — `NarrowingInference.effectiveSorts(root, ctx) → Map<Origin.Span, IrSort>` (lens over a
  tree) + shared env-threading helpers `letBodyCtx`/`matchArmCtx`/`lambdaBodyCtx` (infer reuses them).
- `0ca02a0` — **materialize**: `EffectiveSortLens.of(module)` → the lens carried on `CompiledModule`
  (new `effectiveSorts` field + back-compat ctor); computed in `IrCompiler` after promotions, before
  the gate. Robustness: `effectiveSort` caps projection at a predicate-depth bound (`predicateDepthWithin`,
  iterative) — dense projection over the plot module overflowed `closeOver`'s recursive predicate walk.

**STASHED — `git stash list` → `stash@{0}` "phase3-wip …":** the construction/claim gate CONSUMING the
lens + §1d stamp-kill. Correct and working (the README-opener `Account(this.balance+n)` now *proves* at
compile time via the accumulated `[Int:@>=1]`), but NOT committed because it needs the churn below +
vetting. Contents: `ConstructionGate` gains `effectiveArg` (reads `lens.get(span)`, inference fallback),
routes `argSort`→`effectiveArg` in `gateRecord`/`gateClaim`, threads `lens` through `rewriteExpr`; the
`UNKNOWN → stamp` is replaced by a **compile error** (except the parametric-`Stream` `[!!]` defer);
`classify` gains a `refinementFits` fallback (Int+Decimal via `Refinements.imply`) so Decimal-literal
fits discharge; `IrCompiler` calls `ConstructionGate.rewrite(resolved, effectiveSorts)`; plus an
`inferRecord` fix (a plain construction with no refined fields now infers the bare struct type, not
null) and a 4-case call-gate probe test. `InferenceContext.withParams` helper added.

**To land the stash (next session, step 3 finish):** pop it, then resolve the **15 churn tests** — all
the agreed §1d change (unprovable/overlap → compile error), NOT regressions:
- 12 §1d flips → expect compile-error: `ConstructionGateTest` {`decimalRefinedField`, `overlapCase_isActuallyStamped`,
  `overlappingDeclaredVar_compilesAndPasses…`, `…failsAtConstruction…`}; `LetClaimGateTest`
  {`decimalClaim_misses`, `forcing_chainsThroughDependentLets`, `genuineZeroArgFunctions`, `overlapClaim_isActuallyStamped`,
  `overlapClaim_passesWhenValueFits…`, `unreferencedTopLevelLet_constructionChecksAlsoFire`, `…isStillNotarized`};
  `SpecOnlyLetTest.synthesizedBinding_isForcedLikeAnyOther`.
- 3 top-level downcasts → **compile-error** (James's ruling: unchecked downcast is *wrong*):
  `TraitAttributeTest.traitDowncastToConcreteStruct_recoversFields`, `RecursiveTraitTest.recursiveTrait_implementedAndUsed`,
  `ReadmeSnippetTest.readmeTraitCoercionSnippet_roundTripsTo4`. (These were top-level lets lowered to
  0-arg functions, so the downcast crosses a declared-return contract — genuinely unprovable; they only
  worked before via the killed runtime stamp.) Then vet the `inferRecord` change against the full suite.

**THE KEY OPEN REQUIREMENT (James, the real "next"): the CALL gate must consume the effective sort.**
Today the call/dispatch gate (`SortChecker`/`StaticDispatch`) resolves on the **declared** sort, not the
effective one — so James's four cases fail where they should pass. With only `bark(d:Dog)` (no fallback
— pure static resolution, no specificity needed):
```
let dog:Animal = Dog()             # effective Dog → bark(dog) SHOULD route    — currently FAILS ("cannot prove routes")
function speak(a:Animal)->bark(a)  # a is just Animal → SHOULD compile-error   — currently fails (correctly)
let dog:Animal = Husky()           # effective Husky is-a Dog → SHOULD route   — currently fails
let cat:Animal = Cat()             # effective Cat not-a Dog → SHOULD compile-error
```
Fix = the same move as step 3, applied to the call path: thread the effective-sort lens into the call
gate so `bark(dog)` sees `dog`'s effective `Dog`. The probe `ConstructionGateTest.effectiveSortCallRouting_fourCases`
(in the stash) is the regression target. My earlier "declared-view" mis-analysis was a red herring —
dispatch already routes on the concrete at *runtime*; the gap is purely the *static* call gate reading
the declared label.

**Separate latent bug (NOT needed for the four cases, note for later):** there are **no specificity
rules anywhere**. With a *total* overload set (`bark(Dog)` + `bark(Animal)`), a concrete `Dog` matches
both and runtime dispatch throws **"Ambiguous dispatch between 2 candidates"** instead of preferring the
more-specific `bark(Dog)`. The compile-time `OverloadOverlap` check *allows* these overlapping overloads
(assuming specificity resolves them), so compile-time and runtime disagree. Lives in `DispatchTable`.

**Also document (low priority):** effective-sort refinement in a `match` arm — `[d:Dog] -> bark(d)`
works today; the `[Dog] -> bark(a)` (reuse the scrutinee, narrowed) form is the nice-to-have.

**Recommended next-session order:** (1) pop the stash, resolve the 15 churn + vet `inferRecord`, commit
step 3 green; (2) the call-gate effective-sort consumption (the four cases); (3) the docs above; the
dispatch-specificity bug is its own separate track.

### 4.8 Effective-sort landing — session close (2026-07-21, cont.)

All three items above are **done**; full `mvn test` green. Two commits (step 3 and the call gate merged,
because James's refined downcast ruling below made them share one mechanism):

- **`d9a320b` — dispatch gate consults trait satisfaction.** The vetting of `inferRecord` (step 1)
  surfaced a latent gap: once a fieldless construction narrows to its bare struct type (`Dog()` → `Dog`,
  no longer `null`), that concrete arg reaches the call gate, whose disjointness leg
  (`StaticDispatch.provablyDisjoint`) read `Refinements.imply(arg, param) == Failed` as "provably
  disjoint". Refinements can't see trait satisfaction, so `imply(Parabola, Curve2D)` is `Failed` even
  though `Parabola is-a Curve2D` — wrongly excluding a satisfying struct from its trait param (13 plot
  tests). Fix (roadmap §4.3, nominal decider): thread the closed `typeName → traits` view (extracted as
  `AssignabilityContext.traitImplsOf`) through `InferenceContext` into the gate; a satisfying struct is
  not disjoint, a non-satisfying one still is (genuine misroute still caught).
- **`5eb9aaa` — gates consume the effective sort (§1d stamp-kill + top-level-let see-through).** The
  stashed step-3 work (construction/claim gate reads the lens, `UNKNOWN → stamp` replaced by
  compile-error) + `inferRecord` (bare struct floor) + the 15 churn flips, PLUS the call gate:
  `NarrowingInference.inferThroughLets` (shared by `inferArg` and `effectiveSort`) sees a reference to a
  top-level let (a 0-arg lowering) through to its value's sort, so `bark(dog)` routes on the effective
  `Dog`. The four-cases probe passes; case 2 (`bark(a)`, `a:Animal`) already compile-fails via the
  nominal checker (`SortChecker`), not this gate.

**James's refined downcast ruling (the reframing that merged steps 2 and 3).** A struct coerces to a
trait it satisfies (upcast, always). A trait coerces **back** to a struct **only when the effective sort
IS the struct** — `let dog:Animal = Dog()` (effective `Dog`) downcasts; `let dog:Dog = human.pet()` where
`pet():Animal` does **not** (a method return is only a "could-be"). So the handoff's "3 downcasts →
compile-error" was mis-grouped: only `RecursiveTraitTest.recursiveTrait_…` (a method-return downcast)
flips to compile-error; `readmeTraitCoercionSnippet` and `traitDowncastToConcreteStruct_recoversFields`
are **valid** (effective sort is the struct) and now pass via the see-through — **the README's
bidirectional-coercion example stays correct**, no README change.

**Still open (unchanged from §4.7, own tracks):**
- **Dispatch-specificity bug** — no specificity rules anywhere; a *total* overload set (`bark(Dog)` +
  `bark(Animal)`) throws runtime "Ambiguous dispatch" on a concrete `Dog` instead of preferring
  `bark(Dog)`. Compile-time `OverloadOverlap` allows the overlap; compile/runtime disagree. Lives in
  `DispatchTable`. (Not blocking the four cases — those use a single `bark(Dog)`.)
- **`match`-arm effective-sort refinement (low priority)** — `[d:Dog] -> bark(d)` works; the
  scrutinee-reuse form `[Dog] -> bark(a)` (narrow the bound scrutinee `a`) is the nice-to-have.

