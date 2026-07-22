package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guards for the two type-checker fixes that closing {@code AlgExpr} into a closed
 * union surfaced (docs/recursive-union-typecheck-blowup.md §8) — both about a union type-alias
 * (and a metareference to it) crossing a module boundary. These pin the <em>mechanisms</em>
 * explicitly so a refactor that reintroduces either regression fails here with a clear name,
 * rather than only tripping an incidental semantics test elsewhere.
 *
 * <p>Both scenarios use the real {@code pontif.algebra} / {@code pontif.poly} builtins because
 * both bugs were <b>cross-module</b>: the failing sorts arrive as imported names, which is
 * exactly the case a same-module test cannot reproduce.
 */
class UnionAliasCrossModuleTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private PontifCompiler.CompileResult compile(String src) {
        return compiler.compileAlt(src, "cross-module.ptf");
    }

    private String run(String src) {
        PontifCompiler.CompileResult r = compile(src);
        PontifCompiler.CompileResult.Compiled ok = assertInstanceOf(
                PontifCompiler.CompileResult.Compiled.class, r, () -> "should compile; got " + r);
        return new PontifRunner().run(ok.program(), Engine.INTERPRETER).text();
    }

    // --- Fix 1: the call gate must resolve an imported union alias before it decides routing ----
    // The call gate (PontifCompiler.firstUnprovableCall) ran on the pre-AliasResolver module, so an
    // imported union alias (`AlgExpr`) reached it as a bare Named the refinement kernel could not
    // relate to its member structs. `imply(Add, AlgExpr)` then read as PROVABLY DISJOINT and the
    // call was rejected as a misroute. The fix resolves aliases first, so the gate sees the union.

    @Test
    void memberValueRoutesToImportedUnionAliasParam() {
        // `substitute`'s first parameter is the imported union `AlgExpr`; the argument
        // `Add(Param("x"), Const(1.0))` narrows to the single member `Add`. The call gate must
        // prove `Add ⊑ AlgExpr` and route — pre-fix it rejected this as a provable misroute.
        assertEquals("true", run("""
                requires pontif.poly.{substitute}
                requires pontif.algebra.{AlgExpr, Const, Param, Add, eval}
                eval(substitute(Add(Param("x"), Const(1.0)), "x", Const(5.0)), 0.0) == 6.0
                """));
    }

    @Test
    void wholeUnionValueRoutesToImportedUnionAliasParam() {
        // The reflexive case: a value declared as the whole union (`let src:AlgExpr = …`) passed to
        // a union-alias parameter — `imply(AlgExpr, AlgExpr)`. Guards that union-on-the-left
        // subsumption survives the same cross-module alias resolution.
        assertEquals("true", run("""
                requires pontif.poly.{simplify}
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Sin, eval}
                let src:AlgExpr = Add(Param("x"), Sin(Param("x")))
                eval(simplify(src), 0.0) == eval(src, 0.0)
                """));
    }

    // (No "String into union param must reject" guard: a union looser is RESIDUAL, not FAILED, in
    // the kernel — `imply(String, Const|…|Log)` abstains rather than proving disjoint — and the call
    // gate rejects only on a PROVABLE misroute, so this abstains to runtime by design, not a false
    // accept the alias-resolution fix introduced.)

    // --- Fix 2: `.ast` on a metareference must project the union across a module boundary --------
    // A metareference `$f[…]` narrows to a dispatch-style CallSig (`[AlgebraicDispatch]`). The
    // shared baseName helper handles only Named/Refined, so `.ast`'s attribute-producer lookup got
    // no nominal to key on and the field's static sort was lost — the union claim `let e:AlgExpr =
    // $f[…].ast` then failed the construction gate ("value's sort is not statically known"). The fix
    // reads the nominal from the CallSig's typeName so the producer's `AlgExpr` return is projected.

    @Test
    void metareferenceAstFieldIsTypedAsTheImportedUnion() {
        // `$poly[Decimal].ast` must be statically typed as `AlgExpr` so the union-typed `let` binding
        // discharges at the construction gate — and the value is genuinely an `Add` at its root.
        assertEquals("1", run("""
                requires pontif.algebra.{AlgExpr, Add}
                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic
                let e:AlgExpr = $poly[Decimal].ast
                match e {
                  [Add(_, _)] -> 1
                  [_]         -> 0
                }
                """));
    }

    @Test
    void metareferenceAstProjectionIsSoundForNonAlgebraicReference() {
        // The projection reads a metareference nominal, but it must not fabricate one: `$inc[Int]`
        // is a plain Dispatch (no `assign proof inc:Algebraic`) with no `.ast` producer, so the
        // access is still a compile error — the fix adds a static sort, never a false one.
        PontifCompiler.CompileResult r = compile("""
                requires pontif.algebra.{AlgExpr}
                function inc(n:Int):Int -> n + 1
                let e:AlgExpr = $inc[Int].ast
                e
                """);
        assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                () -> ".ast on a non-algebraic metareference must not type-check; got " + r);
    }
}
