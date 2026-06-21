package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Stream war slice 2a: the synthesis-fragment primitive's single-stream
 * <em>map</em> shape (docs/stream-war.md §3). A {@code &}-spread argument applies
 * a single-return function per element of a stream, sealing to a new stream:
 * {@code double(&s)} is map, lowered to {@link sibarum.pontif.ir.IrExpr.Iterate}.
 */
class StreamMapTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void spreadOverTuple_mapsPerElement() throws Exception {
        // double(&s) maps the function over every element, sealing to a new stream.
        assertEquals("(2, 4, 6, 8)", String.valueOf(run("""
                function double(x:Int):Int -> x * 2
                let s = (1, 2, 3, 4)
                double(&s)""")));
    }

    @Test
    void spreadResult_isAStream_typedLetAutoboxes() {
        // The mapped result is a stream, so a Stream[Int]-typed binding accepts it.
        CompileResult r = compiler.compileAlt("""
                requires pontif.core.{Stream}
                function double(x:Int):Int -> x * 2
                let s:Stream[Int] = (1, 2, 3, 4)
                let doubled:Stream[Int] = double(&s)
                0""", "m.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "a spread map produces a Stream; got " + r);
    }

    @Test
    void multipleSpreads_rejected_untilZipSlice() {
        // Two `&` args (zip / fan-in) is a later sub-slice — must fail clearly now.
        CompileResult r = compiler.compileAlt("""
                function add(a:Int, b:Int):Int -> a + b
                let s = (1, 2, 3)
                let t = (4, 5, 6)
                add(&s, &t)""", "m.ptf");
        assertInstanceOf(CompileResult.Failed.class, r,
                () -> "multi-spread (zip) is not implemented yet; got " + r);
    }
}
