package sibarum.pontif.gui;

import sibarum.dasum.gui.core.GlfwContext;
import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.em.EmContext;
import sibarum.dasum.gui.core.event.EventLoop;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.HoverState;
import sibarum.dasum.gui.core.input.InputState;
import sibarum.dasum.gui.core.layout.HitTest;
import sibarum.dasum.gui.core.layout.LatestLayout;
import sibarum.dasum.gui.core.layout.Layout;
import sibarum.dasum.gui.core.layout.LayoutResult;
import sibarum.dasum.gui.core.layout.PixelRect;
import sibarum.dasum.gui.core.layout.Render;
import sibarum.dasum.gui.core.render.Batcher;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.render.Projection;
import sibarum.dasum.gui.core.render.Texture;
import sibarum.dasum.gui.core.text.AtlasData;
import sibarum.dasum.gui.core.text.FontGroup;
import sibarum.dasum.gui.core.text.FontGroups;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.dasum.gui.core.ui.Ui;
import sibarum.dasum.gui.core.window.Window;
import sibarum.dasum.gui.natives.gl.Gl;
import sibarum.dasum.gui.natives.glfw.Glfw;
import sibarum.dasum.gui.natives.glfw.GlfwCallbacks;
import sibarum.dasum.gui.vis.DasumVis;
import sibarum.dasum.gui.vis.render.BloomPass;
import sibarum.dasum.gui.vis.plot.Axis;
import sibarum.dasum.gui.vis.plot.LinePlot;
import sibarum.dasum.gui.vis.plot.Axis;
import sibarum.dasum.gui.vis.plot.PlotFrame;
import sibarum.dasum.gui.vis.plot.PlotScene2D;
import sibarum.dasum.gui.vis.plot.PlotScene2DRenderer;
import sibarum.dasum.gui.vis.plot.PlotStyle;
import sibarum.dasum.gui.vis.plot.PlotView;
import sibarum.dasum.gui.vis.plot.Series;
import sibarum.dasum.gui.vis.plot.SvgPlotWriter;
import sibarum.dasum.gui.mathtext.MathConstants;
import sibarum.dasum.gui.mathtext.MathBox;
import sibarum.dasum.gui.mathtext.MathLayout;
import sibarum.dasum.gui.mathtext.MathOgl;
import sibarum.dasum.gui.mathtext.MathSvg;
import sibarum.dasum.gui.mathtext.LaidOut;
import sibarum.dasum.gui.vis.plot.Ticks;
import sibarum.dasum.gui.vis.math.CameraRig;
import sibarum.dasum.gui.vis.math.CameraSpec;
import sibarum.dasum.gui.vis.math.Vec3;
import sibarum.dasum.gui.vis.pointcloud.SceneViewController;
import sibarum.dasum.gui.vis.scene.BlendMode;
import sibarum.dasum.gui.vis.scene.InteractionSpec;
import sibarum.dasum.gui.vis.scene.Layer;
import sibarum.dasum.gui.vis.scene.LineLayer;
import sibarum.dasum.gui.vis.scene.PointLayer;
import sibarum.dasum.gui.vis.scene.RaymarchLayer;
import sibarum.dasum.gui.vis.scene.SceneSnapshot;
import sibarum.dasum.gui.vis.scene.SceneStates;
import sibarum.dasum.gui.vis.scene.TextLayer;
import sibarum.dasum.gui.vis.scene.TriangleLayer;
import sibarum.dasum.gui.vis.scene.VolumeLayer;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;

