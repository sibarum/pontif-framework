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
    public Map<String, NativeCalls.NativeCall> calls() {
        // window is the only native: elements are constructed directly in Pontif now.
        return Map.of("window", DasumBridge::openWindow);
    }
}
