# Parser / builder / linker refactor — killing the "requires bugs"

A work-order for consolidating a recurring class of defect in the **parser's declaration
dispatch** and the **linker's pipeline routing**. These are the bugs that keep recurring with a
different shape each time a new top-level construct is added (`action`, `conduit`, `conductor`,
`spawn`, …): a cross-cutting fact about the *set of top-level constructs* is duplicated across
the pipeline as hand-maintained lists / ad-hoc predicates, and nothing makes the copies agree.

## Not the type-system consolidation

This is a **sibling** effort, not part of the type-system convergence (the one-inference-engine
`NarrowingInference` work, and the parser↔type-system decoupling). It touches none of the same
code — only declaration dispatch and pipeline routing. It shares the *philosophy* the rest of the
codebase already lives by — **one source of truth + a sealed exhaustive `switch` that forces
coverage** — and the fix in every case below is to bring a spot that opted *out* of that
discipline back under it.

## The meta-problem

Whenever the code needs to know "the set of top-level constructs" (or a property of it), it should
derive that from **one** place, ideally so the compiler forces every construct to be classified.
The defects below are all spots where that isn't true today.

---

## Item 1 — link-gate classifier — DONE (2026-08-05)

**Symptom:** the "does this module need linking vs. bare pass-through?" decision was duplicated as
two independent `anyMatch` predicates — `ModuleResolver.resolveAndCombine` and
`ModuleLinker.combineSingle` — each hardcoding `hasRequires`. Adding `spawn` required updating
both; missing `ModuleResolver` let a `spawn`-only program silently skip seating.

**Fix (landed):** a single source of truth, `ModuleLinker.needsLinking(IrModule)`, backed by
`triggersLink(IrStmt)` — an **exhaustive** switch over the sealed `IrStmt` (no `default`). Both
gates delegate to it. Adding a new statement kind now fails to compile until it is classified
link-triggering or not, so this hole cannot silently reopen.

**Possible follow-up:** the two gates are now drift-proof but still redundant (`combineSingle` is
only ever reached once `resolveAndCombine` already decided to link). Collapsing to a single gate
would change `combineSingle`'s public contract (it would link even a bare module), so it was left
alone — revisit only if a caller other than `resolveAndCombine` appears.

---

## Item 2 — declaration-keyword registry (TODO)

**Symptom:** three lists that must agree, none checked against the others, in `PontifParser`:
- `KEYWORDS` (`PontifSexprParser.java:79`) — the superset (also holds `match`/`emit`/`true`/…);
- the decl-head set inside `isMainExpressionStart` (`PontifSexprParser.java:585`) — the *exact* declaration
  keywords;
- the `parseDeclaration` switch cases (`PontifSexprParser.java:644`).

The decl-head set and the switch must be **identical**; drift gives the
`"unexpected keyword 'X' in expression position"` error (hit when adding `conductor`, whose keyword
was added to the switch + `KEYWORDS` but initially not to the decl-head set).

**Proposed fix:** one registry for declaration keywords, e.g. a
`Map<String, Supplier<IrStmt>>` (keyword → parse method):
- `isMainExpressionStart` derives its set from `registry.keySet()`;
- `parseDeclaration` becomes a lookup in the same map (its `default` stays the "not a top-level
  declaration" error);
- one entry per construct, zero drift.

**Lighter alternative** if restructuring the switch is undesirable: a single
`DECLARATION_KEYWORDS` constant used by both `isMainExpressionStart` and (asserted against) the
switch, plus a unit test that `DECLARATION_KEYWORDS ⊆ KEYWORDS` and that the switch covers exactly
`DECLARATION_KEYWORDS`.

---

## Item 3 — `default ->` on `IrStmt` switches silently swallows new kinds — DONE for the rewriter passes (2026-08-25)

**Three live defects, not a latent trap.** The prediction below was that this was harmless
*today* and would bite when a later cut relied on it. It was already biting. Auditing the
four rewriting passes found:

1. **A return proof in a required module was rejected outright.** `NameResolver` never
   FQN-rewrote `IrStmt.ReturnProof` — a kind the stale comment did not even list — so an
   `assign proof f(…)` kept its bare target name while the function it proves became
   `mod/f`. `ReturnProofBinding` then refused a valid program with *"assign proof
   references unknown function 'isSparse'"*.
2. **A conductor state initializer calling an imported function died at runtime** with
   "No function named 'startAt' is declared". Initializers are compiled — `IrCompiler`
   builds the conductor's state seed from them — so this was never dormant.
3. **A conductor state initializer containing a method call failed to compile** with the
   internal-sounding *"MethodResolver must eliminate MethodCall before IrCompiler"*: the
   pass meant to eliminate it never looked inside a conductor.

**What landed.** `NameResolver`, `DestructureResolver`, `StructLiteralRewriter` and
`MethodOperatorResolver` are exhaustive over `IrStmt` — every kind either handled or
explicitly declared to need nothing, so the next kind cannot be added silently.
`ConductorDecl.mapStateInits` is the one home for "which part of a conductor a
body-rewriting pass must visit". A coercion body is now destructure-rewritten too (it was
in the same `default`, while `StructLiteralRewriter` had already singled it out).

