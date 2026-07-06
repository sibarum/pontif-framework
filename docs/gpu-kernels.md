# GPU compute kernels in Pontif (via SuperVast → SPIR-V → Vulkan)

**Status: PLANNING — 2026-07-05.** The first real use-case of SuperVast as *the* Pontif→GPU
substrate: run an explicitly-marked data-parallel iteration as a compute kernel on the GPU,
wired end-to-end into Pontif (a `.ptf` program dispatches and consumes a real kernel — not a
Java unit test). Deliberately planned + sliced so no piece is overwhelming and there is **no
future unification project** (build async on the event substrate from the start, not a
standalone Promise reconciled later).

Markers: **RULED** (settled with James) · **OPEN** (needs a ruling) · **DERIVED**.

## Context / vision

`SuperVast` (`~/IdeaProjects/supirvast`) is a general GPU-program IR — `core` spans **kernels
and shaders** (it has `VertexFragmentShaderTest`/`MvpShaderTest`/`TextureShaderTest`, floats,
`vec3`, `mat4`, the full `GLSL.std.450` `MathFn` set, textures, structured control flow) and
lowers to **SPIR-V** (compute + graphics) and, via `spirv-cross`, to GLSL/HLSL/MSL. So it is
the substrate for "write arbitrary GPU shaders and kernels in Pontif." **Pontif is the reuse
layer; SuperVast is a target** (one authoring source lowered to possibly-many targets), not the
other way around. Vulkan/SPIR-V is the deliberate target (the full SPIR-V toolchain at runtime
is the selling point) — use-cases are designed around it.

This front is the **compute-kernel** beachhead (cleanest SuperVast path: SPIR-V, no
cross-compile). The **graphics/Dasum** path (SuperVast → spirv-cross → GLSL) and the SDF
work (`docs/sdf-glsl.md`, a direct hand-written Pontif→GLSL spike) fold onto the same substrate
later; the SDF spike is scaffolding, not the long-term home.

## RULED (this design session)

- **Surface = an execution directive `… on Gpu`** on a data-parallel iteration (stream war
  option (b) — a directive, not a value-type ascription and not a metatype). It reads "how to
  run," keeping the value/type honest.
- **GPU is an execution strategy, not a semantic change.** The computed value is identical to
  the CPU version (SuperVast's CPU==GPU differential oracle, `KernelHandle.verify()`, is the
  proof). The GPU is a "stateful source below the pure membrane."
- **Explicit opt-in ⇒ lowering *is* the eligibility check.** Marking `on Gpu` compiles the
  iteration through `KernelLowering`; in-subset → a kernel, out-of-subset → a **source-located
  compile error** (never a silent CPU fallback — that would lie about what ran where).
- **Two-stage gate** (matches the construction-gate + `!!` model): compile-time = kernel *shape*
  eligibility; runtime = device dispatch, where a SuperVast/driver `Rejection` becomes the `!!`
  hazard.
- **Single batch roundtrip; fusion; finite inputs.** One upload → one dispatch → one readback.
  Composed pure ops under one marker **fuse** into one kernel (the SDF tree-inlining, reused) —
  a pipeline is *not* ping-pong. `on Gpu` is a **materialization boundary**: it forces inputs
  finite/materializable, so **infinite/lazy streams are ineligible** (honest, not a limitation).
  **No per-element streaming back** — that is the compute anti-pattern (latency vs throughput).
- **Async, built ON the event substrate — no standalone Promise.** A GPU completion *is* an
  async event; it must be one from day one so there is no future unification.
- **v1 element type = Int (→ i64)**, matching the landed `KernelLowering`; float/vec3 later
  (the `ExprLowering`/`SortLowering` extension) — that is also the shader on-ramp.
- **"resident" GPU streams are OUT** — fusion covers the pure-pipeline case; residency only
  pays for non-fusible multi-dispatch iterative workloads (sims/solvers), far past this.

## THE pivotal OPEN — how the async result comes back

The events substrate RULED that `emit`/`main` yield a **payload-free completion handle** (await
*that* it completes, never *what* it produced) — a result-bearing `Promise<T>` would let the
emitter observe its own downstream *effect* and pierce the purity membrane. That rule was
motivated by **effectful** emits (IO, GUI, mutation). So:

