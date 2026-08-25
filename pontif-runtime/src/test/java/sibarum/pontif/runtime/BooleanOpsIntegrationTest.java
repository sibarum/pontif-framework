package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the boolean connectives {@code &} and {@code |} at the value level,
 * and for the refinement predicates built from them — a union of pinned values, a bounded
 * range — which is the headline use.
 *
 * <p>Ported from the S-expression syntax when that parser was decommissioned (its spellings
 * were {@code &&} / {@code ||}). The "rejected value" cases changed shape: they relied on a
 * partial match failing at RUNTIME, and match totality is a compile-time obligation now, so
 * each is a rejection with a diagnostic naming the uncovered value.
 */
class BooleanOpsIntegrationTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Runs on both engines, asserts they agree, and returns the shared answer. */
    private String value(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "t.ptf");
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

    private String reject(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "t.ptf");
        return ((PontifCompiler.CompileResult.Failed) r).error().text();
    }

    // --- the connectives at the value level ---------------------------------------

    @Test
    void andBetweenLiterals_reducesToBoolean() {
        assertEquals("true", value("true & true"));
        assertEquals("false", value("true & false"));
        assertEquals("false", value("false & true"));
        assertEquals("false", value("false & false"));
    }

    @Test
    void orBetweenLiterals_reducesToBoolean() {
        assertEquals("true", value("true | true"));
        assertEquals("true", value("true | false"));
        assertEquals("true", value("false | true"));
        assertEquals("false", value("false | false"));
    }

    @Test
    void andOverComparisons_works() {
        assertEquals("true", value("(3 > 0) & (3 < 10)"));
        assertEquals("false", value("(3 > 0) & (3 < 0 - 1)"));
    }

    @Test
    void orOverComparisons_works() {
        assertEquals("true", value("""
                let n = 1
                (n == 0) | (n == 1)
                """));
    }

    @Test
    void nestedConnectives_agreeAcrossEngines() {
        assertEquals("true", value("(true & false) | (true & true)"));
    }

    // --- refinement predicates built from them ------------------------------------

    @Test
    void unionRefinement_acceptsEveryListedValue() {
        assertEquals("42", value("match 0 { [Int:@==0 | @==1] -> 42  [_] -> 0 }"));
        assertEquals("42", value("match 1 { [Int:@==0 | @==1] -> 42  [_] -> 0 }"));
        assertEquals("0", value("match 2 { [Int:@==0 | @==1] -> 42  [_] -> 0 }"));
    }

    @Test
    void unionRefinementWithoutADefault_isRejectedForTheValueItMisses() {
        // The S-expr version ran this and expected a no-match at runtime. The uncovered value
        // is named at compile time now, which is the same fact caught earlier.
        String err = reject("match 2 { [Int:@==0 | @==1] -> 42 }");
        assertTrue(err.contains("not exhaustive") && err.contains("@ == 2"),
                () -> "expected the uncovered value named; got: " + err);
    }

    @Test
    void unionRefinement_throughDispatch_picksRightOverload() {
        // classify(0) = 100, classify(1) = 100, classify(7) = 200.
        assertEquals("400", value("""
                function classify(n:[Int:@==0 | @==1]):Int -> 100
                function classify(n:Int):Int -> 200
                classify(0) + classify(1) + classify(7)
                """));
    }

    @Test
    void compoundRefinement_andOfBounds_acceptsRange() {
        assertEquals("999", value("match 5 { [Int:@>=0 & @<=10] -> 999  [_] -> 0 }"));
        assertEquals("0", value("match 20 { [Int:@>=0 & @<=10] -> 999  [_] -> 0 }"));
    }
}
