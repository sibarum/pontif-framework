# Receipt-graph overhaul — scoping

> Status: **scoping / not started** (2026-08-01). Captures the big picture
> for restructuring the proof subsystem toward fully-automatic function
> classification, and sequences small proving-out steps. Nothing here is
> ratified; it is the map + a recommended first cut for red-pen.

## The goal (James, 2026-08-01)

Make **function classification fully automatic** in the compiler, and
**expose every proof-relevant detail through the receipt graph**. "Automatic"
= the engine discovers which proofs apply; the user never tells it which to
assign. Runs either for every function every compile, or for marked functions
(figuring out the proofs itself), or both.

Four design moves requested:

1. Every auto-classified function gets **its own receipt graph**, with a
   **unique entrypoint** per function.
2. The entrypoint becomes **load-bearing**: it is the origin from which
   **effective sorts** are computed through the call stack.
3. Effective sorts **change under repeated iteration**; the permutations are
   analyzed as a **gradient** — **converging** (toward a value), **diverging**
   (magnitude unbounded), or **wandering** (no coherent direction found).
4. Conservation, algebraic, halting, effective-sort details all live **on the
   receipt graph**.

---

## What exists today (the map)

Five loosely-coupled subsystems, three modules:

| Subsystem | Module | Shape | Per-function? | Composition | Module fixpoint |
| --- | --- | --- | --- | --- | --- |
| **Receipt graph** | `pontif-receipts` | ONE `ReceiptGraph{roots:[Node]}`, recursion keyed by **function name** | ✗ (many roots, one graph) | ✗ | ✗ |
| **Conservation** | `pontif-conservation` | `Ledger([ConservationGraph])`, one **per function** | ✓ | ✓ `ConservationSummary` over call DAG | — |
| **Halting (No-Halt)** | `pontif-conservation` | `NoHalt.of(Ledger)` — divergence set | (derived) | (reads summaries) | ✓ greatest fixpoint |
| **Effective sorts** | `pontif-ir` | `EffectiveSortLens` — flat `Map<Span, IrSort>` on `CompiledModule` | ✗ (module-wide, intraprocedural) | ✗ | ✗ |
| **Algebraic class** | `pontif-ir` | `AlgebraicCheck` — syntactic fragment walk over `IrExpr` | ✓ (per function) | acyclicity check | — |

Pipeline: `IrModule` → `Drafter` → `ReceiptGraph`; `BuiltinIssuer` discharges
(routed by `Sort` to `IntegerDischarge`/`DecimalDischarge` over
`BoundAnalysis`); `Notary` verifies refutation-only (`graphExists`,
`skeletonMatches`, `hypothesisSupported`). In-source `proof f = …` enters via
`ProofBinding`→`Refinement{Leaf|Split}` (receipts) or `ConservationProofs`
(conservation). `pontif-runtime` surfaces everything as `*Report` text and as
hard gates in `PontifCompiler` (`ReturnGate`, `ConservationGate`,
`ConstructionGate` — the last reads the effective-sort lens).

### The central observation

**Conservation is already the architecture the receipt graph is being asked to
become.** Per-function graphs, per-function summaries composed over the call
DAG (no-duplicate-edges as substitution), a module-wide fixpoint for the whole-
program fact. The receipt graph never made that move — it is still one module-
wide graph with name-keyed back-references and no summary/composition layer.

So most of moves (1) and (4) are **"make receipts look like conservation,"**
then unify the two (plus halting, effective-sort trajectory, algebraic class)
behind one **per-function dossier**. Move (3) — the gradient — is the genuinely
new capability, and it lives naturally in that unified per-function frame.

---

## The big picture

### A. Per-function dossier (the unifying object)

Introduce a per-function **`Classification`** (working name) — the "expanded
receipt graph" in James's sense — keyed by function (entrypoint), holding:

- the function's **receipt (sub)graph** (its `Node`, sliced out of the module
  graph),
- its **conservation summary** + graph,
- its **halting fact** (grounds / diverges / no-claim),
- its **effective-sort trajectory** (see the gradient below),
- its **algebraic classification** (is-algebraic + purity witness),
- the **discharge outcomes** the issuer produced for its obligations.

A single `classify(function) → Classification` is the automatic-classification
entry. The module-level artifact becomes a `Ledger`-of-classifications — the
same container shape conservation already uses.

**This is mostly re-slicing + wiring existing analyses, no new reasoning.** It
delivers move (4) immediately and gives moves (2)/(3) a home.

### B. Entrypoint-relative effective sorts (the deep lift)

