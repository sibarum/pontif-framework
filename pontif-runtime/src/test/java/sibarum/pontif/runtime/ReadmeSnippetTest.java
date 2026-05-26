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

/** Verifies the README's "A taste of the language" snippet evaluates as advertised. */
class ReadmeSnippetTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "readme.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void readmeSnippet_evaluatesTo25() throws Exception {
        String src = """
                struct Point(x:Int, y:Int)

                let Sized:Type{
                  magnitude:[Function():Int]
                }

                assign trait Point:Sized {
                  magnitude():Int -> self.x * self.x + self.y * self.y
                }

                function describe(d:Sized):Int -> d.magnitude()

                describe(Point(3, 4))
                """;
        assertEquals(25L, run(src));
    }
}
