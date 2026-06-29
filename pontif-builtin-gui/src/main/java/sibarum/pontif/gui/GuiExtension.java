package sibarum.pontif.gui;

import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.module.Extension;

import java.util.Map;

/**
 * The GUI extension (docs/extensions.md) — the first external Pontif extension. Its Pontif-side
 * interface declares the {@code window} function (placeholder body); its associated Java object
 * is {@link DasumBridge#openWindow}, bound by name. Install it via
 * {@code Extensions.install(new GuiExtension())} (the {@link GuiLauncher} does this) before
 * compiling a program that {@code requires pontif.gui}.
 *
 * <p>G1 exposes one call: {@code window(title)} opens a titled window and blocks until closed.
 * Richer surface (text/widgets) and interactivity (events/actions/Property) are later slices.
 */
public final class GuiExtension implements Extension {

    @Override
    public String moduleName() {
        return "pontif.gui";
    }

    @Override
    public String pontifSource() {
        return """
                requires pontif.core.{Stream}
                exports @.{window}

                # Opens a titled window and runs the GUI loop until closed (a side-effect backed
                # by DasumBridge.openWindow, bound by name). The placeholder body is never run —
                # the resolved call runs the extension's Java object instead.
                function window(title:String):Stream[String] -> {}

                0
                """;
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        return Map.of("window", DasumBridge::openWindow);
    }
}