Today `EffectiveSortLens` is a flat, intraprocedural `Span→Sort` map: a
function body's positions get sorts seeded only from that body's own
parameters. James wants the **entrypoint** to be the origin — effective sorts
computed **through the call stack** from a chosen root. That makes them
**context-sensitive interprocedural**: `main` calling `f(5)` refines `f`'s
parameter to `[Int:@==5]` *for that entry*, and it propagates downward.

This is the ambitious, risky part:

- Context-sensitivity is potentially exponential (call-string blowup). Needs a
  discipline up front: bounded call-strings (`k`-CFA), or **summary-based**
  (compute a per-function transfer function once, instantiate per call site —
  mirrors `ConservationSummary`). Summary-based is the natural fit given the
  rest of the subsystem already works that way.
- It changes the **gate contract**: `ConstructionGate` currently reads the flat
  lens. An entry-relative lens means "the effective sort *under this entry*,"
  so the gates need a defined entry (or a conservative join over all entries)
  to stay sound. **Do not start here.**

### C. The effective-sort gradient (the new idea)

For a recursive/iterative position, unfold the call and watch the position's
effective sort evolve: `s₀, s₁, s₂, …`. Classify the direction:

- **Converging** — the interval tightens toward a fixpoint / bound.
  `f(n-1)` on `[Int:@>=0]`: `n` marches toward `0`. This is **termination /
  well-founded descent** — exactly the arithmetic descent `NoHalt` explicitly
  refuses to prove and hands to "receipt-graph territory."
- **Diverging** — magnitude grows without bound. `f(n+1)`: `n` climbs forever.
  Non-termination by growth.
