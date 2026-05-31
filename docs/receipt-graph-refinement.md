# Receipt-Graph Refinement

**Custom issuers as type-checked refinement of the proof graph.**

> **Status: architectural direction, not yet implemented.** Recorded
> 2026-05-31. Describes how Pontif's receipt-graph subsystem can become
> the surface for user-extensible proof systems while keeping the trust
> base unchanged.

---

## What this is

Pontif's built-in proof system can discharge a useful but bounded fragment
of integer arithmetic obligations — linear bounds, sign reasoning,
inductive proofs through recursive back-references. Plenty for refinement
predicates like `[Int:@>=1]` and recurrences like factorial, but bounded
when obligations involve products of varying terms, polynomial shapes,
or anything genuinely nonlinear.

This document describes a direction where users can extend Pontif's
proof reach by writing custom issuers *in Pontif itself*, whose
correctness is verified by the same compile-time machinery that gates
ordinary Pontif functions. No new trust mechanism, no plugin protocol,
no reflective metaprogramming. The trust kernel stays exactly as small
as it is today; new proof power comes from the kernel checking *more
elaborate refinement trees*, not from accepting unverified procedures.

The headline payoff: **nonlinear obligations decomposed piecewise into
linear leaves become first-class, fully traceable, kernel-verified.**

---

## The receipt graph, refreshed

Pontif compiles every function body into a *receipt graph* — a structured
data form recording the inductive shape of the function's computation:

- Each function declaration becomes a **node**.
- Each match arm (or unconditional body) becomes a **branch** under that
  node, carrying an optional **guard** (the arm's pattern predicate, in
  symbolic form).
- Each sub-call inside a branch becomes a **CallRef** with a fresh result
  variable; recursive calls to the same function are **back-references**
  to the same node, carrying the function's own postcondition as an
  inductive hypothesis automatically.
- The body's value equation (`r_0 = body`) becomes an **initial
  receipt** — a fact recorded against the result variable.

The receipt graph is produced by a deterministic component called the
**Drafter**. The Drafter is small, Java-trusted, and does no reasoning —
only transcription. The same source always produces the same graph.

A separate component, the **built-in issuer**, walks the graph and
attempts to discharge each branch's *obligation* (the function's return
refinement applied to its result variable). For each branch it gathers
the **path facts** — parameter refinements, branch guard, back-reference
inductive hypotheses, non-defining body receipts — and calls a discharge
engine. When discharge succeeds, the issuer emits a **closing receipt**
referencing that branch.

The **notary**, also Java-trusted, verifies receipts by attempted
refutation: it negates the conclusion, substitutes the body definition,
and tries to discharge the negation. If the negation discharges, the
receipt is refuted; otherwise accepted.

The receipt graph plus the closing receipts form a complete, reviewable
proof artifact. The Pontif compiler currently emits these to
`target/receipt-graphs/*.receipts.txt` for inspection. See
`docs/receipt-graph.md` for the original design and worked factorial
example.

---

## What the proof system handles today

The built-in issuer's discharge engine is `BoundAnalysis` — a hybrid
linear-bound + sign engine over the integer domain. It decides:

- **Linear integer thresholds.** `[Int:@>=k]`, `[Int:@>k]`, equalities
  and inequalities against constants. The `>0`-vs-`>1` cliff that
  sign-only analysis suffered from is gone.
- **Linear combinations.** `2x + 3 >= 5` from `x >= 1`.
- **Products via opaque-atom sign reasoning.** `x*x >= 0` (positive or
  zero × positive or zero = non-negative); `x*y >= 1` from `x >= 1, y >= 1`.
- **Inductive proofs via back-references.** Factorial's recursive branch
  closes because the back-reference brings the function's own
  postcondition into scope as a hypothesis.
- **Reflexive equality**, the integer-strictness bridge, range refinements.

And via the recent `DefaultRules` slice, the compile-time function-check
path (`FunctionCheck.verifyDefinition`) gets the same capability,
so refinements like `[Int:@>=1]` are now provable at compile time, not
just in the receipt-graph artifact.

What it doesn't handle:

- **Products of varying terms with magnitude content.** `x*y >= 6` from
  `x >= 2, y >= 3` — sign reasoning gives only `x*y >= 1`. The product
  magnitude `>=6` requires interval-multiplying the factors, which the
  engine doesn't currently do.
- **Polynomial inequalities.** `(x-3)*(x+5) >= -16` for any integer x.
  The product is treated as a single opaque atom; the engine has no way
  to reason about its global lower bound.
