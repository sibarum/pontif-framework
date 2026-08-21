package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Nested destructuring patterns — struct/tuple components nest arbitrarily, and
 * a refinement may constrain a struct field positionally, bringing struct-field
 * patterns to parity with tuple components. Inner binders reach the arm body
 * through the recursive destructure desugar; the matcher already recurses on
 * member sorts. Covers match arms, type positions, and top-level destructuring lets.
 */
class NestedDestructureTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "nest.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    @Test
    void structInStruct() {
        assertEquals("6", run("""
                struct Inner(a:Int, b:Int)
                struct Outer(inner:Inner, c:Int)
                function f(o:Outer):Int -> match o { [Outer(Inner(x, y), c)] -> x+y+c  [_] -> -1 }
                f(Outer(Inner(1,2), 3))"""));
    }

    @Test
    void tupleInStruct() {
        assertEquals("6", run("""
                struct Pair(t:[{Int, Int}], c:Int)
                function f(p:Pair):Int -> match p { [Pair({a, b}, c)] -> a+b+c  [_] -> -1 }
                f(Pair({1,2}, 3))"""));
    }

    @Test
    void tupleInTuple() {
        assertEquals("6", run("""
                function f(p:[{{Int, Int}, Int}]):Int -> match p { [{{a, b}, c}] -> a+b+c  [_] -> -1 }
                f({{1,2}, 3})"""));
    }

    @Test
    void structInStructInTuple() {
        assertEquals("10", run("""
                struct Inner(a:Int, b:Int)
                struct Mid(inner:Inner, c:Int)
                function f(p:[{Mid, Int}]):Int -> match p { [{Mid(Inner(x, y), c), z}] -> x+y+c+z  [_] -> -1 }
                f({Mid(Inner(1,2),3), 4})"""));
    }

    @Test
    void refinedConstraintInStructField() {
        // Parity with tuples: [P([Int:@>0], y)] constrains slot a, binds y.
        assertEquals("3", run("""
                struct P(a:Int, b:Int)
                function f(p:P):Int -> match p { [P([Int:@>0], y)] -> y  [_] -> -1 }
                f(P(5, 3))"""));
    }

    @Test
    void refinedConstraint_noMatchFallsThrough() {
        assertEquals("-1", run("""
                struct P(a:Int, b:Int)
                function f(p:P):Int -> match p { [P([Int:@>0], y)] -> y  [_] -> -1 }
                f(P(-5, 3))"""));
    }

    @Test
    void topLevelLet_structInStruct() {
        assertEquals("6", run("""
                struct Inner(a:Int, b:Int)
                struct Outer(inner:Inner, c:Int)
                let [Outer(Inner(x, y), c)] = Outer(Inner(1,2), 3)
                x + y + c"""));
    }

    @Test
    void topLevelLet_tupleInTuple() {
        assertEquals("6", run("""
                let [{{a, b}, c}] = {{1, 2}, 3}
                a + b + c"""));
    }
}
