# Projective-rational algebra: the graded field `(⊕, ×)`

A builtin numeric type carrying **a coefficient and an integer order**, on which
multiplication is order-graded and addition is *harmonic*. The pair forms a total
field isomorphic to ℝ — but with the roles of `0` and `∞` swapped — and the order
integer doubles as the **calculus grading** (`∫` raises it, `d/dx` lowers it).

Harmonic addition turns out to be one rung of a whole **dial of compatible additions**
(§7), all of which multiplication distributes over; and there is a genuinely *total*
addition (§6) that makes the carrier the complex plane and vindicates the
`0 ↔ i, w ↔ −i` correspondence.

This note pins down the operations exactly, the identities that hold, how it relates
to and differs from the Riemann sphere, and why the order↔calculus link is practically
useful.

---

## 1. Representation

A value is `coeff · ε^order`, where `ε` is the formal "unit zero" (`0/1`).

```
struct Proj:
    coeff: Rational      # residual coefficient, nonzero
    order: Int           # valuation / grade
```

Read the order as a *class tag* plus a magnitude:

| order      | class            | written | meaning                          |
|------------|------------------|---------|----------------------------------|
| `= 0`      | finite           | `c`     | ordinary nonzero value `coeff`   |
| `= +1`     | zero             | `0/q`   | a zero, residual coeff survives  |
| `= -1`     | pole (`w`)       | `p/0`   | a pole, residual coeff survives  |
| `|order|>1`| repeated zero/pole | —     | only from multiplying like kinds |

Two distinguished constants:

```
ZERO = Proj(1, +1)      # 0   — additive absorber, "vanishing" pole of the field
W    = Proj(1, -1)      # w   — additive identity, the "point at infinity"
ONE  = Proj(1,  0)      # multiplicative identity
```

> **Not projective.** Unlike homogeneous coordinates, the residual coefficient on a
> zero is *retained*: `0/1 ≠ 0/2`. This is a graded/valuation object, not a quotient
> by scale. (See §4.)

---

## 2. Operations (exact pseudocode)

### Multiplication — orders add

```
mul(a, b):
    return Proj(a.coeff * b.coeff, a.order + b.order)
```

### Reciprocal — the ×-inverse. **ORDER FLIPS.**

```
recip(a):                      # a * recip(a) == ONE
    return Proj(1 / a.coeff, -a.order)
```

`recip(ZERO) == W` and `recip(W) == ZERO`. This is where `0` and `w` swap, and it is
the honest meaning of the shorthand "`-0 = w`": the `-` there is *reciprocal*, not
negation.

### Negation — the ⊕-inverse. **ORDER IS KEPT.**

```
neg(a):                        # a ⊕ neg(a) == W
    return Proj(-a.coeff, a.order)
```

Note `neg(ZERO) = Proj(-1, +1)` — a *different zero*, **not** `w`. Negation and
reciprocal are two distinct inverses; conflating them is the one thing that breaks
consistency, so they are kept apart here.

### Harmonic addition — `a ⊕ b = ab / (a + b)`

The reciprocal-conjugate of ordinary `+`. Implemented via order comparison so the
poles are handled without ever forming `∞`:

```
hadd(a, b):                    # a ⊕ b
    # More-zero side dominates: order(a⊕b) = max(order a, order b).
    if a.order > b.order: return a          # e.g. 0 ⊕ x = 0   (0 absorbs)
    if b.order > a.order: return b          # e.g. w ⊕ x = x   (w is identity)

    # equal order: harmonic combine of the coefficients
    s = a.coeff + b.coeff
    if s == 0:
        # exact cancellation → a definite pole one grade down (finite ⊕ = w)
        return Proj(a.coeff * b.coeff, a.order - 1)
    return Proj(a.coeff * b.coeff / s, a.order)
```

Key behaviours fall out: `w` is the identity, `0` absorbs, and cancellation
`x ⊕ neg(x)` lands on `w` — a *definite* pole — which is exactly why `⊕` closes where
ordinary `+` did not. (Ordinary `+` sends cancellation to `0`, whose order is
unknowable without the full series tail; `⊕` sends it to `w`, which is representable.)

