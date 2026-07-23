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
 * `requires $a.b.c` — importing a ptf file that is just an object literal as
 * data. The data file's terminal value is bound under the last FQN segment and
 * typed by its structural effective sort, so field access is statically safe.
 * These are disk-resolved (a data file has no {@code module} header; it is found
 * by literal filename {@code $a.b.c.ptf}), so they run through {@code compileAlt}
 * with a real resolve directory.
 */
class DataRequireTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private void write(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    private CompileResult compile(Path dir, String entrySource) {
        return compiler.compileAlt(entrySource, "app.ptf", dir);
    }

    @Test
    void dataRequire_bindsValue_andFieldAccessIsTypesafe(@TempDir Path dir) throws IOException {
        write(dir, "$demo.config.ptf", "{port=8080, db={host=\"h\", pool=4}}");
        CompileResult r = compile(dir, """
                module app
                requires $demo.config
                config.db.pool
                """);
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected compile success; got: " + ((CompileResult.Failed) r).error().text());
        RunResult run = runner.run(r, Engine.INTERPRETER);
        assertTrue(!run.isError(), () -> "expected success; got: " + run.text());
        assertEquals("4", run.text());
    }

    @Test
    void dataRequire_topLevelField_reads(@TempDir Path dir) throws IOException {
        write(dir, "$demo.config.ptf", "{port=8080, db={host=\"h\", pool=4}}");
        CompileResult r = compile(dir, """
                module app
                requires $demo.config
                config.port
                """);
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected compile success; got: " + ((CompileResult.Failed) r).error().text());
        assertEquals("8080", runner.run(r, Engine.INTERPRETER).text());
    }

    @Test
    void missingField_failsAtCompileTime_parityWithLocalRecord(@TempDir Path dir) throws IOException {
        // Access parity with a local record is the correctness criterion for this
        // slice: a data-required value reads exactly like an in-module record. Both
        // now fail at COMPILE time — field-existence is decided by the value's
        // structural effective sort (the data file's `{host, pool}` shape), so a
        // field it does not carry is rejected before it can run, exactly as a local
        // `let cfg = {…}; cfg.db.missing` is. Not specific to data requires.
        write(dir, "$demo.config.ptf", "{port=8080, db={host=\"h\", pool=4}}");
        CompileResult r = compile(dir, """
                module app
                requires $demo.config
                config.db.missing
                """);
        assertInstanceOf(CompileResult.Failed.class, r,
                "reading a non-existent field is a compile error (parity with local record)");
        String msg = ((CompileResult.Failed) r).error().text();
        assertTrue(msg.contains("has no field 'missing'"),
                () -> "expected missing-field diagnostic; got: " + msg);
    }

    @Test
    void dataFileWithDeclaration_isRejected(@TempDir Path dir) throws IOException {
        // A data file must be a single object literal — a declaration disqualifies it.
        write(dir, "$demo.bad.ptf", """
                function helper(x:Int):Int -> x + 1
                {port=8080}
                """);
        CompileResult r = compile(dir, """
                module app
                requires $demo.bad
                config.port
                """);
        assertInstanceOf(CompileResult.Failed.class, r, "a $-data file with a declaration must be rejected");
        String msg = ((CompileResult.Failed) r).error().text();
        assertTrue(msg.contains("single object literal"), () -> "expected data-file diagnostic; got: " + msg);
    }

    @Test
    void missingDataFile_isReported(@TempDir Path dir) {
        CompileResult r = compile(dir, """
                module app
                requires $demo.absent
                config.port
                """);
        assertInstanceOf(CompileResult.Failed.class, r, "a missing $-data file must be reported");
        String msg = ((CompileResult.Failed) r).error().text();
        assertTrue(msg.contains("$demo.absent.ptf"), () -> "expected filename in diagnostic; got: " + msg);
    }
}
