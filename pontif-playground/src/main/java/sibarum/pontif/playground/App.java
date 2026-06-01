package sibarum.pontif.playground;

import sibarum.dasum.gui.core.GlfwContext;
import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.dialog.FileDialog;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.em.EmContext;
import sibarum.dasum.gui.core.event.EventLoop;
import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.input.CursorManager;
import sibarum.dasum.gui.core.input.FocusState;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.HoverState;
import sibarum.dasum.gui.core.input.InputState;
import sibarum.dasum.gui.core.input.ScrollStates;
import sibarum.dasum.gui.core.input.ScrollbarController;
import sibarum.dasum.gui.core.input.TextInputController;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.layout.HitTest;
import sibarum.dasum.gui.core.layout.LatestLayout;
import sibarum.dasum.gui.core.layout.Layout;
import sibarum.dasum.gui.core.layout.LayoutResult;
import sibarum.dasum.gui.core.layout.PixelRect;
import sibarum.dasum.gui.core.layout.Render;
import sibarum.dasum.gui.core.overlay.OverlayStack;
import sibarum.dasum.gui.core.render.Batcher;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.render.DrawCommand;
import sibarum.dasum.gui.core.render.Projection;
import sibarum.dasum.gui.core.render.Texture;
import sibarum.dasum.gui.core.status.Status;
import sibarum.dasum.gui.core.text.AtlasData;
import sibarum.dasum.gui.core.text.FontGroup;
import sibarum.dasum.gui.core.text.FontGroups;
import sibarum.dasum.gui.core.text.Icon;
import sibarum.dasum.gui.core.theme.Palette;
import sibarum.dasum.gui.core.theme.Theme;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.dasum.gui.core.window.Window;
import sibarum.dasum.gui.natives.gl.Gl;
import sibarum.dasum.gui.natives.glfw.Glfw;
import sibarum.dasum.gui.natives.glfw.GlfwCallbacks;
import sibarum.pontif.playground.generated.Icons;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.ReceiptGraphReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static sibarum.dasum.gui.natives.gl.Gl.GL_COLOR_BUFFER_BIT;

/**
 * Pontif Playground — a minimal GUI for typing Pontif source, running it on
 * a background thread, and seeing the result (or origin-tagged error) flash
 * in the status ribbon at the bottom. Click the ribbon for the full event
 * log. The two execution paths in pontif-ir (interpreter and Truffle
 * lowering) are both available via PontifRunner; this app uses the
 * interpreter so errors carry their IR origins straight through.
 */
public final class App {

    // --- Palette ---
    private static final Color FRAME_BG    = new Color(0.05f, 0.06f, 0.09f, 1f);
    private static final Color TOOLBAR_BG  = new Color(0.13f, 0.14f, 0.18f, 1f);
    private static final Color EDITOR_BG   = new Color(0.07f, 0.09f, 0.13f, 1f);
    private static final Color CODE_FG     = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color LABEL_FG    = new Color(0.70f, 0.75f, 0.82f, 1f);

    /** Font group key for the monospace atlas (registered alongside the primary one). */
    private static final String MONO_FONT_GROUP = "mono";

    private static final String UNTITLED_LABEL = "(untitled)";
    private static final String DEFAULT_FILE_NAME = "untitled.ptf";
    private static final List<FileDialog.Filter> PTF_FILTERS = List.of(
            FileDialog.Filter.of("Pontif source", "ptf"),
            FileDialog.Filter.of("All files", "*"));
    private static final List<FileDialog.Filter> RECEIPTS_FILTERS = List.of(
            FileDialog.Filter.of("Receipt-graph report", "txt"),
            FileDialog.Filter.of("All files", "*"));

    private static final float WHEEL_PIXELS_PER_STEP = 40f;

