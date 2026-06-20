package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A trait may reference itself in its own member sorts (a recursive trait — an
 * AST/Expr abstraction whose methods return the trait). Before the fix this hit
 * "Cyclic type alias chain: T → T" because AliasResolver inlined the trait and
 * tried to expand the self-reference forever; now the cyclic occurrence resolves
 * to a nominal trait shell, exactly as a recursive struct stays nominal through
 * its constructor.
 */
class RecursiveTraitTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "rec.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    @Test
    void selfReferentialTraitDeclaration_compiles() {
        // The bare declaration alone used to fail with the alias-cycle error.
        assertEquals("0", run("""
                trait Expr{
                  simplify:[Method():Expr]
                }
                0
                """));
    }

    @Test
    void mutuallyRecursiveTraits_compile() {
        assertEquals("0", run("""
                trait Ping{ toPong:[Method():Pong] }
                trait Pong{ toPing:[Method():Ping] }
                0
                """));
    }

    @Test
    void recursiveTrait_implementedAndUsed() {
        // A concrete type implements the recursive trait; its method returns the
        // trait (here, itself coerced to Expr). The result downcasts back.
        assertEquals("5", run("""
                trait Expr{
                  simplify:[Method():Expr]
                }
                struct Lit(value:Int)
                assign trait Lit:Expr {
                  simplify():Expr -> this
                }
                let e:Expr = Lit(5)
                let back:Lit = e.simplify()
                back.value
                """));
    }
}
