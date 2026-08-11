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
 * The inline conditional {@code if c then a else b} — a full expression that
 * lowers to a two-arm boolean match ({@code [Bool: @] -> a  _ -> b}). Exercises
 * every expression position: terminal, let-RHS, nested call arg, and the
 * {@code else if} chain that falls out of the else-branch being any expression.
 */
class InlineConditionalTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "t.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void terminalPosition_true() throws Exception {
        assertEquals(1L, run("if 2 > 1 then 1 else 0"));
    }

    @Test
    void terminalPosition_false() throws Exception {
        assertEquals(0L, run("if 2 < 1 then 1 else 0"));
    }

    @Test
    void letRhsPosition() throws Exception {
        assertEquals(10L, run("let y = if 3 == 3 then 10 else 20  y"));
    }

    @Test
    void nestedInCallArg() throws Exception {
        String src = """
                function double(n:Int):Int -> n * 2
                double(if 1 == 1 then 5 else 7)
                """;
        assertEquals(10L, run(src));
    }

    @Test
    void elseIfChain() throws Exception {
        // The else-branch is itself an `if` — no special grammar needed.
        String src = "if 1 > 2 then 100 else if 2 > 3 then 200 else 300";
        assertEquals(300L, run(src));
    }

    @Test
    void conditionFromLetBinding() throws Exception {
        assertEquals(42L, run("let flag = 5 > 0  if flag then 42 else 0"));
    }

    @Test
    void elseIfChain_middleArmWins() throws Exception {
        // 85 >= 90 false -> 85 >= 80 true -> 3. Exposes else-if grouping (both-false
        // would return the tail regardless, so it must not be the only chain test).
        assertEquals(3L, run("if 85 >= 90 then 4 else if 85 >= 80 then 3 else 1"));
    }

    @Test
    void elseIfChainInLetRhs() throws Exception {
        assertEquals(3L, run("let band = if 85 >= 90 then 4 else if 85 >= 80 then 3 else 1  band"));
    }

    @Test
    void showcaseExampleRuns() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "pontif-playground", "examples", "inline-conditional.ptf"));
        assertEquals(118L, run(src));
    }
}
