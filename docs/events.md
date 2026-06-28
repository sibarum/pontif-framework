# The Event substrate — IO & concurrency

Status: **WAR DECLARED 2026-06-24.** Slice 0 + slice 1b (output IO) LANDED; model refined
2026-06-26 (see "The three stages"). Supersedes `actions.md`'s global-event-queue framing
with a localized Event model. Realizes `infinite-streams` (the event system / stateful
sources) as concrete machinery — laziness lives in the iterator (the conduit + scheduler),
not the stream value.

## Why

Pontif has no IO and no application loop. A program is a module whose trailing
expression is evaluated once and formatted (`IrInterpreter.eval(CompiledModule)` — the
"Inquisition" force-evals top-level lets, then evaluates `IrModule.main`). The only
side-effecting native is the `Decimal` *constructor*; the native *function* registry is
documented but unbuilt.

Basic stdin/stderr is the forcing function to build the substrate the language already
ruled essential. The result is not "an IO library" — it is the event/concurrency model.

## The model

- **`emit MyEvent(...)`** — a *statement*, shaped like `let`: write-only, no return,
  uninspectable. This is the purity membrane: from the emitting code's side, emitting is
  observationally invisible to its own control flow; all effect is downstream. `emit`
  routes **by event type via the dispatch table**, so coherence yields exactly **one
  conduit per event type**.

- **`EventConduit[type E, type S, type R]`** — the synchronous, single-threaded
  reduction point; the spiritual successor to `Action`. It is a **non-associative
  fold/scan**: `S` is the managed internal state (collapses to a single `R` when
  `S = R`). `triggered(payload:E):R` **pulls** the next event off the conduit's mailbox
  and **pushes** the folded `R` outward — that is why it is "both". A conduit body may use
  map / filter / fold / scan / fan; **`zip` is admitted only over a product-typed
  emission** (each `emit` supplies all positions at once) — zipping two *independent*
  emission sources is impossible (no reliable cross-emission join).

