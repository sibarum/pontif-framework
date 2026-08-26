package sibarum.pontif.anybox;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.text.TextLayout;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.NativeCalls;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static sibarum.pontif.anybox.Atoms.*;

/**
 * The one pass that turns a Pontif {@code Box} tree into vexelray {@link Node}s (docs/anybox.md).
 *
 * <p>A Box is {@code (kind, style, children)}. The kind decides what to make; the style aggregate
 * is folded onto it left to right, so the last atom of a kind wins — {@code .padding(a).padding(b)}
 * is {@code b}, the way a stylesheet reads. Two atoms are consumed BEFORE the node exists rather
 * than folded onto it: {@code Ident} (which is identity, not appearance) and {@code Content} (which
 * a Text is made of and a Field is seeded with), so both are scanned out first and the fold skips
 * them.
 *
 * <p>The walk runs ONCE. What comes back is a retained tree whose id'd nodes are recorded in
 * {@link #byId}, and thereafter every update is isolated: a command names an id and posts one
 * mutation for one node. Nothing here is ever re-walked.
 */
final class BoxWalker {

    private final Gui gui;
    private final NativeCalls.Context ctx;

    /**
     * id → node, for the isolated update commands. A TreeMap because iteration order shows up in
     * diagnostics and a salted hash order would make two runs of the same program disagree.
     */
    private final Map<String, Node> byId = new TreeMap<>();

    /**
     * id → field, kept apart from {@link #byId} because a field's text lives in its Document, not
     * in the node's text prop — writing the node directly would bypass the caret and the undo log.
     */
    private final Map<String, TextField> fieldsById = new TreeMap<>();

    BoxWalker(Gui gui, NativeCalls.Context ctx) {
        this.gui = gui;
        this.ctx = ctx;
    }

    Map<String, Node> nodes() {
        return byId;
    }

    Map<String, TextField> fields() {
        return fieldsById;
    }

    /**
     * Walk one value into a node. A value that is not a Box renders as visible red text rather
     * than throwing: a malformed corner of a tree should show you where it is, not cost you the
     * window.
     */
    Node walk(Object value) {
        if (!(value instanceof RecordValue rv) || !"Box".equals(bareType(rv))) {
            return error("not a Box: " + value);
        }
        List<Object> atoms = items(rv.members().get("style"));
        String id = lastString(atoms, "Ident", "name");
        String content = lastString(atoms, "Content", "text");

        Node node = create(rv, id, content);
        for (Object atom : atoms) {
            if (atom instanceof RecordValue a) apply(node, a);
        }
        if (!id.isEmpty()) byId.put(id, node);
        return node;
    }

    /** The node a kind makes, before any styling. */
    private Node create(RecordValue box, String id, String content) {
        String kind = kindKey(box);
        return switch (kind) {
            case "TEXT" -> gui.text(content);
            case "BUTTON" -> makeButton(id, content);
            case "FIELD" -> makeField(id, content);
            case "ROW" -> withChildren(gui.row(), box);
            case "COLUMN" -> withChildren(gui.column(), box);
            default -> withChildren(gui.box(), box);
        };
    }

    private Node withChildren(Node node, RecordValue box) {
        for (Object child : items(box.members().get("children"))) {
            node.append(walk(child));
        }
        return node;
    }

    /**
     * A button: a centred label on a filled, lit, slightly raised box, whose click fires {@code
     * Clicked{id}} back into Pontif. Depth is the feedback — hover lifts it, pressing sets it flush
     * — and it costs nothing extra to draw, being another transfer function over the same SDF.
     *
     * <p>The look is applied here rather than left to the caller because a button with no styling
     * is not a button; every part of it is still overridable by an atom, since the fold runs after.
     */
    private Node makeButton(String id, String label) {
        Node b = gui.text(label)
                .height(Length.rem(2.25f))
                .padding(Length.rem(0.4f), Length.rem(1.0f))
                .background(gui.theme().color(Role.ACTION))
                .textColor(gui.theme().color(Role.ON_ACTION))
                .corner(Length.rem(0.625f))
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .lit(gui.theme().lit())
                .elevation(Length.rem(0.375f));
        gui.onState(b, state -> {
            b.background(gui.theme().color(Role.ACTION, state));
            b.elevation(switch (state) {
                case NORMAL -> Length.rem(0.375f);
                case HOVER -> Length.rem(0.625f);
                case PRESSED -> Length.ZERO;
            });
        });
        if (!id.isEmpty()) {
            gui.onClick(b, () -> ctx.fireEvent(event("pontif.gui/Clicked", "id", id)));
        }
        return b;
    }

    /**
     * An editable field. The widget owns its buffer — caret, selection, undo and the clipboard are
     * vexelray's, and re-deriving any of that here would be re-implementing what the platform
     * ships. Every edit fires {@code TextChanged{id, text}}; the conduit folds that and drives
     * OTHER widgets. Writing this same field back from the conduit would fight the caret.
     */
    private Node makeField(String id, String initial) {
        TextField f = new TextField(gui, initial);
        if (!id.isEmpty()) {
            fieldsById.put(id, f);
            f.onChange(s -> ctx.fireEvent(event("pontif.gui/TextChanged", "id", id, "text", s)));
        }
        return f.node();
    }

    /** Fold one style atom onto a node. Ident and Content were consumed at construction. */
    private void apply(Node node, RecordValue atom) {
        Map<String, Object> m = atom.members();
        switch (bareType(atom)) {
            case "Ident", "Content" -> { /* consumed before the node existed */ }
            case "Gap" -> node.gap(length(m.get("size")));
            case "Pad" -> node.padding(length(m.get("size")));
            case "Wide" -> node.width(length(m.get("size")));
            case "Tall" -> node.height(length(m.get("size")));
            case "Sized" -> node.textSize(length(m.get("size")));
            case "Rounds" -> node.corner(length(m.get("radius")));
            case "Raises" -> node.elevation(length(m.get("blur")));
            case "Fills" -> node.background(gui.theme().color(role(m.get("role"))));
            case "Inks" -> node.textColor(gui.theme().color(role(m.get("role"))));
            case "Edged" -> node.border(length(m.get("width")), gui.theme().color(role(m.get("role"))));
            case "Glows" -> node.lit(bool(atom, "on"));
            case "Shows" -> node.visible(bool(atom, "on"));
            case "Runs" -> node.justify(justify(m.get("way")));
            case "Lines" -> node.alignItems(align(m.get("way")));
            case "Scrolls" -> node.scroll(bool(atom, "x"), bool(atom, "y"));
            default -> System.err.println("[anybox] unknown style atom: " + bareType(atom));
        }
    }

    /** The last atom of {@code type}'s {@code field}, or "" — last-wins, like every other atom. */
    private static String lastString(List<Object> atoms, String type, String field) {
        String found = "";
        for (Object atom : atoms) {
            if (atom instanceof RecordValue rv && type.equals(bareType(rv))) found = str(rv, field);
        }
        return found;
    }

    private static String kindKey(RecordValue box) {
        return box.members().get("kind") instanceof RecordValue k ? str(k, "key") : "BOX";
    }

    private Node error(String message) {
        return gui.text(message).textColor(gui.theme().color(Role.DANGER));
    }
}
