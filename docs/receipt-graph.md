# Receipt-graph and notary

How Pontif represents refinement obligations as a data structure, the
components that operate on it, and the relationship between the graph
itself and the closing receipts produced *about* it.

Three components, two artifacts:

- **Drafter** — produces a receipt-graph deterministically from source.
  Immutable language component. Lives in `pontif-receipts`.
- **Issuers** — produce closing receipts *about* a receipt-graph.
  Pluggable: built-in default, oracle modules (Z3, custom, AI),
  hand-written.
- **Notary** — verifies. Immutable language component.

The two artifacts:

- **Receipt-graph** — ground truth, immutable. The drafter's output.
- **Closing receipts** — external artifacts that reference *into* the
  graph but never extend it. Each one carries an issuer identifier, a
  conclusion (the claim), and arbitrary issuer-specific payload.

See `glossary.md` for terse definitions; `TODO.md` priority section for
the work-in-progress slice.

---

## Framing

Pontif is a sophisticated *type system*, not a proof assistant.
Refinement sorts (`[Int:@>0]`, `[Point:@.x + @.y > 0]`, …) carry
obligations: the compiler should know whether a value at a given site
satisfies its declared refinement. Those obligations meet a well-defined
structure — the **receipt-graph** — that is the contract between Pontif
and the world.

Three load-bearing claims:

- **Validity flows from the issuer.** The notary doesn't *prove*; it
  *fails to refute*. A closing receipt is accepted because nothing on
  the relevant path of the graph contradicts its conclusion, not
  because the notary independently derived it.
- **Drafter and notary are immutable; issuers are pluggable.** Drafter
  and notary change only via Pontif language upgrades. Issuers are
  extension points: Pontif ships a default trusted issuer, third
  parties may bring more (Maven-plugin style, eventually), users may
  disable or distrust any of them.
- **Snake oil is allowed but flagged.** If you trust an oracle module,
  Pontif won't fight you. Its closing receipts sail through the notary
  when nothing refutes them. If the oracle has a bug, expect runtime
  errors — runtime checks remain the safety net.

---

## The receipt-graph

A **receipt-graph** is a directed graph whose nodes are call sites and
whose edges encode control flow plus recursion. Each node carries:

- A parameter-constraint header (e.g., `factorial(n_0:[Int:@>=0])`).
- A result variable (`r_0`) with the declared return refinement.
- Children — one per arm of an internal `match`, plus back-references
  for recursive calls.

**Initial / body receipts** are attached at leaf positions on a path.
Transcribed *deterministically* from the source: body equations
(`r_0 = n_0 * r_1`), arm guards (`n_0 > 0`), literal bodies
(`r_0 = 1`). The drafter produces these as part of graph construction.

The receipt-graph is **one artifact, immutable**. Once the drafter
produces it, it doesn't change. There are no "two stages"; there is
just the graph. Closing receipts are produced *separately* by issuers
(see below) and reference into the graph but never extend it.

### The no-duplicate-edges rule

Nodes may appear multiple times in the graph (the same function
referenced at the top *and* inside its own body) — but each edge
appears once. A recursive call doesn't expand the called node again;
it points back to the existing one. Same node, same logical entity,
observed at a substituted argument.

This rule does real work:

- **Well-foundedness becomes a graph property**, not a separate proof
  rule. Recursion cannot unfold infinitely.
- **The back-reference carries the postcondition along** as an
  inductive hypothesis automatically. No separate "assume `r_1 >= 1`
  inductively" step — the cycle in the graph encodes it.

### Worked example — factorial

```pontif
function factorial(n:[Int:@>=0]):[Int:@>=1] -> match n
  [@==0] -> 1
  [@>0 ] -> n * factorial(n-1)
```