**A boundary worth recording:** the passes must NOT rewrite a conductor's *reactions*.
Seating injects them as ordinary `FunctionDecl`s before these passes run, so the copies
that are compiled go through the normal function arm with the parameter scope that makes
`this.n.apply(…)` resolvable; rewriting the conductor's own copies out of that context
fails with "Cannot determine the type of the receiver". That is how the boundary was found.

**Verification (the work-order's own suggestion, and it works).** Adding a throwaway
`IrStmt` permit fails the build at these sites. Note that javac reports **one file per
compile round** here — satisfy the site it names and recompile to see the next. Tests:
`StatementKindResolutionTest` (4).

### The original entry (kept — its diagnosis was right)

**Symptom (the flip side of the sealed discipline):** ~15 `switch (stmt)` sites over the sealed
`IrStmt` use a `default ->` clause. Unlike an exhaustive switch, `default` means a **newly added
`IrStmt` kind is silently ignored** rather than flagged by the compiler — the opposite of the
"the compiler enumerates every site I must touch" property that made adding `ConductorDecl`/`Spawn`
safe at the *exhaustive* sites.

Concrete latent trap already present: `NameResolver.java:113`
```java
default -> stmt;  // Requires / Exports / NoOp unchanged   <-- comment is now stale
```
This silently also catches `ConductorDecl` and `Spawn`, passing them through **unresolved**. It is
harmless *today* only because seating injects a conductor's reactions into the statement list
*before* `NameResolver` runs (so they resolve as ordinary `FunctionDecl`s). The moment a later cut
relies on `NameResolver` resolving a `ConductorDecl`'s own reactions / state initializers (mutable
state, Fork B), this becomes a silent bug.

**Proposed work:** audit each of the ~15 sites and decide, per site, whether it should be
**exhaustive** (replace `default` with explicit `case … -> {}`/pass-through arms, so future kinds
force a decision) or whether `default` is genuinely correct (the site legitimately only cares about
one or two kinds and new kinds are always irrelevant). At minimum, make the *rewriter/resolver*
passes exhaustive (`NameResolver`, `AliasResolver`, `DestructureResolver`, `StructLiteralRewriter`,
`MethodOperatorResolver`) — those transform statements and a silently-dropped kind is a correctness
bug, not a cosmetic one. Fix `NameResolver`'s stale comment either way.

Sites with `default ->` and an `IrStmt` case (from a grep of `pontif-ir` main):
`AggregatePromotion`, `CallGate`, `CallNameCheck`, `CastGate`, `ConstructionGate`,
`DecimalPromotion`, `DestructureResolver`, `EffectiveSortLens`, `IrCompiler`, `IrPrinter`,
`IrSourcePrinter`, `MethodOperatorResolver`, `ModuleSymbolTable`, `NameResolver`,
`StructLiteralRewriter`. (Note: `IrCompiler`/`IrPrinter`/`IrSourcePrinter`/`ModuleSymbolTable`
were made to handle the new kinds already — but via cases added *next to* a `default`, so they are
not compiler-forced for the *next* kind. Consider dropping their `default` to lock coverage.)

---

## Item 4 — reference parser divergence (TODO / decision)

**Symptom:** the S-expression reference `SexprParser` (`SexprSexprParser.java`) — documented in the README as
"the ground truth the test suite is written against" — contains **none** of the effect /
orchestration surface: a grep for `action` / `conduit` / `conductor` / `spawn` / `emit` returns
zero. That entire subsystem is expressible only through `PontifParser`.

**Decision needed:** is the reference parser meant to track every surface construct (in which case
it is already well behind — this predates conductors), or has it been intentionally frozen to the
pre-effects language as a stable IR-shape oracle? If the former, it needs the effect/orchestration
constructs added; if the latter, the README's "ground truth" claim should be narrowed to say so.
Either way, "two parsers, one IR" is currently "one-and-a-fraction parsers, one IR."

---

## Item 5 — native sink registry has no enumeration (TODO — minor)

`NativeFunctions` (`pontif-ir`) can be queried only by exact event-type name (`get(name)`); it
exposes no `keySet`/iteration. Any future logic that needs "the set of native effect sinks" (e.g.,
a static dead-letter/coverage analysis over the emits-interface, or listing available sinks)
cannot get it without adding an accessor. Low priority; noted so it isn't rediscovered.

---

## How to verify a fix here

- Item 1/2/3 fixes should be provable by the compiler: after the change, adding a throwaway
  `IrStmt` permit (or declaration keyword) should **fail to compile** at every site that must
  handle it — that failure *is* the guarantee. Revert the throwaway once confirmed.
- Behavioral regression: the full `pontif-ir` + `pontif-parser` + `pontif-runtime` suites
  (`spawn`-only linking is covered by `ConductorSeatingTest.spawnOfUnknownConductor_isACompileError`,
  the no-`requires` link path).