- **`Action`** — the *reaction* (the consumer; the role events.md first sketched as the
  pull `EventStream[type R]` receiver — **reframed 2026-06-26, see "The three stages"**).
  An Action carries a **match** (a filter/sort over the conduit's output) and a reaction
  body; it **fires on every matching instance**. It has **no control over delivery** — it
  does not pull `next` on its own initiative; *when* it runs is the scheduler's call.

- **Two-way sort selection** governs delivery: the `AND` of (a) the *Action's* match —
  its payload-content sort — and (b) the *conduit's* per-message receiver-metadata sort,
  each defaulting to the universal sort. Both reuse `Refinements.satisfies` — delivery is
  membership. (The Action filters on payload content; the conduit routes on receiver
  identity — orthogonal, and the OpenGL case needs both.)

- **Failure is the existing `!!` runtime hazard.** The happy path is **uninspectable** —
  it yields a payload-free completion handle, not a result (see "`emit` and `main` both
  return a completion handle" below; this supersedes the earlier "returns unit"). A delivery
  failure *fires* the hazard — which crashes by default, the safety net against corruption.
  Recovery is the explicit `match [!!]` discharge. (No parallel `Success|Fail` union.)

- **Monotonic emission index** stamps each `emit` so the non-associative fold is
  **deterministic and replayable** under concurrent emit (arrival order is otherwise
  scheduler-dependent, and the fold's order is semantic). Default **per-conduit**; a
  single **global** atomic only if whole-program deterministic replay is wanted.

## The three stages (RULED 2026-06-26, James)

The substrate is a three-stage pipeline; this sharpens the bullets above and is the
canonical statement.

1. **Emit an Event.** `emit MyEvent(...)` — anywhere, *even inside a pure function*. The
   conduit is the membrane that quarantines the effect, so the emitting code stays
   honestly pure (write-only, observationally invisible to its own control flow). There is
   **no first-class "emitter" type** — `emit` is the only producer mechanism.
2. **Process the Event via its Conduit.** Exactly **one conduit per Event type**; it sees
   **all** instances. It may reject, modify the data, or change the payload type (`E→R`),
   and what it passes through is **multiplexed** to the Actions. Because it threads state
   `S`, the natural jobs are **stateful folds** over the instance stream: enrich with
   context, group similar events, de-bounce.
3. **React with an Action.** An Action declares a **match** (filter/sort) and a reaction
   body, and runs on every matching instance. One conduit fans out to **many** Actions.

**Delivery is push *AND* pull, with both endpoints decoupled (RULED).** Neither the
emitter nor the Action controls delivery: the emitter fires and forgets; the Action runs
when it is run. Delivery — timing, rate, **backpressure** — lives entirely in the
**Conduit + the action scheduling** (configured in `main`). So backpressure is *not* a
receiver demanding `next` (the earlier framing); it is the conduit + scheduler arbitrating
between the push side (`emit`→conduit) and the pull side (conduit/scheduler→Action).

**The Conduit + scheduler is *the iterator* (RULED).** "All instances of an Event type,
over time" *is* a stream — a temporal sequence, not a value — and the conduit (plus the
scheduler) is the iterator over it. This is the home of the **infinite-stream** machinery:
laziness is a property of the **iterator**, never of the stream value
([[project_infinite_streams]], 2026-06-26). The eager `Iterate` construct is the *other*
iterator (materialize a finite source); the conduit pull-loop is the lazy one. (A
2026-06-26 misstep that baked laziness into a `LazyStream` *value* was reverted — wrong
locus.)

**Input is an inbound `emit` (RULED).** `stdin` is emitted by the Pontif internals into
its conduit, so input and output are the *same* Emit→Conduit→Action shape, differing only
in who emits (the world vs the program). **EOF = the internals stop emitting** ⇒ the loop
terminates by construction. (Output, slice 1b: the program emits `StdOut`, a native sink
conduit with no Action.)

**`main` is a special `emit`, and both return a completion handle (RULED 2026-06-28, NOT
yet built).** Since `emit` is "the only producer mechanism," `main echo(&stdin())` is
simply emit-ing the **root drive** into the scheduler. So `emit` and `main` return the
*same thing* — and it is **neither `unit` nor a `Promise`**. A result-bearing `Promise<T>`
would let the emitter observe its own downstream effect, piercing the purity membrane
(a lie). What survives the membrane is a **payload-free completion handle**: an *identity*
for the spawned work plus a *liveness/completion* token — you can join/await *that it
completes*, never *what it produced*. The same handle is **inert inside pure code**
(`emit` in a pure function stays pure — the handle carries nothing inspectable there) and
**live only in the scheduler/`main` domain** (where awaiting completion is legitimate);
*where* you hold it decides whether it is usable (observation-relative, COTT). Designing
the handle type in full — `emit`'s return changing from body-passthrough, the two
observability regimes, the scheduler join, concurrent `main(a, b)` — is **its own design
pass (the real slice 2)**. Today's placeholder is the interpreter's inert
`IrInterpreter.DriveResult` (returned by a for-effect drive; see slice 1d).

## Design invariants (hold across all slices — they protect the thread story)

1. A conduit is the **sole owner and mutator** of its state `S`.
2. `emit` is the **only** path between conduits — the future thread boundary. Even when
   Slice 1 dispatches synchronously, an emit goes *through the mailbox*, never a direct
   call into another conduit's state.
3. Receivers **pull** (demand-driven `next`) — backpressure is intrinsic, not retrofitted.
4. **Thread-affinity is a property a conduit has**, even when every thread is the main
   thread. The mailbox and affinity types are the *real* concurrent abstractions in Slice 1.
5. `main` is the **only** place invocation/logic happens; non-entrypoint files are
   declarative-only.

Design multi-threaded-correct from day one; run single-threaded in Slice 1. Turning on the
worker pool is then an executor swap, not a redesign. (OpenGL host: the root thread is the
only one permitted to touch GL state; workers `emit` async; the root-pinned conduit
serializes.)

## Slices

- **Slice 0 — entry block (LANDED 2026-06-24, non-breaking).** Explicit `main ( EXPR )`
  block — **paren**-delimited because the body is grouped *logic*, not an aggregate (the
  bracket/paren law's block role; brace-aggregates moved blocks to `( let …; expr )`).
  Body sequences via let-in; it is the runtime entry, accepted *alongside* top-level logic.
  (`match`'s `{ … }` is the same category and migrates to `( … )` as a low-priority
  pre-launch requirement — docs/TODO.md.) **RULED 2026-06-24 (James):** the large migration
  surface (151 probe `entry.ptf`, 192 `.ptf`, 215+ inline test compile sites) is a good
  reason to **keep top-level logic** — recast it as a **preprocessor** layer rather than
  "the program". The breaking flip (trailing-expr → error / declarative-only top level)
  and the Inquisition retirement are **shelved**. Future direction (not now): forbid the
  preprocessor from invoking *imported* subroutines, which structurally rules out any
  load-order dependence. `main` is where the event loop(s) are set up; only the entrypoint
  file's main is driven.
- **Slice 1 — event core (single-threaded execution / multi-threaded design).**
  `EventConduit`/`EventStream`/`Event` traits; the `emit` statement; conduit fold + pull
  delivery (reuse the `driveGenerator` pull-loop, not the eager `Iterate` materialize path);
  two-way selection; `!!` on failure. stdin/stderr/stdout as the first conduits via a new
  `NativeFunctions` registry (OS holds the far end; EOF seals the stream ⇒ the loop
  terminates by construction). `main` wires the conduits and the runtime drives the loop.
  - **1b — output IO LANDED (2026-06-26).** `emit StdOut(…)` / `emit StdErr(…)` print to
    the process streams — Pontif's first side effect beyond the `Decimal` constructor.
    `emit EVENT  BODY` is a statement keyword that lowers to a dedicated **`IrExpr.Emit`**
    node (every IR pass recurses into event + body); the interpreter routes the event
    **by its type name** to a `NativeFunctions` effect (the registry's first tenant), then
    yields the body. The builtin `StdOut`/`StdErr` events (with a `text:String` field) are
    the first write-only conduits. `EventEmitTest` (print / route-by-type / sequence /
    write-only). **Two deferral decisions** (the thin vertical, ruled with James):
    (1) routing reuses dispatch-by-the-event-type rather than the multi-param
    `EventConduit[E,S,R]` trait dispatch; (2) `emit` is a dedicated IR node, **not** a
    routing `Call` — a keyword can't be imported, so a routing call would be an "unknown
    function" to the sort checker and unresolvable under module scoping.
    **Deferred to later 1x slices:** the stateful conduit fold + `triggered(E):R` contract
    + `S`-threading; user-defined `EventConduit` impls; two-way sort selection; `!!`
    recovery; the monotonic emission index.
  - **1c — input IO LANDED (2026-06-27).** `stdin` as the first inbound source — the
    counterpart to 1b's output. `stdin()` is a declared `pontif.events` function (so it's
    import-gated, `requires pontif.events.{stdin}`) whose resolved call is intercepted by
    the interpreter to yield a fresh **`LiveSource`** (the new `NativeSources` registry,
    the read counterpart to `NativeFunctions`) reading `System.in` line by line. The
    `Iterate` engine recognises a `LiveSource` source and drives it with a **demand-driven
    pull-loop** — one line pulled → arms run (`echo` side-`emit`s `StdOut`) → next pulled —
    rather than pre-materialising a tuple. **`main echo(&stdin())`** echoes each line as
    it's read and **terminates on EOF** by construction (`StdinEchoTest`). Realizes the
    crystallized model: laziness is in the *iterator* (the pull-loop / `main`), not the
    stream value; `emit` is the effect side-channel woven through an ordinary `String →
    String` map. **Honest deferrals:** (a) **RESOLVED in slice 1d** (was: a `LiveSource`
    `Iterate` seals/collects, so a never-EOF source grows unbounded). (b) Source spelling is
    `&stdin()` (a live-source value, spread by the existing `&`), not yet the
    `&(StdIn.nextLine())` repeatable-call form. (c) A top-level `let echoStream = …; main
    echoStream` would force `echoStream` at the Inquisition (eager) — fine for EOF stdin,
    but the Inquisition-inversion for stream lets is deferred. (d) single source; concurrent
    `main(a, b)` / threads is slice 2.
  - **1d — for-effect / unbounded sources LANDED (2026-06-28).** A `LiveSource` drive is
    **for-effect**: a live source is potentially unbounded, so collecting its output is the
    unsafe operation we decline to support ([[project_infinite_streams]], productivity). The
    drive still evaluates each arm's write — so the woven `emit`s fire — but **discards**
    instead of accumulating (`IrInterpreter` threads a `forEffect` flag into
    `iterateStep`/`routeWrite`; the STREAM append is skipped), so `main echo(&stdin())` runs
    in **O(1) output memory** regardless of input length. It returns the inert
    `IrInterpreter.DriveResult` placeholder (the runner renders it as no output) — **not**
    `unit`, not the collected tuple. `DriveResult` is the **seam for the future payload-free
    completion handle** (`main` = special `emit`; see "both return a completion handle"
    above). Witnessed by `StdinEchoTest.liveDrive_discardsOutput_returningDriveResult_notATuple`.
    The eager (finite-tuple) path is untouched — it still collects and seals
    (`map((1,2,3),…)` → `{2,4,6}`). **Remaining deferral:** bounded collection of a live
    *prefix* (`takeWhile(&stdin())` → a value) is unsupported; it would materialise a finite
    tuple and ride the eager path.
  - **1e — the Action reaction leg LANDED (2026-06-28).** The consumer stage (stage 3),
    previously design-only. Surface: `action NAME(e:EventSort) -> BODY` — a one-parameter
    function invoked **reactively**. The event is the sole parameter; its **sort is the
    match-filter** (`e:Tick` matches all `Tick`; `e:[Tick:@.n > 0]` matches a refined subset),
    tested against each emitted event with `Refinements.satisfies` (the same `Passed`-only
    test `iterateStep` uses for concrete values); the body is the **for-effect** reaction (its
    value discarded). The name is diagnostic-only — Actions are never called by name. **No new
    IR node, zero new passes:** the parser lowers an `action` to an ordinary `FunctionDecl`
    under a reserved non-lexable `#action#` key (the `lowerCoercions` precedent), so every
    pass handles it uniformly; the compile loop recognises the key (`contains`, since the
    linker may module-qualify it) and records a `(matchSort → reaction)` `CompiledAction` in
    the module, keyed by the event type's **bare** name. `evalEmit` now fires every matching
    Action for an emitted event's type in **declaration order** (one event fans out to many),
    then applies the native sink if any. **Dispatch is synchronous** (during the `emit`); the
    mailbox/scheduler is a later slice. The **fail-closed** rule is tightened: an event with
    *no* consumer (no sink, no action registered for the type) still errors, but a registered
    Action that doesn't match *this* instance is a legitimate no-op. `ActionReactionTest`.
    **Still deferred:** the stateful `EventConduit` fold (`triggered(E):R` + `S`-threading)
    *between* emit and the Actions; the scheduler / root-thread story (Slice 2); and the GUI
    consumer (`pontif-builtin-gui`), where an Action's body mutates a dasum `Property`.
- **Slice 2 — real threads.** Worker pool, thread-pinned conduits, the OpenGL root-thread
  case; concurrent mailbox + optional global index. Executor swap by invariants 1–4.
- **Slice 3+ — hardening.** Explicit `Fail` recovery surface; backpressure policies on
  `next`; product-typed `zip` admission; global-index replay if deferred.

## Naming

"Sink" was rejected (reads as /dev/null). The vocabulary is `emit` / `EventConduit` /
`Action` (the reaction; match-sort + body) / `triggered`. **Reframed 2026-06-26:** the
consumer is an **`Action`**, not a pull `EventStream[R]` receiver calling `next` — the
Action has no delivery control (see "The three stages"), so `next`/demand-pull drops out of
the consumer vocabulary. Whether the `pontif.events` trait keeps the name `EventStream[R]`
(as the typed channel a conduit multiplexes onto) or is renamed `Action` is an open naming
call.
