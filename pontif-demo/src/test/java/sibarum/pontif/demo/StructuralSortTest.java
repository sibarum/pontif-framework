package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.defaults.DefaultRules;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuralSortTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(DefaultRules.production());

    // --- Construction ---

    @Test
    void structuralSort_constructed() throws Exception {
        Sort person = Sort.structural("Person", Map.of(
                "name", Sort.of("String"),
                "age", Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(0)))));
        assertTrue(person.isStructural());
        assertFalse(person.isRefined());
        assertEquals(2, person.members().size());
    }

    @Test
    void scalarAndStructural_areDifferentKinds() throws Exception {
        Sort scalar = Sort.of("Int");
        Sort struct = Sort.structural("Empty", Map.of());
        assertFalse(scalar.isStructural());
        assertTrue(struct.isStructural());
    }

    // --- Records and field access ---

    @Test
    void recordLiteral_constructs() throws Exception {
        SymExpr rec = SymExpr.record(Map.of(
                "x", SymExpr.lit(5),
                "y", SymExpr.lit(7)));
        assertInstanceOf(SymExpr.Record.class, rec);
        SymExpr.Record asRecord = (SymExpr.Record) rec;
        assertEquals(2, asRecord.members().size());
    }

    @Test
    void fieldAccessOnRecord_simplifiesToMember() throws Exception {
        SymExpr rec = SymExpr.record(Map.of(
                "x", SymExpr.lit(5),
                "y", SymExpr.lit(7)));
        SymExpr access = SymExpr.fieldAccess(rec, "x");
        assertEquals(SymExpr.lit(5), SIMPLIFIER.simplify(access));
    }

    @Test
    void fieldAccessOnSymbolicBase_staysSymbolic() throws Exception {
        SymExpr access = SymExpr.fieldAccess(SymExpr.var("obj"), "field");
        SymExpr result = SIMPLIFIER.simplify(access);
        assertInstanceOf(SymExpr.FieldAccess.class, result);
    }

    @Test
    void fieldAccessOnMissingField_staysSymbolic() throws Exception {
        // Asking for a field the record doesn't have — stays as FieldAccess
        // (residual, since runtime would error)
        SymExpr rec = SymExpr.record(Map.of("x", SymExpr.lit(5)));
        SymExpr access = SymExpr.fieldAccess(rec, "missing");
        SymExpr result = SIMPLIFIER.simplify(access);
        assertInstanceOf(SymExpr.FieldAccess.class, result);
    }

    // --- Satisfies ---

    @Test
    void recordSatisfiesStructuralSort_exact() throws Exception {
        Sort point = Sort.structural("Point", Map.of(
                "x", Sort.of("Int"),
                "y", Sort.of("Int")));
        SymExpr origin = SymExpr.record(Map.of(
                "x", SymExpr.lit(0),
                "y", SymExpr.lit(0)));
        assertTrue(Refinements.satisfies(origin, point, SIMPLIFIER).isPassed());
    }

    @Test
    void recordWithExtraMembers_satisfiesStructuralSort_widthSubtyping() throws Exception {
        // Structural sorts are width-subtyped: extra members are fine.
        Sort point = Sort.structural("Point", Map.of(
                "x", Sort.of("Int"),
                "y", Sort.of("Int")));
        SymExpr pointWithMeta = SymExpr.record(Map.of(
                "x", SymExpr.lit(3),
                "y", SymExpr.lit(4),
                "color", SymExpr.var("red")));
        assertTrue(Refinements.satisfies(pointWithMeta, point, SIMPLIFIER).isPassed());
    }

    @Test
    void recordMissingRequiredMember_fails() throws Exception {
        Sort point = Sort.structural("Point", Map.of(
                "x", Sort.of("Int"),
                "y", Sort.of("Int")));
        SymExpr incomplete = SymExpr.record(Map.of("x", SymExpr.lit(3)));
        ProofResult r = Refinements.satisfies(incomplete, point, SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r);
        assertTrue(((ProofResult.Failed) r).witness().contains("y"),
                "diagnostic should name the missing member; got: " + ((ProofResult.Failed) r).witness());
    }

    @Test
    void recordWithRefinedMemberSatisfying_passes() throws Exception {
        Sort positivePoint = Sort.structural("PositivePoint", Map.of(
                "x", Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0))),
                "y", Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)))));
        SymExpr p = SymExpr.record(Map.of(
                "x", SymExpr.lit(3),
                "y", SymExpr.lit(7)));
        assertTrue(Refinements.satisfies(p, positivePoint, SIMPLIFIER).isPassed());
    }

    @Test
    void recordWithRefinedMemberViolating_fails() throws Exception {
        Sort positivePoint = Sort.structural("PositivePoint", Map.of(
                "x", Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0))),
                "y", Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)))));
        SymExpr p = SymExpr.record(Map.of(
                "x", SymExpr.lit(3),
                "y", SymExpr.lit(-7)));  // violates y > 0
        ProofResult r = Refinements.satisfies(p, positivePoint, SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r);
        assertTrue(((ProofResult.Failed) r).witness().contains("y"),
                "diagnostic should mention the failing member; got: " + ((ProofResult.Failed) r).witness());
    }

    @Test
    void recordWithSymbolicMember_yieldsResidual() throws Exception {
        Sort point = Sort.structural("PositivePoint", Map.of(
                "x", Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)))));
        SymExpr p = SymExpr.record(Map.of("x", SymExpr.var("ux")));
        ProofResult r = Refinements.satisfies(p, point, SIMPLIFIER);
        assertInstanceOf(ProofResult.Residual.class, r);
    }

    @Test
    void nonRecordValue_failsStructuralSort() throws Exception {
        Sort point = Sort.structural("Point", Map.of("x", Sort.of("Int")));
        ProofResult r = Refinements.satisfies(SymExpr.lit(5), point, SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r);
    }

    @Test
    void symbolicValue_yieldsResidualOnStructuralSort() throws Exception {
        Sort point = Sort.structural("Point", Map.of("x", Sort.of("Int")));
        ProofResult r = Refinements.satisfies(SymExpr.var("obj"), point, SIMPLIFIER);
        assertInstanceOf(ProofResult.Residual.class, r);
    }

    // --- Implication (width subtyping) ---

    @Test
    void biggerSortImpliesSmallerSort_widthSubtyping() throws Exception {
        Sort big = Sort.structural("Person", Map.of(
                "name", Sort.of("String"),
                "age", Sort.of("Int"),
                "email", Sort.of("String")));
        Sort small = Sort.structural("Named", Map.of(
                "name", Sort.of("String")));
        assertTrue(Refinements.imply(big, small, SIMPLIFIER).isPassed(),
                "{name, age, email} should imply {name}");
    }

    @Test
    void smallerSortDoesNotImplyBiggerSort() throws Exception {
        Sort big = Sort.structural("Person", Map.of(
                "name", Sort.of("String"),
                "age", Sort.of("Int")));
        Sort small = Sort.structural("Named", Map.of(
                "name", Sort.of("String")));
        ProofResult r = Refinements.imply(small, big, SIMPLIFIER);
        assertFalse(r.isPassed(),
                "{name} should NOT imply {name, age}; got: " + r);
    }

    @Test
    void memberRefinementImplication_widthAware() throws Exception {
        // {x: Int[@>5]} implies {x: Int[@>0]} (member sort tightens)
        Sort tight = Sort.structural("S", Map.of(
                "x", Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(5)))));
        Sort loose = Sort.structural("S", Map.of(
                "x", Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)))));
        assertTrue(Refinements.imply(tight, loose, SIMPLIFIER).isPassed());
    }

    @Test
    void structuralAndScalar_doNotImplyEachOther() throws Exception {
        Sort struct = Sort.structural("S", Map.of("x", Sort.of("Int")));
        Sort scalar = Sort.of("Int");
        assertFalse(Refinements.imply(struct, scalar, SIMPLIFIER).isPassed());
        assertFalse(Refinements.imply(scalar, struct, SIMPLIFIER).isPassed());
    }

    // --- Nominal / recursive struct implication (registry + coinductive guard) ---

    @Test
    void recursiveStructImpliesItself_terminates() throws Exception {
        // struct Node(v:Int, next:Node) — a by-reference self-reference. With the
        // registry resolving "Node" to its structural definition, subsumption
        // unfolds Node and revisits the (Node, Node) pair; the coinductive guard
        // assumes it holds and stops, so this returns Passed instead of looping.
        Sort node = Sort.structural("Node", Map.of(
                "v", Sort.of("Int"),
                "next", Sort.of("Node")));
        Simplifier withReg = SIMPLIFIER.withRegistry(Map.of("Node", node));
        assertTrue(Refinements.imply(Sort.of("Node"), Sort.of("Node"), withReg).isPassed(),
                "recursive Node should imply itself and terminate");
    }

    @Test
    void byReferenceStructs_declaredNamesDoNotCrossImply() throws Exception {
        // The claim rule: a DECLARED name is implied only by itself. Big's
        // shape contains Small's, but Big-ness is not Small-ness — implying a
        // registered nominal from a same-shaped other nominal is re-badging
        // (the sort-level face of passing a Vec where a Point is required).
        // Width subtyping survives where it's honest: against ANONYMOUS
        // shapes (struct ⊑ anonymous, the directional rule).
        Sort big = Sort.structural("Big", Map.of(
                "name", Sort.of("String"),
                "age", Sort.of("Int")));
        Sort small = Sort.structural("Small", Map.of(
                "name", Sort.of("String")));
        Simplifier withReg = SIMPLIFIER.withRegistry(Map.of("Big", big, "Small", small));
        assertFalse(Refinements.imply(Sort.of("Big"), Sort.of("Small"), withReg).isPassed(),
                "Big must NOT imply the declared nominal Small — that's re-badging");
        Sort anonymousShape = Sort.structural("_record", Map.of("name", Sort.of("String")));
        assertTrue(Refinements.imply(Sort.of("Big"), anonymousShape, withReg).isPassed(),
                "Big still satisfies the anonymous {name} shape — struct ⊑ anonymous, width OK");
    }

    @Test
    void disjointNominalStructs_doNotImply() throws Exception {
        // The soundness case at the sort level: a Node arg must not satisfy a
        // Leaf param. Resolved structurally, Node lacks Leaf's 'tag' field, so
        // imply fails — rather than treating the bare name "Leaf" as unconstrained.
        Sort node = Sort.structural("Node", Map.of(
                "v", Sort.of("Int"),
                "next", Sort.of("Node")));
        Sort leaf = Sort.structural("Leaf", Map.of("tag", Sort.of("Int")));
        Simplifier withReg = SIMPLIFIER.withRegistry(Map.of("Node", node, "Leaf", leaf));
        assertFalse(Refinements.imply(Sort.of("Node"), Sort.of("Leaf"), withReg).isPassed(),
                "Node should NOT imply Leaf (Node lacks 'tag')");
    }

    // --- Nested structural sorts ---

    @Test
    void nestedStructuralSort_satisfiedByNestedRecord() throws Exception {
        Sort innerSort = Sort.structural("Inner", Map.of(
                "value", Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)))));
        Sort outerSort = Sort.structural("Outer", Map.of(
                "label", Sort.of("String"),
                "inner", innerSort));
        SymExpr outerRec = SymExpr.record(Map.of(
                "label", SymExpr.var("lbl"),
                "inner", SymExpr.record(Map.of("value", SymExpr.lit(42)))));
        assertTrue(Refinements.satisfies(outerRec, outerSort, SIMPLIFIER).isPassed());
    }

    @Test
    void nestedStructuralSort_violatedInnerMember_fails() throws Exception {
        Sort innerSort = Sort.structural("Inner", Map.of(
                "value", Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)))));
        Sort outerSort = Sort.structural("Outer", Map.of(
                "inner", innerSort));
        SymExpr outerRec = SymExpr.record(Map.of(
                "inner", SymExpr.record(Map.of("value", SymExpr.lit(-5)))));
        ProofResult r = Refinements.satisfies(outerRec, outerSort, SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r);
    }

    // --- The headline: the user's original keyPair sketch ---

    @Test
    void keyPairSketch_fromOriginalUserExample() throws Exception {
        // keyPair : [@->public[String] & @->private[Array[byte][@->length=32]]]
        // We don't have Array yet; model it as a refined sort with a length-like field.
        Sort lengthIs32 = Sort.refined("Length", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(32)));
        Sort bytes32 = Sort.structural("Bytes32", Map.of("length", lengthIs32));
        Sort keyPairSort = Sort.structural("KeyPair", Map.of(
                "public", Sort.of("String"),
                "private", bytes32));

        // A valid keypair: has both members, private has length 32
        SymExpr validKey = SymExpr.record(Map.of(
                "public", SymExpr.var("pubkey_string"),
                "private", SymExpr.record(Map.of("length", SymExpr.lit(32)))));
        assertTrue(Refinements.satisfies(validKey, keyPairSort, SIMPLIFIER).isPassed());

        // Wrong length
        SymExpr badKey = SymExpr.record(Map.of(
                "public", SymExpr.var("pubkey_string"),
                "private", SymExpr.record(Map.of("length", SymExpr.lit(16)))));
        ProofResult r = Refinements.satisfies(badKey, keyPairSort, SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r);
        assertTrue(((ProofResult.Failed) r).witness().contains("private"),
                "diagnostic should attribute the failure to the 'private' member; got: " + ((ProofResult.Failed) r).witness());

        // Missing public
        SymExpr missingKey = SymExpr.record(Map.of(
                "private", SymExpr.record(Map.of("length", SymExpr.lit(32)))));
        ProofResult r2 = Refinements.satisfies(missingKey, keyPairSort, SIMPLIFIER);
        assertInstanceOf(ProofResult.Failed.class, r2);
        assertTrue(((ProofResult.Failed) r2).witness().contains("public"),
                "diagnostic should name the missing member; got: " + ((ProofResult.Failed) r2).witness());
    }

    // --- imply across sort KINDS (WAR(dependent-sorts) §5: Failed ⟺ provably disjoint) ---
    // Before the hardening, imply's "different sort kinds" catch-all reported these
    // subset relations as Failed — the false-positive families the call-gate
    // measurement surfaced (a struct vs a union containing it; a refined struct vs
    // its own base). Failed must be reserved for the genuinely disjoint.

    @Test
    void structImpliesUnionContainingIt() throws Exception {
        // Element ⊑ [Element|Leaf] — membership, not a kind clash.
        Sort element = Sort.structural("Element", Map.of("head", Sort.of("Int"), "rest", Sort.of("Leaf")));
        Sort leaf = Sort.structural("Leaf", Map.of());
        Sort union = Sort.union(List.of(Sort.of("Element"), Sort.of("Leaf")));
        Simplifier withReg = SIMPLIFIER.withRegistry(Map.of("Element", element, "Leaf", leaf));
        assertTrue(Refinements.imply(Sort.of("Element"), union, withReg).isPassed(),
                "a struct must imply a union that contains it");
    }

    @Test
    void unionImpliesIdenticalUnion() throws Exception {
        // [Element|Leaf] ⊑ [Element|Leaf] — every branch implies the looser union.
        Sort element = Sort.structural("Element", Map.of("head", Sort.of("Int"), "rest", Sort.of("Leaf")));
        Sort leaf = Sort.structural("Leaf", Map.of());
        Sort union = Sort.union(List.of(Sort.of("Element"), Sort.of("Leaf")));
        Simplifier withReg = SIMPLIFIER.withRegistry(Map.of("Element", element, "Leaf", leaf));
        assertTrue(Refinements.imply(union, union, withReg).isPassed(),
                "a union must imply itself");
    }

    @Test
    void refinedStructImpliesItsBase() throws Exception {
        // [Countdown:@.n == 3] ⊑ Countdown — the predicate only narrows the base.
        Sort countdown = Sort.structural("Countdown", Map.of("n", Sort.of("Int")));
        Sort refined = Sort.refined("Countdown",
                SymExpr.cmp(SymExpr.fieldAccess(SymExpr.self(), "n"), SymExpr.CmpOp.EQ, SymExpr.lit(3)));
        Simplifier withReg = SIMPLIFIER.withRegistry(Map.of("Countdown", countdown));
        assertTrue(Refinements.imply(refined, Sort.of("Countdown"), withReg).isPassed(),
                "a refined struct must imply its own base struct");
    }

    @Test
    void refinedTupleVsStructuralTuple_isUndecidedNotFailed() throws Exception {
        // [_tuple:@._0==1 & @._1==true] vs _tuple{_0:Int, _1:Bool}: the base kind
        // matches (both tuples), but the predicate's field sorts aren't recoverable
        // from the refinement alone — undecided, NOT a kind-clash Failed.
        Sort refinedTuple = Sort.refined("_tuple",
                SymExpr.and(
                        SymExpr.cmp(SymExpr.fieldAccess(SymExpr.self(), "_0"), SymExpr.CmpOp.EQ, SymExpr.lit(1)),
                        SymExpr.cmp(SymExpr.fieldAccess(SymExpr.self(), "_1"), SymExpr.CmpOp.EQ, SymExpr.bool(true))));
        Sort tupleSort = Sort.structural("_tuple", Map.of("_0", Sort.of("Int"), "_1", Sort.of("Bool")));
        ProofResult r = Refinements.imply(refinedTuple, tupleSort, SIMPLIFIER);
        assertFalse(r.isPassed(), "can't prove the field sorts from the predicate alone");
        assertTrue(r.isResidual(), "but a tuple base is not disjoint from a tuple — must be Residual, got: " + r);
    }

    @Test
    void refinedPrimitiveVsStruct_staysFailed() throws Exception {
        // [Int:@>0] vs a struct IS provably disjoint — an int is never a record.
        // The hardening must not soften this to Residual.
        Sort refinedInt = Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        Sort struct = Sort.structural("Empty", Map.of());
        assertInstanceOf(ProofResult.Failed.class, Refinements.imply(refinedInt, struct, SIMPLIFIER),
                "a refined primitive and a struct are disjoint kinds");
    }

    @Test
    void scalarDisjointness_staysFailed() throws Exception {
        // The genuine call-gate hole (`h(-3)` against `[Int:@>0]`): scalar
        // disjointness must still register as Failed — the hardening only touched
        // struct/union/tuple kind-pairings, not the arithmetic path.
        Sort negThree = Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(-3)));
        Sort positive = Sort.refined("Int", SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
        assertInstanceOf(ProofResult.Failed.class, Refinements.imply(negThree, positive, SIMPLIFIER),
                "[Int:@==-3] is provably disjoint from [Int:@>0]");
    }
}
