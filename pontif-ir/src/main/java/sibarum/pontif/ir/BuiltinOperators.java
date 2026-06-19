package sibarum.pontif.ir;

import java.util.Set;

/**
 * The single source of truth for which built-in binary operators apply to which
 * primitive operand types. It exists so the <em>compile-time</em> operator
 * completeness check ({@link OperatorCompletenessCheck}) and the
 * <em>runtime</em> interpreter agree by construction rather than by two
 * hand-kept tables that could drift — the "one reasoner" discipline applied to
 * operator availability.
 *
 * <p>{@link #acceptsPrimitive} mirrors, exactly, the decision tree in
 * {@code IrInterpreter.evalBinOp} for primitive operands (the String, Decimal,
 * Char, and Int/Bool tiers, in that resolution order). A {@code (op, left,
 * right)} the interpreter would evaluate without throwing returns {@code true};
 * one it rejects at runtime (e.g. {@code Char + Char}, {@code "a" - "b"},
 * {@code Int && Int}) returns {@code false} — which the completeness check turns
 * into a compile error, honoring the mandate that no operator reaches runtime
 * undefined.
 *
 * <p>Scope: <b>primitive</b> operands only ({@link #isPrimitiveBase}). Struct /
 * trait / abstract (type-parameter) operands are not this predicate's concern —
 * those resolve through user overload dispatch and the trait-bound check, whose
 * completeness is enforced separately.
 */
final class BuiltinOperators {

    /** The five scalar primitives operators are defined over. */
    private static final Set<String> PRIMITIVES = Set.of("Int", "Bool", "Decimal", "Char", "String");

    private BuiltinOperators() {}

    /** Whether {@code base} is one of the scalar primitive type names. */
    static boolean isPrimitiveBase(String base) {
        return base != null && PRIMITIVES.contains(base);
    }

    /**
     * Whether the built-in interpreter evaluates {@code op} over operands of the
     * given primitive base types without failing closed. Both bases must be
     * primitives (caller's responsibility — see {@link #isPrimitiveBase}).
     *
     * <p>The tiers and their order match {@code IrInterpreter.evalBinOp}:
     * <ol>
     *   <li><b>String</b> (either operand String): {@code +} always concatenates
     *       (the other operand is rendered); ordering/equality require <em>both</em>
     *       String; all other arithmetic/logical ops are rejected.</li>
     *   <li><b>Decimal</b> (either Decimal, neither String): arithmetic and
     *       comparison apply, but only when the other operand is numeric
     *       (Int or Decimal — Int promotes); logical ops are rejected.</li>
     *   <li><b>Char</b> (either Char, none of the above): comparison/equality only,
     *       and only when <em>both</em> operands are Char; no arithmetic, no logic,
     *       no Char/non-Char mixing.</li>
     *   <li><b>Int/Bool</b>: equality ({@code == != ~=}) is accepted for any pairing;
     *       arithmetic and ordering require both Int; logical ({@code & |}) require
     *       both Bool.</li>
     * </ol>
     */
    static boolean acceptsPrimitive(IrExpr.Op op, String left, String right) {
        boolean leftStr = left.equals("String"), rightStr = right.equals("String");
        if (leftStr || rightStr) {
            if (op == IrExpr.Op.ADD) return true;                 // concat, other operand rendered
            if (isComparison(op)) return leftStr && rightStr;     // both must be String
            return false;                                         // SUB/MUL/DIV/MOD/POW/AND/OR
        }
        if (left.equals("Decimal") || right.equals("Decimal")) {
            boolean bothNumeric = isNumeric(left) && isNumeric(right);
            return bothNumeric && (isArithmetic(op) || isComparison(op));   // not AND/OR
        }
        if (left.equals("Char") || right.equals("Char")) {
            boolean bothChar = left.equals("Char") && right.equals("Char");
            return bothChar && isComparison(op);                  // compare-only; no mixing
        }
        // Int/Bool tier.
        if (op == IrExpr.Op.EQ || op == IrExpr.Op.NE || op == IrExpr.Op.APPROX) {
            return true;                                          // structural equality over any pairing
        }
        if (isArithmetic(op) || op == IrExpr.Op.LT || op == IrExpr.Op.LE
                || op == IrExpr.Op.GT || op == IrExpr.Op.GE) {
            return left.equals("Int") && right.equals("Int");
        }
        if (op == IrExpr.Op.AND || op == IrExpr.Op.OR) {
            return left.equals("Bool") && right.equals("Bool");
        }
        return false;
    }

    private static boolean isNumeric(String base) {
        return base.equals("Int") || base.equals("Decimal");
    }

    private static boolean isArithmetic(IrExpr.Op op) {
        return switch (op) {
            case ADD, SUB, MUL, DIV, MOD, POW -> true;
            default -> false;
        };
    }

    private static boolean isComparison(IrExpr.Op op) {
        return switch (op) {
            case LT, LE, GT, GE, EQ, NE, APPROX -> true;
            default -> false;
        };
    }

    /**
     * A short explanatory clause for why {@code op} is rejected over the given
     * primitive operands — the same guidance the interpreter's fail-closed
     * messages carried, now delivered at compile time. Mirrors the tiers of
     * {@link #acceptsPrimitive}; only meaningful when that returned {@code false}.
     */
    static String rejectionHint(IrExpr.Op op, String left, String right) {
        if (left.equals("String") || right.equals("String")) {
            return isComparison(op)
                    ? "strings compare only with strings (no String/number or String/Char tower)"
                    : "strings order and compare; only '+' concatenates";
        }
        if (left.equals("Decimal") || right.equals("Decimal")) {
            return (isNumeric(left) && isNumeric(right))
                    ? "logical operators need Bool operands"
                    : "decimals combine only with numbers (Int or Decimal)";
        }
        if (left.equals("Char") || right.equals("Char")) {
            return (left.equals("Char") && right.equals("Char"))
                    ? "chars order and compare; they don't compute"
                    : "chars compare only with chars (no Char/number tower)";
        }
        if (op == IrExpr.Op.AND || op == IrExpr.Op.OR) {
            return "logical operators need Bool operands";
        }
        return "arithmetic and ordering need Int operands";
    }

    /** The source symbol for an operator (for diagnostics), all variants covered. */
    static String symbol(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case MOD -> "%"; case POW -> "^";
            case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ -> "=="; case NE -> "!="; case APPROX -> "~=";
            case AND -> "&"; case OR -> "|";
        };
    }
}
