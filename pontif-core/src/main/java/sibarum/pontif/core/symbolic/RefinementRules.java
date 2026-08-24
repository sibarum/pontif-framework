package sibarum.pontif.core.symbolic;

import java.util.List;
import java.util.Optional;

public final class RefinementRules {

    private RefinementRules() {}

    public static final RewriteRule CMP_LIT_LIT = (expr, simp) -> {
        if (expr instanceof SymExpr.Cmp(SymExpr.Lit l, SymExpr.CmpOp op, SymExpr.Lit r)) {
            boolean truth = switch (op) {
                case LT -> l.value() < r.value();
                case LE -> l.value() <= r.value();
                case GT -> l.value() > r.value();
                case GE -> l.value() >= r.value();
                case EQ -> l.value() == r.value();
                case NE -> l.value() != r.value();
            };
            return Optional.of(SymExpr.bool(truth));
        }
        return Optional.empty();
    };

    /**
     * Bool counterpart to {@link #CMP_LIT_LIT}: folds {@code Bool(a) == Bool(b)}
     * and {@code Bool(a) != Bool(b)} to a Bool literal so {@code Bool} match
     * arms (e.g. {@code [@==true]}) can be decided at runtime after substituting
     * {@code Self} with the scrutinee value. Ordering ops on Bool aren't part of
     * Pontif's surface semantics — leave them residual rather than impose an
     * arbitrary boolean ordering.
     */
    public static final RewriteRule CMP_BOOL_BOOL = (expr, simp) -> {
        if (expr instanceof SymExpr.Cmp(SymExpr.Bool l, SymExpr.CmpOp op, SymExpr.Bool r)) {
            return switch (op) {
                case EQ -> Optional.of(SymExpr.bool(l.value() == r.value()));
                case NE -> Optional.of(SymExpr.bool(l.value() != r.value()));
                case LT, LE, GT, GE -> Optional.empty();
            };
        }
        return Optional.empty();
    };

    /**
     * Decimal counterpart to {@link #CMP_LIT_LIT}: folds a comparison where at
     * least one side is a {@link SymExpr.Dec} and both sides are numeric
     * constants (Dec, or an integer Lit — Decimal narrows like
     * {@code [Decimal:@>0]} compare against integer-literal bounds), via
     * BigDecimal {@code compareTo} — so equality is up-to-scale
     * ({@code 2.0 == 2.00}). Decides Decimal narrows after Self-substitution,
     * both statically and in runtime deferred checks.
     */
    public static final RewriteRule CMP_DEC_NUMERIC = (expr, simp) -> {
        if (expr instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r)
                && (l instanceof SymExpr.Dec || r instanceof SymExpr.Dec)) {
            java.math.BigDecimal a = asNumeric(l);
            java.math.BigDecimal b = asNumeric(r);
            if (a == null || b == null) {
                return Optional.empty();
            }
            int c = a.compareTo(b);
            boolean truth = switch (op) {
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
                case EQ -> c == 0;
                case NE -> c != 0;
            };
            return Optional.of(SymExpr.bool(truth));
        }
        return Optional.empty();
    };

    private static java.math.BigDecimal asNumeric(SymExpr e) {
        if (e instanceof SymExpr.Dec d) return d.value();
        if (e instanceof SymExpr.Lit l) return java.math.BigDecimal.valueOf(l.value());
        return null;
    }

    /**
     * Char counterpart: folds comparisons where BOTH sides are {@link
     * SymExpr.Chr}, by code point. Strictly Chr-with-Chr — there is no
     * Char/Int tower, so a Chr never folds against a Lit; mixed comparisons
     * stay residual (and fail closed downstream) rather than inventing a
     * conversion.
     */
    public static final RewriteRule CMP_CHR_CHR = (expr, simp) -> {
        if (expr instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r)
                && l instanceof SymExpr.Chr lc && r instanceof SymExpr.Chr rc) {
            int c = Integer.compare(lc.codePoint(), rc.codePoint());
            boolean truth = switch (op) {
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
                case EQ -> c == 0;
                case NE -> c != 0;
            };
            return Optional.of(SymExpr.bool(truth));
        }
        return Optional.empty();
    };

    /**
     * String counterpart: folds comparisons where BOTH sides are {@link SymExpr.Str},
     * by lexicographic {@code compareTo}. Strings <em>order and compare</em> (they
     * just don't compute), so all six operators fold — the same six {@link
     * #CMP_CHR_CHR} folds, which is the consistent reading for a sequence of Chars.
     *
     * <p>Without this a String refinement was only half-decidable at runtime: equal
     * strings collapsed by structural identity, but UNEQUAL ones stayed residual, so
     * a match arm like {@code [R:@.driver=="NTFS"]} died with an undecidable
     * obligation instead of simply not matching. Strictly Str-with-Str: there is no
     * String/Char tower, so a mixed comparison stays residual rather than inventing a
     * conversion.
     */
    public static final RewriteRule CMP_STR_STR = (expr, simp) -> {
        if (expr instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r)
                && l instanceof SymExpr.Str ls && r instanceof SymExpr.Str rs) {
            int c = ls.value().compareTo(rs.value());
            boolean truth = switch (op) {
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
                case EQ -> c == 0;
                case NE -> c != 0;
            };
            return Optional.of(SymExpr.bool(truth));
        }
        return Optional.empty();
    };

    public static List<RewriteRule> all() {
        return List.of(CMP_LIT_LIT, CMP_BOOL_BOOL, CMP_DEC_NUMERIC, CMP_CHR_CHR, CMP_STR_STR);
    }
}
