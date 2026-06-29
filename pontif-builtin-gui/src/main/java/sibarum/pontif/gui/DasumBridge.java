package sibarum.pontif.gui;

import sibarum.dasum.gui.core.GlfwContext;
import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.em.EmContext;
import sibarum.dasum.gui.core.event.EventLoop;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.HoverState;
import sibarum.dasum.gui.core.input.InputState;
import sibarum.dasum.gui.core.layout.HitTest;
import sibarum.dasum.gui.core.layout.LatestLayout;
import sibarum.dasum.gui.core.layout.Layout;
import sibarum.dasum.gui.core.layout.LayoutResult;
import sibarum.dasum.gui.core.layout.PixelRect;
import sibarum.dasum.gui.core.layout.Render;
import sibarum.dasum.gui.core.render.Batcher;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.render.Projection;
import sibarum.dasum.gui.core.render.Texture;
import sibarum.dasum.gui.core.text.AtlasData;
import sibarum.dasum.gui.core.text.FontGroup;
import sibarum.dasum.gui.core.text.FontGroups;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.dasum.gui.core.window.Window;
import sibarum.dasum.gui.natives.gl.Gl;
import sibarum.dasum.gui.natives.glfw.Glfw;
import sibarum.dasum.gui.natives.glfw.GlfwCallbacks;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Java side of the GUI extension (docs/extensions.md), G5: a <b>declarative UI</b>. The
 * builder natives ({@link #label}/{@link #button}/{@link #column}) construct element
 * {@link RecordValue}s; {@link #openWindow} receives the root tree and {@link #toComponent walks
 * it} into dasum components, rendering it on the root thread until the window closes. State is the
 * record tree itself (Pontif values), so nothing opaque crosses the boundary. A button placed in
 * the tree fires a {@code ButtonEvent} via {@link NativeCalls.Context} (the slice-1e action leg).
 * Single window, single thread; construction only (reactive update is the next slice).
 */
public final class DasumBridge {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 600;
    private static final Color TEXT = new Color(0.92f, 0.92f, 0.96f, 1f);
    private static final Color TRANSPARENT = new Color(0f, 0f, 0f, 0f);
    private static final Color BACKGROUND = new Color(0.05f, 0.07f, 0.12f, 1f);

    /** The component captured on mouse-down, to confirm the release lands on the same one. */
    private static Component pressTarget;

    private DasumBridge() {}

    // --- Builder natives: each returns an element record the window walker interprets ----------

    /** {@code label({text = …})} → a Label element record. */
    public static Object label(List<Object> args, NativeCalls.Context ctx) {
        return element("pontif.gui/Label", "text", cfgStr(args, 0, "text"));
    }

    /** {@code button("…")} → a Button element record (positional label). */
    public static Object button(List<Object> args, NativeCalls.Context ctx) {
        String text = args.isEmpty() || !(args.get(0) instanceof StringValue s) ? "" : s.content();
        return element("pontif.gui/Button", "text", text);
    }

    /** {@code column({justify = …, align = …}, {children…})} → a Column element record. */
    public static Object column(List<Object> args, NativeCalls.Context ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("justify", new StringValue(cfgStr(args, 0, "justify")));
        m.put("align", new StringValue(cfgStr(args, 0, "align")));
        m.put("children", args.size() > 1 ? args.get(1) : emptyTuple());
        return new RecordValue("pontif.gui/Column", m);
    }

    private static RecordValue element(String type, String field, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(field, new StringValue(value));
        return new RecordValue(type, m);
    }

    // --- The window: walk the tree, then render it -------------------------------------------

    /**
     * {@code window({title = …}, {root…})}: build the dasum tree from the root record(s), open the
     * window, and block in the loop (root thread) until closed. Returns the inert for-effect result.
     */
    public static Object openWindow(List<Object> args, NativeCalls.Context ctx) {
        String title = cfgStr(args, 0, "title");
        if (title.isEmpty()) title = "Pontif";
        Object rootTree = args.size() > 1 ? args.get(1) : emptyTuple();

        try (GlfwContext glfw = GlfwContext.init();
             Window win = Window.create(WIDTH, HEIGHT, title);
             Batcher batcher = new Batcher()) {
            // Gl.load() before any GL call (texture upload); see the texture-ordering bugfix.
            Gl.load();
            batcher.init();
            EmContext.setDpiScale(win.contentScaleX());

            Texture fontTexture = Texture.fromPngResource("/dasum/atlas/primary.png");
            AtlasData atlas = AtlasData.loadFromResource("/dasum/atlas/primary.json");
            FontGroups.register(FontGroup.of(FontGroups.DEFAULT, atlas, fontTexture));

            // Build components after font + Em setup, so styled widgets resolve correctly.
            Component root = toComponent(rootTree, ctx);
            wireInput();

            EventLoop loop = new EventLoop(win, () -> {
                int fbW = win.framebufferWidth();
                int fbH = win.framebufferHeight();
                float[] projection = Projection.orthoTopLeft(fbW, fbH);
                Gl.glViewport(0, 0, fbW, fbH);
                Gl.glClearColor(BACKGROUND.r(), BACKGROUND.g(), BACKGROUND.b(), BACKGROUND.a());
                Gl.glClear(Gl.GL_COLOR_BUFFER_BIT);
                LayoutResult layout = Layout.compute(root, new PixelRect(0f, 0f, fbW, fbH));
                LatestLayout.store(root, layout);  // required so hit-testing has coordinates
                batcher.beginFrame(fbH);
                Render.render(root, layout, batcher, projection);
                batcher.endFrame(projection);
            });
            loop.run();  // blocks on this (root) thread until the window is closed
        }
        return new IrInterpreter.DriveResult();
    }

    /**
     * Recursively turns an element record (or a {@code _tuple} of them) into a dasum component.
     * The heart of the declarative UI: it switches on the record's (bare) type name. An unknown
     * node renders as a visible error label rather than failing silently.
     */
    private static Component toComponent(Object value, NativeCalls.Context ctx) {
        if (!(value instanceof RecordValue rv)) {
            return errorLabel("not a component: " + value);
        }
        return switch (bareType(rv.typeName())) {
            case "Label" -> new Component.Text(str(rv, "text"), Em.of(2f), TEXT);
            case "Button" -> {
                String t = str(rv, "text");
                yield Themed.button(t, Em.of(10f), Variant.PRIMARY, 0,
                        () -> ctx.fireEvent(element("pontif.gui/ButtonEvent", "label", t)));
            }
            case "Column" -> new Component.Flex(
                    null, null, Em.of(0.5f), TRANSPARENT,
                    Direction.COLUMN, justify(str(rv, "justify")), align(str(rv, "align")), Em.of(0.8f),
                    childrenOf(rv, ctx), false, 1);
            // A bare children aggregate (window's root arg) → an implicit centered column.
            case "_tuple" -> new Component.Flex(
                    null, null, Em.of(0.5f), TRANSPARENT,
                    Direction.COLUMN, JustifyContent.CENTER, AlignItems.CENTER, Em.of(0.8f),
                    tupleToComponents(rv, ctx), false, 1);
            default -> errorLabel("unknown component: " + bareType(rv.typeName()));
        };
    }

    /** Wires cursor hover + per-component click dispatch (the minimal dasum input pipeline). */
    private static void wireInput() {
        GlfwCallbacks.setCursorPosListener((w, x, y) -> {
            InputState.updateMousePos(x, y);
            LayoutResult lr = LatestLayout.result();
            Component r = LatestLayout.root();
            if (lr != null && r != null) {
                HoverState.update(HitTest.test(r, lr, (float) x, (float) y));
            }
        });
        GlfwCallbacks.setMouseButtonListener((w, button, action, mods) -> {
            if (button != Glfw.GLFW_MOUSE_BUTTON_LEFT) return;
            if (action == Glfw.GLFW_PRESS) {
                pressTarget = HoverState.hovered();
            } else if (action == Glfw.GLFW_RELEASE) {
                LayoutResult lr = LatestLayout.result();
                Component r = LatestLayout.root();
                Component released = (lr != null && r != null)
                        ? HitTest.test(r, lr, (float) InputState.mouseX(), (float) InputState.mouseY())
                        : null;
                if (pressTarget != null && released == pressTarget) {
                    Handlers.activate(pressTarget, r);  // fires the button's Runnable
                }
                pressTarget = null;
            }
        });
    }

    // --- helpers ------------------------------------------------------------------------------

    private static List<Component> childrenOf(RecordValue column, NativeCalls.Context ctx) {
        return column.members().get("children") instanceof RecordValue kids
                ? tupleToComponents(kids, ctx) : new ArrayList<>();
    }

    private static List<Component> tupleToComponents(RecordValue tuple, NativeCalls.Context ctx) {
        List<Component> out = new ArrayList<>();
        for (Object child : tuple.members().values()) out.add(toComponent(child, ctx));
        return out;
    }

    private static Component errorLabel(String message) {
        return new Component.Text(message, Em.of(1f), new Color(0.95f, 0.4f, 0.4f, 1f));
    }

    /** A config field as a String, or "" — {@code args[i]} is the config record {field = …}. */
    private static String cfgStr(List<Object> args, int i, String field) {
        return i < args.size() && args.get(i) instanceof RecordValue rv ? str(rv, field) : "";
    }

    private static String str(RecordValue rv, String field) {
        return rv.members().get(field) instanceof StringValue s ? s.content() : "";
    }

    private static String bareType(String typeName) {
        if (typeName == null) return "";
        int slash = typeName.lastIndexOf('/');
        return slash < 0 ? typeName : typeName.substring(slash + 1);
    }

    private static RecordValue emptyTuple() {
        return new RecordValue("_tuple", new LinkedHashMap<>());
    }

    private static JustifyContent justify(String s) {
        return switch (s) {
            case "center" -> JustifyContent.CENTER;
            case "end" -> JustifyContent.END;
            default -> JustifyContent.START;
        };
    }

    private static AlignItems align(String s) {
        return switch (s) {
            case "center", "middle" -> AlignItems.CENTER;
            case "end" -> AlignItems.END;
            case "stretch" -> AlignItems.STRETCH;
            default -> AlignItems.START;
        };
    }
}
