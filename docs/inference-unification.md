# Inference unification — scope-aware narrowing (the core capability superset)

*Status: design ratified in principle (James, 2026-06-18). This is the "feel the
shape" artifact for a **declare-war-then-divide-and-conquer** change (see memory
`feedback_declare_war_divide_conquer`). It is the durable record of the data model so
the campaign survives a context reset. Slices 1–3 of the inference-engine unification
(docs/TODO.md "Cluster 5+") already landed; this supersedes the original slice 4.*

## The principle

There must be **no place in the syntax where a narrowing feature is unavailable when it
is logically derivable.** Today three typers disagree (docs/TODO.md Cluster 5); slices
1–2 collapsed the two *engine-side* ones (`SortChecker.inferSort` → `inferFloor`). The
*parser-side* `AltParser.inferMaximalSort` is the last divergent reasoner — and the way
it differs reveals a real **hole in the core**, not a thing to mechanically absorb.

The parser produces an exact **value-pin** `[Int:@==a+b]` for arithmetic; the core
(`NarrowingInference.inferBinOp`) produces only a numeric **bound** `[Int:@>=2]` (or
`null` when the operand is unbounded). The pin is *strictly more precise*.

## The dissolution: a bound IS a pin closed over its escaping variables

A narrowing is a **refinement predicate over `@`** that may reference **free variables**
(the in-scope bindings). `@==a+b` and `@>=2` are both predicates — not competing shapes.
A "bound" is not a rival representation; it is **what a pin becomes after the variables
it references leave scope**. The eliminator is `BoundAnalysis` (bound a variable to an
interval, substitute). So:

> Produce the **most precise kernel-compilable predicate valid in the consuming scope.**
> In-scope → the pin. Crossing a scope boundary → **close** over the departing variables
> (project them out via `BoundAnalysis`), which yields the bound.

The current core does the closing **eagerly and always** (it never keeps a pin), which
is *correct for escaping returns but lossy for in-scope uses*. The fix is to close
**only at boundaries**.

### The one hard invariant (was: "cluster-4 tuning")

**Never mint a refinement whose predicate the kernel can't compile.** `@==a/b` must not
be synthesized — `/`,`%`,`^` have no linear-kernel node, so such a pin is a latent
crash. This lives in the core now, not just the parser. (Mechanically: only pin when
`IrCompiler.compileSymExpr(expr)` succeeds; `isArithmetic` already excludes DIV/MOD/POW.)

## The scope boundaries (where closing happens) — THE AUDIT LIST

A narrowing must be closed over a variable exactly when that variable **leaves the scope
in which the narrowing will be consumed**:

