package sibarum.pontif.ast.binary;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import sibarum.pontif.core.types.RecordValue;

import java.math.BigDecimal;
import java.util.List;

public abstract class BinaryOp extends PontifNode {

    @Child private PontifNode left;
    @Child private PontifNode right;

    /** Runtime dispatch for a user operator overload, or null when the node was built without it. */
    private sibarum.pontif.ast.func.RuntimeDispatch dispatch;

    protected BinaryOp(PontifNode left, PontifNode right) {
        this.left = left;
        this.right = right;
    }

    /**
     * Supplies the dispatch table for the aggregate-operand fallback below. Set at lowering time
     * rather than passed through every subclass constructor: ten node kinds and their factories
     * would otherwise each grow the same three parameters for one shared behavior.
     */
    public final <T extends BinaryOp> T withDispatch(sibarum.pontif.ast.func.RuntimeDispatch d) {
        this.dispatch = d;
        @SuppressWarnings("unchecked")
        T self = (T) this;
        return self;
    }

    /**
     * The dispatch symbol this operator routes to when applied to STRUCT operands, or null for
     * one that is never user-overloaded. Mirrors {@code MethodOperatorResolver}'s static routing
     * table and the interpreter's {@code dispatchOperatorSymbol}: arithmetic and ordering route;
     * {@code ==}/{@code !=} stay built-in structural equality, and {@code &}/{@code |} are always
     * primitive Bool ops.
     */
    protected String operatorSymbol() {
        return null;
    }

    /**
     * Whether this node has a BUILT-IN rule for these aggregate operands, and so must not route
     * to a user overload first. Tuple concatenation under {@code +} is the one such rule, and the
     * interpreter's ladder checks it ahead of operator dispatch — mirroring that order here is
     * what keeps the two engines answering the same thing for a program that has both a tuple
     * addition and a user {@code +} in scope.
     */
    protected boolean handlesAggregate(Object leftValue, Object rightValue) {
        return false;
    }

    @Override
    public final Object execute(VirtualFrame frame) {
        Object l = left.execute(frame);
        Object r = right.execute(frame);
        // An operator over a trait-bounded type VARIABLE — `function sum[type E:Numeric](a:E,
        // b:E):E -> a + b` — has no operand sort to route on until the argument arrives, so
        // MethodOperatorResolver cannot resolve it statically and it survives to here as a plain
        // operator node. The interpreter has always finished the job at runtime; this node used
        // to reach `(Long) leftValue` and die with a ClassCastException, so a program that ran on
        // one engine crashed on the other (docs/soundness-holes.md — the engines must agree).
        if ((l instanceof RecordValue || r instanceof RecordValue) && !handlesAggregate(l, r)) {
            Object dispatched = tryOperatorOverload(l, r);
            if (dispatched != sibarum.pontif.ast.func.RuntimeDispatch.NO_MATCH) {
                return dispatched;
            }
        }
        // Chars order and compare; they don't compute. Only comparison nodes
        // opt in — everything else fails closed with a located error instead
        // of a ClassCastException.
        if (!acceptsChar() && (l instanceof sibarum.pontif.core.types.CharValue
                || r instanceof sibarum.pontif.core.types.CharValue)) {
            throw new RuntimeCheckException(
                    "Operator applied to a Char operand — chars order and compare; "
                            + "they don't compute (got " + l + ", " + r + ")", origin());
        }
        // Strings order and compare; they don't compute either (no arithmetic,
        // no indexing — concatenation is the stream `concat` combinator).
        if (!acceptsString() && (l instanceof sibarum.pontif.core.types.StringValue
                || r instanceof sibarum.pontif.core.types.StringValue)) {
            throw new RuntimeCheckException(
                    "Operator applied to a String operand — strings order and compare; "
                            + "they don't compute (got " + l + ", " + r + ")", origin());
        }
        return combine(l, r);
    }

    /**
     * The user overload's result, or {@code NO_MATCH} when this operator does not route, no
     * dispatch is wired, or no overload accepts these operands.
     *
     * <p>The key needs reconstructing: an overload is registered under its DECLARATION name,
     * module-qualified in a linked module ({@code gen.vecmod/+}) and bare in a single file, while
     * a node that survives to runtime carries only the bare symbol. Prefer a key the table
     * actually declares, then one built from an operand's own module — the same reconstruction
     * the interpreter makes, and it goes away with the same association index that retires that
     * one (docs/cross-module-dispatch.md §6 phase 2).
     */
    private Object tryOperatorOverload(Object l, Object r) {
        String symbol = operatorSymbol();
        if (symbol == null || dispatch == null) {
            return sibarum.pontif.ast.func.RuntimeDispatch.NO_MATCH;
        }
        if (dispatch.declares(symbol)) {
            return dispatch.tryInvoke(symbol, new Object[]{l, r});
        }
        for (Object operand : new Object[]{l, r}) {
            if (operand instanceof RecordValue rv && rv.typeName() != null) {
                String module = sibarum.pontif.core.QualifiedName.parse(rv.typeName()).module();
                if (module.isEmpty()) continue;
                String qualified = sibarum.pontif.core.QualifiedName.of(module, symbol).fqn();
                if (dispatch.declares(qualified)) {
                    return dispatch.tryInvoke(qualified, new Object[]{l, r});
                }
            }
        }
        return sibarum.pontif.ast.func.RuntimeDispatch.NO_MATCH;
    }

    /** Whether this node accepts Char operands (comparison nodes only). */
    protected boolean acceptsChar() {
        return false;
    }

    /** Whether this node accepts String operands (comparison nodes only). */
    protected boolean acceptsString() {
        return false;
    }

    @Override
    public final List<PontifNode> children() {
        return List.of(left, right);
    }

    protected abstract Object combine(Object leftValue, Object rightValue);

    /**
     * Coerces an operand of a subclass's decimal branch to BigDecimal. Int
     * promotes — the lossless direction of the embedding ({@code Decimal op
     * Int} is Decimal, matching the static sort); anything else meeting a
     * Decimal is a clear, origin-carrying error rather than a
     * {@code ClassCastException}.
     */
    protected final BigDecimal asDecimal(Object v, String symbol) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Long n) return BigDecimal.valueOf(n);
        if (v instanceof Integer n) return BigDecimal.valueOf(n);
        throw new RuntimeCheckException(
                "Operator '" + symbol + "' applied to " + typeName(v)
                        + " and Decimal operands — only Int promotes to Decimal.",
                origin());
    }

    private static String typeName(Object v) {
        if (v instanceof Long || v instanceof Integer) return "Int";
        if (v instanceof BigDecimal) return "Decimal";
        if (v instanceof Boolean) return "Bool";
        if (v instanceof sibarum.pontif.core.types.CharValue) return "Char";
        if (v instanceof sibarum.pontif.core.types.StringValue) return "String";
        return v == null ? "null" : v.getClass().getSimpleName();
    }
}
