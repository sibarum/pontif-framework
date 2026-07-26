package sibarum.pontif.parser;

import sibarum.pontif.ir.CallKinds;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;

import java.util.List;

/**
 * Pure, stateless queries over already-built IR nodes and tokens.
 *
 * These classify or derive information from IrSort, IrExpr, IrExpr.Op and
 * tokens without consuming any input, so they belong with the syntax model
 * rather than the recursive-descent parser. Keeping them here lets AltParser
 * stay focused on token consumption and lets the queries be tested in
 * isolation.
 */
final class IrQueries {

    private IrQueries() {
    }

    /** Structural-sort name marking an anonymous tuple aggregate. */
    static final String TUPLE_SENTINEL = "_tuple";

    /** A compact, human-readable rendering of a sort for error messages. */
    static String describeSort(IrSort s) {
        return switch (s) {
            case IrSort.Named n -> {
                if (n.typeArgs().isEmpty()) yield n.name();
                StringBuilder sb = new StringBuilder(n.name()).append("[");
                for (int i = 0; i < n.typeArgs().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(describeSort(n.typeArgs().get(i)));
                }
                yield sb.append("]").toString();
            }
            case IrSort.Refined r -> r.name();  // base only; the predicate is elided for readability
            case IrSort.Structural st -> {
                if (!TUPLE_SENTINEL.equals(st.name())) yield st.name();
                StringBuilder sb = new StringBuilder("(");
                boolean first = true;
                for (IrSort m : st.members().values()) {
                    if (!first) sb.append(", ");
                    sb.append(describeSort(m));
                    first = false;
                }
                yield sb.append(")").toString();
            }
            case IrSort.CallSig c -> c.typeName() + "(...)";
            case IrSort.Union u -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < u.branches().size(); i++) {
                    if (i > 0) sb.append(" | ");
                    sb.append(describeSort(u.branches().get(i)));
                }
                yield sb.toString();
            }
            case IrSort.Intersection i -> {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < i.branches().size(); j++) {
                    if (j > 0) sb.append(" & ");
                    sb.append(describeSort(i.branches().get(j)));
                }
                yield sb.toString();
            }
            case IrSort.Trait t -> t.name();
        };
    }

    static boolean isCapitalizedName(String s) {
        return !s.isEmpty() && Character.isUpperCase(s.charAt(0));
    }

    static boolean isPositionalKey(String name) {
        if (name.length() < 2 || name.charAt(0) != '_') return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return false;
        }
        return true;
    }

    static boolean isComparison(IrExpr.Op op) {
        return switch (op) {
            case LT, LE, GT, GE, EQ, NE -> true;
            default -> false;
        };
    }

    /** Order comparisons only (less/less-equal/greater/greater-equal) - the ones that chain. */
    static boolean isOrderComparison(IrExpr.Op op) {
        return switch (op) {
            case LT, LE, GT, GE -> true;
            default -> false;
        };
    }

    static boolean isStar(AltToken t) {
        return t.kind() == AltToken.Kind.OP && "*".equals(t.text());
    }

    static boolean isSelfType(IrSort sort) {
        return sort instanceof IrSort.Named n && n.name().equals(IrSort.SELF_TYPE);
    }

    /** True if the sort is the unknown placeholder sort (a bare underscore). */
    static boolean isUnknownSort(IrSort s) {
        return s instanceof IrSort.Named n && n.name().equals("_");
    }

    /**
     * The common base name shared by every branch of a union/intersection, or
     * null when the branches disagree or any branch has no simple base name.
     */
    static String sameBaseName(List<IrSort> branches) {
        String base = null;
        for (IrSort b : branches) {
            String n = switch (b) {
                case IrSort.Named bn -> bn.name();
                case IrSort.Refined r -> r.name();
                default -> null;
            };
            if (n == null) return null;
            if (base == null) base = n;
            else if (!base.equals(n)) return null;
        }
        return base;
    }

    static String baseSortName(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            case IrSort.CallSig c -> CallKinds.builtin(c.typeName()) == CallKinds.Kind.FUNCTION
                    ? null : c.typeName();
            case IrSort.Trait t -> t.name();
            // Cross-base unions/intersections have no single base name.
            case IrSort.Union u -> null;
            case IrSort.Intersection i -> null;
        };
    }

    /**
     * If the expression is a chain of field accesses rooted at a Var, returns the
     * dotted name (for example "Point.manhattan"). Otherwise returns null. Used to
     * decide whether Name.fn(args) is a qualified Call or an Apply.
     */
    static String extractDottedName(IrExpr expr) {
        if (expr instanceof IrExpr.Var v) return v.name();
        if (expr instanceof IrExpr.FieldAccess fa) {
            String base = extractDottedName(fa.base());
            if (base == null) return null;
            return base + "." + fa.fieldName();
        }
        return null;
    }

    /**
     * Rewrites a per-field pattern predicate (Self meaning the FIELD) into a
     * whole-value predicate (Self meaning the carrier) - the form native-anatomy
     * patterns refine with.
     */
    static IrExpr selfToFieldAccess(IrExpr pred, String field) {
        return switch (pred) {
            case IrExpr.SelfRef s -> new IrExpr.FieldAccess(s, field, s.origin());
            case IrExpr.BinOp op -> new IrExpr.BinOp(op.op(),
                    selfToFieldAccess(op.left(), field),
                    selfToFieldAccess(op.right(), field),
                    op.origin());
            default -> pred;  // literals and anything Self-free pass through
        };
    }
}
