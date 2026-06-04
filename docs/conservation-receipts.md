
Feature: Conservation Receipts
===

Information conservation is an important property of many algorithms.
This is especially true for algorithms transforming immutable data structures, where the correctness of the algorithm isn't based on algebraic identities but rather on the lack of distortion, truncation, or corruption of data.

- Sorting algorithms: Ordinality is only half of the picture. Size comparison isn't definitive. Conservation receipts can prove, at compile time, that no elements will be transformed, discarded, or duplicated in the proceess.

- Symbolic translations: Asymmetric, heterogenous data structures being mapped from one format to another have plenty of opportunities to accidentally lose data. Difficult to troubleshoot by hand, time-consuming to test. But now, trivial to verify. Conservation receipts track every attribute of every struct, recursively. A proof merely asserts on the receipts. Proven conservation before ever reaching a runtime.

- Intentional erasure: Truncate, safely. An assertion on the conservation receipts can prove all invariants are properly discharged.

- Algebraic equivariance: Prove every attribute among inputs is utilized in computing the output. Prove every attribute in the output depends on every attribute among inputs. Or assert each specific interaction - any level of granularity is achievable, and every proof is issued with auditable receipts.

- Optimization potential: Future iterations could utilize conservation receipts to preemptively allocate, deallocate, or safely mutate data structures with strong correctness guarantees.

- Totality, reversibility, and halting: Can be proven by ensuring all data gets mapped, no transformation loses data, nothing is truncated, and nothing is processed more than once. As with algebraic receipt issuance, stubborn cases can be manually split for automated discharge by the notary.

# How it works:

1. Conservation receipts are generated in a similar fashion as the receipt graph.
2. The calculations and mapping of values between input and output is tracked and recorded into a compressed/summarized format.
3. Each function call is analyzed independently, and recursion is handled via the no-duplicate-edges rule, ensuring finite decideability.
4. The resulting conservation receipts can then be queried and asserted, proving algorithmic properties by completeness of query and passing assertions.
5. Future changes to the algorithm are protected - a proof that fails to verify results in a compilation error. The query-assert paradigm ensures stability under change and resuability between unrelated functions with the same algorithmic properties.

# What it doesn't do:

1. Conservation isn't a type narrowing feature.
2. It doesn't affect from where a function may be called or what it may return.
3. Install any logic into the runtime.

# Querying Conservation Receipts

NOTE: WIP, currently over-engineered

**Refined Wildcard:** An automated destructuring macro that recursively walks the struct graph (with the no duplicate edges rule)
and collects every matching struct/attribute into a named array.

**Select Inputs:** Refined wildcards over the argument tuple

**Select Output:** Refined wildcards over the return

**Select Classifications:** Verbatim, derived, branch/flow, untouched, translated, opaque

**Filter Expression:** A boolean expression of refined wildcard references and comparisons.

**Full Query:** At least one select of inputs, outputs, and/or classifications with a modifier (AND/OR)
with an optional filter expression.

**Assertion:** A simple true or false, or a branch merge (all branches, zero branches, at least one branch)

# See Also

- **conservation-algebra.md** — the classification ontology, DERIVED from the
  sealed IR rather than hypothesized (circular design); draft pending red-pen.
  The v1 event taxonomy below is the hypothesized version it supersedes.
- provenance semirings
- reversibility-as-conservation-corollary; witnessing that the dataflow is a fan-in/fan-out-free composition of bijective primitives is an invertibility witness, structurally, which lines up with reversible-computing theory
- conservation facts as propositions other proofs can consume

# Status

*(The Slice 1/2 notes below are historical — the event taxonomy and sample
rendering they describe are the superseded v1; the current model is
`conservation-algebra.md`'s, per the "Re-cut + composition landed" entry at
the end of this section.)*

**Slice 1 landed: the ledger and its first reading** (`pontif-conservation`). The
drafter transcribes per-function dataflow events — in the converged taxonomy,
reads have kinds: *consult* (read to determine branching), *combine* (read to
operate with another value), *emit* (set into a return slot) — per branch, in
order, with calls recorded by reference (no-duplicate-edges) and anything v1
can't trace marked **OPAQUE** (the no-lie law applied to the ledger itself:
honest ignorance is never reported as conserved or dropped, and every query
fails closed on it). Event names are provisional pending review of the data.

