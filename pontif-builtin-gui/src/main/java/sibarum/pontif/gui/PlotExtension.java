package sibarum.pontif.gui;

import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.module.Extension;

import java.util.Map;

/**
 * The plotting extension (docs/plotting.md), slice V1 — <b>shape-trait directed visualization</b>.
 * A type becomes plottable by assigning a per-shape trait and implementing its projection; a
 * per-rendering function (here {@code plotLine}) consumes that shape. There is no function-passing:
 * the projection <em>is</em> the trait method body.
 *
 * <pre>
 *   requires pontif.plot.{Curve2D, plotLine}
 *   struct Parabola()
 *   assign trait Parabola:Curve2D {
 *     at(x:Decimal):Decimal -> x * x
 *     domain():{Decimal,Decimal} -> {-10.0, 10.0}
 *   }
 *   main ( plotLine(Parabola()) )
 * </pre>
 *
 * <p><b>Sampling is Pontif-side.</b> {@code plotLine} dispatches on the {@code Curve2D} trait
 * (verified routing — {@code DispatchTable.enforceTraitParams}), samples the curve by calling its
 * own {@code at}/{@code domain} methods over the domain, and hands the resulting numeric
 * aggregates to the native {@code renderCurve}. Only primitives cross the boundary — the same
 * contract as the declarative GUI. The native opens the dasum-vis line-chart window.
 */
public final class PlotExtension implements Extension {

    @Override
    public String moduleName() {
        return "pontif.plot";
    }

