package sibarum.pontif.demo.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.demo.symbolic.ArithmeticRules;
import sibarum.pontif.demo.symbolic.RefinementRules;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageDefTest {

    // --- defaults() ---

    @Test
    void defaults_carriesAllStandardKeywordsAndOps() {
        LanguageDef def = LanguageDef.defaults();
        assertEquals("module", def.moduleKeyword());
        assertEquals("defn", def.functionDeclKeyword());
        assertEquals("let", def.letKeyword());
        assertEquals("call", def.callKeyword());
        assertEquals("match", def.matchKeyword());
        assertEquals("refined", def.refinedSortKeyword());
        assertEquals("true", def.trueLiteral());
        assertEquals("false", def.falseLiteral());
        assertEquals("self", def.selfReference());
        assertEquals(9, def.binaryOps().size());
        assertEquals(IrExpr.Op.ADD, def.binaryOps().get("+"));
        assertEquals(IrExpr.Op.NE, def.binaryOps().get("!="));
    }

    @Test
    void isReserved_coversAllConfiguredWords() {
        LanguageDef def = LanguageDef.defaults();
        for (String s : List.of("module", "defn", "let", "call", "match", "refined",
                "true", "false", "self", "+", "-", "*", "<", "<=", ">", ">=", "==", "!=")) {
            assertTrue(def.isReserved(s), "expected '" + s + "' to be reserved");
        }
        assertFalse(def.isReserved("factorial"));
        assertFalse(def.isReserved("n"));
    }

    @Test
    void binaryOpFor_returnsOpKindForKnownSymbol() {
        assertEquals(IrExpr.Op.ADD, LanguageDef.defaults().binaryOpFor("+").orElseThrow());
        assertTrue(LanguageDef.defaults().binaryOpFor("nope").isEmpty());
    }

    @Test
    void withRenamedBinaryOp_replacesSpelling() {
        LanguageDef def = LanguageDef.defaults().withRenamedBinaryOp("+", "plus");
        assertFalse(def.binaryOps().containsKey("+"));
        assertEquals(IrExpr.Op.ADD, def.binaryOps().get("plus"));
        assertTrue(def.isReserved("plus"));
        assertFalse(def.isReserved("+"));
    }

    @Test
    void withRenamedBinaryOp_unknownSymbol_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> LanguageDef.defaults().withRenamedBinaryOp("nope", "x"));
    }

    // --- Parser with a customized LanguageDef parses the new spelling ---

    @Test
    void customLetKeyword_parsesWithNewWord() {
        LanguageDef def = LanguageDef.defaults().withLetKeyword("bind");
        IrExpr e = Parser.parseExpr("(bind x Int 5 (+ x 3))", "t.ptf", def);
        IrExpr.LetIn let = (IrExpr.LetIn) e;
        assertEquals("x", let.name());
        assertEquals(5L, ((IrExpr.Lit) let.value()).value());
    }

    @Test
    void customLetKeyword_oldSpellingNoLongerWorks() {
        LanguageDef def = LanguageDef.defaults().withLetKeyword("bind");
        // "let" is no longer a form keyword under this config, so it falls through
        // to "Unknown form" inside parseCompoundExpr.
        ParseException ex = assertThrows(ParseException.class,
                () -> Parser.parseExpr("(let x Int 5 x)", "t.ptf", def));
        assertTrue(ex.getMessage().contains("Unknown form")
                        && ex.getMessage().contains("let"),
                "expected Unknown form 'let'; got: " + ex.getMessage());
    }

    @Test
    void customLetKeyword_oldSpellingNowAvailableAsAVariable() {
        // Because "let" is no longer reserved, it can be used as a free variable name.
        LanguageDef def = LanguageDef.defaults().withLetKeyword("bind");
        IrExpr e = Parser.parseExpr("let", "t.ptf", def);
        assertInstanceOf(IrExpr.Var.class, e);
        assertEquals("let", ((IrExpr.Var) e).name());
    }

    @Test
    void renamedBinaryOp_parsesNewSpelling() {
        LanguageDef def = LanguageDef.defaults().withRenamedBinaryOp("+", "plus");
        IrExpr e = Parser.parseExpr("(plus 1 2)", "t.ptf", def);
        IrExpr.BinOp op = (IrExpr.BinOp) e;
        assertEquals(IrExpr.Op.ADD, op.op());
    }

    @Test
    void renamedAtomKeywords_parseCorrectly() {
        LanguageDef def = LanguageDef.defaults()
                .withTrueLiteral("yes")
                .withFalseLiteral("no")
                .withSelfReference("it");
        assertInstanceOf(IrExpr.Bool.class, Parser.parseExpr("yes", "t.ptf", def));
        assertEquals(false, ((IrExpr.Bool) Parser.parseExpr("no", "t.ptf", def)).value());
        assertInstanceOf(IrExpr.SelfRef.class, Parser.parseExpr("it", "t.ptf", def));
    }

    // --- End-to-end: a fully rebranded module still runs factorial through to the answer ---

    @Test
    void rebrandedFactorialModule_compilesAndRunsTo120() {
        // Swap roughly every keyword to demonstrate the parser is fully driven by config.
        LanguageDef rebranded = LanguageDef.defaults()
                .withModuleKeyword("program")
                .withFunctionDeclKeyword("fn")
                .withCallKeyword("invoke")
                .withMatchKeyword("switch")
                .withRefinedSortKeyword("where");

        String src = """
                (program factorial
                  ((fn factorial ((n Int)) Int
                     (switch n
                       ((where Int (== self 0)) 1)
                       ((where Int (> self 0)) (* n (invoke factorial (- n 1)))))))
                  (invoke factorial 5))
                """;

        IrModule module = Parser.parseModule(src, "rebranded.ptf", rebranded);
        Simplifier simp = simplifier();
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        Object result = new IrInterpreter(simp).eval(compiled);
        assertEquals(120L, result);
    }

    @Test
    void allBinaryOperatorsCanBeRenamedAtOnce_andProgramStillRuns() {
        // Replace every operator with a word form.
        Map<String, IrExpr.Op> wordOps = new LinkedHashMap<>();
        wordOps.put("plus", IrExpr.Op.ADD);
        wordOps.put("minus", IrExpr.Op.SUB);
        wordOps.put("times", IrExpr.Op.MUL);
        wordOps.put("lt", IrExpr.Op.LT);
        wordOps.put("le", IrExpr.Op.LE);
        wordOps.put("gt", IrExpr.Op.GT);
        wordOps.put("ge", IrExpr.Op.GE);
        wordOps.put("eq", IrExpr.Op.EQ);
        wordOps.put("ne", IrExpr.Op.NE);
        LanguageDef def = LanguageDef.defaults().withBinaryOps(wordOps);

        String src = """
                (module wordy
                  ()
                  (let x Int 7 (plus (times x 2) 1)))
                """;

        IrModule module = Parser.parseModule(src, "wordy.ptf", def);
        Simplifier simp = simplifier();
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        assertEquals(15L, new IrInterpreter(simp).eval(compiled));
    }

    // --- Default behaviour unchanged when no override is supplied ---

    @Test
    void defaultStaticEntryPoints_stillWork_withoutLanguageDefArg() {
        IrExpr e = Parser.parseExpr("(+ 1 2)", "t.ptf");
        assertInstanceOf(IrExpr.BinOp.class, e);
        assertEquals(IrExpr.Op.ADD, ((IrExpr.BinOp) e).op());
    }

    // --- Helpers ---

    private static Simplifier simplifier() {
        List<RewriteRule> rules = new ArrayList<>();
        rules.addAll(RefinementRules.all());
        rules.addAll(ArithmeticRules.all());
        return new Simplifier(rules);
    }
}
