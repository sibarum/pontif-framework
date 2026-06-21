package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trait-extends-trait (WAR(stream) slice 1a): {@code trait B : A} makes B extend A,
 * so an {@code assign trait T : B} impl must satisfy A's contract too (the base
 * members merge into B's effective contract — {@code SortChecker.flattenTrait}).
 * The forcing case is {@code IndexedStream : Stream}; here it's exercised with toy
 * traits, independent of the Stream substrate.
 */
class TraitExtendsTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        CompileResult r = compiler.compileAlt(src, "t.ptf");
        CompileResult.Compiled c =
                assertInstanceOf(CompileResult.Compiled.class, r, () -> "expected compile success; got " + r);
        PontifRunner.RunResult rr = runner.run(c.program(), PontifRunner.Engine.INTERPRETER);
        assertTrue(!rr.isError(), () -> "run error: " + rr.text());
        return rr.text();
    }

    @Test
    void implOfSubTrait_mustSatisfyBaseContract() {
        // T : Derived provides only Derived's `b`, not Base's `a` → rejected.
        CompileResult r = compiler.compileAlt("""
                trait Base{ a:[Method():Int] }
                trait Derived:Base{ b:[Method():Int] }
                struct T(x:Int)
                assign trait T:Derived {
                  b():Int -> 2
                }
                T(0)""", "miss.ptf");
        CompileResult.Failed f =
                assertInstanceOf(CompileResult.Failed.class, r, "expected a compile rejection");
        assertTrue(f.error().text().contains("missing method 'a'"),
                () -> "should require the base trait's member; got: " + f.error().text());
    }

    @Test
    void implOfSubTrait_satisfyingBothContracts_compilesAndRuns() {
        // Provides Base's `a` and Derived's `b` → compiles; both callable on the value.
        String result = run("""
                trait Base{ a:[Method():Int] }
                trait Derived:Base{ b:[Method():Int] }
                struct T(x:Int)
                assign trait T:Derived {
                  a():Int -> 10
                  b():Int -> 20
                }
                let t = T(0)
                t.a() + t.b()""");
        assertEquals("30", result, () -> "both inherited and own methods callable; got " + result);
    }

    @Test
    void subTraitExtendingUnknownTrait_isRejected() {
        // `Derived : Missing` where Missing is undeclared → hard error at impl time.
        CompileResult r = compiler.compileAlt("""
                trait Derived:Missing{ b:[Method():Int] }
                struct T(x:Int)
                assign trait T:Derived {
                  b():Int -> 2
                }
                T(0)""", "unknownbase.ptf");
        CompileResult.Failed f =
                assertInstanceOf(CompileResult.Failed.class, r, "expected a compile rejection");
        assertTrue(f.error().text().contains("unknown trait 'Missing'"),
                () -> "should reject extending an undeclared trait; got: " + f.error().text());
    }
}
