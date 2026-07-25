package sibarum.pontif.gui;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.dasum.gui.core.ui.Ui;
import sibarum.dasum.gui.mathtext.LaidOut;
import sibarum.dasum.gui.mathtext.MathConstants;
import sibarum.dasum.gui.mathtext.MathLayout;
import sibarum.dasum.gui.mathtext.MathOgl;
import sibarum.dasum.gui.vis.plot.Axis;
import sibarum.dasum.gui.vis.plot.LinePlot;
import sibarum.dasum.gui.vis.plot.PlotFrame;
import sibarum.dasum.gui.vis.plot.PlotScene2D;
import sibarum.dasum.gui.vis.plot.PlotScene2DRenderer;
import sibarum.dasum.gui.vis.plot.PlotStyle;
import sibarum.dasum.gui.vis.plot.PlotView;
import sibarum.dasum.gui.vis.plot.Series;
import sibarum.dasum.gui.vis.scene.InteractionSpec;
import sibarum.dasum.gui.vis.scene.Layer;
import sibarum.dasum.gui.vis.scene.SceneStates;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.NativeCalls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static sibarum.pontif.gui.GuiShared.*;
import static sibarum.pontif.gui.MathText.*;
import static sibarum.pontif.gui.ReliableSeries.*;

/**
 * 2D chart construction with annotations (docs/plotting.md, docs/reliable-plotting.md): decomposes a
 * {@code chart} layer tuple into series + markers + asymptotes + enclosure bands (applying the
 * per-layer primitive failsafe), lifts them into the shared {@link PlotScene2D} IR that drives both
 * the on-screen renderer and the SVG exporter, and stacks a colour-coded typeset math title above.
 * Split out of the former god-class {@code DasumBridge}. Depends on {@link ReliableSeries}/{@link MathText}.
 */
final class ChartBuilder {
    private ChartBuilder() {}

    /**
     * The shared line-chart component: a {@link Component.SceneView} carrying a single
     * {@link Series} published through a {@link PlotView}, axes auto-ranged to the data, pan/zoom
     * enabled. Used both by the {@code LinePlot} element and the {@code plotLine} sampler.
     */
    static Component buildLinePlotView(double[] xs, double[] ys) {
        return chartComponent(List.of(Series.line(xs, ys, SERIES_COLOR)));
    }

    /** Turn a {@code {layers}} tuple of {@code Curve} records into line series. A curve carrying an
     *  explicit colour ({@code colored = true}) uses its own {@code {r,g,b}}; the rest auto-colour
     *  from {@link GuiShared#SERIES_PALETTE}, cycled by the order of the <em>un-coloured</em> curves (so
     *  an explicit colour doesn't shift the palette slot of a later auto curve).
     *  Package-visible: the headless test seam for {@code renderChart}. */
    static List<Series> buildChartSeries(Object layersValue) {
        List<Series> out = new ArrayList<>();
        int autoIdx = 0;
        if (layersValue instanceof RecordValue tuple) {
            for (Object member : tuple.members().values()) {
                if (member instanceof RecordValue rv && "Curve".equals(bareType(rv.typeName()))) {
                    double[] xs = doubles(rv.members().get("xs"));
                    double[] ys = doubles(rv.members().get("ys"));
                    Color color = rv.members().get("colored") instanceof Boolean c && c
                            ? new Color(clamp01(memberD(rv, "r")), clamp01(memberD(rv, "g")),
                                        clamp01(memberD(rv, "b")), 1f)
                            : SERIES_PALETTE[autoIdx++ % SERIES_PALETTE.length];
                    out.add(Series.line(xs, ys, color));
                }
            }
        }
        return out;
    }

    /**
     * The shared 2D line-chart component: a {@link Component.SceneView} carrying the given
     * {@link Series} published through a {@link PlotView}, axes auto-ranged over ALL series, with
     * gridlines + tick labels (from {@link PlotStyle#defaults()}), pan/zoom enabled.
     */
    static Component chartComponent(List<Series> series) {
        Component.SceneView view = plotSceneView();
        PlotFrame frame = LinePlot.autoFrame(0f, 0f, 10f, 5.5f, series);
        new PlotView(view).showLinePlot(frame, series, PlotStyle.defaults());
        // Pan/zoom fenced to the plot's world rect so it can't be dragged off screen.
        SceneStates.setInteraction(view, InteractionSpec.panZoom2d()
                .withPanBounds(frame.wx0(), frame.wy0(), frame.wx1(), frame.wy1()));
        return view;
    }

