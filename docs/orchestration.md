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

> **Refinement (2026-08-02).** The single primitive is realized as **two surfaces**: `spawn` **seats a
> conductor** (a standing worker — entry-point-only manifest, static topology; see *Seating*), and `on X`
> **places work** (a one-shot async computation, body-level — the `… on Gpu` / `… on Thread` case). Both
> are "a routine over `X` targeting `Y`"; they differ only in whether the placed thing is a live worker or
> a single computation, and `spawn` is no longer a body-level term.

## Three roles

They **name seams that already exist** — this is a unification, not an invention.

| Role | Is | Nature |
| --- | --- | --- |
| **Player** | a **conduit** — an event handler over its conductor's (single-owner, mutable) state; ticked by a cadence (see *Authoring*) | single-owner, shares nothing → the conductor is **mobile** (place it anywhere) |
| **Instrument** | an **effect sink** (`NativeFunctions.Effect`: `StdOut`, `SetText`, `present`) | impure, touches the world → **pinned** to its hardware |
| **Conductor** | a **worker** that runs Pontif code (a thread/process; one per worker — see *The conductor graph*) | owns conduits + an inbox, wires clocks, routes emits on the static graph |

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

## Authoring — three types, one member block (RULED 2026-08-02)

`struct`, `trait`, and `conductor` are the **three kinds of authorable type**, and they share **one member
syntax** — the trait member block that already exists:

```
trait Duck { quack:[Method():Audio],  eat:[Method(food:Food):Poop] }
```

A member's *kind* is read from its **type-constructor**, not a keyword. `Method` and `Dispatch` (an
operator overload) already work this way; `Action` and `Conduit` join them. A body present (`name(…) ->
…`, or the general `name:[…] -> …`) makes a member concrete/default; absent, abstract. `method` becomes
pure **sugar** — `name(params):R -> body` ⟺ `name:[Method(params):R] -> body` — so the keyword retires and
every type gets methods from the one mechanism (the tell of the right seam: one construct, every type
benefits for free).

- **struct** — concrete data (+ behaviour). Its *data* stays a positional declaration, because that is the
  pattern-matching contract: fields are exposed in full, ordinality is only convenience, and there is no
  constructor overloading — the field list **is** the public constructor. A `{ }` block adds methods only:
  `struct Point(x:Int, y:Int) { length():Decimal -> sqrt(x*x + y*y) }`.
- **trait** — an abstract contract (members abstract, or defaulted via a body). Unchanged.
- **conductor** — a live worker, authored as a type; **seated**, not constructed, at the entry point.

### Effectful members are transform-sorts, not annotated methods

An `Action`/`Conduit` is **not** "a `Method` plus an emit list." Per *Logic in the sorts* (README): a
member's sort can *be* a transform-chain, and an effectful member's behaviour lives **there** — the sort is
the thing itself, the way `[@==5]` is a value, except with side-effects. An **Action** is a transform-chain
whose terminus is **write-only** (an `emit`) rather than a returned value — "a transform chain without the
output type":

```
onKey:[KeyPress -> match @ { [ctrl 's'] -> emit SaveRequested()  [char] -> emit Insert(@.ch) }]
```

`@` is the event in flight; the stages emit; nothing is returned. A **Conduit** is the same with a value
terminus (the state) alongside its effects. These compose exactly like the README's **shells**:
author-owned when written in a conductor, **trait-owned** when a conductor *satisfies* an event-interface
trait — so the sort carries the consumed/emitted interface, the routing graph becomes **type-checkable**
(single-owner, no-consumer, cross-conductor cycles — from the types alone), and conductors become
**interface-typed, swappable components** on the same narrowing/satisfaction machinery traits already use.

### A conductor has mutable, single-owner state

Everything in the language is immutable **except a conductor's own state** — and that is the one place
mutation is provably safe. A conductor is single-owner (one thread drains it), so its fields are
thread-confined: never shared, so never raced. This is the seam conductors exist to smooth — purity
everywhere the compiler can guarantee it, mutation confined to exactly where it cannot hurt.