- **Wandering** — no coherent direction. `f(n)` verbatim (stationary — ties
  directly to `NoHalt`'s *verbatim re-entry* divergence witness), or a
  permutation the engine can't read → honest "no claim."

The payoff is a **single trichotomy that unifies the two halves of partial
correctness Pontif already has** — conservation's "if it completes it
conserves" and `NoHalt`'s "it can't complete" — and adds the third the docs
say is missing: convergence = *it does complete*. The engine is already built:
`core.symbolic.RealInterval` + `BoundAnalysis` reason about interval endpoints
and direction; the gradient is "which way do the endpoints move under the
recurrence's step." **This is the highest-signal, lowest-footprint place to
prove the whole overhaul out.**

### D. Automatic vs annotated — recommendation

Don't make the user choose. Mirror the conservation pattern already ruled
("programs with no conservation proofs pay nothing"):

- **Discovery runs for every function, always** — cheap analyses eagerly
  (algebraic class, halting fact, conservation summary), and **reports** the
  classification. Never requires the user to name a proof.
- **Gating stays opt-in** via the existing `proof f = …` / `assign proof`
  surface — that is the only place a classification becomes a hard compile
  error.
- Expensive/experimental analyses (gradient, interprocedural entry-relative
  sorts) run **only for functions that reach a gate or are annotated**, until
  they are cheap and trusted enough to run everywhere.

So: *classification is automatic and reported; assertion is optional and
gating.* No upfront "which proofs" from the user — that is the whole point.

---

## Suggested small steps (sequenced, de-risking)

### Step 1 — Characterization + breakage-surfacing tests *(James's stated first step)*

Current receipt-graph tests are shape-focused (`Drafter*`, a few discharge
cases) and predate the type-system refactor (CallSig/dispatch elimination,
RecordValue relocation, keyed dispositions, refined streams). The
conservation/receipt drafters are **exhaustive switches over sealed IR with no
default** (`conservation-algebra.md`) — so any IR form added during refactoring
either breaks the switch (good, loud) or is mishandled. TODO already files the
"sweep every IrExpr switch in the proof/receipt + conservation drafters" work.

Build a suite that (a) **golden-snapshots** current `Drafter` +
`ReceiptGraphPrinter` output for a spread of function shapes to lock behavior
before the overhaul, and (b) deliberately **exercises the new language
features** (CallSig callables, dispatch / algebraic-dispatch metarefs, keyed
`Iterate`, brace aggregates, refined streams) through the drafter → issuer →
notary pipeline to **surface what's broken**. Output: the test class + a short
findings list of what throws / mis-drafts. Low risk, high information; this is
the diagnosis James wants before committing to structure.

### Step 2 — Per-function slice + the dossier container *(mechanical)*

Introduce the `Classification` record and a `classify(function)` that returns
it, wiring in **only the analyses that already exist** (receipt sub-graph,
conservation summary, `NoHalt` fact, algebraic bit, discharge outcomes). Re-
slice the module-wide `ReceiptGraph` into per-entrypoint views (keep the
existing graph as the backing store first; don't rewrite the drafter).
Delivers move (4) with today's reasoning and gives (2)/(3) a home. No new
proofs, so it's safe and testable against Step 1's goldens.

### Step 3 — Gradient over a single self-recursive numeric parameter *(the new idea, smallest slice)*

Narrowest viable case: one function, one recursive call, argument a linear step
of one `Int`/`Decimal` param (`f(n-1)`, `f(n+1)`, `f(n)`). Compute the
effective-sort sequence for that param across unfoldings and classify
converging / diverging / wandering via `RealInterval`/`BoundAnalysis` endpoint
direction. Assert the three canonical cases and the `f(n)`→wandering tie to
`NoHalt` verbatim re-entry. Tiny, self-contained, reuses the numeric kernel,
and demonstrates the unifying trichotomy end-to-end.

### Step 4 — Entrypoint-relative interprocedural effective sorts *(deferred, deep)*

The big lift (§B). Gate behind Steps 1–3. Decide the context discipline
(summary-based, following conservation) and the gate contract (what "effective
sort" means once it is entry-relative) **before** touching `ConstructionGate`.

---

## Step 1 findings (2026-08-01)

The characterization suite (`ReceiptGraphFeatureCoverageTest` +
`ReceiptGraphFeatureProbe`, `pontif-runtime`) pushed 11 post-refactor feature
shapes through the drafter→issuer→notary pipeline. **Good news: nothing throws
— the "no-default" sealed switches did not hard-break; every new IR form still
drafts.** The breakage is quieter and worse: *silent mis-drafting* that defeats
the issuer. Existing tests missed all of it because `ReceiptGraphReportTest` /
`DrafterMatchTest` only ever use **guard-only** match arms (`[@>0] -> …`), which
bind no variables.

**Finding 1 — destructuring match arms weren't inlined (HIGH) — FIXED
(2026-08-01).** A destructuring arm (`[{a,b}] -> {b,a}`, `[Cons(h,t)] -> 1 +
len(t)`) desugars (single-file) to nested projection lets
(`let h = xs.head in let t = xs.tail in …`), which `IrCompiler.compileSymExpr`
encodes as an *un-reduced* `App(Lam(name, body), value)` wrapper (it can't emit a
bare let — SymExpr has no let node). The drafter never inlined them, so `len`'s
recursive arm drafted as `r_0 == (h)->(t)-> 1 + r_1(xs_0.tail)(xs_0.head)` with
the call hoisted against the **dangling binder** (`len(t)`, not
`len(xs_0.tail)`). `BoundAnalysis` can't see through the wrapper, so the
trivially-true `[Int:@>=0]` bound on a `Nil|Cons` list **failed to discharge** —
though it's the exact analogue of factorial (which passed only because its arms
are guard-only and bind nothing). *Net effect had been: all ADT/structural
recursion proofs broken.* **Fix:** the drafter now beta-reduces administrative
redexes and inlines **projection lets** (values that are vars / field-access
chains / literals — duplication-safe) at the IR level *before* hoisting, so
binders are replaced by their projections and calls hoist against the real
argument. Call-valued lets are left as shared bindings (no duplication).
`len` now drafts `call: len(xs_0.tail) -> r_1` + `r_0 == 1 + r_1` and discharges
on both branches; `swap` drafts `r_0 == _tuple{_0=p_0._1, _1=p_0._0}`. Change is
local to `pontif-receipts` `Drafter` (`betaReduce`/`substVars`/`isInlineable`);
full `pontif-runtime -am` reactor stayed green. Also fixes the cross-module path
for free (`DestructureResolver` emits the same projection lets). *The
apparent "reversed arg order / curried lambda" in the old output was just the
SymExpr printer rendering the let-as-`App(Lam)` encoding — not a semantic bug.*

**Finding 2 — `assign proof f(x):[…]`-granted refinements were invisible to the
receipt graph (HIGH) — FIXED (2026-08-03).** `assign proof isSparse(x):[… ->
[Int:@>=-16]]` grants a return refinement the return gate honors
(`AssignProofTest` compiles the program on its strength), but the granted
refinement lives on the *proof* (`IrStmt.ReturnProof`), not the function's
declared base return — so the drafter's node reads `r_0: Int` and the report
printed "nothing to prove", disagreeing with the gate. The gate already resolved
these via `ReturnProofBinding.validate` but only for pass/fail; nothing exposed
the granted obligation. **Fix:** `ReturnProofBinding.bind` returns each granted
obligation with its node/branch and discharge outcome (the report/dossier view
of what `validate` checks); `ReceiptGraphReport` renders them, so `isSparse` now
shows `isSparse : r_0 >= -16 (assign proof) → discharged [via proof; notary:
accepted]`. Same class as the old `proof = <tree>` "receipts say X, runtime does
Y" gap (`importedProofTypes_reportAgreesWithRun`), now closed for the
`assign proof` surface too — receipt view and gate agree. *Deliberately at the
report/exposure layer (where `proof=` consumption already lives), not the
drafter: the graph node keeps the declared return; per-region dispatched proofs
map to their own branches. Folding granted returns onto the node itself is a
Step-2 dossier concern.*

**Finding 3 — stream-query drop arm carries no receipt (judged: NOT a defect,
2026-08-03).** A refined query `&s:[Int:@>1]` drafts an `$iter$` step with the
filter's keep-branch *and* an unconditional drop-branch carrying no initial
receipt. Investigated and judged **honest + sound**: (a) a filter's drop arm
produces no output, so having no `r_0 == …` equation is the correct model; (b)
`BuiltinIssuer.attemptAll` visits every branch, and a branch with no result-var
definition leaves the obligation's `r_0` un-substituted, so `Discharge` fails
**closed** (NOT DISCHARGED) — the empty branch can never be silently skipped into
a false discharge; (c) the step carries a bare return today, so there is no
obligation at all and the report honestly says "nothing to prove". No code
change; locked by `streamQuery_dropArmHasNoReceipt_isHonestAndSound`. (The
caller-side lambda-app blob noted originally was the Finding 1 family and is
gone with that fix.)

**Finding 4 — receipt output was nondeterministic on multi-field records
(MEDIUM) — FIXED (2026-08-01).** The identical `swap` program printed
`_tuple{_0=b, _1=a}` on one run and `_tuple{_1=a, _0=b}` on the next. Root
cause was **not** the printer but `SymExpr.Record`'s compact constructor:
`members = Map.copyOf(members)`, and `Map.copyOf` returns an immutable map whose
iteration order is **deliberately salted/randomized per JVM run**. Every
consumer that walks members in order (printer, `Substitute`, `AlphaRename`,
`Simplifier`) inherited the randomness — so text artifacts, diffs, and any
rendered-output cache were nondeterministic, against the design's "same source →
same graph" contract. Fix: canonicalize into a `TreeMap` (field-name keys are
`Comparable`, so natural order is a stable canonical order; record equality is
order-independent regardless). One-line change in `pontif-core` `SymExpr.java`;
full `pontif-runtime -am` suite (1108 + upstream) stayed green. *Rule going
forward: TreeMap (or explicit insertion-order `LinkedHashMap`) for any map whose
iteration is observable — never `Map.copyOf`/`HashMap`.*

**Bearing on the overhaul.** Findings 1 and 2 were the priority: 1 blocked the
whole ADT/recursion proof story the gradient (Step 3) and per-function dossier
(Step 2) will build on; 2 is the receipt graph failing its core promise today.
**All four Step-1 findings are resolved** — 1 (ADT/structural recursion
discharges), 2 (assign-proof obligations exposed), 4 (deterministic rendering)
fixed; 3 (stream drop-arm) judged by-design + sound. The Step-1 suite is the
guard that keeps them fixed; the drafter's proof surface is now sound enough to
build the Step-2 per-function dossier on. (Probe `08-fold` hit a probe-syntax parse error, not a
receipt bug — the `fold` lambda spelling in the probe is wrong; re-confirm fold
coverage once the syntax is corrected.)

## Risks / open questions

- **Interprocedural blowup** — pick summary-based vs `k`-CFA before Step 4.
- **Gate soundness under entry-relative sorts** — `ConstructionGate` reads the
  flat lens today; an entry-relative lens needs a defined entry or a
  conservative all-entries join to stay sound.
- **Gradient decidability** — non-linear or multi-atom recurrences will
  *wander* by design; that is honest, but we should confirm the engine abstains
  (never false-converges) — same fail-closed discipline as the ledger.
- **Drafter re-slicing vs rewrite** — Step 2 keeps the module-wide graph as
  backing store to avoid a drafter rewrite; revisit if per-function drafting
  turns out cleaner once Step 1's breakage is understood.

## See also

- `docs/receipt-graph.md`, `docs/receipt-graph-refinement.md` — current design.
- `docs/conservation-algebra.md`, `docs/conservation-receipts.md` — the
  architecture receipts should converge toward; `NoHalt` ruling.
- `docs/type-system-roadmap.md` §4.7–4.8 — effective sorts, `EffectiveSortLens`.
- `docs/TODO.md` — "Receipt-graph subsystem", "Conservation receipts",
  and the "sweep every IrExpr switch" note.
</content>
</invoke>
