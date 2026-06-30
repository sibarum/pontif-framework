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
                exports @.{Label, Button, Column, LinePlot, window, ButtonEvent, Clickable}

                # Elements are ordinary structs you CONSTRUCT directly — Label{text = "hi"},
                # Button{text = "go"}, Column{justify = "center", align = "middle", children = {a, b}}.
                # The bridge walks the resulting record tree, switching on each element's type name.
                # children is `_` so any aggregate of elements fits (it is validated structurally as
                # the bridge renders it).
                struct Label(text:String)
                struct Button(text:String)
                struct Column(justify:String, align:String, children:_)

                # A 2D line chart over two number aggregates: LinePlot({0.0,1.0,4.0}, {0.0,1.0,2.0}).
                # The bridge converts xs/ys to double[] and renders them in a Component.SceneView
                # (dasum-vis), with pan-on-drag / zoom-on-scroll. `_` so any numeric aggregate fits.
                struct LinePlot(xs:_, ys:_)

                # A widget the bridge renders as a clickable button and invokes on click. Make your
                # own button by subtyping Button and assigning Clickable with an onClick that emits:
                #   struct PushButton:[Button](text:String)
                #   assign trait PushButton:Clickable { onClick():_ -> emit StdOut("hi")  this }
                trait Clickable { onClick():_ }

                # Fired when a button placed in the tree is clicked, carrying its label.
                struct ButtonEvent(label:String)
                assign trait ButtonEvent:Event{}

                # The window action (native; the placeholder body is never run): opens a window
                # rendering the given root tree on the root thread, until closed.
                function window(cfg:_, children:_):Stream[String] -> {}

                0
                """;
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        // window is the only native: elements are constructed directly in Pontif now.
        return Map.of("window", DasumBridge::openWindow);
    }
}
