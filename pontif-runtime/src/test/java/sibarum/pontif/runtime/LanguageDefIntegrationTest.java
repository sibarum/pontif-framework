package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.parser.LanguageDef;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * End-to-end runtime coverage for {@link LanguageDef}-driven rebrands: parser
 * tests in pontif-parser confirm the IR shape; this confirms a fully rebranded
 * source string parses, compiles, AND executes through {@link PontifCompiler}
 * + {@link PontifRunner}.
 */
class LanguageDefIntegrationTest {

    private final PontifRunner runner = new PontifRunner();

    @Test
    void rebrandedFactorialModule_compilesAndRunsTo120() throws Exception {
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

        PontifCompiler compiler = new PontifCompiler(rebranded, PontifCompiler.defaultRules());
        RunResult r = runner.run(compiler.compile(src, "rebranded.ptf"), Engine.INTERPRETER);
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("120", r.text());
    }

    @Test
    void allBinaryOperatorsCanBeRenamedAtOnce_andProgramStillRuns() throws Exception {
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

        PontifCompiler compiler = new PontifCompiler(def, PontifCompiler.defaultRules());
        RunResult r = runner.run(compiler.compile(src, "wordy.ptf"), Engine.INTERPRETER);
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("15", r.text());
    }
}
