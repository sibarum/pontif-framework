package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trait impl assigned to a base struct (`assign trait Base:Trait`) is inherited by every
 * sub-struct (`struct Sub:Base`), the same way {@link sibarum.pontif.types.Assignability} already
 * widens a {@code Sub} value to {@code Base}. Before the fix the impl was keyed to the base's exact
 * nominal name, and four separate engines each failed to walk the struct-inheritance chain to find
 * it: compile-time method routing (a concrete {@code Sub} receiver — cases D/G), the call gate and
 * the runtime param-match (a {@code Base}-typed receiver holding a {@code Sub} value — E/H), and
 * runtime trait dispatch (a trait-typed receiver — F). The letters mirror the diagnostic probe used
 * to scope the fix; A–C (plain assignment, already sound via Assignability's nominal-base widen)
 * guard against regression.
 */
class StructInheritedTraitImplTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** The Expr/BiOp/Leaf hierarchy from the scoping example, minus the final call. */
    private static final String PRELUDE = """
            requires pontif.core.{Stream}
            trait Expr { walk():[Method():Stream[Expr]] }
            struct Leaf()
            struct Residue:Leaf(exp:Int, sign:Bool)
            let zero = Residue(1, false)
            struct BiOp(left:Expr, right:Expr)
            struct Exp:BiOp(left:Expr, right:Expr)
            assign trait Leaf:Expr { walk():Stream[Expr] -> {this} }
            assign trait BiOp:Expr { walk():Stream[Expr] -> this.left.walk() + {this} + this.right.walk() }
            """;

    // Deterministic rendered forms of the leaf/node values (single-file module = "_anonymous").
    private static final String RES = "_anonymous/Residue{exp: 1, sign: false}";
    private static final String EXP = "_anonymous/Exp{left: " + RES + ", right: " + RES + "}";
    /** walk on an Exp(zero,zero): left.walk() ++ {this} ++ right.walk() = {zero, this, zero}. */
    private static final String WALK_EXP = "{" + RES + ", " + EXP + ", " + RES + "}";
    /** walk on a lone Residue leaf: the singleton {this}. */
    private static final String WALK_RESIDUE = "{" + RES + "}";

    private String run(String tail) {
        PontifCompiler.CompileResult r = compiler.compileAlt(PRELUDE + tail, "inherit.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    // --- A–C: plain assignment (the widen Assignability already proved sound) ---------------

    @Test
    void a_subStructAssignsToBaseStruct() {
        assertEquals("0", run("let s:BiOp = Exp(zero, zero)\n0\n"));
    }

    @Test
    void b_subStructAssignsToTraitViaBase() {
        assertEquals("0", run("let s:Expr = Exp(zero, zero)\n0\n"));
    }

    @Test
    void c_leafSubStructAssignsToLeaf() {
        assertEquals("0", run("let s:Leaf = zero\n0\n"));
    }

    // --- D/G: concrete sub-struct receiver — compile-time method routing --------------------

    @Test
    void d_walkOnConcreteExp_inheritsBiOpImpl() {
        assertEquals(WALK_EXP, run("let s:Exp = Exp(zero, zero)\ns.walk()\n"));
    }

    @Test
    void g_walkOnConcreteResidue_inheritsLeafImpl() {
        assertEquals(WALK_RESIDUE, run("let s:Residue = zero\ns.walk()\n"));
    }

    // --- E/H: base-typed receiver holding a sub-struct value — call gate + runtime match ----

    @Test
    void e_walkOnBiOpTypedExp_routes() {
        assertEquals(WALK_EXP, run("let s:BiOp = Exp(zero, zero)\ns.walk()\n"));
    }

    @Test
    void h_walkOnLeafTypedResidue_routes() {
        assertEquals(WALK_RESIDUE, run("let s:Leaf = zero\ns.walk()\n"));
    }

    // --- F: trait-typed receiver — runtime trait dispatch -----------------------------------

    @Test
    void f_walkOnExprTypedExp_dispatchesToInheritedImpl() {
        assertEquals(WALK_EXP, run("let s:Expr = Exp(zero, zero)\ns.walk()\n"));
    }

    @Test
    void f_walkOnExprTypedResidue_dispatchesToInheritedLeafImpl() {
        assertEquals(WALK_RESIDUE, run("let s:Expr = zero\ns.walk()\n"));
    }

    // --- override: a sub-struct's own impl wins over the inherited one -----------------------

    @Test
    void subStructPartialImpl_overridesOneMethod_inheritsBaseForTheRest() {
        // Expr has two methods; `walk` is ABSTRACT (no trait default) and `simplify`
        // defaults. BiOp provides walk; Exp:BiOp provides ONLY simplify and must
        // inherit BiOp.walk. The compile-completeness check credits the base
        // struct's method (the dispatcher already routes there per-method), so the
        // partial override is accepted — and walk/simplify each resolve correctly.
        String prelude = """
                requires pontif.core.{Stream}
                trait Expr {
                  walk():[Method():Stream[Expr]]
                  simplify():[Method():Expr] -> this
                }
                struct Leaf(v:Int)
                assign trait Leaf:Expr { walk():Stream[Expr] -> {this} }
                struct BiOp(left:Expr, right:Expr)
                assign trait BiOp:Expr {
                  walk():Stream[Expr] -> this.left.walk() + this.right.walk() + {this}
                }
                struct Exp:BiOp(left:Expr, right:Expr)
                assign trait Exp:Expr {
                  simplify():[Method():Expr] -> Exp(this.left.simplify(), this.right.simplify())
                }
                let e:Exp = Exp(Leaf(1), Leaf(2))
                """;
        String node = "_anonymous/Exp{left: _anonymous/Leaf{v: 1}, right: _anonymous/Leaf{v: 2}}";
        // walk inherits BiOp's override: left.walk ++ {this} ++ right.walk, in-order.
        assertEquals("{_anonymous/Leaf{v: 1}, _anonymous/Leaf{v: 2}, " + node + "}",
                runOK(prelude + "e.walk()\n"));
        // simplify uses Exp's own override; leaves take the trait default (identity).
        assertEquals(node, runOK(prelude + "e.simplify()\n"));
    }

    @Test
    void missingMethod_noBaseProvider_stillRejected() {
        // walk is abstract and NEITHER the impl nor any base struct provides it →
        // a genuine hole; the completeness check must still reject it.
        String src = """
                requires pontif.core.{Stream}
                trait Expr {
                  walk():[Method():Stream[Expr]]
                  simplify():[Method():Expr] -> this
                }
                struct BiOp(left:Expr, right:Expr)
                assign trait BiOp:Expr {
                  simplify():[Method():Expr] -> this
                }
                0
                """;
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "hole.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "an unprovided abstract method must be rejected");
        assertTrue(((PontifCompiler.CompileResult.Failed) r).error().text().contains("missing method 'walk'"),
                () -> "got: " + ((PontifCompiler.CompileResult.Failed) r).error().text());
    }

    private String runOK(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "partial.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    @Test
    void subStructOwnImplOverridesInheritedBaseImpl() {
        // Residue supplies its OWN walk (empty stream); it must win over Leaf's inherited singleton.
        String prelude = """
                requires pontif.core.{Stream}
                trait Expr { walk():[Method():Stream[Expr]] }
                struct Leaf()
                struct Residue:Leaf(exp:Int, sign:Bool)
                let zero = Residue(1, false)
                assign trait Leaf:Expr { walk():Stream[Expr] -> {this} }
                assign trait Residue:Expr { walk():Stream[Expr] -> {} }
                """;
        PontifCompiler.CompileResult r = compiler.compileAlt(
                prelude + "let s:Residue = zero\ns.walk()\n", "override.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        assertEquals("{}", runner.run(r, PontifRunner.Engine.INTERPRETER).text());
    }
}
