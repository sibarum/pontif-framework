package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The IR/AST inspector report: both sections present on success, and precise,
 * stage-named, located failures (parse / compile) with the IR still shown when
 * the parse succeeded.
 */
class IrAstReportTest {

    private static String report(String src) {
        return switch (IrAstReport.fromAltSource(src, "t.ptf")) {
            case IrAstReport.Result.Generated g -> g.text();
            case IrAstReport.Result.Failed f -> "FAILED: " + f.error();
        };
    }

    @Test
    void valid_showsIrTreeThenLoweredAst() {
        String r = report("""
                function factorial(n:[Int:@>0]):[Int:@>=1] -> n * factorial(n-1)
                factorial(5)
                """);
        // IR section: structural tree with kinds + inline sorts.
        assertTrue(r.contains("# IR"), r);
        assertTrue(r.contains("function factorial(n: [Int:(@ > 0)]) : [Int:(@ >= 1)]"), r);
        assertTrue(r.contains("BinOp *"), r);
        assertTrue(r.contains("Call factorial"), r);
        // AST section: lowered Truffle node tree (BinOp * lowers to Mul, Call to CallNode).
        assertTrue(r.contains("# Execution AST"), r);
        assertTrue(r.contains("Mul"), r);
        assertTrue(r.contains("CallNode"), r);
    }

    @Test
    void parseError_locatedAndAstSkipped() {
        String r = report("function f(n:Int):Int -> n +");
        assertTrue(r.contains("Parse failed at t.ptf:"), r);
        assertTrue(r.contains("(not generated — source did not parse)"), r);
    }

    @Test
    void compileError_showsIrButNamesTheFailingStage() {
        String r = report("undefinedFn(3)");
        // Parse succeeded, so the IR is shown...
        assertTrue(r.contains("Call undefinedFn"), r);
        // ...but the AST section names the located compile failure.
        assertTrue(r.contains("(not generated — compile failed at t.ptf:"), r);
        assertTrue(r.contains("Unknown function 'undefinedFn'"), r);
    }

    @Test
    void stringsAndStructs_render() {
        String r = report("""
                struct Tagged(label:String, n:Int)
                Tagged("hi", 1)
                """);
        assertTrue(r.contains("Record Tagged"), r);
        assertTrue(r.contains("Str \"hi\""), r);
        assertTrue(r.contains("RecordNode"), r);   // lowered AST
    }
}
