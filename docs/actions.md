
Actions: effects as observation of objective reality
===

Status: DRAFT FOR RED-PEN (2026-06-06). The architecture converged in design
conversation; nothing here is implemented. The queue's substrate — the
Stream trait, the Queue/Array implementor split, the combinator basis and
the one-fold theory — is `streams.md` (drafted the same day; the array
digression resolved open question 1 below: events are stream elements, and
the queue is a Stream whose source is reality). Markers below distinguish what was
**ruled** in that conversation from what is **derived** (follows from ruled
material plus the standing laws) and what is **proposed** (Claude's
suggestion, awaiting a ruling). All surface names — `action`, `when`, the
queue's name, "objective reality" itself — are provisional until ratified
into the glossary.

# Method

Backward design, theory first. The substrate (Truffle/Java) is wildly
effectful; the language's job is to fence that leniency exactly where it
would lie. The ruling insight: an effect is not intrinsically a lie — an
*untracked* effect makes every existing proof artifact (receipt graph,
conservation ledger, No-Halt) retroactively a lie, because all of them are
sound *under pure, strict evaluation*. So the design constraint was never
"no effects"; it is: **no effect may exist that the ledgers cannot see** —
and the strongest way to satisfy it is for effects to never enter the
ledgered world at all.

# The two worlds (RULED)

**Deterministic code is objective reality.** It is pure, strict, fully
ledgered, and — critically — *unannotated*: it contains no effect syntax, no
emit statements, no markers of being observed. A deterministic module
compiles, proves, and means exactly what it would mean if no action code
existed anywhere. Every existing artifact keeps its soundness without
amendment, because the observed code is literally unchanged by being
observed.

**Action code is an observer.** It attaches *externally* — the same
attachment pattern as `proof f = DataConservative()`, which already says
things about a function's data without the function's body knowing. Where a
proof statement is an external *assertion* over a function's dataflow, an
action is an external *reaction* to it. Both observe; neither perturbs.

Between them sits **the queue**: the deterministic side's evaluation writes
observation events to it; the action side consumes them. Effects and
mutability exist only on the consuming side.

# The observer model

## Observation is not duplication (RULED)

Actions exist completely external to the deterministic process. Sending a
value to the queue is not a placement the conservation ledger counts — and
this is provable, not decreed: deterministic values are immutable, so the
tap is *aliasing*, and aliasing immutable data is observationally nothing.
The ledger's no-duplicate law governs placements in the result; the tap
never touches the result. Observer-independence holds with no asterisk.

## Binding by destructuring (RULED)

The observation surface is declared by *pattern*: an action arm names what it
observes with the same destructuring vocabulary as match — patterns are
sorts, sorts carry predicates, so the pattern is simultaneously the
selection ("which events") and the guard ("under what condition") and the
binder ("give me these parts").

## The ordering contract (PROPOSED, leaned-toward in conversation)

The queue's delivery order is deterministic (strict evaluation has a defined
order) but the *contract* promises less, deliberately:

> If event B's value depends on event A's value, A is delivered before B.
> Events on independent dataflow chains are unordered; the queue's
> particular interleaving of them is deterministic but **uncontracted**.

The promised partial order is the dataflow dependency order — which is the
conservation graph, i.e. already objective reality. Anything finer is
implementation gossip. Two payoffs: (1) the reserved freedom is exactly what
a future parallel or reordered evaluator changes, so the contract cannot be
bitten later; (2) reliance on uncontracted order is in principle detectable
from the patterns an action binds — a future compile-time warning.

# The action form: a fold (RULED)

An action is a **fold over the event stream** — a reducer
`(State, Event) → State` written as match arms. The once-hypothesized
"when-monad" (a `Function(T):Boolean` guard plus a `Function(T):T` step)
dissolves into existing machinery: **the Boolean function is the pattern**
(patterns carry predicates) and **the step is the arm body**. No new control
construct exists; `when` is match wearing event clothes.

