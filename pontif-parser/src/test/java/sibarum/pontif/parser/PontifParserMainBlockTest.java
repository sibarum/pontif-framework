package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for the {@code main ( … )} entry block (docs/events.md Slice 0).
 *
 * <p>Executable logic belongs in an explicit, paren-delimited {@code main}
 * block — parens because the body is grouped logic, not an aggregate (the
 * bracket/paren law's block role). During the migration window a bare trailing
 * expression is still accepted as the legacy entry form (coexistence); a file
 * with neither is declarative-only and gets a dormant placeholder main.
 */
class PontifParserMainBlockTest {

    @Test
    void mainBlock_bindsBodyExpressionAsMain() throws Exception {
        IrModule m = PontifParser.parseModule("main ( 1 + 2 )", "t");
        IrExpr.BinOp main = assertInstanceOf(IrExpr.BinOp.class, m.main());
        assertEquals(IrExpr.Op.ADD, main.op());
    }

    @Test
    void mainStatement_parensOptional() throws Exception {
        // `main` takes a bare statement; parens are optional grouping, not required.
        IrModule m = PontifParser.parseModule("main 1 + 2", "t");
        IrExpr.BinOp main = assertInstanceOf(IrExpr.BinOp.class, m.main());
        assertEquals(IrExpr.Op.ADD, main.op());
    }

    @Test
    void mainBlock_sequencesWithLetIn() throws Exception {
        IrModule m = PontifParser.parseModule("main ( let x = 5 x + 1 )", "t");
        IrExpr.LetIn let = assertInstanceOf(IrExpr.LetIn.class, m.main());
        assertEquals("x", let.name());
    }

    @Test
    void mainBlock_coexistsWithDeclarations() throws Exception {
        String src = "function inc(n:Int):Int -> n + 1\nmain ( inc(41) )";
        IrModule m = PontifParser.parseModule(src, "t");
        assertEquals(1, m.statements().size());
        assertInstanceOf(IrExpr.Call.class, m.main());
    }

    @Test
    void legacyTrailingExpression_stillParses_duringMigration() throws Exception {
        IrModule m = PontifParser.parseModule("1 + 2", "t");
        assertInstanceOf(IrExpr.BinOp.class, m.main());
    }

    @Test
    void declarativeOnly_getsPlaceholderMain() throws Exception {
        IrModule m = PontifParser.parseModule("function inc(n:Int):Int -> n + 1", "t");
        IrExpr.Lit placeholder = assertInstanceOf(IrExpr.Lit.class, m.main());
        assertEquals(0L, placeholder.value());
    }

    @Test
    void trailingTokensAfterMainBlock_rejected() {
        assertThrows(ParseException.class,
                () -> PontifParser.parseModule("main ( 1 ) extra", "t"));
    }
}
