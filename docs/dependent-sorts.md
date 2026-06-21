# Dependent sorts & the call gate: proving arguments route, at compile time

**Status: WAR — EXECUTING (design ratified via dialogue, 2026-06-20).** Slice 1
(`IrSort.Method` parameter names) **LANDED** (`8aab30a`). **Slice 2 is COMPLETE** — all of (a) `classify`/`Verdict`, (d) report-only measurement,
the `imply`-hardening prerequisite (`Failed` ⟺ provably disjoint; corpus FAILED 59 → 0,
§5.1), (b) dependent-param sibling/receiver substitution, and (c) the live gate
(`FAILED → compile error`) have **LANDED**, suite green. The call gate — the missing dual
of the return gate — now rejects provably-misrouting calls at compile time and closes the
§0 holes (`h(-3)`, `g(5,7)`). What remains is the deferred **RESIDUAL policy** (the no-lie
sweep — a separate ruling, §5.1) and the later slices (3: `Indexed` consumer; 4:
diagnostics). Markers: **RULED** = settled ·
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

- **(a) DONE (`StaticDispatch.classify` + `Verdict`).** The three-way verdict is now
  exposed *additively*: `StaticDispatch.classify(overloads, args[, registry])` →
  `Verdict.{PASSED,RESIDUAL,FAILED}`, reusing the same private `matchStatus`. `resolve`'s
  `Resolved/Unresolved` consumer (`inferCall`) is untouched — `resolve` still collapses
  FAILED+RESIDUAL into `Unresolved`; `classify` keeps them apart for the gate.
- **(d) DONE — MEASURED (report-only, see §5).** `CallGate.walk(module)`
  (`pontif-ir`) classifies every in-jurisdiction call (a `Call` whose name has registered
  overloads), threading in-scope narrowings exactly as `NarrowingInference` does (param
  seedings, `let`, `match`-arm hypotheses, `Iterate` element patterns).
  `PontifCompiler.reportCallGate` logs the counts to stderr, **opt-in** behind
  `-Dpontif.callgate.report=true` (off by default — measurement scaffolding, not a
  standing pass). **Suite result: 237 RESIDUAL, 59 FAILED, 54 PASSED** (call sites across
  all suite compiles; PASSED-only modules unsummed). See §5 for the decisive finding.
- **(imply-hardening) DONE — the §5 prerequisite is cleared.** `Refinements.imply` now
  reserves `Failed` for the *provably disjoint*: union subsumption is peeled before the
  kind-mismatch catch-all (tighter-union `(A|…) ⊑ L` iff every branch ⊑ L; looser-union
  `T ⊑ (B|…)` iff T implies some branch, else Residual — never a false reject), and a
  refinement is related to a structural sort through its base (`[Countdown:p] ⊑ Countdown`
  via `Countdown ⊑ Countdown`; an unresolvable `_tuple`/`_record` base → Residual; only a
  refined **primitive** base stays a sound `Failed` against a struct). **Re-measured: the
  corpus FAILED count dropped 59 → 0**, no test regressions, the scalar-disjointness path
  (the genuine `h(-3)` holes) still `Failed`. Pinned by 6 cases in `StructuralSortTest`.
- **(b) DONE — sibling substitution in `StaticDispatch.matchStatus`.** Before the
  `imply`, each sibling parameter pinned to a singleton arg (`Refinements.uniqueValue`)
  is substituted into the other params' dependent predicates (`substituteSiblings`), so
  `g`'s `[Int:@<x]` becomes `[Int:@<5]` and `g(5,7)` FAILS provably. Self (`@`) is
  untouched; a non-singleton/absent sibling leaves the dependent sort residual (no
  invented value). Receiver-relative `[Int:@<this.n]` rides the same path (bind `this`).
  Pinned by `StaticDispatchTest` (provable-fail / provable-pass / unpinned-residual).
- **(c) DONE — the live gate.** `PontifCompiler.firstUnprovableCall` (the dual of
  `firstUnprovableReturn`) runs `CallGate.walk` and rejects the first **FAILED** call;
  invoked in `compileModule`. RESIDUAL abstains (the no-lie sweep is deferred). Two
  refinements fell out of wiring it live: (1) `CallGate.walk` also visits `module.main()`
  (the top-level expression is not a statement), so a bare top-level call is gated; (2)
  **arity is not the gate's jurisdiction** — `classify` weighs only arity-matching
  overloads and abstains (RESIDUAL) when none match, so a wrong-arg-count call (or a
  metareference/lambda invocation lowered to a 0-param `let`) is left to the existing
  "No matching method/function" diagnostics rather than mis-reported as a refinement
  failure. End-to-end pinned by `CallGateTest` (rejects `h(-3)` + dependent `g(5,7)`,
  accepts the satisfied calls, abstains on residual).