    /** Em gap laid out between two adjacent equations in a multi-expression title. Also consumed by
     *  {@link PlotSvg#titledChartSvg} so the exported title matches the on-screen layout. */
    static final double TITLE_GAP_EM = 1.0;

    /** Single-AST convenience (a reliable plot's title in the default {@link GuiShared#TEXT} colour).
     *  Delegates to the colour-coded list form. */
    static Component mathTitleComponent(RecordValue ast) {
        if (ast == null || !isAlgExprNode(ast)) return null;
        return mathTitleComponent(List.of(new TitledExpr(ast, TEXT)));
    }

    /**
     * A fixed-height viewport typesetting one or more {@code AlgExpr} ASTs as a math title (STIX Two
     * Math) placed above a reliable plot. With several expressions (a multi-plot {@code chart}) they
     * are laid out left-to-right with a gap and each is drawn in its curve's palette colour, so the
     * title colour-codes to the lines. Baselines are aligned across the equations. Returns
     * {@code null} when there is nothing typesettable (so a plot without an expression stays untitled).
     */
    static Component mathTitleComponent(List<TitledExpr> titles) {
        if (titles == null || titles.isEmpty()) return null;
        MathConstants mc = MathConstants.stixTwoMath();
        List<LaidOut> laids = new ArrayList<>();
        for (TitledExpr t : titles) laids.add(new MathLayout(mathAtlas(), mc).layout(algExprToMathBox(t.ast())));
        double maxAsc = 0, maxDesc = 0, totalW = 0;
        for (int i = 0; i < laids.size(); i++) {
            maxAsc = Math.max(maxAsc, laids.get(i).ascent());
            maxDesc = Math.max(maxDesc, laids.get(i).descent());
            totalW += (i == 0 ? 0 : TITLE_GAP_EM) + laids.get(i).width();
        }
        float w = (float) Math.max(1e-3, totalW), h = (float) Math.max(1e-3, maxAsc + maxDesc);
        // All equations share the baseline y = maxDesc; each is offset in x by the running width.
        List<Layer> layers = new ArrayList<>();
        double xoff = 0;
        for (int i = 0; i < laids.size(); i++) {
            LaidOut laid = laids.get(i);
            float originY = (float) (maxDesc - laid.descent());     // baseline = originY + descent = maxDesc
            layers.addAll(MathOgl.toLayers(laid, mc, titles.get(i).color(), 1f, (float) xoff, originY, /*yUp*/ true));
            xoff += laid.width() + TITLE_GAP_EM;
        }
        // The bar height sets the title size: the ortho camera fits the equations tightly to the bar
        // height (PlotView's 2D fit). 3.5em reads as a prominent title without dominating the window.
        Component.SceneView view = (Component.SceneView) Ui.sceneView()
                .background(PLOT_BG).height(Em.of(3.5f)).grow(0).interactive(false).build();
        PlotFrame frame = new PlotFrame(0f, 0f, w, h, Axis.linear(0, w), Axis.linear(0, h));
        new PlotView(view).show(frame, layers);
        return view;
    }

    /** Stack a typeset math title above a plot (title fixed-height, plot grows to fill). With no
     *  title AST the plot is returned unwrapped. */
    static Component titledPlot(Component plot, RecordValue titleAst) {
        return titledPlot(plot, mathTitleComponent(titleAst));
    }

    /** As above, from a colour-coded list of expressions (a multi-plot chart's title). */
    static Component titledPlot(Component plot, List<TitledExpr> titles) {
        return titledPlot(plot, mathTitleComponent(titles));
    }

    private static Component titledPlot(Component plot, Component title) {
        if (title == null) return plot;
        // grow(1) so the wrapper takes its slot's main-axis space (correct at the window root, and
        // it won't collapse the plot). The title is fixed-height; the plot grows to fill the rest.
        return Ui.column().fill().grow(1).gap(Em.of(0.3f)).padding(Em.of(0.3f))
                .add(title).add(plot).build();
    }

