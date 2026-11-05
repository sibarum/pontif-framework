package sibarum.pontif.tooling;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.tooling.PontifProblems.Problem;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a failed compile is marked. The first two were the playground's {@code LiveCompileMarksTest}: an unresolved
 * name used in the buffer underlines the name at its use site, not the {@code requires} statements (a file with any
 * import reports it as a "Link error", which an older heuristic sent to the imports).
 */
class PontifProblemsTest {

    private static final String SRC = "<editor>";
    private static final PontifCompiler COMPILER = new PontifCompiler();

    private static List<Problem> problems(String content) {
        return PontifProblems.check(COMPILER, content, SRC, null);
    }

    @Test
    void unknownFunctionWithImport_underlinesTheName_notTheRequires() {
        String content = """
                requires pontif.core.{Stream}
                main ( nonExistentFn(1) )""";
        List<Problem> marks = problems(content);
        assertEquals(1, marks.size(), () -> "one mark expected, got " + marks);
        Problem m = marks.getFirst();
        assertFalse(m.fromImport(), "must not be flagged as an import error");
        assertEquals("nonExistentFn", content.substring(m.start(), m.end()), "the underline covers the name");
        assertTrue(m.start() > content.indexOf('\n'), "the mark is past the requires line");
    }

    @Test
    void unknownFunctionWithoutImport_stillUnderlinesTheName() {
        String content = "main ( nonExistentFn(1) )";
        List<Problem> marks = problems(content);
        assertEquals(1, marks.size());
        assertEquals("nonExistentFn", content.substring(marks.getFirst().start(), marks.getFirst().end()));
    }

    @Test
    void aCleanProgramHasNoProblems() {
        assertEquals(List.of(), problems("function greet(n:Int):Int -> n + 1\n\ngreet(41)\n"));
    }

    @Test
    void anUnexpectedEndOfFileIsMarkedOnTheLastThingTyped() {
        String content = "function greet(n:Int):Int -> n + 1\n\ngreet(\n";
        List<Problem> marks = problems(content);
        assertEquals(1, marks.size(), () -> "got " + marks);
        Problem m = marks.getFirst();
        assertTrue(m.message().startsWith("Parse error"), m.message());
        assertEquals("greet", content.substring(m.start(), m.end()), "the unfinished call, by its name");
    }

    @Test
    void anImportThatCannotBeFoundMarksItsRequires() {
        String content = "requires no.such.module.{thing}\n\nthing(1)\n";
        List<Problem> marks = problems(content);
        assertFalse(marks.isEmpty(), "the missing module is marked");
        Problem m = marks.getFirst();
        assertEquals("requires no.such.module.{thing}", content.substring(m.start(), m.end()), m.message());
    }

    @Test
    void aCarriageReturnIsNotPartOfTheMarkedRequires() {
        String content = "requires no.such.module.{thing}\r\n\r\nthing(1)\r\n";
        Problem m = problems(content).getFirst();
        assertEquals("requires no.such.module.{thing}", content.substring(m.start(), m.end()));
    }

    /** An editor checks on a pool, so two compiles may run at once; they must not disturb each other. */
    @Test
    void compilesAtOnceAgreeWithCompilesOneAtATime() throws Exception {
        String good = "function greet(n:Int):Int -> n + 1\n\ngreet(41)\n";
        String bad = "main ( nonExistentFn(1) )";
        List<Problem> expected = problems(bad);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<List<Problem>>> work = IntStream.range(0, 64)
                    .<Callable<List<Problem>>>mapToObj(i -> () -> problems(i % 2 == 0 ? good : bad)).toList();
            List<Future<List<Problem>>> done = pool.invokeAll(work);
            for (int i = 0; i < done.size(); i++) {
                assertEquals(i % 2 == 0 ? List.of() : expected, done.get(i).get(), "compile " + i);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
