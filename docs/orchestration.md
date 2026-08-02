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
  - **Next — the generic multi-Player Conductor** driving *several* conduits **and** the graphics
    render Player together (the original motivation) — unify the supirvast spike with the
    pontif-runtime Conductor; the render Player (pontif-builtin-vulkan) seats on the runtime Conductor.
    This is where `Vsync`/`Retained` become real (the swapchain/`glfwWaitEvents` source).
  - **Then — non-blocking graphics cadence** (`present()` moves off the blocking `window.run()` onto
    `tick()`/`drain()`, the Slice-1 supirvast shape, so it stops hoarding the thread).
- **Slice 3 (`spawn` proper):** the `spawn` term in the grammar (parser), its effectful-expression
  semantics + returned handle, and the **supervision boundary** (catch/retire/restart/escalate).
- **Slice 4 (placement):** the `over X targeting Y` axis — thread / process (ElectroQ transport) /
  host / GPU — with honest boundary types (`Unreachable`).

Interpreter seams the Conductor builds on (verified): `IrInterpreter.fireEvent` (folds the matching
conduit, state in `conduitState`, routes to actions + `NativeFunctions` sinks), `CompiledModule`'s
`conduitsByType`/`conduitsMatching` (bare-name, trait-aware), the blocking-native pattern
(`main`'s eval blocks inside a native, as `window`/`present` do). Conduits match by **bare** event
type name; effect sinks by **fully-qualified** name.
