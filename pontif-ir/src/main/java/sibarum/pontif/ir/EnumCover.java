package sibarum.pontif.ir;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The <b>finite cover</b> of a sealed {@code enum} — the one home for the fact that
 * makes an enum an enum rather than a struct with some subtypes (docs/enums.md).
 *
 * <p>An enum's base struct carries its cover as {@link IrSort.Structural#sealedCases()};
 * each case is an ordinary struct with no fields of its own whose is-a morphism
 * <em>pins</em> every field of the base to a literal. Everything an enum needs beyond
 * plain struct machinery reduces to one question — <b>which cases can this pattern
 * match?</b> — and that question is decided here by <em>substitution into a table</em>:
 * each case's field values are compile-time literals, so a pattern's predicate is
 * evaluated once per case with the constants plugged in. There is no solver call, and
 * the reasoning is auditable case by case, which is why the seal can be trusted to
 * decide match totality (docs/enums.md §4).
 *
 * <p>Undecidability is reported honestly as {@code null}, never guessed: a predicate
 * outside the closed fragment (a function call, arithmetic over a free variable) makes
 * the whole cover question unanswerable for that arm, and the caller falls back to
 * demanding a default arm — the standing conservation rule.
 */
public final class EnumCover {

    private EnumCover() {}

    /**
     * The compiler-forced enum discriminant. Present on every enum base as an
     * ordinary field, {@code [Int:@>=0 & @<caseCount]}, pinned per case. It is what
     * lets a payload-free enum ({@code enum Color { Red Green Blue }}) and an enum
     * with duplicate payloads have distinct cases at all, and it gives every enum an
     * order for free. Leading underscore per the forced-member rule.
     */
    public static final String ORDINAL_FIELD = "_ordinal";

    /** The separator between an enum's name and a case's, in the internal type name. */
    private static final char SEP = '$';

    /** The internal type name of {@code enumName}'s case {@code caseName}. */
    public static String caseType(String enumName, String caseName) {
        return enumName + SEP + caseName;
    }

    /** The bare case name from an internal case type name — {@code E$C} → {@code C}. */
    public static String caseLabel(String internalName) {
        int i = internalName.lastIndexOf(SEP);
        return i < 0 ? internalName : internalName.substring(i + 1);
    }

    /**
     * The surface spelling of an internal case type name — {@code E$C} → {@code E.C},
     * with any linker qualification kept. Diagnostics use this so a user never has to
     * read the mangled form.
     */
    public static String display(String internalName) {
        return internalName.replace(SEP, '.');
    }

    /**
     * The literal each base field is pinned to on this case, read off its is-a
     * morphism. Empty when {@code caseStruct} has no refined base (so it is not an
     * enum case at all).
     */
    public static Map<String, IrExpr> pins(IrSort.Structural caseStruct) {
        Map<String, IrExpr> out = new LinkedHashMap<>();
        if (caseStruct != null && caseStruct.baseSort() instanceof IrSort.Refined r) {
            collectPins(r.predicate(), out);
        }
        return out;
    }

    private static void collectPins(IrExpr pred, Map<String, IrExpr> out) {
        if (!(pred instanceof IrExpr.BinOp op)) return;
        switch (op.op()) {
            case AND -> {
                collectPins(op.left(), out);
                collectPins(op.right(), out);
            }
            case EQ -> {
                String l = selfField(op.left());
                if (l != null && isLiteral(op.right())) out.put(l, op.right());
                String r = selfField(op.right());
                if (r != null && isLiteral(op.left())) out.put(r, op.left());
            }
            default -> { }
        }
    }

    /** {@code @.field} → {@code "field"}; anything else → null. */
    private static String selfField(IrExpr e) {
        return e instanceof IrExpr.FieldAccess fa && fa.base() instanceof IrExpr.SelfRef
                ? fa.fieldName() : null;
    }

    // --- the cover question -------------------------------------------------

