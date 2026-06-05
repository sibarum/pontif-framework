package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Metareferences, Slice 1: dispatch references. {@code $inc[Int]} reifies the
 * DISPATCH keyed at those sorts — not a function pointer: invocation (by
 * application, per the bracket/paren law) reruns runtime dispatch over the
 * name's candidates, narrowings intact. The {@code $} sigil marks the NAME
 * (quoted, not evaluated). {@code [Dispatch(...)]} and
 * {@code [Method(...)]} mirror the two dispatch mechanisms and never
 * cross-assign.
 */
class MetareferenceTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "t.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void moduleLevelReference_appliesThroughTheBinding() throws Exception {
        // James's headline sample: the binding is a top-level let (a zero-arg
        // function); application reaches through it per the ()-law.
        assertEquals(3L, run("""
                function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1
                let incDispatch:[Dispatch(Int):Int] = $inc[Int]
                incDispatch(2)
                """));
    }

    @Test
    void referenceAsParameter_invokedByApplication() throws Exception {
        // The payoff: dispatches as first-class arguments.
        assertEquals(7L, run("""
                function inc(x:Int):Int -> x + 1
                function twice(d:[Dispatch(Int):Int], x:Int):Int -> d(d(x))
                twice($inc[Int], 5)
                """));
    }

    @Test
    void overloadSet_capturedNotPinned_runtimeDispatchAtApply() throws Exception {
        // A metareference to a refinement-overloaded name captures the
        // DISPATCH, not a winner: apply selects by value, like a direct call.
        String src = """
                function g(x:[Int:@>0]):Int -> x
                function g(x:[Int:0]):Int -> 99
                function via(d:[Dispatch(Int):Int], x:Int):Int -> d(x)
                via($g[Int], %s)
                """;
        assertEquals(5L, run(src.formatted("5")));
        assertEquals(99L, run(src.formatted("0")));
    }

    @Test
    void calleeNarrowings_surviveTheReference() {
        // inc requires [Int:@>=1]; the reference doesn't launder that away.
        RuntimeCheckException e = assertThrows(RuntimeCheckException.class, () -> run("""
                function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1
                function via(d:[Dispatch(Int):Int], x:Int):Int -> d(x)
                via($inc[Int], 0)
                """));
        assertTrue(e.getMessage().contains("Dispatch failed for 'inc'"),
                () -> e.getMessage());
    }

    @Test
    void zeroCandidates_isACompileError() {
        Exception e = assertThrows(CompileException.class,
                () -> run("function f(x:Int):Int -> x\nlet d = $nosuch[Int]\n0"));
        assertTrue(e.getMessage().contains("names no declared function"),
                () -> e.getMessage());
    }

    @Test
    void keySortMismatch_failsClosedAtTheParamWall() {
        // $inc[Decimal] is a reference keyed at Decimal; a [Dispatch(Int):Int]
        // param refuses it (v1: exact key match).
        RuntimeCheckException e = assertThrows(RuntimeCheckException.class, () -> run("""
                function inc(x:Int):Int -> x + 1
                function via(d:[Dispatch(Int):Int], x:Int):Int -> d(x)
                via($inc[Decimal], 5)
                """));
        assertTrue(e.getMessage().contains("Dispatch failed for 'via'"),
                () -> e.getMessage());
    }

    @Test
    void dispatchAndMethod_neverCrossAssign() {
        // The wall between the two mechanisms: a metareference does not
        // satisfy a [Method(...)] param.
        RuntimeCheckException e = assertThrows(RuntimeCheckException.class, () -> run("""
                function inc(x:Int):Int -> x + 1
                function apply(f:[Method(Int):Int], x:Int):Int -> f(x)
                apply($inc[Int], 5)
                """));
        assertTrue(e.getMessage().contains("Dispatch failed for 'apply'"),
                () -> e.getMessage());
    }

    @Test
    void wrongArity_atApplication_isHonest() {
        RuntimeCheckException e = assertThrows(RuntimeCheckException.class, () -> run("""
                function inc(x:Int):Int -> x + 1
                function via(d:[Dispatch(Int):Int]):Int -> d(1, 2)
                via($inc[Int])
                """));
        assertTrue(e.getMessage().contains("takes 1 argument(s); got 2"),
                () -> e.getMessage());
    }

    @Test
    void bareNameLiteral_isAnHonestParseError() {
        // `$inc` with no key sorts is not yet a value: the name-literal sigil
        // parses, but a bare name reference is reserved for the type-reference
        // slice. Whitespace never silently changes meaning — this errors
        // honestly rather than misparsing.
        ParseException e = assertThrows(ParseException.class,
                () -> run("function inc(x:Int):Int -> x\nlet d = $inc\n0"));
        assertTrue(e.getMessage().contains("not yet a value"),
                () -> e.getMessage());
    }

    @Test
    void trufflePath_agrees() {
        PontifCompiler compiler = new PontifCompiler();
        PontifRunner runner = new PontifRunner();
        PontifRunner.RunResult r = runner.run(compiler.compileAlt("""
                function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1
                let incDispatch:[Dispatch(Int):Int] = $inc[Int]
                incDispatch(2)
                """, "t.ptf"), PontifRunner.Engine.TRUFFLE);
        assertEquals("3", r.text(), () -> r.text());
    }
}
