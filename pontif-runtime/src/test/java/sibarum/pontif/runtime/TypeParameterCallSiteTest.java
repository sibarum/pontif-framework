package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free type parameters (docs/type-parameters.md), slice 2b — call-site
 * derivation (§3.1). A parametric function's `[type E]` is recovered at the call
 * by unifying its declared param sorts against the arguments' narrowings, then
 * substituted into the return — so the caller observes the precise result type.
 *
 * <p>Observed through the construction gate: feeding `idd(n):E` (which derives to
 * the argument's narrowing) into a refined field rejects a provably-disjoint
 * value and admits a fitting one. Without the derivation the return would be the
 * opaque `E` and neither verdict could be reached.
 */
class TypeParameterCallSiteTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "tpcs.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compileAlt(src, "tpcs.ptf"), "expected a compile rejection");
    }

    private static final String SETUP = """
            struct Pos(v:[Int:@>0])
            function idd[type E](x:E):E -> x
            """;

    @Test
    void derivedReturn_satisfyingRefinement_compilesAndRuns() {
        // idd(5):E derives E ↦ [Int:@==5]; 5 fits [Int:@>0], so Pos accepts it.
        assertEquals("5", run(SETUP + "Pos(idd(5)).v\n"));
    }

    @Test
    void derivedReturn_disjointFromRefinement_isRejected() {
        // idd(0):E derives E ↦ [Int:@==0]; 0 is disjoint from [Int:@>0]. The
        // rejection proves the call-site derivation flowed into the return —
        // without it the return is the opaque E and the gate could not decide.
        PontifCompiler.CompileResult.Failed f = rejects(SETUP + "Pos(idd(0))\n");
        assertTrue(f.error().text().contains("disjoint"), () -> f.error().text());
    }
}