- **(A) Pure-async Future (result-bearing).** A GPU compute kernel is a **pure** function —
  `f(x)` on GPU ≡ on CPU, referentially transparent; there is *no downstream effect to hide*,
  only elapsed time + possible device failure. So awaiting its **result** observes a pure value
  delayed, not an effect — arguably exempt from the payload-free rule. `… on Gpu : Future[Stream[T]]`;
  `await(f)` → `[Stream[T] | !!]` (the `!!` is device Rejection). Ergonomic: bind, await where
  needed, use inline. **Refines a RULED principle** (adds "pure computations are exempt"), so it
  is James's to ratify. *(lean)*
- **(B) Payload-free handle + result via Action (events-uniform).** `emit GpuKernel(…)`; on
  completion the runtime `emit`s a completion event carrying the batch; an `action` consumes the
  result. The caller gets only a liveness handle. Maximally uniform (one async model, no
  special-case), but forces the common "compute and use" case through the Emit→Action detour and
  arguably over-applies an effect-motivated rule to a pure computation.

**Lean: (A)**, on the reasoning that purity is the distinguishing property — the membrane
protects against hidden *effects*, and a pure kernel has none; the differential oracle is the
proof of purity. But this is a genuine refinement of a ruling, and the cleaner-uniformity case
for (B) is real. **James's ruling gates Slice 2's design; Slice 1 is independent of it.**

**RULED (A), 2026-07-05 (James)** — pure-async Future, *conditioned on Future and Action being
interchangeable* (a Future wrappable as an Action, an Action convertible to a Future). See below.

**REFINED 2026-07-05 (James) — NO `await`; delivery is a woven `emit`.** *"I don't think Pontif
should ever have a concept like `await`."* A backward pull (block here, hand me the value) is exactly
what the forward-only membrane forbids. And there is **no built-in result type** either — an early
attempt shipped a `GpuResult(values:Stream[Int])` completion event, but that *assumes* the payload
shape; the moment the payload is user-defined, the existing `emit`/`action` substrate already does
everything cleanly (James: *"Why are we reinventing systems that have already been defined?"*).