- **Multi-atom linear hypotheses.** `x + y > 0` bounds neither x nor y
  alone; deciding goals from such a hypothesis needs a real linear
  solver (Fourier–Motzkin / Presburger).
- Anything nonlinear, transcendental, quantified, or outside the
  decidable fragment of integer arithmetic.

The TODO has filed all of these under "Deep work — oracle territory" —
they're not Pontif's burden to ship directly. The format of the receipt
graph is the contract; oracle modules (Z3, custom solvers, AI provers)
plug in via that contract.

---

## The gap

Consider:

```
function isSparse(x:Int):[Int:@>=-16] -> (x-3)*(x+5)
```

This is a function over the integers. Its return refinement claims the
output is `>= -16`. Is it provable?

By inspection, yes — the polynomial `(x-3)*(x+5)` has its minimum at the
midpoint `x = -1`, where it evaluates to `(-4)*4 = -16`. So `>= -16` is
tight but true for every integer.

By the current built-in issuer, no — it can't decide this. The product
`(x-3)*(x+5)` is nonlinear, and `BoundAnalysis` treats it as a single
opaque atom whose only known property is its sign (from `SignAnalysis`).
The sign of the product is `TOP` (could be positive, negative, or zero
depending on `x`), so no bound is available. The obligation reaches
`NOT DISCHARGED`.

This is the canonical shape of obligations that should be provable
piecewise. The function isn't truly nonlinear in a problematic sense —
each region of the integer line admits a linear bound. The proof just
needs to *say so*, region by region.

---

## The idea: branch refinement

A **custom issuer** is a Pontif function that takes a branch of the
receipt graph and produces a list of finer-grained sub-branches:

```
function refine(b: Branch): List<Branch>
```

The output sub-branches collectively cover the same obligation as the
input branch, but with narrower hypotheses on each. A custom issuer
that produces N sub-branches from one branch is performing **case
analysis** on the proof.

For the construction to be valid — for `refine`'s output to be
trustworthy — three properties must hold:

1. **Coverage.** The union of the sub-branch hypotheses equals the
   original branch's hypothesis. No case is missed.
2. **Disjointness.** The pairwise intersection of sub-branch hypotheses
   is unsatisfiable. No case is double-counted.
3. **Per-leaf discharge.** Each sub-branch's obligation closes under
   its narrower hypothesis — either directly via the built-in engine,
   or by being further refined by another custom issuer (recursively).

These three properties are exactly what Pontif's existing compile-time
kernel already decides for ordinary user code:

- **Coverage** is **match totality** — the same property that lets
  `match n { [@==0] -> ...; [@>0] -> ...; [@<0] -> ... }` be accepted
  as exhaustive. The `PredicateArithmetic` kernel decides
  union-covers-domain for the integer and boolean fragments.

- **Disjointness** is **overload-overlap rejection** — the same property
  that forbids two function overloads from claiming overlapping
  parameter regions. The kernel decides pairwise satisfiability of
  refinement predicates and rejects irreducible overlap.

- **Per-leaf discharge** is **refinement satisfaction** — the same
  property that verifies a function body's value satisfies its
  declared return refinement.

So a custom issuer is valid iff Pontif type-checks it. The kernel that
verifies ordinary Pontif functions verifies custom issuers identically.

The validity-encoding shape, in Pontif terms:

```
function refine(b: Branch): [List<Branch>:
    covers(@, b) &
    disjoint(@) &
    all-recursively-dischargeable(@)]
```

The refinement on the return value is the precondition for constructing
it. If Pontif accepts the issuer at compile time, the output is correct
by construction.

---

## Worked example: `(x-3)*(x+5) >= -16`

Suppose the Drafter has produced a receipt graph for `isSparse(x:Int)`
with a single unconditional branch:

```
isSparse(x_0: Int) : r_0: [Int: @ >= -16]
  branch (unconditional):
    receipt: r_0 == (x_0 - 3) * (x_0 + 5)
```

The obligation is `r_0 >= -16`, which after substitution becomes
`(x_0 - 3) * (x_0 + 5) >= -16`. The built-in issuer would emit `NOT
DISCHARGED` here.

A custom issuer refines the unconditional branch into three sub-branches
by case-splitting on `x_0`:

