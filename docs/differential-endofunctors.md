# Differential endofunctors: one primitive, three gauges

A family of "difference operators" that are usually taught as **three unrelated
calculi** — the ordinary derivative, the multiplicative (logarithmic) derivative, and
the theory of iteration — turn out to be one construction read through independent gauge
choices. This note pins the construction down, fills the lattice, shows the cross-terms
factor the chain rule, and maps each cell to its prior art (§6). The organizing thesis is
the house one: *nothing here is a new primitive; the "different calculi" are one object
with different structure unfrozen* (cf. gauge-relativity of impossibility).

---

## 1. The three starting operators

The two we began with, plus the one that opened the compositional axis:

```
additive         0f  =  f(x + 0) − f(x)          # domain shift, compare by −
multiplicative   wf  =  f(x + w) / f(x)          # domain shift, compare by ÷
compositional    ∘f  =  f(f(x))  − f(x)          # no domain shift; the "shift" is f itself
```

(The literal `+0` / `+w` name the null increment; the linearizations are `f′`, `f′/f`,
and the iteration generator `v` respectively.)

The first two vanish at zero increment — that is what makes them *differences*
(pure linearizations). The third does **not** vanish, because its two sides are
different iterates of `f`. That non-vanishing is the tell that iteration is a genuinely
different axis, not a re-flavouring of the first two.

---

## 2. The primitive and the single axis

Everything below is one object read through gauges:

```
the primitive  :  compare  f^{n+1}  against  f^{n}         (here n = 1: f∘f vs f)
```

A difference operator is then two independent choices layered on top:

1. **gauge** `α` — a coordinate chart on the output line. The *derivative in gauge `α`* is
   nothing but the ordinary derivative taken **after** recoordinatizing by `α`:

   ```
   D_α f (x)  =  (α ∘ f)′(x)  =  α′(f(x)) · f′(x)
   ```

   The "comparison group" language of the older draft is exactly this: comparing outputs
   in group `G` = differentiating in the chart `α` that linearizes `G`.
2. **injection site** for a probe increment `δ`, used to *linearize* the primitive:
   - **domain** `f(f(x+δ))` — δ is pulled back through the inner map (picks up `f′(x)`);
   - **value** `f(f(x)+δ)` — δ is injected after the inner `f` fired (skips `f′(x)`).

The whole content of this note is: **the "three calculi" are three choices of `α`, and the
injection site is a change of variables.** The chart is the axis; there are not really
three columns, there is one axis with points on it.

| gauge `α`            | `D_α f = (α∘f)′`     | classical name                     |
|----------------------|----------------------|------------------------------------|
| `id`                 | `f′`                 | ordinary / additive derivative     |
| `log`                | `f′ / f`             | multiplicative / log-derivative    |
| `(·)^p`              | `p f^{p-1} f′`       | bigeometric / `S_p` rung (§ below) |
| `α` solving `α∘f=α+1`| `1 / v`  (const rate)| **iteration** (Abel gauge, §5)     |

The abelian calculi use a chart you can **write down** (`id`, `log`, `x^p`); the
compositional calculus uses the chart that **solves a functional equation for this `f`**.
Same slot on the axis — the coordinate is just implicit rather than elementary.

> **Lemma (this axis *is* the `S_p` dial).** The power-sum addition
> `S_p(x,y) = (x^p + y^p)^{1/p}` of `projective-rational-algebra.md` §7 is ordinary `+`
> conjugated by `φ(x) = x^p`. That conjugator is exactly the chart `α = (·)^p` here:
> `D_α f = (x^p ∘ f)′ = p f^{p-1} f′`. So the addition dial and the derivative dial are the
> **same family of coordinates** seen on a binary operation vs. on a difference operator —
> `p=1 → id →` additive, `p→0 → log →` multiplicative. The two documents are dialing the
> same knob.

---

## 3. The lattice

Baselines (constant term, δ → 0):

```
additive  compositional difference   Δ∘f = f∘f − f      (discrete generator, v∘f to 1st order)
mult.     compositional ratio        R∘f = f∘f / f      (multiplicative step along the orbit)
```

Linearizations (coefficient of δ), by comparison group × injection site:

| injection ↓  /  compare → | additive `f∘f − f`            | multiplicative `f∘f / f`                    |
|---------------------------|-------------------------------|---------------------------------------------|
| **domain** `x+δ`          | `f′(f(x)) · f′(x)`  = `(f∘f)′` | `f′(f(x))f′(x) / f(f(x))`  = `(log f∘f)′`    |
| **value** `f(x)+δ`        | `f′(f(x))`                    | `f′(f(x)) / f(f(x))`  = `(log f)′ ∘ f`       |

So the four operators the exploration produced are:

```
f(f(x+0)) − f(x)   ≈  Δ∘f  +  f′(f(x))f′(x) · δ
f(f(x)+0) − f(x)   ≈  Δ∘f  +  f′(f(x))      · δ
f(f(x+0)) / f(x)   ≈  R∘f  · (1 + (log f∘f)′ · δ)
f(f(x)+0) / f(x)   ≈  R∘f  · (1 + (log f)′(f(x)) · δ)
```

