package sibarum.pontif.ir;

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

    /** One native call: run the Java implementation against the evaluated arguments. */
    @FunctionalInterface
    public interface NativeCall {
        Object call(List<Object> args);
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
