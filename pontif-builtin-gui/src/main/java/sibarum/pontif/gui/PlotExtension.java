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
