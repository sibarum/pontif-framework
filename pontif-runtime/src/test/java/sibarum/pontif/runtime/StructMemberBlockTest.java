package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The struct member block (docs/struct-methods.md): a struct may carry a
 * {@code { name(params):Ret -> body … }} block of compact-form method decls, and
 * an is-a base written as an intersection {@code :[Super & T1 & T2]} naming at
 * most one struct super plus zero or more traits the block must satisfy. Methods
 * are declared once in the block; each declared trait is verified against that
 * one method set.
 */
class StructMemberBlockTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private PontifCompiler.CompileResult compile(String src) {
        return compiler.compile(src, "structblock.ptf");
    }

    private String run(String src) {
        PontifCompiler.CompileResult r = compile(src);
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private String failure(String src) {
        PontifCompiler.CompileResult r = compile(src);
        assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "expected a compile failure");
        return ((PontifCompiler.CompileResult.Failed) r).error().text();
    }

    // --- Slice 1: a block with no traits is just methods on the struct -----------------------

    @Test
    void blockMethodIsCallable() {
        assertEquals("42", run("""
                struct Counter(n:Int) {
                  bumped():Int -> this.n + 1
                }
                let c = Counter(41)
                c.bumped()
                """));
    }

    @Test
    void blockMethodReadsMultipleFields() {
        assertEquals("7", run("""
                struct Pair(a:Int, b:Int) {
                  sum():Int -> this.a + this.b
                }
                Pair(3, 4).sum()
                """));
    }

    @Test
    void blockMethodCallsSiblingMethod() {
        assertEquals("20", run("""
                struct Box(v:Int) {
                  doubled():Int -> this.v + this.v
                  quadrupled():Int -> this.doubled() + this.doubled()
                }
                Box(5).quadrupled()
                """));
    }

    // --- Slice 2/3: an intersection base names traits the block must satisfy ------------------

    private static final String DUCK = "trait Duck { quack:[Method():Int] }\n";

    @Test
    void blockSatisfiesTraitInIntersectionBase() {
        assertEquals("7", run(DUCK + """
                struct Mallard:[Duck](volume:Int) {
                  quack():Int -> this.volume
                }
                let d:Duck = Mallard(7)
                d.quack()
                """));
    }

    @Test
    void blockSatisfiesTraitAlongsideStructSuper() {
        assertEquals("3", run(DUCK + """
                struct Bird(volume:Int)
                struct Mallard:[Bird & Duck](volume:Int) {
                  quack():Int -> this.volume
                }
                let b:Bird = Mallard(3)
                let d:Duck = Mallard(3)
                d.quack()
                """));
    }

    @Test
    void untetheredBlockMethodAlongsideTrait() {
        assertEquals("15", run(DUCK + """
                struct Mallard:[Duck](volume:Int) {
                  quack():Int -> this.volume
                  paddle():Int -> this.volume + this.volume
                }
                Mallard(5).paddle() + Mallard(5).quack()
                """));
    }

    @Test
    void oneMethodSatisfiesTwoTraits() {
        assertEquals("9", run("""
                trait Sized { size:[Method():Int] }
                trait Weighed { size:[Method():Int] }
                struct Crate:[Sized & Weighed](n:Int) {
                  size():Int -> this.n
                }
                let a:Sized = Crate(9)
                let b:Weighed = Crate(9)
                a.size()
                """));
    }

    @Test
    void missingTraitMethodIsRejected() {
        String err = failure(DUCK + """
                struct Mallard:[Duck](volume:Int) {
                  paddle():Int -> this.volume
                }
                Mallard(5).paddle()
                """);
        assertTrue(err.contains("quack"), () -> "expected a missing-'quack' error; got: " + err);
    }

    @Test
    void twoStructSupersRejected() {
        String err = failure("""
                struct A(x:Int)
                struct B(x:Int)
                struct C:[A & B](x:Int) {
                }
                C(1).x
                """);
        assertTrue(err.contains("at most one struct supertype")
                        || err.contains("more than one struct supertype"),
                () -> "expected a one-supertype error; got: " + err);
    }

    @Test
    void traitDefaultFillsOmittedMethodWithBlockPresent() {
        assertEquals("42", run("""
                trait Doubler {
                  base:[Method():Int]
                  doubled():Int -> this.base() + this.base()
                }
                struct T:[Doubler](seed:Int) {
                  base():Int -> this.seed
                }
                T(21).doubled()
                """));
    }
}