Queries are programmatic for now (`ConservationQueries`: `lossless`,
`verbatimBijection` — the reversibility witness, `duplicated`, `untouched`,
branch quantifiers) — deliberately: the proof surface gets designed AFTER the
printed ledgers have been read. Reports render via `ConservationReport` →
`<name>.conservation.txt` (see `ConservationReportTest`,
`target/conservation/`). A sample reading:

```
translate(s_0: Source) -> r_0: Target
  inputs:  s_0.name, s_0.age, s_0.email
  outputs: r_0.fullName, r_0.years
  branch (unconditional):
    emit:    s_0.name -> r_0.fullName   [verbatim]
    combine: s_0.age + 1 -> d_1
    emit:    d_1 -> r_0.years   [derived]
    classification:
      s_0.name         emitted-verbatim
      s_0.age          flows-derived
      s_0.email        UNTOUCHED (no flow into any output)
```

`lossless(translate)` fails on the UNTOUCHED line and passes once `email`
flows — the compile gate, demonstrated. `swap((a, b) -> (b, a))` passes
`verbatimBijection`: reversibility witnessed without arrays.

**Slice 2 landed: the assertion surface and the real gate.** Zero new syntax:
properties ship as values in the builtin module **`std.conservation`**
(`Lossless()`, `Reversible()`, `NoDuplication()`, `LosslessExcept(s.email)` —
names provisional), attached with the existing `proof f = …` statement. One
proof statement, two ledgers — the tree's head vocabulary picks which
(`Leaf`/`Split` → the algebraic notary, conservation heads → this ledger), so
"conservation facts as propositions" is literal. A failing assertion is a
compile error whose body includes the printed ledger node — **the error IS the
receipt**. Assertions are re-evaluated against the freshly-drafted ledger on
every compile: `LosslessExcept(s.email)` makes the lossy translation compile
(the drop is declared) and FAILS once the drop disappears — stale-proof
protection, exactly as promised above. Fail-closed throughout: opaque or
call-mediated flow never certifies, but programs with no conservation proofs
pay nothing.

```
requires std.conservation.{Lossless}
...
proof translate = Lossless()      # compile error until every Source attribute flows
```

The selector/filter property-DEFINITION language remains deliberately deferred
until real property definitions demand it; the named-property library is the
assertion surface.

**Re-cut + composition landed.** The ledger is rebuilt as the ratified
algebra (`conservation-algebra.md`): three node kinds + metadata edges,
exhaustive over the sealed IR (no default case — the compiler is the
taxonomy's standing completeness proof), per-branch-path role multisets, the
sort-aware `DataConservative` under the capacity law (`Lossless` stays
reserved for the cross-ledger property), and **composition over the call
DAG**: callee summaries substitute at call sites, overloaded callees are
dispatch-as-Branch over candidates, callee-internal branching spend credits
the caller's atoms. Real helper-delegating code now certifies end-to-end; a
helper that drops its argument surfaces the loss in the caller.

**Recursion fixpoint landed.** Cycle members' summaries are computed as a
per-cycle Kleene fixpoint from the optimistic seed (the inductive
hypothesis), so recursive and mutually recursive functions get honest
ledgers and verdicts — `fact` now fails `DataConservative` on the merits
(the base path spends `n` on one branching bit and returns a constant), not
on located ignorance. The ruling, including the partial-correctness reading
(claims quantify over completed evaluations; ungrounded loops certify
vacuously), is in `conservation-algebra.md`. Recursive overloaded names stay
dispatch-as-Branch, out of scope.

**No-Halt landed.** The divergence fact: a module-wide greatest fixpoint
over the ledger's branch-paths proves some functions never complete (no
grounding path; verbatim self re-entry; every path calls a never-halting
function), printed as a `no-halt:` line and per-path markers. The vacuous
certificate no longer stands silent — the ungrounded loop still certifies
`DataConservative` (the partial-correctness ruling) but the ledger names the
divergence beside it. A fact, not a verdict change: how certificates, the
gate, and the receipt graph's inductive hypothesis consume it is the open
ruling, decided after reading printed ledgers. Ruling and limits in
`conservation-algebra.md`.

**Next slices:** the No-Halt consumption ruling (vacuity-annotated
certificates? receipt-graph IH refusal — the first cross-ledger
proposition?); the collection atom model (the sorting case needs it —
element-quantified atoms over arrays); multi-branch `Reversible` via the
exit-assertion theorem; the property-definition language (when real
definitions demand it); cross-ledger `Lossless`.