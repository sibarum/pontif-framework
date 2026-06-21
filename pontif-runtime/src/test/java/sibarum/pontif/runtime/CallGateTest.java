package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The call gate (WAR(dependent-sorts) slice 2 step (c)): the dual of the return
 * gate. {@code PontifCompiler} rejects a call whose arguments PROVABLY fail every
 * overload's parameter refinements, and accepts the ones that route or are merely
 * undecided. Like {@link ReturnGateTest}, these assert a real rejection AND a real
 * acceptance through the production compile path — a vacuously-abstaining gate
 * would also leave the suite green.
 */
class CallGateTest {

    private final PontifCompiler compiler = new PontifCompiler();

    @Test
    void rejectsProvablyFailingCall() {
        // h(-3) against [Int:@>0]: -3 provably violates @>0 → no overload routes.
        CompileResult r = compiler.compileAlt(
                "module m\nfunction h(x:[Int:@>0]):Int -> x\nh(-3)", "h.ptf");
        CompileResult.Failed f =
                assertInstanceOf(CompileResult.Failed.class, r, "expected a compile rejection");
        assertTrue(f.error().text().contains("Cannot prove the call to"),
                () -> "unexpected gate message: " + f.error().text());
        assertTrue(f.error().text().contains("'h'"),
                () -> "message should name the offending call: " + f.error().text());
    }

    @Test
    void rejectsDependentParamCall_afterSiblingSubstitution() {
        // g(x:Int, i:[Int:@<x]) called g(5, 7): substitute x↦5 ⇒ i:[Int:@<5]; 7 ⊀ 5.
        // The §0 dependent hole — only provable once the sibling value is pinned.
        CompileResult r = compiler.compileAlt(
                "module m\nfunction g(x:Int, i:[Int:@<x]):Int -> i\ng(5, 7)", "g.ptf");
        CompileResult.Failed f =
                assertInstanceOf(CompileResult.Failed.class, r, "expected a compile rejection");
        assertTrue(f.error().text().contains("'g'"),
                () -> "message should name the offending call: " + f.error().text());
    }

    @Test
    void acceptsProvablyRoutingCall() {
        // h(5): 5 satisfies @>0 → routes → compiles.
        CompileResult r = compiler.compileAlt(
                "module m\nfunction h(x:[Int:@>0]):Int -> x\nh(5)", "h-ok.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "a provably-routing call should compile; got " + r);
    }

    @Test
    void acceptsDependentParamCall_whenSatisfied() {
        // g(5, 3): x↦5 ⇒ i:[Int:@<5]; 3 < 5 → routes → compiles.
        CompileResult r = compiler.compileAlt(
                "module m\nfunction g(x:Int, i:[Int:@<x]):Int -> i\ng(5, 3)", "g-ok.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "a satisfied dependent call should compile; got " + r);
    }

    @Test
    void abstainsOnResidualCall() {
        // pass(n) where n:Int is unrefined: the arg can't be proven to satisfy
        // @>0, but it isn't provably disjoint either → RESIDUAL → the gate
        // abstains (no rejection). Pins that the gate fires ONLY on provable
        // failure, not on every unproven call (the no-lie sweep is a later ruling).
        CompileResult r = compiler.compileAlt("""
                module m
                function pass(x:[Int:@>0]):Int -> x
                function caller(n:Int):Int -> pass(n)
                caller(5)""", "residual.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "a residual (undecided) call must not be rejected yet; got " + r);
    }
}