So a conduit is a **handler over conductor fields**, not a self-contained fold. Several handlers share the
conductor's state directly, which is what makes multi-input conductors read naturally (the GUI toy's
mouse + keyboard + dialog all mutating one `doc`) instead of the fold-threading workaround that existed
only because there was nowhere else to put shared state:

```
conductor Editor {
  doc: Doc = Doc.blank                # mutable, but single-owner → safe
  onKey:[KeyPress  -> … this.doc = … ]
  onClick:[Click   -> … this.doc = … ]
  onSaved:[Saved   -> … this.doc = … ]
}
```

Mutable state stays *deterministically replayable*: re-running the event sequence through the handlers
rebuilds `doc`, deterministic as long as nothing ambient leaks in (the determinism rule) — which is what
keeps the journal usable for *deliberate* recovery, not that the runtime auto-restarts (see *Failure* —
it doesn't). Conductor **methods** are private
helpers — they group logic and touch the state but aren't exported to dispatch. A **handle** (`let h =
spawn Editor`) is the only conductor value that escapes; the worker itself never becomes a passable value.

> **Implemented (2026-08-02) vs ratified.** The conductor authoring model above is **built and
> end-to-end tested**: `conductor Name { field:T = init, handler(e:E) -> … this.field = … }` parses,
> seats via entry-point `spawn`, and runs with mutable single-owner state (several handlers sharing one
> field — `ConductorStateTest`). **Two ratified pieces are not yet wired** (roadmap gaps 1–2): the
> `emits`/consumes **interface is extracted but unused** (`EmitInterface`), so the type-checked routing it
> feeds doesn't exist; and the shipped **dead letter fires on the wrong trigger** (§Honest edges). The
> standalone `conduit` keyword still uses the older **fold** form (`conduit N(e,s):{R,S} from INIT`,
> docs/reactive-gui.md) and coexists with the conductor handler form.

## Seating — the entry-point manifest (RULED 2026-08-02)

Conductors are **static** (see *The conductor graph*), so there is no runtime conductor spawn, and `spawn`
is **not** a body-level term. It is an **entry-point-only** statement — the manifest of which conductors
this program runs. Libraries *define*; only the entry point *activates*. This is the privilege `main`
already has (a required module's `main` is inert — `ModuleLinker.combine` takes `main` from the entry
module alone; `RequiredModuleMainInertTest` pins it), generalized to every worker:

- **Libraries define, the app activates.** `requires audioLib` loads its conductor *definitions*
  hermetically — no window opens, no thread starts. Only a top-level `spawn` (or `main`) in the entry
  module brings a conductor to life; a `spawn` written anywhere else is a compile error. This is what
  stops `requires pontif.vulkan` from silently opening a window.
- **`main` is the pinned root; `spawn` seats the siblings.** `main ( body )` / `main Conductor(…)` is the
  one conductor pinned to the main thread, whose retirement drains the orchestra. `spawn Conductor
  [over X]` seats another worker; **`over X` (thread / process / host) rides the seat** — the *app*
  chooses where each conductor runs, not the conductor's author. Source order = deterministic startup.
- **The handle is the endpoint** (Erlang's Pid). `let audio = spawn audioLib.player` binds the thing you
  address directed messages to; `emit` is ambient (into the graph), a handle-send is addressed (one
  conductor) — same substrate.
- **The whole runtime is the manifest.** Reading the entry point top-to-bottom lists every live worker in
  boot order. No dependency can stand up a thread you didn't name — the runtime is a pure function of what
  you seat, so a change to what runs is always traceable to a conductor add/update/remove, never to merely
  pulling in a library. A conductor's statically-declared **sub-conductors** ride along as its subtree
  (inspectable, still traceable — the teardown / isolation tree).
- **Program lifetime.** A program ends when the orchestra **drains** (every conductor retired), not when
  `main`'s body returns; the root's retirement tears the rest down (closing the window ends the program).

Async *compute* offload is a **different axis** and stays body-level: `expr on Thread` / `… on Gpu`
dispatches a one-shot computation whose result returns forward as an event (below). That is expression
**placement**, not `spawn` — `spawn` seats conductors; `on X` places work.

### Results flow forward — there is no `await` (RULED 2026-08-02)

There is **no thread synchronization at the language level — only orchestration.** Async work is
fire-and-forget: nothing blocks a thread waiting on a result, and there is no future / join / `await` to
read one back. An offloaded computation's output returns the *only* way any effect returns — as an
**emitted event** the substrate routes forward to a reacting handler. (Cross-conductor messaging is the
same shape: a handler `emit`s, the routing table delivers to the owning conductor's inbox, it folds on its
own thread — never a caller blocking on a reply.)

This is not new: it is the **exact ruling already made for `… on Gpu`** (2026-07-05, `IrInterpreter`
line ~110 — "consumed by a reacting `action`, forward only; no `await` reads it back"). So **async offload
(`… on Gpu`, `… on Thread`) mirrors the GPU model**, with a daemon thread as the "device":

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
- **A crash is a stop, not a supervised restart** (see *Failure* below — Pontif's deliberate deviation
  from Erlang). Totality makes crashes exceptional, so the non-total / opt-in cases (`[!!]`, a resource
  limit, an external fault) **crash clean and halt** — the runtime does *not* catch a handler throw to
  retire or restart it, because a crash can land mid-effect and no journal makes that safe to replay.
  Fault *isolation* is the placement axis's job (an own-process conductor's crash is OS-contained);
  *restart* is an external supervisor's, never the language's.

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
   they are the **distribution/placement axis's** job, answered by **external** supervision + replication
   across nodes (the operator's, not the runtime's — see *Failure*). So placement earns the *outer*
   guarantee as totality earns the *inner* one, and "it can't crash" is defensible spoken precisely:
   **no internal error source, modulo a small trusted core, with external faults answered by replication.**

**Placement is also the fault-isolation axis.** Same-thread conductor = fast, but a crash *or* hang is
**shared-fate** — it takes the whole process down. Own-process/host conductor = the OS isolates *both*
crash and hang, at the cost of the boundary (marshaling) and exactly the `Unreachable`-typed handle the
honest-types rule already demands — and it is the boundary an external supervisor restarts *across*.
Choosing a chair is simultaneously a performance choice and a trust/fault-isolation choice — and the
type system forces you to acknowledge it.

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
| **same-process thread** | in-process bounded queue | spawned daemon; a crash is **shared-fate** (takes the process) | local |
| **separate process** | elektroq socket + a process spawner | OS-isolated crash boundary; **external** restart | `[… \| Unreachable]` |
| **cross-machine** | elektroq socket | OS-isolated; **external** restart | `[… \| Unreachable]` |

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
(for audit + *deliberate* external recovery — never an auto-restart, see *Failure*) and the run-anywhere
transport therefore **consume the same stream**; they differ
only in where it is written (RAM vs socket). Build the in-memory journal now and most of the
cross-process wire model comes with it — which is exactly how the thread-first plan avoids blocking the
process-later future.

### Failure — crash clean, restart is external (RULED 2026-08-02)

Pontif **deviates from Erlang here**: the platform does **not** guarantee uptime and does **not**
auto-restart. Totality earns the austerity — handlers are *proven* not to crash, so a crash is
genuinely exceptional (a `[!!]` you opted into, a resource limit, an external fault, a real bug), not
the routine event Erlang's supervision papers over. When one happens the runtime **crashes clean and
stops**; it does not catch, retire, or restart a handler.

The reason is the no-lie law taken to its conclusion. Effects don't un-happen, and a crash can land
*mid-effect* — a half-written file, a partial transaction, a sent-but-unacked message — a state **no
journal can undo**. An auto-rebooting runtime that "recovered" by replaying past such a crash would be
**lying about recoverability**, and could *compound* the corruption. So the language refuses to pretend:
it records faithfully and stops honestly.

**Isolation stays; restart leaves.** Erlang fused the two; Pontif separates them, keeping the half a
language can do soundly:

- **Isolation is the placement axis.** `over process` / `over host` gives a conductor a real OS crash
  boundary — its crash is contained, siblings live. That, the language guarantees.
- **Restart is the operator's.** systemd, a process manager, k8s, a supervising process, replication
  across nodes — whoever holds the domain knowledge of *whether* a restart is safe. The language hands
  them a clean crash and the journal; it imposes no policy.

So the **journal is not an auto-heal.** It is three things: the wire format (§"The journal is the wire
format"), an audit / inspection log, and *raw material an external tool can use for a deliberate,
risk-owned recovery*. The **commit-marker** is a technique for that deliberate recovery (replay the
committed prefix silently, resume from the tail) — never a runtime guarantee and never corruption-proof:
past a mid-effect crash it can still land in corruption, which is exactly why the runtime won't do it
for you.

Orderly **shutdown** is untouched and is *not* crash-recovery: the root/`main` conductor's retirement
tears down the rest (closing the window ends the program). That is lifecycle, not resurrection.

## The conductor graph — static topology, dynamic resources

> Ratified 2026-08-02 (James, thinking aloud → agreed). The wiring model: what is fixed at compile
> time, what is fluid at runtime, and why the split makes a whole class of failures impossible.

### A conductor *is* the worker

**"Conductor" is the name of the thing that runs Pontif code** — a thread, a process, the main thread
itself. It is no longer a coordinator-of-others sitting apart; each conductor *is* one logical worker,
and a running program is a **hive-mind of conductors** passing event messages between them. This
retires the need to say "thread" or "process" for the general case — the placement axis (`over X`)
just chooses *which conductor* (and where its hardware lives). The `Conductor` class already built is
the per-worker executor: a run-loop that now also owns an inbox, its conduits, and the static routing
table.

### The graph is fixed at compile time

- **Every conduit belongs to at most one conductor** — the one that *owns* that event type, as its
  primary receiver (a public-API endpoint) or primary emitter (a status broadcaster). One owning fold
  per type; **many static subscribers** for fan-out (the reacting actions/conduits elsewhere).
- **The whole routing graph — who owns what, which conductor they sit on, every subscription — is
  constructed at compile time.** Routing is therefore a resolved table, not a runtime lookup: the
  compiler **specializes each emit-site** — a same-conductor emit lowers to a *direct synchronous fold*
  (no mailbox hop), a cross-conductor emit to a *mailbox enqueue*. "Practically hardcoded"; the mailbox
  cost is paid only where a thread boundary is actually crossed. An emitted type with zero subscribers
  is a statically-known no-op.
- **There are no runtime conductor spawns.** Every conductor is alive and listening before the first
  line of user code runs. This designs out the actor model's nastiest failure mode by construction: a
  message can never reach a conduit that is not yet online, because there is no moment at which a
  conductor exists but is unwired. No initialization races — not "handled," *impossible*.

### Resources are dynamic — decoupled from the worker's lifecycle

The apparent counterexample — *opening a window at runtime* — is resolved by not conflating a
**resource** with a **worker**. A window is not a conductor; it is an **Instrument** (a pinned
effect-sink / resource) that a conductor *acquires* on an `OpenWindow` message and *releases* on close.
The conductor is static and always-listening; it simply hasn't been told to open a window yet.

Mechanically this stays inside "effects at boundaries, immutable data": a conduit's immutable state
carries **resource handles** — opaque refs, data like a file descriptor — while the resource itself
lives in the external world. Open/close are effects that thread a handle into / out of state. The
display conductor is naturally the **main-thread conductor** (the one lane that may own a Cocoa
window), so macOS correctness falls out for free.

**The split generalizes**, which is the tell that it is right: a **remote network peer** is likewise a
*connection resource* a conductor acquires — not a conductor spawned at runtime. Each program keeps its
own static topology and they message-pass across the connection. So **static conductors + dynamic
resources** covers dynamically-opened windows *and* dynamic P2P membership (elektroq's STUN/TURN
future) with one rule.

The only thing genuinely forbidden is a runtime-created **local worker** (dynamic worker topology /
load-scaled pools — the deliberate trade against Erlang). Dynamic *compute* parallelism is a different
axis: data-parallel fan-out (`… on Gpu`) runs *within* a conductor and is unaffected. Nothing you
actually need is in the forbidden gap.

### Honest edges

- **Dead letters — the config gap.** An emitted event whose type falls outside the union of the
  *seated* conductors' consumes-interfaces has nowhere to go. It is **not** an intentional no-op (that
  is an event a seated conductor *does* receive and chooses not to act on — the muted instrument), and
  **not** a crash (no invariant is broken). It is *unhandled by this configuration* — a silent failure
  worth surfacing. Because seating is static, the common case is a **compile warning** ("`emit SaveAs`
  but no seated conductor consumes it"); the runtime **dead letter** is the safety net for coverage
  that isn't locally provable (chiefly cross-process, where the remote handler's presence isn't a local
  static fact). A dead letter is re-emitted as `DeadLetter(event, reason)` routed to a dead-letter
  conductor — a built-in default prints to `StdOut`, an app seats its own to override. It must be the
  **terminus**: an unhandled `DeadLetter` hits a last-resort stderr and stops, never loops.
  > **Impl status (2026-08-05).** The runtime now fires the dead letter on the config gap —
  > `IrInterpreter.hasCoverage`: no conduit, no action bucket, no sink for the type — and stays silent for
  > the muted instrument (a registered handler that rejects the instance). It logs to stderr; the
  > overridable `DeadLetter`-conductor form and the compile-time warning are the remaining refinements,
  > and the cross-conductor "union of seated consumes-interfaces" coverage rides on `EmitInterface`
  > (roadmap gap 1).
- **Backpressure cycles.** Bounded mailboxes + a cycle in the conductor graph can deadlock (A fills B's
  inbox, B fills A's, both block in `send`). The no-`await` ruling already killed the reply-wait
  deadlock; this is the only one left — and the **static graph is exactly the tool to detect cycles at
  compile time**, turning a runtime hang into a compile-time warning.
- **Stale resource handles.** A journal replay faithfully rebuilds a conductor's state, *including* a
  window/connection handle — but the external resource behind it may be gone. So any **deliberate
  external recovery** (never an automatic one — see *Failure*) must **reconcile resources** (re-acquire
  / revalidate a replayed handle), because effects don't un-happen *and* resources can vanish.

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
  stream is byte-for-byte the cross-process wire format (journal = transport). It carries a
  **commit-marker** (for *deliberate* external recovery — replay the committed prefix, resume from the
  tail; not an auto-restart, see *Failure*) and a **dead-letter** list (repurposed for the config-gap
  capture, §Honest edges — *not* poison-message retry; a handler crash is a full crash).
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
- **Conductor-graph runtime shape — DONE (host-level spike).** `ConductorGraphSpike` realizes the
  hive-mind at the host level (à la Slice 1): two conductors on their own threads, each owning its
  conduits + state, a **static routing table** (event type → owning conductor), and events flowing
  forward across conductors. An `app` conductor folds `Command`→counter and **emits `Status`**, which
  the router delivers *across* to the `display` conductor (a cross-conductor hop → enqueue → fold on
  display's thread). All conductors start before any message flows (init race designed out); only the
  mailboxes + the read-only table are shared. `ConductorGraphSpikeTest` asserts cross-conductor order
  under load. This validates the routing/ownership shape the interpreter will take on next.
- **Single-owner rule enforced before execution — DONE (first real compiler brick).** The static-graph
  invariant "every event type has at most one owning conduit" is now checked eagerly:
  `CompiledModule.validateSingleOwnerConduits` runs at the top of `eval`, before any top-level `let` or
  `main`, and rejects two conduits whose event types are in an ancestry relation (one is-a the other's
  trait) — a routing conflict the runtime previously discovered only when such an event was fired.
  Needed a bare-on-both-sides trait check (`TraitRegistry.satisfiesBareBoth`) because conduit keys are
  bare while satisfier registrations are qualified. `fireEvent`'s multi-conduit guard stays as the
  backstop for the residual "diamond" case (a concrete type satisfying two unrelated conduit-key
  traits). `ConduitTest` covers it; the full 1118-test suite is green. (Ran at load rather than link
  because the trait registry isn't fully populated at the point in `IrCompiler` where conduits compile.)
- **Routing reified as a resolved table — DONE (the subscriber side).** `RoutingTable` (pontif-ir)
  holds, per emitted event type, its **owning conduit(s)** and its **subscriber actions** (the fan-out),
  resolved *once* and cached — "routing is a resolved table, not a runtime lookup." The interpreter owns
  one per run; `fireEvent`/`dispatchToActions` now read `routeFor(type)` instead of re-scanning every
  conduit/action bucket per emit. Behavior-preserving (the per-instance `matchSort` refinement still
  gates at the fire site — the table resolves only the candidate *set*, which depends solely on the
  type); full 1118-test suite green, plus `RoutingTableTest` (single owner + both trait subscribers +
  cache identity). This is the object emit-site specialization and cross-conductor cycle detection read.
### Authoring model — LANDED, with tracked gaps (2026-08-02)

Built and end-to-end tested by a parallel work-stream (commits below). The parse/represent + seating +
mutable-state substrate runs real programs through the interpreter.

- **Member type-constructors — DONE (`f26cc45`).** `IrSort.CallSig` gains `Action`/`Conduit` head types;
  `CallKinds.Kind` is `{FUNCTION, DISPATCH, ACTION, CONDUIT}`; the trait/struct member block classifies
  them via `isCallableMemberKind`. `method(…)->` ≡ `name:[Method(…)]->`. (`MemberUnificationTest`.)
- **Conductor authorable type — DONE (`d0eb2c8`).** `IrStmt.ConductorDecl` + `conductor Name { field:T
  = init, handler(e:E) -> … }` (comma-separated members). (`ConductorDeclTest`.)
- **Seating — DONE (`2dc4514`).** A top-level `spawn Name` in the **entry module only** appends that
  conductor's handlers to the run; unknown spawn = compile error; a required module's spawn is inert.
  (`ConductorSeatingTest`.)
- **Mutable single-owner state — DONE (`02b9822`).** `this.field` read + `this.field = v` mutate over a
  per-conductor cell, gated to conductor handlers; several handlers share one field.
  (`ConductorStateTest`, incl. the multi-handler motivating case.)

**Tracked gaps / divergences** (2026-08-02 review — reconcile before calling the model *complete*):

1. **The `emits`/consumes interface is extracted but unused** (`f05f65f`, `EmitInterface`). Zero
   consumers — so the type-checked routing, no-consumer diagnostic, and cross-conductor cycle detection it
   was built to feed **do not exist yet**. The built-but-unwired half; wiring it is what unblocks gap 2.
2. **The dead letter — FIXED (2026-08-05), aligned to the ruling.** It now fires on the **config gap**
   (`IrInterpreter.hasCoverage`: the emitted type has *no registered consumer at all* — no conduit, no
   action bucket, no sink) and stays **silent** for the muted instrument (a registered handler whose
   refinement rejects *this* instance). `DeadLetterTest`'s refinement-miss case now asserts *no* dead
   letter. For the single-conductor runtime "no routing coverage" *is* the seated consumes-interface; the
   cross-conductor form (coverage = union of seated conductors' interfaces) + the compile-time
   no-consumer warning still ride on gap 1's `EmitInterface` wiring.
3. **`conductor` member block vs the trait/struct one — NOT a real DRY problem (resolved 2026-08-05).**
   The *domain* logic (member-kind recognition) is already shared: both blocks classify a callable member
   via `isCallableMemberKind(CallKinds.builtin(…))`, so a new callable kind is added **once** and both pick
   it up. What repeats is only thin loop boilerplate (brace / comma-sep / name / duplicate-set), and the
   member *kinds* legitimately differ (traits: operators, associated types, defaults, shells, attributes;
   conductors: state fields, reaction bodies). A shared `parseMemberBlock` skeleton is an *optional* future
   tidy — **deferred as premature** (the blocks may diverge further; skeleton churn isn't justified yet).
4. **Abstract handler contracts are dead.** `ConductorDecl.handlers` (the `name:[Action(…)]` map) is only
   printed — never checked against the concrete reactions, never routed. Wire "a conductor satisfies its
   declared handler interface", or drop it.
5. **`Action` write-only terminus is unenforced** (nominal return `_`; a value-producing body is silently
   discarded), and a concrete **`Conduit`-with-value-terminus** handler isn't realized inside a conductor
   (only Action-shaped handlers run; `Conduit` survives only as a parse-level contract string).
6. **A non-entry `spawn` is silently ignored, not a compile error** (the doc's Seating section says error;
   the impl no-ops it like an inert `main` — pick one rule for consistency).
7. **Brittle `#caction#` / `#action#` substring contract.** Conductor-reaction routing relies on the
   invariant that the `#caction#`-prefixed key must not *contain* the substring `#action#`, spread across
   `AltParser` (`:2977`), `IrCompiler` (`:173`), and `IrInterpreter`. It holds today, but a typed
   discriminator would be sturdier than a cross-file substring convention.

Coverage note: the untested surfaces track the still-open feature gaps — no end-to-end for a
`Conduit`-value-terminus handler (gap 5), for an abstract handler contract validated against its reaction
(gap 4), or for `EmitInterface` driving anything (gap 1). Closing a gap should bring its test with it.
(Gap 2's `DeadLetterTest` now pins the *correct* config-gap semantics.)

### Runtime, still to build

- **Async offload — `on X`, mirroring the GPU model.** `expr on Thread` (and `… on Gpu`) reuses
  `Pending`/`outstanding`/drive-to-quiescence: the "device" is a daemon thread, the dispatch registers a
  `Pending` whose result is fetched off a result `Mailbox`, and a **woven completion `emit`** delivers it
  forward to a reacting handler at quiescence. **No `await`.** New work: the parser/lowering for the woven
  completion `emit` + the daemon-backed `Pending`; the quiescence loop is reused as-is. (This is *not*
  `spawn` — `spawn` seats conductors; `on X` offloads a computation.)
- **Concurrent runtime — `over thread` (the same-process-thread tier), in cuts.** Additive: a bare
  `spawn C` seats on the MAIN_LANE (synchronous — today's behavior, 1134 tests untouched); `spawn C over
  thread` runs on its own thread.
  - **Cut 1 — DONE (parse + represent).** `spawn C over thread` parses (`over` a contextual keyword);
    `IrStmt.Spawn` carries a `Placement` (`MAIN_LANE`/`THREAD`); printers round-trip it; unbuilt tiers
    (process/host) fail closed. Runtime effect currently identical to the main lane (`ConductorDeclTest`
    parse tests + `ConductorSeatingTest` end-to-end guard).
  - **Cut 2 — DONE (thread-safety prep).** The shared interpreter state a second thread touches is now
    safe, behavior-preserving: `conductorState` → `ConcurrentHashMap` (single-owner per-conductor keys),
    the `RoutingTable` cache → `ConcurrentHashMap` via `computeIfAbsent` (the table reference itself
    `volatile` so a second thread sees a published table, not a stale `null`), `currentConductor` →
    `ThreadLocal` (the firing conductor belongs to the folding thread, so per-thread save/restore can't
    clobber). Full suite green (1135).
  - **Cut 3 — threaded execution, in sub-cuts** (the risky one — it rewires `fireEvent`/`dispatchToActions`
    and the quiescence loop, and 1136 tests lean on synchronous ordering, so it lands green between steps).
    The `ConductorGraphSpike` is the working blueprint: per-conductor daemon + inbox, static owner table,
    forward-only cross-conductor enqueue.
    - **Cut 3a — DONE (placement survives compilation).** The seat's tier now rides onto the compiled
      module: `CompiledConductor` carries a `Placement`, `CompiledModule.threadedConductors()` names the
      `over thread` seats. Behavior-preserving — everything still folds inline; the interpreter can now
      *see* which conductors are THREAD-tier. (`ConductorSeatingTest` asserts `over thread` ⇒ `{Meter}`,
      bare spawn ⇒ empty.)
    - **Cut 3b — DONE (threaded execution).** Every lane — each THREAD conductor *and* the main thread
      (ratified: main is "just another thread with a mailbox") — has an inbox; lanes start before any event
      flows (init race designed out). `fireEvent` routes an event to its owning lane: off-thread ⇒ hand the
      immutable event to that lane's inbox and return; own-thread (or nothing seated) ⇒ fold inline, the
      original synchronous path byte-for-byte. The main thread drives to quiescence — cooperatively drains its
      inbox until an `inFlight` counter hits zero — then poisons + joins the daemons; a handler crash on any
      lane is rethrown there (a crash halts the program). `conduitState` joined `conductorState`/routing as
      concurrent (distinct types fold on distinct lanes at once). Backpressure is left unbounded for now (a
      bounded-inbox refinement). Tests: multi-emit quiescence + a two-`over thread` pipeline that hops
      daemon→daemon→main. Full suite green (runtime 1138). *Deferred:* THREAD conductor + `on Gpu` in one
      program (the `outstanding` list is not yet concurrent); bounded-inbox backpressure.
    - **Cut 3c — gap-1 consumer.** Build the cross-conductor emit graph via `EmitInterface` and detect
      cycles — the first real consumer of that static edge set.
- **Placement — the rest of `over X targeting Y`:** `over process` (elektroq socket + a **process
  spawner**, which neither repo provides yet — new work) → `over host` / GPU, with honest
  `[… | Unreachable]` boundary types. `over thread` and `over process` share one integration pattern;
  only the transport row changes.
- **Failure + dead letters (revised — no auto-restart, see *Failure*).** A handler crash / MIA
  addressed conductor is a **full crash + halt**; there is *no* catch/retire/restart-from-`INIT`
  supervision boundary to build (Pontif's deviation from Erlang). What *is* to build: the **dead-letter**
  path for the config gap — the compile warning ("`emit X`, no seated consumer") plus the runtime
  `DeadLetter(event, reason)` → default-StdOut conductor (overridable). Restart/uptime is external tooling
  over the placement boundary, fed by the journal for *deliberate* recovery.

Interpreter seams the Conductor builds on (verified): `IrInterpreter.fireEvent` (folds the matching
conduit, state in `conduitState`, routes to actions + `NativeFunctions` sinks), `CompiledModule`'s
`conduitsByType`/`conduitsMatching` (bare-name, trait-aware), the blocking-native pattern
(`main`'s eval blocks inside a native, as `window`/`present` do). Conduits match by **bare** event
type name; effect sinks by **fully-qualified** name.
