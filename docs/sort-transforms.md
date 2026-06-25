# Sort-transforms: logic in sorts, effects by shell

Status: **DESIGN RULED 2026-06-25; SLICE 1 LANDED.** Converged in a design
conversation with James. Builds on the just-landed default-method implementations
(`traits.md` → "Default method implementations").
Markers: **RULED** (James ruled it in conversation), **DERIVED** (follows from
ruled material plus the standing principles), **PROPOSED** (Claude's suggestion,
awaiting a ruling). All surface names — "shell", "kernel", "sort-transform", the
eventual spelling — are provisional until ratified into `glossary.md`.

**Implementation status.**
- **Plain-function shells — ALREADY SHIPPED** (pre-dates this doc): the S2
  return-clause + S7 param-conversion arrow-unification work runs
  `function foo(bar:[Int]):[Int -> @+"" -> String] -> bar*2` and
  `f(bar:[Int -> @+1 -> Int]):Int -> bar` today (`ClauseChainTest`). That is the
  substrate (apply a clause-chain to a value, in or out), lowered to
  `Lambda`/`Closure` + `Apply`/`LetIn`.
- **Slice 1 — trait-owned RETURN shell — LANDED 2026-06-25** (`TraitReturnShellTest`):
  a trait method's return may be a clause-chain shell `[C -> … -> D]`; callers see
  the terminus `D`, the impl's kernel returns the domain `C`, and
  `TraitDefaultExpansion` wraps every impl/default kernel with the shell (the impl
  can't change it). Composes with default bodies. Parser stores the shell in
  `IrSort.Trait.returnShells`; the wrap is `Apply(shell, [kernel])` applied at the
  expansion pre-pass; the kernel-returns-`C` obligation is checked there (option a).
- **Slice 2 — trait-owned ARGUMENT shells — LANDED 2026-06-25** (`TraitArgShellTest`):
  a trait method parameter may be a clause-chain shell `[A -> … -> B]`; the caller
  passes the domain `A` (what dispatch keys on), the impl's kernel sees the codomain
  `B`. `TraitDefaultExpansion.applyArgShells` rewrites the registered param to `A` and
  prefixes `let p = shell(p)` (the IR form of `wrapParamConversions`); the
  kernel-takes-`B` obligation is checked there. Stored by user-param **position** in
  `IrSort.Trait.argShells` (the impl may rename the param). Composes with the return
  shell (args inner, return outer) and with default bodies. The two faces are now
  complete: caller sees the outer ends, the kernel the inner ends, the trait owns both
  shells.
- **Deferred (named, not built):** let-tunneling / fan-in scope; `emit`;
  carrier-returning kernels (→ inverse-synthesis).

# Why

Default methods let a trait inject a *body* an impl inherits. But the body is a
fixed expression, and the impl can rewrite anything it overrides. There was no
way for a trait to inject behavior the impl **cannot see or change** — wrapping
that is mandatory because it is part of the contract. The type position was the
one thing the impl is genuinely bound by, and it was only ever a *predicate*.

This makes the type position carry *logic*, so the contract's sorts can wrap the
impl. The endpoint (see "Effects by shell") is that this is the scaffolding for
`emit` — Pontif's side-effects.

# The move (RULED)

The argument sorts and the return sort of a method may be **sequences**
(`[A->R]` transform-chains), not just membership predicates. By
*ascription-is-transform* (`principle_ascription_is_transform`), a sort that is a
transform **coerces** the value flowing through that position. Because the impl
is bound by the trait's sorts — it cannot restate or change them — whatever
transform the trait places in an argument or return position becomes mandatory
wrapping around the impl's body. A plain sort `[A]` is the identity transform:
today's behavior, unchanged. So this is a conservative generalization.

# Two faces (RULED)

A sort-decorated signature has **two faces**, bridged by the transforms:

```
function foo(bar:[A -> B]):[C -> D] -> [B -> C]
```

- the **caller** faces the outer ends: passes `A`, receives `D`
- the **arg shell** (signature-owned): `A -> B`
- the **kernel/body** (what the impl writes): `B -> C`  ← the `-> [B -> C]`
- the **return shell** (signature-owned): `C -> D`
- whole function: `A → B → C → D`

The kernel's type `[B -> C]` is **not declared** — it is the *computed hole*
between the inner faces (arg-shell output → return-shell input); the only bridge
that typechecks. When `B = C` the kernel is identity and **elidable**: the method
is fully synthesized from its sorts, no body written. This is the
*destructure/conversion duality* (`principle_destructure_conversion_duality`)
realized on signatures — put the destructure in the arg sorts, the conversion in
the return sort, and the function *is* its sorts.

**Gradient:** plain predicate sorts + full hand-written body (today) → partial
transform sorts + a shrunken kernel → fully-bridging sorts + zero body
(synthesized). A hard floor: the kernel can be empty.

**Division of labor** (complementary to default methods): default methods let
the trait own the *whole body*; sort-transforms let the trait own the *edges*
(shells) and leave the *middle* (kernel) to the impl. Two orthogonal axes of "the
trait contributes implementation."

# Scope & dataflow (RULED)

The signature is a small telescoped pipeline:

- **Argument sorts** read the **pre-transformed** (raw) values of *all*
  arguments — mutually visible, so `f(a:A, b:[B -> @ + a -> B'])` is legal and
  `a`'s sort may read `b` too. Each emits its post-transformed value plus any
  `let`s.
- **Return sort** reads the **post-transformed** arguments, the **accumulated
  `let`s** from the argument transforms, and the body's result (its `@`).
- The **value-body (kernel)** sees **only the post-transformed arguments** — not
  the lets (RULED 2026-06-25). Shell lets are private to the return shell;
  everything the kernel sees was handed to it.

**Order.** Value-reads across arguments are order-free (they read raw inputs, so
there is no "which arg-transform ran first"). `let`-accumulation runs
**top-to-bottom** by argument declaration order; a name collision is
**last-write-wins**. Order matters only for shadowing, never for visibility.

`let`s are **always pure**.

The forward-accumulating let-context is a **fan-in**: the arg shells read the raw
inputs in parallel, accumulate lets, and converge at the return shell — lets
*tunnel past* the kernel from an arg shell to the return shell. This is **not** a
linear pipeline (no stage→stage chain); the only "linear query telescope" was the
`project_query_dsl` idea, which is **design-rejected** (see `streams.md`,
`events.md`). What is real and shared: a sort-shell and a stream transform are the
**same `[A->R]` clause-fragment** — `&stream:[ (e:A) -> … ]` *spreads* the fragment
over a stream's elements (the Iterate driver, `streams.ptf`), while a shell applies
the same fragment **in place** to one value in an argument/return position.

## Reiteration & let-visibility (RULED 2026-06-25)

The kernel sees **only the post-transformed arguments** — declared-inputs-only.
Consequences:

- An impl writes the **bare kernel** and **never restates the shells**. The
  shells are the trait's; the impl is forbidden to change them and need not
  repeat them.
- Shell `let`s are **private to the return shell**; the kernel does not capture
  them ambiently. This keeps shell binder-names out of the trait→impl API (the
  trait may refactor its plumbing freely) and keeps the kernel's scope honest —
  everything it sees was handed to it.
- To **gift** the kernel a computed intermediate, the trait puts it in the
  kernel's **input type** (the arg-shell's output, e.g. `{norm:Norm, id:Id}`) —
  an explicit declared input, not an ambient capture.

