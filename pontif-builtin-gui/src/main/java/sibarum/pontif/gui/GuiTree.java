package sibarum.pontif.gui;

import sibarum.dasum.gui.core.GlfwContext;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.em.EmContext;
import sibarum.dasum.gui.core.event.EventLoop;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.input.FocusState;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.HoverState;
import sibarum.dasum.gui.core.input.InputState;
import sibarum.dasum.gui.core.input.TextInputController;
import sibarum.dasum.gui.core.layout.HitTest;
import sibarum.dasum.gui.core.layout.LatestLayout;
import sibarum.dasum.gui.core.layout.Layout;
import sibarum.dasum.gui.core.layout.LayoutResult;
import sibarum.dasum.gui.core.layout.PixelRect;
import sibarum.dasum.gui.core.layout.Render;
import sibarum.dasum.gui.core.render.Batcher;
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
import sibarum.dasum.gui.vis.pointcloud.SceneViewController;
import sibarum.dasum.gui.vis.render.BloomPass;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static sibarum.pontif.gui.ChartBuilder.*;
import static sibarum.pontif.gui.GuiShared.*;

/**
 * The declarative UI (docs/extensions.md, G5): walks a Pontif element record tree into dasum
 * components ({@link #toComponent}), opens the window and renders it on the root thread until closed
 * ({@link #openWindowWithRoot}), and wires the minimal cursor/click/keyboard/scene input pipeline
 * ({@link #wireInput}). Split out of the former god-class {@code DasumBridge}.
 */
final class GuiTree {
    private GuiTree() {}

    /** The component captured on mouse-down, to confirm the release lands on the same one. */
    private static Component pressTarget;

    /**
     * The retained-tree widget registry (docs/reactive-gui.md). dasum is retained-mode: the tree is
     * built ONCE and never rebuilt — updates are ISOLATED and addressed by widget id. {@link
     * #toComponent} records each id'd widget here as it builds; a targeted update command (e.g.
     * {@code SetText(id, …)} → {@link #setText}) looks the component up and mutates it through dasum's
     * blessed identity-keyed API + {@code Invalidator}. We do NOT rebuild the tree (that would orphan
     * the per-component caret/selection/scroll/undo state dasum keys on Component identity). One
     * window runs at a time (the loop blocks the root thread), so a single static registry suffices;
     * it is cleared on window open/close.
     */
    private static final java.util.Map<String, Component> widgets = new java.util.HashMap<>();

    /** Record an id'd widget as the tree is built (empty id = not addressable; last-wins per build). */
    private static void register(String id, Component c) {
        if (id != null && !id.isEmpty()) widgets.put(id, c);
    }

    /**
     * Retained expr-plot widgets ({@code ExprPlot}) addressable by id for the {@code SetPlot} command.
     * Kept separate from {@link #widgets} because a plot update re-publishes scene data to a retained
     * {@link sibarum.dasum.gui.vis.plot.PlotView} (never a rebuild), and the reliable sampler needs the
     * {@code ctx} captured when the plot was built. Cleared with the window like {@link #widgets}.
     */
    private static final java.util.Map<String, PlotEntry> plots = new java.util.HashMap<>();
    private record PlotEntry(sibarum.dasum.gui.vis.plot.PlotView view, NativeCalls.Context ctx) {}

    /**
     * Apply an isolated expr-plot update (the {@code pontif.gui/SetPlot} sink): re-plot the retained
     * SceneView with this id from the expression string, IN PLACE (no rebuild — the camera survives).
     * Unparseable/half-typed text keeps the last good plot ({@link ChartBuilder#plotExprInto} returns
     * false and leaves it). An unknown id is a no-op logged to StdErr.
     */
    static void setPlot(String id, Object exprs) {
        PlotEntry e = plots.get(id);
        if (e == null) {
            System.err.println("SetPlot: no plot widget with id '" + id + "' in the current window");
            return;
        }
        ChartBuilder.plotExprInto(e.view(), exprs, e.ctx());
    }

