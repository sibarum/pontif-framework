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
 * Stream war slice 2c: the <em>fragment literal</em> {@code let f:[ (el:Int) ->
 * body ]} — the synthesis fragment as a first-class value (docs/stream-war.md §3,
 * James's ruling). A fragment is a closure (the lambda replacement): bound by
 * {@code let}, applied directly or by {@code &} spread.
 */
class StreamFragmentTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void fragment_directApplication() throws Exception {
        // A bound fragment is a callable value.
        assertEquals(6L, run("""
                let double:[ (el:Int) -> el * 2 ]
                double(3)"""));
    }

    @Test
    void fragment_spreadIsMap() throws Exception {
        // `&` spreads the fragment over a stream — map, no ceremony.
        assertEquals("(2, 4, 6, 8)", String.valueOf(run("""
                let double:[ (el:Int) -> el * 2 ]
                let s = (1, 2, 3, 4)
                double(&s)""")));
    }

    @Test
    void spreadAscription_inlineMap() throws Exception {
        // The inline/anonymous face: `&s:[ (el)-> … ]` ≡ applying the transform per
        // element. No name needed.
        assertEquals("(2, 4, 6, 8)", String.valueOf(run("""
                let s = (1, 2, 3, 4)
                &s:[ (el:Int) -> el * 2 ]""")));
    }

    @Test
    void spreadAscription_inlineFilter_dropsNothing() {
        // The canonical filteredLossy line in the ruled syntax: &s:[ fragment ].
        CompileResult r = compiler.compileAlt("""
                requires pontif.core.{Stream, Nothing}
                let null:Nothing = Nothing()
                let s:Stream[Int] = (1, 2, 3, 4)
                &s:[
                  (el:Int) -> match el {
                    [@>2] -> el
                    [_]   -> null
                  }
                ]""", "m.ptf");
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "the inline ascription filter should compile; got " + r);
        Object val = new IrInterpreter(c.program().simplifier()).eval(c.program().module());
        assertEquals("(3, 4)", String.valueOf(val));
    }

    @Test
    void fragment_filter_dropsNothing() {
        // The canonical filteredLossy line: a fragment whose arms return the
        // element or null; spread over the stream, the nulls drop.
        CompileResult r = compiler.compileAlt("""
                requires pontif.core.{Stream, Nothing}
                let null:Nothing = Nothing()
                let filter:[
                  (el:Int) -> match el {
                    [@>2] -> el
                    [_]   -> null
                  }
                ]
                let s:Stream[Int] = (1, 2, 3, 4)
                filter(&s)""", "m.ptf");
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "the fragment filter should compile; got " + r);
        Object val = new IrInterpreter(c.program().simplifier()).eval(c.program().module());
        assertEquals("(3, 4)", String.valueOf(val));
    }
}
