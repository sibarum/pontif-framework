# Dispatch unification

*Status: planned (2026-06-01). Consolidates the previously-scattered
"unified-operator-dispatch" direction into one effort, now unblocked because
its prerequisite — module-scoped, coherent dispatch (FQN keys + `CoherenceCheck`,
the orphan rule) — has landed.*

*What "unification" means here: unify the shared resolution **engine** and delete
the ad-hoc accidents layered on top of it — it does **not** mean folding methods
and free functions into one namespace. Pontif keeps **two separately-governed
dispatch mechanisms** (below); this effort makes both ride the same engine and
removes the name-mangling / BinOp-bypass / parse-time-inference hacks that
currently make them look like three unrelated systems.*

## Why

Pontif is a type system built on dispatch: the strength of the type system is
bounded by the strength of dispatch (see the *dispatch-as-the-star* principle).
The **resolution engine** under every dispatched call is already shared —
`StaticDispatch.resolve` / `DispatchTable.resolve` over a
`Map<String, List<FunctionDecl>>`, filtering by arity, proving each candidate by
narrowing (`Refinements.imply`), then picking the most specific. But three call
paths bolt *ad-hoc accidents* on top of that engine, so they read as three
unrelated systems held together by name-string tricks and a hardcoded fast path:

1. **Built-in operators — hardcoded, never dispatched.** `a + b` parses to
   `IrExpr.BinOp(ADD, …)` (`IrExpr.java` `Op` enum) and is evaluated by
   `IrInterpreter.evalBinOp` as `(Long) l + (Long) r`. The dispatch table is
   never consulted, so a user type can never participate in `+` on equal footing
   with `Int`.
2. **User operators — resolved at *parse time*.** `AltParser.tryOperatorOverloadRoute`
   infers the left operand's sort during parsing; if it's a non-primitive type
   with a declared `+` (or legacy `Type.+`) overload, it emits `Call("+", …)`,
   otherwise it falls back to `BinOp`. So `Int`/`Bool` always bypass dispatch and
   user types route to it — a split decided by a parse-time guess at the operand
   sort.
3. **Instance methods — name-mangled.** `method Type.name(…)` desugars to
   `function Type.name(this:Type, …)`, and `p.name(a)` becomes
   `Call("Type.name", [p, a])` via `AltParser.methodNameForReceiver`, which needs
   the receiver's sort *at parse time*. That parse-time requirement is the
   parser-blindness that blocks cross-module `recv.method()`. Trait calls add a
   third redirect: `Trait.method(v)` → `Type.method(v)` via
   `DispatchTable.resolveTraitFallback` + `TraitRegistry`.

**The name-mangling, the BinOp fast-path bypass, and the parse-time sort
inference are accidents, not essence.** This doc is the plan to delete those
accidents and route everything through the one engine — *while preserving the
two-mechanism architecture*, because that shared engine does **not** imply a
shared namespace or shared governance (see below).

## Target model: one engine, two mechanisms

The engine (sort-keyed candidate set → narrowing-proved → most-specific) is
shared. What rides on it is **two mechanisms with deliberately different
governance**:

### Mechanism 1 — free functions + operators (global multi-dispatch)

- **Open.** Any module may add an overload of a free-function or operator name,
  governed by the orphan rule + `CoherenceCheck` (FQN keys per module). Names
  resolve by dispatch over the argument sorts.
- **Symmetric.** No argument is privileged; all operand sorts participate equally
  (`+(Vector,Vector)`, `<(Int,Int)`, …).
- **Promotion-capable.** This is the home of any numeric-tower / coercion
  behavior (scope TBD — see B3).
- **Operators live here, not as methods — but a trait may *require* one.**
  Built-in `Int`/`Bool` arithmetic registers as built-in overloads of the
  operator names; a user type's `+` is a mechanism-1 overload `+(Vector, Vector)`,
  **not** a `Vector.+` method. `a + b` resolves by dispatch over the operand sorts
  — built-ins and user definitions uniformly — and operator *behavior* is extended
  only by adding a mechanism-1 overload. What a trait may now do is **name an
  operator as a `Dispatch` contract member**
  (`+:[Dispatch(this.type, this.type):this.type]`). That is a compile-time
  **bound** — it witnesses that the mechanism-1 overload exists (and is coherent)
  for the satisfying type — **not** a relocation of the operator into mechanism 2
  and **not** a `Vector.+` method. The operator still lives and resolves in
  mechanism 1; the trait only proves it is there, so a `[type E:Trait]` parameter
  can carry that proof into generic code (see B1, reopened).

### Mechanism 2 — methods / static methods / traits / inheritance (localized + rigid)

