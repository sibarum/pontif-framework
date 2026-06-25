# The arrow clause — one construct, five faces

Status: **WAR IN PROGRESS 2026-06-24.** Unifying the five `->` constructs.

## Why

Pontif's `->` (the only arrow token — `AltLexer.java:290`; no `<-`/`=>`) drives five
constructs that are all one atom: a **clause = `DESTRUCTURE → PRODUCTION`**. Left binds the
input (James's "argument sorts = value ranges + destructuring"); right produces the output
("return types are sequences of type conversion"). The realization came from the event war
(`docs/events.md`): a conduit is a fold-with-effects = the monad, and the monad *is* the
arrow notation — so the conduit's `triggered`/`transformState` are just more clauses.

## The abstraction

A **clause** = `DESTRUCTURE → PRODUCTION`.

- **DESTRUCTURE (left)** — introduces input bindings into scope. Forms, all "scope
  introduction": param binders `(x:A, …)` (fragment, cast), pattern `[p]` (match arm),
  let-stages `let x:S = E` (in-type pipeline). A `match` on the left is the *aggregated*
  form — a set of pattern-clauses.
- **PRODUCTION (right)** — an output ascription-transform (one notion per
  [[principle_ascription_is_transform]]), three faces:
  - **value (definitional)** — an expression body (fragment, cast, match-arm result);
    equivalently the `@==witness` in sort position (pipeline, construction-pin, spec-only `;`).
    The value↔sort-witness bridge already exists: `definitionWitness` (`AltParser.java:1282`).
  - **predicate (membership)** — a sort `[Base:pred]` (proof-grant's granted sort when it is
    not a definition).
- **AGGREGATION** — a set of pattern-clauses = `Match` (ordered, overlap-ok); proof-grant's
  case-function is the same in sort-output position.
- **COMPOSITION** — chained `→…→` = nested let-in (the in-type pipeline).

## The five faces

| Face | Left | Right | IR (unchanged) |
|---|---|---|---|
| Fragment `[ (el:A) -> body ]` | param binders | value | `IrExpr.Lambda` |
| Instance cast `cast T:(n:S) -> body` | one source binder | value | `IrStmt.Coercion` |
| Match arm `[p] -> result` | pattern | value | `IrExpr.MatchBranch` |
| Proof return-grant `[ (match …) -> Sort ]` | case-split | predicate (sort) | `IrStmt.ReturnProof` |
| In-type synthesis `[ let x=E -> … -> @==w ]` | let-stages | value-as-sort-witness | `IrSort.Refined` |

## What "unify" means here (and what it does NOT)

Unify the **front-end** (one shared clause-body parse) and the **production notion** (value
≡ sort-witness via the existing witness machinery). Each site keeps its **existing IR node**
and lowers to it — this is *not* a big-bang merge of the five IR nodes (that is a later
slice), per lens-not-cage ([[project_type_spec_layering]]). Tree green after every slice.

The shared core extracted: `parseClauseBody(binders, destrs, freshScope)` — the
save-scope → install-binders → `parseExpr` → restore-scope dance every value-producing
clause performs identically today (fragment, cast, and — eligible to follow —
function/method bodies). `freshScope=true` is a *closed* clause (fragment, cast: scope is
just the binders); `freshScope=false` is an *open* clause (a match arm augments the
enclosing scope). The production-as-sort-witness face rides `definitionWitness`.

## Slices — outcomes (2026-06-25)

- **S1 — DONE.** Extracted `parseClauseBody(binders, destrs, freshScope)`; migrated
  **fragment** (`parseFragmentLiteral`) and **instance cast** (`parseCoercion`) onto it
  (closed clauses, `freshScope=true`). The save→install→`parseExpr`→restore dance is now
  written once. Green.
- **S2 — DONE.** Migrated the **match arm** onto `parseClauseBody` (open clause,
  `freshScope=false`); `seedPatternBinders` became `patternBinders` (the destructure
  extractor). Green.
- **S3 — REGROUPED (no forced migration; honest finding).** The two **sort-production**
  faces do NOT fit `parseClauseBody`, and forcing them would be a cage:
  - **Proof return-grant** scopes its proof params across BOTH the case body AND the
    granted sort (the sort references the params; restore is in `finally`), its production
    is a *sort*, and its case body sits *left* of the arrow — `parseClauseBody`
    (binders → value-body, restore-after-body) can't model that. BUT the proof's case body
    is a `parseMatch`, whose arms now ride `parseClauseBody` (S2) — so it is **transitively
    unified** for free.
  - **In-type pipeline** is the one genuinely-specialized site: let-stage binders that
    accumulate across stages and a *sort* production. It is unified only **conceptually +
    by the already-existing value↔sort-witness bridge** (`definitionWitness`,
    `AltParser.java`), which is exactly the production-duality. No code change.

  Net: the three **value-production** clauses share one front-end (`parseClauseBody`); the
  two **sort-production** clauses are unified by the existing witness bridge, with the proof
  riding the unified match arms transitively. Deeper unification (a single IR-level `Clause`
  node spanning all five) remains deliberately deferred (the big-bang the plan scoped out).

Downstream consumer (after this war): the event conduit's `triggered(state:R, event:E):R`
and optional `transformState(state:R):S` are written as clauses on `parseClauseBody`
(`docs/events.md`, [[project_event_substrate]]).

## The unified clause-chain (RATIFIED 2026-06-25 — IN PROGRESS)

**Build status:** S1–S5 LANDED — the unified clause-chain is built. **S5 is the single parser**
(`parseClauseStages`): one running `@` threaded through a `[ stage -> … ]` sequence, with TWO
stage kinds freely interleaved — a **binding** `let a (:S)? = b` (names only, `@` unchanged) and
a **production** that sets `@ := expr`. A `[Type]` is *not* a separate kind: it is the coercion
case of a production, `@ := (Type : @)`, lowering to an `IrExpr.Cast` (identity when `@` already
has that type; validity — String render, `Int→Decimal`, user `cast` — is the existing cast
machinery's job, fail-closed otherwise). So a chain is `[ in → let… → expr → Type → … ]` in any
order, not the rigid `[let… let… expr]` shape. **There is no two-kinds-of-`@`:** `@` is always
the running value; a fragment's `@` starts at the named input, a chain's at the anonymous input,
a closed clause's is established by its stages — same `@`. The old pipeline's `Base:@==witness`
terminus is just the **sort projection's spelling** of "the final `@` is that value", so it is
no longer required — `[let r = n*2 -> r]` synthesizes via the production terminus. `parseClauseStages`
is ONE fold over stages producing a final `@`-expression, then ONE position projection:
`asLambda` for input-led value faces (fragment / `@`-chain → `IrExpr.Lambda`), `asRefinedSort`
for the closed clause (→ `IrSort.Refined(base, @==finalAt & constraints)`; a sort method cannot
return a value). It retired `parseFragmentLiteral` / `parseClauseChain` / `parsePipelineSort`.
The deeper IR-level merge (one `Clause` IR node) stays deferred (lens-not-cage).

**Unified ascription (S6).** Applying an ascribed clause to its subject is now ONE primitive
(`applyReturnClause`) shared by every ascription site, not a function-return-only feature:
- `function foo(bar):[Int -> @+"" -> String] -> bar*2` — applied to the body ⟹ `"24"`.
- `let x:[Int -> @+"" -> String] = 12` — applied to the `= rhs` subject ⟹ `"12"`.
- `let f:[(el) -> el*2]` / `let f:[Int -> @+3 -> Int]` (NO `= rhs`) — no subject, so the clause
  binds AS A VALUE (the fragment), unchanged.
The rule: an ascription applies its clause to the subject being ascribed; with no subject, the
binding receives the clause itself (a transform with nothing to apply to IS the function value).
`=`-presence isn't a separate rule — it just decides whether a subject exists. `parseLet` /
`parseLetExpr` route a clause-typed `= rhs` binding through `applyReturnClause`, exactly as
`parseFunction`/`parseMethod` do for the body.

This completes the symmetry — there is **no** further "param-side mirror". Return and let are
*production* sites (they yield a value a clause can post-process); a **param is a membership
sort** — a value the function *receives*, producing nothing to apply a clause to. A function-typed
param is already `[Method(A):B]`; writing `[A->B]` there would collide with it and lie (the caller
passes an `A`). Input-boundary adjustment (e.g. `Int→Decimal`) is the construction gate coercing to
fit the membership sort — not a transform clause.

The doc below describes the S1–S4 shape; S5 generalized the stage model (every production sets
`@`; `[Type]` = coercion) and unified the parsers — see `project_arrow_unification` memory.

- **S1** — the anonymous-`@` conversion chain `[ A -> @… -> B ]` runs as a one-input transform
  (`AltParser.parseClauseChain` lowers it to an `IrExpr.Lambda` over a synthetic `$at` param;
  `@`→`$at` via `substituteSelf`, so the interpreter never meets a chain `SelfRef`). Recognized
  in the fragment value positions (`let f:[…]` top-level + nested, `&s:[…]` spread) via
  `looksLikeClauseChain` (a top-level `->` that is neither the named-binder fragment nor
  `[let …]` pipeline nor `[{…}]` tuple).
- **S2** — return-type-as-transform: a chain in return position
  (`function foo(bar:[Int]):[Int -> @+"" -> String] -> bar*2`) parses via `parseReturnClause`;
  the gate-visible return sort is the chain's terminus type, and `applyReturnClause` wraps the
  body in `Apply(chainLambda, body)` so the chain CONVERTS the result (`foo(12) == "24"`). No
  interpreter change — rides `Apply`. Both `parseFunction` and `parseMethod`.
- **S3** — front-end collapse. The two VALUE-producing faces — the named-binder fragment
  (`parseFragmentLiteral`) and the anonymous-`@` chain (`parseClauseChain`) — now share ONE
  value-position entry, `parseClause` / `looksLikeClause`, replacing the three duplicated
  `fragment-or-chain` call sites (`let f:[…]` top-level + nested, `&s:[…]` spread). Output kind
  is read from the first stage (param-list head → fragment; checkpoint head → chain), both
  lowering to one `IrExpr.Lambda`. The `parseSort` `[`-dispatch is documented with the
  **output-kind-by-position** rule: SORT-producing faces (the `[let …]` in-type pipeline)
  dispatch from `parseSort`; VALUE faces from value positions — a sort method cannot return a
  value, so a single union-returning mega-parser would be a cage (consistent with the prior
  arrow-war S3 regroup). The pipeline stays its own entry, unified with the chain by the shared
  `@==witness` value↔sort-witness bridge (`definitionWitness`) rather than forced code-merge.
- **S4** — stage type-flow. `@`'s type flows `A -> … -> B`: the synthetic `$at` is bound in
  scope at its running type so a conversion's output type is inferred, and a checkpoint
  *re-types* `@` (so the terminus checkpoint pins the return sort, overriding imprecise
  inference). A checkpoint also *asserts* the running type — but `checkChainStage` errors only
  when that type is RELIABLY known (the input or a prior checkpoint) and provably disjoint from
  the checkpoint; after a conversion the type is inferred, and since the inference substrate can
  mis-type a conversion (it pins `@+""` as `Int`, not `String`), the assertion **abstains** there
  (a false rejection is the worse lie — the no-lie law). Proof return-grant needs no code change:
  its case body is a `parseMatch` whose arms already ride `parseClauseBody`, so it is unified
  transitively (as the prior arrow-war S3 found) — confirmed green.

`ClauseChainTest`.

The whole family collapses into ONE bracketed construct that threads `@`:

```
[ stage -> stage -> … -> stage ]
```

- **`@` is the value-level self** ([[project_self_reference_law]]), threaded through the chain.
  A `:`-stage *tests* it (membership predicate); a `->`-stage *transforms* it (conversion) —
  the two faces of ascription-as-transform over one `@`.
- **Stage kinds:**
  - `(name:[T], …)` — named binder(s); names the threaded input (for arity > 1 or clarity).
  - `[T]` (bare `T` = caps-sugar) — type checkpoint: assert / re-type `@`.
  - a value-expr mentioning `@` (e.g. `@+""`) — conversion: computes the next `@`.
  - `let x:[S] = E` — local binding (the pipeline stage).
  - terminus — final type / value / witness.
- **Disambiguation is the bracket, NOT capitalization** (caps is a non-load-bearing hint):
  the `[]` marks the type — `(value:[Type])` = binder, `([Type]:value)` = cast; likewise
  `[v:[T]]`/`[[T]:v]`, `((v):T)`/`(T:(v))`.
- **Output kind is inferred from the FIRST stage:**
  - leading named param-list `(x:[A], …)` → an inline anonymous **`Method`** (a `Lambda`
    value) — the fragment.
  - else (`let` / `[T]` / `@`-expr first) → **closed**; type = the terminus's inferred type
    (a value, or a sort when the terminus is a sort-pin).
- **What collapses in:** fragment (named-binder form) · conversion-sequence
  `[A -> @… -> B]` (anonymous-`@` form) · in-type synthesis pipeline (let-stages) ·
  **return-type-as-transform** — the return clause applied to the body's result:
  `function foo(bar:[Int]):[Int -> @+"" -> String] -> bar*2` ⟹ `foo(12) == "24"` ·
  proof return-grant (case → sort, the else-branch). **A named binder is just a *named* `@`.**
- Dispatched from `parseSort`'s `[`-lookahead — one entry replacing the separate
  `parseFragmentLiteral` / `parsePipelineSort`.

**Why this is its own (bigger) sprint, not a continuation of S1–S3:** S1–S3 were a pure
front-end refactor. This adds genuinely NEW semantics — runtime evaluation of a conversion
chain applied to a value (thread `@` through stages); the **return-type-as-transform** eval
path (apply the return clause to the body's result); output-kind inference; conversion-stage
type-checking — on top of the parser unification and collapsing the existing sites. S1–S3
(value-clause front-end + value↔sort-witness duality) are the foundation it builds on.
