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
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.layout.HitTest;
import sibarum.dasum.gui.core.layout.LatestLayout;
import sibarum.dasum.gui.core.layout.Layout;
import sibarum.dasum.gui.core.layout.LayoutResult;
import sibarum.dasum.gui.core.layout.PixelRect;
import sibarum.dasum.gui.core.layout.Render;
import sibarum.dasum.gui.core.reactive.Property;
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
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Java side of the GUI extension (docs/extensions.md). The Pontif↔dasum bridge across the GUI
 * slices: a window (G1), a reactive background colour driven by clicks (G2), a text label (G3),
 * and now <b>clickable buttons</b> (G4). State is bridge-managed (dasum {@link Property} /
 * {@link Component.Text} / the button list); only primitives cross the boundary, since opaque Java
 * objects cannot be Pontif values. Single window, single thread.
 *
 * <p>G4: a Pontif {@code button("label")} registers a dasum button whose click {@link Runnable}
 * fires a {@code ButtonEvent(label)} back through the substrate (via {@link NativeCalls.Context}),
 * where a slice-1e {@code action} reacts. Clicks are dispatched per-component through dasum's input
 * pipeline (cursor hit-test → {@link HoverState}; press/release → {@link Handlers#activate}).
 */
public final class DasumBridge {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;

    /** The window background — a reactive Property the render loop reads each frame (G2). */
    private static final Property<Color> bg = new Property<>(new Color(0.05f, 0.07f, 0.12f, 1f));

    /** The centered text label; {@code setText} mutates its content via {@link TextStates} (G3). */
    private static final Component.Text label =
            new Component.Text("Click anywhere", Em.of(2f), new Color(0.92f, 0.92f, 0.96f, 1f));

    /** Buttons registered by {@code button(...)} before {@code window(...)} (G4). */
    private static final List<Component> buttons = new ArrayList<>();

    /** The component captured on mouse-down, to confirm the release lands on the same one. */
    private static Component pressTarget;

    private DasumBridge() {}

    /**
     * The {@code window(title:String)} native call: load the font, build the tree (label + any
     * registered buttons), wire input + click dispatch, and block in the loop (root thread) until
     * the window closes. Returns the inert for-effect result.
     */
    public static Object openWindow(List<Object> args, NativeCalls.Context ctx) {
        String title = args.isEmpty() || !(args.get(0) instanceof StringValue s) ? "Pontif" : s.content();
        try (GlfwContext glfw = GlfwContext.init();
             Window win = Window.create(WIDTH, HEIGHT, title);
             Batcher batcher = new Batcher()) {
            // Gl.load() MUST run before any GL call. The font texture therefore loads HERE, in
            // the body after Gl.load() — not in the try-with-resources header above, which would
            // upload it before the GL function handles exist (Gl.GL_GEN_TEXTURES null → NPE). It
            // is a plain local: the texture lives for the whole window and the process exits when
            // the window closes, so there is nothing to close early.
            Gl.load();
            batcher.init();
            EmContext.setDpiScale(win.contentScaleX());

            Texture fontTexture = Texture.fromPngResource("/dasum/atlas/primary.png");
            AtlasData atlas = AtlasData.loadFromResource("/dasum/atlas/primary.json");
            FontGroups.register(FontGroup.of(FontGroups.DEFAULT, atlas, fontTexture));

            // The tree: the label plus any buttons declared before this window opened.
            List<Component> children = new ArrayList<>();
            children.add(label);
            children.addAll(buttons);
            Component root = new Component.Flex(
                    null, null, Em.of(0.5f), new Color(0f, 0f, 0f, 0f),
                    Direction.COLUMN, JustifyContent.CENTER, AlignItems.CENTER, Em.of(0.8f),
                    children, false, 0);

            wireInput(ctx);

            EventLoop loop = new EventLoop(win, () -> {
                int fbW = win.framebufferWidth();
                int fbH = win.framebufferHeight();
                float[] projection = Projection.orthoTopLeft(fbW, fbH);
                Color c = bg.get();
                Gl.glViewport(0, 0, fbW, fbH);
                Gl.glClearColor(c.r(), c.g(), c.b(), c.a());
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

    /** Wires cursor hover + per-component click dispatch (the minimal dasum input pipeline). */
    private static void wireInput(NativeCalls.Context ctx) {
        GlfwCallbacks.setCursorPosListener((w, x, y) -> {
            InputState.updateMousePos(x, y);
            LayoutResult lr = LatestLayout.result();
            Component r = LatestLayout.root();
            if (lr != null && r != null) {
                HoverState.update(HitTest.test(r, lr, (float) x, (float) y));
            }
        });
        GlfwCallbacks.setMouseButtonListener((w, button, action, mods) -> {
            if (button != Glfw.GLFW_MOUSE_BUTTON_LEFT) return;
            if (action == Glfw.GLFW_PRESS) {
                // G2: a whole-window click (drives the background colour).
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("x", (long) InputState.mouseX());
                fields.put("y", (long) InputState.mouseY());
                ctx.fireEvent(new RecordValue("pontif.gui/ClickEvent", fields));
                // G4: remember which component the press landed on.
                pressTarget = HoverState.hovered();
            } else if (action == Glfw.GLFW_RELEASE) {
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
    }

    /**
     * The {@code button(label:String)} native: register a button whose click fires a
     * {@code ButtonEvent(label)} through the substrate. Called before {@code window(...)}.
     */
    public static Object button(List<Object> args, NativeCalls.Context ctx) {
        String text = args.isEmpty() || !(args.get(0) instanceof StringValue s) ? "" : s.content();
        buttons.add(Themed.button(text, Em.of(10f), Variant.PRIMARY, 0, () -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("label", new StringValue(text));
            ctx.fireEvent(new RecordValue("pontif.gui/ButtonEvent", fields));
        }));
        return new IrInterpreter.DriveResult();
    }

    /** {@code setText(s:String)}: set the label's content (the next frame paints it). */
    public static Object setText(List<Object> args, NativeCalls.Context ctx) {
        String text = args.isEmpty() || !(args.get(0) instanceof StringValue s) ? "" : s.content();
        TextStates.setContent(label, text);
        return new IrInterpreter.DriveResult();
    }

    /** {@code setBackground(r,g,b:Int)}: mutate the reactive background (next frame paints it). */
    public static Object setBackground(List<Object> args, NativeCalls.Context ctx) {
        bg.set(new Color(comp(args, 0), comp(args, 1), comp(args, 2), 1f));
        return new IrInterpreter.DriveResult();
    }

    /** Component {@code i} (a Pontif Int = Long) mapped into [0,1] via its low byte. */
    private static float comp(List<Object> args, int i) {
        long v = i < args.size() && args.get(i) instanceof Long l ? l : 0L;
        return (v & 0xFFL) / 255f;
    }
}
