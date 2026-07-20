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
| C2 | **Dispatch unification** | method/operator/trait resolution → post-link | ◐ in progress | `dispatch-unification.md`, `cross-module-dispatch.md` |
| C3 | **Nominal-subtype / `Assignability`** | is-a / assign / construct / cast = one engine | ◐ Slice 1 landed, else ☐ | **this doc** (§4) |
| C4 | **Three-records model** | Declared / Inferred / Value split | ◐ model settled, migration ☐ | `type-records.md` |
| C5 | **Scoped type-level binding substrate** | dependent sorts, structural traits, Type fragments, sub-traits, generics | ◐ per-facet (see `feature-matrix.md`) | `TODO.md` "4 facets", `dependent-sorts.md` |

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
- **C2 (dispatch) — in progress.** Cluster 4 (operators route once, drop method-form
  operators) + Phases 2–4 (methods resolve on the receiver sort **post-typecheck / post-link**;
  trait dispatch becomes receiver-sort resolution; parser de-blinding). **Phase 2 is the
  linchpin for C3** (see §3).
- **C3 (nominal-subtype) — the undocumented one.** `Assignability`
  ([pontif-ir/types/Assignability.java]) is a pure engine over `IrSort` + a `TypeCatalog`.
  Slice 0 (the `fromModule` context adapter) and Slice 1 (struct↔struct `let`-binding assign,
  live at [AltParser.java:2098]) landed; `isA`/`construct`/`cast` exist but are **test-only**.
  Everything else still flows through `CoercionResolver` / `ConstructionGate`. This doc is its
  plan-of-record (§4).
- **C4 (three-records) — model settled, migration pending.** `type-records.md`'s migration
  path (nominal dispatch reads the Declared sort; stop the `parseLet None→inferredSort`
  collapse; re-stamp discipline) overlaps C2's post-link move.
- **C5 (binding substrate) — per-facet.** Tracked in `feature-matrix.md`; independent of C3's
  finish-line except where generics touch `Assignability` type-args (§4 gap).

---

## 3. The dependency graph (the part written down nowhere until now)

```
C1 inference ✔ ──────────────► (on master) ──► everything builds on the unified state
                                                        │
C2 dispatch ◐ ── Phase 2 (post-link resolution) ───────┤
     │                                                  │
     │  UNBLOCKS: the parser can see trait satisfaction │
     ▼                                                  ▼
C3 Assignability ── parser-side CoercionResolver deletion  (BLOCKED until C2 Phase 2)
     │
     ├─ gate-side ConstructionGate delegation   (NOT blocked — module in hand)
     ├─ engine gaps (refinement-precise, Int→Decimal, intersection, function-sorts)  (NOT blocked)
     └─ static-cast wiring                       (NOT blocked)
```

**The one hard cross-campaign gate:** the largest copy `Assignability` must absorb,
`CoercionResolver`, is invoked at the **parser** ([AltParser.java:1883] / `:4576`). The
parser cannot see trait satisfaction — that is a **post-link** fact
([AssignabilityContext.of] carries *no* trait impls; only [AssignabilityContext.fromModule]
does, and it needs a finished `IrModule`). So the parser-side migration is **capped at the
struct↔struct slice already shipped until C2 Phase 2 moves method/trait resolution
post-link**. Everything else in C3 (below) is independent of C2 and can proceed now.

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
| `construct` fit as a **two-way** prove/reject decision | partial (nominal only, no refinement) | M | §1d: do **not** grow a `UNKNOWN → stamp` tier — fit delegates to `Refinements`; unprovable → compile error (or `[!!]`), never a runtime stamp |
| Generics / type-args (`Box[Int]`) | absent (parser guard skips type-args) | L | |
| Intersection sorts | ✅ **DONE** (2026-07-18) | S | `isA` gained the dual-of-union arms (is-a ∩ = every branch; ∩ is-a X = some branch); pinned by `AssignabilityTest.intersectionSubtyping` |
| Method / Dispatch function-sorts | ✅ **DONE** (2026-07-18) | M | `isA` arms: **Method** delegates to `Refinements.imply` (contra-params / covariant-return); **Dispatch** decided directly (same keys, covariant return — imply has no dispatch arm); the two never cross-assign. Pinned by `AssignabilityTest.method/dispatchSortSubtyping`. **NB (2026-07-19):** these two arms are exactly what the Dispatch/Method elimination (`docs/dispatch-method-elimination.md`, §5) makes **capability-driven** — Stage E1 replaces the `instanceof Method`/`instanceof Dispatch` selection here with a call-kind-capability lookup on the unified `CallSig` node (behavior-preserving) |
| Static-cast legality wiring | decision present, unwired | M | currently decided *nowhere*; `IrExpr.Cast` legality is implicit-at-runtime |

