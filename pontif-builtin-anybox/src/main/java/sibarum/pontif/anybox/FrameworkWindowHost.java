package sibarum.pontif.anybox;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import sibarum.pontif.ir.IrInterpreter;
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
 */
final class FrameworkWindowHost implements Mounts.Host {

    private final Shell shell;
    private AutoCloseable mounted;

    FrameworkWindowHost(Shell shell) {
        this.shell = shell;
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
                .background(gui.theme().color(Role.PAGE))
                .padding(Length.dp(12))
                .children(tree);
        Node window = gui.root().direction(Direction.COLUMN).background(gui.theme().color(Role.PAGE));
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
