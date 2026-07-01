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
                exports @.{Curve2D, Cloud3D, HeightMap3D, plotLine, plotCloud, plotSurface,
                           Surface, Cloud, Text3D, surface, surfaceFine, cloud, text3d,
                           fade, cmap, wire, scene, Curve, curve, chart}

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
                # inside plotLine's body).
                let sampleIndices:Stream[Int:0 <= @ < 65];

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

                # Native: opens ONE window compositing all the given layers (3D depth occlusion).
                function renderScene(cfg:_, layers:_):Stream[String] -> {}

                # Compose layers into one orbitable window: scene({title="…"}, {surface(a), cloud(b)}).
                function scene(cfg:_, layers:_):Stream[String] -> renderScene(cfg, layers)

                # A sampled 2D curve as a composable chart layer (65 points over its domain).
                struct Curve(xs:_, ys:_)

                # Sample a curve into a Curve layer — the plotLine sampling, as a layer value.
                function curve(c:[Curve2D]):Curve -> (
                  let [{lo, hi}] = c.domain()
                  let step = (hi - lo) / 64.0
                  let xs = &sampleIndices:[ (i:Int) -> lo + i * step ]
                  let ys = &sampleIndices:[ (i:Int) -> sampleAt(c, lo + i * step) ]
                  Curve(xs, ys)
                )

                # Native: one line-chart window with all the given curves overlaid (auto axes,
                # gridlines, and tick labels; each curve gets its own colour).
                function renderChart(cfg:_, layers:_):Stream[String] -> {}

                # Overlay several curves in one 2D chart: chart({title="…"}, {curve(a), curve(b)}).
                function chart(cfg:_, layers:_):Stream[String] -> renderChart(cfg, layers)

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
                "renderChart", DasumBridge::renderChart);
    }
}
