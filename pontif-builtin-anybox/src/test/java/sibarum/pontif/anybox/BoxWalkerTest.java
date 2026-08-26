package sibarum.pontif.anybox;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walk from a Pontif {@code Box} to a vexelray node tree, headless.
 *
 * <p>No Vulkan is involved: a {@link Gui} is a retained model and a layout pass, and only
 * {@code GuiApp} needs a device. So the fold can be run and then <em>read back</em> — {@link
 * Gui#frame} reconciles the posted mutations into the retained tree this asserts against, which is
 * the same tree the renderer consumes.
 *
 * <p>Boxes are built here as records rather than compiled from Pontif on purpose: {@link
 * BoxSurfaceTest} already covers what the surface produces, and this covers what the bridge does
 * with it. Between them the boundary is pinned from both sides.
 */
class BoxWalkerTest {

    /** Every glyph one unit wide and one line tall — enough for layout to have something to do. */
    private static final TextMeasurer MEASURER = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode node, Axis axis, float textSizePx) {
            String s = node.textString();
            return axis == Axis.HORIZONTAL ? (s == null ? 0 : s.length()) * textSizePx * 0.5f : textSizePx;
        }
    };

    // ---------------------------------------------------------------- building Pontif values

    private static RecordValue rec(String type, Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return new RecordValue(type, m);
    }

    private static RecordValue tuple(Object... items) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i++) m.put("_" + i, items[i]);
        return new RecordValue("_tuple", m);
    }

    private static RecordValue kind(String key) {
        return rec("pontif.gui/Kind$X", "key", new StringValue(key));
    }

    private static RecordValue role(String key) {
        return rec("pontif.gui/Role$X", "key", new StringValue(key));
    }

    private static RecordValue rem(double v) {
        return rec("pontif.gui/Rem", "v", BigDecimal.valueOf(v));
    }

    private static RecordValue box(String kindKey, RecordValue style, RecordValue children) {
        return rec("pontif.gui/Box", "kind", kind(kindKey), "style", style, "children", children);
    }

    private static RetainedNode frame(Gui gui) {
        return gui.frame(800f, 600f, MEASURER);
    }

    private static RetainedNode only(RetainedNode parent) {
        assertEquals(1, parent.children.size(), "expected exactly one child");
        return parent.children.get(0);
    }

    // ---------------------------------------------------------------- the walk

    @Test
    void aColumnBecomesABoxWithItsChildrenInOrder() {
        try (Gui gui = new Gui()) {
            Node root = new BoxWalker(gui, null).walk(box("COLUMN", tuple(), tuple(
                    box("TEXT", tuple(rec("pontif.gui/Content", "text", new StringValue("first"))), tuple()),
                    box("TEXT", tuple(rec("pontif.gui/Content", "text", new StringValue("second"))), tuple()))));
            gui.root().children(root);

            RetainedNode column = only(frame(gui));
            assertEquals(NodeKind.BOX, column.kind);
            assertEquals(2, column.children.size());
            assertEquals("first", column.children.get(0).textString());
            assertEquals("second", column.children.get(1).textString());
            assertEquals(NodeKind.TEXT, column.children.get(0).kind);
        }
    }

    /** The fold is what turns atoms into pixels — each one lands on the property it names. */
    @Test
    void everyStyleAtomFoldsOntoItsProperty() {
        try (Gui gui = new Gui()) {
            Node root = new BoxWalker(gui, null).walk(box("BOX", tuple(
                    rec("pontif.gui/Fills", "role", role("PANEL")),
                    rec("pontif.gui/Rounds", "radius", rem(1.0)),
                    rec("pontif.gui/Raises", "blur", rem(2.0)),
                    rec("pontif.gui/Glows", "on", Boolean.TRUE)), tuple()));
            gui.root().children(root);

            RetainedNode n = only(frame(gui));
            assertEquals(gui.theme().color(Role.PANEL), n.background());
            assertTrue(n.cornerPx > 0f, "a corner radius resolves to pixels");
            assertTrue(n.elevationPx > 0f, "an elevation resolves to pixels");
            assertTrue(n.lit(), "Glows(true) draws the fill lit");
        }
    }

    /** Last wins: both atoms are applied in order, so the final one is what the node ends up with. */
    @Test
    void repeatedAtomsResolveToTheLastOne() {
        try (Gui gui = new Gui()) {
            Node root = new BoxWalker(gui, null).walk(box("BOX", tuple(
                    rec("pontif.gui/Fills", "role", role("PANEL")),
                    rec("pontif.gui/Fills", "role", role("DANGER"))), tuple()));
            gui.root().children(root);

            assertEquals(gui.theme().color(Role.DANGER), only(frame(gui)).background());
        }
    }

    /** An Ident makes a box addressable; that registry is the whole of how an update finds a node. */
    @Test
    void identRegistersTheNodeForIsolatedUpdates() {
        try (Gui gui = new Gui()) {
            BoxWalker walker = new BoxWalker(gui, null);
            Node root = walker.walk(box("COLUMN", tuple(), tuple(
                    box("TEXT", tuple(
                            rec("pontif.gui/Content", "text", new StringValue("0")),
                            rec("pontif.gui/Ident", "name", new StringValue("count"))), tuple()))));
            gui.root().children(root);

            Node counted = walker.nodes().get("count");
            assertNotNull(counted, "an id'd box is registered under its id");
            assertEquals("0", only(only(frame(gui))).textString());

            counted.text("42");
            assertEquals("42", only(only(frame(gui))).textString(),
                    "the update lands on that node without the tree being rebuilt");
        }
    }

    /** A box with no Ident is deliberately not addressable — most boxes are pure layout. */
    @Test
    void aBoxWithoutAnIdentIsNotRegistered() {
        try (Gui gui = new Gui()) {
            BoxWalker walker = new BoxWalker(gui, null);
            walker.walk(box("BOX", tuple(), tuple()));
            assertTrue(walker.nodes().isEmpty());
        }
    }

    /** A malformed corner shows you where it is rather than costing the window. */
    @Test
    void aNonBoxRendersAsVisibleError() {
        try (Gui gui = new Gui()) {
            Node root = new BoxWalker(gui, null).walk(new StringValue("not a box"));
            gui.root().children(root);

            RetainedNode n = only(frame(gui));
            assertEquals(NodeKind.TEXT, n.kind);
            assertTrue(n.textString().startsWith("not a Box:"));
        }
    }
}
