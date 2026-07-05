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
    **(1d)** playground example + `handle.verify` CPU==GPU differential.
- **Slice 2 — async delivery on the event substrate.** Resolve the pivotal OPEN, then: worker-thread
  dispatch, completion surfaced through the events substrate (`Context.fireEvent` is the existing
  seam), the Future/handle type per the ruling. GPU becomes the first real **async event source** —
  drives the minimal async-events capability.
- **Slice 3 — fusion of composed pipelines.** `(…):[f]):[g] on Gpu` → one fused kernel (reuse the
  SDF inlining traversal). Guarantees pipelines stay one roundtrip.
- **Slice 4 — reductions.** `fold`/`scan` → on-device multi-pass reduction (stays device-resident
  between passes, one host readback).
- **Slice 5 — float/vec3 lowering.** Extend `ExprLowering`/`SortLowering` past Int; the on-ramp to
  shaders + the graphics/Dasum path (SuperVast → spirv-cross → GLSL), folding `docs/sdf-glsl.md`
  onto the substrate.

## Naming (OPEN — offer candidates, James rules)

- The async handle: `Future[T]` / `Promise[T]` / `Async[T]` / `Pending[T]` (`Pending` reads well
  against the `!!` failure arm). Settle when Slice 2 lands.
- The directive target: `on Gpu` (current), vs `on Vulkan` / `@gpu` / a named device.
