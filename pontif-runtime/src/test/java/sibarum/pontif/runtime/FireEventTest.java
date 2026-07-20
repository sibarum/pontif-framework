package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The GUI re-entry seam (docs/extensions.md, G2): {@link IrInterpreter#fireEvent} fires an event
 * through the substrate — running its matching {@code action}s — from OUTSIDE the normal
 * {@code emit} flow. This is what a GUI click callback calls (a long-running native re-enters the
 * interpreter). Tested headlessly with no window: build a {@code Ping} event in Java and fire it,
 * asserting the registered action ran (it emits {@code StdOut}, captured here).
 */
class FireEventTest {

    private final PontifCompiler compiler = new PontifCompiler();

    @Test
    void fireEvent_runsMatchingActionsOutsideEmit() {
        CompileResult r = compiler.compileAlt("""
                requires pontif.events.{Event, StdOut}
                struct Ping(n:Int)
                assign trait Ping:Event{}
                action onPing(e:Ping) -> emit StdOut("pinged")  e
                main 0""", "fire.ptf");
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "should compile; got " + (r instanceof CompileResult.Failed f ? f.error().text() : r));

        IrInterpreter interp = new IrInterpreter(c.program().simplifier());
        // A single-file struct carries the "_anonymous/<Name>" qualified type at runtime; the
        // action is keyed by the bare name, and fireEvent's bare-suffix fallback matches it.
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("n", 1L);
        RecordValue ping = new RecordValue("_anonymous/Ping", fields);

        PrintStream origOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            interp.fireEvent(ping, c.program().module(), Origin.NONE);
        } finally {
            System.setOut(origOut);
        }
        assertEquals("pinged", out.toString(StandardCharsets.UTF_8),
                "fireEvent should run the action registered for Ping");
    }
}
