package sibarum.pontif.gui;

import sibarum.dasum.gui.core.dialog.FileDialog;
import sibarum.dasum.gui.vis.plot.Series;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static sibarum.pontif.gui.GuiShared.*;

/**
 * The native façade of the GUI/plot extension (docs/extensions.md, docs/plotting.md): each public
 * method is a native call registered by {@link GuiExtension}/{@link PlotExtension}. It parses the
 * primitives-only argument records and delegates the actual work to the cohesive collaborators —
 * {@link GuiTree} (declarative UI + window loop), {@link ChartBuilder}/{@link ReliableSeries} (2D
 * charts), {@link SceneBuilder} (3D scenes), {@link MathText} (typesetting), {@link PlotSvg} (SVG
 * export), and {@link LiveEdit} (interactive windows). Only Pontif primitives cross the boundary; the
 * former god-class body now lives in those classes. Shared leaves are in {@link GuiShared}.
 */
public final class DasumBridge {
    private DasumBridge() {}

    /**
     * {@code window({title = …}, {root…})}: build the dasum tree from the root record(s), open the
     * window, and block in the loop (root thread) until closed. Returns the inert for-effect result.
     */
    public static Object openWindow(List<Object> args, NativeCalls.Context ctx) {
        String title = cfgStr(args, 0, "title");
        if (title.isEmpty()) title = "Pontif";
        Object rootTree = args.size() > 1 ? args.get(1) : emptyTuple();
        return GuiTree.openWindowWithRoot(title, cfgInt(args, 0, "width", WIDTH), cfgInt(args, 0, "height", HEIGHT),
                false, () -> GuiTree.toComponent(rootTree, ctx));
    }

    /**
     * {@code renderCurve(xs, ys)} (pontif.plot, docs/plotting.md): opens a window showing a line
     * chart of the two sample aggregates. The sampling itself happens in Pontif; this native receives
     * only the concrete numbers and renders them — the primitives-only boundary.
     */
    public static Object renderCurve(List<Object> args, NativeCalls.Context ctx) {
        double[] xs = !args.isEmpty() ? doubles(args.get(0)) : new double[0];
        double[] ys = args.size() > 1 ? doubles(args.get(1)) : new double[0];
        return GuiTree.openWindowWithRoot("Plot", false, () -> ChartBuilder.buildLinePlotView(xs, ys));
    }

    /**
     * {@code renderChart(cfg, {layers})} (pontif.plot): opens ONE line-chart window overlaying all
     * the given {@code Curve} layers, each in its own palette colour, with auto axes / gridlines /
     * tick labels. Multi-series composition; the 2D sibling of {@link #renderScene}.
     */
    public static Object renderChart(List<Object> args, NativeCalls.Context ctx) {
        String title = cfgStr(args, 0, "title");
        if (title.isEmpty()) title = "Chart";
        Object layers = args.size() > 1 ? args.get(1) : emptyTuple();
        ChartBuilder.AnnotatedChart chart = ChartBuilder.buildAnnotatedChart(layers, ctx);
        boolean export = cfgBool(args, 0, "export", false);   // add an Export-SVG button below the plot
        return GuiTree.openWindowWithRoot(title, cfgInt(args, 0, "width", WIDTH), cfgInt(args, 0, "height", HEIGHT),
                false, () -> ChartBuilder.chartRoot(chart, export));
    }

    /**
     * {@code renderCloud(points)} (pontif.plot): opens an orbitable 3D window showing a point cloud.
     * {@code points} is an aggregate of {@code {x,y,z}} triples shaped in Pontif; this native flattens
     * it to {@code float[]} and renders.
     */
    public static Object renderCloud(List<Object> args, NativeCalls.Context ctx) {
        float[] xyz = !args.isEmpty() ? xyzTriples(args.get(0)) : new float[0];
        return GuiTree.openWindowWithRoot("Cloud", true, () -> SceneBuilder.buildCloudView(xyz));
    }

    /**
     * {@code renderSurface(zs, xlo, xhi, ylo, yhi)} (pontif.plot): opens an orbitable 3D surface
     * from a row-major height grid {@code zs} (length {@code N*N}) over the rectangular domain.
     */
    public static Object renderSurface(List<Object> args, NativeCalls.Context ctx) {
        double[] zs = !args.isEmpty() ? doubles(args.get(0)) : new double[0];
        double xlo = arg(args, 1), xhi = arg(args, 2), ylo = arg(args, 3), yhi = arg(args, 4);
        return GuiTree.openWindowWithRoot("Surface", true, () -> SceneBuilder.buildSurfaceView(zs, xlo, xhi, ylo, yhi));
    }

    /**
     * {@code renderScene(cfg, {layers})} (pontif.plot): opens ONE orbitable window compositing all
     * the given layers. Each layer value was sampled in Pontif; this native turns each into a dasum
     * layer, frames the camera to their combined bounds, and publishes them as a single snapshot.
     */
    public static Object renderScene(List<Object> args, NativeCalls.Context ctx) {
        String title = cfgStr(args, 0, "title");
        if (title.isEmpty()) title = "Scene";
        Object layers = args.size() > 1 ? args.get(1) : emptyTuple();
        boolean axes = cfgBool(args, 0, "axes", true);   // graduations on by default
        boolean grid = cfgBool(args, 0, "grid", true);
        boolean equalAspect = "equal".equals(cfgStr(args, 0, "aspect"));  // default: box (cube) aspect
        SceneBuilder.SceneBuild build = SceneBuilder.buildSceneLayers(layers);
        return GuiTree.openWindowWithRoot(title, cfgInt(args, 0, "width", WIDTH), cfgInt(args, 0, "height", HEIGHT),
                true, () -> SceneBuilder.sceneComponent(build, axes, grid, equalAspect));
    }

