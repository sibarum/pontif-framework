package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

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
            java.util.Map<String, Sort> registry) {
        boolean anyArityMatch = false;
        boolean anyPassed = false;
        boolean anyResidual = false;
        for (IrStmt.FunctionDecl ov : overloads) {
            if (ov.params().size() != argNarrowings.size()) {
                continue;  // arity mismatch — not a refinement failure; abstain below
            }
            anyArityMatch = true;
            switch (matchStatus(ov, argNarrowings, registry)) {
                case PASSED -> anyPassed = true;
                case RESIDUAL -> anyResidual = true;
                case FAILED -> { /* refinement-disjoint */ }
            }
        }
        if (!anyArityMatch) return Verdict.RESIDUAL;
        if (anyPassed) return Verdict.PASSED;
        if (anyResidual) return Verdict.RESIDUAL;
        return Verdict.FAILED;
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
        if (!isAtLeastAsSpecific(a, b, registry)) return false;
        return !isAtLeastAsSpecific(b, a, registry);
    }

    private static boolean isAtLeastAsSpecific(IrStmt.FunctionDecl a, IrStmt.FunctionDecl b,
                                               java.util.Map<String, Sort> registry) {
        if (a.params().size() != b.params().size()) return false;
        Simplifier simp = new Simplifier(List.of()).withRegistry(registry);
        for (int i = 0; i < a.params().size(); i++) {
            try {
                Sort aSort = IrCompiler.compileSort(a.params().get(i).sort());
                Sort bSort = IrCompiler.compileSort(b.params().get(i).sort());
                if (!Refinements.imply(aSort, bSort, simp).isPassed()) return false;
            } catch (CompileException ce) {
                return false;
            }
        }
        return true;
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
