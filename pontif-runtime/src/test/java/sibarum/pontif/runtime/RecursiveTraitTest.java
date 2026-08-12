package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void selfRefNestedInStream_withoutImport_compiles() {
        // A self-referential trait nested in a parametric type — the existential
        // `Stream[Expr]` (a stream of *some* Expr, vs the type-preserving
        // `Stream[this.type]`). The parametric head (Stream) need not be an alias
        // for AliasResolver to descend into its type ARGUMENTS, so the inner `Expr`
        // is shelled and this compiles without an explicit Stream import — the
        // builtin Stream is usable bare, so requiring it here was a wart (James
        // 2026-08-12). Same result as the with-import case below.
        assertEquals("0", run("""
                trait Expr{
                  walkExprTree:[Method():[Stream[Expr]]]
                }
                0
                """));
    }

    @Test
    void selfRefInStream_acrossTraitFunctionAndImpl_withoutImport_runs() {
        // The full pattern, no `requires`: the trait method, an impl method, and a
        // free function all return `Stream[Expr]`. Every one resolves the nested
        // self-referential trait — the fix covers both AliasResolver routines
        // (resolveSort for the trait body, substituteResolved for function/impl
        // signatures). walk flattens the tree in-order.
        assertEquals("{Leaf{v: 1}, Leaf{v: 2}, BiOp{left: Leaf{v: 1}, right: Leaf{v: 2}}}", run("""
                trait Expr {
                  walk:[Method():Stream[Expr]]
                }
                struct Leaf(v:Int)
                assign trait Leaf:Expr { walk():Stream[Expr] -> {this} }
                struct BiOp(left:Expr, right:Expr)
                assign trait BiOp:Expr {
                  walk():Stream[Expr] -> this.left.walk() + this.right.walk() + {this}
                }
                let e:Expr = BiOp(Leaf(1), Leaf(2))
                e.walk()
                """));
    }

    @Test
    void selfRefNestedInStream_withImport_compiles() {
        // With Stream imported, the existential Stream[Expr] resolves and the inner
        // self-reference shells correctly.
        assertEquals("0", run("""
                requires pontif.core.{Stream}
                trait Expr{
                  walkExprTree:[Method():[Stream[Expr]]]
                }
                0
                """));
    }

    @Test
    void recursiveTrait_implementedAndCalled_atTheTraitView() {
        // A concrete type implements the recursive trait; simplify() returns the trait
        // (here `this` coerced to Expr). The trait-typed result is usable AS Expr — the
        // impl compiles and the method call runs.
        assertEquals("0", run("""
                trait Expr{
                  simplify:[Method():Expr]
                }
                struct Lit(value:Int)
                assign trait Lit:Expr {
                  simplify():Expr -> this
                }
                let e:Expr = Lit(5)
                let s:Expr = e.simplify()
                0
                """));
    }

    @Test
    void recursiveTrait_concreteDowncastOfMethodReturn_isCompileError() {
        // Downcasting the method result back to the concrete Lit is rejected: a method
        // return is a "could-be" (simplify():Expr — the concrete behind the Expr can't be
        // proved), so its effective sort is Expr, not Lit. Per James's rule, trait→struct
        // is valid only when the effective sort IS the struct; here it isn't → compile error.
        reject("""
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
                """, "cannot be proved to satisfy");
    }

    /** Asserts a program is rejected at compile time with an error containing {@code needle}. */
    private void reject(String src, String needle) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "rec.ptf");
        PontifCompiler.CompileResult.Failed failed = assertInstanceOf(
                PontifCompiler.CompileResult.Failed.class, r, "expected a compile-time rejection");
        assertTrue(failed.error().text().contains(needle),
                () -> "expected '" + needle + "'; got: " + failed.error().text());
    }
}
