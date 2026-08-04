package sibarum.pontif.ir;

import java.util.Set;

/**
 * The <b>call-kind capability</b> registry (docs/dispatch-method-elimination.md §2). A
 * {@link IrSort.CallSig} sort ({@code Type(Params):Return}) behaves as a <em>function</em>
 * (a lambda's contract — full function subtyping, satisfied by a {@code Lam}) or as a
 * <em>dispatch</em> (a metareference's contract — exact key-sort match, satisfied by a
 * {@code DispatchRef}) depending on which capability its head type is-a. That association
 * is <em>data</em>, never a keyword or an {@code instanceof} on a hardcoded kind:
 *
 * <ul>
 *   <li><b>function-style</b> — {@code Method} (the builtin), plus any user type declared
 *       is-a {@link #FUNCTION_STYLE};</li>
 *   <li><b>dispatch-style</b> — {@code Dispatch}, {@code DispatchBase},
 *       {@code AlgebraicDispatch} (builtins), plus any user type declared is-a
 *       {@link #DISPATCH_STYLE}.</li>
 * </ul>
 *
 * <p>The builtins are recognized as <em>known names</em> the way primitives are
 * ({@code SortChecker.PRIMITIVE_SORT_NAMES}) — name recognition is fine; only name-based
 * <em>logic</em> is the hardcoding this refactor removes. A user type acquires the same
 * capability by declaring it (an {@code assign trait Foo : dispatch-style}), which flows
 * through the trait-impl view {@code AssignabilityContext} exposes — no type-system code
 * change. The ctx-aware lookup lives beside that data in
 * {@code sibarum.pontif.types.Assignability}; this class holds the builtin seed and the
 * capability trait-name constants both layers share.
 */
public final class CallKinds {

    /** The builtin function-style capability trait name. */
    public static final String FUNCTION_STYLE = "function-style";
    /** The builtin dispatch-style capability trait name. */
    public static final String DISPATCH_STYLE = "dispatch-style";

    private static final Set<String> FUNCTION_BUILTINS = Set.of(IrSort.CallSig.METHOD);
    private static final Set<String> DISPATCH_BUILTINS =
            Set.of(IrSort.CallSig.DISPATCH, "DispatchBase", "AlgebraicDispatch");
    private static final Set<String> ACTION_BUILTINS = Set.of(IrSort.CallSig.ACTION);
    private static final Set<String> CONDUIT_BUILTINS = Set.of(IrSort.CallSig.CONDUIT);

    /**
     * The call kinds a {@link IrSort.CallSig} head type can carry. {@code FUNCTION} and
     * {@code DISPATCH} are the value-returning contracts (a lambda's, a metareference's);
     * {@code ACTION} and {@code CONDUIT} are the effectful members (an event reaction with a
     * write-only terminus, and one with a value terminus alongside its effects) — the
     * sort-carried siblings of the {@code action}/{@code conduit} keyword declarations.
     */
    public enum Kind { FUNCTION, DISPATCH, ACTION, CONDUIT }

    private CallKinds() {}

    /**
     * The call kind of {@code typeName} from the <em>builtin</em> seed alone, or {@code null}
     * if no builtin capability applies (a user type's capability is resolved through the
     * ctx-aware lookup, which falls back here for the builtins).
     */
    public static Kind builtin(String typeName) {
        if (typeName == null) return null;
        if (FUNCTION_BUILTINS.contains(typeName)) return Kind.FUNCTION;
        if (DISPATCH_BUILTINS.contains(typeName)) return Kind.DISPATCH;
        if (ACTION_BUILTINS.contains(typeName)) return Kind.ACTION;
        if (CONDUIT_BUILTINS.contains(typeName)) return Kind.CONDUIT;
        return null;
    }

    /** Whether {@code typeName} names a builtin function-style head type. */
    public static boolean isFunctionBuiltin(String typeName) {
        return builtin(typeName) == Kind.FUNCTION;
    }

    /** Whether {@code typeName} names a builtin dispatch-style head type. */
    public static boolean isDispatchBuiltin(String typeName) {
        return builtin(typeName) == Kind.DISPATCH;
    }

    /** Whether {@code typeName} names a builtin effect-reaction ({@code Action}) head type. */
    public static boolean isActionBuiltin(String typeName) {
        return builtin(typeName) == Kind.ACTION;
    }

    /** Whether {@code typeName} names a builtin {@code Conduit} head type. */
    public static boolean isConduitBuiltin(String typeName) {
        return builtin(typeName) == Kind.CONDUIT;
    }
}
