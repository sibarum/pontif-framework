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
import sibarum.dasum.gui.core.input.TextStyle;
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
import sibarum.dasum.gui.core.text.TextGeometry;
import sibarum.dasum.gui.core.text.WordBoundary;
import sibarum.dasum.gui.core.theme.Palette;
import sibarum.dasum.gui.core.theme.Theme;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.dasum.gui.core.window.Window;
import sibarum.dasum.gui.natives.gl.Gl;
import sibarum.dasum.gui.natives.glfw.Glfw;
import sibarum.dasum.gui.natives.glfw.GlfwCallbacks;
import sibarum.pontif.core.Origin;
import sibarum.pontif.playground.generated.Icons;
import sibarum.pontif.net.debug.DebugServer;
import sibarum.pontif.net.debug.DebugSession;
import sibarum.pontif.runtime.ConservationReport;
import sibarum.pontif.runtime.IrAstReport;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.QuickTour;
import sibarum.pontif.runtime.ReceiptGraphReport;
import sibarum.pontif.runtime.ReflectionReport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static sibarum.dasum.gui.natives.gl.Gl.GL_COLOR_BUFFER_BIT;

/**
 * Pontif Editor — a minimal GUI for typing Pontif source, running it on
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

    /** The idle status-ribbon message; restored when the caret leaves an error. */
    private static final String DEFAULT_STATUS =
            "Pontif Editor — edit code, press Run; the Receipts tab shows both proof graphs.  Click here to view the event log.";

    private static final String UNTITLED_LABEL = "(untitled)";
    private static final String DEFAULT_FILE_NAME = "untitled.ptf";
    private static final List<FileDialog.Filter> PTF_FILTERS = List.of(
            FileDialog.Filter.of("Pontif source", "ptf"),
            FileDialog.Filter.of("All files", "*"));

    /** Tab indices in the main tab strip (Editor = 0). */
    private static final int IR_AST_TAB = 1;
    private static final int REPORT_TAB = 2;
    private static final int NARROWINGS_TAB = 3;
    /** Read-only "go to definition" view, populated on Ctrl+click (see {@link #openDefinition}). */
    private static final int DEFINITION_TAB = 4;

    // Ctrl+click navigation palette: the IntelliJ-style "this is a link" affordance.
    private static final Color LINK_UNDERLINE = new Color(0.40f, 0.62f, 1.00f, 1f);
    // The clicked declaration (strong) vs. its other references (faint) in the
    // definition view — every occurrence of the name is highlighted.
    private static final Color DEFN_HIGHLIGHT     = new Color(0.40f, 0.62f, 1.00f, 0.45f);
    private static final Color DEFN_REF_HIGHLIGHT = new Color(0.85f, 0.75f, 0.30f, 0.30f);

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
    private static Component.Text narrowingsText;
    private static Component.Text definitionText;
    // The editor's scroll pane — held so the Ctrl-hover underline can be clipped
    // to its viewport (the underline is drawn in the top-level render pass, not
    // inside the Scroll's own clipped render).
    private static Component.Scroll codeScroll;

    // Ctrl-hover "link" underline over the identifier under the mouse (IntelliJ
    // style). [linkStart, linkEnd) into the editor content, or -1/-1 when no word
    // is underlined. Set by updateLinkHover, drawn in the render loop.
    private static int linkStart = -1;
    private static int linkEnd = -1;
    // The Definition tab's selected name must be scrolled into view, but only
    // after the tab is active and its text has been laid out — so the request is
    // deferred one frame and serviced in the render loop.
    private static boolean scrollDefnPending = false;
    // The main tab strip's active index, hoisted so the entrypoint menu can revert
    // it on dismiss. `committedTab` is the tab the user actually settled on (a press
    // of the Narrowings tab is transient until an entrypoint is chosen);
    // `narrowingsEntry` is the chosen reflection entrypoint (null = main).
    private static Property<Integer> activeTab;
    private static int committedTab = 0;
    private static String narrowingsEntry = null;
    // Last-published cursor string, so the per-frame refresh only writes the
    // ribbon's docked field when the caret actually moved.
    private static String lastCursorText = null;

    // --- Live compilation state ---
    // The editor recompiles on a short debounce as you type; a clean compile
    // autosaves the open file and a failed one underlines the offending token
    // (or its requires statement) and shows the message when the caret is near.

    /** Red error underline + ribbon tint. */
    private static final Color ERROR_MARK = new Color(0.95f, 0.36f, 0.36f, 1f);

    /** Recompile this long after the last edit settles. */
    private static final long COMPILE_DEBOUNCE_MS = 400L;

    /**
     * One error to surface: a half-open {@code [start, end)} char range in the
     * editor buffer and the message shown when the caret is near it.
     * {@code fromImport} records that the failure came from an imported module —
     * the range then points at the offending {@code requires} statement, not a
     * foreign offset that can't be mapped into this buffer.
     */
    private record ErrorMark(int start, int end, String message, boolean fromImport) {}

    /** Latest compile's error marks; read each frame by the underline + caret
     *  hooks. Single-writer (the debounce worker) / many-reader (GLFW thread). */
    private static volatile List<ErrorMark> errorMarks = List.of();

    /** Monotonic edit counter, bumped on every content change. A compile captures
     *  it at schedule time and discards its result if a newer edit has landed, so
     *  stale marks (computed against text the user has since changed) never show. */
    private static volatile long editVersion = 0L;

    /** The error message currently parked in the ribbon's default slot, so the
     *  per-frame caret check only republishes when it actually changes. */
    private static String shownErrorMessage = null;

    private static final ScheduledExecutorService COMPILE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pontif-live-compile");
                t.setDaemon(true);
                return t;
            });
    private static ScheduledFuture<?> pendingCompile = null;

    // Hoisted so file-dialog button handlers can reach it. Lifetime is
    // bounded by main()'s try-with-resources; handlers only fire while the
    // event loop is running, so the field is always non-null when read.
    private static Window window;

    private static Component pressTarget = null;
    private static Path currentFile = null;

    /** A file named on the command line to open at startup (e.g. launched by
     *  {@code pontif editor foo.ptf}); overrides the restored session file when
     *  readable. Null when launched with no argument. */
    private static Path startupFile = null;

    /** Background persistence: recovery autosave + session-file writer. Created
     *  in {@link #main} once the state directory is confirmed writable. */
    private static SessionManager session;

    // The editor compiles in-process for live error marks (runLiveCompile); programs
    // are RUN out-of-process (see launchProgram), so no shared PontifRunner is held here.
    private static final PontifCompiler COMPILER = new PontifCompiler();

    /** Self-exec flags: {@code pontif-editor <flag> <program.ptf> [resolveDir] [name]} runs a
     *  program in a child instance of this executable instead of opening the editor. Used only when
     *  the editor is a native image (no bundled {@code java} to spawn). */
    private static final String RUN_HEADLESS_FLAG = "--pontif-run";
    private static final String RUN_GUI_FLAG = "--pontif-run-gui";

    public static void main(String[] args) throws Exception {
        // Program-run modes: the editor re-invokes its OWN executable to run a program
        // out-of-process (see launchProgram / childCommand) whenever it is itself a native image,
        // because a native image has no bundled `java` to spawn (System.getProperty("java.home")
        // is null). These flags route straight to the launchers and never open the editor. On the
        // JVM the editor spawns `java -cp … <launcher>` instead, so these branches are dormant
        // there — but they are the reachability anchor that pulls the launchers into the image.
        if (args.length >= 1 && RUN_HEADLESS_FLAG.equals(args[0])) {
            sibarum.pontif.net.ProgramLauncher.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length >= 1 && RUN_GUI_FLAG.equals(args[0])) {
            sibarum.pontif.gui.GuiLauncher.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        // The editor compiles in-process, so every extension module on the classpath must be
        // RESOLVABLE here. They self-register via ServiceLoader discovery (BuiltinModules →
        // Extensions.installDiscovered, which runs before any module resolution), so this needs no
        // per-extension wiring — adding a new pontif-builtin-* dependency to the editor is enough.
        // This only makes those modules RESOLVABLE; GUI programs still RUN in a separate process
        // (see onRunGuiClicked / isGuiProgram), so nothing windowed executes in the editor.

        // An optional file argument (the CLI's `pontif editor <file>`): open it
        // at startup instead of restoring the last session's file.
        if (args.length > 0 && args[0] != null && !args[0].isBlank()) {
            Path candidate = Path.of(args[0]);
            if (Files.isReadable(candidate)) startupFile = candidate;
        }
        // Per-user state directory + the previous session (if any). Read before
        // the window is created so its size can be restored at creation time.
        boolean stateEnabled = AppPaths.ensureDirs();
        SessionState restored = stateEnabled
                ? SessionState.read(AppPaths.sessionFile()).orElse(null)
                : null;
        int initW = (restored != null && restored.hasGeometry()) ? restored.windowWidth : 1100;
        int initH = (restored != null && restored.hasGeometry()) ? restored.windowHeight : 720;

        try (GlfwContext ctx = GlfwContext.init();
             Window win = Window.create(initW, initH, "Pontif Editor");
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

                Status.setDefaultMessage(DEFAULT_STATUS, Variant.DEFAULT);
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
                    // Surface the nearest live-compile error in the ribbon when the
                    // caret is on it; restore the default hint when it moves away.
                    updateErrorStatus();
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

                    // A just-opened definition needs its highlighted name scrolled
                    // into view, but only now that the Definition tab is active and
                    // its text has a layout rect (it had none while another tab showed).
                    if (scrollDefnPending) {
                        scrollSelectionIntoView(definitionText);
                        scrollDefnPending = false;
                    }

                    batcher.beginFrame(fbH);
                    Render.render(root, layout, batcher, projection);
                    drawLinkUnderline(layout, batcher);
                    drawErrorUnderlines(layout, batcher);
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
        Component guiBtn    = Themed.button("Window", Em.of(7f), Variant.SUCCESS, 0, App::onRunGuiClicked);
        Component newBtn    = Themed.iconButton(Icons.FILE,     Em.of(2f), Variant.DEFAULT, 0, App::onNewClicked);
        Component openBtn   = Themed.iconButton(Icons.FOLDER,   Em.of(2f), Variant.DEFAULT, 0, App::onOpenClicked);
        Component saveBtn   = Themed.iconButton(Icons.SAVE,     Em.of(2f), Variant.DEFAULT, 0, App::onSaveClicked);
        Component saveAsBtn = Themed.iconButton(Icons.SAVE_ALL, "Save As", Em.of(7.5f), Variant.DEFAULT, 0, App::onSaveAsClicked);
        Component modulesBtn = Themed.button("Modules", Em.of(8f), Variant.DEFAULT, 0, App::openModuleExplorer);
        Component systemBtn = Themed.iconButton(Icons.SETTINGS, "System", Em.of(7f), Variant.DEFAULT, 0, App::openSystemMenu);

        filenameLabel = new Component.Text(
            UNTITLED_LABEL, FontGroups.DEFAULT, Em.of(0.9f), LABEL_FG,
            null, null, Em.of(0.3f),
            null, true,
            false, false, false, false, 1);

        Component toolbar = new Component.Flex(
            null, Em.of(3f), Em.of(0.5f), TOOLBAR_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.5f),
            List.of(runBtn, guiBtn, newBtn, openBtn, saveBtn, saveAsBtn, modulesBtn, filenameLabel, systemBtn),
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

        codeScroll = new Component.Scroll(null, null, Em.ZERO, EDITOR_BG, codeText, false, 1);
        Component codePane = codeScroll;

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

        // Read-only "reflected source" view: the program re-emitted with declared
        // sorts replaced by the inferred narrowings the one engine derives.
        // Regenerated from the editor source on tab activation.
        narrowingsText = new Component.Text(
            "", MONO_FONT_GROUP, Em.of(0.95f), CODE_FG,
            null, null, Em.of(0.5f),
            null, false,
            true, true, false, false, 1);

        Component narrowingsPane = new Component.Scroll(null, null, Em.ZERO, EDITOR_BG, narrowingsText, false, 1);

        // Read-only "go to definition" view: Ctrl+click a name in the editor and
        // the declaring module's source opens here with the name highlighted
        // (see onMouseDown's Ctrl branch → openDefinition). Esc returns to Editor.
        definitionText = new Component.Text(
            "Ctrl+click a type, function, or method name in the editor to open its definition here.",
            MONO_FONT_GROUP, Em.of(0.95f), CODE_FG,
            null, null, Em.of(0.5f),
            null, false,
            true, true, false, false, 1);

        Component definitionPane = new Component.Scroll(null, null, Em.ZERO, EDITOR_BG, definitionText, false, 1);

        activeTab = new Property<>(0);
        activeTab.subscribe(i -> {
            if (i == null) return;
            // The Narrowings tab is driven by the entrypoint menu (onTabPressed), not
            // by this change signal — so don't regenerate or commit on switch-to. Every
            // other tab commits and regenerates as usual.
            if (i == IR_AST_TAB) { committedTab = i; regenerateIrAst(); }
            else if (i == REPORT_TAB) { committedTab = i; regenerateReports(); }
            else if (i != NARROWINGS_TAB) { committedTab = i; }  // Editor
        });

        Component tabs = Themed.tabs(
            null, null, Em.of(2.2f), Em.of(1f), Em.ZERO,
            Em.of(0.95f),
            List.of(
                new Component.Tabs.TabPanel("Editor",   codePane),
                new Component.Tabs.TabPanel("IR / AST", irAstPane),
                new Component.Tabs.TabPanel("Receipts", reportPane),
                new Component.Tabs.TabPanel("Narrowings", narrowingsPane),
                new Component.Tabs.TabPanel("Definition", definitionPane)),
            activeTab,
            Variant.PRIMARY
        ).withOnTabPressed(App::onTabPressed).withFlexGrow(1);

        return new Component.Flex(
            null, null, Em.of(0.5f), FRAME_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.5f),
            List.of(toolbar, tabs),
            false, 0);
    }

    /**
     * Module paths whose {@code requires} marks a program as a GUI/windowed
     * program — exactly the extensions {@code GuiLauncher} installs
     * ({@code GuiExtension.moduleName()} / {@code PlotExtension.moduleName()}).
     * A program importing either opens its own GLFW window, so it must run in a
     * separate process rather than in-process in the editor (which already owns a
     * GLFW root thread). Mirrored here as literals to keep the editor from
     * instantiating the dasum-bearing extensions just to read a name.
     */
    private static final java.util.Set<String> GUI_MODULES = java.util.Set.of("pontif.gui", "pontif.plot");

    /** True when the buffer directly {@code requires} a windowed module (see
     *  {@link #GUI_MODULES}). Line-based on the single-line {@code requires} form,
     *  matching {@link DefinitionNavigator}. */
    private static boolean isGuiProgram(String code) {
        for (String line : code.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("requires ")) {
                String module = requiresModule(t);
                if (module != null && GUI_MODULES.contains(module)) return true;
            }
        }
        return false;
    }

    private static void onRunClicked() {
        String code = TextStates.contentOf(codeText);
        // A program that imports a windowed extension opens its own GLFW window and
        // must own a root thread, so it runs under GuiLauncher rather than the headless
        // launcher. Every other program runs out-of-process too (crash isolation +
        // uniform log capture) via ProgramLauncher.
        if (isGuiProgram(code)) {
            onRunGuiClicked();
            return;
        }
        String sourceName = currentFile != null ? currentFile.getFileName().toString() : "<editor>";
        // Resolve sibling `requires` from the open file's directory; captured on the
        // main thread (the click handler) so the worker doesn't race a file change.
        launchProgram("sibarum.pontif.net.ProgramLauncher", RUN_HEADLESS_FLAG, sourceName, code,
                resolveDir(), sourceName + " finished");
    }

    /**
     * Launches the current buffer as a GUI program in its OWN window, via the {@code GuiLauncher}
     * subprocess (docs/extensions.md). The GUI program owns its own GLFW context + root thread, so
     * it never collides with the editor's window/loop. Delegates to {@link #launchProgram} — the
     * same out-of-process path ordinary runs use — differing only in the child main class (which
     * installs the windowed extensions) and the completion wording.
     */
    private static void onRunGuiClicked() {
        String code = TextStates.contentOf(codeText);
        String sourceName = currentFile != null ? currentFile.getFileName().toString() : "editor.ptf";
        launchProgram("sibarum.pontif.gui.GuiLauncher", RUN_GUI_FLAG, sourceName, code, resolveDir(),
                "GUI window closed (" + sourceName + ")");
    }

    /**
     * Runs {@code code} out-of-process through the debug port, on a worker thread so the editor
     * stays responsive while the program (or its window) is alive. Stands up a loopback
     * {@link DebugServer} first and hands the child its port via {@link DebugSession#PORT_ENV}; the
     * child ({@code mainClass}: {@code ProgramLauncher} headless, or {@code GuiLauncher} windowed)
     * streams typed telemetry back — all routed into the {@link Status} event log by
     * {@link #startDebugServer}. The child's merged stdout/stderr is drained line-by-line into the
     * same log (see {@link #drainAndCapture}), so uncaught exceptions and native/JVM output are
     * captured even when the debug port never opens; the process exit code is the crash backstop.
     * Classpath is inherited from the editor (which depends on pontif-builtin-gui → -net), so both
     * launchers resolve. {@code resolveDir} is captured by the caller on the main thread so the
     * worker can't race a file change.
     */
    private static void launchProgram(String jvmMainClass, String nativeFlag, String sourceName,
                                      String code, Path resolveDir, String successMessage) {
        String resolveArg = resolveDir != null ? resolveDir.toString() : "";
        Status.info("launching " + sourceName + " ...");
        Thread worker = new Thread(() -> {
            Path tmp = null;
            DebugServer debug = null;
            try {
                // The buffer runs from a temp file, so sibling `requires` resolve against the
                // ORIGINAL file's directory (passed as resolveArg — the temp dir has none).
                tmp = Files.createTempFile("pontif-run-", ".ptf");
                Files.writeString(tmp, code, StandardCharsets.UTF_8);
                // Stand up the debug port BEFORE spawning so we can hand the child its port; if it
                // can't open, run untapped rather than block the launch.
                debug = startDebugServer(sourceName);
                ProcessBuilder pb = new ProcessBuilder(
                        childCommand(jvmMainClass, nativeFlag, tmp.toString(), resolveArg, sourceName));
                if (debug != null) {
                    pb.environment().put(DebugSession.PORT_ENV, Integer.toString(debug.port()));
                }
                pb.redirectErrorStream(true);   // merge stderr into stdout: one chronological stream
                Process proc = pb.start();
                // Drain the merged stream to EOF (which coincides with process exit), logging each
                // line — no separate reader thread, no pipe-buffer deadlock. waitFor() then returns
                // immediately.
                String captured = drainAndCapture(proc);
                int exit = proc.waitFor();
                if (exit == 0) {
                    Status.success(successMessage);
                } else {
                    // The process-level exit code is the crash backstop: a hard death (segfault,
                    // System.exit, OOM) never gets to send RunFailed over the debug port, so this
                    // is the authoritative failure witness. Each output line is already an event
                    // (drainAndCapture → Status.log); only when nothing was captured do we carry a
                    // hint as the event's details.
                    String details = captured.isBlank()
                            ? "The program exited with code " + exit + " and produced no output."
                            : null;
                    Status.error(sourceName + " exited with code " + exit, details);
                }
            } catch (IOException | InterruptedException e) {
                Status.error("Could not launch " + sourceName + ": " + e.getMessage(), String.valueOf(e));
            } finally {
                if (debug != null) {
                    debug.close();
                }
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                        // best-effort cleanup of the temp .ptf
                    }
                }
            }
        }, "pontif-runner");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Builds the child process command to run a program out-of-process. Two shapes:
     * <ul>
     *   <li><b>JVM editor</b> — {@code java --enable-native-access=ALL-UNNAMED -cp <inherited cp>
     *       <jvmMainClass> <args>}. The editor's classpath (pontif-builtin-gui → -net) resolves
     *       both launchers.</li>
     *   <li><b>Native-image editor</b> — {@code <this-exe> <nativeFlag> <args>}. A native image has
     *       no bundled {@code java} and no classpath of {@code .class} files, so it re-executes
     *       itself; {@code main} routes {@code nativeFlag} to the same launcher (compiled into the
     *       image). The self path comes from {@link ProcessHandle}.</li>
     * </ul>
     */
    private static List<String> childCommand(String jvmMainClass, String nativeFlag,
                                             String program, String resolveArg, String sourceName) {
        if (isNativeImage()) {
            String self = ProcessHandle.current().info().command().orElseThrow(() ->
                    new IllegalStateException("cannot determine the editor executable to launch a program"));
            return List.of(self, nativeFlag, program, resolveArg, sourceName);
        }
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(
                javaBin,
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                jvmMainClass,
                program,
                resolveArg,
                sourceName);
    }

    /** True when this editor is itself a GraalVM native image (set to {@code "runtime"} while a
     *  built image runs; absent on the JVM). Decides how {@link #childCommand} spawns a program. */
    private static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    /**
     * Opens the loopback debug port for a run, or returns {@code null} if it can't bind (the
     * program then runs untapped). The listener records the child's typed telemetry — run
     * lifecycle, domain events, and action fan-out — into the {@link Status} event log (the
     * dialog that opens when the bottom ribbon is clicked). Program stdout/stderr are
     * intentionally NOT taken from here: the merged process stream (see {@link #drainAndCapture})
     * already logs them verbatim (and carries native/JVM output the interpreter emit-seam can't
     * see), so mirroring the {@code StdoutChunk}/{@code StderrChunk} telemetry too would double
     * every line. Callbacks run on elektro-Q's receive thread; {@code Status.log} is thread-safe.
     */
    private static DebugServer startDebugServer(String source) {
        try {
            return DebugServer.start(new DebugServer.Listener() {
                @Override public void onRunStarted(String src) {
                    Status.info("run started: " + src);
                }
                @Override public void onEvent(long seq, String typeName, sibarum.elektro.queue.dyn.DynValue payload) {
                    Status.log("event #" + seq + " " + typeName + " " + payload);
                }
                @Override public void onActionFired(String reactionName, String eventType) {
                    Status.log("action " + reactionName + " reacted to " + eventType);
                }
                @Override public void onRunCompleted(String resultText) {
                    Status.success("run completed -> " + resultText);
                }
                @Override public void onRunFailed(String message, int line, int col) {
                    String at = (line > 0) ? " (" + line + ":" + col + ")" : "";
                    Status.error("run failed" + at, message);
                }
            });
        } catch (RuntimeException e) {
            Status.warn("debug port unavailable (" + e.getMessage() + "); running untapped");
            return null;
        }
    }

    /** Cap on the captured GUI-output tail surfaced in the log dialog (chars). */
    private static final int GUI_CAPTURE_CAP = 64 * 1024;

    /**
     * Reads {@code proc}'s (merged) output stream to EOF, echoing each line to the editor's own
     * {@code System.out} and accumulating a bounded tail. Only the last {@link #GUI_CAPTURE_CAP}
     * characters are retained (a long-lived window can't grow this without limit); when the tail
     * is trimmed a marker is prefixed. Returns the captured tail; never throws (a read failure
     * just ends capture, leaving what was gathered so far).
     */
    private static String drainAndCapture(Process proc) {
        StringBuilder buf = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println(line);           // preserve the dev-tree terminal view
                Status.log(line);                   // and record it in the editor's event log
                buf.append(line).append('\n');
                if (buf.length() > GUI_CAPTURE_CAP) {
                    buf.delete(0, buf.length() - GUI_CAPTURE_CAP);
                }
            }
        } catch (IOException ignored) {
            // read failed / stream closed early — return whatever was captured so far
        }
        String out = buf.toString();
        return out.length() >= GUI_CAPTURE_CAP
                ? "…(earlier output truncated)…\n" + out
                : out;
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

    /** Fill the "reflected source" inspector from the current editor source —
     *  the program re-emitted with declared sorts replaced by inferred narrowings
     *  (entrypoint defaults to main). Bounded + fast; runs on the tab-switch
     *  callback like {@link #regenerateIrAst}. */
    private static void regenerateNarrowings() {
        String code = TextStates.contentOf(codeText);
        String sourceName = currentFile != null ? currentFile.getFileName().toString() : "<editor>";
        String text = switch (ReflectionReport.fromAltSource(code, sourceName, resolveDir(), narrowingsEntry)) {
            case ReflectionReport.Result.Generated g -> g.text();
            case ReflectionReport.Result.Failed f -> f.error();
        };
        TextStates.setContent(narrowingsText, text);
    }

    /** Every press of the Narrowings tab — even while it's already active — opens
     *  the entrypoint menu (Dasum's onTabPressed fires on every press, unlike the
     *  activeIndex change signal). Dismiss returns to the committed tab; selecting
     *  an entrypoint opens Narrowings reflected from it. */
    private static void onTabPressed(Integer idx) {
        if (idx != null && idx == NARROWINGS_TAB) openEntrypointMenu();
    }

    /** Modal entrypoint chooser: pick the function (or main) to reflect the
     *  computation tree from. Reuses the overlay idiom the System menu uses. */
    private static void openEntrypointMenu() {
        if (OverlayStack.isActive()) return;  // don't stack on another overlay
        String code = TextStates.contentOf(codeText);
        String sourceName = currentFile != null ? currentFile.getFileName().toString() : "<editor>";
        String current = narrowingsEntry == null ? "main" : narrowingsEntry;

        java.util.List<Component> rows = new java.util.ArrayList<>();
        rows.add(new Component.Text("Reflect from entrypoint", Em.of(1.15f), MENU_TITLE_FG));
        rows.add(new Component.Text(
                "The reflected code is the call tree reachable from this entrypoint, "
                        + "narrowed by how it's called.", Em.of(0.9f), MENU_HINT_FG));
        for (String name : ReflectionReport.entrypoints(code, sourceName)) {
            Variant v = name.equals(current) ? Variant.PRIMARY : Variant.DEFAULT;
            rows.add(Themed.button(name, Em.of(18f), v, 0, () -> chooseEntrypoint(name)));
        }
        rows.add(Themed.button("Close", Em.of(18f), Variant.DEFAULT, 0, OverlayStack::pop));

        Component panel = new Component.Flex(
                Em.of(24f), Em.AUTO, Em.of(1f), MENU_BG,
                Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.5f),
                rows, false, 0);
        // Dismiss (Close, click-outside, ESC) → return to the tab we came from. On a
        // selection chooseEntrypoint() has already committed Narrowings, so this set
        // is a no-op then.
        OverlayStack.push(new OverlayStack.Overlay(
                panel, Anchor.CENTER, true, () -> activeTab.set(committedTab)));
    }

    private static void chooseEntrypoint(String name) {
        narrowingsEntry = "main".equals(name) ? null : name;
        committedTab = NARROWINGS_TAB;     // settle on Narrowings (onClose revert → no-op)
        regenerateNarrowings();            // refresh behind the overlay before it closes
        activeTab.set(NARROWINGS_TAB);     // ensure the tab is shown (no-op if already)
        OverlayStack.pop();
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
        // Detach the document BEFORE clearing the buffer — see loadFile: setContent fires the
        // live-compile autosave synchronously, so a stale currentFile here would blank the
        // previously open file on disk.
        currentFile = null;
        updateFilenameLabel();
        if (session != null) session.onDocumentChanged(RecoveryStore.keyFor(null), "", null);
        TextStates.setContent(codeText, "");
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
            // Adopt the document BEFORE pushing content into the editor. setContent fires
            // onEditorContentChanged synchronously, which schedules the live-compile autosave
            // against `currentFile` (and pushes the recovery baseline). If the buffer were set
            // first, that autosave would target the PREVIOUSLY open file and overwrite it with
            // the newly opened file's content — the file-swap corruption bug.
            currentFile = path;
            updateFilenameLabel();
            if (session != null) {
                session.onDocumentChanged(RecoveryStore.keyFor(path), content,
                        path.toAbsolutePath().normalize().toString());
            }
            TextStates.setContent(codeText, content);  // fires onEditorContentChanged → highlight + live-compile
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
        scheduleLiveCompile(content);
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

        // A file named on the command line wins over the restored session file.
        if (startupFile != null) {
            loadFile(startupFile, true);
        } else if (restored != null && restored.openFile != null) {
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
            // Ctrl press/release toggles the link-hover underline even with no
            // mouse movement (IntelliJ shows/hides it the instant Ctrl changes).
            updateLinkHover();
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

            // Ctrl+Enter on the caret's word: navigate to its definition, or add the
            // requires for it — the keyboard twin of Ctrl+click. Must precede the plain
            // Enter handler (which would otherwise insert a newline).
            if (ctrl && key == Glfw.GLFW_KEY_ENTER) {
                handleNavigateOrImportAtCaret();
                return;
            }

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
                // Leaving the read-only definition view returns to the Editor.
                if (activeTab != null && activeTab.get() != null
                        && activeTab.get() == DEFINITION_TAB) {
                    activeTab.set(0);
                    Invalidator.invalidate();
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
            // Ctrl-hover: underline the editor identifier under the mouse and show
            // a hand cursor — the "this is a link" affordance (see drawLinkUnderline).
            updateLinkHover();
            cursors.setShape(linkStart >= 0 ? CursorManager.CursorShape.HAND : cursorShapeFor(hit));

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
                // Ctrl+click on an editor identifier navigates to its definition or,
                // when the name isn't in scope, adds the requires for it — instead of
                // moving the caret (the same action Ctrl+Enter runs from the caret).
                boolean ctrl = (mods & Glfw.GLFW_MOD_CONTROL) != 0;
                if (ctrl && HoverState.hovered() == codeText && handleNavigateOrImportUnderMouse()) {
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
                linkStart = -1;   // drop the Ctrl-hover underline when the mouse leaves
                linkEnd = -1;
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

    // --- Ctrl+click "go to definition" + Ctrl-hover link underline ---

    /** Run the navigate-or-import action on the identifier under the mouse. Returns
     *  false when there's no identifier there, so the click falls through to ordinary
     *  caret placement. */
    private static boolean handleNavigateOrImportUnderMouse() {
        int[] w = editorIdentBoundsUnderMouse();
        if (w == null) return false;
        navigateOrImport(TextStates.contentOf(codeText).substring(w[0], w[1]));
        return true;
    }

    /** Run the navigate-or-import action on the identifier at the caret (Ctrl+Enter). */
    private static void handleNavigateOrImportAtCaret() {
        int[] w = caretIdentBounds();
        if (w == null) {
            Status.info("Put the caret on a name to navigate to it or add its requires.");
            return;
        }
        navigateOrImport(TextStates.contentOf(codeText).substring(w[0], w[1]));
    }

    /**
     * The unified action behind Ctrl+click and Ctrl+Enter:
     * <ul>
     *   <li>in scope (declared here or already imported) → open its definition;</li>
     *   <li>not in scope but exported by a module → add/merge its {@code requires}
     *       (a chooser when more than one module exports the name);</li>
     *   <li>not in scope, not exported, but defined somewhere → open it anyway;</li>
     *   <li>primitive / unknown → a status message.</li>
     * </ul>
     */
    private static void navigateOrImport(String name) {
        if (DefinitionNavigator.isPrimitive(name)) {
            Status.info("'" + name + "' is a builtin primitive — no source or import.");
            return;
        }
        String content = TextStates.contentOf(codeText);
        if (DefinitionNavigator.inScope(content, name)) {
            openDefinition(name);
            return;
        }
        java.util.List<String> exporters = DefinitionNavigator.exporters(content, name, resolveDir());
        if (exporters.isEmpty()) {
            if (DefinitionNavigator.resolve(content, name, resolveDir()).isPresent()) {
                openDefinition(name);   // exists but isn't exported — show it, can't import
            } else {
                Status.warn("No definition or exporting module found for '" + name + "'.");
            }
        } else if (exporters.size() == 1) {
            addRequires(exporters.get(0), name);
        } else {
            openImportChoice(name, exporters);
        }
    }

    /** Identifier bounds {@code [start, end)} at the caret, or null when the caret
     *  isn't on (or just past) an identifier. A caret resting at a word's end counts. */
    private static int[] caretIdentBounds() {
        if (codeText == null) return null;
        String content = TextStates.contentOf(codeText);
        if (content.isEmpty()) return null;
        int caret = Math.max(0, Math.min(TextStates.of(codeText).caretIndex, content.length()));
        int probe = caret;
        if (probe >= content.length() || !isIdentChar(content.charAt(probe))) {
            if (caret > 0 && isIdentChar(content.charAt(caret - 1))) probe = caret - 1;  // just past a word
            else return null;
        }
        int[] w = WordBoundary.wordBoundsAt(content, probe);
        if (w == null || w[1] <= w[0]) return null;
        return isIdentifier(content.substring(w[0], w[1])) ? w : null;
    }

    /** Apply {@link DefinitionNavigator#insertRequires} to the editor buffer, shifting the
     *  caret to track inserted text. The pure surgery lives in DefinitionNavigator (tested). */
    private static void addRequires(String module, String name) {
        int caret = TextStates.of(codeText).caretIndex;
        DefinitionNavigator.RequiresEdit edit =
                DefinitionNavigator.insertRequires(TextStates.contentOf(codeText), module, name);
        if (!edit.changed()) {
            Status.info(edit.message());
            return;
        }
        TextStates.setContent(codeText, edit.text());
        TextState ts = TextStates.of(codeText);
        int newCaret = caret >= edit.editOffset()
                ? Math.min(edit.text().length(), caret + edit.delta())
                : caret;
        ts.caretIndex = newCaret;
        ts.selectionAnchor = newCaret;
        Status.success(edit.message() + " — Ctrl+click/Ctrl+Enter again to open it.");
    }

    // Module explorer palette colors.
    private static final Color EXPLORER_MODULE_FG = new Color(0.62f, 0.80f, 1.00f, 1f);

    /**
     * Modal module explorer: browse every importable module and its exported names —
     * the discovery surface for "I don't know the name." Clicking a name imports it
     * from that module (or opens it if already in scope). Scrollable; grouped by module,
     * siblings before builtins.
     */
    private static void openModuleExplorer() {
        if (OverlayStack.isActive()) return;
        String content = TextStates.contentOf(codeText);
        java.util.List<DefinitionNavigator.ModuleExports> modules =
                DefinitionNavigator.allModules(content, resolveDir());

        java.util.List<Component> rows = new java.util.ArrayList<>();
        rows.add(new Component.Text("Modules", Em.of(1.15f), MENU_TITLE_FG));
        rows.add(new Component.Text(
                "Browse exported names. Click one to import it (or open it if already in scope).",
                Em.of(0.9f), MENU_HINT_FG));
        if (modules.isEmpty()) {
            rows.add(new Component.Text("No modules found.", Em.of(0.9f), MENU_HINT_FG));
        }
        String lastGroup = null;
        for (DefinitionNavigator.ModuleExports me : modules) {
            String group = me.builtin() ? "Builtins" : "This project";
            if (!group.equals(lastGroup)) {
                rows.add(new Component.Text(group, Em.of(0.8f), MENU_HINT_FG));
                lastGroup = group;
            }
            rows.add(new Component.Text(me.module(), Em.of(1.0f), EXPLORER_MODULE_FG));
            for (String sym : me.symbols()) {
                final String module = me.module();
                final String symbol = sym;
                boolean inScope = DefinitionNavigator.inScope(content, symbol);
                String label = inScope ? symbol + "  (in scope)" : symbol;
                rows.add(Themed.button(label, Em.of(24f),
                        inScope ? Variant.SUCCESS : Variant.DEFAULT, 0, () -> {
                            OverlayStack.pop();
                            String now = TextStates.contentOf(codeText);
                            if (DefinitionNavigator.inScope(now, symbol)) openDefinition(symbol);
                            else addRequires(module, symbol);
                        }));
            }
        }
        rows.add(Themed.button("Close", Em.of(24f), Variant.DEFAULT, 0, OverlayStack::pop));

        Component list = new Component.Flex(
                null, null, Em.ZERO, MENU_BG,
                Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.3f),
                rows, false, 1);
        Component scroll = new Component.Scroll(Em.of(30f), Em.of(32f), Em.of(0.5f), MENU_BG, list, false, 0);
        Component panel = new Component.Flex(
                Em.AUTO, Em.AUTO, Em.of(0.5f), MENU_BG,
                Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
                java.util.List.of(scroll), false, 0);
        OverlayStack.push(new OverlayStack.Overlay(panel, Anchor.CENTER, true, () -> {}));
    }

    /** Modal chooser when more than one module exports the name — pick the import source. */
    private static void openImportChoice(String name, java.util.List<String> exporters) {
        if (OverlayStack.isActive()) return;
        java.util.List<Component> rows = new java.util.ArrayList<>();
        rows.add(new Component.Text("Import '" + name + "' from…", Em.of(1.15f), MENU_TITLE_FG));
        rows.add(new Component.Text(
                "Several modules export this name — choose where to import it from.",
                Em.of(0.9f), MENU_HINT_FG));
        for (String mod : exporters) {
            rows.add(Themed.button(mod, Em.of(20f), Variant.DEFAULT, 0, () -> {
                addRequires(mod, name);
                OverlayStack.pop();
            }));
        }
        rows.add(Themed.button("Cancel", Em.of(20f), Variant.DEFAULT, 0, OverlayStack::pop));
        Component panel = new Component.Flex(
                Em.of(26f), Em.AUTO, Em.of(1f), MENU_BG,
                Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.5f),
                rows, false, 0);
        OverlayStack.push(new OverlayStack.Overlay(panel, Anchor.CENTER, true, () -> {}));
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Identifier bounds {@code [start, end)} under the mouse in the editor, or
     *  null when the cursor isn't over an identifier (or the editor isn't laid
     *  out — e.g. another tab is showing). */
    private static int[] editorIdentBoundsUnderMouse() {
        if (codeText == null) return null;
        LayoutResult lr = LatestLayout.result();
        PixelRect rect = lr == null ? null : lr.rectOf(codeText);
        if (rect == null) return null;
        String content = TextStates.contentOf(codeText);
        int idx = TextGeometry.charIndexAt(codeText, content, rect,
                (float) InputState.mouseX(), (float) InputState.mouseY());
        int[] w = WordBoundary.wordBoundsAt(content, idx);
        if (w == null || w[1] <= w[0]) return null;
        return isIdentifier(content.substring(w[0], w[1])) ? w : null;
    }

    /** Look up {@code name}'s definition and show it in the read-only Definition
     *  tab — name highlighted, scrolled into view. Primitives and misses flash an
     *  explanatory status instead of switching tabs. */
    private static void openDefinition(String name) {
        if (DefinitionNavigator.isPrimitive(name)) {
            Status.info("'" + name + "' is a builtin primitive — no source to open.");
            return;
        }
        Optional<DefinitionNavigator.Target> found = DefinitionNavigator.resolve(
                TextStates.contentOf(codeText), name, resolveDir());
        if (found.isEmpty()) {
            Status.warn("No definition found for '" + name + "'.");
            return;
        }
        DefinitionNavigator.Target def = found.get();
        TextStates.setContent(definitionText, def.sourceText());
        // Same token syntax coloring as the editor (foreground only — the
        // background axis carries the reference highlights below).
        TextStyleStates.setForeground(definitionText, AltHighlighter.foreground(def.sourceText()));
        // Highlight EVERY occurrence of the name — the declaration strongly, its
        // other references faintly. Background spans render whether or not the text
        // is focused (unlike a selection highlight), so they're the reliable marker;
        // the selection rides along so the caret sits on the declaration and Ctrl+C
        // copies it.
        java.util.List<TextStyle> spans = new java.util.ArrayList<>();
        for (int[] ref : DefinitionNavigator.references(def.sourceText(), name)) {
            boolean isDecl = ref[0] == def.selStart() && ref[1] == def.selEnd();
            spans.add(new TextStyle(ref[0], ref[1], isDecl ? DEFN_HIGHLIGHT : DEFN_REF_HIGHLIGHT));
        }
        TextStyleStates.setBackground(definitionText, spans);
        TextState ts = TextStates.of(definitionText);
        ts.selectionAnchor = def.selStart();
        ts.caretIndex = def.selEnd();
        ts.hoverCaretIndex = -1;
        activeTab.set(DEFINITION_TAB);
        scrollDefnPending = true;   // scroll once the tab's text has a layout rect
        Status.success("Definition of '" + name + "' in " + def.moduleLabel()
                + " — Esc to return to the editor.");
    }

    /** Nudge the Definition pane's scroll so the highlighted name is visible — the
     *  public-API twin of {@code TextInputController.scrollCaretIntoView}, which is
     *  package-private to dasum. */
    private static void scrollSelectionIntoView(Component.Text text) {
        Component rootC = LatestLayout.root();
        LayoutResult lr = LatestLayout.result();
        if (rootC == null || lr == null) return;
        PixelRect textRect = lr.rectOf(text);
        if (textRect == null) return;
        TextState ts = TextStates.of(text);
        PixelRect caret = TextGeometry.caretBounds(
                text, TextStates.contentOf(text), textRect, ts.selectionStart());
        for (Component anc : HitTest.pathTo(rootC, text)) {
            if (!(anc instanceof Component.Scroll scroll)) continue;
            PixelRect outer = lr.rectOf(scroll);
            if (outer == null) continue;
            float pad = scroll.padding().toPixels();
            PixelRect interior = new PixelRect(
                    outer.x() + pad, outer.y() + pad,
                    Math.max(0f, outer.width() - 2f * pad),
                    Math.max(0f, outer.height() - 2f * pad));
            float dx = 0f, dy = 0f;
            if (caret.bottom() > interior.bottom()) dy = caret.bottom() - interior.bottom();
            else if (caret.y() < interior.y())      dy = caret.y() - interior.y();
            if (caret.right() > interior.right())   dx = caret.right() - interior.right();
            else if (caret.x() < interior.x())      dx = caret.x() - interior.x();
            if (dx != 0f || dy != 0f) ScrollStates.of(scroll).scrollByPx(dx, dy);
        }
        Invalidator.invalidate();
    }

    /** Recompute the Ctrl-hover underline target: the editor identifier under the
     *  mouse while Ctrl is held and the editor is the active, hovered pane. Cheap
     *  enough to run on every cursor move and Ctrl press/release. */
    private static void updateLinkHover() {
        boolean ctrl = window != null
                && (Glfw.glfwGetKey(window.handle(), Glfw.GLFW_KEY_LEFT_CONTROL) == Glfw.GLFW_PRESS
                 || Glfw.glfwGetKey(window.handle(), Glfw.GLFW_KEY_RIGHT_CONTROL) == Glfw.GLFW_PRESS);
        int[] w = (ctrl && !OverlayStack.isActive() && HoverState.hovered() == codeText)
                ? editorIdentBoundsUnderMouse() : null;
        // Don't underline primitives — they aren't navigable.
        if (w != null && DefinitionNavigator.isPrimitive(
                TextStates.contentOf(codeText).substring(w[0], w[1]))) {
            w = null;
        }
        int ns = w == null ? -1 : w[0];
        int ne = w == null ? -1 : w[1];
        if (ns != linkStart || ne != linkEnd) {
            linkStart = ns;
            linkEnd = ne;
            Invalidator.invalidate();
        }
    }

    /** Draw the Ctrl-hover underline beneath the editor identifier, clipped to the
     *  editor viewport. No-op unless a word is hovered and the Editor tab is showing
     *  (an inactive tab's text has no layout rect). */
    private static void drawLinkUnderline(LayoutResult layout, Batcher batcher) {
        if (linkStart < 0 || linkEnd <= linkStart || codeText == null) return;
        PixelRect tr = layout.rectOf(codeText);
        if (tr == null) return;   // Editor tab not active
        String content = TextStates.contentOf(codeText);
        if (linkEnd > content.length()) return;
        PixelRect a = TextGeometry.caretBounds(codeText, content, tr, linkStart);
        PixelRect b = TextGeometry.caretBounds(codeText, content, tr, linkEnd);
        float x = a.x();
        float right = b.x();
        if (right <= x) return;   // word wrapped across visual lines — skip
        float y = a.bottom() - 2f;
        PixelRect vp = codeScroll == null ? null : layout.rectOf(codeScroll);
        if (vp != null) {
            if (y < vp.y() || y > vp.bottom()) return;   // scrolled out of view
            x = Math.max(x, vp.x());
            right = Math.min(right, vp.right());
            if (right <= x) return;
        }
        batcher.submit(new DrawCommand.ColoredQuad(x, y, right - x, 1.5f, LINK_UNDERLINE));
    }

    // --- Live compilation: debounced compile + error underlines + autosave ---

    /** Debounced live compile: cancel any pending pass and schedule a fresh one
     *  {@value #COMPILE_DEBOUNCE_MS} ms out. The buffer, source name, resolve
     *  directory, and file are captured on the GLFW thread so the worker can't
     *  race a file swap. Stale underlines are dropped immediately — we no longer
     *  know where the errors are until the next pass settles (matching the
     *  highlighter's mid-edit tolerance: show nothing rather than something wrong). */
    private static void scheduleLiveCompile(String content) {
        long version = ++editVersion;
        errorMarks = List.of();
        String sourceName = currentFile != null ? currentFile.getFileName().toString() : "<editor>";
        Path resolveDir = resolveDir();
        Path file = currentFile;
        if (pendingCompile != null) pendingCompile.cancel(false);
        pendingCompile = COMPILE_SCHEDULER.schedule(
                () -> runLiveCompile(content, sourceName, resolveDir, file, version),
                COMPILE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /** Compile {@code content} (no run), publish error underlines, and — on a clean
     *  compile — autosave to {@code file}. Runs on the debounce thread. The result
     *  is discarded if the buffer changed since it was scheduled ({@code version}
     *  no longer current): a newer pass already owns the marks. */
    private static void runLiveCompile(String content, String sourceName, Path resolveDir,
                                       Path file, long version) {
        PontifCompiler.CompileResult result = COMPILER.compileAlt(content, sourceName, resolveDir);
        if (version != editVersion) return;   // superseded by a newer edit
        if (result instanceof PontifCompiler.CompileResult.Failed failed) {
            errorMarks = computeMarks(content, sourceName, failed.error());
        } else {
            errorMarks = List.of();
            autosaveClean(content, file, version);
        }
        Invalidator.invalidate();
    }

    /** Translate a failed compile into editor underlines. An error located in an
     *  imported module (a link error, or an origin from another source) is mapped
     *  to the offending {@code requires} statement; an in-buffer error is mapped to
     *  its origin span. Empty when there is no origin to anchor to. */
    private static List<ErrorMark> computeMarks(String content, String sourceName,
                                                PontifRunner.RunResult error) {
        String message = error.text();
        Optional<Origin> originOpt = error.origin();
        boolean fromImport = message.startsWith("Link error")
                || (originOpt.isPresent() && !sourceName.equals(originOpt.get().source()));
        if (fromImport) {
            return requiresMarks(content, message, originOpt.orElse(null));
        }
        if (originOpt.isPresent() && originOpt.get().isPresent()) {
            int[] span = spanOffsets(content, originOpt.get());
            if (span != null) return List.of(new ErrorMark(span[0], span[1], message, false));
        }
        return List.of();
    }

    /** Char offsets {@code [start, end)} for an origin span, clamped to the start
     *  line so a multi-line span underlines just its first line. A point span
     *  (start == end) is widened over the token that begins there. Null if it
     *  doesn't map into {@code content}. */
    private static int[] spanOffsets(String content, Origin o) {
        int start = offsetOf(content, o.span().start().line(), o.span().start().column());
        if (start < 0) return null;
        int end = offsetOf(content, o.span().end().line(), o.span().end().column());
        if (end <= start) end = wordEnd(content, start);
        int lineEnd = content.indexOf('\n', start);
        if (lineEnd < 0) lineEnd = content.length();
        end = Math.min(end, lineEnd);
        if (end <= start) end = Math.min(start + 1, content.length());
        return start >= end ? null : new int[]{start, end};
    }

    /** Char offset of 1-based {@code (line, column)}, or -1 if the line is out of
     *  range. The column is clamped to the buffer length. */
    private static int offsetOf(String content, int line, int column) {
        int idx = 0, ln = 1;
        while (ln < line && idx < content.length()) {
            if (content.charAt(idx) == '\n') ln++;
            idx++;
        }
        if (ln != line) return -1;
        return Math.min(idx + (column - 1), content.length());
    }

    /** End of the identifier token starting at {@code start} (at least one char). */
    private static int wordEnd(String content, int start) {
        int i = start;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') i++;
            else break;
        }
        return i > start ? i : Math.min(start + 1, content.length());
    }

    /** Underlines over the {@code requires} statement(s) implicated by an import
     *  error. Prefers the one whose module name appears in the error message or
     *  matches the foreign origin's source; falls back to every {@code requires}
     *  line so the import area is still flagged when no single one can be pinned. */
    private static List<ErrorMark> requiresMarks(String content, String message, Origin origin) {
        String foreign = origin != null && origin.isPresent() ? stripExt(origin.source()) : null;
        List<ErrorMark> all = new ArrayList<>();
        List<ErrorMark> matched = new ArrayList<>();
        int offset = 0;
        for (String line : content.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("requires ")) {
                int lead = line.length() - line.stripLeading().length();
                int start = offset + lead;
                int end = offset + line.length();
                if (end > start) {
                    ErrorMark mark = new ErrorMark(start, end, message, true);
                    all.add(mark);
                    String module = requiresModule(t);
                    if (module != null && (message.contains(module) || (foreign != null
                            && (foreign.equals(module) || foreign.contains(module) || module.contains(foreign))))) {
                        matched.add(mark);
                    }
                }
            }
            offset += line.length() + 1;   // + the '\n' that split removed
        }
        return List.copyOf(matched.isEmpty() ? all : matched);
    }

    /** The module name in a {@code requires <module>.{…}} (or {@code requires <module>}) line. */
    private static String requiresModule(String strippedLine) {
        String rest = strippedLine.substring("requires ".length()).strip();
        int db = rest.indexOf(".{");
        if (db > 0) return rest.substring(0, db).strip();
        int sp = rest.indexOf(' ');
        return sp > 0 ? rest.substring(0, sp).strip() : rest;
    }

    private static String stripExt(String source) {
        if (source == null) return null;
        int dot = source.lastIndexOf('.');
        return dot > 0 ? source.substring(0, dot) : source;
    }

    /** Write {@code content} to {@code file} after a clean compile — the autosave-
     *  on-success rule. Skips an untitled buffer (no file; the recovery snapshot
     *  still covers it), a buffer that changed since the compile, and a file that
     *  already matches. Quiet on success; failures flash in the ribbon. */
    private static void autosaveClean(String content, Path file, long version) {
        if (file == null || version != editVersion) return;
        try {
            String onDisk = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            if (content.equals(onDisk)) return;   // nothing to write
            Files.writeString(file, content, StandardCharsets.UTF_8);
            // The on-disk file is now the source of truth, so drop the recovery copy
            // and reset the dirty baseline (same as an explicit Save of this file).
            if (session != null) {
                String key = RecoveryStore.keyFor(file);
                session.onSaved(key, key, content, file.toAbsolutePath().normalize().toString());
            }
        } catch (IOException e) {
            Status.error("Autosave failed for " + file.getFileName() + ": " + e.getMessage());
        }
    }

    /** Show the nearest error's message in the ribbon when the caret is within or
     *  adjacent to an underlined error; restore the default hint otherwise. Runs
     *  each frame on the GLFW thread, republishing only when the message changes. */
    private static void updateErrorStatus() {
        if (codeText == null) return;
        int caret = TextStates.of(codeText).caretIndex;
        String near = null;
        for (ErrorMark m : errorMarks) {
            if (caret >= m.start() && caret <= m.end()) { near = m.message(); break; }
        }
        if (near != null) {
            if (!near.equals(shownErrorMessage)) {
                shownErrorMessage = near;
                Status.setDefaultMessage(near, Variant.ERROR);
            }
        } else if (shownErrorMessage != null) {
            shownErrorMessage = null;
            Status.setDefaultMessage(DEFAULT_STATUS, Variant.DEFAULT);
        }
    }

    /** Red underline beneath each error's token (or its {@code requires} statement),
     *  clipped to the editor viewport — the error analogue of {@link
     *  #drawLinkUnderline}. No-op unless the Editor tab is showing. */
    private static void drawErrorUnderlines(LayoutResult layout, Batcher batcher) {
        List<ErrorMark> marks = errorMarks;
        if (marks.isEmpty() || codeText == null) return;
        PixelRect tr = layout.rectOf(codeText);
        if (tr == null) return;   // Editor tab not active
        String content = TextStates.contentOf(codeText);
        PixelRect vp = codeScroll == null ? null : layout.rectOf(codeScroll);
        for (ErrorMark m : marks) {
            if (m.start() >= m.end() || m.end() > content.length()) continue;
            PixelRect a = TextGeometry.caretBounds(codeText, content, tr, m.start());
            PixelRect b = TextGeometry.caretBounds(codeText, content, tr, m.end());
            float x = a.x();
            float right = b.x();
            if (right <= x) continue;   // wrapped across visual lines — skip
            float y = a.bottom() - 1.5f;
            if (vp != null) {
                if (y < vp.y() || y > vp.bottom()) continue;   // scrolled out of view
                x = Math.max(x, vp.x());
                right = Math.min(right, vp.right());
                if (right <= x) continue;
            }
            batcher.submit(new DrawCommand.ColoredQuad(x, y, right - x, 2f, ERROR_MARK));
        }
    }

    /** A bare Pontif identifier: a letter/underscore start, then letters/digits/{@code _}/{@code $}. */
    private static boolean isIdentifier(String s) {
        if (s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if (!(Character.isLetter(c0) || c0 == '_')) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '$')) return false;
        }
        return true;
    }
}
