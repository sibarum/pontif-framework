package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The first inbound event source (docs/events.md, "Input is an inbound emit"): {@code stdin}
 * as a live, demand-driven source consumed by {@code main}. The pipeline
 * {@code main echo(&stdin())} pulls one line at a time through the lazy iterator (the
 * {@code Iterate} engine's LiveSource pull-loop), runs {@code echo} (which side-{@code emit}s
 * {@code StdOut}), and terminates when EOF seals the source.
 *
 * <p>This is the input counterpart to slice 1b's {@code emit StdOut} output. It proves the
 * crystallized model: laziness lives in the iterator (here {@code main}/the pull-loop), not
 * the stream value; the source's seal ends the loop by construction; and {@code emit} is the
 * effect side-channel woven through an ordinary {@code String → String} map.
 */
class StdinEchoTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private static final String ECHO = """
            requires pontif.events.{StdOut, stdin}
            function echo(line:String):String ->
              emit StdOut(line)
              line
            main echo(&stdin())
            """;

    /** Runs {@code src} with {@code input} on stdin, returning what it wrote to stdout. */
    private String runCapturingStdout(String src, String input) {
        InputStream oldIn = System.in;
        PrintStream oldOut = System.out;
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            CompileResult r = compiler.compileAlt(src, "echo.ptf");
            CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                    () -> "should compile; got " + (r instanceof CompileResult.Failed f ? f.error().text() : r));
            new IrInterpreter(c.program().simplifier()).eval(c.program().module());
            return cap.toString(StandardCharsets.UTF_8);
        } finally {
            System.setIn(oldIn);
            System.setOut(oldOut);
        }
    }

    @Test
    void echoesEachLine_thenTerminatesOnEof() {
        // StdOut.print adds no newline, so the three echoed lines concatenate.
        assertEquals("abc", runCapturingStdout(ECHO, "a\nb\nc\n"));
    }

    @Test
    void emptyInput_emitsNothing_andTerminates() {
        // EOF immediately ⇒ the pull-loop runs zero iterations and the program ends.
        assertEquals("", runCapturingStdout(ECHO, ""));
    }
}
