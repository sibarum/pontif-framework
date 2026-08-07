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
import sibarum.dasum.gui.core.find.FindBar;
import sibarum.dasum.gui.core.input.CursorManager;
import sibarum.dasum.gui.core.input.FocusState;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.HoverState;
import sibarum.dasum.gui.core.input.InputState;
import sibarum.dasum.gui.core.input.ScrollFocusFrame;
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
import sibarum.dasum.gui.core.status.Severity;
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
import sibarum.dasum.gui.vis.DasumVis;
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
import sibarum.pontif.runtime.module.Extensions;

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

    private static final String UNTITLED_LABEL = "(untitled)";
    private static final String DEFAULT_FILE_NAME = "untitled.ptf";
    private static final List<FileDialog.Filter> PTF_FILTERS = List.of(
            FileDialog.Filter.of("Pontif source", "ptf"),
            FileDialog.Filter.of("All files", "*"));

    /** Tab indices in the main tab strip (Editor = 0). */
    private static final int EDITOR_TAB = 0;
    private static final int IR_AST_TAB = 1;
    private static final int REPORT_TAB = 2;
    private static final int NARROWINGS_TAB = 3;
    /** Read-only "go to definition" view, populated on Ctrl+click (see {@link #openDefinition}). */
    private static final int DEFINITION_TAB = 4;
    /** Welcome / samples landing page; auto-selected at startup unless "open most recent" is on. */
    private static final int INFO_TAB = 5;

    // Ctrl+click navigation palette: the IntelliJ-style "this is a link" affordance.
    private static final Color LINK_UNDERLINE = new Color(0.40f, 0.62f, 1.00f, 1f);
    // The clicked declaration (strong) vs. its other references (faint) in the
    // definition view — every occurrence of the name is highlighted.
    private static final Color DEFN_HIGHLIGHT     = new Color(0.40f, 0.62f, 1.00f, 0.45f);
    private static final Color DEFN_REF_HIGHLIGHT = new Color(0.85f, 0.75f, 0.30f, 0.30f);

    /** ASCII divider between the two report sections (could now use box-drawing — the
     *  mono atlas is JetBrains Mono, which carries the full Box Drawing block). */
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
    // The editor's + definition view's scroll panes — held so the Ctrl-hover
    // underline can be clipped to whichever viewport owns it (the underline is
    // drawn in the top-level render pass, not inside the Scroll's own clipped render).
    private static Component.Scroll codeScroll;
    private static Component.Scroll definitionScroll;

    // Ctrl-hover "link" underline over the identifier under the mouse (IntelliJ
    // style). [linkStart, linkEnd) into linkView's content, or -1/-1 when no word
    // is underlined. linkView is the code view the underline belongs to (the
    // editor or the definition view). Set by updateLinkHover, drawn in the render loop.
    private static int linkStart = -1;
    private static int linkEnd = -1;
    private static Component.Text linkView = null;
    // The Definition tab's selected name must be scrolled into view, but only
    // after the tab is active and its text has been laid out — so the request is
    // deferred one frame and serviced in the render loop.
    private static boolean scrollDefnPending = false;
    // Same, for the editor: a go-to-definition jump onto a locally-declared name
    // sets the caret and requests its scroll-into-view on the next laid-out frame.
    private static boolean scrollEditorPending = false;
    // The main tab strip's active index, hoisted so the entrypoint menu can revert
    // it on dismiss. `committedTab` is the tab the user actually settled on (a press
    // of the Narrowings tab is transient until an entrypoint is chosen);
    // `narrowingsEntry` is the chosen reflection entrypoint (null = main).
    private static Property<Integer> activeTab;
    private static int committedTab = 0;
    private static String narrowingsEntry = null;
    // Settings menu: "Always open most recent file". When true the editor restores the last
    // open file and boots into the Editor tab; when false (default) it opens the Info/Welcome
    // tab with an untitled buffer. Persisted through SessionState.openMostRecent.
    private static Property<Boolean> openMostRecentSetting;
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
    record ErrorMark(int start, int end, String message, boolean fromImport) {}

    /** Latest compile's error marks; read each frame by the underline + caret
     *  hooks. Single-writer (the debounce worker) / many-reader (GLFW thread). */
    private static volatile List<ErrorMark> errorMarks = List.of();

    /** Monotonic edit counter, bumped on every content change. A compile captures
     *  it at schedule time and discards its result if a newer edit has landed, so
     *  stale marks (computed against text the user has since changed) never show. */
    private static volatile long editVersion = 0L;

    /** The latest {@code editVersion} whose live-compile finished while still current.
     *  When it equals {@link #editVersion} the buffer is "settled" (no edits in flight),
     *  which is the only time the caret hint recomputes — so its parse-backed lookups
     *  never run mid-keystroke. */
    private static volatile long settledVersion = -1L;

    /** The caret-error text last pushed as the status bar's contextual override, so the
     *  per-frame caret check only republishes (or clears) when it actually changes. */
    private static String shownContextual = null;

    // Caret-hint cache: the parse-backed inScope/exporters lookups are too heavy to run
    // every frame, but only change when the token under the caret or the buffer changes.
    private static String hintName = null;      // token the cached hint was computed for
    private static long   hintVersion = -1L;    // editVersion the cache is valid for
    private static String hintResult = null;    // cached hint text (null = no hint applies)

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
        // RESOLVABLE here. Trigger ServiceLoader discovery UP FRONT so the live compiler has them
        // all before its first pass (and no transient "unknown module" underline on startup). This
        // is generic — no per-extension wiring; a new pontif-builtin-* dependency on the editor,
        // shipping its META-INF/services provider file, is picked up automatically. It only makes
        // the modules RESOLVABLE; GUI programs still RUN in a separate process (see onRunGuiClicked
        // / isGuiProgram), so nothing windowed executes in the editor.
        Extensions.installDiscovered();

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
            // Register the dasum-vis SceneView renderer so the Welcome page's screenshot
            // thumbnails (Component.SceneView + ImageLayer) draw in the editor's render pass.
            // Idempotent; must run after Gl.load(). Harmless when no SceneView is on screen.
            DasumVis.init();
            cursors.init();
            EmContext.setDpiScale(win.contentScaleX());
            applyTheme();
            restoreWindowGeometry(win, restored);
            session = new SessionManager(stateEnabled);

            // "Always open most recent file" setting, restored from the session file. Seed the
            // manager with the restored value first so the next autosave tick preserves it, then
            // mirror later toggles (from the System menu checkbox) back into the manager.
            boolean openRecent = restored != null && restored.openMostRecent;
            openMostRecentSetting = new Property<>(openRecent);
            session.setOpenMostRecent(openRecent);
            openMostRecentSetting.subscribe(v -> { if (v != null) session.setOpenMostRecent(v); });

            try (Texture primaryTexture = Texture.fromPngResource("/dasum/atlas/primary.png");
                 Texture monoTexture    = Texture.fromPngResource("/dasum/atlas/mono.png");
                 Texture iconsTexture   = Texture.fromPngResource("/dasum/atlas/icons.png")) {
                AtlasData primaryAtlas = AtlasData.loadFromResource("/dasum/atlas/primary.json");
                AtlasData monoAtlas    = AtlasData.loadFromResource("/dasum/atlas/mono.json");
                AtlasData iconsAtlas   = AtlasData.loadFromResource("/dasum/atlas/icons.json");
                FontGroups.register(FontGroup.of(FontGroups.DEFAULT,        primaryAtlas, primaryTexture));
                FontGroups.register(FontGroup.of(MONO_FONT_GROUP,           monoAtlas,    monoTexture));
                FontGroups.register(FontGroup.of(Icon.DEFAULT_FONT_GROUP,   iconsAtlas,   iconsTexture));

                // Give the global Find bar (Ctrl+F) proper lucide glyphs — dasum-core
                // ships no icons of its own, so the app supplies them (see the mvp demo).
                // Without this the bar still works, falling back to ASCII < > x controls.
                FindBar.configureIcons(new FindBar.IconSpec(
                    Icon.DEFAULT_FONT_GROUP,
                    Icons.CHEVRON_UP, Icons.CHEVRON_DOWN, Icons.X, Icons.SEARCH));

                // The idle bar is the status ledger's own "N new" counter now; the editor
                // only feeds it alerts (Status.good/bad/notify) and a contextual override
                // for caret errors (updateErrorStatus). No app-owned idle message.
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
                    if (scrollEditorPending) {
                        scrollSelectionIntoView(codeText);
                        scrollEditorPending = false;
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
        // The "Window" button was removed: Run already routes GUI programs to the windowed
        // launcher (see onRunClicked → isGuiProgram → onRunGuiClicked), so a separate button
        // was redundant. onRunGuiClicked stays as that internal path.
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
            List.of(runBtn, newBtn, openBtn, saveBtn, saveAsBtn, modulesBtn, filenameLabel, systemBtn),
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
        // Draw the editor's focus ring on the scroll viewport's fixed frame rather than
        // on the (scrolled) text content, so the blue outline doesn't slide out of view
        // as you scroll — the "scroll lives inside the focus boundary" behavior.
        ScrollFocusFrame.enable(codeScroll);
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
        // (see onMouseDown's Ctrl branch → openDefinition). Behaves like the Editor
        // tab but non-editable — same monospace font, line-number gutter, and
        // Ctrl+click / Ctrl+Enter navigation (you can keep following symbols from
        // here). Esc returns to the Editor.
        definitionText = new Component.Text(
            "Ctrl+click a type, function, or method name (here or in the editor) to open its definition.",
            MONO_FONT_GROUP, Em.of(0.95f), CODE_FG,
            null, null, Em.of(0.5f),
            null, false,
            true, true, false, false, 1).withLineNumbers(true);

        definitionScroll = new Component.Scroll(null, null, Em.ZERO, EDITOR_BG, definitionText, false, 1);
        ScrollFocusFrame.enable(definitionScroll);
        Component definitionPane = definitionScroll;

        // Info tab: the Welcome page — a gallery of sample programs. Clicking a card loads
        // that sample into the editor as a fresh untitled buffer (see loadSample). Wrapped in
        // a Scroll like the other panes so the card grid scrolls when it overflows.
        Component welcomePane = new Component.Scroll(
                null, null, Em.ZERO, EDITOR_BG, WelcomePage.build(App::loadSample), false, 1);

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
                new Component.Tabs.TabPanel("Definition", definitionPane),
                new Component.Tabs.TabPanel("Info", welcomePane)),
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
    private static final java.util.Set<String> GUI_MODULES =
            java.util.Set.of("pontif.gui", "pontif.plot", "pontif.vulkan");

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
        Status.notify("launching " + sourceName + " ...");
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
                    Status.good(successMessage);
                } else {
                    // The process-level exit code is the crash backstop: a hard death (segfault,
                    // System.exit, OOM) never gets to send RunFailed over the debug port, so this
                    // is the authoritative failure witness. Each output line is already an event
                    // (drainAndCapture → Status.log); only when nothing was captured do we carry a
                    // hint as the event's details.
                    String details = captured.isBlank()
                            ? "The program exited with code " + exit + " and produced no output."
                            : null;
                    Status.bad(sourceName + " exited with code " + exit, details);
                }
            } catch (IOException | InterruptedException e) {
                Status.bad("Could not launch " + sourceName + ": " + e.getMessage(), String.valueOf(e));
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
                    Status.notify("run started: " + src);
                }
                @Override public void onEvent(long seq, String typeName, sibarum.elektro.queue.dyn.DynValue payload) {
                    Status.log("event #" + seq + " " + typeName + " " + payload);
                }
                @Override public void onActionFired(String reactionName, String eventType) {
                    Status.log("action " + reactionName + " reacted to " + eventType);
                }
                @Override public void onRunCompleted(String resultText) {
                    Status.good("run completed -> " + resultText);
                }
                @Override public void onRunFailed(String message, int line, int col) {
                    String at = (line > 0) ? " (" + line + ":" + col + ")" : "";
                    Status.bad("run failed" + at, message);
                }
            });
        } catch (RuntimeException e) {
            Status.bad("debug port unavailable (" + e.getMessage() + "); running untapped");
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
        Status.good("New file");
    }

    private static void onOpenClicked() {
        FileDialog.open(window, PTF_FILTERS, dialogStartPath()).ifPresent(path -> loadFile(path, true));
    }

    /** Load a Welcome-page sample into the editor as a fresh untitled buffer and switch to the
     *  Editor tab. Mirrors {@link #onNewClicked} + the content push in {@link #loadFile}: detach
     *  the document before setContent (which fires the autosave synchronously), so nothing on disk
     *  is touched. Save As keeps it. */
    static void loadSample(Samples.Sample sample) {
        String src = Samples.source(sample);
        currentFile = null;
        updateFilenameLabel();
        if (session != null) session.onDocumentChanged(RecoveryStore.keyFor(null), src, null);
        TextStates.setContent(codeText, src);
        activeTab.set(EDITOR_TAB);
        Status.good("Loaded sample: " + sample.title());
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
            if (announce) Status.good("Opened " + path.getFileName());
            return true;
        } catch (IOException e) {
            if (announce) {
                Status.bad("Error opening " + path.getFileName() + ": " + e.getMessage(), path.toString());
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
            Status.good("Saved " + path.getFileName());
        } catch (IOException e) {
            Status.bad("Error saving " + path.getFileName() + ": " + e.getMessage(), path.toString());
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

    /** Adopt the startup document and choose the opening tab. A file named on the command
     *  line always wins and opens in the Editor. Otherwise, when "Always open most recent
     *  file" is on, the last session file is restored into the Editor (the classic behavior);
     *  when it's off (the default), the last file is left closed and the Info/Welcome tab is
     *  shown over the untitled buffer. Finally flag any recovery for whichever document is
     *  active. */
    private static void initializeDocument(SessionState restored) {
        // The editor currently holds DEFAULT_CODE as an untitled buffer.
        if (session != null) session.onDocumentChanged(RecoveryStore.keyFor(null), DEFAULT_CODE, null);

        boolean openRecent = openMostRecentSetting != null && Boolean.TRUE.equals(openMostRecentSetting.get());
        if (startupFile != null) {
            loadFile(startupFile, true);
        } else if (openRecent && restored != null && restored.openFile != null) {
            Path path = Path.of(restored.openFile);
            if (Files.isReadable(path)) loadFile(path, false);
        } else {
            // Default: no most-recent restore — land on the Welcome page instead of the Editor.
            activeTab.set(INFO_TAB);
        }

        String activeKey = RecoveryStore.keyFor(currentFile);
        if (session != null && session.hasRecovery(activeKey)) {
            Status.notify("Unsaved changes from a previous session are available for "
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

        // Setting: "Always open most recent file". Bound to openMostRecentSetting, whose
        // subscription (wired in main) mirrors it into the session file. When on, the next
        // launch restores the last file into the Editor tab; when off, it opens the Info tab.
        rows.add(new Component.Flex(
                null, null, Em.ZERO, Color.TRANSPARENT,
                Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.5f),
                java.util.List.of(
                        Themed.checkbox(Em.of(1.1f), openMostRecentSetting, Variant.PRIMARY),
                        new Component.Text("Always open most recent file", Em.of(0.95f), MENU_TITLE_FG)),
                false, 0));

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
            Status.bad("No recovery available for " + documentDisplayName());
            return;
        }
        // Replaces the editor buffer; the same file stays open. The recovered
        // text now differs from disk, so it's treated as unsaved until saved
        // (at which point the recovery copy is deleted).
        TextStates.setContent(codeText, recovered.get());
        Status.good("Recovered " + documentDisplayName() + " — save to keep the changes.");
    }

    private static void onPurgeAllClicked() {
        int removed = session == null ? 0 : session.purgeAll();
        OverlayStack.pop();
        Status.good("Purged " + removed + " recovery file" + (removed == 1 ? "" : "s"));
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
            // Ctrl+D — duplicate the current line, or the full lines spanned by the
            // selection (the selection is first grown out to whole lines). Editor only.
            if (ctrl && key == 'D' && FocusState.focused() == codeText) { duplicateEditorLines(); return; }
            if (ctrl && key == 'Z') {
                if (shift) { if (TextInputController.onRedo()) return; }
                else       { if (TextInputController.onUndo()) return; }
            }
            if (ctrl && key == 'Y' && TextInputController.onRedo()) return;

            // Ctrl+F opens the Find bar for the focused selectable text area (the
            // editor or any of the read-only inspector panes). Guarded on focus so
            // it only fires where there's something to search; FindBar.open is itself
            // a no-op otherwise.
            if (ctrl && key == 'F') {
                // Already open (focus may have moved to the editor): snap back
                // to the query field with its contents selected, so a type or
                // paste replaces the query.
                if (FindBar.isOpen()) {
                    FindBar.focusAndSelectQuery();
                    return;
                }
                Component f = FocusState.focused();
                if (f instanceof Component.Text t && t.selectable()) {
                    FindBar.open();
                    return;
                }
            }

            // Ctrl+Enter on the caret's word: navigate to its definition, or add the
            // requires for it — the keyboard twin of Ctrl+click. Works in the editor
            // and the read-only Definition view (whichever holds focus, else the
            // active tab's code view). Must precede the plain Enter handler (which
            // would otherwise insert a newline).
            if (ctrl && key == Glfw.GLFW_KEY_ENTER) {
                Component.Text view = navigableView(FocusState.focused());
                if (view == null) view = activeCodeView();
                if (view != null) handleNavigateOrImportAtCaret(view);
                return;
            }

            // Find bar (if open) consumes Enter / Shift+Enter / Up / Down for
            // next/prev-match navigation before the editing handlers, so Enter
            // doesn't insert a newline and the arrows don't move the query caret.
            // Esc closes it via the generic overlay pop in the ESCAPE branch below.
            if (FindBar.handleKey(key, shift)) return;

            // Editing keys.
            if (key == Glfw.GLFW_KEY_BACKSPACE && TextInputController.onBackspace(ctrl)) return;
            if (key == Glfw.GLFW_KEY_DELETE    && TextInputController.onDelete(ctrl))    return;
            if (key == Glfw.GLFW_KEY_ENTER     && TextInputController.onEnter())         return;
            // Tab in the editor is an indentation macro (spaces, not a tab char — a tab
            // doesn't render in the mono atlas anyway); see handleEditorTab. Elsewhere it
            // falls through to the framework's plain tab insert / focus cycling.
            if (key == Glfw.GLFW_KEY_TAB && FocusState.focused() == codeText) { handleEditorTab(); return; }
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
            // GLFW reports the cursor in window (screen-point) coordinates, but the whole
            // UI is laid out and hit-tested in FRAMEBUFFER PIXELS (see the render loop's
            // fbW/fbH viewport). On a HiDPI / Retina display those two spaces differ by the
            // window's backing scale, so an unconverted cursor lands hover/click at the
            // wrong place (and over the wrong widgets). Convert here — this is the single
            // entry point for cursor coordinates; every other handler reads the stored
            // InputState.mouseX()/mouseY(), so scaling once keeps them all consistent.
            //
            // The factor is framebuffer/window size, NOT contentScale: on macOS they match
            // (2.0 on Retina), but on Windows contentScale is a DPI hint while the
            // framebuffer already equals the window in pixels, i.e. the factor is 1.
            int[] wsz = window.size();
            double sx = wsz[0] > 0 ? x * (double) window.framebufferWidth()  / wsz[0] : x;
            double sy = wsz[1] > 0 ? y * (double) window.framebufferHeight() / wsz[1] : y;

            InputState.updateMousePos(sx, sy);
            ScrollbarController.onCursorMove(sx, sy);
            if (ScrollbarController.isDragging()) return;

            LayoutResult lr = LatestLayout.result();
            Component layoutRoot = LatestLayout.root();
            if (lr == null || layoutRoot == null) return;

            // A modal overlay captures hit-testing exclusively; a non-modal one
            // (the Find bar) only where the pointer is over it, so hover on the
            // editor beneath keeps working while the bar floats above.
            Component hitRoot = OverlayStack.inputRootAt(layoutRoot, lr, (float) sx, (float) sy);
            Component hit = HitTest.test(hitRoot, lr, (float) sx, (float) sy);
            HoverState.update(hit);
            // Ctrl-hover: underline the editor identifier under the mouse and show
            // a hand cursor — the "this is a link" affordance (see drawLinkUnderline).
            updateLinkHover();
            cursors.setShape(linkStart >= 0 ? CursorManager.CursorShape.HAND : cursorShapeFor(hit));

            TextInputController.onCursorMove(hit, sx, sy);
            TabsController.onCursorMove(sx, sy);
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
                // component tree. A press OUTSIDE the topmost overlay dismisses a
                // modal one; for a non-modal overlay (the Find bar) it instead
                // falls through to the main UI below, so the user can click and
                // select in the editor while the bar stays open.
                if (OverlayStack.isActive()) {
                    LayoutResult lr = LatestLayout.result();
                    boolean outside = OverlayStack.isOutsideTopmost(lr,
                            (float) InputState.mouseX(), (float) InputState.mouseY());
                    if (outside) {
                        if (OverlayStack.anyModal()) {
                            OverlayStack.pop();
                            pressTarget = null;
                            return;
                        }
                        // Non-modal, pressed outside: fall through to the normal
                        // dispatch path below against the main UI.
                    } else {
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
                }
                // Tab cells aren't components (TabsController synthesizes their
                // geometry at render time) — same treatment as scrollbar thumbs.
                // After the overlay block, so a modal still captures the press.
                if (TabsController.onMouseDown(InputState.mouseX(), InputState.mouseY())) {
                    pressTarget = null;
                    return;
                }
                // Ctrl+click on a code-view identifier (editor or the read-only
                // Definition view) navigates to its definition or, in the editor,
                // adds the requires when the name isn't in scope — instead of moving
                // the caret (the same action Ctrl+Enter runs from the caret).
                boolean ctrl = (mods & Glfw.GLFW_MOD_CONTROL) != 0;
                Component.Text hovView = navigableView(HoverState.hovered());
                if (ctrl && hovView != null && handleNavigateOrImportUnderMouse(hovView)) {
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
                Component dispatchRoot = OverlayStack.inputRootAt(LatestLayout.root(), lr2,
                    (float) InputState.mouseX(), (float) InputState.mouseY());
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
                linkView = null;
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
            Component layoutRoot = OverlayStack.inputRootAt(LatestLayout.root(), lr,
                (float) InputState.mouseX(), (float) InputState.mouseY());
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

    // --- Editor indentation macros (Tab, Ctrl+D) ---
    // Tab and Ctrl+D are handled here rather than by TextInputController so the
    // editor gets space-based, grid-aligned indentation instead of a literal tab
    // char (which the mono atlas can't even render). All three mutate the buffer
    // through TextStates.setContent — the same path the app's other programmatic
    // edits use (see addRequires) — then reposition the caret/selection.

    /** One indentation level, in spaces. Pontif source indents two spaces per level. */
    private static final int INDENT = 2;

    /**
     * Tab in the editor (IntelliJ-style). With a selection: grow it out to whole lines
     * and prefix {@link #INDENT} spaces to each. With no selection, at the line start or
     * when whitespace-then-text sits to the right of the caret: jump the caret past that
     * whitespace and advance the leading indent to the next {@code INDENT} stop. Anywhere
     * else in a line: insert spaces to the next tab stop. Tab always moves the indent
     * forward by one stop (never a no-op) — one press, one indent.
     */
    private static void handleEditorTab() {
        TextState ts = TextStates.of(codeText);
        String content = TextStates.contentOf(codeText);
        int len = content.length();
        if (ts.hasSelection()) {
            indentSelectedLines(content, clamp(ts.selectionStart(), len), clamp(ts.selectionEnd(), len));
            return;
        }
        int caret = clamp(ts.caretIndex, len);
        int lineStart = content.lastIndexOf('\n', caret - 1) + 1;
        int lineEnd = content.indexOf('\n', caret);
        if (lineEnd < 0) lineEnd = len;

        int afterWs = caret;
        while (afterWs < lineEnd && content.charAt(afterWs) == ' ') afterWs++;
        boolean atLineStart = caret == lineStart;
        boolean wsRightWithText = afterWs > caret && afterWs < lineEnd;

        int insertAt;
        int addCount;
        int newCaret;
        if (atLineStart || wsRightWithText) {
            // Jump to the end of the whitespace to the right, then advance the leading
            // run of spaces to the next INDENT stop (1..INDENT spaces — always advances).
            int jump = afterWs;
            int runStart = jump;
            while (runStart > lineStart && content.charAt(runStart - 1) == ' ') runStart--;
            int spacesBefore = jump - runStart;
            addCount = INDENT - (spacesBefore % INDENT);
            insertAt = jump;
            newCaret = jump + addCount;
        } else {
            int col = caret - lineStart;
            addCount = INDENT - (col % INDENT);
            insertAt = caret;
            newCaret = caret + addCount;
        }

        String updated = content.substring(0, insertAt) + " ".repeat(addCount) + content.substring(insertAt);
        TextStates.setContent(codeText, updated);
        TextState ns = TextStates.of(codeText);
        ns.caretIndex = newCaret;
        ns.selectionAnchor = newCaret;
        Invalidator.invalidate();
    }

    /** Prefix {@link #INDENT} spaces to every line touched by {@code [selStart, selEnd)},
     *  and leave the whole (now-indented) block selected. */
    private static void indentSelectedLines(String content, int selStart, int selEnd) {
        int len = content.length();
        int firstLineStart = content.lastIndexOf('\n', selStart - 1) + 1;
        int blockEnd = blockLineEnd(content, selStart, selEnd, len);
        String before = content.substring(0, firstLineStart);
        String block = content.substring(firstLineStart, blockEnd);
        String after = content.substring(blockEnd);
        String pad = " ".repeat(INDENT);
        String[] lines = block.split("\n", -1);
        StringBuilder sb = new StringBuilder(block.length() + pad.length() * lines.length);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(pad).append(lines[i]);
        }
        TextStates.setContent(codeText, before + sb + after);
        TextState ns = TextStates.of(codeText);
        ns.selectionAnchor = firstLineStart;
        ns.caretIndex = blockEnd + pad.length() * lines.length;
        Invalidator.invalidate();
    }

    /** Ctrl+D — duplicate the whole lines spanned by the selection (or the caret's
     *  line when there's none), inserting the copy directly below, and select the copy. */
    private static void duplicateEditorLines() {
        TextState ts = TextStates.of(codeText);
        String content = TextStates.contentOf(codeText);
        int len = content.length();
        int selStart = ts.hasSelection() ? clamp(ts.selectionStart(), len) : clamp(ts.caretIndex, len);
        int selEnd   = ts.hasSelection() ? clamp(ts.selectionEnd(), len)   : selStart;
        int firstLineStart = content.lastIndexOf('\n', selStart - 1) + 1;
        int blockEnd = blockLineEnd(content, selStart, selEnd, len);
        String block = content.substring(firstLineStart, blockEnd);
        String updated = content.substring(0, blockEnd) + "\n" + block + content.substring(blockEnd);
        TextStates.setContent(codeText, updated);
        TextState ns = TextStates.of(codeText);
        int dupStart = blockEnd + 1;               // just past the inserted newline
        ns.selectionAnchor = dupStart;
        ns.caretIndex = dupStart + block.length();
        Invalidator.invalidate();
    }

    /** End offset of the last line touched by {@code [selStart, selEnd)} — the next
     *  newline at/after the last selected char, or the buffer end. */
    private static int blockLineEnd(String content, int selStart, int selEnd, int len) {
        int lastCharPos = selEnd > selStart ? selEnd - 1 : selEnd;
        int nl = content.indexOf('\n', lastCharPos);
        return nl < 0 ? len : nl;
    }

    private static int clamp(int v, int len) {
        return Math.max(0, Math.min(v, len));
    }

    // --- Ctrl+click "go to definition" + Ctrl-hover link underline ---

    /** The code view (Editor or Definition) for a component, or null for anything
     *  else — the two panes that share the go-to-definition machinery. */
    private static Component.Text navigableView(Component c) {
        return (c == codeText || c == definitionText) ? (Component.Text) c : null;
    }

    /** The code view backing the active tab (Editor or Definition), or null. Used as
     *  the Ctrl+Enter target when focus isn't itself on a code view. */
    private static Component.Text activeCodeView() {
        Integer t = activeTab == null ? null : activeTab.get();
        if (t == null) return null;
        if (t == DEFINITION_TAB) return definitionText;
        if (t == EDITOR_TAB) return codeText;
        return null;
    }

    /** The scroll pane wrapping a code view, so its Ctrl-hover underline clips correctly. */
    private static Component.Scroll scrollFor(Component.Text view) {
        if (view == definitionText) return definitionScroll;
        if (view == codeText) return codeScroll;
        return null;
    }

    /** Run the navigate-or-import action on the identifier under the mouse in {@code view}.
     *  Returns false when there's no identifier there, so the click falls through to
     *  ordinary caret placement. */
    private static boolean handleNavigateOrImportUnderMouse(Component.Text view) {
        int[] w = identBoundsUnderMouse(view);
        if (w == null) return false;
        navigateFrom(view, TextStates.contentOf(view).substring(w[0], w[1]));
        return true;
    }

    /** Run the navigate-or-import action on the identifier at {@code view}'s caret (Ctrl+Enter). */
    private static void handleNavigateOrImportAtCaret(Component.Text view) {
        int[] w = caretIdentBounds(view);
        if (w == null) {
            Status.notify("Put the caret on a name to navigate to it or add its requires.");
            return;
        }
        navigateFrom(view, TextStates.contentOf(view).substring(w[0], w[1]));
    }

    /**
     * The unified action behind Ctrl+click and Ctrl+Enter, resolved against
     * {@code source}'s own text (so you can keep following symbols from within the
     * Definition view, not just the editor):
     * <ul>
     *   <li>in scope (declared there or already imported) → open its definition;</li>
     *   <li>not in scope but exported by a module → add/merge its {@code requires}
     *       (a chooser when more than one module exports the name) — editor only,
     *       since the Definition view is read-only;</li>
     *   <li>not in scope, not importable, but defined somewhere reachable → open it;</li>
     *   <li>primitive / unknown → a status message.</li>
     * </ul>
     */
    private static void navigateFrom(Component.Text source, String name) {
        if (DefinitionNavigator.isPrimitive(name)) {
            Status.notify("'" + name + "' is a builtin primitive — no source or import.");
            return;
        }
        String content = TextStates.contentOf(source);
        // A token defined in the editor buffer jumps to it in the editor itself, rather
        // than opening a read-only copy in the Definition view. (Only for the editor —
        // navigating within the Definition view stays in that view.)
        if (source == codeText) {
            Optional<int[]> local = DefinitionNavigator.localDeclaration(content, name);
            if (local.isPresent()) {
                jumpInEditor(local.get()[0], local.get()[1], name);
                return;
            }
        }
        if (DefinitionNavigator.inScope(content, name)) {
            openDefinition(content, name);
            return;
        }
        // Imports mutate the buffer, so only the editor may add a requires; the
        // read-only Definition view can only follow to what it can already resolve.
        boolean editor = source == codeText;
        java.util.List<String> exporters = editor
                ? DefinitionNavigator.exporters(content, name, resolveDir())
                : java.util.List.of();
        if (exporters.isEmpty()) {
            if (DefinitionNavigator.resolve(content, name, resolveDir()).isPresent()) {
                openDefinition(content, name);   // resolvable but not importable — just show it
            } else if (editor) {
                Status.bad("No definition or exporting module found for '" + name + "'.");
            } else {
                Status.bad("No definition for '" + name + "' reachable from this view.");
            }
        } else if (exporters.size() == 1) {
            addRequires(exporters.get(0), name);
        } else {
            openImportChoice(name, exporters);
        }
    }

    /** Identifier bounds {@code [start, end)} at {@code view}'s caret, or null when the
     *  caret isn't on (or just past) an identifier. A caret resting at a word's end counts. */
    private static int[] caretIdentBounds(Component.Text view) {
        if (view == null) return null;
        String content = TextStates.contentOf(view);
        if (content.isEmpty()) return null;
        int caret = Math.max(0, Math.min(TextStates.of(view).caretIndex, content.length()));
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
            Status.notify(edit.message());
            return;
        }
        TextStates.setContent(codeText, edit.text());
        TextState ts = TextStates.of(codeText);
        int newCaret = caret >= edit.editOffset()
                ? Math.min(edit.text().length(), caret + edit.delta())
                : caret;
        ts.caretIndex = newCaret;
        ts.selectionAnchor = newCaret;
        Status.good(edit.message() + " — Ctrl+click/Ctrl+Enter again to open it.");
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

    /** Identifier bounds {@code [start, end)} under the mouse in {@code view}, or
     *  null when the cursor isn't over an identifier (or the view isn't laid
     *  out — e.g. another tab is showing). */
    private static int[] identBoundsUnderMouse(Component.Text view) {
        if (view == null) return null;
        LayoutResult lr = LatestLayout.result();
        PixelRect rect = lr == null ? null : lr.rectOf(view);
        if (rect == null) return null;
        String content = TextStates.contentOf(view);
        int idx = TextGeometry.charIndexAt(view, content, rect,
                (float) InputState.mouseX(), (float) InputState.mouseY());
        int[] w = WordBoundary.wordBoundsAt(content, idx);
        if (w == null || w[1] <= w[0]) return null;
        return isIdentifier(content.substring(w[0], w[1])) ? w : null;
    }

    /** Jump to a declaration <em>inside the editor buffer</em>: activate the Editor tab,
     *  select the name at {@code [start, end)}, and scroll it into view — go-to-definition
     *  for a locally-defined token, no read-only Definition view needed. */
    private static void jumpInEditor(int start, int end, String name) {
        if (activeTab != null) activeTab.set(EDITOR_TAB);
        FocusState.set(codeText);
        TextState ts = TextStates.of(codeText);
        int len = TextStates.contentOf(codeText).length();
        ts.selectionAnchor = Math.max(0, Math.min(start, len));
        ts.caretIndex = Math.max(0, Math.min(end, len));
        ts.hoverCaretIndex = -1;
        scrollEditorPending = true;   // scroll once the caret's line has a layout rect
        Status.good("Jumped to the definition of '" + name + "' in this file.");
        Invalidator.invalidate();
    }

    /** Resolve {@code name} against the editor buffer and show it in the Definition tab.
     *  The entry point for editor-driven navigation (Ctrl+click in the editor, the
     *  module explorer). */
    private static void openDefinition(String name) {
        openDefinition(TextStates.contentOf(codeText), name);
    }

    /** Look up {@code name}'s definition — resolved against {@code fromContent}'s scope —
     *  and show it in the read-only Definition tab: name highlighted, scrolled into view.
     *  {@code fromContent} is the editor buffer for editor navigation, or the currently
     *  shown definition source when following symbols from within the Definition view.
     *  Primitives and misses flash an explanatory status instead of switching tabs. */
    private static void openDefinition(String fromContent, String name) {
        if (DefinitionNavigator.isPrimitive(name)) {
            Status.notify("'" + name + "' is a builtin primitive — no source to open.");
            return;
        }
        Optional<DefinitionNavigator.Target> found = DefinitionNavigator.resolve(
                fromContent, name, resolveDir());
        if (found.isEmpty()) {
            Status.bad("No definition found for '" + name + "'.");
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
        Status.good("Definition of '" + name + "' in " + def.moduleLabel()
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

    /** Recompute the Ctrl-hover underline target: the identifier under the mouse in a
     *  code view (editor or Definition) while Ctrl is held and that view is the hovered
     *  pane. Cheap enough to run on every cursor move and Ctrl press/release. */
    private static void updateLinkHover() {
        boolean ctrl = window != null
                && (Glfw.glfwGetKey(window.handle(), Glfw.GLFW_KEY_LEFT_CONTROL) == Glfw.GLFW_PRESS
                 || Glfw.glfwGetKey(window.handle(), Glfw.GLFW_KEY_RIGHT_CONTROL) == Glfw.GLFW_PRESS);
        Component.Text view = (ctrl && !OverlayStack.isActive())
                ? navigableView(HoverState.hovered()) : null;
        int[] w = view != null ? identBoundsUnderMouse(view) : null;
        // Don't underline primitives — they aren't navigable.
        if (w != null && DefinitionNavigator.isPrimitive(
                TextStates.contentOf(view).substring(w[0], w[1]))) {
            w = null;
        }
        int ns = w == null ? -1 : w[0];
        int ne = w == null ? -1 : w[1];
        Component.Text nv = w == null ? null : view;
        if (ns != linkStart || ne != linkEnd || nv != linkView) {
            linkStart = ns;
            linkEnd = ne;
            linkView = nv;
            Invalidator.invalidate();
        }
    }

    /** Draw the Ctrl-hover underline beneath the hovered identifier, clipped to its code
     *  view's viewport. No-op unless a word is underlined and its view is showing (an
     *  inactive tab's text has no layout rect). */
    private static void drawLinkUnderline(LayoutResult layout, Batcher batcher) {
        if (linkStart < 0 || linkEnd <= linkStart || linkView == null) return;
        Component.Text view = linkView;
        PixelRect tr = layout.rectOf(view);
        if (tr == null) return;   // view's tab not active
        String content = TextStates.contentOf(view);
        if (linkEnd > content.length()) return;
        PixelRect a = TextGeometry.caretBounds(view, content, tr, linkStart);
        PixelRect b = TextGeometry.caretBounds(view, content, tr, linkEnd);
        float x = a.x();
        float right = b.x();
        if (right <= x) return;   // word wrapped across visual lines — skip
        float y = a.bottom() - 2f;
        Component.Scroll sc = scrollFor(view);
        PixelRect vp = sc == null ? null : layout.rectOf(sc);
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
        settledVersion = version;             // buffer is stable at this version — hints may recompute
        if (result instanceof PontifCompiler.CompileResult.Failed failed) {
            errorMarks = computeMarks(content, sourceName, failed.error());
        } else {
            errorMarks = List.of();
            autosaveClean(content, file, version);
        }
        Invalidator.invalidate();
    }

    /** Translate a failed compile into editor underlines. Precedence:
     *  <ol>
     *    <li>an error whose origin points into <b>this buffer</b> underlines the
     *        offending token — even a "Link error" for an unresolved name used here
     *        (its origin is the in-buffer use site, not an import problem);</li>
     *    <li>otherwise a link error, or an origin from another module's source, maps
     *        to the offending {@code requires} statement;</li>
     *    <li>otherwise no mark.</li>
     *  </ol>
     *  Package-visible for {@code LiveCompileMarksTest}. */
    static List<ErrorMark> computeMarks(String content, String sourceName,
                                        PontifRunner.RunResult error) {
        String message = error.text();
        Optional<Origin> originOpt = error.origin();
        // (1) A precise in-buffer origin wins over the "Link error" prefix: an
        // unresolved name used here is anchored at its use site, not the imports.
        if (originOpt.isPresent() && originOpt.get().isPresent()
                && sourceName.equals(originOpt.get().source())) {
            int[] span = spanOffsets(content, originOpt.get());
            if (span != null) {
                span = callNameSpan(content, span);   // pull left onto the callee name
                return List.of(new ErrorMark(span[0], span[1], message, false));
            }
        }
        // (2) A genuine import/link problem in another module (or an origin-less link
        // error): flag the requires area so the import site is surfaced.
        if (message.startsWith("Link error")
                || (originOpt.isPresent() && originOpt.get().isPresent()
                    && !sourceName.equals(originOpt.get().source()))) {
            return requiresMarks(content, message, originOpt.orElse(null));
        }
        return List.of();
    }

    /** The compiler anchors a call error at the application ({@code (args)}), so the
     *  offending name sits immediately to its left. When {@code [start, end)} begins
     *  right after an identifier, return that identifier's span instead — the name the
     *  user actually needs to see underlined. Otherwise the span is returned unchanged. */
    private static int[] callNameSpan(String content, int[] span) {
        int start = span[0];
        if (start <= 0 || !isIdentChar(content.charAt(start - 1))) return span;
        int nameStart = start;
        while (nameStart > 0 && isIdentChar(content.charAt(nameStart - 1))) nameStart--;
        return new int[]{nameStart, start};
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
            Status.bad("Autosave failed for " + file.getFileName() + ": " + e.getMessage());
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
        // Surface the nearest caret error (plus its Auto-Action hint when the token offers
        // one: un-imported → require; resolvable-but-misused → go to definition) as the
        // status bar's CONTEXTUAL OVERRIDE — shown only while the caret sits on an error,
        // and cleared otherwise, reverting the bar to its own ledger counter. Runs each
        // frame; republishes only when the shown text changes.
        String full = null;
        if (near != null) {
            String hint = caretHint();
            full = hint != null ? near + "   " + hint : near;
        }
        if (!java.util.Objects.equals(full, shownContextual)) {
            shownContextual = full;
            if (full != null) Status.setContextualMessage(full, Severity.BAD);
            else Status.clearContextualMessage();
        }
    }

    /**
     * A contextual hint for the identifier at the editor caret (Slice A), or null when
     * none applies — the Auto-Action (Ctrl+click / Ctrl+Enter) affordance, appended to a
     * compile error on that token (its two triggers are both error conditions):
     * <ul>
     *   <li>a name not in scope but <b>exported by some module</b> → auto-require it
     *       (e.g. an un-imported name);</li>
     *   <li>any other <b>resolvable, non-primitive</b> name → go to its definition
     *       (e.g. a builtin called with the wrong arguments).</li>
     * </ul>
     * Cached by (token, {@code settledVersion}) and only computed while the buffer is
     * settled: the {@code inScope}/{@code exporters} lookups parse source and must not
     * run every frame or mid-keystroke. Only meaningful when the editor is focused.
     */
    private static String caretHint() {
        if (codeText == null || FocusState.focused() != codeText) return null;
        // Only while the buffer is settled (debounced compile has caught up) — so the
        // parse-backed lookups never run mid-keystroke.
        if (editVersion != settledVersion) return null;
        int[] w = caretIdentBounds(codeText);
        if (w == null) return null;
        String content = TextStates.contentOf(codeText);
        String name = content.substring(w[0], w[1]);
        if (name.equals(hintName) && settledVersion == hintVersion) return hintResult;
        hintName = name;
        hintVersion = settledVersion;
        hintResult = computeHint(content, name);
        return hintResult;
    }

    /** Resolve the hint for {@code name} (the parse-backed body of {@link #caretHint}). */
    private static String computeHint(String content, String name) {
        if (DefinitionNavigator.isPrimitive(name)) return null;
        if (!DefinitionNavigator.inScope(content, name)) {
            return DefinitionNavigator.exporters(content, name, resolveDir()).isEmpty()
                    ? null   // an unknown name we can't auto-require — no hint to offer
                    : "Hint: Ctrl+click '" + name + "' to auto-require it.";
        }
        return "Hint: Ctrl+click '" + name + "' to go to its definition.";
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
