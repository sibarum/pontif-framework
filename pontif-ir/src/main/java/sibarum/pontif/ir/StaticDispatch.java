package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.List;

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
        if (overloads.isEmpty()) {
            return new Result.Unresolved("no overloads registered");
        }

        List<IrStmt.FunctionDecl> definite = new ArrayList<>();
        boolean anyResidual = false;
        for (IrStmt.FunctionDecl ov : overloads) {
            MatchStatus s = matchStatus(ov, argNarrowings);
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
        IrStmt.FunctionDecl winner = pickMostSpecific(definite);
        if (winner != null) {
            return new Result.Resolved(winner);
        }
        return new Result.Unresolved(
                "multiple equally-specific definite matches; post-D.1 this should be unreachable");
    }

    // --- Internals ---------------------------------------------------------

    private enum MatchStatus { PASSED, RESIDUAL, FAILED }

    /**
     * Per-overload match status — AND of the per-param implication
     * results. Arity mismatch is {@link MatchStatus#FAILED}. Null arg
     * narrowings degrade the whole result to {@link MatchStatus#RESIDUAL}.
     */
    private static MatchStatus matchStatus(IrStmt.FunctionDecl ov, List<IrSort> args) {
        if (ov.params().size() != args.size()) return MatchStatus.FAILED;
        Simplifier simp = new Simplifier(List.of());
        MatchStatus overall = MatchStatus.PASSED;
        for (int i = 0; i < args.size(); i++) {
            IrSort argNarrowing = args.get(i);
            if (argNarrowing == null) {
                overall = MatchStatus.RESIDUAL;
                continue;
            }
            try {
                Sort argSort = IrCompiler.compileSort(argNarrowing);
                Sort paramSort = IrCompiler.compileSort(ov.params().get(i).sort());
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
     * Returns the single strictly-most-specific overload from
     * {@code candidates}, or {@code null} if no unique most-specific
     * overload exists (multiple incomparable, or ties).
     */
    private static IrStmt.FunctionDecl pickMostSpecific(List<IrStmt.FunctionDecl> candidates) {
        List<IrStmt.FunctionDecl> undominated = new ArrayList<>();
        for (IrStmt.FunctionDecl c : candidates) {
            boolean dominated = false;
            for (IrStmt.FunctionDecl other : candidates) {
                if (other == c) continue;
                if (isStrictlyMoreSpecific(other, c)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) undominated.add(c);
        }
        return undominated.size() == 1 ? undominated.get(0) : null;
    }

    private static boolean isStrictlyMoreSpecific(IrStmt.FunctionDecl a, IrStmt.FunctionDecl b) {
        if (!isAtLeastAsSpecific(a, b)) return false;
        return !isAtLeastAsSpecific(b, a);
    }

    private static boolean isAtLeastAsSpecific(IrStmt.FunctionDecl a, IrStmt.FunctionDecl b) {
        if (a.params().size() != b.params().size()) return false;
        Simplifier simp = new Simplifier(List.of());
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