None of the four is a *primitive*: each is `[baseline] ⊕_G [a linearization]`. What makes
them distinct is only **which slope** — i.e. which gauge — sits on the baseline.

---

## 4. The chain-rule factorization

Within each column, **domain-slope ÷ value-slope = `f′(x)`** — the inner link of the
chain. Injecting the probe in the domain pulls it back through the inner map; injecting
in value-space skips that link. So the two injection sites literally decompose the chain
rule for the second iterate:

```
(f∘f)′(x)  =  f′(f(x))  ·  f′(x)
              └ value ┘    └ domain ÷ value ┘
```

The additive row reads the decomposition as a product of slopes; the multiplicative row
reads it as a product of log-slopes. Same structural fact — the presence or absence of
the inner factor `f′(x)` — in two gauges.

The value-multiplicative cell, `(log f)′∘f`, is the one that matters for iteration near a
fixed point: it is the multiplier transported along the orbit (the quantity Schröder /
Koenigs linearization is built on). So value-space injection is the *natural* gauge for
the compositional axis, exactly as domain injection is natural for the additive one.

---

## 5. Collapsing the compositional column onto the axis

The compositional case *looks* like a third, non-abelian comparison group — compare
`f(f(x))` to `f(x)` through `f` itself, and its infinitesimal is a **vector field**, the
generator of the iteration semigroup `f^t`:

```
v(x) = ∂_t f^t(x) |_{t=0} ,      satisfying   v(f(x)) = f′(x) · v(x)     (Julia / Abel)
```

But it is **not** a new axis — it is the same "choose a chart `α`" slot, with `α` defined
implicitly. Ask for the chart in which `f` acts trivially:

```
Abel:      α ∘ f = α + 1        →  in the α-coordinate, f is translation-by-1
Schröder:  σ ∘ f = λ · σ        →  in the σ-coordinate, f is multiplication-by-λ
```

- In the **Abel gauge** (`α∘f = α+1`), iterating `f` *is* the additive column: `f^n` = "+n",
  and `Δ∘f = f∘f − f` becomes an ordinary finite difference. Applies at a **parabolic**
  fixed point, multiplier `λ = 1`.
- In the **Schröder / Koenigs gauge** (`σ∘f = λσ`), iterating `f` *is* the multiplicative
  column: `f^n` = "×λ^n". Applies near an **attracting/repelling** fixed point,
  multiplier `λ = f′(x*) ≠ 0, 1`.

The multiplier `λ` is exactly the quantity that fell out of the value-multiplicative cell
of §3, `(log f)′∘f` — that cell reads off the Schröder gauge. Sanity check that the
compositional derivative is trivial in its own gauge: `D_α f = (α∘f)′ = (α+1)′ = α′`, i.e.
constant rate 1 in orbit-time; equivalently `f′(x)/v(f(x)) = 1/v(x)` via the Julia
relation. In the linearizing coordinate, `f` is just "+1" — there is nothing left to
differentiate.

**So the "non-abelianness" is local fiction.** Near a fixed point the composition group is
*conjugate* to translation (`λ=1`) or scaling (`λ≠1`) — abelian after conjugation — and the
conjugating map **is** the gauge `α`. Koenigs/Schröder/Abel linearization is precisely the
change of coordinate that drops the compositional column onto one of the abelian ones. The
`S_p` dial (`projective-rational-algebra.md` §7) is the family of *explicit* charts; Abel
and Schröder are two more charts on the same axis that happen to be *implicit* (solutions
of a functional equation rather than formulas).

### The residue — where it genuinely refuses

The conjugacy is only **local** (a neighbourhood of one fixed point) and the gauge `α` need
not be globally single-valued. With several fixed points, or a parabolic germ, `α` develops
**monodromy / moduli**:

- for a parabolic germ (`λ=1`), the local Abel coordinate exists on petals but the
  transition maps between petals are the **Écalle–Voronin moduli** — an infinite-dimensional
  invariant with no closed form;
- globally the orbit structure is the **Julia set**, which no single chart flattens.

Those moduli are the irreducible non-abelian content the additive/multiplicative columns do
not carry. So the consolidation is **exact locally** and carries an **obstruction cocycle
globally**. In the house language: the compositional column is the additive column once you
unfreeze the coordinate, and the obstruction to doing so globally is the curvature you
uncover — the thing that was never really "impossible," only gauge-hidden.

---

## 6. Prior art, and what (if anything) is new

Each column is a known calculus; the *consolidation* is the interesting question.

**Additive column — the derivative and finite differences.** Newton/Leibniz; the
calculus of finite differences (Boole, Jordan), umbral calculus (Rota). The domain-shift
group is translation.