The three holes to close (all genuinely FAILED): `h(-3)`, `manhattan(Point(-2,7))`,
`g(5,7)` — *none are in the test corpus* (spike-only), so the corpus FAILED count
measures something else entirely (§5).

---

## 5. Blast radius — MEASURED (2026-06-20, report-only walk over the full suite)

The gate's classifier (`CallGate.walk`) ran report-only over every test compile.
Counts (call sites, summed across all compiles; identical on repeat runs; PASSED-only
modules not summed):

| verdict | count | meaning |
|---|---:|---|
| **FAILED** | 59 | every overload provably excluded — the gate's would-be compile errors |
| **RESIDUAL** | 237 | undecided — the prove-from-hypotheses-or-error population |
| **PASSED** | 54+ | provably routes (undercount; quiet modules unsummed) |

**The decisive finding: the FAILED count is NOT migration cost — it is a soundness
defect in `Refinements.imply`.** Every one of the 59 FAILED sites is in a program that
**compiles and runs correctly** (its suite test passes). None is one of §0's genuine
provable-disjointness holes (`h(-3)` etc. aren't in the corpus). Two families, one root
cause — `imply`'s catch-all at `Refinements.java:378-382` returns `Failed` for
"different sort kinds" on pairings that are actually subset relations:

- **`std.stream/concat` (40×) and friends** — arg `Element` (a struct) vs param
  `[Element|Leaf]` (a union *containing it*). `imply(Structural, Union)` has no
  union-membership case → hits the catch-all → `Failed`. Correct answer: `Passed`.
- **`Countdown.toZero`, `N.ping`/`N.pong` (recursive methods)** — arg
  `[Countdown:@.n==n-1]` (a refined struct) vs param `Countdown` (its own base).
  `imply(Refined-struct, Structural)` → catch-all → `Failed`. Correct: `Passed`.
- **operators (`+`, `*`, `//`, cross-module `app.cd/+`, `num.frac//`)** — same class of
  kind-pairing the narrowing engine doesn't model as a subset.

A `Failed` that means "I couldn't relate these sort kinds" is **not** the same as
"provably disjoint" — yet the gate (step (c)) reads `Failed` as the latter. **Flipping
FAILED → compile error today would reject valid recursion, methods, and stream
combinators.** So the war reorders: a new prerequisite — *make `imply`'s `Failed` ⟺
provably disjoint* (add the struct∈union membership case; route refined-struct-vs-base
through the structural/refinement subset check; reserve `Failed` for genuine
disjointness, else `Residual`) — must land **before** step (c), alongside step (b)'s
substitution.

### 5.1 Prerequisite cleared — re-measured FAILED 59 → 0 (2026-06-20)

The `imply`-hardening landed (entry point, "imply-hardening DONE"). Re-running the
report-only walk over the full suite:

| verdict | before | after |
|---|---:|---:|
| **FAILED** | 59 | **0** |
| RESIDUAL | 237 | 240 |
| PASSED | 54+ | 86+ |

Every former FAILED was a false positive and is now `Passed` or `Residual`; the suite is
green (no regressions). Both families closed: `std.stream/concat` now routes (its
recursive call lands on RESIDUAL only because `NarrowingInference` doesn't track the
`a.rest` field-access narrowing — a *precision* gap, not a soundness one); the recursive
methods (`toZero`, `ping`/`pong`) and the refined-tuple call (`swap`/`dup`) all clear.
Crucially, the scalar-disjointness path is untouched — `[Int:@==-3] ⊑ [Int:@>0]` still
`Failed` — so the genuine §0 holes will still reject once (b)+(c) wire them in. The
hardening only retired the `Failed`s that meant "couldn't relate the kinds." With the
corpus FAILED count at 0, **step (c) can flip without rejecting anything valid in the
suite** — the remaining work is RESIDUAL's policy and (b)'s dependent-param substitution.

The **237 RESIDUAL** is the real prove-or-error surface (e.g. `std.stream/exchange` 80×,
`partition`/`map`/`concat` 40× each, recursive `sum` 26×, `factorial` 8×) — the genuine
migration cost, and it is large: this is a sweep, not a patch. RESIDUAL's policy
(prove-from-in-scope-hypotheses, else error) is what the bulk of the work will be.
Reported, not assumed (R1: no suppression, no silent caps).

### 5.2 Gate live — FAILED → compile error shipped (2026-06-20)

(b) and (c) landed on top of the cleared prerequisite. The gate now rejects a FAILED
call at compile time; the suite is green with **0 corpus FAILED** (the only FAILEDs are
`CallGateTest`'s own deliberate `h(-3)`/`g(5,7)`). Wiring it live taught two boundaries,
both folded into `CallGate`/`classify`: the walk must visit `module.main()` (top-level
calls aren't statements), and **arity mismatch is out of jurisdiction** — `classify`
abstains (RESIDUAL) when no overload matches arity, leaving wrong-arg-count and
metareference/lambda-invocation errors to the existing "No matching method/function"
diagnostics. `FAILED` is now exclusively "an arity-matching overload exists, its
refinement is provably violated."

**Deferred (a separate ruling): the RESIDUAL → error sweep.** The no-lie law says an
unproven refinement is an error, which would promote the 240 RESIDUAL calls. That is the
large migration and is left for an explicit decision — the gate ships today on the safe,
provable-disjoint subset.

### 5.3 Discharge foundation — hypothesis-bounded args + disjoint-based FAILED (2026-06-21)

Built as slice 3's foundation (the Indexed consumer needs it). Two pieces, both
reusing existing machinery:

- **`NarrowingInference.inferArg`** — at a call site, an `Int` value-pin is projected
  over the in-scope hypotheses to a bound: `[Int:@==n-1]` under `n:[Int:@>0]` →
  `[Int:@>=0]` (the same `BoundAnalysis`/`closeOver` projection the return gate uses).
  This is what lets a decremented/recursive arg discharge against a weaker param
  refinement. The call gate (`CallGate`) uses it for arg narrowings.
- **`StaticDispatch.gateFit` + `provablyDisjoint`** — the gate's FAILED is now
  **disjoint-based**, distinct from `matchStatus`'s subset-based exclusion (which
  `resolve`/dispatch keeps). The lesson that forced this: bounding an arg to a *range*
  exposed that `imply`'s scalar `Failed` means **"not a subset," not "disjoint"** —
  `[Int:@>=0]` is not ⊆ `[Int:@>0]` yet *overlaps* it. The first attempt (inferArg
  alone) wrongly rejected multi-overload `factorial` because the bounded arg straddled
  `{[Int:0],[Int:@>0]}` and each overload reported subset-`Failed`. `provablyDisjoint`
  decides true emptiness via `BoundAnalysis` (`@==-3 ∩ @>0 = ∅` excludes; `@>=0 ∩ @>0 ≠ ∅`
  abstains), completing `Failed ⟺ provably disjoint` for scalars (the struct/union case
  landed in §5.1).

Result (suite green, no regressions): **single-overload inductive recursion now PROVES**
(`fac(n-1)` → PASSED), **multi-overload recursion abstains** (`sum(n-1)` →
RESIDUAL, never rejected), provable misroutes FAIL (`h(-3)`, `g(5,7)`), and an
overlapping/uncertain arg is RESIDUAL. Exhaustiveness (proving multi-overload recursion
PASSED rather than abstaining) is deliberately **not** built — slice 3's `at` is a single
refined method, so it isn't needed; multi-overload abstention is sound. Pinned by
`StaticDispatchTest` (range-overlap→residual, range-disjoint→failed) and `CallGateTest`
(single- and multi-overload recursion compile).

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
- `SortChecker` `Call` case — still only checks name existence; the call gate lives in a
  dedicated pass (`PontifCompiler.firstUnprovableCall`) mirroring `firstUnprovableReturn`,
  **done**.
- `StaticDispatch` — `classify`/`Verdict` (slice 2 (a)) + sibling/receiver substitution in
  `matchStatus` (`substituteSiblings`, slice 2 (b)) + arity-out-of-jurisdiction in
  `classify` (slice 2 (c)), all **done**. (`resolve`'s two-valued consumer untouched.)
- `Refinements.imply` (`pontif-core`) — **DONE (slice-2 prerequisite):** `Failed` now
  means *provably disjoint*, never "couldn't relate sort kinds". Union subsumption (both
  sides) + refinement-through-base peels before the kind-mismatch catch-all; `isPrimitiveBase`
  guards the sound-Failed case. Corpus FAILED 59 → 0 (§5.1); pinned by `StructuralSortTest`.
- `CallGate` (`pontif-ir`) — the classifier walk (slice 2 (d)); walks statements **and**
  `module.main()`. Consumed two ways: `PontifCompiler.reportCallGate` (opt-in measurement,
  `-Dpontif.callgate.report=true`) and `firstUnprovableCall` (the live FAILED→error gate,
  (c)). **Done.**
- `IrInterpreter` dispatch-failure path — the runtime error the gate preempts for FAILED;
  still the live path for **arity** mismatches (the gate abstains on those by design) and
  for RESIDUAL calls (pending the no-lie sweep).
- The construction-gate / synthesis path — already sound; the call gate is the
  symmetric front-end for arguments. **Slice 2 complete.**