```pontif
action audit(s:AuditState) {
  when [Transfer(from, _, amt:[Decimal:@>10000])] -> AuditState(s.flagged + 1, s.day)
  when [DayClose()]                               -> report(s)
}
```

Properties, all inherited rather than designed:

- **Match semantics, not dispatch**: arms are ordered, overlap allowed —
  "first matching reaction wins."
- **State is owned exclusively** by the fold and threaded arm to arm. The
  queue serializes delivery, so stateful updates are race-free by
  architecture, not by programmer discipline. `while` is the degenerate case
  where the stream is the fold's own state repeated.
- **Each arm body is itself a pure step** (`State × Event → State`), which
  means the action side is eventually ledgerable by the same drafter, with
  queue edges as its inputs. The observer can be observed.

## The feedback loop (RULED in shape)

Per evaluation, the glass is one-way: observers never influence the
computation they observe. Across evaluations, the fold's state is what the
procedural side hands to the *next* deterministic evaluation as input.
Alternation, not interleaving: reality computes, observers react, reality is
re-invoked with new inputs.

# Proofs license runtime dynamics (RULED)

The generalization the architecture was looking for: **you influence runtime
dynamics by making proofs about the program's logic** — control by theorem,
not by pragma.

The comparison that names it: Haskell's thunk graph is the dataflow DAG,
materialized at runtime, out of compiler ignorance — the runtime must carry
dependency structure as live objects and discover evaluation order by
walking them, paying on every access. Pontif has the same DAG at compile
time, proven and printed: the conservation graph. The thunk graph was never
necessary; it was always the ledger, built at the wrong time by the wrong
party.

The license table:

| Proof | Licensed dynamics |
| --- | --- |
| `NoDuplication` (atom placed ≤ once) | in-place / destructive update in the lowering — mutation as a certificate, not a semantics |
| the ordering contract (dependency partial order only) | parallel or reordered evaluation of independent chains |
| zero unproven residue at a site (see below) | non-evaluation of unconsumed computation — laziness derived from conservation, exactly where it is provably unobservable |
| queue + fold | streaming / unbounded sequences as observation — co-data without thunks |

The language's semantics stay strict and pure. Every entry above is an
*implementation freedom purchased by a proof*, never a meaning change.

# Non-evaluation modes (RULED)

Non-evaluation is observable: a skipped computation that would have diverged
or errored is a behavior change. The ruling that makes the skip license
sound:

> **Every possible non-evaluation mode is identified and acknowledged.**
> The mode taxonomy is sealed; extending it — including extensions to error
> reporting — is a breaking change.

## Error reporting = unproven theories (RULED)

The taxonomy is not a new ontology; it is **indexed by the obligation kinds
the proof system already has**. The universal trichotomy:

| Verdict at compile time | Consequence |
| --- | --- |
| proven | no check, no error mode — and dynamics freedom |
| refuted | compile error; the program never exists |
| unproven | a runtime check remains, and that check's failure IS the error mode |

Every runtime error site is the residue of an undischarged obligation. The
system already says this in one corner — receipt reports print
`NOT DISCHARGED (…; runtime check remains)`, and the construction gate
stamps `runtimeChecks` on exactly the members whose fit was unproven. The
sealed taxonomy is that inventory made first-class: one mode per obligation
sort.

Consequences:

- **An error payload is a counterexample**: `(obligation, witness, site)` —
  the theory that was unproven, the value that escaped it, where. Payload
  shapes are not designed per-mode; they are derived. What action arms
  destructure is a counterexample stream — which is exactly what one feeds
  back to strengthen the program. Error logs are proof TODO lists.
- **A program's possible errors are a static, printable list** (the residue
  report, per function) — checked exceptions' goal achieved without
  infecting a single type signature: accounted in ledgers, not propagated
  through arrows.
- **Skippable = zero residue.** The license is a finite checklist against
  the sealed enumeration, and the dischargers largely exist: match totality
  (mandatory already), construction-gate FITS, refinement receipts,
  overload coherence.