    private static final String DEFAULT_CODE = """
            # Pontif quick tour — click Run to compile and evaluate this module.
            # Pontif PROVES every declared return refinement at compile time, or
            # rejects it. When the built-in prover falls short, you hand it a
            # proof. Comments start with #.

            module tour

            # Most returns prove themselves. inc's declared return [Int:@>1] is
            # a linear bound: given x >= 1, the engine sees x + 1 lands in
            # [2, infinity) and clears the > 1 bar on its own — no help needed.
            function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1

            # Some don't. quirk(x) = x * (x - 1) is the product of two
            # consecutive integers, so it's always >= 0 — but it's an opaque
            # product, and the built-in engine can't see that. Declaring
            # [Int:@>=0] would be rejected on its own. So we hand it a PROOF.

            # A proof is a tree of case-splits, built from these two structs.
            # (Split refers to itself through the [Leaf|Split] union, so this is
            # also a recursive type — lists and trees work the same way.)
            struct Leaf()
            struct Split(p:Bool, whenTrue:[Leaf|Split], whenFalse:[Leaf|Split])

            # Split on x >= 1; each leaf is then within the engine's reach:
            #   x >= 1  ->  both factors >= 0, so the product >= 0
            #   x <  1  ->  x <= 0 and x - 1 <= -1, product of two negatives >= 0
            # The combinators are conservative: a bogus split can never validate,
            # so a proof rescues a true-but-hard return but never launders a
            # false one. (Delete the proof line and Run — quirk is rejected.)
            function quirk(x:Int):[Int:@>=0] -> x * (x - 1)
            proof quirk = Split(x >= 1, Leaf(), Leaf())

            # Main expression — runs when you click Run.
            # inc(4) = 5, quirk(5) = 20.  Sum: 25.
            inc(4) + quirk(5)
            """;

    // Component references held in static fields so the toolbar's click
    // handler and the worker thread can find them without rebuilding the
    // tree (rebuilding would break identity-keyed state).
    private static Component.Text codeText;
    private static Component.Text filenameLabel;

    // Hoisted so file-dialog button handlers can reach it. Lifetime is
    // bounded by main()'s try-with-resources; handlers only fire while the
    // event loop is running, so the field is always non-null when read.
    private static Window window;

    private static Component pressTarget = null;
    private static Path currentFile = null;

    private static final PontifCompiler COMPILER = new PontifCompiler();
    private static final PontifRunner RUNNER = new PontifRunner();

    public static void main(String[] args) {
        try (GlfwContext ctx = GlfwContext.init();
             Window win = Window.create(1100, 720, "Pontif Playground");
             Batcher batcher = new Batcher();
             CursorManager cursors = new CursorManager(win.handle().address())) {
            window = win;

            Gl.load();
            batcher.init();
            cursors.init();
            EmContext.setDpiScale(win.contentScaleX());
            applyTheme();

            try (Texture primaryTexture = Texture.fromPngResource("/dasum/atlas/primary.png");
                 Texture monoTexture    = Texture.fromPngResource("/dasum/atlas/mono.png");
                 Texture iconsTexture   = Texture.fromPngResource("/dasum/atlas/icons.png")) {
                AtlasData primaryAtlas = AtlasData.loadFromResource("/dasum/atlas/primary.json");
                AtlasData monoAtlas    = AtlasData.loadFromResource("/dasum/atlas/mono.json");
                AtlasData iconsAtlas   = AtlasData.loadFromResource("/dasum/atlas/icons.json");
                FontGroups.register(FontGroup.of(FontGroups.DEFAULT,        primaryAtlas, primaryTexture));
                FontGroups.register(FontGroup.of(MONO_FONT_GROUP,           monoAtlas,    monoTexture));
                FontGroups.register(FontGroup.of(Icon.DEFAULT_FONT_GROUP,   iconsAtlas,   iconsTexture));

                Status.setDefaultMessage(
                    "Pontif Playground — edit code, press Run.  Click here to view the event log.",
                    Variant.DEFAULT);
                Status.setCloseIcon(Icons.X);
                Component root = Status.wrap(buildUi());
                wireInput(win, cursors);

                EventLoop loop = new EventLoop(win, () -> {
                    int fbW = win.framebufferWidth();
                    int fbH = win.framebufferHeight();
                    float[] projection = Projection.orthoTopLeft(fbW, fbH);

                    Gl.glViewport(0, 0, fbW, fbH);
                    Gl.glClearColor(0.03f, 0.03f, 0.05f, 1f);
                    Gl.glClear(GL_COLOR_BUFFER_BIT);

                    PixelRect viewport = new PixelRect(0f, 0f, fbW, fbH);
                    LayoutResult mainLayout = Layout.compute(root, viewport);
                    // Merge overlay rects (status log dialog, etc.) into the same
                    // LayoutResult so hit-testers and Render share one layout.
                    java.util.Map<Component, PixelRect> mergedRects =
                            new java.util.IdentityHashMap<>(mainLayout.rects());
                    OverlayStack.layoutInto(mergedRects, viewport);
                    LayoutResult layout = new LayoutResult(mergedRects);
                    LatestLayout.store(root, layout);

                    batcher.beginFrame(fbH);
                    Render.render(root, layout, batcher, projection);
                    if (OverlayStack.isActive()) {
                        batcher.flush(projection);
                        if (OverlayStack.anyModal()) {
                            batcher.submit(new DrawCommand.ColoredQuad(
                                viewport.x(), viewport.y(), viewport.width(), viewport.height(),
                                Theme.overlayBackdrop()));
                            batcher.flush(projection);
                        }
                        for (OverlayStack.Overlay o : OverlayStack.active()) {
                            Render.render(o.component(), layout, batcher, projection);
                            batcher.flush(projection);
                        }
                    }
                    batcher.endFrame(projection);
                });
                loop.run();
            }
        }
    }

