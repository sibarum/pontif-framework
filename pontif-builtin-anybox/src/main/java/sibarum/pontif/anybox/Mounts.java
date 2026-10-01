package sibarum.pontif.anybox;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.widget.TextField;
import sibarum.pontif.ir.NativeCalls;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where a program's {@code window} call lands, and where its isolated updates find their widgets.
 *
 * <p>The extension is discovered by ServiceLoader and its natives are static, so the two things a window needs
 * from the application that hosts it - somewhere to mount a tree, and a way to find an id'd widget afterwards -
 * live here rather than on a host the native could be handed. {@link #install} is how the application says
 * where windows go ({@link FrameworkWindowHost} in an application, a stub in a test); there is no default,
 * because a window that opens a loop of its own is exactly the second composition root this replaced.
 *
 * <p>Each mounted window keeps its own registries of ids, so a window leaving takes its ids with it and a
 * command that arrives with no window simply finds nothing. A command addresses an id, not a window: the first
 * mounted window that has the id answers, which is unambiguous while a program has one window and is the rule
 * to revisit when it has several.
 */
final class Mounts {

    /** Somewhere a program's root {@code Box} becomes a window. */
    interface Host {
        /**
         * Mount {@code root} and return at once: the loop is the host's, so this neither blocks nor runs one.
         * {@code ctx} is how the handlers the walk installs fire events back into the running program.
         */
        Object open(String title, Object root, NativeCalls.Context ctx);
    }

    /** One window's retained registries - id to node, and id to field. */
    private record Mounted(Map<String, Node> nodes, Map<String, TextField> fields) {}

    private static volatile Host host;
    private static final List<Mounted> mounted = new CopyOnWriteArrayList<>();

    private Mounts() {}

    static void install(Host h) {
        host = h;
    }

    static Host host() {
        Host h = host;
        if (h == null) {
            throw new IllegalStateException(
                    "no window host: run an Anybox program through AnyboxLauncher, or install a Mounts.Host");
        }
        return h;
    }

    /** Register one window's widgets; the returned handle takes them out again when the window goes. */
    static AutoCloseable mount(Map<String, Node> nodes, Map<String, TextField> fields) {
        Mounted m = new Mounted(nodes, fields);
        mounted.add(m);
        return () -> mounted.remove(m);
    }

    /**
     * The {@code pontif.gui/SetText} sink: set the text of the retained widget with this id, in place. A field
     * is written through its {@link TextField} rather than its node, because its text lives in a Document the
     * caret and undo log are keyed to - writing the node would desynchronise both. An unknown id is reported
     * and dropped rather than thrown: a stale command should not take down a running window.
     */
    static void setText(String id, String text) {
        for (Mounted m : mounted) {
            TextField field = m.fields().get(id);
            if (field != null) {
                field.text(text);
                return;
            }
            Node node = m.nodes().get(id);
            if (node != null) {
                node.text(text);
                return;
            }
        }
        System.err.println("[anybox] SetText: no widget with id '" + id + "' in any open window");
    }
}
