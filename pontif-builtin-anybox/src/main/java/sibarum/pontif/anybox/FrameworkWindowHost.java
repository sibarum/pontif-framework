package sibarum.pontif.anybox;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Views;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.NodeEvent;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TextField;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.host.FrameworkLaneTransport;
import sibarum.pontif.host.PontifBusGraph;
import sibarum.pontif.ir.NativeCalls;

/**
 * Mounts a program's tree into the application's own {@code Gui} (docs/anybox.md).
 *
 * <p>Everything the program used to open by hand - the input backend and its coordinate space, the clipboard,
 * the loop, the zoom range, the window's size and where it was left - is the framework's, so none of it is
 * here. What is left is the part only Pontif knows: walking a {@code Box} into nodes, and placing the result
 * under the page the way a one-line window should look like part of the same UI as a hand-styled one.
 *
 * <p>{@code window} returns at once. It runs in the {@code TREE} phase, where the framework builds widgets
 * before there is a window, so the tree is the same one whether a window follows or a screenshot does.
 *
 * <p><b>The window's size and title.</b> The framework sizes and remembers the window from the application's
 * facts, which are fixed before the program runs; a program's {@code width} and {@code height} therefore no
 * longer apply, and that is the better answer - a window comes back where the user left it. The program's
 * {@code title} is shown in the framework's title bar, when the application draws its own frame.
 *
 * <p><b>The window is a view</b> (vexelray-framework's {@code docs/threading.md}, T7.4): its widgets are heard as
 * messages on the GUI thread and leave for the program's lane, and what the program writes back ({@code SetText})
 * arrives as a message in the frame's widget tick. No Pontif handler runs on the GUI thread.
 */
final class FrameworkWindowHost implements Mounts.Host {

    /** Capacity is for edges; {@code SetText} is a state topic and holds one write per widget. */
    private static final int CAPACITY = 64;

    private final Shell shell;
    private final FrameworkLaneTransport.Outlet<SetText> setText;
    private AutoCloseable mounted;

    FrameworkWindowHost(Shell shell, FrameworkLaneTransport lanes) {
        this.shell = shell;
        this.setText = lanes.outlet(SetText.TOPIC);
        Views.mailbox(shell.gui(), "pontif.gui#SetText", SetText.TOPIC, c -> Mounts.apply(c.id(), c.text()), CAPACITY);
    }

    /** The window's part in the bus graph. */
    static final String PART = "pontif.gui";

    /** How many edges the widget library's routed mailboxes (a field's {@code CHANGED}, a node's events) hold. */
    private static final int ROUTED = 4096;

    /**
     * What the window adds to the program's message graph: it is a view that hears {@code SetText}, its buttons'
     * clicks and looks and its fields' edits, and sends what it hears into the program, to whichever lane owns the
     * event; and every lane may send {@code SetText}. The node topics are named by the window's tree, which is the
     * framework's main window: one window per program for now.
     */
    static void declare(PontifBusGraph graph) {
        String tree = dev.vexelray.framework.core.CloseRequested.MAIN;
        @SuppressWarnings("unchecked")
        Class<NodeEvent.Message<Object>> message = (Class<NodeEvent.Message<Object>>) (Class<?>) NodeEvent.Message.class;
        graph.view(PART)
                .hears(PART + "#SetText", SetText.TOPIC, CAPACITY)
                .hears(PART + "#TextChanged", TextField.CHANGED, ROUTED)
                .hears(PART + "#Clicked", sibarum.atchung.Topic.edge("vexelray.gui." + tree + ".node."
                        + NodeEvent.CLICK.name(), message), ROUTED)
                .hears(PART + "#look", sibarum.atchung.Topic.state("vexelray.gui." + tree + ".node."
                        + NodeEvent.STATE.name(), message, NodeEvent.Message::node), ROUTED)
                .sendsToLanes(PART)
                .instrument(SetText.TOPIC);
    }

    @Override
    public void setText(String id, String text) {
        setText.send(new SetText(id, text));
    }

    @Override
    public Object open(String title, Object root, NativeCalls.Context ctx) {
        if (mounted != null) {
            throw new IllegalStateException("a program has one window for now: window was called twice");
        }
        Gui gui = shell.gui();
        BoxWalker walker = new BoxWalker(gui, ctx);
        Node tree = walker.walk(root);

        Node page = gui.box()
                .width(Length.FILL).height(Length.grow(1f))
                .background(Role.PAGE)
                .padding(Length.dp(12))
                .children(tree);
        Node window = gui.root().direction(Direction.COLUMN).background(Role.PAGE);
        if (shell.appearance().drawsOwnFrame()) {
            window.children(shell.titleBar().title(title).node(), page);
        } else {
            window.children(page);
        }

        mounted = Mounts.mount(walker.nodes(), walker.fields());
        shell.disposer().register(mounted);
        return new IrInterpreter.DriveResult();
    }
}
