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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exploratory pre-flight battery: realistic alt-syntax programs of the kind
 * you'd write doing serious work, run end-to-end (AltParser → IrCompiler →
 * IrInterpreter). The Group A tests have hand-computed expected values, so a
 * failure is a real bug. The Group B tests are behavior <em>probes</em> for
 * uncertain corners — each documents the prediction; a failure pins down the
 * actual behavior for triage, not necessarily a defect.
 */
class PlaygroundProbeTest {

    private static Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "probe.ptf");
        Simplifier simp = new Simplifier(List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    // ===== Group A: should pass with known-correct values ===================

    // --- arithmetic & precedence & associativity ---------------------------

    @Test void precedence_mulBeforeAdd() throws Exception { assertEquals(7L, run("1 + 2 * 3")); }
    @Test void precedence_parens() throws Exception { assertEquals(9L, run("(1 + 2) * 3")); }
    @Test void assoc_subtractionLeft() throws Exception { assertEquals(5L, run("10 - 2 - 3")); }
    @Test void assoc_mixedAddSubLeft() throws Exception { assertEquals(75L, run("100 - 50 + 25")); }
    @Test void precedence_twoProducts() throws Exception { assertEquals(26L, run("2 * 3 + 4 * 5")); }
    @Test void nested_parens() throws Exception { assertEquals(70L, run("2 * (3 + 4) * 5")); }
    @Test void negation_viaSubtraction() throws Exception { assertEquals(-5L, run("0 - (2 + 3)")); }
    @Test void negativeLiteral_standalone() throws Exception { assertEquals(-5L, run("-5")); }

    // --- boolean / comparison ----------------------------------------------

    @Test void and_bothTrue() throws Exception { assertEquals(true, run("1 < 2 & 2 < 3")); }
    @Test void or_oneTrue() throws Exception { assertEquals(true, run("1 > 2 | 3 < 4")); }
    @Test void eqAndNe() throws Exception { assertEquals(true, run("1 == 1 & 2 != 3")); }
    @Test void geAndLe() throws Exception { assertEquals(true, run("5 >= 5 & 5 <= 5")); }

    // --- recursion via multi-dispatch --------------------------------------

    @Test
    void recursion_sumToN() throws Exception {
        assertEquals(15L, run("""
                function sum(n:[Int:0]):Int   -> 0
                function sum(n:[Int:@>0]):Int -> n + sum(n-1)
                sum(5)"""));
    }

    @Test
    void recursion_power() throws Exception {
        assertEquals(1024L, run("""
                function pow(b:Int, e:[Int:0]):Int   -> 1
                function pow(b:Int, e:[Int:@>0]):Int -> b * pow(b, e-1)
                pow(2, 10)"""));
    }

    @Test
    void recursion_factorial20_nearLongMax() throws Exception {
        assertEquals(2432902008176640000L, run("""
                function factorial(n:[Int:0]):Int   -> 1
                function factorial(n:[Int:@>0]):Int -> n * factorial(n-1)
                factorial(20)"""));
    }

    // --- recursion via match (two recursive calls) -------------------------

    @Test
    void recursion_fibonacciViaMatch() throws Exception {
        assertEquals(55L, run("""
                function fib(n:Int):Int -> match n {
                  [@<=0] -> 0
                  [@==1] -> 1
                  [@>1]  -> fib(n-1) + fib(n-2)
                }
                fib(10)"""));
    }

    // --- dispatch most-specific --------------------------------------------

    @Test
    void dispatch_mostSpecificThenFallthrough() throws Exception {
        assertEquals(30L, run("""
                function f(n:[Int:0]):Int -> 10
                function f(n:Int):Int     -> 20
                f(0) + f(5)"""));
    }

    // --- structs ------------------------------------------------------------

    @Test
    void struct_nestedFieldAccessChain() throws Exception {
        // l.b.x - l.a.x : probes a two-level field-access chain.
        assertEquals(4L, run("""
                struct Point(x:Int, y:Int)
                struct Line(a:Point, b:Point)
                function dx(l:Line):Int -> l.b.x - l.a.x
                dx(Line(Point(1,2), Point(5,6)))"""));
    }

    @Test
    void struct_byNameLiteralReordered() throws Exception {
        assertEquals(7L, run("""
                struct Point(x:Int, y:Int)
                function manhattan(p:Point):Int -> p.x + p.y
                manhattan(Point{y=4, x=3})"""));
    }

    @Test
    void struct_returnedFromFunctionThenField() throws Exception {
        // origin().y — field access on a call result.
        assertEquals(0L, run("""
                struct Point(x:Int, y:Int)
                function origin():Point -> Point(0, 0)
                origin().y"""));
    }

    // --- let ----------------------------------------------------------------

    @Test
    void let_crossReferenceArithmetic() throws Exception {
        assertEquals(12L, run("let n = 5\nlet m = n + 1\nm * 2"));
    }

    // --- match on a computed scrutinee -------------------------------------

    @Test
    void match_onComputedScrutineeWithExplicitBase() throws Exception {
        // Computed scrutinee (n + 1) works fine when the arm base is explicit.
        assertEquals(0L, run("""
                function f(n:Int):Int -> match n + 1 {
                  [Int:@<=0] -> 0
                  [Int:@>0]  -> 1
                }
                f(-1)"""));
    }

    // --- compile-time refinement discharge (good case) ---------------------

    @Test
    void compileTime_goodThresholdReturnCompilesAndRuns() throws Exception {
        // inc raises x>=1 to x>1 — the BoundAnalysis discharge should accept it
        // at compile time, and it runs.
        assertEquals(6L, run("""
                function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1
                inc(5)"""));
    }

    // --- receipt graph report generates on a real program ------------------

    @Test
    void receiptReport_factorialGeneratesAndDischarges() {
        String src = """
                function factorial(n:[Int:@>=0]):[Int:@>=1] -> match n {
                  [@==0] -> 1
                  [@>0]  -> n * factorial(n-1)
                }
                factorial(5)""";
        ReceiptGraphReport.Result r = ReceiptGraphReport.fromAltSource(src, "factorial.ptf");
        ReceiptGraphReport.Result.Generated g =
                assertInstanceOf(ReceiptGraphReport.Result.Generated.class, r,
                        () -> "report failed: " + r);
        assertTrue(g.text().contains("discharged"),
                () -> "expected a discharged obligation in the report:\n" + g.text());
    }

    // ===== Group B: behavior probes (prediction in comment) =================

    @Test
    void probe_unaryMinusMidExpression() throws Exception {
        // CONFIRMED: 3 * -2 == -6 — unary minus parses mid-expression.
        assertEquals(-6L, run("3 * -2"));
    }

    @Test
    void finding_contextualBaseFailsOnComputedScrutinee() {
        // FINDING: contextual [@..] arms need the scrutinee's base sort, which
        // the parser infers only for a bare Var scrutinee. With a computed
        // scrutinee (match n + 1) there's no base → parse error. Workaround is
        // explicit [Int:@..] (see match_onComputedScrutineeWithExplicitBase).
        ParseException ex = assertThrows(ParseException.class, () -> run("""
                function f(n:Int):Int -> match n + 1 {
                  [@<=0] -> 0
                  [@>0]  -> 1
                }
                f(-1)"""));
        assertTrue(ex.getMessage().contains("no contextual base"),
                () -> "unexpected: " + ex.getMessage());
    }

    @Test
    void finding_boolNotPrefixNotLowerable() {
        // FINDING (known TODO): `!` lexes/parses but the IR has no Not op, so
        // boolean negation can't lower. Not yet usable.
        ParseException ex = assertThrows(ParseException.class, () -> run("!(1 < 2)"));
        assertTrue(ex.getMessage().contains("Not op"), () -> "unexpected: " + ex.getMessage());
    }

    @Test
    void finding_inlineLambdaNotSupported() {
        // FINDING (known TODO): inline alt-syntax lambda `(x:Int) -> ...` isn't
        // parseable; S-expr `(lambda ...)` is the only lambda surface today.
        assertThrows(ParseException.class, () -> run("let inc = (x:Int) -> x + 1\ninc(5)"));
    }

    @Test
    void letRedefinitionGivesRedefinitionMessage() {
        // Redefining a `let` is rejected (no shadowing) — now with a targeted
        // "already defined" message instead of the generic overload-overlap one.
        CompileException ex = assertThrows(CompileException.class,
                () -> run("let n = 5\nlet n = 10\nn"));
        assertTrue(ex.getMessage().contains("already defined"),
                () -> "unexpected: " + ex.getMessage());
    }

    @Test
    void finding_unprovableReturnRefinementIsUnenforced() throws Exception {
        // FINDING (soundness gap — verified root cause): return refinements are
        // never verified by the compile pipeline. FunctionCheck.verifyDefinition
        // (the "proven return sort" check) is implemented + demo-tested but NOT
        // invoked by IrCompiler.compile (shared by compile/compileAlt). The
        // compiler checks only *argument* refinements (StaticDispatch / runtime
        // match), never *returns*. So bad(x:Int):[Int:@>0] -> x compiles and
        // bad(-1) returns -1, silently violating the declared [Int:@>0]. Locked
        // in as current behavior; wiring verifyDefinition into compile is a
        // design call.
        assertEquals(-1L, run("""
                function bad(x:Int):[Int:@>0] -> x
                bad(-1)"""));
    }

    @Test
    void probe_nonExhaustiveMatchIsRejected() throws Exception {
        // PREDICT: compile-time totality rejects [@>0] alone (misses @<=0).
        // Asserting it does NOT silently compile+run.
        boolean threw = false;
        try {
            run("""
                    function f(n:Int):Int -> match n {
                      [@>0] -> 1
                    }
                    f(5)""");
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw, "non-exhaustive match [@>0] should be rejected (misses @<=0)");
    }

    @Test
    void probe_comparisonChain() throws Exception {
        // PREDICT: `1 < 2 < 3` is either a parse/type error or evaluates oddly.
        // Asserting it throws; if it returns a value, triage what it means.
        boolean threw = false;
        try {
            run("1 < 2 < 3");
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw, "chained comparison 1 < 2 < 3 should not silently evaluate");
    }

    // ===== Scenario 1: dependent return refinements (reference params) =======

    @Test
    void probe_dependentReturnWithBodyRuns() throws Exception {
        // PROBE: return refinement references params a, b: [Int:a+b] (≡ @==a+b).
        // With an explicit body, does it parse/compile/run?
        assertEquals(5L, run("""
                function add(a:Int, b:Int):[Int:a+b] -> a + b
                add(2, 3)"""));
    }

    @Test
    void probe_dependentReturnSpecOnlySynthesizesBody() throws Exception {
        // PROBE: spec-only, body synthesized from the value-pin a+b.
        assertEquals(5L, run("""
                function add(a:Int, b:Int):[Int:a+b]
                add(2, 3)"""));
    }

    @Test
    void probe_matchFreeNamePredicateRejected() {
        // SCENARIO 2 restriction: a bare free-name predicate in a match arm has
        // no referent without tuples — should be rejected (not silently treated
        // as the scrutinee or an unknown).
        boolean threw = false;
        try {
            run("""
                    function f(x:Int):Int -> match x {
                      [y>0]  -> 1
                      [y<=0] -> 0
                    }
                    f(5)""");
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw, "free-name match predicate [y>0] should be rejected (needs tuples)");
    }
}
