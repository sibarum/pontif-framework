package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The return-refinement gate (step B): {@code PontifCompiler} rejects a
 * declared return the proof system can't discharge, and accepts the ones it
 * can. Proves the gate actually enforces — a vacuously-abstaining gate would
 * also leave the suite green, so these assert a real rejection and a real
 * acceptance through the production compile path.
 */
class ReturnGateTest {

    private final PontifCompiler compiler = new PontifCompiler();

    @Test
    void rejectsUnprovableReturn() {
        // bad(x:Int):[Int:@>0] -> x is false for x<=0; the engine can't prove
        // it and no proof is supplied → the gate rejects at compile time.
        CompileResult r = compiler.compileAlt(
                "module m\nfunction bad(x:Int):[Int:@>0] -> x\nbad(5)", "bad.ptf");
        CompileResult.Failed f =
                assertInstanceOf(CompileResult.Failed.class, r, "expected a compile rejection");
        assertTrue(f.error().text().contains("Cannot prove the declared return refinement"),
                () -> "unexpected gate message: " + f.error().text());
        assertTrue(f.error().text().contains("bad"),
                () -> "message should name the offending function: " + f.error().text());
    }

    @Test
    void acceptsProvableThresholdReturn() {
        // inc raises x>=1 to x>1 — provable; the gate lets it through.
        CompileResult r = compiler.compileAlt(
                "module m\nfunction inc(x:[Int:@>=1]):[Int:@>1] -> x + 1\ninc(5)", "inc.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "provable return should compile; got " + r);
    }

    @Test
    void acceptsProvableInductiveReturn() {
        // factorial's [Int:@>=1] closes inductively via the back-reference.
        CompileResult r = compiler.compileAlt("""
                module m
                function factorial(n:[Int:@>=0]):[Int:@>=1] -> match n {
                  [@==0] -> 1
                  [@>0]  -> n * factorial(n-1)
                }
                factorial(5)""", "fact.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "inductive return should compile; got " + r);
    }

    @Test
    void acceptsBareReturnUnaffected() {
        // No refined return → nothing to prove → unaffected by the gate.
        CompileResult r = compiler.compileAlt(
                "module m\nfunction add(a:Int, b:Int):Int -> a + b\nadd(2, 3)", "add.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r);
    }
}
