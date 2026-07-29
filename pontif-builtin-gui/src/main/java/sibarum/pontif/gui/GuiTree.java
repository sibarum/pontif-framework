package sibarum.pontif.gui;

import sibarum.dasum.gui.core.GlfwContext;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.em.EmContext;
import sibarum.dasum.gui.core.event.EventLoop;
import sibarum.dasum.gui.core.event.Invalidator;
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
     * The reactive host (docs/reactive-gui.md §6, G3). The {@code Draw} native sink has no
     * {@code ctx} and must not build components — it only stashes the raw Pontif element tree here
     * (the "pending root") + marks it dirty and wakes the loop. The window loop (running on the root
     * thread) rebuilds the dasum tree from {@link #pendingTree} on the next dirty frame. Only ONE
     * window runs at a time (the loop blocks the root thread), so a single static holder suffices; it
     * is cleared when the window closes. {@code volatile} because a worker thread may {@code emit}
     * Draw in a later slice — today the emit is on the root thread and {@link Invalidator} handles the
     * cross-thread wake either way.
     */
    private static volatile RecordValue pendingTree;
    private static volatile boolean pendingDirty;

    /**
     * The render-sink seam ({@code pontif.gui/Draw} effect → here): stash the new element tree, mark
     * it dirty, and wake the event loop. Called from {@code fireEvent}'s effect dispatch — no GL, no
     * component building (that happens on the root thread in the window loop).
     */
    static void publish(RecordValue tree) {
        pendingTree = tree;
        pendingDirty = true;
        Invalidator.invalidate();
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
            return Themed.button(str(rv, "text"), Em.of(10f), Variant.PRIMARY, 0,
                    () -> ctx.invoke(rv, "onClick"));
        }
        return switch (bareType(rv.typeName())) {
            case "Label" -> new Component.Text(str(rv, "text"), Em.of(2f), TEXT);
            case "Button" -> {
                String t = str(rv, "text");
                // The inbound-emit door (docs/reactive-gui.md, G2): a click fires a `Clicked`
                // notification tagged with the button's text as its id. The app's GuiEvent conduit
                // folds it into the model and re-emits Draw; see publish() / the reactive host.
                yield Themed.button(t, Em.of(10f), Variant.PRIMARY, 0,
                        () -> ctx.fireEvent(element("pontif.gui/Clicked", "id", t)));
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
        return openWindowCore(title, width, height, enableBloom, rootFactory, null);
    }

    /**
     * The <b>reactive</b> window (docs/reactive-gui.md, Slice 1): opens a window whose root is
     * rebuilt from the pending Pontif tree whenever a {@code Draw} sink publishes a new one. The
     * initial tree ({@code view(initialModel)}, the {@code window(...)} children arg) seeds the
     * pending root so the first frame renders it; thereafter {@code emit Draw(view(model))} from the
     * app's conduit drives repaints. The {@code ctx} is threaded in from {@link DasumBridge#openWindow}
     * so the loop can call {@link #toComponent} on the root thread (the only thread allowed to touch
     * GL). The static pending-root holder is cleared when the window closes.
     */
    static Object openWindowReactive(String title, int width, int height,
            RecordValue initialTree, NativeCalls.Context ctx) {
        pendingTree = initialTree;
        pendingDirty = false;  // the initial factory below builds initialTree; Draw marks it dirty
        java.util.function.Supplier<Component> factory = () -> toComponent(initialTree, ctx);
        // The per-frame rebuild hook: rebuild only when a Draw has published a new tree.
        java.util.function.Supplier<Component> rebuild = () -> {
            if (!pendingDirty) return null;
            pendingDirty = false;
            RecordValue t = pendingTree;
            return t == null ? null : toComponent(t, ctx);
        };
        try {
            return openWindowCore(title, width, height, false, factory, rebuild);
        } finally {
            pendingTree = null;
            pendingDirty = false;
        }
    }

    /**
     * The shared window core. Builds the initial root via {@code rootFactory} after GL + font setup,
     * then renders in the loop until the window closes. When {@code rebuild} is non-null (the reactive
     * host), each frame first asks it for a new root; a non-null answer replaces + re-lints the root
     * (this is how a published {@code Draw} tree repaints). Static plot/GUI windows pass
     * {@code rebuild == null} and render their build-once root forever.
     */
    private static Object openWindowCore(String title, int width, int height, boolean enableBloom,
            java.util.function.Supplier<Component> rootFactory,
            java.util.function.Supplier<Component> rebuild) {
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

            // Build components after font + Em setup, so styled widgets resolve correctly. Held in a
            // one-cell array so the reactive rebuild hook can swap it in-place inside the loop.
            Component[] root = { rootFactory.get() };
            // Layout guardrail (docs/plotting.md): lint the built tree BEFORE rendering. Fonts are
            // registered and Em is set, so the geometry pass can lay it out — a collapsed plot/scene
            // (the plot-in-a-column trap) throws here with a fix hint instead of rendering blank.
            Ui.lint(root[0]);
            wireInput();

            // 3D plot windows opt into HDR + bloom: the frame renders into an offscreen HDR target,
            // then bright-pass/blur/tonemap composites to the screen (the emissive glow blooms).
            BloomPass bloom = enableBloom ? new BloomPass() : null;
            if (bloom != null) bloom.init();

            EventLoop loop = new EventLoop(win, () -> {
                // Reactive rebuild: if a Draw sink published a new tree since last frame, rebuild the
                // dasum root from it (root thread, GL-safe) and re-lint before laying it out.
                if (rebuild != null) {
                    Component next = rebuild.get();
                    if (next != null) {
                        root[0] = next;
                        Ui.lint(root[0]);
                    }
                }
                int fbW = win.framebufferWidth();
                int fbH = win.framebufferHeight();
                float[] projection = Projection.orthoTopLeft(fbW, fbH);
                if (bloom != null) bloom.begin(fbW, fbH);   // bind HDR target; frame renders into it
                Gl.glViewport(0, 0, fbW, fbH);
                Gl.glClearColor(BACKGROUND.r(), BACKGROUND.g(), BACKGROUND.b(), BACKGROUND.a());
                Gl.glClear(Gl.GL_COLOR_BUFFER_BIT);
                LayoutResult layout = Layout.compute(root[0], new PixelRect(0f, 0f, fbW, fbH));
                LatestLayout.store(root[0], layout);  // required so hit-testing has coordinates
                batcher.beginFrame(fbH);
                Render.render(root[0], layout, batcher, projection);
                batcher.endFrame(projection);
                if (bloom != null) bloom.end(fbW, fbH);     // bloom + composite to the screen
            });
            loop.run();  // blocks on this (root) thread until the window is closed
            if (bloom != null) bloom.close();               // free FBOs while the GL context is alive
        }
        return new IrInterpreter.DriveResult();
    }
}
