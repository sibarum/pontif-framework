package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The iteration construct's <em>alt-syntax</em> surface (docs/iteration.md §8),
 * slice 1 — map + filter, over a NATIVE stream (a tuple literal `(1,2,3)`; no
 * Element/Leaf — trees use recursion, streams are native; James 2026-06-15).
 * Parses `iter(src).{…} { match value … }` end-to-end (AltParser →
 * {@link sibarum.pontif.ir.IrExpr.Iterate} → IrInterpreter) and inspects the
 * completed result's streams.
 */
class IterationParseTest {

    private Object run(String src) throws Exception {
        IrModule module = AltParser.parseModule(src, "iter.ptf");
        Simplifier simp = new Simplifier(List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    /** A sealed stream is a positional record; its elements are its member values. */
    private static List<Object> elems(Object stream) {
        return stream instanceof RecordValue rv
                ? new ArrayList<>(rv.members().values())
                : List.of();
    }

    @Test
    void map_doublesEachElement() throws Exception {
        // Pure map: only `value`; the bare-value arm → default stream. Single
        // output ⇒ returned directly.
        Object r = run("""
                iter((1, 2, 3)).{value} {
                  match value
                    [_] -> value * 2
                }
                """);
        assertEquals(List.of(2L, 4L, 6L), elems(r));
    }

    @Test
    void filter_partitionsAcceptAndReject() throws Exception {
        RecordValue r = (RecordValue) run("""
                iter((1, 2, 3)).{value, accept, reject} {
                  match value
                    [@>1] -> accept(value)
                    [_]   -> reject(value)
                }
                """);
        assertEquals(List.of(2L, 3L), elems(r.get("accept", Origin.NONE)));
        assertEquals(List.of(1L), elems(r.get("reject", Origin.NONE)));
    }

    @Test
    void filterAndMap_acceptCarriesATransform() throws Exception {
        RecordValue r = (RecordValue) run("""
                iter((1, 2, 3)).{value, accept, reject} {
                  match value
                    [@>1] -> accept(value * 10)
                    [_]   -> reject(value)
                }
                """);
        assertEquals(List.of(20L, 30L), elems(r.get("accept", Origin.NONE)));
        assertEquals(List.of(1L), elems(r.get("reject", Origin.NONE)));
    }

    @Test
    void filter_boolArmsRouteCurrentValue() throws Exception {
        RecordValue r = (RecordValue) run("""
                iter((0, 1, 2)).{value, accept, reject} {
                  match value
                    [0] -> false
                    [_] -> true
                }
                """);
        assertEquals(List.of(1L, 2L), elems(r.get("accept", Origin.NONE)));
        assertEquals(List.of(0L), elems(r.get("reject", Origin.NONE)));
    }
}