    /**
     * Apply an isolated status update (the {@code pontif.gui/Status} sink): surface a message on the
     * retained status ribbon (docs/status.md) — a ledger entry plus a brief, faintly-tinted alert.
     * {@code kind} maps to the ledger's severity: "good"/"bad" → good/bad alerts, else a neutral
     * notice. Never a popup.
     */
    static void status(String text, String kind) {
        switch (kind == null ? "" : kind) {
            case "good" -> sibarum.dasum.gui.core.status.Status.good(text);
            case "bad"  -> sibarum.dasum.gui.core.status.Status.bad(text);
            default      -> sibarum.dasum.gui.core.status.Status.notify(text);
        }
    }

    /**
     * Apply an isolated text update to a retained widget (the {@code pontif.gui/SetText} sink): set
     * the component's content through dasum {@link TextStates}, which mutates the identity-keyed
     * sidecar and invalidates — NO rebuild. An unknown id is a no-op logged to StdErr (a likely typo
     * in the update command, surfaced rather than swallowed).
     */
    static void setText(String id, String text) {
        if (!(widgets.get(id) instanceof Component.Text t)) {
            System.err.println("SetText: no text widget with id '" + id + "' in the current window");
            return;
        }
        TextStates.setContent(t, text);
    }

    /**
     * Recursively turns an element record (or a {@code _tuple} of them) into a dasum component.
     * The heart of the declarative UI: it switches on the record's (bare) type name. An unknown
     * node renders as a visible error label rather than failing silently.
     */
    static Component toComponent(Object value, NativeCalls.Context ctx) {
        if (!(value instanceof RecordValue rv)) {
            return errorLabel("not a component: " + value);
        }
        // A user widget: any value whose type satisfies Clickable renders as a button whose click
        // invokes its own onClick method (which emits). This is how per-element behavior lives —
        // the user subtypes Button and assigns Clickable with an onClick (docs/extensions.md G6).
        // The trait registry keys on the fully-qualified trait name (pontif.gui/Clickable).
        if (ctx.satisfies(rv, "pontif.gui/Clickable")) {
            Component c = Themed.button(str(rv, "text"), Em.of(10f), Variant.PRIMARY, 0,
                    () -> ctx.invoke(rv, "onClick"));
            register(str(rv, "id"), c);  // a Clickable subtypes Button, so it carries an id
            return c;
        }
        return switch (bareType(rv.typeName())) {
            case "Label" -> {
                Component c = Ui.text(str(rv, "text")).size(Em.of(2f)).color(TEXT).build();
                register(str(rv, "id"), c);  // addressable for SetText(id, …)
                yield c;
            }
            case "TextField" -> {
                String id = str(rv, "id");
                // Uncontrolled editable field (docs/reactive-gui.md §7), built through the blessed
                // Ui.text().editable() path — NOT the raw constructor. editable() forces
                // interactive+selectable on (the caret pipeline needs both) and leaves acceptsTab
                // false, so Tab cycles focus rather than inserting a tab — right for a single-line
                // field. width() gives it a stable, clickable extent even while empty (an empty
                // editable Text has no glyphs; editable() alone would fall back to the builder's
                // anti-collapse default width, but we want a wider field here).
                Component.Text field = (Component.Text) Ui.text(str(rv, "text"))
                        .size(Em.of(2f)).color(TEXT).editable().width(Em.of(18f)).build();
                // Register the FINAL instance: withEditable/withWidth each return a NEW record, and
                // TextStates / FocusState are identity-keyed, so both the SetText registry entry and
                // the onContentChange listener must key on the exact instance placed in the tree.
                register(id, field);
                // Fire TextChanged{id, text} on every edit — the inbound-emit door for typing. The
                // app conduit folds it (parse/eval) and drives OTHER widgets; it never writes back
                // here (uncontrolled). onContentChange also fires on programmatic setContent, so do
                // not SetText this same field from the conduit (feedback loop; §7 cautions).
                TextStates.onContentChange(field, s ->
                        ctx.fireEvent(element("pontif.gui/TextChanged", "id", id, "text", s)));
                // Auto-focus the FIRST editable field built, so the caret shows and the user can type
                // immediately without a click first (tightening the idea→result loop). Only if nothing
                // else has grabbed focus yet — a later field or a click still wins.
                if (FocusState.focused() == null) FocusState.set(field);
                // A bare Text draws no fill, so wrap it in a Ui.column() frame — rounded + bordered.
                // (This used to hide the caret/selection because the old Batcher drew all rounded
                // fills over all flat fills; the unified single-stream renderer preserves painter's
                // order, so the frame no longer occludes them.) The interactive Text stays the
                // hit-test/focus/SetText target (the column is non-interactive; HitTest returns the
                // deepest INTERACTIVE node).
                yield Ui.column().padding(Em.of(0.4f)).background(FIELD_BG)
                        .border(Em.of(0.1f), FIELD_BORDER).cornerRadius(Em.of(0.3f))
                        .add(field).build();
            }
            case "Button" -> {
                String id = str(rv, "id");
                // The inbound-emit door (docs/reactive-gui.md, G2): a click fires a `Clicked`
                // notification tagged with the button's stable id. The app's GuiEvent conduit folds
                // it into the model and emits isolated update commands (e.g. SetText); no rebuild.
                Component c = Themed.button(str(rv, "text"), Em.of(10f), Variant.PRIMARY, 0,
                        () -> ctx.fireEvent(element("pontif.gui/Clicked", "id", id)));
                register(id, c);
                yield c;
            }
            // Ui.column() defaults (align=STRETCH) make children fill the cross axis, so a nested
            // plot/scene resolves instead of collapsing; the user's justify/align still apply. grow(1)
            // lets a column-in-a-column take vertical space.
            case "Column" -> Ui.column()
                    .padding(Em.of(0.5f)).gap(Em.of(0.8f)).grow(1)
                    .justify(justify(str(rv, "justify"))).align(align(str(rv, "align")))
                    .addAll(childrenOf(rv, ctx)).build();
            case "LinePlot" -> buildLinePlot(rv);
            // A RETAINED, reactive expression plot (docs/reactive-gui.md, Slice A): a SceneView drawn
            // from `expr` and updatable in place via SetPlot(id, expr). Registered in `plots` (not
            // `widgets`) with the build ctx, which the reliable sampler needs. Initial draw here;
            // later edits re-publish without rebuilding (the camera survives).
            case "ExprPlot" -> {
                String id = str(rv, "id");
                // Build the SceneView with an explicit grow so it fills its backing panel below.
                Component.SceneView view = (Component.SceneView) Ui.sceneView()
                        .background(PLOT_BG).grow(1).build();
                // Uniform (aspect-preserving) camera for now: fill mode (fillViewport(true)) stretches
                // the world-space axis labels + gridlines and skews them. True fill without skew needs
                // screen-space chrome (labels/ticks/grid at fixed pixel size) — a dasum-vis slice.
                sibarum.dasum.gui.vis.plot.PlotView pv =
                        new sibarum.dasum.gui.vis.plot.PlotView(view);
                plots.put(id, new PlotEntry(pv, ctx));
                ChartBuilder.plotExprInto(pv, str(rv, "expr"), ctx);  // no-op if blank/unparseable
                // A padded backing panel (solid PLOT_BG) around the plot: the SceneView no longer runs
                // flush to the window edges (where axis labels / the curve were getting cropped) — it
                // sits inside a defined panel with a margin. fill()+grow(1) so the panel still fills
                // the window; the SceneView grows within, inset by the padding.
                yield Ui.column().fill().grow(1).background(PLOT_BG).padding(Em.of(0.8f)).add(view).build();
            }
            // An embeddable annotated chart (pontif.plot chartView): the same reliable/annotated
            // chart `chart(...)` opens standalone, but as a component so it can sit in a layout
            // beside a user Button whose onClick calls exportSvg on the same layers.
            case "ChartView" -> annotatedChartComponent(buildAnnotatedChart(rv.members().get("layers"), ctx));
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
    static void wireInput() {
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
                // Hit-test the LIVE tree at the cursor — do NOT trust the hover cache. A reactive
                // re-render (a Draw repaint) swaps the whole Component tree, but HoverState only
                // refreshes on cursor MOVE; a second click at the same spot would otherwise arm a
                // stale (dead) component that can never match the release's fresh hit-test, so the
                // click silently no-ops until the mouse moves. Probing the current layout here (as
                // the release branch does) makes a click register regardless of movement or repaint;
                // we refresh HoverState from the same probe so the cache stays consistent.
                LayoutResult lr = LatestLayout.result();
                Component r = LatestLayout.root();
                Component hit = (lr != null && r != null)
                        ? HitTest.test(r, lr, (float) InputState.mouseX(), (float) InputState.mouseY())
                        : null;
                HoverState.update(hit);
                // THE FOCUS GAP (docs/reactive-gui.md §7): onMouseDown places the caret but does NOT
                // set focus, and every char/key handler early-returns unless FocusState.focused() is
                // an editable Text — so keystrokes are silently swallowed until focus is set. Set it
                // here when the press lands on an editable Text. (The counter never needed this.)
                if (hit instanceof Component.Text t && t.editable()) FocusState.set(hit);
                // Focus an editable Text under the cursor (caret placement / selection start).
                TextInputController.onMouseDown(hit, InputState.mouseX(),
                        InputState.mouseY(), (mods & Glfw.GLFW_MOD_SHIFT) != 0);
                // Let a viewport claim the press first; if it does, don't also arm a button click.
                boolean scene = SceneViewController.onMouseDown(
                        hit, InputState.mouseX(), InputState.mouseY());
                pressTarget = scene ? null : hit;
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
        // Keyboard → the focused editable Text: typed characters, and edit keys (backspace, arrows,
        // Ctrl+A, …) on press/repeat. This is what makes an input field actually accept input.
        GlfwCallbacks.setCharListener((w, cp) -> TextInputController.onCharInput(cp));
        GlfwCallbacks.setKeyListener((w, key, sc, action, mods) -> {
            if (action != Glfw.GLFW_PRESS && action != Glfw.GLFW_REPEAT) return;
            boolean shift = (mods & Glfw.GLFW_MOD_SHIFT) != 0;
            boolean ctrl = (mods & Glfw.GLFW_MOD_CONTROL) != 0;
            // Editing keys and clipboard are SEPARATE entry points from onKey (which only does
            // caret navigation), so dispatch them explicitly — first match wins. Without this an
            // editable Text accepts typed chars but not backspace/delete/enter.
            MemorySegment win = MemorySegment.ofAddress(w);       // clipboard calls take the GLFW handle
            // GLFW letter keycodes match ASCII uppercase ('A'..'Z' = 65..90).
            if (ctrl && key == 'A' && TextInputController.onSelectAll()) return;
            if (ctrl && key == 'C' && TextInputController.onCopy(win)) return;
            if (ctrl && key == 'X' && TextInputController.onCut(win)) return;
            if (ctrl && key == 'V' && TextInputController.onPaste(win)) return;
            if (key == Glfw.GLFW_KEY_BACKSPACE && TextInputController.onBackspace(ctrl)) return;
            if (key == Glfw.GLFW_KEY_DELETE && TextInputController.onDelete(ctrl)) return;
            if (key == Glfw.GLFW_KEY_ENTER && TextInputController.onEnter()) return;
            if (key == Glfw.GLFW_KEY_TAB && TextInputController.onTab()) return;
            TextInputController.onKey(key, shift, ctrl);           // arrows / Home / End
        });
    }

    private static List<Component> childrenOf(RecordValue column, NativeCalls.Context ctx) {
        return column.members().get("children") instanceof RecordValue kids
                ? tupleToComponents(kids, ctx) : new ArrayList<>();
    }

    private static List<Component> tupleToComponents(RecordValue tuple, NativeCalls.Context ctx) {
        List<Component> out = new ArrayList<>();
        for (Object child : tuple.members().values()) out.add(toComponent(child, ctx));
        return out;
    }

    /**
     * Builds a 2D line chart (dasum-vis) from a {@code LinePlot(xs, ys)} element: converts the two
     * numeric aggregates to {@code double[]} and delegates to the shared line-plot component.
     */
    private static Component buildLinePlot(RecordValue rv) {
        return buildLinePlotView(doubles(rv.members().get("xs")), doubles(rv.members().get("ys")));
    }

    /**
     * Opens a window on the calling (root) thread, builds its root via {@code rootFactory}
     * <b>after</b> GL + font setup (so styled / plotted components resolve correctly), then renders
     * in the loop until the window closes. Shared by every GUI/plot window. Returns the inert
     * for-effect result.
     */
    static Object openWindowWithRoot(String title, boolean enableBloom,
            java.util.function.Supplier<Component> rootFactory) {
        return openWindowWithRoot(title, WIDTH, HEIGHT, enableBloom, rootFactory);
    }

    /** As {@link #openWindowWithRoot(String, boolean, java.util.function.Supplier)}, at an explicit
     *  window size — the {@code width}/{@code height} cfg keys flow here; the cfg-less render natives
     *  use the {@link GuiShared#WIDTH}×{@link GuiShared#HEIGHT} default overload. */
    static Object openWindowWithRoot(String title, int width, int height, boolean enableBloom,
            java.util.function.Supplier<Component> rootFactory) {
        return openWindowCore(title, width, height, enableBloom, rootFactory);
    }

    /**
     * The <b>reactive</b> window (docs/reactive-gui.md): builds the retained tree ONCE from the
     * {@code window(...)} children and renders it until closed. The tree is never rebuilt —
     * interactivity is isolated updates: a click fires a {@code Clicked} notification, the app's
     * conduit folds it and emits targeted commands ({@code SetText(id, …)}) that mutate specific
     * retained widgets through dasum's identity-keyed stores + {@code Invalidator}, which wakes the
     * loop to repaint. {@code ctx} is threaded from {@link DasumBridge#openWindow} so the build (and
     * the click handlers' {@code fireEvent}) run on the root thread. The widget registry is scoped to
     * this window (cleared on open and close).
     */
    static Object openWindow(String title, int width, int height,
            RecordValue tree, NativeCalls.Context ctx) {
        widgets.clear();
        plots.clear();
        try {
            // Wrap the app's tree in the status ribbon (docs/status.md): a reactive window gets the
            // ledger at the bottom for free, and `emit Status(...)` from the conduit surfaces there.
            return openWindowCore(title, width, height, false,
                    () -> sibarum.dasum.gui.core.status.Status.wrap(toComponent(tree, ctx)));
        } finally {
            widgets.clear();
            plots.clear();
        }
    }

    /**
     * The shared window core. Builds the root ONCE via {@code rootFactory} after GL + font setup,
     * then renders that retained root in the loop until the window closes (the loop re-renders only
     * on a dirty frame; targeted updates flip the dirty bit via {@code Invalidator}). Shared by the
     * reactive GUI and the build-once plot/scene windows alike.
     */
    private static Object openWindowCore(String title, int width, int height, boolean enableBloom,
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

            // Build the retained root once after font + Em setup, so styled widgets resolve
            // correctly. It is never rebuilt — updates mutate its widgets in place (see setText).
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
}