### Calculus — `∫` and `d/dx` as order shifts

```
deriv(a):                      # d/dx of coeff·x^order
    if a.order == 0: return ZERO            # derivative of a constant
    return Proj(a.coeff * a.order, a.order - 1)

integ(a):                      # ∫ coeff·x^order dx
    if a.order == -1:                       # THE residue case — leaves the algebra
        return Log(a.coeff)                 # ∫ dx/x = ln
    return Proj(a.coeff / (a.order + 1), a.order + 1)
```

`integ` raises order by 1, `deriv` lowers it by 1, and the **sole special case is
`order == -1` (= `w`)** — the residue, the one power whose integral is transcendental.
The algebra's special element *is* the CAS's special case (§5).

---

## 3. Identities that hold

Multiplicative:

```
ZERO * W        == ONE                     # 0·w = 1   (the "0/0 ⇒ 1/1" move)
(a/0) * (0/b)   == a/b                     # (a,-1)*(1/b,+1) = (a/b, 0)
a * recip(a)    == ONE
recip(ZERO)     == W ,  recip(W) == ZERO   # order flip
recip is the isomorphism  (algebra, ⊕, ×) → (ℝ, +, ×)
```

Additive (harmonic) field:

```
W  ⊕ x          == x                       # w is the additive identity
ZERO ⊕ x        == ZERO                    # 0 absorbs
x  ⊕ neg(x)     == W                       # ⊕-inverse; cancellation → w
a * (x ⊕ y)     == (a*x) ⊕ (a*y)           # × distributes over ⊕   ← makes it a field
recip(x ⊕ y)    == recip(x) "+" recip(y)   # reciprocal turns ⊕ into ordinary +
```

The whole structure is **`(everything, ⊕, ×) ≅ (ℝ, +, ×)` via `x ↦ 1/x`**, with
`w ↔ 0_field`, `ONE ↔ 1`, `ZERO ↔ ∞`.

---

## 4. Relation to the Riemann sphere — and where it differs

Same starting impulse (make `1/0` a first-class citizen), but a different resolution.

| question                | Riemann sphere `ℂ ∪ {∞}`      | this algebra                          |
|-------------------------|-------------------------------|---------------------------------------|
| `1/0`                   | `∞`                           | `w` — a pole that **keeps its coeff** |
| `0 · ∞`                 | undefined (indeterminate)     | `0 · w = 1` (defined)                 |
| coefficient on a zero   | collapsed — `0` is one point  | **retained** — `0/1 ≠ 0/2`            |
| how many infinities     | one `∞` (all directions merge)| a graded tower `w, w², …` (orders)    |
| addition                | **not total** (`∞ − ∞`)       | `⊕` total; cancellation → `w`         |
| group structure         | **none** — `χ(S²) = 2`        | `(⊕, ×) ≅ ℝ`, a total field           |
| nature                  | projective (quotient by scale)| graded / valuation (scale retained)   |

The crucial divergence: the sphere is a **quotient** (it forgets coefficient and order,
keeping only the ratio), which is exactly why it has no group structure and no total
addition. This algebra **retains** coefficient and order — it *left the sphere* — and
that is precisely what buys back a total, associative, distributive field. It is
flatter and better-behaved than the sphere *because* it is not the sphere.

What it is **not**: it is not a model of `ℂ ∪ {∞}` addition, and it does not contain
ordinary `+` (`2 ⊕ 3 = 6/5`, not `5`). Harmonic addition *replaces* ordinary addition;
you cannot have both in one field (that would require carrying the full series tail).

---

## 5. The order↔calculus connection, and why it's useful

Because `order` is the exponent grade, `∫` and `d/dx` are just `order ± 1`. This makes
a family of otherwise-symbolic operations into **pure arithmetic on the order integer**.

### Worked examples

```
∫ 3x² dx      : (3, 2)            → integ → (1, 3)        = x³          ✓
d/dx x³       : (1, 3)            → deriv → (3, 2)        = 3x²         ✓
∫ dx/x        : (1, -1)           → integ → Log(1) = ln x               ✓ (residue case)
∫ (const) dx  : (c, 0)            → integ → (c, 1)        = c·x          ✓
```

### Practical payoffs

