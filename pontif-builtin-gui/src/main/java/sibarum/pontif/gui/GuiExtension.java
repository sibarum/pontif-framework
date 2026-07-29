package sibarum.pontif.gui;

import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.ir.NativeFunctions;
import sibarum.pontif.runtime.module.Extension;

import java.util.Map;

import static sibarum.pontif.gui.GuiShared.str;

/**
 * The GUI extension (docs/extensions.md) — the first external Pontif extension. <b>G5</b> is a
 * declarative UI: a program builds a tree of element <i>values</i> ({@code Label}, {@code Button},
 * {@code Column}) and hands it to {@code window}, which the bridge walks into a dasum component tree
 * <b>once</b>. Elements are ordinary Pontif records (not opaque Java handles), so they
 * {@code let}-bind, nest in {@code {…}}, and pass as args. The UI is <b>reactive</b> the way dasum is
 * built for (docs/reactive-gui.md): the tree is retained, and updates are ISOLATED — a placed
 * {@code Button} fires a {@code Clicked} notification, a {@code conduit} folds it into an app model
 * and emits a targeted command ({@code SetText(id, …)}), and the {@code SetText} sink ({@link
 * #effects}) mutates just that one widget in place. The tree is never rebuilt.
 *
 * <pre>
 *   requires pontif.gui.{Label, Button, Column, window, GuiEvent, Clicked, SetText}
 *   struct Model(count:Int)
 *   conduit app(e:GuiEvent, s:Model):Model from Model(0) ->
 *     ( let m2 = match e { [Clicked] -> Model(s.count + 1)  [_] -> s }
 *       emit SetText("count", m2.count + "")  m2 )
 *   main ( window({title = "Counter"},
 *            { Column("center", "middle", { Label("count", "0"), Button("inc", "increment") }) }) )
 * </pre>
 *
 * {@code window} is the one native call; the {@code SetText} render sink is the one native effect.
 * See {@link GuiTree} for the retained-tree widget registry (id → component), the isolated
 * {@code setText} update, and the click → {@code fireEvent(Clicked)} wiring.
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
     * The isolated-update sink (docs/reactive-gui.md): {@code emit SetText(id, text)} routes here and
     * mutates just the retained widget with that id, via {@link GuiTree#setText} (dasum's identity-
     * keyed {@code TextStates} + invalidate) — no rebuild. The sink has NO {@code ctx}: it addresses
     * an already-built component by id rather than constructing anything. Installed fully-qualified as
     * {@code pontif.gui/SetText}. Future targeted commands (SetChecked, SetChildren, …) register here
     * alongside it.
     */
    @Override
    public Map<String, NativeFunctions.Effect> effects() {
        return Map.of("SetText", (event, origin) -> GuiTree.setText(str(event, "id"), str(event, "text")));
    }
}
