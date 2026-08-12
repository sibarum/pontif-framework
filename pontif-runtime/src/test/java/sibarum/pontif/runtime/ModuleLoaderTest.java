package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;
import sibarum.pontif.runtime.module.ProjectRoot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end project loading from disk: write {@code .ptf} files (and an
 * optional {@code module.toml} marker) under a temp root, then
 * {@code compileProjectDir} → discover + parse + link + run.
 */
class ModuleLoaderTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private static void write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent() == null ? dir : file.getParent());
        Files.writeString(file, content);
    }

    private RunResult run(Path root) {
        CompileResult r = compiler.compileProjectDir(root);
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected project to compile; got: " + ((CompileResult.Failed) r).error().text());
        return runner.run(r, Engine.INTERPRETER);
    }

    private String rejected(Path root) {
        CompileResult r = compiler.compileProjectDir(root);
        assertInstanceOf(CompileResult.Failed.class, r, "expected project compile failure");
        return ((CompileResult.Failed) r).error().text();
    }

    @Test
    void projectWithMarkerEntry_linksAndRuns(@TempDir Path root) throws IOException {
        write(root, "lib.ptf", """
                module lib
                exports @.{inc}
                function inc(x:Int):Int -> x + 1
                """);
        write(root, "app.ptf", """
                module app
                requires lib.{inc}
                inc(41)
                """);
        write(root, ProjectRoot.MARKER, "entry = \"app\"\n");
        assertEquals("42", run(root).text());
    }

    @Test
    void entryInferredFromSoleMain_noMarker(@TempDir Path root) throws IOException {
        // lib has no trailing main; app does → app is the entry, inferred.
        write(root, "lib.ptf", """
                module lib
                exports @.{dbl}
                function dbl(x:Int):Int -> x * 2
                """);
        write(root, "app.ptf", """
                module app
                requires lib.{dbl}
                dbl(21)
                """);
        assertEquals("42", run(root).text());
    }

    @Test
    void moduleInSubdirectory_isDiscovered(@TempDir Path root) throws IOException {
        write(root, "sub/lib.ptf", """
                module lib
                exports @.{inc}
                function inc(x:Int):Int -> x + 1
                """);
        write(root, "app.ptf", """
                module app
                requires lib.{inc}
                inc(7)
                """);
        assertEquals("8", run(root).text());
    }

    @Test
    void sameNamespaceAcrossFiles_mergesAndSeesEachOther(@TempDir Path root) throws IOException {
        // Two files declaring the same namespace are folded into one module: names are
        // mutually visible with no `requires`, and the one file carrying a real `main`
        // supplies the entry expression.
        write(root, "a.ptf", "module pkg\nfunction f(x:Int):Int -> x\n0");
        write(root, "b.ptf", "module pkg\nfunction g(x:Int):Int -> f(x) + 1\ng(7)");
        assertEquals("8", run(root).text());
    }

    @Test
    void unknownMarkerEntry_isError(@TempDir Path root) throws IOException {
        write(root, "lib.ptf", "module lib\nfunction f(x:Int):Int -> x\n0");
        write(root, ProjectRoot.MARKER, "entry = \"ghost\"\n");
        String err = rejected(root);
        assertTrue(err.contains("ghost"), () -> err);
    }
}
