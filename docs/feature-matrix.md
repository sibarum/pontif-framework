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

**The detector now runs.** `FeatureMatrixWitnessTest` asserts that every citation below
resolves to a test class, a test method, or a probe directory that exists. It was written
after a hand-check found three dead citations, one of which was worse than a rename: the
`emit` row cited `EventEmitCheck` rejecting a provable non-Event — a pass since RETIRED
because the rule was reversed, so the ledger asserted the opposite of the language and
named a witness for it. A detector nothing runs detects nothing.

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
| enum           | ^^   | ^^^    | !!     | ?       | ^^     |          | ^    | ^^    |       |        | ^^^     | ^^^    |
| trait          | ^^^  | ^^^    | !!     | ^^^     | ^^^    | !        |      | ^^    |       | ^^     | ^^^     | !      |
| match          |      | ^^^    |        |         | ^^     |          |      | ^^^   |       | ^^     | ^^^     | ^^^    |
| destructuring  | ^^   | ^^     |        | ^^      |        |          |      | ^^    |       | ^^     | ^^^     | ^^^    |
| assign trait   | ^^   | ^^     | !!     | ^^^     | ^^^    | !        |      | ^^    |       | ^^     | ^^^     | !      |
| assign proof   |      | ^^^    |        |         |        |          |      | ^^    |       | ^^^    | ^^      |        |
| function       | ^    | ^^^    | /      | ^^^     | ^^^    | ^^       |      | ^^^   | ^^^   | ^^^    | ^^^     | ^^     |
| method         | ^^^  | ^^^    | !!     | ^^      | ^^^    | ^^       |      | ^^^   | ^^    | ^^     | ^^^     | ^^     |
| ; (synthesize) | ^    | ^^^    | /      | ^^      |        | /        |      | ^^    | ^^^   | ^^^    |         |        |
| Stream         |      | ^^     |        | /       | ^^     |          | ^^   | /     | ^^    | /      | ^^      | ^^^    |
| Indexed        | !    | !      | !!     | !       | !      |          | !    |       |       |        | !       | !      |
| iter           |      | ^^     |        | *       |        |          | /    | ^^    |       |        |         | ^^     |
| emit           | ^    | /      |        |         | /      |          |      | ^^    |       | /      | ^^      | ^      |
| sort-transform |      | ^^^    |        | /       | ^^^    | /        |      |       | ^^    | ^^     | ^^      | ^^     |
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
`StructExtensionTest` (promotion) · Proofs: `ConstructionGateTest`, `BaseTypeGateTest`
(a field's declared BASE is judged, not only its refinement), `DeclaredSortNameTest`
(a field sort names a type that exists) · Nominal:
`StructExtensionTest`, `ClaimRuleTest` · Struct: `StructExtensionTest`,
`PartialPatternTest`, `AnonymousTupleSortTest` (a written shape is a claim, member-wise).

**enum** — this: `EnumTest.enum_carriesMethods_whichMayNameSiblingCases` · Refine:
`EnumTest.match_refinementArm_coversWhateverTheCoverSays`,
`EnumTest.match_literalRowArmsAlone_areTotal` · Traits (see N14):
`EnumTest.enum_declaresTraitObligations_andItsBlockMethodsSatisfyThem`,
`EnumTest.caseValue_passesWhereTheEnumsTraitIsExpected`,
`StructInheritedTraitImplTest` (the I-group) · Infer:
`EnumTest.caseValue_demotesToTheEnum_keepingItsFields`,
`EnumTest.caseValue_passesWhereTheEnumIsExpected` · Nominal:
`EnumTest.match_isTotalOverTheCasesAlone_withNoDefaultArm`,
`EnumTest.match_missingACase_namesTheCaseNoArmCovers`,
`NominalIsaClaimTest` · Struct:
`EnumTest.caseName_isAValue_andCarriesItsPinnedFields`,
`EnumTest.sealedBase_cannotBeConstructedDirectly`, `EnumTest.ordinal_ordersTheCasesInDeclarationOrder`.

**trait** — this: `AssociatedTypeDeclTest`, `AssociatedTypeSelfTypeTest` · Refine:
`TraitAttributeTest`, `traits__18_producer_violates_refinement_reject` · Generic:
`TypeParameterTraitTest`, `generics__16_parametric_trait_decl` · Traits:
`TraitAttributeTest`, `OperatorTraitContractTest` · Infer: `TypeParameterTraitSortTest`
· Proofs: `traits__18…` · Nominal: `traits__01_decl_assign_basic`,
`RecursiveTraitTest`.

**match** — Refine: `MatchTotalityTest`, `inference__08_match_arm_narrowing` · Traits:
`traits__21_trait_method_in_match_destructure` · Infer: `NarrowingInferenceTest`,
`IrMatchTest` · Proofs: `MatchTotalityTest` · Nominal:
`destructure__06_match_struct`, `ClaimRuleTest` · Struct:
`MatchTotalityTest.unionScrutinee_bareArmPerBranch_isTotalByConstruction`
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
`OverloadedFactorialDischargeTest`, `ReturnBaseGateTest` (the return's declared BASE,
the half the refinement gate never asked) · Nominal: `dispatch__01_freefn_specificity`,
`StaticDispatchTest` · Struct: `TypeAliasIntegrationTest`.

**method** — this: `methods__04…`, `FieldReceiverMethodTest`,
`AssociatedTypeSelfTypeTest` (`this.type`) · Refine: `methods__09_self_recursive_method`
· Generic: `generics__27_parametric_method` · Traits:
`traits__15_crossmodule_call_method` · TypeFrag: `TypeAliasIntegrationTest` · Infer:
`inference__02_field_method_receiver` · Synth: `StructExtensionTest`
(promotion via method) · Proofs: `ReturnGateTest`,
`ReturnBaseGateTest.aMethodReturnIsJudgedToo` · Nominal: `methods__01_recv_method_basic`,
`MethodResolutionTest` · Struct: `destructure__24_crossmodule_nested_method_recv`.

**; (synthesize)** — Refine/Synth/Proofs: `SpecOnlySynthesisTest`, `SpecOnlyLetTest`
· Depend (partial, N1): `SpecOnlySynthesisTest` · Generic: `generics__13…` · TypeFrag
(N3): `ReusableSortTest` · Infer: `SpecOnlyLetTest` (singleton-interval synthesis).

**Stream** — Refine/Nominal/Struct: `StreamElementCheckTest` (element conformance at the
autobox), `StreamConcatTypedTest` (structural append on Stream-TYPED operands, not only
tuple literals) · Iter: `StreamMapTest`, `StreamConcatTest`, `StreamGuardFilterTest`,
`StreamBreakTest`, plus generator/unfold/takeWhile/concat-`+` (stream war 2e–2f, see
`docs/stream-war.md`)
· Generic (partial, N6): `CallDispatchTest` (tuple arg → `Stream[T]` param, element-checked),
`GenericInstantiationTest` (turbofish `map[Int,String]` + bare-call inference) — the §8.6
parametric-trait carrier (`Stream[T]` element check) · Infer (partial, N12):
`GenericInstantiationTest` (bare-call element inference) — combinator **element-refinement**
flow is OPEN · Synth: `StreamRangeSynthesisTest` (finite range `Stream[Int:0<=@<10]` →
`{0..9}`, direction/bounds/edges/filters) · Proofs (partial, N11): `StreamRangeSynthesisTest`
(range membership discharged via `SynthesisBridge`→`Refinements`, same gate as param guards;
`unbounded_isHonestlyRejected`) · Traits: `StreamTraitTest` (`trait Stream[type E]` in
`pontif.core`, importable, tuple autoboxes with element check). See N6.

**iter** — Iter/Struct/Refine: `IterationConstructTest`, `IterationParseTest`
(hand-built `IrExpr.Iterate` + Pontif-syntax `iter(src).{…}` parse) · Infer:
`NarrowingInferenceTest.iterate_map_narrowsToStreamOfTransformedElement`,
`iterate_filter_narrowsToRecordOfRefinedStreams`, `iterate_unknownElement_narrowsToBareStream`.
See N7.

**emit** — this: `^` (statement, no receiver, see N9) · Refine (partial, N9):
`EventEmitTest.emit_ofANonEvent_isANoOp` (emit accepts ANY value — there is deliberately no
Event guard) — payload two-way sort selection is deferred · Traits (partial, N9): the builtin
conduit is not hijackable by name
(`EventEmitTest.userStructNamedStdOut_doesNotHijackTheBuiltinConduit`) — conduit-fold trait
deferred · Infer: `EventEmitTest.emit_isWriteOnly_mainValueIsTheTrailingExpr` (narrowing =
body's; `NarrowingInference:109`) · Proofs (partial, N9): routing by event type
(`EventEmitTest.emit_routesByEventType_toStderr`) — `!!`-hazard/deterministic-index discharge
deferred · Nominal: `EventEmitTest.emit_routesByEventType_toStderr` (routes by event type) ·
Struct: `^` (events are nominal constructions, N9).

**sort-transform** — Refine: `TraitReturnShellTest.shellChangesReturnType`,
`TraitArgShellTest.shellChangesArgumentType` (the shell clause is checked as an ordinary
sort) · Generic (partial, N10): shells compose with trait `[type T]` (no dedicated witness) ·
Traits: `TraitReturnShellTest.shellThroughTraitTypedParam`,
`TraitArgShellTest.shellThroughTraitTypedParam` (trait-owned, composes with default bodies) ·
TypeFrag (partial, N10): the clause-chain `[A->…->B]` fragment · Synth:
`TraitReturnShellTest` (return shell wraps the kernel), `TraitArgShellTest` (arg shell wraps
the param) · Proofs: `TraitReturnShellTest.kernelMustReturnShellDomain`,
`TraitArgShellTest.kernelMustDeclareShellCodomain` (kernel-returns-C / kernel-takes-B
obligations) · Nominal/Struct: `shellChangesReturnType` (`Int → String`). See N10.

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
(`[Method(i:Int):…i…]`, currently rejected at `PontifParser`). **Update (2026-07-21):** the
**sibling-parameter** case at a *call* site is now landed and tested — `StaticDispatch.substituteSiblings`
+ `StaticDispatchTest`/`CallGateTest` (e.g. `g(5,7)` provable-fail), and the dependent-`let` claim via
`ConstructionGate.gateClaim`/`dischargesUnderScope` + `DependentLetClaimTest`. The "zero tests"
statement remains true only for the **struct-field**, **receiver-relative**, **value-indexed**, and
**named-parameter-method-contract** cases. See `docs/indexed-streams.md`; the dependent-sorts war doc
is the home for the design.

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
any sort position (`PontifParserTraitTest.anonymousTypeSort_usableInAnySortPosition_parses`)
but see N2 for its dispatch gap.

**N4 — `Indexed` does not exist (`!` across the row).** No tests, no IR. It's a
proposed sub-trait of `Stream` for random access (`docs/indexed-streams.md`), and it
depends on three other incomplete things: the `Stream` *trait* (N6), sub-traits, and
dependent sorts (N1). Its honest signature (`at(i):[T|OutOfRange(i)]`, `count` a data
attribute) is the first real consumer of the war.

**N5 — `function`/`;` × `this` = `^` (intentional N/A).** A free function has no
receiver; `this`/`this.type` are meaningful only with one (methods, trait contracts).

**N6 — `Stream` × `Traits` = `^^`: the `Stream` trait is now the sole abstraction.**
`trait Stream[type E]{}` lives in `pontif.core` (importable, tuple-literal autobox with
element check, `StreamTraitTest`); trait-extends machinery landed (`TraitExtendsTest`).
The **contract is deliberately empty** — James ruled (i) *internal iteration, no external
`next()`* (`docs/stream-war.md` §7): the `&s:[…]` synthesis-fragment primitive drives a
source, so there is no eliminator method to expose. The old flat `std.stream` module of
`Element`/`Leaf` structs + cons-cell combinators was **retired** in the stream-trait war
(§7 step 5): the fragment primitive subsumes the whole basis (map/filter/fold/scan/fork/
zip/concat — `StreamFragmentTest`, `StreamMapTest`, `StreamConcatTest`), so the cons-cell
carried no remaining weight (`Element` deleted, `Leaf` stays in `std.common` for proofs).
**Deferred (named, not built):** `validateSortNames` is not yet trait-aware — a
hardcoded `BUILTIN_PARAMETRIC_TYPES = {"Stream"}` still admits `Stream[T]` as a sort name
(a user's own parametric trait in a let/param sort position would not yet validate); an
*incompleteness*, not a lie. Still upstream of `Indexed` (N4) and parametric streams
(`Stream × Generic` = `/`).

**N7 — `iter` is partial (`/`).** The `Iterate` IR node and Pontif-syntax parse are
tested (`IterationConstructTest`, `IterationParseTest`), but the surface design is a
DRAFT (`docs/iteration.md`) and the full foreach/conservation-ledger semantics are not
complete. `struct × Iter = ^` (structs are intentionally not iterable — iteration is
the Stream substrate's jurisdiction, `docs/streams.md`).

**N8 — Deliberate absences elsewhere.** `^` cells record *design choices*, not gaps
(Pontif discharges obligations by non-existence). Example in force: no arithmetic on
`Char`/`String`/`Bool` (`OperatorCompletenessTest` rejects them). (Previously this note
also claimed positional tuple access `p._0` is "rejected at parse" — that is now stale:
after the brace-aggregates war, `{10,20,30}._0` projects, witnessed by
`BraceAggregateTest.bracePositionalTuple_projects`. The retired form is the *paren*-tuple
`(10,20)`, now a parse error — `BraceAggregateTest.parenTuple_isRetired`,
`parenSort_isRetired`.)

**N9 — `emit` (event substrate slice 1b) is landed but partial.** `emit EVENT BODY` is a
write-only statement (`docs/events.md`): its narrowing/value is the body's
(`NarrowingInference:109`), so the enclosing function's return gate still sees the body —
no escape. There is **no compile-time obligation at all**, deliberately: `emit` accepts any
value and an event with no consumer is a silent no-op by design (`docs/reactive-gui.md`),
because isolation comes from using a distinct type hierarchy, not from an `Event` marker.
*(This note previously described an "honesty guard" — `EventEmitCheck`, rejecting a provable
non-Event, fail-closed at runtime on "no conduit". Both halves were reversed when that pass
was retired, and the citation outliving the rule is exactly what the staleness discipline
below is for: the cell was witnessed by a test whose successor asserts the opposite,
`emit_ofANonEvent_isANoOp`.)* The `Refine`/`Traits`/`Proofs` cells are `/` because the rest of the design is
**deferred to later event slices**: the receiver/conduit machinery (`EventConduit[E,S,R]`,
`EventStream[R]`), the **two-way payload-sort selection** (receiver payload-sort AND
conduit receiver-metadata-sort via `Refinements.satisfies`), and the `!!`-hazard failure
model + per-conduit monotonic emission index. Slice 1b is `emit → stdout/stderr` only.
`this`/`Struct` are `^` (a statement has no receiver; events are nominal constructions).

**N10 — `sort-transform` shells (sort-transforms.md, slices 1+2) are landed.** A trait
method may own a **return** clause-chain shell `[C -> … -> D]` (callers see `D`, the kernel
returns `C`) and/or **argument** shells `[A -> … -> B]` (caller passes `A` — what dispatch
keys on — kernel sees `B`). `TraitDefaultExpansion` wraps every impl/default kernel and
checks the kernel-returns-`C` / kernel-takes-`B` obligations; the two compose (args inner,
return outer). `Generic`/`TypeFrag` are `/`: the shell clause can reference the trait's
`[type T]` and the `[A->…->B]` form is the conversion-sequence fragment (see
[[principle_destructure_conversion_duality]]), but neither has a *dedicated* witness yet.

**N11 — Infinite streams / productivity is the open critical gap (`Stream × Proofs = /`).**
Finite range synthesis and bounded folds discharge soundly (`StreamRangeSynthesisTest`,
`SynthesisBridge`). But infinite/lazy streams — RULED **essential** (the event system /
concurrency model, `docs/infinite-streams`, built via guarded infinite recursion) — have
**no productivity gate**: the coinductive dual of termination ("does it keep emitting?"
vs "does it stop?"). There is no compile-time check that a generator emits in finite time
between pulls, no IR for guarded corecursion, and the discharge kernel assumes finite
descent. This is *not currently a lie* (infinite generators aren't expressible yet — finite
generators carry a base case via domain refinement, stream war 2f), so it is an honest
**unbuilt** gap, not a false claim. It is its own future war (`docs/stream-war.md` §8c).

**N12 — Stream combinator element-refinement flow is OPEN (`Stream × Infer = /`).** Bare-call
and turbofish generic combinators infer/thread the element *base* type (`GenericInstantiationTest`),
and `Iterate`-construct narrowing flows element sorts (the `iter` row). What is **not** wired:
propagating an element **refinement** (`Stream[Int:@>0]`) through a combinator's dispatch as a
key-sort coherence check — `map($f[Int], s:Stream[Int:@>0])` loses `@>0` at the call boundary.
`docs/streams.md` (§"element-sort flow") marks this OPEN; zero tests witness refinement
preservation across combinators.

**N14 — `enum × Traits`: fixed 2026-08-24, was `/`.** An enum takes trait obligations
(`enum Tier:[Budgeted](…)`), its block methods satisfy them, and a CASE now routes to a
trait parameter (`spend(Tier.Costly)` for `function spend(b:Budgeted)`). The gap was
never enum-specific: `StaticDispatch` held both halves of the answer — the
`traitImpls` view ("does THIS type implement it", walking trait-extends) and the
`structAncestors` view ("what does it inherit from") — and never composed them, so a
sub-struct argument read as *provably disjoint* from its base's trait. That is a false
disjointness claim; the gate's FAILED verdict means provably-misroutes.
`Assignability.isA` had it right all along by recursing on the nominal base, which is
why static assignment worked while the call gate refused the same widen — one more
symptom of the is-a/base-chain fork. Both legs now go through one
`StaticDispatch.satisfiesTrait`, and widening to an unrefined trait an ancestor
implements is a *proved* match, not merely not-disjoint. Negatives still hold (a struct
implementing nothing, and an ancestor implementing a different trait, are both
rejected). `Generic` stays `?` (no `[type T]` slot on the declaration, not scoped);
`Synth`/`Proofs`/`TypeFrag` have no enum-specific requirement identified yet. See
docs/enums.md §6.

**N13 — Conservation has no column (deliberate, for now).** The conservation ledger
(`pontif-conservation`) underpins `Proofs`/`Synth`/`destructuring` rather than standing as a
separate capability, and brace-aggregate construction/destructuring is traced correctly
(`ConservationReportTest`, `ConservationGateTest` — all on `{…}`). One real gap was found:
`SortChecker.checkIterationConservation` (`SortChecker.java:1408+`) enforces the no-bare-drop /
one-placement laws **procedurally** rather than by querying the ledger that
`ConservationDrafter.draftIterate` builds — a divergence risk (`docs/iteration.md` §10 marks
SortChecker [REVISIT]). Adding a Conservation column is a candidate but not taken unilaterally
(it would require a cell on every row); flagged for James.

---

## Maintaining this matrix

- **Adding a capability** = adding a **column**, then filling its cells against every
  construct row — which *is* mapping the feature's blast radius. Empty cells in a new
  column are the work list.
- **Adding a construct** = adding a **row**; each cell asks "does this construct
  compose with that capability, and what proves it?"
- A cell graduates `?` → `!!`/`!` → `WIP` → `/` → `^^`/`^^^` only when a **named
  passing test** backs the final state. No witness, no `^`.
