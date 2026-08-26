# SDF → GLSL: rendering Pontif shapes on the GPU

**Status: WAR — declared 2026-07-05.** Turn `pontif.shape` SDF composition into real
GPU raymarching by lowering a shape's signed-distance function to a GLSL `float map(vec3 p)`
and handing it to Dasum's `RaymarchLayer`. Replaces the slow 24³-sample glow preview with a
live sphere-traced surface.

Markers: **RULED** = settled with James · **DERIVED** = follows from ruled material ·
**OPEN** = undecided.

## Context

`pontif.shape` (docs/shapes.md, S1–S4 landed) represents a shape as a runtime tree of
Pontif structs (`Sphere`, `Union`, `Translated`, `SmoothUnion`, …), each satisfying
`SdfShape.distance(x,y,z):Decimal`. `previewGradientField` **interprets** that tree per grid point
(13,824 dispatches), clamps to a shell band, and renders it as a `pontif.plot` `Volume` — a
glow approximation of the gradient field, not a surface.

Dasum now hosts caller-supplied SDF shaders: `RaymarchLayer.standard(sdf, center, halfExtent,
color)` takes GLSL defining **`float map(vec3 p)`** in box-local coords (OpenGL 3.3), splices
it into a full sphere-tracing harness (`raymarch_std.frag`, marker `//@SDF@`), and draws it in
the scene framebuffer with correct depth. It is wired + unit-tested but has **no callers**.
This work is its first consumer.

**Not through SuperVast (RULED, from exploration).** SuperVast has no core→GLSL emitter (only
core→SPIR-V, then `spirv-cross`). Routing Pontif→SuperVast→SPIR-V→spirv-cross→GLSL to feed a
target that already accepts GLSL text is an absurd detour. The old `pontif-supirvast`
Int-compute path is a *different target for a different source*; not retired, just not this.
Its `ExprLowering`/`LoweringError` traversal-and-fail-closed **pattern** is a useful template;
its code (SuperVast `core.Expr` leaves, Int64-only) does not transfer.

## Rulings (James, 2026-07-05)

1. **Decimal→float is a VIEW, exempt from no-lie.** Shapes use `Decimal`; GLSL `map` runs in
   IEEE `float`. A rendered image is a visual approximation, not a claim of exact geometry —
   like other UI ephemera already exempt (feedback_visual_experimentation_mode). Emit float
   freely for rendering; `Decimal` stays the shape's honest own representation. (This clears
   the "settle the float story before shader work" flag from supirvast planning: settled =
   rendering is a view.)
2. **General body-inlining lowerer, not hardcoded templates.** The lowerer reads each shape's
   ACTUAL `distance` method IR and inlines it recursively, so it can't silently diverge from
   the Pontif formula (no-lie) and handles user-defined `assign trait X:SdfShape` for free.
   Fail-closed (LoweringError-style) on anything outside the GLSL-expressible subset. De-risk
   with a Sphere-only wiring spike first.

## Architecture (DERIVED)

The GLSL is generated **interpreter-side** (where the shape value AND its `distance` IR are
both reachable) and crosses to `DasumBridge` as an **inert string** — consistent with the
existing data-only boundary (`buildSceneLayers` is static, sees only `RecordValue`s, no IR).

Flow: Pontif `render(shape)` → native `sdfMap(shape):String` (Java, in `pontif-builtin-shape`)
lowers the shape value to `float map(...)` → Pontif assembles a `Raymarch` layer record
(map string + center + halfExtent + color, all inert) → `scene(cfg, {Raymarch(...)})` →
`renderScene` → `DasumBridge` new `case "Raymarch"` builds `RaymarchLayer.standard(...)`.

**The lowering algorithm (partial evaluation of the shape tree):** given a shape `RecordValue`
and a point expression `p`, emit a GLSL expression for its distance:
- look up the value's type → its `distance` method IR body;
- substitute params `x,y,z` with `p.x,p.y,p.z` (or the current sub-point);
- substitute `this.<field>` with the RecordValue's concrete field value (a float literal for a
  `Decimal`, or a recursive inline when the field is a child `[SdfShape]`);
- rewrite `distanceAt(child, ex,ey,ez)` (the trait-call shim) → recursively lower `child` at
  point `(ex,ey,ez)`;
- map arithmetic + the `pontif.math` GLSL.std.450 calls (`sqrt/min/max/mix/clamp/sin/cos/
  radians/…`, already opcode-tagged in `MathExtension`) to GLSL operators/builtins;
- anything else (unsupported IrExpr, non-GLSL type, unbound name) → a source-located
  `LoweringError`.

**Feasibility gate for slice 1:** the native `Context` (`NativeCalls.Context`, backed by
`IrInterpreter`) exposes `invoke`/`satisfies`/`fireEvent` but NOT method-body IR. Slice 1 must
**extend the Context seam** to fetch a type's `distance` body IR (the interpreter has it via
`CompiledModule`; the seam is explicitly built to be extended). Slice 0 doesn't need it.

