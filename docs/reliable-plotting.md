# Reliable plotting — interval arithmetic as the no-lie law at the pixel

Status: **DESIGN (2026-07-21).** Successor to docs/plotting.md's Slice 5 ("adaptive sampling +
derivative honesty"), now reframed: point sampling cannot be made honest for asymptotes, so the
honest renderer is *interval-arithmetic reliable graphing* (Tupper). Builds directly on the
`evalSafe` / `Undefined` substrate landed 2026-07-21 in `pontif.algebra`, which becomes the
point-wise special case of the interval evaluator this doc designs.

## The problem point sampling cannot solve

`plotLine` samples a curve at N points over its domain and connects them
([pontif-builtin-gui PlotExtension] `plotLine`). Two failures, both fatal for algebraic curves:

- **Sparse poles.** `1/x` over `[-10, 10]`: a sample on `x = 0` fails closed (now `Undefined`
  via `evalSafe`), and a sample *near* it returns a huge finite value that dominates the y-range.
  Breaking the polyline at the pole ("Slice 5 / plan C") handles this.
- **Dense poles — the case that kills sampling.** `tan(1/x)` near `x = 0` has **infinitely many**
  asymptotes in any neighbourhood of the origin. Between two adjacent samples there can be
  thousands of poles. *No finite sample count detects them* — you always alias, and no amount of
  adaptive refinement converges. Point sampling is not "incomplete" here; it is **blind in
  principle**.

The line-break instrument (plan C) is a genuine improvement for the sparse case and worthless for
the dense case. James's instinct — *"fill the space with a solid block once there's >1 asymptote
per pixel"* — is the correct answer, and it is a known algorithm.

## The technique: Tupper reliable graphing

