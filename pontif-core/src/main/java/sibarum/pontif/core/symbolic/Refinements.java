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
        return expr instanceof SymExpr.Lit || expr instanceof SymExpr.Frac
                || expr instanceof SymExpr.Dec || expr instanceof SymExpr.Chr
                || expr instanceof SymExpr.Bool;
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
        java.math.BigDecimal a = asNumeric(factBound);
        java.math.BigDecimal b = asNumeric(goalBound);
        if (a == null || b == null) return false;
        return checkImplies(factOp, a, goalOp, b);
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
        if (sort.isRefined() || sort.isStructural() || sort.isMethod()
                || sort.isDispatch() || sort.isUnion() || sort.isIntersection()) {
            return sort;
        }
        Sort resolved = registry.get(sort.name());
        return resolved != null ? resolved : sort;
    }

    /**
     * True when {@code name} is a DECLARED nominal type — present in the
     * module's struct registry. The claim rule keys on this: declared names
     * bite; sentinel names ({@code _record}, {@code _tuple}, {@code _}) and
     * inline shape-labels (S-expr structural sorts never registered) stay
     * shape-only, because there is no nominal type to falsely claim.
     */
    private static boolean isDeclaredName(String name, Simplifier simplifier) {
        return name != null && simplifier.registry().containsKey(name);
    }

    public static ProofResult satisfies(SymExpr value, Sort sort, Simplifier simplifier) {
        // Resolve a nominal struct reference to its definition so its shape is
        // checked. Terminates by the finite value even on a recursive type:
        // descent only follows members present in the runtime record.
        sort = resolveNominal(sort, simplifier.registry());
        if (sort.isStructural()) {
            return satisfiesStructural(value, sort, simplifier);
        }
        if (sort.isMethod()) {
            return satisfiesFunction(value, sort, simplifier);
        }
        if (sort.isDispatch()) {
            // A Dispatch sort is satisfied only by a metareference whose key
            // sorts match exactly (v1 — subsumption/widening is a later
            // ruling). A Method/closure never satisfies a Dispatch and vice
            // versa: the two mechanisms don't cross-assign.
            if (!(value instanceof SymExpr.DispatchRef ref)) {
                return ProofResult.failed(
                        "Value " + value + " is not a dispatch reference — "
                                + "[Dispatch(...)] is satisfied only by a metareference "
                                + "like name[Sorts]");
            }
            if (!ref.keySorts().equals(sort.dispatchKeySorts())) {
                return ProofResult.failed(
                        "Metareference " + value + " is keyed at " + ref.keySorts()
                                + " but the sort requires keys " + sort.dispatchKeySorts()
                                + " (exact match — key subsumption is a later ruling)");
            }
            return ProofResult.passed();
        }
        if (sort.isUnion()) {
            return satisfiesUnion(value, sort, simplifier);
        }
        if (sort.isIntersection()) {
            return satisfiesIntersection(value, sort, simplifier);
        }
        if (!sort.isRefined()) {
            return satisfiesBareNamed(value, sort, simplifier);
        }
        // Refined-primitive kind gate: the base bites on concrete value kind
        // BEFORE the predicate runs, same ruling as bare names — a record that
        // happens to carry a 'scale' member must not satisfy
        // [Decimal:@.scale==2] by predicate coincidence.
        ProofResult kind = primitiveKindGate(value, sort.name(), simplifier);
        if (kind != null) {
            return kind;
        }
        // Refined-by-name claim gate: `[Point:@.x > @.y]` asserts Point-ness
        // before its predicate. When the refined base is a DECLARED type and
        // the value is a record, the claim must match — and thanks to
        // construction totality, a matching claim IS a shape proof, so no
        // structural re-walk is needed. Primitive/trait/unregistered bases
        // keep the existing predicate-only behavior.
        if (isDeclaredName(sort.name(), simplifier)
                && simplifier.simplify(value) instanceof SymExpr.Record(var ignored, String claimed)
                && !sort.name().equals(claimed)) {
            return ProofResult.failed(
                    "Value " + (claimed == null ? "makes no type claim" : "claims '" + claimed + "'")
                            + " but the refined sort requires the declared type '" + sort.name() + "'");
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
     * A bare named sort with no refinement. The KNOWN primitive names bite on
     * the value's kind (ruled 2026-06-06: an anonymous aggregate never matches
     * {@code [Decimal]} — and by the same claim-rule reading, {@code [Int]}
     * never matches a Bool). The lossless Int→Decimal embedding is honored
     * ({@code [Decimal]} accepts an Int value). Symbolic values (Vars and
     * unevaluated expressions) stay unconstrained — biting is for concrete
     * wrong-kind values only, so symbolic dispatch/receipt resolution keeps
     * its hypothesis-driven behavior. Unregistered non-primitive names
     * (traits, inline shape labels) keep the substrate's leniency.
     */
    private static ProofResult satisfiesBareNamed(SymExpr value, Sort sort, Simplifier simplifier) {
        ProofResult kind = primitiveKindGate(value, sort.name(), simplifier);
        return kind != null ? kind : ProofResult.passed();
    }

    /**
     * The primitive kind check shared by bare and refined sorts: fails iff a
     * CONCRETE value's kind mismatches a known primitive base name; {@code
     * null} otherwise (symbolic value, matching kind, or a non-primitive name
     * — the caller decides what no-rejection means). The Int→Decimal
     * embedding is honored.
     */
    private static ProofResult primitiveKindGate(SymExpr value, String name, Simplifier simplifier) {
        SymExpr v = simplifier.simplify(value);
        boolean concrete = v instanceof SymExpr.Lit || v instanceof SymExpr.Dec
                || v instanceof SymExpr.Bool || v instanceof SymExpr.Chr
                || v instanceof SymExpr.Record;
        if (!concrete) {
            return null;
        }
        boolean ok = switch (name) {
            case "Int" -> v instanceof SymExpr.Lit;
            case "Decimal" -> v instanceof SymExpr.Dec || v instanceof SymExpr.Lit;
            case "Bool" -> v instanceof SymExpr.Bool;
            case "Char" -> v instanceof SymExpr.Chr;
            default -> true;  // trait / shape-label / unknown — unconstrained
        };
        return ok ? null
                : ProofResult.failed("Value " + v + " is not a " + name
                        + " — a primitive base sort accepts only values of its kind");
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

        // The claim rule: a DECLARED name bites. A sort named with a registered
        // nominal type accepts only values claiming exactly that type —
        // matching TESTS claims; it never invents one (claims are made at
        // construction, via promotion at assertion boundaries). Sentinel and
        // unregistered (inline shape-label) names stay shape-only, and an
        // anonymous sort still accepts named values (struct ⊑ anonymous — the
        // directional rule). Positional sorts ("_tuple") are additionally
        // arity-EXACT: width is honest for by-name projection but is
        // lying-by-omission for positional slots.
        if ("_tuple".equals(sort.name())) {
            if (!"_tuple".equals(recordTypeName)) {
                return ProofResult.failed(
                        "Value " + simplifiedValue + " is not a tuple; cannot satisfy positional sort " + sort);
            }
            if (members.size() != sort.members().size()) {
                return ProofResult.failed(
                        "Tuple arity mismatch: value has " + members.size()
                                + " component(s) but the sort requires exactly "
                                + sort.members().size() + " — positional sorts take no width");
            }
        } else if (isDeclaredName(sort.name(), simplifier)) {
            if (!sort.name().equals(recordTypeName)) {
                return ProofResult.failed(
                        "Value " + (recordTypeName == null ? "makes no type claim" : "claims '" + recordTypeName + "'")
                                + " but the sort requires the declared type '" + sort.name()
                                + "' — a name is satisfied only by values constructed as that type");
            }
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
        java.util.List<Sort> paramSorts = sort.methodParams();
        Sort returnSort = sort.methodReturnSort();

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
        if (tighter.isMethod() && looser.isMethod()) {
            return implyFunction(tighter, looser, simplifier, assumed);
        }
        if (tighter.isStructural() || looser.isStructural()
                || tighter.isMethod() || looser.isMethod()) {
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
        if (tighter.methodParams().size() != looser.methodParams().size()) {
            return ProofResult.failed(
                    "Function arity mismatch: " + tighter + " vs " + looser);
        }
        // Parameter sorts are contravariant: looser's param implies tighter's param
        for (int i = 0; i < tighter.methodParams().size(); i++) {
            Sort tParam = tighter.methodParams().get(i);
            Sort lParam = looser.methodParams().get(i);
            ProofResult r = imply(lParam, tParam, simplifier, assumed);
            if (!r.isPassed()) {
                if (r instanceof ProofResult.Failed f) {
                    return ProofResult.failed("Parameter " + i + " (contravariant): " + f.witness());
                }
                return r;
            }
        }
        // Return sorts are covariant: tighter's return implies looser's return
        ProofResult retCheck = imply(tighter.methodReturnSort(), looser.methodReturnSort(), simplifier, assumed);
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
        // The claim rule, sort-vs-sort (see satisfiesStructural): a looser sort
        // naming a DECLARED type is implied only by a sort of the same name; a
        // looser positional sort ("_tuple") requires a tuple of the SAME arity;
        // a looser anonymous/label-named sort accepts any tighter shape (width
        // OK — by-name projection honesty, and struct ⊑ anonymous).
        if ("_tuple".equals(looser.name())) {
            if (!"_tuple".equals(tighter.name())) {
                return ProofResult.failed(
                        "Sort " + tighter + " is not a tuple sort; cannot imply positional " + looser);
            }
            if (tighter.members().size() != looser.members().size()) {
                return ProofResult.failed(
                        "Tuple arity mismatch: " + tighter.members().size() + " vs "
                                + looser.members().size() + " — positional sorts take no width");
            }
        } else if (isDeclaredName(looser.name(), simplifier)
                && !looser.name().equals(tighter.name())) {
            return ProofResult.failed(
                    "Sort '" + tighter.name() + "' does not imply the declared type '"
                            + looser.name() + "' — names are satisfied only by the type itself");
        }

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
        java.math.BigDecimal ta = asNumeric(tr);
        java.math.BigDecimal la = asNumeric(lr);
        if (ta == null || la == null) {
            return ProofResult.residual(looser);
        }
        boolean holds = checkImplies(top, ta, lop, la);
        return holds
                ? ProofResult.passed()
                : ProofResult.failed(
                        "Self " + top + " " + ta + " does not imply Self " + lop + " " + la);
    }

    /**
     * Numeric constant extraction for the implication check. Generalized to
     * BigDecimal so the same dense-valid order logic serves both the integer
     * and Decimal domains — integers convert exactly, so integer results are
     * unchanged. (The integer-only discreteness facts — {@code >0 ⟹ >=1} —
     * never lived here; they are quarantined in {@code BoundAnalysis}, reached
     * only via the integer discharge route.)
     */
    private static java.math.BigDecimal asNumeric(SymExpr e) {
        if (e instanceof SymExpr.Lit l) return java.math.BigDecimal.valueOf(l.value());
        if (e instanceof SymExpr.Frac f && f.denom() == 1) return java.math.BigDecimal.valueOf(f.num());
        if (e instanceof SymExpr.Dec d) return d.value();
        return null;
    }

    /**
     * Dense-valid implication over constant bounds: every case below holds in
     * any ordered field (no integer adjacency). Value comparison is
     * {@code compareTo}-based, so {@code 2.0} and {@code 2.00} agree.
     */
    private static boolean checkImplies(SymExpr.CmpOp tOp, java.math.BigDecimal ta,
                                        SymExpr.CmpOp lOp, java.math.BigDecimal la) {
        int c = ta.compareTo(la);
        return switch (tOp) {
            case GT -> switch (lOp) {
                case GT -> c >= 0;
                case GE -> c >= 0;
                default -> false;
            };
            case GE -> switch (lOp) {
                case GT -> c > 0;
                case GE -> c >= 0;
                default -> false;
            };
            case LT -> switch (lOp) {
                case LT -> c <= 0;
                case LE -> c <= 0;
                default -> false;
            };
            case LE -> switch (lOp) {
                case LT -> c < 0;
                case LE -> c <= 0;
                default -> false;
            };
            case EQ -> switch (lOp) {
                case EQ -> c == 0;
                case GT -> c > 0;
                case GE -> c >= 0;
                case LT -> c < 0;
                case LE -> c <= 0;
                case NE -> c != 0;
            };
            case NE -> lOp == SymExpr.CmpOp.NE && c == 0;
        };
    }
}
