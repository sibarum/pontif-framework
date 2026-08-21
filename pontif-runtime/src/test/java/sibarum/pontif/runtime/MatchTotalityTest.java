package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.PontifParser;
import sibarum.pontif.parser.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Match totality (Pontif-syntax principle 8), under the conservation rule:
 * <b>if totality cannot be determined at compile time, a default arm is
 * required.</b> {@code SortChecker} proves coverage where it can (the
 * decidable Int/Bool fragment, bare-struct Tier A, single-field Tier B,
 * union-of-bare-arms Tier C); a provably non-exhaustive match is rejected
 * with the uncovered witness; an <em>undecidable</em> match without a
 * catch-all arm is rejected too — never deferred to a runtime gamble.
 */
class MatchTotalityTest {

    private static Object run(String src) throws ParseException, CompileException {
        IrModule module = PontifParser.parseModule(src, "t.ptf");
        Simplifier simp = new Simplifier(
                java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }


    @Test
    void nonExhaustiveInt_isCompileError() {
        // [@<0] and [@>0] miss 0 → rejected, with @ == 0 as the witness.
        String src = """
                module m
                function f(n:Int):Int -> match n
                  [@<0] -> -1
                  [@>0] -> 1
                f(3)
                """;
        CompileException ex = assertThrows(CompileException.class, () -> run(src));
        assertTrue(ex.getMessage().contains("not exhaustive"),
                () -> "Expected a non-exhaustive error; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("@ == 0"),
                () -> "Expected the uncovered witness @ == 0; got: " + ex.getMessage());
    }

    @Test
    void thresholdGap_isCompileError() {
        // [@<0] and [@>5] leave 0..5 uncovered.
        String src = """
                module m
                function f(n:Int):Int -> match n
                  [@<0] -> -1
                  [@>5] -> 1
                f(3)
                """;
        CompileException ex = assertThrows(CompileException.class, () -> run(src));
        assertTrue(ex.getMessage().contains("not exhaustive"),
                () -> "Expected a non-exhaustive error; got: " + ex.getMessage());
    }

    @Test
    void exhaustiveInt_compilesAndRuns() throws Exception {
        String src = """
                module m
                function sign(n:Int):Int -> match n
                  [@<0 ] -> -1
                  [@==0] -> 0
                  [@>0 ] -> 1
                sign(-5)
                """;
        assertEquals(-1L, run(src));
    }

    @Test
    void defaultArm_isTotalByConstruction() throws Exception {
        // `_` desugars to the complement of [@<0] over Int (= [@>=0]) → total.
        String src = """
                module m
                function f(n:Int):Int -> match n
                  [@<0] -> -1
                  _ -> 1
                f(7)
                """;
        assertEquals(1L, run(src));
    }

    @Test
    void exhaustiveBool_compilesAndRuns() throws Exception {
        String src = """
                module m
                function f(b:Bool):Int -> match b
                  [@==true ] -> 1
                  [@==false] -> 0
                f(true)
                """;
        assertEquals(1L, run(src));
    }

    @Test
    void nonExhaustiveBool_isCompileError() {
        // Only [@==true] → false is uncovered.
        String src = """
                module m
                function f(b:Bool):Int -> match b
                  [@==true] -> 1
                f(true)
                """;
        CompileException ex = assertThrows(CompileException.class, () -> run(src));
        assertTrue(ex.getMessage().contains("not exhaustive"),
                () -> "Expected a non-exhaustive error; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("@ == false"),
                () -> "Expected the uncovered witness @ == false; got: " + ex.getMessage());
    }

    @Test
    void nonExhaustiveStructField_isCompileError() {
        // Refined-field arms vary on x: [x>0] and [x<0] leave x==0 uncovered.
        // Tier B reduces to single-field totality on x and rejects with witness.
        String src = """
                module m
                struct Point(x:Int, y:Int)
                function classify(p:Point):Int -> match p
                  [Point(x:[Int:@>0], y)] -> 1
                  [Point(x:[Int:@<0], y)] -> -1
                classify(Point(3, 4))
                """;
        CompileException ex = assertThrows(CompileException.class, () -> run(src));
        assertTrue(ex.getMessage().contains("not exhaustive"),
                () -> "Expected a non-exhaustive error; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("'x'")
                        && ex.getMessage().contains("@ == 0"),
                () -> "Expected witness on field 'x' with @ == 0; got: " + ex.getMessage());
    }

    @Test
    void exhaustiveStructFieldThreeArms_compilesAndRuns() throws Exception {
        // Three arms on x: <0, ==0, >0 cover Int — Tier B accepts.
        String src = """
                module m
                struct Point(x:Int, y:Int)
                function signX(p:Point):Int -> match p
                  [Point(x:[Int:@<0 ], y)] -> -1
                  [Point(x:[Int:@==0], y)] -> 0
                  [Point(x:[Int:@>0 ], y)] -> 1
                signX(Point(5, 7))
                """;
        assertEquals(1L, run(src));
    }

    @Test
    void bareStructMatch_isTotalByConstruction() throws Exception {
        // [Point(x, y)] is a bare structural arm whose fields are a subset of
        // Point's — Pontif's subset-match semantics make it cover every Point
        // value (Tier A struct totality).
        String src = """
                module m
                struct Point(x:Int, y:Int)
                function sumXY(p:Point):Int -> match p
                  [Point(x, y)] -> x + y
                sumXY(Point(3, 4))
                """;
        assertEquals(7L, run(src));
    }

    @Test
    void undecidableTotality_withoutDefault_isCompileError() {
        // Decimal-field struct arms are outside the decidable fragment — per
        // the conservation rule, no default means compile error, not a
        // runtime gamble.
        String src = """
                module m
                struct Box(v:Decimal)
                function f(b:Box):Int -> match b
                  [Box:@.v==0] -> 0
                f(Box(1.5))
                """;
        CompileException ex = assertThrows(CompileException.class, () -> run(src));
        assertTrue(ex.getMessage().contains("cannot prove"),
                () -> "Expected the cannot-prove rejection; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("default"),
                () -> "Expected the add-a-default hint; got: " + ex.getMessage());
    }

    @Test
    void undecidableTotality_withCatchAll_compilesAndRuns() throws Exception {
        String src = """
                module m
                struct Box(v:Decimal)
                function f(b:Box):Int -> match b
                  [Box:@.v==0] -> 0
                  [_] -> 1
                f(Box(1.5))
                """;
        assertEquals(1L, run(src));
    }

    @Test
    void underscoreDefault_overStructScrutinee_compilesAndRuns() throws Exception {
        // `_` now falls back to the universal pattern where the precise
        // complement isn't computable — usable as the default everywhere.
        String src = """
                module m
                struct Box(v:Decimal)
                function f(b:Box):Int -> match b
                  [Box:@.v==0] -> 0
                  _ -> 1
                f(Box(0.0))
                """;
        assertEquals(0L, run(src));
    }

    @Test
    void unionScrutinee_bareArmPerBranch_isTotalByConstruction() throws Exception {
        // Tier C: every union branch covered by a bare arm of its type — the
        // canonical sum-type match is determined total, no default required.
        String src = """
                module m
                struct A(x:Int)
                struct B(y:Int)
                function f(v:[A|B]):Int -> match v
                  [A] -> 1
                  [B] -> 2
                f(B(5))
                """;
        assertEquals(2L, run(src));
    }

    @Test
    void boolDefaultArm_isTotalByConstruction() throws Exception {
        // `_` over a Bool scrutinee desugars to the complement of [@==true]
        // (= [@==false]) via PredicateArithmetic.complement; the Bool-literal
        // comparison fold lets the resulting arm be decided at runtime.
        String src = """
                module m
                function f(b:Bool):Int -> match b
                  [@==true] -> 1
                  _ -> 0
                f(false)
                """;
        assertEquals(0L, run(src));
    }
}
