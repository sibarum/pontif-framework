package sibarum.pontif.gui;

import sibarum.dasum.gui.core.GlfwContext;
import sibarum.dasum.gui.core.em.EmContext;
import sibarum.dasum.gui.core.event.EventLoop;
import sibarum.dasum.gui.core.render.Batcher;
import sibarum.dasum.gui.core.render.Projection;
import sibarum.dasum.gui.core.window.Window;
import sibarum.dasum.gui.natives.gl.Gl;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrInterpreter;

import java.util.List;

/**
 * The Java side of the GUI extension (docs/extensions.md) — bridges a Pontif {@code window(...)}
 * call to the dasum toolkit. <b>G1</b> is deliberately minimal: open a titled window with a solid
 * background and run the render/event loop on the <b>calling (root) thread</b> until the window is
 * closed. Only primitives cross the Pontif↔Java boundary (the title String), so no opaque dasum
 * objects flow as Pontif values yet.
 *
 * <p>Text/widgets (which need font atlases) and interactivity (click → {@code emit} → an
 * {@code action} → a dasum {@code Property} mutation) are later slices.
 *
 * <p>Thread note: GLFW must own the thread it was initialised on. The interpreter runs the
 * program — and so this call — on the launcher's main thread, which is the root thread (on
 * macOS the JVM also needs {@code -XstartOnFirstThread}; on Windows/Linux the main thread is
 * fine).
 */
public final class DasumBridge {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;

    private DasumBridge() {}

    /**
     * The {@code window(title:String)} native call: open the window, block in the loop until the
     * user closes it, then return the inert for-effect result (the runner renders it as no
     * output). {@code args} is the evaluated Pontif argument list — {@code args[0]} the title.
     */
    public static Object openWindow(List<Object> args) {
        String title = args.isEmpty() || !(args.get(0) instanceof StringValue s)
                ? "Pontif"
                : s.content();
        openTitledWindow(title);
        return new IrInterpreter.DriveResult();
    }

    private static void openTitledWindow(String title) {
        try (GlfwContext ctx = GlfwContext.init();
             Window win = Window.create(WIDTH, HEIGHT, title);
             Batcher batcher = new Batcher()) {
            Gl.load();
            batcher.init();
            EmContext.setDpiScale(win.contentScaleX());

            EventLoop loop = new EventLoop(win, () -> {
                int fbW = win.framebufferWidth();
                int fbH = win.framebufferHeight();
                float[] projection = Projection.orthoTopLeft(fbW, fbH);
                Gl.glViewport(0, 0, fbW, fbH);
                Gl.glClearColor(0.05f, 0.07f, 0.12f, 1f);
                Gl.glClear(Gl.GL_COLOR_BUFFER_BIT);
                // An empty batch each frame: this slice draws no components, just clears the
                // background. beginFrame/endFrame still flush + swap so the window presents.
                batcher.beginFrame(fbH);
                batcher.endFrame(projection);
            });
            loop.run();  // blocks on this (root) thread until the window is closed
        }
    }
}
