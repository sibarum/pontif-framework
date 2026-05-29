# Numeric discharge — the built-in issuer's integer reasoning

**Status: slices 1–3 built.** `BoundAnalysis` ships in
`pontif-predicates`, is the sole engine inside `IntegerDischarge`
(receipt-graph issuer + notary), and powers `NarrowingInference`'s
arithmetic narrowing + the Drafter's body-inference fallback for
unrefined-return callees. Dispatch-implication consumer
(`Refinements.imply`) remains designed-but-not-wired (see TODO). This
document doubles as the design record and the as-built description.

How Pontif's built-in default issuer decides integer obligations like
`r_0 >= 1`, `y_0 + 1 > 1`, `n_0 * r_1 >= 1`, `x_0 * x_0 >= 0`. It replaces
the issuer's reliance on sign analysis alone with a **hybrid
linear-bound + sign** engine.

---

## The problem

Sign analysis answers exactly one question: *which side of zero?* Its
whole vocabulary is `{POSITIVE, NEGATIVE, ZERO, NON_NEGATIVE,
NON_POSITIVE, TOP}`. So it can discharge `> 0`, `>= 0`, `< 0`, `<= 0`,
`== 0`, `!= 0` — and nothing else. `[Int:@>1]` is unreachable: there's
no sign value for "≥ 2."

That makes the issuer feel arbitrary — `[Int:@>0]` discharges,
`[Int:@>1]` doesn't, though both are obvious. The fix is to reason about
**bounds**, not just signs. Comparisons are translation-invariant
(`x > 1 ⟺ x - 1 > 0`), so the right tool normalizes to a linear form and
reasons relative to it — no privileged reference frame, no `>0`-vs-`>1`
cliff.

But pure linear arithmetic can't do **products**: `n_0 * r_1` and
`x_0 * x_0` are nonlinear, and those are exactly what sign analysis is
good at (`pos*pos = pos`, `square ≥ 0`). So neither tool subsumes the
other. The engine is a **hybrid**: linear bound propagation, with sign
analysis supplying bounds for the nonlinear pieces.

---

## The engine

Decide a goal `subject OP bound` (a `SymExpr.Cmp`, `bound` need not be
constant) under a set of hypotheses (the `PathFacts`: parameter
refinements, guards, sub-call IHs, body receipts).

### 1. Linear normal form

Normalize an integer `SymExpr` to `c₀ + Σ cᵢ·aᵢ` — a constant plus a map
from **atoms** to integer coefficients.

- `Lit(k)` → constant `k`
- `Var(v)`, `Self` → atom, coefficient 1
- `Add(a, b)` → merge normal forms (sum coefficients per atom, sum
  constants). `Sub` is already `Add(a, Mul(-1, b))` in the IR.
- `Mul(a, b)`:
  - one side a pure constant `k` → scale the other normal form by `k`
    (linear)
  - both sides have atoms → the product is **nonlinear**; treat the whole
    `Mul(a, b)` as a single **opaque atom**, coefficient 1
- `Pow`, `App`, `FieldAccess`, `Frac`, … → opaque atom

Atoms are identified by structural equality of the `SymExpr`. (v1 caveat:
`a*b` and `b*a` are distinct atoms — commutative canonicalization is a
cheap later refinement.)

### 2. Atom bounds → integer interval `[lo, hi]` (±∞ allowed)

For each atom, start at `[-∞, ∞]` and intersect:

