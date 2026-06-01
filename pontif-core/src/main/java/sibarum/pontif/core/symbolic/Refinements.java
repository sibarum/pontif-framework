package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.util.Optional;

public final class Refinements {

    private Refinements() {}

    public static Optional<SymExpr> uniqueValue(Sort sort) {
        if (!sort.isRefined()) {
            return Optional.empty();
        }
        SymExpr p = sort.predicate();
        if (p instanceof SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) && op == SymExpr.CmpOp.EQ) {
            if (l instanceof SymExpr.Self && isConstant(r)) {
                return Optional.of(r);
            }
            if (r instanceof SymExpr.Self && isConstant(l)) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }

    private static boolean isConstant(SymExpr expr) {
        return expr instanceof SymExpr.Lit || expr instanceof SymExpr.Frac || expr instanceof SymExpr.Bool;
    }

    /**
     * Best-effort discharge: can {@code goal} be derived from any combination
     * of {@code hypotheses}? Tries direct single-hypothesis implication first,
     * then falls back to sign analysis for compound arithmetic subjects.
     */
    public static boolean discharge(java.util.List<SymExpr> hypotheses, SymExpr goal) {
        for (SymExpr fact : hypotheses) {
            if (implies(fact, goal)) return true;
        }
        return SignAnalysis.canDischarge(hypotheses, goal);
    }

    /**
     * Does {@code fact} imply {@code goal}? Both should typically be Cmp expressions
     * about the same subject (Self, Var, or any matching expression).
     * Returns true for trivial cases (structural equality) and for arithmetic
     * inferences over Cmp with constant bounds.
     */
    public static boolean implies(SymExpr fact, SymExpr goal) {
        if (fact.equals(goal)) return true;
        if (!(fact instanceof SymExpr.Cmp(SymExpr factSubj, SymExpr.CmpOp factOp, SymExpr factBound))) {
            return false;
        }
        if (!(goal instanceof SymExpr.Cmp(SymExpr goalSubj, SymExpr.CmpOp goalOp, SymExpr goalBound))) {
            return false;
        }
        if (!factSubj.equals(goalSubj)) return false;
        Long a = asLong(factBound);
        Long b = asLong(goalBound);
        if (a == null || b == null) return false;
        return checkImpliesOnLongs(factOp, a, goalOp, b);
    }

    /**
     * Resolves a by-reference struct sort ({@code Sort.of("Node")}) to its
     * structural definition via the simplifier's registry. A sort that already
     * carries structure (refined / structural / function / union / intersection)
     * or a name absent from the registry (a primitive, an undeclared name) is
     * returned unchanged. One lookup, no recursion — the structural definition's
     * own members stay by-reference and are resolved lazily one level at a time,
     * so this never unrolls a recursive type.
     */
    private static Sort resolveNominal(Sort sort, java.util.Map<String, Sort> registry) {
        if (sort.isRefined() || sort.isStructural() || sort.isFunction()
                || sort.isUnion() || sort.isIntersection()) {
            return sort;
        }
        Sort resolved = registry.get(sort.name());
        return resolved != null ? resolved : sort;
    }

    public static ProofResult satisfies(SymExpr value, Sort sort, Simplifier simplifier) {
        // Resolve a nominal struct reference to its definition so its shape is
        // checked. Terminates by the finite value even on a recursive type:
        // descent only follows members present in the runtime record.
        sort = resolveNominal(sort, simplifier.registry());
        if (sort.isStructural()) {
            return satisfiesStructural(value, sort, simplifier);
        }
        if (sort.isFunction()) {
            return satisfiesFunction(value, sort, simplifier);
        }
        if (sort.isUnion()) {
            return satisfiesUnion(value, sort, simplifier);
        }
        if (sort.isIntersection()) {
            return satisfiesIntersection(value, sort, simplifier);
        }
        if (!sort.isRefined()) {
            return ProofResult.passed();
        }
        SymExpr substituted = Substitute.applySelf(sort.predicate(), value);
        SymExpr simplified = simplifier.simplify(substituted);
        if (simplified instanceof SymExpr.Bool b) {
            return b.value()
                    ? ProofResult.passed()
                    : ProofResult.failed(
                            "Value " + value + " does not satisfy " + sort + " — predicate " + sort.predicate() + " evaluates to false");
        }
        return ProofResult.residual(simplified);
    }