    /**
     * {@code renderReliable(xlo, xhi, spans)} (pontif.plot, docs/reliable-plotting.md): opens a
     * window painting per-column interval enclosures. The reliable, asymptote-safe sibling of
     * {@link #renderCurve}.
     */
    public static Object renderReliable(List<Object> args, NativeCalls.Context ctx) {
        double xlo = arg(args, 0), xhi = arg(args, 1);
        Object spans = args.size() > 2 ? args.get(2) : emptyTuple();
        RecordValue titleAst = args.size() > 3 && args.get(3) instanceof RecordValue r ? r : null;
        List<Series> series = ReliableSeries.buildReliableSeries(xlo, xhi, spans);
        return GuiTree.openWindowWithRoot("Plot", false,
                () -> ChartBuilder.titledPlot(ChartBuilder.chartComponent(series), titleAst));
    }

    /**
     * {@code exportSvg({layers})} (pontif.plot): serialise the SAME {@code chart} layer tuple to a
     * semantically-classed SVG and pop a native Save dialog to write it. A cancelled dialog is a no-op.
     */
    public static Object exportSvg(List<Object> args, NativeCalls.Context ctx) {
        Object layers = args.isEmpty() ? emptyTuple() : args.get(0);
        PlotSvg.writeChartSvgDialog(ChartBuilder.buildAnnotatedChart(layers, ctx));
        return new IrInterpreter.DriveResult();
    }

    /**
     * {@code plotInput(expr)} (pontif.plot): opens a window with an editable expression field over a
     * live reliable plot + typeset title. Typing re-parses (debounced) and re-publishes in place.
     */
    public static Object plotInput(List<Object> args, NativeCalls.Context ctx) {
        String initial = !args.isEmpty() && args.get(0) instanceof StringValue s ? s.content() : "";
        return GuiTree.openWindowWithRoot("Plot", WIDTH, HEIGHT, false, () -> LiveEdit.plotInputRoot(initial, ctx));
    }

    /**
     * {@code markup(s)} (pontif.plot): parse an ASCII math-notation string and open a window
     * typesetting it (STIX Two Math), with an Export-SVG button. A malformed string shows an error label.
     */
    public static Object markup(List<Object> args, NativeCalls.Context ctx) {
        String s = !args.isEmpty() && args.get(0) instanceof StringValue v ? v.content() : "";
        return GuiTree.openWindowWithRoot("Math", WIDTH, HEIGHT, false, () -> LiveEdit.markupRenderRoot(s));
    }

    /** {@code exportMarkupSvg(s)} (pontif.plot): typeset a markup string and pop a Save dialog. */
    public static Object exportMarkupSvg(List<Object> args, NativeCalls.Context ctx) {
        String s = !args.isEmpty() && args.get(0) instanceof StringValue v ? v.content() : "";
        PlotSvg.writeMarkupSvgDialog(s);
        return new IrInterpreter.DriveResult();
    }

    /**
     * {@code markupInput(s)} (pontif.plot): an editable field over a live-typeset equation — type math
     * notation and it re-typesets as you type (debounced). The interactivity twin of {@link #plotInput}.
     */
    public static Object markupInput(List<Object> args, NativeCalls.Context ctx) {
        String initial = !args.isEmpty() && args.get(0) instanceof StringValue v ? v.content() : "";
        return GuiTree.openWindowWithRoot("Math", WIDTH, HEIGHT, false, () -> LiveEdit.markupInputRoot(initial));
    }

    /**
     * {@code exportMathSvg(e)} (pontif.plot): typeset an algebraic expression's AST to a semantic SVG
     * and pop a native Save dialog to write it — the math sibling of {@link #exportSvg}. A cancelled
     * dialog is a no-op.
     */
    public static Object exportMathSvg(List<Object> args, NativeCalls.Context ctx) {
        if (args.isEmpty() || !(args.get(0) instanceof RecordValue ast)) {
            System.err.println("exportMathSvg: expected an AlgExpr AST.");
            return new IrInterpreter.DriveResult();
        }
        String svg = MathText.mathSvg(ast);
        Optional<Path> dest = FileDialog.save(null,
                List.of(FileDialog.Filter.of("SVG image", "svg")), null, "math.svg");
        if (dest.isPresent()) {
            Path p = dest.get();
            if (!p.getFileName().toString().toLowerCase().endsWith(".svg")) {
                p = p.resolveSibling(p.getFileName() + ".svg");
            }
            try {
                Files.writeString(p, svg);
            } catch (IOException e) {
                System.err.println("exportMathSvg: could not write " + p + ": " + e.getMessage());
            }
        }
        return new IrInterpreter.DriveResult();
    }
}