- **The breaking-change rule is self-evident**: extending the mode set
  extends the obligation vocabulary — a logic-version change, not a
  reporting policy.

## Edges of the taxonomy (RULED in conversation, drawn here)

1. **Divergence is the asymmetric member.** Every other mode is an event
   that arrives; non-termination is the absence of one. It is in the
   taxonomy but observed only by its complement, and its discharge needs the
   hard direction — proven-*halting* (No-Halt proves the other direction).
   Conservative default: not provably halting ⇒ not skippable.
2. **Resource exhaustion is outside the contract.** StackOverflow/OOM are
   "the machine failed," not "the program failed" — acknowledged, excluded,
   in the same spirit as partial-correctness quantification over completed
   evaluations. (Were they modes, nothing would ever be skippable.)
3. **Granularity is a load-bearing dial.** Fine-grained modes make skip
   licenses sharper and extensions more frequent; coarse modes the reverse.
   Lean fine-grained pre-1.0 while breakage is free — noting honestly that
   under this regime, the construction gate (landed 2026-06-05) was a
   breaking change, and that this is correct.
4. **Prose is free; shape is contract.** Error *messages* may improve at
   will. The mode set and each mode's payload shape — the fields action
   arms destructure — are versioned.

## Error handling lives in the action layer (DERIVED)

With modes enumerated and destructurable, error handling moves entirely to
observers: `when [DivisionByZero(site, …)] -> …`. The deterministic side
completes or it doesn't; *how it didn't* is part of objective reality.
Supervision, Erlang-shaped, derived from the observer model — and the
deterministic language never grows `try`/`catch`. Its conspicuous absence
was the architecture waiting.

# What stays untouched

- **Receipt graph**: pure code unchanged; values read from the world (the
  feedback inputs) enter as attested OPAQUE atoms — receipts refuse rather
  than lie.
- **Conservation algebra**: no new node kinds, no new clauses. The queue tap
  is off-ledger by the observer-independence argument. (Contrast: the
  rejected inline-`emit` proposal needed "content reaches the return OR a
  declared port" — this architecture needs nothing.)
- **No-Halt / the recursion fixpoint**: untouched; observation does not
  alter call structure.
- **Match vs dispatch**: actions are match-semantics, confirming the
  distinction rather than straining it.

# Open questions (for red-pen)

1. **Event granularity.** Which evaluation moments produce events —
   constructions of named types? function returns? every binding? A pattern
   can only select from what flows. (Candidate: constructions and returns —
   the Construction node kind — since "emission is construction of the
   result" per the conservation algebra; this would make the queue precisely
   the runtime shadow of Construction nodes.)
2. **Attachment surface.** Where does action code live — same module,
   sibling module, a `requires`-like header naming the observed module? Ties
   into the requires/exports/emits vocabulary.
3. **Re-entry form.** The concrete shape of "the fold's state feeds the next
   evaluation" — a designated entry function? the action naming its target?
4. **Names.** `action`, `when`, the queue, "objective reality," "residue,"
   mode names. All provisional; glossary entries follow ratification.
5. **The mode enumeration itself** — the first concrete listing of
   obligation sorts as modes, payload shapes derived per the counterexample
   rule.

# Slices (proposed)

0. **This document ratified** (red-pen pass; rulings above confirmed or
   corrected).
1. **The queue and one fold, interpreter-only**: constructions of named
   types as events; one `action` with `when` arms over a running program;
   state threaded; no feedback yet. Reviewable artifact: a printed event
   log next to the printed ledgers.
2. **The mode taxonomy, sealed**: the obligation-sort enumeration, error
   events on the queue with counterexample payloads, the per-function
   residue report.
3. **Feedback**: fold state as next-evaluation input; the alternation loop.
4. **First license**: `NoDuplication` → in-place lowering (or the ordering
   contract → parallel evaluation, whichever has the smaller honest slice).

Each license thereafter is its own slice with its own proof-hypothesis
statement, per the breaking-change discipline: a license names the taxonomy
version it was issued against.
