# Dependent sorts & the call gate: proving arguments route, at compile time

**Status: WAR — EXECUTING (design ratified via dialogue, 2026-06-20).** Slice 1
(`IrSort.Method` parameter names) **LANDED** (`8aab30a`). The remaining work is **the
call gate** — the missing dual of the return gate. Markers: **RULED** = settled ·
**DERIVED** = follows from ruled material + standing laws · **VERIFIED** = confirmed
by a run/spike · **OPEN** = undecided.

This is one facet of the type-system convergence (`project_type_spec_layering`,
`docs/feature-matrix.md`). The investigation changed the war's shape: the *hard* part
isn't representing dependent sorts (that already works) — it's that **arguments are
never proven, at compile time, to satisfy the parameters they're passed to.**

---

## 0. Ground truth (spike-verified, 2026-06-20)

Three things, all confirmed by running code:

1. **Representation already works.** A refinement predicate is an ordinary `IrExpr`
   (`@` = `SelfRef`, names = `Var`, `@.field`/`this.field` = `FieldAccess`). Dependent
   references *resolve and run* today — receiver-relative `[Int:@<this.n]`,
   sibling-param `[Int:@<x]`, and return-references-param `[OutOfRange(i)]` all
   parse, type-check, and execute (slice 1's named method sorts were the last unlock).
   Value-indexed struct sorts are **not** new syntax: `[OutOfRange(2)]` (positional)
   and `[OutOfRange:@.at==2]` (named) both work for literals; the only delta is the
   operand `literal → binder`.

2. **Return refinements are handled and sound.** Two paths, both VERIFIED:
   - **Synthesis** (`;` + staged-`let` return sorts): `manhattan(p):[ let r=p.x+p.y ->
     [Int:@==r & @>p.x & @>p.y] ];` discharges (→ 7); the same shape claiming a false
     refinement (`@<p.x`) is **rejected at compile time**. The staged `let` is a proof
     scaffold — you *guide* the checker rather than hoping it auto-infers.
   - **`assign proof`** for explicit bodies (the `isSparse` case-split from the README):
     `function isSparse(x):[Int] -> (x-3)*(x+5)` + `assign proof isSparse(x):[ (match x
     …) -> [Int:@>=-16] ]` proves the bound per region.

3. **The gap is the CALL GATE.** Arguments are never proven, at compile time, to
   satisfy the parameter refinements they're passed to. Enforcement is deferred to
   runtime dispatch — or, for dependent params, dropped entirely. VERIFIED holes:

   | call | param | today | should be |
   |------|-------|-------|-----------|
   | `h(-3)` | `[Int:@>0]` | **runtime** "no matching function" | compile error |
   | `manhattan(Point(-2,7))` | `[Point:@.x>0&@.y>0]` | **runtime** error | compile error |
   | `g(5,7)` (param `i:[Int:@<x]`) | dependent | **silent → 7** (lie) | compile error |

   `SortChecker`'s `Call` case checks only that the name exists and args are
   well-formed — *"overload-mismatch errors happen at dispatch time."* `StaticDispatch`
   resolves *which* overload via `Refinements.imply(arg_narrowing, param_sort)`
   (Passed/Failed/Residual) but it **routes, it doesn't gate** — even a provable
   `Failed` returns *Unresolved* (defer to runtime) rather than rejecting.
   **All three holes are *provable* failures** (`-3⊥@>0`; `x=-2⊥@.x>0`; `7⊥@<5` once
   `x=5` is substituted) — so a gate that rejects provable failures closes every one.

---

## 1. The call gate (the dual of the return gate)

The return gate proves a function *body* satisfies its declared *return* refinement,
at compile time, and rejects when it can't (`PontifCompiler.firstUnprovableReturn` →
`BuiltinIssuer`/`PathFacts`/`Discharge`). The **call gate** is its dual: at every call
site, prove the *arguments* satisfy the *parameters'* refinements (routing to a unique
overload), at compile time. **No runtime deferral, no silent pass.**

