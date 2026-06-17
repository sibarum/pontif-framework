package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Positional param destructure {@code f(p:[P(a, b)])} binds the struct's fields by
 * position in the body — parity with the {@code .{}} form and match patterns. Only
 * pure-binder patterns over a declared struct are wired; tuple types, field-sort
 * narrowings, and nested patterns are left alone (destructure those with match).
 * Also covers the located unbound-variable compile error (no more runtime
 * NoSuchElementException without a position).
 */
class PositionalParamDestructureTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        var r = runner.run(compiler.compileAlt(src, "p.ptf", null), Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "expected success; got: " + r.text());
        return r.text();
    }

    private String reject(String src) {
        CompileResult r = compiler.compileAlt(src, "p.ptf", null);
        assertInstanceOf(CompileResult.Failed.class, r, "expected compile failure");
        return ((CompileResult.Failed) r).error().text();
    }

    @Test
    void positionalBinders_boundInBody() {
        assertEquals("7", run("""
                struct P(a:Int, b:Int)
                function f(p:[P(x, y)]):Int -> x + y
                f(P(3, 4))
                """));
    }

    @Test
    void binderNamesEqualFieldNames() {
        assertEquals("7", run("""
                struct P(a:Int, b:Int)
                function f(p:[P(a, b)]):Int -> a + b
                f(P(3, 4))
                """));
    }

    @Test
    void worksForOperatorFunctions() {
        assertEquals("4", run("""
                struct P(a:Int, b:Int)
                function +(p1:[P(x, y)], p2:[P(z, w)]):Int -> x + z
                P(1, 2) + P(3, 4)
                """));
    }

    @Test
    void refinedDeclaredFieldStillBinds() {
        // A field whose DECLARED sort is itself refined ([Decimal:@>=0]) is still a
        // pure binder (reference-identity to the declared sort), not a constraint.
        assertEquals("5.0", run("""
                struct Acct(bal:[Decimal:@>=0], r:Decimal)
                function f(x:[Acct(bal, r)]):Decimal -> bal
                f(Acct(5.0, 1.0))
                """));
    }

    @Test
    void tupleTypedParam_stillDestructuredByMatch() {
        // `[(Int, Int)]` is a tuple TYPE, not a destructure — must not be intercepted.
        assertEquals("7", run("""
                function f(p:[(Int, Int)]):Int -> match p {
                  [(a, b)] -> a + b
                }
                f((3, 4))
                """));
    }

    @Test
    void byNameNarrowing_ofNonExistentField_isLocatedError() {
        // [P(x:Int, y:Int)] narrows fields x,y by name, but P's fields are a,b.
        // The author meant the positional binder form [P(x, y)].
        String err = reject("""
                struct P(a:Int, b:Int)
                function f(p:[P(x:Int, y:Int)]):Int -> x + y
                f(P(3, 4))
                """);
        assertTrue(err.contains("no such field"), () -> err);
        assertTrue(err.contains("positional binder"), () -> err);
    }

    @Test
    void byNameNarrowing_ofRealField_stillWorks() {
        // [P(a:[Int:@>0])] narrows the REAL field a — legitimate, not rejected.
        assertEquals("5", run("""
                struct P(a:Int, b:Int)
                function f(p:[P(a:[Int:@>0])]):Int -> p.a
                f(P(5, 9))
                """));
    }

    @Test
    void positionalParam_tooFewFields_isRejected() {
        // [P(a)] over a 2-field struct is lying by omission (verdict B). The
        // arity-total rule must fire for the param form's too-FEW case too, not
        // just the match form's too-many — one rule, one place.
        String err = reject("""
                struct P(a:Int, b:Int)
                function f(p:[P(a)]):Int -> a
                f(P(3, 4))
                """);
        assertTrue(err.contains("1 of 2 fields") || err.contains("account for every field"),
                () -> err);
    }

    @Test
    void unboundVariable_isLocatedCompileError() {
        String err = reject("""
                function f(x:Int):Int -> x + y
                f(1)
                """);
        assertTrue(err.contains("Unbound variable 'y'"), () -> err);
        assertTrue(err.contains(":1:"), () -> "expected a line:col location, got: " + err);
    }
}
