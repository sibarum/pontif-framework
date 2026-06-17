# Pontif Language-Interaction Inventory

Generated from a probe sweep across six interaction categories (dispatch, traits,
destructure, generics, inference, methods). Each probe carries an EXPECTED
status/value (manifest) and an ACTUAL status/detail (harness). The two are joined
by probe name and classified:

- **PASS** — actual == expected (incl. expected-a-fail / got-the-same-kind-of-fail).
- **BUG** — expected `OK`, actual `COMPILE_FAIL` / `RUNTIME_FAIL` / `CRASH`. A capability the language should have but rejects/crashes.
- **MISFIRE** — expected a `*_FAIL`, actual `OK`. A *missing check* — the language silently accepts something it should reject.
- **PROBE_ERROR** — the probe itself looks malformed (a parse error reading like an author syntax mistake, not a language gap). Flagged separately so it doesn't pollute the bug count.

Totals: **128 PASS, 15 BUG, 5 MISFIRE, 0 PROBE_ERROR** (148 probes).

After review, **none** of the failures are PROBE_ERRORs: every COMPILE_FAIL on an
expected-OK probe is the compiler rejecting legitimate syntax with a *located,
language-level* diagnostic (cross-module struct-decl-not-found, "cannot determine
receiver type", "Unknown sort 'T'"), not a typo in the probe source. The two
parse-error MISFIRE-adjacent cases (`methods__14`) are genuine parser gaps, not
author mistakes.

---

## 1. Summary table

| Category    | PASS | BUG | MISFIRE | PROBE_ERROR | Total |
|-------------|-----:|----:|--------:|------------:|------:|
| dispatch    |   22 |   2 |       2 |           0 |    26 |
| traits      |   23 |   1 |       0 |           0 |    24 |
| destructure |   21 |   3 |       1 |           0 |    25 |
| generics    |   24 |   4 |       0 |           0 |    28 |
| inference   |   20 |   3 |       0 |           0 |    23 |
| methods     |   18 |   2 |       2 |           0 |    22 |
| **Total**   | **128** | **15** | **5** | **0** | **148** |

---

## 2. What works

The core single-module surface is solid. Highlights of the 128 passing probes:

- **Free-function multi-dispatch with refinement specificity** — most-specific
  selection, fall-through to the general arm, complementary splits, transitive
  three-level specificity (`dispatch__01,02,03,20,24`).
- **Struct/operator dispatch in one module** — `+ - * / % ^ < == != <=` overloaded
  on structs, chains, mixed precedence, operator-calls-operator, struct-on-left
  mixed-type ops (`dispatch__05–13,21,23`).
- **Cross-module free-fn + infix operator use** — free overload on an imported type,
  infix `+`/`*` defined in a dep used in entry, operator-body-uses-imported-operators
  (the tractioncd shape) (`dispatch__15,16,17`).
- **Traits end to end** — decl+assign, trait-typed params, DATA attributes (producer,
  field, state-reading producer), operator-contract witnessing, struct↔trait
  up/down-cast with no-lie downcast rejection, multi-satisfier dispatch, recursive
  trait impl, and the cross-module trait/satisfier/param cases (`traits__01–04,07,
  10–17,19,21,22`).
- **Destructuring (single-module, every form)** — positional param, `.{}` by-name
  (rename / subset projection), match (struct / nested / tuple / discard /
  mixed-constrain-bind / struct-in-tuple), let (tuple / nested-struct /
  tuple-in-tuple / discard), real-field narrowing, binder-as-method-receiver
  (`destructure__01–12,15–17,20,25`). Cross-module `.{}` param works
  (`destructure__22`).
- **Generics** — identity, parametric struct construct/unbox (Int/Bool/struct
  payloads), Pair consistency + conflict rejection, call-site inference into the
  construction gate, nested parametric, parametric trait decl, is-a parametric base
  (invariant arg), forwarding base, by-name construction, two type params, generic
  returning a parametric, and the cross-module parametric-struct inference
  (`generics__01–09,11,13,16,17,19–21,24–26,28`).
- **Inference / methods (single-module)** — field-access typing, chained projection
  (+ ending in a method), operator-result→method/field, match-arm narrowing,
  let-sort inference through arithmetic, return-narrowing (provable accepted /
  unprovable rejected), recv.method, static `Type.of`, chained methods, forward
  refs, self- and mutual recursion, method-on-fn-return, method-returns-String, and
  most cross-module method cases (field access, field-method, chained projection,
  recv-from-imported-fn-return, this.field.method, chain-to-string)
  (`inference__01–17,19,22,23`, `methods__01–13,15,16,19–22`).

---

## 3. What's broken

### BUGs (expected OK, got a failure)

| Probe | Tests | Actual error |
|-------|-------|--------------|
| `dispatch__22_crossmodule_div_chain` | chain of `/` on an imported type, `/` defined in dep | COMPILE_FAIL: "Division/remainder/power ('/','%','^') is not supported inside refinement predicates - the discharge kernel is linear." |
| `dispatch__26_crossmodule_method_form_operator` | legacy method-form `MV.+` in dep, used infix in entry | RUNTIME_FAIL: internal ClassCastException `RecordValue cannot be cast to java.lang.Long` |
| `traits__20_crossmodule_operator_contract` | Numeric `+` contract + witness all in dep; entry imports Vector and uses `+` | COMPILE_FAIL: trait impl `num.vector/Vector : num.vector/Numeric` requires `+`, "but no overload `+(num.vector/Vector, ...)` is declared" (FQN-qualified witness not found) |
| `destructure__21_crossmodule_positional_param` | positional param destructure of imported struct `[Vec(x,y)]` | COMPILE_FAIL (parse): "Bare field name 'x' inside [Vec(...)] requires 'Vec' to be declared before this point (struct decl not found)" |
| `destructure__23_crossmodule_match` | match destructure of imported struct `[Vec(x,y)]` | COMPILE_FAIL (parse): same "struct decl not found" for Vec |
| `destructure__24_crossmodule_nested_method_recv` | nested match of imported structs `[Outer(Inner(x,y),c)]` | COMPILE_FAIL (parse): "A positional pattern inside [Outer(...)] requires 'Outer' to be declared before this point" |
| `generics__14_bound_method_call` | `[type E:Sized]` body calls trait method `a.size()` | COMPILE_FAIL: "No method 'size' on type 'E'" |
| `generics__22_crossmodule_generic_imported_op` | generic bounded body uses imported `+` on imported Vec; trait/assign local in B | COMPILE_FAIL: trait impl requires `+`, "no overload `+(gen.vecmod/Vec, ...)` declared" |
| `generics__23_crossmodule_trait_bound` | generic bounded by trait imported from another module | COMPILE_FAIL: same FQN-witness-not-found shape |
| `generics__27_parametric_method` | method on parametric struct returning T (`method Box.get():T`) | COMPILE_FAIL: "Unknown sort 'T' - not a primitive and not a declared type" |
| `inference__18_crossmodule_operator_method` | `(a+b).sum()` on imported-type operator result, cross-module | COMPILE_FAIL: "Cannot determine the type of the receiver of method 'sum'" |
| `inference__20_crossmodule_op_localmethod` | local method on imported type, called on cross-module operator result | COMPILE_FAIL: "Cannot determine the type of the receiver of method 'mag2'" |
| `inference__21_return_narrow_via_field` | return refinement `[Int:@>0]` discharged from a refined struct field read `h.n` | COMPILE_FAIL: "Cannot prove the declared return refinement of 'get': r_0 > 0" |
| `methods__14_method_on_tuple_binder` | method on struct component bound by positional tuple destructure `[(i,k)]` | COMPILE_FAIL (parse): "Expected a sort (bare ident or '[...]'); got LPAREN '('" |
| `methods__18_crossmodule_method_on_operator_result` | `(a+b).sum()` on imported operator result inside importing fn | COMPILE_FAIL: "Cannot determine the type of the receiver of method 'sum'" |

### MISFIREs (expected a `*_FAIL`, got OK — a missing check)

| Probe | Should reject because | Actual |
|-------|-----------------------|--------|
| `dispatch__14_mixed_int_times_struct` | `3*Vec`: parser keeps BinOp, `*(Int,Vec)` never routed — manifest expected a RUNTIME_FAIL | OK → `15`. Silently produced a number; the primitive-left mixed operator was *not* the documented failure. |
| `dispatch__18_crossmodule_recv_method` | manifest flags `recv.method()` on an imported type as the known Phase-2 gap (expected RUNTIME_FAIL) | OK → `7`. The gap is closed for plain recv.method (good), so the *manifest's expectation* is stale, not a real over-acceptance. |
| `destructure__18_positional_subset_rejected` | positional `[P(a)]` over a 2-field struct should reject (lying by omission, arity-total rule) | OK → `3`. Subset positional pattern silently accepted. |
| `methods__17_crossmodule_static_of` | dotted static `Type.of` NOT in the dep's exports list should be a name error | OK → `14`. The unexported dotted static was reachable across the module boundary. |
| `methods__22_crossmodule_chain_to_string` | `Type.of(...).scale(...).show()` where dotted `of` is not exported | OK → `"<6, 6>"`. Same unexported-dotted-static leak as `methods__17`. |

> Note on `dispatch__14` / `dispatch__18`: these two MISFIREs are arguably *capability
> gains* (the language now does more than the manifest assumed) rather than soundness
> holes. The three real soundness MISFIREs are `destructure__18`, `methods__17`,
> `methods__22` — each silently accepts something the no-lie / export discipline says
> it should reject.

---

## 4. Root-cause clustering

Every BUG/MISFIRE is bucketed under one of the five structural issues (or "other"),
with reasoning. **The clusters are numbered in the recommended fix order** (§5): the
foundational FQN-identity refactor is (1), and the deepest/most-orthogonal cleanups
are (4)–(5).

### (1) FQNs hand-split on '/' everywhere — division-operator / module-separator collision

A module FQN is dotted (`num.vector`), the separator before a member is `/`, and the
division operator is itself `/` — so a linked division overload is `num.vector//`.
`OperatorResolver.simpleName` (line 218–225) hand-rolls `indexOf('/')` (not
`lastIndexOf`, with a paragraph of comment explaining why the latter would drop the
division overload). This fragile split is the shared root of the FQN-qualified
operator-witness lookups failing to match:

- **`dispatch__22_crossmodule_div_chain`** — surfaces as a refinement-kernel error
  ("'/' not supported inside refinement predicates — the discharge kernel is linear"),
  but the trigger is the cross-module `/` overload's FQN routing colliding with the
  predicate path: a chained `/` on an imported type drags the division name into a
  context the linear kernel rejects. *(BUG)*
- **`traits__20_crossmodule_operator_contract`** — the witness exists
  (`+(num.vector/Vector, num.vector/Vector)`) but the contract check looks for it under
  a differently-FQN-qualified name and reports "no overload declared." *(BUG)*
- **`generics__22_crossmodule_generic_imported_op`** — same: trait impl
  `gen.vecmod/Vec : gen.adder/Numeric` requires `+`, witness present, "no overload
  `+(gen.vecmod/Vec, ...)` declared." *(BUG)*
- **`generics__23_crossmodule_trait_bound`** — same FQN-witness mismatch for
  `gen.client/Vec : gen.traitmod/Numeric`. *(BUG)*

These four share one signature: a cross-module operator/contract whose witness is
present but invisible because the name is FQN-qualified on one side and not the other,
and the only place the qualifier is parsed is the hand-split `/`-handling. (The `+`
cases don't hit the `/` collision directly, but they exercise the same
"split-the-FQN-by-hand to find the operator's home module" code that the `/` special
case proves is brittle and inconsistent across call sites.)

Cluster (1) owns: **dispatch__22, traits__20, generics__22, generics__23** (4 BUGs).

### (2) Destructuring wired per-form with seams — positional-param, .{}-param, match, tuple, nested

The parser resolves struct *shapes at parse time* via `patternShapeFor(typeName)`
(AltParser.java:2836), which only sees declarations earlier **in the same file**. Each
destructuring form is wired independently, so they fail asymmetrically across the
module boundary:

- **`destructure__21_crossmodule_positional_param`** — `[Vec(x,y)]` positional param of
  an imported struct: "requires 'Vec' to be declared before this point (struct decl not
  found)." *(BUG)*
