package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The narrow-in-place tuple-pattern binder {@code name:Sort} — the third cell of the
 * tuple-component grid. A bare {@code a} binds the whole component but tests nothing;
 * a {@code Sort(fields)} tests the sort and opens the value up into its fields; the
 * gap was "test the sort AND keep the whole (narrowed) value bound" — the
 * conditional-cast use case. {@code a:Lit} now fills it: the arm fires only when the
 * component is a {@code Lit}, and {@code a} is bound as a {@code Lit} so {@code a.v}
 * type-checks and resolves.
 */
class TupleNarrowBinderTest {

    private final PontifCompiler compiler = new PontifCompiler();

    @Test
    void narrowedBinderTestsSortAndBindsWholeValue() {
        // Two-branch union scrutinee; the narrowed arm fires only for (Lit, Lit), and
        // inside it a/b are typed Lit so their `.v` field reads resolve.
        String program = """
                struct Lit(v:Int)
                struct Wrap(inner:Int)
                let Node:Type[Lit | Wrap]
                function combine(x:Node, y:Node):Int -> match {x, y} {
                  [{a:Lit, b:Lit}] -> a.v + b.v
                  [_]              -> 0
                }
                """;
        // Both Lit → the narrowed arm fires, whole values bound and field-read.
        assertEquals("7", run(program + "combine(Lit(3), Lit(4))\n"));
        // A non-Lit component → the sort test fails, fall through to the default arm.
        assertEquals("0", run(program + "combine(Lit(3), Wrap(4))\n"));
        assertEquals("0", run(program + "combine(Wrap(3), Lit(4))\n"));
    }

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "tuple-narrow-binder.ptf");
        PontifCompiler.CompileResult.Compiled compiled =
                assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                        () -> "should compile; got " + r);
        return new PontifRunner().run(compiled.program(), Engine.INTERPRETER).text();
    }
}
