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
import sibarum.dasum.gui.core.window.Window;
import sibarum.dasum.gui.natives.gl.Gl;
import sibarum.dasum.gui.natives.glfw.Glfw;
import sibarum.dasum.gui.natives.glfw.GlfwCallbacks;
import sibarum.dasum.gui.vis.DasumVis;
import sibarum.dasum.gui.vis.plot.Axis;
import sibarum.dasum.gui.vis.plot.LinePlot;
import sibarum.dasum.gui.vis.plot.PlotStyle;
import sibarum.dasum.gui.vis.plot.PlotView;
import sibarum.dasum.gui.vis.plot.Series;
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
import sibarum.dasum.gui.vis.scene.SceneSnapshot;
import sibarum.dasum.gui.vis.scene.SceneStates;
import sibarum.dasum.gui.vis.scene.TextLayer;
import sibarum.dasum.gui.vis.scene.TriangleLayer;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return openWindowWithRoot(title, () -> toComponent(rootTree, ctx));
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
        return openWindowWithRoot("Plot", () -> buildLinePlotView(xs, ys));
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
        List<Series> series = buildChartSeries(layers);
        return openWindowWithRoot(title, () -> chartComponent(series));
    }

    /**
     * {@code renderCloud(points)} (pontif.plot): opens an orbitable 3D window showing a point
     * cloud. {@code points} is an aggregate of {@code {x,y,z}} triples shaped in Pontif
     * (pontif.plot's {@code plotCloud}); this native flattens it to {@code float[]} and renders.
     */
    public static Object renderCloud(List<Object> args, NativeCalls.Context ctx) {
        float[] xyz = !args.isEmpty() ? xyzTriples(args.get(0)) : new float[0];
        return openWindowWithRoot("Cloud", () -> buildCloudView(xyz));
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
        return openWindowWithRoot("Surface", () -> buildSurfaceView(zs, xlo, xhi, ylo, yhi));
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
        SceneBuild build = buildSceneLayers(layers);
        return openWindowWithRoot(title, () -> sceneComponent(build, axes, grid));
    }

    private static double arg(List<Object> args, int i) {
        return i < args.size() ? toDouble(args.get(i)) : 0.0;
    }

    /**
     * Opens a window on the calling (root) thread, builds its root via {@code rootFactory}
     * <b>after</b> GL + font setup (so styled / plotted components resolve correctly), then renders
     * in the loop until the window closes. Shared by the declarative-UI window ({@link #openWindow})
     * and the plot window ({@link #renderCurve}). Returns the inert for-effect result.
     */
    private static Object openWindowWithRoot(String title, java.util.function.Supplier<Component> rootFactory) {
        try (GlfwContext glfw = GlfwContext.init();
             Window win = Window.create(WIDTH, HEIGHT, title);
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

            // Build components after font + Em setup, so styled widgets resolve correctly.
            Component root = rootFactory.get();
            wireInput();

            EventLoop loop = new EventLoop(win, () -> {
                int fbW = win.framebufferWidth();
                int fbH = win.framebufferHeight();
                float[] projection = Projection.orthoTopLeft(fbW, fbH);
                Gl.glViewport(0, 0, fbW, fbH);
                Gl.glClearColor(BACKGROUND.r(), BACKGROUND.g(), BACKGROUND.b(), BACKGROUND.a());
                Gl.glClear(Gl.GL_COLOR_BUFFER_BIT);
                LayoutResult layout = Layout.compute(root, new PixelRect(0f, 0f, fbW, fbH));
                LatestLayout.store(root, layout);  // required so hit-testing has coordinates
                batcher.beginFrame(fbH);
                Render.render(root, layout, batcher, projection);
                batcher.endFrame(projection);
            });
            loop.run();  // blocks on this (root) thread until the window is closed
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
            case "Column" -> new Component.Flex(
                    null, null, Em.of(0.5f), TRANSPARENT,
                    Direction.COLUMN, justify(str(rv, "justify")), align(str(rv, "align")), Em.of(0.8f),
                    childrenOf(rv, ctx), false, 1);
            case "LinePlot" -> buildLinePlot(rv);
            // A bare children aggregate (window's root arg) → an implicit centered column.
            case "_tuple" -> new Component.Flex(
                    null, null, Em.of(0.5f), TRANSPARENT,
                    Direction.COLUMN, JustifyContent.CENTER, AlignItems.CENTER, Em.of(0.8f),
                    tupleToComponents(rv, ctx), false, 1);
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

    /** Turn a {@code {layers}} tuple of {@code Curve} records into palette-coloured line series.
     *  Package-visible: the headless test seam for {@link #renderChart}. */
    static List<Series> buildChartSeries(Object layersValue) {
        List<Series> out = new ArrayList<>();
        if (layersValue instanceof RecordValue tuple) {
            for (Object member : tuple.members().values()) {
                if (member instanceof RecordValue rv && "Curve".equals(bareType(rv.typeName()))) {
                    double[] xs = doubles(rv.members().get("xs"));
                    double[] ys = doubles(rv.members().get("ys"));
                    out.add(Series.line(xs, ys, SERIES_PALETTE[out.size() % SERIES_PALETTE.length]));
                }
            }
        }
        return out;
    }

    /**
     * The shared 2D line-chart component: a {@link Component.SceneView} carrying the given
     * {@link Series} published through a {@link PlotView}, axes auto-ranged over ALL series, with
     * gridlines + tick labels (from {@link PlotStyle#defaults()}), pan/zoom enabled. Used by the
     * {@code LinePlot} element, the {@code plotLine} sampler, and the composed {@code chart}.
     * {@code DasumVis.init()} must have run first ({@link #openWindowWithRoot} ensures it).
     */
    static Component chartComponent(List<Series> series) {
        Component.SceneView view =
                new Component.SceneView(Em.of(22f), Em.of(12f), Em.ZERO, PLOT_BG, true, 1);
        new PlotView(view).showLinePlot(
                LinePlot.autoFrame(0f, 0f, 10f, 5.5f, series), series, PlotStyle.defaults());
        SceneStates.setInteraction(view, InteractionSpec.panZoom2d());
        return view;
    }

    /**
     * A 3D point cloud: a {@link Component.SceneView} carrying a single {@link PointLayer},
     * a perspective camera, and orbit/zoom interaction (drag orbits, scroll zooms — wired in
     * {@link #wireInput}). {@code DasumVis.init()} must have run first ({@link #openWindowWithRoot}
     * ensures it).
     */
    static Component buildCloudView(float[] xyz) {
        Component.SceneView view =
                new Component.SceneView(Em.of(24f), Em.of(16f), Em.ZERO, PLOT_BG, true, 1);
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
        Component.SceneView view =
                new Component.SceneView(Em.of(26f), Em.of(18f), Em.ZERO, PLOT_BG, true, 1);
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

    /** The scene component: one {@link Component.SceneView} carrying all layers (plus the axis box
     *  when {@code axes}), framed to bounds. */
    private static Component sceneComponent(SceneBuild build, boolean axes, boolean grid) {
        List<Layer> layers = new ArrayList<>(build.layers());
        if (axes) layers.addAll(axisBoxLayers(build.min(), build.max(), grid));
        Component.SceneView view =
                new Component.SceneView(Em.of(26f), Em.of(18f), Em.ZERO, PLOT_BG, true, 1);
        SceneStates.publish(view, new SceneSnapshot(layers));
        SceneStates.setCamera(view,
                CameraRig.fitToBounds(CameraSpec.defaultPerspective(), build.min(), build.max()));
        SceneStates.setInteraction(view, InteractionSpec.defaults());  // ORBIT_3D
        if (build.bar() == null) return view;
        // Colorbar key beside the scene (reuses component composition, not a second camera).
        return new Component.Flex(null, null, Em.of(0.6f), TRANSPARENT,
                Direction.ROW, JustifyContent.CENTER, AlignItems.CENTER, Em.of(1f),
                List.of(view, colorbar(build.bar())), false, 1);
    }

    /** A vertical colorbar strip (high at top) for {@code bar}'s colormap, with min/max labels. */
    private static Component colorbar(Bar bar) {
        int steps = 24;
        List<Component> col = new ArrayList<>();
        col.add(new Component.Text(fmtNum(bar.hi()), Em.of(0.85f), TEXT));
        for (int i = steps - 1; i >= 0; i--) {           // top row = highest value
            float[] c = colorFor(bar.colormap(), i / (float) (steps - 1));
            col.add(new Component.Flex(Em.of(1.5f), Em.of(0.32f), Em.ZERO, new Color(c[0], c[1], c[2], 1f),
                    Direction.COLUMN, JustifyContent.CENTER, AlignItems.CENTER, Em.ZERO,
                    List.of(), false, 0));
        }
        col.add(new Component.Text(fmtNum(bar.lo()), Em.of(0.85f), TEXT));
        return new Component.Flex(null, null, Em.of(0.4f), TRANSPARENT,
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

    /** A struct field as a double (Int/Decimal scalar), or 0.0. */
    private static double memberD(RecordValue rv, String field) {
        return toDouble(rv.members().get(field));
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
