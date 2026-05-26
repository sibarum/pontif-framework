package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.predicates.PredicateArithmetic;
import sibarum.pontif.predicates.SatResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compile-time check: do any two overloads of the same function name
 * share a value? Catches ambiguity at module-compile time so runtime
 * dispatch ambiguity ({@link sibarum.pontif.core.symbolic.DispatchResult.Ambiguous})
 * becomes unreachable in practice for overloads the kernel can decide.
 *
 * <p>Algorithm — pairwise per function name:
 * <ol>
 *   <li><b>Different arities</b> are automatically disjoint.</li>
 *   <li>For matching arities, check each parameter position for
 *       overlap. The two overloads overlap iff <em>every</em> position
 *       overlaps; they are disjoint iff <em>any</em> position is
 *       provably disjoint.</li>
 *   <li>For each parameter position:
 *     <ul>
 *       <li>Sorts with different base names (after alias resolution) are
 *           disjoint. {@code Int} vs {@code Bool}, {@code Point} vs
 *           {@code Banana}, {@code [Int:…]} vs {@code [Bool:…]} — all
 *           disjoint.</li>
 *       <li>Both refined over the same base: ask the predicate kernel
 *           whether {@code pred_A ∧ pred_B} is satisfiable. Unknown
 *           when the kernel can't decide (e.g., struct-refined params —
 *           the kernel is Int-only today).</li>
 *       <li>Same base, at least one bare: the bare set contains the
 *           refined as a subset; they overlap.</li>
 *       <li>Function, Trait, Union, Intersection: Unknown (these have
 *           more nuanced overlap semantics — trait-vs-struct overlaps
 *           on values whose concrete type satisfies the trait, etc.).
 *           Conservative: don't claim disjointness without proof.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p><b>Subsumption escape hatch.</b> Provable overlap is only an
 * error when neither overload strictly implies the other. The pattern
 * "catch-all + more-specific specialization" — e.g.,
 * {@code handle(x:Int)} together with {@code handle(x:[Int:@>0])} —
 * overlaps on positive ints, but runtime dispatch picks the more
 * specific overload via {@code isStrictlyMoreSpecific}. That's
 * unambiguous, so we accept it. Only irreducible overlap — where
 * neither overload subsumes the other — is rejected.
 *
 * <p>Policy: <b>Unknown is accepted silently</b>. We only fail on
 * provable irreducible overlap. Unknown overloads continue to dispatch
 * at runtime (with the existing ambiguity check as the safety net).
 */
public final class OverloadOverlap {

    private OverloadOverlap() {}

    /**
     * Checks every pair of same-name overloads in {@code module} for
     * provable overlap. Throws {@link CompileException} on the first
     * overlap found.
     *
     * <p>Expects the module to be post-{@link AliasResolver} so base
     * names are canonical.
     */
    public static void check(IrModule module) throws CompileException {
        Map<String, List<IrStmt.FunctionDecl>> byName = collectOverloads(module);
        for (Map.Entry<String, List<IrStmt.FunctionDecl>> entry : byName.entrySet()) {
            List<IrStmt.FunctionDecl> overloads = entry.getValue();
            for (int i = 0; i < overloads.size(); i++) {
                for (int j = i + 1; j < overloads.size(); j++) {
                    IrStmt.FunctionDecl a = overloads.get(i);
                    IrStmt.FunctionDecl b = overloads.get(j);
                    Result r = checkPair(a, b);
                    if (r instanceof Result.Overlapping ov) {
                        throw new CompileException(
                                "Overloads of '" + a.name()
                                        + "' overlap at parameter " + (ov.paramIndex() + 1)
                                        + ": " + ov.reason()
                                        + " — define only one or narrow the param sorts so "
                                        + "they're provably disjoint.",
                                b.origin());
                    }
                }
            }
        }
    }

    // --- Internals ---------------------------------------------------------

