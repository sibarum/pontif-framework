
Conservation Algebra: deriving the theory from the IR
===

Status: RATIFIED AND IMPLEMENTED (see Consequences). The hypothesized v1
ledger (consult/combine/emit) is superseded by the derived model below; the
remaining open rulings are cosmetic (rung display names, the method-note's
eventual home).

# Method: circular design

Backward language design says decisions flow upward from the deepest layer
with jurisdiction, and treats the theory as layer zero — fixed, given, the
source. The conservation taxonomy exposed the limit of that framing: its event
vocabulary (consult / combine / emit) was *hypothesized* — named from intuition
about what dataflow should consist of, before reading the alphabet it would
transcribe. The tell is in the v1 drafter: a `default -> OPAQUE` case. A
hypothesized taxonomy needs a catch-all; **a derived taxonomy has no default
case.**

The correction: for a feature whose artifact is a *transcription of the IR*,
the IR is not an implementation detail downstream of the theory — it is the
theory's alphabet. The IR is a sealed, closed algebra of thirteen expression
forms. The reliable conservation-theoretic states are exactly the per-form
behaviors of that algebra — no more, no fewer. Derive, don't invent.

This closes a loop rather than extending the chain: theory shaped the IR
(backward design); the IR now calibrates the theory; the recalibrated theory
gates what the IR may become. Concretely, the completeness of the
classification set is not an argument — it is a **compiler check**: a
conservation switch over a sealed interface that omits a form does not
compile, and any *future* IR variant breaks that switch until someone declares
what it conserves. The theory audits the language; the language type-checks
the theory. Backward theory design; circular language design.

# The correspondence (RULED: three node kinds + metadata)

The mirror is a **homomorphism, not an isomorphism**: every sealed form maps
somewhere (the totality guarantee stands — the drafting switch stays
exhaustive, no default), but forms with the same conservation effect share a
node kind, and forms with *no* conservation effect are not nodes at all —
they **populate metadata onto other nodes** of the graph.

**The graph has exactly three node kinds:**

| Node kind | IR forms | Conservation character |
| --- | --- | --- |
| **Computation** | `BinOp`; **resolved** `Call` | content is operated on. Stratified by op class (below); a resolved call's behavior is the callee's summary, by reference (no-duplicate-edges) |
| **Branch** | `Match`; **dispatch sites** (overloaded calls) | a measurement selects a continuation. Matchers and dispatches are the same act — discrimination — differing only in guard vocabulary: match guards are predicates, dispatch guards are *narrowings*. (Consistent with the match/dispatch distinction elsewhere: ordered-overlapping vs unordered-disjoint — but both discriminate.) |
| **Construction** | `Record`; **function returns** | content is placed into slots. A return IS a construction site — the output (`r_0`, or `r_0.<slot>` per member) is constructed, even for scalars. Emission is not a separate concept; it is construction of the result |