    /**
     * Union: value satisfies iff it satisfies at least one branch.
     * Returns Passed on the first satisfying branch; falls back to
     * Residual if any branch is residual (none Passed yet); else Failed.
     */
    private static ProofResult satisfiesUnion(SymExpr value, Sort sort, Simplifier simplifier) {
        boolean anyResidual = false;
        for (Sort branch : sort.unionBranches()) {
            ProofResult r = satisfies(value, branch, simplifier);
            if (r instanceof ProofResult.Passed) return r;
            if (r instanceof ProofResult.Residual) anyResidual = true;
        }
        if (anyResidual) return ProofResult.residual(value);
        return ProofResult.failed(
                "Value " + value + " does not satisfy any branch of union " + sort);
    }

    /**
     * Intersection: value satisfies iff it satisfies every branch. Fails
     * fast on the first non-satisfying branch.
     */
    private static ProofResult satisfiesIntersection(SymExpr value, Sort sort, Simplifier simplifier) {
        boolean anyResidual = false;
        for (Sort branch : sort.intersectionBranches()) {
            ProofResult r = satisfies(value, branch, simplifier);
            if (r instanceof ProofResult.Failed f) {
                return ProofResult.failed(
                        "Value " + value + " fails branch " + branch
                                + " of intersection " + sort + ": " + f.witness());
            }
            if (r instanceof ProofResult.Residual) anyResidual = true;
        }
        if (anyResidual) return ProofResult.residual(value);
        return ProofResult.passed();
    }

    private static ProofResult satisfiesStructural(SymExpr value, Sort sort, Simplifier simplifier) {
        SymExpr simplifiedValue = simplifier.simplify(value);
        if (!(simplifiedValue instanceof SymExpr.Record(java.util.Map<String, SymExpr> members, String recordTypeName))) {
            if (simplifiedValue instanceof SymExpr.Var) {
                return ProofResult.residual(simplifiedValue);
            }
            return ProofResult.failed(
                    "Value " + simplifiedValue + " is not a record; cannot satisfy structural sort " + sort);
        }

        // Build map of (member name → simplified sibling-invariant) for each refined member.
        // Each member's invariant is the member sort's predicate with Self substituted by
        // the FieldAccess to that member, then simplified. Trivially-true invariants are
        // omitted; symbolic ones remain as useful hypotheses about field references.
        java.util.Map<String, SymExpr> memberInvariants = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Sort> required : sort.members().entrySet()) {
            Sort memberSort = required.getValue();
            if (memberSort.isRefined()) {
                SymExpr fact = simplifier.simplify(Substitute.applySelf(
                        memberSort.predicate(),
                        SymExpr.fieldAccess(simplifiedValue, required.getKey())));
                if (!(fact instanceof SymExpr.Bool)) {
                    memberInvariants.put(required.getKey(), fact);
                }
            }
        }