    /**
     * The standalone chart window root: the titled plot filling the window, plus (when {@code export})
     * a centered Export-SVG button below it. Everything is root-descended with proper grow/stretch, so
     * nothing collapses. The button's click runs on the GLFW thread — where NFD's Save dialog lives —
     * and writes the same semantic SVG as the {@code exportSvg} native.
     */
    static Component chartRoot(AnnotatedChart chart, boolean export) {
        Component plot = titledPlot(annotatedChartComponent(chart), chart.titles());
        if (!export) return plot;
        Component button = Themed.button("Export SVG", Em.of(11f), Variant.PRIMARY, 0,
                () -> PlotSvg.writeChartSvgDialog(chart));
        Component buttonRow = Ui.row().justify(JustifyContent.CENTER).padding(Em.of(0.4f)).add(button).build();
        return Ui.column().fill().gap(Em.of(0.2f)).add(plot).add(buttonRow).build();
    }

    // --- Supplemental expression layers: reliable curve + annotations -----------------------------
    // (docs/reliable-plotting.md) `chart(cfg, {expr(e), zeros(e), optima(e), asymptotes(e),
    // intersections(e,g)})` composites an interval-reliable curve with feature MARKERS, LABELS, and
    // half-opacity vertical ASYMPTOTE lines — all expression-driven, all in one window.

    /** Max markers / vertical lines one annotation layer may paint. A layer that overflows this is
     *  SUPPRESSED with a log — the "unreasonable quantity of primitives" failsafe. */
    static final int FEATURE_CAP = 24;

    /** A detected feature anchor (data coordinates). */
    record Feature(double x, double y) {}

    /** A set of markers sharing a label style: kind 0 = zero (x-axis, x label), 1 = optimum
     *  ("(x, y)" label), 2 = intersection ("(x, y)" label). {@code color} colour-codes them to the
     *  owning plot (null = neutral default). */
    record MarkSet(int kind, List<Feature> pts, Color color) {}

    /** The parsed, failsafe-applied decomposition of a {@code chart} layer list: drawn series, marker
     *  sets, vertical-asymptote x's, and the reliable interval-enclosure band. Package-visible test seam. */
    record AnnotatedChart(List<Series> series, List<MarkSet> marks, List<VLineMark> vlines,
                          List<PlotScene2D.EnclosureBand> enclosures, List<TitledExpr> titles,
                          double[] exprWindow) {
        AnnotatedChart {
            enclosures = enclosures == null ? List.of() : enclosures;
            titles = titles == null ? List.of() : titles;
        }
    }

    /** One reliably-plotted expression's AST paired with the palette {@link Color} its curve draws in
     *  — so the typeset math title above a multi-plot chart colour-codes each expression to its line. */
    record TitledExpr(RecordValue ast, Color color) {}

    /** A vertical asymptote at data-x {@code x}, tinted {@code color} (null = neutral default). */
    record VLineMark(double x, Color color) {}

    /** A raw marker layer held until all expression plots are seen, so its {@code astKey} can be matched
     *  to the owning plot's colour. */
    private record RawMark(int kind, List<Feature> pts, String astKey) {}

    /** A raw asymptote layer, likewise deferred for colour-matching by {@code astKey}. */
    private record RawVLine(List<Double> xs, String astKey) {}

    /** A reliable plot resolved from an {@code ExprLayer} — its colour, x-window, classified spans, and
     *  (optional) AST for the title — held until the shared y-range is computed across all such plots. */
    private record ExprPlot(Color color, double xlo, double xhi, List<ReliableSpan> spans, RecordValue ast) {}

    /**
     * Decompose a {@code chart} layer tuple into series + annotations, applying the per-layer
     * primitive {@link #FEATURE_CAP} failsafe (an overflowing annotation layer is dropped and logged).
     */
    static AnnotatedChart buildAnnotatedChart(Object layersValue) {
        return buildAnnotatedChart(layersValue, null);
    }

