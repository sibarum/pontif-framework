package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cluster 2: positional / match / nested / tuple destructuring of an IMPORTED
 * struct. The parser can't see an imported struct's field order/sorts at parse
 * time (requires-linking runs later), so it leaves these patterns DEFERRED;
 * {@link sibarum.pontif.ir.DestructureResolver} resolves them post-link against
 * the combined struct registry, mapping positional slots to declared field
 * names and enforcing the arity-total rule. The {@code .{}} by-name form already
 * worked cross-module — these tests cover the positional forms that didn't, plus
 * the arity-total check (the one rule, now firing for both too-few and too-many
 * in both single- and cross-module forms).
 */
class CrossModuleDestructureTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(Path dir, String entryFile) throws Exception {
        var r = compiler.compileAlt(Files.readString(dir.resolve(entryFile)), entryFile, dir);
        var run = runner.run(r, Engine.INTERPRETER);
        assertFalse(run.isError(), () -> "expected success; got: " + run.text());
        return run.text();
    }

    private String reject(Path dir, String entryFile) throws Exception {
        CompileResult r = compiler.compileAlt(Files.readString(dir.resolve(entryFile)), entryFile, dir);
        assertInstanceOf(CompileResult.Failed.class, r, "expected compile failure");
        return ((CompileResult.Failed) r).error().text();
    }

    private void writeVec(Path dir) throws Exception {
        Files.writeString(dir.resolve("geom.ptf"), """
                module geom.shapes
                exports @.{Vec}
                struct Vec(x:Int, y:Int)
                """);
    }

    @Test
    void positionalParam_ofImportedStruct(@TempDir Path dir) throws Exception {
        writeVec(dir);
        Files.writeString(dir.resolve("entry.ptf"), """
                module app.main
                requires geom.shapes.{Vec}
                function f(v:[Vec(x, y)]):Int -> x + y
                f(Vec(3, 4))
                """);
        assertEquals("7", run(dir, "entry.ptf"));
    }

    @Test
    void positionalParam_renamedBinders(@TempDir Path dir) throws Exception {
        writeVec(dir);
        Files.writeString(dir.resolve("entry.ptf"), """
                module app.main
                requires geom.shapes.{Vec}
                function f(v:[Vec(first, second)]):Int -> first - second
                f(Vec(9, 4))
                """);
        assertEquals("5", run(dir, "entry.ptf"));
    }

    @Test
    void matchDestructure_ofImportedStruct(@TempDir Path dir) throws Exception {
        writeVec(dir);
        Files.writeString(dir.resolve("entry.ptf"), """
                module app.main
                requires geom.shapes.{Vec}
                function f(v:Vec):Int -> match v {
                  [Vec(x, y)] -> x + y
                }
                f(Vec(10, 5))
                """);
        assertEquals("15", run(dir, "entry.ptf"));
    }

    @Test
    void matchDestructure_withDiscardSlot(@TempDir Path dir) throws Exception {
        writeVec(dir);
        Files.writeString(dir.resolve("entry.ptf"), """
                module app.main
                requires geom.shapes.{Vec}
                function f(v:Vec):Int -> match v {
                  [Vec(x, _)] -> x
                }
                f(Vec(10, 5))
                """);
        assertEquals("10", run(dir, "entry.ptf"));
    }

    @Test
    void matchDestructure_withLiteralConstraint(@TempDir Path dir) throws Exception {
        writeVec(dir);
        Files.writeString(dir.resolve("entry.ptf"), """
                module app.main
                requires geom.shapes.{Vec}
                function f(v:Vec):Int -> match v {
                  [Vec(x, 0)] -> x
                  [_] -> -1
                }
                f(Vec(10, 0))
                """);
        assertEquals("10", run(dir, "entry.ptf"));
    }

    @Test
    void nestedMatchDestructure_ofImportedStructs(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("geom.ptf"), """
                module geom.shapes
                exports @.{Inner, Outer}
                struct Inner(x:Int, y:Int)
                struct Outer(inner:Inner, c:Int)
                """);
        Files.writeString(dir.resolve("entry.ptf"), """
                module app.main
                requires geom.shapes.{Inner, Outer}
                function f(o:Outer):Int -> match o {
                  [Outer(Inner(x, y), c)] -> x + y + c
                }
                f(Outer(Inner(2, 3), 4))
                """);
        assertEquals("9", run(dir, "entry.ptf"));
    }

    @Test
    void tupleOfImportedStruct_inMatch(@TempDir Path dir) throws Exception {
        writeVec(dir);
        // A tuple wrapper (non-deferred) nesting an imported (deferred) struct: the
        // parser binds the tuple's plain slot `c`, DestructureResolver binds Vec's.
        Files.writeString(dir.resolve("entry.ptf"), """
                module app.main
                requires geom.shapes.{Vec}
                function f(p:[{Vec, Int}]):Int -> match p {
                  [{Vec(x, y), c}] -> x + y + c
                  [_] -> -1
                }
                f({Vec(1, 2), 3})
                """);
        assertEquals("6", run(dir, "entry.ptf"));
    }

    @Test
    void importedBinder_isMethodReceiver(@TempDir Path dir) throws Exception {
        // The resolved binder carries the imported struct's field sort, so a method
        // on it (cross-module) resolves — the deferred slot is typed, not stuck at `_`.
        Files.writeString(dir.resolve("geom.ptf"), """
                module geom.shapes
                exports @.{Inner, Outer}
                struct Inner(x:Int, y:Int)
                struct Outer(inner:Inner, c:Int)
                method Inner.sum():Int -> this.x + this.y
                """);
        Files.writeString(dir.resolve("entry.ptf"), """
                module app.main
                requires geom.shapes.{Inner, Outer}
                function f(o:[Outer(inner, c)]):Int -> inner.sum() + c
                f(Outer(Inner(3, 4), 5))
                """);
        assertEquals("12", run(dir, "entry.ptf"));
    }

    @Test
    void arityTotal_tooFew_param_isRejected(@TempDir Path dir) throws Exception {
        writeVec(dir);
        // [Vec(x)] over a 2-field struct: lying by omission — rejected post-link.
        Files.writeString(dir.resolve("entry.ptf"), """
                module app.main
                requires geom.shapes.{Vec}
                function f(v:[Vec(x)]):Int -> x
                f(Vec(3, 4))
                """);
        String err = reject(dir, "entry.ptf");
        assertTrue(err.contains("1 of 2 fields") || err.contains("account for every field"),
                () -> err);
    }

    @Test
    void arityTotal_tooMany_match_isRejected(@TempDir Path dir) throws Exception {
        writeVec(dir);
        Files.writeString(dir.resolve("entry.ptf"), """
                module app.main
                requires geom.shapes.{Vec}
                function f(v:Vec):Int -> match v {
                  [Vec(x, y, z)] -> x + y + z
                }
                f(Vec(3, 4))
                """);
        String err = reject(dir, "entry.ptf");
        assertTrue(err.contains("Too many fields for struct"), () -> err);
    }
}
