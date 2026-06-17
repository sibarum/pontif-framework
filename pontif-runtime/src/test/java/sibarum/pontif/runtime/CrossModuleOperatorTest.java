package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.pontif.runtime.PontifRunner.Engine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Operators dispatch globally across module boundaries (dispatch-unification Phase 1):
 * a function in module B can use module A's operator overload on an imported type. The
 * routing decision is made post-link by {@link sibarum.pontif.ir.OperatorResolver},
 * which resolves the operator to its (FQN-after-linking) overload by operand sort —
 * something the per-file parser can't do. Regression for the {@code tractioncd.ptf}
 * case (a {@code TractionCD} {@code +} body that calls the imported {@code Traction}'s
 * {@code *}/{@code +} once gave a runtime {@code ClassCastException}).
 */
class CrossModuleOperatorTest {

    @Test
    void importedTypeOperator_dispatchesAndChains(@TempDir Path dir) throws Exception {
        // Module A: a Vec type with `+` and `*` operators (global multi-dispatch).
        Files.writeString(dir.resolve("vec.ptf"), """
                module geom.vec
                exports @.{Vec}
                struct Vec(x:Int, y:Int)
                function +(a:Vec, b:Vec):Vec -> Vec(a.x + b.x, a.y + b.y)
                function *(a:Vec, b:Vec):Vec -> Vec(a.x * b.x, a.y * b.y)
                """);
        // Module B: imports Vec, and its own `+` body uses A's `*` and `+` on Vec —
        // a chain (`a*b + b*a`) so the inner op's dispatched result type feeds the outer.
        Files.writeString(dir.resolve("blend.ptf"), """
                module geom.blend
                requires geom.vec.{Vec}
                function blend(a:Vec, b:Vec):Vec -> a*b + b*a
                blend(Vec(1, 2), Vec(3, 4)).x
                """);

        String src = Files.readString(dir.resolve("blend.ptf"));
        var r = new PontifCompiler().compileAlt(src, "blend.ptf", dir);
        var run = new PontifRunner().run(r, Engine.INTERPRETER);
        assertFalse(run.isError(), () -> "expected success; got: " + run.text());
        // a*b = (3,8); b*a = (3,8); sum.x = 6.
        assertEquals("6", run.text());
    }

    @Test
    void divisionOperatorResult_isAMethodReceiver(@TempDir Path dir) throws Exception {
        // A module-qualified (FQN'd) `/` operator with a METHOD on its result —
        // `(a / b).sum()`, the tractioncd shape. Regression for two slash-family
        // bugs in resolving the FQN of the `/` call (linked name `module//`): the
        // operator-symbol split (OperatorResolver.simpleName) and the linker's
        // call-name resolver (NameResolver.resolveCallName) both treated the
        // leading '/' as a module separator / "already an FQN", leaving the `/`
        // call's type unresolvable so its method receiver couldn't be typed.
        Files.writeString(dir.resolve("vec.ptf"), """
                module geom.vec
                struct Vec(x:Int, y:Int)
                function /(a:Vec, b:Vec):Vec -> Vec(a.x / b.x, a.y / b.y)
                method Vec.sum():Int -> this.x + this.y
                (Vec(8, 9) / Vec(2, 3)).sum()
                """);

        var r = new PontifCompiler().compileAlt(
                Files.readString(dir.resolve("vec.ptf")), "vec.ptf", dir);
        var run = new PontifRunner().run(r, Engine.INTERPRETER);
        assertFalse(run.isError(), () -> "expected success; got: " + run.text());
        // (8/2, 9/3) = (4, 3); .sum() = 7.
        assertEquals("7", run.text());
    }
}