    /** Tweak palettes for variants used by this app. Process-global; runs once at startup. */
    private static void applyTheme() {
        // DEFAULT is the secondary-button look (Open / Save / Save As). The framework
        // ships a muted slate-gray (#6c757d) that risks reading as disabled against
        // our dark frame. Bump it to a deeper slate-blue with a brighter onBase.
        Theme.override(Variant.DEFAULT, new Palette(
            new Color(0.275f, 0.337f, 0.420f, 1f),   // base — slate-blue 600-ish
            new Color(0.749f, 0.804f, 0.871f, 1f),   // emphasis — slate-blue 200-ish
            new Color(0.97f, 0.98f, 1.00f, 1f)));    // onBase — near-white
    }

    private static Component buildUi() {
        Component runBtn      = Themed.button("Run",      Em.of(5f), Variant.PRIMARY,  0, App::onRunClicked);
        Component receiptsBtn = Themed.button("Receipts", Em.of(7f), Variant.INFO,     0, App::onReceiptsClicked);
        Component openBtn     = Themed.button("Open",     Em.of(5f), Variant.DEFAULT,  0, App::onOpenClicked);
        Component saveBtn     = Themed.button("Save",     Em.of(5f), Variant.DEFAULT,  0, App::onSaveClicked);
        Component saveAsBtn   = Themed.button("Save As",  Em.of(6f), Variant.DEFAULT,  0, App::onSaveAsClicked);

        filenameLabel = new Component.Text(
            UNTITLED_LABEL, FontGroups.DEFAULT, Em.of(0.9f), LABEL_FG,
            null, null, Em.of(0.3f),
            null, true,
            false, false, false, false, 1);

        Component toolbar = new Component.Flex(
            null, Em.of(3f), Em.of(0.5f), TOOLBAR_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.5f),
            List.of(runBtn, receiptsBtn, openBtn, saveBtn, saveAsBtn, filenameLabel),
            false, 0);

        // Editable code editor — monospace, accepts tab, wraps to its pane width.
        codeText = new Component.Text(
            DEFAULT_CODE, MONO_FONT_GROUP, Em.of(0.95f), CODE_FG,
            null, null, Em.of(0.5f),
            null, false,
            true, true, true, true, 1);

        Component codePane = new Component.Scroll(null, null, Em.ZERO, EDITOR_BG, codeText, false, 1);