This refines the earlier "the body shares the let scope": the body shares an
intermediate only when the trait *chose* to, by typing it into the kernel's
input — never by ambient capture across the trait/impl seam.

# Not a monad (RULED)

Per-method decoration is the feature. The "true monad" — a wrap that stays *on*
and threads across calls (bind) — is a **separate, optional** construct:
`assign monad` on a type, supplying an explicit bind *woven between calls*.
Shelved: no current use-case ("monads are too confusing"). The dichotomy that
caused confusion: *decorate* = each call's wrapper is applied fresh and done;
*compose/weave* = the wrapper threads into the next call (the bind). Only the
former is intrinsic to sort-transforms.

The contrast is the design goal. A monad puts the effect in the return *type* but
smears its code and ordering across the bind chain — you reconstruct "what
happens, in what order" by tracing the do-block and transformer stack.
Sort-transforms put the effect in the **shell of the one method it belongs to**,
adjacent to it, with order fixed locally by the pure telescope: **side-effects
grouped in close proximity to their intended function** — read a function's whole
effect footprint off its signature.

# Effects by shell — the endpoint (RULED)

The reason to admit arbitrary (pure) logic in sorts is to give the coming
**`emit`** primitive a home (`actions.md`, `events.md`): a write-only,
`let`-shaped emission living in the same shell positions. A trait's return shell
can `emit` an effect the impl never wrote — **effects injected by shape, not
annotated on the body** — which is the actions law "pure side observed not
annotated" (`project_actions_architecture`) falling out of the type system. The
pure-let telescope is the deterministic spine those emissions thread through
(cf. the per-conduit monotonic emission index, `events.md`).

# Partial evaluation (RULED hint; DERIVED detail)

The shells are a **binding-time annotation**: trait-fixed shells + pure lets =
the known-early stratum; raw argument values + `emit`s = the runtime stratum. The
interpreter gets a static, alias-free, ordered pipeline of pure stages and can:
fuse adjacent stages, **elide an identity kernel** (`B = C` → no runtime call at
all), hoist/CSE the pure lets, and constant-fold any stage whose input is known.
Honest scope: this specializes pipeline *structure* and folds the pure spine — it
does **not** evaluate runtime data early.

[DERIVED] With `emit` the only impurity, PE **residualizes to the effects**: fold
the pure spine away at compile time and what remains is a minimal *ordered list
of emissions* — which is exactly the actions "dependency-ordered queue." Pure /
effect split = static / dynamic split = the queue falls out of PE.

# Open edges

- **Reiteration / let-visibility** rule (above) — unruled.
- **Shared fragment substrate** (RULED 2026-06-25): a sort-shell and a stream
  transform are the same `[A->R]` clause-fragment, fired two ways — `&`-spread over
  a stream vs in-place on one value. No "telescope" unification to make: the linear
  query-DSL telescope is design-rejected (`streams.md`, `events.md`); the signature
  is a fan-in, not a pipeline.
- **Carrier-returning kernels.** A method whose result mentions `Self` (e.g.
  `combine(other):Self`) needs `R -> Self` to map a representation-result back —
  the inverse, which re-enters inverse-synthesis (`project_inverse_synthesis`,
  `metatypes.md`). The clean first slice is **forward-only** shells (results that
  don't mention `Self`): comparisons, projections, predicates, `show`. [PROPOSED
  slice boundary.]
- **Return shell referencing arguments** — already covered by the
  post-transformed args + lets it sees; cross-references that *name* params lean
  on `dependent-sorts.md` (carried `paramNames`).
- **Where the transform runs** — conceptually a wrapper at the method-call seam;
  the PE story wants it inlinable.
- **Gate interaction** — when a shell both transforms *and* refines, how it rides
  the construction gate / return-refinement gate.
