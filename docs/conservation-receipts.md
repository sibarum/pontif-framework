
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

# See Also

- provenance semirings
- reversibility-as-conservation-corollary; witnessing that the dataflow is a fan-in/fan-out-free composition of bijective primitives is an invertibility witness, structurally, which lines up with reversible-computing theory
- conservation facts as propositions other proofs can consume

# Status

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

**Next slices:** the query/proof surface (designed from the printed data);
callee-summary substitution (so `via-call` flow becomes provable); sorting
(needs arrays); cross-ledger propositions (conservation facts consumed by
algebraic proofs).