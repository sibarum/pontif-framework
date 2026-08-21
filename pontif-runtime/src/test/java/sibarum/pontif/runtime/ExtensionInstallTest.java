package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.NativeFunctions;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;
import sibarum.pontif.runtime.module.Extension;
import sibarum.pontif.runtime.module.Extensions;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The extension API (docs/extensions.md): a fresh, non-builtin {@link Extension} can be installed
 * and its Pontif-written interface links + its associated Java effect fires — proving the side-
 * effect channel works for arbitrary extensions, not just the default {@code IoExtension}. (The
 * native-call path is exercised end-to-end by {@code StdinEchoTest}, which now flows through
 * {@code NativeCalls}.)
 */
class ExtensionInstallTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** A toy extension: a {@code Note} event whose effect echoes its text to stdout. */
    private static final class NoteExtension implements Extension {
        @Override public String moduleName() { return "test.notes"; }
        @Override public String pontifSource() {
            return """
                    requires pontif.events.{Event}
                    exports @.{Note}
                    struct Note(text:String)
                    assign trait Note:Event{}
                    0
                    """;
        }
        @Override public Map<String, NativeFunctions.Effect> effects() {
            return Map.of("Note", (event, origin) -> System.out.print(textOf(event)));
        }
        private static String textOf(RecordValue event) {
            return event.members().get("text") instanceof StringValue s ? s.content() : "";
        }
    }

    @Test
    void installedExtension_linksItsModule_andItsEffectFires() {
        Extensions.install(new NoteExtension());

        PrintStream origOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            RunResult r = runner.run(
                    compiler.compile("""
                            requires test.notes.{Note}
                            main ( emit Note("from an extension")  0 )""", "ext.ptf"),
                    Engine.INTERPRETER);
            assertFalse(r.isError(), () -> "program errored: " + r.text());
        } finally {
            System.setOut(origOut);
        }
        assertEquals("from an extension", out.toString(StandardCharsets.UTF_8));
    }
}
