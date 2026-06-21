package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Stream war slice 1b: the {@code Stream} trait lives in {@code pontif.core} (a
 * builtin module, marker capability — no contract member yet, docs/stream-war.md).
 * A tuple literal autoboxes into {@code Stream[E]} when the elements conform.
 */
class StreamTraitTest {

    private final PontifCompiler compiler = new PontifCompiler();

    @Test
    void streamTrait_importable_andLiteralTypes() {
        CompileResult r = compiler.compileAlt("""
                requires pontif.core.{Stream}
                let s:Stream[Int] = (1,2,3,4)
                0""", "s.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "import + matching Stream literal should compile; got " + r);
    }

    @Test
    void streamLiteral_wrongElementType_rejected() {
        // 1,2,3 are Int, not Bool — the autobox element gate must reject.
        CompileResult r = compiler.compileAlt("""
                requires pontif.core.{Stream}
                let s:Stream[Bool] = (1,2,3)
                0""", "s.ptf");
        assertInstanceOf(CompileResult.Failed.class, r,
                () -> "a Bool stream can't box Int elements; got " + r);
    }
}
