package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The POSITIONAL face of an anonymous structural type: {@code [{Int, String}]}, satisfied
 * by the tuple literal {@code {1, "s"}}. Sibling of {@link AnonymousRecordSortTest}, which
 * pins the by-name face — and deliberately shaped the same way, because the two faces are
 * one thing written two ways and any asymmetry between them is a bug.
 *
 * <p>One had been: a tuple sort in a declared position constrained NOTHING. Not arity, not
 * component base types, and — sharpest — not component refinements, which every other
 * judgment site in the language proves. A tuple slot was the one place where
 * {@code [Int:@>0]} was decoration.
 *
 * <p>The cause was not a missing rule but a shape mismatch. {@code NarrowingInference}
 * gives a NAMED record its field-conjunct refinement ({@code [P:@.x==1]}), where the name
 * carries the shape and the conjuncts only add pins — and a tuple literal is a named
 * record, stamped {@code _tuple}. But {@code _tuple} names no shape, so that sort reduced
 * to a bare {@code _tuple} head which is reflexively is-a every tuple sort whatsoever. The
 * by-name face escaped only because inference abstains on a null typeName and the
 * structural floor was reached instead. Both faces now take the same road: the gate reads
 * the literal's own member-wise shape, and {@code Assignability} compares the two shapes
 * member-wise — key sets first, which for keys {@code _0 .. _n} IS the arity rule, so
 * there is one arity check rather than a second one written here.
 *
 * <p>Value cases run on BOTH engines and assert they agree.
 */
class AnonymousTupleSortTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Runs on every engine and asserts they agree; returns the shared result. */
    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "tup.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        String first = null;
        for (PontifRunner.Engine e : PontifRunner.Engine.values()) {
            String out = runner.run(r, e).text();
            if (first == null) {
                first = out;
            } else {
                final String expected = first;
                assertEquals(expected, out, () -> "engines disagree on the same program");
            }
        }
        return first;
    }

    private String reject(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "tup.ptf");
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "expected a compile rejection").error().text();
    }

    // --- the form works ---------------------------------------------------------

    @Test
    void slotsAreDeclaredAndRead() {
        assertEquals("1", run("""
                let t:[{Int, String}] = {1, "a"}
                t._0
                """));
    }

    @Test
    void severalSlots_ofDifferentSorts() {
        assertEquals("3", run("""
                let t:[{Int, Int}] = {1, 2}
                t._0 + t._1
                """));
    }

    @Test
    void satisfiedSlotRefinement_isDischarged() {
        assertEquals("5", run("""
                let t:[{[Int:@>0], Int}] = {5, 2}
                t._0
                """));
    }

    @Test
    void asAStructField() {
        assertEquals("5", run("""
                struct S(t:[{[Int:@>0], Int}])
                S({5, 2}).t._0
                """));
    }

    @Test
    void nestedTuple() {
        assertEquals("7", run("""
                let t:[{[{Int, Int}], Int}] = {{7, 8}, 9}
                t._0._0
                """));
    }

    @Test
    void destructuresLikeAnyTuple() {
        assertEquals("3", run("""
                let t:[{Int, Int}] = {1, 2}
                let [{a, b}] = t
                a + b
                """));
    }

    // --- it does not lie: a slot is a claim, judged --------------------------------

    @Test
    void slotBaseType_isJudged() {
        assertTrue(reject("""
                let t:[{Int, Int}] = {1, "s"}
                t._1
                """).contains("disjoint"), "a slot's base type is a claim, not a decoration");
    }

    /** The sharpest one: a slot was the single place a refinement went unproved. */
    @Test
    void slotRefinement_isProved() {
        assertTrue(reject("""
                let t:[{[Int:@>0], Int}] = {0-5, 2}
                t._0
                """).contains("satisfy"), "a slot refinement is proved, not assumed");
    }

    @Test
    void tooManySlots_isRejected() {
        assertTrue(reject("""
                let t:[{Int, Int}] = {1, 2, 3}
                t._0
                """).contains("satisfy"), "arity is part of the shape");
    }

    @Test
    void tooFewSlots_isRejected() {
        assertTrue(reject("""
                let t:[{Int, Int, Int}] = {1, 2}
                t._0
                """).contains("satisfy"), "arity is part of the shape");
    }

    @Test
    void theSameHoleThroughAStructField_isRejected() {
        assertTrue(reject("""
                struct S(t:[{[Int:@>0], Int}])
                S({0-5, 2})
                """).contains("t"), "the diagnostic should name the offending field");
    }

    @Test
    void nestedSlot_isJudgedToo() {
        assertTrue(reject("""
                let t:[{[{Int, Int}], Int}] = {{1, "s"}, 9}
                t._1
                """).contains("satisfy"), "a nested shape's slots are judged as well");
    }

    // --- the fix does not overshoot ----------------------------------------------

    /**
     * {@code Int → Decimal} is a ruled language feature, not a hole, so a Decimal slot
     * must keep accepting an Int literal. Judging the slot exposed that it had never
     * actually been coerced — the slot claimed Decimal and held a raw Int, which printed
     * as {@code 3} rather than {@code 3.0}. The assertion is on the promoted value for
     * that reason: accepting the program is not enough, it has to mean what it says.
     */
    @Test
    void intIntoADecimalSlot_promotes() {
        assertEquals("3.0", run("""
                let t:[{Decimal, Int}] = {3, 2}
                t._0
                """));
    }

    @Test
    void intIntoADecimalSlot_throughAStructField_promotes() {
        assertEquals("3.0", run("""
                struct S(t:[{Decimal, Int}])
                S({3, 2}).t._0
                """));
    }

    /** A widen into a slot: a value of a sub-struct satisfies a base-struct slot. */
    @Test
    void widenIntoASlot_isAccepted() {
        assertEquals("1", run("""
                struct Base(n:Int)
                struct Sub:Base(n:Int)
                let t:[{Base, Int}] = {Sub(1), 2}
                t._0.n
                """));
    }

    /** An UNDECLARED tuple keeps its previous freedom — nothing claims a shape for it. */
    @Test
    void undeclaredTuple_isUnconstrained() {
        assertEquals("\"s\"", run("""
                let t = {1, "s"}
                t._1
                """));
    }

    /** The by-name face must be unchanged by all of this — it is the same one rule now. */
    @Test
    void byNameFace_isUnaffected() {
        assertEquals("3", run("""
                let p:[{x:Int, y:Int}] = {x = 1, y = 2}
                p.x + p.y
                """));
        assertTrue(reject("""
                let p:[{x:Int}] = {x = "s"}
                p.x
                """).contains("x"), "the by-name face still names the offending member");
    }
}
