
Conservation Algebra: deriving the theory from the IR
===

Status: DRAFT FOR RED-PEN. Nothing here is implemented; the v1 ledger
(consult/combine/emit) stays as-is until this derivation is ratified. Every
state name below is provisional.

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

# The correspondence

One stance per sealed `IrExpr` variant. "Content-carrying" means the input
content itself moves; "measurement" means content is collapsed to relational
information (bits).

| IrExpr form | State | Conservation character |
| --- | --- | --- |
| `Lit` / `Dec` / `Bool` | **introduction** | a constant enters the flow; carries no input content |
| `Var` | **reference** | identity flow of a binding; content-preserving |
| `FieldAccess` | **projection** | path narrowing (`p` → `p.x`); content-preserving on the projected path |
| `LetIn` | **binding** | names a flow; the sequence point; content-preserving |
| `Record` | **construction** | fan-in of flows into named/positional slots — projection's inverse; content-preserving per slot |
| `BinOp` | **combination** | stratified by the `Op` enum — see below |
| `Match` | **discrimination** | a measurement selects a branch; per-branch flows |
| `Call` | **composition** | flow crosses a function boundary; behavior = the callee's summary, by reference (no-duplicate-edges) |
| `Lambda` | **capture** | free variables are packaged into a closure — *delayed* flow; a definite state, not ignorance |
| `Apply` | **release** | captured flow is applied. Known target: traceable through the capture. Unknown target: the algebra's one genuinely residual case |
| `SelfRef` | **(typing-level)** | the refinement subject; no runtime flow — outside the runtime ledger |

OPAQUE thereby stops being a category and becomes a *location*: at most,
release-of-unknown. Honest ignorance survives, pinned to the one place the
algebra cannot decide — never as a catch-all absorbing what the vocabulary
forgot to name. (The v1 ledger marked nested construction, nested
discrimination, and capture as OPAQUE. All three are among the most traceable
forms in the language. That was vocabulary poverty, not ignorance.)

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

The role ladder stratifies "the content reached the output" into named
thresholds (RULING NEEDED on which name attaches where — this dissolves the
"Lossless over-claims" problem by making the choice explicit):

1. **flows-verbatim** — reference/projection/construction chain only.
2. **flows-recoverable** — chain may include combinations, every step
   recoverable (co-operands constant or themselves emitted; `/`+`%` jointly).
3. **flows-degraded** — content influenced the output through a
   non-recoverable combination (`*0`, lone `/`).
4. **measured-only** — one bit of relational information survives.
5. **absent** — no role at all.

- *Nothing-dropped* (née `Lossless`): every atom ≥ threshold 3, every branch.
- *Content-conserved* (stronger candidate): every atom ≥ threshold 2.
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

# Consequences for the implementation (deferred until ratified)

1. Event vocabulary re-cut to mirror the forms; the drafter's switch goes
   exhaustive — no default case, by construction and forever.
2. Nested construction, nested discrimination, and capture become traced;
   OPAQUE shrinks to release-of-unknown.
3. Combination events gain op-class + recoverability verdicts (from the
   linear kernel's existing knowledge).
4. Per-atom role multisets; fates demoted to views.
5. Properties restated per the threshold ladder; multi-branch `Reversible`
   becomes a theorem application instead of a refusal.

# Open rulings (red-pen targets)

1. **All state names** — including the original consult/combine/emit, which
   may survive as the display names of derived views.
2. **The lossless threshold**: which rung (≥3 influence vs ≥2 recoverable)
   gets the headline name, and what the other is called.
3. **Does measurement count as "use"?** Equivariance ("every output depends
   on every input") plausibly wants threshold 4 to count; conservation does
   not. Two different quantifiers over the same roles.
4. **Capture/release depth for the next slice**: trace through known-lambda
   application, or keep capture-as-leaf initially.
5. **Whether this method note belongs in `backward-language-design.md`** as
   the circular generalization, or stays here as the worked instance.

# See Also

- `conservation-receipts.md` — the feature this algebra underwrites.
- `backward-language-design.md` — the method this generalizes: the loop
  closes; the theory is not layer zero but a layer in the circle.
- `IrExpr` (`pontif-ir`) — the sealed alphabet; the correspondence table's
  left column is its `permits` clause, and must remain so.
