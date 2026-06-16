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
 * Demand-driven single-file resolution ({@code compileAlt(src, name, dir)}):
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
        CompileResult r = compiler.compileAlt(entry, "entry", dir);
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected compile; got: " + ((CompileResult.Failed) r).error().text());
        return runner.run(r, Engine.INTERPRETER);
    }

    private String rejected(String entry, Path dir) {
        CompileResult r = compiler.compileAlt(entry, "entry", dir);
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
