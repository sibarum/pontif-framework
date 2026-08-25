package sibarum.pontif.ast.binary;

import sibarum.pontif.core.PontifNode;

import java.math.BigDecimal;

public final class Cmp extends BinaryOp {

    public enum Op { LT, LE, GT, GE, EQ, NE, APPROX }

    private final Op op;

    private Cmp(PontifNode left, PontifNode right, Op op) {
        super(left, right);
        this.op = op;
    }

    public static Cmp of(PontifNode left, PontifNode right, Op op) {
        return new Cmp(left, right, op);
    }

    public Op op() {
        return op;
    }

    /**
     * ORDERING routes to a user overload on struct operands; equality does not. That split is
     * ruled in two places already — {@code IrInterpreter.dispatchOperatorSymbol} and
     * docs/keyed.md — and it is why an aggregate {@code ==} is structural while an aggregate
     * {@code <} is a compile error unless the author defines one.
     */
    @Override
    protected String operatorSymbol() {
        return switch (op) {
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case EQ, NE, APPROX -> null;
        };
    }

    @Override
    protected boolean acceptsChar() {
        return true;
    }

    @Override
    protected boolean acceptsString() {
        return true;
    }

    @Override
    protected Object combine(Object leftValue, Object rightValue) {
        // Char compares only with Char, by code point — no Char/Int tower.
        if (leftValue instanceof sibarum.pontif.core.types.CharValue
                || rightValue instanceof sibarum.pontif.core.types.CharValue) {
            if (!(leftValue instanceof sibarum.pontif.core.types.CharValue lc)
                    || !(rightValue instanceof sibarum.pontif.core.types.CharValue rc)) {
                throw new sibarum.pontif.core.symbolic.RuntimeCheckException(
                        "Char compares only with Char — got " + leftValue
                                + " " + op.name() + " " + rightValue, origin());
            }
            int c = Integer.compare(lc.codePoint(), rc.codePoint());
            return switch (op) {
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
                case EQ -> c == 0;
                case NE -> c != 0;
                case APPROX -> c == 0;  // code points are exact → ~= is ==
            };
        }
        // String compares only with String, lexicographically by code point —
        // no String/Char and no String/Int tower.
        if (leftValue instanceof sibarum.pontif.core.types.StringValue
                || rightValue instanceof sibarum.pontif.core.types.StringValue) {
            if (!(leftValue instanceof sibarum.pontif.core.types.StringValue ls)
                    || !(rightValue instanceof sibarum.pontif.core.types.StringValue rs)) {
                throw new sibarum.pontif.core.symbolic.RuntimeCheckException(
                        "String compares only with String — got " + leftValue
                                + " " + op.name() + " " + rightValue, origin());
            }
            int c = compareByCodePoint(ls.content(), rs.content());
            return switch (op) {
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
                case EQ -> c == 0;
                case NE -> c != 0;
                case APPROX -> c == 0;  // code points are exact → ~= is ==
            };
        }
        if (leftValue instanceof BigDecimal || rightValue instanceof BigDecimal) {
            BigDecimal a = asDecimal(leftValue, op.name());
            BigDecimal b = asDecimal(rightValue, op.name());
            int c = a.compareTo(b);
            return switch (op) {
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
                case EQ -> c == 0;
                case NE -> c != 0;
                // Equal within one ulp at the working precision (see Decimals).
                case APPROX -> sibarum.pontif.core.Decimals.approxEqual(a, b);
            };
        }
        // Equality is BUILT-IN and STRUCTURAL for every remaining operand kind — Int,
        // Bool, and any aggregate (struct, tuple, dictionary, anonymous record shape,
        // enum case), all of which arrive here as a value whose `equals` is already the
        // structural one (`RecordValue` is a record). This is the ruled split, stated in
        // `IrInterpreter.dispatchOperatorSymbol`: arithmetic and ORDERING route to a user
        // overload, `==`/`!=`/`~=` never do. Without this branch an aggregate operand fell
        // into the `(Long)` cast below and died with an internal ClassCastException —
        // Truffle disagreeing with the interpreter, which has always evaluated these three
        // ops as `Objects.equals` (IrInterpreter.java, the tail of `evalBinOp`).
        if (op == Op.EQ || op == Op.NE || op == Op.APPROX) {
            // No rounding is in play for these operand kinds, so ~= coincides with ==.
            boolean equal = java.util.Objects.equals(leftValue, rightValue);
            return op == Op.NE ? !equal : equal;
        }
        // Ordering stays Int-only. An aggregate never reaches here through a checked
        // compile — `P(1) < P(2)` is rejected with "Operator '<' is not defined for
        // (P, P)" — so the cast below is a backstop for hand-built nodes, not the guard.
        long l = (Long) leftValue;
        long r = (Long) rightValue;
        return switch (op) {
            case LT -> l < r;
            case LE -> l <= r;
            case GT -> l > r;
            case GE -> l >= r;
            case EQ, NE, APPROX -> throw new IllegalStateException(
                    "equality is handled structurally above");
        };
    }

    /**
     * Lexicographic comparison by Unicode code point — not {@link
     * String#compareTo}, which orders by UTF-16 char and so misranks astral
     * code points relative to the BMP. Consistent with Char's code-point
     * ordering: a String is a sequence of Chars, ordered the same way.
     */
    private static int compareByCodePoint(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        // Shorter is less when it is a prefix of the longer.
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
