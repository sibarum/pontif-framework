package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OriginTest {

    private static List<RewriteRule> defaultRules() {
        List<RewriteRule> rules = new ArrayList<>();
        rules.add(cmpLitLit());
        return rules;
    }

    private static RewriteRule cmpLitLit() {
        return (expr, simp) -> {
            if (expr instanceof sibarum.pontif.core.symbolic.SymExpr.Cmp(
                    sibarum.pontif.core.symbolic.SymExpr.Lit l,
                    sibarum.pontif.core.symbolic.SymExpr.CmpOp op,
                    sibarum.pontif.core.symbolic.SymExpr.Lit r)) {
                boolean truth = switch (op) {
                    case LT -> l.value() < r.value();
                    case LE -> l.value() <= r.value();
                    case GT -> l.value() > r.value();
                    case GE -> l.value() >= r.value();
                    case EQ -> l.value() == r.value();
                    case NE -> l.value() != r.value();
                };
                return Optional.of(sibarum.pontif.core.symbolic.SymExpr.bool(truth));
            }
            return Optional.empty();
        };
    }

    private static Simplifier simplifier() throws Exception {
        return new Simplifier(defaultRules());
    }

    // --- Origin construction and formatting ---

    @Test
    void origin_NONE_isNotPresent() throws Exception {
        assertFalse(Origin.NONE.isPresent());
        assertEquals("<unknown>", Origin.NONE.toString());
    }

    @Test
    void origin_at_pointIsCorrectlyFormatted() throws Exception {
        Origin o = Origin.at("counter.ptf", 14, 7);
        assertTrue(o.isPresent());
        assertTrue(o.isPoint());
        assertEquals("counter.ptf:14:7", o.toString());
    }

    @Test
    void origin_span_singleLineRangeFormatsAsRange() throws Exception {
        Origin o = Origin.span("counter.ptf", 14, 7, 14, 23);
        assertEquals("counter.ptf:14:7-23", o.toString());
    }

    @Test
    void origin_span_multiLineRangeFormatsWithBothPositions() throws Exception {
        Origin o = Origin.span("counter.ptf", 14, 7, 18, 3);
        assertEquals("counter.ptf:14:7-18:3", o.toString());
    }

    @Test
    void position_validatesPositiveValues() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Origin.Position(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Origin.Position(1, 0));
    }

    // --- Runtime errors carry origin ---

    @Test
    void runtimeCheckFailure_includesOriginInMessage() throws Exception {
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));

        IrStmt.FunctionDecl powDecl = IrStmt.functionDecl(
                "pow",
                List.of(new IrParam("b", positive)),
                positive,
                IrExpr.var("b"));

        // Build a call with an explicit Origin
        Origin callSite = Origin.at("test.ptf", 5, 10);
        IrExpr badCall = new IrExpr.Call("pow", List.of(IrExpr.lit(-3)), callSite);

        IrModule module = new IrModule("originDemo", List.of(powDecl), badCall);
        CompiledModule compiled = new IrCompiler(simplifier()).compile(module);

        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> new IrInterpreter(simplifier()).eval(compiled));

        assertEquals(callSite, ex.origin());
        assertTrue(ex.getMessage().contains("test.ptf:5:10"),
                "Error message should include origin; got: " + ex.getMessage());
    }

    @Test
    void irRuntimeException_isCaughtByRuntimeCheckExceptionHandler() throws Exception {
        // Existing handlers of RuntimeCheckException must continue to catch IrRuntimeException.
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));

        IrStmt.FunctionDecl powDecl = IrStmt.functionDecl(
                "pow",
                List.of(new IrParam("b", positive)),
                positive,
                IrExpr.var("b"));

        IrExpr badCall = new IrExpr.Call("pow", List.of(IrExpr.lit(-3)), Origin.at("x.ptf", 1, 1));
        IrModule module = new IrModule("compat", List.of(powDecl), badCall);
        CompiledModule compiled = new IrCompiler(simplifier()).compile(module);

        // Backward-compat: catching RuntimeCheckException still works
        assertThrows(RuntimeCheckException.class,
                () -> new IrInterpreter(simplifier()).eval(compiled));
    }

    @Test
    void dispatchFailure_carriesCallSiteOrigin() {
        // Call to a function that doesn't exist — now caught at compile time
        // by SortChecker's unknown-function-name validation. The origin still
        // propagates onto the CompileException so the editor can point at
        // the offending call site.
        Origin callSite = Origin.at("test.ptf", 42, 5);
        IrExpr badCall = new IrExpr.Call("doesNotExist", List.of(IrExpr.lit(5)), callSite);
        IrModule module = new IrModule("missing", List.of(), badCall);

        CompileException ex = assertThrows(
                CompileException.class,
                () -> new IrCompiler(simplifier()).compile(module));

        assertEquals(callSite, ex.origin());
        assertTrue(ex.getMessage().contains("test.ptf:42:5"));
        assertTrue(ex.getMessage().contains("doesNotExist"));
    }

    @Test
    void noOrigin_messagesAreUnformatted() throws Exception {
        // Calls without explicit origin still produce errors, just without origin prefix
        IrSort positive = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));

        IrStmt.FunctionDecl powDecl = IrStmt.functionDecl(
                "pow",
                List.of(new IrParam("b", positive)),
                positive,
                IrExpr.var("b"));

        // call() factory uses Origin.NONE
        IrModule module = new IrModule("noOrigin",
                List.of(powDecl),
                IrExpr.call("pow", List.of(IrExpr.lit(-3))));
        CompiledModule compiled = new IrCompiler(simplifier()).compile(module);

        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> new IrInterpreter(simplifier()).eval(compiled));

        assertFalse(ex.origin().isPresent());
        // Message should NOT have a "[<unknown>]" prefix when origin is absent
        assertFalse(ex.getMessage().startsWith("["),
                "Message should not start with a bracket when origin is unknown; got: " + ex.getMessage());
    }

    // --- Origin survives through compilation ---

    @Test
    void nodeOriginIsPreservedThroughIRConstruction() throws Exception {
        Origin o = Origin.span("x.ptf", 1, 1, 1, 10);
        IrExpr.Lit lit = new IrExpr.Lit(42, o);
        assertEquals(o, lit.origin());
    }

    @Test
    void factoryHelpers_defaultToOriginNONE() throws Exception {
        IrExpr e = IrExpr.lit(42);
        assertEquals(Origin.NONE, e.origin());
    }
}