1. **Function return** (`NarrowingInference.inferFunctionReturn`). The body's narrowing
   may reference params; the return value escapes to the *caller* (becomes a `CallRef`
   result sort in the caller's receipt graph), where the params don't exist. **Close over
   all param names.** — Guardrail: `ReceiptGraphReportTest.chainArithmetic_…`,
   `bareIntCallee_…` (`chain(x) -> add5(x)+5` discharges `@>=10` only if `add5`'s return
   closes `x+5` to `@>=5`).
2. **Stream / iterate element quantification** (`NarrowingInference.inferIterate`). The
   per-element narrowing references the element var; the stream sort is
   `∀ element ⟹ …`, so the element var leaves scope. **Close over the element var.** —
   Guardrail: `NarrowingInferenceTest.iterate_map_narrowsToStreamOfTransformedElement`
   (`e+1` with `e:[@>=0]` → `Stream[Int:@>=1]`).
3. **Match-arm union** — arm results are unioned; a per-arm hypothesis on the scrutinee
   var leaves scope at the union. Today `sameBaseUnion` already operates on closed
   results; verify pins don't leak a scrutinee var across the union.

In-function-scope uses (a `let v = x+1` consumed in the same body; the parser typing a
local let for desugar) **keep the pin** — `x` is still in scope. This is the precision
the parser already has and the core was missing.

## The data model / flow

One engine, invoked at every stage with a stage-appropriate `InferenceContext`:

- **Core** (`NarrowingInference`): `infer(expr, ctx)` returns the **open** most-precise
  narrowing (pin when compilable; may carry free vars from `ctx`). `inferFloor` adds the
  base fallback (landed, slice 1). New: `closeOver(sort, Set<String> escaping, ctx)` —
  if the predicate references any escaping var, re-project via `BoundAnalysis`
  (reusing `intervalToIntSort`); else return as-is. Boundaries call `closeOver`.
- **Parser** (`AltParser`): build an `InferenceContext` from its scope maps
  (`currentScope` → `typeEnv`, `declaredFunctionReturns` → `functionReturns`,
  `declaredTopLevelLets`, `declaredStructs` → `structDefs`) and call `inferFloor`.
  `inferMaximalSort` is deleted; parse-time weakness then falls out *only* from an
  emptier context (no imports → abstain/`_` where post-link resolves), never from a
  divergent strategy. NOTE the parser uses `"_"` (Named) as its "unknown" floor where the
  core uses `null`; the delegation must map `null → "_"` at the parser boundary to
  preserve the desugar contracts.

## The war plan (declare war → divide & conquer)

Branch off master (`master` stays green at slice 3, commit `557fa83`). Then:

1. **The break:** `inferBinOp` produces the value-pin `[Int:@==expr]` when
   `compileSymExpr` succeeds, else `null`. Delete the eager `BoundAnalysis`/`intervalToIntSort`
   call *from `inferBinOp`* (it moves into `closeOver`).
2. **Static analysis lights up.** Build + full suite. Every red test is a boundary that
   was relying on eager closing. Expect: `NarrowingInferenceTest` arithmetic (now pins —
   update expectations), the iterate test, the `chain`/`add5` discharge tests, possibly
   issuer/notary paths.
3. **Divide & conquer the boundaries:** add `closeOver` at (1) `inferFunctionReturn`,
   (2) `inferIterate`, (3) any match-union leak. Re-green each.
4. **Route the parser through the core** (build `InferenceContext` from scope maps; map
   `null → "_"`); delete `inferMaximalSort`. Watch parser/desugar + snapshot tests.
5. **Verify** full suite + 150-probe matrix; the discharge headliners (factorial / inc /
   ackermann / chain) must still close.

Leave `// WAR:` breadcrumbs at any site left broken mid-campaign, and commit green
logical checkpoints on the branch so a context reset can resume from here + the diff.

## Campaign status (2026-06-18)

**Steps 1–3 LANDED on branch `war/scope-aware-narrowing`** (commit after 489f84b):
`inferBinOp` produces the open value-pin; `closeOver` projects at the two boundaries
(`inferFunctionReturn`, `inferIterate`); `checkMatchTotality` widens an open-pin
scrutinee to its base. **Full suite + 150-probe matrix green.** The core is now the
capability superset for narrowing *shapes* — the exact pin the parser had is now the
core's behavior.

**Step 4 (route the parser through the core; delete `inferMaximalSort`) — NOT a clean
swap. THREE real impedance points the core must absorb first** (the parser types at
parse time, pre-link, for desugar purposes — a genuinely different stage):
1. **MethodCall result typing.** `inferMaximalSort` types `recv.method()` via a
   `Type.method` lookup in `declaredFunctionReturns`; the core's `infer` returns `null`
   for `MethodCall` (deliberate: "unresolved until MethodResolver"). Routing needs a
   best-effort `MethodCall` case — put it in the **floor** (`inferFloor`), matching the
   parser's best-effort stance, not in `infer`.
2. **Unrouted-operator return.** For a parse-time DIV/MOD/POW or struct-operand BinOp
   (an un-routed user operator), `inferMaximalSort` looks up the operator *symbol*'s
   declared return (`declaredFunctionReturns.get("+")`). The core has no operator-symbol
   case (post-link these are `Call`s). Needs a floor-level fallback.
3. **Record shape mismatch.** `inferMaximalSort` → `IrSort.Structural(name, memberSorts)`
   (a bare structural with member sorts, for parse-time field typing of inline/anonymous
   records); the core's `inferRecord` → an `IrSort.Refined` (field-predicate conjuncts)
   or `null`. Consumers of the parser's record result (FieldAccess base typing,
   demotion/coercion detection) expect the structural shape. Reconcile before routing.

Plus: map the core's `null` → the parser's `"_"` floor at the delegation boundary, and
preserve the cluster-4 operator-routing-blindness behavior (the `null`/`_` for unrouted
cross-module operands). These are parse-stage concerns; the clean home for 1–2 is the
floor layer (best-effort), and 3 is a real shape-unification decision (James).
