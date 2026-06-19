# Cross-module dispatch: import by association

**Status: RATIFIED — executing (war branch `war/cross-module-dispatch`, 2026-06-18).**
Phases 1–4 (orphan rule for operators + methods + static members, the association
index) and Phase 3a (static-attribute import-by-association) have **landed on master**.
The **§5.3 nod is GIVEN** (James, 2026-06-18): build the visibility cutover. Two
rulings now drive the rest — **(R1)** the cutover *migrates* tests, it does not
suppress them; breakage gets fixed. **(R2, the mandate)** *no operator application may
reach runtime undefined* — "no applicable overload" and "ambiguous overload" become
**compile errors**, never runtime throws (north star: no runtime errors, ever). The
operator-on-trait question is resolved (contract model, **§8**). The concrete
execution plan is **§8**; §0–§6 are the scoping that produced it. This is the *sole*
remaining piece of dispatch unification: the cross-module *visibility* model — it
tightens `docs/dispatch-unification.md` (§"Mechanism 1") from "global multi-dispatch"
to **module-scoped, import-by-association** (global dispatch is what sank SPN).

## 0. Scoping (ground truth, 2026-06-18)

What's actually built, confirmed against the code (not the docs' old phase numbering):

- **Operators + methods already resolve post-link by sort.** `MethodOperatorResolver`
  routes operators by both operand base sorts and methods by receiver sort; the
  parse-time routing hacks are gone; `method T.+` is rejected at parse. So mechanism
  1 is the sole home of operators, and this doc has a single uniform overload set to
  govern — its prerequisite is met.
- **The hole is visibility.** `CoherenceCheck` enforces the orphan rule for **trait
  impls only** — *not* for free-function/operator overloads. And `ModuleLinker.combine`
  concatenates every module's statements into one FQN-keyed table, so **every overload
  is globally visible after link** (accidental global dispatch). `ModuleSymbolTable`
  does **not** index overloads by parameter type. Those three gaps are exactly Phases
  1–3 of §6.

**The concrete motivating gap (James, 2026-06-18):** **static/class attributes don't
import with the struct.** `let Traction.one = …` in a module is not surfaced by
`requires m.{Traction}` — only the type itself comes across. A static member is
`Type.member`-keyed (a 0-arg top-level let), structurally identical to a method
(`Type.method`); import-by-association must bring *all* of a type's associated members
— methods, operators, and static attributes — uniformly. This is the user-visible
payoff of the war (a single clean namespace; see memory `project_import_by_association`).

**Gating-probe reality (verified on the war branch — the inference unification already
fixed two of the four I expected):**

| probe | status now | disposition |
|---|---|---|
| `dispatch__26` (method-form operator) | COMPILE_FAIL (correct rejection) | **no-op** — confirming negative probe. |
| `traits__20` (`let v=Vector(1,2)`; `(v+v).x` cross-module) | **OK already** | the inference unification fixed the top-level-let routing — *not* war work. |
| `generics__22` (generic `a+b`, `+(Vec,Vec)` imported) | **OK already** | also fixed upstream — *not* war work. |
| `inference__20` (`method Vec.mag2` on an *imported* `Vec`) | COMPILE_FAIL | **the one directly-relevant probe** → becomes a clean **orphan rejection** (Phase 4): "define it in `Vec`'s module." |

So the war has **no companion narrowing bugs** — it is purely the visibility model
(orphan rule + association index + import-by-association), whose user-visible win is
static-attribute import, and whose only probe motion is `inference__20` →
expected-rejection. (`generics__14` / `generics__27` are *generics* feature gaps —
method-call-on-bounded-type-param and parametric method declarations — unrelated to
this war; tracked separately.)

---

## 1. What is already settled

