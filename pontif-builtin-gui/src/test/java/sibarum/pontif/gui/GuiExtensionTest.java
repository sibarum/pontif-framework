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
}