```
branch A [x_0 >= 3]:
    goal: (x_0 - 3) * (x_0 + 5) >= -16

branch B [-5 <= x_0 <= 2]:
    goal: (x_0 - 3) * (x_0 + 5) >= -16

branch C [x_0 <= -6]:
    goal: (x_0 - 3) * (x_0 + 5) >= -16
```

(The exact split boundaries are an implementation choice; what matters
is that the three guards partition the integers exhaustively without
overlap.)

The kernel verifies:

- **Coverage**: `x_0 >= 3 ∨ -5 <= x_0 <= 2 ∨ x_0 <= -6` covers all
  integers. `PredicateArithmetic.complement` decides this.
- **Disjointness**: each pair is unsatisfiable. `PredicateArithmetic
  .satisfiable(p1 ∧ p2)` returns "no" for each pair.

Now each leaf is handled piecewise. Branches A and C close via sign
reasoning:

- **Branch A** [`x_0 >= 3`]: `(x_0 - 3) >= 0` (linear bound) and
  `(x_0 + 5) >= 8` (linear bound), so the product is `>= 0`, which is
  `>= -16`. `BoundAnalysis` handles both linear bounds; the
  non-negative product follows by sign.

- **Branch C** [`x_0 <= -6`]: `(x_0 - 3) <= -9` and `(x_0 + 5) <= -1`,
  both negative. The product of two negatives is positive, hence `>= 0
  >= -16`. Same machinery.

**Branch B** is the interesting case — the minimum lives here. The
custom issuer refines B into two further sub-branches:

```
branch B [-5 <= x_0 <= 2] refines into:

  branch B.1 [-5 <= x_0 <= -1]:
      goal: (x_0 - 3) * (x_0 + 5) >= -16

  branch B.2 [-1 <= x_0 <= 2]:
      goal: (x_0 - 3) * (x_0 + 5) >= -16
```

(Note: `x_0 == -1` is in both. The disjointness check would catch this
unless the issuer is careful — say, splitting as `-5 <= x_0 <= -1` and
`0 <= x_0 <= 2`, picking up the gap at `x_0 == -1` in either branch.
Actual refinements have to be airtight; the kernel will catch sloppy
ones.)

Each of B.1 and B.2 is a bounded interval on which the polynomial is
piecewise monotonic, with known endpoint values. The issuer can either
refine each further down to singleton cases (`x_0 == -5`, `x_0 == -4`,
…) — which is finite and decidable since the interval is bounded — or
recognize the monotonicity pattern and discharge via interval reasoning.

Either way, **every leaf closes under the existing built-in discharge
engine**, with no new trusted code.

The result is a refined receipt graph where the original single branch
has been replaced by a tree of sub-branches, each kernel-verified for
coverage and disjointness, each leaf kernel-verified for discharge. The
overall obligation is proven by exhaustive case analysis — entirely in
data that Pontif can pattern-match on, inspect, replay, and audit.

---

## Why trust doesn't move

The trust base in Pontif is small and explicit:

- The **Drafter** — deterministic, no reasoning, transcribes source to
  graph.
- The **Notary** — refutation-only, refuses to confirm validity; rejects
  receipts whose negation discharges.
- The **kernel decision procedures** — `BoundAnalysis`,
  `PredicateArithmetic`, `SignAnalysis`, `Refinements`. Small, audited,
  Java-implemented.

A custom issuer adds **none** of these. It's a Pontif function whose
outputs are values gated by refinement at construction time. If the
kernel decides the refinement holds, the value can be constructed; if
not, it can't. The custom issuer participates in the proof system the
same way any other user function participates in the type system: by
being checked, not by being trusted.

This is the key property: **custom issuers extend reach without
extending trust.** A user who writes a buggy issuer produces an issuer
that doesn't typecheck. They cannot produce an issuer that emits
incorrect receipts; the kernel won't let the value be constructed.

The notary, when it later verifies a closing receipt, doesn't need to
inspect the issuer's logic. It just checks that the receipt is
well-formed and attempts to refute it. If the issuer was wrong, the
notary will refute (because the kernel decisions inside the issuer
would have failed too — the same kernel is in use). The trust loop
closes cleanly.

---

## Recursive refinement and well-foundedness

Real decision procedures often recurse deeply: a single high-level
obligation gets split into many sub-obligations, each split further,
until the leaves are trivially decidable. Cooper's algorithm for
Presburger arithmetic works this way. Simplex-style enumeration over
integer half-planes works this way.

