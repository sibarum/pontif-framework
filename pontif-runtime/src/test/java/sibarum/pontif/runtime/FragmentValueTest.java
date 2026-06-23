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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stream war §8b (generics slice A): fragments as first-class, passable VALUES. A
 * fragment (a Closure) can be passed to a [Method(A):R] parameter and invoked — the
 * lambda replacement becoming abstractable. Previously crashed: dispatch symbolized
 * the Closure argument via toSymExpr, which threw.
 */
class FragmentValueTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test void passFragmentToMethodParam_andInvoke() throws Exception {
        assertEquals(10L, run("""
                function applyTo( f:[Method(Int):Int], x:Int ):Int -> f(x)
                let double:[ (el:Int) -> el * 2 ]
                applyTo(double, 5)"""));
    }

    @Test void returnFragmentFromFunction_andInvoke() throws Exception {
        // A function can return a fragment value; the caller binds and invokes it.
        assertEquals(10L, run("""
                let double:[ (el:Int) -> el * 2 ]
                function mk():[Method(Int):Int] -> double
                let d = mk()
                d(5)"""));
    }

    @Test void oneTupleSort_isBraced_notParens() throws Exception {
        // BRACE-AGGREGATES WAR: a 1-tuple sort is `{Int}` (braces); the old
        // `(S)`-grouping-in-a-sort spelling is retired (it was only there to
        // disambiguate from a 1-tuple, which now has its own brace form). A plain
        // scalar param is just `[Int]`.
        assertEquals(7L, run("""
                function id( x:[Int] ):Int -> x
                id(7)"""));
    }
}
