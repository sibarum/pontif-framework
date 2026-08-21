> **ARCHIVED — predates current Pontif design.** This sketch was
> written under an earlier project name ("SPN") and uses concepts and
> framing that do not match the current language. Kept for historical
> context only. The canonical current docs are
> `docs/language-reference.ptf`, `docs/glossary.md`, `docs/traits.md`,
> `docs/receipt-graph.md`, and `docs/backward-language-design.md`.

---

# Symbols + Numbers (SPN)

*A proof-carrying programming language where correctness is compiled, not tested.*

*NOTE: All references to SPN in this document should be ignored, this file does not describe SPN.*

---

## What SPN Is

SPN is a programming language with a built-in AI proof engine. You write code. SPN proves it correct. If it can't prove it, it doesn't compile.

Not "we ran a lot of tests and it looked fine." A mathematical proof, issued as a receipt, checkable by anyone, composable with the receipts of every library you depend on.

SPN unifies three things that have never been unified before: a **total, reversible type system** that accounts for information loss at the signature level; a **lazy action monad** that makes proofs about infinite and mutable structures tractable; and an **AI proof engine** that discharges closure obligations automatically, so you declare intent and the compiler proves consistency.

---

## The Foundation

### Everything is an Endofunctor or a Pattern Matcher

SPN has two primitive building blocks.

**Endofunctors** are the unit of computation. An endofunctor `F : C → C` composes naturally — sequential actions are functor composition, infinite structures are fixed points `μF`, and the grade algebra that tracks information loss is implicit in how functors compose. You don't define a separate algebraic tower for invariant tracking; the type system already is one.

**Pattern matchers with default branches** are the unit of case analysis. Named branches handle what you've explicitly accounted for. The default branch is the loss certificate — a typed proof that everything not named has been deliberately discarded. Exhaustiveness is guaranteed by construction. The compiler knows exactly what was thrown away and why.

Everything else — numbers, graphs, concurrent actions, proof obligations — is built from these two things.

### Totality and Reversibility

Every function in SPN is total. No partiality, no bottom, no runtime exceptions from unhandled cases. Pattern matching is exhaustive by construction; the default branch makes sure of it.

Every function is also reversible, in the precise sense: a function `f : A → B` that discards information compiles only if you supply a loss certificate `λ : A → L` such that `(f, λ) : A → B × L` is a bijection. The compiler checks the bijection. The AI finds `λ` when it can. If neither can produce it, the function doesn't exist as written.

This is not a constraint layered on top of computation. It is the algebra of computation. The intermediate representation is always bijective. Irreversible functions are syntactic sugar for bijections with explicitly typed garbage outputs.

### The Abstract Numeric Type

Numbers in SPN are not a primitive — they are a fixed point of a structured endofunctor, carrying their algebraic properties in the type. Subtraction requires a proof that the subtrahend is bounded by the minuend. There is no underflow at runtime because there is no subtraction without a certificate at compile time.

The numeric type is what makes grade algebra concrete. Loss grades, step bounds, concurrency bounds — all of these are numeric values whose arithmetic the compiler reasons about directly, without evaluating the program.

---

## The Action Monad

Every proof, every effectful computation, every mutation in SPN begins with an **action**. The action monad is the seam between the pure bijective kernel and the world of lazy evaluation, mutable state, and observable effects.

```java
action bounded_update(Graph g, Numeric<Count> limit)
    where limit.witnessed(g.countX() ≤ limit) {
    // The compiler knows the bound holds here.
    // The AI proved it. The receipt says so.
}
```

The `witnessed` call is syntactic sugar for demanding a proof certificate inline. The AI fills it in when the proof is tractable. When it isn't, it tells you precisely which closure condition is missing — not a runtime assertion, not a test failure, a typed statement of what remains unproved.

### Laziness Is Not a Feature. It Is the Architecture.

SPN is lazy in the sense that matters: branches that are never forced never produce loss. The loss certificate for an untaken branch is trivially empty because the projection never fired. An observer who never forces a branch has no information loss relative to it.

This is the causal frame principle made computational. Information loss is not absolute — it is relative to an observer with a particular causal access. The action monad is parameterized by that frame. The grade tracks what is knowable from where.

### Graded Actions Compose

The type of a sequenced action carries the composed grade:

```
action[π₁] >>= action[π₂] : action[π₁ ⊗ π₂]
```

Loss accumulates in the type. The compiler rejects programs that undercount information loss at the signature level, before any code runs. The AI searches for the minimum grade that closes the composition — the tightest honest accounting of what the program discards.

---

## The AI Proof Engine

The AI in SPN is not a suggestion engine. It is the compiler's closure oracle.

You declare the transformation character of your code: what survives, what is discarded, what is invariant, what is transformed. That declaration requires domain knowledge — only you know what matters. But whether those declarations are *consistent* is a mechanical question about the TypeGraph, and the AI answers it.

### The TypeGraph

Every SPN program maintains a TypeGraph: a graph whose nodes are types and whose edges are actions with grade annotations. Invariant proofs are path properties of the TypeGraph. "The total count of X never exceeds N" is a constraint on all paths through mutation nodes. The AI checks it by graph analysis, not by simulation.

