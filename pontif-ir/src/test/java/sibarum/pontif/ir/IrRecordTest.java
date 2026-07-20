package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.defaults.DefaultRules;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrRecordTest {

    private static Simplifier simplifier() throws Exception {
        return new Simplifier(DefaultRules.production());
    }

    private static Object runInterpreter(IrModule module) throws Exception {
        Simplifier simp = simplifier();
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    private static Object runTruffle(IrModule module) throws Exception {
        Simplifier simp = simplifier();
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        TruffleProgram program = new TruffleLowering(compiler).lower(compiled);
        return program.run();
    }

    private static Map<String, IrExpr> members(Object... kvPairs) {
        if (kvPairs.length % 2 != 0) {
            throw new IllegalArgumentException("members() requires alternating keys and values");
        }
        Map<String, IrExpr> m = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            m.put((String) kvPairs[i], (IrExpr) kvPairs[i + 1]);
        }
        return m;
    }

    // --- Construction: interpreter ---

    @Test
    void interpreter_buildSimpleRecord_yieldsRecordValueWithMembers() throws Exception {
        IrExpr program = IrExpr.record(members("x", IrExpr.lit(3), "y", IrExpr.lit(4)));
        Object result = runInterpreter(new IrModule("m", List.of(), program));
        assertInstanceOf(RecordValue.class, result);
        RecordValue r = (RecordValue) result;
        assertEquals(3L, r.members().get("x"));
        assertEquals(4L, r.members().get("y"));
    }

    @Test
    void interpreter_emptyRecord_evaluatesToEmptyRecordValue() throws Exception {
        IrExpr program = IrExpr.record(Map.of());
        Object result = runInterpreter(new IrModule("m", List.of(), program));
        assertInstanceOf(RecordValue.class, result);
        assertEquals(0, ((RecordValue) result).members().size());
    }

    @Test
    void interpreter_recordFieldsAreEvaluatedExpressions() throws Exception {
        // { sum: 2 + 3, prod: 4 * 5 }
        IrExpr program = IrExpr.record(members(
                "sum", IrExpr.binOp(IrExpr.Op.ADD, IrExpr.lit(2), IrExpr.lit(3)),
                "prod", IrExpr.binOp(IrExpr.Op.MUL, IrExpr.lit(4), IrExpr.lit(5))));
        RecordValue r = (RecordValue) runInterpreter(new IrModule("m", List.of(), program));
        assertEquals(5L, r.members().get("sum"));
        assertEquals(20L, r.members().get("prod"));
    }

    @Test
    void interpreter_nestedRecord_evaluatesRecursively() throws Exception {
        // { p: {x: 1, y: 2}, label: true }
        IrExpr inner = IrExpr.record(members("x", IrExpr.lit(1), "y", IrExpr.lit(2)));
        IrExpr program = IrExpr.record(members("p", inner, "label", IrExpr.bool(true)));
        RecordValue r = (RecordValue) runInterpreter(new IrModule("m", List.of(), program));
        assertInstanceOf(RecordValue.class, r.members().get("p"));
        assertEquals(true, r.members().get("label"));
        RecordValue p = (RecordValue) r.members().get("p");
        assertEquals(1L, p.members().get("x"));
        assertEquals(2L, p.members().get("y"));
    }

    // --- Field access: interpreter ---

    @Test
    void interpreter_fieldAccessOnLiteralRecord_returnsFieldValue() throws Exception {
        // ({x: 7, y: 9}).x = 7
        IrExpr program = IrExpr.fieldAccess(
                IrExpr.record(members("x", IrExpr.lit(7), "y", IrExpr.lit(9))),
                "x");
        assertEquals(7L, runInterpreter(new IrModule("m", List.of(), program)));
    }

    @Test
    void interpreter_fieldAccessThroughLetBinding_works() throws Exception {
        // let p = {x: 10, y: 20} in p.y  = 20
        IrExpr program = IrExpr.letIn("p",
                IrSort.structural("Point", Map.of(
                        "x", IrSort.named("Int"),
                        "y", IrSort.named("Int"))),
                IrExpr.record(members("x", IrExpr.lit(10), "y", IrExpr.lit(20))),
                IrExpr.fieldAccess(IrExpr.var("p"), "y"));
        assertEquals(20L, runInterpreter(new IrModule("m", List.of(), program)));
    }

    @Test
    void interpreter_nestedFieldAccess_walksMultipleLayers() throws Exception {
        // ({outer: {inner: 42}}).outer.inner = 42
        IrExpr program = IrExpr.fieldAccess(
                IrExpr.fieldAccess(
                        IrExpr.record(members(
                                "outer", IrExpr.record(members("inner", IrExpr.lit(42))))),
                        "outer"),
                "inner");
        assertEquals(42L, runInterpreter(new IrModule("m", List.of(), program)));
    }

    @Test
    void interpreter_missingField_throwsWithAccessOrigin() throws Exception {
        Origin accessSite = Origin.at("test.ptf", 3, 7);
        IrExpr program = new IrExpr.FieldAccess(
                IrExpr.record(members("x", IrExpr.lit(1))),
                "missing",
                accessSite);
        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runInterpreter(new IrModule("m", List.of(), program)));
        assertEquals(accessSite, ex.origin());
        assertTrue(ex.getMessage().contains("test.ptf:3:7"),
                "error should include access origin; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("missing"),
                "error should name the missing field; got: " + ex.getMessage());
    }

    @Test
    void interpreter_fieldAccessOnNonRecord_throwsWithOrigin() throws Exception {
        Origin accessSite = Origin.at("test.ptf", 5, 1);
        IrExpr program = new IrExpr.FieldAccess(IrExpr.lit(5), "x", accessSite);
        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runInterpreter(new IrModule("m", List.of(), program)));
        assertEquals(accessSite, ex.origin());
        assertTrue(ex.getMessage().contains("test.ptf:5:1"),
                "error should include origin; got: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("record"),
                "error should say what was expected; got: " + ex.getMessage());
    }

    // --- Both paths agree ---

    @Test
    void truffle_buildAndAccessRecord_matchesInterpreter() throws Exception {
        IrExpr program = IrExpr.fieldAccess(
                IrExpr.record(members("a", IrExpr.lit(11), "b", IrExpr.lit(22))),
                "b");
        IrModule module = new IrModule("m", List.of(), program);
        assertEquals(22L, runTruffle(module));
        assertEquals(22L, runInterpreter(module));
    }

    @Test
    void truffle_nestedRecordAndFieldAccess_matchesInterpreter() throws Exception {
        IrExpr program = IrExpr.fieldAccess(
                IrExpr.fieldAccess(
                        IrExpr.record(members(
                                "outer", IrExpr.record(members("inner", IrExpr.lit(99))))),
                        "outer"),
                "inner");
        IrModule module = new IrModule("m", List.of(), program);
        assertEquals(99L, runTruffle(module));
        assertEquals(99L, runInterpreter(module));
    }

    @Test
    void truffle_missingField_throwsWithAccessOrigin() throws Exception {
        Origin accessSite = Origin.at("test.ptf", 8, 2);
        IrExpr program = new IrExpr.FieldAccess(
                IrExpr.record(members("x", IrExpr.lit(1))),
                "missing",
                accessSite);
        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runTruffle(new IrModule("m", List.of(), program)));
        assertEquals(accessSite, ex.origin());
        assertTrue(ex.getMessage().contains("test.ptf:8:2"),
                "error should include origin; got: " + ex.getMessage());
    }

    @Test
    void truffle_fieldAccessOnNonRecord_throwsWithOrigin() throws Exception {
        Origin accessSite = Origin.at("test.ptf", 9, 4);
        IrExpr program = new IrExpr.FieldAccess(IrExpr.lit(5), "x", accessSite);
        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runTruffle(new IrModule("m", List.of(), program)));
        assertEquals(accessSite, ex.origin());
        assertTrue(ex.getMessage().contains("test.ptf:9:4"),
                "error should include origin; got: " + ex.getMessage());
    }

    // --- Records flow through let-bindings, lambdas, and match ---

    @Test
    void truffle_recordPassedThroughLambda_andFieldAccessedAfter() throws Exception {
        // let p = {a: 100, b: 1} in (\r -> r.a + r.b)(p) = 101
        IrSort point = IrSort.structural("P", Map.of(
                "a", IrSort.named("Int"), "b", IrSort.named("Int")));
        IrExpr lambda = IrExpr.lambda(
                List.of(new IrParam("r", point)),
                IrSort.named("Int"),
                IrExpr.binOp(IrExpr.Op.ADD,
                        IrExpr.fieldAccess(IrExpr.var("r"), "a"),
                        IrExpr.fieldAccess(IrExpr.var("r"), "b")));
        IrExpr program = IrExpr.letIn("p", point,
                IrExpr.record(members("a", IrExpr.lit(100), "b", IrExpr.lit(1))),
                IrExpr.apply(lambda, List.of(IrExpr.var("p"))));
        assertEquals(101L, runTruffle(new IrModule("m", List.of(), program)));
    }

    @Test
    void truffle_fieldAccessAsMatchScrutinee() throws Exception {
        // let p = {n: -3} in match p.n with | negative -> 42 | _ -> 0
        IrSort negative = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0)));
        IrExpr program = IrExpr.letIn("p",
                IrSort.structural("P", Map.of("n", IrSort.named("Int"))),
                IrExpr.record(members("n", IrExpr.lit(-3))),
                IrExpr.match(IrExpr.fieldAccess(IrExpr.var("p"), "n"), List.of(
                        IrExpr.matchBranch(negative, IrExpr.lit(42)),
                        IrExpr.matchBranch(IrSort.named("Int"), IrExpr.lit(0)))));
        assertEquals(42L, runTruffle(new IrModule("m", List.of(), program)));
    }

    // --- Lifting Record / FieldAccess into refinement predicates ---

    @Test
    void compileSymExpr_record_liftsToSymExprRecord() throws Exception {
        IrExpr ir = IrExpr.record(members("a", IrExpr.lit(1), "b", IrExpr.lit(2)));
        SymExpr sym = IrCompiler.compileSymExpr(ir);
        assertInstanceOf(SymExpr.Record.class, sym);
        SymExpr.Record r = (SymExpr.Record) sym;
        assertEquals(SymExpr.lit(1), r.members().get("a"));
        assertEquals(SymExpr.lit(2), r.members().get("b"));
    }

    @Test
    void compileSymExpr_fieldAccess_liftsToSymExprFieldAccess() throws Exception {
        IrExpr ir = IrExpr.fieldAccess(IrExpr.self(), "count");
        SymExpr sym = IrCompiler.compileSymExpr(ir);
        SymExpr expected = SymExpr.fieldAccess(SymExpr.self(), "count");
        assertEquals(expected, sym);
    }
}