So the model is: the kernel's per-element function carries a **woven `emit` of a user-defined event**
(exactly the stdin-echo shape — `emit` threaded through a map). That emit is **sugar, not a live
per-element fire** (a GPU can't emit): the GPU computes the emit's **argument** (a pure value, one
per element), and once the batch resolves the interpreter replays the emit on the host — per element,
in order, single-threaded — so an ordinary `action` reacts. The GPU does the computation; only the
*effect* is deferred. Example:

```
struct AddSamplesEvent(r:Int)
assign trait AddSamplesEvent:Event{}
function thisRunsOnGpu(x:Int, y:Int):Int ->
  let r = x + y
  emit AddSamplesEvent(r)   # sugar — the GPU computes r; the emit is deferred to the host
  r
action log(e:AddSamplesEvent) -> emit StdOut("" + e.r + " ")  e
main ( (&a, &b):[ (x:Int, y:Int) -> thisRunsOnGpu(x, y) ] on Gpu )   # prints "11 22 33 44 "
```

`Pending` survives only as an **internal** handle (the interpreter tracks outstanding dispatches to
drive to quiescence — no user-facing type, no `await`). `main` stays live until every dispatch
resolves, then exits — the same lifecycle as `main echo(&stdin())`. Failure is the `!!` hazard
(a device `Rejection`).

## Future ↔ Action interchangeability (RULED condition; built in Slice 2, documented now)

The condition is satisfiable without a second async model because **`Future` and `Action` are
two cardinalities of the one temporal-stream / event substrate** — "all instances of an event
type over time is a stream" (events.md); a Future is the **length-1** case of that stream:

| | `Future[T]` (pure compute) | `Action` (events) |
|---|---|---|
| cardinality | single-shot (resolves once) | multi-shot (fires per matching instance) |
| role | value **producer** (delayed pure value) | **reaction** consumer (for-effect body) |
| result | carries `T` (payload crosses — pure) | payload-free reaction (effectful) |

So they are the same substrate at different cardinalities/roles, with two explicit bridge ops:

- **Future → Action** (wrap a Future in the Action model): a Future's resolution *is* an event.
  On resolve it `emit`s a completion event carrying `T`; a matching `action(e:Done[T]) -> …`
  reacts. Single resolution → single emit → the Action fires once. Uses machinery already
  landed (`Context.fireEvent` + the 1e Action reaction leg). *(single → fan-out)*
- **Action → Future** (convert an Action/event source to a Future): `next(EventType):Future[T]`
  — a single-shot Future that resolves on the **next** matching event, via an internally-registered
  one-shot Action that resolves-and-unregisters on first match. The standard "await the next
  event" bridge. *(multi → single, take-1)*

And the **handle types align** rather than compete: the effectful **payload-free completion
handle** (`emit`/`main`) and the pure **`Future[T]`** are the *same* async-handle concept,
differing only by whether the payload crosses the membrane — which is exactly the purity gate.
`Future[T]` = the completion handle *plus* a payload, admitted only because the work is pure.

Built in **Slice 2** (both bridges + the Future type on the event substrate); documented here
now to discharge the RULED condition. Interchangeability is a **design invariant** for Slice 2,
not a later reconciliation.

## Dependencies / reality (honest)

- **`KernelLowering` lives on branch `war/supirvast-streams`** (Int-only: `IrExpr.Iterate` →
  SuperVast `KernelSpec` for map+zip; `ExprLowering`/`SortLowering`/`ValueMarshaller`; targets the
  `Accelerator` facade). Not on master; `pontif-supirvast` is **not in the parent reactor**
  (keeps GPU deps out of the core build); SuperVast must be `mvn install`-ed in `~/.m2`. Slice 1
  begins by reviving + building this against current master + current SuperVast.
- **Events async is unbuilt.** Dispatch is synchronous; the scheduler/threads/mailbox (events
  Slice 2) and the completion-handle are design-only. Genuine async GPU needs *some* of this. The
  GPU kernel is the **forcing function** for a *minimal* async-events capability — we build only
  what GPU needs, co-designed, rather than the whole async-events war first.
- **float/vec3 lowering** is needed for shaders and richer kernels; Int-first keeps Slice 1 small.

## Slice plan (vertical, each end-to-end, each comfortable)

- **Slice 1 — synchronous compute path, wired into Pontif.** Revive `KernelLowering` onto
  master; build a `pontif.gpu` extension (own module, ServiceLoader-discovered) exposing the
  surface for a **map/zip Int kernel**; a `.ptf` program dispatches vector-add over two
  `Stream[Int]` → SuperVast `KernelSpec` → SPIR-V → **Vulkan** → result → a `Stream[Int]` the
  program uses. **Blocking** (no async yet — isolates the compute path from the pivotal question).
  Differential **CPU==GPU verify** wired in so the integration proves itself. Example `.ptf` in the
  playground. *This is the beachhead and does not depend on the async ruling.*
  - Sub-cuts: **(1a) LANDED** — revived onto master (one fail-closed `default`), 20/20 green incl.
    a real GPU run. **(1b) LANDED** — new opt-in `pontif-gpu` module (ServiceLoader-discovered),
    concrete `gpuVectorAdd` native dispatches through `KernelLowering` → `Accelerator` → Vulkan; a
    `.ptf` runs it end-to-end (`{1,2,3,4}`+`{10,20,30,40}` → `{11,22,33,44}`; 64-bit survives).
    **(1c) LANDED** — the general `… on Gpu` directive over an arbitrary map/zip fragment. Core:
    a GPU-agnostic `gpu` marker on `IrExpr.Iterate` (threaded through the 4 reconstructors) + a
    `KernelRunners` seam (interface in pontif-ir; impl injected by pontif-gpu); the interpreter
    routes a gpu-marked Iterate to the runner (honest error if pontif-gpu absent); parser `on Gpu`
    postfix (`maybeOnGpu`, contextual — no reserved word). Runner beta-reduces the applied fragment
    (`Apply(λ,args)` → inlined body — the bridge the hand-built KernelLowering tests skipped) then
    reuses `KernelLowering`; relaxed `inputColumnName` (source may be any expr — data binds
    positionally). `(&a,&b):[(x,y)->x+y] on Gpu` → `{11,22,33,44}`, `&s:[(x)->x*x] on Gpu` →
    `{1,4,9,16}`, both on Vulkan. 931 core + 20 supirvast + 4 gpu green.
    **(1d) LANDED** — `handle.verify` differential-oracle test (CPU==GPU agree; `skipped`→pass
    when no GPU, so it holds on any machine) + `pontif-gpu/examples/vector-add.ptf`. README gains
    a "GPU compute kernels" section (the stale "SPIR-V is only a direction" note corrected — GLSL
    and SPIR-V are now two shipped IR backends).

**SLICE 1 COMPLETE (2026-07-05):** `… on Gpu` runs Int map/zip compute kernels on Vulkan
end-to-end from a `.ptf`, synchronous, wired into Pontif via two opt-in modules. Next: Slice 2
(async `Promise` on the event substrate).
- **Slice 2 — async delivery on the event substrate.** Resolve the pivotal OPEN, then: worker-thread
  dispatch, completion surfaced through the events substrate (`fireEvent` is the existing seam), the
  `Pending` handle type per the ruling. GPU becomes the first real **async event source** — drives
  the minimal async-events capability.
  - **2a — worker-thread dispatch + woven-emit delivery (LANDED 2026-07-05).** `… on Gpu` no longer
    blocks the interpreter: `GpuKernelRunner` inlines the fragment lambda + the user function it calls,
    **splits the woven `emit`** (its argument → the kernel output; its event construction, argument
    replaced by a `$gpu0` placeholder → the completion template), lowers + marshals synchronously (so
    a shape-ineligible kernel still errors immediately), and submits only the device round trip to a
    daemon worker pool — returning a `Pending` (internal; new `sibarum.pontif.ir.Pending`) at once. The
    interpreter registers each Pending and, after `main`, **drives to quiescence**
    (`IrInterpreter.eval(CompiledModule)`): for each per-element GPU-computed value it binds `$gpu0`,
    **evaluates the event template** (so the event routes exactly like an author-written `emit` —
    nominal identity comes for free from the normal construction path, no by-hand qualification), and
    `fireEvent`s it on the main thread. Consumption is **forward-only, no `await`, no built-in result
    type** (RULED): the user defines the event and reacts with the landed `action` leg. `KernelRunners`
    gained a `FunctionResolver` so the runner can inline the user function (`ExprLowering` can't lower a
    `Call`); the resolver is built from `module.functions()` in `IrInterpreter.gpuFunctionResolver`. A
    device `Rejection` surfaces as the `!!` hazard (`Pending.values`). `… on Gpu` is for-effect →
    `DriveResult` (renders nothing), like `main echo`. Tests: `GpuKernelTest` (zip + map woven-emit
    runs on Vulkan/CPU-fallback via a reacting action + repeated-dispatch cache hit + no-emit
    rejection). 931 core + 7 gpu green.
    - **Latency fix (2026-07-05).** Measured: a fresh `Accelerator` per dispatch cost **~580 ms**
      (Vulkan context create + SPIR-V lowering + `spirv-val` + pipeline build + ~90 ms teardown), and
      **~1.9 s** the very first GPU touch in a JVM — while the actual compute is **~2 ms**. Slice 1 and
      the first 2a cut both did `new Accelerator()` per `… on Gpu`, throwing away the context SuperVast
      is designed to hold ("repeated runs re-marshal only data"). Fixed: a **single GPU worker thread**
      owns **one long-lived `Accelerator`** (thread-affinity — SuperVast's queue isn't free-threaded,
      and the GPU serializes anyway) plus a **per-kernel `KernelHandle` cache** (keyed by the pure
      kernel's structure). A repeated kernel (e.g. the editor re-compiling on each edit) now reuses the
      context + pipeline at **~2 ms**; a new kernel is **~44 ms**; the ~1.9 s cold init is paid once per
      JVM. (The legacy `gpuVectorAdd` native still uses a per-call Accelerator — it's the slice-1 spike,
      not the `on Gpu` path.)
    - **Spec cache (2026-07-05).** The handle cache skipped *registration* (the ~580 ms build) but
      SPIR-V *lowering* still re-ran every dispatch (on the calling thread, unconditionally). Now the
      lowered `KernelSpec` is cached by the same structural key (`SPEC_CACHE`, a `ConcurrentHashMap` —
      the calling thread may be one of several live editor interpreters). `computeIfAbsent` keeps
      lowering synchronous, so a shape-ineligible kernel is still an immediate `LoweringError`, not a
      deferred `!!`. Pinned by `onGpu_repeatedDispatch_lowersTheKernelOnlyOnce` (asserts a repeat lowers
      zero extra times). So a re-run now pays neither lowering nor registration — only marshalling +
      the device round trip.
    **Deferred to 2b+:** multi-field / multi-emit events (need multi-output kernels — v1 is one emit,
    one field); the parser gap on `main ( &spread:… )` (bare spread inside the `main` paren — use the
    trailing form); `!!` recovery via `match [!!]`; a real worker/mailbox scheduler for the general
    event substrate (2a builds only the GPU-needed minimum); concurrent in-flight ordering guarantees.
    - **SuperVast enablers LANDED (2026-07-05, upstream `~/IdeaProjects/supirvast`).** Two primitives
      Pontif's kernel-reuse / destroy / concurrency slices will consume, verified on a Vulkan device:
      (1) **per-handle release** — `Accelerator.release(KernelHandle)` / `KernelHandle.close()` frees
      one kernel's pipeline without dropping the whole context (degraded-not-dead: a released handle
      still runs on the equal CPU path); the primitive behind a Pontif `destroy(kernel)`. (2) **async
      concurrent dispatch** — `KernelHandle.submitAsync(...)` → `Submission` → `await(...)`, with the
      context now holding several compute queues (round-robined), so N distinct kernels run in flight
      and are awaited together; the primitive behind the forward-only "launch 2, join both" pattern.
      A handle has one descriptor set, so only one in-flight submission per handle (submitAsync throws
      otherwise — distinct handles are free). **NOT yet wired into Pontif:** the user-facing kernel
      handle + `destroy` + concurrent `on Gpu` await the handle-surface naming/design rulings below.

### Execution model — eager dispatch, synchronize on spread (RULED 2026-07-05, James)

The sync/async split is **not** a GPU concept and **not** about map-vs-reduce (that analogy was a
stretch). It is the general **stream/effect duality**, and `… on Gpu` merely inherits both legs like any
other stream producer:

- **Spread `someFunction(&stream)` is synchronous — always** (map or fold, CPU or GPU). Binding a stream
  and spreading it into a function iterates it and blocks until done.
- **The emit/action pattern is the asynchronous model** — fire-and-forward, delivered through the event
  substrate; never synchronized by a spread.

And critically, **nothing is lazy — a stream is eager**. So for `… on Gpu`:

- **`let r:Stream[Int] = frag on Gpu` dispatches eagerly** (`submitAsync`): the GPU work starts *at the
  bind*. The bind does not block; `r` is a stream over the in-flight batch.
- **`&r` synchronizes it** (`await`): the spread is the join — it blocks until that dispatch completes,
  then iterates.

Because binds are eager, concurrency needs **no new syntax** — bind N kernels (all in flight, round-robined
across the compute queues) and then spread each to join it:

```pontif
let r1:Stream[Int] = A on Gpu     # eager — A dispatched now
let r2:Stream[Int] = B on Gpu     # eager — B dispatched now; both in flight concurrently
f(&r1)                            # synchronize on A
g(&r2)                            # synchronize on B
```

- **Slice 2b — `… on Gpu` as an eager stream (the sync leg).** Make the gpu-marked `Iterate` evaluate to
  a `Stream[Int]` value backed by an eager `submitAsync` (kick the dispatch at the bind, don't block).
  Introduce a GPU-backed stream source the interpreter's spread-drive **awaits** when it iterates it — so
  `f(&r)` synchronizes and materializes. The kernel output becomes the **fragment's return value** (not
  the woven emit's argument), and the woven `emit` becomes **optional** — observability relaxes from
  "must emit" to "**must be observed**": spread-consumed *or* woven-emit(+action). The async (emit/action)
  leg is exactly slice 2a, unchanged. Concurrency (multiple eager binds) rides the multi-queue
  `submitAsync`/`await` primitives already upstream. v1 stays single-output (return only; a distinct
  emit-arg = the deferred multi-output kernel). Guardrail: a `Stream[Int]` used where an `Int` is wanted
  is an ordinary type error (no bespoke "can't materialize" rule needed — collapsing to a scalar is just
  `fold`, and `fold(&r, 0)` synchronizes like any other spread).
- **Slice 3 — fusion of composed pipelines.** `(…):[f]):[g] on Gpu` → one fused kernel (reuse the
  SDF inlining traversal). Guarantees pipelines stay one roundtrip.
- **Slice 4 — reductions.** `fold`/`scan` → on-device multi-pass reduction (stays device-resident
  between passes, one host readback).
- **Slice 5 — float/vec3 lowering.** Extend `ExprLowering`/`SortLowering` past Int; the on-ramp to
  shaders + the graphics/Dasum path (SuperVast → spirv-cross → GLSL), folding `docs/sdf-glsl.md`
  onto the substrate.

## Naming (OPEN — offer candidates, James rules)

- The async handle: **`Pending`** — internal only (the interpreter's outstanding-work handle), not a
  user-facing type. `await` was **rejected outright** (James); results are consumed by reacting to a
  woven `emit`. (Earlier candidates `Future`/`Promise`/`Async` and a user-facing `Pending[T]` are moot.)
- The completion event: **user-defined** — the program declares its own event struct and weaves an
  `emit` of it into the kernel (no built-in result type). v1 = one `emit`, one field.
- The directive target: `on Gpu` (current), vs `on Vulkan` / `@gpu` / a named device.