1. **Indeterminate forms resolve by order arithmetic.** `0/0`, `∞·0`, `∞/∞` become
   order subtraction plus a coefficient — no limits machinery:
   ```
   lim_{x→0} 5x² / x³  =  (5,2) * recip(1,3)  =  (5,2)*(1,-3)  =  (5,-1)
                       →  order < 0 ⇒ diverges to w, residue 5.   (L'Hôpital in one step)
   ```

2. **Limits are read off the order sign.** `lim_{x→0}` of `(c, n)` is `0` if `n>0`,
   `w` (∞) if `n<0`, `c` if `n=0`. Asymptotics become a comparison of integers.

3. **Residues are just the coefficient at order −1.** Residue of `7/x` = coeff of
   `(7, -1)` = `7`. The type flags the residue case structurally (it's `w`), which is
   also the exact case the argument principle and contour integration care about.

4. **Pole/zero bookkeeping is order addition.** A triple zero times a simple pole:
   `(2, +3) * (5, -1) = (10, +2)` — net double zero, tracked for free. Useful for the
   argument principle (count = Σ orders) and Laurent leading-behaviour.

5. **Automatic differentiation is the order≤1 truncation of this.** Dual numbers
   (`ε² = 0`) keep grade 0 and 1 only; this type keeps the whole `ε^n` tower — a jet.
   Ties directly into the differential-programming / CAS substrate (see the CAS notes).

---

## 6. A third addition: the ℂ / phase embedding

Put the object in **polar-order coordinates**: `coeff` = radius, `order` = phase / π,
so a value is `r·e^{iπ·order}`. Then adding two points on the unit circle:

$$
e^{i\pi a} + e^{i\pi b} \;=\; 2\cos\!\Big(\tfrac{\pi(a-b)}{2}\Big)\; e^{\,i\pi(a+b)/2}
$$

Two factors, each meaningful:

- **phase of the sum = `(a+b)/2`** — the *arithmetic mean* of the phases,
- **magnitude = `2cos(π(a−b)/2)`** — depends only on the *difference*.

And the phase factor is exactly the **geometric mean** of the points,
`e^{iπ(a+b)/2} = √(z₁ z₂)`, so `z₁ + z₂ = 2cos(½·gap) · √(z₁z₂)`.

What multiplication and addition do to the order:

| operation | effect on order (phase) |
|-----------|-------------------------|
| `×`       | **orders add** — `e^{iπa}·e^{iπb} = e^{iπ(a+b)}` (the graded multiplication, exactly) |
| `+`       | **orders average** — mean `(a+b)/2`, weighted by `2cos(gap/2)` |

This `+` is a **true, total, associative addition** — because in these coordinates the
carrier is **ℂ** and `+` is ordinary complex addition. It resolves the "sphere wants
addition" wish by going to the *plane* (which, unlike `S²`, has total `+`).

The price — this is a **different object** from the harmonic field `(⊕, ×)`:

- `0` and `w` become the ordinary points `i` and `−i`;
- the true additive identity is the **origin**, a new point: `i + (−i) = 0`
  (the formula gives `2cos(π/2) = 0`);
- `order` must be **real-valued** (a phase), not the integer valuation;
- `+` leaves the unit circle (radius changes), so the carrier is all of ℂ.

So `0 ↔ i, w ↔ −i` was right — it's this addition, not the harmonic one.

---

## 7. The unifying dial: power-sum additions `S_p`

Harmonic `⊕` and ordinary `+` are not two unrelated additions — they are two rungs of
one family. For a real dial `p`, define the **conjugate addition**

$$
S_p(x,y) \;=\; (x^p + y^p)^{1/p} \;=\; \varphi^{-1}\!\big(\varphi(x)+\varphi(y)\big),
\qquad \varphi(x) = x^p .
$$

Because each `S_p` is ordinary `+` conjugated by the bijection `x ↦ x^p`, **every one is
associative and commutative**, with its own identity and inverses. The rungs:

| `p`        | `S_p`                    | name / where it shows up                    |
|------------|--------------------------|---------------------------------------------|
| `1`        | `x + y`                  | ordinary addition                           |
| `-1`       | `xy/(x+y)`               | **harmonic `⊕`** — resistors, lenses, `μ`   |
| `2`        | `√(x² + y²)`             | quadrature — RMS, variances, hypotenuse     |
| `→ +∞`     | `max(x, y)`              | tropical max-plus                           |
| `→ −∞`     | `min(x, y)`              | tropical min-plus                           |
| `φ = log`  | `x · y`                  | multiplication itself (the `p→0` *group*)   |

**Theorem 1 — multiplication distributes over every `S_p`.** One line:
`a·S_p(x,y) = (aᵖ(xᵖ+yᵖ))^{1/p} = S_p(ax, ay)`. So `(ℝ₊, S_p, ×)` is a semifield
isomorphic to `(ℝ₊, +, ×)` via `x ↦ xᵖ`, **for every `p`**. The harmonic field of §3 is
just `p = −1`.

**Theorem 2 — the sign of `p` picks the additive zero.** The identity `e` solves
`eᵖ = 0`, so `e = 0` when `p > 0` and `e = w (∞)` when `p < 0`. That is exactly why
harmonic addition (`p = −1`) has **`w` as its identity** and cancellation lands on `w`,
while ordinary addition (`p = +1`) has `0`. The `0`/`w` swap is the sign of `p`.

**The correction to the "sum vs. mean" picture.** The geometric mean — and the
phase-averaging of §6 — is **not** a rung of this dial:

- `S_p` *diverges* as `p → 0` (`(xᵖ+yᵖ)^{1/p} → ∞`); the geometric mean is the
  normalized *mean* `M_0 = √(xy)`, which is **not associative**;
- `√(xy) = √∘×` lives on the **multiplicative** side, not the addition dial;
- the §6 complex `+` is `p = 1` **on ℂ**, not `p = 0`. Its geometric-mean *appearance*
  is a polar-coordinate artifact of adding two equal-magnitude unit vectors — it is the
  multiplication showing through addition, not a new rung.

So the real picture is: **one multiplication `×`, and a whole dial `S_p` of additions it
distributes over** — with harmonic (`p=−1`) and ordinary (`p=+1`) both on it, `min`/`max`
at the ends, and quadrature at `p=2`. "Averaging the order" is `×` in disguise, off-dial.

> **Same dial, different operand.** The conjugator `φ(x) = x^p` that generates `S_p` is the
> *same* family of coordinate charts that generates the differential endofunctors in
> `differential-endofunctors.md` — there it is applied to a **difference operator** rather
> than to a binary `+`, giving `α = id →` ordinary derivative, `α = log →` multiplicative
> derivative, and (as an *implicit* member of the dial) the Abel/Schröder chart `→` the
> iteration generator. The `S_p` addition dial and the "three calculi" are one knob turned
> on two different operations. See that note's §2 lemma for the exact correspondence.

---

## 8. Honest edges

- The **exact field** `(⊕, ×)` lives at orders `{-1, 0, +1}` (`0`, finite, `w`). The
  unbounded ℤ orders are a **multiplicative** phenomenon (repeated poles/zeros) and the
  **calculus** grading — they are a group `ℝ* × ℤ`, but `⊕` does not act naturally on
  `|order| ≥ 2` (double-pole cancellation is where a single leading term is not enough,
  and you would be back to carrying a series tail). Keep `⊕` on the field; keep the
  tower for `×`, `∫`, `d/dx`.
- The three additions are **three different objects**: harmonic `⊕` (≅ ℝ, §3), the
  power-sum rungs `S_p` (each ≅ ℝ₊, §7), and complex `+` (carrier ℂ, §6). They share
  the *same* multiplication; they do not compose into one super-addition.
- `Log(...)` from `integ` is a value *outside* this algebra — the order-−1 residue is
  exactly the escape hatch into transcendentals.

---

## Appendix — the `1/x` triple role

Everything above meets at one function. `1/x` is simultaneously:

- the **conjugator** defining `⊕`  (`a ⊕ b = 1/(1/a + 1/b)`),
- the **integrand** whose integral is transcendental (`∫ dx/x = ln`),
- the **residue**, order `−1`, the element `w`.

Those are the same point seen from algebra, analysis, and geometry.
