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

**Enforcement — audit item (deferred, not yet run):** sweep the tree for every site that
stamps a can-throw runtime check without a discharged proof or an explicit `[!!]`, and flag
each. The construction `UNKNOWN` stamp is the known one; there are likely others
(`IrExpr.Record.runtimeChecks` producers, any `executeChecks`/`RuntimeCheckException` path
reached on an unproven claim). Enumerate before eliminating.

---

## 2. The campaigns (status board)

Five efforts converge on §1. Two are representation-model, three are engine-unification.
Status markers: ☐ not started · ◐ in progress · ☑ landed.

| # | Campaign | Owns | Status | Doc |
|---|---|---|---|---|
| C1 | **Inference unification** | narrowing = one engine (`NarrowingInference`) | ☑ landed (unmerged branch) | `inference-unification.md` |
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

- **C1 (inference) — done, but on a branch.** `NarrowingInference` is the sole
  expression-typer: `SortChecker.inferSort` → `inferFloor` ([SortChecker.java:2226]),
  `AltParser.inferMaximalSort` → `inferFloor` ([AltParser.java:2039]); all four stages route
  through it; TODO marks Cluster 5 done. **Caveat:** the whole campaign lives on
  `war/scope-aware-narrowing`, **not merged to master** (`TODO.md` "Merge
  `war/scope-aware-narrowing` → master"). *Merging it is a precondition for building on the
  unified state.*
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
C1 inference ✔ ──────────────► (merge to master) ──► everything builds on the unified state
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

**Merge-first:** C1 is on an unmerged branch. Land that merge before C3 work, so C3 builds on
the single-engine narrowing state rather than diverging from it.

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

### 4.1 Do this first — the differential harness (the license to delete)

Build a test that runs `Assignability`'s verdict against the incumbent
(`CoercionResolver` / `ConstructionGate`) over the existing snapshot/probe corpus and asserts
agreement. A strangler migration deletes a copy only once "engine == copy on every existing
case" is green. This is the **highest-priority missing guardrail** (today
`AssignabilityTest` + `AssignabilityContextTest` cover increment-1 shape logic well but have
**zero** refinement, generics, intersection, function-sort, or Int→Decimal cases, and no
engine-vs-copy comparison). *Size: medium. Blocks everything else in C3.*

### 4.2 Engine capability gaps (build inside `Assignability`)

| Gap | Status | Size | Notes |
|---|---|---|---|
| Refinement-precise leaf subsumption | absent (shape-equality only) | M | delegate to `Refinements.imply`; partly gated on TODO "strengthen `imply` via bounds" |
| `Int→Decimal` / primitive coercion | absent | S–M | lives only in `CoercionResolver`/`ConstructionGate` today |
| `construct` fit as a **two-way** prove/reject decision | partial (nominal only, no refinement) | M | §1d: do **not** grow a `UNKNOWN → stamp` tier — fit delegates to `Refinements`; unprovable → compile error (or `[!!]`), never a runtime stamp |
| Generics / type-args (`Box[Int]`) | absent (parser guard skips type-args) | L | |
| Intersection sorts | absent (`isA` handles Union only) | S | |
| Method / Dispatch function-sorts | absent (`baseName` → null) | M | **the arm `AlgebraicDispatch` needs** (§5) |
| Static-cast legality wiring | decision present, unwired | M | currently decided *nowhere*; `IrExpr.Cast` legality is implicit-at-runtime |

### 4.3 Migration targets (wire onto the engine, then delete)

| Decision | Site | Action | Size |
|---|---|---|---|
| struct↔struct `let` assign | [AltParser.java:2098] | landed (Slice 1) | — |
| all other `let` coercions | [AltParser.java:1883] / `:4576` (`coercionFor` → `CoercionResolver`) | re-point → engine; delete `CoercionResolver`/`CoercionContext`/`Coercion` | **L, C2-Phase-2-blocked** |
| construction fit | `ConstructionGate.gateRecord/gateClaim` (`classify`) | fit → single-engine query (`Assignability`+`Refinements`); **drop the `UNKNOWN → runtimeChecks` stamp** (§1d) — unprovable = compile error / `[!!]`. Orchestration (demotion, dependent-claims, type-params) stays. | M (context cheap here) |
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