        return new Component.Flex(
            null, null, Em.of(0.5f), FRAME_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.5f),
            List.of(toolbar, codePane),
            false, 0);
    }

    private static void onRunClicked() {
        String code = TextStates.contentOf(codeText);
        String sourceName = currentFile != null ? currentFile.getFileName().toString() : "<editor>";
        long startNs = System.nanoTime();
        Thread worker = new Thread(() -> {
            PontifRunner.RunResult result = RUNNER.run(
                    COMPILER.compileAlt(code, sourceName),
                    PontifRunner.Engine.INTERPRETER);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            if (result.isError()) {
                // First line in the ribbon (immediate signal), full text in the
                // log dialog (one click away).
                String firstLine = result.text().split("\\R", 2)[0];
                Status.error(firstLine + " (" + elapsedMs + " ms)", result.text());
            } else {
                Status.success("Ran " + sourceName + " in " + elapsedMs + " ms → " + result.text());
            }
        }, "pontif-runner");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Drafts the receipt-graph for the current editor source, flashes the
     * full report into the status log (click the ribbon to read it), then
     * offers a save dialog to write it to disk. Drafting is bounded
     * transcription + sign-analysis discharge — fast and terminating, so
     * (unlike Run) it's fine on the GLFW main thread, which the FileDialog
     * requires anyway.
     */
    private static void onReceiptsClicked() {
        String code = TextStates.contentOf(codeText);
        String sourceName = currentFile != null ? currentFile.getFileName().toString() : "<editor>";

        ReceiptGraphReport.Result result = ReceiptGraphReport.fromAltSource(code, sourceName);
        if (result instanceof ReceiptGraphReport.Result.Generated generated) {
            String report = generated.text();
            Status.log(
                "Receipt-graph drafted for " + sourceName + " — click to view; save dialog follows.",
                report, Variant.SUCCESS);
            FileDialog.save(window, RECEIPTS_FILTERS, dialogStartPath(), receiptsDefaultName(sourceName))
                .ifPresent(path -> {
                    try {
                        Files.writeString(path, report, StandardCharsets.UTF_8);
                        Status.success("Wrote receipt-graph report to " + path.getFileName());
                    } catch (IOException e) {
                        Status.error("Error writing " + path.getFileName() + ": " + e.getMessage(),
                                path.toString());
                    }
                });
        } else {
            String error = ((ReceiptGraphReport.Result.Failed) result).error();
            Status.error("Receipt-graph: " + error.split("\\R", 2)[0], error);
        }
    }

    private static String receiptsDefaultName(String sourceName) {
        String base = sourceName;
        if (base.endsWith(".ptf")) {
            base = base.substring(0, base.length() - ".ptf".length());
        }
        if (base.isBlank() || base.equals("<editor>")) {
            base = "untitled";
        }
        return base + ".receipts.txt";
    }

    // --- File operations: must run on the GLFW main thread (FileDialog requirement). ---

    private static void onOpenClicked() {
        FileDialog.open(window, PTF_FILTERS, dialogStartPath()).ifPresent(path -> {
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                TextStates.setContent(codeText, content);
                currentFile = path;
                updateFilenameLabel();
                Status.success("Opened " + path.getFileName());
            } catch (IOException e) {
                Status.error("Error opening " + path.getFileName() + ": " + e.getMessage(), path.toString());
            }
        });
    }

    private static void onSaveClicked() {
        if (currentFile == null) {
            onSaveAsClicked();
            return;
        }
        writeCurrent(currentFile);
    }

    private static void onSaveAsClicked() {
        String defaultName = currentFile != null
                ? currentFile.getFileName().toString()
                : DEFAULT_FILE_NAME;
        FileDialog.save(window, PTF_FILTERS, dialogStartPath(), defaultName).ifPresent(path -> {
            currentFile = path;
            updateFilenameLabel();
            writeCurrent(path);
        });
    }

    private static void writeCurrent(Path path) {
        try {
            String content = TextStates.contentOf(codeText);
            Files.writeString(path, content, StandardCharsets.UTF_8);
            Status.success("Saved " + path.getFileName());
        } catch (IOException e) {
            Status.error("Error saving " + path.getFileName() + ": " + e.getMessage(), path.toString());
        }
    }

    private static Path dialogStartPath() {
        if (currentFile != null && currentFile.getParent() != null) {
            return currentFile.getParent();
        }
        return Path.of(System.getProperty("user.dir"));
    }

    private static void updateFilenameLabel() {
        String label = currentFile == null
                ? UNTITLED_LABEL
                : currentFile.getFileName().toString();
        TextStates.setContent(filenameLabel, label);
    }

    // --- Input wiring: GLFW callbacks → framework controllers ---
    // Slimmed down from the demo's wireInput; covers text editing, scrolling,
    // hover/focus/click, and standard shortcuts (Ctrl+C/V/X/A/Z/Y, zoom).

    private static void wireInput(Window window, CursorManager cursors) {
        GlfwCallbacks.setKeyListener((win, key, scancode, action, mods) -> {
            InputState.setMods(mods);
            if (action != Glfw.GLFW_PRESS && action != Glfw.GLFW_REPEAT) return;
            boolean ctrl  = (mods & Glfw.GLFW_MOD_CONTROL) != 0;
            boolean shift = (mods & Glfw.GLFW_MOD_SHIFT)   != 0;

            // Clipboard / undo shortcuts first.
            if (ctrl && key == 'C' && TextInputController.onCopy(window.handle())) return;
            if (ctrl && key == 'X' && TextInputController.onCut(window.handle()))  return;
            if (ctrl && key == 'V' && TextInputController.onPaste(window.handle())) return;
            if (ctrl && key == 'A' && TextInputController.onSelectAll())            return;
            if (ctrl && key == 'Z') {
                if (shift) { if (TextInputController.onRedo()) return; }
                else       { if (TextInputController.onUndo()) return; }
            }
            if (ctrl && key == 'Y' && TextInputController.onRedo()) return;

            // Editing keys.
            if (key == Glfw.GLFW_KEY_BACKSPACE && TextInputController.onBackspace(ctrl)) return;
            if (key == Glfw.GLFW_KEY_DELETE    && TextInputController.onDelete(ctrl))    return;
            if (key == Glfw.GLFW_KEY_ENTER     && TextInputController.onEnter())         return;
            if (key == Glfw.GLFW_KEY_TAB       && TextInputController.onTab())           return;
            if (TextInputController.onKey(key, shift, ctrl)) return;

            if (key == Glfw.GLFW_KEY_ESCAPE && action == Glfw.GLFW_PRESS) {
                if (OverlayStack.isActive()) {
                    OverlayStack.pop();
                    return;
                }
                Component focused = FocusState.focused();
                if (focused instanceof Component.Text t && t.selectable() && TextStates.of(focused).hasSelection()) {
                    TextStates.of(focused).collapseToCaret();
                    Invalidator.invalidate();
                } else if (focused != null) {
                    FocusState.clear();
                } else {
                    Glfw.glfwSetWindowShouldClose(window.handle(), true);
                    Invalidator.invalidate();
                }
            } else if (key == Glfw.GLFW_KEY_TAB) {
                Component layoutRoot = LatestLayout.root();
                if (layoutRoot != null) FocusState.cycle(layoutRoot, shift);
            } else if (ctrl && key == Glfw.GLFW_KEY_EQUAL) {
                EmContext.multiplyZoom(1.1f);
            } else if (ctrl && key == Glfw.GLFW_KEY_MINUS) {
                EmContext.multiplyZoom(1f / 1.1f);
            } else if (ctrl && key == Glfw.GLFW_KEY_0) {
                EmContext.setZoom(1f);
            }
        });

        GlfwCallbacks.setCursorPosListener((win, x, y) -> {
            InputState.updateMousePos(x, y);
            ScrollbarController.onCursorMove(x, y);
            if (ScrollbarController.isDragging()) return;

            LayoutResult lr = LatestLayout.result();
            Component layoutRoot = LatestLayout.root();
            if (lr == null || layoutRoot == null) return;

            // When an overlay is active, hit-test against its tree exclusively
            // (modal behavior); when none, the main UI.
            Component hitRoot = OverlayStack.activeInputRoot(layoutRoot);
            Component hit = HitTest.test(hitRoot, lr, (float) x, (float) y);
            HoverState.update(hit);
            cursors.setShape(cursorShapeFor(hit));

            TextInputController.onCursorMove(hit, x, y);
        });

        GlfwCallbacks.setMouseButtonListener((win, button, action, mods) -> {
            InputState.setMods(mods);
            if (button != Glfw.GLFW_MOUSE_BUTTON_LEFT) return;
            boolean pressed = (action == Glfw.GLFW_PRESS);
            InputState.setLeftButtonHeld(pressed);
            boolean shift = (mods & Glfw.GLFW_MOD_SHIFT) != 0;

            if (pressed) {
                if (ScrollbarController.onMouseDown(InputState.mouseX(), InputState.mouseY())) {
                    pressTarget = null;
                    return;
                }
                // Overlay capture: route the press through the topmost overlay's
                // component tree. Click-outside on a modal dismisses it.
                if (OverlayStack.isActive()) {
                    LayoutResult lr = LatestLayout.result();
                    if (OverlayStack.isOutsideTopmost(lr,
                            (float) InputState.mouseX(), (float) InputState.mouseY())) {
                        if (OverlayStack.anyModal()) OverlayStack.pop();
                        pressTarget = null;
                        return;
                    }
                    Component overlayRoot = OverlayStack.activeInputRoot(null);
                    Component hit = (lr != null && overlayRoot != null)
                        ? HitTest.test(overlayRoot, lr,
                            (float) InputState.mouseX(), (float) InputState.mouseY())
                        : null;
                    pressTarget = hit;
                    if (hit != null) FocusState.set(hit);
                    TextInputController.onMouseDown(hit, InputState.mouseX(), InputState.mouseY(), shift);
                    return;
                }
                Component hovered = HoverState.hovered();
                pressTarget = hovered;
                if (hovered != null) FocusState.set(hovered);
                else                 FocusState.clear();
                TextInputController.onMouseDown(hovered, InputState.mouseX(), InputState.mouseY(), shift);
            } else {
                boolean scrollDrag = ScrollbarController.isDragging();
                ScrollbarController.onMouseUp();

                LayoutResult lr2 = LatestLayout.result();
                Component dispatchRoot = OverlayStack.activeInputRoot(LatestLayout.root());
                Component released = (lr2 != null && dispatchRoot != null)
                    ? HitTest.test(dispatchRoot, lr2, (float) InputState.mouseX(), (float) InputState.mouseY())
                    : null;
                if (!scrollDrag && pressTarget != null && released == pressTarget) {
                    Handlers.activate(pressTarget, dispatchRoot);
                }
                pressTarget = null;
            }
        });

        GlfwCallbacks.setCharListener((win, codepoint) -> {
            TextInputController.onCharInput(codepoint);
        });

        GlfwCallbacks.setCursorEnterListener((win, entered) -> {
            if (!entered) {
                HoverState.clear();
                TextStates.clearAllHoverCarets();
                ScrollbarController.clearHover();
                cursors.setShape(CursorManager.CursorShape.ARROW);
                Invalidator.invalidate();
            }
        });

        GlfwCallbacks.setWindowFocusListener((win, focused) -> {
            if (!focused) {
                ScrollbarController.cancelDrag();
                HoverState.clear();
                TextStates.clearAllHoverCarets();
                InputState.setLeftButtonHeld(false);
            }
            Invalidator.invalidate();
        });

        GlfwCallbacks.setScrollListener((win, xOff, yOff) -> {
            LayoutResult lr = LatestLayout.result();
            Component layoutRoot = OverlayStack.activeInputRoot(LatestLayout.root());
            if (lr == null || layoutRoot == null) return;

            boolean shift = Glfw.glfwGetKey(window.handle(), Glfw.GLFW_KEY_LEFT_SHIFT)  == Glfw.GLFW_PRESS
                         || Glfw.glfwGetKey(window.handle(), Glfw.GLFW_KEY_RIGHT_SHIFT) == Glfw.GLFW_PRESS;

            Component.Scroll target = HitTest.findScroll(layoutRoot, lr,
                    (float) InputState.mouseX(), (float) InputState.mouseY());
            if (target == null) return;
            double dx, dy;
            if (shift) { dx = -yOff * WHEEL_PIXELS_PER_STEP; dy = 0; }
            else        { dx = -xOff * WHEEL_PIXELS_PER_STEP; dy = -yOff * WHEEL_PIXELS_PER_STEP; }
            ScrollStates.of(target).scrollByPx((float) dx, (float) dy);
        });
    }

    private static CursorManager.CursorShape cursorShapeFor(Component hit) {
        if (hit instanceof Component.Text t && t.selectable()) return CursorManager.CursorShape.IBEAM;
        if (hit != null) return CursorManager.CursorShape.HAND;
        return CursorManager.CursorShape.ARROW;
    }
}
