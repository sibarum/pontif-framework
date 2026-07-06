package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The {@code type Name:[Sort]} declaration — a transparent type alias (a dedicated spelling for what
 * {@code let Name:Type[…]} also produces). Unions, tuples, and compositions of other aliases all inline
 * via {@code AliasResolver}, so an alias is fully interchangeable with its definition. (Methods on
 * aliases — the nominal case — are a later slice; these are pure abbreviations.)
 */
class TypeAliasDeclTest {

    private String run(String src) {
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt(src, "typealias.ptf"), PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "should run; got " + r.text());
        return r.text();
    }

    @Test
    void unionAlias() {
        assertEquals("1", run("""
                type AnyNumber:[Decimal|Int]
                let x:AnyNumber = 1
                x
                """));
    }

    @Test
    void tupleAlias_inlinesTransparently() {
        // `type ThreeTuple:[{3*Decimal}]` — the tuple-alias inlining carve-out; a bare 3-tuple satisfies it.
        assertEquals("6.0", run("""
                type ThreeTuple:[{3*Decimal}]
                let v:ThreeTuple = {1.0, 2.0, 3.0}
                match v { [{a, b, c}] -> a + b + c }
                """));
    }

    @Test
    void tupleAlias_interchangeableWithTheExplicitTuple() {
        // Transparent: a ThreeTuple flows into an explicit-tuple slot and back, no coercion.
        assertEquals("6.0", run("""
                type ThreeTuple:[{3*Decimal}]
                let v:ThreeTuple = {1.0, 2.0, 3.0}
                let w:[{Decimal, Decimal, Decimal}] = v
                match w { [{a, b, c}] -> a + b + c }
                """));
    }

    @Test
    void nestedAlias_resolvesTransitively() {
        // Whatever references two other aliases — AliasResolver pre-resolves each fully.
        assertEquals("7", run("""
                type AnyNumber:[Decimal|Int]
                type ThreeTuple:[{3*Decimal}]
                type Whatever:[AnyNumber|ThreeTuple]
                let x:Whatever = 7
                x
                """));
    }
}
