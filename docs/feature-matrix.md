# Feature Matrix

The feature matrix maps how Pontif's language **constructs** (rows) compose with the
cross-cutting **type-system capabilities** (columns), and tracks the interdependencies
whenever the type system is enhanced. It is the *compositionality* ledger — the
type-system analogue of the probe harness (which is the *behavior* ledger).

**The no-lie rule applies to this file.** A "supported" cell must name a **passing
test or probe** that witnesses it (see [Witnesses](#witnesses)) — a `^^^` with no
witness is exactly the unverified claim Pontif refuses everywhere else. Cells that
are gaps, partials, or deliberate absences are explained in [Notes](#notes).

**Staleness discipline.** Cells point at named tests so "is this still true?" is
*re-runnable*, not *re-remembered*. When a feature changes, re-validate its **row or
column** (scoped, not a full sweep); cosmetic changes trigger nothing. If a cited
test is renamed or deleted, its cell is stale by construction — that's the detector.

| Symbol | Description                                         |
|--------|-----------------------------------------------------|
| ^^^    | Fully supported, witnessed.                         |
| ^^     | All relevant functionality supported, witnessed.    |
| ^      | Intentionally not supported, N/A (a design choice). |
| /      | Partially implemented — not complete.               |
| WIP    | Work-in-progress.                                   |
| !      | Critical path — incomplete (needed, not built).     |
| !!     | Critical path — needs design.                       |
| X      | Not supported, nofix.                               |
| ?      | New requirement — not scoped or committed.          |
| *      | Future roadmap, aspirational, nice-to-have.         |
|        | (blank) No requirement identified.                  |

Columns: **this** = `@`/`this`/`this.type` self-reference · **Refine** = refinement
sorts `[Base:pred]` · **Depend** = sorts referencing *value* binders (param,
sibling field, receiver) · **Generic** = type parameters `[type T]` · **Traits** =
trait contracts (methods/attributes/operators) · **TypeFrag** = nameable/reusable
sort fragments · **Iter** = stream/iteration substrate · **Infer** = narrowing
inference · **Synth** = `;` value/spec synthesis · **Proofs** = proof discharge /
receipt graph · **Nominal** = by-name typing · **Struct** = shape-based typing.

| Construct      | this | Refine | Depend | Generic | Traits | TypeFrag | Iter | Infer | Synth | Proofs | Nominal | Struct |
|----------------|------|--------|--------|---------|--------|----------|------|-------|-------|--------|---------|--------|
| @              | ^^^  | ^^^    | /      | ^^      |        | ?        |      | ^^^   | ^^^   | ^^^    | ^^      | ^^     |
| let            | ^^   | ^^^    | /      | ^^      | ^^     | /        |      | ^^^   | ^^^   | ^^^    | ^^^     | ^^^    |
| struct         | ^^   | ^^^    | !!     | ^^^     | ^^^    | ^^       | ^    | ^^^   | ^^^   | ^^^    | ^^^     | ^^^    |
| trait          | ^^^  | ^^^    | !!     | ^^^     | ^^^    | !        |      | ^^    |       | ^^     | ^^^     | !      |
| match          |      | ^^^    |        |         | ^^     |          |      | ^^^   |       | ^^     | ^^^     | ^^^    |
| destructuring  | ^^   | ^^     |        | ^^      |        |          |      | ^^    |       | ^^     | ^^^     | ^^^    |
| assign trait   | ^^   | ^^     | !!     | ^^^     | ^^^    | !        |      | ^^    |       | ^^     | ^^^     | !      |
| assign proof   |      | ^^^    |        |         |        |          |      | ^^    |       | ^^^    | ^^      |        |
| function       | ^    | ^^^    | /      | ^^^     | ^^^    | ^^       |      | ^^^   | ^^^   | ^^^    | ^^^     | ^^     |
| method         | ^^^  | ^^^    | !!     | ^^      | ^^^    | ^^       |      | ^^^   | ^^    | ^^     | ^^^     | ^^     |
| ; (synthesize) | ^    | ^^^    | /      | ^^      |        | /        |      | ^^    | ^^^   | ^^^    |         |        |
| Stream         |      | ^^     |        | *       | !      |          | ^^   | ^^    |       |        | ^^      | ^^^    |
| Indexed        | !    | !      | !!     | !       | !      |          | !    |       |       |        | !       | !      |
| iter           |      | ^^     |        | *       |        |          | /    | ^^    |       |        |         | ^^     |
| op overloading |      |        |        |         |        |          |      |       |       |        |         |        |
| multi-dispatch |      |        |        |         |        |          |      |       |       |        |         |        |
| module         |      |        |        |         |        |          |      |       |       |        |         |        |
| requires       |      |        |        |         |        |          |      |       |       |        |         |        |
| exports        |      |        |        |         |        |          |      |       |       |        |         |        |

---

## Witnesses

Each supported cell, with the passing test(s)/probe(s) that witness it. Probes live in
`pontif-runtime/src/test/resources/probes/`; the rest are JUnit classes.

**@** — this: `StructRefinementTest` · Refine: `StructRefinementTest`,
`StructRefinementAltTest`, `dispatch__03_refinement_split` · Depend (partial, see N1):
`SpecOnlySynthesisTest` · Generic: `TypeParameterCallSiteTest`,
`generics__06_callsite_inference_gate` · Infer: `NarrowingInferenceTest` · Synth:
`SpecOnlyLetTest` · Proofs: `ConstructionGateTest` · Nominal/Struct:
`StructRefinementTest` (`@.x + @.y` cross-field).

**let** — this: `inference__15_this_field_destructure_let` · Refine/Proofs:
`LetClaimGateTest` · Depend (partial, N1): `SpecOnlyLetTest` · Generic:
`generics__13_bound_let_chain` · Traits/TypeFrag (N3): `TypeAliasIntegrationTest`,
`ReusableSortTest` · Infer: `inference__12_let_sort_inference_arith` · Synth:
`SpecOnlyLetTest` · Nominal/Struct: `destructure__09_let_tuple`,
`destructure__10_let_nested_struct`.

**struct** — this: `methods__04_this_field_method` · Refine:
`StructRefinementTest`, `ConstructionGateTest` · Generic: `TypeParameterDeclTest`,
`TypeParameterConstructionTest`, `generics__02_box_construct_open` · Traits:
`TraitAttributeTest` · TypeFrag: `TypeAliasIntegrationTest` (alias to structural sort)
· Infer: `inference__01_field_access_typing`, `StructNarrowingTest` · Synth:
`StructExtensionTest` (promotion) · Proofs: `ConstructionGateTest` · Nominal:
`StructExtensionTest`, `ClaimRuleTest` · Struct: `StructExtensionTest`,
`PartialPatternTest`.

**trait** — this: `AssociatedTypeDeclTest`, `AssociatedTypeSelfTypeTest` · Refine:
`TraitAttributeTest`, `traits__18_producer_violates_refinement_reject` · Generic:
`TypeParameterTraitTest`, `generics__16_parametric_trait_decl` · Traits:
`TraitAttributeTest`, `OperatorTraitContractTest` · Infer: `TypeParameterTraitSortTest`
· Proofs: `traits__18…` · Nominal: `traits__01_decl_assign_basic`,
`RecursiveTraitTest`.

**match** — Refine: `MatchTotalityTest`, `inference__08_match_arm_narrowing` · Traits:
`traits__21_trait_method_in_match_destructure` · Infer: `NarrowingInferenceTest`,
`IrMatchTest` · Proofs: `MatchTotalityTest` · Nominal:
`destructure__06_match_struct`, `ClaimRuleTest` · Struct: `StreamQueueTest`
(bare-arm union), `TupleTest`.

**destructuring** — this: `inference__15…` · Refine:
`destructure__12_field_narrow_real` · Generic: `TypeParameterDestructureTest`,
`generics__08_inline_destructure_typevar` · Infer: `inference__15…` · Proofs:
`DestructuringLetTest` (refutable→reject) · Nominal: `destructure__06…` · Struct:
`TupleTest`, `NestedDestructureTest`, `destructure__03_dotbrace_param`.

**assign trait** — this: `AssociatedTypeSelfTypeTest` · Refine/Proofs: `traits__18…`
· Generic: `TypeParameterTraitSortTest` (parametric impl) · Traits:
`TraitAttributeTest` · Infer: `TypeParameterTraitSortTest` · Nominal:
`traits__01…`, `traits__17_multi_satisfier_dispatch` · Struct (field-satisfies-attr,
see N2): `traits__04_attribute_field`.

**assign proof** — Refine/Proofs: `AssignProofTest`, `ProofAuthoringTest`,
`ProofAuthoringAdversarialTest` · Infer: `NarrowingInferenceDispatchTest` · Nominal
(per-region dispatch): `AssignProofTest`.

**function** — this: see N5 (no receiver) · Refine: `dispatch__03…`, `ReturnGateTest`
· Depend (partial, N1): `SpecOnlySynthesisTest` · Generic: `TypeParameterFunctionTest`,
`generics__01_function_id` · Traits: `generics__11_operator_bound_sum`,
`OperatorBoundPropagationTest` · TypeFrag: `TypeAliasIntegrationTest` (alias in sig) ·
Infer: `inference__10_return_narrowing_provable`, `NarrowingInferenceDispatchTest` ·
Synth: `SpecOnlySynthesisTest` · Proofs: `ReturnGateTest`,
`OverloadedFactorialDischargeTest` · Nominal: `dispatch__01_freefn_specificity`,
`StaticDispatchTest` · Struct: `TypeAliasIntegrationTest`.

**method** — this: `methods__04…`, `FieldReceiverMethodTest`,
`AssociatedTypeSelfTypeTest` (`this.type`) · Refine: `methods__09_self_recursive_method`
· Generic: `generics__27_parametric_method` · Traits:
`traits__15_crossmodule_call_method` · TypeFrag: `TypeAliasIntegrationTest` · Infer:
`inference__02_field_method_receiver` · Synth: `StructExtensionTest`
(promotion via method) · Proofs: `ReturnGateTest` · Nominal: `methods__01_recv_method_basic`,
`MethodResolutionTest` · Struct: `destructure__24_crossmodule_nested_method_recv`.

**; (synthesize)** — Refine/Synth/Proofs: `SpecOnlySynthesisTest`, `SpecOnlyLetTest`
· Depend (partial, N1): `SpecOnlySynthesisTest` · Generic: `generics__13…` · TypeFrag
(N3): `ReusableSortTest` · Infer: `SpecOnlyLetTest` (singleton-interval synthesis).

**Stream** — Refine/Nominal/Struct: `StreamQueueTest` (Element/Leaf, structural
recursion, shared Leaf) · Iter: `StreamCombinatorTest` (map/partition/concat/exchange).
Traits: see N6.

**iter** — Iter/Struct/Infer/Refine: `IterationConstructTest` (hand-built
`IrExpr.Iterate`) + `StreamMapTest`/`StreamCombinatorTest` (the live `&s:[…]`
spread-ascription surface that lowers to `Iterate`). See N7.

**Indexed** — none. See N4.

---

## Notes

**N1 — `Depend` is partial, not zero (the dependent-sorts war's starting line).**
A return-position **value-pin may already reference a parameter**: `ackermann(x, y) :
[Int: @ == y_0 + 1]` synthesizes and discharges (`SpecOnlySynthesisTest`; visible in
the receipt-graph report). So `@`/`let`/`function`/`;` get `/`. What is **not** built
(`!!`) and is the war: a refinement referencing a **sibling parameter or another
field's value** (`f(x:Int, i:[Int:@<x])`, `struct Window(n, data:[Indexed:@.count==n])`);
**receiver-relative** bounds (`at(i:[Int:@<this.count])`); **value-indexed struct
sorts** (`OutOfRange(i)`); and **named-parameter method sorts** in contracts
(`[Method(i:Int):…i…]`, currently rejected at `AltParser`). Zero tests for any of
these — confirmed across the suite. See `docs/indexed-streams.md`; the dependent-sorts
war doc is the home for the design.

**N2 — Trait satisfaction is Nominal-only; Structural is unwired (`!`).** A type
satisfies a trait today via explicit `assign trait T:Tr` (registry-backed) — fully
witnessed (`Nominal` = `^^^`). **Structural** satisfaction of an *anonymous* trait
sort (`function f(x:Type{ m:[Method():Int] })`) parses but fails type-check: spike
result `No method 'm' on type '_pending'` — the placeholder name leaks into dispatch.
So `trait`/`assign trait` × `Struct` and × `TypeFrag` are `!`. This is the
**structural-traits** task (separate from the war): resolve methods against the
trait's contract members (named or anonymous) + a call-site structural-satisfaction
check.

**N3 — `TypeFrag` is partial: complete-sort aliases work, fragment naming does not.**
Naming a *complete* sort is built (`let P:Type[Int:@>0]`, `ReusableSortTest`,
`TypeAliasIntegrationTest`, inlined by `AliasResolver`). Naming a **baseless predicate
fragment** (`let gtz:Type = [@>0]`, applied as `[Int:gtz]`) is unbuilt — the
**named-fragments** task (`?`/`/`). The anonymous `Type{…}` sort literal now parses in
any sort position (`AltParserTraitTest.anonymousTypeSort_usableInAnySortPosition_parses`)
but see N2 for its dispatch gap.

**N4 — `Indexed` does not exist (`!` across the row).** No tests, no IR. It's a
proposed sub-trait of `Stream` for random access (`docs/indexed-streams.md`), and it
depends on three other incomplete things: the `Stream` *trait* (N6), sub-traits, and
dependent sorts (N1). Its honest signature (`at(i):[T|OutOfRange(i)]`, `count` a data
attribute) is the first real consumer of the war.

**N5 — `function`/`;` × `this` = `^` (intentional N/A).** A free function has no
receiver; `this`/`this.type` are meaningful only with one (methods, trait contracts).

**N6 — `Stream` × `Traits` = `!`: the `Stream` trait is unbuilt.** `std.stream` is
today a flat module of `Element`/`Leaf` structs + combinator *functions*, not a trait
(witnessed structurally by `StreamQueueTest`/`StreamCombinatorTest`). The trait
abstraction (streams slice 2b, `docs/streams.md`) is the prerequisite for `Indexed`
(N4) and for parametric streams (`Stream × Generic` = `*`, aspirational).

**N7 — `iter` is partial (`/`).** The `Iterate` IR node is tested
(`IterationConstructTest`) and its live surface is the `&s:[…]` spread-ascription
over the Stream trait (`StreamMapTest`/`StreamCombinatorTest`, `docs/streams.md`).
The old `iter(src).{…}` construct (`docs/iteration.md`, SUPERSEDED) has been
retired from the parser. The full foreach/conservation-ledger semantics are not yet
complete. `struct × Iter = ^` (structs are intentionally not iterable — iteration is
the Stream substrate's jurisdiction, `docs/streams.md`).

**N8 — Deliberate absences elsewhere.** `^` cells record *design choices*, not gaps
(Pontif discharges obligations by non-existence). Examples already in force: no
arithmetic on `Char`/`String`/`Bool` (`OperatorCompletenessTest` rejects them); no
value-level positional access on tuples (`p._0` rejected at parse). These belong in
the relevant cells as the corresponding rows/capabilities are filled out.

---

## Maintaining this matrix

- **Adding a capability** = adding a **column**, then filling its cells against every
  construct row — which *is* mapping the feature's blast radius. Empty cells in a new
  column are the work list.
- **Adding a construct** = adding a **row**; each cell asks "does this construct
  compose with that capability, and what proves it?"
- A cell graduates `?` → `!!`/`!` → `WIP` → `/` → `^^`/`^^^` only when a **named
  passing test** backs the final state. No witness, no `^`.
