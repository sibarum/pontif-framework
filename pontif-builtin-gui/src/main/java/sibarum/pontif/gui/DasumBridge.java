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
import sibarum.dasum.gui.vis.plot.LinePlot;
import sibarum.dasum.gui.vis.plot.PlotStyle;
import sibarum.dasum.gui.vis.plot.PlotView;
import sibarum.dasum.gui.vis.plot.Series;
import sibarum.dasum.gui.vis.math.CameraRig;
import sibarum.dasum.gui.vis.math.CameraSpec;
import sibarum.dasum.gui.vis.math.Vec3;
import sibarum.dasum.gui.vis.pointcloud.SceneViewController;
import sibarum.dasum.gui.vis.scene.BlendMode;
import sibarum.dasum.gui.vis.scene.InteractionSpec;
import sibarum.dasum.gui.vis.scene.PointLayer;
import sibarum.dasum.gui.vis.scene.SceneSnapshot;
import sibarum.dasum.gui.vis.scene.SceneStates;
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
        Component.SceneView view =
                new Component.SceneView(Em.of(22f), Em.of(12f), Em.ZERO, PLOT_BG, true, 1);
        List<Series> series = List.of(Series.line(xs, ys, SERIES_COLOR));
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
        int n = (int) Math.round(Math.sqrt(zs.length));
        if (n < 2) return errorLabel("surface needs an N*N grid (N>=2); got " + zs.length + " heights");

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
                emitSurfaceVerts(verts, cols, o, n, xlo, ylo, sx, sy, zs, zmin, zspan, i00, i10, i11);
                emitSurfaceVerts(verts, cols, o, n, xlo, ylo, sx, sy, zs, zmin, zspan, i00, i11, i01);
            }
        }

        Component.SceneView view =
                new Component.SceneView(Em.of(26f), Em.of(18f), Em.ZERO, PLOT_BG, true, 1);
        // OPAQUE (not the TriangleLayer 2-arg default of ALPHA): the surface is solid, so it must
        // WRITE the depth buffer. An ALPHA layer has depth writes disabled in SceneRenderer, which
        // leaves the surface rendering in submission order — far triangles bleed through near ones.
        SceneStates.publish(view,
                SceneSnapshot.of(new TriangleLayer(verts, cols).withBlend(BlendMode.OPAQUE)));
        SceneStates.setCamera(view, CameraRig.fitToBounds(CameraSpec.defaultPerspective(),
                new Vec3((float) xlo, (float) zmin, (float) ylo),
                new Vec3((float) xhi, (float) zmax, (float) yhi)));
        SceneStates.setInteraction(view, InteractionSpec.defaults());  // ORBIT_3D
        return view;
    }

    /** Appends the given grid vertices (by index) as world (x, height, y) positions + height colour. */
    private static void emitSurfaceVerts(float[] verts, float[] cols, int[] o, int n,
            double xlo, double ylo, double sx, double sy, double[] zs, double zmin, double zspan,
            int... indices) {
        for (int idx : indices) {
            double x = xlo + (idx % n) * sx, z = ylo + (idx / n) * sy, h = zs[idx];
            verts[o[0]] = (float) x;
            verts[o[0] + 1] = (float) h;     // height is the up axis
            verts[o[0] + 2] = (float) z;
            float t = (float) ((h - zmin) / zspan);
            cols[o[0]] = t;                  // blue (low) → red (high)
            cols[o[0] + 1] = 0.5f;
            cols[o[0] + 2] = 1f - t;
            o[0] += 3;
        }
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
