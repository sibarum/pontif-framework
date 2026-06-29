package sibarum.pontif.ir;

import sibarum.pontif.ast.record.RecordValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The native <b>call</b> registry — application-invoked natives, keyed by the resolved
 * function name. The generalization of the retired {@code NativeSources}: any native function
 * (0..n args, returning a value) registers here, and {@link IrInterpreter}'s resolved-call path
 * invokes it with the evaluated arguments instead of running the Pontif declaration's
 * placeholder body. Covers {@code stdin} (0-arg → a {@link LiveSource}) and the GUI window
 * function (args → opens a window, blocks, returns) alike.
 *
 * <p><b>Populated by extensions</b> (docs/extensions.md): the Pontif-side interface declares the
 * function with a placeholder body (e.g. {@code function stdin():Stream[String] -> {}}); the
 * extension's Java object is bound here by name. Each call is registered under both its bare
 * name and its module-qualified form, and {@link #get} also tries the bare suffix of a
 * qualified resolved name — so a resolved decl name in either form finds its impl.
 */
public final class NativeCalls {

    /**
     * One native call: run the Java implementation against the evaluated arguments. The
     * {@link Context} lets a long-running native (the GUI {@code window} loop) re-enter the
     * substrate — firing a Pontif event back through its {@code action}s, e.g. on a click.
     * Most calls ignore it.
     */
    @FunctionalInterface
    public interface NativeCall {
        Object call(List<Object> args, Context ctx);
    }

    /**
     * The interpreter handle a native call may use to re-enter the runtime — the seam for GUI
     * interactivity (docs/extensions.md). A long-running native (the GUI {@code window} loop) uses
     * it to drive program logic from outside the normal call flow:
     * <ul>
     *   <li>{@link #fireEvent} — fire a Pontif event through the event substrate (run its matching
     *       {@code action}s + native sink);</li>
     *   <li>{@link #satisfies} — test whether a value's type satisfies a trait (e.g. is this
     *       element {@code Clickable}?);</li>
     *   <li>{@link #invoke} — invoke a 0-user-arg instance method on a value (e.g. a widget's
     *       {@code onClick}), dispatching {@code <type>.<method>(this)}.</li>
     * </ul>
     * Backed by {@code IrInterpreter}.
     */
    public interface Context {
        void fireEvent(RecordValue event);

        boolean satisfies(RecordValue value, String traitName);

        Object invoke(RecordValue value, String methodName);
    }

    private static final Map<String, NativeCall> ENTRIES = new LinkedHashMap<>();

    private NativeCalls() {}

    /** Binds {@code call} to {@code name} (extension install registers bare + qualified forms). */
    public static void register(String name, NativeCall call) {
        ENTRIES.put(name, call);
    }

    /** The native call for a resolved name, or null — exact match, else the bare suffix. */
    public static NativeCall get(String name) {
        if (name == null) return null;
        NativeCall c = ENTRIES.get(name);
        if (c != null) return c;
        int slash = name.lastIndexOf('/');
        return slash < 0 ? null : ENTRIES.get(name.substring(slash + 1));
    }
}
