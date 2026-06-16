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
import sibarum.dasum.gui.core.input.TabsController;
import sibarum.dasum.gui.core.input.TextInputController;
import sibarum.dasum.gui.core.input.TextState;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.input.TextStyleStates;
import sibarum.dasum.gui.core.layout.HitTest;
import sibarum.dasum.gui.core.layout.LatestLayout;
import sibarum.dasum.gui.core.layout.Layout;
import sibarum.dasum.gui.core.layout.LayoutResult;
import sibarum.dasum.gui.core.layout.PixelRect;
import sibarum.dasum.gui.core.layout.Render;
import sibarum.dasum.gui.core.overlay.Anchor;
import sibarum.dasum.gui.core.overlay.OverlayStack;
import sibarum.dasum.gui.core.reactive.Property;
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
import sibarum.pontif.runtime.ConservationReport;
import sibarum.pontif.runtime.IrAstReport;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.QuickTour;
import sibarum.pontif.runtime.ReceiptGraphReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
    // System-menu overlay palette (mirrors the status log dialog).
    private static final Color MENU_BG       = new Color(0.10f, 0.12f, 0.16f, 1f);
    private static final Color MENU_TITLE_FG = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color MENU_HINT_FG  = new Color(0.65f, 0.70f, 0.78f, 0.85f);

    /** Font group key for the monospace atlas (registered alongside the primary one). */
    private static final String MONO_FONT_GROUP = "mono";

    private static final String UNTITLED_LABEL = "(untitled)";
    private static final String DEFAULT_FILE_NAME = "untitled.ptf";
    private static final List<FileDialog.Filter> PTF_FILTERS = List.of(
            FileDialog.Filter.of("Pontif source", "ptf"),
            FileDialog.Filter.of("All files", "*"));

    /** Tab indices in the main tab strip (Editor = 0). */
    private static final int IR_AST_TAB = 1;
    private static final int REPORT_TAB = 2;

    /** ASCII divider between the two report sections — the mono atlas is ASCII-only. */
    private static final String REPORT_DIVIDER = "=".repeat(72);

    private static final float WHEEL_PIXELS_PER_STEP = 40f;

    // The default editor content is the canonical quick tour, owned by
    // pontif-runtime so the app and the runtime test suite share one copy.
    private static final String DEFAULT_CODE = QuickTour.SOURCE;

    // Component references held in static fields so the toolbar's click
    // handler and the worker thread can find them without rebuilding the
    // tree (rebuilding would break identity-keyed state).
    private static Component.Text codeText;
    private static Component.Text filenameLabel;
    private static Component.Text reportText;
    private static Component.Text irAstText;
    // Last-published cursor string, so the per-frame refresh only writes the
    // ribbon's docked field when the caret actually moved.
    private static String lastCursorText = null;

    // Hoisted so file-dialog button handlers can reach it. Lifetime is
    // bounded by main()'s try-with-resources; handlers only fire while the
    // event loop is running, so the field is always non-null when read.
    private static Window window;

    private static Component pressTarget = null;
    private static Path currentFile = null;

    /** Background persistence: recovery autosave + session-file writer. Created
     *  in {@link #main} once the state directory is confirmed writable. */
    private static SessionManager session;

    private static final PontifCompiler COMPILER = new PontifCompiler();
    private static final PontifRunner RUNNER = new PontifRunner();

    public static void main(String[] args) {
        // Per-user state directory + the previous session (if any). Read before
        // the window is created so its size can be restored at creation time.
        boolean stateEnabled = AppPaths.ensureDirs();
        SessionState restored = stateEnabled
                ? SessionState.read(AppPaths.sessionFile()).orElse(null)
                : null;
        int initW = (restored != null && restored.hasGeometry()) ? restored.windowWidth : 1100;
        int initH = (restored != null && restored.hasGeometry()) ? restored.windowHeight : 720;

        try (GlfwContext ctx = GlfwContext.init();
             Window win = Window.create(initW, initH, "Pontif Playground");
             Batcher batcher = new Batcher();
             CursorManager cursors = new CursorManager(win.handle().address())) {
            window = win;

            Gl.load();
            batcher.init();
            cursors.init();
            EmContext.setDpiScale(win.contentScaleX());
            applyTheme();
            restoreWindowGeometry(win, restored);
            session = new SessionManager(stateEnabled);

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
                    "Pontif Playground — edit code, press Run; the Receipts tab shows both proof graphs.  Click here to view the event log.",
                    Variant.DEFAULT);
                Status.setCloseIcon(Icons.X);
                Component root = Status.wrap(buildUi());
                wireInput(win, cursors);

                // Restore the previously-open file, then surface any recovery for it.
                initializeDocument(restored);

                EventLoop loop = new EventLoop(win, () -> {
                    // The caret moves only via input that triggers a redraw, so
                    // refreshing the indicator at the top of the frame catches
                    // every move; it writes the label only on change.
                    updateCursorIndicator();
                    // Sample window placement each frame for the session file;
                    // the manager only writes when it actually changed.
                    int[] winPos  = win.position();
                    int[] winSize = win.size();
                    session.updateGeometry(winPos[0], winPos[1], winSize[0], winSize[1], win.isMaximized());

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
                session.start();
                try {
                    loop.run();
                } finally {
                    // Final flush catches edits made within the last tick window;
                    // recovery copies for unsaved work intentionally remain on disk.
                    session.flushAndStop();
                }
            }
        }
    }

    /** Recompute and publish both style axes for the editor: token colors
     *  (foreground) and bracket-block tints (background, outermost-first). */
    private static void applyHighlight(String content) {
        AltHighlighter.Styles styles = AltHighlighter.highlight(content);
        TextStyleStates.setForeground(codeText, styles.foreground());
        TextStyleStates.setBackground(codeText, styles.background());
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
        Component runBtn    = Themed.iconButton(Icons.PLAY,     "Run",     Em.of(6f),   Variant.PRIMARY, 0, App::onRunClicked);
        Component newBtn    = Themed.iconButton(Icons.FILE,     Em.of(2f), Variant.DEFAULT, 0, App::onNewClicked);
        Component openBtn   = Themed.iconButton(Icons.FOLDER,   Em.of(2f), Variant.DEFAULT, 0, App::onOpenClicked);
        Component saveBtn   = Themed.iconButton(Icons.SAVE,     Em.of(2f), Variant.DEFAULT, 0, App::onSaveClicked);
        Component saveAsBtn = Themed.iconButton(Icons.SAVE_ALL, "Save As", Em.of(7.5f), Variant.DEFAULT, 0, App::onSaveAsClicked);
        Component systemBtn = Themed.iconButton(Icons.SETTINGS, "System", Em.of(7f), Variant.DEFAULT, 0, App::openSystemMenu);

        filenameLabel = new Component.Text(
            UNTITLED_LABEL, FontGroups.DEFAULT, Em.of(0.9f), LABEL_FG,
            null, null, Em.of(0.3f),
            null, true,
            false, false, false, false, 1);

        Component toolbar = new Component.Flex(
            null, Em.of(3f), Em.of(0.5f), TOOLBAR_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.5f),
            List.of(runBtn, newBtn, openBtn, saveBtn, saveAsBtn, filenameLabel, systemBtn),
            false, 0);

        // Editable code editor — monospace, accepts tab, wraps to its pane
        // width, with a logical-line number gutter.
        codeText = new Component.Text(
            DEFAULT_CODE, MONO_FONT_GROUP, Em.of(0.95f), CODE_FG,
            null, null, Em.of(0.5f),
            null, false,
            true, true, true, true, 1).withLineNumbers(true);

        // Live syntax highlighting: recompute all spans from scratch on
        // every content change (keystroke, file open), plus one initial
        // publish so the default content is colored before the first edit.
        // Registered against codeText's final identity (after withLineNumbers).
        TextStates.onContentChange(codeText, App::onEditorContentChanged);
        onEditorContentChanged(DEFAULT_CODE);

        Component codePane = new Component.Scroll(null, null, Em.ZERO, EDITOR_BG, codeText, false, 1);

        // Read-only combined proof-graph view (receipt graph + conservation
        // ledger). Selectable so text can be copied out; regenerated from the
        // current editor source every time the tab is activated, so it can
        // never go stale.
        reportText = new Component.Text(
            "", MONO_FONT_GROUP, Em.of(0.95f), CODE_FG,
            null, null, Em.of(0.5f),
            null, false,
            true, true, false, false, 1);

        Component reportPane = new Component.Scroll(null, null, Em.ZERO, EDITOR_BG, reportText, false, 1);

        // Read-only inspector for the two compilation intermediates: the
        // parsed IR tree and the lowered Truffle execution AST. Regenerated
        // from the editor source on tab activation, so it never goes stale.
        irAstText = new Component.Text(
            "", MONO_FONT_GROUP, Em.of(0.95f), CODE_FG,
            null, null, Em.of(0.5f),
            null, false,
            true, true, false, false, 1);

        Component irAstPane = new Component.Scroll(null, null, Em.ZERO, EDITOR_BG, irAstText, false, 1);

        Property<Integer> activeTab = new Property<>(0);
        activeTab.subscribe(i -> {
            if (i == null) return;
            if (i == IR_AST_TAB) regenerateIrAst();
            else if (i == REPORT_TAB) regenerateReports();
        });

        Component tabs = Themed.tabs(
            null, null, Em.of(2.2f), Em.of(1f), Em.ZERO,
            Em.of(0.95f),
            List.of(
                new Component.Tabs.TabPanel("Editor",   codePane),
                new Component.Tabs.TabPanel("IR / AST", irAstPane),
                new Component.Tabs.TabPanel("Receipts", reportPane)),
            activeTab,
            Variant.PRIMARY
        ).withFlexGrow(1);

        return new Component.Flex(
            null, null, Em.of(0.5f), FRAME_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.5f),
            List.of(toolbar, tabs),
            false, 0);
    }

    private static void onRunClicked() {
        String code = TextStates.contentOf(codeText);
        String sourceName = currentFile != null ? currentFile.getFileName().toString() : "<editor>";
        // Resolve sibling `requires` from the open file's directory; captured on
        // the main thread so the worker doesn't race a file change.
        Path resolveDir = resolveDir();
        long startNs = System.nanoTime();
        Thread worker = new Thread(() -> {
            PontifRunner.RunResult result = RUNNER.run(
                    COMPILER.compileAlt(code, sourceName, resolveDir),
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
     * Drafts both proof graphs for the current editor source and fills the
     * Receipts tab with the combined text: receipt graph (sorts) first, then
     * the conservation ledger, separated by a divider — both report texts
     * self-title. Failures print as the section body, so a parse error is
     * visible in both sections rather than silently emptying the tab.
     * Drafting is bounded transcription + discharge — fast and terminating,
     * so it's fine on the GLFW main thread (this runs from the tab-switch
     * callback).
     */
    /** Fill the IR/AST inspector from the current editor source. Parsing and
     *  lowering are bounded and fast, so this is fine on the GLFW main thread
     *  (runs from the tab-switch callback, like {@link #regenerateReports}). */
    private static void regenerateIrAst() {
        String code = TextStates.contentOf(codeText);
        String sourceName = currentFile != null ? currentFile.getFileName().toString() : "<editor>";
        String text = switch (IrAstReport.fromAltSource(code, sourceName, resolveDir())) {
            case IrAstReport.Result.Generated g -> g.text();
            case IrAstReport.Result.Failed f -> f.error();
        };
        TextStates.setContent(irAstText, text);
    }

    /** Directory used to resolve sibling {@code requires} for the open file —
     *  its parent dir, or null for an unsaved/untitled buffer (builtins only). */
    private static Path resolveDir() {
        return currentFile != null ? currentFile.getParent() : null;
    }

    /** Recompute the editor's 1-based line/column from the caret offset and
     *  publish it to the status ribbon's docked field — only when it changed,
     *  so an unmoved caret costs nothing. Same GLFW thread as input, so the
     *  read is safe. */
    private static void updateCursorIndicator() {
        if (codeText == null) return;
        TextState ts = TextStates.of(codeText);
        String content = TextStates.contentOf(codeText);
        int caret = Math.max(0, Math.min(ts.caretIndex, content.length()));
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < caret; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int col = caret - lineStart + 1;
        String text = "Ln " + line + ", Col " + col;
        if (!text.equals(lastCursorText)) {
            lastCursorText = text;
            Status.setDockedMessage(text);
        }
    }

    private static void regenerateReports() {
        String code = TextStates.contentOf(codeText);
        String sourceName = currentFile != null ? currentFile.getFileName().toString() : "<editor>";
        Path resolveDir = resolveDir();

        String receipts = switch (ReceiptGraphReport.fromAltSource(code, sourceName, resolveDir)) {
            case ReceiptGraphReport.Result.Generated g -> g.text();
            case ReceiptGraphReport.Result.Failed f ->
                    "# Receipt-graph report: " + sourceName + "\n\n" + f.error() + "\n";
        };
        String conservation = switch (ConservationReport.fromAltSource(code, sourceName, resolveDir)) {
            case ConservationReport.Result.Generated g -> g.text();
            case ConservationReport.Result.Failed f ->
                    "# Conservation ledger: " + sourceName + "\n\n" + f.error() + "\n";
        };
        TextStates.setContent(reportText,
                receipts + "\n" + REPORT_DIVIDER + "\n\n" + conservation);
    }

    // --- File operations: must run on the GLFW main thread (FileDialog requirement). ---

    /** Blank the editor and detach from any file — a fresh untitled document.
     *  Replaces the buffer outright, like Open; there is no dirty-tracking, so
     *  no save prompt (consistent with the rest of the toolbar). */
    private static void onNewClicked() {
        TextStates.setContent(codeText, "");
        currentFile = null;
        updateFilenameLabel();
        if (session != null) session.onDocumentChanged(RecoveryStore.keyFor(null), "", null);
        Status.success("New file");
    }

    private static void onOpenClicked() {
        FileDialog.open(window, PTF_FILTERS, dialogStartPath()).ifPresent(path -> loadFile(path, true));
    }

    /** Read {@code path} into the editor and adopt it as the current document.
     *  Shared by the Open button and startup session restore; {@code announce}
     *  controls whether success/failure flashes in the status ribbon (startup
     *  restore stays quiet). Returns whether the load succeeded. */
    private static boolean loadFile(Path path, boolean announce) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            TextStates.setContent(codeText, content);  // fires onEditorContentChanged → snapshot + highlight
            currentFile = path;
            updateFilenameLabel();
            if (session != null) {
                session.onDocumentChanged(RecoveryStore.keyFor(path), content,
                        path.toAbsolutePath().normalize().toString());
            }
            if (announce) Status.success("Opened " + path.getFileName());
            return true;
        } catch (IOException e) {
            if (announce) {
                Status.error("Error opening " + path.getFileName() + ": " + e.getMessage(), path.toString());
            }
            return false;
        }
    }

    private static void onSaveClicked() {
        if (currentFile == null) {
            onSaveAsClicked();
            return;
        }
        writeCurrent(currentFile, RecoveryStore.keyFor(currentFile));
    }

    private static void onSaveAsClicked() {
        // Capture the key of the document as it stands now (untitled, or the
        // previous file) so its recovery copy is cleared even when Save As
        // redirects to a different path.
        String prevKey = RecoveryStore.keyFor(currentFile);
        String defaultName = currentFile != null
                ? currentFile.getFileName().toString()
                : DEFAULT_FILE_NAME;
        FileDialog.save(window, PTF_FILTERS, dialogStartPath(), defaultName).ifPresent(path -> {
            currentFile = path;
            updateFilenameLabel();
            writeCurrent(path, prevKey);
        });
    }

    private static void writeCurrent(Path path, String prevKey) {
        try {
            String content = TextStates.contentOf(codeText);
            Files.writeString(path, content, StandardCharsets.UTF_8);
            // Saving clears the recovery copy: the on-disk file is now the
            // source of truth, so there's nothing unsaved to recover.
            if (session != null) {
                session.onSaved(prevKey, RecoveryStore.keyFor(path), content,
                        path.toAbsolutePath().normalize().toString());
            }
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

    // --- Session restore + crash recovery (GLFW main thread) ---

    /** Single content-change sink: refresh syntax highlighting and publish the
     *  latest text to the recovery autosave snapshot. */
    private static void onEditorContentChanged(String content) {
        applyHighlight(content);
        if (session != null) session.onContentChanged(content);
    }

    /** Apply restored window size/position/maximized state before the first
     *  frame. Size is already applied at creation; this adds position and the
     *  maximized toggle. No-op when there's nothing to restore. */
    private static void restoreWindowGeometry(Window win, SessionState restored) {
        if (restored == null) return;
        if (restored.hasGeometry()) win.setSize(restored.windowWidth, restored.windowHeight);
        if (restored.hasPosition()) win.setPosition(restored.windowX, restored.windowY);
        if (restored.maximized) win.maximize();
    }

    /** Adopt the startup document: the untitled default, or the file from the
     *  last session if it still exists, then flag any recovery available for
     *  whichever document ended up active. */
    private static void initializeDocument(SessionState restored) {
        // The editor currently holds DEFAULT_CODE as an untitled buffer.
        if (session != null) session.onDocumentChanged(RecoveryStore.keyFor(null), DEFAULT_CODE, null);

        if (restored != null && restored.openFile != null) {
            Path path = Path.of(restored.openFile);
            if (Files.isReadable(path)) loadFile(path, false);
        }

        String activeKey = RecoveryStore.keyFor(currentFile);
        if (session != null && session.hasRecovery(activeKey)) {
            Status.info("Unsaved changes from a previous session are available for "
                    + documentDisplayName() + " — open the System menu to recover.");
        }
    }

    private static String documentDisplayName() {
        return currentFile == null ? UNTITLED_LABEL : currentFile.getFileName().toString();
    }

    /** Modal System menu: recover the current file (when a recovery exists) and
     *  purge all recovery files. Reuses the overlay machinery the status log
     *  dialog uses; dismissed by Close, click-outside, or ESC. */
    private static void openSystemMenu() {
        if (OverlayStack.isActive()) return;  // don't stack on another overlay

        String key = RecoveryStore.keyFor(currentFile);
        boolean hasRecovery = session != null && session.hasRecovery(key);

        java.util.List<Component> rows = new java.util.ArrayList<>();
        rows.add(new Component.Text("System", Em.of(1.15f), MENU_TITLE_FG));

        if (hasRecovery) {
            rows.add(new Component.Text(
                    "A recovery copy of " + documentDisplayName() + " is available.",
                    Em.of(0.9f), MENU_HINT_FG));
            rows.add(Themed.iconButton(Icons.ROTATE_CCW, "Recover current file",
                    Em.of(18f), Variant.PRIMARY, 0, App::onRecoverClicked));
        } else {
            rows.add(new Component.Text(
                    "No recovery available for " + documentDisplayName() + ".",
                    Em.of(0.9f), MENU_HINT_FG));
        }
        rows.add(Themed.iconButton(Icons.TRASH_2, "Purge all recovery files",
                Em.of(18f), Variant.DEFAULT, 0, App::onPurgeAllClicked));
        rows.add(Themed.button("Close", Em.of(18f), Variant.DEFAULT, 0, OverlayStack::pop));

        Component panel = new Component.Flex(
                Em.of(26f), Em.AUTO, Em.of(1f), MENU_BG,
                Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.6f),
                rows, false, 0);
        OverlayStack.push(new OverlayStack.Overlay(panel, Anchor.CENTER, true, () -> {}));
    }

    private static void onRecoverClicked() {
        String key = RecoveryStore.keyFor(currentFile);
        Optional<String> recovered = session == null ? Optional.empty() : session.recover(key);
        OverlayStack.pop();
        if (recovered.isEmpty()) {
            Status.warn("No recovery available for " + documentDisplayName());
            return;
        }
        // Replaces the editor buffer; the same file stays open. The recovered
        // text now differs from disk, so it's treated as unsaved until saved
        // (at which point the recovery copy is deleted).
        TextStates.setContent(codeText, recovered.get());
        Status.success("Recovered " + documentDisplayName() + " — save to keep the changes.");
    }

    private static void onPurgeAllClicked() {
        int removed = session == null ? 0 : session.purgeAll();
        OverlayStack.pop();
        Status.success("Purged " + removed + " recovery file" + (removed == 1 ? "" : "s"));
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
            // Tab strip: Left/Right/Home/End cycle tabs, but only when a Tabs
            // component holds focus — a focused editor still wins above.
            if (TabsController.onKey(key)) return;

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
            TabsController.onCursorMove(x, y);
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
                // Tab cells aren't components (TabsController synthesizes their
                // geometry at render time) — same treatment as scrollbar thumbs.
                // After the overlay block, so a modal still captures the press.
                if (TabsController.onMouseDown(InputState.mouseX(), InputState.mouseY())) {
                    pressTarget = null;
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
                TabsController.clearHover();
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

            double dx, dy;
            if (shift) { dx = -yOff * WHEEL_PIXELS_PER_STEP; dy = 0; }
            else        { dx = -xOff * WHEEL_PIXELS_PER_STEP; dy = -yOff * WHEEL_PIXELS_PER_STEP; }
            // Walk the scroll chain innermost→outermost; the first container
            // that actually moves consumes the wheel. A nested scroll
            // bottomed-out at its limit returns false from scrollByPx, so the
            // event bubbles to its parent (and clip-respect keeps a scroll
            // not visually under the cursor from capturing).
            java.util.List<Component.Scroll> chain = HitTest.findScrollChain(
                    layoutRoot, lr, (float) InputState.mouseX(), (float) InputState.mouseY());
            for (Component.Scroll s : chain) {
                if (ScrollStates.of(s).scrollByPx((float) dx, (float) dy)) break;
            }
        });
    }

    private static CursorManager.CursorShape cursorShapeFor(Component hit) {
        if (hit instanceof Component.Text t && t.selectable()) return CursorManager.CursorShape.IBEAM;
        if (hit != null) return CursorManager.CursorShape.HAND;
        return CursorManager.CursorShape.ARROW;
    }
}
