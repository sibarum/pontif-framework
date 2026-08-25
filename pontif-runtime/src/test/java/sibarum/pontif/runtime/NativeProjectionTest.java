package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.conservation.ConservationDrafter;
import sibarum.pontif.conservation.ConservationGraph;
import sibarum.pontif.conservation.ConservationQueries;
import sibarum.pontif.parser.PontifParser;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Decimal anatomy's projection half (slice 2, ruled 2026-06-06):
 * {@code .unscaled} is the canonical scale-0 integer-valued <b>Decimal</b>
 * (never an Int — the Int→Decimal embedding is a one-way wall, so both
 * projections are TOTAL), {@code .scale} is an Int. The anatomy is recursive
 * with scale-0 self-fixpoints. Destructure {@code [Decimal(u, s)]} is
 * irrefutable; literal-constrained patterns refine over the canonical
 * anatomy; {@code [Decimal:@.scale==2]} refinements are decidable.
 */
class NativeProjectionTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src, Engine engine) {
        return runner.run(compiler.compile(src, "t.ptf"), engine);
    }

    private void assertBothEngines(String src, String expected) {
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals(expected, r.text(), engine.toString());
        }
    }

    // --- projection, both engines ---------------------------------------------

    @Test
    void scaleAndUnscaled_project() {
        assertBothEngines("Decimal(250, 2).scale", "2");
        // unscaled is a Decimal (scale 0), never an Int.
        assertBothEngines("Decimal(250, 2).unscaled", "250.0");
    }

    @Test
    void unscaledIsTotal_beyondIntRange() {
        // 1.0/3.0 carries 34 digits — no Int could hold its unscaled value,
        // but the canonical unscaled is a Decimal: projection is total.
        assertBothEngines("(1.0/3.0).unscaled",
                "3333333333333333333333333333333333.0");
        assertBothEngines("(1.0/3.0).scale", "34");
    }

    @Test
    void recursiveAnatomy_fixpointAtScaleZero() {
        assertBothEngines("Decimal(25, 1).unscaled.scale", "0");
        assertBothEngines("Decimal(25, 1).unscaled.unscaled == Decimal(25, 1).unscaled",
                "true");
    }

    @Test
    void longLiteral_carriesItsScale() {
        // James's probe, formalized: a 62-place literal parses exactly and
        // its anatomy reads back the right scale.
        String tiny = "0.00000000000000000000000000000000000000000000000000000000000001";
        assertBothEngines("let x = " + tiny + "\nx.scale", "62");
        assertBothEngines("let x = " + tiny + "\nx.unscaled", "1.0");
    }

    @Test
    void unknownField_isCompileError() {
        CompileResult result = compiler.compile(
                "function f(d:Decimal):Int -> d.scael\nf(2.5)", "t.ptf");
        CompileResult.Failed failed = assertInstanceOf(CompileResult.Failed.class, result,
                "expected the anatomy typo to be caught");
        assertTrue(failed.error().text().contains("anatomy"),
                () -> "got: " + failed.error().text());
    }

    // --- destructure: irrefutable, canonical -----------------------------------

    @Test
    void destructure_bindsCanonicalAnatomy() {
        String src = """
                match Decimal(250, 2) {
                  [Decimal(u, s)] -> s
                }
                """;
        assertBothEngines(src, "2");
        String src2 = """
                match Decimal(250, 2) {
                  [Decimal(u, s)] -> u
                }
                """;
        assertBothEngines(src2, "250.0");
    }

    @Test
    void literalConstrainedPattern_testsCanonicalAnatomy() {
        // [Decimal(250, s)] constrains the canonical unscaled; 2.50 has
        // canonical unscaled 250, 2.5 has 25.
        String src = """
                function probe(d:Decimal):Int -> match d {
                  [Decimal(250, s)] -> s
                  _ -> -1
                }
                probe(%s)
                """;
        assertBothEngines(String.format(src, "Decimal(250, 2)"), "2");
        assertBothEngines(String.format(src, "Decimal(25, 1)"), "-1");
    }

    // --- refinement over the anatomy -------------------------------------------

    @Test
    void scaleRefinement_dispatches() {
        String src = """
                function f(d:[Decimal:@.scale==2]):Int -> 1
                f(%s)
                """;
        assertBothEngines(String.format(src, "Decimal(250, 2)"), "1");
        for (Engine engine : Engine.values()) {
            RunResult r = run(String.format(src, "Decimal(25, 1)"), engine);
            assertTrue(r.isError(), () -> engine + ": expected dispatch failure");
        }
    }

    @Test
    void scaleRefinement_inMatchArms() {
        String src = """
                function classify(d:Decimal):Int -> match d {
                  [Decimal:@.scale==0] -> 0
                  [Decimal:@.scale==2] -> 2
                  _ -> -1
                }
                classify(%s)
                """;
        assertBothEngines(String.format(src, "Decimal(7, 0)"), "0");
        assertBothEngines(String.format(src, "Decimal(250, 2)"), "2");
        assertBothEngines(String.format(src, "Decimal(25, 1)"), "-1");
    }

    @Test
    void anonymousRecordWithScaleField_doesNotSatisfyDecimalRefinement() {
        // The refined-primitive kind gate: predicate coincidence is not kind. The rule stands;
        // it is enforced at compile time now — a record can never match a scalar arm, so the
        // arm is dead code rather than a branch that quietly loses at runtime (RULED
        // 2026-08-25). The kind gate itself still guards every other site a value meets a sort.
        PontifCompiler.CompileResult r = compiler.compile("""
                let d = {scale = 2}
                match d {
                  [Decimal:@.scale==2] -> 1
                  _ -> 0
                }
                """, "t.ptf");
        String err = ((PontifCompiler.CompileResult.Failed) r).error().text();
        assertTrue(err.contains("can never match"),
                () -> "expected the dead arm to be named; got: " + err);
    }

    // --- conservation: projection is derived content, not UNTOUCHED ------------

    @Test
    void projection_isDerivedContentInTheLedger() throws Exception {
        // f(d) = d.scale: d's content reaches the return DERIVED (the anatomy
        // sliver) — Data-Conservative holds; the atom is not UNTOUCHED.
        // STANCE FLAGGED FOR RED-PEN: anatomy projection drafts as a DEGRADED
        // ARITHMETIC computation; (unscaled, scale) jointly recoverable is a
        // later cross-node fact.
        var module = PontifParser.parseModule("""
                function f(d:Decimal):Int -> d.scale
                f(2.50)
                """, "t.ptf");
        for (ConservationGraph graph : ConservationDrafter.draft(module).graphs()) {
            if (graph.functionName().equals("f")) {
                var violation = ConservationQueries.dataConservative(graph);
                assertTrue(violation.isEmpty(),
                        () -> "expected Data-Conservative; got: " + violation);
                return;
            }
        }
        throw new AssertionError("f's conservation graph not found");
    }
}