**Receipt-graph (drafter's output, immutable):**

```
factorial(n_0:[Int:@>=0])  :  r_0:[Int:@>=1]
  │
  ├── arm [n_0==0]
  │     └── initial receipt:  { r_0 = 1 }
  │
  └── arm [n_0>0]
        ├── (back-reference) factorial(n_1 := n_0 - 1) : r_1
        └── initial receipt:  { r_0 = n_0 * r_1 }
```

**Closing receipts (separate; one per discharged obligation):**

```
issuer:     <pontif-default>
reference:  factorial / arm [n_0>0]
conclusion: { r_0 >= 1 }
payload:    (issuer-specific; opaque to notary)
```

The notary's job, given this graph + this closing receipt: confirm the
graph's skeleton matches a fresh draft from source (it does), then
locate the referenced point in the graph and check whether anything on
that path contradicts `conclusion`. On the `[n_0>0]` arm the path
receipts are `n_0 > 0` (guard), `r_0 = n_0 * r_1` (initial), and the
back-reference's IH `r_1 >= 1`. Together: `r_0 = n_0 * r_1 >= 1`.
Nothing contradicts `{ r_0 >= 1 }`. Accepted.

If the issuer had produced `conclusion: { r_0 < 0 }`, the notary would
refute (since `n_0 >= 1 ∧ r_1 >= 1 ⊢ n_0 * r_1 >= 1`, contradicting
`r_0 < 0`). If the issuer produced something the notary couldn't refute
*and* couldn't independently re-derive, it'd be accepted as snake oil
— flagged.

### References into the graph (intentionally underspecified)

A closing receipt needs to point at *where* in the graph its conclusion
applies — a node, an arm, a path. The exact reference shape (node ID,
structural path, name + arm index, etc.) is **deferred** until the
3rd-party-issuer story is clearer. For the v1 built-in default issuer,
direct object references suffice (same-JVM). When 3rd-party issuers
land, this becomes a serialization-and-stability concern. The
receipt-graph types should keep this point thin and replaceable.

---

## The drafter

Pontif's built-in deterministic component that produces receipt-graphs
from source. **Single job**, immutable, no reasoning.

- Walks the IR per function, transcribes the body into a receipt-graph,
  emits initial receipts at the leaves (body equations, arm guards,
  the literals encoded directly).
- Recursive calls become back-references — never re-expansions — by
  the no-duplicate-edges rule.
- Output is a self-contained data structure (lives in
  `pontif-receipts`) that issuers consume and the notary uses for
  verification.

The drafter is the only component that touches `IrModule`; downstream
consumers (issuers, notary) operate on the receipt-graph, not on IR.
The drafter is not pluggable: it changes only across Pontif language
versions.

The drafter is invokable **standalone** — given an `IrModule`, produce
a `ReceiptGraph`. This makes it usable for purposes beyond compilation
(IDE-driven analysis of code snippets, on-demand re-drafting for the
notary's skeleton-match check).

---

## The notary

Pontif's built-in verifier. **Existence and consent, not correctness.**
Three independently-invocable verifications:

1. **Graph exists.** A receipt-graph is present for the code under
   examination.
2. **Skeleton match.** Given a receipt-graph, the notary asks the
   drafter for a fresh receipt-graph from the same source and confirms
   they agree. Guards against tampered or stale graphs (e.g., cached
   output from a stale source).
3. **Hypothesis support (closing-receipt verification).** Given a
   receipt-graph + a closing receipt, the notary reads
   `(issuer, conclusion)` from the closing receipt, locates the
   referenced point in the graph, walks the relevant path (arms are
   alternatives, not conjuncts), and checks whether anything
   contradicts the conclusion. The closing receipt's *other* payload is
   opaque to the notary — it exists for 3rd-party consumers (audit,
   proof certificates someone else might verify, debug info).

   If a contradiction is found, the closing receipt is **rejected**.
   Otherwise it's **accepted** — and accepted does not mean validated;
   it means *not refuted yet*.

The notary never confirms validity. Only refutes. The asymmetry is what
makes the design tractable: a small refutation kernel can admit
arbitrary issuers without knowing their reasoning.

Like the drafter, the notary is not pluggable; it changes only across
Pontif language versions.

---

## Issuers and oracles

An **issuer** is the role that *produces closing receipts about* a
receipt-graph. Each closing receipt carries:

- **issuer info** — who/what produced it. Read by the notary for
  trust-by-attribution.
- **conclusion** — the claim being made (e.g., `{ r_0 >= 1 }`). Read by
  the notary for refutation check.
- **reference** — points at *where* in the graph the conclusion applies
  (see "References into the graph" above; intentionally underspecified
  for now).
- **arbitrary payload** — opaque to the notary; for 3rd-party
  verifiers, audit trails, debug info, whatever the issuer wants to
  include (e.g., a Z3 proof certificate someone else could check).

**Issuers don't change the graph.** They produce separate closing-receipt
artifacts that reference it.

Three flavors of issuer:

- **Built-in default issuer.** Pontif ships this. Uses `SignAnalysis` +
  equality + what's already in `Refinements`. Handles `x*x >= 0`,
  sign-trichotomy match-totality, simple equational closings.
  **Trusted by the notary by default**, but the user may disable or
  distrust (closings still go through normal refutation; the trust
  shortcut is gone).
- **Oracle modules.** Third-party issuers the user installs and trusts.
  Examples: Z3, custom solvers, AI provers. Integration is Maven-plugin
  style. *Deferred — gated on Pontif's not-yet-designed
  package-management / build tool. v1 may ship without 3rd-party issuer
  support at all; runtime checks remain the fallback.*
- **Hand-written receipts.** Literal escape hatch — the user writes a
  closing receipt directly. Indistinguishable from any other issuer's
  output to the notary; flagged because no automated source recognized
  it.

### Configuration and overlap (deferred)

Issuers will be configurable per-module / per-file / per-region, with
overlap allowed. Issuers don't critique each other or the notary; the
notary verifies whichever closings end up in play. Spec deferred to the
issuer-plugin work.

### Closing modes

An issuer can be invoked in two modes:

- **Eager close** — derive whatever closing receipts the issuer can
  from the graph, with no specific target.
- **Hypothesis-driven close** — close in service of a specific
  hypothesis ("close in a way that supports `{ r_0 >= 1 }`"). The
  notary's hypothesis-support verification then has a concrete claim
  to check.

### Snake oil

A closing receipt the notary can't refute *and* can't independently
re-derive. Accepted (refutation came up empty) but flagged. Allowed;
discouraged. Becomes "authentic by attribution" once a Proof Authority
signs it.

### Proof Authority (PA) — roadmap

A trusted issuer whose receipts are accepted by *attribution* rather
than by independent validation. Mirrors how Certificate Authorities
work without literally being them. Under this model, snake oil becomes
a *status* — receipts from any issuer not in the trusted set — rather
than a *class* of receipt.

Pontif could ship a default trusted set (built-in default issuer, Z3
once landed) and let users register project-specific PAs. Roadmap goal;
the trust-by-attribution framing is the natural extension once oracle
modules exist.

---

## Orthogonal axes (naming)

Three component roles, each verb-form:

- **Drafter** *drafts* — produces the receipt-graph.
- **Issuer** *issues* (colloquially: *closes*) — produces closing
  receipts about a graph.
- **Notary** *notarizes* — verifies. Doesn't produce.

Two receipt kinds, in two locations:

- **Initial / body receipts** — produced by the drafter, *inside* the
  receipt-graph.
- **Closing / derived receipts** — produced by an issuer, *separate*
  from the receipt-graph (referenced, not embedded).

"Closer" (verb-form noun for what an issuer does) is fine colloquially,
but in formal docs use *issuer*. Two collisions argue against "closer"
in writing: the `Closure` IR value type, and the comparative
"more close" in prose.

---

## What's *not* in scope

- **Not a full proof system.** Pontif's built-in default issuer handles
  the trivial fragment. Anything richer needs an oracle module.
- **Not a soundness check on foreign issuers.** If your Z3 binding has
  a bug, Pontif won't catch it. The notary checks for contradictions
  *with the graph*, not against an external ground truth.
- **Drafter and notary are not pluggable.** They're part of the core
  language, not extension points. Only issuers are.
- **Runtime errors are the safety net.** When compile-time discharge
  fails (no closing receipt, refuted closing receipt, or no trusted
  issuer for the code in scope), the runtime check stays in place. The
  promise is "compile-time wins when possible, runtime errors
  otherwise" — not "compile-time wins always."

---

See `docs/glossary.md` for term definitions. See `docs/TODO.md` priority
section ("receipt-graph + sweep the trivial cases") for the
work-in-progress slice.
