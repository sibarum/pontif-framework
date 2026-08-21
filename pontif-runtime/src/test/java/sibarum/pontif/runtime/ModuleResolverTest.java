package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demand-driven single-file resolution ({@code compile(src, name, dir)}):
 * the entry buffer's {@code requires} closure is loaded from {@code dir}, and
 * <b>only</b> that closure — so an unrelated unparseable sibling never breaks a
 * script that doesn't import it. The whole-project counterpart lives in
 * {@link ModuleLoaderTest}.
 */
class ModuleResolverTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private static void write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent() == null ? dir : file.getParent());
        Files.writeString(file, content);
    }

    private RunResult run(String entry, Path dir) {
        CompileResult r = compiler.compile(entry, "entry", dir);
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected compile; got: " + ((CompileResult.Failed) r).error().text());
        return runner.run(r, Engine.INTERPRETER);
    }

    private String rejected(String entry, Path dir) {
        CompileResult r = compiler.compile(entry, "entry", dir);
        assertInstanceOf(CompileResult.Failed.class, r, "expected compile failure");
        return ((CompileResult.Failed) r).error().text();
    }

    private static final String LIB = """
            module lib
            exports @.{inc}
            function inc(x:Int):Int -> x + 1
            """;

    /** Deliberately unparseable; declares a module header so it's still indexable. */
    private static final String BROKEN = """
            module broken
            function nope(x:Int):Int ->
              @@@ (((  not valid
            """;

    @Test
    void requiresSibling_resolvesAndRuns(@TempDir Path dir) throws IOException {
        write(dir, "lib.ptf", LIB);
        String entry = """
                module app
                requires lib.{inc}
                inc(41)
                """;
        assertEquals("42", run(entry, dir).text());
    }

    @Test
    void unrelatedBrokenSibling_isIgnored(@TempDir Path dir) throws IOException {
        // The crux: a broken file the entry does NOT require must not stop the run.
        write(dir, "lib.ptf", LIB);
        write(dir, "broken.ptf", BROKEN);
        String entry = """
                module app
                requires lib.{inc}
                inc(41)
                """;
        assertEquals("42", run(entry, dir).text());
    }

    @Test
    void noRequires_unaffectedByBrokenSibling(@TempDir Path dir) throws IOException {
        write(dir, "broken.ptf", BROKEN);
        // No requires → bare single-file path; the directory is never scanned.
        assertEquals("9", run("4 + 5", dir).text());
    }

    @Test
    void requiredModuleMissing_isError(@TempDir Path dir) throws IOException {
        write(dir, "lib.ptf", LIB);
        String entry = """
                module app
                requires ghost.{inc}
                inc(41)
                """;
        String err = rejected(entry, dir);
        assertTrue(err.contains("ghost") && err.toLowerCase().contains("not found"), () -> err);
    }

    @Test
    void requiredModuleBroken_isError(@TempDir Path dir) throws IOException {
        // When the broken module IS required, that's a real error (names it).
        write(dir, "broken.ptf", BROKEN);
        String entry = """
                module app
                requires broken.{nope}
                nope(1)
                """;
        String err = rejected(entry, dir);
        assertTrue(err.contains("broken") && err.toLowerCase().contains("parse"), () -> err);
    }

    @Test
    void sameNamespaceSibling_visibleWithoutRequires(@TempDir Path dir) throws IOException {
        // The editor scenario: a trait lives in one file and its impl/use in another,
        // both declaring the SAME namespace. They must see each other with no `requires`
        // (you cannot require your own namespace) — the resolver folds same-namespace
        // siblings into the entry unit before checking.
        write(dir, "expr.ptf", """
                module poly
                trait Expr { eval:[Method():Int] }
                """);
        String entry = """
                module poly
                struct Lit(v:Int)
                assign trait Lit:Expr { eval():Int -> this.v }
                Lit(41).eval()
                """;
        assertEquals("41", run(entry, dir).text());
    }

    @Test
    void sameNamespaceSibling_structConstructedAcrossFiles(@TempDir Path dir) throws IOException {
        // Harder cross-file case: the entry constructs a struct DEFINED in a sibling file.
        // The buffer parsed `Lit(41)` as a bare call (it couldn't see the sibling's struct);
        // after the fold, StructLiteralRewriter's unique-bare-alias resolves it to a Record.
        write(dir, "types.ptf", """
                module poly
                struct Lit(v:Int)
                """);
        String entry = """
                module poly
                Lit(41).v
                """;
        assertEquals("41", run(entry, dir).text());
    }

    @Test
    void entryFileOnDisk_notDoubleIncluded(@TempDir Path dir) throws IOException {
        // The entry buffer's own file is among the namespace's on-disk files; it must be
        // excluded from the fold (identified by sourceName) so its definitions aren't
        // merged twice. Here `a.ptf` defines f and is compiled as the buffer; `b.ptf` is
        // its sibling. A double-include would surface as a duplicate-definition error.
        String a = "module poly\nfunction f(x:Int):Int -> x + 1\n0";
        write(dir, "a.ptf", a);
        write(dir, "b.ptf", "module poly\nfunction g(x:Int):Int -> f(x) * 2\ng(20)");
        CompileResult r = compiler.compile(a, "a.ptf", dir);
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected compile; got: " + ((CompileResult.Failed) r).error().text());
        assertEquals("42", runner.run(r, Engine.INTERPRETER).text());
    }

    @Test
    void transitiveRequires_resolved(@TempDir Path dir) throws IOException {
        write(dir, "base.ptf", """
                module base
                exports @.{seed}
                function seed():Int -> 10
                """);
        write(dir, "lib.ptf", """
                module lib
                requires base.{seed}
                exports @.{plus}
                function plus(x:Int):Int -> x + seed()
                """);
        String entry = """
                module app
                requires lib.{plus}
                plus(32)
                """;
        assertEquals("42", run(entry, dir).text());
    }
}
