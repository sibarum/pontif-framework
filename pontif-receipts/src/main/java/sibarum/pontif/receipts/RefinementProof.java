package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;

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
        String type = rec.typeName();
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
        throw new CompileException(
                "unknown proof constructor '" + type + "'; expected Leaf or Split",
                rec.origin());
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
