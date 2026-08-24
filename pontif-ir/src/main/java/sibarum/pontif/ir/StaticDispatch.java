package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.predicates.PredicateArithmetic;
import sibarum.pontif.predicates.SatResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compile-time overload resolution against argument <em>narrowings</em>
 * (the symbolic claims about each arg's value set, sourced from
 * {@link NarrowingInference}). The static analog of
 * {@link sibarum.pontif.core.symbolic.DispatchTable#resolve}, which
 * operates on runtime values.
 *
 * <p>Algorithm — for each overload candidate:
 * <ol>
 *   <li>Per param position, ask {@link Refinements#imply
 *       Refinements.imply(arg_narrowing, param_sort)}. Three outcomes:
 *     <ul>
 *       <li>{@code Passed} — arg's narrowing is provably a subset of
 *           the param sort; this position is a definite match.</li>
 *       <li>{@code Failed} — provably not a subset; the overload is
 *           definitively excluded.</li>
 *       <li>{@code Residual} — kernel couldn't decide.</li>
 *     </ul>
 *   </li>
 *   <li>AND the per-param results: an overload is a <em>definite</em>
 *       match iff every position is {@code Passed}; <em>excluded</em>
 *       if any position is {@code Failed}; otherwise {@em residual}.</li>
 *   <li>If exactly one definite match remains, return it. If multiple,
 *       apply most-specific resolution (post-D.1 either picks a winner
 *       or signals a residual). Otherwise the call is
 *       {@link Result.Unresolved Unresolved} and consumers fall back
 *       to declared-sort behavior.</li>
 * </ol>
 *
 * <p>Null entries in {@code argNarrowings} are treated as <em>unknown</em>
 * — the position is marked {@code Residual} for every overload, so no
 * definite match can be claimed through them. Callers preferring a
 * declared-fallback should substitute the declared sort before
 * calling.
 */
public final class StaticDispatch {

    private StaticDispatch() {}

    /**
     * Resolves a call site against its candidate overloads given the
     * static narrowings of each argument.
     */
    public static Result resolve(
            List<IrStmt.FunctionDecl> overloads,
            List<IrSort> argNarrowings) {
        return resolve(overloads, argNarrowings, java.util.Map.of());
    }

    /**
     * As {@link #resolve(List, List)} but with a nominal-struct registry
     * (name → structural {@link Sort}) so subsumption between struct-typed
     * params/args is decided structurally rather than by treating a
     * by-reference struct sort as unconstrained.
     */
    public static Result resolve(
            List<IrStmt.FunctionDecl> overloads,
            List<IrSort> argNarrowings,
            java.util.Map<String, Sort> registry) {
        if (overloads.isEmpty()) {
            return new Result.Unresolved("no overloads registered");
        }

        List<IrStmt.FunctionDecl> definite = new ArrayList<>();
        boolean anyResidual = false;
        for (IrStmt.FunctionDecl ov : overloads) {
            MatchStatus s = matchStatus(ov, argNarrowings, registry);
            switch (s) {
                case PASSED -> definite.add(ov);
                case RESIDUAL -> anyResidual = true;
                case FAILED -> { /* exclude */ }
            }
        }

        if (definite.isEmpty()) {
            return new Result.Unresolved(
                    anyResidual
                            ? "no definite static match (kernel undecided)"
                            : "no overload matches the arg narrowings");
        }
        if (definite.size() == 1) {
            return new Result.Resolved(definite.get(0));
        }

        // Multiple definite matches — pick most-specific.
        IrStmt.FunctionDecl winner = pickMostSpecific(definite, registry);
        if (winner != null) {
            return new Result.Resolved(winner);
        }
        return new Result.Unresolved(
                "multiple equally-specific definite matches; post-D.1 this should be unreachable");
    }

    /**
     * The three-way per-<em>call</em> verdict, exposed additively alongside the
     * two-valued {@link Result} (WAR(dependent-sorts), slice 2 step (a)). Where
     * {@link #resolve} answers "which overload" (Resolved/Unresolved, the
     * routing question), {@code classify} answers "does this call provably
     * route at all" — the question the call gate asks:
     * <ul>
     *   <li>{@link Verdict#PASSED} — at least one overload is a definite static
     *       match (the call provably routes).</li>
     *   <li>{@link Verdict#FAILED} — <em>every</em> overload is provably excluded
     *       (a provable failure: the gate's compile-error case).</li>
     *   <li>{@link Verdict#RESIDUAL} — no definite match, but the kernel couldn't
     *       exclude every overload (undecided — the gate must prove from in-scope
     *       hypotheses or error).</li>
     * </ul>
     *
     * <p>This deliberately does <em>not</em> disturb {@link #resolve}'s
     * Resolved/Unresolved consumer ({@code NarrowingInference.inferCall}):
     * {@code resolve} collapses both FAILED and RESIDUAL into {@code Unresolved}
     * (defer to declared semantics); {@code classify} keeps them apart so the gate
     * can reject a provable FAILED while abstaining on a RESIDUAL.
     */
    public enum Verdict { PASSED, RESIDUAL, FAILED }

    /** As {@link #classify(List, List, java.util.Map)} with an empty registry. */
    public static Verdict classify(
            List<IrStmt.FunctionDecl> overloads,
            List<IrSort> argNarrowings) {
        return classify(overloads, argNarrowings, java.util.Map.of());
    }

    /** As {@link #classify(List, List, java.util.Map, java.util.Map)} with no trait-impl view. */
    public static Verdict classify(
            List<IrStmt.FunctionDecl> overloads,
            List<IrSort> argNarrowings,
            java.util.Map<String, Sort> registry) {
        return classify(overloads, argNarrowings, registry, java.util.Map.of());
    }

    /** As {@link #classify(List, List, java.util.Map, java.util.Map, java.util.Map)} with no
     *  struct-ancestry view (struct inheritance not consulted). */
    public static Verdict classify(
            List<IrStmt.FunctionDecl> overloads,
            List<IrSort> argNarrowings,
            java.util.Map<String, Sort> registry,
            java.util.Map<String, java.util.Set<String>> traitImpls) {
        return classify(overloads, argNarrowings, registry, traitImpls, java.util.Map.of());
    }

    /**
     * The call-level three-way verdict (see {@link Verdict}). Reuses the same
     * per-overload {@link #matchStatus} {@link #resolve} uses, then combines:
     * any PASSED overload ⇒ PASSED; else any RESIDUAL ⇒ RESIDUAL; else (every
     * overload provably FAILED) ⇒ FAILED.
     *
     * <p><b>Arity is not the call gate's jurisdiction.</b> Only overloads whose
     * arity matches the call are weighed; an {@link Verdict#FAILED} therefore
     * means "an arity-matching overload exists but its parameter <em>refinement</em>
     * is provably violated" — never "no overload has the right number of params".
     * A pure arity mismatch (wrong arg count, or a name bound to a value rather
     * than a function — a metareference/lambda invocation lowered to a 0-param
     * let) yields {@link Verdict#RESIDUAL}, so the gate abstains and the existing
     * dispatch/arity diagnostics ("No matching method/function") own that error.
     * An empty overload list likewise ⇒ RESIDUAL (a name unknown to static
     * dispatch — possibly a builtin). Most-specific resolution is irrelevant to a
     * yes/no routing question, so it is not consulted here.
     */
    public static Verdict classify(
            List<IrStmt.FunctionDecl> overloads,
            List<IrSort> argNarrowings,
            java.util.Map<String, Sort> registry,
            java.util.Map<String, java.util.Set<String>> traitImpls,
            java.util.Map<String, java.util.Set<String>> structAncestors) {
        boolean anyArityMatch = false;
        boolean anyMatch = false;
        boolean anyUndecided = false;
        for (IrStmt.FunctionDecl ov : overloads) {
            if (ov.params().size() != argNarrowings.size()) {
                continue;  // arity mismatch — not a refinement failure; abstain below
            }
            anyArityMatch = true;
            switch (gateFit(ov, argNarrowings, registry, traitImpls, structAncestors)) {
                case MATCHES -> anyMatch = true;
                case UNDECIDED -> anyUndecided = true;
                case EXCLUDED -> { /* arg provably disjoint from this overload */ }
            }
        }
        if (!anyArityMatch) return Verdict.RESIDUAL;
        if (anyMatch) return Verdict.PASSED;
        if (anyUndecided) return Verdict.RESIDUAL;
        return Verdict.FAILED;  // every arity-matching overload provably excluded
    }

    /**
     * Per-overload fit for the call <em>gate</em> — distinct from {@link #matchStatus}
     * (which {@link #resolve} uses for dispatch). The difference is the exclusion
     * criterion: the gate excludes an overload only when an argument is <em>provably
     * disjoint</em> from a parameter (the call can't route here), never on mere
     * subset-failure. This is what keeps a hypothesis-bounded range arg honest:
     * {@code [Int:@>=0]} vs {@code [Int:@>0]} is not-a-subset but <em>overlaps</em>, so
     * it is {@link OverloadFit#UNDECIDED} (abstain), not excluded — only a genuine
     * empty intersection (a singleton {@code -3} vs {@code @>0}) excludes. So the gate's
     * FAILED verdict means PROVABLY MISROUTES, the no-lie boundary.
     */
    private enum OverloadFit { MATCHES, EXCLUDED, UNDECIDED }

    private static OverloadFit gateFit(IrStmt.FunctionDecl ov, List<IrSort> args,
                                       java.util.Map<String, Sort> registry,
                                       java.util.Map<String, java.util.Set<String>> traitImpls,
                                       java.util.Map<String, java.util.Set<String>> structAncestors) {
        Simplifier simp = new Simplifier(List.of()).withRegistry(registry);
        Sort[] argSorts = new Sort[args.size()];
        Map<String, SymExpr> siblingValues = new HashMap<>();
        for (int j = 0; j < args.size(); j++) {
            if (args.get(j) == null) continue;
            try {
                argSorts[j] = IrCompiler.compileSort(args.get(j));
            } catch (CompileException ce) {
                continue;
            }
            java.util.Optional<SymExpr> value = Refinements.uniqueValue(argSorts[j]);
            if (value.isPresent()) {
                siblingValues.put(ov.params().get(j).name(), value.get());
            }
        }
        boolean allPassed = true;
        for (int i = 0; i < args.size(); i++) {
            Sort argSort = argSorts[i];
            if (argSort == null) {  // unknown arg — this param can't pass, can't exclude
                allPassed = false;
                continue;
            }
            try {
                Sort paramSort = substituteSiblings(
                        IrCompiler.compileSort(ov.params().get(i).sort()), siblingValues);
                ProofResult pr = Refinements.imply(argSort, paramSort, simp);
                if (pr instanceof ProofResult.Passed) {
                    continue;
                }
                // Struct inheritance: a `Sub` arg is-a an unrefined `Base` param. Refinements is a
                // refinement engine and can't see the nominal base chain (imply is Failed here), so
                // consult the ancestry view. A widen to the bare base is total (no predicate to satisfy),
                // so this is a genuine MATCHES, not merely not-disjoint — mirrors Assignability's widen.
                if (isStructBaseWiden(argSort, paramSort, structAncestors)) {
                    continue;
                }
                // The same widen, one step further: an unrefined TRAIT param that the arg's type — or
                // any is-a ancestor of it — implements. Also total (a bare trait carries no predicate),
                // so also a genuine MATCHES. Assignability already reaches this by recursing on the
                // nominal base; this is the same conclusion for the gate's Sort-level view.
                if (!paramSort.isRefined()
                        && satisfiesTrait(argSort.name(), paramSort.name(), traitImpls, structAncestors)) {
                    continue;
                }
                allPassed = false;
                if (provablyDisjoint(argSort, paramSort, simp, traitImpls, structAncestors)) {
                    return OverloadFit.EXCLUDED;  // arg ∩ this param = ∅ → can't route here
                }
            } catch (CompileException ce) {
                allPassed = false;
            }
        }
        return allPassed ? OverloadFit.MATCHES : OverloadFit.UNDECIDED;
    }

    /**
     * Whether {@code arg} is PROVABLY disjoint from {@code param} (empty
     * intersection — no value satisfies both). For two {@code Int} refinements this
     * is decided by the integer engine: a value variable constrained by <em>both</em>
     * predicates yields an empty {@link BoundAnalysis} interval iff they contradict
     * (so {@code @==-3} ∩ {@code @>0} = ∅, but {@code @>=0} ∩ {@code @>0} ≠ ∅). For
     * other sort kinds it defers to {@link Refinements#imply}'s {@code Failed} —
     * which the slice-2 hardening already made mean provably-disjoint for
     * struct/union/kind pairings. Sound and conservative: an undecidable case yields
     * {@code false} (not disjoint → the gate abstains), never a false exclusion.
     *
     * <p><b>Trait satisfaction (roadmap §4.3).</b> {@link Refinements} is a refinement engine — it
     * cannot see that a struct satisfies a trait, so {@code imply(Parabola, Curve2D)} is {@code Failed}
     * even though {@code Parabola is-a Curve2D}. Reading that as disjoint would wrongly exclude a
     * satisfying struct from its trait parameter. So the nominal decider (the {@code traitImpls} view)
     * is consulted first: an arg whose type satisfies the param trait is <em>not</em> disjoint. A
     * struct that does <em>not</em> satisfy the trait still falls through to the {@code Failed} check
     * and is correctly excluded — a genuine misroute the gate should catch.
     */
    private static boolean provablyDisjoint(Sort arg, Sort param, Simplifier simp,
                                            java.util.Map<String, java.util.Set<String>> traitImpls,
                                            java.util.Map<String, java.util.Set<String>> structAncestors) {
        // Refined scalars over the same base: decide disjointness with the shared
        // predicate kernel (Int / Decimal / Bool) — the same engine OverloadOverlap
        // uses — instead of the legacy Int-only Interval path. A definite verdict
        // returns; an out-of-fragment Unknown falls through to the nominal /
        // implication reasoning below.
        if (arg.isRefined() && param.isRefined()
                && arg.name() != null && arg.name().equals(param.name())) {
            SatResult sat = PredicateArithmetic.satisfiable(
                    SymExpr.and(arg.predicate(), param.predicate()), Sort.of(arg.name()));
            if (sat instanceof SatResult.No) return true;    // provably disjoint
            if (sat instanceof SatResult.Yes) return false;  // provably overlaps
            // Unknown → fall through.
        }
        // The arg's type satisfies the param trait (nominal is-a) → not disjoint. Refinements can't
        // see this relation, so its Failed would otherwise mis-read a satisfying struct as excluded.
        if (satisfiesTrait(arg.name(), param.name(), traitImpls, structAncestors)) {
            return false;
        }
        // The arg's struct is-a the param's struct through the inheritance chain (`Sub:Base`) → not
        // disjoint, for the same reason: Refinements can't see the nominal base chain. (A refined base
        // param stays UNDECIDED rather than excluded — the predicate may or may not hold, so abstain.)
        if (isStructBaseWiden(arg, param, structAncestors)) {
            return false;
        }
        return Refinements.imply(arg, param, simp) instanceof ProofResult.Failed;
    }

    /**
     * Whether {@code arg}'s struct is-a {@code param}'s struct through the inheritance chain and the
     * {@code param} carries no refinement predicate — an unconditional widen (`Exp is-a BiOp`), the call
     * gate's counterpart of {@link sibarum.pontif.types.Assignability}'s nominal-base widen. Compared by
     * <em>bare</em> name (the ancestry view is bare-keyed). A refined param is excluded here so its
     * predicate obligation is not silently dropped — that case falls through to the abstain path.
     */
    /**
     * Whether the type named {@code argName} satisfies the trait named {@code paramName} — by its own
     * impl, or by an impl on any of its is-a ancestors.
     *
     * <p>Both halves of this were already here and neither was composed with the other: the
     * {@code traitImpls} view answers "does THIS type implement it" (walking the trait-extends chain),
     * and {@code structAncestors} answers "what does this type inherit from". An {@code assign trait
     * Base:T} impl is inherited by every descendant of {@code Base} — which is exactly what
     * {@code structAncestors}' own contract says the gate must respect — so asking only the first
     * question read a {@code Sub} argument as provably disjoint from a {@code T} parameter. That is a
     * false disjointness claim, and the gate's FAILED verdict is supposed to mean provably-misroutes.
     * {@link sibarum.pontif.types.Assignability#isA} reaches the same conclusion by recursing on the
     * nominal base; this is that conclusion for the gate's compiled-{@link Sort} view.
     *
     * <p>Bare-tolerant on both sides: {@code structAncestors} is bare-keyed while {@code traitImpls} may
     * be qualified by the linker, so names are compared unqualified as well as as-written.
     */
    private static boolean satisfiesTrait(String argName, String paramName,
            java.util.Map<String, java.util.Set<String>> traitImpls,
            java.util.Map<String, java.util.Set<String>> structAncestors) {
        if (argName == null || paramName == null) return false;
        if (implementsTrait(argName, paramName, traitImpls)) return true;
        for (String ancestor : structAncestors.getOrDefault(bareName(argName), java.util.Set.of())) {
            if (implementsTrait(ancestor, paramName, traitImpls)) return true;
        }
        return false;
    }

    /** One {@code traitImpls} membership test, tolerating linker qualification on either side. */
    private static boolean implementsTrait(String typeName, String traitName,
            java.util.Map<String, java.util.Set<String>> traitImpls) {
        String bareTrait = bareName(traitName);
        for (java.util.Map.Entry<String, java.util.Set<String>> e : traitImpls.entrySet()) {
            if (!bareName(e.getKey()).equals(bareName(typeName))) continue;
            for (String t : e.getValue()) {
                if (bareName(t).equals(bareTrait)) return true;
            }
        }
        return false;
    }

    private static boolean isStructBaseWiden(Sort arg, Sort param,
            java.util.Map<String, java.util.Set<String>> structAncestors) {
        if (arg == null || param == null || param.isRefined()) return false;
        String argBare = bareName(arg.name());
        String paramBare = bareName(param.name());
        if (argBare == null || paramBare == null) return false;
        return structAncestors.getOrDefault(argBare, java.util.Set.of()).contains(paramBare);
    }

    private static String bareName(String name) {
        if (name == null) return null;
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }


    // --- Internals ---------------------------------------------------------

    private enum MatchStatus { PASSED, RESIDUAL, FAILED }

    /**
     * Per-overload match status — AND of the per-param implication
     * results. Arity mismatch is {@link MatchStatus#FAILED}. Null arg
     * narrowings degrade the whole result to {@link MatchStatus#RESIDUAL}.
     */
    private static MatchStatus matchStatus(IrStmt.FunctionDecl ov, List<IrSort> args,
                                           java.util.Map<String, Sort> registry) {
        if (ov.params().size() != args.size()) return MatchStatus.FAILED;
        Simplifier simp = new Simplifier(List.of()).withRegistry(registry);

        // Compile the arg narrowings once, and collect each sibling parameter's
        // concrete value when its argument pins one (a singleton `[Int:@==5]`).
        // WAR(dependent-sorts) slice 2 (b): these bindings are substituted into a
        // dependent param sort's predicate BEFORE the implication, so `g`'s
        // `[Int:@<x]` becomes `[Int:@<5]` and a provable miss FAILS rather than
        // staying residual. An arg that's null, uncompilable, or non-singleton
        // contributes no binding — the dependent sort then stays residual (we
        // never invent a value), which keeps the verdict honest.
        Sort[] argSorts = new Sort[args.size()];
        Map<String, SymExpr> siblingValues = new HashMap<>();
        for (int j = 0; j < args.size(); j++) {
            if (args.get(j) == null) continue;
            try {
                argSorts[j] = IrCompiler.compileSort(args.get(j));
            } catch (CompileException ce) {
                continue;
            }
            java.util.Optional<SymExpr> value = Refinements.uniqueValue(argSorts[j]);
            if (value.isPresent()) {
                siblingValues.put(ov.params().get(j).name(), value.get());
            }
        }

        MatchStatus overall = MatchStatus.PASSED;
        for (int i = 0; i < args.size(); i++) {
            Sort argSort = argSorts[i];
            if (argSort == null) {  // null narrowing or a narrowing that didn't compile
                overall = MatchStatus.RESIDUAL;
                continue;
            }
            try {
                Sort paramSort = substituteSiblings(
                        IrCompiler.compileSort(ov.params().get(i).sort()), siblingValues);
                ProofResult pr = Refinements.imply(argSort, paramSort, simp);
                if (pr instanceof ProofResult.Failed) return MatchStatus.FAILED;
                if (pr instanceof ProofResult.Residual) overall = MatchStatus.RESIDUAL;
            } catch (CompileException ce) {
                overall = MatchStatus.RESIDUAL;
            }
        }
        return overall;
    }

    /**
     * Substitutes sibling-parameter values into a dependent (refined) param
     * sort's predicate — {@code [Int:@<x]} with {@code x↦5} becomes
     * {@code [Int:@<5]}. {@code @} (Self) is untouched; only sibling-name
     * {@code Var}s are replaced. Non-refined sorts, and the common case of no
     * bound siblings, return unchanged. A receiver-relative {@code [Int:@<this.n]}
     * substitutes when {@code this} is bound to a record value (the
     * {@code FieldAccess} then simplifies during the implication).
     */
    private static Sort substituteSiblings(Sort paramSort, Map<String, SymExpr> values) {
        if (values.isEmpty() || !paramSort.isRefined()) {
            return paramSort;
        }
        return Sort.refined(paramSort.name(), Substitute.apply(paramSort.predicate(), values));
    }

    /**
     * Returns the single strictly-most-specific overload from
     * {@code candidates}, or {@code null} if no unique most-specific
     * overload exists (multiple incomparable, or ties).
     */
    private static IrStmt.FunctionDecl pickMostSpecific(List<IrStmt.FunctionDecl> candidates,
                                                        java.util.Map<String, Sort> registry) {
        List<IrStmt.FunctionDecl> undominated = new ArrayList<>();
        for (IrStmt.FunctionDecl c : candidates) {
            boolean dominated = false;
            for (IrStmt.FunctionDecl other : candidates) {
                if (other == c) continue;
                if (isStrictlyMoreSpecific(other, c, registry)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) undominated.add(c);
        }
        return undominated.size() == 1 ? undominated.get(0) : null;
    }

    private static boolean isStrictlyMoreSpecific(IrStmt.FunctionDecl a, IrStmt.FunctionDecl b,
                                                  java.util.Map<String, Sort> registry) {
        List<Sort> as = compiledParamSorts(a);
        List<Sort> bs = compiledParamSorts(b);
        // An uncompilable parameter leaves the overload incomparable (as before).
        if (as == null || bs == null) return false;
        Simplifier simp = new Simplifier(List.of()).withRegistry(registry);
        return Refinements.strictlyMoreSpecific(as, bs, simp);
    }

    /** Compiles a decl's parameter sorts, or null if any is outside the compilable fragment. */
    private static List<Sort> compiledParamSorts(IrStmt.FunctionDecl decl) {
        List<Sort> sorts = new ArrayList<>(decl.params().size());
        try {
            for (IrParam p : decl.params()) sorts.add(IrCompiler.compileSort(p.sort()));
        } catch (CompileException ce) {
            return null;
        }
        return sorts;
    }

    // --- Result type -------------------------------------------------------

    /**
     * Outcome of static dispatch. Two-valued: {@link Resolved} when a
     * single overload is statically picked; {@link Unresolved} when
     * the kernel / narrowings can't decide. Consumers fall back to
     * declared semantics on {@link Unresolved}.
     */
    public sealed interface Result {

        record Resolved(IrStmt.FunctionDecl decl) implements Result {
            /** Convenience: the resolved overload's declared return sort. */
            public IrSort returnSort() { return decl.returnSort(); }
        }

        record Unresolved(String reason) implements Result {}
    }
}
