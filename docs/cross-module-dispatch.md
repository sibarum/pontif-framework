# Cross-module dispatch: import by association

**Status: DRAFT — for ratification.** Sequenced after Cluster 4 (operators route
once; see `docs/dispatch-unification.md` and `docs/TODO.md`). This doc pins the
*visibility* model for mechanism-1 dispatch — how operator/free-function overloads
are shared across modules — before it is built. It refines, not replaces,
`docs/dispatch-unification.md` (§"Mechanism 1"): that doc described mechanism 1 as
"global multi-dispatch"; this tightens the cross-module half to **module-scoped,
import-by-association**, which is what the namespace-hygiene direction requires
(global dispatch is what sank SPN).

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
