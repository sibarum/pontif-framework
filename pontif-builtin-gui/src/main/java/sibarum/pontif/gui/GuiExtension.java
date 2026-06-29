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
                requires pontif.events.{Event}
                exports @.{window, button, setBackground, setText, ClickEvent, ButtonEvent}

                # A click in the window, carrying the cursor position. An `action onClick(e:ClickEvent)`
                # reacts to it (slice-1e), reading e.x / e.y.
                struct ClickEvent(x:Int, y:Int)
                assign trait ClickEvent:Event{}

                # A button press, carrying the button's label (so multiple buttons are
                # distinguishable). An `action onButton(e:ButtonEvent)` reacts to it.
                struct ButtonEvent(label:String)
                assign trait ButtonEvent:Event{}

                # Opens a titled window, runs the GUI loop until closed, and fires a ClickEvent on
                # each left-click. Side-effect backed by DasumBridge.openWindow (bound by name);
                # the placeholder body is never run.
                function window(title:String):Stream[String] -> {}

                # Registers a button (call before window); clicking it fires a ButtonEvent(label).
                function button(label:String):Stream[String] -> {}

                # Sets the window background colour (components mod 256, scaled to [0,1]).
                function setBackground(r:Int, g:Int, b:Int):Stream[String] -> {}

                # Sets the centered text label's content (the next frame paints it).
                function setText(s:String):Stream[String] -> {}

                0
                """;
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        return Map.of(
                "window", DasumBridge::openWindow,
                "button", DasumBridge::button,
                "setBackground", DasumBridge::setBackground,
                "setText", DasumBridge::setText);
    }
}
