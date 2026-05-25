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
        assertTrue(m.statements().get(0) instanceof sibarum.pontif.ir.IrStmt.NoOp);
        assertTrue(m.statements().get(1) instanceof sibarum.pontif.ir.IrStmt.NoOp);
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
                "module m\nfunction alwaysFalse():[Bool:false]",
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
                function answer():[Int:42]
                answer()
                """;
        assertEquals(42L, run(src));
    }

    @Test
    void spec_only_function_with_typed_equality_runs_end_to_end() throws Exception {
        // [Int:0] sugars to Refined(Int, @==0) → derived body = 0
        String src = """
                module m
                function zero():[Int:0]
                zero()
                """;
        assertEquals(0L, run(src));
    }

    @Test
    void spec_only_function_with_underspecified_return_stays_noop() throws Exception {
        // [Int:@>=0] → Refined(Int, @>=0) — no single value to project. Stays
        // NoOp until a real proof engine can either pick a witness or flag it
        // as needing one.
        IrModule m = AltParser.parseModule(
                "module m\nfunction f():[Int:@>=0]",
                "t.ptf");
        assertTrue(m.statements().get(0) instanceof sibarum.pontif.ir.IrStmt.NoOp);
    }

    @Test
    void spec_only_method_with_synthesizable_return_runs_end_to_end() throws Exception {
        // method synthesis path: derived body + injected self param.
        // `method Int.zero():[Int:0]` ⇒ `function Int.zero(self:Int):[Int:0] -> 0`.
        String src = """
                module m
                method Int.zero():[Int:0]
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
    void playground_default_sample_runs_to_134() throws Exception {
        // Mirror of pontif-playground App.DEFAULT_CODE. Keeps the playground's
        // visible default covered: if a parser/compiler change breaks this
        // program, the test catches it before the user opens the playground.
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

                function timesTwo(n:Int):[Int:n*2]

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

                method Point.add(p:Point):Point
                let Point.origin:Point

                function f():[Int:@>=0]

                42
                """;
        IrModule m = AltParser.parseModule(src, "t.ptf");
        // requires, exports, spec-only method (named return — not synthesizable),
        // let, spec-only function with under-specified return = 5 NoOps.
        // (Synthesizable spec-only decls — `[Bool:true]`, `[Int:42]`, `[Int:0]`
        // — become FunctionDecls at parse time; see spec_only_* tests above.
        // Methods WITH a body desugar to FunctionDecls too.)
        long noOpCount = m.statements().stream()
                .filter(s -> s instanceof sibarum.pontif.ir.IrStmt.NoOp)
                .count();
        assertEquals(5, noOpCount);
        // Main is 42.
        assertEquals(42L, run("module m\n42"));
    }

    @Test
    void method_with_body_desugars_to_function_decl() throws Exception {
        // method Box.bump(by:Int):Int -> self.value + by
        //   ⇒ function Box.bump(self:Box, by:Int):Int -> self.value + by
        // (Note: the user writes `self` directly under the new design. The
        // old @-as-receiver substitution magic is gone.)
        IrModule m = AltParser.parseModule(
                "module m\nmethod Box.bump(by:Int):Int -> self.value + by",
                "t.ptf");
        assertEquals(1, m.statements().size());
        sibarum.pontif.ir.IrStmt.FunctionDecl fd =
                (sibarum.pontif.ir.IrStmt.FunctionDecl) m.statements().get(0);
        assertEquals("Box.bump", fd.name());
        assertEquals(2, fd.params().size());
        assertEquals("self", fd.params().get(0).name());
        sibarum.pontif.ir.IrSort.Named receiver =
                (sibarum.pontif.ir.IrSort.Named) fd.params().get(0).sort();
        assertEquals("Box", receiver.name());
        assertEquals("by", fd.params().get(1).name());
        // Body: (self.value) + by
        sibarum.pontif.ir.IrExpr.BinOp body = (sibarum.pontif.ir.IrExpr.BinOp) fd.body();
        assertEquals(sibarum.pontif.ir.IrExpr.Op.ADD, body.op());
        sibarum.pontif.ir.IrExpr.FieldAccess lhs =
                (sibarum.pontif.ir.IrExpr.FieldAccess) body.left();
        assertEquals("value", lhs.fieldName());
        sibarum.pontif.ir.IrExpr.Var lhsBase = (sibarum.pontif.ir.IrExpr.Var) lhs.base();
        assertEquals("self", lhsBase.name());
        sibarum.pontif.ir.IrExpr.Var rhs = (sibarum.pontif.ir.IrExpr.Var) body.right();
        assertEquals("by", rhs.name());
    }

    @Test
    void method_on_primitive_dispatches_end_to_end() throws Exception {
        // Receiver doesn't have to be a struct — Int works too, since `Int` is
        // just an IrSort.Named like any other. `self` is the receiver name.
        String src = """
                module m

                method Int.bump(by:Int):Int -> self + by

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
    void method_param_named_self_is_parse_error() {
        ParseException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ParseException.class,
                () -> AltParser.parseModule(
                        "module m\nmethod T.f(self:Int):Int -> self",
                        "t.ptf"));
        assertTrue(ex.getMessage().contains("'self'"),
                "Got: " + ex.getMessage());
    }

    @Test
    void spec_only_method_with_named_return_stays_noop() throws Exception {
        // Return sort `Point` is a plain named sort (not Refined), so the
        // synthesis pass has nothing to project. NoOp until value synthesis
        // for nominal types lands (same problem as `let Point.origin:Point`).
        IrModule m = AltParser.parseModule(
                "module m\nmethod Point.add(p:Point):Point",
                "t.ptf");
        assertTrue(m.statements().get(0) instanceof sibarum.pontif.ir.IrStmt.NoOp);
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
