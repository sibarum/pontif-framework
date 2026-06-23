package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Stream war: concat {@code +} must be allowed on STREAM-TYPED operands, not only raw
 * tuple literals. It's a built-in structural append (slice 2e), not a trait contract
 * member — so the operator-completeness check exempts it on a Stream operand, like
 * structural equality.
 */
class StreamConcatTypedTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private Object run(String src) {
        CompileResult r = compiler.compileAlt(src, "m.ptf");
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "should compile; got " + (r instanceof CompileResult.Failed f ? f.error().text() : r));
        return new IrInterpreter(c.program().simplifier()).eval(c.program().module());
    }

    @Test void concat_onStreamTypedLets() {
        assertEquals("{1, 2, 3, 4}", String.valueOf(run("""
                requires pontif.core.{Stream}
                let a:Stream[Int] = {1, 2}
                let b:Stream[Int] = {3, 4}
                a + b""")));
    }

    @Test void concat_onStreamTypedParams() {
        assertEquals("{1, 2, 3, 4}", String.valueOf(run("""
                requires pontif.core.{Stream}
                function cat(x:Stream[Int], y:Stream[Int]):Stream[Int] -> x + y
                cat({1, 2}, {3, 4})""")));
    }
}
