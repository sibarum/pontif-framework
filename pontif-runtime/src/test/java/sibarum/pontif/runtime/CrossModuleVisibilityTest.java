package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.module.ProjectRoot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The import-by-association visibility cutover (Step B): a cross-module operator
 * overload is visible to a module only if that module owns or imports ≥1 of the
 * overload's signature types. Using an operator on a type you didn't import — even
 * with a value obtained from an imported function — is a compile error, not a
 * silent resolution against a globally-visible overload.
 */
class CrossModuleVisibilityTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private static void write(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    /** A dep module owning Vec, its `+`, and a factory `mk` returning a Vec. */
    private static void writeDep(Path root) throws IOException {
        write(root, "vec.ptf", """
                module geo.vec
                exports @.{Vec, mk}
                struct Vec(x:Int, y:Int)
                function mk():Vec -> Vec(1, 2)
                function +(a:Vec, b:Vec):Vec -> Vec(a.x + b.x, a.y + b.y)
                """);
        write(root, ProjectRoot.MARKER, "entry = \"app.use\"\n");
    }

    @Test
    void operatorOnUnimportedType_isRejectedWithMigrationError(@TempDir Path root) throws IOException {
        writeDep(root);
        // Imports only the factory `mk`, not the type `Vec` — so the `+(Vec,Vec)`
        // overload in geo.vec must not be visible here.
        write(root, "app.ptf", """
                module app.use
                requires geo.vec.{mk}
                (mk() + mk()).x
                """);
        CompileResult r = compiler.compileProjectDir(root);
        assertInstanceOf(CompileResult.Failed.class, r, "expected the visibility gate to reject");
        String msg = ((CompileResult.Failed) r).error().text();
        assertTrue(msg.contains("does not import"), () -> msg);
        assertTrue(msg.contains("requires geo.vec.{Vec}"), () -> msg);   // the migration fix
    }

    @Test
    void operatorOnImportedType_resolvesByAssociation(@TempDir Path root) throws IOException {
        writeDep(root);
        // Importing the type Vec brings its associated `+` overload along.
        write(root, "app.ptf", """
                module app.use
                requires geo.vec.{mk, Vec}
                (mk() + mk()).x
                """);
        CompileResult r = compiler.compileProjectDir(root);
        assertInstanceOf(CompileResult.Compiled.class, r, () -> "expected success; got: "
                + (r instanceof CompileResult.Failed f ? f.error().text() : "?"));
        assertEquals("2", runner.run(r, Engine.INTERPRETER).text());   // (1+1, 2+2).x == 2
    }
}