- **From hypotheses:** any hypothesis whose normal form is exactly
  `1·atom OP const` bounds this atom. Integer-strict:
  `> c → [c+1, ∞)`, `>= c → [c, ∞)`, `< c → (-∞, c-1]`, `<= c → (-∞, c]`,
  `== c → [c, c]`. (`!= c` can't be one interval — skipped.) This is
  where the integer-strictness bridge now lives, naturally: `> 0` *is*
  `[1, ∞)`.
- **From sign analysis (nonlinear atoms):** run
  `SignAnalysis.computeSign(atom, hypotheses)` and map the sign to an
  interval (`POSITIVE → [1, ∞)`, `NON_NEGATIVE → [0, ∞)`,
  `NEGATIVE → (-∞, -1]`, …). This is how `n_0*r_1` gets `[1, ∞)` and
  `x_0*x_0` gets `[0, ∞)`.

Intersection of sound bounds is sound.

### 3. Interval evaluation of the linear form

`eval(c₀ + Σ cᵢ·aᵢ) = c₀ + Σ (cᵢ · interval(aᵢ))`, using interval scaling
(negative `cᵢ` flips the interval) and addition, saturating at ±∞.

### 4. Discharge

Normalize the goal to `(subject − bound) OP 0`:

1. `lin = normalize(subject) − normalize(bound)`
2. `iv = eval(lin)`
3. discharge iff the *entire* interval `iv` satisfies `OP 0`:
   - `GT`: `iv.lo > 0`   `GE`: `iv.lo >= 0`
   - `LT`: `iv.hi < 0`   `LE`: `iv.hi <= 0`
   - `EQ`: `iv == [0, 0]`   `NE`: `iv.lo > 0 || iv.hi < 0`

Interval analysis over-approximates the value set, so a whole-interval
pass means the real set passes too — **sound, never a false discharge.**
Incompleteness shows up honestly as `NOT DISCHARGED`.

This uniform `OP 0` framing subsumes the current special cases:
reflexive equality (`y+1 == y+1` → `lin = 0` → `[0,0]` → `EQ` ✓) and the
integer-strictness bridge (folded into atom-bound extraction).

---

## Worked checks

| goal | lin (subject − bound) | interval | result |
|---|---|---|---|
| `y_0 + 1 > 1`, hyp `y_0 > 0` | `y_0` (const 0) | `[1, ∞)` | `lo 1 > 0` ✓ |
| `n_0*r_1 >= 1`, hyps `n_0>0, r_1>=1` | `(n_0*r_1) − 1` | `[0, ∞)` | `lo 0 >= 0` ✓ |
| `x_0*x_0 >= 0` | `x_0*x_0` | `[0, ∞)` | `lo 0 >= 0` ✓ |
| `1 >= 1` | `0` | `[0, 0]` | ✓ |
| `y_0+1 == y_0+1` | `0` (atoms cancel) | `[0, 0]` | ✓ |
| `x_0*x_0 >= 1` (false-ish) | `(x_0*x_0) − 1` | `[-1, ∞)` | `lo -1 >= 0`? **no** — correctly NOT discharged (x=0) |

---

## Coverage and the new boundary

**Built-in issuer now covers:**
- any `[Int op n]` threshold (the headline — translation-invariant)
- linear combinations: `2x + 3 >= 5` from `x >= 1`
- products / squares via opaque atoms + sign analysis (factorial, square)
- linear-form equality (reflexivity and beyond)

**Still oracle territory (honest `NOT DISCHARGED`):**
- **Multi-atom hypothesis constraints** — a hypothesis like `x + y > 0`
  bounds neither `x` nor `y` alone; deciding goals from it needs a real
  linear solver (Fourier–Motzkin / Presburger). Pontif's hypotheses are
  overwhelmingly single-atom (`y_0 > 0`, `r_1 >= 1`), so this is rare.
- **Product magnitude** — `x*y >= 6` from `x>=2, y>=3` gets only the sign
  (`POSITIVE → ≥1`), not `≥6`. A later refinement could interval-multiply
  the *factors* (`[2,∞)·[3,∞) = [6,∞)`) — but must keep the square rule
  for `a*a` (interval-multiplying `(-∞,∞)·(-∞,∞)` loses `≥0`). Deferred.
- general nonlinear, quantifiers, undecidable fragments.

This revises `receipt-graph.md` / TODO, which filed *all* linear
arithmetic under oracle territory. New line: **linear integer arithmetic
is built-in; oracles start at general nonlinear / quantified.**

---

## Placement, integration, naming (as built)

- **Home:** `pontif-predicates`, the integer-calibrated predicate-arithmetic
  kernel. It depends only on `pontif-core`, so it can call `SignAnalysis`
  for atom bounds, and everything above it (`pontif-ir`, `pontif-receipts`)
  can call it — the layering that lets the IR-level consumers reuse it
  later. Integer-domain reasoning belongs here, kept out of the
  rational-calibrated `Sign` lattice (see the
  `project-sign-is-rational-calibrated` note).
  - **`Interval` is a *new* type, not a reuse.** The design originally
    expected to reuse `PredicateArithmetic`'s `Interval`/`IntervalSet`,
    but those are private nested records modelling integer *sets*
    (`intersect`/`union`/`complement`) with no scaling or saturating
    addition, and they use `Long.MIN/MAX` as ±∞ sentinels that would
    overflow under arithmetic. `BoundAnalysis` ships its own public
    `Interval` (a single range with saturating `scale`/`add`). Merging the
    two is a deferred follow-up — see `docs/TODO.md`.
- **Integration:** `pontif-receipts/IntegerDischarge.discharge` is now a
  thin one-line wrapper that delegates to `BoundAnalysis.discharge`. The
  earlier OR-chain backstops (sign / `Refinements` / integer-strictness /
  reflexive-equality) were empirically confirmed subsumed across the full
  test suite (~920 tests, all green with each backstop removed in turn)
  and dropped. The wrapper still earns its keep as a soundness gate —
  it marks the call site as "integer-domain only," important for future
  Float-refinement work.
- **Naming (chosen):** `BoundAnalysis`, parallel to `SignAnalysis`. Public
  API: `discharge(List<SymExpr> hypotheses, SymExpr goal) → boolean` and
  `bound(SymExpr, hypotheses) → Interval` (exposed now for the call-site
  narrowing consumer, even though that consumer isn't wired yet).

---

## Risks / open questions

1. **Atom identity** — structural equality misses `a*b` vs `b*a` and
   un-normalized linear shapes. Mitigation: normalize commutative
   products and rely on the linear normal form for additive shapes;
   accept the rest as incompleteness (sound).
2. **Interval blow-up** — none expected; the forms are tiny. No fixpoint
   loop (it's a single bottom-up evaluation, not iterative dataflow).
3. **Reusability** — the same `bound(expr, hyps)` is exactly what
   call-site dispatch narrowing (Phase D) could use to tighten inferred
   sorts. Worth designing the signature with that second consumer in mind.
4. **Does it fully subsume sign analysis at the goal level?** ✅ Confirmed
   empirically: the receipt-graph path's full test suite (~920 tests)
   passes with `BoundAnalysis.discharge` as the *sole* engine, all four
   prior backstops removed. The wrapper survives only as a soundness gate.