This is why SPN can prove properties of infinite data structures. You never instantiate the infinite structure. The proof lives in the grade algebra, which is finite even when the structure isn't.

### A* Over Proof States

The AI's search is A* over the TypeGraph:

- **Nodes** are partially elaborated proof terms with their grade signatures
- **Edges** are endofunctor actions — rewrite steps, pattern match expansions, branch forcings
- **Cost** is accumulated loss grade
- **Heuristic** is tree edit distance under stable tree equality — an admissible bound on remaining rewrite cost

Stable tree equality gives the search its decision procedure: two proof states that reduce to the same normal form under the rewrite system are identified, preventing the search from re-exploring equivalent states. The laziness of the action monad and the A* forcing order are the same mechanism — the search decides which branches to force, and the monad handles the forcing.

The AI finds the proof with minimum information loss, or it reports exactly why no proof exists within the declared grade bounds.

---

## Receipts

Every successful compilation produces a **receipt**.

```
Receipt<
    Transform<BoundedGraphUpdate>,
    Loss<transient_metadata>,
    Preserved<node_count_invariant>,
    Steps<≤ 3n>,
    Sync<Causal(receipt_init)>
>
```

A receipt is not a log. It is a typed artifact in the SPN kernel — a term whose type encodes everything the compiler proved about the program. Anyone with the kernel can check it independently. No trust required.

Receipts compose:

```
receipt_A ⊗ receipt_B → receipt_AB
```

If you have proved closure over transformation A and transformation B separately, composing their receipts gives you closure over the composed transformation without rerunning the AI. The proof is already done. You are combining certificates.

Ship the receipt with the library. Downstream users do not trust your code. They verify your receipt.

---

## What SPN Proves

### Invariants Over Mutable and Infinite Structures

The grade algebra tracks what changes across a transformation at the type level. "The total number of X never exceeds N" is checked by composing grade signatures, not by traversing the structure. This works for infinite graphs, infinite streams, and any coinductive structure. The proof never instantiates the data.

### Finite Time Completeness

Termination and bounded termination are distinct type signatures in SPN:

```haskell
-- Termination: the computation halts
action terminate : A →[μF] B

-- Finite time completeness: the computation halts within a proved bound
action complete  : A →[μF, steps ≤ n] B
```

The step bound emerges from the A* search: the path length of the closure proof is the complexity certificate. Correctness and complexity are proved simultaneously, in the same search, by the same engine.

### Concurrency Correctness

The synchronization prior module instantiates the grade algebra for concurrent programs:

```ocaml
type prior =
  | Exclusive                      (* write lock *)
  | Shared                         (* read lock *)
  | Unordered                      (* no sync — loss certificate required *)
  | Causal of receipt * bound      (* happens-after, proved by receipt *)
  | LockFree of contention_bound   (* lock-free with proved contention bound *)
```

Two actions with incompatible priors on the same node fail at the grade composition step. Deadlocks are cycles in the causal receipt graph — type errors, found by the A* search before any code runs. Atomicity violations fail the closure proof. Race conditions, deadlocks, and atomicity violations are compile errors, not runtime behaviors.

The concurrent receipt proves not just that each thread is individually correct but that their interaction is correct.

---

## The Syntax

SPN borrows syntax from three languages, each donating its strongest feature.

**OCaml** provides the module system and functor syntax. OCaml functors are literally endofunctors; the syntax already means the right thing. Module-level structure, variant types, and the logic layer are written in OCaml style.

**Haskell** provides pattern matching, type inference, and do-notation for the monad sugar. The grade algebra and type-level reasoning are written in Haskell style.

**Java 25** provides sealed classes, records, and switch pattern matching for the action layer. Sealed interfaces map directly onto exhaustive pattern matching with default branches. Actions look like familiar imperative code. The proof obligations are invisible unless they fail.

```java
// Java 25-style action declaration
sealed interface Node permits CriticalNode, Metadata {}

action transform_graph(Graph g) {
    case CriticalNode n => preserve(n)
    case Metadata m     => drop(m)          // loss certificate: metadata
    case _              => transform(this)  // AI proves closure over remainder
}
// Compiles only when the AI can prove closure.
// Receipt issued on success.
```

The kernel never surfaces. Users write Java-flavored actions over Haskell-flavored types inside OCaml-flavored modules, and the proof obligations are discharged invisibly unless they cannot be met — at which point the AI reports precisely what is missing, in the terms of the layer the user was writing in.

---

## Why Not Tests

Tests are examples. Examples cannot prove universal properties. Ten thousand passing tests say nothing about input ten thousand and one.

SPN proofs are universal. The receipt does not say the invariant held for the cases we tried. It says the invariant holds for all inputs, by construction, and here is the proof.

The effort curve is also inverted. Test suites grow with system complexity — more cases, more maintenance, more false failures. SPN receipts compose: proofs already done do not need redoing. Complexity feeds the proof engine rather than fighting it. The larger the system, the more the AI has to work with.

---

## Status

SPN is implemented in the `sibarum.strnn` namespace. The core primitives — HPB, KSQP, TractionQuaternion, Rigid Kuramoto oscillators — are active development targets. The proof assistant architecture described here is the theoretical foundation the implementation is converging toward.

Licensed under Apache 2.0 (code) and CC-BY (technique descriptions).
