package sibarum.pontif.anybox;

import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.ir.NativeFunctions;
import sibarum.pontif.runtime.module.Extension;

import java.util.List;
import java.util.Map;

/**
 * Anybox — the {@code pontif.gui} extension on vexelray-gui (docs/anybox.md).
 *
 * <p>The Pontif-facing surface is ONE element. A program builds a tree of {@code Box} <i>values</i>,
 * styles them by chaining methods that each append one small style atom, and hands the root to
 * {@code window}. Boxes are ordinary Pontif records — they {@code let}-bind, nest in {@code {…}},
 * and pass as arguments — so the framework itself is written in Pontif ({@code pontif.gui.ptf}) and
 * the Java here is a walker, a window, and a sink.
 *
 * <pre>
 *   requires pontif.gui.{column, text, button, Rem, Role, window, GuiEvent, Clicked, SetText}
 *   struct Model(count:Int)
 *   conduit app(e:GuiEvent, s:Model):Model from Model(0) -&gt; (
 *     let m2 = match e { [Clicked] -&gt; Model(s.count + 1)  [_] -&gt; s }
 *     emit SetText("count", m2.count + "")
 *     m2 )
 *   main ( window({title = "Counter"},
 *            column({ text("0").id("count").textSize(Rem(2.0)), button("inc", "increment") })
 *              .gap(Rem(0.75)).padding(Rem(1.5)).background(Role.Panel)) )
 * </pre>
 *
 * <p>{@code window} is the one native call and {@code SetText} the one native effect. The loop
 * between them is the whole reactive story: a click fires a {@code Clicked} <i>notification</i>, a
 * {@code conduit} folds it into an app model held as a Pontif value, and the model emits a targeted
 * <i>command</i> that mutates exactly one retained widget. The tree is never rebuilt.
 */
public final class AnyboxExtension implements Extension {

    @Override
    public String moduleName() {
        return "pontif.gui";
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        return Map.of("window", AnyboxExtension::openWindow);
    }

    /**
     * The isolated-update sink: {@code emit SetText(id, text)} routes here and mutates just the
     * retained widget with that id. Future targeted commands (SetVisible, SetChildren, …) register
     * here alongside it; each is one line, because the addressing is already solved.
     */
    @Override
    public Map<String, NativeFunctions.Effect> effects() {
        return Map.of("SetText",
                (event, origin) -> AnyboxWindow.setText(Atoms.str(event, "id"), Atoms.str(event, "text")));
    }

    /**
     * {@code window(cfg, root)}: build the tree from {@code root}, open the window, and block in the
     * loop on the root thread until it closes. Absent {@code cfg} keys take their defaults.
     */
    private static Object openWindow(List<Object> args, NativeCalls.Context ctx) {
        Object cfg = args.isEmpty() ? null : args.get(0);
        Object root = args.size() > 1 ? args.get(1) : null;
        return AnyboxWindow.open(
                Atoms.cfgStr(cfg, "title", "Pontif"),
                Atoms.cfgInt(cfg, "width", 900),
                Atoms.cfgInt(cfg, "height", 600),
                root, ctx);
    }
}
