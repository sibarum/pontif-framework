# Recursive-union type-checking blowup

*Analysis of why closing `AlgExpr` into a closed union currently hangs the compiler,
and what it takes to fix it. Written 2026-07-21 after the attempt was made and
reverted. Companion to `docs/dispatch-method-elimination.md` and the `pontif.poly`
CAS core.*

Status: **PROBLEM DIAGNOSED, FIX PROPOSED, NOT YET BUILT.** The closed-union change is
reverted; `AlgExpr` stays an open trait for now.

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

## 5. The solution

**Memoize the recursive-type work** so each distinct subproblem is computed once. Turning
the super-exponential product into a polynomial number of cached lookups:

1. **`Assignability`: add a memo table** keyed by `(subSort, supSort)` (and one for
   `sameType`). This is the highest-leverage change — the engine currently recomputes
   every membership/structural comparison, and a recursive union makes those repeat
   combinatorially. A per-`AssignabilityContext` `Map<Pair, Boolean/Assignment>` cache,
   with an in-progress marker to break cycles on recursive types, is sufficient.
2. **Resolve recursive unions by reference, not expansion.** Where the alias/sort machinery
   expands `AlgExpr` structurally, cache the resolution by name so a recursive field
   (`Add.left : AlgExpr`) is resolved once and shared, never re-expanded per occurrence.
3. **(Optional) Memoize `NarrowingInference` match-branch narrowings** keyed by
   `(expr, incoming sort)`, so a nested match doesn't re-infer the same branch bodies.

The set of `(subexpression, sort)` pairs in a program is finite and small, so memoization
makes type-checking a recursive union **linear-ish in program size × arity** instead of
exponential. This is standard fixpoint/memoization for recursive-type systems.

**Placement:** core type-system work in `pontif-ir` (`types/Assignability` primarily,
plus the alias/narrowing paths). This overlaps the **C3 `Assignability` campaign**'s active
area — coordinate so the memo table lands with, not against, that work.

## 6. Verification

Reconstruct the §3 repro as a benchmark: compile the `same`-over-`T` scenario for
`N = 4, 6, 8, 12` and assert compile time stays roughly **linear** in `N` (e.g. N=12 under
a second). Before the fix: 0.16 s / 14.6 s / >20 s / hang. After: all sub-second. That
benchmark is the regression guard — a future change that reintroduces un-memoized
recursive-type work will show up as the curve bending upward again.

Then closing `AlgExpr` becomes viable end-to-end: apply the §2 wall-1 `.ast` fix, change
`AlgExpr` to the union, drop the catch-alls, and confirm the `pontif.poly` suite passes —
`differentiate`'s `[_] -> Const(0.0)` lie is gone (a new node is a compile error), and
`substitute` recurses into every node by construction.

## 7. Interim state (until the fix lands)

- `AlgExpr` stays an **open trait**. `differentiate` keeps `[_] -> Const(0.0)` — a
  **documented known limitation**, not an accepted design (the closed union is the fix).
- **No error primitive / `[!!]` sort** — it contradicts the no-lie design intent, and the
  closed union supersedes it once the checker scales.
- `gradient` and the other `pontif.poly` work are independent of this and unaffected.