        for (java.util.Map.Entry<String, Sort> required : sort.members().entrySet()) {
            SymExpr memberValue = members.get(required.getKey());
            if (memberValue == null) {
                return ProofResult.failed(
                        "Record is missing required member '" + required.getKey() + "' (expected sort " + required.getValue() + ")");
            }

            // Build a context for verifying THIS member that includes every OTHER
            // member's invariant but excludes this member's own (avoids circularity).
            Context memberCtx = simplifier.context();
            for (java.util.Map.Entry<String, SymExpr> inv : memberInvariants.entrySet()) {
                if (!inv.getKey().equals(required.getKey())) {
                    memberCtx = memberCtx.with(inv.getValue());
                }
            }
            Simplifier enriched = simplifier.withContext(memberCtx);

            // Substitute Self in the member value with the enclosing record so methods'
            // bodies see Self as the record they belong to.
            SymExpr effective = Substitute.applySelf(memberValue, simplifiedValue);
            ProofResult memberResult = satisfies(effective, required.getValue(), enriched);
            if (!memberResult.isPassed()) {
                if (memberResult instanceof ProofResult.Failed f) {
                    return ProofResult.failed(
                            "Member '" + required.getKey() + "': " + f.witness());
                }
                return memberResult;
            }
        }
        return ProofResult.passed();
    }

    private static ProofResult satisfiesFunction(SymExpr value, Sort sort, Simplifier simplifier) {
        java.util.List<Sort> paramSorts = sort.functionParams();
        Sort returnSort = sort.functionReturnSort();

        SymExpr current = simplifier.simplify(value);
        Context ctx = simplifier.context();

        for (Sort paramSort : paramSorts) {
            if (!(current instanceof SymExpr.Lam(String paramName, Sort declaredType, SymExpr body))) {
                if (current instanceof SymExpr.Var) {
                    return ProofResult.residual(current);
                }
                return ProofResult.failed(
                        "Expected a function with " + paramSorts.size() + " parameter(s); "
                                + "value is not a lambda at the required depth: " + current);
            }
            if (paramSort.isRefined()) {
                SymExpr precondition = Substitute.applySelf(
                        paramSort.predicate(), SymExpr.var(paramName));
                ctx = ctx.with(precondition);
            }
            current = body;
        }

        Simplifier enriched = simplifier.withContext(ctx);
        return satisfies(current, returnSort, enriched);
    }

    public static ProofResult imply(Sort tighter, Sort looser, Simplifier simplifier) {
        return imply(tighter, looser, simplifier, Coinduction.Assumed.empty());
    }

    private static ProofResult imply(Sort tighter, Sort looser, Simplifier simplifier,
                                     Coinduction.Assumed assumed) {
        // Resolve by-reference struct sorts so subsumption compares structure.
        tighter = resolveNominal(tighter, simplifier.registry());
        looser = resolveNominal(looser, simplifier.registry());
        if (tighter.isStructural() && looser.isStructural()) {
            return implyStructural(tighter, looser, simplifier, assumed);
        }
        if (tighter.isFunction() && looser.isFunction()) {
            return implyFunction(tighter, looser, simplifier, assumed);
        }
        if (tighter.isStructural() || looser.isStructural()
                || tighter.isFunction() || looser.isFunction()) {
            return ProofResult.failed(
                    "Cannot relate different sort kinds: " + tighter + " vs " + looser);
        }
        if (!looser.isRefined()) {
            return ProofResult.passed();
        }
        if (!tighter.isRefined()) {
            return ProofResult.residual(looser.predicate());
        }
        if (AlphaEquivalence.equivalent(tighter.predicate(), looser.predicate())) {
            return ProofResult.passed();
        }
        ProofResult arithmetic = tryArithmeticImplication(tighter.predicate(), looser.predicate());
        if (!(arithmetic instanceof ProofResult.Residual)) {
            return arithmetic;
        }
        SymExpr obligation = new SymExpr.Cmp(tighter.predicate(), SymExpr.CmpOp.LE, looser.predicate());
        return ProofResult.residual(obligation);
    }

    private static ProofResult implyFunction(Sort tighter, Sort looser, Simplifier simplifier,
                                             Coinduction.Assumed assumed) {
        if (tighter.functionParams().size() != looser.functionParams().size()) {
            return ProofResult.failed(
                    "Function arity mismatch: " + tighter + " vs " + looser);
        }
        // Parameter sorts are contravariant: looser's param implies tighter's param
        for (int i = 0; i < tighter.functionParams().size(); i++) {
            Sort tParam = tighter.functionParams().get(i);
            Sort lParam = looser.functionParams().get(i);
            ProofResult r = imply(lParam, tParam, simplifier, assumed);
            if (!r.isPassed()) {
                if (r instanceof ProofResult.Failed f) {
                    return ProofResult.failed("Parameter " + i + " (contravariant): " + f.witness());
                }
                return r;
            }
        }
        // Return sorts are covariant: tighter's return implies looser's return
        ProofResult retCheck = imply(tighter.functionReturnSort(), looser.functionReturnSort(), simplifier, assumed);
        if (!retCheck.isPassed()) {
            if (retCheck instanceof ProofResult.Failed f) {
                return ProofResult.failed("Return sort: " + f.witness());
            }
            return retCheck;
        }
        return ProofResult.passed();
    }

    private static ProofResult implyStructural(Sort tighter, Sort looser, Simplifier simplifier,
                                               Coinduction.Assumed assumed) {
        // Coinductive guard: subsumption on equi-recursive structs is a greatest
        // fixed point. Revisiting a (tighter, looser) name pair already on the
        // path means it's the hypothesis discharging itself — assume it holds and
        // stop, which is what makes `imply(Node, Node)` terminate. Sound because
        // every non-back-edge member obligation below is still checked normally.
        if (assumed.holds(tighter.name(), looser.name())) {
            return ProofResult.passed();
        }
        Coinduction.Assumed next = assumed.assuming(tighter.name(), looser.name());
        for (java.util.Map.Entry<String, Sort> required : looser.members().entrySet()) {
            Sort tighterMember = tighter.members().get(required.getKey());
            if (tighterMember == null) {
                return ProofResult.failed(
                        "Tighter sort " + tighter.name() + " lacks required member '" + required.getKey() + "'");
            }
            ProofResult memberResult = imply(tighterMember, required.getValue(), simplifier, next);
            if (!memberResult.isPassed()) {
                if (memberResult instanceof ProofResult.Failed f) {
                    return ProofResult.failed(
                            "Member '" + required.getKey() + "': " + f.witness());
                }
                return memberResult;
            }
        }
        return ProofResult.passed();
    }

    private static ProofResult tryArithmeticImplication(SymExpr tighter, SymExpr looser) {
        if (!(tighter instanceof SymExpr.Cmp(SymExpr tl, SymExpr.CmpOp top, SymExpr tr))) {
            return ProofResult.residual(looser);
        }
        if (!(looser instanceof SymExpr.Cmp(SymExpr ll, SymExpr.CmpOp lop, SymExpr lr))) {
            return ProofResult.residual(looser);
        }
        if (!(tl instanceof SymExpr.Self) || !(ll instanceof SymExpr.Self)) {
            return ProofResult.residual(looser);
        }
        Long ta = asLong(tr);
        Long la = asLong(lr);
        if (ta == null || la == null) {
            return ProofResult.residual(looser);
        }
        boolean holds = checkImpliesOnLongs(top, ta, lop, la);
        return holds
                ? ProofResult.passed()
                : ProofResult.failed(
                        "Self " + top + " " + ta + " does not imply Self " + lop + " " + la);
    }

    private static Long asLong(SymExpr e) {
        if (e instanceof SymExpr.Lit l) return l.value();
        if (e instanceof SymExpr.Frac f && f.denom() == 1) return f.num();
        return null;
    }

    private static boolean checkImpliesOnLongs(SymExpr.CmpOp tOp, long ta, SymExpr.CmpOp lOp, long la) {
        return switch (tOp) {
            case GT -> switch (lOp) {
                case GT -> ta >= la;
                case GE -> ta >= la;
                default -> false;
            };
            case GE -> switch (lOp) {
                case GT -> ta > la;
                case GE -> ta >= la;
                default -> false;
            };
            case LT -> switch (lOp) {
                case LT -> ta <= la;
                case LE -> ta <= la;
                default -> false;
            };
            case LE -> switch (lOp) {
                case LT -> ta < la;
                case LE -> ta <= la;
                default -> false;
            };
            case EQ -> switch (lOp) {
                case EQ -> ta == la;
                case GT -> ta > la;
                case GE -> ta >= la;
                case LT -> ta < la;
                case LE -> ta <= la;
                case NE -> ta != la;
            };
            case NE -> lOp == SymExpr.CmpOp.NE && ta == la;
        };
    }
}