    /**
     * Which of {@code enumBase}'s cases the match-arm pattern {@code pattern} can
     * match, or {@code null} when that cannot be decided (an arm outside the closed
     * fragment). Recognised arms:
     *
     * <ul>
     *   <li>{@code [Enum]} — the bare base, or a bare full destructure of it: every
     *       case;</li>
     *   <li>{@code [Enum.Case]} — that one case;</li>
     *   <li>{@code [Enum:pred]} — every case whose pinned field values satisfy
     *       {@code pred} (which is how {@code [Enum(literal…)]} arrives, the parser
     *       having desugared it to the equivalent field pins);</li>
     *   <li>{@code [Enum.Case:pred]} — that case, if it satisfies {@code pred}.</li>
     * </ul>
     *
     * An arm naming something unrelated covers nothing (the empty set) — that is a
     * decided answer, not an undecidable one.
     */
    public static Set<String> covered(
            IrSort pattern, IrSort.Structural enumBase,
            Map<String, IrSort.Structural> structs) {
        String enumName = enumBase.name();
        List<String> cases = enumBase.sealedCases();
        return switch (pattern) {
            case IrSort.Named n -> named(n.name(), enumName, cases);
            case IrSort.Refined r -> {
                Set<String> head = named(r.name(), enumName, cases);
                yield head == null ? null : filter(head, r.predicate(), structs);
            }
            // A struct pattern over the enum: bare (all member sorts unconstrained)
            // is a full destructure and covers everything; a member carrying a
            // refinement constrains that field, so evaluate it per case.
            case IrSort.Structural s -> {
                Set<String> head = named(s.name(), enumName, cases);
                if (head == null) yield null;
                Set<String> acc = head;
                for (Map.Entry<String, IrSort> m : s.members().entrySet()) {
                    if (!(m.getValue() instanceof IrSort.Refined mr)) continue;
                    acc = filterField(acc, m.getKey(), mr.predicate(), structs);
                    if (acc == null) yield null;
                }
                yield acc;
            }
            // A union arm covers the union of its branches' coverage.
            case IrSort.Union u -> {
                Set<String> acc = new LinkedHashSet<>();
                for (IrSort b : u.branches()) {
                    Set<String> part = covered(b, enumBase, structs);
                    if (part == null) yield null;
                    acc.addAll(part);
                }
                yield acc;
            }
            default -> null;
        };
    }

    /** The cases a bare NAME covers: the enum itself → all; one case → itself. */
    private static Set<String> named(String name, String enumName, List<String> cases) {
        if (name == null) return null;
        if (name.startsWith("_")) return new LinkedHashSet<>(cases);  // anonymous/universal
        if (name.equals(enumName)) return new LinkedHashSet<>(cases);
        if (cases.contains(name)) return new LinkedHashSet<>(List.of(name));
        return new LinkedHashSet<>();
    }

