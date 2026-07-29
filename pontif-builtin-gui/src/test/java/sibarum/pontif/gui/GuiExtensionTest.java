package sibarum.pontif.gui;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.module.Extensions;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Headless-safe check of the declarative GUI extension (docs/extensions.md, G5): installing
 * {@link GuiExtension} makes {@code pontif.gui} linkable and registers the element builders
 * ({@code label}/{@code button}/{@code column}/{@code window}) as native calls, and a declarative
 * UI program links. The actual window is verified manually (needs a display + GLFW):
 * {@code mvn -pl pontif-builtin-gui -am exec:exec -Dptf=examples/ui.ptf}. This test does NOT open a
 * window — it only compiles, so it is safe on a headless CI.
 */
class GuiExtensionTest {

    @Test
    void installingGuiExtension_linksDeclarativeUi_andBindsBuilders() {
        Extensions.install(new GuiExtension());

        // Capitalized, directly-constructed elements + a clickable widget subtype.
        CompileResult result = new PontifCompiler().compileAlt("""
                requires pontif.gui.{Label, Button, Column, window, Clickable}
                requires pontif.events.{StdOut}
                struct PushButton:[Button](id:String, text:String)
                assign trait PushButton:Clickable { onClick():_ -> emit StdOut("hi")  this }
                main (
                  let lbl = Label("greeting", "hi")
                  let btn = PushButton("go-btn", "go")
                  window({title = "t"}, { Column("center", "middle", {lbl, btn}) })
                )""", "gui.ptf");
        assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "capitalized-element pontif.gui program should link; got "
                        + (result instanceof CompileResult.Failed f ? f.error().text() : result));

        // Natives are registered under their qualified name only (the resolved call name is the
        // FQN); the bare name is intentionally NOT registered, so it can't hijack a user's local
        // function of the same name.
        assertNotNull(NativeCalls.get("pontif.gui/window"), "window should be the registered native call");
    }

    @Test
    void reactiveCounterProgram_typeChecks() {
        Extensions.install(new GuiExtension());

        // The reactive loop (docs/reactive-gui.md): the tree is built ONCE; a GuiEvent conduit folds
        // Clicked into a Model and emits the ISOLATED command SetText("count", …), which the SetText
        // sink applies to that one retained widget — no rebuild. This only compiles + links (the id'd
        // widgets, the conduit, the SetText command); the actual click→increment repaint is verified
        // manually (needs GLFW): exec:exec -Dptf=examples/reactive-counter.ptf. Kept in sync with
        // examples/reactive-counter.ptf.
        CompileResult result = new PontifCompiler().compileAlt("""
                requires pontif.gui.{Label, Button, Column, window, GuiEvent, Clicked, SetText}
                struct Model(count:Int)
                conduit app(e:GuiEvent, s:Model):Model from Model(0) -> (
                  let m2 = match e { [Clicked] -> Model(s.count + 1)  [_] -> s }
                  emit SetText("count", m2.count + "")
                  m2
                )
                main ( window({title = "Counter"}, {
                  Column("center", "middle", { Label("count", "0"), Button("inc", "increment") })
                }) )""", "reactive-counter.ptf");
        assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "reactive counter program should type-check + link; got "
                        + (result instanceof CompileResult.Failed f ? f.error().text() : result));
    }

    @Test
    void reactiveConduit_foldsClickedAcrossEmits_headless() {
        // The reactive DATA-FLOW, executed (not just type-checked) and without a window: a GuiEvent
        // conduit folds each Clicked (matched via the GuiEvent ancestor trait) into a threaded Model,
        // using the `:S` pass-through sugar. The fold body emits the running count so we can observe
        // the state advancing 0 -> 1 -> 2 -> 3 across three Clicks. This is the reactive loop's engine
        // minus the GLFW repaint (which needs a live window); it proves the click->conduit->state path.
        Extensions.install(new GuiExtension());
        PrintStream origOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            var compiled = new PontifCompiler().compileAlt("""
                    requires pontif.gui.{GuiEvent, Clicked}
                    requires pontif.events.{StdOut}
                    struct Model(count:Int)
                    conduit app(e:GuiEvent, s:Model):Model from Model(0) -> (
                      let m2 = match e { [Clicked] -> Model(s.count + 1)  [_] -> s }
                      emit StdOut(m2.count + "")  emit StdOut(" ")
                      m2
                    )
                    main ( emit Clicked("b")  emit Clicked("b")  emit Clicked("b")  0 )""",
                    "reactive-fold.ptf");
            assertInstanceOf(CompileResult.Compiled.class, compiled,
                    () -> "reactive-fold program should link; got "
                            + (compiled instanceof CompileResult.Failed f ? f.error().text() : compiled));
            var result = new PontifRunner().run(compiled, Engine.INTERPRETER);
            assertFalse(result.isError(), () -> "program errored: " + result.text());
        } finally {
            System.setOut(origOut);
        }
        assertEquals("1 2 3 ", out.toString(StandardCharsets.UTF_8),
                "the conduit threads the Model across the three Clicked emits (ancestor-matched)");
    }

    @Test
    void linePlotProgram_linksAgainstExtension() {
        Extensions.install(new GuiExtension());

        // A 2D line chart over two number aggregates (the dasum-vis plotting slice). This only
        // compiles + links; the chart render itself is verified manually (needs GLFW):
        // mvn -pl pontif-builtin-gui -am exec:exec -Dptf=examples/line-plot.ptf
        CompileResult result = new PontifCompiler().compileAlt("""
                requires pontif.gui.{LinePlot, window}
                main (
                  let xs = {0.0, 1.0, 2.0, 3.0}
                  let ys = {0.0, 1.0, 4.0, 9.0}
                  window({title = "y = x^2"}, { LinePlot(xs, ys) })
                )""", "line-plot.ptf");
        assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "LinePlot pontif.gui program should link; got "
                        + (result instanceof CompileResult.Failed f ? f.error().text() : result));
    }
}
