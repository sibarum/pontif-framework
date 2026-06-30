package sibarum.pontif.gui;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.module.Extensions;

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
                struct PushButton:[Button](text:String)
                assign trait PushButton:Clickable { onClick():_ -> emit StdOut("hi")  this }
                main (
                  let lbl = Label("hi")
                  let btn = PushButton("go")
                  window({title = "t"}, { Column("center", "middle", {lbl, btn}) })
                )""", "gui.ptf");
        assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "capitalized-element pontif.gui program should link; got "
                        + (result instanceof CompileResult.Failed f ? f.error().text() : result));

        assertNotNull(NativeCalls.get("window"), "window should be the registered native call");
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