import sibarum.dasum.gui.core.dialog.FileDialog;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Java side of the GUI extension (docs/extensions.md), G5: a <b>declarative UI</b>. The
 * builder natives ({@link #label}/{@link #button}/{@link #column}) construct element
 * {@link RecordValue}s; {@link #openWindow} receives the root tree and {@link #toComponent walks
 * it} into dasum components, rendering it on the root thread until the window closes. State is the
 * record tree itself (Pontif values), so nothing opaque crosses the boundary. A button placed in
 * the tree fires a {@code ButtonEvent} via {@link NativeCalls.Context} (the slice-1e action leg).
 * Single window, single thread; construction only (reactive update is the next slice).
 */
public final class DasumBridge {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;
    private static final Color TEXT = new Color(0.92f, 0.92f, 0.96f, 1f);
    private static final Color TRANSPARENT = new Color(0f, 0f, 0f, 0f);
    private static final Color BACKGROUND = new Color(0.05f, 0.07f, 0.12f, 1f);
    private static final Color PLOT_BG = new Color(0.04f, 0.05f, 0.08f, 1f);
    private static final Color SERIES_COLOR = new Color(0.40f, 0.80f, 1.0f, 1f);

    /** The component captured on mouse-down, to confirm the release lands on the same one. */
    private static Component pressTarget;

    private DasumBridge() {}

    // Elements (Label/Button/Column) are now constructed directly in Pontif — no builder natives.
    // The window action walks the resulting record tree (toComponent below).

    private static RecordValue element(String type, String field, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(field, new StringValue(value));
        return new RecordValue(type, m);
    }

    // --- The window: walk the tree, then render it -------------------------------------------

    /**
     * {@code window({title = …}, {root…})}: build the dasum tree from the root record(s), open the
     * window, and block in the loop (root thread) until closed. Returns the inert for-effect result.
     */
    public static Object openWindow(List<Object> args, NativeCalls.Context ctx) {
        String title = cfgStr(args, 0, "title");
        if (title.isEmpty()) title = "Pontif";
        Object rootTree = args.size() > 1 ? args.get(1) : emptyTuple();
        return openWindowWithRoot(title, cfgInt(args, 0, "width", WIDTH), cfgInt(args, 0, "height", HEIGHT),
                false, () -> toComponent(rootTree, ctx));
    }

    /**
     * {@code renderCurve(xs, ys)} (pontif.plot, docs/plotting.md): opens a window showing a line
     * chart of the two sample aggregates. The sampling itself happens in Pontif (pontif.plot's
     * {@code plotLine}); this native receives only the concrete numbers and renders them — the
     * same primitives-only boundary as the declarative GUI.
     */
    public static Object renderCurve(List<Object> args, NativeCalls.Context ctx) {
        double[] xs = !args.isEmpty() ? doubles(args.get(0)) : new double[0];
        double[] ys = args.size() > 1 ? doubles(args.get(1)) : new double[0];
        return openWindowWithRoot("Plot", false, () -> buildLinePlotView(xs, ys));
    }

    /**
     * {@code renderChart(cfg, {layers})} (pontif.plot): opens ONE line-chart window overlaying all
     * the given {@code Curve} layers, each in its own palette colour, with auto axes / gridlines /
     * tick labels (dasum-vis {@code LinePlot}/{@code PlotFrame}). Multi-series composition; the
     * 2D sibling of {@link #renderScene}.
     */
    public static Object renderChart(List<Object> args, NativeCalls.Context ctx) {
        String title = cfgStr(args, 0, "title");
        if (title.isEmpty()) title = "Chart";
        Object layers = args.size() > 1 ? args.get(1) : emptyTuple();
        AnnotatedChart chart = buildAnnotatedChart(layers);
        return openWindowWithRoot(title, cfgInt(args, 0, "width", WIDTH), cfgInt(args, 0, "height", HEIGHT),
                false, () -> annotatedChartComponent(chart));
    }

    /**
     * {@code renderCloud(points)} (pontif.plot): opens an orbitable 3D window showing a point
     * cloud. {@code points} is an aggregate of {@code {x,y,z}} triples shaped in Pontif
     * (pontif.plot's {@code plotCloud}); this native flattens it to {@code float[]} and renders.
     */
    public static Object renderCloud(List<Object> args, NativeCalls.Context ctx) {
        float[] xyz = !args.isEmpty() ? xyzTriples(args.get(0)) : new float[0];
        return openWindowWithRoot("Cloud", true, () -> buildCloudView(xyz));
    }

    /**
     * {@code renderSurface(zs, xlo, xhi, ylo, yhi)} (pontif.plot): opens an orbitable 3D surface
     * from a row-major height grid {@code zs} (length {@code N*N}) over the rectangular domain. The
     * grid sampling happens in Pontif (pontif.plot's {@code plotSurface}); this native rebuilds the
     * {@code (x,y)} coordinates from the domain and meshes the surface into triangles.
     */
    public static Object renderSurface(List<Object> args, NativeCalls.Context ctx) {
        double[] zs = !args.isEmpty() ? doubles(args.get(0)) : new double[0];
        double xlo = arg(args, 1), xhi = arg(args, 2), ylo = arg(args, 3), yhi = arg(args, 4);
        return openWindowWithRoot("Surface", true, () -> buildSurfaceView(zs, xlo, xhi, ylo, yhi));
    }

    /**
     * {@code renderScene(cfg, {layers})} (pontif.plot): opens ONE orbitable window compositing all
     * the given layers. Each layer value ({@code Surface}/{@code Cloud}/{@code Text3D}) was sampled
     * in Pontif; this native turns each into a dasum {@link Layer}, frames the camera to their
     * combined bounds, and publishes them as a single {@link SceneSnapshot} — so solid surfaces
     * occlude by depth and a faded surface shows what's behind it (docs/plotting.md).
     */
    public static Object renderScene(List<Object> args, NativeCalls.Context ctx) {
        String title = cfgStr(args, 0, "title");
        if (title.isEmpty()) title = "Scene";
        Object layers = args.size() > 1 ? args.get(1) : emptyTuple();
        boolean axes = cfgBool(args, 0, "axes", true);   // graduations on by default
        boolean grid = cfgBool(args, 0, "grid", true);
        boolean equalAspect = "equal".equals(cfgStr(args, 0, "aspect"));  // default: box (cube) aspect
        SceneBuild build = buildSceneLayers(layers);
        return openWindowWithRoot(title, cfgInt(args, 0, "width", WIDTH), cfgInt(args, 0, "height", HEIGHT),
                true, () -> sceneComponent(build, axes, grid, equalAspect));
    }

    private static double arg(List<Object> args, int i) {
        return i < args.size() ? toDouble(args.get(i)) : 0.0;
    }

    /**
     * {@code renderReliable(xlo, xhi, spans)} (pontif.plot, docs/reliable-plotting.md): opens a
     * window painting per-column interval enclosures as vertical bars. Each {@code Span} is a curve
     * segment, a full-height pole block, or an empty break — line / break / block in one form. The
     * spans are computed Pontif-side ({@code evalInterval} per column); this native frames the
     * y-range and rasterises. The reliable, asymptote-safe sibling of {@link #renderCurve}.
     */
    public static Object renderReliable(List<Object> args, NativeCalls.Context ctx) {
        double xlo = arg(args, 0), xhi = arg(args, 1);
        Object spans = args.size() > 2 ? args.get(2) : emptyTuple();
        RecordValue titleAst = args.size() > 3 && args.get(3) instanceof RecordValue r ? r : null;
        List<Series> series = buildReliableSeries(xlo, xhi, spans);
        return openWindowWithRoot("Plot", false, () -> titledPlot(chartComponent(series), titleAst));
    }

    /**
     * {@code exportSvg({layers})} (pontif.plot): serialise the SAME {@code chart} layer tuple to a
     * semantically-classed SVG (dasum {@link SvgPlotWriter}, via the shared {@link PlotScene2D} IR)
     * and pop a native Save dialog ({@link FileDialog}) to write it. Wire it to a button's
     * {@code onClick} next to the plot — the click runs on the GLFW thread (where NFD must be
     * invoked), so no Pontif filesystem API is needed. A cancelled dialog is a no-op.
     */
    public static Object exportSvg(List<Object> args, NativeCalls.Context ctx) {
        Object layers = args.isEmpty() ? emptyTuple() : args.get(0);
        AnnotatedChart chart = buildAnnotatedChart(layers);
        PlotFrame frame = annotatedFrame(chart);
        if (frame == null) {
            System.err.println("exportSvg: no drawable layers — nothing to export.");
            return new IrInterpreter.DriveResult();
        }
        String svg = SvgPlotWriter.write(buildScene(chart, frame), WIDTH, HEIGHT);
        Optional<Path> dest = FileDialog.save(null,
                List.of(FileDialog.Filter.of("SVG image", "svg")), null, "plot.svg");
        if (dest.isPresent()) {
            Path p = dest.get();
            if (!p.getFileName().toString().toLowerCase().endsWith(".svg")) {
                p = p.resolveSibling(p.getFileName() + ".svg");   // ensure the .svg extension
            }
            try {
                Files.writeString(p, svg);
            } catch (IOException e) {
                System.err.println("exportSvg: could not write " + p + ": " + e.getMessage());
            }
        }
        return new IrInterpreter.DriveResult();
    }

    /** A classified column: kind 0 = curve span {@code [lo,hi]}, 1 = isolated pole (a single
     *  asymptote — a break the curve blows through), 2 = empty (a domain-edge break), 3 = dense pole
     *  (unresolvable detail — a full-height fill block). The isolated-vs-dense split is decided
     *  Pontif-side by a subdivision probe (pontif.plot's {@code classifyColumn}), not a run length. */
    record ReliableSpan(int kind, double lo, double hi) {}

    /** Parse the Pontif {@code {spans}} tuple of {@code Span(kind,lo,hi)} records, in column order.
     *  Package-visible: the headless test seam. */
    static List<ReliableSpan> parseSpans(Object spansValue) {
        List<ReliableSpan> out = new ArrayList<>();
        if (spansValue instanceof RecordValue tuple) {
            for (Object m : tuple.members().values()) {
                if (m instanceof RecordValue s && "Span".equals(bareType(s.typeName()))) {
                    out.add(new ReliableSpan((int) Math.round(memberD(s, "kind")),
                            memberD(s, "lo"), memberD(s, "hi")));
                }
            }
        }
        return out;
    }

    /**
     * Turn per-column interval spans into readable line series over {@code [xlo, xhi]}. Each maximal
     * run of consecutive CURVE columns becomes ONE connected polyline through the column midpoints
     * (clamped to the viewport) — so the plot reads as a curve, not a field of vertical dashes.
     * A break (an empty column, or an isolated pole — kind 1) ends the current polyline; the curve
     * resumes as a fresh one on the far side — so an asymptote is a clean gap, never a line drawn
     * across it. A DENSE pole (kind 3 — unresolvable detail) still fills as a full-height bar. The
     * y-range is a ROBUST 2nd–98th percentile of the bounded columns so a near-pole spike can't
     * flatten the plot. Package-visible test seam.
     */
    static List<Series> buildReliableSeries(double xlo, double xhi, Object spansValue) {
        List<ReliableSpan> spans = parseSpans(spansValue);
        int n = spans.size();
        if (n == 0) return List.of();
        List<Double> bounds = new ArrayList<>();
        for (ReliableSpan s : spans) if (s.kind() == 0) { bounds.add(s.lo()); bounds.add(s.hi()); }
        double[] yr = robustRange(bounds);
        double ymin = yr[0], ymax = yr[1];
        double dx = (xhi - xlo) / n;
        double beyond = ymax - ymin;                 // how far past an edge to aim an ∞-bound point
        List<Series> out = new ArrayList<>();
        List<Double> runX = new ArrayList<>(), runY = new ArrayList<>();   // UN-clamped polyline points
        ReliableSpan lastCurve = null;               // last curve column added to the current run
        double poleBeforeX = Double.NaN;             // x of a pole immediately preceding this run, if any
        for (int i = 0; i < n; i++) {
            ReliableSpan s = spans.get(i);
            double x = xlo + (i + 0.5) * dx;
            if (s.kind() == 0) {                                 // curve → a point on the polyline
                if (!Double.isNaN(poleBeforeX) && runX.isEmpty()) {
                    // this run starts right after a pole → the curve comes FROM ±∞: begin off the
                    // edge so the clip draws it entering at the frame boundary.
                    runX.add(poleBeforeX);
                    runY.add(blowSign(s) >= 0 ? ymax + beyond : ymin - beyond);
                }
                runX.add(x);
                runY.add((s.lo() + s.hi()) / 2.0);               // true midpoint; clipped, not clamped
                lastCurve = s;
                poleBeforeX = Double.NaN;
                continue;
            }
            // A pole (kind 1 isolated OR kind 3 dense) is a proven blow-up: the line keeps going, so
            // aim it off the edge and let the clip draw it TO the boundary. An empty (Undefined, kind
            // 2) column is a domain edge — the curve genuinely stops, so it is left as a plain break.
            boolean pole = s.kind() == 1 || s.kind() == 3;
            if (pole && lastCurve != null && !runX.isEmpty()) {
                runX.add(x);
                runY.add(blowSign(lastCurve) >= 0 ? ymax + beyond : ymin - beyond);
            }
            clipRunToBand(runX, runY, ymin, ymax, out);
            runX.clear();
            runY.clear();
            lastCurve = null;
            poleBeforeX = pole ? x : Double.NaN;                 // only a pole makes the next run enter from ∞
            if (s.kind() == 3) {                                 // dense pole → fill (the block)
                out.add(Series.line(new double[]{x, x}, new double[]{ymin, ymax}, SERIES_COLOR));
            }
        }
        clipRunToBand(runX, runY, ymin, ymax, out);
        return out;
    }

    /** Which way a curve column adjacent to a pole is blowing up: the sign of its larger-magnitude
     *  enclosure endpoint (positive → toward the top edge, negative → toward the bottom). */
    private static double blowSign(ReliableSpan s) {
        return (Math.abs(s.hi()) >= Math.abs(s.lo()) ? s.hi() : s.lo()) >= 0 ? 1.0 : -1.0;
    }

    /**
     * Clip a polyline to the horizontal band {@code [ymin, ymax]} and emit the in-band pieces as
     * series. Where a segment crosses an edge the crossing point is interpolated (correct slope),
     * so a curve heading off-screen reaches the frame edge and stops — no pile-up along the edge
     * (the "serif" the clamp-to-viewport version produced). Off-screen stretches draw nothing.
     */
    private static void clipRunToBand(List<Double> xs, List<Double> ys,
                                      double ymin, double ymax, List<Series> out) {
        List<Double> cx = new ArrayList<>(), cy = new ArrayList<>();
        for (int i = 0; i < xs.size(); i++) {
            double x = xs.get(i), y = ys.get(i);
            boolean in = y >= ymin && y <= ymax;
            if (i > 0) {
                double px = xs.get(i - 1), py = ys.get(i - 1);
                boolean pin = py >= ymin && py <= ymax;
                if (pin && !in) {                                // exiting the band → cut at the edge
                    double edge = y > ymax ? ymax : ymin;
                    cx.add(px + (edge - py) * (x - px) / (y - py));
                    cy.add(edge);
                    flushPts(cx, cy, out);
                } else if (!pin && in) {                         // entering → start at the edge
                    double edge = py > ymax ? ymax : ymin;
                    cx.add(px + (edge - py) * (x - px) / (y - py));
                    cy.add(edge);
                } else if (!pin && ((py < ymin && y > ymax) || (py > ymax && y < ymin))) {
                    double e1 = py < ymin ? ymin : ymax;         // crosses the whole band (very steep)
                    double e2 = py < ymin ? ymax : ymin;
                    cx.add(px + (e1 - py) * (x - px) / (y - py)); cy.add(e1);
                    cx.add(px + (e2 - py) * (x - px) / (y - py)); cy.add(e2);
                    flushPts(cx, cy, out);
                }
            }
            if (in) { cx.add(x); cy.add(y); }
        }
        flushPts(cx, cy, out);
    }

    /** Emit the accumulated points as one polyline (if it has ≥ 2 of them) and reset the buffer. */
    private static void flushPts(List<Double> xs, List<Double> ys, List<Series> out) {
        if (xs.size() >= 2) {
            double[] ax = new double[xs.size()], ay = new double[ys.size()];
            for (int i = 0; i < ax.length; i++) { ax[i] = xs.get(i); ay[i] = ys.get(i); }
            out.add(Series.line(ax, ay, SERIES_COLOR));
        }
        xs.clear();
        ys.clear();
    }

    /** {@code [ymin, ymax]} from the 2nd/98th percentile of {@code vals}, padded 5% — robust to
     *  near-pole spikes. An empty set (no bounded columns) yields a default unit window. */
    private static double[] robustRange(List<Double> vals) {
        if (vals.isEmpty()) return new double[]{-1, 1};
        List<Double> s = new ArrayList<>(vals);
        Collections.sort(s);
        double lo = percentile(s, 0.02), hi = percentile(s, 0.98);
        if (hi <= lo) { double m = (lo + hi) / 2; lo = m - 1; hi = m + 1; }
        double pad = (hi - lo) * 0.05;
        return new double[]{lo - pad, hi + pad};
    }

    private static double percentile(List<Double> sorted, double p) {
        int i = (int) Math.round(p * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /**
     * Opens a window on the calling (root) thread, builds its root via {@code rootFactory}
     * <b>after</b> GL + font setup (so styled / plotted components resolve correctly), then renders
     * in the loop until the window closes. Shared by the declarative-UI window ({@link #openWindow})
     * and the plot window ({@link #renderCurve}). Returns the inert for-effect result.
     */
    private static Object openWindowWithRoot(String title, boolean enableBloom,
            java.util.function.Supplier<Component> rootFactory) {
        return openWindowWithRoot(title, WIDTH, HEIGHT, enableBloom, rootFactory);
    }

    /** As {@link #openWindowWithRoot(String, boolean, java.util.function.Supplier)}, at an explicit
     *  window size — the {@code width}/{@code height} cfg keys ({@code chart({width=…, height=…}, …)})
     *  flow here; the cfg-less render natives use the {@link #WIDTH}×{@link #HEIGHT} default overload. */
    private static Object openWindowWithRoot(String title, int width, int height, boolean enableBloom,
            java.util.function.Supplier<Component> rootFactory) {
        try (GlfwContext glfw = GlfwContext.init();
             Window win = Window.create(width, height, title);
             Batcher batcher = new Batcher()) {
            // Gl.load() before any GL call (texture upload); see the texture-ordering bugfix.
            Gl.load();
            batcher.init();
            // Register the dasum-vis renderer for Component.SceneView (plots/scenes). Idempotent;
            // needs Gl.load() first. After this, Render.render dispatches SceneViews automatically.
            DasumVis.init();
            EmContext.setDpiScale(win.contentScaleX());

            Texture fontTexture = Texture.fromPngResource("/dasum/atlas/primary.png");
            AtlasData atlas = AtlasData.loadFromResource("/dasum/atlas/primary.json");
            FontGroups.register(FontGroup.of(FontGroups.DEFAULT, atlas, fontTexture));

            // The "math" font group: STIX Two Math (OFL) — italic math alphanumerics, Greek,
            // blackboard, operators, radical, delimiters. The math typesetter selects it via
            // TextLayer.withFontGroup("math") and picks glyphs by their Unicode math codepoints.
            Texture mathTexture = Texture.fromPngResource("/dasum/atlas/math.png");
            AtlasData mathAtlas = AtlasData.loadFromResource("/dasum/atlas/math.json");
            FontGroups.register(FontGroup.of("math", mathAtlas, mathTexture));

            // Build components after font + Em setup, so styled widgets resolve correctly.
            Component root = rootFactory.get();
            // Layout guardrail (docs/plotting.md): lint the built tree BEFORE rendering. Fonts are
            // registered and Em is set, so the geometry pass can lay it out — a collapsed plot/scene
            // (the plot-in-a-column trap) throws here with a fix hint instead of rendering blank.
            Ui.lint(root);
            wireInput();

            // 3D plot windows opt into HDR + bloom: the frame renders into an offscreen HDR target,
            // then bright-pass/blur/tonemap composites to the screen (the emissive glow blooms).
            BloomPass bloom = enableBloom ? new BloomPass() : null;
            if (bloom != null) bloom.init();

            EventLoop loop = new EventLoop(win, () -> {
                int fbW = win.framebufferWidth();
                int fbH = win.framebufferHeight();
                float[] projection = Projection.orthoTopLeft(fbW, fbH);
                if (bloom != null) bloom.begin(fbW, fbH);   // bind HDR target; frame renders into it
                Gl.glViewport(0, 0, fbW, fbH);
                Gl.glClearColor(BACKGROUND.r(), BACKGROUND.g(), BACKGROUND.b(), BACKGROUND.a());
                Gl.glClear(Gl.GL_COLOR_BUFFER_BIT);
                LayoutResult layout = Layout.compute(root, new PixelRect(0f, 0f, fbW, fbH));
                LatestLayout.store(root, layout);  // required so hit-testing has coordinates
                batcher.beginFrame(fbH);
                Render.render(root, layout, batcher, projection);
                batcher.endFrame(projection);
                if (bloom != null) bloom.end(fbW, fbH);     // bloom + composite to the screen
            });
            loop.run();  // blocks on this (root) thread until the window is closed
            if (bloom != null) bloom.close();               // free FBOs while the GL context is alive
        }
        return new IrInterpreter.DriveResult();
    }

    /**
     * Recursively turns an element record (or a {@code _tuple} of them) into a dasum component.
     * The heart of the declarative UI: it switches on the record's (bare) type name. An unknown
     * node renders as a visible error label rather than failing silently.
     */
    private static Component toComponent(Object value, NativeCalls.Context ctx) {
        if (!(value instanceof RecordValue rv)) {
            return errorLabel("not a component: " + value);
        }
        // A user widget: any value whose type satisfies Clickable renders as a button whose click
        // invokes its own onClick method (which emits). This is how per-element behavior lives —
        // the user subtypes Button and assigns Clickable with an onClick (docs/extensions.md G6).
        // The trait registry keys on the fully-qualified trait name (pontif.gui/Clickable).
        if (ctx.satisfies(rv, "pontif.gui/Clickable")) {
            return Themed.button(str(rv, "text"), Em.of(10f), Variant.PRIMARY, 0,
                    () -> ctx.invoke(rv, "onClick"));
        }
        return switch (bareType(rv.typeName())) {
            case "Label" -> new Component.Text(str(rv, "text"), Em.of(2f), TEXT);
            case "Button" -> {
                String t = str(rv, "text");
                yield Themed.button(t, Em.of(10f), Variant.PRIMARY, 0,
                        () -> ctx.fireEvent(element("pontif.gui/ButtonEvent", "label", t)));
            }
            // Ui.column() defaults (align=STRETCH) make children fill the cross axis, so a nested
            // plot/scene resolves instead of collapsing; the user's justify/align still apply. grow(1)
            // lets a column-in-a-column take vertical space.
            case "Column" -> Ui.column()
                    .padding(Em.of(0.5f)).gap(Em.of(0.8f)).grow(1)
                    .justify(justify(str(rv, "justify"))).align(align(str(rv, "align")))
                    .addAll(childrenOf(rv, ctx)).build();
            case "LinePlot" -> buildLinePlot(rv);
            // An embeddable annotated chart (pontif.plot chartView): the same reliable/annotated
            // chart `chart(...)` opens standalone, but as a component so it can sit in a layout
            // beside a user Button whose onClick calls exportSvg on the same layers.
            case "ChartView" -> annotatedChartComponent(buildAnnotatedChart(rv.members().get("layers")));
            // A bare children aggregate (window's root arg) → the implicit root column: FILL the
            // window (both axes) so fill children (a plot) resolve, STRETCH so they span the width.
            case "_tuple" -> Ui.column().fill().padding(Em.of(0.5f)).gap(Em.of(0.8f))
                    .addAll(tupleToComponents(rv, ctx)).build();
            default -> errorLabel("unknown component: " + bareType(rv.typeName()));
        };
    }

    /**
     * Wires cursor hover + per-component click dispatch (the minimal dasum input pipeline), plus
     * the dasum-vis {@link SceneViewController} so a plot/scene viewport pans on drag and zooms on
     * scroll. The scene controller is consulted first on press: if it claims the gesture (a drag on
     * a viewport), no button activation is armed.
     */
    private static void wireInput() {
        GlfwCallbacks.setCursorPosListener((w, x, y) -> {
            InputState.updateMousePos(x, y);
            LayoutResult lr = LatestLayout.result();
            Component r = LatestLayout.root();
            if (lr != null && r != null) {
                HoverState.update(HitTest.test(r, lr, (float) x, (float) y));
            }
            SceneViewController.onCursorMove(x, y);  // orbit/pan while dragging a viewport
        });
        GlfwCallbacks.setMouseButtonListener((w, button, action, mods) -> {
            if (button != Glfw.GLFW_MOUSE_BUTTON_LEFT) return;
            if (action == Glfw.GLFW_PRESS) {
                // Let a viewport claim the press first; if it does, don't also arm a button click.
                boolean scene = SceneViewController.onMouseDown(
                        HoverState.hovered(), InputState.mouseX(), InputState.mouseY());
                pressTarget = scene ? null : HoverState.hovered();
            } else if (action == Glfw.GLFW_RELEASE) {
                SceneViewController.onMouseUp();
                LayoutResult lr = LatestLayout.result();
                Component r = LatestLayout.root();
                Component released = (lr != null && r != null)
                        ? HitTest.test(r, lr, (float) InputState.mouseX(), (float) InputState.mouseY())
                        : null;
                if (pressTarget != null && released == pressTarget) {
                    Handlers.activate(pressTarget, r);  // fires the button's Runnable
                }
                pressTarget = null;
            }
        });
        // Scroll zooms the viewport under the cursor; onScroll self-guards to interactive SceneViews.
        GlfwCallbacks.setScrollListener((w, xo, yo) -> SceneViewController.onScroll(HoverState.hovered(), yo));
    }

    // --- helpers ------------------------------------------------------------------------------

    private static List<Component> childrenOf(RecordValue column, NativeCalls.Context ctx) {
        return column.members().get("children") instanceof RecordValue kids
                ? tupleToComponents(kids, ctx) : new ArrayList<>();
    }

    private static List<Component> tupleToComponents(RecordValue tuple, NativeCalls.Context ctx) {
        List<Component> out = new ArrayList<>();
        for (Object child : tuple.members().values()) out.add(toComponent(child, ctx));
        return out;
    }

    private static Component errorLabel(String message) {
        return new Component.Text(message, Em.of(1f), new Color(0.95f, 0.4f, 0.4f, 1f));
    }

    /**
     * Builds a 2D line chart (dasum-vis) from a {@code LinePlot(xs, ys)} element: converts the two
     * numeric aggregates to {@code double[]}, makes a {@link Component.SceneView}, and publishes a
     * single {@link Series} through a {@link PlotView}. Axes auto-range to the data; drag pans and
     * scroll zooms (wired in {@link #wireInput}). {@code DasumVis.init()} must have run first
     * (it has — {@link #openWindow} calls it before walking the tree).
     */
    private static Component buildLinePlot(RecordValue rv) {
        return buildLinePlotView(doubles(rv.members().get("xs")), doubles(rv.members().get("ys")));
    }

    /**
     * The shared line-chart component: a {@link Component.SceneView} carrying a single
     * {@link Series} published through a {@link PlotView}, axes auto-ranged to the data, pan/zoom
     * enabled (wired in {@link #wireInput}). Used both by the {@code LinePlot} element
     * ({@link #buildLinePlot}) and the {@code plotLine} sampler ({@link #renderCurve}).
     * {@code DasumVis.init()} must have run first ({@link #openWindowWithRoot} ensures it).
     */
    static Component buildLinePlotView(double[] xs, double[] ys) {
        return chartComponent(List.of(Series.line(xs, ys, SERIES_COLOR)));
    }

    /** Distinct series colours, cycled by curve index in a composed chart. */
    private static final Color[] SERIES_PALETTE = {
            new Color(0.40f, 0.80f, 1.00f, 1f),   // cyan
            new Color(1.00f, 0.55f, 0.35f, 1f),   // orange
            new Color(0.55f, 0.95f, 0.55f, 1f),   // green
            new Color(0.95f, 0.55f, 0.85f, 1f),   // magenta
            new Color(0.95f, 0.85f, 0.40f, 1f),   // yellow
    };

    /** Turn a {@code {layers}} tuple of {@code Curve} records into line series. A curve carrying an
     *  explicit colour ({@code colored = true}) uses its own {@code {r,g,b}}; the rest auto-colour
     *  from {@link #SERIES_PALETTE}, cycled by the order of the <em>un-coloured</em> curves (so an
     *  explicit colour doesn't shift the palette slot of a later auto curve).
     *  Package-visible: the headless test seam for {@link #renderChart}. */
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

    /** A colour channel clamped to the renderable [0,1] range (a {@link Color} out of range throws). */
    private static float clamp01(double v) {
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * The shared 2D line-chart component: a {@link Component.SceneView} carrying the given
     * {@link Series} published through a {@link PlotView}, axes auto-ranged over ALL series, with
     * gridlines + tick labels (from {@link PlotStyle#defaults()}), pan/zoom enabled. Used by the
     * {@code LinePlot} element, the {@code plotLine} sampler, and the composed {@code chart}.
     * {@code DasumVis.init()} must have run first ({@link #openWindowWithRoot} ensures it).
     */
    /** A plot/scene viewport built through the {@code Ui} builder — fill + grow + interactive by
     *  default (a scene has no intrinsic size, so filling its slot is the correct default and keeps a
     *  plot from collapsing when it isn't the whole window). The plot background is applied here. */
    static Component.SceneView plotSceneView() {
        return (Component.SceneView) Ui.sceneView().background(PLOT_BG).build();
    }

    static Component chartComponent(List<Series> series) {
        Component.SceneView view = plotSceneView();
        PlotFrame frame = LinePlot.autoFrame(0f, 0f, 10f, 5.5f, series);
        new PlotView(view).showLinePlot(frame, series, PlotStyle.defaults());
        // Pan/zoom fenced to the plot's world rect so it can't be dragged off screen.
        SceneStates.setInteraction(view, InteractionSpec.panZoom2d()
                .withPanBounds(frame.wx0(), frame.wy0(), frame.wx1(), frame.wy1()));
        return view;
    }

    /**
     * A fixed-height viewport that typesets an {@code AlgExpr} AST as a math title (STIX Two Math) —
     * placed above a reliable plot. The math is rendered y-up and framed to its own box by an ortho
     * camera (via {@link PlotView#show}). Returns {@code null} when there is no AST or it isn't a
     * recognised algebraic node (so a plot without an expression stays untitled).
     */
    static Component mathTitleComponent(RecordValue ast) {
        if (ast == null || !isAlgExprNode(ast)) return null;
        MathConstants mc = MathConstants.stixTwoMath();
        LaidOut laid = new MathLayout(mathAtlas(), mc).layout(algExprToMathBox(ast));
        float w = (float) Math.max(1e-3, laid.width());
        float h = (float) Math.max(1e-3, laid.ascent() + laid.descent());
        List<Layer> layers = MathOgl.toLayers(laid, mc, TEXT, 1f, 0f, 0f, /*yUp*/ true);
        Component.SceneView view = (Component.SceneView) Ui.sceneView()
                .background(PLOT_BG).height(Em.of(3f)).grow(0).interactive(false).build();
        PlotFrame frame = new PlotFrame(0f, 0f, w, h, Axis.linear(0, w), Axis.linear(0, h));
        new PlotView(view).show(frame, layers);
        return view;
    }

    /** Stack a typeset math title above a plot (title fixed-height, plot grows to fill). With no
     *  title AST the plot is returned unwrapped. */
    static Component titledPlot(Component plot, RecordValue titleAst) {
        Component title = mathTitleComponent(titleAst);
        if (title == null) return plot;
        return Ui.column().fill().gap(Em.of(0.3f)).padding(Em.of(0.3f)).add(title).add(plot).build();
    }

    /** Whether a record is a recognised {@code AlgExpr} node (so we don't try to typeset e.g. a
     *  {@code Nothing} sentinel as a title). */
    private static boolean isAlgExprNode(RecordValue r) {
        return switch (bareType(r.typeName())) {
            case "Add", "Sub", "Mul", "Div", "Pow", "Sin", "Cos", "Tan", "Exp", "Log", "Const", "Param" -> true;
            default -> false;
        };
    }

    // --- Supplemental expression layers: reliable curve + annotations -----------------------------
    // (docs/reliable-plotting.md) `chart(cfg, {expr(e), zeros(e), optima(e), asymptotes(e),
    // intersections(e,g)})` composites an interval-reliable curve with feature MARKERS, LABELS, and
    // half-opacity vertical ASYMPTOTE lines — all expression-driven, all in one window. The feature
    // detection ran Pontif-side (bounded numeric scans over evalInterval); this native only turns the
    // resulting primitives into dasum layers over the shared plot frame.

    /** Max markers / vertical lines one annotation layer may paint. A layer that overflows this is
     *  SUPPRESSED with a log — the "unreasonable quantity of primitives" failsafe: a wildly
     *  oscillating curve (e.g. sin(1/x) near 0) has unboundedly many zeros/extrema, and hundreds of
     *  markers would bury the plot rather than inform it. */
    static final int FEATURE_CAP = 24;

    /** A detected feature anchor (data coordinates). */
    record Feature(double x, double y) {}

    /** A set of markers sharing a label style: kind 0 = zero (x-axis, x label), 1 = optimum
     *  ("(x, y)" label), 2 = intersection ("(x, y)" label). */
    record MarkSet(int kind, List<Feature> pts) {}

    /** The parsed, failsafe-applied decomposition of a {@code chart} layer list: drawn series (from
     *  sampled {@code Curve} + {@code ExprLayer}), marker sets, vertical-asymptote x's, and the
     *  reliable interval-enclosure band (from an {@code ExprLayer}, else {@code null}). This is the
     *  Pontif-side gather; {@link #buildScene} lifts it into the dasum {@link PlotScene2D} IR that
     *  drives BOTH the on-screen renderer and the SVG exporter. Package-visible test seam. */
    record AnnotatedChart(List<Series> series, List<MarkSet> marks, List<Double> vlines,
                          PlotScene2D.EnclosureBand enclosure, RecordValue titleAst) {}

    /**
     * Decompose a {@code chart} layer tuple into series + annotations, applying the per-layer
     * primitive {@link #FEATURE_CAP} failsafe (an overflowing annotation layer is dropped and logged
     * to {@code System.err}). Handles {@code Curve} (sampled), {@code ExprLayer} (interval spans →
     * reliable series + enclosure band), {@code MarkLayer} (zeros/optima/intersections), and
     * {@code VLineLayer} (asymptotes).
     */
    static AnnotatedChart buildAnnotatedChart(Object layersValue) {
        List<Series> series = new ArrayList<>();
        List<MarkSet> marks = new ArrayList<>();
        List<Double> vlines = new ArrayList<>();
        PlotScene2D.EnclosureBand enclosure = null;
        RecordValue titleAst = null;
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
                        double xlo = memberD(rv, "xlo"), xhi = memberD(rv, "xhi");
                        Object spans = rv.members().get("spans");
                        series.addAll(buildReliableSeries(xlo, xhi, spans));
                        if (enclosure == null) enclosure = enclosureBand(xlo, xhi, spans);
                        if (titleAst == null && rv.members().get("ast") instanceof RecordValue a) titleAst = a;
                    }
                    case "MarkLayer" -> {
                        int kind = (int) Math.round(memberD(rv, "kind"));
                        List<Feature> pts = parseMarks(rv.members().get("pts"));
                        if (capOk(pts.size(), markKindName(kind))) marks.add(new MarkSet(kind, pts));
                    }
                    case "VLineLayer" -> {
                        List<Double> xs = parseVLines(rv.members().get("xs"));
                        if (capOk(xs.size(), "asymptotes")) vlines.addAll(xs);
                    }
                    default -> { /* unknown layer kind — ignored */ }
                }
            }
        }
        return new AnnotatedChart(series, marks, vlines, enclosure, titleAst);
    }

    /** Build the reliable enclosure band from an {@code ExprLayer}'s spans: each bounded (curve)
     *  column contributes its guaranteed {@code [lo, hi]} at the column midpoint. Pole/empty columns
     *  are gaps (skipped). Returns {@code null} when there are &lt; 2 bounded columns. */
    static PlotScene2D.EnclosureBand enclosureBand(double xlo, double xhi, Object spansValue) {
        List<ReliableSpan> spans = parseSpans(spansValue);
        int n = spans.size();
        if (n == 0) return null;
        double dx = (xhi - xlo) / n;
        List<Double> xs = new ArrayList<>(), lo = new ArrayList<>(), hi = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ReliableSpan s = spans.get(i);
            if (s.kind() != 0) continue;                       // only bounded columns bound the curve
            xs.add(xlo + (i + 0.5) * dx);
            lo.add(s.lo());
            hi.add(s.hi());
        }
        if (xs.size() < 2) return null;
        return new PlotScene2D.EnclosureBand(toArray(xs), toArray(lo), toArray(hi));
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
     * The 2D chart with annotations: axes + drawn series (via {@code LinePlot.build}) plus overlay
     * layers — a {@code +} glyph and a text label per marker, and a half-opacity vertical line with an
     * x-value label per asymptote — all placed through the shared {@link PlotFrame}. The frame ranges
     * over the drawn series AND the marker points, so an off-curve marker is never framed out.
     */
    static Component annotatedChartComponent(AnnotatedChart chart) {
        PlotFrame frame = annotatedFrame(chart);
        if (frame == null) return errorLabel("chart: no drawable layers");
        Component.SceneView view = plotSceneView();
        new PlotView(view).show(frame, buildAnnotatedLayers(chart, frame));
        SceneStates.setInteraction(view, InteractionSpec.panZoom2d()
                .withPanBounds(frame.wx0(), frame.wy0(), frame.wx1(), frame.wy1()));
        // Typeset the expression as a math title above the plot (when a `expr(e)` layer carried its AST).
        return titledPlot(view, chart.titleAst());
    }

    /**
     * The shared {@link PlotFrame} for an annotated chart: framed over the drawn series AND every
     * marker point, so an off-curve marker (or an asymptote) is never framed out. Returns
     * {@code null} when there is nothing to draw. A {@link Series} needs &ge; 2 points, so a lone
     * marker (e.g. a single local optimum) is duplicated into a degenerate framing series — enough
     * to stretch the frame, invisible ({@code TRANSPARENT}) and never actually drawn. Package-visible
     * test seam (pure — no window).
     */
    static PlotFrame annotatedFrame(AnnotatedChart chart) {
        List<Series> framing = new ArrayList<>(chart.series());
        List<Double> mx = new ArrayList<>(), my = new ArrayList<>();
        for (MarkSet ms : chart.marks()) for (Feature f : ms.pts()) { mx.add(f.x()); my.add(f.y()); }
        if (mx.size() == 1) { mx.add(mx.get(0)); my.add(my.get(0)); }
        if (!mx.isEmpty()) framing.add(Series.line(toArray(mx), toArray(my), TRANSPARENT));
        if (framing.isEmpty()) return null;
        return LinePlot.autoFrame(0f, 0f, 10f, 5.5f, framing);
    }

    /**
     * Lift the Pontif-side {@link AnnotatedChart} into the dasum {@link PlotScene2D} IR — the single
     * semantic description that feeds BOTH the on-screen renderer ({@link PlotScene2DRenderer}) and
     * the SVG exporter ({@link SvgPlotWriter}). Curves pass through as {@link Series}; each asymptote
     * gets its {@code x=…} label; each marker becomes a {@link PlotScene2D.Feature} (a zero sits on
     * the axis, y = 0); the enclosure band rides along. Defining the drawing meaning once here is
     * what keeps the two backends from duplicating it.
     */
    static PlotScene2D buildScene(AnnotatedChart chart, PlotFrame frame) {
        List<PlotScene2D.Asymptote> asy = new ArrayList<>();
        for (double x : chart.vlines()) asy.add(new PlotScene2D.Asymptote(x, "x=" + fmt(x)));
        List<PlotScene2D.Feature> feats = new ArrayList<>();
        for (MarkSet ms : chart.marks()) {
            PlotScene2D.FeatureKind kind = featureKind(ms.kind());
            for (Feature f : ms.pts()) {
                double y = ms.kind() == 0 ? 0.0 : f.y();       // a zero is marked on the x-axis
                feats.add(new PlotScene2D.Feature(kind, f.x(), y, markLabel(ms.kind(), f)));
            }
        }
        return new PlotScene2D(frame, chart.series(), asy, feats, chart.enclosure());
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

    /** A compact numeric label: up to 3 decimals, trailing zeros trimmed ({@code 2.0 → "2"}). */
    static String fmt(double v) {
        double r = Math.round(v * 1000.0) / 1000.0;
        if (r == Math.rint(r) && !Double.isInfinite(r)) return Long.toString((long) r);
        return java.math.BigDecimal.valueOf(r).stripTrailingZeros().toPlainString();
    }

    private static double[] toArray(List<Double> xs) {
        double[] a = new double[xs.size()];
        for (int i = 0; i < a.length; i++) a[i] = xs.get(i);
        return a;
    }

    /**
     * A 3D point cloud: a {@link Component.SceneView} carrying a single {@link PointLayer},
     * a perspective camera, and orbit/zoom interaction (drag orbits, scroll zooms — wired in
     * {@link #wireInput}). {@code DasumVis.init()} must have run first ({@link #openWindowWithRoot}
     * ensures it).
     */
    static Component buildCloudView(float[] xyz) {
        Component.SceneView view =                       // null size → fills the window
                plotSceneView();
        SceneStates.publish(view, SceneSnapshot.of(new PointLayer(xyz, null)));
        SceneStates.setCamera(view, CameraSpec.defaultPerspective());
        SceneStates.setInteraction(view, InteractionSpec.defaults());  // ORBIT_3D
        return view;
    }

    /**
     * A 3D surface from a row-major height grid {@code zs} (length {@code N*N}) over the
     * {@code [xlo,xhi] x [ylo,yhi]} domain: each grid cell becomes two triangles
     * ({@link TriangleLayer}), height is the up (y) axis, colour ramps blue→red by height. The
     * camera is framed to the surface bounds; drag orbits, scroll zooms (wired in
     * {@link #wireInput}).
     */
    static Component buildSurfaceView(double[] zs, double xlo, double xhi, double ylo, double yhi) {
        SurfaceMesh mesh = meshSurface(zs, xlo, xhi, ylo, yhi, "cool");
        if (mesh == null) {
            return errorLabel("surface needs an N*N grid (N>=2); got " + zs.length + " heights");
        }
        Component.SceneView view =                       // null size → fills the window
                plotSceneView();
        // OPAQUE (not the TriangleLayer 2-arg default of ALPHA): the surface is solid, so it must
        // WRITE the depth buffer. An ALPHA layer has depth writes disabled in SceneRenderer, which
        // leaves the surface rendering in submission order — far triangles bleed through near ones.
        SceneStates.publish(view,
                SceneSnapshot.of(new TriangleLayer(mesh.verts(), mesh.cols()).withBlend(BlendMode.OPAQUE)));
        SceneStates.setCamera(view, CameraRig.fitToBounds(CameraSpec.defaultPerspective(),
                new Vec3((float) xlo, (float) mesh.zmin(), (float) ylo),
                new Vec3((float) xhi, (float) mesh.zmax(), (float) yhi)));
        SceneStates.setInteraction(view, InteractionSpec.defaults());  // ORBIT_3D
        return view;
    }

    /** A triangulated height grid: interleaved xyz {@code verts}, per-vertex RGB {@code cols}, and
     *  the height extent {@code [zmin, zmax]} (world Y). */
    private record SurfaceMesh(float[] verts, float[] cols, double zmin, double zmax) {}

    /**
     * Meshes a row-major height grid {@code zs} (length {@code N*N}) over {@code [xlo,xhi]x[ylo,yhi]}
     * into two triangles per cell — height is the world Y (up) axis, colour ramps blue→red by height.
     * Shared by {@link #buildSurfaceView} and the composed-scene path ({@link #surfaceLayer}). Returns
     * {@code null} when the grid isn't a usable N*N (N&gt;=2).
     */
    private static SurfaceMesh meshSurface(double[] zs, double xlo, double xhi, double ylo, double yhi,
                                           String colormap) {
        int n = (int) Math.round(Math.sqrt(zs.length));
        if (n < 2 || n * n != zs.length) return null;

        double zmin = Double.POSITIVE_INFINITY, zmax = Double.NEGATIVE_INFINITY;
        for (double z : zs) { zmin = Math.min(zmin, z); zmax = Math.max(zmax, z); }
        double zspan = zmax - zmin == 0 ? 1 : zmax - zmin;
        double sx = (xhi - xlo) / (n - 1), sy = (yhi - ylo) / (n - 1);

        int cells = (n - 1) * (n - 1);
        float[] verts = new float[cells * 2 * 9];   // 2 triangles/cell * 3 vertices * 3 floats
        float[] cols = new float[verts.length];
        int[] o = {0};
        for (int r = 0; r < n - 1; r++) {
            for (int c = 0; c < n - 1; c++) {
                int i00 = r * n + c, i01 = i00 + 1, i10 = i00 + n, i11 = i10 + 1;
                emitSurfaceVerts(verts, cols, o, n, xlo, ylo, sx, sy, zs, zmin, zspan, colormap, i00, i10, i11);
                emitSurfaceVerts(verts, cols, o, n, xlo, ylo, sx, sy, zs, zmin, zspan, colormap, i00, i11, i01);
            }
        }
        return new SurfaceMesh(verts, cols, zmin, zmax);
    }

    /** Appends the given grid vertices (by index) as world (x, height, y) positions + colormap colour. */
    private static void emitSurfaceVerts(float[] verts, float[] cols, int[] o, int n,
            double xlo, double ylo, double sx, double sy, double[] zs, double zmin, double zspan,
            String colormap, int... indices) {
        for (int idx : indices) {
            double x = xlo + (idx % n) * sx, z = ylo + (idx / n) * sy, h = zs[idx];
            verts[o[0]] = (float) x;
            verts[o[0] + 1] = (float) h;     // height is the up axis
            verts[o[0] + 2] = (float) z;
            float[] rgb = colorFor(colormap, (float) ((h - zmin) / zspan));
            cols[o[0]] = rgb[0];
            cols[o[0] + 1] = rgb[1];
            cols[o[0] + 2] = rgb[2];
            o[0] += 3;
        }
    }

    // --- Composed scenes: many layers, one window (docs/plotting.md) --------------------------

    /** The layers of a composed scene, the world-space bounds to frame the camera to, and (when the
     *  scene has a surface) the colorbar key for the first surface's colormap + height range. */
    record SceneBuild(List<Layer> layers, Vec3 min, Vec3 max, Bar bar) {}

    /** A colorbar key: the colormap name and the value range {@code [lo, hi]} it spans. */
    record Bar(String colormap, double lo, double hi) {}

    /** Accumulates a world-space axis-aligned bounding box over the layers of a scene. */
    private static final class Bounds {
        private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        private boolean any = false;

        void add(double x, double y, double z) {
            minX = Math.min(minX, (float) x); maxX = Math.max(maxX, (float) x);
            minY = Math.min(minY, (float) y); maxY = Math.max(maxY, (float) y);
            minZ = Math.min(minZ, (float) z); maxZ = Math.max(maxZ, (float) z);
            any = true;
        }

        Vec3 min() { return any ? new Vec3(minX, minY, minZ) : new Vec3(-1f, -1f, -1f); }
        Vec3 max() { return any ? new Vec3(maxX, maxY, maxZ) : new Vec3(1f, 1f, 1f); }

        /** A representative world span, used to size text relative to the scene (>=1 unit). */
        float span() {
            if (!any) return 2f;
            float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
            return Math.max(1e-3f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
    }

    /**
     * Turns a Pontif {@code {layers}} tuple (each member a {@code Surface}/{@code Cloud}/{@code Text3D}
     * record, sampled Pontif-side) into dasum {@link Layer}s, in input order, and computes the scene
     * bounds. Geometry layers come first; text layers are appended last (so they draw over the
     * geometry) and sized relative to the geometry's bounds. Package-visible: the headless test seam
     * (asserts layer count/kind without opening a window), the analog of {@link #buildSurfaceView}.
     */
    static SceneBuild buildSceneLayers(Object layersValue) {
        List<Layer> geometry = new ArrayList<>();
        List<RecordValue> texts = new ArrayList<>();
        Bounds b = new Bounds();
        Bar bar = null;
        if (layersValue instanceof RecordValue tuple) {
            for (Object member : tuple.members().values()) {
                if (!(member instanceof RecordValue rv)) continue;
                switch (bareType(rv.typeName())) {
                    case "Surface" -> {
                        Layer l = surfaceLayer(rv, b);
                        if (l != null) {
                            geometry.add(l);
                            if (bar == null) bar = surfaceBar(rv);   // colorbar keys off the first surface
                            if (rv.members().get("wire") instanceof Boolean w && w) {
                                Layer wf = wireframeLayer(rv);
                                if (wf != null) geometry.add(wf);
                            }
                        }
                    }
                    case "Cloud" -> geometry.add(cloudLayer(rv, b));
                    case "Volume" -> {
                        Layer l = volumeLayer(rv, b);
                        if (l != null) {
                            geometry.add(l);
                            if (rv.members().get("normals") instanceof Boolean nrm && nrm) {
                                Layer g = gradientGlyphLayer(rv);   // overlay gradient-direction glyphs
                                if (g != null) geometry.add(g);
                            }
                        }
                    }
                    case "Raymarch" -> {
                        Layer l = raymarchLayer(rv, b);
                        if (l != null) geometry.add(l);
                    }
                    case "Text3D" -> { texts.add(rv); addText3dBounds(rv, b); }
                    default -> { /* skip unknown layer kinds rather than fail the whole scene */ }
                }
            }
        }
        List<Layer> layers = new ArrayList<>(geometry);
        float textHeight = 0.06f * b.span();   // legible relative to the scene, not a fixed world size
        for (RecordValue rv : texts) layers.add(text3dLayer(rv, textHeight));
        return new SceneBuild(layers, b.min(), b.max(), bar);
    }

    /**
     * A caller-supplied SDF shader (pontif.shape {@code render}): a GLSL {@code float map(vec3 p)}
     * plus a world-space AABB (center ± half-extent). The shape's signed-distance function is
     * lowered to GLSL interpreter-side (docs/sdf-glsl.md); only the inert {@code map} string
     * crosses the boundary. Rendered by Dasum's {@link RaymarchLayer} sphere-tracer, which
     * depth-composes with the rest of the scene.
     */
    private static Layer raymarchLayer(RecordValue rv, Bounds b) {
        if (!(rv.members().get("map") instanceof StringValue map) || map.content().isBlank()) return null;
        double cx = memberD(rv, "cx"), cy = memberD(rv, "cy"), cz = memberD(rv, "cz");
        double hx = memberD(rv, "hx"), hy = memberD(rv, "hy"), hz = memberD(rv, "hz");
        b.add(cx - hx, cy - hy, cz - hz);
        b.add(cx + hx, cy + hy, cz + hz);
        Vec3 center = new Vec3((float) cx, (float) cy, (float) cz);
        Vec3 half = new Vec3((float) hx, (float) hy, (float) hz);
        return RaymarchLayer.standard(map.content(), center, half, Color.rgb(0.62f, 0.71f, 0.92f));
    }

    /** The colorbar key for a {@code Surface} record: its colormap name over its height range. */
    private static Bar surfaceBar(RecordValue rv) {
        double[] zs = doubles(rv.members().get("zs"));
        double zmin = Double.POSITIVE_INFINITY, zmax = Double.NEGATIVE_INFINITY;
        for (double z : zs) { zmin = Math.min(zmin, z); zmax = Math.max(zmax, z); }
        String map = rv.members().get("colormap") instanceof StringValue s ? s.content() : "cool";
        return zs.length == 0 ? null : new Bar(map, zmin, zmax);
    }

    /** A {@code Surface} layer record → an OPAQUE (solid) or ALPHA (faded) triangle mesh. */
    private static Layer surfaceLayer(RecordValue rv, Bounds b) {
        double[] zs = doubles(rv.members().get("zs"));
        double xlo = memberD(rv, "xlo"), xhi = memberD(rv, "xhi"),
               ylo = memberD(rv, "ylo"), yhi = memberD(rv, "yhi");
        String colormap = rv.members().get("colormap") instanceof StringValue s ? s.content() : "cool";
        SurfaceMesh mesh = meshSurface(zs, xlo, xhi, ylo, yhi, colormap);
        if (mesh == null) return null;
        b.add(xlo, mesh.zmin(), ylo);
        b.add(xhi, mesh.zmax(), yhi);
        double opacity = rv.members().containsKey("opacity") ? memberD(rv, "opacity") : 1.0;
        TriangleLayer tri = new TriangleLayer(mesh.verts(), mesh.cols());
        // Solid (opacity>=1) writes depth (OPAQUE → true occlusion); faded is translucent so
        // layers behind show through (the "stack on top" case), reading depth but not writing it.
        return opacity >= 1.0
                ? tri.withBlend(BlendMode.OPAQUE)
                : tri.withBlend(BlendMode.ALPHA).withOpacity((float) opacity);
    }

    private static final Color WIRE_COLOR = new Color(0.10f, 0.12f, 0.16f, 1f);

    /** A {@code Surface} record → a {@link LineLayer} tracing its N×N sample grid, lifted a hair
     *  toward the viewer so it doesn't z-fight the surface it overlays. Returns null for a bad grid. */
    private static Layer wireframeLayer(RecordValue rv) {
        double[] zs = doubles(rv.members().get("zs"));
        int n = (int) Math.round(Math.sqrt(zs.length));
        if (n < 2 || n * n != zs.length) return null;
        double xlo = memberD(rv, "xlo"), xhi = memberD(rv, "xhi"),
               ylo = memberD(rv, "ylo"), yhi = memberD(rv, "yhi");
        double sx = (xhi - xlo) / (n - 1), sy = (yhi - ylo) / (n - 1);
        double zmin = Double.POSITIVE_INFINITY, zmax = Double.NEGATIVE_INFINITY;
        for (double z : zs) { zmin = Math.min(zmin, z); zmax = Math.max(zmax, z); }
        float lift = (float) (0.004 * (zmax - zmin == 0 ? 1 : zmax - zmin));   // avoid z-fighting

        // Row edges (n rows × n-1) + column edges (n cols × n-1) = 2n(n-1) segments.
        float[] ep = new float[2 * n * (n - 1) * 6];
        int[] o = {0};
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n - 1; c++) {
                putEdge(ep, o, xlo + c * sx, zs[r * n + c] + lift, ylo + r * sy,
                        xlo + (c + 1) * sx, zs[r * n + c + 1] + lift, ylo + r * sy);
            }
        }
        for (int c = 0; c < n; c++) {
            for (int r = 0; r < n - 1; r++) {
                putEdge(ep, o, xlo + c * sx, zs[r * n + c] + lift, ylo + r * sy,
                        xlo + c * sx, zs[(r + 1) * n + c] + lift, ylo + (r + 1) * sy);
            }
        }
        return new LineLayer(ep, filledColor(ep.length, WIRE_COLOR));
    }

    /** Writes one line segment (two xyz endpoints) into {@code ep} at cursor {@code o[0]}. */
    private static void putEdge(float[] ep, int[] o,
            double ax, double ay, double az, double bx, double by, double bz) {
        ep[o[0]] = (float) ax; ep[o[0] + 1] = (float) ay; ep[o[0] + 2] = (float) az;
        ep[o[0] + 3] = (float) bx; ep[o[0] + 4] = (float) by; ep[o[0] + 5] = (float) bz;
        o[0] += 6;
    }

    /** Fraction of the peak gradient below which a voxel is dropped (flat space contributes nothing). */
    private static final float VOLUME_THRESHOLD = 0.05f;
    /** Emission gain on voxel density. HDR: pushed above 1 so bright cores overflow and bloom picks
     *  them up (the ACES tone-map in the composite brings the range back). Tune by eye. */
    private static final float VOLUME_EXPOSURE = 6.0f;
    /** Per-layer alpha for the additive voxels — the "how bright does the glow accumulate" knob. */
    private static final float VOLUME_OPACITY = 0.3f;

    /**
     * A {@code Volume} record → a raymarched {@link VolumeLayer} coloured by GRADIENT DIRECTION: at
     * each grid voxel the field's gradient is estimated by central differences; its DIRECTION
     * ({@code |∂x|,|∂y|,|∂z|} normalized) is the voxel's RGB (so the axis of fastest change lights
     * its channel) and a LOG of its magnitude is the voxel's density/alpha (so steep and gentle
     * boundaries both read). The dense RGBA grid uploads to a 3D texture and the shader accumulates
     * it emissively along each ray — continuous (trilinear-filtered), crisper than points.
     * (docs/plotting.md)
     */
    private static Layer volumeLayer(RecordValue rv, Bounds b) {
        double[] vs = doubles(rv.members().get("vs"));
        int n = (int) Math.round(Math.cbrt(vs.length));
        if (n < 2 || (long) n * n * n != vs.length) return null;
        double xlo = memberD(rv, "xlo"), xhi = memberD(rv, "xhi"),
               ylo = memberD(rv, "ylo"), yhi = memberD(rv, "yhi"),
               zlo = memberD(rv, "zlo"), zhi = memberD(rv, "zhi");
        b.add(xlo, ylo, zlo);
        b.add(xhi, yhi, zhi);
        double sx = (xhi - xlo) / (n - 1), sy = (yhi - ylo) / (n - 1), sz = (zhi - zlo) / (n - 1);
        int nn = n * n;

        // Pass 1: per-voxel abs gradient components (central differences, one-sided at edges) and
        // gradient magnitude; track the peak magnitude for the log-brightness normalization.
        float[] gx = new float[vs.length], gy = new float[vs.length], gz = new float[vs.length];
        float[] mag = new float[vs.length];
        float magMax = 1e-12f;
        for (int iz = 0; iz < n; iz++) for (int iy = 0; iy < n; iy++) for (int ix = 0; ix < n; ix++) {
            int idx = ix + iy * n + iz * nn;
            int xm = Math.max(0, ix - 1), xp = Math.min(n - 1, ix + 1);
            int ym = Math.max(0, iy - 1), yp = Math.min(n - 1, iy + 1);
            int zm = Math.max(0, iz - 1), zp = Math.min(n - 1, iz + 1);
            gx[idx] = sx > 0 ? (float) Math.abs((vs[xp + iy*n + iz*nn] - vs[xm + iy*n + iz*nn]) / ((xp - xm) * sx)) : 0f;
            gy[idx] = sy > 0 ? (float) Math.abs((vs[ix + yp*n + iz*nn] - vs[ix + ym*n + iz*nn]) / ((yp - ym) * sy)) : 0f;
            gz[idx] = sz > 0 ? (float) Math.abs((vs[ix + iy*n + zp*nn] - vs[ix + iy*n + zm*nn]) / ((zp - zm) * sz)) : 0f;
            mag[idx] = (float) Math.sqrt((double) gx[idx]*gx[idx] + (double) gy[idx]*gy[idx] + (double) gz[idx]*gz[idx]);
            magMax = Math.max(magMax, mag[idx]);
        }

        // Pass 2: fill a dense RGBA grid — rgb = gradient DIRECTION (which axis it changes along),
        // a = LOG of the gradient magnitude × exposure (log compresses steep-vs-gentle so both
        // read). Flat voxels below a small threshold stay transparent (0).
        float[] rgba = new float[vs.length * 4];
        double logDen = Math.log1p(Math.E - 1);   // = 1; normalizes the log curve to [0,1]
        for (int idx = 0; idx < vs.length; idx++) {
            float t = mag[idx] / magMax;                       // linear steepness in [0,1]
            if (t < VOLUME_THRESHOLD) continue;                // leave this voxel transparent (0)
            float bright = (float) (Math.log1p(t * (Math.E - 1)) / logDen) * VOLUME_EXPOSURE;
            float inv = 1f / mag[idx];                          // signed unit gradient direction
            rgba[idx*4    ] = gx[idx] * inv;
            rgba[idx*4 + 1] = gy[idx] * inv;
            rgba[idx*4 + 2] = gz[idx] * inv;
            rgba[idx*4 + 3] = bright;                           // density/alpha
        }
        float opacity = rv.members().containsKey("opacity")
                ? Math.max(0f, Math.min(1f, (float) memberD(rv, "opacity"))) : VOLUME_OPACITY;
        Vec3 center = new Vec3((float) ((xlo + xhi) / 2), (float) ((ylo + yhi) / 2), (float) ((zlo + zhi) / 2));
        Vec3 half = new Vec3((float) ((xhi - xlo) / 2), (float) ((yhi - ylo) / 2), (float) ((zhi - zlo) / 2));
        return new VolumeLayer(rgba, n, n, n, center, half, 128, BlendMode.ADDITIVE, opacity);
    }

    /** Longest glyph spans this fraction of the inter-glyph spacing (<=1 ⇒ no glyph reaches a neighbour). */
    private static final float GLYPH_FILL = 0.9f;
    /** Neutral overlay colour for the gradient-direction glyphs — a distinct annotation over the glow. */
    private static final Color GLYPH_COLOR = new Color(0.85f, 0.87f, 0.92f, 1f);

    /**
     * A {@code Volume} record with {@code normals} enabled → a {@link LineLayer} of short segments on a
     * {@code stride}-spaced lattice, each centred on a voxel and oriented along the field's SIGNED
     * gradient there. Segment length ∝ the gradient magnitude normalized by the volume's peak, scaled
     * so the steepest glyph spans {@link #GLYPH_FILL} of the inter-glyph gap — so none reach into a
     * neighbour. Near-flat voxels below {@link #VOLUME_THRESHOLD} are skipped, matching {@link
     * #volumeLayer}. Length is relative within one volume (normalized by its own peak), not an absolute
     * magnitude. Recomputes the gradient independently, as {@link #wireframeLayer} does for its surface.
     */
    private static Layer gradientGlyphLayer(RecordValue rv) {
        double[] vs = doubles(rv.members().get("vs"));
        int n = (int) Math.round(Math.cbrt(vs.length));
        if (n < 2 || (long) n * n * n != vs.length) return null;
        int stride = rv.members().containsKey("stride")
                ? Math.max(1, (int) Math.round(memberD(rv, "stride"))) : 3;
        double xlo = memberD(rv, "xlo"), xhi = memberD(rv, "xhi"),
               ylo = memberD(rv, "ylo"), yhi = memberD(rv, "yhi"),
               zlo = memberD(rv, "zlo"), zhi = memberD(rv, "zhi");
        double sx = (xhi - xlo) / (n - 1), sy = (yhi - ylo) / (n - 1), sz = (zhi - zlo) / (n - 1);
        int nn = n * n;

        // Peak gradient magnitude over the whole grid — the SAME normalization the volume brightness
        // uses (volumeLayer pass 1), so glyph length tracks the glow's steepness.
        double magMax = 1e-12;
        for (int iz = 0; iz < n; iz++) for (int iy = 0; iy < n; iy++) for (int ix = 0; ix < n; ix++) {
            double[] g = gradVec(vs, n, nn, ix, iy, iz, sx, sy, sz);
            magMax = Math.max(magMax, Math.sqrt(g[0]*g[0] + g[1]*g[1] + g[2]*g[2]));
        }

        // Steepest glyph = GLYPH_FILL of the inter-glyph spacing (stride cells along the tightest axis)
        // so it can't reach its neighbour; glyphs are centred on the voxel (half the length each way).
        double maxLen = GLYPH_FILL * stride * Math.min(sx, Math.min(sy, sz));

        // One segment per surviving strided voxel: two xyz endpoints (6 floats) each.
        List<Float> ep = new ArrayList<>();
        for (int iz = 0; iz < n; iz += stride) for (int iy = 0; iy < n; iy += stride) for (int ix = 0; ix < n; ix += stride) {
            double[] g = gradVec(vs, n, nn, ix, iy, iz, sx, sy, sz);   // signed ∂x,∂y,∂z
            double m = Math.sqrt(g[0]*g[0] + g[1]*g[1] + g[2]*g[2]);
            double t = m / magMax;                                     // normalized steepness in [0,1]
            if (t < VOLUME_THRESHOLD) continue;                        // skip near-flat voxels, as the volume does
            double half = 0.5 * t * maxLen / m;                        // (half length) / |g|, to unit-scale g below
            double hx = g[0]*half, hy = g[1]*half, hz = g[2]*half;
            double px = xlo + ix * sx, py = ylo + iy * sy, pz = zlo + iz * sz;
            ep.add((float)(px - hx)); ep.add((float)(py - hy)); ep.add((float)(pz - hz));
            ep.add((float)(px + hx)); ep.add((float)(py + hy)); ep.add((float)(pz + hz));
        }
        if (ep.isEmpty()) return null;
        float[] arr = new float[ep.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ep.get(i);
        return new LineLayer(arr, filledColor(arr.length, GLYPH_COLOR));
    }

    /** Signed central-difference gradient {∂x,∂y,∂z} of the scalar grid at voxel (ix,iy,iz), one-sided
     *  at edges — the signed sibling of {@link #volumeLayer}'s (abs) pass-1 gradient. */
    private static double[] gradVec(double[] vs, int n, int nn, int ix, int iy, int iz,
            double sx, double sy, double sz) {
        int xm = Math.max(0, ix - 1), xp = Math.min(n - 1, ix + 1);
        int ym = Math.max(0, iy - 1), yp = Math.min(n - 1, iy + 1);
        int zm = Math.max(0, iz - 1), zp = Math.min(n - 1, iz + 1);
        double dx = sx > 0 ? (vs[xp + iy*n + iz*nn] - vs[xm + iy*n + iz*nn]) / ((xp - xm) * sx) : 0;
        double dy = sy > 0 ? (vs[ix + yp*n + iz*nn] - vs[ix + ym*n + iz*nn]) / ((yp - ym) * sy) : 0;
        double dz = sz > 0 ? (vs[ix + iy*n + zp*nn] - vs[ix + iy*n + zm*nn]) / ((zp - zm) * sz) : 0;
        return new double[]{dx, dy, dz};
    }

    /** A {@code Cloud} layer record → an OPAQUE point layer (so it occludes with surfaces). */
    private static Layer cloudLayer(RecordValue rv, Bounds b) {
        float[] xyz = xyzTriples(rv.members().get("points"));
        for (int i = 0; i + 2 < xyz.length; i += 3) b.add(xyz[i], xyz[i + 1], xyz[i + 2]);
        double opacity = rv.members().containsKey("opacity") ? memberD(rv, "opacity") : 1.0;
        PointLayer pts = new PointLayer(xyz, null).withBlend(BlendMode.OPAQUE);
        return opacity >= 1.0 ? pts : pts.withBlend(BlendMode.ALPHA).withOpacity((float) opacity);
    }

    /** A {@code Text3D} layer record → a billboarded world-space label of the given world height. */
    private static Layer text3dLayer(RecordValue rv, float heightWorld) {
        Vec3 at = new Vec3((float) memberD(rv, "x"), (float) memberD(rv, "y"), (float) memberD(rv, "z"));
        return new TextLayer(str(rv, "text"), at, heightWorld, TEXT).withBillboard(true);
    }

    private static void addText3dBounds(RecordValue rv, Bounds b) {
        b.add(memberD(rv, "x"), memberD(rv, "y"), memberD(rv, "z"));
    }

    /** Target side length of the display cube for box-aspect normalization. */
    private static final float CUBE = 10f;

    /**
     * The scene component: one window-filling {@link Component.SceneView} carrying all layers (plus
     * the axis box when {@code axes}). Geometry is built in DATA space and then mapped into a display
     * cube so any data range reads well (box aspect); {@code equalAspect} keeps true proportions
     * instead. A colorbar sidebar is added when the scene has a surface.
     */
    private static Component sceneComponent(SceneBuild build, boolean axes, boolean grid, boolean equalAspect) {
        List<Layer> raw = new ArrayList<>(build.layers());
        if (axes) raw.addAll(axisBoxLayers(build.min(), build.max(), grid));

        Transform t = equalAspect ? Transform.IDENTITY : boxTransform(build.min(), build.max());
        float textScale = t.gmean();
        List<Layer> shown = new ArrayList<>(raw.size());
        for (Layer l : raw) shown.add(scaleLayer(l, t, textScale));

        Component.SceneView view =                       // null width/height → fills the window
                plotSceneView();
        SceneStates.publish(view, new SceneSnapshot(shown));
        SceneStates.setCamera(view, CameraRig.fitToBounds(CameraSpec.defaultPerspective(),
                t.apply(build.min()), t.apply(build.max())));
        SceneStates.setInteraction(view, InteractionSpec.defaults());  // ORBIT_3D
        if (build.bar() == null) return view;
        // Colorbar key beside the scene: the view flex-grows to fill, the bar takes its own width.
        return new Component.Flex(null, null, Em.of(0.6f), TRANSPARENT,
                Direction.ROW, JustifyContent.START, AlignItems.STRETCH, Em.of(0.8f),
                List.of(view, colorbar(build.bar())), false, 1);
    }

    // --- Box-aspect normalization: map data-space coordinates into a display cube ---------------

    /** A per-axis affine map (center + scale) from data space into the display cube. Tick labels
     *  keep their data values; only positions are transformed. */
    private record Transform(float cx, float cy, float cz, float sx, float sy, float sz) {
        static final Transform IDENTITY = new Transform(0, 0, 0, 1, 1, 1);
        float ax(double v) { return (float) ((v - cx) * sx); }
        float ay(double v) { return (float) ((v - cy) * sy); }
        float az(double v) { return (float) ((v - cz) * sz); }
        Vec3 apply(Vec3 p) { return new Vec3(ax(p.x()), ay(p.y()), az(p.z())); }
        /** Uniform factor for scaling text height (geometric mean of the axis scales). */
        float gmean() { return (float) Math.cbrt(Math.abs((double) sx * sy * sz)); }
    }

    /** Build the box transform mapping {@code [lo, hi]} onto a {@code CUBE}-sided cube centred at origin. */
    private static Transform boxTransform(Vec3 lo, Vec3 hi) {
        float cx = (lo.x() + hi.x()) / 2f, cy = (lo.y() + hi.y()) / 2f, cz = (lo.z() + hi.z()) / 2f;
        return new Transform(cx, cy, cz,
                CUBE / Math.max(1e-4f, hi.x() - lo.x()),
                CUBE / Math.max(1e-4f, hi.y() - lo.y()),
                CUBE / Math.max(1e-4f, hi.z() - lo.z()));
    }

    /** Rebuild a layer with its coordinates mapped through {@code t} (text height by {@code textScale}). */
    private static Layer scaleLayer(Layer l, Transform t, float textScale) {
        if (t == Transform.IDENTITY) return l;
        return switch (l) {
            case TriangleLayer tr -> new TriangleLayer(scaleXYZ(tr.vertices(), t), tr.colors(), tr.blend(), tr.opacity());
            case PointLayer p -> {
                // World-sized points scale their diameter with the box transform (like text);
                // screen-pixel points keep their fixed size.
                float size = p.perspectiveSize() ? p.defaultSizePx() * textScale : p.defaultSizePx();
                yield new PointLayer(scaleXYZ(p.positions(), t), p.colors(), p.sizes(),
                        size, p.perspectiveSize(), p.blend(), p.opacity());
            }
            case LineLayer ln -> new LineLayer(scaleXYZ(ln.endpoints(), t), ln.colors(), ln.blend(), ln.opacity());
            case TextLayer tx -> new TextLayer(tx.text(), tx.fontGroup(), t.apply(tx.anchor()),
                    tx.heightWorld() * textScale, tx.color(), tx.align(), tx.billboard(), tx.blend(), tx.opacity());
            case VolumeLayer vol -> {
                // The box maps into the display cube: centre transforms like a point, the per-axis
                // half-extent scales (no centre offset). Grid data is unchanged.
                Vec3 h = new Vec3(vol.halfExtent().x() * t.sx(),
                        vol.halfExtent().y() * t.sy(), vol.halfExtent().z() * t.sz());
                yield new VolumeLayer(vol.rgba(), vol.nx(), vol.ny(), vol.nz(),
                        t.apply(vol.center()), h, vol.maxSteps(), vol.blend(), vol.opacity());
            }
            default -> l;
        };
    }

    /** Map every interleaved xyz triple in {@code a} through {@code t}, returning a new array. */
    private static float[] scaleXYZ(float[] a, Transform t) {
        float[] o = new float[a.length];
        for (int i = 0; i + 2 < a.length; i += 3) {
            o[i] = t.ax(a[i]); o[i + 1] = t.ay(a[i + 1]); o[i + 2] = t.az(a[i + 2]);
        }
        return o;
    }

    /** A vertical colorbar strip (high at top) for {@code bar}'s colormap, with min/max labels.
     *  Fixed width (an explicit flex basis): a null-width flex child resolves to intrinsic 0 and,
     *  with no grow weight, would be allocated 0px and overflow its content off-screen. */
    private static Component colorbar(Bar bar) {
        int steps = 24;
        List<Component> col = new ArrayList<>();
        col.add(new Component.Text(fmtNum(bar.hi()), Em.of(0.85f), TEXT));
        for (int i = steps - 1; i >= 0; i--) {           // top row = highest value
            float[] c = colorFor(bar.colormap(), i / (float) (steps - 1));
            col.add(new Component.Flex(Em.of(2.4f), Em.of(0.32f), Em.ZERO, new Color(c[0], c[1], c[2], 1f),
                    Direction.COLUMN, JustifyContent.CENTER, AlignItems.CENTER, Em.ZERO,
                    List.of(), false, 0));
        }
        col.add(new Component.Text(fmtNum(bar.lo()), Em.of(0.85f), TEXT));
        return new Component.Flex(Em.of(4f), null, Em.of(0.4f), TRANSPARENT,
                Direction.COLUMN, JustifyContent.CENTER, AlignItems.CENTER, Em.of(0.15f),
                col, false, 0);
    }

    /** Compact number for a colorbar/label: integer when whole, else up to 3 significant decimals. */
    private static String fmtNum(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString(Math.round(v));
        String s = String.format("%.3f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    // --- 3D graduations: a labeled, tick-marked bounding box (docs/plotting.md) ---------------

    private static final Color AXIS_COLOR = new Color(0.55f, 0.60f, 0.70f, 1f);
    private static final Color GRID_COLOR = new Color(0.26f, 0.29f, 0.36f, 1f);

    /**
     * Builds the 3D graduation layers for a scene's world bounds {@code [lo, hi]}: a wireframe
     * bounding box, per-axis tick marks + billboard numeric labels (nice-number positions from
     * dasum's {@link Ticks}/{@link Axis}, reused from the 2D chart stack), and — when {@code grid} —
     * a floor grid on the {@code y = lo.y} plane. World axes are X (right), Y (up/height), Z (depth);
     * because plot geometry is placed at world = data coordinates, ticks sit at data values directly.
     * Package-visible: the headless test seam.
     */
    static List<Layer> axisBoxLayers(Vec3 lo, Vec3 hi, boolean grid) {
        List<Layer> out = new ArrayList<>();
        float span = dist(lo, hi);
        if (span <= 0f) return out;                    // degenerate / empty scene
        float tickLen = 0.02f * span;
        float labelH = 0.035f * span;
        float gap = 0.03f * span;

        float[] box = boxEdges(lo, hi);
        out.add(new LineLayer(box, filledColor(box.length, AXIS_COLOR)));

        addAxisTicks(out, Axis3.X, lo, hi, tickLen, labelH, gap);
        addAxisTicks(out, Axis3.Y, lo, hi, tickLen, labelH, gap);
        addAxisTicks(out, Axis3.Z, lo, hi, tickLen, labelH, gap);

        if (grid) {
            float[] floor = floorGrid(lo, hi);
            if (floor.length > 0) out.add(new LineLayer(floor, filledColor(floor.length, GRID_COLOR)));
        }
        return out;
    }

    private enum Axis3 { X, Y, Z }

    /** Adds one tick-mark {@link LineLayer} plus a billboard label {@link TextLayer} per nice tick,
     *  along the {@code axis} edge meeting at the {@code lo} corner. */
    private static void addAxisTicks(List<Layer> out, Axis3 axis, Vec3 lo, Vec3 hi,
                                     float tickLen, float labelH, float gap) {
        double min = switch (axis) { case X -> lo.x(); case Y -> lo.y(); case Z -> lo.z(); };
        double max = switch (axis) { case X -> hi.x(); case Y -> hi.y(); case Z -> hi.z(); };
        if (max <= min) return;
        Ticks.TickSet ts = Ticks.forAxis(Axis.linear(min, max), 5);

        List<float[]> segs = new ArrayList<>();
        for (int i = 0; i < ts.count(); i++) {
            double v = ts.values()[i];
            if (v < min - 1e-9 || v > max + 1e-9) continue;   // drop loose ticks outside the box
            float fv = (float) v;
            // Tick position on the lo-corner edge, a short mark outward, and a label just beyond.
            float[] a, b, label;
            switch (axis) {
                case X -> { a = new float[]{fv, lo.y(), lo.z()}; b = new float[]{fv, lo.y(), lo.z() - tickLen};
                            label = new float[]{fv, lo.y(), lo.z() - tickLen - gap}; }
                case Y -> { a = new float[]{lo.x(), fv, lo.z()}; b = new float[]{lo.x() - tickLen, fv, lo.z()};
                            label = new float[]{lo.x() - tickLen - gap, fv, lo.z()}; }
                default -> { a = new float[]{lo.x(), lo.y(), fv}; b = new float[]{lo.x() - tickLen, lo.y(), fv};
                            label = new float[]{lo.x() - tickLen - gap, lo.y(), fv}; }
            }
            segs.add(new float[]{a[0], a[1], a[2], b[0], b[1], b[2]});
            out.add(new TextLayer(ts.labels()[i], new Vec3(label[0], label[1], label[2]), labelH, AXIS_COLOR)
                    .withBillboard(true));
        }
        if (!segs.isEmpty()) {
            float[] marks = new float[segs.size() * 6];
            for (int i = 0; i < segs.size(); i++) System.arraycopy(segs.get(i), 0, marks, i * 6, 6);
            out.add(new LineLayer(marks, filledColor(marks.length, AXIS_COLOR)));
        }
    }

    /** The 12 edges of the axis-aligned box {@code [lo, hi]} as line-segment endpoints (72 floats). */
    private static float[] boxEdges(Vec3 lo, Vec3 hi) {
        float x0 = lo.x(), y0 = lo.y(), z0 = lo.z(), x1 = hi.x(), y1 = hi.y(), z1 = hi.z();
        float[][] e = {
                // bottom rectangle (y0)
                {x0,y0,z0, x1,y0,z0}, {x1,y0,z0, x1,y0,z1}, {x1,y0,z1, x0,y0,z1}, {x0,y0,z1, x0,y0,z0},
                // top rectangle (y1)
                {x0,y1,z0, x1,y1,z0}, {x1,y1,z0, x1,y1,z1}, {x1,y1,z1, x0,y1,z1}, {x0,y1,z1, x0,y1,z0},
                // verticals
                {x0,y0,z0, x0,y1,z0}, {x1,y0,z0, x1,y1,z0}, {x1,y0,z1, x1,y1,z1}, {x0,y0,z1, x0,y1,z1},
        };
        float[] out = new float[e.length * 6];
        for (int i = 0; i < e.length; i++) System.arraycopy(e[i], 0, out, i * 6, 6);
        return out;
    }

    /** A floor grid on the {@code y = lo.y} plane at the X and Z nice-tick positions. */
    private static float[] floorGrid(Vec3 lo, Vec3 hi) {
        Ticks.TickSet xs = Ticks.forAxis(Axis.linear(lo.x(), hi.x()), 5);
        Ticks.TickSet zs = Ticks.forAxis(Axis.linear(lo.z(), hi.z()), 5);
        List<float[]> segs = new ArrayList<>();
        float y = lo.y();
        for (int i = 0; i < xs.count(); i++) {
            float x = (float) xs.values()[i];
            if (x < lo.x() - 1e-6 || x > hi.x() + 1e-6) continue;
            segs.add(new float[]{x, y, lo.z(), x, y, hi.z()});
        }
        for (int i = 0; i < zs.count(); i++) {
            float z = (float) zs.values()[i];
            if (z < lo.z() - 1e-6 || z > hi.z() + 1e-6) continue;
            segs.add(new float[]{lo.x(), y, z, hi.x(), y, z});
        }
        float[] out = new float[segs.size() * 6];
        for (int i = 0; i < segs.size(); i++) System.arraycopy(segs.get(i), 0, out, i * 6, 6);
        return out;
    }

    // --- Colormaps: t in [0,1] -> RGB (docs/plotting.md) --------------------------------------
    // "cool" is the legacy blue->red ramp; viridis/turbo are stop-table approximations of the
    // perceptually-uniform Matplotlib/Google maps (linearly interpolated — honest, not the exact
    // polynomial, but monotone and close).

    private static final float[][] VIRIDIS = {
            {0.267f, 0.005f, 0.329f}, {0.283f, 0.141f, 0.458f}, {0.254f, 0.265f, 0.530f},
            {0.207f, 0.372f, 0.553f}, {0.164f, 0.471f, 0.558f}, {0.128f, 0.567f, 0.551f},
            {0.135f, 0.659f, 0.518f}, {0.267f, 0.749f, 0.441f}, {0.478f, 0.821f, 0.318f},
            {0.741f, 0.873f, 0.150f}, {0.993f, 0.906f, 0.144f},
    };
    private static final float[][] TURBO = {
            {0.190f, 0.072f, 0.232f}, {0.275f, 0.408f, 0.859f}, {0.180f, 0.718f, 0.926f},
            {0.153f, 0.921f, 0.640f}, {0.451f, 0.995f, 0.318f}, {0.780f, 0.940f, 0.223f},
            {0.968f, 0.760f, 0.224f}, {0.977f, 0.469f, 0.130f}, {0.851f, 0.211f, 0.044f},
            {0.600f, 0.061f, 0.010f}, {0.480f, 0.016f, 0.011f},
    };

    /** Map a normalized height {@code t} to an RGB triple by named colormap. */
    static float[] colorFor(String colormap, float t) {
        float u = Math.max(0f, Math.min(1f, t));
        return switch (colormap == null ? "cool" : colormap) {
            case "grayscale", "gray" -> new float[]{u, u, u};
            case "viridis" -> lerpStops(VIRIDIS, u);
            case "turbo" -> lerpStops(TURBO, u);
            default -> new float[]{u, 0.5f, 1f - u};   // "cool" — legacy blue->red ramp
        };
    }

    /** Linearly interpolate an RGB stop table at {@code u} in [0,1]. */
    private static float[] lerpStops(float[][] stops, float u) {
        float pos = u * (stops.length - 1);
        int i = (int) Math.floor(pos);
        if (i >= stops.length - 1) return stops[stops.length - 1].clone();
        float f = pos - i;
        float[] a = stops[i], b = stops[i + 1];
        return new float[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
    }

    /** A per-vertex colour array of {@code len} floats filled with {@code c}'s RGB (3 per vertex). */
    private static float[] filledColor(int len, Color c) {
        float[] cols = new float[len];
        for (int i = 0; i + 2 < len; i += 3) { cols[i] = c.r(); cols[i + 1] = c.g(); cols[i + 2] = c.b(); }
        return cols;
    }

    private static float dist(Vec3 a, Vec3 b) {
        float dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** A config field as a boolean, or {@code def} when absent — {@code args[i]} is {@code {field = …}}. */
    private static boolean cfgBool(List<Object> args, int i, String field, boolean def) {
        if (i < args.size() && args.get(i) instanceof RecordValue rv
                && rv.members().get(field) instanceof Boolean b) {
            return b;
        }
        return def;
    }

    /** A numeric cfg field (Int/Decimal) as a positive int, or {@code def} when absent / non-positive.
     *  Powers the optional {@code width}/{@code height} keys on a plot cfg record ({@code chart({title
     *  = "…", width = 1200, height = 800}, {…})}); an omitted or ≤ 0 value falls back to the default. */
    private static int cfgInt(List<Object> args, int i, String field, int def) {
        if (i < args.size() && args.get(i) instanceof RecordValue rv) {
            Object v = rv.members().get(field);
            if (v instanceof Long l && l > 0) return Math.toIntExact(l);
            if (v instanceof Integer n && n > 0) return n;
            if (v instanceof BigDecimal d && d.signum() > 0) return d.intValue();
        }
        return def;
    }

    /** A struct field as a double (Int/Decimal scalar), or 0.0. */
    private static double memberD(RecordValue rv, String field) {
        return toDouble(rv.members().get(field));
    }

    /** A numeric member as a double, or {@code def} when the field is absent — so a partial config
     *  record falls back to the profile default per missing field. */
    private static double memberOr(RecordValue rv, String field, double def) {
        return rv.members().containsKey(field) ? toDouble(rv.members().get(field)) : def;
    }

    /**
     * Read a math-style config record (a {@code requires $…} data module, docs/plotting.md) into the
     * dasum {@link MathConstants} the typesetter consumes — the seam between "config as data" and the
     * engine. Fields are read by name; any omitted field falls back to the STIX Two Math default, so a
     * user config may override only the values they care about. Package-visible test seam.
     */
    static MathConstants mathConstantsFrom(RecordValue cfg) {
        MathConstants d = MathConstants.stixTwoMath();
        if (cfg == null) return d;
        String fontGroup = cfg.members().get("fontGroup") instanceof StringValue s ? s.content() : d.fontGroup();
        return new MathConstants(
                memberOr(cfg, "scriptScale", d.scriptScale()),
                memberOr(cfg, "scriptScriptScale", d.scriptScriptScale()),
                memberOr(cfg, "axisHeight", d.axisHeight()),
                memberOr(cfg, "fractionRuleThickness", d.fractionRuleThickness()),
                memberOr(cfg, "fractionGapNum", d.fractionGapNum()),
                memberOr(cfg, "fractionGapDen", d.fractionGapDen()),
                memberOr(cfg, "superscriptShiftUp", d.superscriptShiftUp()),
                memberOr(cfg, "subscriptShiftDown", d.subscriptShiftDown()),
                memberOr(cfg, "scriptGapAfter", d.scriptGapAfter()),
                memberOr(cfg, "radicalRuleThickness", d.radicalRuleThickness()),
                memberOr(cfg, "radicalGapAbove", d.radicalGapAbove()),
                memberOr(cfg, "radicalKernBefore", d.radicalKernBefore()),
                memberOr(cfg, "radicalKernAfter", d.radicalKernAfter()),
                memberOr(cfg, "spaceBinaryOp", d.spaceBinaryOp()),
                memberOr(cfg, "spaceRelation", d.spaceRelation()),
                memberOr(cfg, "spacePunct", d.spacePunct()),
                memberOr(cfg, "functionGap", d.functionGap()),
                memberOr(cfg, "delimiterPad", d.delimiterPad()),
                fontGroup);
    }

    // --- AlgExpr → MathBox: typeset an algebraic AST (docs/plotting.md) ---------------------------
    // The `AlgExpr` front-end of the math typesetter: a precedence-correct tree-walk from the same
    // AST we plot into the semantic MathBox IR, which then lays out + renders to SVG/OGL. Div →
    // fraction, Pow → superscript (or a radical for the ½ power), Mul → juxtaposition (a middot only
    // before a number, so a coefficient·power reads as `7x`, not `7·x`, but 2·3 doesn't become `23`),
    // functions → an upright name + parenthesised argument. Parentheses are added by precedence.

    /** Operator precedence for parenthesization: higher binds tighter. Div is a fraction (visually
     *  grouped), so it and the atoms never need parens around their children. */
    private static int mathPrec(RecordValue n) {
        return switch (bareType(n.typeName())) {
            case "Add", "Sub" -> 1;
            case "Mul" -> 2;
            case "Pow" -> 4;
            default -> 5;                       // Const / Param / Div / Sin… — atomic or self-grouping
        };
    }

    /** Translate an {@code AlgExpr} AST value into a {@link MathBox}. Package-visible test seam. */
    static MathBox algExprToMathBox(RecordValue ast) {
        return mathConv(ast);
    }

    /** Convert, wrapping in parentheses when the node binds looser than the surrounding context. */
    private static MathBox mathConvP(Object node, int minPrec) {
        MathBox b = mathConv(node);
        return (node instanceof RecordValue rv && mathPrec(rv) < minPrec) ? MathBox.paren(b) : b;
    }

    private static MathBox mathConv(Object node) {
        if (!(node instanceof RecordValue r)) return MathBox.num(String.valueOf(node));
        Map<String, Object> m = r.members();
        return switch (bareType(r.typeName())) {
            case "Const" -> MathBox.num(fmt(memberD(r, "value")));
            case "Param" -> MathBox.var(str(r, "name"));
            case "Add" -> MathBox.row(mathConvP(m.get("left"), 1), MathBox.op("+"), mathConvP(m.get("right"), 1));
            case "Sub" -> MathBox.row(mathConvP(m.get("left"), 1), MathBox.op("−"), mathConvP(m.get("right"), 2));
            case "Mul" -> mathMul(m.get("left"), m.get("right"));
            case "Div" -> MathBox.frac(mathConv(m.get("left")), mathConv(m.get("right")));
            case "Pow" -> mathPow(m.get("base"), m.get("exponent"));
            case "Sin" -> mathFunc("sin", m.get("arg"));
            case "Cos" -> mathFunc("cos", m.get("arg"));
            case "Tan" -> mathFunc("tan", m.get("arg"));
            case "Log" -> mathFunc("log", m.get("arg"));
            case "Exp" -> MathBox.pow(MathBox.sym("e"), mathConv(m.get("arg")));
            default -> MathBox.num("?");
        };
    }

    private static MathBox mathMul(Object l, Object r) {
        MathBox lb = mathConvP(l, 2), rb = mathConvP(r, 2);
        boolean rightIsNumber = r instanceof RecordValue rv && "Const".equals(bareType(rv.typeName()));
        return rightIsNumber ? MathBox.row(lb, MathBox.op("·"), rb) : MathBox.row(lb, rb);
    }

    private static MathBox mathPow(Object base, Object exp) {
        if (exp instanceof RecordValue ev && "Const".equals(bareType(ev.typeName()))
                && Math.abs(memberD(ev, "value") - 0.5) < 1e-9) {
            return MathBox.sqrt(mathConv(base));              // x^(1/2) → √x
        }
        return MathBox.pow(mathConvP(base, 5), mathConv(exp));
    }

    private static MathBox mathFunc(String name, Object arg) {
        return MathBox.row(MathBox.fn(name), MathBox.paren(mathConv(arg)));
    }

    /** The math-font atlas metrics for layout (lazy classpath load; same atlas the FontGroup uses). */
    private static AtlasData mathAtlas;
    private static synchronized AtlasData mathAtlas() {
        if (mathAtlas == null) mathAtlas = AtlasData.loadFromResource("/dasum/atlas/math.json");
        return mathAtlas;
    }

    /** Typeset an {@code AlgExpr} AST to an SVG string. Package-visible test seam. */
    static String mathSvg(RecordValue ast) {
        LaidOut laid = new MathLayout(mathAtlas(), MathConstants.stixTwoMath())
                .layout(algExprToMathBox(ast));
        return MathSvg.write(laid, 48.0);
    }

    /**
     * {@code exportMathSvg(e)} (pontif.plot): typeset an algebraic expression's AST to a semantic
     * SVG and pop a native Save dialog to write it — the math sibling of {@link #exportSvg}. Wire it
     * to a button, or call it for-effect. A cancelled dialog is a no-op.
     */
    public static Object exportMathSvg(List<Object> args, NativeCalls.Context ctx) {
        if (args.isEmpty() || !(args.get(0) instanceof RecordValue ast)) {
            System.err.println("exportMathSvg: expected an AlgExpr AST.");
            return new IrInterpreter.DriveResult();
        }
        String svg = mathSvg(ast);
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

    /**
     * Converts a Pontif numeric aggregate (a {@code _tuple} RecordValue whose members are Pontif
     * Int/Decimal scalars) to a {@code double[]} in member order — the data marshalling across the
     * native boundary (only primitives cross). Non-record or non-numeric members yield 0.0.
     */
    static double[] doubles(Object value) {  // package-private: unit-tested in DasumBridgeTest
        if (!(value instanceof RecordValue rv)) return new double[0];
        double[] out = new double[rv.members().size()];
        int i = 0;
        for (Object member : rv.members().values()) out[i++] = toDouble(member);
        return out;
    }

    /**
     * Flattens a Pontif aggregate of {@code {x,y,z}} triples (a {@code _tuple} whose members are
     * each a {@code _tuple} of three numeric scalars) to a row-major {@code float[]} of length
     * {@code 3*N} — the marshalling for {@link #renderCloud}. Missing coordinates default to 0.
     */
    static float[] xyzTriples(Object value) {  // package-private: unit-tested in PlotExtensionTest
        if (!(value instanceof RecordValue rv)) return new float[0];
        float[] out = new float[rv.members().size() * 3];
        int p = 0;
        for (Object point : rv.members().values()) {
            if (point instanceof RecordValue pr) {
                int j = 0;
                for (Object coord : pr.members().values()) {
                    if (j < 3) out[p * 3 + j] = (float) toDouble(coord);
                    j++;
                }
            }
            p++;
        }
        return out;
    }

    private static double toDouble(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Integer n) return n;
        if (o instanceof BigDecimal d) return d.doubleValue();
        return 0.0;
    }

    /** A config field as a String, or "" — {@code args[i]} is the config record {field = …}. */
    private static String cfgStr(List<Object> args, int i, String field) {
        return i < args.size() && args.get(i) instanceof RecordValue rv ? str(rv, field) : "";
    }

    private static String str(RecordValue rv, String field) {
        return rv.members().get(field) instanceof StringValue s ? s.content() : "";
    }

    private static String bareType(String typeName) {
        if (typeName == null) return "";
        int slash = typeName.lastIndexOf('/');
        return slash < 0 ? typeName : typeName.substring(slash + 1);
    }

    private static RecordValue emptyTuple() {
        return new RecordValue("_tuple", new LinkedHashMap<>());
    }

    private static JustifyContent justify(String s) {
        return switch (s) {
            case "center" -> JustifyContent.CENTER;
            case "end" -> JustifyContent.END;
            default -> JustifyContent.START;
        };
    }

    private static AlignItems align(String s) {
        return switch (s) {
            case "center", "middle" -> AlignItems.CENTER;
            case "end" -> AlignItems.END;
            case "stretch" -> AlignItems.STRETCH;
            default -> AlignItems.START;
        };
    }
}
