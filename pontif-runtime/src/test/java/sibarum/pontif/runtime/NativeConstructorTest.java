package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.conservation.ConservationDrafter;
import sibarum.pontif.conservation.ConservationGraph;
import sibarum.pontif.conservation.ConservationQueries;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native constructor registry's first tenant: {@code Decimal(unscaled,
 * scale)} — the carrier's true anatomy, total and exact (the bijection
 * contract: no rounding mode, no lossy path; ruled 2026-06-06). Rides the
 * record substrate, so the construction gate covers it and the conservation
 * drafter sees a Construction node. Nominal-only: an anonymous aggregate
 * never matches {@code [Decimal]}.
 */
class NativeConstructorTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src, Engine engine) {
        return runner.run(compiler.compileAlt(src, "t.ptf"), engine);
    }

    private void assertBothEngines(String src, String expected) {
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals(expected, r.text(), engine.toString());
        }
    }

    // --- construction, both engines ------------------------------------------

    @Test
    void constructsFromParts() {
        assertBothEngines("Decimal(25, 1)", "2.5");
        assertBothEngines("Decimal(2567, 3)", "2.567");
    }

    @Test
    void negativeScale_allowed() {
        // Decimal(25, -1) = 25 × 10^1 — totality is the point of the contract.
        assertBothEngines("Decimal(25, -1)", "250.0");
    }

    @Test
    void scaleIsCarried_notNormalized() {
        // Numerically equal, distinct as carriers — the scale is information.
        assertBothEngines("Decimal(250, 2)", "2.50");
        assertBothEngines("Decimal(250, 2) == Decimal(25, 1)", "true");
    }

    @Test
    void byNameForm_works() {
        assertBothEngines("Decimal{unscaled = 25, scale = 1}", "2.5");
    }

    @Test
    void interopsWithDecimalArithmetic() {
        assertBothEngines("Decimal(25, 1) + 0.5", "3.0");
        assertBothEngines("Decimal(1, 0) / Decimal(3, 0) * Decimal(3, 0) ~= Decimal(1, 0)",
                "true");
    }

    // --- the construction gate covers the native shape ------------------------

    @Test
    void decimalUnscaled_isLegal() {
        // unscaled is Decimal (ruled 2026-06-06): a Decimal literal there is
        // silly but lawful — Decimal(2.5, 1) = 2.5 × 10⁻¹.
        for (Engine engine : Engine.values()) {
            RunResult r = run("Decimal(2.5, 1)", engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("0.25", r.text(), engine.toString());
        }
    }

    @Test
    void wrongBasedScaleArgument_isCompileError() {
        // 2.5 is a Decimal — provably disjoint from the scale:Int field.
        CompileResult result = compiler.compileAlt("let z = Decimal(25, 2.5)\n42", "t.ptf");
        CompileResult.Failed failed = assertInstanceOf(CompileResult.Failed.class, result,
                "expected a compile-time rejection");
        assertTrue(failed.error().text().contains("can never satisfy"),
                () -> "Expected the disjoint-construction error; got: " + failed.error().text());
    }

    @Test
    void structNamedDecimal_isRejected() {
        CompileResult result = compiler.compileAlt(
                "struct Decimal(a:Int)\nlet z = Decimal(1)\n42", "t.ptf");
        CompileResult.Failed failed = assertInstanceOf(CompileResult.Failed.class, result,
                "expected the native-name guard");
        assertTrue(failed.error().text().contains("native type"),
                () -> "got: " + failed.error().text());
    }

    // --- nominal-only: anonymous never matches (ruled 2026-06-06) -------------

    @Test
    void anonymousAggregate_doesNotMatchDecimal() {
        String src = """
                let d = {unscaled = 25, scale = 1}
                match d {
                  [Decimal] -> 1
                  _ -> 0
                }
                """;
        assertBothEngines(src, "0");
    }

    @Test
    void constructedValue_matchesDecimal() {
        String src = """
                match Decimal(25, 1) {
                  [Decimal] -> 1
                  _ -> 0
                }
                """;
        assertBothEngines(src, "1");
    }

    // --- conservation: the constructor is a Construction node -----------------

    @Test
    void constructor_isDataConservative() throws Exception {
        // Both parts flow verbatim into the carrier — the bijection's
        // conservation reading, certified by the ruled algebra with no
        // native-specific plumbing.
        var module = AltParser.parseModule("""
                function mk(u:Int, s:Int):Decimal -> Decimal(u, s)
                mk(25, 1)
                """, "t.ptf");
        for (ConservationGraph graph : ConservationDrafter.draft(module).graphs()) {
            if (graph.functionName().equals("mk")) {
                var violation = ConservationQueries.dataConservative(graph);
                assertTrue(violation.isEmpty(),
                        () -> "expected Data-Conservative; got: " + violation);
                return;
            }
        }
        throw new AssertionError("mk's conservation graph not found");
    }
}
