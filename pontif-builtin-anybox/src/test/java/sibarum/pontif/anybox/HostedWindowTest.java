package sibarum.pontif.anybox;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.pontif.host.PontifWiring;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real Anybox program as a framework application, headless: {@code window} mounts into the application's own
 * {@code Gui} instead of opening a loop, a click on a button is heard as a message and handled in the logic tick,
 * and the {@code SetText} the program answers with arrives on the GUI thread and finds its widget by id. This
 * thread plays the GUI thread, stepping frames as the loop would.
 */
class HostedWindowTest {

    private static final String COUNTER = """
            requires pontif.gui.{column, text, button, window, GuiEvent, Clicked, SetText}
            struct Model(count:Int)
            conduit app(e:GuiEvent, s:Model):Model from Model(0) -> (
              let m2 = match e { [Clicked] -> Model(s.count + 1)  [_] -> s }
              emit SetText("count", m2.count + "")
              m2
            )
            main ( window({title = "Hosted"}, column({ text("0").id("count"), button("inc", "increment") })) )
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

    /** Step frames until {@code until} finds something, or five seconds pass; the last frame either way. */
    private static RetainedNode frameUntil(Shell shell, Function<RetainedNode, RetainedNode> until) throws Exception {
        RetainedNode frame = BoxWalkerTest.frame(shell.gui());
        for (long end = System.nanoTime() + 5_000_000_000L; until.apply(frame) == null && System.nanoTime() < end; ) {
            Thread.sleep(5);
            frame = BoxWalkerTest.frame(shell.gui());
        }
        return frame;
    }

    private static Shell run(String source) {
        var compiled = new PontifCompiler().compile(source, "hosted.ptf");
        if (compiled instanceof PontifCompiler.CompileResult.Failed f) throw new AssertionError(f.error().text());
        var program = ((PontifCompiler.CompileResult.Compiled) compiled).program();
        PontifWiring wiring = new PontifWiring(AnyboxLauncher.appInfo("hosted.ptf"), program,
                (shell, lanes) -> Mounts.install(new FrameworkWindowHost(shell, lanes)));
        return VexelApplication.tree(wiring, new String[0]);
    }

    @Test
    void aClickIsHandledInTheLogicTick_andItsSetTextLandsOnTheWidgetInPlace() throws Exception {
        Shell shell = run(COUNTER);
        try {
            RetainedNode frame = frameUntil(shell, f -> find(f, "increment"));
            RetainedNode button = find(frame, "increment");
            assertNotNull(button, "the button was mounted; tree: " + dump(frame));
            int x = (int) (button.x + button.w / 2), y = (int) (button.y + button.h / 2);

            shell.bus().publish(shell.gui().input(), new InputEvent.ButtonPressed(MouseButton.LEFT, x, y, 0));
            BoxWalkerTest.frame(shell.gui());
            shell.bus().publish(shell.gui().input(), new InputEvent.ButtonReleased(MouseButton.LEFT, x, y, 0));

            // The click leaves in this frame; the logic tick folds it and sends SetText back, and a later frame
            // applies it. Nothing here waits on the logic: the frames are stepped until the write has arrived.
            frame = frameUntil(shell, f -> find(f, "1"));
            assertNotNull(find(frame, "1"), "the conduit counted the click and SetText reached 'count'; tree: " + dump(frame));
            assertNull(find(frame, "0"), "and nothing was rebuilt: the old text is gone, not duplicated");
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void aSetTextFromOutsideTheProgramStillArrivesInAFrame_andAClosedWindowTakesItsIdsWithIt() throws Exception {
        Shell shell = run(COUNTER);
        try {
            Mounts.setText("count", "7");   // the SetText effect's body, sent from a thread serving no lane
            RetainedNode frame = frameUntil(shell, f -> find(f, "7"));
            assertNotNull(find(frame, "7"), "SetText reached the retained widget with that id; tree: " + dump(frame));
        } finally {
            shell.disposer().close();
        }

        PrintStream orig = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            Mounts.apply("count", "9");
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
