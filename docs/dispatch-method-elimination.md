# Dispatch/Method elimination — capability-driven call signatures

*Plan of record for the refactor that removes the hardcoded `Method`/`Dispatch` special-casing
from the type system, and delivers `$f[Decimal].ast` (AlgebraicDispatch) as its first customer.
Ratified with James across the 2026-07-19 design session. This supersedes roadmap §5's earlier
"`AlgebraicDispatch <: Dispatch` intersection view" framing (that was a stepping stone).*

Status: **E1 LANDED (behavior-preserving); E2 not yet started.** Baseline for E1 work: `1890fda`
(Slices A + B on master). Full `mvn test` green after E1 including the extensibility acid test.

### E1 implementation notes (sub-decisions taken)

- **One node.** `IrSort.CallSig(String typeName, List<IrSort> paramSorts, List<String> paramNames,
  IrSort returnSort, Origin)` replaced `IrSort.Method` + `IrSort.Dispatch`. Factories
  `IrSort.method(...)` / `IrSort.dispatch(...)` and constants `CallSig.METHOD` / `CallSig.DISPATCH`
  preserve call sites. `Trait.methods` and `Trait.operators` retyped to `CallSig`.
- **Capability registry.** New `pontif-ir/.../ir/CallKinds` holds the builtin seed
  (`function-style = {Method}`, `dispatch-style = {Dispatch, DispatchBase, AlgebraicDispatch}`) and
  the shared trait-name constants `FUNCTION_STYLE` / `DISPATCH_STYLE`. The ctx-aware lookup
  (`Assignability.callKind`) is `builtin(typeName)` else `ctx.satisfies(typeName, …-style)`.
- **Parser.** Discrimination is purely syntactic — `PontifParser.callSigColonFollows()` (a `:` after the
  matching `)` at depth 0) → `parseCallSigBody(headTok)` with the head name as data; the
  `equals("Method")`/`equals("Dispatch")` branches are gone. Both the Pontif parser AND the live
  S-expression reference parser (`SexprSexprParser.java`, used by the unit suite) were updated.
- **Core `Sort` (deliberate E1 deferral).** §2 calls for a `callSigTypeName` discriminator on the
  core `Sort`; for E1 I KEPT the existing `method`/`dispatch` field-pairs instead (so `Refinements`
  is untouched — lowest-risk behavior-preservation) and made `IrCompiler.compileSort(CallSig)` pick
  the pair by capability: a function-style head → `Sort.method`, every other callable head →
  `Sort.dispatch` (Method is the sole function-style head, §2). Adding `callSigTypeName` to the core
  `Sort` is folded into **E2** if `.ast` resolution needs the concrete nominal name at the core layer.
- **`Refinements` (core) is unchanged.** Its `satisfies`/`imply` still key on `Sort.isMethod()` /
  `isDispatch()`, but those field-pairs are now SET by capability at compile — so behavior is
  capability-driven one layer up, and a new callable type needs no core edit. Reworking `Refinements`
  to read a `callSigTypeName` directly is deferred with the core-`Sort` decision above.
- **Acid test (green).** `PontifParserSortTest.callSignature_arbitraryHeadName_parsesPurelySyntactically`
  (parse) + `AssignabilityTest.newCallableType_parsesSubtypesSatisfies_viaCapabilityDataOnly`
  (subtype via `ctx.satisfies` capability data; satisfy via the dispatch-shaped compiled sort). A new
  callable type is recognized entirely through capability DATA — no edit to Assignability,
  Refinements, or the parser.

---

---

## 1. Why (the problem)

`Method` and `Dispatch` are hardcoded three ways: **(a)** parser keywords
(`PontifParser.parseBracketBranch` matches `equals("Method")`/`equals("Dispatch")`), **(b)** two
bespoke `IrSort` kinds (`IrSort.Method`, `IrSort.Dispatch`), and **(c)** name/`instanceof`-based
special logic in subtyping, satisfaction, printing, resolution — ~40 sites. This makes the type
system **genuinely hard to extend**: the `.ast` feature (a `Dispatch` that statically carries
"algebraic") had nowhere general to live, which is what surfaced the problem.