- **Receiver-rooted.** Dispatch is anchored on the receiver's sort. A method is
  resolved on the receiver, not as a symmetric N-ary call.
- **Localized.** Methods are owned by the type/module; they live in their own
  namespace, separate from free functions. `magnitude(v)` (a free function) and
  `v.magnitude()` (a method) are *different* names and may coexist — there is no
  `p.method()` ≡ `method(p)` equivalence.
- **Rigid.** No promotion; the localized resolution is the whole story. Trait
  satisfaction and inheritance both resolve *within* this mechanism.
- **Resolution moves off the parser.** Today method resolution needs the
  receiver's sort at parse time (the `Type.` mangling). The fix is to resolve on
  the receiver's sort **post-typecheck**, where `StaticDispatch` runs and the
  receiver's FQN'd sort is known. Removing the parse-time guess is what dissolves
  cross-module `recv.method()` — *without* merging methods into mechanism 1.

The parser emits uniform nodes (a "call name on args" node and a method-call node
carrying receiver + field) and does **no** sort inference; one resolution pass
runs post-parse, dispatching each node into its mechanism.

### Execution model: runtime dispatch is the semantics; static-lowering is a guarded optimization

The *meaning* of an operator/method call is **dispatch on the runtime values'
sorts** (`DispatchTable.resolve` already resolves over the concrete runtime
argument, most-specific). Static resolution is an **optimization layered on top**:
lower a call to a fixed target (e.g. a built-in operator → `IrExpr.BinOp`) **only
when the static operand sort is concrete enough that no more-specific overload
could match at runtime.**