A custom issuer in Pontif can do the same: the `refine` function can
recursively call itself (or other refining issuers) on its output
sub-branches. The receipt-graph data structure naturally accommodates
this — a refined branch is just another branch that might itself be
further refined.

**The termination concern is handled by Pontif's existing
structural-recursion guarantee.** Principle 8 totality requires every
Pontif function to terminate, with the kernel verifying termination
via:

- Match exhaustiveness (every case handled).
- Structural recursion (recursive calls on strictly smaller
  sub-structures).
- Or explicit decreasing measures.

A recursive `refine` function that satisfies these is total — and
therefore its proof process terminates. The dispatch mechanism *is*
the case-split engine: each dispatch case produces sub-branches, and
the kernel guarantees the dispatch is non-overlapping and total.

The result: **users can write recursive case-splitting decision
procedures as ordinary Pontif functions**, with the kernel verifying
their soundness and termination by the same checks that gate any
recursive function.

This is structurally what makes the approach feasible. There's no
separate "tactic monad" to verify, no separate language for proof
procedures, no fixed-point semantics to reason about. The proof
procedure is a Pontif function. Pontif verifies it. That's the whole
story.

---

## Full traceability

Every step of the case-split is recorded in the refined receipt graph.
No reasoning happens off the books. A reader inspecting the graph can:

- See exactly which branches were refined and which were left
  untouched.
- See the hypotheses on every sub-branch.
- See which sub-branches discharged directly and which required further
  refinement.
- Replay any portion of the proof by re-running the issuer on the
  relevant branch.

This contrasts with most proof-tactic systems where the tactic execution
is opaque — Coq's `auto` succeeds or fails but doesn't typically yield
an inspectable case tree. Pontif's receipt graph stays the same kind of
object before and after refinement: structured, serializable,
inspectable text.

**Traceability and trust come from the same property.** The kernel
gates each refinement step; the gating is what records the step. There
is no version of "the proof closed but I can't tell you why."

---

## What this changes

**Piecewise-linear nonlinear arithmetic moves from oracle territory to
user-Pontif territory.** Any obligation that admits decomposition into
linear leaves over integer ranges is decidable by a user-written
refining issuer. That covers:

- Polynomial inequalities over the integers.
- Product-magnitude bounds (`x*y >= 6` from `x >= 2, y >= 3` becomes
  provable via case-split on `x`).
- Bounded-domain decision problems.
- Many ad-hoc invariants users want to prove about their own data
  structures.

**The "issuer plugin interface" deferred TODO dissolves.** Custom
issuers are just user functions; no plugin protocol is needed beyond
the language itself. The original deferral was gated on Pontif's
not-yet-designed package-management/build tool, but with issuers as
user functions, distribution becomes a library concern, not a plugin
concern.

**The compile-time / receipt-graph parallel paths can converge.** Right
now, `FunctionCheck.verifyDefinition` and the receipt-graph subsystem
are two separate proof surfaces sharing engines. With user issuers
producing inspectable, kernel-verified proofs, the compile-time path
could consult those proofs directly — closing the long-standing gap.

**Custom algebras become library-implementable.** A traction algebra
or three-valued Tri-logic doesn't need language-level support. Its
laws are refinement predicates; its discharge rules are pattern-match
arms in a user-written issuer; its soundness is verified by the kernel
exactly as any user function's soundness is.

**What's still oracle territory** is narrower than before:

- Genuinely nonlinear obligations that don't admit case-decomposition
  to linear leaves — most transcendental shapes, some polynomial
  shapes over the rationals.
- Quantified statements that can't be enumerated (`∀x ∈ ℤ.` over
  unbounded `x` where the body doesn't case-split nicely).
- Undecidable fragments.
- Anything requiring solvers fundamentally outside Pontif's expressible
  reach (Z3-FPA for floating-point, for instance).

---

## Open design questions

These are real decisions that need to be made when this direction is
taken on. None of them block the approach; they're shape questions, not
viability questions.

**1. What does `ClosingReceipt`'s refinement clause require?**

Three candidates:

- **Strict**: `[ClosingReceipt: discharge(graph.facts(@.ref),
  @.conclusion)]` — Pontif must re-verify the obligation from the
  named path facts. Requires `discharge` to be callable from Pontif
  in some form (probably a built-in that wraps the kernel engines).
- **Structural**: `[ClosingReceipt: substituted(@.ref).implies
  (@.conclusion)]` — the substituted goal at the referenced branch
  implies the conclusion. Less prescriptive about which decision
  procedure proves it; more about logical entailment.