**The ruling (James, 2026-06-20): a refinement claim that is never proven is a compile
error — period.** Not a runtime check, not a silent pass. The default `[Sort]` must be
proven; `[!!Sort]` is the *explicit* opt-in escape hatch for deferring a check to
runtime (it is not the default). This makes refinement satisfaction a fully
compile-time property: the language has no runtime refinement surprises.

---

## 2. The resolution ladder — prove / synthesize / widen / error

When the gate meets a call it can't immediately prove, there are **four outcomes, all
at compile time**. Critically, only **error** is the *system's* action — the other
three are the **developer's** recourse (the system never silently changes your types):

1. **Prove** *(system)* — discharge the precondition from the narrowings and
   hypotheses in scope (param refinements, `match`-arm guards, `BoundAnalysis`). The
   call keeps the precise type. This is the happy path and covers most real calls
   (literals; `match`-narrowed recursion like `fib(n-1)` under `n>1`).
2. **Synthesize** *(developer)* — supply the missing proof/body via `;` or
   `assign proof`, guiding the checker (the `manhattan`/`isSparse` paths).
3. **Widen** *(developer)* — relax *your own* declared sorts to a weaker, provable
   type: write `at(i):[T|OutOfRange]` (total, no precondition) instead of
   `at(i:[Int:@<this.count]):T` (refined, needs proof). The uncertainty becomes
   explicit *in your type* and the caller must discharge it (a match arm). **The system
   does not do this for you** — no auto-widening, no surprise wider types (that would
   be the system lying about what you wrote).
4. **Error** *(system)* — if the developer has done none of the above and the gate
   can't prove the precondition, it is a **compile error**. The error names the
   unproven obligation and the three recourses.

