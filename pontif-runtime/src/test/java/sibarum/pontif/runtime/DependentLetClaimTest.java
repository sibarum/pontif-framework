package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Value-in-a-type: a refinement claim whose predicate names an <em>in-scope binding</em>
 * ({@code let x = 5} then {@code let y:[Int:@>=x] = …}) is proved at the construction
 * gate from the referenced binding's own sort — the local-{@code let} counterpart of the
 * call gate's dependent-argument discharge (docs/dependent-sorts.md). The governing rule
 * (James, docs/TODO.md): prove it at compile time; never stamp a runtime check. So:
 * <ul>
 *   <li>a reference the scope pins to a value, or bounds tightly enough to <em>entail</em>
 *       the claim, discharges — the binding is accepted with no runtime check;</li>
 *   <li>a provably-disjoint value is a compile error;</li>
 *   <li>a reference too weak to prove the claim (an unbounded param, a loose range) is a
 *       compile error <em>as-is</em> — never an accept-then-throw-at-runtime.</li>
 * </ul>
 * This replaces the earlier behavior where such a claim compiled and then threw at runtime
 * for every value (a no-lie violation).
 */
class DependentLetClaimTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private void assertRuns(String src, String expected) {
        for (Engine engine : Engine.values()) {
            RunResult r = runner.run(compiler.compile(src, "t.ptf"), engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals(expected, r.text(), engine.toString());
        }
    }

    private String assertCompileError(String src) {
        RunResult r = runner.run(compiler.compile(src, "t.ptf"), Engine.INTERPRETER);
        assertTrue(r.isError() && r.text().startsWith("Compile error"),
                () -> "expected a compile error; got: " + r.text());
        return r.text();
    }

    // --- Reference pinned to a value ------------------------------------------------

    @Test
    void pinnedLet_satisfied_isAccepted() {
        // x pinned to 5; 7 >= 5 holds → discharged, yields the value. (Previously this
        // compiled then threw at runtime for every value.)
        assertRuns("let x:Int = 5\nlet y:[Int:@>=x] = 7\ny", "7");
    }

    @Test
    void pinnedLet_disproven_isACompileError() {
        // 3 >= 5 is false; with x pinned the claim is provably disjoint → compile error.
        String err = assertCompileError("let x:Int = 5\nlet y:[Int:@>=x] = 3\ny");
        assertTrue(err.contains("disjoint") || err.contains("can never satisfy"),
                () -> "expected a disjoint error; got: " + err);
    }

    // --- Reference bounded by a range (proved from its refinement, not its value) ----

    @Test
    void rangeBoundedRef_thatEntailsTheClaim_discharges() {
        // x:[Int:@<=7] entails 7 >= x for EVERY valid x, so the dependent claim is
        // discharged from the reference's refinement — no runtime check, any valid x.
        assertRuns("""
                function f(x:[Int:@<=7]):Int ->
                  let y:[Int:@>=x] = 7
                  y
                f(4)""", "7");
    }

    @Test
    void rangeBoundedRef_tooWeakToProve_isACompileError() {
        // x:[Int:@<=20] does not entail 7 >= x (x could be 8..20) → compile error as-is,
        // NOT a stamped runtime check.
        assertCompileError("""
                function f(x:[Int:@<=20]):Int ->
                  let y:[Int:@>=x] = 7
                  y
                f(4)""");
    }

    @Test
    void unboundedParamRef_isACompileError() {
        // An unbounded reference can never prove the claim → compile error as-is.
        String err = assertCompileError("""
                function f(x:Int):Int ->
                  let y:[Int:@>=x] = 7
                  y
                f(5)""");
        assertTrue(err.contains("dependent") || err.contains("narrow"),
                () -> "expected the dependent-claim guidance; got: " + err);
    }

    // --- Control: a non-dependent claim is unaffected -------------------------------

    @Test
    void nonDependentClaim_stillWorks() {
        assertRuns("let y:[Int:@>0] = 3\ny", "3");
    }
}
