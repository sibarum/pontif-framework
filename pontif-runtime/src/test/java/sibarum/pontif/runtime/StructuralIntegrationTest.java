package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for records, field access and decomposition at the source level — the
 * value side of structural sorts, and the compile-time checking that follows a field name
 * through a chain of them.
 *
 * <p>Ported from the S-expression syntax when that parser was decommissioned. Two changes in
 * substance, both improvements, and both noted at the case that shows them: a missing field on
 * a base whose sort was NOT statically inferable used to be a runtime failure and is caught at
 * compile time now; and a binding that shadows an outer one of the same name is rejected
 * outright rather than quietly winning. The original also had two pairs of tests that differed
 * only in their literal values; each pair is one case here.
 *
 * <p>Every case runs on both engines, which the original did for about half of them.
 */
class StructuralIntegrationTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Runs on both engines, asserts they agree, and returns the shared answer. */
    private String value(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "t.ptf");
        assertFalse(r instanceof PontifCompiler.CompileResult.Failed,
                () -> "expected compile success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        String first = null;
        for (Engine e : Engine.values()) {
            RunResult out = runner.run(r, e);
            assertFalse(out.isError(), () -> "expected success; got: " + out.text());
            if (first == null) {
                first = out.text();
            } else {
                final String expected = first;
                assertEquals(expected, out.text(), () -> "engines disagree on: " + src);
            }
        }
        return first;
    }

    /** The compile diagnostic, asserting the program was rejected at compile time. */
    private String reject(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "t.ptf");
        assertTrue(r instanceof PontifCompiler.CompileResult.Failed,
                () -> "expected a compile rejection; got a compiling program");
        return ((PontifCompiler.CompileResult.Failed) r).error().text();
    }

    // --- construction and field access --------------------------------------------

    @Test
    void recordConstructionAndFieldRead() {
        assertEquals("3", value("""
                let p = {x = 3, y = 4}
                p.x
                """));
    }

    @Test
    void recordWithComputedFields_evaluatesEagerly() {
        assertEquals("7", value("""
                let p = {sum = 1 + 2 * 3}
                p.sum
                """));
    }

    @Test
    void nestedRecord_accessedThroughChainedFieldReads() {
        assertEquals("42", value("""
                let o = {inner = {n = 42}}
                o.inner.n
                """));
    }

    @Test
    void aStructsFieldsAreReadTheSameWay() {
        assertEquals("7", value("""
                struct Point(x:Int, y:Int)
                let p = Point(3, 4)
                p.x + p.y
                """));
    }

    @Test
    void emptyStruct_canBeDeclaredAndConstructed() {
        assertEquals("42", value("""
                struct Unit()
                let u = Unit()
                42
                """));
    }

    // --- a field name that does not exist -----------------------------------------

    @Test
    void missingField_onADeclaredStruct_isACompileError() {
        String err = reject("""
                struct P(x:Int)
                let p = P(1)
                p.missing
                """);
        assertTrue(err.contains("missing"), () -> "should name the field; got: " + err);
        assertTrue(err.contains("'P'"), () -> "should name the sort; got: " + err);
    }

    @Test
    void missingField_onAnAnonymousRecord_isACompileError() {
        String err = reject("""
                let p = {x = 1}
                p.missing
                """);
        assertTrue(err.contains("missing") && err.contains("[x]"),
                () -> "should name the field and what IS available; got: " + err);
    }

    @Test
    void missingField_onACallResult_isACompileErrorToo() {
        // The S-expr version expected a RUNTIME failure here, because the base's sort was not
        // statically inferable in that pipeline. It is now: the declared return sort carries.
        String err = reject("""
                struct P(x:Int)
                function mkRec():P -> P(1)
                mkRec().missing
                """);
        assertTrue(err.contains("missing"), () -> "should name the field; got: " + err);
    }

    @Test
    void missingField_inTheInnerLayerOfAChain_isCaught() {
        String err = reject("""
                let o = {inner = {n = 7}}
                o.inner.bogus
                """);
        assertTrue(err.contains("bogus"), () -> "should name the inner field; got: " + err);
    }

    @Test
    void missingField_againstAParametersDeclaredShape_isCaughtInTheBody() {
        // The parameter's shape flows into the body's environment, so the bad access is caught
        // at the definition rather than at a call site.
        String err = reject("""
                function manhattan(p:[{x:Int, y:Int}]):Int -> p.x + p.oops
                manhattan({x = 3, y = 4})
                """);
        assertTrue(err.contains("oops"), () -> "should name the field; got: " + err);
    }

    // --- structural sorts as parameters --------------------------------------------

    @Test
    void functionDeclaredWithAShapeParam_acceptsARecord() {
        assertEquals("7", value("""
                function manhattan(p:[{x:Int, y:Int}]):Int -> p.x + p.y
                manhattan({x = 3, y = 4})
                """));
    }

    // --- decomposition --------------------------------------------------------------

    @Test
    void decompositionBindsFieldNames() {
        assertEquals("7", value("""
                struct Point(x:Int, y:Int)
                let p = Point(3, 4)
                let p.{x, y}
                x + y
                """));
    }

    @Test
    void decompositionOverACallResult_evaluatesItOnce() {
        assertEquals("12", value("""
                struct Pair(x:Int, y:Int)
                function mkPair(a:Int, b:Int):Pair -> Pair(a, b)
                let p = mkPair(5, 7)
                let p.{x, y}
                x + y
                """));
    }

    @Test
    void decompositionRenamesToAvoidACollision() {
        // The S-expr version let an inner binding shadow an outer `x` silently. Pontif rejects
        // shadowing outright, so the rename form — which the decomposition grammar has for
        // exactly this — is how the collision is resolved.
        assertEquals("11", value("""
                let x = 100
                struct P(x:Int)
                let p = P(10)
                let p.{x -> inner}
                inner + 1
                """));
    }

    @Test
    void aBindingThatWouldShadowAnotherIsRejected() {
        String err = reject("""
                let x = 100
                struct P(x:Int)
                let p = P(10)
                let p.{x}
                x + 1
                """);
        assertTrue(err.contains("already defined") || err.toLowerCase().contains("shadow"),
                () -> "should reject the collision; got: " + err);
    }

    @Test
    void decompositionOfANestedRecord_bindsTheTopLevelFieldOnly() {
        // The inner value is bound whole; its own fields are read from it.
        assertEquals("5", value("""
                let o = {inner = {n = 5}}
                let o.{inner}
                inner.n
                """));
    }
}
