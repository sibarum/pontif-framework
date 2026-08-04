package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.runtime.PontifRunner.Engine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the load-bearing assumption behind the conductor-seating model (docs/orchestration.md): a
 * <b>required module's {@code main} is inert</b> — only the entry module's {@code main} runs. This is the
 * mechanism {@code ModuleLinker.combine} implements (it concatenates every module's <em>statements</em> —
 * the definitions — but takes {@code main} from the entry module alone), and it is exactly the property a
 * library must have so it cannot activate anything (a window, a worker, a conductor) merely by being imported;
 * activation stays the entry point's privilege.
 */
class RequiredModuleMainInertTest {

    private final PontifCompiler compiler = new PontifCompiler();

    @Test
    void aRequiredModulesMainDoesNotRun_onlyTheEntryModulesDoes() throws sibarum.pontif.parser.ParseException {
        // A "third-party library" whose own main has an observable effect. If required mains ran, importing
        // this would print LIB-RAN.
        IrModule lib = AltParser.parseModule("""
                module lib
                requires pontif.events.{Event, StdOut}
                emit StdOut("LIB-RAN")  0
                """, "lib");
        // The app: its own main is the only one that should fire.
        IrModule app = AltParser.parseModule("""
                module app
                requires pontif.events.{Event, StdOut}
                emit StdOut("APP-RAN")  0
                """, "app");

        PrintStream origOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String output;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            PontifCompiler.CompileResult result =
                    compiler.compileProject(Map.of(lib.name(), lib, app.name(), app), app.name());
            PontifCompiler.CompileResult.Compiled ok = assertInstanceOf(
                    PontifCompiler.CompileResult.Compiled.class, result, () -> "should compile; got " + result);
            new PontifRunner().run(ok.program(), Engine.INTERPRETER);
            output = out.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(origOut);
        }

        assertTrue(output.contains("APP-RAN"), () -> "the entry module's main must run; output was [" + output + "]");
        assertFalse(output.contains("LIB-RAN"),
                () -> "a required module's main must be inert (never activated by import); output was [" + output + "]");
        assertEquals("APP-RAN", output.trim(), "only the entry main's effect appears");
    }
}
