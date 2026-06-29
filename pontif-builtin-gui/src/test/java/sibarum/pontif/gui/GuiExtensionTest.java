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

        CompileResult result = new PontifCompiler().compileAlt("""
                requires pontif.gui.{label, button, column, window, ButtonEvent}
                requires pontif.events.{StdOut}
                action onButton(e:ButtonEvent) -> emit StdOut("hi")  e
                main (
                  let lbl = label({text = "hi"})
                  let btn = button("go")
                  window({title = "t"}, { column({justify = "center", align = "middle"}, {lbl, btn}) })
                )""", "gui.ptf");
        assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "declarative pontif.gui program should link; got "
                        + (result instanceof CompileResult.Failed f ? f.error().text() : result));

        assertNotNull(NativeCalls.get("label"), "label should be a registered native call");
        assertNotNull(NativeCalls.get("button"), "button should be registered");
        assertNotNull(NativeCalls.get("column"), "column should be registered");
        assertNotNull(NativeCalls.get("window"), "window should be registered");
    }
}
