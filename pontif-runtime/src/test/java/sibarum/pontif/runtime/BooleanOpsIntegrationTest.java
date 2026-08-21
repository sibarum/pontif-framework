package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the new {@code &&} and {@code ||} binary ops at the
 * source level, plus union-style refinement predicates that use them.
 */
class BooleanOpsIntegrationTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src) {
        return runner.run(compiler.compileSexpr(src, "t.ptf"), Engine.INTERPRETER);
    }

    private RunResult runTruffle(String src) {
        return runner.run(compiler.compileSexpr(src, "t.ptf"), Engine.TRUFFLE);
    }

    // --- Direct evaluation at the value level ---

    @Test
    void andBetweenLiterals_reducesToBoolean() {
        assertEquals("true", run("(module m () (&& true true))").text());
        assertEquals("false", run("(module m () (&& true false))").text());
        assertEquals("false", run("(module m () (&& false true))").text());
        assertEquals("false", run("(module m () (&& false false))").text());
    }

    @Test
    void orBetweenLiterals_reducesToBoolean() {
        assertEquals("true", run("(module m () (|| true true))").text());
        assertEquals("true", run("(module m () (|| true false))").text());
        assertEquals("true", run("(module m () (|| false true))").text());
        assertEquals("false", run("(module m () (|| false false))").text());
    }

    @Test
    void andOverComparisons_works() {
        // (3 > 0) && (3 < 10) = true
        assertEquals("true", run("(module m () (&& (> 3 0) (< 3 10)))").text());
        assertEquals("false", run("(module m () (&& (> 3 0) (< 3 -1)))").text());
    }

    @Test
    void orOverComparisons_works() {
        // (n == 0) || (n == 1) where n = 1 → true
        String src = """
                (module m
                  ()
                  (let n Int 1
                    (|| (== n 0) (== n 1))))
                """;
        assertEquals("true", run(src).text());
    }

    // --- Truffle path parity ---

    @Test
    void truffle_andOr_matchesInterpreter() {
        String src = "(module m () (|| (&& true false) (&& true true)))";
        assertEquals("true", run(src).text());
        assertEquals("true", runTruffle(src).text());
    }

    // --- Union-style refinement predicates: the headline use case ---

    @Test
    void unionRefinement_acceptsListedValues_rejectsOthers() {
        // Refinement predicate uses || to express "self is 0 or 1".
        // Match against this sort succeeds for 0 and 1, fails otherwise.
        String acceptedZero = """
                (module m
                  ()
                  (match 0
                    ((refined Int (|| (== self 0) (== self 1))) 42)))
                """;
        assertEquals("42", run(acceptedZero).text());

        String acceptedOne = """
                (module m
                  ()
                  (match 1
                    ((refined Int (|| (== self 0) (== self 1))) 42)))
                """;
        assertEquals("42", run(acceptedOne).text());

        String rejectedTwo = """
                (module m
                  ()
                  (match 2
                    ((refined Int (|| (== self 0) (== self 1))) 42)))
                """;
        RunResult r = run(rejectedTwo);
        assertTrue(r.isError(), "expected no-match runtime error; got: " + r.text());
    }

    @Test
    void unionRefinement_throughDispatch_picksRightOverload() {
        // Two overloads, one matching {0,1}, the other catching everything else.
        String src = """
                (module m
                  ((defn classify ((n (refined Int (|| (== self 0) (== self 1))))) Int 100)
                   (defn classify ((n Int)) Int 200))
                  (+ (call classify 0) (+ (call classify 1) (call classify 7))))
                """;
        // classify(0) = 100, classify(1) = 100, classify(7) = 200; total = 400
        RunResult r = run(src);
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("400", r.text());
    }

    @Test
    void compoundRefinement_andOfBounds_acceptsRange() {
        // Refined Int representing 0..10 inclusive.
        String acceptedFive = """
                (module m
                  ()
                  (match 5
                    ((refined Int (&& (>= self 0) (<= self 10))) 999)))
                """;
        assertEquals("999", run(acceptedFive).text());

        String rejectedTwenty = """
                (module m
                  ()
                  (match 20
                    ((refined Int (&& (>= self 0) (<= self 10))) 999)))
                """;
        assertTrue(run(rejectedTwenty).isError());
    }
}