- **Loose**: well-formedness only; the payload carries the proof
  witness for third-party verification. Snake-oil territory unless
  signed.

The middle ground is probably the design sweet spot, but it's a real
choice.

**2. What does the Pontif-side `SymExpr` look like?**

The Java `SymExpr` is a sealed sum-type with `Var`, `Lit`, `Frac`,
`Bool`, `Self`, `Add`, `Mul`, `Pow`, `Cmp`, `And`, `Or`, `Lam`, `App`,
`Case`, `Record`, `FieldAccess`. Translating to a Pontif union with
struct branches is mechanical, but the language details — naming,
field access conventions, how the constructors interact with
narrowing — need to be pinned down.

**3. Performance.**

A Pontif-written discharge engine will be slower than the Java
`BoundAnalysis`, possibly by orders of magnitude. For compile-time
work this is tolerable; for runtime checks it matters more. The Java
engines stay available as built-ins; users opt into Pontif issuers
when they want custom behavior, not raw performance.

**4. Bootstrapping the receipt-graph types.**

Whether the Pontif-side `ReceiptGraph` types are auto-generated from
the Java types, hand-mirrored, or part of a shared schema is a build
question. Auto-generation keeps them in sync; hand-mirroring lets the
Pontif side use cleaner names. Probably worth a small code-generation
step.

**5. Termination measures.**

For recursive refining issuers, what's the canonical termination
metric? Structural recursion on the branch (each refinement produces
sub-branches the issuer can't refine further trivially) is one
answer. Decreasing the "polynomial degree" or "domain size" of the
obligation is another. The mechanism Pontif uses for ordinary
functions probably suffices, but the issuer-specific case might
benefit from explicit decreasing-measure annotations.

---

## What might be new about this

A note of cautious framing. The components individually exist
elsewhere:

- **Decision procedures via case-splitting** are textbook (Cooper,
  Omega, Presburger procedures).
- **Refinement type systems** are well-explored (Liquid Haskell, F*,
  Stainless).
- **User-extensible proof systems** exist (Coq tactics, Lean
  elaborators).
- **Verified tactics / reflexive proofs** exist (Coq's ssreflect,
  Agda's reflection, Lean 4's meta-programming).

What's potentially new in the Pontif approach is the *combination*:

- The proof artifact (receipt graph) is **first-class structured
  data** the user code can pattern-match on. Not a tactic state, not
  a proof term in a separate calculus — the same data that Pontif
  itself produces and consumes.
- The user's proof procedure is **verified by the same type checker**
  that verifies ordinary user code. Not a separate tactic-language
  trust model; not a reflection mechanism that bypasses checking.
  The proof procedure is just a function.
- The trust base stays **minimal and fixed**. Adding new proof power
  doesn't grow the trusted core. New decision procedures are
  user-written; the kernel verifies them via the same machinery that
  gates ordinary code.
- The proof structure **mirrors program structure** (call graph
  branches), making proofs naturally compositional with program
  decomposition. A program's proof is shaped like the program.

Whether this composition is *strictly novel* requires more thorough
literature review than this document attempts. The closest precedents
are probably ssreflect's reflexive tactics (proofs by computation in
the same language) and Lean 4's elaboration (meta-programming with
type-checked outputs). The Pontif framing is distinctive at minimum in
*centering the receipt graph* — making the proof artifact the
load-bearing surface for extensibility, rather than a side-effect of
tactic execution.

The honest claim is **architecturally interesting and worth building
out** rather than "this is a first." Whether it turns out to be
genuinely novel will become clear when it's built and the comparison
to existing systems can be done in detail.

---

## Where this fits in the roadmap

This is recorded as an architectural direction, not as an immediate
slice. Several pieces would need to land before it could be undertaken:

- Receipt-graph types serialized to a Pontif-accessible representation
  (either a runtime value or a compile-time-introduced family of
  user types).
- A Pontif-side `SymExpr` and its construction surface.
- The `ClosingReceipt` refinement clause chosen and implemented.
- The `refine` function's type pinned down with the refinement
  precondition.

Each of these is a real slice on its own. Taken together they're a
significant architectural lift. The payoff is that all the deferred
"oracle territory" items for piecewise-decidable obligations stop
being oracle items.

See `docs/TODO.md` section "Receipt-graph refinement — custom issuers
as type-checked Pontif functions" for the queued summary; this
document is the long-form rationale.
