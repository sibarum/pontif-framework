package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;

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
    void defaults_carriesAllStandardKeywordsAndOps() throws Exception {
        LanguageDef def = LanguageDef.defaults();
        assertEquals("module", def.moduleKeyword());
        assertEquals("defn", def.functionDeclKeyword());
        assertEquals("deftype", def.typeAliasKeyword());
        assertEquals("let", def.letKeyword());
        assertEquals("call", def.callKeyword());
        assertEquals("match", def.matchKeyword());
        assertEquals("lambda", def.lambdaKeyword());
        assertEquals("record", def.recordKeyword());
        assertEquals("field", def.fieldKeyword());
        assertEquals("refined", def.refinedSortKeyword());
        assertEquals("function", def.functionSortKeyword());
        assertEquals("struct", def.structSortKeyword());
        assertEquals("true", def.trueLiteral());
        assertEquals("false", def.falseLiteral());
        assertEquals("self", def.selfReference());
        assertEquals(11, def.binaryOps().size());
        assertEquals(IrExpr.Op.ADD, def.binaryOps().get("+"));
        assertEquals(IrExpr.Op.NE, def.binaryOps().get("!="));
    }

    @Test
    void isReserved_coversAllConfiguredWords() throws Exception {
        LanguageDef def = LanguageDef.defaults();
        for (String s : List.of("module", "defn", "deftype", "let", "call", "match", "lambda", "record", "field",
                "refined", "function", "struct",
                "true", "false", "self",
                "+", "-", "*", "<", "<=", ">", ">=", "==", "!=", "&&", "||")) {
            assertTrue(def.isReserved(s), "expected '" + s + "' to be reserved");
        }
        assertFalse(def.isReserved("factorial"));
        assertFalse(def.isReserved("n"));
        // "apply" is no longer a keyword — unified into "call".
        assertFalse(def.isReserved("apply"));
    }

    @Test
    void renamedLambdaKeyword_parsesWithNewSpelling() throws Exception {
        LanguageDef def = LanguageDef.defaults().withLambdaKeyword("fn");
        IrExpr e = Parser.parseExpr("(call (fn ((x Int)) Int (+ x 1)) 5)", "t.ptf", def);
        IrExpr.Apply app = (IrExpr.Apply) e;
        assertInstanceOf(IrExpr.Lambda.class, app.fn());
        assertEquals(1, app.args().size());
        // The old spelling "lambda" is now free; it parses as a Var ref.
        IrExpr free = Parser.parseExpr("lambda", "t.ptf", def);
        assertInstanceOf(IrExpr.Var.class, free);
    }

    @Test
    void binaryOpFor_returnsOpKindForKnownSymbol() throws Exception {
        assertEquals(IrExpr.Op.ADD, LanguageDef.defaults().binaryOpFor("+").orElseThrow());
        assertTrue(LanguageDef.defaults().binaryOpFor("nope").isEmpty());
    }

    @Test
    void withRenamedBinaryOp_replacesSpelling() throws Exception {
        LanguageDef def = LanguageDef.defaults().withRenamedBinaryOp("+", "plus");
        assertFalse(def.binaryOps().containsKey("+"));
        assertEquals(IrExpr.Op.ADD, def.binaryOps().get("plus"));
        assertTrue(def.isReserved("plus"));
        assertFalse(def.isReserved("+"));
    }

    @Test
    void withRenamedBinaryOp_unknownSymbol_throws() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> LanguageDef.defaults().withRenamedBinaryOp("nope", "x"));
    }

    // --- Parser with a customized LanguageDef parses the new spelling ---

    @Test
    void customLetKeyword_parsesWithNewWord() throws Exception {
        LanguageDef def = LanguageDef.defaults().withLetKeyword("bind");
        IrExpr e = Parser.parseExpr("(bind x Int 5 (+ x 3))", "t.ptf", def);
        IrExpr.LetIn let = (IrExpr.LetIn) e;
        assertEquals("x", let.name());
        assertEquals(5L, ((IrExpr.Lit) let.value()).value());
    }

    @Test
    void customLetKeyword_oldSpellingNoLongerWorks() throws Exception {
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
    void customLetKeyword_oldSpellingNowAvailableAsAVariable() throws Exception {
        // Because "let" is no longer reserved, it can be used as a free variable name.
        LanguageDef def = LanguageDef.defaults().withLetKeyword("bind");
        IrExpr e = Parser.parseExpr("let", "t.ptf", def);
        assertInstanceOf(IrExpr.Var.class, e);
        assertEquals("let", ((IrExpr.Var) e).name());
    }

    @Test
    void renamedBinaryOp_parsesNewSpelling() throws Exception {
        LanguageDef def = LanguageDef.defaults().withRenamedBinaryOp("+", "plus");
        IrExpr e = Parser.parseExpr("(plus 1 2)", "t.ptf", def);
        IrExpr.BinOp op = (IrExpr.BinOp) e;
        assertEquals(IrExpr.Op.ADD, op.op());
    }

    @Test
    void renamedAtomKeywords_parseCorrectly() throws Exception {
        LanguageDef def = LanguageDef.defaults()
                .withTrueLiteral("yes")
                .withFalseLiteral("no")
                .withSelfReference("it");
        assertInstanceOf(IrExpr.Bool.class, Parser.parseExpr("yes", "t.ptf", def));
        assertEquals(false, ((IrExpr.Bool) Parser.parseExpr("no", "t.ptf", def)).value());
        assertInstanceOf(IrExpr.SelfRef.class, Parser.parseExpr("it", "t.ptf", def));
    }

    // --- End-to-end runtime behavior (rebranded factorial actually executes) lives in
    //     pontif-demo's LanguageDefIntegrationTest, where the symbolic rule fixtures are
    //     available. These tests cover parsing-into-IR only.

    @Test
    void rebrandedKeywords_produceTheExpectedIrShape() throws Exception {
        LanguageDef rebranded = LanguageDef.defaults()
                .withModuleKeyword("program")
                .withFunctionDeclKeyword("fn")
                .withCallKeyword("invoke");

        String src = "(program demo ((fn id ((n Int)) Int n)) (invoke id 5))";
        sibarum.pontif.ir.IrModule m = Parser.parseModule(src, "t.ptf", rebranded);
        assertEquals("demo", m.name());
        assertEquals(1, m.statements().size());
        assertInstanceOf(IrExpr.Call.class, m.main());
        assertEquals("id", ((IrExpr.Call) m.main()).functionName());
    }

    @Test
    void allBinaryOperatorsCanBeRenamedAtOnce_parsesIntoBinOp() throws Exception {
        Map<String, IrExpr.Op> wordOps = new LinkedHashMap<>();
        wordOps.put("plus", IrExpr.Op.ADD);
        wordOps.put("times", IrExpr.Op.MUL);
        LanguageDef def = LanguageDef.defaults().withBinaryOps(wordOps);

        IrExpr e = Parser.parseExpr("(plus (times 2 3) 1)", "t.ptf", def);
        IrExpr.BinOp top = (IrExpr.BinOp) e;
        assertEquals(IrExpr.Op.ADD, top.op());
        assertEquals(IrExpr.Op.MUL, ((IrExpr.BinOp) top.left()).op());
    }

    // --- Default behaviour unchanged when no override is supplied ---

    @Test
    void defaultStaticEntryPoints_stillWork_withoutLanguageDefArg() throws Exception {
        IrExpr e = Parser.parseExpr("(+ 1 2)", "t.ptf");
        assertInstanceOf(IrExpr.BinOp.class, e);
        assertEquals(IrExpr.Op.ADD, ((IrExpr.BinOp) e).op());
    }
}
