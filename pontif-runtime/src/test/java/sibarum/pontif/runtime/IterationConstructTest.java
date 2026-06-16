package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The iteration construct (docs/iteration.md), slice 1 — NO surface syntax yet,
 * so the {@link IrExpr.Iterate} node is hand-built here and run on the
 * IrInterpreter path (parse is the deferred REVISIT, §10). Exercises the
 * read/write-stream model: a STREAM output (map / filter) seals to an
 * {@code Element/Leaf} chain; an ACCUMULATOR output (fold) seals to its final
 * revision, reading its prior per frame.
 */
class IterationConstructTest {

    private Object run(IrExpr main) throws Exception {
        IrModule module = new IrModule("itertest", List.<IrStmt>of(), main);
        Simplifier simp = new Simplifier(List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    /** Builds a native stream source — a positional record (tuple) — from literals. */
    private static IrExpr intChain(long... vals) {
        Map<String, IrExpr> m = new LinkedHashMap<>();
        for (int i = 0; i < vals.length; i++) m.put("_" + i, IrExpr.lit(vals[i]));
        return new IrExpr.Record("_tuple", m, Origin.NONE);
    }

    /** A sealed stream is a positional record; its elements are its member values. */
    private static List<Object> heads(Object chain) {
        return chain instanceof RecordValue rv
                ? new ArrayList<>(rv.members().values())
                : new ArrayList<>();
    }

    private static final IrSort ANY_INT = IrSort.named("Int");

    private static IrSort intRefined(IrExpr.Op cmp, long n) {
        return new IrSort.Refined("Int",
                new IrExpr.BinOp(cmp, IrExpr.self(), IrExpr.lit(n), Origin.NONE), Origin.NONE);
    }

    @Test
    void fold_sumsToSix() throws Exception {
        // ACCUMULATOR `sum` init 0; each frame writes sum = sum + e (reads prior).
        IrExpr.Iterate it = new IrExpr.Iterate(
                intChain(1, 2, 3), "e",
                List.of(new IrExpr.OutputSpec("sum", IrExpr.OutputKind.ACCUMULATOR, IrExpr.lit(0))),
                List.of(new IrExpr.Arm(ANY_INT, List.of(new IrExpr.Write("sum", null,
                        new IrExpr.BinOp(IrExpr.Op.ADD, IrExpr.var("sum"), IrExpr.var("e"), Origin.NONE))))),
                Origin.NONE);
        assertEquals(6L, run(it));  // single output → returned directly
    }

    @Test
    void map_doublesEachElement() throws Exception {
        // One STREAM output; each element placed transformed (e * 2).
        IrExpr.Iterate it = new IrExpr.Iterate(
                intChain(1, 2, 3), "e",
                List.of(new IrExpr.OutputSpec("mapped", IrExpr.OutputKind.STREAM, null)),
                List.of(new IrExpr.Arm(ANY_INT, List.of(new IrExpr.Write("mapped", null,
                        new IrExpr.BinOp(IrExpr.Op.MUL, IrExpr.var("e"), IrExpr.lit(2), Origin.NONE))))),
                Origin.NONE);
        assertEquals(List.of(2L, 4L, 6L), heads(run(it)));
    }

    @Test
    void filter_routesToTwoStreams_nothingErased() throws Exception {
        // Two STREAM outputs; each element routed to exactly one (partition).
        IrExpr.Iterate it = new IrExpr.Iterate(
                intChain(1, -2, 3, -4), "e",
                List.of(new IrExpr.OutputSpec("pos", IrExpr.OutputKind.STREAM, null),
                        new IrExpr.OutputSpec("neg", IrExpr.OutputKind.STREAM, null)),
                List.of(
                        new IrExpr.Arm(intRefined(IrExpr.Op.GT, 0),
                                List.of(new IrExpr.Write("pos", null, IrExpr.var("e")))),
                        new IrExpr.Arm(intRefined(IrExpr.Op.LE, 0),
                                List.of(new IrExpr.Write("neg", null, IrExpr.var("e"))))),
                Origin.NONE);
        Object result = run(it);
        RecordValue rec = (RecordValue) result;  // two outputs → a record keyed by name
        assertEquals(List.of(1L, 3L), heads(rec.get("pos", Origin.NONE)));
        assertEquals(List.of(-2L, -4L), heads(rec.get("neg", Origin.NONE)));
    }

    @Test
    void mapAndCount_placementPlusObservation() throws Exception {
        // One STREAM (placement) + one ACCUMULATOR (observation) together.
        IrExpr.Iterate it = new IrExpr.Iterate(
                intChain(10, 20, 30), "e",
                List.of(new IrExpr.OutputSpec("kept", IrExpr.OutputKind.STREAM, null),
                        new IrExpr.OutputSpec("count", IrExpr.OutputKind.ACCUMULATOR, IrExpr.lit(0))),
                List.of(new IrExpr.Arm(ANY_INT, List.of(
                        new IrExpr.Write("kept", null, IrExpr.var("e")),
                        new IrExpr.Write("count", null,
                                new IrExpr.BinOp(IrExpr.Op.ADD, IrExpr.var("count"), IrExpr.lit(1), Origin.NONE))))),
                Origin.NONE);
        RecordValue rec = (RecordValue) run(it);
        assertEquals(List.of(10L, 20L, 30L), heads(rec.get("kept", Origin.NONE)));
        assertEquals(3L, rec.get("count", Origin.NONE));
    }

    // --- Conservation §4 (no silent erase), enforced by SortChecker ----------

    /**
     * A bare drop is not expressible: an arm that writes nothing, with no
     * default stream to fall through to, leaves the element unaccounted — a
     * compile error, never a silent loss (docs/iteration.md §4).
     */
    @Test
    void bareDrop_isRejected() {
        IrExpr it = new IrExpr.Iterate(
                intChain(1, 2, 3), "e",
                List.of(new IrExpr.OutputSpec("kept", IrExpr.OutputKind.STREAM, null)),
                List.of(new IrExpr.Arm(ANY_INT, List.of())),  // no write, no default
                Origin.NONE);
        CompileException ex = assertThrows(CompileException.class, () -> run(it));
        assertTrue(ex.getMessage().contains("accounts for nothing"),
                () -> "expected a bare-drop error, got: " + ex.getMessage());
    }

    /**
     * Placing the same element into two streams is an emission (a creation),
     * not a free copy — rejected in slice 1 (docs/iteration.md §4).
     */
    @Test
    void placingIntoTwoStreams_isRejected() {
        IrExpr it = new IrExpr.Iterate(
                intChain(1, 2, 3), "e",
                List.of(new IrExpr.OutputSpec("accept", IrExpr.OutputKind.STREAM, null),
                        new IrExpr.OutputSpec("reject", IrExpr.OutputKind.STREAM, null)),
                List.of(new IrExpr.Arm(ANY_INT, List.of(
                        new IrExpr.Write("accept", null, IrExpr.var("e")),
                        new IrExpr.Write("reject", null, IrExpr.var("e"))))),
                Origin.NONE);
        CompileException ex = assertThrows(CompileException.class, () -> run(it));
        assertTrue(ex.getMessage().contains("into 2 streams"),
                () -> "expected a duplication error, got: " + ex.getMessage());
    }
}
