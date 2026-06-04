package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conservation receipts, Slice 2 — the assertion surface and the real gate.
 * `proof f = <property>` with std.conservation vocabulary: zero new syntax,
 * the tree's head picks the ledger, and a failing assertion is a compile
 * error whose body includes the printed ledger node (the error IS the
 * receipt). Property names provisional.
 */
class ConservationGateTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String compileError(String src) {
        CompileResult r = compiler.compileAlt(src, "t.ptf");
        assertInstanceOf(CompileResult.Failed.class, r, "expected the gate to reject");
        return ((CompileResult.Failed) r).error().text();
    }

    private String runOk(String src) {
        CompileResult r = compiler.compileAlt(src, "t.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected compile success; got: " + ((CompileResult.Failed) r).error().text());
        return runner.run(r, Engine.INTERPRETER).text();
    }

    private static final String LOSSY_TRANSLATE = """
            struct Source(name:Int, age:Int, email:Int)
            struct Target(fullName:Int, years:Int)
            function translate(s:Source):Target -> {fullName = s.name, years = s.age + 1}
            """;

    private static final String FIXED_TRANSLATE = """
            struct Source(name:Int, age:Int, email:Int)
            struct Target(fullName:Int, years:Int, contact:Int)
            function translate(s:Source):Target ->
              {fullName = s.name, years = s.age + 1, contact = s.email}
            """;

    // --- the lossless gate ---

    @Test
    void lossyTranslation_failsTheGate_withTheReceiptInTheError() {
        String err = compileError("""
                requires std.conservation.{DataConservative}
                """ + LOSSY_TRANSLATE + """
                proof translate = DataConservative()
                translate(Source(1, 2, 3)).years
                """);
        assertTrue(err.contains("Conservation proof"), () -> err);
        assertTrue(err.contains("email"), () -> err);
        assertTrue(err.contains("UNTOUCHED"), () -> "the error should BE the receipt:\n" + err);
    }

    @Test
    void fixedTranslation_passesTheGate_andRuns() {
        assertEquals("3", runOk("""
                requires std.conservation.{DataConservative}
                """ + FIXED_TRANSLATE + """
                proof translate = DataConservative()
                translate(Source(1, 2, 3)).years
                """));
    }

    // --- reversibility + duplication ---

    @Test
    void swap_provesReversible() {
        assertEquals("1", runOk("""
                requires std.conservation.{Reversible}
                function swap(p:[(Int, Bool)]):[(Bool, Int)] ->
                  match p { [(a, b)] -> (b, a) }
                proof swap = Reversible()
                let [(x, y)] = swap((1, true)) y
                """));
    }

    @Test
    void duplication_failsNoDuplication() {
        String err = compileError("""
                requires std.conservation.{NoDuplication}
                function dup(p:[(Int, Bool)]):[(Int, Int)] ->
                  match p { [(a, _)] -> (a, a) }
                proof dup = NoDuplication()
                let [(x, y)] = dup((1, true)) x
                """);
        assertTrue(err.contains("more than once"), () -> err);
    }

    // --- intentional erasure: the stale-proof pair (future changes are protected) ---

    @Test
    void declaredDrop_makesTheLossyTranslationCompile() {
        assertEquals("3", runOk("""
                requires std.conservation.{DataConservativeExcept}
                """ + LOSSY_TRANSLATE + """
                proof translate = DataConservativeExcept(s.email)
                translate(Source(1, 2, 3)).years
                """));
    }

    @Test
    void fixingTheDrop_makesTheDeclarationStale_andFails() {
        String err = compileError("""
                requires std.conservation.{DataConservativeExcept}
                """ + FIXED_TRANSLATE + """
                proof translate = DataConservativeExcept(s.email)
                translate(Source(1, 2, 3)).years
                """);
        assertTrue(err.contains("stale"), () -> err);
    }

    // --- coexistence: one proof statement, two ledgers ---

    @Test
    void algebraicAndConservationProofs_coexist() {
        assertEquals("3", runOk("""
                requires std.proof.{Leaf, Split}
                requires std.conservation.{DataConservative}
                """ + FIXED_TRANSLATE + """
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                proof f = Split(x>=1, Leaf(), Leaf())
                proof translate = DataConservative()
                translate(Source(1, 2, 3)).years
                """));
    }

    // --- problems are clear compile errors ---

    @Test
    void proofForUnknownFunction_isRejected() {
        String err = compileError("""
                requires std.conservation.{DataConservative}
                proof nothere = DataConservative()
                0
                """);
        assertTrue(err.contains("unknown function"), () -> err);
    }

    @Test
    void nonPathArgument_isRejected() {
        String err = compileError("""
                requires std.conservation.{DataConservativeExcept}
                """ + LOSSY_TRANSLATE + """
                proof translate = DataConservativeExcept(1 + 1)
                0
                """);
        assertTrue(err.contains("attribute expression"), () -> err);
    }

    // --- residual honesty at the gate: the located ignorance never certifies ---

    @Test
    void residualFlow_neverCertifies() {
        // Recursion stays residual (the fixpoint is a later slice) — flow
        // through it can never pass a conservation assertion.
        String err = compileError("""
                requires std.conservation.{DataConservative}
                function fact(n:Int):Int -> match n {
                  [@==0] -> 1
                  _ -> n * fact(n - 1)
                }
                proof fact = DataConservative()
                fact(3)
                """);
        assertTrue(err.contains("Conservation proof"), () -> err);
    }

    @Test
    void nestedDiscrimination_nowTraced_failsOnTheMerits() {
        // v1 called this OPAQUE; the algebra traces it. It still fails — x is
        // genuinely dropped on the else path — but the error now names the
        // honest reason instead of shrugging.
        String err = compileError("""
                requires std.conservation.{DataConservative}
                function f(x:Int, y:Int):Int ->
                  let inner = match y { [@>0] -> x
                  _ -> 0 }
                  inner + y
                proof f = DataConservative()
                f(1, 2)
                """);
        assertTrue(err.contains("Conservation proof"), () -> err);
        assertTrue(err.contains("x_0"), () -> err);
    }
}