    @Override
    public String pontifSource() {
        return """
                requires pontif.core.{Stream}
                requires pontif.algebra.{AlgExpr, Interval, Unbounded, Undefined, evalInterval}
                requires pontif.math.{sign, min, max}
                exports @.{Curve2D, Cloud3D, HeightMap3D, plotLine, plotCloud, plotSurface,
                           Surface, Cloud, Text3D, surface, surfaceFine, cloud, text3d,
                           fade, cmap, wire, scene, Curve, curve, color, chart,
                           Volume3D, Volume, volume, normals, plotExpr, autoFrame}

                # A 2D curve shape: y at each x, over a domain. Assign it to your type and
                # implement the projection in the method bodies; plotLine does the rest.
                trait Curve2D{ at(x:Decimal):Decimal, domain():[{Decimal,Decimal}] }

                # A 3D point-cloud shape: the points are given (discrete), no sampling. points()
                # returns a stream of {x,y,z} triples — the shape stated honestly in the contract.
                trait Cloud3D{ points():Stream[[{Decimal,Decimal,Decimal}]] }

                # A height-map surface shape: z = at(x, y) over a rectangular {xlo,xhi,ylo,yhi}
                # domain. plotSurface samples a grid and renders it as a 3D surface.
                trait HeightMap3D{ at(x:Decimal, y:Decimal):Decimal, domain():[{Decimal,Decimal,Decimal,Decimal}] }

                # Native: opens a line-chart window from the two sample aggregates. Declared
                # before plotLine, which calls it. The placeholder body is never run.
                function renderCurve(xs:_, ys:_):Stream[String] -> {}

                # Native: opens an orbitable 3D window from an aggregate of {x,y,z} triples.
                function renderCloud(points:_):Stream[String] -> {}

                # Native: opens an orbitable 3D surface from a row-major grid of heights `zs`
                # (N*N) spanning the {xlo,xhi,ylo,yhi} domain (N inferred from the grid length).
                function renderSurface(zs:_, xlo:_, xhi:_, ylo:_, yhi:_):Stream[String] -> {}

                # 65 evenly-spaced sample indices {0..64}, synthesized once from the refinement
                # (the `;` synthesis directive is a top-level-let feature, so it lives here, not
                # inside plotLine's body). This is the DEFAULT resolution.
                let sampleIndices:Stream[Int:0 <= @ < 65];

                # A runtime-length index range (a §7.9 generator): indexRange(0, k)._0 = {0,1,…,k},
                # the unfold halting when `from` overruns `to`. Unlike the synthesized index streams
                # above (whose length is baked in at compile time), its length is a RUNTIME value —
                # this is what lets curve(f, n) / plotLine(f, n) choose n at runtime.
                let indexRange:[
                  (from:[Int:@>=0], to:[Int:@>=from]):{Stream[Int], Int, Int} ->
                  {from, from+1, to}
                ]

                # 33*33 grid indices for surface sampling (row = i / 33, col = i % 33).
                let surfaceIndices:Stream[Int:0 <= @ < 1089];

                # The trait-method call routed through a top-level function: `c.at(x)` resolves
                # in an ordinary function body but not inside a stream fragment, so the fragment
                # calls this instead.
                function sampleAt(c:[Curve2D], x:Decimal):Decimal -> c.at(x)

                # Same fragment workaround for the 2-arg surface height.
                function surfaceZ(h:[HeightMap3D], x:Decimal, y:Decimal):Decimal -> h.at(x, y)

                # Sample the curve over its domain (65 points) and show a line chart. The
                # sampling is ordinary Pontif — only the concrete numbers cross to the native
                # renderer.
                function plotLine(c:[Curve2D]):Stream[String] -> (
                  let [{lo, hi}] = c.domain()
                  let step = (hi - lo) / 64.0
                  let xs = &sampleIndices:[ (i:Int) -> lo + i * step ]
                  let ys = &sampleIndices:[ (i:Int) -> sampleAt(c, lo + i * step) ]
                  renderCurve(xs, ys)
                )

                # Same, at a chosen resolution: plotLine(f, 200) samples n points over the domain
                # (n >= 2) instead of the default 65. The n-1 intervals span [lo, hi] exactly, so the
                # endpoints always land on lo and hi. Indices come from the runtime `indexRange`.
                # `(n - 1) * 1.0` forces DECIMAL division: a domain with Int bounds (e.g. from
                # `let radius = 2`) makes `hi - lo` an Int, and Int/Int would truncate the step to 0
                # for n >= span+2 — collapsing every sample onto lo (the "ghost curve").
                function plotLine(c:[Curve2D], n:[Int:@ >= 2]):Stream[String] -> (
                  let [{lo, hi}] = c.domain()
                  let step = (hi - lo) / ((n - 1) * 1.0)
                  let idx = indexRange(0, n - 1)._0
                  let xs = &idx:[ (i:Int) -> lo + i * step ]
                  let ys = &idx:[ (i:Int) -> sampleAt(c, lo + i * step) ]
                  renderCurve(xs, ys)
                )

                # Show a 3D point cloud (orbit on drag, zoom on scroll). No sampling — the
                # points are taken straight from the shape and handed to the native renderer.
                function plotCloud(c:[Cloud3D]):Stream[String] -> renderCloud(c.points())

                # Sample the height map on a 33x33 grid (flat index → row i/33, col i%33) and
                # render it as a 3D surface. The grid heights cross to the native renderer; it
                # rebuilds the (x,y) coordinates from the domain and meshes the surface.
                function plotSurface(h:[HeightMap3D]):Stream[String] -> (
                  let [{xlo, xhi, ylo, yhi}] = h.domain()
                  let dx = (xhi - xlo) / 32.0
                  let dy = (yhi - ylo) / 32.0
                  let zs = &surfaceIndices:[ (i:Int) -> surfaceZ(h, xlo + (i % 33) * dx, ylo + (i / 33) * dy) ]
                  renderSurface(zs, xlo, xhi, ylo, yhi)
                )

                # --- Composition: layers rendered together in one window ---------------------
                # A scene is a config record + a {…} of layer values, mirroring the GUI's
                # window(cfg, {children}). Multiple 3D layers occlude by depth (the default);
                # `fade` makes a surface translucent so a layer behind it shows through.

                # A sampled height-map surface as a composable layer: the sampled grid `zs`, its
                # domain, an opacity in [0,1] (1 = solid, depth-occluding), the height colormap
                # name ("cool" | "viridis" | "turbo" | "grayscale"), and whether to overlay a
                # wireframe of the sample grid.
                struct Surface(zs:_, xlo:Decimal, xhi:Decimal, ylo:Decimal, yhi:Decimal, opacity:Decimal, colormap:String, wire:Bool)

                # 65*65 grid indices for the finer surface preset (surfaceFine).
                let fineIndices:Stream[Int:0 <= @ < 4225];

                # A point set as a composable layer.
                struct Cloud(points:_, opacity:Decimal)

                # A text label anchored at a 3D world point (billboards to face the camera).
                struct Text3D(text:String, x:Decimal, y:Decimal, z:Decimal)

                # Sample a height map into a Surface layer (33x33 grid) — the plotSurface sampling,
                # returning a layer value instead of opening a window.
                function surface(h:[HeightMap3D]):Surface -> (
                  let [{xlo, xhi, ylo, yhi}] = h.domain()
                  let dx = (xhi - xlo) / 32.0
                  let dy = (yhi - ylo) / 32.0
                  let zs = &surfaceIndices:[ (i:Int) -> surfaceZ(h, xlo + (i % 33) * dx, ylo + (i / 33) * dy) ]
                  Surface(zs, xlo, xhi, ylo, yhi, 1.0, "cool", false)
                )

                # A finer 65x65 sampling of the same surface — more detail (e.g. for a spiral or a
                # ridge), at 4x the samples. Dynamic resolution isn't expressible yet (the sample
                # index stream is a statically-synthesized constant), so this is a fixed preset.
                function surfaceFine(h:[HeightMap3D]):Surface -> (
                  let [{xlo, xhi, ylo, yhi}] = h.domain()
                  let dx = (xhi - xlo) / 64.0
                  let dy = (yhi - ylo) / 64.0
                  let zs = &fineIndices:[ (i:Int) -> surfaceZ(h, xlo + (i % 65) * dx, ylo + (i / 65) * dy) ]
                  Surface(zs, xlo, xhi, ylo, yhi, 1.0, "cool", false)
                )

                # Take a point cloud straight from the shape into a Cloud layer.
                function cloud(c:[Cloud3D]):Cloud -> Cloud(c.points(), 1.0)

                # A 3D text label at the given {x,y,z}.
                function text3d(text:String, at:[{Decimal, Decimal, Decimal}]):Text3D -> (
                  let [{x, y, z}] = at
                  Text3D(text, x, y, z)
                )

                # Make a surface translucent (opacity in [0,1]) so layers behind it show through —
                # the "stack on top" knob; solid surfaces occlude by depth on their own.
                function fade(s:Surface, opacity:Decimal):Surface ->
                  Surface(s.zs, s.xlo, s.xhi, s.ylo, s.yhi, opacity, s.colormap, s.wire)

                # Choose a surface's height colormap: cmap(surface(h), "viridis").
                function cmap(s:Surface, name:String):Surface ->
                  Surface(s.zs, s.xlo, s.xhi, s.ylo, s.yhi, s.opacity, name, s.wire)

                # Overlay the sample-grid wireframe on a surface: wire(surface(h)).
                function wire(s:Surface):Surface ->
                  Surface(s.zs, s.xlo, s.xhi, s.ylo, s.yhi, s.opacity, s.colormap, true)

                # A scalar field over a 3D box: value at each (x,y,z), over {xlo,xhi,ylo,yhi,zlo,zhi}.
                # Rendered volumetrically — each voxel's colour is the field's GRADIENT DIRECTION
                # (x-change → red, y → green, z → blue) and its brightness is the gradient steepness,
                # summed additively along the view ray. So the field's fastest-changing boundaries
                # glow, tinted by which axis they change along.
                trait Volume3D{ at(x:Decimal, y:Decimal, z:Decimal):Decimal, domain():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] }

                # 24*24*24 grid indices for volume sampling (x = i%24, y = (i/24)%24, z = i/576).
                let volumeIndices:Stream[Int:0 <= @ < 13824];

                # Fragment workaround for the 3-arg field value (as surfaceZ, but for volumes).
                function volumeAt(v:[Volume3D], x:Decimal, y:Decimal, z:Decimal):Decimal -> v.at(x, y, z)

                # A sampled scalar field as a composable volumetric layer. `opacity` is the glow
                # intensity (additive alpha) — set it with `fade`. `normals` overlays a lattice of
                # short gradient-direction glyphs (set it with `normals`); `stride` is how many
                # voxels apart those glyphs sit (every k-th voxel of the 24^3 grid).
                struct Volume(vs:_, xlo:Decimal, xhi:Decimal, ylo:Decimal, yhi:Decimal, zlo:Decimal, zhi:Decimal, opacity:Decimal, normals:Bool, stride:Int)

                # Sample a Volume3D on a 24^3 grid into a Volume layer (row-major x,y,z).
                function volume(v:[Volume3D]):Volume -> (
                  let [{xlo, xhi, ylo, yhi, zlo, zhi}] = v.domain()
                  let dx = (xhi - xlo) / 23.0
                  let dy = (yhi - ylo) / 23.0
                  let dz = (zhi - zlo) / 23.0
                  let vs = &volumeIndices:[ (i:Int) ->
                    volumeAt(v, xlo + (i % 24) * dx, ylo + ((i / 24) % 24) * dy, zlo + (i / 576) * dz) ]
                  Volume(vs, xlo, xhi, ylo, yhi, zlo, zhi, 0.3, false, 3)
                )

                # Set a volume's glow opacity: fade(volume(v), 0.15). Lower = subtler additive glow.
                function fade(v:Volume, opacity:Decimal):Volume ->
                  Volume(v.vs, v.xlo, v.xhi, v.ylo, v.yhi, v.zlo, v.zhi, opacity, v.normals, v.stride)

                # Overlay gradient-direction glyphs: normals(volume(v), 3). At every `stride`-th
                # voxel a short neutral segment points along the field's gradient there, its length
                # tracking the (normalized) steepness — flat regions draw nothing. The glow shows
                # where the field concentrates; the glyphs show which way it changes.
                function normals(v:Volume, stride:Int):Volume ->
                  Volume(v.vs, v.xlo, v.xhi, v.ylo, v.yhi, v.zlo, v.zhi, v.opacity, true, stride)

                # Native: opens ONE window compositing all the given layers (3D depth occlusion).
                function renderScene(cfg:_, layers:_):Stream[String] -> {}

                # Compose layers into one orbitable window: scene({title="…"}, {surface(a), cloud(b)}).
                function scene(cfg:_, layers:_):Stream[String] -> renderScene(cfg, layers)

                # A sampled 2D curve as a composable chart layer (65 points over its domain).
                # `colored` gates the {r,g,b} line colour (each channel in [0,1]): false ⇒ the
                # colour is auto-assigned from a palette by curve order; set an explicit colour
                # with `color`.
                struct Curve(xs:_, ys:_, r:Decimal, g:Decimal, b:Decimal, colored:Bool)

                # Sample a curve into a Curve layer — the plotLine sampling, as a layer value.
                # Leaves the colour auto (palette by order); override it with `color`.
                function curve(c:[Curve2D]):Curve -> (
                  let [{lo, hi}] = c.domain()
                  let step = (hi - lo) / 64.0
                  let xs = &sampleIndices:[ (i:Int) -> lo + i * step ]
                  let ys = &sampleIndices:[ (i:Int) -> sampleAt(c, lo + i * step) ]
                  Curve(xs, ys, 0.0, 0.0, 0.0, false)
                )

                # Sample a curve at a chosen resolution: curve(f, 200) takes n points over the
                # domain (n >= 2) instead of the default 65. The index stream is built at runtime by
                # `indexRange`, so n is an ordinary value, not a compile-time preset. Colour still
                # defaults to auto (palette by order); wrap in `color` to override.
                function curve(c:[Curve2D], n:[Int:@ >= 2]):Curve -> (
                  let [{lo, hi}] = c.domain()
                  let step = (hi - lo) / ((n - 1) * 1.0)
                  let idx = indexRange(0, n - 1)._0
                  let xs = &idx:[ (i:Int) -> lo + i * step ]
                  let ys = &idx:[ (i:Int) -> sampleAt(c, lo + i * step) ]
                  Curve(xs, ys, 0.0, 0.0, 0.0, false)
                )

                # Give a curve an explicit line colour: color(curve(f), {1.0, 0.0, 0.0}) — an
                # {r,g,b} aggregate, each channel in [0,1]. Without it, curves auto-colour from a
                # palette by their order in the chart.
                function color(c:Curve, rgb:[{Decimal, Decimal, Decimal}]):Curve -> (
                  let [{r, g, b}] = rgb
                  Curve(c.xs, c.ys, r, g, b, true)
                )

                # Native: one line-chart window with all the given curves overlaid (auto axes,
                # gridlines, and tick labels; each curve gets its own colour).
                function renderChart(cfg:_, layers:_):Stream[String] -> {}

                # Overlay several curves in one 2D chart: chart({title="…"}, {curve(a), curve(b)}).
                function chart(cfg:_, layers:_):Stream[String] -> renderChart(cfg, layers)

                # --- Reliable plotting: interval-arithmetic column rasterization -----------------
                # (docs/reliable-plotting.md) An algebraic Expression is rendered by SOUND interval
                # ENCLOSURE, not point sampling: each pixel column's x-interval is evaluated with
                # evalInterval, and the y-enclosure becomes a vertical span. This is blind-spot-free
                # at asymptotes — where point sampling aliases (`tan(1/x)` has ∞ poles between two
                # samples), the enclosure fills the column. Line, break, and block are ONE form:
                # a bounded span is the curve, an empty column is a break, a full column is a pole.

                # 256 pixel columns, synthesized once — the default horizontal resolution.
                let columnIndices:Stream[Int:0 <= @ < 256];

                # One classified column: kind 0 = curve (bounded span [lo,hi]), 1 = pole (Unbounded →
                # a full-height block), 2 = empty (Undefined → a break). The native reads these.
                struct Span(kind:Int, lo:Decimal, hi:Decimal)

                # Classify one column [xa, xb] by its interval enclosure — the reliable core. The
                # three enclosure outcomes map one-to-one onto the three column kinds.
                function classifyColumn(e:AlgExpr, xa:Decimal, xb:Decimal):Span -> match evalInterval(e, xa, xb) {
                  [Interval(lo, hi)] -> Span(0, lo, hi)
                  [Unbounded]        -> Span(1, 0.0, 0.0)
                  [Undefined]        -> Span(2, 0.0, 0.0)
                }

                # Native: frame the y-range and paint the per-column spans as vertical bars in one
                # window (framing native-side for v1). Placeholder body; the native runs.
                function renderReliable(xlo:_, xhi:_, spans:_):Stream[String] -> {}

                # Plot an algebraic expression tree over [xlo, xhi] by reliable interval enclosure —
                # one span per column, evaluated with evalInterval. Takes a bare AlgExpr: pass a
                # hand-built tree, `$f[Decimal].ast` from a proven-Algebraic function, or an
                # Expression's `.ast` field. (A convenience Expression overload is deferred until the
                # transitive pontif.poly import bug is fixed — see docs/reliable-plotting.md.)
                function plotExpr(e:AlgExpr, xlo:Decimal, xhi:Decimal):Stream[String] -> (
                  let dx = (xhi - xlo) / 256.0
                  let spans = &columnIndices:[ (i:Int) -> classifyColumn(e, xlo + i * dx, xlo + (i + 1) * dx) ]
                  renderReliable(xlo, xhi, spans)
                )

                # --- Auto-framing: find the interesting x-window NUMERICALLY ----------------------
                # "Where is the action?" answered by scanning the domain with evalInterval — no CAS /
                # root-finder (that path is blocked by the transitive pontif.poly bug, and it's less
                # robust). A column is a FEATURE if its enclosure is Unbounded (a pole) or its midpoint
                # sign differs from the previous column's (a root crossing) — so this catches
                # irrational and transcendental features exact rational roots never could. The window
                # is the feature span, padded; with no features it falls back to [-10, 10].

                # Bool AND (matches its Bool parameter — a computed comparison can't be a match subject).
                function andB(a:Bool, b:Bool):Bool -> match a { [Bool:true] -> b  [Bool:false] -> false }

                # A column's sign proxy: -1/0/1 from the enclosure midpoint, 2 = pole (Unbounded),
                # 7 = skip (Undefined — off-domain, not a feature and doesn't carry a sign).
                function colSign(e:AlgExpr, xa:Decimal, xb:Decimal):Decimal -> match evalInterval(e, xa, xb) {
                  [Interval(lo, hi)] -> sign((lo + hi) / 2.0)
                  [Unbounded]        -> 2.0
                  [Undefined]        -> 7.0
                }

                # Wrap comparisons in :Bool functions — only a computed comparison used as a match
                # SUBJECT trips totality; a :Bool return or a Bool parameter is fine.
                function isPole(s:Decimal):Bool -> s == 2.0
                function isReal(s:Decimal):Bool -> s < 1.5
                function differ(a:Decimal, b:Decimal):Bool -> a != b
                function atEnd(i:Int):Bool -> i >= 256

                # A feature boundary: a pole column, or a genuine sign flip between two real columns.
                function isFeature(prev:Decimal, cur:Decimal):Bool -> match isPole(cur) {
                  [Bool:true]  -> true
                  [Bool:false] -> andB(andB(isReal(prev), isReal(cur)), differ(prev, cur))
                }

                # The previous REAL sign to carry forward (skip/pole columns don't overwrite it).
                function nextPrev(prev:Decimal, s:Decimal):Decimal -> match isReal(s) {
                  [Bool:true]  -> s
                  [Bool:false] -> prev
                }

                # The left edge of probe column i: 256 columns of width 0.25 over [-32, 32].
                function colX(i:Int):Decimal -> 0.0 - 32.0 + i * 0.25

                # Merge this column's feature into the recursive tail {loX, hiX, found}.
                function combine(x:Decimal, feat:Bool, rest:[{Decimal, Decimal, Bool}]):[{Decimal, Decimal, Bool}] ->
                  match feat {
                    [Bool:true]  -> ( let [{rlo, rhi, rany}] = rest  {min(x, rlo), max(x, rhi), true} )
                    [Bool:false] -> rest
                  }

                # Scan probe columns i..255, carrying the last real sign; return the feature x-span.
                function scanFrom(e:AlgExpr, i:Int, prev:Decimal):[{Decimal, Decimal, Bool}] -> match atEnd(i) {
                  [Bool:true]  -> {1000000.0, 0.0 - 1000000.0, false}   # empty: lo=+big, hi=-big, none
                  [Bool:false] -> (
                    let xa = colX(i)
                    let s = colSign(e, xa, xa + 0.25)
                    let rest = scanFrom(e, i + 1, nextPrev(prev, s))
                    combine(xa, isFeature(prev, s), rest)
                  )
                }

                # Turn the feature span into a padded window; no features → the [-10, 10] default.
                function frameOf(lo:Decimal, hi:Decimal, any:Bool):[{Decimal, Decimal}] -> match any {
                  [Bool:true]  -> ( let pad = max((hi - lo) * 0.15, 1.0)  {lo - pad, hi + pad} )
                  [Bool:false] -> {0.0 - 10.0, 10.0}
                }

                # autoFrame: the numeric x-window for an expression (prev starts at 7 = "no sign yet").
                function autoFrame(e:AlgExpr):[{Decimal, Decimal}] -> (
                  let [{lo, hi, any}] = scanFrom(e, 0, 7.0)
                  frameOf(lo, hi, any)
                )

                # Plot with NO domain now AUTO-FRAMES to the interesting region (was fixed [-10, 10]).
                function plotExpr(e:AlgExpr):Stream[String] -> (
                  let [{xlo, xhi}] = autoFrame(e)
                  plotExpr(e, xlo, xhi)
                )

                0
                """;
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        // The natives are the thin renderers; the sampling/shaping lives in Pontif above.
        return Map.of(
                "renderCurve", DasumBridge::renderCurve,
                "renderCloud", DasumBridge::renderCloud,
                "renderSurface", DasumBridge::renderSurface,
                "renderScene", DasumBridge::renderScene,
                "renderChart", DasumBridge::renderChart,
                "renderReliable", DasumBridge::renderReliable);
    }
}
