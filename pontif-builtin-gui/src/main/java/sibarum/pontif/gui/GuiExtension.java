package sibarum.pontif.gui;

import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.module.Extension;

import java.util.Map;

/**
 * The GUI extension (docs/extensions.md) — the first external Pontif extension. <b>G5</b> is a
 * declarative UI: a program builds a tree of element <i>values</i> ({@code label}, {@code button},
 * {@code column}) and hands it to {@code window}, which the bridge walks into dasum components.
 * Elements are ordinary Pontif records (not opaque Java handles), so they {@code let}-bind, nest
 * in {@code {…}}, and pass as args. A button placed in the tree fires a {@code ButtonEvent} that a
 * slice-1e {@code action} reacts to.
 *
 * <pre>
 *   requires pontif.gui.{label, button, column, window, ButtonEvent}
 *   action onButton(e:ButtonEvent) -> emit StdOut("clicked")  e
 *   main (
 *     let lbl = label({text = "Press the Button"})
 *     let btn = button("Press me")
 *     let win = window({title = "Title"}, { column({justify = "center", align = "middle"}, {lbl, btn}) })
 *   )
 * </pre>
 *
 * The builders are native calls with permissive params: they build the element record in Java,
 * sidestepping record-arg and heterogeneous-children typing. Construction only — reactively
 * updating a rendered element (element identity) is the next slice.
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
                exports @.{label, button, column, window, Label, Button, Column, ButtonEvent, Clickable}

                # Element values, built by the functions below. The fields document the shape; the
                # native builders construct the records directly (so there is no construction-gate
                # or typing friction), and the bridge switches on the type name to render each.
                struct Label(text:String)
                struct Button(text:String)
                struct Column(justify:String, align:String)

                # A widget the bridge renders as a clickable button and invokes on click. Make your
                # own button by subtyping Button and assigning Clickable with an onClick that emits:
                #   struct PushButton:[Button](text:String)
                #   assign trait PushButton:Clickable { onClick():_ -> emit StdOut("hi")  this }
                trait Clickable { onClick():_ }

                # Fired when a button placed in the tree is clicked, carrying its label.
                struct ButtonEvent(label:String)
                assign trait ButtonEvent:Event{}

                # Builders (native-backed; placeholder bodies are never run). `_` params accept
                # the config record / children aggregate as-is; the bridge reads them in Java.
                function label(cfg:_):Label -> {}
                function button(text:String):Button -> {}
                function column(cfg:_, children:_):Column -> {}

                # Opens a window rendering the given root tree, on the root thread, until closed.
                function window(cfg:_, children:_):Stream[String] -> {}

                0
                """;
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        return Map.of(
                "label", DasumBridge::label,
                "button", DasumBridge::button,
                "column", DasumBridge::column,
                "window", DasumBridge::openWindow);
    }
}
