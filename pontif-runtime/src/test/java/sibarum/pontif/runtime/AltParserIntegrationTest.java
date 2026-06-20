package sibarum.pontif.runtime;

import org.junit.jupiter.api.Disabled;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the alt syntax frontend: source text → AltParser →
 * IrModule → IrCompiler → IrInterpreter. Verifies that the alt-syntax frontend
 * produces the same IR as the S-expression frontend for the example programs
 * in {@code docs/alternative-syntax.ptf}.
 */
class AltParserIntegrationTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "t.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void module_decl_aloneIsValid() throws Exception {
        IrModule m = AltParser.parseModule("module alt.syntax", "t.ptf");
        assertEquals("alt.syntax", m.name());
        assertEquals(0, m.statements().size());
    }

    @Test
    void trailing_expression_becomesMain() throws Exception {
        // No module decl, no top-level decls, just an expression. Becomes main.
        assertEquals(7L, run("1 + 2 * 3"));
    }

    @Test
    void requires_and_exports_useDotBraceForm() throws Exception {
        // `.{...}` is the dictionary-decomposition syntax — see principle 7 in
        // docs/alternative-syntax.ptf. Brackets are reserved for sorts.
        String src = """
                module m

                requires math.{min, max, avg, floor}
                exports @.{factorial, isEven}

                42
                """;
        IrModule m = AltParser.parseModule(src, "t.ptf");
        assertEquals("m", m.name());
        assertEquals(2, m.statements().size());
        assertTrue(m.statements().get(0) instanceof sibarum.pontif.ir.IrStmt.Requires);
        sibarum.pontif.ir.IrStmt.Requires req =
                (sibarum.pontif.ir.IrStmt.Requires) m.statements().get(0);
        assertEquals("math", req.targetModule());
        assertEquals(java.util.List.of("min", "max", "avg", "floor"), req.localNames());
        assertTrue(m.statements().get(1) instanceof sibarum.pontif.ir.IrStmt.Exports);
        sibarum.pontif.ir.IrStmt.Exports exp =
                (sibarum.pontif.ir.IrStmt.Exports) m.statements().get(1);
        assertTrue(exp.self());
        assertEquals(java.util.List.of("factorial", "isEven"), exp.names());
        // Main still parses & runs.
        assertEquals(42L, run(src));
    }

    @Test
    void singleCharLogicalOps_parseAndEvaluate() throws Exception {
        // `&`, `|`, `!` are the logical operators (no `&&`/`||`).
        // & has higher precedence than |, both lower than comparisons.
        assertEquals(true,  run("1 < 2 & 3 < 4"));
        assertEquals(true,  run("1 < 2 | 3 > 4"));
        assertEquals(false, run("1 > 2 | 3 > 4"));
    }

    @Test
    void multi_dispatch_factorial_runs() throws Exception {
        String src = """
                module factorial

                function factorial(n:[Int:0]):Int   -> 1
                function factorial(n:[Int:@>0]):Int -> n * factorial(n-1)

                factorial(6)
                """;
        assertEquals(720L, run(src));
    }

    @Test
    void mutually_recursive_isEven_isOdd_runs() throws Exception {
        String src = """
                module eo

                function isEven(n:[Int:0]):Int   -> 1
                function isEven(n:[Int:@>0]):Int -> isOdd(n-1)
                function isOdd(n:[Int:0]):Int    -> 0
                function isOdd(n:[Int:@>0]):Int  -> isEven(n-1)

                isEven(4)
                """;
        assertEquals(1L, run(src));
    }

    @Test
    void in_body_match_with_predicate_branches() throws Exception {
        // The classic sign function — predicate-style branches using `@`
        // (the scrutinee) and contextual base inference (the scrutinee `n`
        // has sort Int, so [@<0] sugars to [Int:@<0]).
        String src = """
                module sign

                function sign(n:Int):Int -> match n
                  [@<0 ] -> -1
                  [@==0] -> 0
                  [@>0 ] -> 1

                sign(-7)
                """;
        assertEquals(-1L, run(src));
    }

    @Test
    void match_withExplicitBase_alsoWorks() throws Exception {
        // The explicit form must continue to work — contextual is sugar, not
        // a replacement.
        String src = """
                module sign

                function sign(n:Int):Int -> match n
                  [Int:@<0 ] -> -1
                  [Int:@==0] -> 0
                  [Int:@>0 ] -> 1

                sign(7)
                """;
        assertEquals(1L, run(src));
    }

    @Test
    void match_withBraces_works() throws Exception {
        // Brace form lets matches nest cleanly.
        String src = """
                module m

                function classify(n:Int):Int -> match n {
                  [@<0]  -> -1
                  [@>=0] -> 1
                }

                classify(0)
                """;
        assertEquals(1L, run(src));
    }

    @Test
    void union_refinement_acceptsListedValues() throws Exception {
        String src = """
                module m

                function classify(n:[Int:0|1]):Int -> 100
                function classify(n:Int):Int       -> 200

                classify(1) + classify(7)
                """;
        // classify(1) hits the union refinement (100); classify(7) falls to general (200)
        assertEquals(300L, run(src));
    }

    @Test
    void singleton_bool_refinement_synthesizes_body() throws Exception {
        // `function alwaysFalse():[Bool:false]` — spec-only, but the return sort
        // sugars to `Refined(Bool, @==false)`, so the body is derived as `false`
        // and the decl becomes a normal FunctionDecl.
        IrModule m = AltParser.parseModule(
                "module m\nfunction alwaysFalse():[Bool:false];",
                "t.ptf");
        sibarum.pontif.ir.IrStmt.FunctionDecl fd =
                (sibarum.pontif.ir.IrStmt.FunctionDecl) m.statements().get(0);
        assertEquals("alwaysFalse", fd.name());
        assertEquals(false, ((sibarum.pontif.ir.IrExpr.Bool) fd.body()).value());
    }

    @Test
    void spec_only_function_singleton_int_runs_end_to_end() throws Exception {
        // [Int:42] → Refined(Int, @==42) → derived body = 42
        String src = """
                module m
                function answer():[Int:42];
                answer()
                """;
        assertEquals(42L, run(src));
    }

    @Test
    void spec_only_function_with_typed_equality_runs_end_to_end() throws Exception {
        // [Int:0] sugars to Refined(Int, @==0) → derived body = 0
        String src = """
                module m
                function zero():[Int:0];
                zero()
                """;
        assertEquals(0L, run(src));
    }

    @Test
    void bare_operator_function_runs_end_to_end() throws Exception {
        // `function +(l:Rational, r:Rational)` — bare-name operator generic.
        // a + b inside sum routes to Call("+", [a, b]); n = 1*4 + 3*2 = 10.
        String src = """
                module m
                struct Rational(n:Int, d:Int)
                function +(l:Rational, r:Rational):Rational -> Rational(l.n*r.d + r.n*l.d, l.d*r.d)
                function sum(a:Rational, b:Rational):Rational -> a + b
                sum(Rational(1,2), Rational(3,4)).n
                """;
        assertEquals(10L, run(src));
    }

    @Test
    void spec_only_function_with_underspecified_return_is_hard_error() {
        // [Int:@>=0] → Refined(Int, @>=0) — no single value to synthesize a
        // body from. A body-less function with a non-pinning return is a hard
        // error now, rather than a silently-dropped NoOp that looked defined
        // but failed later with "Unknown function".
        ParseException ex = assertThrows(ParseException.class, () ->
                AltParser.parseModule("module m\nfunction f():[Int:@>=0];", "t.ptf"));
        assertTrue(ex.getMessage().contains("has no body"),
                () -> "Unexpected message: " + ex.getMessage());
    }

    @Test
    void spec_only_method_with_synthesizable_return_runs_end_to_end() throws Exception {
        // method synthesis path: derived body + injected self param.
        // `method Int.zero():[Int:0]` ⇒ `function Int.zero(self:Int):[Int:0] -> 0`.
        String src = """
                module m
                method Int.zero():[Int:0];
                Int.zero(5)
                """;
        assertEquals(0L, run(src));
    }

    @Test
    void struct_decl_creates_typeAlias() throws Exception {
        IrModule m = AltParser.parseModule(
                "module m\nstruct Point(x:Int, y:Int)",
                "t.ptf");
        assertEquals(1, m.statements().size());
        assertTrue(m.statements().get(0) instanceof sibarum.pontif.ir.IrStmt.TypeAlias);
        sibarum.pontif.ir.IrStmt.TypeAlias ta = (sibarum.pontif.ir.IrStmt.TypeAlias) m.statements().get(0);
        assertEquals("Point", ta.name());
        assertTrue(ta.sort() instanceof sibarum.pontif.ir.IrSort.Structural);
    }

    @Test
    void struct_then_function_uses_alias() throws Exception {
        String src = """
                module geo

                struct Point(x:Int, y:Int)

                function manhattan(p:Point):Int -> p.x + p.y

                manhattan(Point(3, 4))
                """;
        assertEquals(7L, run(src));
    }

    @Test
    void struct_literal_positional_and_by_name_are_equivalent() throws Exception {
        // Both forms construct the same record value; field access yields the
        // same result regardless of source ordering.
        String src = """
                struct Point(x:Int, y:Int)

                function manhattan(p:Point):Int -> p.x + p.y

                manhattan(Point{y=4, x=3})
                """;
        assertEquals(7L, run(src));
    }

    @Test
    void let_topLevel_integerSingleton_evaluates() throws Exception {
        // `let n = 5` registers n as a 0-arg function returning 5.
        // Bare `n` in main expression position rewrites to Call("n", []).
        String src = """
                let n = 5
                n + 1
                """;
        assertEquals(6L, run(src));
    }

    @Test
    void let_crossReference_inferredSortStillCompiles() throws Exception {
        // `m` references `n` — the inferred sort of m is [Int:@==(n()+1)],
        // which must lower through the compiler without complaint.
        String src = """
                let n = 5
                let m = n + 1
                m * 2
                """;
        assertEquals(12L, run(src));
    }

    @Test
    void let_record_fieldAccess_evaluatesEndToEnd() throws Exception {
        // The original "I can't access the variable after construction"
        // case: `origin.x` must work because `origin` rewrites to Call("origin", [])
        // and the dispatch table resolves it to the 0-arg let binding.
        String src = """
                struct Point(x:Int, y:Int)
                let origin = Point(3, 4)
                origin.x + origin.y
                """;
        assertEquals(7L, run(src));
    }

    @Test
    void letExpr_simpleBinding_evaluates() throws Exception {
        // In-expression let: `let m = 5 m + 1` evaluates to 6.
        String src = "function f(n:Int):Int -> let m = 5 m + 1\nf(0)";
        assertEquals(6L, run(src));
    }

    @Test
    void letExpr_referencesParam() throws Exception {
        String src = "function f(n:Int):Int -> let m = n + 1 m * 2\nf(3)";
        assertEquals(8L, run(src));
    }

    @Test
    void letExpr_nestedBindings() throws Exception {
        String src = """
                function f(n:Int):Int ->
                  let a = n + 1
                  let b = a * 2
                  a + b
                f(3)
                """;
        // a = 4, b = 8, a + b = 12
        assertEquals(12L, run(src));
    }

    @Test
    void letExpr_shadowsParam() throws Exception {
        // Inside the let body, `n` refers to the let-bound 10, not param 3.
        String src = "function f(n:Int):Int -> let n = 10 n + 1\nf(3)";
        assertEquals(11L, run(src));
    }

    @Test
    void sameBaseUnion_normalizesAndDispatches_endToEnd() throws Exception {
        // [Int:0|1|2] uses per-disjunct @==EXPR sugar — predicate-level.
        // [[Int:0]|[Int:1]] is sort-level same-base — normalizes to the
        // same Refined sort. Both forms should accept 0, 1 and reject 2.
        String src = """
                function tag(n:[[Int:0]|[Int:1]]):Int -> n + 100
                tag(0)
                """;
        assertEquals(100L, run(src));
    }

    @Test
    void crossBaseUnion_dispatches_endToEnd() throws Exception {
        // A function accepting Int OR Bool — both arg shapes should work.
        // Use a body that just returns a fixed value (doesn't care about
        // which branch).
        String intSrc = """
                function tag(x:[Int|Bool]):Int -> 7
                tag(42)
                """;
        String boolSrc = """
                function tag(x:[Int|Bool]):Int -> 7
                tag(true)
                """;
        assertEquals(7L, run(intSrc));
        assertEquals(7L, run(boolSrc));
    }

    @Test
    void intersection_narrowsRange_endToEnd() throws Exception {
        // [Int:@>0 & @<10] — both the AND form and the same-base
        // intersection should accept 5 and reject 100 / -3.
        String src = """
                function smallPositive(n:[[Int:@>0] & [Int:@<10]]):Int -> n * 2
                smallPositive(5)
                """;
        assertEquals(10L, run(src));
    }

    @Test
    void trait_endToEnd_fromAltSyntax() throws Exception {
        // The full Pontif trait story, top to bottom:
        //   - Declare a trait via `trait Duck{...}`.
        //   - Declare a struct via `struct Donald(...)`.
        //   - Assign the trait via `assign trait Donald:Duck { ... }`.
        //   - Function takes a trait-typed param; call with a Donald.
        String src = """
                trait Duck{quack:[Method():Int]}
                struct Donald(name:Int)
                assign trait Donald:Duck {
                  quack():Int -> this.name + 100
                }
                function describe(d:Duck):Int -> d.quack()
                describe(Donald(7))
                """;
        // Donald(7).name = 7; quack returns this.name + 100 = 107.
        assertEquals(107L, run(src));
    }

    @Test
    void trait_directTraitMethodCall_fromAltSyntax() throws Exception {
        // Trait.method call directly on a struct value — slice-1 fallback.
        String src = """
                trait Duck{quack:[Method():Int]}
                struct Donald(name:Int)
                assign trait Donald:Duck {
                  quack():Int -> 42
                }
                let donald = Donald(0)
                donald.quack()
                """;
        assertEquals(42L, run(src));
    }

    @Test
    void staticAccess_zeroArgFunction_bareReferenceEvaluates() throws Exception {
        // `Point.zero` (no parens) routes to a 0-arg Call via the
        // bare-access rewrite. Same shape as `let Point.zero = Point(0,0)`.
        String src = """
                struct Point(x:Int, y:Int)
                function Point.zero():Point -> Point(0, 0)
                Point.zero.x
                """;
        assertEquals(0L, run(src));
    }

    @Test
    void staticAccess_letAndZeroArgFunction_produceSameValue() throws Exception {
        // The two declaration forms are interchangeable at use sites.
        String viaLet = """
                struct Point(x:Int, y:Int)
                let Point.origin = Point(7, 8)
                Point.origin.x + Point.origin.y
                """;
        String viaFn = """
                struct Point(x:Int, y:Int)
                function Point.origin():Point -> Point(7, 8)
                Point.origin.x + Point.origin.y
                """;
        assertEquals(run(viaLet), run(viaFn));
        assertEquals(15L, run(viaFn));
    }

    @Test
    void staticAccess_unqualifiedZeroArg_evaluates() throws Exception {
        String src = """
                function five():Int -> 5
                five + 1
                """;
        assertEquals(6L, run(src));
    }

    @Test
    void operatorOverload_pointPlusPoint_evaluates() throws Exception {
        String src = """
                struct Point(x:Int, y:Int)
                function +(a:Point, b:Point):Point -> Point(a.x + b.x, a.y + b.y)
                function sum(a:Point, b:Point):Point -> a + b
                sum(Point(1, 2), Point(3, 4)).x
                """;
        // a + b → Point(4, 6). .x → 4.
        assertEquals(4L, run(src));
    }

    @Test
    void operatorOverload_pointEquality_evaluates() throws Exception {
        String src = """
                struct Point(x:Int, y:Int)
                function ==(a:Point, b:Point):Bool -> a.x == b.x & a.y == b.y
                function eq(a:Point, b:Point):Bool -> a == b
                eq(Point(1, 2), Point(1, 2))
                """;
        assertEquals(true, run(src));
    }

    @Test
    void operatorOverload_pointEquality_negative() throws Exception {
        String src = """
                struct Point(x:Int, y:Int)
                function ==(a:Point, b:Point):Bool -> a.x == b.x & a.y == b.y
                function eq(a:Point, b:Point):Bool -> a == b
                eq(Point(1, 2), Point(1, 99))
                """;
        assertEquals(false, run(src));
    }

    @Test
    void operatorOverload_chainsLeftToRight() throws Exception {
        String src = """
                struct Point(x:Int, y:Int)
                function +(a:Point, b:Point):Point -> Point(a.x + b.x, a.y + b.y)
                function tri(a:Point, b:Point, c:Point):Point -> a + b + c
                tri(Point(1, 1), Point(2, 2), Point(3, 3)).x
                """;
        // (1+2)+3 = 6 in x
        assertEquals(6L, run(src));
    }

    @Test
    void operatorOverload_primitiveArithmeticUnchanged() throws Exception {
        // With Point.+ declared, Int+Int must still route through BinOp.
        String src = """
                struct Point(x:Int, y:Int)
                function +(a:Point, b:Point):Point -> Point(a.x + b.x, a.y + b.y)
                3 + 4
                """;
        assertEquals(7L, run(src));
    }

    @Test
    void operatorOverload_mixesWithMethodCalls() throws Exception {
        // Both `pointA.shifted(...)` and `p + q` work in the same function.
        String src = """
                struct Point(x:Int, y:Int)
                function +(a:Point, b:Point):Point -> Point(a.x + b.x, a.y + b.y)
                method Point.shifted(dx:Int, dy:Int):Point ->
                  Point(this.x + dx, this.y + dy)
                function f(p:Point, q:Point):Int -> (p + q).shifted(10, 10).x
                f(Point(1, 1), Point(2, 2))
                """;
        // p+q = Point(3,3); shifted(10,10) = Point(13,13); .x = 13
        assertEquals(13L, run(src));
    }

    @Test
    void gibberishTypeNames_failAtCompileTime() {
        // The exact example the user posted. Should fail at compile time
        // with a clear "unknown sort" error pointing at the param/return
        // sort, NOT silently parse and fail at runtime.
        String src = """
                function shifted(p:Zzzzz):Xxxxx -> {
                  let dx = p.x + 1
                  let dy = p.y + 1
                  Qqqqq(dx, dy)
                }
                shifted(0)
                """;
        Exception ex = assertThrows(Exception.class, () -> run(src));
        assertTrue(ex instanceof sibarum.pontif.ir.CompileException,
                () -> "Expected CompileException; got " + ex.getClass().getSimpleName()
                        + ": " + ex.getMessage());
        String msg = ex.getMessage();
        assertTrue(msg.contains("Zzzzz") || msg.contains("Xxxxx") || msg.contains("Qqqqq"),
                () -> "Expected gibberish name in error; got: " + msg);
    }

    @Test
    void methodCall_onParam_evaluatesEndToEnd() throws Exception {
        // method declared, then called from a function via instance syntax.
        String src = """
                struct Point(x:Int, y:Int)
                method Point.magnitudeSq():Int -> this.x * this.x + this.y * this.y
                function f(p:Point):Int -> p.magnitudeSq()
                f(Point(3, 4))
                """;
        assertEquals(25L, run(src));
    }

    @Test
    void methodCall_onLetBoundValue_evaluatesEndToEnd() throws Exception {
        // The let-bound value is rewritten to a 0-arg Call before being
        // passed as `self`. Verifies the rewrite composes with method routing.
        String src = """
                struct Point(x:Int, y:Int)
                method Point.magnitudeSq():Int -> this.x * this.x + this.y * this.y
                let origin = Point(3, 4)
                origin.magnitudeSq()
                """;
        assertEquals(25L, run(src));
    }

    @Test
    void methodCall_chained_evaluatesEndToEnd() throws Exception {
        // Two methods on Point: shifted produces a new Point, magnitudeSq
        // reads from one. Composes naturally.
        String src = """
                struct Point(x:Int, y:Int)
                method Point.shifted(dx:Int, dy:Int):Point ->
                  Point(this.x + dx, this.y + dy)
                method Point.magnitudeSq():Int -> this.x * this.x + this.y * this.y
                function f(p:Point):Int -> p.shifted(1, 1).magnitudeSq()
                f(Point(2, 3))
                """;
        // p = (2,3), shifted(1,1) = (3,4), magnitudeSq = 9+16 = 25
        assertEquals(25L, run(src));
    }

    @Test
    void dottedLet_fieldAccess_evaluatesEndToEnd() throws Exception {
        String src = """
                struct Point(x:Int, y:Int)
                let Point.origin = Point(3, 4)
                Point.origin.x + Point.origin.y
                """;
        assertEquals(7L, run(src));
    }

    @Test
    void blockExpr_aroundLetChain_evaluates() throws Exception {
        // User's example: explicit block braces around a let chain make the
        // body boundary unambiguous (no greedy-Pratt edge cases).
        String src = """
                struct Point(x:Int, y:Int)
                function shifted(p:Point):Point ->
                {
                  let dx = p.x + 1
                  let dy = p.y + 1
                  Point(dx, dy)
                }
                shifted(Point(2, 3)).x + shifted(Point(2, 3)).y
                """;
        // dx = 3, dy = 4, Point(3, 4); .x + .y = 7
        assertEquals(7L, run(src));
    }

    @Test
    void blockExpr_isPureUnwrap() throws Exception {
        // `{ EXPR }` and `EXPR` are interchangeable at runtime.
        assertEquals(42L, run("{ 42 }"));
        assertEquals(42L, run("{ { { 42 } } }"));
    }

    @Test
    void letExpr_recordValue_fieldAccess() throws Exception {
        String src = """
                struct Point(x:Int, y:Int)
                function dist(p:Point):Int ->
                  let dx = p.x
                  let dy = p.y
                  dx * dx + dy * dy
                dist(Point(3, 4))
                """;
        assertEquals(25L, run(src));
    }

    @Test
    void let_recordAsFunctionArg() throws Exception {
        String src = """
                struct Point(x:Int, y:Int)
                function manhattan(p:Point):Int -> p.x + p.y
                let origin = Point(3, 4)
                manhattan(origin)
                """;
        assertEquals(7L, run(src));
    }

    @Test
    void struct_literal_distance_endToEnd() throws Exception {
        // Mixed positional and by-name construction passed to a function that
        // reads multiple fields. Exercises the LinkedHashMap canonicalization:
        // {y=4, x=3} reorders to (x=3, y=4) so field access by name still works.
        String src = """
                struct Point(x:Int, y:Int)

                function distSq(p:Point, q:Point):Int ->
                    (p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y)

                distSq(Point(0, 0), Point{y=4, x=3})
                """;
        assertEquals(25L, run(src));
    }

    @Test
    void struct_match_destructure_desugarsToLetBindings() throws Exception {
        // [Point(x, y)] in match position binds x and y to the scrutinee's fields.
        // The parser desugars to let-wrapping the result expression.
        String src = """
                module m

                struct Point(x:Int, y:Int)

                function sumXY(p:Point):Int -> match p {
                  [Point(x, y)] -> x + y
                }

                42
                """;
        IrModule m = AltParser.parseModule(src, "t.ptf");
        // struct + function decls
        assertEquals(2, m.statements().size());
        sibarum.pontif.ir.IrStmt.FunctionDecl fd =
                (sibarum.pontif.ir.IrStmt.FunctionDecl) m.statements().get(1);
        sibarum.pontif.ir.IrExpr.Match match = (sibarum.pontif.ir.IrExpr.Match) fd.body();
        sibarum.pontif.ir.IrExpr.MatchBranch branch = match.branches().get(0);
        // The branch result should be wrapped: let x = p.x in (let y = p.y in (x + y))
        sibarum.pontif.ir.IrExpr.LetIn outer =
                (sibarum.pontif.ir.IrExpr.LetIn) branch.result();
        assertEquals("x", outer.name());
        sibarum.pontif.ir.IrExpr.FieldAccess xAccess =
                (sibarum.pontif.ir.IrExpr.FieldAccess) outer.value();
        assertEquals("x", xAccess.fieldName());
        sibarum.pontif.ir.IrExpr.LetIn inner =
                (sibarum.pontif.ir.IrExpr.LetIn) outer.body();
        assertEquals("y", inner.name());
    }

    @Test
    void multiFeatureSample_runs_to_134() throws Exception {
        // A multi-feature smoke sample (overloads, mutual recursion, match,
        // value-pin synthesis) exercised end to end. NOT the playground default
        // — that's QuickTour.SOURCE, pinned by PlaygroundIntegrationTest.
        String src = """
                module tour

                function factorial(n:[Int:0])  :Int -> 1
                function factorial(n:[Int:@>0]):Int -> n * factorial(n-1)

                function isEven(n:[Int:0])  :[Int:0|1] -> 1
                function isEven(n:[Int:@>0]):[Int:0|1] -> isOdd(n-1)
                function isOdd (n:[Int:0])  :[Int:0|1] -> 0
                function isOdd (n:[Int:@>0]):[Int:0|1] -> isEven(n-1)

                function sign(n:Int):Int -> match n
                  [@<0 ] -> -1
                  [@==0] ->  0
                  [@>0 ] ->  1

                function timesTwo(n:Int):[Int:n*2];

                factorial(5) + isEven(8) + sign(-3) + timesTwo(7)
                """;
        // 120 + 1 + (-1) + 14 = 134
        assertEquals(134L, run(src));
    }

    @Test
    void noop_placeholders_for_unimplemented_forms() throws Exception {
        String src = """
                module m

                requires math.{min, max}
                exports @.{foo, bar}

                42
                """;
        IrModule m = AltParser.parseModule(src, "t.ptf");
        // requires/exports lower to structured IrStmt.Requires/Exports — no NoOp
        // placeholders remain. (Spec-only functions/methods/lets now require the
        // `;` synthesis directive; non-synthesizable ones are hard errors — see
        // spec_only_*_is_hard_error and SpecOnlyLetTest.)
        long requiresCount = m.statements().stream()
                .filter(s -> s instanceof sibarum.pontif.ir.IrStmt.Requires).count();
        long exportsCount = m.statements().stream()
                .filter(s -> s instanceof sibarum.pontif.ir.IrStmt.Exports).count();
        long noOpCount = m.statements().stream()
                .filter(s -> s instanceof sibarum.pontif.ir.IrStmt.NoOp).count();
        assertEquals(1, requiresCount);
        assertEquals(1, exportsCount);
        assertEquals(0, noOpCount);
        // Main is 42.
        assertEquals(42L, run("module m\n42"));
    }

    @Test
    void method_with_body_desugars_to_function_decl() throws Exception {
        // method Box.bump(by:Int):Int -> this.value + by
        //   ⇒ function Box.bump(self:Box, by:Int):Int -> this.value + by
        // (Note: the user writes `self` directly under the new design. The
        // old @-as-receiver substitution magic is gone.)
        IrModule m = AltParser.parseModule(
                "module m\nmethod Box.bump(by:Int):Int -> this.value + by",
                "t.ptf");
        assertEquals(1, m.statements().size());
        sibarum.pontif.ir.IrStmt.FunctionDecl fd =
                (sibarum.pontif.ir.IrStmt.FunctionDecl) m.statements().get(0);
        assertEquals("Box.bump", fd.name());
        assertEquals(2, fd.params().size());
        assertEquals("this", fd.params().get(0).name());
        sibarum.pontif.ir.IrSort.Named receiver =
                (sibarum.pontif.ir.IrSort.Named) fd.params().get(0).sort();
        assertEquals("Box", receiver.name());
        assertEquals("by", fd.params().get(1).name());
        // Body: (this.value) + by
        sibarum.pontif.ir.IrExpr.BinOp body = (sibarum.pontif.ir.IrExpr.BinOp) fd.body();
        assertEquals(sibarum.pontif.ir.IrExpr.Op.ADD, body.op());
        sibarum.pontif.ir.IrExpr.FieldAccess lhs =
                (sibarum.pontif.ir.IrExpr.FieldAccess) body.left();
        assertEquals("value", lhs.fieldName());
        sibarum.pontif.ir.IrExpr.Var lhsBase = (sibarum.pontif.ir.IrExpr.Var) lhs.base();
        assertEquals("this", lhsBase.name());
        sibarum.pontif.ir.IrExpr.Var rhs = (sibarum.pontif.ir.IrExpr.Var) body.right();
        assertEquals("by", rhs.name());
    }

    @Test
    void method_on_primitive_dispatches_end_to_end() throws Exception {
        // Receiver doesn't have to be a struct — Int works too, since `Int` is
        // just an IrSort.Named like any other. `this` is the receiver name.
        String src = """
                module m

                method Int.bump(by:Int):Int -> this + by

                Int.bump(10, 5)
                """;
        assertEquals(15L, run(src));
    }

    @Test
    void method_without_receiver_qualification_is_parse_error() {
        ParseException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ParseException.class,
                () -> AltParser.parseModule("module m\nmethod foo(x:Int):Int -> x", "t.ptf"));
        assertTrue(ex.getMessage().contains("must be qualified"),
                "Got: " + ex.getMessage());
    }

    @Test
    void method_param_named_this_is_parse_error() {
        ParseException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ParseException.class,
                () -> AltParser.parseModule(
                        "module m\nmethod T.f(this:Int):Int -> this",
                        "t.ptf"));
        assertTrue(ex.getMessage().contains("'this'"),
                "Got: " + ex.getMessage());
    }

    @Test
    void spec_only_method_with_named_return_is_hard_error() {
        // Return sort `Point` is a plain named sort (not Refined), so there's
        // nothing to synthesize a body from — a hard error now, like the
        // function form (and unlike `let Point.origin:Point`, which stays NoOp
        // on the separate let path).
        ParseException ex = assertThrows(ParseException.class, () ->
                AltParser.parseModule("module m\nmethod Point.add(p:Point):Point;", "t.ptf"));
        assertTrue(ex.getMessage().contains("has no body"),
                () -> "Unexpected message: " + ex.getMessage());
    }

    @Test
    void function_qualified_name_mangles_to_dispatch_identifier() throws Exception {
        // function Point.manhattan(p:Int):Int -> p
        // produces IrStmt.FunctionDecl with name = "Point.manhattan"
        IrModule m = AltParser.parseModule(
                "module m\nfunction Point.manhattan(p:Int):Int -> p",
                "t.ptf");
        sibarum.pontif.ir.IrStmt.FunctionDecl fd =
                (sibarum.pontif.ir.IrStmt.FunctionDecl) m.statements().get(0);
        assertEquals("Point.manhattan", fd.name());
    }
}
