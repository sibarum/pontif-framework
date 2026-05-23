package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for {@code (deftype Name sort)} at the source level.
 * Verifies that aliases resolve correctly through every site that mentions
 * a sort: function params, return sorts, let-binding declared sorts,
 * match-branch patterns, and nested compound sorts.
 */
class TypeAliasIntegrationTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src) {
        return runner.run(compiler.compile(src, "t.ptf"), Engine.INTERPRETER);
    }

    private RunResult runTruffle(String src) {
        return runner.run(compiler.compile(src, "t.ptf"), Engine.TRUFFLE);
    }

    @Test
    void aliasToStructuralSort_usedAsParamType_dispatchesAndDestructures() throws Exception {
        String src = """
                (module m
                  ((deftype Point (struct P (x Int) (y Int)))
                   (defn manhattan ((p Point)) Int
                     (match p
                       ((struct P (x Int) (y Int)) (+ x y)))))
                  (call manhattan (record (x 3) (y 4))))
                """;
        assertEquals("7", run(src).text());
        assertEquals("7", runTruffle(src).text());
    }

    @Test
    void aliasToRefinedSort_usedAsParamType() throws Exception {
        String src = """
                (module m
                  ((deftype PosInt (refined Int (> self 0)))
                   (defn double ((n PosInt)) Int (* n 2)))
                  (call double 5))
                """;
        assertEquals("10", run(src).text());
    }

    @Test
    void aliasUsedInsideAnotherAlias_resolvesTransitively() throws Exception {
        String src = """
                (module m
                  ((deftype Coord Int)
                   (deftype Point (struct P (x Coord) (y Coord)))
                   (defn xPlusY ((p Point)) Int
                     (match p
                       ((struct P (x Int) (y Int)) (+ x y)))))
                  (call xPlusY (record (x 10) (y 20))))
                """;
        assertEquals("30", run(src).text());
    }

    @Test
    void aliasUsedAsLetBindingDeclaredSort() throws Exception {
        String src = """
                (module m
                  ((deftype Point (struct P (x Int) (y Int))))
                  (let p Point (record (x 5) (y 7))
                    (field p y)))
                """;
        assertEquals("7", run(src).text());
    }

    @Test
    void aliasUsedAsReturnSort() throws Exception {
        String src = """
                (module m
                  ((deftype Point (struct P (x Int) (y Int)))
                   (defn origin () Point (record (x 0) (y 0))))
                  (field (call origin) x))
                """;
        assertEquals("0", run(src).text());
    }

    @Test
    void aliasInsideFunctionSort_resolvesRecursively() throws Exception {
        // The function-sort form's param and return positions get resolved.
        // (Local binding of a closure used, not dispatch — passing a closure
        // through dispatch hits a separate toSymExpr limitation, see TODO.)
        String src = """
                (module m
                  ((deftype IntFn (function (Int) Int)))
                  (let inc IntFn (lambda ((n Int)) Int (+ n 1))
                    (call inc (call inc 5))))
                """;
        // inc(inc(5)) = 7
        assertEquals("7", run(src).text());
    }

    @Test
    void unknownNamedSortIsLeftAlone_notAnError() throws Exception {
        // Primitive sort names like Int / Bool aren't aliases. The resolver
        // leaves them as Named sorts. Custom names not declared as aliases
        // also fall through.
        String src = """
                (module m
                  ((defn id ((n Int)) Int n))
                  (call id 42))
                """;
        assertEquals("42", run(src).text());
    }

    @Test
    void cyclicAliasChain_isACompileError() throws Exception {
        String src = """
                (module m
                  ((deftype A B)
                   (deftype B A))
                  42)
                """;
        RunResult r = run(src);
        assertTrue(r.isError(), "expected compile error");
        assertTrue(r.text().toLowerCase().contains("cycl"),
                "should mention cycle; got: " + r.text());
    }

    @Test
    void duplicateAliasDeclaration_isACompileError() throws Exception {
        String src = """
                (module m
                  ((deftype Foo Int)
                   (deftype Foo Bool))
                  42)
                """;
        RunResult r = run(src);
        assertTrue(r.isError(), "expected compile error");
        assertTrue(r.text().toLowerCase().contains("duplicate"),
                "should mention duplicate; got: " + r.text());
    }

    @Test
    void aliasInsideMatchBranchPattern_resolvesForMatching() throws Exception {
        // The match pattern itself is an IrSort — alias references in branch
        // patterns resolve at compile time, so the pattern correctly identifies
        // the record's shape. (Field destructuring through an alias-named
        // pattern doesn't work — the parser-time destructuring desugar runs
        // before alias resolution; see TODO. Branch body uses explicit field
        // access instead.)
        String src = """
                (module m
                  ((deftype Point (struct P (x Int) (y Int))))
                  (let p Point (record (x 3) (y 4))
                    (match p
                      (Point (+ (field p x) (field p y))))))
                """;
        assertEquals("7", run(src).text());
    }

    @Test
    void aliasKeyword_canBeRebrandedViaLanguageDef() throws Exception {
        sibarum.pontif.parser.LanguageDef def =
                sibarum.pontif.parser.LanguageDef.defaults().withTypeAliasKeyword("type");
        PontifCompiler customCompiler = new PontifCompiler(def, PontifCompiler.defaultRules());
        String src = """
                (module m
                  ((type PosInt (refined Int (> self 0)))
                   (defn double ((n PosInt)) Int (* n 2)))
                  (call double 5))
                """;
        RunResult r = runner.run(customCompiler.compile(src, "t.ptf"), Engine.INTERPRETER);
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("10", r.text());
    }
}