    /** Keeps the cases of {@code in} whose pinned fields satisfy {@code pred}. */
    private static Set<String> filter(
            Set<String> in, IrExpr pred, Map<String, IrSort.Structural> structs) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : in) {
            Boolean holds = evaluate(pred, pins(structs.get(c)));
            if (holds == null) return null;
            if (holds) out.add(c);
        }
        return out;
    }

    /**
     * Keeps the cases whose value for {@code field} satisfies a MEMBER-level
     * predicate, in which {@code @} denotes that field's value rather than the record.
     */
    private static Set<String> filterField(
            Set<String> in, String field, IrExpr pred,
            Map<String, IrSort.Structural> structs) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : in) {
            IrExpr value = pins(structs.get(c)).get(field);
            if (value == null) return null;
            Boolean holds = evaluate(pred, Map.of(), value);
            if (holds == null) return null;
            if (holds) out.add(c);
        }
        return out;
    }

    // --- the closed evaluator ----------------------------------------------

    /**
     * Evaluates a refinement predicate against one case's constant fields, with
     * {@code @} denoting the record. {@code null} means "outside the closed
     * fragment" — the honest answer, which the caller turns into a demand for a
     * default arm rather than a guess.
     */
    static Boolean evaluate(IrExpr pred, Map<String, IrExpr> fields) {
        return evaluate(pred, fields, null);
    }

    /** As above, with {@code self} the value {@code @} denotes when it stands alone. */
    private static Boolean evaluate(IrExpr pred, Map<String, IrExpr> fields, IrExpr self) {
        if (pred instanceof IrExpr.Bool b) return b.value();
        if (!(pred instanceof IrExpr.BinOp op)) return null;
        switch (op.op()) {
            case AND -> {
                Boolean l = evaluate(op.left(), fields, self);
                Boolean r = evaluate(op.right(), fields, self);
                if (Boolean.FALSE.equals(l) || Boolean.FALSE.equals(r)) return false;
                return (l == null || r == null) ? null : true;
            }
            case OR -> {
                Boolean l = evaluate(op.left(), fields, self);
                Boolean r = evaluate(op.right(), fields, self);
                if (Boolean.TRUE.equals(l) || Boolean.TRUE.equals(r)) return true;
                return (l == null || r == null) ? null : false;
            }
            case EQ, NE, LT, LE, GT, GE -> {
                IrExpr l = resolve(op.left(), fields, self);
                IrExpr r = resolve(op.right(), fields, self);
                if (l == null || r == null) return null;
                return compare(op.op(), l, r);
            }
            default -> {
                return null;
            }
        }
    }

    /** A predicate operand reduced to a literal, or null if it is not one. */
    private static IrExpr resolve(IrExpr e, Map<String, IrExpr> fields, IrExpr self) {
        if (isLiteral(e)) return e;
        if (e instanceof IrExpr.SelfRef) return self != null && isLiteral(self) ? self : null;
        String field = selfField(e);
        if (field == null) return null;
        IrExpr v = fields.get(field);
        return isLiteral(v) ? v : null;
    }

    static boolean isLiteral(IrExpr e) {
        return e instanceof IrExpr.Lit || e instanceof IrExpr.Dec || e instanceof IrExpr.Str
                || e instanceof IrExpr.Chr || e instanceof IrExpr.Bool;
    }

    /** Whether two literals are the same value (across the Int/Decimal boundary too). */
    public static boolean sameLiteral(IrExpr a, IrExpr b) {
        return Boolean.TRUE.equals(compare(IrExpr.Op.EQ, a, b));
    }

    /** Compares two literals, or null when they are not comparable kinds. */
    private static Boolean compare(IrExpr.Op op, IrExpr a, IrExpr b) {
        BigDecimal na = number(a);
        BigDecimal nb = number(b);
        if (na != null && nb != null) return fromSign(op, na.compareTo(nb));
        if (a instanceof IrExpr.Str sa && b instanceof IrExpr.Str sb) {
            return fromSign(op, sa.value().compareTo(sb.value()));
        }
        if (a instanceof IrExpr.Bool ba && b instanceof IrExpr.Bool bb) {
            return switch (op) {
                case EQ -> ba.value() == bb.value();
                case NE -> ba.value() != bb.value();
                default -> null;
            };
        }
        return null;
    }

    /** The numeric value of an Int, Decimal, or Char literal; null for the rest. */
    private static BigDecimal number(IrExpr e) {
        return switch (e) {
            case IrExpr.Lit l -> BigDecimal.valueOf(l.value());
            case IrExpr.Dec d -> d.value();
            case IrExpr.Chr c -> BigDecimal.valueOf(c.codePoint());
            case null, default -> null;
        };
    }

    private static Boolean fromSign(IrExpr.Op op, int sign) {
        return switch (op) {
            case EQ -> sign == 0;
            case NE -> sign != 0;
            case LT -> sign < 0;
            case LE -> sign <= 0;
            case GT -> sign > 0;
            case GE -> sign >= 0;
            default -> null;
        };
    }
}
