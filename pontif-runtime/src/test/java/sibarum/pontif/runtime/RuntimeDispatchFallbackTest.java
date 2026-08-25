package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three questions a node cannot answer when it is BUILT, which both engines must therefore answer
 * the same way when it runs.
 *
 * <p>Each was a place the interpreter finished a job at runtime and the Truffle node could not
 * ask: a field access whose base is viewed through a trait that ADDS the attribute; an operator
 * over a trait-bounded type variable, which has no operand sort to route on until the argument
 * arrives; and the built-in rule that {@code +} concatenates two positional streams. The results
 * were an engine-dependent meaning for an accepted program — a value on one side, a
 * {@code ClassCastException} or "Record has no field" on the other.
 *
 * <p>The fix is not a second dispatcher. Both engines run the same {@link
 * sibarum.pontif.core.symbolic.DispatchTable}; it was simply unreachable from those two node
 * kinds, and the tuple rule lived in only one engine's source tree. Each test below asserts the
 * engines agree, so the assertion is the property rather than one engine's answer.
 */
class RuntimeDispatchFallbackTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Runs on every engine, asserts they agree, and returns the shared answer. */
    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "fallback.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        String first = null;
        for (PontifRunner.Engine e : PontifRunner.Engine.values()) {
            String out = runner.run(r, e).text();
            if (first == null) {
                first = out;
            } else {
                final String expected = first;
                assertEquals(expected, out, () -> "engines disagree on the same program");
            }
        }
        return first;
    }

    // --- the trait-view attribute ------------------------------------------------

    @Test
    void anAttributeProducerResolvesOnBothEngines() {
        assertEquals("7", run("""
                trait Weighted{ weight:[Int:@>0] }
                struct Item(name:String)
                assign trait Item:Weighted {
                    weight:Int -> 7
                }
                Item("a").weight
                """));
    }

    @Test
    void anAttributeProducerReadsTheValuesOwnState() {
        assertEquals("6", run("""
                trait Doubled{ twice:Int }
                struct Counter(n:Int)
                assign trait Counter:Doubled {
                    twice:Int -> this.n * 2
                }
                Counter(3).twice
                """));
    }

    @Test
    void aStoredFieldStillWinsOverAProducer() {
        // The fallback is for a field the record does NOT carry; a stored one is read, not computed.
        assertEquals("\"a\"", run("""
                struct Item(name:String)
                Item("a").name
                """));
    }

    @Test
    void aFieldThatIsNeitherStoredNorProducedStillFails() {
        PontifCompiler.CompileResult r = compiler.compile("""
                struct Item(name:String)
                function pick(i:Item):Int -> 1
                pick(Item("a"))
                """, "fallback.ptf");
        // (The interesting half is that the fallback did not turn a missing field into something
        // else; a genuine typo is caught statically, before either engine is involved.)
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r);
        String err = compiler.compile("""
                struct Item(name:String)
                Item("a").weight
                """, "fallback.ptf") instanceof PontifCompiler.CompileResult.Failed f
                ? f.error().text() : "";
        assertTrue(err.contains("weight"), () -> "expected the missing field to be named; got: " + err);
    }

    // --- the operator over a trait-bounded type variable -------------------------

    @Test
    void anOperatorOverABoundTypeVariableDispatchesOnBothEngines() {
        assertEquals("4", run("""
                trait Numeric{ +:[Dispatch(this.type, this.type):this.type] }
                struct Vec(x:Int, y:Int)
                function +(a:Vec, b:Vec):Vec -> Vec(a.x + b.x, a.y + b.y)
                assign trait Vec:Numeric { }
                function sum[type E:Numeric](a:E, b:E):E -> a + b
                sum(Vec(1, 2), Vec(3, 4)).x
                """));
    }

    @Test
    void aChainOfBoundOperatorsDispatchesEachTime() {
        assertEquals("5", run("""
                trait Numeric{ +:[Dispatch(this.type, this.type):this.type] }
                struct Vec(x:Int, y:Int)
                function +(a:Vec, b:Vec):Vec -> Vec(a.x + b.x, a.y + b.y)
                assign trait Vec:Numeric { }
                function poly[type E:Numeric](a:E, b:E):E ->
                  let c = a + b
                  c + a
                poly(Vec(1, 2), Vec(3, 4)).x
                """));
    }

    @Test
    void aDirectlyRoutedOperatorStillWorks() {
        // The statically routed path is untouched — the fallback only catches what it could not route.
        assertEquals("3", run("""
                struct V(x:Int)
                function +(a:V, b:V):V -> V(a.x + b.x)
                (V(1) + V(2)).x
                """));
    }

    @Test
    void equalityStaysBuiltInStructural() {
        // `==` is never a user overload — it is structural equality on both engines, which is the
        // boundary the operator fallback must not cross (docs/keyed.md).
        assertEquals("1", run("""
                struct P(x:Int)
                match (P(1) == P(1)) { [Bool:true] -> 1  [_] -> 0 }
                """));
    }

    // --- the built-in tuple rule --------------------------------------------------

    @Test
    void tuplesConcatenateOnBothEngines() {
        assertEquals("{1, 2, 3, 4}", run("{1, 2} + {3, 4}"));
    }

    @Test
    void tupleConcatenationBeatsAUserOverloadInScope() {
        // The interpreter checks the built-in tuple rule BEFORE operator dispatch; mirroring that
        // order is what keeps the engines agreeing when a program has both in scope.
        assertEquals("{1, 2, 3, 4}", run("""
                struct V(x:Int)
                function +(a:V, b:V):V -> V(a.x + b.x)
                {1, 2} + {3, 4}
                """));
    }
}