- **`destructure__23_crossmodule_match`** — `[Vec(x,y)]` in a match: same parse-time
  struct-decl-not-found. *(BUG)*
- **`destructure__24_crossmodule_nested_method_recv`** — `[Outer(Inner(x,y),c)]` nested:
  "positional pattern inside [Outer(...)] requires 'Outer' declared before this point."
  *(BUG)*
- **`destructure__22_crossmodule_dotbrace_param`** *(PASS)* — the `.{}` by-name form
  *succeeds* cross-module (`12`) where the positional/match forms fail, proving the seam:
  one form learned to defer shape resolution past parse, the others didn't.
- **`methods__14_method_on_tuple_binder`** — `[(i,k)]` tuple binder then method on a
  component: parse error "Expected a sort … got LPAREN '('." The tuple-binder form
  doesn't accept the same pattern surface the struct forms do. *(BUG — parser gap, same
  per-form-seam family.)*
- **`destructure__18_positional_subset_rejected`** — positional `[P(a)]` over a 2-field
  struct should be rejected by the arity-total rule but is accepted. The match/positional
  arity check (which fires correctly for `destructure__19` too-many-slots) does not fire
  for the too-few / subset case in the positional-param form — a per-form gap in the
  same wiring. *(MISFIRE.)*

Cluster (2) owns: **destructure__21, destructure__23, destructure__24, methods__14**
(4 BUGs) + **destructure__18** (1 MISFIRE).