This is the no-lie version of graceful degradation: never a silent drop (today's bug),
never a runtime dispatch surprise (today's deferral), never a magically-rewritten type
(auto-widen). Every refinement is proven, synthesized, explicitly widened by the
author, or rejected — at compile time.

---

## 3. Mechanism (reuse, don't add)

The call gate reuses the engine the return gate already uses:

- **Obligation:** at a call `f(a₁…aₙ)`, for each parameter `pᵢ:[Base:pred]`, the
  obligation is `argᵢ ⊨ pred`. For a **dependent** param, substitute the concrete
  sibling-arg narrowings and the receiver into `pred` first (`i:[Int:@<x]` with
  `x↦arg-for-x`; `[Int:@<this.n]` with `this↦receiver`). This is the only genuinely
  new step, and it's substitution, not new theory.
- **Hypotheses:** the narrowings in scope at the call site — param refinements,
  enclosing `match`-arm guards (`NarrowingInference` already computes these) — exactly
  as `PathFacts` gathers for the return gate.
- **Discharge:** `Refinements.imply` / the integer-decimal discharge engine.
  `StaticDispatch` already computes Passed/Failed/Residual; the gate's job is to turn
  **Failed → compile error** (today it defers) and, where the obligation is provable
  from in-scope hypotheses, **Residual → Passed**. Genuinely-unprovable Residual →
  compile error (the developer must prove/synthesize/widen).

No auto-widen logic exists to build (§2): widen is the developer relaxing their own
declarations.

---

## 4. Slice plan

- **Slice 1 — `IrSort.Method` carries parameter names. LANDED (`8aab30a`).** The
  fulcrum: names can be written/carried; `[Method(i:Int):R]` parses; mixed
  named/positional rejected. Back-compat constructor kept all ~20 sites compiling.
- **Slice 2 — the call gate: prove-or-error + cross-arg/receiver substitution.** Reject
  provable-Failed calls at compile time (closes all §0 holes); substitute sibling/
  receiver into dependent param preds; discharge Residual from in-scope narrowings.
  **MEASURE the blast radius** (next section) as part of this slice — it decides how
  much migration the war costs.
- **Slice 3 — `Indexed` consumer.** Total `at(i):[T|OutOfRange(i)]` (no precondition,
  widen-by-design) + the refined `at(i:[Int:@<this.count]):T` (prove). The forcing
  function (`docs/indexed-streams.md`).
- **Slice 4 — diagnostics.** The compile error names the unproven obligation and the
  prove/synthesize/widen recourses; `[!!]` documented as the opt-in escape.

Probe meter: new `callgate__*` probes (provable pass, provable-fail reject, dependent
substituted reject, match-narrowed pass, dynamic-unguarded reject) + the existing
suite as the regression/blast-radius meter.

### Implementation entry point (resume here)

Slice 2, in order — grounded in the code as of `b1d7c72`/doc rewrite:

- **(a)** `StaticDispatch` (`pontif-ir/.../StaticDispatch.java`) is **two-valued**
  (`Resolved`/`Unresolved`) but `matchStatus` is internally three-valued
  (PASSED/FAILED/RESIDUAL). Expose the three-way verdict *additively* (a `classify(...)`
  returning the status) without disturbing `inferCall`'s `Resolved/Unresolved` consumer.
- **(b)** Add cross-arg/receiver **substitution** into dependent param sorts before the
  `Refinements.imply` in `matchStatus` (so `g`'s `[Int:@<x]` becomes `[Int:@<5]` →
  FAILED, not RESIDUAL).
- **(c)** New module pass `firstUnprovableCall(module)` in `PontifCompiler`, mirroring
  `firstUnprovableReturn` (`:348`), invoked in `compileModule` (~`:269`). Walk every
  call with its in-scope narrowings (reuse `NarrowingInference`'s per-call resolution),
  classify, **FAILED → compile error**.
- **(d) MEASURE FIRST:** run (c) in **report-only** mode (log, don't error) over the
  full suite; count FAILED vs RESIDUAL calls before flipping the universal on. That
  number decides RESIDUAL's policy and is the war's migration cost (report it, §5).

The three holes to close (all FAILED): `h(-3)`, `manhattan(Point(-2,7))`, `g(5,7)`.

---

## 5. Blast radius — measure, don't assume

"A never-proven claim is a compile error" is a strong universal: it converts every
currently-runtime-checked or silently-passing call into a compile error unless the
args are statically provable. An eyeball estimate put ~10% of test calls on dynamic
args — but the discharge engine already proves arithmetic-on-narrowed values
(`n-1>0` under `n>1`), so the real figure is likely lower. **Slice 2 measures it**: wire
the gate, run the full suite, count the calls that now error. That count is the
war's migration cost and tells us whether this is a patch or a sweep — reported, not
assumed (R1: no suppression, no silent caps).

---

## 6. Lineage

| ladder rung | literature |
|---|---|
| prove | F\* / Liquid Haskell / Dafny: discharge `i:nat{i<len}` at the call |
| synthesize | proof-carrying / tactic-guided refinement |
| widen | the author choosing a weaker spec (Option vs. precondition) — *not* gradual typing (no runtime cast) |
| Fin endgame | Agda/Idris `Vec n`+`Fin n` — out-of-bounds unrepresentable |

Seminal: **Xi & Pfenning, "Eliminating Array Bound Checking Through Dependent Types,"
PLDI 1998.** Pontif's distinctive choice: full **compile-time** resolution with the
developer's explicit prove/synthesize/widen recourse — no runtime refinement checks by
default (`[!!]` is the opt-in escape).

---

## 7. WAR markers (cut sites)

Marked / to-mark with `WAR(dependent-sorts)`:

- `IrSort.Method` — parameter names (slice 1, **done**).
- `SortChecker` `Call` case — today only checks name existence ("overload-mismatch at
  dispatch time"); slice 2 adds the call gate here (or a dedicated pass mirroring
  `firstUnprovableReturn`).
- `StaticDispatch` — flips from "defer on provable-Failed" to "reject"; adds cross-arg/
  receiver substitution into dependent param sorts.
- `IrInterpreter` dispatch-failure path — the runtime error the gate preempts.
- The construction-gate / synthesis path — already sound; the call gate is the
  symmetric front-end for arguments.
