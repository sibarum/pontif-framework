package sibarum.pontif.gui;

import sibarum.dasum.gui.core.GlfwContext;
import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.em.EmContext;
import sibarum.dasum.gui.core.event.EventLoop;
import sibarum.dasum.gui.core.input.InputState;
import sibarum.dasum.gui.core.input.TextStates;
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
import sibarum.dasum.gui.core.window.Window;
import sibarum.dasum.gui.natives.gl.Gl;
import sibarum.dasum.gui.natives.glfw.Glfw;
import sibarum.dasum.gui.natives.glfw.GlfwCallbacks;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Java side of the GUI extension (docs/extensions.md). <b>G3</b> adds a rendered text label
 * (the first GUI <i>value</i>, not just a colour), reactively updated: a click → Pontif
 * {@code ClickEvent} → a slice-1e {@code action} → {@code setText(...)}, which mutates the label's
 * dasum text state; the next frame paints it. Builds on G1 (window) + G2 (click re-entry +
 * reactive background colour).
 *
 * <p>State is bridge-managed (dasum {@link Property} / {@link Component.Text}); only primitives
 * cross the Pontif↔Java boundary (click coords in, colour components / label string out), since
 * opaque Java objects cannot be Pontif values. Single window, single thread.
 */
public final class DasumBridge {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;

    /** The window background — a reactive Property the render loop reads each frame (G2). */
    private static final Property<Color> bg = new Property<>(new Color(0.05f, 0.07f, 0.12f, 1f));

    /** The centered text label; {@code setText} mutates its content via {@link TextStates}. */
    private static final Component.Text label =
            new Component.Text("Click anywhere", Em.of(2f), new Color(0.92f, 0.92f, 0.96f, 1f));

    /** A transparent flex root that centres the label over the background. */
    private static final Component root = new Component.Flex(
            null, null, Em.of(0.5f), new Color(0f, 0f, 0f, 0f),
            Direction.COLUMN, JustifyContent.CENTER, AlignItems.CENTER, Em.of(0.5f),
            List.<Component>of(label), false, 0);

    private DasumBridge() {}

    /**
     * The {@code window(title:String)} native call: open the window, load the font atlas, register
     * a click handler that fires a {@code ClickEvent}, and block in the loop (root thread) until
     * the window is closed, rendering the label tree each frame. Returns the inert for-effect result.
     */
    public static Object openWindow(List<Object> args, NativeCalls.Context ctx) {
        String title = args.isEmpty() || !(args.get(0) instanceof StringValue s) ? "Pontif" : s.content();
        try (GlfwContext glfw = GlfwContext.init();
             Window win = Window.create(WIDTH, HEIGHT, title);
             Batcher batcher = new Batcher();
             Texture fontTexture = Texture.fromPngResource("/dasum/atlas/primary.png")) {
            Gl.load();
            batcher.init();
            EmContext.setDpiScale(win.contentScaleX());

            // Register the default text font from the shipped MSDF atlas (docs/extensions.md G3).
            AtlasData atlas = AtlasData.loadFromResource("/dasum/atlas/primary.json");
            FontGroups.register(FontGroup.of(FontGroups.DEFAULT, atlas, fontTexture));

            GlfwCallbacks.setMouseButtonListener((w, button, action, mods) -> {
                if (button == Glfw.GLFW_MOUSE_BUTTON_LEFT && action == Glfw.GLFW_PRESS) {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("x", (long) InputState.mouseX());
                    fields.put("y", (long) InputState.mouseY());
                    ctx.fireEvent(new RecordValue("pontif.gui/ClickEvent", fields));
                }
            });

            EventLoop loop = new EventLoop(win, () -> {
                int fbW = win.framebufferWidth();
                int fbH = win.framebufferHeight();
                float[] projection = Projection.orthoTopLeft(fbW, fbH);
                Color c = bg.get();
                Gl.glViewport(0, 0, fbW, fbH);
                Gl.glClearColor(c.r(), c.g(), c.b(), c.a());
                Gl.glClear(Gl.GL_COLOR_BUFFER_BIT);
                LayoutResult layout = Layout.compute(root, new PixelRect(0f, 0f, fbW, fbH));
                batcher.beginFrame(fbH);
                Render.render(root, layout, batcher, projection);
                batcher.endFrame(projection);
            });
            loop.run();  // blocks on this (root) thread until the window is closed
        }
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
