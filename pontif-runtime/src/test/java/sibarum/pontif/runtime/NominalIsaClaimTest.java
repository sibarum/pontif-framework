package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The runtime <b>claim rule</b> read through the is-a chain.
 *
 * <p>A {@code struct Exp:[BiOp:@.op=="+"]} value's claim is {@code Exp}, and the
 * matcher used to require the sort's name to equal that claim exactly — so
 * {@code match anExp { [BiOp] -> … }} found no branch and died at runtime, even
 * though the static side accepts {@code let b:BiOp = anExp} without complaint. The
 * runtime test must agree with the static one, or the compiler proves an arm
 * reachable that the matcher then refuses. Claims are still never invented: the
 * value's constructed type is the only thing consulted, just read through the
 * relation its own declaration asserted.
 *
 * <p>These also pin the {@code String} comparison fold the same arms need. Equal
 * strings collapsed by structural identity, but UNEQUAL ones stayed residual, so a
 * refinement arm over a String field raised an undecidable obligation rather than
 * simply not matching.
 */
class NominalIsaClaimTest {

    private static final String EXPR = """
            struct Expr()
            let Operation:Type[String:@=="+" | @=="-"]
            struct BiOp:Expr(left:Expr, right:Expr, op:Operation)
            struct Exp:[BiOp:@.op=="+"](left:Expr, right:Expr)
            struct Log:[BiOp:@.op=="-"](left:Expr, right:Expr)
            """;

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String value(String src) {
        CompileResult r = compiler.compile(src, "isa.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r, () -> "expected a clean compile; got " + r);
        RunResult interp = runner.run(r, Engine.INTERPRETER);
        assertFalse(interp.isError(), () -> "interpreter: " + interp.text());
        RunResult truffle = runner.run(r, Engine.TRUFFLE);
        assertFalse(truffle.isError(), () -> "truffle: " + truffle.text());
        assertEquals(interp.text(), truffle.text(), "engines disagree");
        return interp.text();
    }

    @Test
    void subtypeValue_matchesABareArmOfItsBase() {
        assertEquals("\"biop\"", value(EXPR + """
                function what(e:BiOp):String -> match e {
                  [Log] -> "log"
                  [BiOp] -> "biop"
                }
                main (
                  let leaf = Expr()
                  what(Exp(leaf, leaf))
                )"""));
    }

    @Test
    void aMoreSpecificArmStillWins_orderDecides() {
        assertEquals("\"log\"", value(EXPR + """
                function what(e:BiOp):String -> match e {
                  [Log] -> "log"
                  [BiOp] -> "biop"
                }
                main (
                  let leaf = Expr()
                  what(Log(leaf, leaf))
                )"""));
    }

    @Test
    void subtypeValue_matchesARefinedArmOverABaseField() {
        assertEquals("\"plus\"", value(EXPR + """
                function what(e:BiOp):String -> match e {
                  [BiOp:@.op == "+"] -> "plus"
                  [BiOp] -> "other"
                }
                main (
                  let leaf = Expr()
                  what(Exp(leaf, leaf))
                )"""));
    }

    /** The unequal-String case: the arm must simply not match, not raise a residual. */
    @Test
    void aRefinedArmOverAStringField_failsCleanlyWhenItDoesNotMatch() {
        assertEquals("\"other\"", value(EXPR + """
                function what(e:BiOp):String -> match e {
                  [BiOp:@.op == "-"] -> "minus"
                  [BiOp] -> "other"
                }
                main (
                  let leaf = Expr()
                  what(Exp(leaf, leaf))
                )"""));
    }

    /** A sibling is NOT an ancestor — the is-a chain widens, it does not spread sideways. */
    @Test
    void siblingSubtypes_doNotMatchEachOther() {
        assertEquals("\"fell-through\"", value(EXPR + """
                function what(e:BiOp):String -> match e {
                  [Log] -> "log"
                  [_] -> "fell-through"
                }
                main (
                  let leaf = Expr()
                  what(Exp(leaf, leaf))
                )"""));
    }
}
