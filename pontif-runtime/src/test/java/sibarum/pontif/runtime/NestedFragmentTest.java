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
 * Stream war §8b (gap B): a fragment literal `let m:[ (el)-> … ]` in a NESTED let (inside
 * a function body), not just at top level — `let` works anywhere a let is permitted.
 */
class NestedFragmentTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = PontifParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test void nestedFragmentLet_directCall() throws Exception {
        assertEquals(10L, run("""
                function f(x:Int):Int ->
                  let m:[ (el:Int) -> el * 2 ]
                  m(x)
                f(5)"""));
    }

    @Test void nestedFragmentLet_spreadMap() throws Exception {
        // James's map shape: a named fragment defined in the body, spread over a stream.
        assertEquals("{2, 4, 6}", String.valueOf(run("""
                function dbl(s:Stream[Int]):[Stream[Int]] ->
                  let m:[ (el:Int) -> el * 2 ]
                  m(&s)
                dbl({1, 2, 3})""")));
    }
}
