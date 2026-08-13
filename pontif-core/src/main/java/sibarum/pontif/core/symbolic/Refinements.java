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
                || expr instanceof SymExpr.Str || expr instanceof SymExpr.Bool;
    }

    /**
     * Best-effort discharge: can {@code goal} be derived from {@code hypotheses}?
     * Delegates to the one linear-bound + sign kernel ({@link BoundAnalysis}) in
     * the <b>dense</b> ({@code Decimal}) domain — refinement proof for
     * subtyping / simplification is domain-neutral, and the integer-strict grid
     * belongs only to the receipts / return-gate side ({@code Domain.INT}). The
     * kernel subsumes the former order-implication + sign backend and adds
     * additive linear reasoning (e.g. {@code x>5 ∧ y>=0 ⟹ x+y>5}).
     */
    public static boolean discharge(java.util.List<SymExpr> hypotheses, SymExpr goal) {
        return BoundAnalysis.discharge(BoundAnalysis.Domain.DECIMAL, hypotheses, goal);
    }

    /**
     * Does the single hypothesis {@code fact} imply {@code goal}? Reflexivity,
     * then the shared linear-bound kernel ({@link #discharge}, dense domain).
     * The one-fact specialization of {@link #discharge}.
     */
    public static boolean implies(SymExpr fact, SymExpr goal) {
        return fact.equals(goal) || discharge(java.util.List.of(fact), goal);
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

    /**
     * A built-in scalar base name (the kinds {@link #primitiveKindGate} bites on).
     * A refinement over one of these is provably disjoint from a struct/tuple; a
     * refinement over any other base ({@code _tuple}, {@code _record}, an inline
     * shape, an unresolved name) shares a kind with aggregates and is at worst
     * undecided against one — never a kind-clash {@code Failed}.
     */
    private static boolean isPrimitiveBase(String name) {
        return switch (name) {
            case "Int", "Decimal", "Bool", "Char", "String" -> true;
            default -> false;
        };
    }

    public static ProofResult satisfies(SymExpr value, Sort sort, Simplifier simplifier) {
        // Resolve a nominal struct reference to its definition so its shape is
        // checked. Terminates by the finite value even on a recursive type:
        // descent only follows members present in the runtime record.
        sort = resolveNominal(sort, simplifier.registry());
        // Parametric Stream contract (WAR(stream) §8.6): a Stream[T] is satisfied by a
        // stream value (a positional tuple) iff every element satisfies T. This closes
        // the no-lie hole where a COMPUTED stream's element type went unchecked (a
        // literal was element-checked at parse, an Iterate result was not). Other
        // parametric traits carry their type args but get NO invented invariant — only
        // Stream's contract ("a sequence of T") is known, so only it is checked.
        if (sort.typeArgs() != null && !sort.typeArgs().isEmpty() && isStreamSort(sort.name())) {
            return satisfiesStreamElements(value, sort.typeArgs().get(0), simplifier);
        }
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

    /** The Stream trait, bare ({@code Stream}) or linker-qualified ({@code …/Stream}). */
    private static boolean isStreamSort(String name) {
        return name != null && (name.equals("Stream") || name.endsWith("/Stream"));
    }

    /**
     * A {@code Stream[T]} is satisfied iff every element of the stream value (a
     * positional tuple) satisfies the element type {@code elem} — the parametric
     * contract that closes the §8.6 no-lie hole. A non-tuple value (a symbolic stream
     * / opaque source handle) can't be walked here, so it stays lenient — its own
     * typing governs. Fails on the first concrete mismatch; propagates residual if an
     * element is undecidable (deferring to the runtime claim check, where it is
     * concrete). Element checks reuse {@link #satisfies}, so the Int→Decimal embedding
     * is honored automatically.
     */
    private static ProofResult satisfiesStreamElements(SymExpr value, Sort elem, Simplifier simplifier) {
        if (!(simplifier.simplify(value) instanceof SymExpr.Record rec)) {
            return ProofResult.passed();
        }
        int idx = 0;
        SymExpr residual = null;
        for (SymExpr m : rec.members().values()) {
            ProofResult pr = satisfies(m, elem, simplifier);
            if (pr instanceof ProofResult.Failed) {
                return ProofResult.failed(
                        "Stream element " + idx + " (" + m + ") does not satisfy the declared "
                                + "element type " + elem);
            }
            if (pr instanceof ProofResult.Residual res) {
                residual = res.obligation();
            }
            idx++;
        }
        return residual == null ? ProofResult.passed() : ProofResult.residual(residual);
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
                || v instanceof SymExpr.Str || v instanceof SymExpr.Record;
        if (!concrete) {
            return null;
        }
        boolean ok = switch (name) {
            case "Int" -> v instanceof SymExpr.Lit;
            case "Decimal" -> v instanceof SymExpr.Dec || v instanceof SymExpr.Lit;
            case "Bool" -> v instanceof SymExpr.Bool;
            case "Char" -> v instanceof SymExpr.Chr;
            case "String" -> v instanceof SymExpr.Str;
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

    /**
     * Overload specificity — the single home for the "is A at least as specific
     * as B" ordering that dispatch selection, static call resolution, and
     * overload-overlap subsumption each need. A is at least as specific as B iff
     * they have the same arity and every A-parameter sort {@link #imply implies}
     * the corresponding B-parameter sort (a more-refined / narrower parameter is
     * more specific). Operates on already-compiled parameter {@link Sort}s so the
     * one relation serves the runtime ({@code core} {@code FunctionDecl}s, sorts
     * pre-compiled) and the compile passes (IR decls, sorts compiled by the
     * caller) alike.
     */
    /** Whether nominal type {@code sub} is a strict subtype of {@code base} (struct is-a, struct-implements-trait, sub-trait). */
    @FunctionalInterface
    public interface NominalSubtyping {
        boolean isSubtype(String sub, String base);
        /** The empty relation — no nominal subtyping known (structural/refinement reasoning only). */
        NominalSubtyping NONE = (sub, base) -> false;
    }

    public static boolean atLeastAsSpecific(java.util.List<Sort> a, java.util.List<Sort> b, Simplifier simplifier) {
        return atLeastAsSpecific(a, b, simplifier, NominalSubtyping.NONE);
    }

    /**
     * As {@link #atLeastAsSpecific(java.util.List, java.util.List, Simplifier)} but
     * a parameter position also counts as more specific when {@code a}'s nominal
     * type is a subtype of {@code b}'s via {@code subtyping} — a {@code Sub} param
     * dominates a {@code Base} param, and a struct param dominates a trait it
     * implements. {@link #imply} deliberately refuses these (a value's nominal
     * name is honest about itself only), so the is-a knowledge enters here, for
     * ordering only. A refined {@code b} still requires an {@link #imply} proof —
     * a nominal subtype does not license bypassing a predicate obligation.
     */
    public static boolean atLeastAsSpecific(java.util.List<Sort> a, java.util.List<Sort> b,
                                            Simplifier simplifier, NominalSubtyping subtyping) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Sort x = a.get(i);
            Sort y = b.get(i);
            if (imply(x, y, simplifier).isPassed()) continue;
            if (!y.isRefined() && subtyping.isSubtype(x.name(), y.name())) continue;
            return false;
        }
        return true;
    }

    /** A is STRICTLY more specific than B: A ⪰ B and not B ⪰ A. The dispatch tiebreak. */
    public static boolean strictlyMoreSpecific(java.util.List<Sort> a, java.util.List<Sort> b, Simplifier simplifier) {
        return strictlyMoreSpecific(a, b, simplifier, NominalSubtyping.NONE);
    }

    /** As {@link #strictlyMoreSpecific(java.util.List, java.util.List, Simplifier)} but honoring the nominal is-a chain. */
    public static boolean strictlyMoreSpecific(java.util.List<Sort> a, java.util.List<Sort> b,
                                               Simplifier simplifier, NominalSubtyping subtyping) {
        return atLeastAsSpecific(a, b, simplifier, subtyping) && !atLeastAsSpecific(b, a, simplifier, subtyping);
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
        // Union and refinement subsumption — peeled BEFORE the kind-mismatch
        // catch-all below. WAR(dependent-sorts) §5: imply's `Failed` must mean
        // PROVABLY DISJOINT, never "couldn't relate the sort kinds". A union is the
        // join of its branches; a refinement is its base narrowed by a predicate —
        // so a struct meeting `[Element|Leaf]`, or a refined struct meeting its own
        // base, is a subset relation, not a kind clash. (The old catch-all reported
        // both as Failed, the false-positive families the call-gate measurement
        // surfaced.)
        // tighter-union checked FIRST: `(A₁|…) ⊑ looser` iff every branch ⊑ looser,
        // which is complete whatever looser's shape — including a union looser (so
        // `(E|L) ⊑ (E|L)` proves via each branch, rather than the weaker
        // implies-some-branch test below returning Residual on an identical union).
        //
        // Coinductive guard for the ANONYMOUS union pair, mirroring implyStructural's
        // named-struct guard: a union carries no name, so a recursive union
        // (`T = K0|… ` with each `Ki` fielded by `T`) re-derives the identical
        // `(union ⊑ union)` obligation on every field descent. Without a back-edge
        // marker the branch × branch × field recursion is super-exponential in arity
        // (the recursive-union type-checking blowup, docs/recursive-union-typecheck-blowup.md);
        // marking the pair on entry and assuming it on revisit collapses it to the
        // finite set of distinct pairs. Sound for the same reason the struct guard is:
        // subsumption over equi-recursive sorts is a greatest fixed point, so the
        // back-edge holds, and every non-back-edge branch obligation is still checked.
        if (tighter.isUnion() || looser.isUnion()) {
            if (assumed.holds(tighter, looser)) {
                return ProofResult.passed();
            }
            Coinduction.Assumed next = assumed.assuming(tighter, looser);
            return tighter.isUnion()
                    ? implyUnionTighter(tighter, looser, simplifier, next)
                    : implyUnionLooser(tighter, looser, simplifier, next);
        }
        // A refined struct ⊆ its base struct: `[Countdown:@.n==k] ⊑ Countdown`
        // reduces to `Countdown ⊑ Countdown` (the predicate only narrows further).
        if (tighter.isRefined() && looser.isStructural()) {
            Sort tBase = resolveNominal(Sort.of(tighter.name()), simplifier.registry());
            if (tBase.isStructural()) {
                return imply(tBase, looser, simplifier, assumed);
            }
            // A non-resolvable structural-ish base (`_tuple`/`_record`/an inline
            // shape) shares a kind with the struct — not provably disjoint, but its
            // member sorts aren't recoverable from the predicate alone → undecided.
            // Only a refined PRIMITIVE base is provably not a struct (catch-all).
            if (!isPrimitiveBase(tighter.name())) {
                return ProofResult.residual(SymExpr.var(tighter + " ⊑ " + looser));
            }
        }
        // A struct meeting a refined struct: fit the base shape, then the predicate
        // is a residual obligation (the value must still discharge it).
        if (tighter.isStructural() && looser.isRefined()) {
            Sort lBase = resolveNominal(Sort.of(looser.name()), simplifier.registry());
            if (lBase.isStructural()) {
                ProofResult base = imply(tighter, lBase, simplifier, assumed);
                return base.isPassed() ? ProofResult.residual(looser.predicate()) : base;
            }
            if (!isPrimitiveBase(looser.name())) {
                return ProofResult.residual(looser.predicate());
            }
            // looser's base is a primitive → a struct is provably not a refined
            // primitive; fall through to the catch-all (a sound Failed).
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

    /**
     * {@code tighter ⊑ (B₁ | … | Bₙ)} — passes if {@code tighter} implies some
     * branch. Implying a single branch is <em>sufficient</em> but not
     * <em>necessary</em> (a tighter sort can span branches, e.g. {@code [Int:@>=0]}
     * vs {@code [Int:0] | [Int:@>0]}), so the no-branch-matched case is
     * {@link ProofResult.Residual}, never {@code Failed} — abstain, never a false
     * reject. This upholds the invariant that {@code Failed} ⟺ provably disjoint.
     */
    private static ProofResult implyUnionLooser(Sort tighter, Sort looser, Simplifier simplifier,
                                                Coinduction.Assumed assumed) {
        ProofResult firstResidual = null;
        for (Sort branch : looser.unionBranches()) {
            ProofResult r = imply(tighter, branch, simplifier, assumed);
            if (r.isPassed()) {
                return ProofResult.passed();
            }
            if (firstResidual == null && r instanceof ProofResult.Residual) {
                firstResidual = r;
            }
        }
        return firstResidual != null
                ? firstResidual
                : ProofResult.residual(SymExpr.var(tighter + " ∈ " + looser));
    }

    /**
     * {@code (A₁ | … | Aₙ) ⊑ looser} — holds iff <em>every</em> branch implies
     * {@code looser}. A branch that provably fails makes the whole union fail
     * (the failing branch is a witness); otherwise residual if any branch is
     * undecided, else passed.
     */
    private static ProofResult implyUnionTighter(Sort tighter, Sort looser, Simplifier simplifier,
                                                 Coinduction.Assumed assumed) {
        boolean anyResidual = false;
        for (Sort branch : tighter.unionBranches()) {
            ProofResult r = imply(branch, looser, simplifier, assumed);
            if (r instanceof ProofResult.Failed) {
                return r;
            }
            if (r instanceof ProofResult.Residual) {
                anyResidual = true;
            }
        }
        return anyResidual ? ProofResult.residual(SymExpr.var(tighter + " ⊑ " + looser))
                : ProofResult.passed();
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
        // Both comparisons must constrain the SAME pure Self-rooted subject —
        // Self itself (`[Int:@>0]`) or a field-projection chain off it
        // (`[Point:@.x>0]`, `[MyType:@.a.b>0]`). A field projection is a pure,
        // deterministic read, so `@.x` denotes ONE value across both predicates:
        // the exact property that made bare-Self bound reasoning sound, now
        // extended to projections. Different subjects (`@.x` vs `@.y`) fail the
        // equality test and stay Residual — never a false Failed. The bound
        // kernel already treats a projection as an opaque linear atom (it keys
        // its coeff map on arbitrary SymExpr), so no kernel change is needed —
        // only this gate widened from "is Self" to "same Self-rooted subject".
        if (!tl.equals(ll) || !isSelfRootedSubject(tl)) {
            return ProofResult.residual(looser);
        }
        java.math.BigDecimal ta = asNumeric(tr);
        java.math.BigDecimal la = asNumeric(lr);
        if (ta == null || la == null) {
            return ProofResult.residual(looser);
        }
        // The positive verdict comes from the one shared kernel (dense domain):
        // does the tighter predicate, as a hypothesis, discharge the looser as a
        // goal? This subsumes and strengthens the former hand-rolled order table
        // (it also proves e.g. @>3 ⟹ @!=0). A non-discharge on this
        // constant-bounded same-subject shape is a genuine non-implication →
        // Failed; shapes the guards above reject stay Residual (undecided),
        // preserving the Failed ⟺ provably-not-implied distinction callers rely on.
        boolean holds = BoundAnalysis.discharge(
                BoundAnalysis.Domain.DECIMAL, java.util.List.of(tighter), looser);
        return holds
                ? ProofResult.passed()
                : ProofResult.failed(
                        tl + " " + top + " " + ta + " does not imply " + ll + " " + lop + " " + la);
    }

    /**
     * A pure Self-rooted subject term: {@code @} itself, or a field-projection
     * chain {@code @.a.b…} whose root is {@code @}. These are the terms whose
     * value is fully determined by the value under refinement, so the same term
     * appearing in two predicates denotes the same value — the soundness
     * premise the arithmetic-implication gate rests on.
     */
    private static boolean isSelfRootedSubject(SymExpr e) {
        return switch (e) {
            case SymExpr.Self ignored -> true;
            case SymExpr.FieldAccess fa -> isSelfRootedSubject(fa.base());
            default -> false;
        };
    }

    /**
     * Numeric constant extraction for the arithmetic-implication guard, over
     * BigDecimal (integers convert exactly). Only gates which predicate shapes
     * are eligible for a Passed/Failed verdict; the verdict itself is the shared
     * {@link BoundAnalysis} kernel's.
     */
    private static java.math.BigDecimal asNumeric(SymExpr e) {
        if (e instanceof SymExpr.Lit l) return java.math.BigDecimal.valueOf(l.value());
        if (e instanceof SymExpr.Frac f && f.denom() == 1) return java.math.BigDecimal.valueOf(f.num());
        if (e instanceof SymExpr.Dec d) return d.value();
        return null;
    }
}