**Metadata (not nodes — they don't affect conservation; they decorate):**

| IR form | Decorates |
| --- | --- |
| `Lit` / `Dec` / `Bool` | a constant operand on a Computation/Construction node |
| `Var` | naming — resolves to an edge between nodes |
| `LetIn` | binding — names an edge; the sequence point |
| `FieldAccess` | path selection — decorates an edge with the projected path |
| `SelfRef` | typing-level; outside the runtime ledger |

**`Lambda` / `Apply` (RULED: skipped for now — a can of worms).** Both are
**residual** in the current cut: a lambda or an application anywhere in a
flow marks that flow untraceable, fail-closed, same as v1. The eventual
placement (closure-as-construction-of-captures, known-target application as a
resolved computation) is sketched in the history but deliberately not part of
this algebra until first-class functions earn their slice. OPAQUE is thereby
a location, not a category: lambdas, applications, unresolved calls — nothing
else. (The v1 ledger also marked nested construction and nested
discrimination as OPAQUE — among the most traceable forms in the language.
That was vocabulary poverty, not ignorance, and the re-cut traces them.)

# Combination, stratified

The `Op` enum partitions into three conservation classes:

- **Arithmetic** (`+ - * / %`): content-combining. Recoverability is per-op,
  per-operand: an operand is *recoverable* when it can be reconstructed from
  the result plus the other operands.
  - `+`, `-`: recoverable in both operands.
  - `*`: recoverable in an operand when the other is provably nonzero;
    annihilated when the other is zero.
  - `/`, `%`: individually lossy; **jointly conserving** — the language's own
    division policy already states the recovery identity
    `a == (a/b)*b + a%b`. A flow that emits *both* `a/b` and `a%b` conserves
    `a`; either alone degrades it.
  - The linear kernel already knows these facts; combination events carry the
    op so verdicts can attach.
- **Measurement** (`< <= > >= == != ~=`): collapses operand content to one
  bit of relational information. This is the theoretical home of
  *discrimination*: a match guard IS a measurement whose bit selects the
  branch. Measurement is not content-carrying — an atom whose only road to
  the output runs through a comparison contributes a bit, not its content.
- **Logical** (`& |`): bit combination over measurement results.

# Derived per-atom record: roles, not a fate

The v1 classification assigns each input atom ONE fate by precedence, which
collapses combinations the theory needs (an atom both measured and emitted; an
atom emitted twice; an atom emitted verbatim AND feeding a derived output).
The derived form is a **role multiset** per atom per branch:

- *referenced / projected* (and where to)
- *measured* (by which measurement — guard or value-level)
- *combined* (per op-class, with the recoverability verdict)
- *constructed-into* (which slot)
- *emitted* (which target, multiplicity, via what chain)
- *captured* (into which closure) / *released*
- *composed* (into which call — pending callee summaries)

Single-fate views (UNTOUCHED, etc.) remain as display projections and simple
query predicates — derived, never stored.

# Properties, restated over the algebra

**RULED: the name `Lossless` is reserved.** A category called "lossless" must
cover ALL lossless cases — and true losslessness (the output determines the
input) requires the **algebraic and conservation ledgers combined**: `x`
emitted as `x + 5` is lossless *algebraically*, which the dataflow shape alone
can't certify. That is the cross-ledger-propositions slice — deliberately last
on the list. Until it exists, no dataflow-only property wears the name; the
trivial all-verbatim case it could honestly cover is uninteresting. The
shipping `std.conservation` property currently named `Lossless()` is therefore
misnamed and gets renamed at the re-cut (candidates for the humbler
"everything reaches an output" property: `NothingDropped`, `FullyConsumed`,
`AllInputsFlow` — ruling needed).

The role ladder stratifies "the content reached the output":

1. **flows-verbatim** — reference/projection/construction chain only.
2. **flows-recoverable** — chain may include combinations, every step
   recoverable (co-operands constant or themselves emitted; `/`+`%` jointly).
3. **flows-degraded** — content influenced the output through a
   non-recoverable combination (`*0`, lone `/`).
4. **measured-only** — one bit of relational information survives.
5. **absent** — no role at all.

**RULED: `Data-Conservative`** — the headline dataflow-only property,
**sort-aware**. The governing law: *measurement counts as conservation
exactly when it exhausts the measured content*, and an atom's content
capacity is a fact the sort system already knows.

- Every `Int` or `Decimal` input atom reaches the return — verbatim or in a
  computation of a derived value in the return (thresholds 1–3: influence,
  not recoverability — which is precisely what the dataflow ledger alone can
  honestly certify, and why the name claims consumption, not losslessness).
- Every `Bool` input atom reaches the return the same way, **or is spent in
  branching logic** (threshold 4 suffices): a Bool's entire content is one
  bit, so a branch consumes all of it. Nothing was dropped.
- Atoms of other sorts default to the numeric rule (content-bearing;
  measurement does not exhaust them) — the conservative direction.
- Every branch; fail-closed on residual flow, as always.

This also rules the measurement-as-use question *sort-wise* rather than
globally — capacity accounting, not a flat yes/no. (Future hook, deliberately
not taken now: a narrowed `[Int:0|1]` is also ~one bit; sort-aware capacity
could eventually read refinements, not just bases — conservation consuming
the narrowing system.)

- **`Lossless` (reserved, cross-ledger)**: every atom *recoverable* from the
  output — thresholds 1–2 certified by dataflow alone, threshold 3 cases
  promoted when the algebraic ledger proves the combination invertible.
- The shipping `std.conservation` property renames `Lossless()` →
  `DataConservative()` at the re-cut (with the sort-aware Bool clause);
  `LosslessExcept` follows (`DataConservativeExcept`, or a better coinage).
- *NoDuplication*: verbatim-emission multiplicity ≤ 1 per atom.
- *Intentional erasure* (`LosslessExcept`): declared atoms ≤ threshold 4;
  all others per the chosen lossless threshold. Stale-proof rule unchanged.
- **Reversibility**, now derived rather than restricted: a single branch is
  reversible when the placement over thresholds 1–2 is bijective and every
  output is single-sourced. A *multi-branch* function is reversible when
  every branch is, **and the join is re-discriminable**: the atoms measured
  by the branch-selecting guards are themselves conserved to the output, and
  the guards partition (complement-derived guards — the `splitOn` discipline —
  partition by construction). This is reversible computing's exit assertion
  (Janus's `fi`), not as a bolted-on rule but as a corollary of
  discrimination-as-measurement: a measurement's bit is recoverable post-hoc
  iff its operands survive. Until implemented, `Reversible` fails closed on
  multi-branch nodes.

# Consequences for the implementation — IMPLEMENTED

1. ✔ The graph model mirrors the ruling (Flow edges + the three FlowNode
   kinds); the drafter's switches are exhaustive — no default case, by
   construction and forever.
2. ✔ Nested construction and nested discrimination trace; residual flow is
   exactly lambdas, applications, and unresolved/recursive calls.
3. ✔ Computations carry op-class + recoverability verdicts.
4. ✔ Per-atom, per-branch-path role multisets (`ConservationRoles`); fates
   demoted to display views.
5. ✔ `DataConservative` shipped sort-aware per the capacity law
   (`std.conservation`); multi-branch `Reversible` still refuses — the
   exit-assertion theorem is the named follow-up.
6. ✔ **Composition** (beyond the original list): per-function
   `ConservationSummary` (MUST relations) substitutes at call sites over the
   call DAG in topological order; overloaded callees are dispatch-as-Branch
   over their candidates; callee-internal branching spend is credited at the
   call site via a single-arm Branch's discriminants.
7. ✔ **Recursion** (the fixpoint slice): cycle members' summaries are the
   Kleene fixpoint from the optimistic seed, so a recursive call substitutes
   its own function's converged summary by reference — the self-referential
   case of no-duplicate-edges. Recursive *overloaded* names stay
   dispatch-as-Branch with no further expansion (residual inside the arms,
   honestly labeled).
8. ✔ **No-Halt** (`NoHalt`): the divergence fact — *this function provably
   never completes* — detected by a module-wide greatest fixpoint over the
   ledger's branch-paths and printed beside the classifications (a `no-halt:`
   line per claimed function; per-path markers). A fact, not a property
   verdict: what the gate and the certificates do with it is an open ruling.

# Rulings so far

- **Node kinds (RULED):** Computation (operations + resolved calls), Branch
  (matchers + dispatches), Construction (constructors + function returns);
  everything else is metadata on nodes/edges, not a node.
- **`Lossless` (RULED):** reserved for the cross-ledger property (algebraic +
  conservation combined — last on the list); the shipping dataflow-only
  property is misnamed and will be renamed.
- **`Lambda`/`Apply` (RULED):** skipped — residual, fail-closed, until
  first-class functions earn their own slice.
- **`Data-Conservative` (RULED):** the headline dataflow property, sort-aware
  per the capacity law (numerics must reach the return; Bools may instead be
  spent in branching). Also rules measurement-as-use, sort-wise.
- **No-Halt (RULED — the detector; verdict consumption still open):** the
  complement of the recursion fixpoint. The fixpoint assumes completion and
  checks what follows; No-Halt proves completion impossible — together they
  are the two halves of the partial-correctness reading. Detector: a
  module-wide greatest fixpoint — D starts as all unambiguous functions;
  repeatedly remove any function having a branch-path with no call-to-D and
  no verbatim self re-entry; the stable D provably never halts. Sound by
  infinite descent under pure, strict evaluation; callers of never-halting
  functions inherit the fact for free. *Verbatim re-entry* is the per-path
  fact feeding it: a direct self-call passing the params unchanged (any
  permutation — the orbit is finite) makes that path divergent even when a
  base path grounds the function. The boundary, worn in the name: this
  decides only a sound corner of *non*-halting — silence is "no claim",
  never "halts"; termination is never proven (factorial's descent is
  arithmetic — receipt-graph territory, a cross-ledger follow-up); calls in
  dead flow (an unused `let`) are invisible from the result — a pinned miss.
  Open ruling: what the property verdicts and the gate do with the fact
  (print-only today; vacuity-annotated certificates and the receipt-graph IH
  refusal are the candidates — decided after reading printed ledgers).
- **Recursion (RULED):** per-cycle Kleene fixpoint over summaries, seeded
  optimistically (every atom CONTENT, every atom spent, nothing residual) —
  the inductive hypothesis "assume the recursive call conserves, check the
  body under that assumption," exactly the receipt graph's IH brought to the
  conservation ledger. Every iteration step is monotone-decreasing in the
  finite summary lattice (relations degrade, spends drop, residuals grow),
  so it converges without widening; a round cap exists only as a loud
  monotonicity tripwire, never a silent fallback. The reading is
  **partial-correctness**: a conservation claim quantifies over *completed
  evaluations*, so an ungrounded loop (`f(n) -> f(n)`) certifies vacuously —
  a run that never completes has nothing to violate. The receipt graph
  already accepts the same reading (its notary discharges `r_0 >= 1` for an
  ungrounded loop via the IH); the two ledgers stay consistent about what a
  claim asserts.

# Open rulings (red-pen targets)

1. **Rung display names** for the threshold ladder (minor — they surface in
   reports and diagnostics, not in `std.conservation`).
2. **Whether this method note belongs in `backward-language-design.md`** as
   the circular generalization, or stays here as the worked instance.
3. *(Confirm)* Bool + derived flow counts (assumed yes — Bools gain the
   branching road in addition to, not instead of, the flow roads); "Float"
   read as `Decimal`.

# See Also

- `conservation-receipts.md` — the feature this algebra underwrites.
- `backward-language-design.md` — the method this generalizes: the loop
  closes; the theory is not layer zero but a layer in the circle.
- `IrExpr` (`pontif-ir`) — the sealed alphabet; the correspondence table's
  left column is its `permits` clause, and must remain so.
