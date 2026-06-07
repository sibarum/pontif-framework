package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Streams slice 2a ({@code docs/streams.md}): the combinator basis over the
 * Queue — {@code singleton}/{@code concat}/{@code append}/{@code map}/
 * {@code exchange}/{@code partition} — written in Pontif source inside
 * {@code std.stream} (the first builtin that is itself Pontif), with
 * function arguments as metareferences invoked by application. Interim
 * leniencies pinned: loose function params (Dispatch-key subsumption is a
 * later ruling) and ledger-residual combinator bodies.
 */
class StreamCombinatorTest {

    private static final String PRELUDE = """
            requires std.stream.{Element, Leaf, singleton, concat, append, map, exchange, partition}
            function sum(q:[Element|Leaf]):Int -> match q {
              [Element] -> q.head + sum(q.rest)
              [Leaf]    -> 0
            }
            """;

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
    void singleton_isPure() {
        assertBothEngines(PRELUDE + "singleton(7).head", "7");
        assertBothEngines(PRELUDE + "sum(singleton(7))", "7");
    }

    @Test
    void concat_isTheMonoidOp() {
        assertBothEngines(PRELUDE + """
                sum(concat(Element(1, Element(2, Leaf())), Element(3, Leaf())))
                """, "6");
        // Leaf() is the identity, both sides.
        assertBothEngines(PRELUDE + "sum(concat(Leaf(), singleton(5)))", "5");
        assertBothEngines(PRELUDE + "sum(concat(singleton(5), Leaf()))", "5");
    }

    @Test
    void append_isConcatSingleton() {
        assertBothEngines(PRELUDE + """
                sum(append(Element(1, Element(2, Leaf())), 3))
                """, "6");
    }

    @Test
    void map_appliesAMetareference() {
        assertBothEngines(PRELUDE + """
                function inc(x:Int):Int -> x + 1
                sum(map($inc[Int], Element(1, Element(2, Element(3, Leaf())))))
                """, "9");
    }

    @Test
    void exchange_modifiesMatchesInPlace_remainderIsTotal() {
        // Elements > 2 get doubled; the rest ride through untouched — the
        // result is the FULL stream: 1 + 2 + 6 + 8.
        assertBothEngines(PRELUDE + """
                function isBig(x:Int):Bool -> x > 2
                function double(x:Int):Int -> x * 2
                sum(exchange($isBig[Int], $double[Int],
                    Element(1, Element(2, Element(3, Element(4, Leaf()))))))
                """, "17");
        // Order preserved, non-matching untouched.
        assertBothEngines(PRELUDE + """
                function isBig(x:Int):Bool -> x > 2
                function double(x:Int):Int -> x * 2
                exchange($isBig[Int], $double[Int],
                    Element(1, Element(3, Leaf()))).rest.head
                """, "6");
    }

    @Test
    void partition_returnsBothHalves() {
        String src = PRELUDE + """
                function isBig(x:Int):Bool -> x > 2
                match partition($isBig[Int],
                    Element(1, Element(3, Element(2, Element(4, Leaf()))))) {
                  [(yes, no)] -> %s
                }
                """;
        assertBothEngines(String.format(src, "sum(yes)"), "7");
        assertBothEngines(String.format(src, "sum(no)"), "3");
    }

    @Test
    void pipeline_composes() {
        // exchange ∘ map ∘ concat — a real pipeline through the basis.
        assertBothEngines(PRELUDE + """
                function inc(x:Int):Int -> x + 1
                function isBig(x:Int):Bool -> x > 2
                function double(x:Int):Int -> x * 2
                sum(exchange($isBig[Int], $double[Int],
                    map($inc[Int], concat(singleton(1), singleton(2)))))
                """, "8");
    }
}
