package sibarum.pontif.gui;

import sibarum.dasum.gui.core.GlfwContext;
import sibarum.dasum.gui.core.em.EmContext;
import sibarum.dasum.gui.core.event.EventLoop;
import sibarum.dasum.gui.core.input.InputState;
import sibarum.dasum.gui.core.reactive.Property;
import sibarum.dasum.gui.core.render.Batcher;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.render.Projection;
import sibarum.dasum.gui.core.window.Window;
import sibarum.dasum.gui.natives.gl.Gl;
import sibarum.dasum.gui.natives.glfw.Glfw;
import sibarum.dasum.gui.natives.glfw.GlfwCallbacks;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Java side of the GUI extension (docs/extensions.md) — bridges Pontif's {@code window} /
 * {@code setBackground} calls to the dasum toolkit. <b>G2</b> closes the reactive-properties
 * loop: a left-click builds a Pontif {@code ClickEvent(x, y)} and {@link NativeCalls.Context#fireEvent
 * fires it back through the substrate}, where a slice-1e {@code action} reacts and calls
 * {@code setBackground}, which mutates the bridge's reactive {@link #bg} colour — the next frame
 * paints it. State is bridge-managed (a dasum {@code Property}); only primitives cross the
 * Pontif↔Java boundary (click coords in, colour components out), since opaque Java objects cannot
 * be Pontif values.
 *
 * <p>Single window, single thread: the click callback fires within {@code loop.run()} on the root
 * thread, so {@code fireEvent} re-enters the interpreter synchronously on that same thread.
 * Text/widgets (font atlases) and per-component hit-testing are later slices.
 */
public final class DasumBridge {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;

    /** The current window's background — a reactive Property the render loop reads each frame. */
    private static final Property<Color> bg = new Property<>(new Color(0.05f, 0.07f, 0.12f, 1f));

    private DasumBridge() {}

    /**
     * The {@code window(title:String)} native call: open the window, register a click handler that
     * fires a {@code ClickEvent} into the substrate, and block in the loop (root thread) until the
     * window is closed. Returns the inert for-effect result.
     */
    public static Object openWindow(List<Object> args, NativeCalls.Context ctx) {
        String title = args.isEmpty() || !(args.get(0) instanceof sibarum.pontif.core.types.StringValue s)
                ? "Pontif"
                : s.content();
        try (GlfwContext glfw = GlfwContext.init();
             Window win = Window.create(WIDTH, HEIGHT, title);
             Batcher batcher = new Batcher()) {
            Gl.load();
            batcher.init();
            EmContext.setDpiScale(win.contentScaleX());

            // Any left-click in the window → a Pontif ClickEvent carrying the cursor position,
            // fired through the substrate (its matching `action`s run, on this same thread).
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
                Color c = bg.get();  // reactive: whatever the latest setBackground stored
                Gl.glViewport(0, 0, fbW, fbH);
                Gl.glClearColor(c.r(), c.g(), c.b(), c.a());
                Gl.glClear(Gl.GL_COLOR_BUFFER_BIT);
                batcher.beginFrame(fbH);
                batcher.endFrame(projection);
            });
            loop.run();  // blocks on this (root) thread until the window is closed
        }
        return new IrInterpreter.DriveResult();
    }

    /**
     * The {@code setBackground(r:Int, g:Int, b:Int)} native call: mutate the reactive background
     * colour (the next frame paints it). Each component is taken mod 256 and scaled to [0,1], so
     * click coordinates spread across the colour space.
     */
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
