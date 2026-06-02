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
   `function Type.name(self:Type, …)`, and `p.name(a)` becomes
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
- **Operators live here, not as methods — and never as trait contracts.**
  Built-in `Int`/`Bool` arithmetic registers as built-in overloads of the
  operator names; a user type's `+` is a mechanism-1 overload `+(Vector, Vector)`,
  **not** a `Vector.+` method and **not** a member of any trait. `a + b` resolves
  by dispatch over the operand sorts — built-ins and user definitions uniformly.
  Operator behavior is *only* ever extended by adding a mechanism-1 overload;
  there is no operator-valued trait contract (no `Addable` with a `+` member).

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
- **B1 — operator-valued trait contracts. RESOLVED: there are none.** Operators
  never resolve via traits; they resolve via mechanism-1 global dispatch, full
  stop (James, 2026-06-01). So `Int : Addable` with a `+` contract is not a thing
  — operator behavior is extended only by adding a mechanism-1 overload. This
  removes the only operator-flavored motivation for **primitives as trait
  implementors**: that item is now purely about whether a built-in type may
  satisfy a *named-method* trait (mechanism 2), independent of the operator work
  — and with `Addable`-style numeric traits off the table, its main use case is
  gone (see TODO note).
- **B2 (Phase 2) — static methods within mechanism 2.** No receiver *value* →
  namespaced under the type and resolved rigidly. Confirm the keying when we get
  there.
- **B3 (Phase 1) — promotion semantics in mechanism 1.** Scope of the numeric
  tower / coercion behavior for operators (defer vs. minimal). Interacts with D5.
- **D5 (Phase 1) — static-resolution coverage for the fast path.** What happens
  when `StaticDispatch` can't resolve a primitive operator at compile time
  (e.g. a fully dynamic operand)? Fall back to a runtime-dispatched operator
  call; confirm the perf envelope.

## Relationship to `recv.method()` cross-module

The deferred "recv.method() sugar cross-module" item is **delivered by Phase 2**,
not pursued separately. The whole reason it was hard — the parser needing the
receiver's sort at parse time — is exactly the thing this effort removes, by
moving method resolution post-typecheck *within mechanism 2*. It does not require
merging methods into the free-function namespace.
