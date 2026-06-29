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

        // The interactive surface links: window + setBackground + an action on ClickEvent.
        CompileResult result = new PontifCompiler().compileAlt("""
                requires pontif.gui.{window, setBackground, ClickEvent}
                action onClick(e:ClickEvent) -> setBackground(e.x, e.y, 128)
                main window("headless compile check")""", "gui.ptf");
        assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "pontif.gui should link; got "
                        + (result instanceof CompileResult.Failed f ? f.error().text() : result));

        assertNotNull(NativeCalls.get("window"), "window should be a registered native call");
        assertNotNull(NativeCalls.get("setBackground"), "setBackground should be registered");
    }
}