## Slice plan (vertical, each end-to-end)

- **Slice 0 — wiring spike (Sphere only).** `sdfMap(sphere)` reads `radius` off the
  `RecordValue`, returns `float map(vec3 p){ return length(p) - <r>; }` (near-hardcoded — just
  to move bytes). Pontif `render(shape)`; `DasumBridge` `case "Raymarch"` →
  `RaymarchLayer.standard`. Verify a sphere actually raymarches in the editor. De-risks the
  whole pipe (boundary, bounds→center/halfExtent, scene hosting) independent of the compiler.
- **Slice 1 — the general body-inlining lowerer. LANDED 2026-07-05.** Extended
  `NativeCalls.Context` with `methodImpl(value, methodName)` (default null; backed in
  `IrInterpreter.resolveMethodImpl` by a scan of `functions()` for the decl named
  `<type>.distance` — the interpreter rewrites `MethodCall` away, so a "Type.method" dispatch
  key doesn't resolve trait impls, but the compiled `functions()` map keys them by that decl
  name). `SdfGlsl` now inlines each shape's real `distance` IR (the algorithm above),
  fail-closed via `SdfGlsl.Unsupported`. Verified: Sphere lowers to its true
  `sqrt((p.x*p.x)+…) - r` (not a hand-written `length`), union→`min`, transforms inline the
  point, smoothUnion/rotateY use `mix`/`clamp`/`sin`/`cos`/`radians`, and a **user-defined
  `assign trait Slab:SdfShape` renders** (`p.y - 0.5`) — dissolving the docs/shapes.md "user
  SDF can only be sampled" blocker (we ship GLSL text, not a function). `RenderLoweringTest`
  (9), examples `render-sphere.ptf` / `render-csg.ptf`.
- **Slice 2 — SDF library. LANDED 2026-07-05.** Since the lowerer reads each shape's real
  `distance` IR, growing the library is pure Pontif source in `ShapeExtension` — no Java
  change, and every addition renders on the GPU for free. Added primitives `Box`, `Torus`,
  `Cylinder`, `Capsule`, `Plane` (scalar analytic SDFs) and smooth boolean modifiers
  `smoothIntersect`, `smoothDifference` (rounding out `smoothUnion`). `PrimitiveTest` (5,
  numeric SDF checks), `BooleanTest` (+smooth), `RenderLoweringTest` render-for-free checks;
  examples `render-primitives.ptf`. `Cone` deferred (fiddly SDF). RULED (James): this was the
  chosen next step over generalizing the macro (concept 1/2 below) or iteration/fractals.

**Concept axis (James, ShaderLab-inspired — for later):** GLSL generation has two modes —
(1) a full vertex/fragment *program* from Pontif (general, further off), vs (2) a *component*
spliced into a pre-built shader (a typed macro; what `//@SDF@` + this lowerer already do). We
are in mode 2. NOTE: GLSL (like SPIR-V) has **no recursion** — slice 1 works by compile-time
inlining a *finite* shape tree into straight-line GLSL; fractals want iteration-as-geometry,
"a different thing," out of scope. Bounded-iteration → GLSL `for` loop (fold over a finite
`Stream[SdfShape]`) is a possible future capability of the snippet compiler, echoing the
supirvast `Iterate → kernel` work retargeted to GLSL.

- **Deferred:** attribute fields (`ScalarField`) → surface color; view modes; refined
  `[Decimal:@>0.0]` primitive params; `Cone`; the macro generalization (mode 1); bounded
  iteration → GLSL loop. `previewGradientField` (sampled glow) stays as the CPU fallback.

## Module placement (OPEN — lean)

The pure IR→GLSL lowerer is reusable compiler tech; lean toward a new `pontif-glsl` module
(feedback_pontif_module_granularity) depending only on `pontif-ir`, consumed by
`pontif-builtin-shape` (which owns the `sdfMap` native + `render` + Dasum wiring). Slice 0 may
keep it inline in `pontif-builtin-shape` and extract in slice 1. Name `pontif-glsl` is a
working default — adjustable.

## WAR markers / files
- `pontif-builtin-shape/.../ShapeExtension.java` — add `render`, the `Raymarch` layer, and the
  `sdfMap` native (`calls()`), currently empty.
- `pontif-builtin-gui/.../DasumBridge.java` `buildSceneLayers` — add `case "Raymarch"`.
- dasum-vis `RaymarchLayer.standard` (`sibarum.dasum.gui.vis.scene`) — the target.
- `pontif-ir/.../NativeCalls.Context` — extend for method-IR access (slice 1).
- `pontif-runtime/.../MathExtension.java` — GLSL.std.450 opcode tags = the math mapping table.