- **Concrete static sort** (`Int`, a struct's exact type, a refinement thereof) —
  nothing more specific can appear at runtime, so static == runtime; lower it.
  Built-in `Int`/`Bool` operators are always in this case (`Int` is never
  trait-typed — traits are over structs), so they always lower to `BinOp`.
- **Trait-typed operand** — a more-specific concrete overload *could* match at
  runtime, so **do not lower**; leave the `Call` and let runtime dispatch pick the
  concrete type's op. This is how polymorphism is preserved; it's also the
  template for later *devirtualizing* mechanism-2 method calls (lower only when
  the receiver's static sort is concrete).
- **Union-typed operand** — dispatch is well-defined at runtime (the value is
  concrete then), but statically guaranteeing no runtime no-match needs a
  union-exhaustiveness verifier (every member has a matching overload). **Deferred
  — not off the table** (TODO); until then a union operand does not statically
  dispatch.

## Invariants this must preserve

- **Module-scoped + coherent.** Names FQN per module; the orphan rule governs
  where an overload `f(T, …)` may be declared. Unification must not reintroduce
  global dispatch for *everything* — that (plus no polymorphism) is what killed
  the prior language SPN. This is *the* non-negotiable.
- **The two mechanisms stay separately governed.** Mechanism 2 (methods/traits)
  must **not** become open global dispatch. Only mechanism 1 (free
  functions/operators) is the open, symmetric multi-dispatch axis. Folding them
  is exactly the over-generalization this rework exists to avoid.
- **Most-specific semantics + overlap rule** stay as they are — including the
  **subsumption escape hatch** (`OverloadOverlap`): provable overlap is an error
  *only when neither overload strictly implies the other*. Comparable overlap
  (catch-all + specialization, e.g. `handle(x:Int)` with `handle(x:[Int:@>0])`,
  and subtype overrides) is accepted because most-specific resolves it. Unknown
  is accepted silently; the runtime ambiguity check is the net.
- **Performance.** Primitive arithmetic must stay cheap. Because dispatch
  resolves at *compile time* for known sorts, a resolved built-in operator call
  lowers back to the same primitive op (a fast path keyed by the resolved
  overload), so the interpreter/Truffle hot path is unchanged. Only genuinely
  dynamic calls pay runtime dispatch.
- **Single-file programs** that use neither methods nor operator overloading
  compile unchanged.

## Phases

Each phase ships independently with the full suite green.

- **Phase 0 — documentation + consolidation.** ✅ This doc; glossary/TODO/memory
  pointers updated; the scattered "unified-operator-dispatch" mentions now point
  here.

- **Phase 1 — operators as mechanism-1 dispatch entries.** Register the built-in
  `Int`/`Bool` operator overloads (`+(Int,Int):Int`, `<(Int,Int):Bool`, …) as
  real dispatch entries in mechanism 1, and route `a op b` through dispatch —
  with a static fast-path that lowers a call resolved to a built-in overload back
  to `IrExpr.BinOp`, so the runtime path and perf are untouched. Remove the
  parse-time primitive bypass in `tryOperatorOverloadRoute`. Delivers uniform
  operator overloading (user types and primitives on equal footing). Self-
  contained: the operator set is closed and binary.

- **Phase 2 — methods resolve on the receiver sort (within mechanism 2).** Move
  method resolution to post-typecheck on the receiver's sort instead of the
  parse-time `Type.`-keyed lookup; methods keep their own namespace (no merge
  with free functions). `p.method(a)` lowers to a method-call node resolved by
  dispatch on the receiver's sort. **Cross-module `recv.method()` falls out for
  free** — the parser no longer needs the receiver's sort, and resolution runs
  over the combined module where the receiver's FQN'd sort and all overloads are
  known. *This phase delivers the deferred "recv.method() cross-module" TODO item
  — it is not a separate task.* Migrate `Type.method`-keyed tests.

- **Phase 3 — trait dispatch becomes mechanism-2 receiver-sort resolution.** With
  methods resolved on the receiver's sort, `Trait.method(v)` is just the method
  resolved to the impl by `v`'s sort *within mechanism 2*, so the
  `Trait.method → Type.method` string redirect in
  `DispatchTable.resolveTraitFallback` disappears. `TraitRegistry` remains only
  for the *satisfaction check* used by narrowing
  (`narrowing-handles-polymorphism`), not as a dispatch redirect.

- **Phase 4 — parser de-blinding + cleanup.** Delete the now-unused parse-time
  sort-inference hacks (`methodNameForReceiver`, `tryOperatorOverloadRoute`, the
  method branch of `extractDottedName`). The parser emits uniform nodes; there is
  one resolution pass, post-parse, that routes each node into its mechanism.

## Coercion: explicit casts as Pontif's answer to promotion

*Surface + direction RULED (James, 2026-06-16). The custom-coercion mechanism
itself follows when a consumer needs it — we do not build ahead of need.*

Pontif's response to Julia-style numeric promotion is **explicit coercion**, not
implicit promotion. Julia's `promote_rule`/`convert` is *implicit*, which forces
it to be both **complete** (a missing pair surfaces as an error deep in generic
code, so you reactively fill an N² matrix) and **unambiguous** (two
equally-specific paths are a hard error). Explicit coercion deletes both
obligations at once: nothing is searched, so nothing is incomplete; the target is
named, so nothing is ambiguous. You define only the coercions you use, where you
use them.

**The split (principled, not a compromise):** implicit coercion is kept ONLY for
the *closed* primitive set — e.g. `Int → Decimal`, a lossless in-domain embedding
the user can neither extend nor shadow. The combinatorial blow-up exists only in
the *open* (user-extensible) world; confining implicitness to the closed set draws
the line exactly where it stays tractable. Everything else is explicit.

**Surface — a cast is a value, written in value-space (RULED):**

```
(Type:value)        # the value-space cast — produce a Type from value
(Stream[Int]:xs)    # a parametric target needs no extra brackets
([Int:@>0]:n)       # a refinement target wears its own [..] — the ([]) crossing
```

The direction is **general → specific**, type-on-the-left — identical to the
type-space refinement `[Base:pred]` and the in-type pipeline
`[… -> Base:@==witness]`. The cast is the value-space **sibling** of a refinement,
not a refinement itself: `[Base:pred]` is a *type* (square, type-space);
`(Type:value)` is a *value* (paren, value-space) — the same colon ("specialize the
general kind to this specific value") on the two sides of the type/value line.
This is exactly the `([…])` crossing the bracket/paren law reserved (`[]` = types,
`()` = values).

**Why it lives here — it is dispatch.** A cast resolves a coercion by
`(source sort → target sort)`; that is the one shared resolution engine, governed
by the same coherence/orphan rule (no two coercions for the same pair) as every
other dispatched call. Custom `Type → Type` coercion functions — the user-defined
conversions that replace Julia's `convert`, and the actual response to the
promotion use-case — register and resolve the same way. They are the planned
consumer, deferred until needed.

**Unambiguous against the binder form (RULED):** `(name:Type)` (binder position —
a fresh name annotated by a type, specific→general) and `(Type:value)` (expression
position — a value, general→specific) never share a position, so the reading is
fixed before scope matters; reinforced by the capitalization law
(`Capitalized:lowercase` for a cast vs `lowercase:Capitalized` for a binder).
There is no `(…) ->` lambda to collide with — lambdas are retired; the matcher is
`[…] -> …`.

**Open:**

- **Does the cast law govern user coercions, or are they trusted functions?**
  `(Type:value)` promises *fabricate-never*; a built-in render honors it, but an
  arbitrary user `S → T` coercion can fabricate. Either it is checked (hard for
  arbitrary code) or trusted like any function (Julia's stance). Rule it when the
  custom-coercion mechanism lands.
- **Resolution precedence when several laws apply** through the same `(T:…)` skin
  (subtype demotion vs. a custom coercion vs. trait coercion) — must be total.
- **Generic-code tradeoff:** implicit promotion buys mixed-type generic code for
  free; explicit pushes that to the call site or to type constraints. Acceptable
  for Pontif (narrowing covers much of it), but a deliberate choice.

This **reframes B3**: operator promotion stays implicit *but primitive-only*
(mechanism 1, the closed tower); boundary coercion at assignment/argument/return
is explicit via `(Type:value)`. The two axes are distinct — which is exactly what
lets both coexist without contradiction.

## Open design decisions (resolved as we reach each phase)

- **D1 (Phase 1) — keep `BinOp` as the lowered form of a resolved built-in
  operator** (fast path) vs. fully replace it with `Call`. *Leaning:* keep
  `BinOp` as the post-resolution representation for built-ins — dispatch decides,
  `BinOp` executes. This preserves the hot path with no Truffle changes.
- **D2 (Phase 2) — do methods and free functions share one namespace?**
  **RESOLVED: no.** Two mechanisms (James's standing design): free functions +
  operators are open global multi-dispatch (mechanism 1); methods/static/
  traits/inheritance are localized and rigid (mechanism 2). The shared resolution
  *engine* does not justify a shared namespace — merging would reintroduce the
  global-dispatch hygiene risk the orphan rule exists to contain, and would force
  same-name method/free-function collisions that the current `Type.`-prefixed
  keys avoid (`OverloadOverlap.collectOverloads` already pools `TraitImpl` methods
  with same-name free functions; only the prefix keeps them apart today).
- **B1 — operator-valued trait contracts. REOPENED → YES (James, 2026-06-16,
  superseding the 2026-06-01 "none").** A trait *may* carry a `Dispatch` contract
  member naming an operator (`+:[Dispatch(this.type, this.type):this.type]`).
  Operators still **resolve** only via mechanism-1 global dispatch — this does not
  move them into mechanism 2. The trait member is a **bound**: at
  `assign trait T:Trait` the compiler verifies the required mechanism-1 overload
  exists (and is coherent) for `T`, and a `[type E:Trait]` parameter then carries
  that proof into generic code — so operator use over an abstract type is
  **decidable at definition time** instead of failing at a runtime dispatch miss.
  *Rationale for the reversal:* leaving operator satisfiability undecidable until
  runtime contradicts the provably-correct stance the rest of the system pays for;
  a trait is the right place to make it a compile-time obligation. **Opt-in** —
  unbounded operator use still resolves Julia-style at the call, preserving
  mechanism-1 openness; the trait is the *optional* compile-time proof. **v1
  scope:** homogeneous `(this.type, this.type):this.type` only; arbitrary operand
  sorts (mixed / promotion contracts) deferred, and must fail with a clear error
  when written. This **restores** the `Addable`-style numeric-trait motivation for
  *primitives as trait implementors* (e.g. `Int` witnessing `+(Int, Int):Int`).
- **B2 (Phase 2) — static methods within mechanism 2.** No receiver *value* →
  namespaced under the type and resolved rigidly. Confirm the keying when we get
  there.
- **B3 (Phase 1) — promotion semantics in mechanism 1. REFRAMED** (see *Coercion*
  above): operator promotion stays implicit but **primitive-only** (the closed
  numeric tower, e.g. `Int → Decimal`); all open/user coercion is **explicit** via
  the `(Type:value)` cast, resolved through this engine. Remaining Phase-1 scope is
  just which primitive operator promotions to wire. Interacts with D5.
- **D5 (Phase 1) — static-resolution coverage for the fast path. RESOLVED by the
  execution model above.** Lower to `BinOp` only when the static operand sort is
  concrete; otherwise leave the `Call` for runtime dispatch (the semantics). For
  Phase 1's built-in `Int`/`Bool` operators the operands are always concrete, so
  they always lower — no genuinely-dynamic built-in-operator call arises. (The
  one residual is union operands, deferred to the exhaustiveness verifier; see the
  execution-model section and TODO.)
- **B4 (deferred / TODO) — static dispatch on union-typed operands.** Runtime
  dispatch handles a union operand fine (the value is concrete at runtime); the
  missing piece is a compile-time verifier that every union member has a matching
  overload. Tracked in `docs/TODO.md`. Not off the table.

## Relationship to `recv.method()` cross-module

The deferred "recv.method() sugar cross-module" item is **delivered by Phase 2**,
not pursued separately. The whole reason it was hard — the parser needing the
receiver's sort at parse time — is exactly the thing this effort removes, by
moving method resolution post-typecheck *within mechanism 2*. It does not require
merging methods into the free-function namespace.
