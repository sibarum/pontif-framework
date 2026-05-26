package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B end-to-end: alt-syntax source with a struct-refined parameter
 * compiles through {@link PontifCompiler} and runs.
 */
class StructRefinementAltTest {

    private static PontifCompiler.CompileResult compile(String src) {
        return new PontifCompiler().compileAlt(src, "t.ptf");
    }

    @Test
    void structRefinedParam_compiles() {
        String src = """
                struct Point(x:Int, y:Int)
                function originIsh(p:[Point:@.x + @.y > 0]):Int -> 1
                originIsh(Point(2, 3))
                """;
        PontifCompiler.CompileResult result = compile(src);
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, result,
                () -> "Expected Compiled; got " + result);
    }

    @Test
    void structRefinedParam_unknownFieldRejected() {
        String src = """
                struct Point(x:Int, y:Int)
                function badRef(p:[Point:@.z > 0]):Int -> 1
                """;
        PontifCompiler.CompileResult result = compile(src);
        assertInstanceOf(PontifCompiler.CompileResult.Failed.class, result,
                () -> "Expected Failed; got " + result);
        String msg = ((PontifCompiler.CompileResult.Failed) result).error().text();
        assertTrue(msg.contains("@.z") && msg.contains("Point"),
                () -> "Expected error about unknown field @.z on Point; got: " + msg);
    }
}
