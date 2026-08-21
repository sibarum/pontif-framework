package sibarum.pontif.playground;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for {@link App#computeMarks}: an unresolved name used in the editor buffer
 * must underline the NAME at its use site — not the {@code requires} statements. When a
 * file has any import, the compiler reports the unknown name as a "Link error", which the
 * old heuristic mis-routed to the imports (bug: red underlines on the requires, nothing
 * on the name).
 */
class LiveCompileMarksTest {

    private static final String SRC = "<editor>";

    private static List<App.ErrorMark> marksFor(String content) {
        PontifCompiler.CompileResult r = new PontifCompiler().compile(content, SRC, null);
        assertTrue(r instanceof PontifCompiler.CompileResult.Failed, "program should fail to compile");
        return App.computeMarks(content, SRC, ((PontifCompiler.CompileResult.Failed) r).error());
    }

    @Test
    void unknownFunctionWithImport_underlinesTheName_notTheRequires() {
        String content = """
                requires pontif.core.{Stream}
                main ( nonExistentFn(1) )""";
        List<App.ErrorMark> marks = marksFor(content);
        assertEquals(1, marks.size(), () -> "one mark expected, got " + marks);
        App.ErrorMark m = marks.get(0);
        assertFalse(m.fromImport(), "must not be flagged as an import error");
        assertEquals("nonExistentFn", content.substring(m.start(), m.end()),
                "the underline covers the offending name");
        // ...and it is NOT on the requires line.
        int requiresLineEnd = content.indexOf('\n');
        assertTrue(m.start() > requiresLineEnd, "the mark is past the requires line");
    }

    @Test
    void unknownFunctionWithoutImport_stillUnderlinesTheName() {
        String content = "main ( nonExistentFn(1) )";
        List<App.ErrorMark> marks = marksFor(content);
        assertEquals(1, marks.size());
        assertEquals("nonExistentFn", content.substring(marks.get(0).start(), marks.get(0).end()));
    }
}
