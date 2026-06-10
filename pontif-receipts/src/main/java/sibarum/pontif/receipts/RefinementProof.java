package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;

import java.util.List;
import java.util.Map;

/**
 * Translates a hand-authored proof — written in Pontif as a struct-literal tree
 * of {@code Leaf} / {@code Split} (see {@code IrStmt.Proof}) — into the Java
 * {@link Refinement} the {@link RefinementValidator} checks.
 *
 * <p>Works on the <b>unevaluated</b> {@link IrExpr.Record}: a {@code Split}'s
 * predicate (e.g. {@code x >= 3}) must stay a symbolic {@link SymExpr.Cmp}, so
 * it is lifted via {@link IrCompiler#compileSymExpr} rather than evaluated to a
 * Bool. Source parameter names are renamed to their graph form ({@code x} →
 * {@code x_0}) so the result aligns with the obligation's {@code PathFacts} —
 * the same renaming the {@code Drafter} applies to the function body.
 *
 * <p>The recursion bottoms out structurally on the proof tree (finite by
 * construction); it never unfolds a recursive sort. A malformed proof is a
 * {@link CompileException} with the offending node's origin — the gate turns
 * that into a clear "your proof doesn't translate" rejection rather than a
 * silent acceptance.
 */
public final class RefinementProof {

    private RefinementProof() {}

    /**
     * @param proofTree the unevaluated struct-literal proof (a {@code Leaf}/{@code Split} {@link IrExpr.Record})
     * @param rename    {@code paramName → graphVar} (e.g. {@code {"x" → x_0}}) applied to every split predicate
     */
    public static Refinement fromIr(IrExpr proofTree, Map<String, SymExpr> rename)
            throws CompileException {
        if (!(proofTree instanceof IrExpr.Record rec)) {
            throw new CompileException(
                    "a proof must be a Leaf/Split struct tree; got "
                            + proofTree.getClass().getSimpleName(),
                    proofTree.origin());
        }
        // Match on the local constructor name: in a linked project the type is
        // FQN'd (e.g. `std.proof/Split`), in a single bare file it's just `Split`.
        String type = localName(rec.typeName());
        if ("Leaf".equals(type)) {
            if (!rec.members().isEmpty()) {
                throw new CompileException("proof Leaf takes no fields", rec.origin());
            }
            return Refinement.leaf();
        }
        if ("Split".equals(type)) {
            IrExpr pExpr = member(rec, "p");
            SymExpr pred = Substitute.apply(IrCompiler.compileSymExpr(pExpr), rename);
            if (!(pred instanceof SymExpr.Cmp)) {
                throw new CompileException(
                        "proof Split predicate must be a comparison (e.g. x >= 0); got " + pred,
                        pExpr.origin());
            }
            return Refinement.splitOn(
                    pred,
                    fromIr(member(rec, "whenTrue"), rename),
                    fromIr(member(rec, "whenFalse"), rename));
        }
        if ("Singletons".equals(type)) {
            // Generative "recursion to singletons": unfold to a conservative
            // ladder isolating each integer in [lo, hi] over the subject. The
            // subject is renamed to its graph form like a Split predicate; lo/hi
            // must be integer literals.
            SymExpr subject = Substitute.apply(
                    IrCompiler.compileSymExpr(member(rec, "subject")), rename);
            long lo = intLiteral(rec, "lo");
            long hi = intLiteral(rec, "hi");
            if (lo > hi) {
                throw new CompileException(
                        "proof Singletons has empty range: lo=" + lo + " > hi=" + hi, rec.origin());
            }
            return Refinement.splitToSingletons(subject, lo, hi);
        }
        throw new CompileException(
                "unknown proof constructor '" + type + "'; expected Leaf, Split, or Singletons",
                rec.origin());
    }

    /**
     * Lowers an {@code assign proof} case-function body to a {@link Refinement}.
     * The ordered, first-match arms nest as
     * {@code Split(g0, Leaf, Split(g1, Leaf, …))}: each arm but the last cuts on
     * its guard (true-side discharges here), and the <b>last</b> arm becomes the
     * final else-{@code Leaf} — its region is exactly the structural complement
     * of the earlier guards (so a trailing {@code [_]} or computed-complement arm
     * needs no explicit guard). Arm bodies are inert; only the guards (the cuts)
     * matter. Guards are the arm patterns' predicates with {@code @} bound to the
     * match subject, then param-renamed to graph vars — the same derivation the
     * {@code Drafter} uses for a function body's match branches.
     *
     * @param match  the case-function {@code (match s [g] -> … …)}
     * @param rename {@code paramName → graphVar} applied to subject and guards
     */
    public static Refinement fromCaseFunction(IrExpr.Match match, Map<String, SymExpr> rename)
            throws CompileException {
        SymExpr subject = Substitute.apply(
                IrCompiler.compileSymExpr(match.scrutinee()), rename);
        return lowerArms(match.branches(), 0, subject, rename);
    }

    private static Refinement lowerArms(
            List<IrExpr.MatchBranch> arms, int i, SymExpr subject, Map<String, SymExpr> rename)
            throws CompileException {
        // The last arm is the residual/else region — its guard is the structural
        // complement of the earlier cuts, which Split derives for free.
        if (i >= arms.size() - 1) {
            return Refinement.leaf();
        }
        IrExpr.MatchBranch arm = arms.get(i);
        if (!(arm.pattern() instanceof IrSort.Refined refined)) {
            throw new CompileException(
                    "an assign-proof arm guard must be a refinement predicate (e.g. [@>=3]); "
                            + "structural patterns aren't supported",
                    arm.pattern().origin());
        }
        SymExpr predicate = Substitute.apply(
                IrCompiler.compileSymExpr(refined.predicate()), rename);
        SymExpr guard = Substitute.applySelf(predicate, subject);
        if (!(guard instanceof SymExpr.Cmp)) {
            throw new CompileException(
                    "an assign-proof arm guard must be a comparison (e.g. [@>=3]); got " + guard,
                    refined.origin());
        }
        return Refinement.splitOn(guard, Refinement.leaf(),
                lowerArms(arms, i + 1, subject, rename));
    }

    /** The local part of a possibly-FQN'd type name ({@code std.proof/Split} → {@code Split}). */
    private static String localName(String typeName) {
        if (typeName == null) return null;
        int slash = typeName.lastIndexOf('/');
        return slash >= 0 ? typeName.substring(slash + 1) : typeName;
    }

    private static long intLiteral(IrExpr.Record rec, String field) throws CompileException {
        IrExpr e = member(rec, field);
        if (e instanceof IrExpr.Lit lit) {
            return lit.value();
        }
        throw new CompileException(
                "proof Singletons field '" + field + "' must be an integer literal; got "
                        + e.getClass().getSimpleName(), e.origin());
    }

    private static IrExpr member(IrExpr.Record rec, String name) throws CompileException {
        IrExpr m = rec.members().get(name);
        if (m == null) {
            throw new CompileException(
                    "proof Split is missing field '" + name + "'; expected p, whenTrue, whenFalse",
                    rec.origin());
        }
        return m;
    }
}