Operators are **symmetric mechanism-1 multi-dispatch** — free functions matched on
*all* operand sorts, exactly like every other function. This is not aspirational;
it is the current behavior (empirically: `1 / Frac(3,4)` → `4` with
`function /(a:Int, b:Frac)` owned by `Frac`'s module; `3 * Vec(2,5)` → `15` with
`function *(k:Int, a:Vec)`). Cluster 4 makes it *intentional* by deleting the
parse-time left-operand routing guess (one routing decision, post-link, by both
operands) and dropping the receiver-rooted `method T.+` operator form (a method's
receiver is forced to be the first operand — the exact asymmetry we remove; regular
`method T.name()` is unaffected — that is mechanism 2, correctly receiver-rooted).

What is NOT yet settled — and what this doc decides — is **how a mechanism-1
overload declared in one module becomes usable in another.**

---

## 2. The question, and where we are by accident

Today the linker fuses every module into one FQN-keyed dispatch table, so *every*
overload everywhere is visible after linking. Cross-module operators work
(`dispatch__16` passes) for this reason. That is **accidental global dispatch** —
the very shape the namespace-hygiene ruling rejects ("multi-dispatch must stay
module-scoped + coherent; SPN died from global dispatch + no polymorphism"), minus
the syntax or guarantees that would make it honest.

Three options were weighed (the language designer's framing):

| Option | Sharing model | Verdict |
|--------|---------------|---------|
| (i) **Explicit dispatch import** | each overload `requires`-imported by name | Most traceable, init-order-invariant — but awkward for multi-dispatch (*which* module do you import `+(Int,Custom)` from?) and fights the ambient-algebra feel. |
| (ii) **Import by association** | importing a *type* brings the overloads that mention it | **Chosen.** Module-scoped + coherent; generalizes "methods come with the type"; answers the "which module" problem (you import the type, the overload tags along). |
| (iii) **Global + `register dispatch` syntax** | overloads enter a global registry, resolved end-of-compile | Honest about being global, init-order-invariant — but it *is* the global dispatch the hygiene ruling ruled out (SPN's failure mode). Set aside. |

---

## 3. The model: import by association + the orphan rule

Two halves, duals of each other. One is *where an overload may be declared*; the
other is *what importing a type gives you*. The coherence rule already exists for
trait impls (`glossary.md` → "coherence rule (orphan rule)"; enforced at link time
by `CoherenceCheck` over FQNs); this **generalizes it to every mechanism-1
overload.**

### 3a. Orphan rule (where an overload may be declared)

> A mechanism-1 overload `f(T₁, T₂, …): R` may be declared only in a module that
> **owns at least one of the parameter types** `Tᵢ` (its *home set*). Never a
> third module that owns none of them.

- `+(Vector, Vector)` → declarable in `Vector`'s module. ✓
- `/(Int, Frac)` → `Int` is owned by the prelude, `Frac` by its module; the home
  set includes `Frac`'s module, so it is declarable there. ✓ (This is exactly why
  `1/customType` is expressible — you own one side.)
- `+(Int, Int)` by a user module → home set is the prelude only; a user module owns
  neither operand → **rejected** (orphan / type-piracy guard). You cannot redefine
  arithmetic on types you don't own.

Enforced at link time, extending `CoherenceCheck` from trait impls to free-function
/ operator overloads, over FQN'd parameter sorts.

### 3b. Import by association (what importing a type gives you)

> `requires m.{T}` brings into scope **every overload whose signature mentions `T`**
> (in any operand position), from whatever module declared it.

This mirrors the single biggest motivation for instance methods — *import the type,
get its methods for free* — and extends it to symmetric overloads. It keeps import
lists short and namespaces clean: you name the *types* you work with, and the
algebra over them follows.

The two halves compose: by the orphan rule an overload lives in a home-set module;
by association, importing any type in its signature surfaces it. So importing
`Frac` surfaces `/(Int, Frac)` even though it co-mentions `Int` — because `Frac` is
in its signature.

---

## 4. Why this is the right consolidation

One coherence principle now governs **free functions, operators, methods, and the
"method on an imported type" question** uniformly:

- A **method** `T.m(this:T, …)` is the degenerate single-type-signature overload;
  its home set is `{owner(T)}`. Defining `Vec.mag2` in a module that does not own
  `Vec` is precisely an orphan violation (this resolves the `inference__20` fork:
  **forbid** the orphan method; the legitimate place for it is `Vec`'s module, and
  importing `Vec` then brings it by association).
- An **operator** is a mechanism-1 overload; same rule.
- A **free function** `f(A, B)`; same rule.

No special cases — the asymmetry James flagged ("everyone trips over it") dissolves
into one rule that reads the same for every dispatched name.

---

## 5. Open questions to pin before building

1. **Home set = owns ANY signature type** (proposed, matches Rust/Julia) vs. owns a
   designated "primary" operand. Recommendation: ANY — it is what makes `/(Int,Frac)`
   work and is the least surprising.
2. **Return type visibility.** Must `R` be reachable to declare `f(A,B):R`?
   Recommendation: **no constraint at declaration** (R need not be in the home set),
   but using the *result* requires `R` reachable at the call site (you can't operate
   on a value whose type you can't name) — which falls out naturally, not as a
   special rule.
3. **The migration is a tightening, not an addition.** Today all overloads are
   globally visible; import-by-association *restricts* visibility to imported-type
   associations. Some currently-passing cross-module cases will start requiring an
   explicit `requires` of a type. This needs: a dedicated probe set, a clear
   migration error ("`+` resolves to an overload in `m`; `requires m.{Vector}` to
   use it"), and a deliberate cutover — NOT a quiet bolt-on.
4. **Multi-type association index.** `+(A,B)` is associated with two types in
   possibly two home modules; importing `A` must surface it even if it was declared
   in `B`'s module. The linker needs an index "overloads keyed by *each* signature
   type," with visibility gated on "imported ≥1 signature type." Tractable; it is the
   core build (see §6).
5. **Built-in primitive ownership.** Treat `Int`/`Bool`/`Decimal`/`Char`/`String`
   as owned by the prelude. Confirm the desired consequence: a user may define
   `op(Int, MyType)` (owns one side) but never `op(Int, Int)` (owns neither) — the
   type-piracy guard. This is believed correct and consistent with the no-lie /
   coherence ethos.
6. **Trait-typed operands.** Symmetric dispatch where an operand's static sort is a
   trait (not a concrete type) routes through the runtime trait-fallback. The
   receiver position is built; verify the *symmetric* (either-operand) trait case.
   (Trait *bounds* on operators already work via the operator-contract member; this
   is about a runtime trait-typed *value* flowing into an operator.)
7. **Initialization-order invariance.** Import-by-association resolves at
   link/compile from the import graph, so file initialization/loading order does not
   affect resolution — a stated goal. Confirm no residual order dependence in the
   linker once visibility is import-gated.

---

## 6. Build plan (phased, after Cluster 4)

- **Phase 0 — this doc.** Ratify the model + §5 answers.
- **Phase 1 — orphan rule for mechanism-1 overloads.** Extend `CoherenceCheck` (it
  already does trait impls) to reject a free-function/operator overload whose home
  set is empty (declaring module owns no parameter type). Link-time, over FQN sorts.
  Low risk; additive check. Add probes for the `op(Int,Int)`-by-user rejection and
  the `op(Int,Custom)`-in-Custom's-module acceptance.
- **Phase 2 — association index.** In `ModuleSymbolTable`, index each overload by
  every parameter type's FQN. Pure data; no behavior change yet.
- **Phase 3 — import-by-association visibility.** Gate mechanism-1 overload
  visibility (in `NameResolver` / the linker's dispatch-table assembly) by the
  importing module's imported types: an overload is visible iff the module imports
  (or owns) ≥1 of its signature types. This is the tightening — migrate the existing
  cross-module probes and add the migration error. Highest care here.
- **Phase 4 — methods fold in.** The orphan rule now covers `T.m`; reject orphan
  methods (resolves `inference__20`), and confirm importing a type brings its methods
  by the same association path (much of which already happens — methods come with the
  type today).

### War sequencing + regression guardrails

Each step lands green on the full suite + the 150-probe matrix (the regression meter).
Suggested order — additive/low-risk first, the disruptive cutover last:

1. **Phase 1 — orphan rule for mechanism-1 overloads** (additive `CoherenceCheck`
   extension). New negative probes: `op(Int,Int)`-by-a-user → rejected; `op(Int,Custom)`
   in `Custom`'s module → accepted. **Watch:** existing single-module operator/function
   suites stay green (single modules own everything they declare — trivially coherent).
2. **Phase 2 — association index** in `ModuleSymbolTable` (pure data, no behavior
   change): index each overload AND each static member (`Type.member`) by every
   associated type's FQN. Verifiable in isolation by a unit test over the index.
3. **Phase 4 — orphan methods** (extends Phase 1 to `T.m`): `inference__20` flips to an
   expected, well-worded rejection. (Sequenced before Phase 3 because it's the same
   additive orphan check, not the visibility cutover.)
4. **Phase 3 — import-by-association visibility (THE CUTOVER, gated on §5.3 nod).**
   Restricts overload + static-member visibility to imported-type associations, and —
   the user-visible payoff — **surfaces a type's static attributes (`let Type.one = …`)
   on `requires m.{Type}`**, the same way methods already come with the type. **This
   breaks currently-passing cross-module cases that relied on accidental global
   visibility** — they will start needing an explicit `requires m.{T}`. Guardrails:
   - A dedicated migration-probe set (cross-module operator/function/static-member uses
     *with* and *without* the now-required type import), incl. a positive probe for
     static-attribute import (the `Traction.one` case).
   - A precise migration error: *"`+` resolves to an overload in `m`; add
     `requires m.{Vector}` to use it."* — never a silent miss.
   - **Watch:** every existing `dispatch__*` / `traits__*` cross-module probe — each
     either still has the needed import or needs one added (a deliberate, reviewed
     migration, not a quiet bolt-on).

**Probe scorecard for the war:** `dispatch__26` already correct (no-op);
`traits__20` / `generics__22` **already PASS** (fixed by the inference unification — not
war work); `inference__20` → expected rejection (Phase 4). New static-attribute-import
probe → PASS (Phase 3). No probe should regress from PASS.

---

## 7. Relationship to other work

- **Cluster 4** (route-once + drop method-form operators) is the prerequisite: it
  makes mechanism-1 the *sole* home of operators, so this visibility model has a
  single, uniform set of overloads to govern.
- **`docs/dispatch-unification.md`** §"Mechanism 1" should be updated when this
  lands: "global multi-dispatch" → "module-scoped multi-dispatch, shared by
  import-by-association under the orphan rule."
- **`glossary.md`** "coherence rule (orphan rule)" entry should be broadened from
  "trait impl" to "any mechanism-1 overload (incl. operators) and methods."

---

## 8. Ratified execution plan — Phase 3 + operator completeness (2026-06-18)

This is the build order. It folds the §6 visibility cutover together with the **R2
mandate** (no runtime undefined-operator) and the **operator-on-trait ruling**, because
they share one resolution path: an operator application either resolves to exactly one
overload *at compile time* or it is a compile error. Each step lands green on the full
suite **and** the 150-probe matrix (the regression meter). Order is additive → disruptive.

### 8.1 Rulings being implemented

- **R1 — migrate, don't suppress.** Programs that break under the cutover get a real
  `requires m.{T}` added (a reviewed migration); genuinely-stale tests get fixed. No
  `@Disabled`, no weakening of an assertion to dodge the cutover.
- **R2 — the mandate.** No operator application reaches runtime undefined. "No
  applicable overload" and "ambiguous overload" are **compile errors**. The runtime
  throws become unreachable defense-in-depth, not the primary guard.
- **Operator-on-trait — option (a) via the contract member**, with *totality fixing the
  contract's shape* (§8.4). Option (c) is deferred (§8.5).

### 8.2 Step A — operator-dispatch completeness (the R2 invariant)

The hole today: `MethodOperatorResolver` leaves operators that find no compile-time
overload "for runtime dispatch" (≈ line 145); `IrInterpreter` then throws at runtime —
ambiguous (≈786), the no-match case, and "Operator not defined for Char/String"
(≈468/503).

The change — at the sort-check / resolution stage (`SortChecker` + `MethodOperatorResolver`):
every operator application (`BinOp` and operator `Call`) must resolve to **exactly one**
applicable overload over its operand sorts. **No applicable overload → compile error;
provable ambiguity at the call → compile error.** The runtime throw sites are demoted to
`assert`-grade "unreachable" backstops.

*Risk: additive.* It only rejects programs that would have crashed at runtime, so no
working program needs migration. This establishes the invariant before the cutover leans
on it.

### 8.3 Step B — import-by-association visibility cutover (§6 Phase 3, THE CUTOVER)

Gate mechanism-1 overload **and** static-member visibility (in `NameResolver` / the
linker's dispatch-table assembly) using the Phase-2 association index: an overload is
visible to a module **iff that module imports or owns ≥1 of the overload's signature
types.** Static attributes (`let Type.one = …`) surface on `requires m.{Type}` the same
way methods already do.

- **Migration error (rides A's compile-error path):** *"`+` resolves to an overload in
  `m`; add `requires m.{Vector}` to use it."* — never a silent miss.
- **Probes:** migrate every existing `dispatch__*` / `traits__*` cross-module probe
  (each gains the now-required `requires`, per R1); add a dedicated migration-probe set
  (the same use *with* and *without* the import) and a **positive static-attribute-import
  probe** (the `Traction.one` case).
- **§5.7:** confirm no residual file-init-order dependence once visibility is import-gated.

*Risk: this is the disruptive cutover.* R1 governs the fixes.

### 8.4 Step C — trait-typed operands (the ruling)

An operator on a trait-abstracted value is legal **iff** it is a contract member of the
trait **and** dispatch is provably total. Totality fixes the contract shape:

| Use | Total when | Status |
|---|---|---|
| parametric bound `[type E:T]`, `a:E + b:E` | self-typed contract `+(this.type, this.type):this.type` (operands unified to one concrete type) | **works today** |
| bare `a:T + b:T` (operands may differ at runtime) | **trait-ranging** contract `+(this.type, T):T` — each impl handles "me + any sibling", so every runtime pairing is covered | **new** |

Anything outside these is a compile error:

> *"`+` on trait `T` is defined only for same-type operands; to combine heterogeneous
> `T` values declare `+(this.type, T):T` in `T`'s contract, or use a parametric bound
> `[type E:T]`."*

Then **remove the runtime trait-fallback's ability to throw undefined-operator** — it is
now total-by-contract or unreachable. Verify the symmetric (either-operand) trait case
(§5.6) lands on this rule.

### 8.5 Deferred — (c) dynamic confirm-before-use

For the genuinely-dynamic case (using an operator on a trait that did *not* contract it),
the narrowing-native answer: the application yields a `[!!]`-style **runtime-hazard sort**
(existence undetermined), discharged by a `match` that confirms the operator is defined
for the concrete value — at which point the survivor upgrades. "Not defined" is then a
**branch you must cover, not a thrown error**, so it honors R2 the same way `match [!!]`
does. It rides the existing `[!!]` machinery and is **explicitly not built in Phase 3** —
the contract model (§8.4) covers the designed case; this is the opportunistic layer.

### 8.6 Sequence + probe deltas

1. **A** — completeness check. New negative probes: undefined-operator and
   ambiguous-operator → compile error.
2. **B** — visibility cutover. Migrate existing cross-module probes; add the migration
   set + the static-attribute-import positive probe.
3. **C** — trait-operand totality + remove the runtime trait-fallback throw. New probes:
   contracted heterogeneous `+` (both shapes) → PASS; uncontracted bare trait-operand →
   compile error.

No probe regresses from PASS. On land, apply the §7 doc updates
(`dispatch-unification.md` §"Mechanism 1"; the `glossary.md` orphan-rule entry).
