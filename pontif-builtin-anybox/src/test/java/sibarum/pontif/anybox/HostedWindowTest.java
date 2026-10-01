package sibarum.pontif.anybox;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.pontif.host.PontifWiring;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.PontifCompiler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real Anybox program as a framework application, headless: {@code window} mounts into the application's own
 * {@code Gui} instead of opening a loop, an isolated {@code SetText} finds its widget by id, and the window's
 * ids go when the window does.
 */
class HostedWindowTest {

    private static final String PROGRAM = """
            requires pontif.gui.{column, text, window}
            main ( window({title = "Hosted"}, column({ text("0").id("count") })) )
            """;

    private static String dump(RetainedNode n) {
        StringBuilder sb = new StringBuilder("[" + n.textString());
        for (RetainedNode c : n.children) sb.append(' ').append(dump(c));
        return sb.append(']').toString();
    }

    private static RetainedNode find(RetainedNode node, String text) {
        if (text.equals(node.textString())) return node;
        for (RetainedNode child : node.children) {
            RetainedNode hit = find(child, text);
            if (hit != null) return hit;
        }
        return null;
    }

    @Test
    void windowMountsIntoTheApplicationsGui_andSetTextFindsItsWidget() throws Exception {
        var compiled = new PontifCompiler().compile(PROGRAM, "hosted.ptf");
        if (compiled instanceof PontifCompiler.CompileResult.Failed f) throw new AssertionError(f.error().text());
        var program = ((PontifCompiler.CompileResult.Compiled) compiled).program();
        // BoxSurfaceTest leaves a stub window native registered for the whole JVM; this test is about the real one.
        NativeCalls.register("pontif.gui/window", AnyboxExtension::openWindow);
        PontifWiring wiring = new PontifWiring(AnyboxLauncher.appInfo("hosted.ptf"), program,
                shell -> Mounts.install(new FrameworkWindowHost(shell)));

        Shell shell = VexelApplication.tree(wiring, new String[0]);
        Mounts.setText("count", "7");   // the one-line body of the SetText effect, once the program has mounted
        try {
            RetainedNode frame = BoxWalkerTest.frame(shell.gui());
            // A mutation is posted, not applied: it lands on the Gui's own mailbox, so the frame that shows it
            // is the one after it arrives.
            for (long end = System.nanoTime() + 5_000_000_000L; find(frame, "7") == null && System.nanoTime() < end; ) {
                Thread.sleep(5);
                frame = BoxWalkerTest.frame(shell.gui());
            }
            assertNotNull(find(frame, "7"), "SetText reached the retained widget with that id, in place; tree: " + dump(frame));
            assertEquals(null, find(frame, "0"), "and nothing was rebuilt: the old text is gone, not duplicated");
        } finally {
            shell.disposer().close();
        }

        PrintStream orig = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            Mounts.setText("count", "9");
        } finally {
            System.setErr(orig);
        }
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("no widget with id 'count'"),
                "a closed window takes its ids with it: " + err);
    }

    @Test
    void theLauncherNamesEachProgramsSettingsAfterThatProgram() {
        assertEquals("pontif-counter", AnyboxLauncher.appInfo("counter.ptf").name());
        assertEquals("pontif-my-odd-name", AnyboxLauncher.appInfo("My Odd_Name.ptf").name());
        assertEquals("pontif-program", AnyboxLauncher.appInfo("....ptf").name());
    }
}