1. **Merge `war/scope-aware-narrowing` → master** (C1) so C3 builds on the unified state.
2. **Write the DoD + differential harness** (§4.1). Nothing gets deleted without it.
3. **Close the cheap engine gaps**: intersection (S), `Int→Decimal` (S–M),
   refinement-precise via `imply` delegation (M).
4. **Migrate `ConstructionGate`'s base leg** — a real deletion that never touches the parser.
5. **Wire static-cast legality** through `Assignability.cast`.
6. **Defer** the `CoercionResolver` parser deletion until **C2 Phase 2** lands (post-link
   trait context) — or consciously accept the struct-only slice until then. Generics and
   function-sorts land as their consumers require.

---

## 5. Motivating first customer — `AlgebraicDispatch` (the thread that surfaced this)

The differential-programming work (`assign proof f:Algebraic` + runtime `pontif.algebra`
reflection, landed 2026-07-18) wants a compile-time-safe `$f[Decimal].ast` — i.e. a
`Dispatch` value that *statically* carries "algebraic," so `.ast` is a type error on a
non-algebraic function and the guarantee propagates through parameters. Design conclusions:

- **Represent it as a trait-style view, not type-extension.** Trait = variable type and value
  type stay distinct (the runtime value is unchanged; "algebraic" is a static view). Type
  extension would wrongly imply a value morphism. `AlgebraicDispatch <: Dispatch`, earned by
  the proof, stamped on the metareference by inference (never fabricated).
- **It is C3's first function-sort case.** It needs the **Method/Dispatch function-sort arm**
  in `Assignability` (§4.2) plus the nominal `AlgebraicDispatch <: Dispatch` edge — a
  contained increment.
- **It sidesteps the C2 wall.** The `.ast` legality check runs at the **gate stage** (module
  in hand → `fromModule` context cheap), so it does **not** wait on C2 Phase 2. That makes it
  a clean, self-contained slice that *advances* C3 while delivering the feature.
- **Per the governing intent**, it is sequenced **after** the convergence lands (or at least
  after §4.5 steps 1–2), not bolted onto `Refinements.imply` ahead of the engine that is
  slated to own subsumption.

Until then, the shipped runtime path keeps `astOf`/`eval` (a runtime fail-closed reflect);
the compile-time `.ast` view is the C3-gated upgrade.

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
     cast** — demote-forget, `Int→Decimal`, autobox.
   - **concrete change, not-obviously-safe**: **explicit `(Target:value)` cast** — render
     (`(String:12)`), sibling re-tag (`Vec3`↔`Color`), narrow.

   `(Target:value)` is the *explicit* concrete-type changer — **not** the only one (the implicit
   lose-freely coercions above also change the concrete value without a cast).
5. **Demotion — view-widen or value-changing re-stamp? — OPEN (needs James).** The two models in
   the tree conflict: `Assignability` classifies `Point3D → Point` as `WIDEN` (*"concrete
   preserved, no runtime work"* — a pure view, concrete stays `Point3D`), while
   `univocal-implementation-plan.md` / `type-records.md` model demotion as a projection
   **morphism** that drops `z` and **re-stamps** the value `Point` (concrete changes). Both
   cannot be literally true. **Revisit and decide: should demotion actually change the concrete
   type** (run the forget morphism, re-stamp `Point` — so `Value ⊑ Declared` and a later trait
   upcast dispatches as `Point`), **or leave the concrete `Point3D` intact** and treat `Point`
   as a view-only widen (methods restricted by the declared sort, concrete unchanged)?
   Ramifications: the re-stamp discipline (`type-records.md` §132+, incl. the `Color`/`Vec3`
   stale-stamp lie), whether `WIDEN` is ever permitted to change the concrete type, and the
   `Assignment.WIDEN` semantics in the engine. Bears on **C3** (`Assignability.assign`) and
   **C4** (Value-Type record). *Sequence: settle before C3 wires `assign` beyond struct↔struct.*

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