### (3) Linear pass ordering can't model mutual dependence — MethodResolver before OperatorResolver

`IrCompiler.compile` runs `MethodResolver.resolve` (line 40) **then**
`OperatorResolver.resolve` (line 48). `MethodResolver` types a method's receiver via
`NarrowingInference.infer` (MethodResolver.java:216). When the receiver is an
*operator result* (`(a+b).sum()`), the `+` is still a raw `BinOp` — OperatorResolver
hasn't turned it into a dispatch `Call` yet — so `NarrowingInference` can't determine
the BinOp's result sort and `MethodResolver` throws "Cannot determine the type of the
receiver." The dependency is genuinely mutual: method-on-operator-result needs the
operator resolved first, while operator-on-method-result needs the method resolved
first. A single linear order can satisfy only one direction.

The single-module cases (`inference__05`, `methods__05`) pass because the operator's
operand sorts are locally visible and NarrowingInference can fold the BinOp's result
sort directly; the cross-module cases fail because the operator's overload lives behind
a `requires` boundary that NarrowingInference, mid-MethodResolver, can't yet see linked.

- **`inference__18_crossmodule_operator_method`** — `(a+b).sum()` cross-module. *(BUG)*
- **`inference__20_crossmodule_op_localmethod`** — local method on a cross-module
  operator result. *(BUG)*
