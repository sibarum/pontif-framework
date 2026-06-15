package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
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

    /** Builds an {@code Element/Leaf} source chain from integer literals. */
    private static IrExpr intChain(long... vals) {
        IrExpr chain = new IrExpr.Record("Leaf", Map.of(), Origin.NONE);
        for (int i = vals.length - 1; i >= 0; i--) {
            Map<String, IrExpr> m = new LinkedHashMap<>();
            m.put("head", IrExpr.lit(vals[i]));
            m.put("rest", chain);
            chain = new IrExpr.Record("Element", m, Origin.NONE);
        }
        return chain;
    }

    /** Walks a sealed {@code Element/Leaf} chain into its list of head values. */
    private static List<Object> heads(Object chain) {
        List<Object> out = new ArrayList<>();
        while (chain instanceof RecordValue rv && "Element".equals(rv.typeName())) {
            out.add(rv.get("head", Origin.NONE));
            chain = rv.get("rest", Origin.NONE);
        }
        return out;
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
}
