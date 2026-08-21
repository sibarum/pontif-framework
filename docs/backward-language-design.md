# Pontif: Backward Language Design

#. Start by implementing language features in the execution AST (Truffle)
#. Then extend the IR with those new features, with slightly different semantics to simplify or abstract features as desired.
#. Lower the IR down to the reference language - doing the absolute minimum to gain support for the feature.
#. Finally, use the reference language output to guide design of the primary language — the surface syntax people actually write.

It's ok to hypothesize about language features ahead of time, but none of this should be load-bearing or contractual.
Here's why:

* This is a highly experimental language. Simplicity of runtime is paramount.
* The only complexity should be in the complex language features: type narrowing, multi-dispatch, etc. Not in kitbashing features.
* The final parser->IR->AST should be a clean flow.
* The language syntax should never fight with the runtime implementation. They should fit like a glove.
* The primary language should be thought of as one big syntactic sugar for the reference language.

## Generalized (2026-06-02)

The stack above starts at the execution AST. In practice a deeper layer sits
beneath it: the **theory** — information conservation (COTT) and the algebraic
kernel (lattices, refinement implication, complement/satisfiability). The
backward method generalizes accordingly:

* **Implementations flow upward**: theory → kernel → IR → reference language →
  primary syntax. Surface features desugar into the small closed core before
  either backend sees them — the cheapest features are pure sugar (literal
  field patterns, rename binders, destructuring let touched no backend at
  all), and cost rises only when the kernel itself is extended (a new value
  leaf, a new operator).
* **Decisions flow upward too**: a surface design question descends to the
  deepest layer with jurisdiction and comes back answered. Refutable
  let-patterns — answered by the match-totality rule. `Int → Decimal`
  promotion — answered by losslessness (promote only the conserving
  direction). Truncating `/` — acceptable only jointly with `%`, by the
  recovery identity `a == (a/b)*b + a%b`. A question no layer can answer yet
  stays parked: hypothesized, never load-bearing.

The original tenet — the primary language is one big syntactic sugar for the
reference language — generalizes to: **the whole language is one big syntactic
sugar for the theory.** When that holds, powerful borrowed machinery can be
given the freedom to cook without making a mess: its preconditions are fenced
(the soundness gates — integer-strictness quarantined in `BoundAnalysis`,
decimals routed to the dense discharger), so its compositionality survives
contact with the language. The fences must be load-bearing in code, not prose
— each principle gets a tripwire test (the Int/Decimal discreteness pair, the
no-shadowing pin, the provable-vs-refutable let pair).

