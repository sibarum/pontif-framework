# The Event substrate — IO & concurrency

Status: **WAR DECLARED 2026-06-24.** Design ratified with James; Slice 0 in progress.
Supersedes `actions.md`'s global-event-queue framing with a localized Event model.
Realizes `infinite-streams` (the event system / stateful sources) as concrete machinery.

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

- **`EventStream[type R]`** — the receiver: a genuine **pull**-stream. Delivery is
  demand-driven — the receiver signals readiness, the conduit then calls
  `next(payload:R)`. This handshake is where **backpressure** lives.

- **Two-way sort selection** governs delivery: the `AND` of (a) the *receiver's*
  payload-content sort and (b) the *conduit's* per-message receiver-metadata sort, each
  defaulting to the universal sort. Both reuse `Refinements.satisfies` — delivery is
  membership. (Receiver filters on payload content; conduit routes on receiver identity —
  orthogonal, and the OpenGL case needs both.)

- **Failure is the existing `!!` runtime hazard.** The happy path returns unit (keeping
  `emit`/`next` uninspectable). A delivery failure *fires* the hazard — which crashes by
  default, the safety net against corruption. Recovery is the explicit `match [!!]`
  discharge. (No parallel `Success|Fail` union.)

- **Monotonic emission index** stamps each `emit` so the non-associative fold is
  **deterministic and replayable** under concurrent emit (arrival order is otherwise
  scheduler-dependent, and the fold's order is semantic). Default **per-conduit**; a
  single **global** atomic only if whole-program deterministic replay is wanted.

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
- **Slice 2 — real threads.** Worker pool, thread-pinned conduits, the OpenGL root-thread
  case; concurrent mailbox + optional global index. Executor swap by invariants 1–4.
- **Slice 3+ — hardening.** Explicit `Fail` recovery surface; backpressure policies on
  `next`; product-typed `zip` admission; global-index replay if deferred.

## Naming

"Sink" was rejected (reads as /dev/null). The vocabulary is `emit` / `EventConduit` /
`EventStream` / `triggered` / `next`.
