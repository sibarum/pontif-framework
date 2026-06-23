package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Stream war §8.6: the parametric-trait no-lie hole. A COMPUTED stream's element
 * type must be checked against the declared Stream[T] (a literal was already checked
 * at parse; an Iterate result was not). Closed by carrying the trait's applied type
 * args to the gate/runtime (Slice A) + the Stream element check in Refinements (Slice B).
 */
class StreamElementCheckTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private Object eval(CompileResult r) {
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "should compile; got " + r);
        return new IrInterpreter(c.program().simplifier()).eval(c.program().module());
    }

    @Test void computedStream_matchingElementType_passes() {
        Object val = eval(compiler.compileAlt("""
                requires pontif.core.{Stream}
                let s:Stream[Int] = {1, 2, 3, 4}
                let double:[ (el:Int) -> el * 2 ]
                let z:Stream[Int] = double(&s)
                z""", "m.ptf"));
        assertEquals("{2, 4, 6, 8}", String.valueOf(val));
    }

    @Test void computedStream_wrongElementType_isRejected() {
        // THE LIE: an Int stream declared as Stream[String]. Must NOT pass clean.
        CompileResult r = compiler.compileAlt("""
                requires pontif.core.{Stream}
                let s:Stream[Int] = {1, 2, 3, 4}
                let double:[ (el:Int) -> el * 2 ]
                let z:Stream[String] = double(&s)
                z""", "m.ptf");
        boolean rejected;
        if (r instanceof CompileResult.Compiled c) {
            try {
                new IrInterpreter(c.program().simplifier()).eval(c.program().module());
                rejected = false;
            } catch (RuntimeException e) {
                rejected = true;
            }
        } else {
            rejected = true;  // compile-time rejection is also acceptable
        }
        org.junit.jupiter.api.Assertions.assertTrue(rejected,
                "Stream[String] bound to an Int stream must be rejected (the §8.6 lie)");
    }
}
