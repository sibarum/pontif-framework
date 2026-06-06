package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streams slice 1 ({@code docs/streams.md}): the Queue, purely — the
 * sequence substrate's inductive view, with deliberately ZERO new machinery.
 * {@code std.stream} declares {@code Element(head, rest:[Element|Leaf])} and
 * re-exports {@code std.common}'s {@code Leaf}; construction, bare-arm
 * sum-type matching over the union, and structural recursion all ride
 * existing rails. No trait, no combinators, no literal sugar — later slices.
 */
class StreamQueueTest {

    private static final String REQUIRES = "requires std.stream.{Element, Leaf}\n";

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private void assertBothEngines(String src, String expected) {
        for (Engine engine : Engine.values()) {
            RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals(expected, r.text(), engine.toString());
        }
    }

    @Test
    void constructsAndReads() {
        assertBothEngines(REQUIRES + """
                let q = Element(1, Element(2, Leaf()))
                q.head
                """, "1");
        assertBothEngines(REQUIRES + """
                let q = Element(1, Element(2, Leaf()))
                q.rest.head
                """, "2");
    }

    @Test
    void bareArmMatch_discriminatesTheUnion() {
        // The canonical sum-type match over [Element|Leaf] — bare arms,
        // totality determined (Tier C), the primitive kind gates keeping the
        // arms honest.
        String classify = REQUIRES + """
                function isEmpty(q:[Element|Leaf]):Int -> match q {
                  [Element] -> 0
                  [Leaf]    -> 1
                }
                isEmpty(%s)
                """;
        assertBothEngines(String.format(classify, "Leaf()"), "1");
        assertBothEngines(String.format(classify, "Element(7, Leaf())"), "0");
    }

    @Test
    void structuralRecursion_consumesTheQueue() {
        // The slice's headline: pure iteration is structural recursion over
        // Element descent — the shape the fixpoint machinery already proves.
        assertBothEngines(REQUIRES + """
                function sum(q:[Element|Leaf]):Int -> match q {
                  [Element] -> q.head + sum(q.rest)
                  [Leaf]    -> 0
                }
                sum(Element(1, Element(2, Element(3, Leaf()))))
                """, "6");
    }

    @Test
    void recursionDepth_isHonest() {
        // Length: the simplest fold-shaped consumer; pins that recursion
        // terminates per element, not per anything cleverer.
        assertBothEngines(REQUIRES + """
                function len(q:[Element|Leaf]):Int -> match q {
                  [Element] -> 1 + len(q.rest)
                  [Leaf]    -> 0
                }
                len(Element(5, Element(5, Element(5, Element(5, Leaf())))))
                """, "4");
    }

    @Test
    void streamLeaf_isProofLeaf_oneNominal() {
        // The shared-Leaf ruling, exercised from the stream door: a Leaf
        // obtained via std.stream matches a pattern imported via std.proof.
        assertBothEngines("""
                requires std.stream.{Element, Leaf}
                requires std.proof.{Leaf -> ProofLeaf}
                match Leaf() {
                  [ProofLeaf] -> 1
                  _ -> 0
                }
                """, "1");
    }

    @Test
    void wrongRest_isCompileError() {
        // The construction gate covering the queue: rest must be
        // [Element|Leaf]; an Int is provably disjoint from both branches.
        CompileResult r = compiler.compileAlt(REQUIRES + """
                let q = Element(1, 2)
                42
                """, "t.ptf");
        CompileResult.Failed failed = assertInstanceOf(CompileResult.Failed.class, r,
                "expected the disjoint-construction rejection");
        assertTrue(failed.error().text().contains("can never satisfy"),
                () -> "got: " + failed.error().text());
    }

    @Test
    void heterogeneousHeads_areAllowedForNow() {
        // head is loose ("_") until [Stream(T)] lands — the element-sort
        // discipline is a later slice, and this pin documents the interim.
        assertBothEngines(REQUIRES + """
                let q = Element(1, Element(true, Leaf()))
                q.head
                """, "1");
    }
}
