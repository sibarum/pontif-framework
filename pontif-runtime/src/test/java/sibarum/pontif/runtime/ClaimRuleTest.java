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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The claim rule (aggregate grid, Slice 3 Phase B): a DECLARED name bites.
 * Construction is where claims are made (AggregatePromotion, at assertion
 * boundaries); matching is where they're tested; nothing in between invents
 * one. Every tripwire here was a live probe during design — re-badging,
 * question-position coercion, and positional width were all silently accepted
 * before this slice.
 */
class ClaimRuleTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = PontifParser.parseModule(src, "t.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    // --- re-badging dies: a contrary claim is never overridden ---

    @Test
    void sameShapeDifferentName_isRejected() {
        // Vec carries its own claim; passing it where Point is required would
        // silently re-badge it. The boldface lie, now refused.
        assertThrows(Exception.class, () -> run("""
                struct Point(x:Int, y:Int)
                struct Vec(x:Int, y:Int)
                function f(p:Point):Int -> p.x
                f(Vec(1, 2))
                """));
    }

    @Test
    void rightClaim_passes() throws Exception {
        assertEquals(1L, run("""
                struct Point(x:Int, y:Int)
                struct Vec(x:Int, y:Int)
                function f(p:Point):Int -> p.x
                f(Point(1, 2))
                """));
    }

    // --- questions stay questions: match never invents a claim ---

    @Test
    void anonymousValue_doesNotAnswerYesToANamedQuestion() throws Exception {
        // d never claimed Point-ness; [Point] is a question, not an assertion.
        assertEquals(0L, run("""
                struct Point(x:Int, y:Int)
                let d = {x = 1, y = 2}
                match d { [Point] -> 1
                _ -> 0 }
                """));
    }

    @Test
    void constructedValue_answersYesToItsOwnName() throws Exception {
        assertEquals(1L, run("""
                struct Point(x:Int, y:Int)
                let p = Point(1, 2)
                match p { [Point] -> 1
                _ -> 0 }
                """));
    }

    // --- equality follows matching (native only) ---

    @Test
    void namedAndAnonymous_areNotEqual() throws Exception {
        assertEquals(false, run("""
                struct Point(x:Int, y:Int)
                Point(1, 2) == {x = 1, y = 2}
                """));
    }

    @Test
    void sameShapeDifferentNames_areNotEqual() throws Exception {
        assertEquals(false, run("""
                struct Point(x:Int, y:Int)
                struct Vec(x:Int, y:Int)
                Point(1, 2) == Vec(1, 2)
                """));
    }

    @Test
    void anonymousValues_compareByContent() throws Exception {
        assertEquals(true, run("{x = 1, y = 2} == {x = 1, y = 2}"));
        assertEquals(true, run("{1, true} == {1, true}"));
    }

    @Test
    void sameType_sameContent_isEqual() throws Exception {
        assertEquals(true, run("""
                struct Point(x:Int, y:Int)
                Point(1, 2) == Point(1, 2)
                """));
    }

    // --- positional width dies: tuple sorts are arity-exact ---

    @Test
    void wideTupleIntoNarrowTupleParam_isRejected() {
        // (1, true, 99) silently dropped the 99 before this slice.
        assertThrows(Exception.class, () -> run("""
                function f(p:[{Int, Bool}]):Int -> match p { [{a, b}] -> a }
                f({1, true, 99})
                """));
    }

    @Test
    void exactArityTuple_passes() throws Exception {
        assertEquals(1L, run("""
                function f(p:[{Int, Bool}]):Int -> match p { [{a, b}] -> a }
                f({1, true})
                """));
    }

    // --- the directional rule survives: struct ⊑ anonymous ---

    @Test
    void namedValue_satisfiesAnonymousShape() throws Exception {
        // A Point honestly IS "something with x and y" — by-name decomposition
        // projects it like any aggregate.
        assertEquals(3L, run("""
                struct Point(x:Int, y:Int)
                let p = Point(1, 2)
                let p.{x, y} x + y
                """));
    }
}