- **`methods__18_crossmodule_method_on_operator_result`** — same shape inside an
  importing function. *(BUG)*

Cluster (3) owns: **inference__18, inference__20, methods__18** (3 BUGs).

### (4) Operator routing decided twice — parse-time name-based + post-link OperatorResolver correction

Operators are routed once at parse time (AltParser keeps a `BinOp` and guesses by
operator name) and again post-link in `OperatorResolver` (which corrects to the exact
overload by operand sort). The two-decision design leaks:

- **`dispatch__26_crossmodule_method_form_operator`** — a *legacy method-form* operator
  `MV.+` declared in a dep, used infix in entry, routes through the wrong arm and the
  interpreter tries to treat a `RecordValue` as a `Long` (ClassCastException). The
  parse-time guess and the post-link correction don't agree on the method-form vs
  free-form operator shape across the boundary. *(BUG)*
- **`dispatch__14_mixed_int_times_struct`** — `3*Vec` (primitive on the left): the
  manifest says the parser keeps the BinOp and `*(Int,Vec)` is never routed, expecting
  a RUNTIME_FAIL. It returned `15` instead — so the *second* decision now routes a case
  the first decision was documented to drop. The two passes have diverged in opposite
  directions from the spec. *(MISFIRE — though a capability gain, it is evidence of the
  double-decision drift.)*

