package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the reusable sort alias {@code let Name:Type[sort]} — that it
 * resolves at every site mentioning a sort: a parameter, a return, a let's declared sort, a
 * match arm, inside another alias, and inside a function sort.
 *
 * <p>Ported from the S-expression syntax when that parser was decommissioned (its spelling was
 * {@code (deftype Name sort)}). One case did not survive: the original checked that the alias
 * KEYWORD could be rebranded through {@code LanguageDef}, which was configuration for that
 * parser and went with it.
 *
 * <p>Each case runs on both engines, which the original did for only one of them.
 */
class TypeAliasIntegrationTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Runs on both engines, asserts they agree, and returns the shared answer. */
    private String value(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "t.ptf");
        String first = null;
        for (Engine e : Engine.values()) {
            RunResult out = runner.run(r, e);
            assertFalse(out.isError(), () -> "expected success; got: " + out.text());
            if (first == null) {
                first = out.text();
            } else {
                final String expected = first;
                assertEquals(expected, out.text(), () -> "engines disagree on: " + src);
            }
        }
        return first;
    }

    private String reject(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "t.ptf");
        return ((PontifCompiler.CompileResult.Failed) r).error().text();
    }

    @Test
    void aliasToStructSort_usedAsParamType_dispatchesAndDecomposes() {
        assertEquals("7", value("""
                struct P(x:Int, y:Int)
                let Point:Type[P]
                function manhattan(p:Point):Int ->
                  let p.{x, y}
                  x + y
                manhattan(P(3, 4))
                """));
    }

    @Test
    void aliasToRefinedSort_usedAsParamType() {
        assertEquals("10", value("""
                let PosInt:Type[[Int:@>0]]
                function double(n:PosInt):Int -> n * 2
                double(5)
                """));
    }

    @Test
    void aliasUsedInsideAnotherDeclaration_resolvesTransitively() {
        assertEquals("30", value("""
                let Coord:Type[Int]
                struct P(x:Coord, y:Coord)
                function xPlusY(p:P):Int ->
                  let p.{x, y}
                  x + y
                xPlusY(P(10, 20))
                """));
    }

    @Test
    void aliasUsedAsLetBindingDeclaredSort() {
        assertEquals("7", value("""
                struct P(x:Int, y:Int)
                let Point:Type[P]
                let p:Point = P(5, 7)
                p.y
                """));
    }

    @Test
    void aliasUsedAsReturnSort() {
        // The alias NAME differs from the struct's own name, so this also exercises the rule
        // that two names for one declaration are one type.
        assertEquals("0", value("""
                struct P(x:Int, y:Int)
                let Point:Type[P]
                function origin():Point -> P(0, 0)
                origin().x
                """));
    }

    @Test
    void aliasInsideAFunctionSort_resolvesRecursively() {
        // inc(inc(5)) = 7 — the alias stands for the whole call signature.
        assertEquals("7", value("""
                let IntFn:Type[[Method(Int):Int]]
                let inc:IntFn = [(n:Int) -> n + 1]
                inc(inc(5))
                """));
    }

    @Test
    void aliasInAMatchArm_resolvesForMatching() {
        assertEquals("7", value("""
                struct P(x:Int, y:Int)
                let Point:Type[P]
                let p:Point = P(3, 4)
                match p { [Point] -> p.x + p.y }
                """));
    }

    @Test
    void aPrimitiveNameIsNotAnAlias() {
        // Nothing to resolve; a primitive name passes through as itself.
        assertEquals("42", value("""
                function id(n:Int):Int -> n
                id(42)
                """));
    }

    @Test
    void cyclicAliasChain_isACompileError() {
        String err = reject("""
                let A:Type[B]
                let B:Type[A]
                42
                """);
        assertTrue(err.toLowerCase().contains("cycl"), () -> "should mention a cycle; got: " + err);
    }

    @Test
    void duplicateAliasDeclaration_isACompileError() {
        String err = reject("""
                let Foo:Type[Int]
                let Foo:Type[Bool]
                42
                """);
        assertTrue(err.toLowerCase().contains("duplicate"),
                () -> "should mention the duplicate; got: " + err);
    }
}