**The goal is not `.ast`.** It is to remove the hardcoding so future callable / functional-
metaprogramming types need **zero type-system changes**.

**Acid test (James):** *adding a new callable type must touch no type-system code* — you only
declare a type carrying a capability. Any step that merely *relocates* hardcoding (e.g. a parser
name-branch → a name→node-kind map) is the anti-pattern and is rejected.

## 2. The design (ratified decisions)

- **One generic sort node.** `IrSort.CallSig(String typeName, List<IrSort> paramSorts,
  List<String> paramNames, IrSort returnSort)` replaces `IrSort.Method` + `IrSort.Dispatch`.
  `typeName` is data (`"Dispatch"`, `"Method"`, or any future type). `paramNames` (dependent-sort
  binders, currently only on `Method`) is preserved. The core `Sort` (pontif-core) gains a
  `callSigTypeName` discriminator carrying the same data through IR→core compilation.
- **Two call-kind capabilities** (builtin traits) that **drive** behavior, selected by which the
  head type is-a — never by name or `instanceof`:
  - **function-style** — subtyping = full function subtyping (contravariant params, covariant
    return); value-satisfaction = a lambda. (`Method` is-a function-style.)
  - **dispatch-style** — subtyping = *exact* key-sort match + covariant return (a metareference
    re-runs dispatch at its exact keys); value-satisfaction = a metareference. (`Dispatch` is-a
    dispatch-style.)
- **`Type(Args):Return` is a capability, not a keyword.** Any type carrying a call-signature
  capability gets the syntax. Parser discrimination is **purely syntactic**: `Name(…):Return`
  (a `:` after the matching `)` at paren-depth 0) → call signature; `Name(…)` → struct.
- **The variadic parameter list is the one sanctioned special thing** — no user type takes
  variable *type-arguments* (established: type params are a fixed declared list, struct fields are
  fixed arity; only `Method`/`Dispatch` are variadic over parameter sorts). This variadicity is a
  justified builtin, in the same category as the `Int→Decimal` coercion exception. It is carried
  structurally by `CallSig.paramSorts`.
- **Capability = a builtin trait the head type is-a**, decided by the existing `TraitRegistry` /
  `AssignabilityContext.satisfies` (`Assignability.java` nominal-trait arm). There is **no**
  `TypeInfo` capability-flag concept and we are **not** adding one; the `Algebraic`-intersection
  precedent in `NarrowingInference` is the working model.
- **Behavior is looked up by capability, as data.** `baseName(CallSig)` returns `typeName` (today
  it returns `null` for Method/Dispatch), so `Assignability`/`Refinements` dispatch on "which
  call-kind capability does `typeName` have," not on `instanceof`.
- **Always-available without special logic.** Builtins (`Dispatch`, `Method`, `Algebraic`,
  `DispatchBase`, `AlgebraicDispatch`) are recognized as *known names* the way primitives are
  (`SortChecker.PRIMITIVE_SORT_NAMES`, `TypeCatalog.PRIMITIVES`) — name *recognition* (like
  `Int`) is acceptable; only name-based *logic* is the hardcoding to remove. Their capability
  associations are registry **data**, seeded once; a user type gets the same capability by
  declaring it, the same way, with no type-system code change. **No prelude/linking change** —
  there is no always-on prelude and adding one is out of scope (see §6).

## 3. Stages

### Stage E1 — the elimination (behavior-preserving)

One coherent change; whole suite behaves identically afterward, but the hardcoding is gone.

1. `IrSort.CallSig` replaces `IrSort.Method`/`IrSort.Dispatch` (sealed `permits`, factories,
   `paramNames`). Core `Sort` gains `callSigTypeName`.