Cluster (4) owns: **dispatch__26** (BUG), **dispatch__14** (MISFIRE).

### (5) Two divergent type-inference paths — `NarrowingInference.infer` vs `SortChecker.inferSort`

`SortChecker` (pontif-ir/.../SortChecker.java:2009) carries a *complete second*
expression-typing implementation (`inferSort`, recursing through BinOp / FieldAccess /
Match at lines 2045–2062), entirely separate from `NarrowingInference.infer` that the
MethodResolver and the return-refinement gate consume. When refinement facts flow
through a field read or a let chain, the two paths disagree on how much they carry.

- **`inference__21_return_narrow_via_field`** — the return gate can't prove `r_0 > 0`
  from a refined struct field `h.n`, even though the field's declared sort carries the
  refinement. The narrowing the field read should propagate is visible to one path but
  not the discharge path. *(BUG)*
- **`traits__18_producer_violates_refinement_reject`** *(PASS by classification, but
  same machinery)* — the producer-violates-refinement rejection actually fires via the
  identical "Cannot prove the declared return refinement of 'Ipsum.weight'" path,
  confirming the discharge path is the shared seam: it correctly rejects there and
  wrongly rejects in `inference__21`. The discriminating signal (refined-field
  provenance) lives in the narrowing path and never reaches the gate.

Cluster (5) owns: **inference__21** (1 BUG).

### Other

Not attributable to the five structural issues:

- **`generics__14_bound_method_call`** — `[type E:Sized]` body calls `a.size()`, rejected
  "No method 'size' on type 'E'." Trait-bound method members aren't surfaced as callable
  on a bounded type parameter. This is a generics/trait-bound machinery gap (the bound's
  method contract isn't consulted when typing `a.size()`), adjacent to but distinct from
  cluster (5)'s inference divergence. *(BUG — Other.)*
- **`generics__27_parametric_method`** — `method Box.get():T` → "Unknown sort 'T'." The
  struct's type parameter is not in scope inside its own method's return-sort position.
  A type-parameter scoping gap, not one of the five. *(BUG — Other.)*
- **`methods__17_crossmodule_static_of` / `methods__22_crossmodule_chain_to_string`** —
  an unexported dotted static (`Type.of`) is reachable across the module boundary.
  An export-gating / visibility hole, not one of the five inference/routing/destructure
  seams. *(MISFIRE ×2 — Other.)*
- **`dispatch__18_crossmodule_recv_method`** — plain `recv.method()` cross-module now
  works; the manifest's RUNTIME_FAIL expectation is stale. *(MISFIRE — stale expectation,
  not a defect.)*

### Cluster tally

| Cluster | BUGs | MISFIREs |
|---------|-----:|---------:|
| (1) FQN hand-split on '/' | 4 | 0 |
| (2) destructuring per-form seams | 4 | 1 |
| (3) linear pass ordering | 3 | 0 |
| (4) operator routed twice | 1 | 1 |
| (5) divergent inference paths | 1 | 0 |
| Other | 2 | 3 |
| **Total** | **15** | **5** |

---

## 5. Prioritized refactor recommendation

