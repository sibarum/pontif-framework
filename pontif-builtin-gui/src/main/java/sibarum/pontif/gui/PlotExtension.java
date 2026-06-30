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
                exports @.{Curve2D, Cloud3D, HeightMap3D, plotLine, plotCloud, plotSurface}

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

                0
                """;
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        // The natives are the thin renderers; the sampling/shaping lives in Pontif above.
        return Map.of(
                "renderCurve", DasumBridge::renderCurve,
                "renderCloud", DasumBridge::renderCloud,
                "renderSurface", DasumBridge::renderSurface);
    }
}
