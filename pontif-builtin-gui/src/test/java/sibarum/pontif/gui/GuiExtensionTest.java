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
    void reactiveTextFieldProgram_typeChecks() {
        Extensions.install(new GuiExtension());

        // Slice 3 (docs/reactive-gui.md §7): an editable TextField fires TextChanged{id, text}; the
        // GuiEvent conduit folds the live buffer into a Model and emits the ISOLATED SetText to a
        // SEPARATE result Label (uncontrolled input — the field is never written back). This only
        // compiles + links (the TextField/TextChanged shapes, the two-field notification, the
        // conduit); the actual type→echo repaint is verified manually (needs GLFW):
        // exec:exec -Dptf=examples/reactive-textfield.ptf. Kept in sync with that example.
        CompileResult result = new PontifCompiler().compileAlt("""
                requires pontif.gui.{Label, TextField, Column, window, GuiEvent, TextChanged, SetText}
                struct Model(text:String)
                conduit app(e:GuiEvent, s:Model):Model from Model("") -> (
                  let m2 = match e { [TextChanged] -> Model(e.text)  [_] -> s }
                  emit SetText("echo", "you typed: " + m2.text)
                  m2
                )
                main ( window({title = "TextField"}, {
                  Column("center", "middle", { TextField("expr", ""), Label("echo", "you typed: ") })
                }) )""", "reactive-textfield.ptf");
        assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "reactive textfield program should type-check + link; got "
                        + (result instanceof CompileResult.Failed f ? f.error().text() : result));
    }

    @Test
    void reactiveConduit_foldsTextChangedAcrossEmits_headless() {
        // The TextField DATA-FLOW, executed without a window: a GuiEvent conduit folds each
        // TextChanged (matched via the GuiEvent ancestor trait, the arm refining `e` to read `text`)
        // into a threaded Model, then emits the current text so we observe the buffer advancing across
        // edits. Proves the type→conduit→state path (the GLFW repaint that a live SetText drives needs
        // a window); sibling of the Clicked fold test above.
        Extensions.install(new GuiExtension());
        PrintStream origOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            var compiled = new PontifCompiler().compileAlt("""
                    requires pontif.gui.{GuiEvent, TextChanged}
                    requires pontif.events.{StdOut}
                    struct Model(text:String)
                    conduit app(e:GuiEvent, s:Model):Model from Model("") -> (
                      let m2 = match e { [TextChanged] -> Model(e.text)  [_] -> s }
                      emit StdOut(m2.text)  emit StdOut("|")
                      m2
                    )
                    main ( emit TextChanged("expr", "x")  emit TextChanged("expr", "x^")  emit TextChanged("expr", "x^2")  0 )""",
                    "reactive-textfield-fold.ptf");
            assertInstanceOf(CompileResult.Compiled.class, compiled,
                    () -> "reactive-textfield-fold program should link; got "
                            + (compiled instanceof CompileResult.Failed f ? f.error().text() : compiled));
            var result = new PontifRunner().run(compiled, Engine.INTERPRETER);
            assertFalse(result.isError(), () -> "program errored: " + result.text());
        } finally {
            System.setOut(origOut);
        }
        assertEquals("x|x^|x^2|", out.toString(StandardCharsets.UTF_8),
                "the conduit threads each TextChanged buffer across the three edit emits (ancestor-matched)");
    }

    @Test
    void reactivePlotterProgram_typeChecks() {
        Extensions.install(new GuiExtension());

        // Slice A (docs/reactive-gui.md): TextField fires TextChanged; the conduit folds the live
        // expression into a Model and emits the ISOLATED SetPlot to the retained ExprPlot (re-plotted
        // in place, never rebuilt). This only compiles + links (the ExprPlot/SetPlot shapes, the
        // conduit, the SetPlot sink); the live type→plot reframe is verified manually (needs GLFW):
        // exec:exec -Dptf=examples/reactive-plotter.ptf. Kept in sync with that example.
        CompileResult result = new PontifCompiler().compileAlt("""
                requires pontif.gui.{TextField, ExprPlot, window, GuiEvent, TextChanged, SetPlot}
                struct Model(expr:String)
                conduit app(e:GuiEvent, s:Model):Model from Model("x^2 - 4") -> (
                  let m2 = match e { [TextChanged] -> Model(e.text)  [_] -> s }
                  emit SetPlot("plot", m2.expr)
                  m2
                )
                main ( window({title = "Plotter"}, {
                  TextField("expr", "x^2 - 4"), ExprPlot("plot", "x^2 - 4")
                }) )""", "reactive-plotter.ptf");
        assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "reactive plotter program should type-check + link; got "
                        + (result instanceof CompileResult.Failed f ? f.error().text() : result));
    }

    @Test
    void multiExpressionCalculatorProgram_typeChecks() {
        Extensions.install(new GuiExtension());

        // Slice B1 (docs/reactive-gui.md): several TextFields, each firing TextChanged; the conduit
        // routes the edit to a Model slot (string == dispatch) and emits SetPlot with an AGGREGATE of
        // expressions, which the bridge composites into one plot. Only compiles + links (the multi
        // SetPlot payload, the per-id fold); the live multi-curve plot is verified manually (needs
        // GLFW): exec:exec -Dptf=examples/calculator-multi.ptf. Kept in sync with that example.
        CompileResult result = new PontifCompiler().compileAlt("""
                requires pontif.gui.{TextField, ExprPlot, Column, window, GuiEvent, TextChanged, SetPlot}
                struct Model(f0:String, f1:String, f2:String)
                conduit app(e:GuiEvent, s:Model):Model from Model("x^2 - 4", "sin(x)", "1 / x") -> (
                  let m2 = match e {
                    [TextChanged] -> match (e.id == "f0") {
                      [Bool:true]  -> Model(e.text, s.f1, s.f2)
                      [Bool:false] -> match (e.id == "f1") {
                        [Bool:true]  -> Model(s.f0, e.text, s.f2)
                        [Bool:false] -> Model(s.f0, s.f1, e.text)
                      }
                    }
                    [_] -> s
                  }
                  emit SetPlot("plot", {m2.f0, m2.f1, m2.f2})
                  m2
                )
                main ( window({title = "Calc"}, {
                  Column("start", "stretch", {
                    TextField("f0", "x^2 - 4"), TextField("f1", "sin(x)"), TextField("f2", "1 / x")
                  }),
                  ExprPlot("plot", "x^2 - 4")
                }) )""", "calculator-multi.ptf");
        assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "multi-expression calculator should type-check + link; got "
                        + (result instanceof CompileResult.Failed f ? f.error().text() : result));
    }

    @Test
    void reactiveStatusCommand_typeChecks() {
        Extensions.install(new GuiExtension());
        // The reactive Status command (docs/status.md) links + folds via the GuiEvent conduit.
        CompileResult result = new PontifCompiler().compileAlt("""
                requires pontif.gui.{GuiEvent, Clicked, Status}
                struct Model(n:Int)
                conduit app(e:GuiEvent, s:Model):Model from Model(0) -> (
                  emit Status("clicked", "good")  s
                )
                main ( emit Clicked("b")  0 )""", "reactive-status.ptf");
        assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "reactive Status program should link; got "
                        + (result instanceof CompileResult.Failed f ? f.error().text() : result));
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
