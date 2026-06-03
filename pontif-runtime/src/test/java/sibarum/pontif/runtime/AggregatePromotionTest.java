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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AggregatePromotion — anonymous aggregate literals are stamped with the
 * struct name the context ASSERTS (let annotations, struct-typed params,
 * return positions): checked construction with the redundant name elided.
 * Question positions (match, ==) never promote — that's Phase B's claim rule.
 */
class AggregatePromotionTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "t.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void letAnnotation_stampsAnonymousLiteral() throws Exception {
        // The display includes the claim — the literal became a real Point.
        assertEquals("Point{x: 1, y: 2}", String.valueOf(run(
                "struct Point(x:Int, y:Int)\nlet p:Point = {x = 1, y = 2}\np")));
    }

    @Test
    void typedParam_stampsAnonymousArgument() throws Exception {
        assertEquals(3L, run("""
                struct Point(x:Int, y:Int)
                function manhattan(p:Point):Int -> p.x + p.y
                manhattan({x = 1, y = 2})
                """));
    }

    @Test
    void returnPosition_stampsAnonymousLiteral() throws Exception {
        assertEquals("Point{x: 1, y: 2}", String.valueOf(run("""
                struct Point(x:Int, y:Int)
                function mk():Point -> {x = 1, y = 2}
                mk()
                """)));
    }

    @Test
    void returnThroughMatchBranches_stamps() throws Exception {
        assertEquals("Point{x: 9, y: 0}", String.valueOf(run("""
                struct Point(x:Int, y:Int)
                function mk(n:Int):Point -> match n {
                  [@>0] -> {x = 9, y = 0}
                  _ -> {x = 0, y = 0}
                }
                mk(5)
                """)));
    }

    @Test
    void nestedAnonymousMembers_promoteRecursively() throws Exception {
        assertEquals(42L, run("""
                struct Inner(v:Int)
                struct Outer(label:Int, inner:Inner)
                let o:Outer = {label = 1, inner = {v = 42}}
                o.inner.v
                """));
    }

    @Test
    void decimalMembers_getLiteralPromotionAfterStamping() throws Exception {
        // AggregatePromotion stamps first; DecimalPromotion then promotes the
        // member literal 1 -> 1.0 — the attribute-level coercion.
        assertEquals("D{x: 1.0}", String.valueOf(run(
                "struct D(x:Decimal)\nlet d:D = {x = 1}\nd")));
    }

    @Test
    void missingField_isRejected() {
        assertThrows(CompileException.class, () -> run(
                "struct Point(x:Int, y:Int)\nlet p:Point = {x = 1}\np"));
    }

    @Test
    void extraField_isRejected() {
        assertThrows(CompileException.class, () -> run(
                "struct Point(x:Int, y:Int)\nlet p:Point = {x = 1, y = 2, z = 3}\np"));
    }

    @Test
    void ambiguousOverloadTarget_isRejected() {
        CompileException ex = assertThrows(CompileException.class, () -> run("""
                struct Point(x:Int, y:Int)
                struct Vec(x:Int, y:Int)
                function f(p:Point):Int -> p.x
                function f(v:Vec):Int -> v.y
                f({x = 1, y = 2})
                """));
        assertTrue(ex.getMessage().contains("ambiguous"), () -> ex.getMessage());
    }

    @Test
    void unannotatedLet_staysAnonymous() throws Exception {
        // No assertion anywhere — the dict stays a dict (findStructByFieldSet
        // is retired; shape never christens).
        assertEquals("{x: 1, y: 2}", String.valueOf(run(
                "struct Point(x:Int, y:Int)\nlet d = {x = 1, y = 2}\nd")));
    }
}