    /**
     * As above, with a runtime {@code ctx} that (when non-null) lets several auto-plots SHARE one
     * x-window: the per-expression auto-frames are unioned into {@code [X0, X1]} and every
     * {@code ExprLayer} is RE-SAMPLED across it, so each reliable curve spans the full frame width.
     */
    static AnnotatedChart buildAnnotatedChart(Object layersValue, NativeCalls.Context ctx) {
        List<Series> series = new ArrayList<>();
        List<MarkSet> marks = new ArrayList<>();
        List<VLineMark> vlines = new ArrayList<>();
        List<PlotScene2D.EnclosureBand> enclosures = new ArrayList<>();
        List<TitledExpr> titles = new ArrayList<>();
        double[] window = exprWindow(layersValue);   // union of the ExprLayers' auto-frames, or null
        List<ExprPlot> exprPlots = new ArrayList<>(); // reliable plots, resolved but not yet drawn
        List<RawMark> rawMarks = new ArrayList<>();   // annotation layers, coloured after all exprs seen
        List<RawVLine> rawVLines = new ArrayList<>();
        int autoIdx = 0;
        if (layersValue instanceof RecordValue tuple) {
            for (Object member : tuple.members().values()) {
                if (!(member instanceof RecordValue rv)) continue;
                switch (bareType(rv.typeName())) {
                    case "Curve" -> {
                        double[] xs = doubles(rv.members().get("xs"));
                        double[] ys = doubles(rv.members().get("ys"));
                        Color color = rv.members().get("colored") instanceof Boolean c && c
                                ? new Color(clamp01(memberD(rv, "r")), clamp01(memberD(rv, "g")),
                                            clamp01(memberD(rv, "b")), 1f)
                                : SERIES_PALETTE[autoIdx++ % SERIES_PALETTE.length];
                        series.add(Series.line(xs, ys, color));
                    }
                    case "ExprLayer" -> {
                        // Each reliable auto-plot takes the next palette slot (shared with Curve), so
                        // overlaid expr(e), expr(r) draw in distinct colours instead of one cyan. Resolve
                        // its spans now; defer series-building until the SHARED y-range is known (below).
                        Color color = SERIES_PALETTE[autoIdx++ % SERIES_PALETTE.length];
                        boolean isAlg = rv.members().get("ast") instanceof RecordValue a && isAlgExprNode(a);
                        RecordValue ast = isAlg ? (RecordValue) rv.members().get("ast") : null;
                        // Re-sample across the SHARED window when we can (ctx + AST + a window); else
                        // fall back to this layer's own pre-sampled spans over its own window.
                        double loU, hiU;
                        List<ReliableSpan> spanList;
                        if (ctx != null && ast != null && window != null) {
                            loU = window[0]; hiU = window[1];
                            spanList = resampleReliable(ast, loU, hiU, ctx);
                        } else {
                            loU = memberD(rv, "xlo"); hiU = memberD(rv, "xhi");
                            spanList = parseSpans(rv.members().get("spans"));
                        }
                        exprPlots.add(new ExprPlot(color, loU, hiU, spanList, ast));
                    }
                    case "MarkLayer" -> {
                        int kind = (int) Math.round(memberD(rv, "kind"));
                        List<Feature> pts = parseMarks(rv.members().get("pts"));
                        if (capOk(pts.size(), markKindName(kind))) rawMarks.add(new RawMark(kind, pts, astKey(rv.members().get("ast"))));
                    }
                    case "VLineLayer" -> {
                        List<Double> xs = parseVLines(rv.members().get("xs"));
                        if (capOk(xs.size(), "asymptotes")) rawVLines.add(new RawVLine(xs, astKey(rv.members().get("ast"))));
                    }
                    default -> { /* unknown layer kind — ignored */ }
                }
            }
        }
        // ONE shared robust y-range across ALL reliable plots, so every curve clips and blows up to the
        // SAME top/bottom — a tall function and a flatter one both reach the frame edges at their poles.
        double[] yShared = null;
        Map<String, Color> colorByAst = new LinkedHashMap<>();   // an expression's AST → its plot colour
        if (!exprPlots.isEmpty()) {
            List<Double> allBounds = new ArrayList<>();
            for (ExprPlot ep : exprPlots) allBounds.addAll(reliableBounds(ep.spans()));
            yShared = allBounds.isEmpty() ? new double[]{-1, 1} : robustRange(allBounds);
        }
        for (ExprPlot ep : exprPlots) {
            series.addAll(buildReliableSeries(ep.xlo(), ep.xhi(), ep.spans(), ep.color(), yShared));
            PlotScene2D.EnclosureBand band = enclosureBand(ep.xlo(), ep.xhi(), ep.spans());
            if (band != null) enclosures.add(band);
            if (ep.ast() != null) {
                titles.add(new TitledExpr(ep.ast(), ep.color()));
                colorByAst.putIfAbsent(astKey(ep.ast()), ep.color());
            }
        }
        // Colour-code each annotation to its owning plot: match its source-expression AST to the plot of
        // the same expression; a null colour (no matching plot) falls back to the neutral default.
        for (RawMark rm : rawMarks) marks.add(new MarkSet(rm.kind(), rm.pts(), colorByAst.get(rm.astKey())));
        for (RawVLine rv : rawVLines) {
            Color c = colorByAst.get(rv.astKey());
            for (double x : rv.xs()) vlines.add(new VLineMark(x, c));
        }
        return new AnnotatedChart(series, marks, vlines, enclosures, titles, window);
    }

