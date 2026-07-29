package sibarum.pontif.gui;

import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.ir.NativeFunctions;
import sibarum.pontif.runtime.module.Extension;

import java.util.Map;

/**
 * The GUI extension (docs/extensions.md) — the first external Pontif extension. <b>G5</b> is a
 * declarative UI: a program builds a tree of element <i>values</i> ({@code label}, {@code button},
 * {@code column}) and hands it to {@code window}, which the bridge walks into dasum components.
 * Elements are ordinary Pontif records (not opaque Java handles), so they {@code let}-bind, nest
 * in {@code {…}}, and pass as args. The UI is <b>reactive</b> (docs/reactive-gui.md): a placed
 * {@code Button} fires a {@code Clicked} notification; a {@code conduit} folds it into an app model
 * and re-emits {@code Draw(view(model))}; the {@code Draw} render sink ({@link #effects}) repaints
 * the window in place.
 *
 * <pre>
 *   requires pontif.gui.{Label, Button, Column, window, GuiEvent, Clicked, Draw}
 *   struct Model(count:Int)
 *   function view(m:Model):_ -> Column("center", "middle", { Label(m.count + ""), Button("increment") })
 *   conduit app(e:GuiEvent, s:Model):Model from Model(0) ->
 *     ( let m2 = match e { [Clicked] -> Model(s.count + 1)  [_] -> s }  emit Draw(view(m2))  m2 )
 *   main ( window({title = "Counter"}, view(Model(0))) )
 * </pre>
 *
 * {@code window} is the one native call; the render sink is the one native effect. Element records
 * are constructed directly in Pontif. See {@link GuiTree} for the reactive host (the loop rebuilds
 * from the latest published tree) and the click → {@code fireEvent(Clicked)} wiring.
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

    /**
     * The render sink (docs/reactive-gui.md, G3): {@code emit Draw(view(model))} routes here. It
     * extracts the {@code tree} element record and hands it to the reactive host, which stashes it +
     * wakes the window loop to repaint. The sink has NO {@code ctx} — it must not build components;
     * it only publishes the raw Pontif tree (component building happens on the root thread in the
     * window loop). Installed fully-qualified as {@code pontif.gui/Draw}.
     */
    @Override
    public Map<String, NativeFunctions.Effect> effects() {
        return Map.of("Draw", (event, origin) -> {
            if (event.members().get("tree") instanceof RecordValue tree) {
                GuiTree.publish(tree);
            }
        });
    }
}