2. Parser: syntactic call-sig-vs-struct discrimination; `CallSig(headName, …)` with the head name
   as data — delete `equals("Method")`/`equals("Dispatch")`.
3. Two capability traits (function-style, dispatch-style) seeded as builtin data; `Dispatch` is-a
   dispatch-style, `Method` is-a function-style. `baseName(CallSig) = typeName`.
4. `Assignability` (the Method/Dispatch arms + `dispatchSubsumes`) and `Refinements` (the
   `satisfies` function/dispatch arms) look up the head type's call-kind capability and apply that
   rule — no `instanceof` on the two old kinds.
5. Fold the ~30 mechanical switch/printer/resolver sites onto `CallSig`; retype `Trait.operators`
   (`Map<String,IrSort.Dispatch>` → `CallSig`) and its parser + `assign trait` satisfaction check;
   route `IrCompiler.compileSort`, `NarrowingInference` (lambda inference + `dispatchRefSort`)
   through the generic node.

**Acid-test in E1:** a throwaway test type declared with the dispatch-style capability must parse
`Foo(Int):Int`, subtype, and satisfy — with **no** type-system edit. Full `mvn test` green
(behavior-preserving for `Method`/`Dispatch`).

### Stage E2 — `.ast` end-to-end (purely additive; proves the acid test)

Using only E1's general machinery — **no** further type-system change (that's the proof):
- Declare `Algebraic` (trait with member `ast:AlgExpr`), `DispatchBase` (dispatch-style),
  `AlgebraicDispatch` (dispatch-style **&** `Algebraic`).
- `NarrowingInference.dispatchRefSort` stamps `$f[…]` as the concrete type
  (`DispatchBase`/`AlgebraicDispatch`) from the root `assign proof f:Algebraic` claim —
  **reworking Slice B's `Algebraic` marker + `[Dispatch & Algebraic]` intersection onto the real
  traits** (retire the `MARKER_SORT_NAMES` shim).
- `.ast` resolves as the `Algebraic.ast` member via the Slice-A intersection member resolution.
- `astOf`'s parameter becomes `Algebraic` (so `astOf(this)` type-checks and a non-algebraic ref
  is rejected); `astOf` is made **non-exported** — `$f[Decimal].ast` is the only surface.

Tests: `eval($poly[Decimal].ast, x) == poly(x)`; `$poly[Decimal].ast` matches `[Add(_,_)]`;
`$inc[Decimal].ast` is a **compile error**; existing `astOf` reflection still green.

#### E2 finalized design (ratified with James 2026-07-19 — supersedes the §5 "value carries its type" sketch)

**Governing rule (James):** *"Anything that looks like an object should actually be one."* Any value
bindable to a variable is a `RecordValue`; special behaviors (invoking/dispatch) are added on top,
read from the value's `typeName`, never a bespoke Java value class. (Memory: `values-are-recordvalues`.)

