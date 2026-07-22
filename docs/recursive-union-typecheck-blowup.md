# Recursive-union type-checking blowup

*Analysis of why closing `AlgExpr` into a closed union currently hangs the compiler,
and what it takes to fix it. Written 2026-07-21 after the attempt was made and
reverted. Companion to `docs/dispatch-method-elimination.md` and the `pontif.poly`
CAS core.*

Status: **CLOSED — `AlgExpr` is now a closed union (2026-07-22).** All three walls are down
and the close landed end-to-end (`let AlgExpr:Type[Const | … | Log]`, catch-alls dropped from
`pontif.poly`). Sequence: the perf blocker (Wall 2) fell first — type-checking is now
polynomial in recursive-union arity (the 12-member scenario type-checks in tens of ms, was a
~19-minute hang) via a coinductive back-edge guard on the *anonymous union pair* in
`Refinements.imply` (narrower than §4's original `Assignability`-memo guess — see the
**Correction**). Reaching the gates then surfaced two more, both fixed 2026-07-22 (§8): Wall 3
(reflexive/member union subsumption — the **call** gate was reasoning over an *unresolved*
imported union alias) and Wall 1 (the `.ast` binding — a metareference's `CallSig` nominal
wasn't read for the attribute-producer projection). Regression guards:
`RecursiveUnionTypeCheckTest` (the arity curve) and `UnionAliasCrossModuleTest` (Walls 1 & 3).

## 1. The goal that surfaced it

`pontif.algebra`'s `AlgExpr` is an **open trait** with per-node `assign trait X:AlgExpr{}`
impls. That means a `match` over it can never be proven exhaustive, so every operation
needs a catch-all — and `pontif.poly`'s `differentiate` uses `[_] -> Const(0.0)`, which
is a **silent lie**: the "derivative" of an unrecognized node is reported as `0`
(see the `simplify(x + sin(x))` class of bug that motivated this).

The clean, no-lie fix is to make `AlgExpr` a **closed union** of its node structs:

```pontif
let AlgExpr:Type[Const | Param | Add | Sub | Mul | Div | Pow | Sin | Cos | Tan | Exp | Log]
```

Then every `match` over it is exhaustive with **no catch-all**, and adding a 13th node
becomes a *compile error* in every operation until the case is handled — exactly the
behavior we want, and no `[!!]`/exception sort needed (which the language is designed to
avoid). This is the right end state.

## 2. The problem — two walls

**Wall 1 — the `.ast` binding (fixable).** With `AlgExpr` a union, `let e:AlgExpr =
$f[Decimal].ast` fails the construction gate: *"cannot be proved to satisfy its declared
sort … the value's sort is (not statically known)."* Under an open **trait** claim the
binding was accepted nominally; a **union** claim demands static membership proof, and the
metareference `.ast` field-access wasn't inferred to the union.

Root cause: `NarrowingInference.inferFieldOnBase` only projects **struct** fields
(`ctx.structDefs().get(baseName)`). A metareference base (`AlgebraicDispatch`, not a struct)
returned `null`, so `.ast` had no static sort. The `.ast` **producer's** declared return
sort (`AlgExpr`) *is* captured in `InferenceContext.functionReturns` (from
`fromModule`'s attribute-producer loop), so the fix is to project it for a non-struct
nominal. **Prototyped and works** — this wall is not the blocker.

**Wall 2 — type-checking blows up super-exponentially in union arity (the blocker).**
Type-checking *any* program that uses `pontif.poly` (even a trivial
`substitute(Add(Param("x"), Const(1.0)), "x", Const(5.0))`) does not terminate in
practice: one observed run burned **~1135 CPU-seconds over ~19 minutes** before being
killed. Compile is fine (the module source is a runtime-parsed string, not `javac`
input); the blowup is in the **type-checker** resolving/checking the recursive union.

## 3. Evidence — quantified, minimal repro

The blowup is **scale-dependent**, which is why it hid: a 2-member recursive union with a
nested-match function type-checks instantly. Isolating the driver — `N` binary-node
structs, one recursive union, and a **single** nested/structural-match function over it:

```pontif
struct K0(a:T, b:T)
struct K1(a:T, b:T)
# … K(N-1) …
let T:Type[K0 | K1 | … | K(N-1)]

function both(p:Bool, q:Bool):Bool -> match p { [Bool:true] -> q  [Bool:false] -> false }
function same(x:T, y:T):Bool -> match x {
  [K0(a, b)] -> match y { [K0(c, d)] -> both(same(a, c), same(b, d))  [_] -> false }
  [K1(a, b)] -> match y { [K1(c, d)] -> both(same(a, c), same(b, d))  [_] -> false }
  # … one arm per member …
}
```

Measured compile time (single function, arity `N`):

| N (union arity) | compile time |
|---|---|
| 2 | instant |
| 4 | **0.16 s** |
| 6 | **14.6 s** |
| 8 | **> 20 s** (aborted) |
| 10 | **> 20 s** (aborted) |
| 12 (= `AlgExpr`) | infeasible (the ~19-min hang) |

Growth is **≈ ×90 per +2 members** (4 → 6). Two conclusions:

- The driver is **union arity × nested/structural match**, *not* the number of
  `pontif.poly` functions — a **single** `same`-shaped function already blows up by N=8.
- This is a **general** type-checker scaling bug, not anything specific to `AlgExpr`:
  any large recursive union used with structural/nested matches hits it.

## 4. Root cause

A nested match `match x { [Ki] -> match y { [Ki] … [_] } }` over an `N`-member recursive
union, whose branches' fields are themselves typed by the union, makes the checker explore
a **product of per-branch narrowings**, and each recursive call (`same(a,c)`,
`same(b,d)`) re-resolves the recursive union **from scratch**. The identical subproblems —
"is `Ki` a member of `T`?", "what is the narrowing of a `T`-typed field?", "does branch
`Ki` structurally match branch `Kj`?" — are recomputed exponentially because nothing
caches them:

- `pontif-ir/types/Assignability` (the `isA` / `sameType` / `bottomStructure` engine) has
  **no memoization at all** (0 cache sites).
- `NarrowingInference` / `SortChecker` have only minimal, purpose-specific caching (a
  `resolving` cycle-guard on one path) — nothing that dedupes the (sub-expression, sort)
  work across a recursive union.

So the work is super-exponential in arity, even though the *set of distinct subproblems*
is small (bounded by program size × union arity). Classic un-memoized recursive-type
checking.

### 4a. Correction — where the time actually went (measured 2026-07-21)

The §4 shape ("un-memoized recursive-type subsumption, exploding factorially") was right;
the **layer** was wrong. Phase-timing the pipeline put ~all of the wall time in
`EffectiveSortLens` (the pre-gate `span → effective sort` pass), **not** in `Assignability`
or the construction gate. The chain is:

```
EffectiveSortLens → NarrowingInference.infer → inferCall → StaticDispatch.matchStatus
                  → Refinements.imply(argSort, paramSort)      # arg/param sort = the union T
```

`Assignability.isA` is essentially *not on this path* — a memo there (the original §5.1
"highest-leverage change") would have done nothing. The real engine is the refinement
kernel's `imply`, and its blowup is precise: `imply(T, T)` for the recursive union `T`
recurses branch × branch into each struct's `T`-typed fields, which re-ask `imply(T, T)`.
`implyStructural` already carries a coinductive back-edge guard — but it is keyed by struct
**name** pairs, and a **union carries no name**, so the `(T ⊑ T)` obligation is re-derived
on *every* field descent. With `f(k)` the cost at `k` structs already assumed,
`f(k) = N²·cheap + 2·(N−k)·f(k+1)` ⟹ `f(0) ≈ N²·2ᴺ·N!` — matching the observed
`≈ ×90 per +2`.

## 5. The fix (as built)

**Give the anonymous union pair the same coinductive back-edge guard the named-struct pair
already has.** `Coinduction.Assumed` gains a second carrier — a set of whole-`Sort` pairs
(`Sort` is a value record, so two occurrences of `K0|…|Kₙ` compare structurally equal
whether they arrive as the argument narrowing or as a recursive struct field). At the top
of `Refinements.imply`'s union arm: if `(tighter, looser)` is already assumed, return
`passed` (the greatest-fixed-point back-edge); otherwise mark it and recurse. That collapses
the field recursion — the first descent into a struct's `T` field revisits `(T ⊑ T)` and
returns immediately — turning `f(0)` from `N²·2ᴺ·N!` into `O(N²)`.

Soundness is the *same* argument that licenses the existing struct guard (class doc on
`Coinduction`): subsumption over equi-recursive sorts is a greatest fixed point, so the
back-edge holds and every non-back-edge branch obligation is still checked. The guard fires
**only** on a genuine back-edge — the identical union pair already on the current derivation
path — which cannot happen for a finite, non-recursive sort, so no non-recursive result
changes (borne out by the full suite: pontif-core 305, pontif-ir + parser, pontif-runtime
1020, all green).

Two files: `pontif-core/.../Coinduction.java` (the `Sort`-pair carrier) and
`Refinements.java` (the union-arm guard). The original §5 proposals (`Assignability` memo,
resolve-by-reference, narrowing memo) were **not** needed and were not done; the C3
`Assignability` campaign is unaffected.

## 6. Verification — done

`RecursiveUnionTypeCheckTest` (pontif-runtime) is the regression guard, two tests:

- **`arity12TypeChecksPromptly`** — compiles the §3 `same`-over-`T` scenario at arity 12
  and asserts it type-checks within a generous 15 s budget (a curve-bend detector). Measured
  after the fix, the whole curve is polynomial:

  | N | 2 | 4 | 6 | 8 | 12 | 16 | 24 | 32 |
  |---|---|---|---|---|----|----|----|----|
  | compile | ~70 ms | ~14 ms | ~28 ms | ~25 ms | ~51 ms | ~100 ms | ~258 ms | ~556 ms |

  (Pre-fix, for comparison: N=6 ≈ 14.6 s, N≥8 aborted, N=12 the ~19-min hang.)
- **`recursiveUnionMatchEvaluatesCorrectly`** — a recursive `Tree = Leaf | Node` *with* a
  leaf, so real trees are built and `same` is run; pins that the coinductive type-check
  shortcut left runtime `match`/dispatch semantics intact (equal trees → `true`, a differing
  leaf or a shape mismatch → `false`).

Closing `AlgExpr` end-to-end is now unblocked on the perf axis: apply the §2 wall-1 `.ast`
fix, change `AlgExpr` to the union, drop the catch-alls, and confirm the `pontif.poly` suite
passes — `differentiate`'s `[_] -> Const(0.0)` lie becomes a compile error and `substitute`
recurses into every node by construction. That is a separate change and is **not** done here.

## 7. Interim state (superseded 2026-07-22 — see §8; `AlgExpr` is now closed)

- The **performance blocker is gone** — a large recursive closed union type-checks quickly.
  Remaining before `AlgExpr` can close: Wall 1 (§2, the `.ast` binding) and the closing
  change itself.
- `AlgExpr` stays an **open trait** for now. `differentiate` keeps `[_] -> Const(0.0)` — a
  **documented known limitation**, not an accepted design (the closed union is the fix).
- **No error primitive / `[!!]` sort** — it contradicts the no-lie design intent, and the
  closed union supersedes it once `AlgExpr` closes.
- `gradient` and the other `pontif.poly` work are independent of this and unaffected.

## 8. Closing the union after the perf fix — Walls 3 and 1, both now fixed (2026-07-22)

With the perf blocker gone the close was carried end-to-end (union alias, catch-alls dropped).
**The hang is gone** — `pontif.poly` type-checks in *milliseconds*, confirming §6. Reaching the
gates (which the hang previously prevented) surfaced **two more gaps**, each a latent bug the
open trait never exercised. Both are now fixed; the close is complete.

**Wall 3 — union subsumption at the gates.** Two layers, because two gates run over union sorts:

- *Construction gate* (`let e:AlgExpr = Add(…)`, and `Add(substitute(l,…), …)`): a union-typed
  argument against a union field read as UNKNOWN because the gate proved a union field only via a
  provable *single* member. **Fix:** `ConstructionGate.classify` checks the argument-side union
  first — a union argument fits iff *every* branch fits the field (reflexive `U ⊑ U` is then
  trivial). This is the wall as originally diagnosed.
- *Call gate* (`substitute(anAdd, …)`, `simplify(src)`, `evalInterval(e, …)`): the real blocker
  turned out to be elsewhere. `PontifCompiler.firstUnprovableCall` runs on the **pre-`AliasResolver`**
  module (every *other* gate runs inside `IrCompiler.compile`, post-resolution), so an *imported*
  union alias reached it as a bare `Named` the refinement kernel could not relate to its member
  structs — `imply(Add, AlgExpr)` read as **provably disjoint** and the call was rejected as a
  misroute. **Fix:** resolve aliases before the call gate walks, so it reasons over the union like
  every other gate. (A union *looser* is RESIDUAL, never FAILED, so a genuinely-wrong arg still
  abstains to runtime by design — the fix widens what routes, it does not blind the gate.)

**Wall 1 — the `.ast` binding.** `let e:AlgExpr = $f[Decimal].ast`. A metareference narrows to a
dispatch-style **`CallSig`** (`[AlgebraicDispatch]`), but `NarrowingInference.baseName` handles
only `Named`/`Refined` — so `inferFieldOnBase` had no nominal to key on and returned before the
attribute-producer lookup (which is otherwise correct: the `ast` producer's `AlgExpr` return sort
*is* in `functionReturns` as `<owner>/AlgebraicDispatch.ast`). **Fix:** read the nominal from the
`CallSig`'s `typeName` for the producer projection (the shared `baseName` helper stays
`Named`/`Refined`-only, its contract for the struct-field paths). The value's sort is then the
union and the construction gate discharges the claim.

**Status: done.** `AlgExpr` is a closed union; `differentiate`'s `[_] -> Const(0.0)` lie is gone
(a new node is a compile error); `substitute` recurses into every node by construction. Guards:
`UnionAliasCrossModuleTest` (Walls 1 & 3, cross-module), `PolynomialModuleTest` /
`AlgebraAstSurfaceTest` (the `pontif.poly` and `.ast` surfaces end-to-end).
