package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Field-existence is decided by a value's EFFECTIVE sort, not by whether that
 * sort is nominal. An anonymous local record is minted with a closed member set,
 * so accessing a field it does not carry is a COMPILE error — the same gate a
 * nominal struct gets — rather than a deferred runtime {@code RuntimeCheckException}.
 */
class LocalRecordFieldSafetyTest {

    private static PontifCompiler.CompileResult compile(String src) {
        return new PontifCompiler().compileAlt(src, "t.ptf");
    }

    @Test
    void localRecord_knownField_compiles() {
        String src = """
                let r = {x = 1, y = 2}
                r.x
                """;
        PontifCompiler.CompileResult result = compile(src);
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, result,
                () -> "Expected Compiled; got " + result);
    }

    @Test
    void localRecord_unknownField_isCompileError() {
        String src = """
                let r = {x = 1, y = 2}
                r.zz
                """;
        PontifCompiler.CompileResult result = compile(src);
        assertInstanceOf(PontifCompiler.CompileResult.Failed.class, result,
                () -> "Expected Failed (field-safety at compile time); got " + result);
        String msg = ((PontifCompiler.CompileResult.Failed) result).error().text();
        assertTrue(msg.contains("has no field 'zz'"),
                () -> "Expected error about missing field 'zz'; got: " + msg);
    }
}