- **Runtime value.** The metareference `$f[…]` evaluates to a `RecordValue(typeName =
  `AlgebraicDispatch`/`DispatchBase`, members = the dispatch payload {functionName, keySorts})` instead
  of `DispatchValue`. `.ast` then flows through the stock `RecordValue` attribute-producer path
  (`tryAttributeProducer` → `AlgebraicDispatch.ast(this) -> astOf(this)`), no interpreter special-case.
  **Scope decision (James): the ir/runtime layer migrates now; relocating `RecordValue` down to
  `pontif-core` and fully retiring `DispatchValue` (incl. the symbolic `Force` path, which is
  primitive-oriented and can't see `RecordValue` from core) is the IMMEDIATELY-FOLLOWING commit, not
  bundled into E2.** During E2 the ir consumers recognize both the new `RecordValue` metaref and a
  legacy `DispatchValue` (transitional).
- **Sort stamp = the concrete nominal (no intersection).** `dispatchRefSort` stamps
  `CallSig("AlgebraicDispatch", keys, ret)` (algebraic) / `CallSig("DispatchBase", keys, ret)` (plain).
  Both are dispatch-style (already seeded in `CallKinds`), so either still fits a `[Dispatch(…)]`
  param via `dispatchSubsumes`. `inferFloor(DispatchRef)` must return this stamp (today it returns
  null), and `SortChecker.floorContext` must carry the real `algebraicFunctions` (today hardcoded
  empty) so the gate can tell the two apart.
- **`.ast` compile gate.** `matchBaseName(CallSig) = typeName`; the FieldAccess member gate, on a
  dispatch-`CallSig` base, requires a registered producer `typeName + ".ast"` (a closed member set) —
  so `$poly[Decimal].ast` (AlgebraicDispatch, producer present) passes and `$inc[Decimal].ast`
  (DispatchBase, no producer) is a **compile error**. This is a general soundness fix (dispatch bases
  were unsoundly blind), extensibility-preserving — a new dispatch type with attributes works with no
  further gate change.
- **Declarations (in `AlgebraExtension.SOURCE`, the required `pontif.algebra` module — not a prelude):**
  `trait Algebraic{ ast:AlgExpr }`; `assign trait AlgebraicDispatch : Algebraic { ast:AlgExpr -> astOf(this) }`
  (a non-struct impl — `satisfier==null` is tolerated, the producer satisfies `ast`). `astOf`'s param
  becomes `Algebraic` (so `astOf(this)` type-checks; a non-algebraic ref is rejected) and `astOf` is
  dropped from `exports` — `$f[Decimal].ast` is the only surface.

#### As-built deviations from the sketch above (E2 landed)

- **Plain nominal is `Dispatch`, not `DispatchBase`.** A distinct `DispatchBase` broke pervasive
  exact-name checks (`let x:[Dispatch(Int):Int] = $inc[Int]`, Truffle agreement) for no benefit — the
  only distinction E2 needs is algebraic vs not. Plain metarefs keep the `Dispatch` nominal (unchanged
  from E1); only algebraic ones get `AlgebraicDispatch`. `DispatchBase` is unused.
- **`MARKER_SORT_NAMES` kept, not retired — expanded.** With the concrete-nominal stamp there is no
  `Named("Algebraic")` intersection branch to validate, but `Algebraic`/`AlgebraicDispatch` must stay
  BARE builtin names (never module-qualified) so the metaref stamp, runtime `Metaref` value, and the
  trait declaration/impl all agree on one spelling. So the marker set + `NameResolver` mirror now
  recognize `{Algebraic, DispatchBase, AlgebraicDispatch}` as builtin names (like `Int`, §2).
- **The `.ast` gate is restricted to dispatch nominals.** The FieldAccess member gate rejects an
  unknown member only on a `CallSig` whose head is-a `dispatch-style` (via `CallKinds.builtin`) — a
  function-style `Method` call sig (a lambda) stays lenient, as before (streams do `frag._0`).
- **Runtime value carries the nominal via `CompiledModule.algebraicFunctions`.** `IrCompiler` collects
  the `assign proof f:Algebraic` names onto `CompiledModule`; `IrInterpreter` tags `$f[…]` as
  `AlgebraicDispatch`/`Dispatch` from it. `SortChecker.floorContext` was threaded the same set so the
  gate's floor sort distinguishes algebraic from not.
- **`SymExpr.DispatchRef` gained a nullable `typeName`** so a metaref satisfies BOTH a `[Dispatch(…)]`
  sort (by keys) AND a trait param (by nominal — `DispatchTable` checks `AlgebraicDispatch is-a
  Algebraic` for `astOf(f:Algebraic)`). This is a small core touch ahead of the substrate move.
- **`tryAttributeProducer` + `validateTraitImpl` made qualification-tolerant** (suffix match) so the
  required-module producer `pontif.algebra/AlgebraicDispatch.ast` resolves against the bare nominal.

*(Retiring `MARKER_SORT_NAMES` entirely, and the `DispatchValue`→`RecordValue` substrate move — incl.
the symbolic `Force`/Truffle paths still on `DispatchValue` — remain the next commit, per the ratified
scope.)*

## 4. The "what and where" — site checklist (as of `1890fda`; verify line numbers)

Sealed `IrSort` means every exhaustive `switch` is compiler-enforced — removing `Method`/
`Dispatch` and adding `CallSig` makes the compiler point at each. Most are mechanical.

**Definitions / factories**
- `pontif-ir/.../ir/IrSort.java` — sealed `permits … Method, Dispatch …` (:8); `record Method`
  (:146, has `paramNames` + back-compat ctor — **preserve**); `record Dispatch` (:178, no
  paramNames); `method(...)` factory (:35); `Trait.operators : Map<String,IrSort.Dispatch>`
  (:205-207 — **retype to CallSig**).
- `pontif-core/.../types/Sort.java` — flat record; `method`/`dispatch` factories (:68,:83),
  `isMethod`/`isDispatch` (:137,:145), `dispatchKeySorts`/`dispatchReturnSort`/`methodParams`/
  `methodReturnSort`, `toString` (:155). **Decide: keep both field-pairs or add `callSigTypeName`.**

**Subtyping / satisfaction (the semantic core — needs care)**
- `pontif-ir/.../types/Assignability.java` — Method arm `kernelImplies` (:64), Dispatch arm
  `dispatchSubsumes` (:67,:273-280), `baseName` returns null for both (:311-320 — **make it
  return `typeName`**).
- `pontif-core/.../symbolic/Refinements.java` — `satisfies` function/dispatch arms (:127-148:
  Method→`satisfiesFunction`/Lam value, Dispatch→`SymExpr.DispatchRef` + exact keys),
  `satisfiesFunction` (:394-420), `implyFunction` + kind guards (:77-78,:434-435,:484-485).

**Parser**
- `pontif-parser/.../PontifSexprParser.java` — the Method/Dispatch keyword branches (:3107-3137),
  `parseFunctionSortBody` (shared grammar, :3433-3462), operator-contract parsing that requires an
  `IrSort.Dispatch` (:2790-2807, `requireHomogeneousSelfOperatorContract` :2992), Method
  construction sites (:205,:1765,:2946,:4537), `describeSort`/`baseSortName` (:5612-5613,
  :5639-5640). *(`SexprSexprParser.java` is a legacy parser — confirm it's not live before editing.)*

**IR→core + resolution + validation (mostly mechanical)**
- `IrCompiler.java` — `compileSort` Dispatch/Method arms (:364-382), `registerSort` (:254-261).
- `NarrowingInference.java` — `dispatchRefSort` (:134, builds the metareference sort + the
  Algebraic intersection — **the E2 rework point**), lambda sort (:286), `substituteTypeArgs`
  (:453-472).
- `SortChecker.java` — `validateSortNames` (:880-887), `sortsExactlyEqual` (:1072-1077),
  `mentionsAny` (:1988-1994), `substituteTypeVars` (:2009-2030), the `assign trait` satisfaction
  check over `contract.methods()` + `contract.operators()` (:390-441,:469-485 — **needs care**).
- `InferenceContext.java` — `mentionsAssociatedType`/`existentialize` (:175-224), existential
  boundary (:150-155).
- `NameResolver.java` (:193-206), `AliasResolver.java` (:254-275,:455-470).

**Printers / diagnostics / misc (mechanical)**
- `IrPrinter.java` (:178-179), `IrSourcePrinter.java` (:151-153), `IrInterpreter.java` base-name
  (:719-720), `CoercionResolver.java` (:104-105,:137-138),
  `ConservationDrafter.java` (:895-896), `ReceiptGraphPrinter.java` (:84-91).

**Overload / lowering / monomorphization (needs care)**
- `OverloadOverlap.java` `checkSorts` (:181-185 — Method listed, Dispatch not; decide CallSig
  handling), `PontifParser.unifyTypeParam`/`needsMono` Dispatch arms (:490-492,:539-544),
  `pontif-supirvast/SortLowering.java` both reject (:39-40), `DispatchTable.java` guard (:186).

**Trait type-params / nominal subtyping (for E2's `AlgebraicDispatch is-a Dispatch`)**
- `IrSort.Trait` separates `typeParams` (decl slots) / `associatedTypes` / `typeArgs` / `baseTrait`
  (:205-211). `AliasResolver.applyTypeArgs` (:507-531) applies args. `AssignabilityContext.fromModule`
  builds `typeName → Set<traitName>` walking the `baseTrait` chain (:43-66) — **arg-blind**
  (`traitTypeArgs` is dropped; `Assignability` is-a ignores `typeArgs` except the `Refinements`
  `Stream` special-case). Implication: `AlgebraicDispatch is-a Dispatch` fits the existing
  `baseTrait`/`TraitRegistry` machinery **as long as the signature args are checked structurally
  by the `CallSig` node, not via trait type-args** (which is exactly the design — the signature is
  `CallSig.paramSorts`, not `Dispatch[Args]` type-args).

## 5. Runtime value (from the earlier investigation, for E2)

A metareference is currently the bespoke `sibarum.pontif.core.types.DispatchValue(functionName,
keySorts)` with **no `typeName()`**, special-cased in `IrInterpreter` (`evalApply` ~:908,
`reflectFunction` ~:879, `eval` producer ~:172, `toSymExpr` ~:1605), `Force.java` (~:23), and the
Truffle mirror (`CallNode.java`, `TruffleLowering.java`). Application only needs the function
*name* (it reuses `module.dispatch().resolve`); `astOf` reflects via
`NativeCalls.Context.reflectFunction`. `toSymExpr` is the single funnel to `SymExpr.DispatchRef`,
so `Refinements` stays untouched if the value keeps mapping there. For E2 the value should carry
its concrete nominal type (`DispatchBase`/`AlgebraicDispatch`) so `.ast` resolves through the
general member machinery. (An earlier partial attempt, "C1a," bolted a `typeName` onto
`DispatchValue`; it was reverted — the E1 nominal model supersedes it. Fully retiring
`DispatchValue` into a `RecordValue` is optional follow-on, not required for E1/E2.)

## 6. Non-goals / explicitly deferred

- **§1d propagation gate.** A non-algebraic ref into a `[… & Algebraic]` / `AlgebraicDispatch`
  *parameter* is currently accepted (call arg-fit is permissive runtime dispatch, not the static
  stamp). The **direct** `$f[Decimal].ast` guarantee is enforced by member resolution and is
  sound; the *parameter-propagation* guarantee needs a static intersection-assignment gate at the
  call site — its own slice, overlapping the C3 call-gate migration.
- **Always-on prelude.** Not built; builtins stay known-names + registry-data (§2).
- **Full `DispatchValue` → `RecordValue` retirement.** ~~Optional cleanup after E1/E2.~~ **DONE (the
  substrate move):** `RecordValue` relocated `pontif-ast` → `pontif-core` (`sibarum.pontif.core.types`)
  as the universal value type; `Metaref` moved with it; `DispatchValue` deleted. A metareference is
  now a `RecordValue` across ALL layers — the tree-walking interpreter, the symbolic `Force`, and the
  Truffle mirror (`CallNode`/`TruffleLowering`/`DispatchRefLiteral`). Realizes the ratified rule
  "anything that looks like an object should actually be one" (memory `values-are-recordvalues`).
  Full `mvn test` green.
- **Retire `MARKER_SORT_NAMES`.** **DONE:** the bespoke marker set is deleted; `validateSortNames`
  now recognizes a builtin call-kind head (`Method`/`Dispatch`/`AlgebraicDispatch`, e.g. an
  `AlgebraicDispatch` impl's `this` self-sort) via the capability registry `CallKinds.builtin`, and
  `Algebraic` validates as the real trait it is. (The `NameResolver` bare-name exemption stays — it is
  the module-qualification exemption, a separate concern.)
- **§1d parameter-propagation gate.** **DONE (targeted):** `SortChecker.checkMetareferencePropagation`
  rejects a direct `$f[…]` argument to a single-overload callee's TRAIT parameter when the reference's
  concrete nominal is-not-a that trait (`Assignability.isA`) — so `g($inc[Decimal])` into
  `g(f:Algebraic)` is now a compile error, not a runtime one. Deliberately narrow (sound, not complete):
  a metareference reached through a let/var, a multi-overload callee, or a non-trait `[Dispatch(…)]`
  parameter still defers to runtime dispatch — the general static call-arg gate remains the C3 slice.

#### The ultimate test: `eval` as a METHOD on the metareference (`$f[Decimal].eval(x)`)

Adding a *method* (not just an attribute) to a callable metaprogramming type. The feature itself is
pure declaration — a trait method + impl in `AlgebraExtension.SOURCE`:
`trait Algebraic{ast:AlgExpr, eval(x:Decimal):Decimal}` + `assign trait AlgebraicDispatch:Algebraic { …
eval(x:Decimal):Decimal -> eval(this.ast, x) }`, used as `$poly[Decimal].eval(3.0) == poly(3.0)`.
**Green.** But it was NOT zero-type-system-change: it surfaced that the *method*-dispatch paths had
never been taught that a `CallSig` is a nominal receiver (metareferences were never method receivers
before), plus the same bare-vs-qualified name tolerance the attribute path already had. Five principled
completions (none Method/Dispatch-specific hardcoding — all "a CallSig's head is its type name" /
"tolerate the linker's module qualification", i.e. E1's mechanical fold + the attribute-path fixes,
extended to method dispatch):
- `MethodOperatorResolver.baseName` and `DispatchResolver.baseName` → `CallSig` yields its `typeName`
  (a metaref receiver dispatches its traits' methods like any nominal).
- `DispatchResolver.routeMethod` → tolerate a `mod/Type.method` key for a bare receiver nominal.
- `MethodOperatorResolver.tryResolveMethodOn` → target the resolved function's actual (qualified) name.
- `SortChecker` impl-method `implByShortName` → take the final name segment (the same
  qualification-tolerant strip already applied to attribute producers), so a builtin-typed impl's
  linker-qualified method matches its contract.

Takeaway: the refactoring holds — a new callable type's members are added by declaration, and every
engine touch was completing a generic pattern for a code path E1/E2 hadn't exercised, not new special-casing.

## 7. Verification

Full `mvn test` green after E1 (behavior-preserving + the extensibility acid-test type) and after
E2 (new `.ast` behavior). Drive end-to-end via `PontifRunner`:
`eval($poly[Decimal].ast, 3.0) == poly(3.0)` → `true`; `$inc[Decimal].ast` → compile error.
Regression surface: ir 295 / parser 247 / runtime 957 + demo (~1290).

## 8. What Slices A + B already landed (`1890fda`)

- **Slice A** — general some-branch **intersection member resolution** (field inference in
  `NarrowingInference.inferFieldOnBase`, method resolution in `MethodOperatorResolver`, the
  `SortChecker` field-existence gate + `branchProvidesField`/`describeIntersection`). E2's `.ast`
  member resolution rides this; **keep**.
- **Slice B** — the `[Dispatch & Algebraic]` stamp: `Algebraic` as a `MARKER_SORT_NAMES` name,
  `algebraicFunctions` on `InferenceContext` (populated in `fromModule` from `assign proof
  f:Algebraic`), `NarrowingInference.dispatchRefSort` stamping the intersection, the
  `AssignabilityTest` edge, `AlgebraicDispatchTest`. **E2 reworks the marker+intersection onto the
  real `Algebraic`/`AlgebraicDispatch` traits** — the `algebraicFunctions` collection and the
  stamping *location* are reusable; the sort it produces changes.
