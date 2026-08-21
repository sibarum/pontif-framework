package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.PontifParser;
import sibarum.pontif.parser.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stream war slice 2e: stream concatenation via {@code +}. `a + b` on two
 * positional streams appends b's elements after a's — the same
 * +-concatenates-sequences rule as String `+` (a String is a Char stream),
 * lifted to any Stream (docs/stream-war.md §7).
 */
class StreamConcatTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = PontifParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void concat_appendsElements() throws Exception {
        assertEquals("{1, 2, 3, 4, 5, 6}", String.valueOf(run("{1, 2, 3} + {4, 5, 6}")));
    }

    @Test
    void concat_throughLetBindings() throws Exception {
        assertEquals("{1, 2, 3, 4}", String.valueOf(run("""
                let a = {1, 2}
                let b = {3, 4}
                a + b""")));
    }

    @Test
    void concat_composesWithSpread() throws Exception {
        // Concatenation is structural and composes with the per-element spread:
        // double each of s, then append the originals.
        assertEquals("{2, 4, 6, 1, 2, 3}", String.valueOf(run("""
                let double:[ (x:Int) -> x * 2 ]
                let s = {1, 2, 3}
                double(&s) + s""")));
    }
}
