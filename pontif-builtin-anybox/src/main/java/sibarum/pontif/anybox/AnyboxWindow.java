package sibarum.pontif.anybox;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TextField;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;
import sibarum.tactroller.clipboard.Clipboard;
import sibarum.tactroller.clipboard.ClipboardException;

import java.util.Map;

/**
 * The window: builds the retained tree ONCE from a Pontif {@code Box}, opens a vexelray window, and
 * runs the GUI loop on the calling (root) thread until it closes.
 *
 * <p>Nothing is ever rebuilt. Interactivity is isolated updates — a click fires {@code Clicked}, the
 * app's conduit folds it into a Pontif value and emits a targeted command, and the command's sink
 * ({@link #setText}) posts one mutation for one node. vexelray handles are write-only and
 * thread-safe by construction, so a sink runs wherever the conduit does and needs no marshalling
 * back to the GUI thread; that is the framework's normal path, not an exception to it.
 */
final class AnyboxWindow {

    private AnyboxWindow() {}

    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;

    /**
     * The retained registries of the window currently open — id → node, and id → field. One window
     * runs at a time (the loop owns the root thread), so a single pair suffices; both are cleared on
     * open and on close, so a command that arrives with no window simply finds nothing.
     */
    private static volatile Map<String, Node> nodes = Map.of();
    private static volatile Map<String, TextField> fields = Map.of();

    /**
     * The {@code pontif.gui/SetText} sink: set the text of the retained widget with this id, in
     * place. A field is written through its {@link TextField} rather than its node, because its text
     * lives in a Document the caret and undo log are keyed to — writing the node would desynchronise
     * both. An unknown id is reported and dropped rather than thrown: a stale command should not
     * take down a running window.
     */
    static void setText(String id, String text) {
        TextField field = fields.get(id);
        if (field != null) {
            field.text(text);
            return;
        }
        Node node = nodes.get(id);
        if (node == null) {
            System.err.println("[anybox] SetText: no widget with id '" + id + "' in the current window");
            return;
        }
        node.text(text);
    }

    /**
     * Open the window for {@code root} and run until it closes. {@code ctx} is threaded from the
     * native call so the walk — and the handlers it installs — fire events back into the same
     * running program.
     */
    static Object open(String title, int width, int height, Object root, NativeCalls.Context ctx) {
        try (Gui gui = new Gui()) {
            gui.minSize(Length.em(20), Length.em(12));
            gui.zoomRange(0.5f, 3f, 1.25f);

            BoxWalker walker = new BoxWalker(gui, ctx);
            Node tree = walker.walk(root);
            nodes = walker.nodes();
            fields = walker.fields();
            // The root paints the page and fills the window; the app's tree is its one child. Doing
            // this here rather than asking every program to say it means a one-line window still
            // looks like part of the same UI as a hand-styled one.
            gui.root().background(gui.theme().color(Role.PAGE))
                    .padding(Length.dp(12))
                    .children(tree);

            try (Tactroller input = openInput();
                 GuiApp app = new GuiApp(title, width, height);
                 Clipboard clipboard = openClipboard(gui)) {
                attachInput(input, app);
                TactrollerInputBridge bridge = input == null ? null : new TactrollerInputBridge(input, gui.bus());
                app.run(gui, 0, () -> pump(bridge));
            }
        } catch (Exception e) {
            System.err.println("[anybox] window failed: " + e);
        } finally {
            nodes = Map.of();
            fields = Map.of();
        }
        return new IrInterpreter.DriveResult();
    }

    static Object open(String title, Object root, NativeCalls.Context ctx) {
        return open(title, WIDTH, HEIGHT, root, ctx);
    }

    // ------------------------------------------------------------------ input

    /**
     * A window whose input backend will not open still renders — it is a degraded window rather than
     * a failed launch, which is what lets a headless or driver-less machine run a program far enough
     * to see that it built.
     */
    private static Tactroller openInput() {
        try {
            return Tactroller.open();
        } catch (BackendException e) {
            System.err.println("[anybox] input unavailable (" + e.getMessage() + "); rendering without input");
            return null;
        }
    }

    /**
     * CLIENT coordinates, and density deliberately left alone: the engine's window and canvas are in
     * logical coordinates, so FRAMEBUFFER coordinates would land every press past its target on a
     * scaled display, and feeding the content scale into the GUI would scale content the OS is
     * already scaling.
     */
    private static void attachInput(Tactroller input, GuiApp app) {
        if (input == null) return;
        try {
            input.attach(NativeWindow.ofHwnd(app.windowHandle()));
            input.setCoordinateSpace(CoordinateSpace.CLIENT);
        } catch (BackendException e) {
            System.err.println("[anybox] input attach failed (" + e.getMessage() + "); pointer input disabled");
        }
    }

    /** The OS clipboard, so fields can cut/copy/paste. Absent → the GUI's in-memory default stands. */
    private static Clipboard openClipboard(Gui gui) {
        try {
            Clipboard clip = Clipboard.open();
            gui.clipboard(new TextClipboard() {
                @Override
                public String get() {
                    try {
                        return clip.getText().orElse("");
                    } catch (ClipboardException e) {
                        return "";
                    }
                }

                @Override
                public void set(String text) {
                    try {
                        clip.setText(text);
                    } catch (ClipboardException e) {
                        // best effort — a transient failure just drops the copy
                    }
                }
            });
            return clip;
        } catch (ClipboardException e) {
            return null;
        }
    }

    /** Snapshot input onto the bus for this frame; a transient poll failure skips the frame. */
    private static void pump(TactrollerInputBridge bridge) {
        if (bridge == null) return;
        try {
            bridge.pump();
        } catch (BackendException e) {
            // drop this frame's input rather than tear down the loop
        }
    }
}