    private static Map<String, List<IrStmt.FunctionDecl>> collectOverloads(IrModule module) {
        Map<String, List<IrStmt.FunctionDecl>> byName = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                byName.computeIfAbsent(fd.name(), k -> new ArrayList<>()).add(fd);
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                for (IrStmt.FunctionDecl m : ti.methods()) {
                    byName.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m);
                }
            }
        }
        return byName;
    }

    private static Result checkPair(IrStmt.FunctionDecl a, IrStmt.FunctionDecl b) {
        if (a.params().size() != b.params().size()) {
            return Result.disjoint();
        }
        boolean anyUnknown = false;
        for (int i = 0; i < a.params().size(); i++) {
            IrSort sortA = a.params().get(i).sort();
            IrSort sortB = b.params().get(i).sort();
            Result r = checkSorts(sortA, sortB, i);
            if (r instanceof Result.Disjoint) {
                return r;
            }
            if (r instanceof Result.Unknown) {
                anyUnknown = true;
            }
        }
        if (anyUnknown) {
            return Result.unknown(-1, "Some parameter positions undecidable");
        }
        // All param positions overlap. Subsumption escape hatch: if one
        // overload is strictly more specific than the other, most-specific
        // dispatch resolves at runtime — no real ambiguity.
        if (strictlyMoreSpecific(a, b) || strictlyMoreSpecific(b, a)) {
            return Result.disjoint();
        }
        return Result.overlapping(0, "all parameter positions overlap and neither overload is strictly more specific");
    }

    /**
     * True iff every param of {@code a} implies the corresponding param
     * of {@code b}, and at least one direction fails for {@code b} →
     * {@code a}. Mirrors {@code DispatchTable.isStrictlyMoreSpecific}.
     */
    private static boolean strictlyMoreSpecific(IrStmt.FunctionDecl a, IrStmt.FunctionDecl b) {
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

    /**
     * Per-position overlap check. {@code paramIndex} is threaded through
     * so the eventual {@link Result.Overlapping} carries it; the caller
     * may override with its own composite reason.
     */
    private static Result checkSorts(IrSort a, IrSort b, int paramIndex) {
        if (a instanceof IrSort.Function || b instanceof IrSort.Function
                || a instanceof IrSort.Trait || b instanceof IrSort.Trait
                || a instanceof IrSort.Union || b instanceof IrSort.Union
                || a instanceof IrSort.Intersection || b instanceof IrSort.Intersection) {
            return Result.unknown(paramIndex, "compound sort kind (function/trait/union/intersection)");
        }

        String aBase = baseName(a);
        String bBase = baseName(b);
        if (aBase == null || bBase == null) {
            return Result.unknown(paramIndex, "no base name to compare");
        }
        if (!aBase.equals(bBase)) {
            return Result.disjoint();
        }

        // Same base name. Three subcases by refinement presence.
        if (a instanceof IrSort.Refined ra && b instanceof IrSort.Refined rb) {
            return kernelCheck(ra, rb, paramIndex);
        }
        // At least one is bare or structural — the non-refined side contains
        // the refined as a subset, so they overlap.
        return Result.overlapping(paramIndex,
                "parameter " + (paramIndex + 1) + ": both inhabit base '" + aBase + "'");
    }

    /**
     * Both sorts refined over the same base. Conjunction satisfiability
     * via the predicate kernel.
     */
    private static Result kernelCheck(IrSort.Refined a, IrSort.Refined b, int paramIndex) {
        SymExpr symA;
        SymExpr symB;
        try {
            symA = IrCompiler.compileSymExpr(a.predicate());
            symB = IrCompiler.compileSymExpr(b.predicate());
        } catch (CompileException ce) {
            // Shouldn't happen post-SortChecker, but be defensive.
            return Result.unknown(paramIndex, "predicate compile failure: " + ce.getMessage());
        }
        SymExpr conjunction = SymExpr.and(symA, symB);
        Sort domain = Sort.of(a.name());
        SatResult sat = PredicateArithmetic.satisfiable(conjunction, domain);
        return switch (sat) {
            case SatResult.Yes ignored -> Result.overlapping(paramIndex,
                    "parameter " + (paramIndex + 1) + ": predicate conjunction satisfiable on "
                            + a.name());
            case SatResult.No ignored -> Result.disjoint();
            case SatResult.Unknown u -> Result.unknown(paramIndex, u.reason());
        };
    }

    private static String baseName(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            default -> null;
        };
    }

    // --- Result type -------------------------------------------------------

    /**
     * Three-valued overlap result. {@link Disjoint} and
     * {@link Overlapping} are decisive; {@link Unknown} means the
     * kernel / heuristics couldn't decide and the caller should defer.
     */
    public sealed interface Result {

        record Disjoint() implements Result {
            public static final Disjoint INSTANCE = new Disjoint();
        }

        record Overlapping(int paramIndex, String reason) implements Result {}

        record Unknown(int paramIndex, String reason) implements Result {}

        static Result disjoint() { return Disjoint.INSTANCE; }

        static Result overlapping(int paramIndex, String reason) {
            return new Overlapping(paramIndex, reason);
        }

        static Result unknown(int paramIndex, String reason) {
            return new Unknown(paramIndex, reason);
        }
    }
}
