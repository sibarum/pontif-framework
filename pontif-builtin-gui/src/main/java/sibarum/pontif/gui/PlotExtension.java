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
                exports @.{Curve2D, plotLine}

                # A 2D curve shape: y at each x, over a domain. Assign it to your type and
                # implement the projection in the method bodies; plotLine does the rest.
                trait Curve2D{ at(x:Decimal):Decimal, domain():[{Decimal,Decimal}] }

                # Native: opens a line-chart window from the two sample aggregates. Declared
                # before plotLine, which calls it. The placeholder body is never run.
                function renderCurve(xs:_, ys:_):Stream[String] -> {}

                # 65 evenly-spaced sample indices {0..64}, synthesized once from the refinement
                # (the `;` synthesis directive is a top-level-let feature, so it lives here, not
                # inside plotLine's body).
                let sampleIndices:Stream[Int:0 <= @ < 65];

                # The trait-method call routed through a top-level function: `c.at(x)` resolves
                # in an ordinary function body but not inside a stream fragment, so the fragment
                # calls this instead.
                function sampleAt(c:[Curve2D], x:Decimal):Decimal -> c.at(x)

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

                0
                """;
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        // renderCurve is the only native; the sampling lives in Pontif (plotLine above).
        return Map.of("renderCurve", DasumBridge::renderCurve);
    }
}