Jeff Tupper, *"Reliable Two-Dimensional Graphing Methods for Mathematical Formulae with Two Free
Variables"* (SIGGRAPH 2001) — the algorithm behind **GrafEq**. Instead of evaluating `f` at a
*point*, evaluate it over a *region* (a pixel column's x-interval) using **interval arithmetic**,
which returns an **enclosure**: an interval guaranteed to contain every value `f` takes on that
column. Each column is then three-valued:

- **provably on** — the enclosure meets the viewport → paint the covered y-pixels;
- **provably off** — the enclosure misses the viewport → leave blank;
- **can't tell** — subdivide the column and recurse; if still unresolved at pixel resolution,
  **paint it** (the honest "there is detail here finer than a pixel").

The tighter modern refinement is **affine arithmetic** (de Figueiredo & Stolfi), which tracks
linear correlations between error terms and so avoids interval arithmetic's over-fattening. It is a
drop-in upgrade of the enclosure engine and is deliberately *out of scope for v1* (interval
arithmetic first; affine later if the fattening hurts).

## Why this is the no-lie law, not a heuristic

Interval arithmetic is **sound**: `evalInterval(f, [x_lo, x_hi])` returns an enclosure that
*provably* contains `{ f(x) : x ∈ [x_lo, x_hi] }`. It may be conservatively fat, but it can never
miss the curve nor place it where it isn't. That is exactly the conservation/no-lie discipline
applied to rasterization — **the painted region provably encloses the true graph.** And every
pathology falls out of the arithmetic instead of being special-cased:

| pathology | how the enclosure surfaces it | rendered as |
|---|---|---|
| **sparse pole** (`1/x`) | `Div` by an interval straddling `0` → `Unbounded` | a full (or spilled) column — a natural break between the finite columns on either side |
| **dense poles** (`tan(1/x)`) | the enclosure fills `[-∞, +∞]` for the whole neighbourhood | a solid block — James's "fill the block", *derived* |
| **domain gap** (`log` of a negative column) | no real value anywhere on the sub-interval → `Undefined` | blank (a true gap) |
| **tame stretch** | thin bounded enclosure | a 1-px-thick curve |

The pole is found by the *arithmetic*, not by a root-finder — division by an interval containing
zero is unbounded, and that is the pole, reliably, with no solving.

## The substrate: `evalInterval` over `AlgExpr`

A new evaluator over the same AST — a sibling of `eval` / `evalSafe` / `differentiate`, and the
interval-valued generalisation of the point-valued `evalSafe`. It is a native in
`pontif.algebra` (like `eval`), returning a three-way union:

```
struct Interval(lo:Decimal, hi:Decimal)     # a bounded enclosure, lo <= hi
struct Unbounded()                          # spills to ±∞ in at least one direction (a pole/dense)
# Undefined() already exists — no real value anywhere on the sub-interval

function evalInterval(e:AlgExpr, lo:Decimal, hi:Decimal):[Interval | Unbounded | Undefined] -> …
```

The rules enclose `{ f(x) : x ∈ [lo, hi] }`. `Param` returns the input column `[lo, hi]`; any
operand that is `Unbounded` propagates `Unbounded`; a fully out-of-domain operand propagates
`Undefined`.

```
Const(c)   → [c, c]
Param      → [lo, hi]                                   # the column
Add(a,b)   → [a.lo+b.lo, a.hi+b.hi]
Sub(a,b)   → [a.lo-b.hi, a.hi-b.lo]
Mul(a,b)   → [min, max] of the four endpoint products
Div(a,b)   → 0 ∈ b  ⇒  Unbounded (THE pole)            # else multiply a by the reciprocal of b
Pow(b,n)   → integer n: even & 0∈b ⇒ [0, max(|lo|,|hi|)^n]; else endpoint powers (odd is monotone)
             rational p/q: even root of a negative sub-part ⇒ Undefined / partial (conservative)
Sin,Cos    → width ≥ 2π ⇒ [-1,1]; else endpoints ∪ any enclosed extremum (π/2+kπ)
Tan(a)     → falls out of Sin/Cos: Cos straddles 0 ⇒ Unbounded
Exp(a)     → [exp(lo), exp(hi)]                         # monotone
Log(a)     → hi ≤ 0 ⇒ Undefined; 0 ∈ (lo,hi] ⇒ Unbounded-below; else [log(lo), log(hi)]
```

The fiddly parts are the **periodic extrema** (sin/cos: an interval wider than a quarter-period may
contain a max/min the endpoints miss) and **partial-domain** enclosures (`sqrt` over a column that
crosses `0`). v1 may be conservative there (widen to a sound superset) — soundness is mandatory,
tightness is not. Transcendental *endpoints* reuse the existing double-backed evaluation
(`evalSafe`'s honesty about significant digits carries over); interval endpoints must **round
outward** (lo down, hi up) to stay a true enclosure — the one place we deliberately widen rather
than round-to-nearest.

## The renderer: one primitive subsumes line, break, and block

The elegant payoff. A reliable render does not draw a polyline at all — it computes, for each of
the `W` pixel columns, the y-enclosure clamped to the viewport, and emits a **per-column vertical
span**. Three cases collapse into one representation:

- **thin span** → the curve (a 1-px-tall bar; adjacent columns' bars overlap into a connected line);
- **empty column** → a break (the pole/gap between two finite stretches — plan C's line-break, *free*);
- **full column** → a solid block (the dense-asymptote fill).

So the `renderCurve(xs, ys)` contract is replaced (for algebraic curves) by an enclosure contract:

```
# Native: paint per-column y-spans over a fixed viewport. `spans` is one {ylo, yhi, kind}
# per pixel column (kind ∈ {curve, full, empty}); the native rasterises vertical bars.
function renderReliable(cfg:_, spans:_):Stream[String] -> {}
```

Everything upstream of the native stays Pontif-side and honest: choose the viewport (auto-frame),
map over the `W` column indices computing `evalInterval` per column, classify, and hand the native
only primitives — the same boundary discipline as the rest of `pontif.plot`. Adaptive subdivision
(Tupper's recurse-on-"can't tell") is a Pontif-side refinement of the per-column step and can land
after the flat version.

`pontif.plot` gains `requires pontif.algebra.{Interval, Unbounded, Undefined, evalInterval}`
(and `pontif.poly.{Expression}` for the entry point). Both are default-installed, pure-JDK.

## How the pieces compose

- `evalSafe` (landed) = `evalInterval` at a degenerate column `[x, x]` — the point-wise special case.
- **Plan C (polyline break)** is *subsumed*: the column-span renderer produces breaks for free, so
  there is no separate break mechanism to build for continuous algebraic curves.
- **Point sampling** (`plotLine`) stays useful for cheap previews and for discrete/`Cloud`/`Scatter`
  data, where a connecting line through given points is the honest picture. It is not the path for
  a continuous algebraic curve that may have poles.

## Implementation slices

1. **`evalInterval` substrate** — the interval evaluator over `AlgExpr` + `Interval` / `Unbounded`
   structs, native in `pontif.algebra`, with tests: `1/x` over a column straddling 0 → `Unbounded`;
   a tame column → a correct enclosure; `log` of a negative column → `Undefined`; `sin` over a
   wide column → `[-1,1]`. Round-outward endpoints asserted. **No renderer yet** — this is the
   sound core, verifiable in pure tests (the dual of how `evalSafe` landed).
2. **Flat reliable renderer** — per-column enclosure → `{ylo, yhi, kind}` spans → `renderReliable`
   native in `DasumBridge` (vertical-bar rasteriser). Auto-frame the viewport. `1/x` breaks,
   `tan(1/x)` near 0 fills, a parabola draws clean. Pinned headlessly like the other plot slices
   (`DasumBridge.buildReliableSpans`).
3. **Adaptive subdivision** — Tupper's recurse-on-"can't tell": subdivide a fat/tall column into
   sub-columns to sharpen before falling back to a fill. Turns conservative blocks into crisp curves
   where the detail is actually resolvable.
4. **The entry point** — `plotReliable(Expression)` (name TBD), and folding it into the
   `chart(cfg, {…})` composition so a reliable curve layers with sampled ones.
5. **(Later) affine arithmetic** — swap the enclosure engine for affine forms to cut over-fattening;
   pure substrate change behind `evalInterval`, no renderer impact.

## Resolutions (2026-07-21)

Ratified with James after the design draft. Each decision favours soundness and modular seams.

- **Q1 — Span representation → list-of-spans contract from the start; singleton until slice 3.**
  The native `renderReliable` takes a *list* of `{lo, hi}` spans per column immediately, but the
  producer emits a one-element list in slices 1–2 (where `evalInterval` over a whole column yields
  exactly one enclosure — multi-branch only appears once subdivision splits a column). Merging
  sub-enclosures to a bounding span is sound but paints the provably-empty gap between branches;
  the list contract costs nothing now and never needs re-cutting the native boundary later (the
  "IR is the stable seam" instinct, applied to the render contract).

- **Q2 — `Unbounded` vs. tall-finite → distinct, tag the fill; the detection guarantee is
  no-false-*negatives*, not no-false-positives.** Every *real* asymptote is caught: a genuine pole
  puts `0` in the denominator's true range, and soundness forces the enclosure to contain it, so
  `Div` / `Tan` / `Log` flag `Unbounded` without fail. The converse leaks — the **dependency
  problem** (a variable appearing twice, e.g. `x/x`, a removable singularity) can flag `Unbounded`
  where no real pole exists, a false *positive*. That is the correct bias for a no-lie plotter:
  never draw confidently through a real singularity; at worst over-warn. So tag each full column
  `full-pole` (from `Unbounded`) vs. `full-clamped` (finite, ran off-viewport): the fill treats them
  alike, but only `full-pole` — and only after subdivision has failed to refute it — earns a future
  asymptote overlay (the dotted vertical). Affine arithmetic (slice 5) removes most false positives
  by tracking the correlations interval arithmetic drops.

- **Q3 — Partial domain → conservatively widen in v1; subdivision sharpens it for free. Correctness
  rule: a *partially*-defined column is an `Interval` of its defined part; only a *fully*
  out-of-domain column is `Undefined`.** The correctness half dominates: returning `Undefined` for a
  column that is merely *partly* out of domain (`sqrt` over `[-1, 1]`) would punch a false gap into a
  curve that genuinely exists on `[0, 1]`. So enclose the defined part and keep it a span. The only
  cost of skipping exact partial-domain math is a handful of over-painted pixels at a domain edge
  until slice 3's subdivision splits the column at the boundary — no missed curve, no false gap, no
  perf cost. Not worth bespoke per-node domain logic now.

- **Q4 — Entry point → a distinct `Expression`-keyed shape, not `Curve2D`; `plotExpr(e)` + a
  `chart` layer `expr(e)`.** `Curve2D.at(x)` is a *point* projection — an enclosure cannot be
  obtained through it without sampling, the very thing that fails. Interval rendering requires the
  AST, so the shape must carry an `Expression` / be `Algebraic`; forcing it into `Curve2D` would make
  the trait promise reliability it can't deliver for an arbitrary `at` body. This mirrors the
  existing gate — `.ast` exists only on proven-`Algebraic` references. On-ramp:
  `plotExpr(Expression($f[Decimal].ast))`, or a convenience `plot($f[Decimal])`.

## Auto-windowing is a swappable policy, not baked in

Choosing the viewport (the x- and y-range the plot frames) is as much preference as mathematics, so
it is isolated as a **framing policy**: a function `analysis → Viewport`, decoupled from both the
evaluator and the rasteriser. Swapping the "feel" of auto-framing never touches interval evaluation
or rendering. The default policy: x-range spans the interesting features (intercepts, extrema from
`differentiate`, detected poles) with padding, falling back to `[-10, 10]` when there are none;
y-range from a robust quantile of the finite column enclosures, *excluding* `full-pole` columns so a
pole can't set the scale. Alternative policies (fixed window, square aspect, symmetric-about-origin)
drop in without touching anything else.

## Module boundaries — high cohesion, loose coupling

Each unit is independently testable and independently swappable (James's directive: maximum
feature-agility). Dependencies point one way, evaluator → renderer, never back.

1. **Interval algebra** — an `Interval` value + its arithmetic (`+ − × ÷ ^`, trig, exp, log,
   round-outward endpoints). Knows nothing of the AST or plotting; the single place
   `Unbounded` / `Undefined` propagation lives.
2. **`evalInterval`** — the `AlgExpr` walk onto (1). Depends on the AST + (1) only; sibling of
   `eval` / `evalSafe`; no plot deps.
3. **Classification** — `(enclosure, viewport) → column kind` (`curve` spans | `empty` |
   `full-pole` | `full-clamped`). A pure function; no evaluator or renderer deps.
4. **Framing policy** — `analysis → Viewport` (above). Swappable; no renderer deps.
5. **Rasteriser (native)** — spans → pixels. Knows nothing of algebra; the stable contract is the
   list-of-spans-plus-kind from Q1 / Q2.
6. **Entry (`plotExpr`)** — the only unit that wires 2 → 4 → 3 → 5 over an `Expression`.

The affine-arithmetic upgrade (slice 5) replaces the *representation* inside (1) behind the same
interface; (2)–(6) are untouched. That is the whole point of the seams.

## Progress

- **Slice 1 — `evalInterval` (LANDED 2026-07-22, `ba22576`).** Interval-algebra unit + AST walk +
  `Interval` / `Unbounded` structs, native in `pontif.algebra`, outward-rounded endpoints,
  `Div`-straddling-0 → `Unbounded`. Verified by `AlgebraIntervalTest` (8) — pole, outward rounding,
  exactness, partial-domain `sqrt`, periodic saturation, domain edges. 66 algebra/poly tests green.
- **Slice 2 — flat reliable renderer (LANDED 2026-07-22).** Pontif-side `classifyColumn` +
  `columnIndices` (256) + `plotExpr`; native `renderReliable` in `DasumBridge` builds one vertical
  `Series` per column (pole → full-height block, empty → break, curve → clamped segment) over a
  **robust** (2nd–98th percentile) y-range so a near-pole spike can't flatten the plot. Verified
  headlessly by `PlotExtensionTest.plotExpr_reliableColumns_detectPoleAndBuildSpans` (whole pipeline,
  no window). Window render is manual, like the other plot snippets.
  - **Sparse-vs-dense pole rendering (LANDED 2026-07-22, Q2 partial).** The first window prototype
    (`(2x+3)/(x²+3x−4)`, poles at −4 and 1) exposed that painting *every* pole column full-height
    draws a solid line across an isolated asymptote — the exact lie the design forbids. Fix: a run
    of `< DENSE_POLE_RUN` (4) consecutive `Unbounded` columns is an isolated pole → **break**; a
    longer run is unresolvable dense detail → **fill** (the block). So a lone asymptote now reads as
    a clean break, while `tan(1/x)`-style density still fills. (`DasumBridge.densePoleRuns`; pinned
    by `PlotExtensionTest` isolated-break / dense-fill cases.) The proven-asymptote dotted overlay
    remains deferred to 4b.
  - **Connected-polyline rendering (LANDED 2026-07-22).** The prototype also showed the original
    one-vertical-segment-per-column style was unreadable — stacked short verticals read as a
    dashed / "doubled" line, not a curve. Now each maximal run of curve columns is drawn as ONE
    connected polyline through the column midpoints, and a break (empty column or isolated pole)
    ends it so the curve resumes fresh on the far side. The reliability is unchanged (poles still
    found by interval arithmetic, still broken, dense runs still filled); the midpoint is just the
    readable representative of each column's enclosure. Showing the enclosure *width* as a ribbon is
    a possible later enhancement; the midpoint polyline is the default.
  - **Edge clipping (LANDED 2026-07-22).** The 2nd prototype (`1/(x²−1)`) showed horizontal "serif"
    ticks where a near-pole run of off-screen midpoints piled up along the frame edge (an artifact
    of clamping each point into the viewport). Fixed by *clipping* rather than clamping: a segment
    crossing the top/bottom edge is cut at the interpolated crossing point (correct slope) and the
    polyline breaks there, so a spike reaches the edge cleanly and off-screen stretches draw
    nothing. (`DasumBridge.clipRunToBand`.)
  - **Extend to the edge at a proven pole (LANDED 2026-07-22).** The 3rd prototype
    (`1/((x²+3x−4)(2x+3)(x+5))`, four poles) showed branches ending in mid-air short of the frame
    when the blow-up fell between the last sample and the pole column. Fix: a run terminated by a
    POLE (an `Unbounded` column — interval-proven blow-up, so the curve *does* keep going) is
    aimed off the frame edge (direction = the sign of the adjacent column's enclosure) and the clip
    draws it to the boundary; a run terminated by an EMPTY column (`Undefined` = a domain edge,
    where the curve genuinely stops) is left as a plain break, not extended. Pinned by
    `PlotExtensionTest` (pole-ended run reaches the edge; empty-ended run stops at the data).
- **`plotExpr` takes a bare `AlgExpr`, not `Expression` (adjustment to Q4).** Making `pontif.plot`
  `requires pontif.poly` (for `Expression`) exposed a **compiler bug**: under that *transitive*
  double-import (a program → `pontif.plot` → both `pontif.algebra` and `pontif.poly`, which itself
  re-imports `pontif.algebra`'s closed `AlgExpr` union), `pontif.poly`'s `differentiate` fails to
  compile — the pattern variable `a` in `[Exp(a)] -> …` is mis-typed as `Decimal[==64.0]` and
  rejected as disjoint from the union, even though `pontif.poly` compiles cleanly standalone
  (`PolynomialModuleTest`, 13 green). So `plotExpr` takes the bare `AlgExpr` (the real requirement;
  `Expression` is only sugar over it) and `pontif.plot` requires only `pontif.algebra`. The
  `Expression` convenience overload is **deferred** until the transitive-union linking bug is fixed
  (a recursive-union / module-linking issue, adjacent to the `ba22576` work). On-ramp meanwhile:
  `plotExpr($f[Decimal].ast)`, a hand-built tree, or `plotExpr(myExpr.ast)`.
- **Slice 4a — numeric auto-framing (LANDED 2026-07-22).** `plotExpr(e)` with no domain now
  chooses its x-window by scanning the probe range `[-32, 32]` with `evalInterval`: a column is a
  FEATURE if it's `Unbounded` (a pole) or its midpoint sign differs from the previous real column's
  (a root crossing), and the window is the feature span padded (fallback `[-10, 10]`). Uses only
  `pontif.algebra` — no `pontif.poly`, so it **sidesteps the transitive-import bug**, and it is
  strictly more robust than roots-based framing: it brackets irrational and transcendental features
  the rational root-finder can't reach. Verified end-to-end by `PlotExtensionTest`
  (`plotExpr(x²+x−6)` brackets roots −3 and 2; a constant falls back). **This closes the
  "specify a function → nicely-framed reliable plot" loop.**
- **Supplemental annotation layers (LANDED 2026-07-22).** Expression-driven overlays composited by
  the existing `chart(cfg, {…})` layer idiom — the reliable curve and its annotations are layer
  VALUES, exactly like the sampled `curve` layers. `expr(e)` is the interval-enclosure curve as a
  chart layer; `zeros(e)` / `optima(e)` / `asymptotes(e)` / `intersections(e, g)` add markers+labels
  (roots, local min/max, crossings) and half-opacity vertical asymptote lines. Every feature is found
  by a BOUNDED numeric scan over `evalInterval`/degenerate-column point samples — each test LOCAL to a
  probe column (fragment-friendly, no recursion), so detection stays in `pontif.algebra` and sidesteps
  the transitive `pontif.poly` import bug that still blocks symbolic `differentiate`. Extrema use a
  discrete-slope sign flip (no derivative), zeros/intersections a sign-crossing bracket biased so a
  root landing exactly on a probe point is caught once, asymptotes an ISOLATED interval-proven pole
  (a dense run is left to the reliable layer's block fill). **Failsafe:** each layer's primitive count
  is capped native-side (`DasumBridge.FEATURE_CAP`); an overflowing layer (a wildly oscillating curve
  — `sin(1/x)` near 0) is SUPPRESSED with a `System.err` notice rather than cluttering the plot.
  Verified headlessly by `PlotExtensionTest` (zeros of x²−4, optima of x³−3x, asymptotes of
  1/(x²−1), intersections of x² and x+2, and the cap-suppression + log). Window render manual:
  `pontif-builtin-gui/examples/annotate.ptf`. The **Auto-Plotted Function** welcome sample
  (`pontif-playground/.../welcome/samples/auto-plot.ptf`) now demonstrates the composition:
  `chart({…}, {expr(f), asymptotes(f), optima(f)})` over `1/(x²−1)`.
  - **No spurious features at asymptotes (fixed 2026-07-22).** The first pass reported false
    zeros / extrema *straddling* a pole — the finite samples either side of an asymptote fake a
    sign flip (a false root) or a slope reversal (a false extremum) across the discontinuity (e.g.
    `1/(x²−1)` produced four bogus extrema hugging x = ±1). Fix: a candidate is rejected when its
    bracketing interval is `Unbounded` (`spanHasPole` — `evalInterval` over the span straddles a
    pole), so a feature is kept only where the curve is actually continuous there. Pinned by
    `PlotExtensionTest.featureLayers_rejectSpuriousFeaturesStraddlingAnAsymptote`.
- **Still ahead:** slice 3 (adaptive subdivision — sharpens fat/tall columns, gives partial-domain
  tightness for free), slice 4b (EXACT feature marking — dotted asymptotes, labeled intercepts —
  using `pontif.poly`'s `roots()`, once the transitive-import bug is fixed; the numeric framing
  above needs neither), slice 5 (affine arithmetic).
- **Auto-frame scan is a FOLD, not deep recursion (fixed 2026-07-22).** The `autoFrame` probe scan
  was a 256-deep hand recursion (`scanFrom`) that overflowed the interpreter stack for a function
  whose features sit at *every* depth — `tan(x)`, with an asymptote in nearly every probe column, hit
  heavy min/max work unwinding on an already-deep stack. Rewritten as a single `fold` over the probe
  range (O(1) stack). Two Pontif-shape gotchas the rewrite navigated: fold over a *refined*-element
  stream (`Stream[Int:0<=@<256]`) demands an undecidable per-element domain match, so the fold runs
  over an unrefined `indexRange(0,255)` stream; and destructuring the fold's accumulator inline
  (`let [{…}] = fold(…)._1`) trips the totality checker, so the accumulator is returned from one
  function and destructured as a plain parameter in another. Pinned by `PlotExtensionTest`
  (`plotExpr_autoFrame_tanWithPolesAtEveryDepth_doesNotOverflowTheStack`).

**Root-finding (`pontif.poly`, the exact-intercept CAS — independent of framing):** `integerScale`
(denominator clearing) and `integerRoots` (RRT band + eval-test) LANDED 2026-07-22; rational `p/q`
roots, deflation, quadratic residual, and the re-expansion verifier remain. Not on the plotting
critical path — numeric framing (4a) supersedes it there — but the exact roots feed 4b's marking.
