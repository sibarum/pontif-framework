package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec-only synthesis: a value-pinning return refinement ({@code [Int:@==EXPR]},
 * including param-referencing) synthesizes the body, AND the synthesized
 * body discharges its own (tautological) return obligation.
 */
class SpecOnlySynthesisTest {

    @Test
    void pinnedValue_referencingParam_synthesizesAndDischarges() {
        // Ackermann base case as a SPEC (no -> body). [Int:y+1] sugars to
        // [Int:@==y+1], which pins the value y+1.
        String src = """
                module ack
                function ackermann(x:[Int:0], y:[Int:@>0]):[Int:y+1];
                ackermann(0, 4)
                """;

        // Body synthesized → ackermann(0,4) evaluates to 5.
        PontifRunner.RunResult run = new PontifRunner().run(
                new PontifCompiler().compileAlt(src, "ack.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertTrue(!run.isError(), () -> "Expected synthesis to run; got " + run.text());
        assertEquals("5", run.text());

        // And the obligation r_0 == y_0+1 discharges against the synthesized
        // body (reflexive, after the return-sort param rename).
        ReceiptGraphReport.Result rep = ReceiptGraphReport.fromAltSource(src, "ack.ptf");
        assertInstanceOf(ReceiptGraphReport.Result.Generated.class, rep);
        String text = ((ReceiptGraphReport.Result.Generated) rep).text();
        System.out.println(text);
        assertTrue(text.contains("r_0: [Int: @ == y_0 + 1]"),
                () -> "return refinement should be renamed to y_0:\n" + text);
        assertTrue(text.contains("-> discharged"),
                () -> "synthesized body should discharge its own obligation:\n" + text);
        assertTrue(!text.contains("NOT DISCHARGED"), () -> text);
    }
}
