# Plotting — shape-trait directed visualization

Status: **DESIGN (2026-06-30).** Spine + dispatch model ratified with James in conversation;
not yet built. Successor front to docs/extensions.md's GUI slices (G1–G7) and the dasum-vis
visualization slice V1 (`LinePlot(xs, ys)`, on master, `c980e29`). This doc designs the
*high-level* API that V1's primitives sit underneath.

## The inversion

V1 is bottom-up: the program computes the data and names the plot type —
`LinePlot(xs, ys)`. This doc is the top-down inverse: the program declares **what shape its
object is** (by assigning a trait + implementing a small projection), and the library renders
it — one way, or, on request, every way.

The use-case driving it (James): *"I have a custom algebraic object of N dimensions; I can
define a method to transform it into a tuple of a specific shape; I want to make it plottable
and let the library figure out the way(s) to plot it."*

## The model: per-shape traits + per-rendering functions

Two layers, ratified 2026-06-30. This replaces an earlier "dispatch on a function's signature
sort / two-stage" framing — the trait model is cleaner and matches the proven G6 `Clickable`
pattern (the bridge already does `ctx.satisfies(value, trait)` + `ctx.invoke(value, method)`).

### Layer 1 — per-shape traits (what the USER implements)
A trait per *shape*, carrying the projection contract. The user assigns it to their type and
implements the methods — that implementation *is* the "transform my object into a tuple of a
specific shape":
```
trait Curve2D       { at(x:Decimal):Decimal              domain():{Decimal,Decimal} }
trait ScalarField2D { at(p:{Decimal,Decimal}):Decimal    region():{Decimal,Decimal,Decimal,Decimal} }
trait Cloud3D       { points():Stream[{Decimal,Decimal,Decimal}] }
trait Scatter2D     { points():Stream[{Decimal,Decimal}] }
```
Function-valued shapes (`Curve2D`, `ScalarField2D`) expose `at(...)` + a domain so the library
can *sample* — and resample adaptively, and difference for honesty (below). Discrete shapes
(`Cloud3D`, `Scatter2D`) expose `points()` directly.

The user's only job is **"what shape is my object."** Renderings are not their concern.

### Layer 2 — per-rendering functions (what the LIBRARY provides)
One function per rendering, each consuming a shape trait. **Several renderings may consume one
shape** — that one-to-many is exactly where tabs come from:
```
function plotLine(c:[Curve2D]):View          -> …
function plotScatter(s:[Scatter2D]):View      -> …
function plotCloud(c:[Cloud3D]):View          -> …
function plotSurface(f:[ScalarField2D]):View  -> …      # ┐
function plotHeatmap(f:[ScalarField2D]):View  -> …      # ├ three renderings, one shape
function plotContour(f:[ScalarField2D]):View  -> …      # ┘
```
**Rendering is chosen by which function you name** — explicit, never ambiguous. No cast is ever
needed to choose a *rendering*.

### The entry points
- `plotLine(x)`, `plotHeatmap(x)`, … — name the rendering you want.
- `plotAll(x)` — expand `x`'s shape to **every** rendering that consumes it, as a tabbed window.
  (This is the deliberate step *outside* winner-take-all dispatch — the only place overlap is
  embraced.)
- `plot(x)` — the friendly default, **defined only for shapes that have a single rendering**
  (`Curve2D` → `plotLine`). Shapes with several renderings have no bare `plot`, so `plot` never
  silently guesses a default. *(Whether to keep `plot` at all is a minor open call.)*