The bug mass is overwhelmingly **cross-module** and concentrated in two clusters that
together account for 8 of 15 BUGs. Recommended order (matches the cluster numbering):

### First: Cluster (1) — unify FQN handling into a typed identity (kills 4 BUGs)

**Why first:** highest bug-kill-per-unit-of-risk. Four cross-module BUGs
(`dispatch__22`, `traits__20`, `generics__22`, `generics__23`) all reduce to "the
operator/contract witness exists but is looked up under an inconsistently-qualified
name." The `OperatorResolver.simpleName` `/`-split (with its defensive comment about
`indexOf` vs `lastIndexOf`) is proof the string-FQN representation is structurally wrong.

**Shape of the fix:** replace string FQNs with a small `QualifiedName(module, member)`
value type carried through linking, so the module qualifier and the member name (which
may itself be `/`) are never re-derived by hand-splitting a string. All the
`split('/')` / `indexOf('/')` sites (DispatchTable, Force, OperatorResolver,
ModuleSymbolTable, NameResolver, SortChecker) consume the structured form; operator and
trait-contract witness lookup then compare `(module, member)` pairs instead of
re-qualified strings. The division operator stops being a special case because `member
== "/"` is just data, not a delimiter collision.

### Second: Cluster (2) — single deferred shape-resolution for all destructuring forms (kills 4 BUGs + 1 MISFIRE)

**Why second:** four BUGs + one soundness MISFIRE, and the `.{}` form already proves the
fix is viable (it resolves shapes post-parse and works cross-module). The asymmetry
(`destructure__22` passes, `destructure__21/23/24` fail) is the strongest possible
evidence that the forms diverged and should share one path.

**Shape of the fix:** lift struct-shape resolution out of the parser
(`patternShapeFor` at parse time) into a single post-link destructuring resolver that
runs after `requires` linking, consumed identically by positional-param, `.{}`-param,
match, tuple, and nested forms. Fold the arity-total check (the rule that fires for
`destructure__19` too-many but is missing for `destructure__18` too-few/subset) into
that one resolver so it can't be implemented inconsistently per form. The
`methods__14` tuple-binder parse gap is fixed by routing the tuple-binder pattern
through the same surface.

### Third: Cluster (3) — fixpoint the method/operator resolution instead of a fixed order (kills 3 BUGs)

**Why third:** real but narrower (3 BUGs, all the `(a+b).method()` cross-module shape),
and it is the riskiest of the three because it touches the resolution scheduler.

**Shape of the fix:** replace the fixed `MethodResolver`-then-`OperatorResolver`
sequence with a worklist that re-runs both until fixpoint (or a single bottom-up pass
that resolves operators and methods in one tree walk, typing each node from its
already-resolved children). Operator-result-as-receiver and method-result-as-operand
then both resolve, because neither pass globally precedes the other.

### Deferred (lower bug-kill or orthogonal)

- **Cluster (4)** — fold the parse-time operator guess into the post-link
  `OperatorResolver` so routing is decided once (`dispatch__26`, and re-aligns
  `dispatch__14` with whatever the intended spec is). Partly subsumed by the Cluster (3)
  fixpoint work.
- **Cluster (5)** — collapse `SortChecker.inferSort` and `NarrowingInference.infer`
  into one engine so refinement provenance reaches the discharge gate (`inference__21`).
  One BUG, but architecturally the deepest cleanup; worth doing after the FQN/destructure
  unifications de-risk the surrounding code.
- **Other** — trait-bound method members on a type parameter (`generics__14`),
  type-parameter scope in a parametric struct's own method (`generics__27`), and the
  unexported-dotted-static export hole (`methods__17`, `methods__22`) are independent
  point fixes, schedulable any time.

**Bottom line:** do **Cluster (1) (FQN identity)** first — it is the cheapest fix with
the highest cross-module bug-kill (4) and removes the brittle `/`-split that makes every
other cross-module operator/trait change risky.