### 4.3 Migration targets (wire onto the engine, then delete)

| Decision | Site | Action | Size |
|---|---|---|---|
| struct↔struct `let` assign | [AltParser.java:2098] | landed (Slice 1) | — |
| all other `let` coercions | [AltParser.java:1888] / `:4605` (`coercionFor` → `CoercionResolver`) | **Phase 0 audit (2026-07-20) split this:** 4 of 6 `CoercionResolver` cases are trait-free — `IntToDecimal`, `RecordPromotion`, `Demote`, `Mismatch`/`None` — migratable **pre-C2** (Phase 5); `Autobox` (tuple→`Stream`) re-homes to the generics slice (Phase 4, retire `isStreamName`); only **`TraitCast`** is C2-Phase-2-blocked — it needs *eager* satisfaction (the parser has `isTrait` but not the post-link impl closure; `CoercionResolver` today is *permissive* and defers). Net: shrink `CoercionResolver` to a trait-upcast stub now; delete it with the residue post-C2. | **M pre-C2 + S residue behind C2** |
| construction fit | `ConstructionGate.gateRecord/gateClaim` (`classify`) | fit → single-engine query (`Assignability`+`Refinements`); **drop the `UNKNOWN → runtimeChecks` stamp** (§1d) — unprovable = compile error / `[!!]`. Remaining orchestration (dependent-claims, type-params) stays; **demotion projection is removed** (§6.5 — demotion is a view now). | M (context cheap here) |
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
  §3. Blocked on C2 Phase 2.
- **Runtime cast** (`IrInterpreter`, a `CompiledModule`): needs a catalog adapter. M.

### 4.5 Recommended order (front-load the unblocked, safe wins)

*Sequencing decision (§6.2): after step 1, steps 2–5 are C2-independent and run in parallel
with C2; only step 6 serializes behind C2 Phase 2.*

1. ~~Merge C1~~ **DONE** — C1 is already on master (war branch fully subsumed).
2. ~~DoD + differential harness (§4.1)~~ **DONE** (`fe4d48d`).
3. ~~Close the cheap engine gaps~~ **DONE** — intersection (`c0fbfa3`), `Int→Decimal`/`COERCE`
   (`90b6b74`), refinement-precise (`759b9c5`); plus Method/Dispatch function-sorts (`03bd09b`).
4. ~~**Migrate `ConstructionGate`'s base leg**~~ **DONE (2026-07-20, Phase 1)** — nominal fit now
   delegates to `Assignability` (see §4.6); refinement leg stays. A real consolidation that never
   touched the parser.
5. **Wire static-cast legality** through `Assignability.cast`.
6. **Defer** the `CoercionResolver` parser deletion until **C2 Phase 2** lands (post-link
   trait context) — or consciously accept the struct-only slice until then.

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
   over-serialize.
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

---

## 7. Relationships

- `docs/type-records.md` — the Declared/Inferred/Value **model** (C4); the representation half
  of §1.
- `docs/inference-unification.md` — C1, the narrowing engine (done).
- `docs/dispatch-unification.md` + `docs/cross-module-dispatch.md` — C2; Phase 2 is the C3
  linchpin.
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
