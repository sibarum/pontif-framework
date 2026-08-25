package sibarum.pontif.ast.func;

import com.oracle.truffle.api.CallTarget;

import sibarum.pontif.core.symbolic.DispatchResult;
import sibarum.pontif.core.symbolic.DispatchTable;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Metaref;
import sibarum.pontif.core.types.RecordValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime dispatch for Truffle nodes that are not calls — the (table, simplifier, registry)
 * trio {@link CallNode} carries, in a form a field access or a binary operator can also hold.
 *
 * <p>Some questions cannot be answered when a node is built. A field access whose base is a
 * struct viewed through a trait may name an attribute no stored record carries, computed by a
 * producer resolved on the value's type; an operator over a trait-bounded type variable
 * (<code>function sum[type E:Numeric](a:E, b:E):E -&gt; a + b</code>) has no operand sort to
 * route on until the argument arrives. The interpreter has always answered both at runtime.
 * The Truffle nodes had no way to ask, so they failed — a {@code ClassCastException} for the
 * operator, "Record has no field" for the attribute — while the interpreter returned a value.
 *
 * <p>Both engines run the SAME {@link DispatchTable}, so this is not a second dispatcher: it is
 * the existing one, reachable from the two node kinds that needed it. {@link #tryInvoke} answers
 * {@link #NO_MATCH} rather than throwing, so each caller keeps its own fail-closed message.
 */
public final class RuntimeDispatch {

    /** Returned by {@link #tryInvoke} when no overload of that name accepts these arguments. */
    public static final Object NO_MATCH = new Object();

    private final DispatchTable dispatch;
    private final Simplifier simplifier;
    private final FunctionRegistry registry;

    private RuntimeDispatch(DispatchTable dispatch, Simplifier simplifier, FunctionRegistry registry) {
        this.dispatch = dispatch;
        this.simplifier = simplifier;
        this.registry = registry;
    }

    public static RuntimeDispatch of(
            DispatchTable dispatch, Simplifier simplifier, FunctionRegistry registry) {
        if (dispatch == null || simplifier == null || registry == null) {
            throw new IllegalArgumentException("RuntimeDispatch needs a table, a simplifier and a registry");
        }
        return new RuntimeDispatch(dispatch, simplifier, registry);
    }

    /** Whether any overload is declared under {@code name} — the cheap pre-check. */
    public boolean declares(String name) {
        return !dispatch.declarationsFor(name).isEmpty();
    }

    /**
     * Resolves {@code name} over {@code args} and calls the winner, or {@link #NO_MATCH} when no
     * overload applies. An ambiguity is the dispatcher's error and propagates; a resolved call's
     * own runtime checks run first, exactly as {@link CallNode} runs them.
     */
    public Object tryInvoke(String name, Object[] args) {
        List<SymExpr> argSymbolics = new ArrayList<>(args.length);
        for (Object arg : args) {
            argSymbolics.add(toSymExpr(arg));
        }
        if (!(dispatch.resolve(name, argSymbolics, simplifier) instanceof DispatchResult.Resolved r)) {
            return NO_MATCH;
        }
        r.call().executeChecks(Map.of(), simplifier);
        CallTarget target = registry.callTarget(r.decl());
        if (target == null) {
            throw new IllegalStateException(
                    "Dispatch resolved to '" + r.decl().name()
                            + "' but no CallTarget was registered for it");
        }
        return target.call(args);
    }

    /**
     * A runtime value as the symbolic the dispatch table narrows against. The single copy for the
     * Truffle side — {@link CallNode} routes its own conversion here rather than keeping the one
     * it used to own privately, since a second copy of this table is how two callers come to
     * disagree about what a value's sort is.
     */
    public static SymExpr toSymExpr(Object value) {
        if (value instanceof Long l) return SymExpr.lit(l);
        if (value instanceof Integer i) return SymExpr.lit(i.longValue());
        if (value instanceof Boolean b) return SymExpr.bool(b);
        if (value instanceof java.math.BigDecimal d) return SymExpr.dec(d);
        if (value instanceof sibarum.pontif.core.types.CharValue c) return SymExpr.chr(c.codePoint());
        if (value instanceof sibarum.pontif.core.types.StringValue s) return SymExpr.str(s.content());
        // A metaref record round-trips as a DispatchRef carrying its nominal — caught before the
        // generic RecordValue case so its dispatch identity is preserved.
        if (Metaref.is(value)) {
            return new SymExpr.DispatchRef(
                    Metaref.functionName(value),
                    Metaref.keySorts(value),
                    ((RecordValue) value).typeName());
        }
        if (value instanceof RecordValue r) {
            Map<String, SymExpr> members = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : r.members().entrySet()) {
                members.put(e.getKey(), toSymExpr(e.getValue()));
            }
            return SymExpr.record(r.typeName(), members);
        }
        throw new IllegalArgumentException(
                "Cannot convert runtime value to SymExpr (type "
                        + (value == null ? "null" : value.getClass().getSimpleName()) + "): " + value);
    }
}