**Multiplicative column — multiplicative / geometric calculus.** The star:
- Volterra's **product integral** (1887) and the multiplicative derivative `f* = e^{f′/f}
  = lim (f(x+h)/f(x))^{1/h}` — Dollard & Friedman, *Product Integration*.
- **Non-Newtonian calculus** (Grossman & Katz, 1972) — geometric, bigeometric,
  anageometric calculi.
- Modern treatment: Bashirov, Kurpınar, Özyapıcı, *Multiplicative calculus and its
  applications* (2008).

**Compositional column — iteration theory.** Abel's, Schröder's, Böttcher's, and Julia's
functional equations; Koenigs linearization; continuous/fractional iteration and the
flow generator `v`. This is *not* usually called "a calculus" and lives in a different
literature (dynamical systems, functional equations) from the other two.

**The comparison-group dial is Kolmogorov–Nagumo / quasi-arithmetic.** Choosing the
codomain group as a conjugate `φ⁻¹(φ(a) + φ(b))` of `+` is exactly the quasi-arithmetic
(Kolmogorov–Nagumo–Aczél) construction, and it is the **same dial as `S_p` in
`projective-rational-algebra.md` §7** — `φ = id` gives additive, `φ = log` gives
multiplicative. So the "additive vs multiplicative comparison" knob is already a rung of
your own power-sum dial; this note just applies that dial to a *difference operator*
instead of to a binary `+`.

**The consolidation that already exists — and where it stops.** Grossman & Katz's
non-Newtonian framework carries **two** generators, one for the domain and one for the
range. That pair is precisely (comparison group) × (a domain-shift group), so the
additive/multiplicative *and* the `x+δ` vs `qx` distinctions are already unified there.
Add **q-calculus / quantum calculus** (Jackson's `D_q f = (f(qx)−f(x))/(qx−x)`; Kac &
Cheung) for the multiplicative *domain*-shift cells, and **time-scale calculus** (Hilger,
1988) for unifying the continuous and discrete additive cases, and the entire
additive-multiplicative block is well-trodden.

What none of those frameworks include is the **compositional column**. Non-Newtonian,
q-, and time-scale calculus all keep the *argument perturbation abelian* (translate or
scale `x`); none of them lets the perturbation be **`f` itself**, because composition is
non-abelian and is not a pointwise conjugate of `+`. Iteration theory handles that column
but in isolation, never as one axis of a lattice that also contains the derivative and the
log-derivative.

**So the likely-novel claim** is narrow and specific — not "a new calculus," but a single
axis (§2, §5):

> the derivative, the multiplicative derivative, and the iteration generator are three
> **choices of chart `α`** for one primitive (`f^{n+1}` vs `f^{n}`): `α = id`, `α = log`,
> and `α = ` the Abel/Schröder coordinate of `f`. The first two are *explicit* charts (the
> `S_p` / Kolmogorov–Nagumo dial); the third is the *implicit* chart that linearizes `f`.
> Domain vs value injection is the change-of-variables that factors the chain rule across
> all three.

Each ingredient is known in isolation — the explicit-chart dial is
Grossman–Katz/Kolmogorov–Nagumo; the implicit chart is Koenigs/Schröder/Abel linearization;
the global obstruction is Écalle–Voronin. What I have **not** found is a source that puts
them on one axis and says plainly *"iteration theory is the additive calculus in the chart
that solves Abel's equation, and the only difference from the multiplicative calculus is
`explicit` vs `implicit` coordinate."* That framing — plus **injection site = chain-rule
link** — is the part worth claiming. Literature to check before leaning on it:
*non-Newtonian calculus*, *product integral*, *quantum / q-calculus*, *Kolmogorov–Nagumo
(quasi-arithmetic) means*, *Abel/Schröder/Koenigs functional equations*, *Écalle–Voronin
moduli*, *iterative functional equations*.

---

## 7. Honest edges

- The compositional column's infinitesimal is a **vector field `v`, not a scalar slope**.
  The "÷ gives `f′(x)`" chain-rule factorization is exact for the *finite* operators, but the
  continuous generator obeys the Julia relation `v∘f = f′·v`, not a naive quotient. The
  chart-collapse of §5 is the reconciliation (`D_α f = 1/v` in the Abel gauge); keep the two
  levels — finite operator vs generator — distinct when reasoning informally.
- The chart collapse is **local**: a single `α` linearizes `f` near one fixed point only.
  Globally the gauge carries monodromy (Écalle–Voronin moduli, Julia sets), which is the
  genuine non-abelian residue and does **not** reduce to the additive/multiplicative axis.
- `f(f(x)+0)` degenerates to `f∘f` on its own (the `+0` evaporates); it only becomes a
  distinct operator once a comparison (`− f(x)` or `/ f(x)`) is attached. The primitive is
  the comparison, not the nesting.
- The value-injection gauge (`f(x)+δ`) is the unusual one relative to standard calculus,
  which only ever perturbs the domain. It is the natural gauge here precisely because it
  isolates the outer chain link — but that means these operators are **sensitivity/adjoint-
  flavoured**, closer to backprop than to a classical difference quotient.
```
