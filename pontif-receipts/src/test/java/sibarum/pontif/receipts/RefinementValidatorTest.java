package sibarum.pontif.receipts;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 1 feasibility: a human-supplied conservative refinement of a branch
 * discharges an obligation the built-in issuer can't close on a single
 * branch — verified end-to-end by the same kernel, fully traceable.
 *
 * <p>Coverage/disjointness are never checked: {@link Refinement.Split} only
 * lets you give one predicate, deriving its complement, so every tree is a
 * partition by construction. The validator proves only the leaves.
 */
class RefinementValidatorTest {

    // --- fixtures -----------------------------------------------------------

    /** {@code function nonneg(x:Int):[Int:@>=0] -> x*(x-1)} */
    private static IrModule nonnegModule() {
        IrExpr body = IrExpr.binOp(IrExpr.Op.MUL,
                IrExpr.var("x"),
                IrExpr.binOp(IrExpr.Op.SUB, IrExpr.var("x"), IrExpr.lit(1)));
        return new IrModule("m",
                List.of(IrStmt.functionDecl("nonneg",
                        List.of(new IrParam("x", IrSort.named("Int"))), intGe(0), body)),
                IrExpr.lit(0));
    }

    /** {@code function isSparse(x:Int):[Int:@>=-16] -> (x-3)*(x+5)} */
    private static IrModule isSparseModule() {
        IrExpr body = IrExpr.binOp(IrExpr.Op.MUL,
                IrExpr.binOp(IrExpr.Op.SUB, IrExpr.var("x"), IrExpr.lit(3)),
                IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(5)));
        return new IrModule("m",
                List.of(IrStmt.functionDecl("isSparse",
                        List.of(new IrParam("x", IrSort.named("Int"))), intGe(-16), body)),
                IrExpr.lit(0));
    }

    private static IrSort intGe(long n) {
        return IrSort.refined("Int", IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(n)));
    }

    // --- helpers ------------------------------------------------------------

    /** The obligation for a node's sole branch (same derivation as BuiltinIssuer). */
    private static RefinementValidator.Result validate(IrModule module, Refinement refinement)
            throws Exception {
        ReceiptGraph graph = Drafter.draft(module);
        Node node = graph.roots().get(0);
        Branch branch = node.branches().get(0);
        SymExpr obligation = Substitute.applySelf(
                node.resultVar().sort().predicate(), SymExpr.var(node.resultVar().name()));
        return RefinementValidator.validate(node, branch, obligation, refinement);
    }

    private static SymExpr cmp(String var, SymExpr.CmpOp op, long n) {
        return SymExpr.cmp(SymExpr.var(var), op, SymExpr.lit(n));
    }

    /**
     * Peels {@code [lo, hi]} into singletons over {@code x_0} via the
     * first-class generative combinator (was hand-rolled here; the combinator
     * now owns the {@code <= k}-cut ladder, so these tests exercise it).
     */
    private static Refinement singletons(long lo, long hi) {
        return Refinement.splitToSingletons(SymExpr.var("x_0"), lo, hi);
    }

    // --- the clean single split ---------------------------------------------

    @Test
    void singleSplitClosesNonneg() throws Exception {
        // x*(x-1) >= 0 for all integers. Split on x_0 >= 1:
        //   true  [x_0>=1]: (x_0-1)>=0, product >=0.
        //   false [x_0<1] : x_0<=0 and (x_0-1)<=-1, product of non-positives >=0.
        Refinement r = Refinement.splitOn(
                cmp("x_0", SymExpr.CmpOp.GE, 1), Refinement.leaf(), Refinement.leaf());
        assertTrue(validate(nonnegModule(), r).verified());
    }

    @Test
    void unsplitNonnegDoesNotDischarge() throws Exception {
        // Sanity: without the split, the obligation does NOT close — so the
        // split above is doing real work, not riding a bound the engine
        // already had.
        assertFalse(validate(nonnegModule(), Refinement.leaf()).verified());
    }

    @Test
    void insufficientSplitReportsUnverified() throws Exception {
        // A *valid* partition (x_0>=5 vs x_0<5) whose false leaf doesn't close:
        // over x_0<5 the product x*(x-1) is unbounded below. The partition is
        // sound; the proof is merely insufficient — exactly the failure mode
        // the architecture must report honestly (not "wrong", just open).
        Refinement r = Refinement.splitOn(
                cmp("x_0", SymExpr.CmpOp.GE, 5), Refinement.leaf(), Refinement.leaf());
        RefinementValidator.Result result = validate(nonnegModule(), r);
        assertFalse(result.verified());

        // ...and the trace pinpoints *which* leaf stayed open.
        RefinementValidator.Outcome.SplitOutcome split =
                (RefinementValidator.Outcome.SplitOutcome) result.tree();
        assertTrue(split.whenTrue().discharged(), "x_0>=5 leaf should close");
        assertFalse(split.whenFalse().discharged(), "x_0<5 leaf should stay open");
    }

    // --- the headline: isSparse via recursion to singletons -----------------

    @Test
    void isSparseClosesViaConservativeSplitsToSingletons() throws Exception {
        // (x-3)*(x+5) >= -16 for all integers. The built-in issuer can't close
        // this (opaque product, true min -16 at x=-1). Human-supplied split:
        //   A [x_0>=3]            : product >= 0          (interval mult)
        //   C [x_0<=-6]           : product of negatives  (interval mult)
        //   B [-5<=x_0<=2]        : recurse to singletons (each exact)
        Refinement tree = Refinement.splitOn(cmp("x_0", SymExpr.CmpOp.GE, 3),
                Refinement.leaf(),                                  // A
                Refinement.splitOn(cmp("x_0", SymExpr.CmpOp.LE, -6),
                        Refinement.leaf(),                          // C
                        singletons(-5, 2)));                        // B
        RefinementValidator.Result result = validate(isSparseModule(), tree);
        assertTrue(result.verified(),
                () -> "isSparse should close piecewise; goal was " + result.substitutedGoal());
    }

    @Test
    void generatedSingletonLadderClosesIsSparseRegionB() throws Exception {
        // The generative combinator used directly (not via the test helper):
        // the middle region [-5, 2] is now a single splitToSingletons call,
        // not a hand-written ladder — the Slice-2 "recursion to singletons".
        Refinement tree = Refinement.splitOn(cmp("x_0", SymExpr.CmpOp.GE, 3),
                Refinement.leaf(),
                Refinement.splitOn(cmp("x_0", SymExpr.CmpOp.LE, -6),
                        Refinement.leaf(),
                        Refinement.splitToSingletons(SymExpr.var("x_0"), -5, 2)));
        assertTrue(validate(isSparseModule(), tree).verified());
    }

    @Test
    void splitToSingletons_degenerateAndEmptyRanges() {
        // A single-point range needs no cut — it's already pinned by guards.
        assertTrue(Refinement.splitToSingletons(SymExpr.var("x_0"), 4, 4)
                instanceof Refinement.Leaf);
        // A two-point range is exactly one cut (true=lo, false=hi).
        assertTrue(Refinement.splitToSingletons(SymExpr.var("x_0"), 4, 5)
                instanceof Refinement.Split s
                && s.whenTrue() instanceof Refinement.Leaf
                && s.whenFalse() instanceof Refinement.Leaf);
        // An empty range is a caller error, not a silent no-op.
        assertThrows(IllegalArgumentException.class,
                () -> Refinement.splitToSingletons(SymExpr.var("x_0"), 5, 4));
    }

    @Test
    void isSparseUnsplitDoesNotDischarge() throws Exception {
        // The whole point: a single branch can't prove it.
        assertFalse(validate(isSparseModule(), Refinement.leaf()).verified());
    }

    // --- conservation invariants are structural -----------------------------

    @Test
    void complementIsExactOperatorFlip() {
        assertEquals(cmp("x", SymExpr.CmpOp.LT, 1),
                Refinement.complement(cmp("x", SymExpr.CmpOp.GE, 1)));
        assertEquals(cmp("x", SymExpr.CmpOp.GT, 0),
                Refinement.complement(cmp("x", SymExpr.CmpOp.LE, 0)));
        assertEquals(cmp("x", SymExpr.CmpOp.NE, 7),
                Refinement.complement(cmp("x", SymExpr.CmpOp.EQ, 7)));
        // complement is an involution: ¬¬p = p
        SymExpr p = cmp("x", SymExpr.CmpOp.GT, 3);
        assertEquals(p, Refinement.complement(Refinement.complement(p)));
    }

    @Test
    void falseSideCarriesTheComplementGuard() throws Exception {
        // The validator gives the false side ¬p, not some independently chosen
        // guard — coverage/disjointness are unrepresentable to get wrong.
        Refinement r = Refinement.splitOn(
                cmp("x_0", SymExpr.CmpOp.GE, 1), Refinement.leaf(), Refinement.leaf());
        RefinementValidator.Outcome.SplitOutcome split =
                (RefinementValidator.Outcome.SplitOutcome) validate(nonnegModule(), r).tree();
        RefinementValidator.Outcome.LeafOutcome falseLeaf =
                (RefinementValidator.Outcome.LeafOutcome) split.whenFalse();
        assertEquals(List.of(cmp("x_0", SymExpr.CmpOp.LT, 1)), falseLeaf.guards());
    }

    @Test
    void splitRejectsNonComparisonPredicate() {
        // The combinator only admits comparisons (whose complement is exact).
        assertThrows(IllegalArgumentException.class,
                () -> Refinement.splitOn(SymExpr.bool(true), Refinement.leaf(), Refinement.leaf()));
    }

    // --- proof-supply via the issuer (Slice 1b) -----------------------------

    /** {@code function bad(x:Int):[Int:@>0] -> x} — false for x <= 0. */
    private static IrModule badModule() {
        return new IrModule("m",
                List.of(IrStmt.functionDecl("bad",
                        List.of(new IrParam("x", IrSort.named("Int"))),
                        IrSort.refined("Int",
                                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0))),
                        IrExpr.var("x"))),
                IrExpr.lit(0));
    }

    private static BuiltinIssuer.Attempt attemptAt(
            List<BuiltinIssuer.Attempt> attempts, GraphReference ref) {
        return attempts.stream()
                .filter(a -> a.nodeIndex() == ref.nodeIndex() && a.branchIndex() == ref.branchIndex())
                .findFirst().orElseThrow();
    }

    @Test
    void issuerDischargesIsSparseViaSuppliedProof() throws Exception {
        // The headline of the proof-supply path: a true-but-hard return the
        // built-in engine reports NOT DISCHARGED becomes discharged once a
        // conservative refinement proof is supplied — extending reach without
        // extending trust (the kernel still validates every leaf).
        ReceiptGraph graph = Drafter.draft(isSparseModule());
        GraphReference ref = new GraphReference(0, 0);  // isSparse, sole branch

        assertFalse(attemptAt(BuiltinIssuer.attemptAll(graph), ref).discharged(),
                "built-in engine should NOT discharge isSparse on its own");

        Refinement proof = Refinement.splitOn(cmp("x_0", SymExpr.CmpOp.GE, 3),
                Refinement.leaf(),
                Refinement.splitOn(cmp("x_0", SymExpr.CmpOp.LE, -6),
                        Refinement.leaf(), singletons(-5, 2)));
        BuiltinIssuer.Attempt a =
                attemptAt(BuiltinIssuer.attemptAll(graph, Map.of(ref, proof)), ref);
        assertTrue(a.discharged(), "supplied proof should discharge isSparse");
        assertTrue(a.provenByRefinement(), "discharge should be attributed to the proof");
    }

    @Test
    void issuerRefusesFalseObligationEvenWithAProof() throws Exception {
        // bad(x:Int):[Int:@>0] -> x is FALSE for x <= 0. No conservative split
        // can discharge it — the x<1 (i.e. x<=0) leaf leaves x>0 unprovable —
        // so a supplied "proof" simply fails to validate. The recourse cannot
        // launder a falsehood.
        ReceiptGraph graph = Drafter.draft(badModule());
        GraphReference ref = new GraphReference(0, 0);
        Refinement attemptedProof = Refinement.splitOn(
                cmp("x_0", SymExpr.CmpOp.GE, 1), Refinement.leaf(), Refinement.leaf());
        BuiltinIssuer.Attempt a =
                attemptAt(BuiltinIssuer.attemptAll(graph, Map.of(ref, attemptedProof)), ref);
        assertFalse(a.discharged(), "no valid proof exists for a false obligation");
        assertFalse(a.provenByRefinement());
    }
}
