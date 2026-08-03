# The Orchestration API

A cooperative concurrency, distribution, and fault model for Pontif. It grew out of the
Pontif→SPIR-V graphics work (docs/vulkan-todo below) — the Vulkan render loop must run on the main
thread, and that forced the question of how *other* concerns (bootstrap, the conduit runtime,
background work) cohabit that one constrained thread without being tangled into the render
boilerplate. The answer generalizes into a single primitive.

## The one primitive

> **spawn a routine over `X` (a communication pipeline) targeting `Y` (hardware).**

This subsumes async worker / thread / process / remote host / GPU dispatch. It is the actor model
(shared-nothing message passing) fused with placement-typed execution (Chapel `on Locale`, CUDA
streams) and location transparency (Erlang). Pontif is unusually entitled to it because purity +
message passing = shared-nothing *by construction*, which is the property that makes transparent
placement sound (the reason Erlang's transparency works).

- `main` is not "the entry point." It is the one worker whose target hardware is **pinned** — the
  main thread, because GLFW seized it. `main present(...)` is `spawn(present) targeting main-thread`.
  We built the most-constrained placement first.
- The graphics stack is already an instance: a SPIR-V shader is a routine `targeting: GPU` over
  `X = push-constants/buffers`; `present` orchestrates it.
- **Discipline: transparent placement, honest types.** You move a routine without rewriting it, but
  its *type* changes with the boundary it crosses. A spawn-to-thread stays total; a spawn-to-process
  or -host returns something that can fail/timeout (`[Result | Unreachable]`, the sibling of
  `evalSafe`'s `[Decimal | Undefined]`); a spawn-to-GPU exposes marshaling at the ABI. Uniform
  surface, differentiated semantics in the types — never RPC's transparency lie.

## Three roles

They **name seams that already exist** — this is a unification, not an invention.

| Role | Is | Nature |
| --- | --- | --- |
| **Player** | a **conduit** (scan over an event stream), ticked by a cadence | pure, shares nothing → **mobile** (place it anywhere) |
| **Instrument** | an **effect sink** (`NativeFunctions.Effect`: `StdOut`, `SetText`, `present`) | impure, touches the world → **pinned** to its hardware |
| **Conductor** | the missing coordination layer: a cooperative main-thread event loop | places Players, wires clocks, routes emits |

Consequences:

- **Players are mobile, Instruments are pinned.** The Conductor moves Players *toward* Instruments
  (and data), never the reverse. That is the honest definition of `main`: the Player the Conductor
  had to seat next to an immovable Instrument (the window/present Instrument GLFW nailed to the main
  thread).
- **Cadence usually originates *at* an Instrument.** The display/DAC *pulls* — that backpressure is
  the tempo (ChucK: the soundcard's fixed consumption rate is the clock). Some cadences are virtual
  timers the Conductor owns directly (`Fixed(dt)` physics). So the Conductor's job splits: *place*
  Players and *wire clocks* (which Instrument-pull or virtual timer drives which Players).
- The musical metaphor bends on purpose: a real player *holds* an instrument (one unit); here the
  Player emits notes (events) and the Instrument sounds them (side-effect). That split **is** the
  purity win — computation stays relocatable because it isn't glued to I/O.

## Cadence — a trait

Frame advancement is a parameterized algorithm, not one loop.

- **Retained** — ticks only on input/events (dasum's `glfwWaitEvents`); zero frames when idle.
- **Eager** — ticks every scheduler pass (uncapped or capped).
- **Vsync** — ticks at display refresh; `dt` = real frame time.
- **Fixed(dt)** — fixed logical increments, accumulator-driven (possibly several sub-steps per render
  frame). This is Unity's physics clock; the render interpolates between the last two fixed states
  using the accumulator remainder. Expose both **real dt** and **sim dt**.

The scheduler is a **deadline-merger**: each cadence answers "next deadline?"; the loop waits
`min(deadlines)` (or an event) via `glfwWaitEventsTimeout`, then ticks whoever is due. dasum's
`EventLoop` already has this shape.

**ChucK lesson (strict timing):** logical time (`now`) advanced explicitly, paced by hardware
backpressure (consumer-pull), with a unified duration/event suspension and deterministic ordering.
But strictness needs **one** master clock — two independent hardware clocks (audio 48 kHz vs display
60 Hz) drift, which is why ChuGL/Unity put render on its own thread. On one main thread you anoint
**one** timing authority; the others are coordinated, not co-strict.

**Non-negotiable:** the graphics cadence must be **non-blocking** (zero-timeout acquire / MAILBOX,
pace by deadline). A blocking FIFO-vsync present hoards the thread and starves every sibling.

## `spawn` semantics

`spawn` is a **body-level effectful term**, sibling to `let` and `emit`:

- `let x = v` — bind a value into scope, continue.
- `emit E(…)` — send an event into the substrate (conduits/effects), continue.
- `spawn P` — seat a Player onto the Conductor, continue; **returns a handle**.

There are no special "spawn rules" — it inherits `emit`'s, because activation is just execution
reachability:

- **Required scripts don't activate anything.** Only the entry-point executes; imports load
  *definitions* (functions, structs, conduits, effects, spawnable routines) hermetically — no
  top-level side effects. A `spawn` at a library's top level is unreachable → a compile warning.
  This is what stops `requires pontif.vulkan` from silently opening a window.
- **Two positions, one rule.** Entry-point top-level spawns = the *initial* orchestra ("multiple
  mains", activated in source order → deterministic startup, bootstrap-before-render just by writing
  it first). A `spawn` inside a conduit/function = a *dynamic* worker (Erlang-style supervision).
  Both fire when reached.
- **Conduit vs spawn.** A conduit is a registered reactive handler — active-on-event by mere import,
  needing no spawn (one-conduit-per-event-type stops silent interception). `spawn`/`conduct` adds a
  **clock**, driving a conduit as a *cadenced* Player. Passive handler = registered; active clocked
  Player = spawned.
- **The handle is the pipeline endpoint** (Erlang's Pid). `let w = spawn P over X targeting Y` binds
  the thing you send directed messages to. `emit` is ambient (into the event stream); sending to a
  handle is addressed (to one Player). Same substrate, ambient vs addressed. The `X` axis isn't a
  separate concept — it's what a spawned handle *is*.
- **Program lifetime changes.** A program ends when the **orchestra drains** (all Players retired),
  not when `main` returns. A root/`main` Player's retirement tears down the rest (supervision root —
  closing the window ends the program even if a background logger would otherwise run forever).

### Results flow forward — there is no `await` (RULED 2026-08-02)

There is **no thread synchronization at the language level — only orchestration.** A `spawn` is
fire-and-forget: nothing blocks a thread waiting on a spawned result, and there is no future / join /
`await` to read one back. A spawned routine's output returns the *only* way any effect returns — as an
**emitted event** the substrate routes forward to a reacting `action`/conduit.

This is not new: it is the **exact ruling already made for `… on Gpu`** (2026-07-05, `IrInterpreter`
line ~110 — "consumed by a reacting `action`, forward only; no `await` reads it back"). So **`spawn`
mirrors the GPU async model**, with a daemon thread as the "device":

- the dispatch produces a `Pending`-shaped handle registered in `outstanding`, carrying a **woven
  completion `emit`** (the event constructed from the computed value — the same `eventTemplate`/`argVar`
  the GPU path weaves);
- the routine runs on its thread; **drive-to-quiescence, on the main thread**, retrieves the result and
  fires that completion `emit` → the reacting action folds it single-threaded. The program stays live
  until every dispatch has drained (which is just the orchestra-drains lifetime above).

The one wait that exists is *internal* — the quiescence loop retrieving a result off the daemon's
result mailbox — exactly as the GPU loop awaits `Pending.values()`. That is the runtime draining the
orchestra, never a user-facing `await`. Net: cross-thread work never touches the substrate on the
worker thread (the completion `emit` fires on the main thread), so no interpreter-wide locking is
needed, and the language surface has no synchronization primitive at all.

## Crash safety

Purity quarantines *side-effects*, not *failure* — those are different. The honest model:

- **Pure code can still throw** (partiality: a deferred `[!!]` refinement, a runtime resource limit)
  **or hang** (non-termination). Purity ≠ totality.
- **Totality closes the internal error sources** — the total-functional-programming thesis (Agda /
  Coq / Idris-with-totality):
  - `[!!]` is **opt-in**; the default is a compile-time proof, so no runtime refinement throw unless
    you chose to defer it.
  - Non-exhaustive match is **compile-forced** to a `[_]` complement — no runtime match failure.
  - Termination/stack is **provable** for the total fragment (structural recursion auto; general
    well-founded recursion takes a decreasing measure via `assign proof`).
- **One property closes both failure modes.** A total Player provably returns, so it can't hang —
  which means cooperative scheduling is **starvation-free** among total Players *for free*. The same
  property that makes the program crash-free makes the Conductor safe.
- **Supervision is the boundary for the non-total / opt-in cases** ("let it crash"): the Conductor
  runs each Player's tick inside a fault boundary, and on a throw applies a policy — retire, restart
  from `init`, or escalate to the supervisor (the root ends the program). A crashing Player is
  contained; siblings and the Conductor live. **This is not yet implemented** — slice 2a's `conduct`
  has no `try` around `fireEvent`, so a throwing conduit currently crashes the loop.

Residual boundaries — where the guarantee ends, stated honestly:

1. **Termination bounds time, not space.** A terminating function can still `O(2ⁿ)`-allocate and OOM;
   deep recursion trampolined to the heap moves the overflow, it doesn't remove it. A space bound is
   a strictly harder proof than a termination measure. "No stack overflow" ≠ "no OOM."
2. **Proof ergonomics, not proof possibility, is the real cost.** Crash-freedom holds only to the
   extent the prover discharges obligations *silently* (the effective-sort / auto-classification
   work). The theory is settled; the ergonomics is the open engineering.
3. **A trusted core (TCB).** Even a total program is crash-free *modulo* the compiler, prover, and
   runtime. Today Pontif's TCB is the whole stack (elaborator, interpreter, GraalVM, JVM); the long
   game is shrinking it toward a small verifiable kernel (Coq's move).
4. **External faults** (power, cosmic rays, physical destruction) are not the language's to prevent —
   they are the **distribution/supervision axis's** job (Erlang/OTP: telecom uptime against hardware
   failure via supervision + replication across nodes). So placement earns the *outer* guarantee as
   totality earns the *inner* one, and "it can't crash" is defensible spoken precisely: **no internal
   error source, modulo a small trusted core, with external faults answered by replication.**

**Placement is also the fault-isolation axis.** Same-thread Player = fast, throws catchable by
supervision, but a hang is shared-fate. Own-thread/process/host Player = the OS isolates *both* crash
and hang, at the cost of the boundary (marshaling) and exactly the `Unreachable`-typed handle the
honest-types rule already demands. Choosing a chair is simultaneously a performance choice and a
trust/fault-isolation choice — and the type system forces you to acknowledge it.

## The concurrency model — one pattern, four transports

> Ratified 2026-08-02 (James + working session). The threading discipline the Orchestration API
> commits to, and the property that makes it sound.

### The theorem: only the queue needs thread-safety

Pontif is unusually entitled to a lock-free concurrency model, and it falls straight out of the
language's existing rules — no new machinery:

- **Every struct is immutable; there is no mutability anywhere in the system.**
- **No closure retains state past the stack frame that made it** — a lambda captures values, not a
  mutable cell.
- **Side-effects happen only when an event crosses a boundary, and an event carries only static
  (immutable) data.**

So the only thing two threads ever share is *the message queue itself*. Everything that flows through
it is immutable — safe to read from any thread, no defensive copy, no lock. The one discipline that
makes this **literally** true is **single-owner conduits**: each Player (conduit) is drained by
exactly one thread, which folds its state serially. Then:

- a conduit's **state** is thread-local — mutated over time, but only ever by its owning thread → no
  lock;
- the linked **registries** (`CompiledModule`, dispatch tables, native bindings) are read-only after
  link → shared freely → no lock;
- the **inbox** is the sole object two threads touch → the *only* place synchronization lives.

**There are zero data locks in the system.** All concurrency is confined to bounded queues. This is
the actor model (shared-nothing message passing), but earned by construction rather than imposed by
convention — purity + message passing = shared-nothing, the same property that makes Erlang's
transparent placement sound.

The framework payoff this was always aiming at: **display logic is always single-threaded** (the main
thread drains one serialized stream of immutable events and applies updates in order), while
**application logic parallelizes freely** on daemon threads — and the two can never race, because the
only thing they share is a queue. "No weird GUI bugs" becomes a property, not a code review.

### The tier matrix

The four placements are **not four runtimes**. The integration pattern is uniform — *an inbox of
immutable messages* — and a Player's code is **transport-blind and identical** across all of them. What
differs is exactly two columns: how a message reaches the inbox (**transport**), and how the Player
stays alive (**liveness**). `spawn … over X` selects a row.

| Tier | Transport | Liveness | Handle type |
| --- | --- | --- | --- |
| **main** | cooperative drain between render ticks | the one thread you never block and never spawn (singleton) | local |
| **same-process thread** | in-process bounded queue | spawned daemon, supervised | local |
| **separate process** | elektroq socket + a process spawner | OS-supervised | `[… \| Unreachable]` |
| **cross-machine** | elektroq socket | OS-supervised | `[… \| Unreachable]` |

- **main is special** because it is cooperatively multiplexed (never blocked — it drains its inbox
  *between* render ticks) and there is exactly one of it. This is what the cut-2 `Conductor` already
  is: the **main-thread lane's executor**. It does not go away — thread/process placement is added
  *around* it.
- **same-process threads are special** because they share a heap: the transport *may* skip the copy.
  But the rule still admits only immutable messages, so nothing above the queue can tell — the
  optimization is invisible, and moving the Player to a process later changes nothing but the row.
- **separate process and cross-machine are the same design** — an elektroq socket — differing only in
  where the socket points. Both hand back an `Unreachable`-typed handle, because a boundary you can't
  reach in-memory is a boundary the *types* must admit can fail (never RPC's transparency lie).

macOS wrinkle (noted, not yet paid): a window is happiest on a consistent thread and on macOS Cocoa
*demands* the true main thread, so "spawn the GUI onto any pool thread" is a Windows-only convenience.
The main lane exists precisely so the display Player can be pinned there when we cross that bridge.

### The journal is the wire format

Because every message is immutable static data, a **per-inbox journal** — an ordered list of those
records — is byte-identical to what you would serialize to cross a process or a network. Journaling
(for crash-restart) and the run-anywhere transport therefore **consume the same stream**; they differ
only in where it is written (RAM vs socket). Build the in-memory journal now and most of the
cross-process wire model comes with it — which is exactly how the thread-first plan avoids blocking the
process-later future.

### Supervision — the Smalltalk restart, stated honestly

A conduit is a deterministic fold from `INIT` over its event stream, so its **state replays
perfectly**: on crash, re-seed `INIT` and replay the journal to rebuild it. That half of the
"detect-crash-and-restart-the-daemon" dream is real and free.

The honest edge is **effects don't un-happen**. Naive replay re-fires `StdOut` / `present` / IO. So
restart carries two rules, and the journal is built to serve them from day one:

- **Commit-marker.** The journal records the last position whose effects were externally observed.
  Restart replays *silently* up to the marker (rebuilding state without re-emitting), then resumes
  live. Alternatively, effects are made idempotent — but the marker is the general answer.
- **Poison-message / retry bound.** A restart re-delivers the event that crashed the Player; after *N*
  failed re-deliveries the event is **dead-lettered**, so one bad message cannot crash-loop a daemon
  forever. This is the retry logic driven by the in-memory journal.

Supervision policy (retire / restart-from-`INIT`+replay / escalate to the root) stays as in *Crash
safety* above; the journal + commit-marker + dead-letter are the mechanism that makes "restart the
daemon" safe rather than a double-effect hazard.

## Status and roadmap

- **Slice 1 (done, host-level spike):** `WindowedVulkanContext.tick()`/`drain()` make present a
  frame-tick, not a thread-owner. A minimal `Conductor` + `Player` (supirvast/vastir-preview) drive a
  render (eager) and a 500 ms logic task cohabiting one main thread. Commit `supirvast 8a613e2`.
- **Slice 2a (done):** `pontif.orchestra` — the `conduct` native fires `Tick` events on a cadence
  into a conduit (a Player), whose `emit`s reach an Instrument (`StdOut`). Reuses the conduit-fold /
  emit-routing core untouched — the native supplies only the clock. Commit `pontif 6036903`.
- **Slice 2b (in progress):**
  - **Cadence as a real trait — DONE.** `trait Cadence` with `Fixed(dt)`/`Eager`/`Vsync`/`Retained`
    variants lives in `pontif.orchestra`; `conduct(ticks, cadence:Cadence)` replaces the old fixed
    `period:Int`. The headless conductor realizes `Fixed(dt)` (beats `dt` ms apart) and `Eager` (no
    wait); `Vsync`/`Retained` pace to a display/event source only the windowed Conductor owns, so the
    headless `conduct` **refuses them honestly** (fail-closed, pointing here) rather than silently
    degrading to `Eager`. Covered by `OrchestraTest` (metronome under `Eager`/`Fixed`; the `Vsync`
    refusal). `OrchestraBridge.beatMillis` reads the cadence variant.
  - **The generic multi-Player Conductor — DONE (substrate).** `runtime.module.Conductor` + `Player`
    (ported from the supirvast spike, lifted into the runtime and keyed to `Cadence`) seat several
    differently-cadenced Players on one main thread, deadline-merged, retiring independently. `conduct`
    is re-expressed on it — a Tick *clock Player* seated at its cadence's period, no bespoke loop — so
    the render Player can seat on the *same* scheduler. Covered by `ConductorTest` (eager, two-player
    drain, fixed-period pacing) with `OrchestraTest` unchanged (identical `conduct` behavior).
  - **Direction change (2026-08-02).** The original 2b bullets 2–3 — seat the render Player on the
    *main-thread* Conductor and make a **cooperative** non-blocking graphics cadence — are
    **superseded** by *The concurrency model* above. Cooperative single-thread multiplexing exists only
    to satisfy the one-main-thread constraint; putting app logic on its **own thread** (tier
    "same-process thread") dissolves the non-blocking-present problem instead of engineering around it,
    and it is the GUI-framework thesis (display single-threaded on main, logic parallel off it). The
    cut-2 `Conductor` is retained as the **main-thread lane**; thread placement is added around it. So
    2b's remaining work is folded into the tiered plan below, not a cooperative co-run.
- **Tier-1 mailbox spike — DONE (host-level, the same-process-thread row).** `runtime.module.Mailbox`
  (the bounded, thread-safe inbox — the sole shared object) + `MailboxSpike` (the harness): display on
  the calling thread, application logic on a spawned daemon, communicating *only* through two mailboxes
  of immutable messages. A `Press` (input) flows display → logic, a `Render` (frame) flows back; the
  counter state lives only on the logic thread, the frames only on the display thread — *nothing shared
  but the queues*. `MailboxSpikeTest` asserts the round-trip in order and that capacity-4 mailboxes
  apply backpressure under 100 presses without deadlock or reorder. No Pontif grammar yet; this is the
  boundary every higher tier reuses (swap a Mailbox for a socket and the two halves are two processes,
  code unchanged).
- **In-memory journal — DONE (additive).** `runtime.module.EventJournal` taps the existing
  observational `IrInterpreter.EventListener` seam, so it records the ordered stream of immutable events
  a run fires **without touching the synchronous fold** — install it, run, read the stream back. That
  stream is byte-for-byte the cross-process wire format (journal = transport). It carries the two things
  supervision needs: a **commit-marker** (splitting the log into a silently-replayed committed prefix
  and a live-replayed uncommitted tail) and a **dead-letter** list (the poison-message bound).
  `EventJournalTest` covers the marker split, dead-lettering, and capture-in-order of a real conduit
  program's emits. Thread-safe (`CopyOnWriteArrayList` + atomic marker) for when Players journal
  concurrently; per-inbox partitioning waits for real mailboxes (below).
- **`Mailbox` is the agnostic boundary — DONE.** Backed by a `LinkedBlockingQueue` (the two-lock queue
  — separate put/take locks), so the many producers and the single draining owner never contend on the
  same lock. Bounded (backpressure) + blocking (parks the consumer); the JDK has no lock-free queue
  that is *also* bounded-and-blocking, so this is the right backpressured pick without an external SPSC
  dependency. The Mailbox knows nothing of tiers — same-process now, socket-fed later, no change above.
- **How much interpreter work threaded `spawn` actually needs (narrower than first thought).** A *pure*
  spawned routine — one that computes and returns through its mailbox without folding a shared conduit
  — touches only local `Environment`s and the read-only linked registries, so with the concurrent
  Mailbox it is **already thread-safe**; no interpreter-wide lock is required. The one thing that still
  needs care is **cross-thread conduit folding** (two Players sharing the single `conduitState` map):
  that wants per-Player state ownership, and it is a later refinement — *not* a blocker for spawning a
  routine onto a thread. So `fireEvent` stays synchronous on the main lane; only a conduit *placed*
  off-thread needs its own state cell, built when placement puts it there.
- **`spawn` proper — mirror the GPU async model (next).** A `spawn` of a routine reuses the
  `Pending`/`outstanding`/drive-to-quiescence machinery `… on Gpu` already has (see *Results flow
  forward* above): the "device" is a daemon thread, the dispatch registers a `Pending` whose result is
  fetched off a result `Mailbox`, and a **woven completion `emit`** delivers it forward to a reacting
  action on the main thread at quiescence. **No `await`** — the language gains no synchronization
  primitive. The new work is the parser/lowering for the woven completion `emit` (mirroring how
  `… on Gpu` weaves its emit) and the daemon-backed `Pending`; the drive-to-quiescence loop is reused
  as-is. Then the **supervision boundary** (catch/retire/restart-from-`INIT`+replay/escalate) rides the
  journal + dead-letter bound.
- **Placement — `over X targeting Y`:** the tier matrix made real. `over thread` (in-process queue) →
  `over process` (elektroq socket + a **process spawner**, which neither repo provides yet — new work)
  → `over host` / GPU, with honest `[… | Unreachable]` boundary types. `over thread` and `over process`
  share one integration pattern; only the transport row changes.

Interpreter seams the Conductor builds on (verified): `IrInterpreter.fireEvent` (folds the matching
conduit, state in `conduitState`, routes to actions + `NativeFunctions` sinks), `CompiledModule`'s
`conduitsByType`/`conduitsMatching` (bare-name, trait-aware), the blocking-native pattern
(`main`'s eval blocks inside a native, as `window`/`present` do). Conduits match by **bare** event
type name; effect sinks by **fully-qualified** name.
