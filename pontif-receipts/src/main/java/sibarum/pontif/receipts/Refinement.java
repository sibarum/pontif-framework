package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;

/**
 * A conservative refinement of a {@link Branch}: a binary tree of
 * case-splits whose leaves are obligations to discharge piecewise.
 *
 * <h2>Information-conservative by construction</h2>
 * The only way to split is {@link #splitOn(SymExpr, Refinement, Refinement)},
 * which takes a <em>single</em> predicate {@code p} and produces two
 * children: the {@code p}-side and the {@code ¬p}-side. The negation is
 * never stored or chosen independently — it is <em>derived</em> at
 * validation time (see {@link #complement}). So the two children's guards
 * are always a predicate and its exact complement, which means:
 * <ul>
 *   <li><b>coverage</b> holds by excluded middle ({@code p ∨ ¬p}) — no case
 *       is missed; and</li>
 *   <li><b>disjointness</b> holds by non-contradiction ({@code p ∧ ¬p ≡ ⊥})
 *       — no case is double-counted.</li>
 * </ul>
 * Neither property has to be <em>checked</em>: an invalid partition is
 * unrepresentable. {@link RefinementValidator} therefore only verifies
 * per-leaf discharge — the partition is sound by the shape of this type.
 *
 * <h2>Why comparisons only (for now)</h2>
 * A split predicate must be a comparison ({@link SymExpr.Cmp}). Its
 * complement is the operator flip ({@code GE↔LT}, {@code GT↔LE},
 * {@code EQ↔NE}), which is the <em>exact</em> logical negation over a total
 * order — and that exactness is precisely what makes coverage/disjointness
 * structural rather than something to prove. Compound ({@code And}/{@code Or})
 * split predicates would need De Morgan complementation and are a later
 * extension; until then the full expressive power is recovered by
 * <em>composing</em> binary cuts (a tree of comparisons partitions any
 * region).
 */
public sealed interface Refinement permits Refinement.Leaf, Refinement.Split {

    /** A leaf: discharge the obligation here, under the accumulated guards. */
    record Leaf() implements Refinement {}

    /**
     * A case-split on {@code predicate}. {@link #whenTrue} carries
     * {@code predicate} as an added guard; {@link #whenFalse} carries
     * {@link Refinement#complement(SymExpr) complement(predicate)}. Only the
     * predicate is stored — the {@code ¬p} guard is derived, so the two
     * sides can never desync into a non-partition.
     */
    record Split(SymExpr predicate, Refinement whenTrue, Refinement whenFalse)
            implements Refinement {
        public Split {
            if (!(predicate instanceof SymExpr.Cmp)) {
                throw new IllegalArgumentException(
                        "split predicate must be a comparison (Cmp); got " + predicate);
            }
            if (whenTrue == null || whenFalse == null) {
                throw new IllegalArgumentException("Split children must be non-null");
            }
        }
    }

    /** A discharge leaf. */
    static Refinement leaf() {
        return new Leaf();
    }

    /**
     * The conservative split combinator: partition into the {@code predicate}
     * case and its exact complement. See the type documentation.
     */
    static Refinement splitOn(SymExpr predicate, Refinement whenTrue, Refinement whenFalse) {
        return new Split(predicate, whenTrue, whenFalse);
    }

    /**
     * The exact logical complement of a comparison, by operator flip. Faithful
     * over a total order (the integer domain) — there is no third outcome
     * between {@code a OP b} and {@code a (¬OP) b}, which is what lets the
     * {@code ¬p}-side cover exactly the complement of the {@code p}-side.
     */
    static SymExpr complement(SymExpr comparison) {
        if (!(comparison instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r))) {
            throw new IllegalArgumentException(
                    "complement requires a comparison (Cmp); got " + comparison);
        }
        return SymExpr.cmp(l, flip(op), r);
    }

    private static SymExpr.CmpOp flip(SymExpr.CmpOp op) {
        return switch (op) {
            case LT -> SymExpr.CmpOp.GE;
            case GE -> SymExpr.CmpOp.LT;
            case LE -> SymExpr.CmpOp.GT;
            case GT -> SymExpr.CmpOp.LE;
            case EQ -> SymExpr.CmpOp.NE;
            case NE -> SymExpr.CmpOp.EQ;
        };
    }
}
