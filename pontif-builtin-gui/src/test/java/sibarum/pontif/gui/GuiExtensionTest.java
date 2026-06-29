package sibarum.pontif.gui;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.module.Extensions;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Headless-safe check of the GUI extension (docs/extensions.md): installing {@link GuiExtension}
 * makes {@code pontif.gui} linkable and registers {@code window} as a native call. The actual
 * window is verified manually (it needs a display + GLFW): run
 * {@code mvn -pl pontif-builtin-gui -am exec:exec -Dptf=examples/hello-window.ptf}. This test only
 * compiles a {@code requires pontif.gui} program and confirms the binding — it never opens a
 * window (no {@code window(...)} call is evaluated), so it is safe on a headless CI.
 */
class GuiExtensionTest {

    @Test
    void installingGuiExtension_makesPontifGuiLinkable_andBindsWindow() {
        Extensions.install(new GuiExtension());

        // The interactive + text + button surface links: window + button + setText + actions.
        CompileResult result = new PontifCompiler().compileAlt("""
                requires pontif.gui.{window, button, setText, ButtonEvent}
                action onButton(e:ButtonEvent) -> setText("clicked!")
                main (
                  let b = button("Press me")
                  window("headless compile check")
                )""", "gui.ptf");
        assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "pontif.gui should link; got "
                        + (result instanceof CompileResult.Failed f ? f.error().text() : result));

        assertNotNull(NativeCalls.get("window"), "window should be a registered native call");
        assertNotNull(NativeCalls.get("button"), "button should be registered");
        assertNotNull(NativeCalls.get("setBackground"), "setBackground should be registered");
        assertNotNull(NativeCalls.get("setText"), "setText should be registered");
    }
}