    /** A structural key for an AST record tree (type + members, recursively) — two references to the
     *  same expression key equal, so {@code asymptotes(e)} matches {@code expr(e)} regardless of order. */
    private static String astKey(Object v) {
        if (v instanceof RecordValue r) {
            StringBuilder sb = new StringBuilder(bareType(r.typeName())).append('(');
            for (Map.Entry<String, Object> e : r.members().entrySet()) {
                sb.append(e.getKey()).append('=').append(astKey(e.getValue())).append(',');
            }
            return sb.append(')').toString();
        }
        return v == null ? null : String.valueOf(v);
    }

    /** The shared x-window for a chart's reliable plots: the union {@code [min xlo, max xhi]} of every
     *  {@code ExprLayer}'s (Pontif-side auto-framed) window. {@code null} when the chart has no
     *  {@code ExprLayer}. */
    private static double[] exprWindow(Object layersValue) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        boolean any = false;
        if (layersValue instanceof RecordValue tuple) {
            for (Object member : tuple.members().values()) {
                if (member instanceof RecordValue rv && "ExprLayer".equals(bareType(rv.typeName()))) {
                    lo = Math.min(lo, memberD(rv, "xlo"));
                    hi = Math.max(hi, memberD(rv, "xhi"));
                    any = true;
                }
            }
        }
        return any && lo < hi ? new double[]{lo, hi} : null;
    }

    /** The failsafe gate: true if the count is within {@link #FEATURE_CAP}; otherwise log and drop. */
    private static boolean capOk(int count, String layer) {
        if (count <= FEATURE_CAP) return true;
        System.err.println("pontif.plot: '" + layer + "' layer produced " + count
                + " features (cap " + FEATURE_CAP + ") — layer suppressed to avoid cluttering the plot.");
        return false;
    }

    private static String markKindName(int kind) {
        return switch (kind) { case 0 -> "zeros"; case 2 -> "intersections"; default -> "optima"; };
    }

    /** Parse a {@code {pts}} tuple of {@code Mark(x, y)} records into features (in scan order). */
    static List<Feature> parseMarks(Object ptsValue) {
        List<Feature> out = new ArrayList<>();
        if (ptsValue instanceof RecordValue tuple) {
            for (Object m : tuple.members().values()) {
                if (m instanceof RecordValue r && "Mark".equals(bareType(r.typeName()))) {
                    out.add(new Feature(memberD(r, "x"), memberD(r, "y")));
                }
            }
        }
        return out;
    }

    /** Parse a {@code {xs}} tuple of {@code VLine(x)} records into asymptote x-positions. */
    static List<Double> parseVLines(Object xsValue) {
        List<Double> out = new ArrayList<>();
        if (xsValue instanceof RecordValue tuple) {
            for (Object m : tuple.members().values()) {
                if (m instanceof RecordValue r && "VLine".equals(bareType(r.typeName()))) {
                    out.add(memberD(r, "x"));
                }
            }
        }
        return out;
    }

    /**
     * The 2D chart with annotations: axes + drawn series plus overlay layers — a {@code +} glyph and a
     * text label per marker, and a half-opacity vertical line with an x-value label per asymptote — all
     * placed through the shared {@link PlotFrame}.
     */
    static Component annotatedChartComponent(AnnotatedChart chart) {
        PlotFrame frame = annotatedFrame(chart);
        if (frame == null) return errorLabel("chart: no drawable layers");
        Component.SceneView view = plotSceneView();
        new PlotView(view).show(frame, buildAnnotatedLayers(chart, frame));
        SceneStates.setInteraction(view, InteractionSpec.panZoom2d()
                .withPanBounds(frame.wx0(), frame.wy0(), frame.wx1(), frame.wy1()));
        // Return the bare SceneView — it fills its slot as a direct child (fillsCrossAxis), so an
        // EMBEDDED chartView composes without collapsing. The math title is added only at the window
        // ROOT (renderChart), where a wrapping column fills the window; see titledPlot.
        return view;
    }

    /**
     * The shared {@link PlotFrame} for an annotated chart: framed over the drawn series AND every
     * marker point, so an off-curve marker (or an asymptote) is never framed out. Returns
     * {@code null} when there is nothing to draw. Package-visible test seam (pure — no window).
     */
    static PlotFrame annotatedFrame(AnnotatedChart chart) {
        List<Series> framing = new ArrayList<>(chart.series());
        List<Double> mx = new ArrayList<>(), my = new ArrayList<>();
        for (MarkSet ms : chart.marks()) for (Feature f : ms.pts()) { mx.add(f.x()); my.add(f.y()); }
        if (mx.size() == 1) { mx.add(mx.get(0)); my.add(my.get(0)); }
        if (!mx.isEmpty()) framing.add(Series.line(toArray(mx), toArray(my), TRANSPARENT));
        if (framing.isEmpty()) return null;
        PlotFrame auto = LinePlot.autoFrame(0f, 0f, 10f, 5.5f, framing);
        double[] w = chart.exprWindow();
        if (w == null) return auto;
        // Reliable plots: pin the x-axis EXACTLY to the (shared) sampling window so the curves reach
        // the frame edges — no 5% x-pad margin. Keep the data-driven, padded y-range from the sweep.
        return new PlotFrame(0f, 0f, 10f, 5.5f, Axis.autoRange(w[0], w[1], 0.0), auto.y());
    }

    /**
     * Lift the Pontif-side {@link AnnotatedChart} into the dasum {@link PlotScene2D} IR — the single
     * semantic description that feeds BOTH the on-screen renderer and the SVG exporter.
     */
    static PlotScene2D buildScene(AnnotatedChart chart, PlotFrame frame) {
        List<PlotScene2D.Asymptote> asy = new ArrayList<>();
        for (VLineMark v : chart.vlines()) asy.add(new PlotScene2D.Asymptote(v.x(), "x=" + fmt(v.x()), v.color()));
        List<PlotScene2D.Feature> feats = new ArrayList<>();
        for (MarkSet ms : chart.marks()) {
            PlotScene2D.FeatureKind kind = featureKind(ms.kind());
            for (Feature f : ms.pts()) {
                double y = ms.kind() == 0 ? 0.0 : f.y();       // a zero is marked on the x-axis
                feats.add(new PlotScene2D.Feature(kind, f.x(), y, markLabel(ms.kind(), f), ms.color()));
            }
        }
        return new PlotScene2D(frame, chart.series(), asy, feats, chart.enclosures());
    }

    private static PlotScene2D.FeatureKind featureKind(int kind) {
        return switch (kind) {
            case 0 -> PlotScene2D.FeatureKind.ZERO;
            case 2 -> PlotScene2D.FeatureKind.INTERSECTION;
            default -> PlotScene2D.FeatureKind.OPTIMUM;
        };
    }

    /**
     * Axes + drawn series + annotation overlays as OGL layers, via the shared IR — {@link
     * PlotScene2DRenderer#toLayers} owns the marker-glyph / asymptote-line / label-thinning logic
     * (the SVG exporter shares the same {@link #buildScene} IR). Package-visible test seam. */
    static List<Layer> buildAnnotatedLayers(AnnotatedChart chart, PlotFrame frame) {
        return PlotScene2DRenderer.toLayers(buildScene(chart, frame), PlotStyle.defaults());
    }

    /** A marker's label: the x-value for a zero (it lies on the axis), else the point {@code (x, y)}. */
    private static String markLabel(int kind, Feature f) {
        return kind == 0 ? fmt(f.x()) : "(" + fmt(f.x()) + ", " + fmt(f.y()) + ")";
    }
}