### Where ambiguity lives (and why it's rare + honest)
Dispatch is winner-take-all; overlap is forbidden ([[project_pontif_match_vs_dispatch]]). So
ambiguity bites **only when a type satisfies multiple *shape* traits** — a Ternion that is both
`Curve2D` and `Cloud3D`. Then a shape-generic entry can't choose, and you **cast** to pick the
shape: `plotAll(t:[Cloud3D])`. Rendering choice is never ambiguous (it's in the function name),
and a single-shape object never needs a cast. This is the no-lie law doing the disambiguation.

## No function-passing — the projection IS the trait method

There is **no** "pass a function to plot." A type satisfies a shape trait and the trait's
`at` / `points` method *is* the projection — ordinary code. Nothing crosses as a function value;
no metareference (`$f[…]`), no `[Dispatch(…)]` / `[Method(…)]`, no trait hung off a function
sort. Every trait subject is a **named type**, which is exactly the proven G6 path
(`assign trait <Struct>:<Trait>`), so the whole mechanism is already supported.

To plot a standalone function, write the one-line type for it (`sin` from `pontif.math`):
```
requires pontif.math.{sin}
struct Sine()
assign trait Sine:Curve2D { at(x:Decimal):Decimal -> sin(x)  domain() -> {-10.0, 10.0} }
plotLine(Sine())
```
To plot your own object, assign the shape that fits it and project in the method body:
```
assign trait Ternion:Cloud3D { points() -> … project z, n, w … }
plotCloud(myTernions)
```

## Discrete derivatives = the honesty instrument

Not a garnish — the mechanism that keeps a plot from lying ([[project_pontif_no_lie]]). Because
the function-shapes expose `at(...)`, the renderer can sample, then finite-difference:

1. **Adaptive sampling** — refine where `|f''|` is large (where the curve bends); reuse the
   camera's `onViewportResize` / `onCameraChange` hooks (already wired in V1) so zoom re-samples.
2. **No-lie shape analysis** — detect a discontinuity / asymptote and **break the polyline**
   rather than draw a confident smooth segment across it; detect undersampling / aliasing and
   surface it instead of rendering a false-but-plausible curve. A plot is a *claim* about the
   object; the derivative is how the library keeps the claim honest.

## Scalars & discreteness (resolved 2026-06-30)
- **`Decimal` is the canonical continuous scalar**; trait methods are typed in `Decimal`. `Frac`
  is continuous too and folds to `Decimal` for sampling.
- **Discreteness is a trait choice, not an auto-rule.** Continuous data → `Curve2D` (a connecting
  line is honest). Discrete data → `Scatter2D` / `points()` (dots, no connecting line). Putting
  discrete data in `Curve2D` — drawing a line through integer samples and so asserting values
  that don't exist between them — is the lie the trait choice avoids ([[project_pontif_no_lie]]).

## Defaults (James: "sensible defaults for everything")
- Unrefined domain → `[-10.0, 10.0]` (and the `region()` analogue for 2-D shapes).
- A refinement sort on the domain *overrides* the default (`at`'s param `[Decimal: 0<=@<=6.28]`
  ⇒ sample that interval), via the existing finite-range synthesis.
- Sampling resolution ~256, then adaptive.

## Implementation slices
- **Slice 1 — one shape, one rendering, end-to-end.** `Curve2D` + `plotLine`: a user struct
  assigns `Curve2D` and implements `at`/`domain`; `plotLine` samples and renders. Default
  `[-10,10]`, fixed resolution. Builds straight on V1's `LinePlot`/`Series`/`PlotView`. Sampling
  is done **Pontif-side** (generate the range, `map` through the trait's `at`), so only primitives
  cross and no native→interpreter callback is needed. Verifies the trait-dispatch machinery on the
  simplest case.
- **Slice 2 — a second shape + `plotAll`.** Add `Cloud3D` + `plotCloud` (PointLayer), and
  `plotAll` collecting all renderings of a shape into tabs (here trivially one each, but it
  exercises the tab machinery).
- **Slice 3 — multi-rendering shape.** `ScalarField2D` → `plotSurface` ∣ `plotHeatmap` ∣
  `plotContour`; `plotAll` now produces three tabs. The real test of the one-shape→many-renderings
  relationship.
- **Slice 4 — custom objects + projections.** A user struct assigning a shape trait with a
  non-trivial `at`/`points`; n-D → first-3 / PCA projection shape.
- **Slice 5 — adaptive sampling + derivative honesty.** Finite-difference refinement;
  discontinuity / asymptote breaks; undersampling warnings.

## Composition, graduations, and styling (landed 2026-07-01)

The single-shape entry points (`plotLine`/`plotCloud`/`plotSurface`) each open their own
one-layer window. Alongside them, **composition mirrors the GUI's `window(cfg, {children})`**:
a shape becomes a *layer value*, and one render call composites many layers into one window.
The dasum-vis engine already renders a list of layers with correct depth occlusion (OPAQUE
layers depth-write) and 3D text (`TextLayer`), and already carries the 2D axis stack (`Axis`,
`Ticks`, `PlotFrame`) — so this is a Pontif-side surface over existing primitives.

- **3D scenes** — `scene(cfg, {layers})`. Layer producers: `surface(h)` / `surfaceFine(h)`
  (65×65), `cloud(c)`, `text3d(text, {x,y,z})`. Solid surfaces occlude by depth; `fade(surface(h),
  0.5)` makes one translucent so a layer behind shows through (the "stack on top" case is just
  opacity, not a mode). `cmap(surface(h), "viridis"|"turbo"|"grayscale"|"cool")` picks the height
  colormap; `wire(surface(h))` overlays the sample-grid mesh.
- **2D charts** — `chart(cfg, {curve(a), curve(b), …})`. Overlays multiple curves (each auto-coloured
  from a palette) with the axes/gridlines/tick-labels the 2D stack already draws. The palette also
  covers reliable `expr(…)` layers, so several auto-plots (`{expr(e), expr(r), …}`) overlay in
  distinct colours with a colour-coded math title — see docs/reliable-plotting.md. Mixing 2D and 3D
  layers is deliberately unsupported (different camera/interaction).
- **Graduations** — 3D scenes draw a labeled, tick-marked bounding box + floor grid by default
  (`{axes = false}` / `{grid = false}` to disable). Tick positions/labels reuse dasum's
  `Ticks.forAxis` (the same nice-number engine as the 2D charts), placed as billboard `TextLayer`s.
- **Colorbar** — a surface scene shows a colorbar key (colormap + height range) as a sidebar beside
  the viewport, reusing component composition rather than a second camera.

`cfg` mirrors the GUI's `{title = …}`, plus `{axes, grid}` for scenes. It also takes optional
`{width, height}` (pixels) to size the window — e.g. `chart({title = "f", width = 1200, height = 800},
{…})`; omitted (or ≤ 0), each falls back to the 900×600 default. `cfg` is an open record, so these
keys need no signature change. All of this is verified
headlessly in `PlotExtensionTest` via `DasumBridge.buildSceneLayers` / `buildChartSeries` /
`axisBoxLayers` / `colorFor` (no window opens); the window-opening examples live in
`pontif-builtin-gui/examples/` (`compose.ptf`, `chart2.ptf`, `axes.ptf`, `colormap.ptf`,
`wireframe.ptf`).

### Exporting a chart to SVG

A 2D chart can be exported as a **semantically-classed SVG** for embedding in a web page — every
element carries a role class (`curve`, `asymptote`, `feature zero`/`optimum`/`intersection`,
`tick-label`) and its data values as `data-*` attributes, so a host page restyles it in CSS and
drives it with JS. The reliable interval **enclosure band** is emitted too, hidden by default
(`display:none`) — a host reveals it to show the provably-contains-the-curve region.

The plot and the exporter share ONE intermediate representation. The dasum `PlotScene2D` (frame +
curves + asymptotes + features + enclosure) is the single semantic scene that feeds *both*
`PlotScene2DRenderer` (the on-screen OpenGL layers) and `SvgPlotWriter` (the SVG string); the drawing
meaning — marker glyphs, half-opacity asymptote lines, overlap-thinned labels — is defined once,
never duplicated per backend. The Pontif bridge lifts its `AnnotatedChart` into that IR.

To wire it up, embed the chart with `chartView(cfg, {layers})` (like `chart`, but a *component* you
place in a GUI `window(cfg, {children})` instead of its own window) and put a button beside it whose
`onClick` calls `exportSvg(layers)` on the SAME layer tuple. Clicking pops a native Save dialog
(dasum NFD) and writes the file — no Pontif filesystem API needed. See
`pontif-builtin-gui/examples/svg-export.ptf`.

> **Layout.** The bridge builds its component tree through dasum's `Ui` builders, whose columns
> default to `STRETCH` and whose `Ui.sceneView()` defaults to fill+grow — so a plot resolves whether
> it's the whole window, stacked with a button, or nested in a `Column`. As a backstop, window build
> runs `Ui.lint` (a real layout pass): if a plot/scene ever still collapses to zero size it throws
> immediately with a fix hint, rather than rendering a silent blank. (This replaced an earlier trap
> where a `SceneView` wrapped in a non-stretching `Flex` collapsed to zero width.) The Pontif→IR→SVG path is verified headlessly in
`PlotExtensionTest` (`DasumBridge.buildScene` + `SvgPlotWriter.write`); the dialog itself is manual.

**Known limit (configurable resolution).** Sampling maps over a *statically-synthesized* index
stream (`let surfaceIndices:Stream[Int:0<=@<1089];`), and finite-range synthesis needs static
bounds — so a fully-dynamic sample count `N` isn't expressible in pure Pontif yet. `surfaceFine`
(65×65) is a fixed preset; a truly dynamic `N` is blocked on the infinite/lazy-stream work.

## Open questions
- Keep the bare `plot` (single-rendering shapes only) or drop it for explicit names + `plotAll`?
- Module placement: a dedicated `pontif.plot` module vs folding into `pontif.gui` (leaning
  dedicated, per the module-granularity preference).

## The renderer seam

`pontif.plot` is the toolkit's face to the language, and that face is being replaced. Dasum (GLFW/OpenGL) is
going; VexelRay is arriving; the replacement is deliberately **not a port** — `pontif-builtin-gui` is to be
deleted rather than rewritten.

**Half of it is already gone (2026-08-26).** The module's other extension, `pontif.gui`, was replaced by Anybox
on VexelRay ([anybox.md](anybox.md)) and deleted — a clean split, because `pontif.plot.ptf` never required
`pontif.gui` and no plot path called the declarative walker. What is left of `pontif-builtin-gui` is
`pontif.plot`, its window loop, and the reason below for why it is still here at all.

**The editor is not part of that deletion** (ruled 2026-08-26, correcting an earlier plan that had the two leave
together). `pontif-playground` stays, on its own toolkit, for as long as it is the editor — so the cut is
`pontif-builtin-gui` **alone**, and the editor has to be able to compile and run without it. That is a different
and stricter requirement than "they go together", and it is what §[The cut](#the-cut-when-it-comes) below is
rehearsed against.

So the state worth being in is one where **the windowed extension can leave on its own and nothing else has to
change**. `ToolkitContainmentTest` is what keeps it true: no module outside `pontif-builtin-gui` and
`pontif-playground` may so much as name the toolkit, in code, in a pom, or in a comment. The editor is on that
list permanently rather than pending its own deletion — it is a toolkit consumer, and allowed to be one.

### What the seam actually is

Two modules needed the renderer and neither needed the toolkit, which is the whole finding.

- **`pontif-builtin-shape`** asked for exactly `Volume(vs, box…, opacity, normals, stride)` and
  `scene(cfg, layers)`. It reached them by depending on `pontif-builtin-gui`, so a pure SDF-algebra assertion —
  `distanceAt(Box(1,1,1), 2, 0, 0) == 1` — pulled a window toolkit onto the test classpath, and one of its tests
  went on to assert things about the renderer's own layer objects, which is the renderer's contract asserted
  through shape. The dependency is gone: `RenderLoweringTest` runs against `StubRenderer`, a six-line
  `pontif.plot` registered as an ordinary service, and asserts only what shape produces — the sampled grid, the
  lowered GLSL, the world box.

  **`pontif.shape` still says `requires pontif.plot.{Volume, scene}`**, and that line is the seam rather than a
  leak: any renderer satisfying it will do, and `StubRenderer`'s six lines are that contract written down. Worth
  knowing, though: without *some* `pontif.plot` the shape module does not link **at all**, not even for
  `distanceAt`. The honest end state is `render(shape)` returning a value a renderer consumes rather than calling
  one — the dependency pointing from the renderer to shape, not back. That is a language-surface change, and it
  is the thing to do when the VexelRay path lands, not before.

- **The root pom** carried `<dasum.version>`. A build declaring the version of a GUI toolkit is a build that knows
  a GUI toolkit exists; the property now lives in the two poms that use one, and the reactor is toolkit-free.

- **The editor** compiled against the windowed extension in three places: `App` twice (the `--run-gui` flag path
  and the Run-as-GUI action) and `DefinitionNavigator` once (parsing `pontif.gui`'s source so its names stay
  navigable). All three now go through `OptionalGui`, which resolves the two class names at runtime, so the
  extension is **a capability the editor may or may not have**: it compiles without it, runs without it, and the
  Run-as-GUI action reports that it cannot rather than spawning a child JVM that dies of a missing main class.
  The dependency is `runtime`-scoped for exactly that reason — it must be on the classpath, and nothing may
  compile against it. The cost, stated: a reflective call is not a native-image reachability anchor, so the
  `-Pnative` editor needs `OptionalGui.LAUNCHER` in its reflection config to keep that path working.

  Making it runtime-scoped immediately exposed a second hidden coupling: **the editor's debug port was resting on
  the doomed module.** `App` imports `DebugServer`, `DebugSession` and `ProgramLauncher` directly and never
  declared `pontif-builtin-net` — it arrived transitively *through* `pontif-builtin-gui`. Now declared, at compile
  scope, where it always belonged.

`pontif-cli` and `pontif-builtin-net` only mentioned it in prose (a JVM flag's comment, a "counterpart to
`GuiLauncher`" note) — reworded, because after the deletion they would be explaining themselves in terms of
something that no longer exists.

### The cut, when it comes

Delete `pontif-builtin-gui`: the module, its `<module>` entry, and its one `<dependency>` block in
`pontif-playground`. The editor stays. **Rehearsed, not assumed** — with the module gone from the reactor and from
the editor's pom, the editor compiles and 19 of its 20 navigator tests pass. What the day actually costs:

1. ~~One test goes with it.~~ **Settled 2026-08-26, and not the way the rehearsal predicted.**
   `DefinitionNavigatorTest.resolvesGuiExtensionStruct_withoutGlobalInstall` was expected to be deleted with
   `pontif.gui`. But `pontif.gui` did not vanish — it *moved*, to `pontif-builtin-anybox` — so the test was
   re-pointed at a symbol that still exists (`Box`) and keeps guarding what it always guarded: that the editor can
   navigate into a windowed extension it never installs. A rehearsal predicts what breaks, not what the fix is.
2. ~~The Run-as-GUI action stops working and says so.~~ **Also settled**: `OptionalGui`'s two class names now
   resolve to `AnyboxLauncher` / `AnyboxExtension`, and the editor carries `pontif-builtin-anybox` at runtime
   scope in place of the block it used to carry for this module. Run-as-GUI is unaffected by this module's
   removal, and so is ordinary Run (`ProgramLauncher`, in `pontif-builtin-net`, which stays).
3. ~~**`pontif.shape` stops linking entirely.**~~ **SETTLED 2026-08-26 — the honest answer, taken.**
   `render(shape)` and `previewGradientField(shape)` are now `raymarch(shape)` and `gradientField(shape)`,
   returning a `Raymarch` and a `GradientField` instead of calling `scene`. `pontif.shape` no longer names
   `pontif.plot` anywhere, so the dependency points **renderer → shape** and shape links, runs and tests with no
   renderer present. `StubRenderer` went with the requirement that created it, and `RenderLoweringTest`'s harness
   collapsed to three lines that read the program's own result. This was the one item on this list that needed a
   decision rather than a deletion; it no longer needs either.
4. `pontif-cli`'s `EditorLauncher` is unaffected — it resolves an editor binary by name and env var, and the
   editor is still there.
5. Drop `pontif-builtin-gui` from `ToolkitContainmentTest.MAY_NAME_IT`, leaving the editor as the sole entry.
   The rule still has something to protect: the editor keeps its toolkit, and nothing else may acquire one.
