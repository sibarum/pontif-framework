package sibarum.pontif.ir;

/**
 * Methods-only facade over {@link MethodOperatorResolver}: resolves
 * {@link IrExpr.MethodCall} nodes to {@code Call("Type.method", [recv, ...args])}
 * dispatch calls WITHOUT routing operators (binary operators are left in their
 * parse-time shape).
 *
 * <p>Kept as a named entry point for the conservation / receipt <em>report</em>
 * paths (ConservationReport, ReceiptGraphReport, PontifCompiler's pre-resolve),
 * whose ledgers deliberately show the parse-routed operator shape rather than the
 * post-link routing. The full run path ({@link IrCompiler}) calls
 * {@link MethodOperatorResolver#resolve(IrModule)} instead, which does both in one
 * bottom-up walk (so {@code (a+b).m()} and {@code m(a)+m(b)} both resolve).
 */
public final class MethodResolver {

    private MethodResolver() {}

    /** Internal-invariant breach: a {@link IrExpr.MethodCall} survived to a later phase. */
    public static IllegalStateException unresolved(IrExpr.MethodCall mc, String phase) {
        return new IllegalStateException(
                "MethodResolver must eliminate MethodCall before " + phase
                        + " — saw an unresolved '" + mc.methodName() + "' call");
    }

    /** Resolve method calls only; leave operators in their parse-time shape. */
    public static IrModule resolve(IrModule module) throws CompileException {
        return MethodOperatorResolver.resolve(module, true, false);
    }
}
