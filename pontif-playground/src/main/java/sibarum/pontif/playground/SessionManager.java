package sibarum.pontif.playground;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the playground's background persistence: the debounced recovery
 * autosave and the regular session-file write. {@link App} drives it through
 * a handful of event hooks and never touches the timer thread or the on-disk
 * format directly.
 *
 * <h2>Threading</h2>
 * A single daemon timer ({@link #tick}) does all disk writes off the GLFW
 * thread. It never reads GUI state directly — instead {@link App} pushes
 * immutable snapshots in from the main thread:
 * <ul>
 *   <li>{@link #onContentChanged} — latest editor text (a {@code String}, safely published);</li>
 *   <li>{@link #onDocumentChanged}/{@link #onSaved} — the current document key + on-disk baseline;</li>
 *   <li>{@link #updateGeometry} — the window rect, sampled each frame.</li>
 * </ul>
 * Recovery write and delete are mutually exclusive under {@link #lock} so a
 * save can't race the timer into re-creating a copy it just removed.
 */
final class SessionManager {

    private static final long TICK_SECONDS = 2L;

    private final boolean enabled;
    private final RecoveryStore recoveryStore;
    private final ScheduledExecutorService scheduler;

    // --- snapshots pushed from the GLFW main thread ---
    private volatile String editorContent = null;   // latest editor text
    private volatile String openFilePath = null;     // absolute path open now, or null
    private volatile int winX = SessionState.UNSET;
    private volatile int winY = SessionState.UNSET;
    private volatile int winWidth = SessionState.UNSET;
    private volatile int winHeight = SessionState.UNSET;
    private volatile boolean winMaximized = false;
    private volatile boolean openMostRecent = false;  // "Always open most recent file" setting

    // --- guarded by lock: recovery bookkeeping ---
    private final Object lock = new Object();
    private String currentKey = null;       // document key being autosaved
    private String diskBaseline = null;      // content as last loaded/saved (== editor ⇒ no unsaved work)
    private String lastRecoveryContent = null;

    // --- timer-thread only: skip redundant session writes ---
    private String lastSessionFingerprint = null;

    SessionManager(boolean enabled) {
        this.enabled = enabled;
        this.recoveryStore = new RecoveryStore(AppPaths.recoveryDir());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pontif-session-autosave");
            t.setDaemon(true);
            return t;
        });
    }

    // ---------- lifecycle ----------

    void start() {
        if (!enabled) return;
        scheduler.scheduleAtFixedRate(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /** Final synchronous flush (catches edits made within the last tick interval), then stop. */
    void flushAndStop() {
        if (!enabled) return;
        tick();
        scheduler.shutdownNow();
    }

    // ---------- event hooks (GLFW main thread) ----------

    /** Latest editor text — called on every content change. */
    void onContentChanged(String content) {
        this.editorContent = content;
    }

    /**
     * The active document changed without a disk write (open / new): adopt its
     * key and treat {@code baseline} as already-persisted, so no recovery is
     * written until the user edits. Does <em>not</em> delete any existing
     * recovery for the key — a copy left by a prior crash must survive to be
     * offered.
     */
    void onDocumentChanged(String key, String baseline, String openFilePath) {
        this.openFilePath = openFilePath;
        synchronized (lock) {
            this.currentKey = key;
            this.diskBaseline = baseline;
            this.lastRecoveryContent = null;
        }
    }

    /**
     * The document was saved to disk: drop its recovery copy (and the previous
     * key's, in case this was a Save As) and re-baseline so the autosave stays
     * quiet until the next edit.
     */
    void onSaved(String prevKey, String newKey, String content, String openFilePath) {
        this.openFilePath = openFilePath;
        synchronized (lock) {
            this.currentKey = newKey;
            this.diskBaseline = content;
            this.lastRecoveryContent = null;
            recoveryStore.delete(prevKey);
            if (!newKey.equals(prevKey)) recoveryStore.delete(newKey);
        }
    }

    /** Record the "Always open most recent file" setting so it round-trips through the session file. */
    void setOpenMostRecent(boolean value) {
        this.openMostRecent = value;
    }

    /** Sample the window rect (screen coordinates). Maximized geometry is not
     *  recorded as the floating size, so un-maximizing restores the prior box. */
    void updateGeometry(int x, int y, int width, int height, boolean maximized) {
        this.winMaximized = maximized;
        if (!maximized) {
            this.winX = x;
            this.winY = y;
            this.winWidth = width;
            this.winHeight = height;
        }
    }

    // ---------- queries used by the System menu (main thread) ----------

    boolean hasRecovery(String key) {
        return enabled && recoveryStore.hasRecovery(key);
    }

    Optional<String> recover(String key) {
        return enabled ? recoveryStore.read(key) : Optional.empty();
    }

    int purgeAll() {
        return enabled ? recoveryStore.purgeAll() : 0;
    }

    // ---------- timer ----------

    private void tick() {
        try {
            writeRecoveryIfUnsaved();
            writeSessionIfChanged();
        } catch (Throwable t) {
            // Never let the autosave thread die on a transient failure.
        }
    }

    private void writeRecoveryIfUnsaved() {
        String content = editorContent;
        if (content == null) return;
        synchronized (lock) {
            if (currentKey == null) return;
            boolean unsaved = !content.equals(diskBaseline);
            if (unsaved && !content.equals(lastRecoveryContent)) {
                recoveryStore.write(currentKey, content);
                lastRecoveryContent = content;
            }
        }
    }

    private void writeSessionIfChanged() {
        SessionState s = new SessionState();
        s.openFile = openFilePath;
        s.windowX = winX;
        s.windowY = winY;
        s.windowWidth = winWidth;
        s.windowHeight = winHeight;
        s.maximized = winMaximized;
        s.openMostRecent = openMostRecent;
        String fingerprint = s.fingerprint();
        if (fingerprint.equals(lastSessionFingerprint)) return;
        s.write(AppPaths.sessionFile());
        lastSessionFingerprint = fingerprint;
    }
}
